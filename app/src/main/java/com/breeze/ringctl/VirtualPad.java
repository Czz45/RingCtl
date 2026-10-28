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
        angle += deltaDeg;
        if (angle > limit) {
            angle = limit;
        } else if (angle < -limit) {
            angle = -limit;
        }
        lastTurnMs = System.currentTimeMillis();
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
    private static void ensureCenterThread(Context ctx) {
        if (sCenterStarted || ctx == null) return;
        sCenterStarted = true;
        final Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30L);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    if (!Cfg.padAutoCenter(app)) continue;
                    float cur;
                    synchronized (VirtualPad.class) {
                        cur = angle;
                    }
                    if (cur == 0f) continue;
                    // 只在「中间附近」才吸回中心：范围内的慢慢回正，
                    // 范围外（打大方向时）保持不动，不然会被硬拽回来
                    float win = Cfg.padCenterWindow(app);
                    if (Math.abs(cur) > win) continue;
                    if (System.currentTimeMillis() - lastTurnMs < Cfg.padCenterDelayMs(app)) {
                        continue;
                    }
                    // 按「度/秒」换算这一步走多少，线程周期 30ms
                    float step = Cfg.padCenterSpeed(app) * 0.03f;
                    if (step < 1f) step = 1f;
                    synchronized (VirtualPad.class) {
                        angle += cur > 0 ? -step : step;
                        // 贴近中心时直接归零，避免在 1~2 度之间反复抖
                        if (Math.abs(angle) < 2f) angle = 0f;
                    }
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

    private static float shownAngle = 0f;   // 当前已发出的角度
    private static Thread glideThread = null;
    private static volatile boolean glideRunning = false;

    /** 启动（或唤醒）插值线程 */
    private static void startGlide(Context ctx, float maxDeg) {
        if (glideRunning) return;   // 线程已在跑，它会自己追
        glideRunning = true;
        final Context c = ctx;
        final float md = maxDeg;
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    float target;
                    synchronized (VirtualPad.class) {
                        target = angle;
                    }
                    float cur = shownAngle;
                    float diff = target - cur;

                    if (Math.abs(diff) < 0.5f) {
                        // 追平了，退出
                        if (diff != 0f) {
                            shownAngle = target;
                            emitValue(c, target, md);
                        }
                        break;
                    }

                    // 每步走剩余差值的 N%（可调），最少 1 度，避免拖很久
                    float rate = c == null ? 0.35f : Cfg.padSmooth(c);
                    if (rate < 0.1f) rate = 0.1f;
                    if (rate > 0.95f) rate = 0.95f;
                    float step = diff * rate;
                    if (Math.abs(step) < 1f) step = diff > 0 ? 1f : -1f;
                    shownAngle = cur + step;
                    emitValue(c, shownAngle, md);

                    try {
                        Thread.sleep(8);
                    } catch (InterruptedException e) {
                        break;
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
            if (!PadDeviceNative.ensureCreated()) {
                lastFailMs = now;
                return false;
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