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

    public static List<Item> load(Context ctx, boolean includeSystem) {
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
        return out;
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