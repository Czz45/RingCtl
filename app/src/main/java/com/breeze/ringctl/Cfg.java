package com.breeze.ringctl;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置项集中管理。
 */
public class Cfg {

    private static final String FILE = "ring_cfg";

    public static final int MODE_SMOOTH = 0;
    public static final int MODE_WHEEL = 1;
    public static final int MODE_PAGE = 2;
    /** 连续拖拽：手指按住不放，页面连续跟随 */
    public static final int MODE_DRAG = 3;
    /** Root 滚轮：用 root 发真实滚轮事件，需要 root */
    public static final int MODE_ROOT_WHEEL = 4;

    public static final int AXIS_VERTICAL = 0;
    public static final int AXIS_HORIZONTAL = 1;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ==================== 基本 ====================

    public static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", true);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).apply();
    }

    public static boolean reverse(Context c) {
        return sp(c).getBoolean("reverse", false);
    }

    public static void setReverse(Context c, boolean v) {
        sp(c).edit().putBoolean("reverse", v).apply();
    }

    public static int mode(Context c) {
        return sp(c).getInt("mode", MODE_SMOOTH);
    }

    public static void setMode(Context c, int v) {
        sp(c).edit().putInt("mode", v).apply();
    }

    public static int axis(Context c) {
        return sp(c).getInt("axis", AXIS_VERTICAL);
    }

    public static void setAxis(Context c, int v) {
        sp(c).edit().putInt("axis", v).apply();
    }

    // ==================== 触发节奏 ====================

    /**
     * 单次触发冷却（毫秒）。
     * 一次手势发出后，这个时长内的所有转动都会被忽略。
     * 用于实现「多少秒内只触发一次」。
     */
    public static int cooldownMs(Context c) {
        return sp(c).getInt("cooldown_ms", 0);
    }

    public static void setCooldownMs(Context c, int v) {
        sp(c).edit().putInt("cooldown_ms", v).apply();
    }

    /** 最小手势间隔（毫秒），决定连续滚动的最高频率 */
    public static int minIntervalMs(Context c) {
        return sp(c).getInt("min_interval_ms", 90);
    }

    public static void setMinIntervalMs(Context c, int v) {
        sp(c).edit().putInt("min_interval_ms", v).apply();
    }

    /** 手势持续时间（毫秒），越大越柔和 */
    public static int gestureDurationMs(Context c) {
        return sp(c).getInt("gesture_dur_ms", 90);
    }

    public static void setGestureDurationMs(Context c, int v) {
        sp(c).edit().putInt("gesture_dur_ms", v).apply();
    }

    // ==================== 灵敏度 ====================

    public static float sens(Context c) {
        return sp(c).getFloat("sens", 1.0f);
    }

    public static void setSens(Context c, float v) {
        sp(c).edit().putFloat("sens", v).apply();
    }

    public static float wheelUnit(Context c) {
        return sp(c).getFloat("wheel_unit", 100f);
    }

    public static void setWheelUnit(Context c, float v) {
        sp(c).edit().putFloat("wheel_unit", v).apply();
    }

    public static int stepPx(Context c) {
        return sp(c).getInt("step_px", 120);
    }

    public static void setStepPx(Context c, int v) {
        sp(c).edit().putInt("step_px", v).apply();
    }

    public static float deadzone(Context c) {
        return sp(c).getFloat("deadzone", 5f);
    }

    public static void setDeadzone(Context c, float v) {
        sp(c).edit().putFloat("deadzone", v).apply();
    }

    /** 单次手势最大位移（像素），防止一次滑太远 */
    public static int maxSwipePx(Context c) {
        return sp(c).getInt("max_swipe_px", 220);
    }

    public static void setMaxSwipePx(Context c, int v) {
        sp(c).edit().putInt("max_swipe_px", v).apply();
    }

    /**
     * 平滑窗口（毫秒）。
     * 转动产生的位移先累积这么久，再一次性平滑滑出去，
     * 避免每次转动都单独发一个小手势导致一顿一顿。
     * 0 = 不平滑，立即响应。
     */
    public static int smoothWindowMs(Context c) {
        return sp(c).getInt("smooth_window_ms", 80);
    }

    public static void setSmoothWindowMs(Context c, int v) {
        sp(c).edit().putInt("smooth_window_ms", v).apply();
    }
    // ==================== 高级 ====================

    /** 松手后多久内继续响应（毫秒），0 = 立即停止 */
    public static int inertiaMs(Context c) {
        return sp(c).getInt("inertia_ms", 0);
    }

    public static void setInertiaMs(Context c, int v) {
        sp(c).edit().putInt("inertia_ms", v).apply();
    }

    /** 是否在相机等应用前台时自动暂停 */
    public static boolean pauseInCamera(Context c) {
        return sp(c).getBoolean("pause_camera", true);
    }

    public static void setPauseInCamera(Context c, boolean v) {
        sp(c).edit().putBoolean("pause_camera", v).apply();
    }

    /** 震动反馈 */
    public static boolean haptic(Context c) {
        return sp(c).getBoolean("haptic", false);
    }

    public static void setHaptic(Context c, boolean v) {
        sp(c).edit().putBoolean("haptic", v).apply();
    }

    /** 震动强度 1~255 */
    public static int hapticAmp(Context c) {
        return sp(c).getInt("haptic_amp", 180);
    }

    public static void setHapticAmp(Context c, int v) {
        sp(c).edit().putInt("haptic_amp", v).apply();
    }

    /** 震动时长（毫秒） */
    public static int hapticMs(Context c) {
        return sp(c).getInt("haptic_ms", 15);
    }

    public static void setHapticMs(Context c, int v) {
        sp(c).edit().putInt("haptic_ms", v).apply();
    }


    // ==================== 快捷暂停 ====================

    /**
     * 临时暂停截止时间（毫秒时间戳）。
     * 大于当前时间说明处于暂停状态。
     */
    public static long pausedUntil(Context c) {
        return sp(c).getLong("paused_until", 0L);
    }

    public static void setPausedUntil(Context c, long v) {
        sp(c).edit().putLong("paused_until", v).apply();
    }

    public static boolean isPaused(Context c) {
        return pausedUntil(c) > System.currentTimeMillis();
    }

    public static void pauseFor(Context c, int minutes) {
        setPausedUntil(c, System.currentTimeMillis() + minutes * 60_000L);
    }

    public static void resumeNow(Context c) {
        setPausedUntil(c, 0L);
    }
}