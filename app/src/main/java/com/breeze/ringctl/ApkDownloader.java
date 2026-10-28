package com.breeze.ringctl;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 内置 APK 下载器。
 *
 * 之前点「去更新」只能跳浏览器，还得自己找下载按钮。
 * 这里直接在 App 里下到 cache/apk/，下完调系统安装器。
 *
 * 要点：
 *   1. 先写 .part 再 rename，避免断网留下半个包
 *   2. 已下好的包直接复用，重复点不会重下
 *   3. Android 8+ 需要「安装未知应用」权限，没给就跳设置页
 */
public class ApkDownloader {

    private static final String TAG = "RingCtlDl";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        /** percent < 0 表示总长度未知 */
        void onProgress(int percent, long done, long total);

        void onDone(File apk);

        void onFail(String reason);
    }

    private ApkDownloader() {
    }

    public static File targetFile(Context c, String ver) {
        File dir = new File(c.getCacheDir(), "apk");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        String safe = ver == null ? "latest" : ver.replaceAll("[^0-9A-Za-z._-]", "");
        return new File(dir, "RingCtl-" + safe + ".apk");
    }

    /** 已下载且看起来完整（大于 1MB）就直接用 */
    public static File cached(Context c, String ver) {
        File f = targetFile(c, ver);
        return (f.exists() && f.length() > 1024 * 1024) ? f : null;
    }

    public static void download(Context ctx, String url, String ver, Callback cb) {
        final Context app = ctx.getApplicationContext();
        final File dest = targetFile(app, ver);

        File had = cached(app, ver);
        if (had != null) {
            MAIN.post(() -> cb.onDone(had));
            return;
        }

        new Thread(() -> {
            HttpURLConnection conn = null;
            FileOutputStream fos = null;
            File tmp = new File(dest.getAbsolutePath() + ".part");
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "RingCtl");
                int code = conn.getResponseCode();
                if (code != 200) {
                    postFail(cb, "服务器返回 " + code);
                    return;
                }
                long total = conn.getContentLengthLong();
                InputStream in = conn.getInputStream();
                fos = new FileOutputStream(tmp);
                byte[] buf = new byte[16 * 1024];
                long done = 0L;
                int lastPct = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            final int p = pct;
                            final long d = done;
                            final long t = total;
                            MAIN.post(() -> cb.onProgress(p, d, t));
                        }
                    }
                }
                fos.flush();
                fos.close();
                fos = null;
                in.close();

                if (tmp.length() < 1024 * 1024) {
                    postFail(cb, "下载内容异常（文件过小）");
                    return;
                }
                if (dest.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                }
                if (!tmp.renameTo(dest)) {
                    postFail(cb, "保存失败");
                    return;
                }
                Log.i(TAG, "downloaded " + dest.getAbsolutePath() + " " + dest.length());
                final File f = dest;
                MAIN.post(() -> cb.onDone(f));
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                postFail(cb, "网络错误：" + e.getMessage());
            } finally {
                try {
                    if (fos != null) fos.close();
                } catch (Exception ignored) {
                }
                if (conn != null) conn.disconnect();
                if (tmp.exists() && !dest.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }, "RingCtlDl").start();
    }

    private static void postFail(Callback cb, String msg) {
        MAIN.post(() -> cb.onFail(msg));
    }

    /**
     * 调起系统安装器。
     *
     * @return true 已弹出安装界面；false 说明缺「安装未知应用」权限，已经跳到设置页
     */
    public static boolean install(Context ctx, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !ctx.getPackageManager().canRequestPackageInstalls()) {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                i.setData(Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return false;
            }
            Uri uri = FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
            return false;
        }
    }

    /** 清掉缓存里的安装包 */
    public static void clearCache(Context c) {
        try {
            File dir = new File(c.getCacheDir(), "apk");
            File[] fs = dir.listFiles();
            if (fs == null) return;
            for (File f : fs) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
