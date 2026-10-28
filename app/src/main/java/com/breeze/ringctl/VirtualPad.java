package com.breeze.ringctl;

import android.content.Context;
import android.util.Log;

/**
 * 方向盘输出。
 *
 * ============================================================
 * 输出通道优先级：
 *
 * 【native 模式】（LSP 模式，推荐，延迟 < 5ms）
 *   走 libringpad.so，在 system_server 进程内常驻持有
 *   /dev/uinput 的 fd，创建一次虚拟手柄，之后只写 ABS_X。
 *   延迟 ≈ 单次 write syscall。
 *
 * 【广播模式】（兜底，延迟 ≈ 17ms）
 *   发广播给 RingHook 模块，由它 injectInputEvent。
 *   问题：MotionEvent 只能表达方向，摇杆数值不准。
 *
 * ============================================================
 * 角度模型：
 *   变焦环输出增量 → 累计角度 → 归一化到 -32767..32767
 */
public class VirtualPad {

    private static final String TAG = "VirtualPad";

    /** 和 RingHook 约定的广播 action */
    public static final String ACTION_PAD = "com.breeze.ringhook.PAD";
    /** 广播目标包（system_server） */
    public static final String TARGET_PKG = "android";

    /** 累计角度（度），正数向右 */
    private static float angle = 0f;
    /** 上次发出的轴值，用于去重 */
    private static int lastSent = Integer.MIN_VALUE;
    /** 上一次创建/发送失败的时间，避免疯狂重试 */
    private static long lastFailMs = 0L;
    private static final long FAIL_BACKOFF_MS = 1500L;

    private VirtualPad() {
    }

    public static void init(Context ctx) {
        if (PadDeviceNative.load()) {
            Log.i(TAG, "native pad available: " + PadDeviceNative.statusText());
        } else {
            Log.w(TAG, "native pad not available");
        }
    }

    public static void reset() {
        synchronized (VirtualPad.class) {
            angle = 0f;
            shownAngle = 0f;
            lastSent = Integer.MIN_VALUE;
            lastTurnMs = System.currentTimeMillis();
        }
        // 光改内部变量不够，系统里的摇杆还停在上次的值，必须真发一次 0
        try {
            if (PadDeviceNative.nativeIsReadySafe()) {
                PadDeviceNative.move(0, 0);
                lastSent = 0;
            }
        } catch (Throwable ignored) {
        }
    }

    public static float getAngle() {
        return angle;
    }

    /**
     * 当前实际发出去的角度（插值后的中间值）。
     * UI 显示用这个，比目标值更贴近真实输出，转动过程不会一跳一跳。
     */
    public static float getShownAngle() {
        return shownAngle;
    }

    /**
     * 累加真实转角并输出。
     *
     * 角度不再被 maxDeg 砍断：maxDeg 只是「摇杆打满」的刻度，
     * 真实盘面可以继续往同方向转，最多到 padLimitDeg（默认 ±1080，即三圈）。
     * 摇杆值在换算时单独 clamp，所以超出的部分只是不再增加推力，不会卡住角度。
     *
     * @param deltaDeg 本次转动角度增量
     * @param maxDeg   摇杆打满对应的角度
     */
    public static synchronized boolean turn(Context ctx, float deltaDeg, float maxDeg) {
        if (maxDeg <= 1f) maxDeg = 540f;
        // 每次转动都确保设备在（重开机 / 切 App 后可能没建）
        ensureReady(ctx);
        float limit = ctx == null
                ? 1080f
                : Math.max(maxDeg, Cfg.padLimitTurns(ctx) * 360f);
        float before = angle;
        angle += deltaDeg;
        if (angle > limit) {
            angle = limit;
        } else if (angle < -limit) {
            angle = -limit;
        }
        // 只有「真的在转」才算一次输入：手搭在环上的细微噪声
        // 不该反复重置归中计时，否则归中永远等不到开始
        if (Math.abs(deltaDeg) >= 1f) {
            lastTurnMs = System.currentTimeMillis();
            sCenterBuzzed = false;
        }
        // 触边震动：只有「从内部撞到边界」才震，一直顶着不会反复响
        if (ctx != null
                && Math.abs(angle) >= limit - 0.01f
                && Math.abs(before) < limit - 0.01f
                && Cfg.padHapticEdge(ctx)) {
            haptic(ctx, true);
        }
        // 穿过中心点：从一边转到另一边，经过 0 时轻震，方便盲操作找中位。
        // 要求起点离中心有点距离，否则在 0 附近的小抖动会一直响。
        if (ctx != null
                && Math.abs(before) > 0.5f
                && ((before > 0f && angle <= 0f) || (before < 0f && angle >= 0f))
                && Cfg.padHapticZero(ctx)) {
            haptic(ctx, false);
        }
        // 目标值变化了，启动插值追赶
        startGlide(ctx, maxDeg);
        // 首次转动时把自动回正的守护线程拉起来（未开启则空转，开销可忽略）
        ensureCenterThread(ctx);
        return true;
    }

    /**
     * 自动回正守护线程。
     *
     * 松开变焦环 N 毫秒后，把盘缓慢拉回中位，
     * 适合「转一下就打方向、松手自动回正」的用法。
     */
    /**
     * 归中看门狗。
     *
     * 只负责「到点了就把插值线程叫起来」，改角度的事交给 PadGlide 做。
     * 之前是这里每 30ms 改一次目标值，插值线程同时在追，
     * 两个节奏错开就会在 0 附近来回跳 —— 用户看到的抽搐就是这么来的。
     */
    private static void ensureCenterThread(Context ctx) {
        if (sCenterStarted || ctx == null) return;
        sCenterStarted = true;
        final Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(40L);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    // 插值线程还在跑就不用管，它自己会处理归中
                    if (glideRunning) continue;
                    if (!centerActive(app)) continue;
                    startGlide(app, Cfg.padTurnsToFull(app) * 360f);
                } catch (Throwable ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.setName("PadCenter");
        t.start();
    }

    // ==================== 插值（消除跳跃感） ====================
    //
    // 问题：传感器一帧就跳 ±70~160，直接映射成摇杆值就是「跳一大格」。
    // 做法：不直接发目标值，而是每 8ms 走一小步，在 ~60ms 内追平。
    // 这样一帧的跳跃被摊成 7~8 个中间值，看起来是平滑滑动。

    private static volatile float shownAngle = 0f;   // 当前已发出的角度
    private static Thread glideThread = null;
    private static volatile boolean glideRunning = false;

    /**
     * 插值线程。
     *
     * 关键改动：用「时间常数 + 指数逼近」代替「每 tick 走固定百分比」。
     *
     * 固定百分比和 tick 频率强耦合：8ms 走 35%，换成 1ms 还是走 35%，
     * 同样的目标就会快 8 倍，1000Hz 直接冲过头并且来回震荡。
     *
     * 指数模型：
     *   tau   = 8 / -ln(1 - 跟随速度)      // 与频率无关的时间常数
     *   alpha = 1 - exp(-dt / tau)
     *   shown += (target - shown) * alpha
     *
     * 这样 125Hz 和 1000Hz 走的是同一条曲线，只是采样点更密。
     */
    private static void startGlide(Context ctx, float maxDeg) {
        if (glideRunning) return;   // 线程已在跑，它会自己追
        glideRunning = true;
        final Context c = ctx;
        final float md = maxDeg;
        Thread t = new Thread(() -> {
            long prev = System.nanoTime();
            long next = prev;
            try {
                while (true) {
                    float hz = c == null ? 250f : Cfg.padHz(c);
                    if (hz < 60f) hz = 60f;
                    if (hz > 1000f) hz = 1000f;
                    long periodNs = (long) (1_000_000_000L / hz);

                    long now = System.nanoTime();
                    // 用真实 dt 算，sleep 抖动不会改变运动曲线
                    double dtMs = (now - prev) / 1_000_000.0;
                    prev = now;
                    if (dtMs < 0.2) dtMs = 0.2;

                    float target;
                    synchronized (VirtualPad.class) {
                        target = angle;
                    }

                    // ===== 归中 =====
                    // 放在插值循环里做，和输出同频。
                    // 之前是独立线程每 30ms 改一次目标值，插值线程同时在追，
                    // 两个节奏错开就会在 0 附近来回跳（用户看到的"抽搐"）。
                    boolean centering = centerActive(c);
                    if (centering) {
                        float delta = Cfg.padCenterSpeed(c) * (float) dtMs / 1000f;
                        if (delta < 0.005f) delta = 0.005f;
                        float nt = target > 0f ? target - delta : target + delta;
                        if (target > 0f ? nt <= 0f : nt >= 0f) {
                            nt = 0f;
                        }
                        synchronized (VirtualPad.class) {
                            angle = nt;
                        }
                        target = nt;
                        if (nt == 0f && !sCenterBuzzed) {
                            sCenterBuzzed = true;
                            if (Cfg.padHapticCenter(c)) {
                                haptic(c, false);
                            }
                        }
                    }

                    float cur = shownAngle;
                    float diff = target - cur;

                    if (Math.abs(diff) < 0.2f) {
                        // 追平了
                        if (diff != 0f) {
                            shownAngle = target;
                            emitValue(c, target, md);
                        }
                        // 还在归中的话不能退出，否则归中会停在半路
                        if (!centering) {
                            break;
                        }
                    }

                    if (dtMs > 50) dtMs = 50;   // 卡顿时别一步跳太远

                    float s = c == null ? 0.35f : Cfg.padSmooth(c);
                    if (s < 0.1f) s = 0.1f;
                    if (s > 0.9f) s = 0.9f;
                    double tau = 8.0 / -Math.log(1.0 - s);
                    double alpha = 1.0 - Math.exp(-dtMs / tau);

                    float step = (float) (diff * alpha);
                    // 差得极小时线性收尾，避免指数逼近的长尾
                    if (Math.abs(step) < 0.05f) step = diff * 0.5f;
                    shownAngle = cur + step;
                    emitValue(c, shownAngle, md);

                    // 精确节拍：睡到下一个周期，最后不足 1ms 用自旋补
                    next += periodNs;
                    long sleepNs = next - System.nanoTime();
                    if (sleepNs > 1_000_000L) {
                        long ms = sleepNs / 1_000_000L - 1;
                        int ns = (int) (sleepNs % 1_000_000L);
                        if (ms < 0) ms = 0;
                        try {
                            Thread.sleep(ms, ns);
                        } catch (InterruptedException e) {
                            break;
                        }
                    } else if (sleepNs > 0) {
                        while (System.nanoTime() < next) {
                            Thread.yield();
                        }
                    } else {
                        // 落后了，重置节拍，防止越追越慢
                        next = System.nanoTime();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                synchronized (VirtualPad.class) {
                    glideRunning = false;
                }
            }
        });
        t.setDaemon(true);
        t.setName("PadGlide");
        // 比普通线程高一点，但别抢 UI
        t.setPriority(Thread.NORM_PRIORITY + 2);
        t.start();
    }

    private static volatile boolean sEnsuring = false;
    private static long sLastEnsureMs = 0L;
    private static final long ENSURE_INTERVAL_MS = 2000L;

    /**
     * 确保虚拟手柄已创建。
     *
     * 之前依赖 UI 上点「重试」或切模式时预热，导致：
     *   - App 重启后已处于方向盘模式，设备不会自动建
     *   - 每个 App 单独设成方向盘模式时，也不会自动建
     * 现在改成在每次转动时检查并异步创建。
     */
    public static void ensureReady(Context ctx) {
        if (PadDeviceNative.nativeIsReadySafe()) return;
        long now = System.currentTimeMillis();
        if (sEnsuring) return;
        if (now - sLastEnsureMs < ENSURE_INTERVAL_MS) return;
        sEnsuring = true;
        sLastEnsureMs = now;
        Thread t = new Thread(() -> {
            try {
                PadDeviceNative.ensureCreated();
            } catch (Throwable ignored) {
            } finally {
                sEnsuring = false;
            }
        });
        t.setDaemon(true);
        t.setName("PadEnsure");
        t.start();
    }

    /** 上次转动的时间，自动回正用 */
    private static long lastTurnMs = 0L;
    private static volatile boolean sCenterStarted = false;
    /** 本轮归中是否已经震过（到位只震一次，重新转动后复位） */
    private static volatile boolean sCenterBuzzed = false;
    private static long sLastHapticMs = 0L;

    /** 极短震动，用于触边 / 归中到位的触觉反馈 */
    private static void haptic(Context ctx, boolean strong) {
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        if (now - sLastHapticMs < 90L) return;
        sLastHapticMs = now;
        try {
            android.os.Vibrator v = (android.os.Vibrator)
                    ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            int ms = strong ? 35 : 16;
            int amp = strong ? 200 : 120;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(ms, amp));
            } else {
                v.vibrate(ms);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 当前是否应该处在「归中」状态：开了开关、落在范围内、且松手够久 */
    private static boolean centerActive(Context c) {
        if (c == null || !Cfg.padAutoCenter(c)) return false;
        float cur;
        synchronized (VirtualPad.class) {
            cur = angle;
        }
        if (cur == 0f) return false;
        if (Math.abs(cur) > Cfg.padCenterWindow(c)) return false;
        return System.currentTimeMillis() - lastTurnMs >= Cfg.padCenterDelayMs(c);
    }

    /**
     * 真实角度 → 摇杆值。
     *
     * 这里做三件事，都是之前缺的：
     *   1. 用 maxDeg 归一化后单独 clamp，超出的角度不会顶死盘面
     *   2. 按用户选的曲线重映射，柔和档让中位更细腻，激进档让中位更灵敏
     *   3. 只在这里 clamp，调用方不需要关心 32000 的边界
     */
    private static int toAxis(Context ctx, float ang, float maxDeg) {
        if (maxDeg <= 1f) maxDeg = 540f;
        float u = ang / maxDeg;
        if (u > 1f) {
            u = 1f;
        } else if (u < -1f) {
            u = -1f;
        }
        int curve = ctx == null ? 0 : Cfg.padCurve(ctx);
        if (u != 0f) {
            float a = Math.abs(u);
            if (curve == 1) {
                a = (float) Math.pow(a, 1.6);       // 柔和：小角度更细
            } else if (curve == 2) {
                a = (float) Math.pow(a, 0.6);       // 激进：小角度更冲
            }
            u = u > 0 ? a : -a;
        }
        int v = Math.round(u * 32000f);
        if (v > 32000) {
            v = 32000;
        } else if (v < -32000) {
            v = -32000;
        }
        return v;
    }

    /** 把某个角度值发出去 */
    private static void emitValue(Context ctx, float ang, float maxDeg) {
        int v = toAxis(ctx, ang, maxDeg);
        if (v == lastSent) return;
        if (sendNative(v)) {
            lastSent = v;
            return;
        }
        sendBroadcast(ctx, v);
        lastSent = v;
    }

    /** 输出当前角度 */
    public static synchronized boolean push(Context ctx, float maxDeg) {
        if (maxDeg <= 1f) maxDeg = 540f;
        int v = toAxis(ctx, angle, maxDeg);

        // 数值没变就不用重复下发
        if (v == lastSent) {
            return true;
        }

        // 优先走 native（LSP 模式）
        if (sendNative(v)) {
            lastSent = v;
            return true;
        }

        // 兜底广播
        sendBroadcast(ctx, v);
        lastSent = v;
        return true;
    }

    /** 走 native 虚拟手柄，失败返回 false */
    private static boolean sendNative(int axisX) {
        long now = System.currentTimeMillis();
        if (now - lastFailMs < FAIL_BACKOFF_MS && !PadDeviceNative.nativeIsReadySafe()) {
            return false;
        }
        try {
            // 高频输出时不要把 ensureCreated 放在热路径上：
            // 它每次都进 synchronized + JNI，1000Hz 下纯属浪费。
            // 已就绪就直接写，没就绪才走创建流程。
            if (!PadDeviceNative.nativeIsReadySafe()) {
                if (!PadDeviceNative.ensureCreated()) {
                    lastFailMs = now;
                    return false;
                }
            }
            if (!PadDeviceNative.move(axisX, 0)) {
                lastFailMs = now;
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendNative failed: " + t);
            lastFailMs = now;
            return false;
        }
    }

    /** 发广播给 RingHook */
    private static void sendBroadcast(Context ctx, int axisX) {
        if (ctx == null) return;
        try {
            android.content.Intent i = new android.content.Intent(ACTION_PAD);
            i.setPackage(TARGET_PKG);
            i.putExtra("x", axisX);
            i.putExtra("y", 0);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            Log.w(TAG, "sendBroadcast failed: " + t);
        }
    }

    /** 兼容旧调用 */
    public static void stop() {
        angle = 0f;
        lastSent = Integer.MIN_VALUE;
        try {
            PadDeviceNative.reset();
        } catch (Throwable ignored) {
        }
    }

    /** UI 显示用的状态 */
    public static String statusText() {
        return PadDeviceNative.statusText();
    }
}