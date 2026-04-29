package com.ctt.healthdisplay.client;

import com.ctt.healthdisplay.server.StageKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * v8.1.0 · 客户端伤害探针（CDP）持久化层。
 *
 * <h2>策略</h2>
 * <ul>
 *   <li><b>合并式全局累计</b>：所有服务器 / 地图 / 会话的数据都合并到同一份 JSON
 *       （{@code config/ctt-health-display-cdp.json}）。换图后旧数据继续累加，
 *       直到用户主动按 K 表 [清空] 按钮清除。</li>
 *   <li><b>持久化字段</b>（HUD / K 表会展示的）：
 *     <ul>
 *       <li>{@code globalTotal}（造成伤害·全局累计）</li>
 *       <li>{@code globalKills}（击杀·全局累计）</li>
 *       <li>{@code stageHistory}：每关 (StageKey → dealt + kills + durationMs)</li>
 *     </ul>
 *   </li>
 *   <li><b>不持久化</b>（瞬时 / 旁路 / 缓存数据）：
 *     {@code stageTotal / stageKills / currentStageStartMs / dpsRing /
 *      takenGlobal / sessionStartMs / entityToLastScore / lastHpByUuid}。</li>
 * </ul>
 *
 * <h2>触发时机</h2>
 * <ul>
 *   <li>切关：{@link ClientDamageProbe#onStageChanged} 末尾</li>
 *   <li>断线：{@link ClientDamageProbe#resetForDisconnect} 头部（freeze + save 后再 clear）</li>
 *   <li>退出游戏：{@code ClientLifecycleEvents.CLIENT_STOPPING}</li>
 *   <li>启动加载：{@code ClientLifecycleEvents.CLIENT_STARTED}</li>
 * </ul>
 *
 * <h2>schema</h2>
 * <pre>{@code
 * {
 *   "schemaVersion": 2,
 *   "globalTotal":   12345,
 *   "globalKills":   67,
 *   "stageHistory": [
 *     { "key":        { "gameId":"...","tier":"2","floor":"7","stageType":"...","stageNum":"1" },
 *       "dealt":      4500,
 *       "kills":      12,
 *       "durationMs": 180000,
 *       "enterMs":    1759158240123 }
 *   ]
 * }
 * }</pre>
 *
 * <p>v8.1.0 升 schema v2：StageEntry 新增 {@code enterMs}（关卡<b>首次</b>进入墙钟时间戳）。
 * 旧 v1 文件加载时该字段缺失为 0；分关表 buildStage 用 stageHistory 迭代序兜底排序，
 * 保留旧用户体验。新版本的写盘恒为 v2，混用旧版客户端读 v2 文件时会忽略 enterMs（不报错）。
 *
 * <p>用 plain class（非 record）+ {@link StageKeyDto} 包装 StageKey，避开 Gson 对
 * record 反序列化在不同版本下的兼容差异；代码层用 {@link #captureSnapshot} /
 * {@link #applySnapshot} 在 StageKey ↔ StageKeyDto 之间转换。
 */
public final class CdpPersistence {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-cdp-persistence");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** {@code config/ctt-health-display-cdp.json} —— 与 ModConfig / ServerConfig 同目录。 */
    public static final Path PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("ctt-health-display-cdp.json");

    private CdpPersistence() {}

    // =========================================================================
    //  Schema DTO（plain class，必须有 no-arg ctor 给 Gson 反射）
    // =========================================================================

    /** {@link StageKey} 的可序列化镜像。Gson 直接读 public 字段，不用反射 record component。 */
    public static final class StageKeyDto {
        public String gameId;
        public String tier;
        public String floor;
        public String stageType;
        public String stageNum;

        public StageKeyDto() {}

        public StageKeyDto(StageKey k) {
            if (k == null) return;
            this.gameId    = k.gameId();
            this.tier      = k.tier();
            this.floor     = k.floor();
            this.stageType = k.stageType();
            this.stageNum  = k.stageNum();
        }

        public StageKey toKey() {
            return new StageKey(gameId, tier, floor, stageType, stageNum);
        }
    }

    /** 单关历史条目。 */
    public static final class StageEntry {
        public StageKeyDto key;
        public long dealt;
        public int kills;
        public long durationMs;
        /**
         * v8.1.0 · schema v2 · 关卡<b>首次</b>进入的墙钟时间戳（{@link System#currentTimeMillis}）。
         * <p>旧 v1 文件无此字段，Gson 反序列化时默认 0；调用方（{@link ClientDamageProbe#applySnapshot}）
         * 看到 0 则不放进 {@code stageHistoryEnterMs}，由分关表 buildStage 用迭代序兜底。
         */
        public long enterMs;

        public StageEntry() {}

        public StageEntry(StageKey key, long dealt, int kills, long durationMs, long enterMs) {
            this.key        = new StageKeyDto(key);
            this.dealt      = dealt;
            this.kills      = kills;
            this.durationMs = durationMs;
            this.enterMs    = enterMs;
        }
    }

    /** 顶层快照容器。 */
    public static final class Snapshot {
        public int schemaVersion = 2;
        public long globalTotal;
        public long globalKills;
        public List<StageEntry> stageHistory = new ArrayList<>();

        public Snapshot() {}

        public static Snapshot empty() {
            return new Snapshot();
        }
    }

    // =========================================================================
    //  IO
    // =========================================================================

    /** 写盘。任何异常都被吞掉打 WARN（持久化失败不能影响游戏）。 */
    public static void save(Snapshot snap) {
        if (snap == null) return;
        try (Writer w = Files.newBufferedWriter(PATH)) {
            GSON.toJson(snap, w);
        } catch (Exception e) {
            LOGGER.warn("[CDP] save failed: {}", e.toString());
        }
    }

    /**
     * 读盘。文件缺失返回 {@link Snapshot#empty}；解析失败也返回 empty 并打 WARN
     * （视作"首次启动"，避免坏文件持续阻塞所有保存路径）。
     */
    public static Snapshot load() {
        if (!Files.exists(PATH)) return Snapshot.empty();
        try (Reader r = Files.newBufferedReader(PATH)) {
            Snapshot s = GSON.fromJson(r, Snapshot.class);
            if (s == null) return Snapshot.empty();
            if (s.schemaVersion <= 0) s.schemaVersion = 1;
            if (s.stageHistory == null) s.stageHistory = new ArrayList<>();
            return s;
        } catch (Exception e) {
            LOGGER.warn("[CDP] load failed (file present but parse error): {}", e.toString());
            return Snapshot.empty();
        }
    }

    /** 删盘。给 K 表 [清空] 按钮用。{@link IOException} 上抛由调用方处理。 */
    public static void deleteFile() throws IOException {
        Files.deleteIfExists(PATH);
    }
}
