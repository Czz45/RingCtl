package com.breeze.ringctl;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.util.Log;
import android.view.KeyEvent;

/**
 * 手势动作执行器。
 *
 * 支持三类：
 *   1. 启动应用（包名）
 *   2. 启动应用内功能（deeplink / scheme，如微信扫一扫）
 *   3. 播放控制（快进、快退、播放暂停、上下曲）
 *
 * 关于「查找应用内功能」：Android 没有公开接口能枚举第三方 App 的
 * 「付款码」这类内部页面。ShortcutManager 只能读自己应用的，
 * 读别家要 ACCESS_SHORTCUTS（系统级）。所以走 deeplink + 包名两条路，
 * 预设失效时用户可以自己填 scheme，或退化成「只打开应用」。
 */
public class LaunchActions {

    private static final String TAG = "RingCtlAction";

    public static final int TYPE_URI = 0;          // 启动应用功能（scheme）
    public static final int TYPE_PACKAGE = 1;      // 启动应用
    public static final int TYPE_MEDIA_PLAY = 2;   // 播放 / 暂停
    public static final int TYPE_MEDIA_NEXT = 3;   // 下一首 / 下一集
    public static final int TYPE_MEDIA_PREV = 4;   // 上一首
    public static final int TYPE_SEEK_FWD = 5;     // 快进
    public static final int TYPE_SEEK_BACK = 6;    // 快退

    /** 预设动作：显示名（分组 · 名称）/ 类型 / 值 */
    public static final String[][] PRESETS = {
            {"微信 · 扫一扫", "0", "weixin://dl/scan"},
            {"微信 · 收付款", "0", "weixin://dl/offlinepay"},
            {"支付宝 · 扫一扫", "0", "alipays://platformapi/startapp?appId=10000007"},
            {"支付宝 · 付款码", "0", "alipays://platformapi/startapp?appId=20000056"},
            {"QQ · 扫一扫", "0", "mqqapi://qrcode/scan"},
            {"播放控制 · 快进", "5", ""},
            {"播放控制 · 快退", "6", ""},
            {"播放控制 · 播放/暂停", "2", ""},
            {"播放控制 · 下一首", "3", ""},
            {"播放控制 · 上一首", "4", ""},
            {"系统 · 相机", "1", "com.android.camera"},
    };

    private LaunchActions() {
    }

    /** 不需要参数的动作，返回固定名称；需要参数返回 null */
    public static String fixedName(int type) {
        switch (type) {
            case TYPE_MEDIA_PLAY:
                return "播放控制 · 播放/暂停";
            case TYPE_MEDIA_NEXT:
                return "播放控制 · 下一首";
            case TYPE_MEDIA_PREV:
                return "播放控制 · 上一首";
            case TYPE_SEEK_FWD:
                return "播放控制 · 快进";
            case TYPE_SEEK_BACK:
                return "播放控制 · 快退";
            default:
                return null;
        }
    }

    public static String describe(int type, String value) {
        String fixed = fixedName(type);
        if (fixed != null) {
            return fixed;
        }
        if (value == null || value.isEmpty()) {
            return "未设置";
        }
        for (String[] p : PRESETS) {
            if (p[2].equals(value) && p[1].equals(String.valueOf(type))) {
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
     * @return 是否成功
     */
    public static boolean launch(Context ctx, int type, String value) {
        if (ctx == null) {
            return false;
        }
        try {
            switch (type) {
                case TYPE_MEDIA_PLAY:
                    return mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                case TYPE_MEDIA_NEXT:
                    return mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT);
                case TYPE_MEDIA_PREV:
                    return mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                case TYPE_SEEK_FWD:
                    return mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
                case TYPE_SEEK_BACK:
                    return mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_REWIND);
                case TYPE_PACKAGE: {
                    if (value == null || value.isEmpty()) {
                        return false;
                    }
                    PackageManager pm = ctx.getPackageManager();
                    Intent i = pm.getLaunchIntentForPackage(value);
                    if (i == null) {
                        Log.w(TAG, "no launch intent for " + value);
                        return false;
                    }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    return true;
                }
                default: {
                    if (value == null || value.isEmpty()) {
                        return false;
                    }
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(value));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "action failed type=" + type + " value=" + value, t);
            return false;
        }
    }

    /**
     * 发一个媒体键。
     *
     * 这是能同时兼容 B 站、YouTube、本地播放器的最省事方式，
     * 代价是「快进多少秒」由播放器自己决定（一般 10 秒）。
     * 精确 seek 需要 MediaSession + 通知使用权，另说。
     */
    private static boolean mediaKey(Context ctx, int keyCode) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return false;
            }
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            Log.i(TAG, "media key " + keyCode);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "media key failed " + keyCode, t);
            return false;
        }
    }
}
