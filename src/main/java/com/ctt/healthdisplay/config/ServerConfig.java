package com.ctt.healthdisplay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端逻辑相关的配置。从 {@link ModConfig} 中拆出的纯服务端字段：
 * 聊天栏广播 3 开关、过滤黑名单、可疑 victim 列表、计分板数据源切换等。
 *
 * <h3>分离的动机</h3>
 * <ul>
 *   <li><b>客户端 vs 服务端职责分离</b> —— {@link ModConfig} 留作客户端 HUD 偏好
 *       （位置 / 大小 / 颜色 / 显示模式），运维向的服务端策略 / 调试开关搬到这里。</li>
 *   <li><b>专用服务器</b> 没有 GUI 配置界面，只能编辑 JSON。把它独立成
 *       {@code config/ctt-health-display-server.json} 后管理员一眼就能看清自己该改哪些。</li>
 *   <li><b>客户端 ConfigScreen</b> 仍然可以在集成单机模式下写这里 —— 同 JVM，
 *       对 {@link #INSTANCE} 的修改即时生效。专用服务器的远程客户端写这里不会同步到服务端。</li>
 * </ul>
 *
 * <h3>迁移</h3>
 * <p>首次加载时若文件不存在，会读旧的 {@code config/ctt-health-display.json} 把这些字段
 * 复制过来，然后写一份新的 {@code config/ctt-health-display-server.json}。
 * 玩家从老版本升级时不会丢配置。
 */
public class ServerConfig {

    public static ServerConfig INSTANCE = new ServerConfig();

    // ===== 聊天栏开发广播 =====
    /** 是否在聊天栏广播伤害事件 {@code [A#N] type victim dmg attacker [layer]}。默认关。 */
    public boolean broadcastDamageInChat = false;
    /** 是否在聊天栏广播击杀消息（含助攻名单）。默认关。 */
    public boolean broadcastKillsInChat = false;
    /** 是否在聊天栏广播承伤事件 {@code [承伤] Player -40}。默认关。 */
    public boolean broadcastTakenInChat = false;
    /** 承伤广播阈值：本 tick DamageTook &lt; 该值时不广播。默认 1（全部广播）。 */
    public int broadcastTakenThreshold = 1;

    // ===== 主数据源切换 =====
    /**
     * 为 <b>true</b> 时，从计分板 {@code RedHearts} 下降量取最终伤害；
     * 为 <b>false</b> 时回退 {@code DamageShower} 粒子线（旧路径）。
     */
    public boolean useRedHeartsTally = true;

    // ===== 大额数值黑名单过滤 =====
    /** 是否过滤"特定大额数值"为伪伤害。 */
    public boolean filterInitHpJumps = true;
    /**
     * 黑名单数值。来自地图侧扫描：
     * <ul>
     *   <li>1000 · 25+ 普通怪初始化 / Cauldron 过场</li>
     *   <li>9000 · 荷尔等 prop / marker 类初始化（v6.6.9 补） —
     *       例：{@code [伤害] AllDMG L4 -9000 荷尔 MARKER/Prop/pid=...}</li>
     *   <li>10000 · necro_king / fury_david / warden / race_horse 等 boss 初始化</li>
     *   <li>100000 · golden_chicken（五形态怪每形态切换）</li>
     * </ul>
     */
    public int[] initHpJumpValues = new int[]{1000, 9000, 10000, 100000};

    // ===== 关卡级黑名单（v6.6.8） =====
    /**
     * 关卡黑名单：处于这些关时不采集任何 stats（伤害 / 击杀 / 承伤）。
     *
     * <p>条目格式：{@code "stageType:stageNum"}，与 {@link com.ctt.healthdisplay.server.StageKey}
     * 第 4/5 字段对齐：
     * <ul>
     *   <li>stageType ∈ {@code boss / mboss / dungeon / shop / ally / misc}</li>
     *   <li>stageNum 是地图侧 {@code #Boss/#MBoss/#Dungeon/...} scoreboard 的具体值</li>
     * </ul>
     *
     * <p>默认屏蔽：
     * <ul>
     *   <li>{@code dungeon:47} · The Race / 赛马 — 关卡脚本会
     *       {@code scoreboard players add ... RedHearts 600} 给坐骑刷血，
     *       模组的 RedHearts 通道会把这视作 600 点的"伤害事件"，
     *       叠加马匹高频心跳还会导致刷屏 → 直接屏蔽该关</li>
     * </ul>
     *
     * <p>命中机制：{@link com.ctt.healthdisplay.server.StageBoundaryDispatcher#computeState}
     * 把该关视为"非战斗关"，三家 stats 的 {@code isCollecting} 检查直接返回 false。
     */
    public String[] blockedStages = new String[]{"dungeon:47"};

    // ===== 可疑 victim 过滤（按显示名子串 + 阈值）=====
    /** 是否过滤"特定怪物 + 单次伤害 ≥ {@link #suspectVictimDamageThreshold}"组合。 */
    public boolean filterSuspectVictims = true;
    /** 可疑 victim 显示名关键字列表（子串匹配，区分大小写）。默认两条："幽匿骷髅" / "幽匿僵尸"。 */
    public String[] suspectVictims = new String[]{"\u5e7d\u533f\u9ab7\u9ac5", "\u5e7d\u533f\u50f5\u5c38"};
    /** 仅当本次记录的伤害 &ge; 此阈值时才触发"可疑 victim"过滤。默认 800。 */
    public int suspectVictimDamageThreshold = 800;

    // ===== I/O =====
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SERVER_CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("ctt-health-display-server.json");
    private static final Path LEGACY_CLIENT_CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("ctt-health-display.json");

    public void save() {
        try (Writer writer = Files.newBufferedWriter(SERVER_CONFIG_PATH)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void load() {
        if (Files.exists(SERVER_CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(SERVER_CONFIG_PATH)) {
                ServerConfig loaded = GSON.fromJson(reader, ServerConfig.class);
                if (loaded != null) {
                    loaded.normalize();
                    INSTANCE = loaded;
                    return;
                }
            } catch (Exception e) {
                INSTANCE = new ServerConfig();
            }
            return;
        }

        // 首次加载：从老的合并 JSON 迁移已知字段，再落盘
        ServerConfig fresh = new ServerConfig();
        tryMigrateFromLegacy(fresh);
        fresh.normalize();
        INSTANCE = fresh;
        INSTANCE.save();
    }

    /**
     * 从旧 {@code config/ctt-health-display.json} 中读取 v6.5.x 时期遗留的服务端字段。
     * 找到就拷贝；找不到不报错（就用默认值）。
     */
    private static void tryMigrateFromLegacy(ServerConfig dst) {
        if (!Files.exists(LEGACY_CLIENT_CONFIG_PATH)) return;
        try (Reader reader = Files.newBufferedReader(LEGACY_CLIENT_CONFIG_PATH)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) return;
            JsonObject obj = root.getAsJsonObject();

            if (obj.has("broadcastDamageInChat"))
                dst.broadcastDamageInChat = obj.get("broadcastDamageInChat").getAsBoolean();
            if (obj.has("broadcastKillsInChat"))
                dst.broadcastKillsInChat = obj.get("broadcastKillsInChat").getAsBoolean();
            if (obj.has("broadcastTakenInChat"))
                dst.broadcastTakenInChat = obj.get("broadcastTakenInChat").getAsBoolean();
            if (obj.has("broadcastTakenThreshold"))
                dst.broadcastTakenThreshold = obj.get("broadcastTakenThreshold").getAsInt();

            if (obj.has("useRedHeartsTally"))
                dst.useRedHeartsTally = obj.get("useRedHeartsTally").getAsBoolean();

            if (obj.has("filterInitHpJumps"))
                dst.filterInitHpJumps = obj.get("filterInitHpJumps").getAsBoolean();
            if (obj.has("initHpJumpValues") && obj.get("initHpJumpValues").isJsonArray()) {
                List<Integer> tmp = new ArrayList<>();
                for (JsonElement e : obj.get("initHpJumpValues").getAsJsonArray()) {
                    try { tmp.add(e.getAsInt()); } catch (Exception ignored) {}
                }
                if (!tmp.isEmpty()) {
                    dst.initHpJumpValues = tmp.stream().mapToInt(Integer::intValue).toArray();
                }
            }

            if (obj.has("filterSuspectVictims"))
                dst.filterSuspectVictims = obj.get("filterSuspectVictims").getAsBoolean();
            if (obj.has("suspectVictims") && obj.get("suspectVictims").isJsonArray()) {
                List<String> tmp = new ArrayList<>();
                for (JsonElement e : obj.get("suspectVictims").getAsJsonArray()) {
                    try { tmp.add(e.getAsString()); } catch (Exception ignored) {}
                }
                if (!tmp.isEmpty()) {
                    dst.suspectVictims = tmp.toArray(new String[0]);
                }
            }
            if (obj.has("suspectVictimDamageThreshold"))
                dst.suspectVictimDamageThreshold = obj.get("suspectVictimDamageThreshold").getAsInt();
        } catch (Exception ignored) {
        }
    }

    /** 修补反序列化后的非法值（数组为 null 等），保证调用方读到的字段不会 NPE。 */
    private void normalize() {
        if (initHpJumpValues == null) initHpJumpValues = new int[0];
        if (suspectVictims == null) suspectVictims = new String[0];
        if (blockedStages == null) blockedStages = new String[0];
        if (broadcastTakenThreshold < 0) broadcastTakenThreshold = 0;
        if (suspectVictimDamageThreshold < 0) suspectVictimDamageThreshold = 0;
    }
}
