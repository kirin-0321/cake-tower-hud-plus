package com.ctt.healthdisplay.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * v6.4.0 · 致死一击候选缓存。
 *
 * <h2>用途</h2>
 * <p>击杀归属（方案 C · `V6_STATS_DEV_PLAN.md` 阶段 ②）的核心缓存：
 * {@link AttackerProbe#recordFromDamageShower} 每次归属成功后写入本表，登记
 * "最近一次对该 victim 的归属结果"。{@link VictimTombstone} 在 END_SERVER_TICK
 * 识别到实体真死亡时，**优先查询本表**取 killer——等价于"最后一击 = killer"。
 *
 * <h2>数据语义</h2>
 * <ul>
 *   <li>Key = victim UUID</li>
 *   <li>Val = 最后一次归属的 (killer, layer, damage, tick, objective)</li>
 *   <li>覆盖写入：同一 victim 后续的归属会覆盖前面的——正好对应"最后一击"</li>
 *   <li>{@link #attackerUuid} 可为 null：表示本 tick victim 吃过伤害但归属失败（L9）。
 *       Tombstone 仍可把它识别为"真死亡"（非清场），只是击杀者记为未分类</li>
 * </ul>
 *
 * <h2>TTL</h2>
 * <p>{@value #TTL_TICKS} tick ≈ 30 s（v6.4.1 从 5 tick 放宽）。
 * <ul>
 *   <li>Tombstone 改为 candidate-driven 扫描后，"最近一击 = killer" 由 {@link #cache}
 *       的覆盖写语义保证，不再依赖 TTL 的短小</li>
 *   <li>必须长到覆盖"被击中→真正死亡"的最坏延迟：
 *       免死救回、长战斗、地图 function 在后续 tick 才跑 {@code /kill} 等</li>
 *   <li>UUID 是 Minecraft 实体唯一 ID、不会复用，长 TTL 不会误触发上一波 candidate</li>
 * </ul>
 *
 * <h2>与 {@link VictimDamageContributors} 的区别</h2>
 * <p>本表只记"最近一次"归属（击杀用），Contributors 表累加"本场总贡献"（助攻用）。
 * 两张表目的正交，互不覆盖。
 */
public final class VictimLethalCandidate {

    private VictimLethalCandidate() {}

    /** 致死候选 TTL（tick）。v6.4.1：从 5 放宽到 600（30 s），见类 javadoc。 */
    public static final long TTL_TICKS = 600;

    /**
     * 致死一击候选。
     *
     * @param attackerUuid  归属的攻击者 UUID；null 表示本 tick 有伤害但归属失败（L9）
     * @param attackerLabel 展示用标签（如 "Player(Joey)"）
     * @param tick          伤害发生时的 server tick
     * @param layer         归属层（决定是否"已分类击杀"）
     * @param damage        本次伤害值（诊断 / 可能用于未来"最大击杀伤害" UI）
     * @param objective     伤害类型 objective 名（"MeleeDMG" / "FireDMG" / "AllDMG" ...）
     */
    public record Entry(UUID attackerUuid, String attackerLabel, long tick,
                        AttackerProbe.Layer layer, int damage, String objective) {
        public long age(long now) { return now - tick; }
    }

    private static final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    /**
     * 写入 / 覆盖一次归属结果。
     * 允许 {@code attackerUuid == null}（L9 未归属），便于 tombstone 识别"吃过伤害"。
     */
    public static void remember(UUID victimUuid, UUID attackerUuid, String attackerLabel,
                                long tick, AttackerProbe.Layer layer,
                                int damage, String objective) {
        if (victimUuid == null) return;
        cache.put(victimUuid,
                new Entry(attackerUuid, attackerLabel, tick,
                        layer, damage, objective == null ? "?" : objective));
    }

    /**
     * 查询。超出 TTL 返回 null 并顺带清除。
     */
    public static Entry lookup(UUID victimUuid, long now) {
        if (victimUuid == null) return null;
        Entry e = cache.get(victimUuid);
        if (e == null) return null;
        if (now - e.tick() > TTL_TICKS) {
            cache.remove(victimUuid);
            return null;
        }
        return e;
    }

    /** 显式清除（tombstone 确认击杀后调用，避免下一 victim 复用错 uuid）。 */
    public static void forget(UUID victimUuid) {
        if (victimUuid != null) cache.remove(victimUuid);
    }

    public static void gcTick(long now) {
        Iterator<Map.Entry<UUID, Entry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Entry> e = it.next();
            if (now - e.getValue().tick() > TTL_TICKS) it.remove();
        }
    }

    public static int size() { return cache.size(); }

    /**
     * v6.4.1 · 遍历所有在 TTL 内的候选（供 {@link VictimTombstone} candidate-driven 扫描）。
     *
     * <p>为避免遍历途中 {@link #remember} 修改导致 {@link java.util.ConcurrentModificationException}，
     * 这里先拷贝 entrySet 再回调。实现细节：
     * <ul>
     *   <li>拷贝开销：每 tick O(N)；N 通常 &lt; 20（同时被攻击的 victim 数）</li>
     *   <li>期间过期的 entry 仍会被回调一次，让 tombstone 自己判断是否处理</li>
     * </ul>
     *
     * @param action 回调，入参 (victim uuid, entry 快照)
     */
    public static void forEach(BiConsumer<UUID, Entry> action) {
        if (cache.isEmpty()) return;
        List<Map.Entry<UUID, Entry>> snapshot = new ArrayList<>(cache.entrySet());
        for (Map.Entry<UUID, Entry> e : snapshot) {
            action.accept(e.getKey(), e.getValue());
        }
    }
}
