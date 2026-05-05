package com.ctt.healthdisplay.client;

import com.ctt.healthdisplay.health.MobHealthData;
import com.ctt.healthdisplay.network.MobHealthPayload;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v8.3.0 · M7 · 客户端怪物血量缓存（服务端权威）。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>接 {@link MobHealthPayload}，把 {@link MobHealthPayload.Entry} 列表转成
 *       {@link MobHealthData} 的 {@code UUID → data} 只读 snapshot；</li>
 *   <li>提供 {@link #isFresh()}：最近一次推送不超过 {@value #FRESH_WINDOW_MS} ms
 *       视作新鲜，驱动 {@link com.ctt.healthdisplay.health.HealthData#getMobHealthMap()}
 *       的顶部分叉；</li>
 *   <li>提供 {@link #viewSnapshot()}：渲染侧直接读的只读 map；</li>
 *   <li>{@link #reset()} 给 DISCONNECT 钩子清缓存，避免切服后看到上一局残留条。</li>
 * </ul>
 *
 * <h2>v8.3.6 · v2 协议适配</h2>
 * <ul>
 *   <li>{@code nameText} 直接拿来用——translate 键留给客户端 lang 渲染；</li>
 *   <li>{@code defence / armorType / armorValue} 按 datapack
 *       {@code misc/bossbars/targetting_enemy.mcfunction} 的 8 档模板（armorType 0..7）
 *       重建带粗体 + 颜色的 suffix Text，UI 渲染端继续读 {@code data.suffixText}。</li>
 * </ul>
 *
 * <h2>服务端未装 mod 的兼容路径</h2>
 * <p>从未收到 payload → {@code lastPushMs = 0} → {@code isFresh() = false} →
 * {@link com.ctt.healthdisplay.health.HealthData#getMobHealthMap()} 继续返回本地
 * {@code mobHealthMap}；{@link com.ctt.healthdisplay.CttHealthDisplay#updateMobTracking}
 * 正常扫 bossbar 写入本地 map。行为与 v8.2 一致。
 *
 * <h2>服务端中途下线的失鲜回落</h2>
 * <p>5 s 没收到包 → {@code isFresh()} 翻 false → HUD 同一时刻切回本地路径，
 * 下一 tick {@link com.ctt.healthdisplay.CttHealthDisplay#updateMobTracking} 重新
 * 填本地 map。最坏情况是 1-2 tick (50-100 ms) 头顶条闪一下 —— 在用户视角是
 * "服务端刚下线这一下"的自然过渡，可接受。
 */
public final class ClientMobHealthCache {

    /** 新鲜窗口：5 秒没收到 payload → 视作服务端失联，回落本地路径。 */
    private static final long FRESH_WINDOW_MS = 5_000L;

    private static volatile long lastPushMs = 0L;
    private static volatile Map<UUID, MobHealthData> snapshot = Collections.emptyMap();

    private ClientMobHealthCache() {}

    public static void onPayload(MobHealthPayload payload) {
        if (payload == null) return;
        Map<UUID, MobHealthData> map = new HashMap<>(Math.max(16, payload.entries().size() * 2));
        for (MobHealthPayload.Entry e : payload.entries()) {
            Text nameText = e.nameText() != null ? e.nameText() : Text.empty();
            Text suffixText = buildArmorSuffix(e.defence(), e.armorType(), e.armorValue());
            // lastUpdateTick 留 0：fresh 路径的 map 由 CttHealthDisplay.updateMobTracking
            // 的 stale-cleanup 完全绕过，这里填 0 也不会被当成陈旧条项错删。
            MobHealthData d = new MobHealthData(nameText, suffixText, e.hp(), e.maxHp(), 0L);
            d.targetted = e.targetted();
            map.put(e.uuid(), d);
        }
        snapshot = Collections.unmodifiableMap(map);
        lastPushMs = System.currentTimeMillis();
    }

    /**
     * 按 datapack {@code targetting_enemy.mcfunction} 第 6-13 行的 8 档模板还原 suffix Text。
     *
     * <p>固定结构：{@code 🛡<defence>}（蓝色）+ 可选 {@code  <icon><value>}（按 armorType 选色）。
     * defence 与 armorType 同时为 0 → 返回空 Text，渲染层不会画前导护甲段（避免在
     * 没有数据的怪头顶画一个 "🛡 0" 误导）。
     *
     * <h3>颜色映射（与 datapack 一致）</h3>
     * <ul>
     *   <li>1 = 🔥 FireArmor → {@link Formatting#GOLD}（gold）</li>
     *   <li>2 = ☃ IceArmor  → {@link Formatting#AQUA}（aqua）</li>
     *   <li>3 = ☠ DarkArmor → {@link Formatting#DARK_PURPLE}（dark_purple）</li>
     *   <li>4 = ☀ LightArmor → {@link Formatting#WHITE}（white）</li>
     *   <li>5 = 🛡 TrueArmor → {@link Formatting#WHITE}（white；与首段蓝 🛡 同图标但白色）</li>
     *   <li>6 = ⚓ WaterArmor → {@link Formatting#AQUA}（aqua）</li>
     *   <li>7 = ⚡ ElectricArmor → {@link Formatting#YELLOW}（yellow）</li>
     * </ul>
     */
    public static Text buildArmorSuffix(int defence, int armorType, int armorValue) {
        if (defence == 0 && armorType == 0) return Text.empty();
        MutableText t = Text.empty();
        // 首段：🛡 Defence （蓝），datapack 在 ArmorDisplay 任意档（含 0）都恒输出
        appendIconValue(t, "\uD83D\uDEE1", defence, Formatting.BLUE);
        switch (armorType) {
            case 1 -> appendIconValue(t, " \uD83D\uDD25", armorValue, Formatting.GOLD);          // 🔥 FireArmor
            case 2 -> appendIconValue(t, " \u2603",       armorValue, Formatting.AQUA);          // ☃ IceArmor
            case 3 -> appendIconValue(t, " \u2620",       armorValue, Formatting.DARK_PURPLE);   // ☠ DarkArmor
            case 4 -> appendIconValue(t, " \u2600",       armorValue, Formatting.WHITE);         // ☀ LightArmor
            case 5 -> appendIconValue(t, " \uD83D\uDEE1", armorValue, Formatting.WHITE);         // 🛡(白) TrueArmor
            case 6 -> appendIconValue(t, " \u2693",       armorValue, Formatting.AQUA);          // ⚓ WaterArmor
            case 7 -> appendIconValue(t, " \u26A1",       armorValue, Formatting.YELLOW);        // ⚡ ElectricArmor
            default -> {}
        }
        return t;
    }

    private static void appendIconValue(MutableText root, String icon, int value, Formatting color) {
        root.append(Text.literal(icon).formatted(Formatting.BOLD, color))
            .append(Text.literal(String.valueOf(value)).formatted(Formatting.BOLD, color));
    }

    public static boolean isFresh() {
        long last = lastPushMs;
        if (last == 0L) return false;
        return (System.currentTimeMillis() - last) < FRESH_WINDOW_MS;
    }

    /** 只读 snapshot — 渲染侧直接用，不要修改。 */
    public static Map<UUID, MobHealthData> viewSnapshot() {
        return snapshot;
    }

    /** DISCONNECT / reset 时清缓存，避免切服后看到上一局残留。 */
    public static void reset() {
        snapshot = Collections.emptyMap();
        lastPushMs = 0L;
    }
}
