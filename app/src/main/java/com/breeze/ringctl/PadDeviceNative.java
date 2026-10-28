package com.breeze.ringctl;

import android.util.Log;

/**
 * 虚拟手柄的 Java 包装层。
 *
 * 底层是 native 的 uinput 实现（libringpad.so），
 * 在 system_server 进程内常驻，避免每次重建设备。
 */
public final class PadDeviceNative {

    private static final String TAG = "PadDeviceNative";

    private static boolean sLoaded = false;
    private static boolean sLoadTried = false;

    private PadDeviceNative() {
    }

    /** 加载 native 库，只尝试一次 */
    public static synchronized boolean load() {
        if (sLoadTried) return sLoaded;
        sLoadTried = true;
        try {
            System.loadLibrary("ringpad");
            sLoaded = true;
            Log.i(TAG, "libringpad loaded");
        } catch (Throwable t) {
            Log.e(TAG, "load libringpad failed: " + t);
            sLoaded = false;
        }
        return sLoaded;
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    /** 安全版 nativeIsReady，库没加载时返回 false，不抛异常 */
    public static boolean nativeIsReadySafe() {
        if (!sLoaded) return false;
        try {
            return nativeIsReady();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 创建虚拟手柄，返回 0 成功 */
    public static native int nativeCreate(String name);

    /** 移动左摇杆，范围 -32767 ~ 32767 */
    public static native int nativeMove(int x, int y);

    public static native void nativeDestroy();

    public static native boolean nativeIsReady();

    /** 探测 /dev/uinput 是否可打开（不做创建设备） */
    public static native boolean nativeCanOpen();


    // ==================== 便捷封装 ====================

    /**
     * 确保手柄已创建。返回 true 表示可用。
     */
    public static boolean ensureCreated() {
        if (!load()) return false;
        if (nativeIsReady()) return true;
        int r = nativeCreate("RingCtlPad");
        if (r != 0) {
            Log.e(TAG, "create failed: " + r);
            return false;
        }
        return nativeIsReady();
    }

    /**
     * 设置左摇杆 X 值（-32767 ~ 32767）。
     * 内部只写变化的轴，并且只在需要时 SYN。
     */
    public static boolean setX(int x) {
        if (!load()) return false;
        if (!nativeIsReady()) return false;
        return nativeMove(x, 0) == 0;
    }

    /**
     * 设置左摇杆 X 和 Y。
     */
    public static boolean move(int x, int y) {
        if (!load()) return false;
        if (!nativeIsReady()) return false;
        return nativeMove(x, y) == 0;
    }

    public static void release() {
        if (!sLoaded) return;
        try {
            nativeDestroy();
        } catch (Throwable ignored) {
        }
    }

    /** 复位到中心并销毁 */
    public static void reset() {
        if (!sLoaded) return;
        try {
            if (nativeIsReady()) {
                nativeMove(0, 0);
                nativeDestroy();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 状态文本，用于 UI 显示 */
    public static String statusText() {
        if (!sLoadTried) return "未加载";
        if (!sLoaded) return "库加载失败";
        try {
            boolean open = nativeCanOpen();
            boolean ready = nativeIsReady();
            if (ready) return "手柄已创建";
            if (open) return "/dev/uinput 可访问，未创建";
            return "/dev/uinput 无权限";
        } catch (Throwable t) {
            return "异常: " + t.getMessage();
        }
    }
}