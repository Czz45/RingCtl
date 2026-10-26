package com.breeze.ringctl;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Root 滚轮注入：调用系统 input scroll 发送真正的滚轮事件（AXIS_VSCROLL）。
 *
 * 实测结论（小米 17 Ultra / HyperOS）：
 *   input roll 0 -5                          → 只对少数控件生效（trackball 源）
 *   input scroll --axis VSCROLL,-5           → 不生效（缺坐标）
 *   input scroll 540 1200 --axis VSCROLL,-5  → 有效，绝大多数 App 都能滚
 *
 * 必须带屏幕坐标，否则事件不会派发到窗口。
 *
 * 注意：每次要起一个 su 进程，约 50~150ms，必须批量 + 节流。
 */
public class RootWheel {

    private static final String TAG = "RootWheel";

    private static int screenX = 540;
    private static int screenY = 1200;

    public static void setScreenSize(int w, int h) {
        screenX = Math.max(1, w / 2);
        screenY = Math.max(1, h / 2);
    }

    /**
     * 竖直滚动。
     *
     * @param dy 正数 = 向下滚（内容上移 / 往后翻），负数 = 向上滚
     */
    public static boolean roll(float dy) {
        int v = (int) dy;
        if (v == 0) {
            return false;
        }
        if (v > 20) {
            v = 20;
        } else if (v < -20) {
            v = -20;
        }
        return exec("input scroll " + screenX + " " + screenY
                + " --axis VSCROLL," + v);
    }

    /** 水平滚动 */
    public static boolean rollX(float dx) {
        int v = (int) dx;
        if (v == 0) {
            return false;
        }
        if (v > 20) {
            v = 20;
        } else if (v < -20) {
            v = -20;
        }
        return exec("input scroll " + screenX + " " + screenY
                + " --axis HSCROLL," + v);
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

    /** 检测 input scroll 是否可用 */
    public static boolean isAvailable() {
        Process p = null;
        BufferedReader br = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "input 2>&1"});
            br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("scroll")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Exception ignored) {
            }
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