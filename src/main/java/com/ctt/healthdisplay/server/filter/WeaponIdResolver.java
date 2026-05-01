package com.ctt.healthdisplay.server.filter;

import com.ctt.healthdisplay.server.PlayerInventoryIndex;

import java.util.Set;
import java.util.UUID;

/**
 * v8.x · 主手武器标识解析器（per-(player, weapon) P95 / DPS 桶用 key）。
 *
 * <h2>职责</h2>
 * <p>把"玩家当前主手武器"映射到一个**稳定的字符串 ID**，用于
 * {@link PerPlayerWeaponP95Registry} 与 {@link com.ctt.healthdisplay.server.PlayerDpsTracker}
 * 的 per-weapon 桶查询。
 *
 * <h2>解析优先级</h2>
 * <ol>
 *   <li><b>CTT custom_data key</b>（最稳定）—— 主手 {@code minecraft:custom_data}
 *       NBT 里第一条非空 key（按 {@link PlayerInventoryIndex} 抽取顺序）。
 *       例：{@code "nutStickLaser" / "ak47" / "darkSword"}。</li>
 *   <li><b>vanilla item id</b>（fallback）—— 主手物品的 registry id，
 *       例：{@code "minecraft:diamond_sword" / "minecraft:bow"}。</li>
 *   <li><b>"empty"</b>（兜底）—— 玩家空手或无法解析。</li>
 * </ol>
 *
 * <p>同一把"经济意义上的武器"在玩家手里始终落到同一个 weaponId——附魔 / 耐久 / 名字
 * 不影响——这正是 P95 桶想要的颗粒度（<i>"长剑" 一桶</i>，而不是 <i>"长剑+5"和"长剑+6"
 * 各一桶</i>）。
 *
 * <p>{@code custom_data} key 优先于 vanilla id 的原因：CTT 地图大量使用同一 vanilla item
 * （如 {@code minecraft:carrot_on_a_stick}）作为不同 CTT 武器的载体，靠 NBT key 区分。
 * 优先用 CTT key 才能正确分桶；vanilla id fallback 只在物品没附 custom_data 时启用。
 */
public final class WeaponIdResolver {

    /** 玩家空手 / 无法解析时使用的统一标识。 */
    public static final String EMPTY = "empty";

    private WeaponIdResolver() {}

    /**
     * 从已抓取的 {@link PlayerInventoryIndex.Snapshot} 解析武器 ID。
     *
     * <p>是 server tick 流水线的常用入口——{@link com.ctt.healthdisplay.server.DamageProbe}
     * 把 enqueue 时的 snapshot 一起塞进 {@link PendingDamageBuffer.Entry}，
     * 之后 buffer.flush 时直接从 entry 读取，不再二次访问 InventoryIndex。
     */
    public static String resolveFromSnapshot(PlayerInventoryIndex.Snapshot snap) {
        if (snap == null || snap == PlayerInventoryIndex.Snapshot.EMPTY) return EMPTY;
        Set<String> mainHand = snap.mainHand();
        if (mainHand != null && !mainHand.isEmpty()) {
            // mainHand 是 Set，无序——用第一条非空字符串。Set 实现是 LinkedHashSet 所以稳定，
            // 但即便不稳定，多 key 时多次解析落到不同桶最多让 P95 颗粒变细，不影响正确性。
            for (String k : mainHand) {
                if (k != null && !k.isEmpty()) return k;
            }
        }
        String vanilla = snap.mainHandItemId();
        if (vanilla != null && !vanilla.isEmpty()) return vanilla;
        return EMPTY;
    }

    /**
     * 在线玩家的主手武器解析（直接查 {@link PlayerInventoryIndex}）。
     *
     * <p>用于 L 面板渲染、buffer.enqueue 等"实时查询当前主手武器"场景。
     * <p>{@link PlayerInventoryIndex} 在 server tick 中以 1 Hz~5 Hz 频率刷新，
     * 因此本方法返回的 weaponId 与玩家真实主手有 ≤ 200 ms 的延迟——对 P95 / DPS
     * 桶分类来说完全可接受（buffer 自身的 delay 就有 2 tick = 100 ms 量级）。
     */
    public static String resolveCurrent(UUID playerUuid) {
        if (playerUuid == null) return EMPTY;
        PlayerInventoryIndex.Snapshot snap = PlayerInventoryIndex.get(playerUuid);
        return resolveFromSnapshot(snap);
    }
}
