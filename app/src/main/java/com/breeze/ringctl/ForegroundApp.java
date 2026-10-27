package com.breeze.ringctl;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

/**
 * 前台应用识别。
 *
 * 优先级：
 *   1. UsageStatsManager（需要「使用情况访问权限」，最准确）
 *   2. 无障碍 getWindows()（兜底）
 *
 * UsageStats 不受输入法、悬浮窗干扰，能真正反映用户正在用的 App。
 */
public class ForegroundApp {

    /** 缓存，避免每次查询都走系统调用 */
    private static String cachedPkg = "";
    private static long cachedAt = 0L;
    private static final long CACHE_MS = 250L;

    /** 是否已授予「使用情况访问权限」 */
    public static boolean hasUsagePermission(Context c) {
        try {
            AppOpsManager aom = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) {
                return false;
            }
            int mode = aom.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 取当前前台包名。优先 UsageStats，失败则退回无障碍。
     */
    public static String get(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - cachedAt < CACHE_MS && cachedPkg.length() > 0) {
            return cachedPkg;
        }
        cachedAt = now;

        String pkg = byUsageStats(c);
        if (pkg == null || pkg.length() == 0) {
            pkg = RingAccessibilityService.resolveForegroundPkg();
        }
        if (pkg == null) {
            pkg = "";
        }
        // 自己不算
        if (pkg.equals(c.getPackageName())) {
            pkg = "";
        }
        cachedPkg = pkg;
        return pkg;
    }

    /** 用 UsageEvents 找最近一次切到前台的 App */
    private static String byUsageStats(Context c) {
        if (!hasUsagePermission(c)) {
            return null;
        }
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long end = System.currentTimeMillis();
            long begin = end - 60_000L;
            UsageEvents events = usm.queryEvents(begin, end);
            if (events == null) {
                return null;
            }
            UsageEvents.Event ev = new UsageEvents.Event();
            String last = null;
            long lastTs = 0L;
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                int t = ev.getEventType();
                // 1 = MOVE_TO_FOREGROUND
                if (t == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || t == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (ev.getTimeStamp() >= lastTs) {
                        lastTs = ev.getTimeStamp();
                        last = ev.getPackageName();
                    }
                }
            }
            return last;
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否输入法 / 系统界面这类不该被当成目标 App 的包 */
    public static boolean isIgnorable(String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return true;
        }
        return pkg.equals("android")
                || pkg.equals("com.android.systemui")
                || pkg.contains("inputmethod")
                || pkg.contains("ime")
                || pkg.contains("keyboard");
    }

    /** 清缓存，切换 App 时更快反映 */
    public static void invalidate() {
        cachedAt = 0L;
        cachedPkg = "";
    }
}