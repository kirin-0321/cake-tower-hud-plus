package com.ctt.healthdisplay.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.4.0 · 单 victim 生命周期内的"玩家伤害贡献表"（助攻统计数据源）。
 *
 * <h2>用途</h2>
 * <p>服务阶段 ② 的助攻规则（`V6_STATS_DEV_PLAN.md` · 用户 2026-04-25 拍板）：
 * 致死一击 = killer，其余对该 victim 造成过**已归属**伤害的玩家 = 助攻者。
 *
 * <h2>写入规则</h2>
 * <ul>
 *   <li>只有 {@link AttackerProbe#isAttributionClassified 已分类层}（L1~L6 + L7b）
 *       的伤害才进本表。L7 / L8 / L8b / L9 （未分类桶）不写——保持和
 *       {@link PlayerDamageStats} 的"未分类不计入玩家账户"策略一致。</li>
 *   <li>每个 victim 维护一张 {@code Map<UUID player, Contrib>}，value 累加该
 *       玩家对该 victim 的总伤害 + 事件数 + 最后更新 tick。</li>
 *   <li>同一玩家多次伤害同一 victim → merge 累加。</li>
 * </ul>
 *
 * <h2>读取规则</h2>
 * <p>{@link #getContributors(UUID)} 返回按伤害贡献降序排列的列表，供
 * {@link VictimTombstone} 结算 killer + assist 时使用。调用方负责去除 killer 自身。
 *
 * <h2>清理</h2>
 * <ul>
 *   <li>{@link #forget(UUID)}：确认击杀 / 明确不会再死后，显式清理。</li>
 *   <li>{@link #gcTick(long)}：兜底清 {@value #STALE_TTL_TICKS} tick（30 s）未被更新
 *       的 victim，防止长时间"打残但没死"的怪物持续占用内存。</li>
 * </ul>
 *
 * <h2>为什么不用 {@link PlayerDamageStats}？</h2>
 * <p>PlayerDamageStats 是"整个 session 按玩家累加"，丢了 victim 维度；这里恰
 * 恰要按 victim 维度查"本怪被谁打过"——两张表正交。
 */
public final class VictimDamageContributors {

    private VictimDamageContributors() {}

    /** 助攻贡献表兜底存活时长：30 s。 */
    public static final long STALE_TTL_TICKS = 600;

    /** 单条贡献（单玩家对单 victim 的累计）。 */
    public static final class Contrib {
        public final UUID playerUuid;
        public volatile String playerName;
        public long totalDamage;
        public int eventCount;
        public long lastTick;

        Contrib(UUID uuid, String name, int damage, long tick) {
            this.playerUuid = uuid;
            this.playerName = name == null ? "?" : name;
            this.totalDamage = damage;
            this.eventCount = 1;
            this.lastTick = tick;
        }

        void merge(String name, int damage, long tick) {
            if (name != null && !name.isEmpty()) this.playerName = name;
            this.totalDamage += damage;
            this.eventCount++;
            if (tick > this.lastTick) this.lastTick = tick;
        }
    }

    /** 不可变快照行（供 {@link VictimTombstone} 读取）。 */
    public record ContribRow(UUID playerUuid, String playerName,
                             long totalDamage, int eventCount, long lastTick) {}

    /** Map<victim UUID, Map<player UUID, Contrib>>. */
    private static final Map<UUID, Map<UUID, Contrib>> table = new ConcurrentHashMap<>();
    /** 最后被写入的 tick（gc 用）。 */
    private static final Map<UUID, Long> lastTouch = new ConcurrentHashMap<>();

    /**
     * 登记一次伤害贡献。
     *
     * @param classified 来自 {@link AttackerProbe#isAttributionClassified}。
     *                   false 时直接丢弃（未分类不进助攻）。
     */
    public static void addContribution(UUID victimUuid, UUID playerUuid, String playerName,
                                       int damage, long tick, boolean classified) {
        if (!classified || victimUuid == null || playerUuid == null || damage <= 0) return;
        Map<UUID, Contrib> perVictim = table.computeIfAbsent(victimUuid, u -> new ConcurrentHashMap<>());
        Contrib existing = perVictim.get(playerUuid);
        if (existing == null) {
            perVictim.putIfAbsent(playerUuid, new Contrib(playerUuid, playerName, damage, tick));
        } else {
            // 简化：同 player 的 merge 用同步块避免 race（table 本身 concurrent，Contrib 字段不是 atomic）
            synchronized (existing) {
                existing.merge(playerName, damage, tick);
            }
        }
        lastTouch.put(victimUuid, tick);
    }

    /** 返回按 damage 降序排列的贡献列表。没有贡献返回空。 */
    public static List<ContribRow> getContributors(UUID victimUuid) {
        if (victimUuid == null) return List.of();
        Map<UUID, Contrib> perVictim = table.get(victimUuid);
        if (perVictim == null || perVictim.isEmpty()) return List.of();
        List<ContribRow> out = new ArrayList<>(perVictim.size());
        for (Contrib c : perVictim.values()) {
            synchronized (c) {
                out.add(new ContribRow(c.playerUuid, c.playerName, c.totalDamage, c.eventCount, c.lastTick));
            }
        }
        out.sort(Comparator.comparingLong(ContribRow::totalDamage).reversed());
        return out;
    }

    /** 显式清理单 victim（击杀结算后调用）。 */
    public static void forget(UUID victimUuid) {
        if (victimUuid == null) return;
        table.remove(victimUuid);
        lastTouch.remove(victimUuid);
    }

    /** 清空整个表（PlayerKillStats.clear / start 时调）。 */
    public static void clearAll() {
        table.clear();
        lastTouch.clear();
    }

    /** 清理超过 30 s 未更新的 victim 条目。 */
    public static void gcTick(long now) {
        long cutoff = now - STALE_TTL_TICKS;
        Iterator<Map.Entry<UUID, Long>> it = lastTouch.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() < cutoff) {
                table.remove(e.getKey());
                it.remove();
            }
        }
    }

    public static int victimCount() { return table.size(); }
}
