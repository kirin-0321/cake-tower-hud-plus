package com.ctt.healthdisplay.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * v6.2.0 · 武器-伤害类型注册表（从 resources/weapon_damage_seed.json 加载）。
 *
 * <h2>数据来源</h2>
 * <p>Python 脚本 {@code scripts/gen_weapon_damage_map.py} 扫描 Cake Team Towers
 * 地图数据包自动生成 {@code weapon_damage_seed.json}：
 * <ul>
 *   <li>识别 601 把带 {@code SelectedItem.custom_data.<KEY>:1b} 签名的物品</li>
 *   <li>抽取每把武器能造成的 {@code *DMG} 类型集合</li>
 *   <li>按"是否 summon 生物"区分 {@code kind = weapon / summon}</li>
 * </ul>
 *
 * <h2>查询模式</h2>
 * <ul>
 *   <li>{@link #getInfo(String)} —— 按 custom_data key 查武器元信息</li>
 *   <li>{@link #canProduce(String, String)} —— 某武器能否造 T 类型伤害</li>
 *   <li>{@link #weaponsOfType(String)} —— 反向：能造 T 类型伤害的所有武器 key 集合</li>
 * </ul>
 *
 * <h2>召唤物特例</h2>
 * <p>部分召唤物（如 skullTome）的 {@code damageTypes} 为空（脚本调用链未深入生物 AI）。
 * 这类武器 <b>不会</b> 被 {@link #weaponsOfType} 返回 —— 其兜底由 AttackerProbe
 * 的 L8_SUMMON_FALLBACK 层负责（唯一召唤物持有者）。
 */
public final class WeaponDamageRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-weapon-registry");

    /** 资源路径（classpath）。 */
    private static final String RESOURCE_PATH = "/weapon_damage_seed.json";

    /**
     * v6.3.4 · vanilla 物品 → 伤害类型硬映射。
     *
     * <p>{@code gen_weapon_damage_map.py} 只能静态分析 mcfunction 里直接写入的 {@code *DMG}，
     * 无法追踪 vanilla 弓/弩/三叉戟这种"箭矢实体飞行后命中才触发 BulletDMG"的延迟链。
     * 这份表就是给它们兜底——玩家主手持有这些物品时，守卫判定直接认定为"能造此伤害"。
     *
     * <p>只处理 vanilla 的远程/投掷型武器；自定义武器仍走 seed.json。
     */
    private static final Map<String, Set<String>> VANILLA_ITEM_DMG = Map.of(
            "minecraft:bow",      Set.of("BulletDMG"),
            "minecraft:crossbow", Set.of("BulletDMG"),
            "minecraft:trident",  Set.of("BulletDMG", "MeleeDMG")
    );

    /** custom_data key 类别。 */
    public enum Kind {
        WEAPON,  // 只看主手
        SUMMON;  // 看主手 + 9 快捷栏 + 27 主背包

        public static Kind parse(String s) {
            if (s == null) return WEAPON;
            return "summon".equalsIgnoreCase(s) ? SUMMON : WEAPON;
        }
    }

    /** 单条武器记录（不可变）。 */
    public record WeaponInfo(String customDataKey,
                             String itemId,
                             Kind kind,
                             Set<String> damageTypes,
                             String fileRel) {}

    // custom_data key → WeaponInfo
    private static volatile Map<String, WeaponInfo> byKey = Collections.emptyMap();
    // DMG type → set of custom_data keys
    private static volatile Map<String, Set<String>> byDamageType = Collections.emptyMap();
    // 所有 summon kind 的 key 集合（供 L8 兜底扫描用）
    private static volatile Set<String> allSummonKeys = Collections.emptySet();

    private static volatile boolean loaded = false;

    private WeaponDamageRegistry() {}

    /** 启动时加载一次。失败仅打日志不抛异常 —— 所有查询会返回空集，L1/L8 变 no-op。 */
    public static synchronized void load() {
        if (loaded) return;
        Map<String, WeaponInfo> keyMap = new HashMap<>();
        Map<String, Set<String>> typeMap = new HashMap<>();
        Set<String> summonSet = new HashSet<>();

        try (InputStream in = WeaponDamageRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                LOGGER.warn("[weapon-registry] resource {} not found, L1/L7/L8 will be no-op",
                        RESOURCE_PATH);
                loaded = true;
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                JsonElement root = JsonParser.parseReader(r);
                JsonObject obj = root.getAsJsonObject();
                JsonObject weapons = obj.getAsJsonObject("weapons");
                if (weapons == null) {
                    LOGGER.warn("[weapon-registry] weapons object missing");
                    loaded = true;
                    return;
                }

                for (Map.Entry<String, JsonElement> e : weapons.entrySet()) {
                    String key = e.getKey();
                    JsonObject w = e.getValue().getAsJsonObject();

                    String itemId = nullSafeString(w.get("itemId"));
                    String kindRaw = nullSafeString(w.get("kind"));
                    String fileRel = nullSafeString(w.get("file"));
                    Kind kind = Kind.parse(kindRaw);

                    JsonArray dmgArr = w.getAsJsonArray("damageTypes");
                    Set<String> dmgs = new HashSet<>();
                    if (dmgArr != null) {
                        for (JsonElement d : dmgArr) {
                            String s = d.getAsString();
                            if (s != null && !s.isEmpty()) dmgs.add(s);
                        }
                    }
                    WeaponInfo info = new WeaponInfo(key, itemId, kind,
                            Collections.unmodifiableSet(dmgs), fileRel);
                    keyMap.put(key, info);

                    for (String d : dmgs) {
                        typeMap.computeIfAbsent(d, k -> new HashSet<>()).add(key);
                    }
                    if (kind == Kind.SUMMON) summonSet.add(key);
                }
            }

            byKey = Collections.unmodifiableMap(keyMap);
            byDamageType = freezeInnerSets(typeMap);
            allSummonKeys = Collections.unmodifiableSet(summonSet);

            LOGGER.info("[weapon-registry] loaded {} weapons ({} summon-kind, {} damage-type indexes)",
                    byKey.size(), allSummonKeys.size(), byDamageType.size());
        } catch (Exception ex) {
            LOGGER.error("[weapon-registry] failed to load", ex);
        } finally {
            loaded = true;
        }
    }

    private static Map<String, Set<String>> freezeInnerSets(Map<String, Set<String>> src) {
        Map<String, Set<String>> out = new HashMap<>(src.size() * 2);
        for (Map.Entry<String, Set<String>> e : src.entrySet()) {
            out.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static String nullSafeString(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }

    public static WeaponInfo getInfo(String customDataKey) {
        return customDataKey == null ? null : byKey.get(customDataKey);
    }

    /** 该武器是否能造成 T 类型伤害（如果未注册或 damageTypes 为空 → false）。 */
    public static boolean canProduce(String customDataKey, String damageType) {
        WeaponInfo info = getInfo(customDataKey);
        return info != null && info.damageTypes().contains(damageType);
    }

    /** 能造 T 类型伤害的所有武器 custom_data key 集合（空集表示"注册表里没有能造此伤害的武器"）。 */
    public static Set<String> weaponsOfType(String damageType) {
        Set<String> s = byDamageType.get(damageType);
        return s == null ? Collections.emptySet() : s;
    }

    /**
     * v6.3.4 · vanilla 武器是否能造 T 类型伤害。
     * <p>自定义武器请用 {@link #canProduce(String, String)}；此 API 走 {@link #VANILLA_ITEM_DMG} 硬表。
     *
     * @param itemId  vanilla item ID（形如 {@code "minecraft:bow"}）
     */
    public static boolean canVanillaProduce(String itemId, String damageType) {
        if (itemId == null || damageType == null) return false;
        Set<String> s = VANILLA_ITEM_DMG.get(itemId);
        return s != null && s.contains(damageType);
    }

    /** v6.3.4 · 能造 T 类型伤害的 vanilla item ID 集合（不走 seed）。供 L1 候选预检用。 */
    public static Set<String> vanillaItemsProducing(String damageType) {
        if (damageType == null) return Collections.emptySet();
        Set<String> out = null;
        for (Map.Entry<String, Set<String>> e : VANILLA_ITEM_DMG.entrySet()) {
            if (e.getValue().contains(damageType)) {
                if (out == null) out = new HashSet<>(2);
                out.add(e.getKey());
            }
        }
        return out == null ? Collections.emptySet() : Collections.unmodifiableSet(out);
    }

    /** 所有 summon kind 的 key 集合（供 L8_SUMMON_FALLBACK 扫描）。 */
    public static Set<String> allSummonKeys() {
        return allSummonKeys;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static int weaponCount() {
        return byKey.size();
    }
}
