package com.ctt.healthdisplay.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.6.7 · 玩家最近 5 秒伤害滑窗追踪器（HUD 关行 DPS 数据源）。
 *
 * <h2>语义</h2>
 * <p>{@code DPS = recent5sSum(uuid) / 5.0}——"最近 5 秒造成的伤害总量"除以窗口宽度。
 * 不按 stageKey 切片：滑窗本身只反映"近期手感"，跨关瞬间会同时显示前后两关的尾巴 +
 * 开头，5 秒后自然衰减为只剩当前关，符合"刚换关时还在打"的真实感觉。
 *
 * <h2>实现</h2>
 * <p>每玩家一个 5 个 1 秒桶的环形缓冲，与 1Hz S2C 推送间隔对齐。
 * <ul>
 *   <li>{@link DpsRing#add}：定位 {@code wallSec = nowMs / 1000} 对应的桶；与上次写入相比
 *       前进了多少秒就清掉中间空桶（避免长时间无伤害后桶残留陈数据）。</li>
 *   <li>{@link DpsRing#sum}：先按当前 wallSec 滚动一遍（清掉过期桶），再返回 5 个桶之和。</li>
 * </ul>
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>{@link PlayerDamageStats#add} 里写入 session 累计后调 {@link #onDealt}。</li>
 *   <li>{@link PlayerDamageStats#start / stop / clear} 联动 {@link #clearAll} 清掉 ring，
 *       避免冻结 / 重置后还显示"幽灵 DPS"。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>{@code RING} 用 {@link ConcurrentHashMap}；DpsRing 内部读写都在 server tick 线程，
 * 无并发；{@link StatsSnapshotBroadcaster} 也只在 {@code END_SERVER_TICK} 期间读，
 * 与写在同一线程。
 */
public final class PlayerDpsTracker {

    /** 滑动窗口宽度（秒）。"最近 5 秒"由用户拍板。 */
    public static final int WINDOW_SECONDS = 5;

    private static final Map<UUID, DpsRing> RING = new ConcurrentHashMap<>();

    private PlayerDpsTracker() {}

    /** {@link PlayerDamageStats#add} 注入点：累加一次伤害事件。 */
    public static void onDealt(UUID uuid, long amount) {
        if (uuid == null || amount <= 0) return;
        RING.computeIfAbsent(uuid, k -> new DpsRing()).add(System.currentTimeMillis(), amount);
    }

    /** 该玩家最近 5 秒造成的伤害总量。 */
    public static long recent5sSum(UUID uuid) {
        if (uuid == null) return 0L;
        DpsRing r = RING.get(uuid);
        return r == null ? 0L : r.sum(System.currentTimeMillis());
    }

    /** 清掉所有玩家 ring（start / stop / clear 联动）。 */
    public static void clearAll() {
        RING.clear();
    }

    // =========================================================================
    //  Ring 实现
    // =========================================================================

    /**
     * 5 个 1 秒桶的环形缓冲。
     * <p>桶索引 = {@code wallSec % WINDOW_SECONDS}。
     * 写入时若距上次写入 ≥ {@code WINDOW_SECONDS} 秒，整个 ring 清空；
     * 否则只清掉跨过的中间桶。
     */
    private static final class DpsRing {
        private final long[] buckets = new long[WINDOW_SECONDS];
        /** 上次访问（add / sum）时的 wallSec；-1 = 还从未访问。 */
        private long lastWallSec = -1L;

        synchronized void add(long nowMs, long amount) {
            long sec = nowMs / 1000L;
            rotateTo(sec);
            buckets[(int) Math.floorMod(sec, WINDOW_SECONDS)] += amount;
        }

        synchronized long sum(long nowMs) {
            long sec = nowMs / 1000L;
            rotateTo(sec);
            long s = 0;
            for (long b : buckets) s += b;
            return s;
        }

        /** 把 ring 滚到 {@code targetSec}：清掉 lastWallSec+1 .. targetSec 之间的过期桶。 */
        private void rotateTo(long targetSec) {
            if (lastWallSec < 0) {
                lastWallSec = targetSec;
                return;
            }
            long advance = targetSec - lastWallSec;
            if (advance <= 0) return;            // 同一秒或时钟回跳——不动
            if (advance >= WINDOW_SECONDS) {
                // 离上次访问已经超过整个窗口：所有桶都过期，整体清零。
                for (int i = 0; i < WINDOW_SECONDS; i++) buckets[i] = 0L;
            } else {
                // 只清跨过的中间桶（不含旧 last，含新 target）
                for (long s = lastWallSec + 1; s <= targetSec; s++) {
                    buckets[(int) Math.floorMod(s, WINDOW_SECONDS)] = 0L;
                }
            }
            lastWallSec = targetSec;
        }
    }
}
