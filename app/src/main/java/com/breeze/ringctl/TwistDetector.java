package com.breeze.ringctl;

/**
 * 「来回扭转」手势检测。
 *
 * 上游 HyperZoomRing / OmniRing 只区分「方向 + 速度」四档，
 * 没有来回扭转这种组合手势，所以这里自己实现。
 *
 * 判定思路：把转动拆成「半程」。
 *   一个方向持续累加，方向翻转时结算一次半程；
 *   只有半程幅度够大才算有效翻转（否则是手抖）；
 *   若在 800ms 内连续出现 2 次有效翻转（正→负→正 或反之），
 *   就认为用户是故意来回扭了一下。
 *
 * 阈值都做成可调，不同人手腕幅度差别很大。
 */
public class TwistDetector {

    private float accum = 0f;        // 当前半程累计幅度
    private int dir = 0;             // 当前方向 1 / -1
    private long lastFlipMs = 0L;    // 上次有效翻转时间
    private int flips = 0;           // 窗口内的有效翻转次数
    private long cooldownUntil = 0L; // 触发后的冷却，避免连发

    private final float minSwing;
    private final long windowMs;
    private final int neededFlips;
    private final long cooldownMs;

    /**
     * @param minSwing     每个半程至少转多少（传感器计数）才算数
     * @param windowMs     两次翻转的最大间隔
     * @param neededFlips  需要几次有效翻转（2 = 扭一个来回）
     * @param cooldownMs   触发后的冷却时间
     */
    public TwistDetector(float minSwing, long windowMs, int neededFlips, long cooldownMs) {
        this.minSwing = minSwing;
        this.windowMs = windowMs;
        this.neededFlips = Math.max(2, neededFlips);
        this.cooldownMs = cooldownMs;
    }

    public void reset() {
        accum = 0f;
        dir = 0;
        flips = 0;
        lastFlipMs = 0L;
    }

    /**
     * 喂一帧的增量。
     *
     * @param d     本帧传感器计数变化（带符号）
     * @param nowMs 当前时间
     * @return true 表示这一帧凑齐了「来回扭转」
     */
    public boolean feed(float d, long nowMs) {
        if (nowMs < cooldownUntil) {
            return false;
        }
        if (d == 0f) {
            return false;
        }
        int nd = d > 0 ? 1 : -1;
        float mag = Math.abs(d);

        if (dir == 0) {
            dir = nd;
            accum = mag;
            return false;
        }
        if (nd == dir) {
            accum += mag;
            return false;
        }

        // 方向变了：结算上一半程
        if (accum < minSwing) {
            // 幅度不够，当成噪声，直接换向重新累计
            dir = nd;
            accum = mag;
            return false;
        }

        // 有效翻转
        if (nowMs - lastFlipMs > windowMs) {
            flips = 0;
        }
        flips++;
        lastFlipMs = nowMs;
        dir = nd;
        accum = mag;

        if (flips >= neededFlips) {
            flips = 0;
            cooldownUntil = nowMs + cooldownMs;
            return true;
        }
        return false;
    }
}
