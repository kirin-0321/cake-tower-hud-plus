package com.ctt.healthdisplay.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * v6.5.7 · 关卡 ID → 本地化名称硬编码注册表（客户端）。
 *
 * <h2>数据来源</h2>
 * <p>Python 脚本 {@code scripts/gen_stage_name_map.py} 扫描 Cake Team Towers 地图：
 * <ul>
 *   <li>解析 {@code datapacks/.../floors/*.mcfunction} 文件名为 (type, id, slug)</li>
 *   <li>用启发式 + SLUG_OVERRIDES 把 slug 映射成 ctt_lang/zh_cn.json 的翻译键</li>
 *   <li>同时取 zh_cn / en_us 两份名字写进 {@code resources/stage_name_map.json}</li>
 * </ul>
 *
 * <p>这样设计的原因：用户明确要求"中文显示中文，英文显示英文"，但又不能直接调
 * vanilla {@code Text.translatable(key)} —— 因为：
 * <ol>
 *   <li>翻译表来自地图自带 {@code ctt_lang} 资源包，不是 mod 自己的，不能保证玩家加载</li>
 *   <li>不同地图版本翻译键可能漂移，硬编码进 mod 资源更稳定</li>
 * </ol>
 *
 * <h2>查询入口</h2>
 * <pre>
 *   StageNameRegistry.load();                          // 启动时加载一次（onInitializeClient）
 *   String name = StageNameRegistry.localizedName(
 *           StageLocation.Kind.STAGE_DUNGEON, 7);     // 根据当前语言返回 "水漫地牢" / "Downpour"
 * </pre>
 *
 * <h2>容错</h2>
 * <ul>
 *   <li>资源文件缺失 → 加载失败，所有查询返回 null（HUD 上就不显示具体名）</li>
 *   <li>(kind,id) 未在表里 → 返回 null</li>
 *   <li>当前语言无翻译 → 自动 fallback 到 en（避免显示 null）</li>
 * </ul>
 */
public final class StageNameRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-stage-names");
    private static final String RESOURCE_PATH = "/stage_name_map.json";

    /** {@link StageLocation.Kind} → JSON 的 type 字符串。 */
    private static final Map<StageLocation.Kind, String> KIND_TO_TYPE_KEY =
            new EnumMap<>(StageLocation.Kind.class);
    static {
        KIND_TO_TYPE_KEY.put(StageLocation.Kind.STAGE_DUNGEON, "dungeon");
        KIND_TO_TYPE_KEY.put(StageLocation.Kind.STAGE_BOSS,    "boss");
        KIND_TO_TYPE_KEY.put(StageLocation.Kind.STAGE_MBOSS,   "mboss");
        KIND_TO_TYPE_KEY.put(StageLocation.Kind.STAGE_SHOP,    "shop");
        KIND_TO_TYPE_KEY.put(StageLocation.Kind.STAGE_ALLY,    "ally");
        KIND_TO_TYPE_KEY.put(StageLocation.Kind.STAGE_MISC,    "misc");
    }

    /** 单条名字（en + zh_cn 都已固化）。 */
    public record StageName(String en, String zhCn) {
        /** 按当前 Minecraft 客户端语言返回最佳显示字符串；zh 缺失时 fallback 到 en。 */
        public String pick(String langCode) {
            if ("zh_cn".equalsIgnoreCase(langCode) && zhCn != null && !zhCn.isEmpty()) {
                return zhCn;
            }
            return en;
        }
    }

    // type → (id → StageName)
    private static volatile Map<String, Map<Integer, StageName>> TABLE = Collections.emptyMap();
    private static volatile boolean loaded = false;

    private StageNameRegistry() {}

    /** 启动时加载一次（{@code CttHealthDisplay#onInitializeClient}）。失败仅打日志不抛。 */
    public static synchronized void load() {
        if (loaded) return;
        Map<String, Map<Integer, StageName>> tmp = new HashMap<>();
        try (InputStream in = StageNameRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                LOGGER.warn("[stage-names] resource {} not found, stage names will be empty",
                        RESOURCE_PATH);
                loaded = true;
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                JsonElement root = JsonParser.parseReader(r);
                JsonObject obj = root.getAsJsonObject();
                JsonObject kinds = obj.getAsJsonObject("kinds");
                if (kinds == null) {
                    LOGGER.warn("[stage-names] 'kinds' missing in {}", RESOURCE_PATH);
                    loaded = true;
                    return;
                }

                int total = 0;
                for (Map.Entry<String, JsonElement> typeEntry : kinds.entrySet()) {
                    String type = typeEntry.getKey();
                    JsonObject byId = typeEntry.getValue().getAsJsonObject();
                    Map<Integer, StageName> idMap = new HashMap<>();
                    for (Map.Entry<String, JsonElement> e : byId.entrySet()) {
                        int id;
                        try {
                            id = Integer.parseInt(e.getKey());
                        } catch (NumberFormatException nfe) {
                            continue;
                        }
                        JsonObject row = e.getValue().getAsJsonObject();
                        String en = nullSafeString(row.get("en"));
                        String zh = nullSafeString(row.get("zh_cn"));
                        if (en == null && zh == null) continue;
                        idMap.put(id, new StageName(en, zh));
                        total++;
                    }
                    tmp.put(type, Collections.unmodifiableMap(idMap));
                }

                TABLE = Collections.unmodifiableMap(tmp);
                LOGGER.info("[stage-names] loaded {} stage entries across {} kinds",
                        total, TABLE.size());
            }
        } catch (Exception ex) {
            LOGGER.error("[stage-names] failed to load", ex);
        } finally {
            loaded = true;
        }
    }

    /** 直接拿 (kind,id) 对应的 StageName 记录；未找到返回 null。 */
    public static StageName lookup(StageLocation.Kind kind, int id) {
        String type = KIND_TO_TYPE_KEY.get(kind);
        if (type == null) return null;
        Map<Integer, StageName> byId = TABLE.get(type);
        if (byId == null) return null;
        return byId.get(id);
    }

    /**
     * 直接返回当前语言下的字符串。未找到返回 null（HUD 自行决定要不要省略）。
     */
    public static String localizedName(StageLocation.Kind kind, int id) {
        StageName n = lookup(kind, id);
        if (n == null) return null;
        return n.pick(currentLangCode());
    }

    /**
     * v6.6.0 hotfix · 不依赖 {@link MinecraftClient} 的重载，给服务端聊天广播
     * （{@code StageReportBroadcaster}）用。
     *
     * <p>{@code preferLang} 直接传给 {@link StageName#pick(String)}，常见取值
     * {@code "zh_cn" / "en_us"}。
     */
    public static String localizedName(StageLocation.Kind kind, int id, String preferLang) {
        StageName n = lookup(kind, id);
        if (n == null) return null;
        return n.pick(preferLang == null ? "en_us" : preferLang);
    }

    /** 当前 Minecraft 客户端语言代码（小写），如 "zh_cn" / "en_us"。 */
    private static String currentLangCode() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return "en_us";
            String code = mc.getLanguageManager().getLanguage();
            return code == null ? "en_us" : code.toLowerCase();
        } catch (Throwable t) {
            return "en_us";
        }
    }

    private static String nullSafeString(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        try {
            return e.getAsString();
        } catch (Exception ex) {
            return null;
        }
    }
}
