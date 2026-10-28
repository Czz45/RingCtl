package com.breeze.ringctl;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 虚拟手柄的 Java 包装层。
 *
 * 底层是 native 的 uinput 实现（libringpad.so），
 * 在进程内常驻，避免每次重建设备。
 *
 * 权限问题的处理：
 *   App 是普通用户（u0_axxx），/dev/uinput 属组是 system，App 不在组里，打不开。
 *   所以每次创建设备前，先用 root 把 /dev/uinput 改成 666，
 *   之后 App 直接 open 即可。fd 由本进程持有，延迟 < 1ms。
 */
public final class PadDeviceNative {

    private static final String TAG = "PadDeviceNative";

    private static boolean sLoaded = false;
    private static boolean sLoadTried = false;
    private static String sLastError = "";

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
            sLastError = "库加载失败: " + t.getMessage();
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

    public static String getLastError() {
        if (sLastError == null || sLastError.isEmpty()) return "无";
        return sLastError;
    }

    // ==================== native ====================

    /** 用外部打开的 fd 初始化，返回 0 成功 */
    public static native int nativeInitWithFd(int fd, String name);

    /** 移动左摇杆，范围 -32767 ~ 32767 */
    public static native int nativeMove(int x, int y);

    public static native void nativeDestroy();

    public static native boolean nativeIsReady();

    /** 探测当前进程能否直接打开 /dev/uinput */
    public static native boolean nativeCanOpen();


    // ==================== 拆分的本机 open ====================

    /**
     * 在 native 里打开 /dev/uinput 并直接初始化。
     * 需要当前进程对该设备有写权限（先由 chmod 解决）。
     */
    public static native int nativeOpenAndInit(String name);

    // ==================== 便捷封装 ====================

    /** 用 root 把 /dev/uinput 权限改成 666，让本进程能打开 */
    public static boolean fixPermissionByRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 666 /dev/uinput"});
            int code = p.waitFor();
            Log.i(TAG, "chmod 666 /dev/uinput exit=" + code);
            return code == 0;
        } catch (Throwable t) {
            Log.w(TAG, "chmod failed: " + t);
            return false;
        }
    }

    /**
     * 确保手柄已创建。返回 true 表示可用。
     */
    public static synchronized boolean ensureCreated() {
        if (!load()) return false;
        if (nativeIsReady()) return true;

        // 1. 先试试当前能不能打开
        boolean canOpen = false;
        try {
            canOpen = nativeCanOpen();
        } catch (Throwable ignored) {
        }

        // 2. 打不开就用 root 改权限
        if (!canOpen) {
            if (!fixPermissionByRoot()) {
                sLastError = "root 提权失败";
                Log.e(TAG, sLastError);
                return false;
            }
            try {
                canOpen = nativeCanOpen();
            } catch (Throwable ignored) {
            }
        }

        if (!canOpen) {
            sLastError = "/dev/uinput 仍无权限（SELinux 可能拦截）";
            Log.e(TAG, sLastError);
            return false;
        }

        // 3. 打开并初始化
        int r;
        try {
            r = nativeOpenAndInit("RingCtlPad");
        } catch (Throwable t) {
            sLastError = "openAndInit 异常: " + t.getMessage();
            Log.e(TAG, sLastError);
            return false;
        }
        if (r != 0) {
            sLastError = "创建失败 code=" + r;
            Log.e(TAG, sLastError);
            return false;
        }
        sLastError = "";
        return nativeIsReady();
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
            if (nativeIsReady()) return "手柄已创建";
            boolean open = nativeCanOpen();
            if (open) return "/dev/uinput 可访问，未创建";
            return "/dev/uinput 无权限（转环时自动提权）";
        } catch (Throwable t) {
            return "异常: " + t.getMessage();
        }
    }
}