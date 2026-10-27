package com.breeze.ringctl;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

/**
 * 按应用配置。
 *
 * 启用/禁用：单 App 独立开关，打开即强生效（不受全局开关影响）。
 * 参数覆盖：每个 App 可单独覆盖任一参数，未覆盖的走全局。
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

    // ==================== 参数覆盖 ====================
    //
    // 每个参数一个 key：<pkg>?<name>
    // 存在 = 该 App 使用自己的值；不存在 = 跟随全局。
    // 用字符串存，浮点也转成字符串，便于统一清理。

    private static String k(String pkg, String name) {
        return "ov:" + pkg + "?" + name;
    }

    public static boolean hasOverride(Context c, String pkg, String name) {
        return sp(c).contains(k(pkg, name));
    }

    public static void clearOverride(Context c, String pkg, String name) {
        sp(c).edit().remove(k(pkg, name)).apply();
    }

    public static void clearAllOverrides(Context c, String pkg) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String key : sp(c).getAll().keySet()) {
            if (key.startsWith("ov:" + pkg + "?")) {
                e.remove(key);
            }
        }
        e.apply();
    }

    /** 该 App 是否有任何覆盖 */
    public static boolean hasAnyOverride(Context c, String pkg) {
        for (String key : sp(c).getAll().keySet()) {
            if (key.startsWith("ov:" + pkg + "?")) {
                return true;
            }
        }
        return false;
    }

    // ---------- 整数 ----------

    public static int getInt(Context c, String pkg, String name, int def) {
        String v = sp(c).getString(k(pkg, name), null);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return def;
        }
    }

    public static void setInt(Context c, String pkg, String name, int v) {
        sp(c).edit().putString(k(pkg, name), String.valueOf(v)).apply();
    }

    /** 整数：有覆盖就返回覆盖值，否则返回全局值 */
    public static int intOf(Context c, String pkg, String name, int global) {
        return getInt(c, pkg, name, global);
    }

    // ---------- 浮点 ----------

    public static float getFloat(Context c, String pkg, String name, float def) {
        String v = sp(c).getString(k(pkg, name), null);
        if (v == null) {
            return def;
        }
        try {
            return Float.parseFloat(v);
        } catch (Exception e) {
            return def;
        }
    }

    public static void setFloat(Context c, String pkg, String name, float v) {
        sp(c).edit().putString(k(pkg, name), String.valueOf(v)).apply();
    }

    public static float floatOf(Context c, String pkg, String name, float global) {
        return getFloat(c, pkg, name, global);
    }

    // ---------- 布尔 ----------

    public static boolean getBool(Context c, String pkg, String name, boolean def) {
        String v = sp(c).getString(k(pkg, name), null);
        if (v == null) {
            return def;
        }
        return "1".equals(v);
    }

    public static void setBool(Context c, String pkg, String name, boolean v) {
        sp(c).edit().putString(k(pkg, name), v ? "1" : "0").apply();
    }

    public static boolean boolOf(Context c, String pkg, String name, boolean global) {
        return getBool(c, pkg, name, global);
    }
}
