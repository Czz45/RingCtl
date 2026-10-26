package com.breeze.ringctl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 连续拖拽会话：模拟「手指按住不放、持续移动、最后抬起」的完整过程。
 *
 * 为什么需要它：
 * 普通 dispatchGesture 每次都是独立的「按下-移动-抬起」，两次之间有几十毫秒空隙，
 * 观感就是一顿一顿。用 continueStroke 可以把多个手势串成一次连续拖拽，
 * 手指始终是按下状态，页面跟着连续滚动。
 *
 * 用法：
 *   start() → feed(位移) 多次 → stop()（或超时自动 stop）
 */
public class DragSession {

    private static final String TAG = "DragSession";

    private final AccessibilityService svc;
    private final Handler handler;
    private final boolean horizontal;
    private final int screenW;
    private final int screenH;

    /** 当前手指位置 */
    private float curX;
    private float curY;
    /** 本段手势的起点 */
    private float segStartX;
    private float segStartY;

    /** 上一段的 continueStroke */
    private GestureDescription.StrokeDescription lastStroke;
    private boolean active = false;
    private boolean busy = false;

    /** 自动抬手超时 */
    private static final long IDLE_TIMEOUT_MS = 180L;
    private final Runnable idleStop;

    /** 每段手势的时长 */
    private static final long SEG_DURATION_MS = 60L;

    public interface Callback {
        void onEnd();
    }

    private final Callback callback;

    public DragSession(AccessibilityService svc,
                       boolean horizontal,
                       int screenW,
                       int screenH,
                       Callback callback) {
        this.svc = svc;
        this.horizontal = horizontal;
        this.screenW = screenW;
        this.screenH = screenH;
        this.callback = callback;
        this.handler = new Handler(Looper.getMainLooper());
        this.idleStop = () -> stop();
    }

    public boolean isActive() {
        return active;
    }

    /**
     * 开始拖拽，起点在屏幕中部。
     *
     * @param initialDelta 首个位移（像素），正数 = 手指向 +方向移动
     */
    public void start(float initialDelta) {
        if (active || busy) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        // 起点放在中间，留出两侧空间
        float cx = screenW / 2f;
        float cy = screenH / 2f;
        curX = cx;
        curY = cy;
        segStartX = cx;
        segStartY = cy;

        float endX = curX;
        float endY = curY;
        if (horizontal) {
            endX = clampX(curX - initialDelta);
        } else {
            endY = clampY(curY - initialDelta);
        }

        Path p = new Path();
        p.moveTo(curX, curY);
        p.lineTo(endX, endY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, SEG_DURATION_MS, true);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();

        active = true;
        busy = true;
        lastStroke = stroke;
        curX = endX;
        curY = endY;
        segStartX = endX;
        segStartY = endY;

        boolean ok = svc.dispatchGesture(gd, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                busy = false;
                scheduleIdle();
            }

            @Override
            public void onCancelled(GestureDescription d) {
                busy = false;
                forceEnd();
            }
        }, handler);

        if (!ok) {
            forceEnd();
            return;
        }
        scheduleIdle();
    }

    /**
     * 追加位移。位移会被转换成手指移动，用 continueStroke 接上去。
     *
     * @param delta 像素，正数表示向「正方向」移动（会和 start 的方向一致）
     */
    public void feed(float delta) {
        if (!active) {
            // 还没开始就从当前点起手
            start(delta);
            return;
        }
        if (lastStroke == null) {
            return;
        }
        if (busy) {
            // 上一段还没结束，这次位移先记在当前位置（累积由调用方处理）
            return;
        }
        if (Math.abs(delta) < 1f) {
            return;
        }

        float endX = curX;
        float endY = curY;
        if (horizontal) {
            endX = clampX(curX - delta);
        } else {
            endY = clampY(curY - delta);
        }

        // 位移太小或已经贴边，跳过
        if (Math.abs(endX - curX) < 1f && Math.abs(endY - curY) < 1f) {
            return;
        }

        Path p = new Path();
        p.moveTo(curX, curY);
        p.lineTo(endX, endY);

        GestureDescription.StrokeDescription stroke;
        try {
            stroke = lastStroke.continueStroke(p, 0, SEG_DURATION_MS, true);
        } catch (Exception e) {
            forceEnd();
            return;
        }

        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();

        busy = true;
        lastStroke = stroke;
        curX = endX;
        curY = endY;

        boolean ok = svc.dispatchGesture(gd, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                busy = false;
            }

            @Override
            public void onCancelled(GestureDescription d) {
                busy = false;
                forceEnd();
            }
        }, handler);

        if (!ok) {
            forceEnd();
            return;
        }
        scheduleIdle();
    }

    /** 结束拖拽：发最后一段（willContinue=false），手指抬起 */
    public void stop() {
        if (!active) {
            return;
        }
        handler.removeCallbacks(idleStop);

        if (lastStroke == null || busy) {
            // 没有可接的手势，直接标记结束
            forceEnd();
            return;
        }

        // 用极短的一段收尾，让手指抬起
        Path p = new Path();
        p.moveTo(curX, curY);
        p.lineTo(curX, curY + 0.5f);

        GestureDescription.StrokeDescription stroke;
        try {
            stroke = lastStroke.continueStroke(p, 0, 40L, false);
        } catch (Exception e) {
            forceEnd();
            return;
        }

        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        busy = true;
        lastStroke = null;

        svc.dispatchGesture(gd, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                busy = false;
                forceEnd();
            }

            @Override
            public void onCancelled(GestureDescription d) {
                busy = false;
                forceEnd();
            }
        }, handler);
    }

    private void scheduleIdle() {
        handler.removeCallbacks(idleStop);
        handler.postDelayed(idleStop, IDLE_TIMEOUT_MS);
    }

    private void forceEnd() {
        handler.removeCallbacks(idleStop);
        boolean was = active;
        active = false;
        busy = false;
        lastStroke = null;
        if (was && callback != null) {
            callback.onEnd();
        }
    }

    private float clampX(float x) {
        float margin = screenW * 0.08f;
        return Math.max(margin, Math.min(screenW - margin, x));
    }

    private float clampY(float y) {
        float margin = screenH * 0.08f;
        return Math.max(margin, Math.min(screenH - margin, y));
    }
}