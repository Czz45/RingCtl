package com.breeze.ringctl;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 应用列表提供者：列出可启动的 App。
 */
public class AppList {

    public static class Item {
        public String pkg;
        public String label;
        public Drawable icon;
        public boolean system;

        public Item(String pkg, String label, Drawable icon, boolean system) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.system = system;
        }
    }

    // 内存缓存：系统应用 / 用户应用各一份，避免每次进页面都全量扫描
    private static List<Item> cacheUser;
    private static List<Item> cacheAll;

    /** 清空缓存，安装或卸载应用后调用 */
    public static void invalidate() {
        cacheUser = null;
        cacheAll = null;
    }

    public static List<Item> load(Context ctx, boolean includeSystem) {
        List<Item> cached = includeSystem ? cacheAll : cacheUser;
        if (cached != null) {
            return cached;
        }
        List<Item> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> all = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : all) {
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (sys && !includeSystem) {
                    continue;
                }
                if (pm.getLaunchIntentForPackage(ai.packageName) == null) {
                    continue;
                }
                if (ai.packageName.equals(ctx.getPackageName())) {
                    continue;
                }
                CharSequence label = pm.getApplicationLabel(ai);
                Drawable icon;
                try {
                    icon = pm.getApplicationIcon(ai);
                } catch (Exception e) {
                    icon = null;
                }
                out.add(new Item(ai.packageName,
                        label == null ? ai.packageName : label.toString(),
                        icon, sys));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        if (includeSystem) {
            cacheAll = out;
        } else {
            cacheUser = out;
        }
        return out;
    }


    /** 静态缓存：包名到 Item，避免重复查询 */
    private static final java.util.HashMap<String, Item> cacheByPkg = new java.util.HashMap<>();

    /**
     * 只查询指定的几个包名。
     * 用于「已设置的应用」这类少量条目，避免全量扫描。
     */
    public static List<Item> loadByPkgs(Context ctx, java.util.Collection<String> pkgs) {
        List<Item> out = new ArrayList<>();
        if (pkgs == null || pkgs.isEmpty()) {
            return out;
        }
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : pkgs) {
            Item cached = cacheByPkg.get(pkg);
            if (cached != null) {
                out.add(cached);
                continue;
            }
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence label = pm.getApplicationLabel(ai);
                Drawable icon;
                try {
                    icon = pm.getApplicationIcon(ai);
                } catch (Exception e) {
                    icon = null;
                }
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                Item item = new Item(pkg,
                        label == null ? pkg : label.toString(),
                        icon, sys);
                cacheByPkg.put(pkg, item);
                out.add(item);
            } catch (Exception e) {
                // App 已卸载，跳过
            }
        }
        return out;
    }

    /** 清空按包名缓存 */
    public static void invalidateByPkg() {
        cacheByPkg.clear();
    }

    public static String labelOf(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            return l == null ? pkg : l.toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    public static Drawable iconOf(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }
}