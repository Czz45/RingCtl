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
        }
    }

    public static float getAngle() {
        return angle;
    }

    /**
     * 累加角度并输出。
     *
     * @param deltaDeg 本次转动角度增量
     * @param maxDeg   最大转角
     */
    public static synchronized boolean turn(Context ctx, float deltaDeg, float maxDeg) {
        if (maxDeg <= 1f) maxDeg = 540f;
        angle += deltaDeg;
        if (angle > maxDeg) {
            angle = maxDeg;
        } else if (angle < -maxDeg) {
            angle = -maxDeg;
        }
        // 目标值变化了，启动插值追赶
        startGlide(ctx, maxDeg);
        return true;
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

                    // 每步走剩余差值的 35%，最少 1 度，避免拖很久
                    float step = diff * 0.35f;
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

    /** 把某个角度值发出去 */
    private static void emitValue(Context ctx, float ang, float maxDeg) {
        if (maxDeg <= 1f) maxDeg = 540f;
        int v = Math.round(ang / maxDeg * 32000f);
        if (v > 32000) {
            v = 32000;
        } else if (v < -32000) {
            v = -32000;
        }
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
        if (maxDeg <= 1f) maxDeg = 450f;

        int v = Math.round(angle / maxDeg * 32000f);
        if (v > 32000) {
            v = 32000;
        } else if (v < -32000) {
            v = -32000;
        }

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