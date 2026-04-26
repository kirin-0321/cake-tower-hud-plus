package com.ctt.healthdisplay.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.3.3 · 跨路径 victim 归属缓存 ── 专为聊天栏显示设计。
 *
 * <h2>为什么需要</h2>
 * <p>{@link AttackerProbe#record} （*DMG 入口）已经在 v6.3.2 停止输出聊天，
 * 因为 scoreboard 累加值常常会被 mixin 误解为"本次伤害"。
 * 但 {@link AttackerProbe#recordFromDamageShower} （DamageShower 入口）只知道
 * 名义的 {@code AllDMG} 伪类型，不知道具体是 FireDMG 还是 MeleeDMG，
 * 用户在聊天栏丢失了"伤害类型"这条关键线索。
 *
 * <h2>做法</h2>
 * <p>*DMG 入口成功归属（任何层级 L1~L8）后，把
 * {@code (objective, attacker, layer)} 写入 cache（以 victim uuid 为 key）。
 * DamageShower 入口触发时，先查 cache（TTL {@value #TTL_TICKS} tick）：
 * <ul>
 *   <li>命中 → 聊天显示 cache 的 objective（FireDMG 等）+ attacker + layer，
 *       数值用 DamageShower 自己的 {@code damage}（永远是真实单次伤害）。</li>
 *   <li>未命中 → fallback 到 AllDMG + {@code attribute()} 自身的 L7/L8/L9。</li>
 * </ul>
 *
 * <p>TTL 定得较短（0.5 s）因为每次伤害事件地图都会在若干 tick 内走完
 * *DMG → Damage → DamageShower 的完整链路，不需要长期 carry；
 * carry 太久反而会把"前一次 FireDMG 命中"误盖到"这次 MeleeDMG 命中"上。
 */
public final class VictimTypeCache {

    public static final long TTL_TICKS = 10; // 0.5s

    public record Snap(long tick, String objective, UUID attackerUuid,
                       String attackerLabel, AttackerProbe.Layer layer, String detail) {}

    private static final Map<UUID, Snap> cache = new ConcurrentHashMap<>();

    private VictimTypeCache() {}

    public static void put(UUID victim, Snap snap) {
        if (victim == null || snap == null) return;
        cache.put(victim, snap);
    }

    /** 查询；过期返回 null。 */
    public static Snap get(UUID victim, long now) {
        Snap s = cache.get(victim);
        if (s == null) return null;
        if (now - s.tick() > TTL_TICKS) return null;
        return s;
    }

    public static void gcTick(long now) {
        cache.entrySet().removeIf(e -> now - e.getValue().tick() > TTL_TICKS);
    }

    public static int size() { return cache.size(); }
}
