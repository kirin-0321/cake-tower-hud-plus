package com.ctt.healthdisplay.server;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.6.7 / v8.x · 玩家最近 5 秒伤害滑窗追踪器（HUD 关行 DPS 数据源 + G7b 双指标 AND 决策核心）。
 *
 * <h2>语义</h2>
 * <p>每玩家一个 5 个 1 秒桶的环形缓冲，与 1Hz S2C 推送间隔对齐。三类查询：
 * <ul>
 *   <li>{@link #recent5sSum} —— 最近 5 秒累积总伤害（HUD"关行 DPS"显示用）</li>
 *   <li>{@link #dpsActive(UUID)} —— 最近 5 秒**按非空桶平均**（v8.x · 双指标 AND
 *       的"DPS 维度"基线，反映"出手秒手感"而不被空闲秒稀释）</li>
 *   <li>{@link #dpsActiveByWeapon(UUID, String)} —— 同上，但按主手武器分桶
 *       （v8.x · L 面板"当前手持武器 DPS"显示用 + per-weapon AND 决策）</li>
 * </ul>
 *
 * <h2>per-weapon 维度（v8.x 引入）</h2>
 * <p>除了原有的 per-player 桶之外，额外维护 {@code Map<UUID, Map<weaponId, DpsRing>>}——
 * 每次 {@link #onDealt} / {@link #onDealtByWeapon} 同时往两个维度入桶；查询时各自独立。
 * 内存开销：单玩家 30 武器 × 5 long ≈ 1.2 KB；4 玩家小队 ≈ 5 KB——可忽略。
 *
 * <h2>实现</h2>
 * <ul>
 *   <li>{@link DpsRing#add}：定位 {@code wallSec = nowMs / 1000} 对应的桶；与上次写入相比
 *       前进了多少秒就清掉中间空桶（避免长时间无伤害后桶残留陈数据）。</li>
 *   <li>{@link DpsRing#sum}：先按当前 wallSec 滚动一遍（清掉过期桶），再返回 5 个桶之和。</li>
 *   <li>{@link DpsRing#dpsActive}：返回 {@code sum / 非空桶数}，全空时返回 0。</li>
 * </ul>
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>{@link PlayerDamageStats#add} 里写入 session 累计后调 {@link #onDealt} +
 *       {@link #onDealtByWeapon}。被过滤事件（{@code forceLayer == L9_FILTER}）**不调**
 *       ——防 DPS 桶被异常大数污染（详见 {@code 大额伤害过滤器.md} 规则 1）。</li>
 *   <li>{@link PlayerDamageStats#start / stop / clear} 联动 {@link #clearAll}。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>{@code RING / RING_BY_WEAPON} 用 {@link ConcurrentHashMap}；DpsRing 内部 synchronized。
 */
public final class PlayerDpsTracker {

    /** 滑动窗口宽度（秒）。"最近 5 秒"由用户拍板。 */
    public static final int WINDOW_SECONDS = 5;

    private static final Map<UUID, DpsRing> RING = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, DpsRing>> RING_BY_WEAPON = new ConcurrentHashMap<>();

    private PlayerDpsTracker() {}

    /** {@link PlayerDamageStats#add} 注入点：累加一次伤害事件（per-player 桶）。 */
    public static void onDealt(UUID uuid, long amount) {
        if (uuid == null || amount <= 0) return;
        RING.computeIfAbsent(uuid, k -> new DpsRing()).add(System.currentTimeMillis(), amount);
    }

    /** v8.x · 同时累加到 per-(player, weapon) 桶。weaponId 为 null/空时跳过 per-weapon 入桶。 */
    public static void onDealtByWeapon(UUID uuid, String weaponId, long amount) {
        if (uuid == null || amount <= 0) return;
        if (weaponId == null || weaponId.isEmpty()) return;
        RING_BY_WEAPON
                .computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(weaponId, k -> new DpsRing())
                .add(System.currentTimeMillis(), amount);
    }

    /** 该玩家最近 5 秒造成的伤害总量（per-player 桶）。 */
    public static long recent5sSum(UUID uuid) {
        if (uuid == null) return 0L;
        DpsRing r = RING.get(uuid);
        return r == null ? 0L : r.sum(System.currentTimeMillis());
    }

    /**
     * v8.x · per-player DPS_active：最近 5 秒累积 ÷ 这 5 秒里**非空桶**数。
     * <p>反映"出手秒手感"而不被空闲秒稀释；停打 ≥ 5 秒时返回 0。
     */
    public static long dpsActive(UUID uuid) {
        if (uuid == null) return 0L;
        DpsRing r = RING.get(uuid);
        return r == null ? 0L : r.dpsActive(System.currentTimeMillis());
    }

    /**
     * v8.x · per-(player, weapon) DPS_active。L 面板"当前手持武器 DPS"显示用。
     * <p>从未用过该武器返回 0；停打该武器 ≥ 5 秒后桶清空也返回 0。
     */
    public static long dpsActiveByWeapon(UUID uuid, String weaponId) {
        if (uuid == null || weaponId == null || weaponId.isEmpty()) return 0L;
        Map<String, DpsRing> byWeapon = RING_BY_WEAPON.get(uuid);
        if (byWeapon == null) return 0L;
        DpsRing r = byWeapon.get(weaponId);
        return r == null ? 0L : r.dpsActive(System.currentTimeMillis());
    }

    /**
     * v8.x · per-(player, weapon) 5 秒累积——主要用于 L 面板调试 / 网络包占位。
     * 通常 UI 用 {@link #dpsActiveByWeapon} 即可。
     */
    public static long recent5sSumByWeapon(UUID uuid, String weaponId) {
        if (uuid == null || weaponId == null || weaponId.isEmpty()) return 0L;
        Map<String, DpsRing> byWeapon = RING_BY_WEAPON.get(uuid);
        if (byWeapon == null) return 0L;
        DpsRing r = byWeapon.get(weaponId);
        return r == null ? 0L : r.sum(System.currentTimeMillis());
    }

    /** 清掉所有玩家 ring（start / stop / clear 联动）。 */
    public static void clearAll() {
        RING.clear();
        RING_BY_WEAPON.clear();
    }

    /** v8.x · 玩家 DISCONNECT 调：清掉该玩家所有桶。 */
    public static void evict(UUID uuid) {
        if (uuid == null) return;
        RING.remove(uuid);
        RING_BY_WEAPON.remove(uuid);
    }

    /**
     * v8.x · 1 Hz S2C 推送 / L 面板诊断用：返回该玩家所有武器的 DPS_active 快照。
     * <p>map 是当场拷贝，调用方可安全持有 / 序列化。
     */
    public static Map<String, Long> dpsActiveByWeaponSnapshot(UUID uuid) {
        if (uuid == null) return Map.of();
        Map<String, DpsRing> byWeapon = RING_BY_WEAPON.get(uuid);
        if (byWeapon == null || byWeapon.isEmpty()) return Map.of();
        long now = System.currentTimeMillis();
        Map<String, Long> out = new LinkedHashMap<>(byWeapon.size());
        for (Map.Entry<String, DpsRing> e : byWeapon.entrySet()) {
            out.put(e.getKey(), e.getValue().dpsActive(now));
        }
        return out;
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

        /**
         * v8.x · DPS_active = sum / 非空桶数。全空时返回 0。
         *
         * <p>语义示例（玩家行为 → 5 桶状态 → DPS_active）：
         * <ul>
         *   <li>持续每秒打 200 → [200, 200, 200, 200, 200] → 200</li>
         *   <li>间歇打（1 秒打 1 秒停） → [500, 0, 500, 0, 500] → 500</li>
         *   <li>短时爆发 1 秒 5000 → [5000, 0, 0, 0, 0] → 5000</li>
         *   <li>停打 ≥ 5 秒 → [0, 0, 0, 0, 0] → 0</li>
         * </ul>
         */
        synchronized long dpsActive(long nowMs) {
            long sec = nowMs / 1000L;
            rotateTo(sec);
            long s = 0;
            int active = 0;
            for (long b : buckets) {
                if (b > 0) { s += b; active++; }
            }
            return active == 0 ? 0L : s / active;
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
