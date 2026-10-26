package com.breeze.ringctl;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Root 辅助：一键开关无障碍服务，免去手动跳转系统设置。
 */
public class RootHelper {

    private static Boolean cachedRoot = null;

    /** 检测是否有 root（异步结果缓存） */
    public static boolean hasRoot() {
        if (cachedRoot != null) {
            return cachedRoot;
        }
        boolean ok = false;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("uid=0")) {
                    ok = true;
                    break;
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
        cachedRoot = ok;
        return ok;
    }

    /** 执行一条 root 命令，返回是否成功 */
    public static boolean exec(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
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

    /**
     * 一键开启本应用的无障碍服务。
     * 先置 0 再写服务列表再置 1，强制系统重新读取绑定。
     */
    public static boolean enableAccessibility(Context ctx) {
        String svc = ctx.getPackageName() + "/" + RingAccessibilityService.class.getName();
        String cmd = "settings put secure enabled_accessibility_services '" + svc + "'; "
                + "settings put secure accessibility_enabled 1";
        return exec(cmd);
    }

    /** 一键关闭无障碍 */
    public static boolean disableAccessibility(Context ctx) {
        return exec("settings put secure enabled_accessibility_services ''; "
                + "settings put secure accessibility_enabled 0");
    }

    /**
     * 强制重启无障碍子系统，让新绑定立即生效。
     * 通过置 0 → 写服务 → 置 1 触发三个 settings 变更广播。
     */
    public static boolean forceRebindAccessibility(Context ctx) {
        String svc = ctx.getPackageName() + "/" + RingAccessibilityService.class.getName();
        return exec("settings put secure accessibility_enabled 0; "
                + "settings put secure enabled_accessibility_services ''; "
                + "settings put secure enabled_accessibility_services '" + svc + "'; "
                + "settings put secure accessibility_enabled 1");
    }

    /** 是否已在无障碍服务列表中 */
    public static boolean isInAccessibilityList(Context ctx) {
        try {
            String cur = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) {
                return false;
            }
            return cur.contains(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /** 跳系统无障碍设置页（无 root 时用） */
    public static void openAccessibilitySettings(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }
}