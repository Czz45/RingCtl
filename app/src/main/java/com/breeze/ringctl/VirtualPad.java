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
        angle = 0f;
        lastSent = Integer.MIN_VALUE;
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
        if (maxDeg <= 1f) maxDeg = 450f;
        angle += deltaDeg;
        if (angle > maxDeg) {
            angle = maxDeg;
        } else if (angle < -maxDeg) {
            angle = -maxDeg;
        }
        return push(ctx, maxDeg);
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