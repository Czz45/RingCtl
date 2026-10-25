package com.breeze.ringctl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 无障碍服务：执行滑动 / 翻页动作。
 * 支持竖直、水平两个方向，所有手势方法带 onFinish 回调用于背压。
 */
public class RingAccessibilityService extends AccessibilityService {

    public static final int DIR_VERTICAL = 0;
    public static final int DIR_HORIZONTAL = 1;

    private static RingAccessibilityService instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean gestureBusy = false;

    public static boolean isReady() {
        return instance != null;
    }

    public static RingAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    // ==================== 对外入口 ====================

    public boolean swipe(float screenDelta, long duration, int dir, Runnable onFinish) {
        if (screenDelta == 0f) {
            if (onFinish != null) {
                onFinish.run();
            }
            return false;
        }
        if (gestureBusy) {
            if (onFinish != null) {
                onFinish.run();
            }
            return false;
        }

        int[] size = screenSize();
        if (size[0] <= 0 || size[1] <= 0) {
            if (onFinish != null) {
                onFinish.run();
            }
            return false;
        }
        float w = size[0];
        float h = size[1];

        // 关键：位移太小会被识别成点击，强制最小滑动距离
        float minSlop = dpToPx(24f);
        if (Math.abs(screenDelta) < minSlop) {
            if (onFinish != null) {
                onFinish.run();
            }
            return false;
        }

        long dur = Math.max(40L, Math.min(400L, duration));

        Path path = new Path();
        if (dir == DIR_HORIZONTAL) {
            // 水平：正数 = 手指向左（内容左移 / 下一页），负数 = 向右
            float maxDx = w * 0.40f;
            float dx = Math.max(-maxDx, Math.min(maxDx, screenDelta));

            float cy = h * 0.5f;
            float startX = w * 0.5f;
            float endX = startX - dx;

            float margin = w * 0.08f;
            if (endX < margin) {
                float shift = margin - endX;
                endX += shift;
                startX += shift;
            } else if (endX > w - margin) {
                float shift = endX - (w - margin);
                endX -= shift;
                startX -= shift;
            }
            startX = Math.max(margin, Math.min(w - margin, startX));
            endX = Math.max(margin, Math.min(w - margin, endX));

            if (Math.abs(endX - startX) < minSlop) {
                if (onFinish != null) {
                    onFinish.run();
                }
                return false;
            }
            path.moveTo(startX, cy);
            path.lineTo(endX, cy);
        } else {
            // 竖直：正数 = 手指向上（内容上滚）
            float maxDy = h * 0.45f;
            float dy = Math.max(-maxDy, Math.min(maxDy, screenDelta));

            float cx = w / 2f;
            float startY = h * 0.5f;
            float endY = startY - dy;

            float margin = h * 0.06f;
            if (endY < margin) {
                float shift = margin - endY;
                endY += shift;
                startY += shift;
            } else if (endY > h - margin) {
                float shift = endY - (h - margin);
                endY -= shift;
                startY -= shift;
            }
            startY = Math.max(margin, Math.min(h - margin, startY));
            endY = Math.max(margin, Math.min(h - margin, endY));

            if (Math.abs(endY - startY) < minSlop) {
                if (onFinish != null) {
                    onFinish.run();
                }
                return false;
            }
            path.moveTo(cx, startY);
            path.lineTo(cx, endY);
        }

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();

        return dispatchSafe(gesture, onFinish);
    }

    public boolean swipeVertical(float screenDy, long duration) {
        return swipeVertical(screenDy, duration, null);
    }

    public boolean swipeVertical(float screenDy, long duration, Runnable onFinish) {
        return swipe(screenDy, duration, DIR_VERTICAL, onFinish);
    }

    public boolean swipeHorizontal(float screenDx, long duration) {
        return swipeHorizontal(screenDx, duration, null);
    }

    /**
     * 水平滑动。
     *
     * @param screenDx 正数 = 手指向左（内容左移 / 下一页）；负数 = 手指向右
     */
    public boolean swipeHorizontal(float screenDx, long duration, Runnable onFinish) {
        return swipe(screenDx, duration, DIR_HORIZONTAL, onFinish);
    }

    public boolean pageScroll(boolean backward) {
        return pageScroll(backward, null);
    }

    /**
     * 翻页：优先用可滚动节点的 ACTION_SCROLL，失败则退回大距离滑动。
     */
    public boolean pageScroll(boolean backward, Runnable onFinish) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo scrollable = findScrollable(root, 0);
                if (scrollable != null) {
                    int action = backward
                            ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                    if (scrollable.performAction(action)) {
                        if (onFinish != null) {
                            onFinish.run();
                        }
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        int[] size = screenSize();
        if (size[1] <= 0) {
            if (onFinish != null) {
                onFinish.run();
            }
            return false;
        }
        float dy = size[1] * 0.42f;
        return swipeVertical(backward ? -dy : dy, 220, onFinish);
    }

    /** 全局返回 */
    public boolean goBack() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /** 全局主页 */
    public boolean goHome() {
        return performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /** 深度优先找可滚动节点，限制深度和广度防卡死 */
    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 12) {
            return null;
        }
        try {
            if (node.isScrollable()) {
                return node;
            }
            int count = node.getChildCount();
            for (int i = 0; i < count && i < 24; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) {
                    continue;
                }
                AccessibilityNodeInfo found = findScrollable(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean dispatchSafe(GestureDescription gesture, Runnable onFinish) {
        gestureBusy = true;
        boolean ok;
        try {
            ok = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gd) {
                    ui.postDelayed(() -> {
                        gestureBusy = false;
                        if (onFinish != null) {
                            onFinish.run();
                        }
                    }, 8);
                }

                @Override
                public void onCancelled(GestureDescription gd) {
                    ui.postDelayed(() -> {
                        gestureBusy = false;
                        if (onFinish != null) {
                            onFinish.run();
                        }
                    }, 8);
                }
            }, ui);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            gestureBusy = false;
        }
        return ok;
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private int[] screenSize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return new int[]{0, 0};
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }
}