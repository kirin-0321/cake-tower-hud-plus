package com.ctt.healthdisplay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {

    public static ModConfig INSTANCE = new ModConfig();

    public static final int MOB_HP_MODE_ALL = 0;
    public static final int MOB_HP_MODE_NEAREST = 1;
    public static final int MOB_HP_MODE_OFF = 2;

    public float teammateX = 0.01f;
    public float teammateY = 0.33f;
    public boolean horizontalLayout = false;
    public float statsX = 0.005f;
    public float statsY = 0.005f;
    public boolean autoRefreshStats = true;
    public int autoRefreshIntervalSeconds = 30;
    public boolean showTeammateHeadHP = true;
    // 旧版字段，仅用于读旧 JSON 做迁移；新代码不直接读它，而是读 mobHeadHPMode。
    public boolean showMobHeadHP = true;
    // 新三档开关：0=ALL 全部显示；1=NEAREST 仅最近（bossbar 锁定的那只）；2=OFF 关。
    public int mobHeadHPMode = MOB_HP_MODE_ALL;
    public int mobBarHalfWidth = 60;
    public int teammateBarHalfWidth = 40;
    public int statsColumns = 2;
    public int statsVisibility = 0;
    public boolean hidePersonalBar = true;
    public boolean hideTeamBar = true;
    public boolean hideMobBars = false;

    // ===== v6.3.0 · 伤害分配面板（测试版）=====
    /**
     * 是否在 HUD 上持续渲染面板（非交互模式的只读展示）。关闭后仅按 L 弹 Screen 时可见。
     * <p>v6.5.30 起默认 false：面板属于实验性功能且开发者向，新装用户不应默认看到。
     */
    public boolean damagePanelHudVisible = false;
    /** 面板左上角屏幕坐标百分比（0~1）。默认左上 1%/20%，便于和左侧血条避让。 */
    public float damagePanelX = 0.005f;
    public float damagePanelY = 0.20f;
    /** 详情展开：否则紧凑行；Screen 模式下可通过按钮切换。 */
    public boolean damagePanelDetailed = true;

    // ===== v6.6.0 · M1 关卡分桶切片 =====
    /**
     * L 键面板的数据切片范围。
     * <ul>
     *   <li>0 = SESSION：整局累计（既有行为，session 总）</li>
     *   <li>1 = CURRENT_STAGE：仅当前关累计（{@link com.ctt.healthdisplay.server.PlayerDamageStats#snapshotOf(com.ctt.healthdisplay.server.StageKey)}）</li>
     * </ul>
     * 由 [C] / [S] 按钮在 Screen 模式下切换。
     */
    public int damagePanelStageScope = 0;
    public static final int STAGE_SCOPE_SESSION = 0;
    public static final int STAGE_SCOPE_CURRENT = 1;

    // ===== v6.6.0 · M3 嵌入式 HUD（队友面板伤害/承伤/击杀紧凑双行）=====
    /**
     * 嵌入式 HUD 模式（设计 §5.4 / §9）。控制队友面板第二/三/四行的显示：
     * <ul>
     *   <li>0 = OFF：完全关掉，回到 v5.3.3 单行</li>
     *   <li>1 = ONLY_STAGE：只显示"关:"行（当前/上一关分桶切片）（默认）</li>
     *   <li>2 = ONLY_SESSION：只显示"局:"行（整局累计）</li>
     *   <li>3 = BOTH：两行都显示</li>
     * </ul>
     * 在大厅 / Game Over kind 下整组 stats 行强制隐藏（设计 §5.6）。
     */
    public int embeddedHudMode = EMBED_ONLY_STAGE;
    public static final int EMBED_OFF          = 0;
    public static final int EMBED_ONLY_STAGE   = 1;
    public static final int EMBED_ONLY_SESSION = 2;
    public static final int EMBED_BOTH         = 3;

    // ===== v6.7.x · P0 客户端伤害探针（CLIENT_SIDE_STATS_PROPOSAL §X）=====
    /**
     * 客户端 DamageShower 粒子探针——是否把每条增量观测以 {@code [CDP] tick=N +D ...}
     * 形式打到本地聊天。仅本地可见（不走 networking），调试用。默认关闭以避免刷屏。
     *
     * <p>详见 {@link com.ctt.healthdisplay.client.ClientDamageProbe}。
     */
    public boolean clientDamageDebugChat = false;

    /**
     * 是否在队友 HUD 顶部绘制"客户端可见伤害"聚合行：
     * {@code ⚔ <本层> ☠ <本层击杀> ⚡ <5sDPS>/s}。仅当 {@link com.ctt.healthdisplay.client.ClientDamageProbe#hasAnyData()}
     * 为 true 时生效——避免空 HUD 占位。默认开启。
     */
    public boolean clientDamageHudHeader = true;

    /**
     * v7.1.0 · 客户端击杀报告聊天日志。开启后每次检测到死亡 → 本地聊天打 {@code [CDP/KILL] tick=N ☠ <名> uuid=...}。
     * 与 {@link #clientDamageDebugChat}（粒子流水）独立，两个开关互不影响。默认关闭以避免刷屏。
     *
     * <p>v7.1.0 · 客户端击杀计数本身始终开启（无配置开关）——开销低（每 tick 一次活体扫描），
     * 关闭只会让 HUD/K 表击杀段数字恒为 0，体验上反而更乱。本字段只控制"是否往聊天打日志"。
     */
    public boolean clientKillDebugChat = false;

    // ===== v6.6.4 · M5 · 服务端字段已迁出 =====
    // 以下字段从 v6.6.4 起搬到 {@link ServerConfig}（独立 JSON 文件
    // {@code config/ctt-health-display-server.json}），本类不再持有：
    //   - broadcastDamageInChat / broadcastKillsInChat / broadcastTakenInChat / broadcastTakenThreshold
    //   - useRedHeartsTally
    //   - filterInitHpJumps / initHpJumpValues
    //   - filterSuspectVictims / suspectVictims / suspectVictimDamageThreshold
    // 老用户的 ctt-health-display.json 里若仍残留这些 key，由 ServerConfig.load()
    // 在首次启动时迁移到新文件，旧 key 在反序列化时被 GSON 静默丢弃，不影响行为。

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("ctt-health-display.json");

    public void save() {
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
                if (loaded != null) {
                    loaded.migrate();
                    INSTANCE = loaded;
                }
            } catch (Exception e) {
                INSTANCE = new ModConfig();
            }
        }
    }

    /**
     * 兼容 v5.2.0 以前只有 boolean `showMobHeadHP` 的旧 config：
     * 旧 JSON 里 `showMobHeadHP=false` 而新字段 `mobHeadHPMode` 仍是默认值 0（ALL）时，
     * 翻译为 {@code MOB_HP_MODE_OFF}，保留用户"关闭"的意图。
     * 旧 JSON 里 `showMobHeadHP=true` 保持默认 ALL，不动。
     */
    private void migrate() {
        if (!showMobHeadHP && mobHeadHPMode == MOB_HP_MODE_ALL) {
            mobHeadHPMode = MOB_HP_MODE_OFF;
        }
        if (mobHeadHPMode < MOB_HP_MODE_ALL || mobHeadHPMode > MOB_HP_MODE_OFF) {
            mobHeadHPMode = MOB_HP_MODE_ALL;
        }
        // v7.1.2 · embeddedHudMode 范围校验（防手改 JSON 出非法值导致循环按钮 (x+1)%4 异常）
        if (embeddedHudMode < EMBED_OFF || embeddedHudMode > EMBED_BOTH) {
            embeddedHudMode = EMBED_ONLY_STAGE;
        }
    }

    public boolean isMobHeadHPEnabled() { return mobHeadHPMode != MOB_HP_MODE_OFF; }
    public boolean isMobHeadHPNearestOnly() { return mobHeadHPMode == MOB_HP_MODE_NEAREST; }
}
