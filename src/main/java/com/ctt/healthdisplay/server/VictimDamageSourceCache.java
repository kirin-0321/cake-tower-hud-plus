package com.ctt.healthdisplay.server;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.0.8 · victim 的"最近元素伤害源"缓存，用于 DoT carry 归属。
 *
 * <h2>背景</h2>
 * <p>火焰 / 冰霜 / 闪电 / 黑暗等元素武器常有持续掉血（DoT）。典型场景：
 * 玩家 A 点火 victim（{@code FireDMG -20}），随后 8 秒内 victim 每 0.5 秒掉一次
 * {@code FireDMG}。这些持续 tick 发生时：
 * <ul>
 *   <li>marker（如 {@code NSLaserHit}）早已被 kill → L2 miss</li>
 *   <li>RightClick 窗口只有 20 tick（1 秒）→ L3/L4 miss</li>
 *   <li>玩家 A 可能离 victim 很远 → L4b miss</li>
 *   <li>结果全部落到 L5 无归属</li>
 * </ul>
 *
 * <h2>设计</h2>
 * <p>key = {@code (victimUuid, objective)}；value = {@code (attackerUuid, tick, weaponHint)}。
 * 每次任何层归属成功且 objective 属于"可 carry 名单"（元素伤害类）时，更新缓存。
 * 当 victim 被同一 objective 命中且所有层都失败时，回退到缓存 → L4c_DOT_CARRY。
 *
 * <h2>为什么只对元素伤害 carry</h2>
 * <p>{@code BulletDMG}（AK47）这种一次性命中不该 carry，否则 A 射了一枪、B 接着打，
 * B 那一枪若 L4 失败就会被错误地算到 A 头上。元素伤害**必须**来自同一来源
 * （burn stack / frost stack 等），carry 才合理。
 *
 * <h2>TTL</h2>
 * <p>默认 200 tick = 10 秒（MC 着火持续 8 秒，留 2 秒余量）。超出此时长视为过期，
 * 后续落回 L5。
 */
public final class VictimDamageSourceCache {

    private VictimDamageSourceCache() {}

    /** 可 carry 的元素伤害类型。近战/枪械/力学 不在此列（一次性命中，不该沿用）。 */
    private static final Set<String> CARRYABLE = Set.of(
            "FireDMG",
            "WaterDMG",
            "IceDMG",
            "DarkDMG",
            "LightDMG",
            "ElectricDMG"
    );

    public static boolean isCarryable(String objective) {
        return CARRYABLE.contains(objective);
    }

    /** 单条缓存项。 */
    public record Entry(UUID attackerUuid, String attackerLabel, long tick, String weaponHint) {
        public long age(long now) { return now - tick; }
    }

    /** 复合 key：同一 victim 对不同 objective 各存一条。 */
    private record Key(UUID victimUuid, String objective) {}

    private static final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    /** DoT carry 的默认生存时长（tick）。MC 着火 8 秒 = 160 tick，留余量至 200 = 10 秒。 */
    public static final long TTL_TICKS = 200;

    /**
     * 把一次"已成功归属"的事件写入缓存。调用方应先判断 {@link #isCarryable}。
     *
     * @param weaponHint 用于日志诊断的武器简介（可选，为空亦可）
     */
    public static void remember(UUID victimUuid, String objective, UUID attackerUuid,
                                String attackerLabel, long tick, String weaponHint) {
        if (victimUuid == null || attackerUuid == null) return;
        cache.put(new Key(victimUuid, objective),
                new Entry(attackerUuid, attackerLabel, tick, weaponHint == null ? "" : weaponHint));
    }

    /**
     * 查该 victim 对该 objective 最近的已归属事件。过期或无记录返回 null。
     */
    public static Entry lookup(UUID victimUuid, String objective, long now) {
        Entry e = cache.get(new Key(victimUuid, objective));
        if (e == null) return null;
        if (now - e.tick > TTL_TICKS) {
            cache.remove(new Key(victimUuid, objective));
            return null;
        }
        return e;
    }

    /** End-of-tick 清理过期条目。O(n)，条目量应在数百内。 */
    public static void gcTick(long now) {
        Iterator<Map.Entry<Key, Entry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> mapEntry = it.next();
            if (now - mapEntry.getValue().tick > TTL_TICKS) {
                it.remove();
            }
        }
    }

    public static int size() { return cache.size(); }
}
