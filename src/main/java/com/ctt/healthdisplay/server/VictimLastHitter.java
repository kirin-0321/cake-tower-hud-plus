package com.ctt.healthdisplay.server;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.2.0 · victim × damageType → 最近一次「硬证据」归属的攻击者。
 *
 * <h2>与 {@link VictimDamageSourceCache} 的关系</h2>
 * <p>v6.0.8 的 DoT carry 只对元素伤害起作用（{@code FireDMG} / {@code IceDMG} 等）。
 * v6.2.0 把「续归属」概念泛化到 <b>所有 9 种 *DMG + AllDMG</b>：
 * <ul>
 *   <li>召唤物 / 友军 / 持续光束等"非左右键驱动"的伤害，往往 L1~L6 全 miss，
 *       但它们的"来源身份"是稳定的。用此表兜底给 L7。</li>
 *   <li>{@link VictimDamageSourceCache} 仍保留，作为元素伤害的 10s 短 TTL 专道；
 *       本类走 {@link #TTL_TICKS} = 400t (20s) 长 TTL。两个缓存互不冲突，
 *       L7 同时查询，取最新者（或 L7 专职查本类，保留原 L4c 概念由本类兼并）。</li>
 * </ul>
 *
 * <h2>写入约束：只写硬证据</h2>
 * <p>写入入口要求调用方自行判断"硬/软层"。建议写入条件：
 * {@code layer ∈ {L2_STAT_TICK, L3_MARKER_NEAR, L4_MARKER_FAR, L5_STAT_WINDOW}} —— 这些都有物理证据。
 * 软层（L1_WEAPON_MATCH 本身就是"持武器+开火"推断、L6_FIRE_WINDOW 是右键+距离推断、L8_SUMMON_FALLBACK 更弱）
 * 不应写入 —— 否则会产生"猜 → 记 → 下次更稳地猜同一个人"的自增强循环。
 *
 * <h2>TTL</h2>
 * <p>默认 400 tick = 20s。比 {@link VictimDamageSourceCache} 的 10s 更长，
 * 覆盖召唤物"间歇性反复攻击同一目标"的场景（4~10s 出手间隔属正常）。
 */
public final class VictimLastHitter {

    private VictimLastHitter() {}

    /** 续归属生存时长（tick）。 */
    public static final long TTL_TICKS = 400;

    public record Entry(UUID attackerUuid, String attackerLabel, long tick, String weaponHint) {
        public long age(long now) { return now - tick; }
    }

    private record Key(UUID victimUuid, String damageType) {}

    private static final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    /**
     * 记录一次「硬证据」归属。软层不应调用本方法。
     *
     * @param damageType objective 名（BulletDMG / MeleeDMG / FireDMG / ... / AllDMG）
     * @param weaponHint 诊断用武器简介，可空
     */
    public static void remember(UUID victimUuid, String damageType, UUID attackerUuid,
                                String attackerLabel, long tick, String weaponHint) {
        if (victimUuid == null || attackerUuid == null || damageType == null) return;
        cache.put(new Key(victimUuid, damageType),
                new Entry(attackerUuid, attackerLabel, tick, weaponHint == null ? "" : weaponHint));
    }

    /** 查该 victim × damageType 最近归属。过期则清除并返回 null。 */
    public static Entry lookup(UUID victimUuid, String damageType, long now) {
        if (victimUuid == null || damageType == null) return null;
        Key key = new Key(victimUuid, damageType);
        Entry e = cache.get(key);
        if (e == null) return null;
        if (now - e.tick() > TTL_TICKS) {
            cache.remove(key);
            return null;
        }
        return e;
    }

    public static void gcTick(long now) {
        Iterator<Map.Entry<Key, Entry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> mapEntry = it.next();
            if (now - mapEntry.getValue().tick() > TTL_TICKS) {
                it.remove();
            }
        }
    }

    public static int size() { return cache.size(); }
}
