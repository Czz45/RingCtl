package com.breeze.ringctl;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Root 滚轮注入：调用系统 input 命令发送 trackball 滚动事件。
 *
 * 本 ROM 的 input roll 走 trackball 源，列表控件会正常响应并自带惯性，
 * 比注入 AXIS_VSCROLL 更有效。
 *
 * 注意：每次执行要起一个 su 进程，约 50~150ms，必须批量 + 节流。
 */
public class RootWheel {

    private static final String TAG = "RootWheel";

    /** dy > 0 = 向下滚（内容上移），dy < 0 = 向上滚 */
    public static boolean roll(float dy) {
        int v = (int) dy;
        if (v == 0) {
            return false;
        }
        if (v > 30) {
            v = 30;
        } else if (v < -30) {
            v = -30;
        }
        return exec("input roll 0 " + v);
    }

    /** 水平滚动 */
    public static boolean rollX(float dx) {
        int v = (int) dx;
        if (v == 0) {
            return false;
        }
        if (v > 30) {
            v = 30;
        } else if (v < -30) {
            v = -30;
        }
        return exec("input roll " + v + " 0");
    }

    private static boolean exec(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            Log.w(TAG, "exec failed: " + e.getMessage());
            return false;
        } finally {
            try {
                if (p != null) {
                    p.destroy();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 检测 input roll 是否可用 */
    public static boolean isAvailable() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "input 2>&1"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("roll")) {
                    br.close();
                    return true;
                }
            }
            br.close();
        } catch (Exception ignored) {
        } finally {
            try {
                if (p != null) {
                    p.destroy();
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}