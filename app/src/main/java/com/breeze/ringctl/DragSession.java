package com.breeze.ringctl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 连续拖拽会话：模拟「手指按住不放、持续移动、最后抬起」。
 *
 * 关键设计（解决一顿一顿）：
 * 1. 上一段手势没结束时，新位移不丢弃，累积到 pending 里
 * 2. 一段结束立即检查 pending，有就立刻接上下一段
 * 3. 段时长短（40ms），减少每次的等待感
 * 4. 单段位移有上限，防止一次跳太远
 */
public class DragSession {

    private static final String TAG = "DragSession";

    private final AccessibilityService svc;
    private final Handler handler;
    private final boolean horizontal;
    private final int screenW;
    private final int screenH;
    private final Callback callback;

    private float curX;
    private float curY;

    private GestureDescription.StrokeDescription lastStroke;
    private boolean active = false;
    private boolean busy = false;
    /** 忙时累积、等空闲后一次性补上的位移 */
    private float pending = 0f;

    private static final long IDLE_TIMEOUT_MS = 150L;
    /** 每段手势时长，越短衔接越密 */
    private static final long SEG_DURATION_MS = 40L;
    /** 单段最大位移，防止一次跳太远 */
    private static final float MAX_SEG_DELTA = 260f;

    private final Runnable idleStop;

    public interface Callback {
        void onEnd();
    }

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

    public void start(float initialDelta) {
        if (active) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        curX = screenW / 2f;
        curY = screenH / 2f;
        pending = initialDelta;
        active = true;
        busy = false;
        lastStroke = null;

        pump();
        scheduleIdle();
    }

    /** 追加位移。忙时会累积，空闲后自动补上。 */
    public void feed(float delta) {
        if (!active) {
            start(delta);
            return;
        }
        pending += delta;
        // 累积量限制，避免长时间卡顿后一次性暴走
        float cap = MAX_SEG_DELTA * 3f;
        if (pending > cap) {
            pending = cap;
        } else if (pending < -cap) {
            pending = -cap;
        }
        pump();
        scheduleIdle();
    }

    /** 空闲时把 pending 发出去 */
    private void pump() {
        if (!active || busy) {
            return;
        }
        if (Math.abs(pending) < 1f) {
            return;
        }

        float take = pending;
        if (take > MAX_SEG_DELTA) {
            take = MAX_SEG_DELTA;
        } else if (take < -MAX_SEG_DELTA) {
            take = -MAX_SEG_DELTA;
        }
        pending -= take;

        float endX = curX;
        float endY = curY;
        if (horizontal) {
            endX = clampX(curX - take);
        } else {
            endY = clampY(curY - take);
        }

        // 贴边了走不动，清掉累积避免死循环
        if (Math.abs(endX - curX) < 1f && Math.abs(endY - curY) < 1f) {
            pending = 0f;
            return;
        }

        Path p = new Path();
        p.moveTo(curX, curY);
        p.lineTo(endX, endY);

        GestureDescription.StrokeDescription stroke;
        try {
            if (lastStroke == null) {
                // 首段：手指从按下开始
                stroke = new GestureDescription.StrokeDescription(
                        p, 0, SEG_DURATION_MS, true);
            } else {
                // 后续：接上一段继续移动，手指保持按下
                stroke = lastStroke.continueStroke(p, 0, SEG_DURATION_MS, true);
            }
        } catch (Exception e) {
            // 接不上就重开一个会话
            lastStroke = null;
            pending += take;
            if (pending == take) {
                // 重开也失败，放弃这次
                pending = 0f;
            }
            return;
        }

        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();

        busy = true;
        lastStroke = stroke;
        curX = endX;
        Log.d(TAG, "seg take=" + take + " cur=" + (horizontal ? curX : curY));
        curY = endY;

        boolean ok;
        try {
            ok = svc.dispatchGesture(gd, new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) {
                    busy = false;
                    Log.d(TAG, "seg done");
                    // 立刻补下一段，保持连续
                    pump();
                }

                @Override
                public void onCancelled(GestureDescription d) {
                    busy = false;
                    // 手势被打断，重新开始
                    lastStroke = null;
                    pump();
                }
            }, handler);
        } catch (Exception e) {
            ok = false;
        }

        if (!ok) {
            busy = false;
            lastStroke = null;
            pending += take;
        }
    }

    public void stop() {
        if (!active) {
            return;
        }
        handler.removeCallbacks(idleStop);

        if (busy) {
            // 等下当前段结束，pump 里会自然处理
            pending = 0f;
            return;
        }
        if (lastStroke == null) {
            forceEnd();
            return;
        }

        // 收尾：接一个极短段，willContinue=false 让手指抬起
        Path p = new Path();
        p.moveTo(curX, curY);
        p.lineTo(curX, curY + 0.5f);

        GestureDescription.StrokeDescription stroke;
        try {
            stroke = lastStroke.continueStroke(p, 0, 20L, false);
        } catch (Exception e) {
            forceEnd();
            return;
        }

        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        busy = true;
        lastStroke = null;

        try {
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
        } catch (Exception e) {
            busy = false;
            forceEnd();
        }
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
        pending = 0f;
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