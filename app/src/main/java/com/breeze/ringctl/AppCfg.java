package com.breeze.ringctl;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 按应用配置：记录哪些 App 启用 / 禁用对焦环。
 *
 * 优先级：单 App 配置 > 全局开关
 * 未出现在任何一个集合里的 App，跟随全局开关。
 */
public class AppCfg {

    private static final String FILE = "ring_app_cfg";

    private static final String KEY_ENABLED = "pkgs_enabled";
    private static final String KEY_DISABLED = "pkgs_disabled";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static Set<String> enabledPkgs(Context c) {
        Set<String> s = sp(c).getStringSet(KEY_ENABLED, null);
        return s == null ? new HashSet<String>() : new HashSet<>(s);
    }

    public static Set<String> disabledPkgs(Context c) {
        Set<String> s = sp(c).getStringSet(KEY_DISABLED, null);
        return s == null ? new HashSet<String>() : new HashSet<>(s);
    }

    /**
     * 设置某个 App 的状态。
     *
     * @param state 1 = 启用，-1 = 禁用，0 = 清除设置（跟随全局）
     */
    public static void setAppState(Context c, String pkg, int state) {
        Set<String> on = enabledPkgs(c);
        Set<String> off = disabledPkgs(c);
        on.remove(pkg);
        off.remove(pkg);
        if (state == 1) {
            on.add(pkg);
        } else if (state == -1) {
            off.add(pkg);
        }
        sp(c).edit()
                .putStringSet(KEY_ENABLED, on)
                .putStringSet(KEY_DISABLED, off)
                .apply();
    }

    /** 返回 1 = 启用，-1 = 禁用，0 = 跟随全局 */
    public static int getAppState(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return 0;
        }
        if (enabledPkgs(c).contains(pkg)) {
            return 1;
        }
        if (disabledPkgs(c).contains(pkg)) {
            return -1;
        }
        return 0;
    }

    public static void clearAll(Context c) {
        sp(c).edit()
                .putStringSet(KEY_ENABLED, new HashSet<String>())
                .putStringSet(KEY_DISABLED, new HashSet<String>())
                .apply();
    }

    /**
     * 综合判断当前 App 是否应该生效。
     */
    public static boolean shouldWork(Context c, boolean globalEnabled, String pkg) {
        int st = getAppState(c, pkg);
        if (st == 1) {
            return true;
        }
        if (st == -1) {
            return false;
        }
        return globalEnabled;
    }
}