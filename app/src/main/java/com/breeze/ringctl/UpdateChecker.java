package com.breeze.ringctl;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GitHub 更新检查：请求 Releases API，比对 tag 与当前 versionName。
 * 仓库已公开，无需 token。
 */
public class UpdateChecker {

    public static final String REPO = "Czz45/RingCtl";
    public static final String API_LATEST =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String URL_REPO = "https://github.com/" + REPO;
    public static final String URL_RELEASES = "https://github.com/" + REPO + "/releases";
    public static final String URL_COOLAPK = "https://www.coolapk.com/u/26711585";

    public interface Callback {
        void onUpdate(String latestVersion, String downloadUrl, String changelog);

        void onLatest(String currentVersion);

        void onFail(String reason);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static String currentVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "0.1" : pi.versionName;
        } catch (Exception e) {
            return "0.1";
        }
    }

    public static void check(Context ctx, Callback cb) {
        final String cur = currentVersion(ctx);
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(API_LATEST);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "RingCtl/" + cur);

                int code = conn.getResponseCode();
                if (code == 404) {
                    post(() -> cb.onFail("还没有发布版本"));
                    return;
                }
                if (code != 200) {
                    post(() -> cb.onFail("服务器返回 " + code));
                    return;
                }

                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                String tag = json.optString("tag_name", "");
                if (tag.startsWith("v") || tag.startsWith("V")) {
                    tag = tag.substring(1);
                }
                String body = json.optString("body", "");

                String download = URL_RELEASES;
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a == null) {
                            continue;
                        }
                        String name = a.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            String u = a.optString("browser_download_url", "");
                            if (!u.isEmpty()) {
                                download = u;
                                break;
                            }
                        }
                    }
                }

                final String latest = tag;
                final String dl = download;
                final String log = body;

                if (isNewer(latest, cur)) {
                    post(() -> cb.onUpdate(latest, dl, log));
                } else {
                    post(() -> cb.onLatest(cur));
                }
            } catch (Exception e) {
                post(() -> cb.onFail("网络错误"));
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
        }).start();
    }

    public static boolean isNewer(String latest, String current) {
        if (latest == null || latest.isEmpty()) {
            return false;
        }
        String[] a = latest.split("\\.");
        String[] b = (current == null ? "0" : current).split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? parseIntSafe(a[i]) : 0;
            int y = i < b.length ? parseIntSafe(b[i]) : 0;
            if (x > y) {
                return true;
            }
            if (x < y) {
                return false;
            }
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void post(Runnable r) {
        MAIN.post(r);
    }

    public static void openUrl(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }
}