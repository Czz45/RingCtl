package com.breeze.ringctl;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

/**
 * 快捷启动动作。
 *
 * 关于「查找应用内功能」：Android 没有公开接口能枚举第三方 App 的
 * 「付款码」这类内部页面。可行的手段只有三种：
 *
 *   1. App Shortcuts（长按图标的菜单）——ShortcutManager 只能读自己应用的，
 *      读别家需要 ACCESS_SHORTCUTS（系统级），普通 App 拿不到
 *   2. Deep link / scheme —— 应用自己注册的，能直接用（本类主用这条路）
 *   3. 硬编码 Activity 组件名 —— 最容易随版本失效
 *
 * 所以这里做成「预设 + 自定义」：预置常见应用的 scheme，
 * 失效了用户还能填自己的 URI，或者退化成「只打开应用」。
 */
public class LaunchActions {

    private static final String TAG = "RingCtlLaunch";

    public static final int TYPE_URI = 0;
    public static final int TYPE_PACKAGE = 1;

    /** 预设动作：显示名 / 类型 / 值 */
    public static final String[][] PRESETS = {
            {"微信 · 扫一扫", "0", "weixin://dl/scan"},
            {"微信 · 收付款", "0", "weixin://dl/offlinepay"},
            {"支付宝 · 扫一扫", "0", "alipays://platformapi/startapp?appId=10000007"},
            {"支付宝 · 付款码", "0", "alipays://platformapi/startapp?appId=20000056"},
            {"QQ · 扫一扫", "0", "mqqapi://qrcode/scan"},
            {"相机", "1", "com.android.camera"},
    };

    private LaunchActions() {
    }

    public static String describe(int type, String value) {
        if (value == null || value.isEmpty()) {
            return "未设置";
        }
        for (String[] p : PRESETS) {
            if (p[2].equals(value)) {
                return p[0];
            }
        }
        if (type == TYPE_PACKAGE) {
            return "应用 " + value;
        }
        return "链接 " + value;
    }

    /**
     * 执行动作。
     *
     * @return 是否成功拉起
     */
    public static boolean launch(Context ctx, int type, String value) {
        if (ctx == null || value == null || value.isEmpty()) {
            return false;
        }
        try {
            Intent i;
            if (type == TYPE_PACKAGE) {
                PackageManager pm = ctx.getPackageManager();
                i = pm.getLaunchIntentForPackage(value);
                if (i == null) {
                    Log.w(TAG, "no launch intent for " + value);
                    return false;
                }
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(value));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            Log.i(TAG, "launched type=" + type + " value=" + value);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "launch failed: " + value, t);
            return false;
        }
    }
}
