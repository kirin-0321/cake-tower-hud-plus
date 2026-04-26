package com.ctt.healthdisplay.server;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * v6.6.1 · M2 · 三家 stats 的 NBT 持久化层。
 *
 * <h2>文件位置</h2>
 * <p>{@code <worldDir>/data/ctt_stats.dat}（gzip 压缩 NBT，由 {@link NbtIo#writeCompressed} 写）。
 *
 * <h2>NBT schema</h2>
 * <pre>
 * root:
 *   version: int = 1
 *   savedAtMs: long
 *   currentGameId: int       ← 0 = 还没开局
 *   session: NbtCompound
 *     damage: NbtCompound    ← {@link PlayerDamageStats#toNbt}
 *     kill:   NbtCompound    ← {@link PlayerKillStats#toNbt}
 *     taken:  NbtCompound    ← {@link PlayerTakenStats#toNbt}
 *     stage:  NbtCompound    ← {@link StageBoundaryDispatcher#timeTablesToNbt}
 *   history: list&lt;NbtCompound&gt;
 *     ─ 每条形如 {savedAtMs, gameId, session: &lt;同上&gt;}
 *     ─ 上限 {@link #HISTORY_CAP}，超出按"最旧先弃"
 * </pre>
 *
 * <h2>写盘时机</h2>
 * <ol>
 *   <li>SERVER_STARTED 之后立刻 {@link #load(MinecraftServer)}</li>
 *   <li>END_SERVER_TICK 节流（{@link #SAVE_INTERVAL_MS}）{@link #onTickEnd}</li>
 *   <li>{@link StageBoundaryDispatcher#onStageExit} 同步 {@link #saveNow}</li>
 *   <li>{@link StageBoundaryDispatcher#onSessionChange} → {@link #archiveAndReset}（归档 + reset + 立即写）</li>
 *   <li>SERVER_STOPPING 收尾 {@link #saveNow}</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * <p>所有公开方法都仅由 server tick 线程 / lifecycle 线程调用（fabric event 事件保证），
 * 内部状态 {@code dirty} / {@code historyCache} 用 {@code volatile} + 同步块兜底。
 *
 * <h2>归档语义（用户 2026-04-26 拍板）</h2>
 * <ul>
 *   <li>归档触发条件 = "{@code #CTT GameID} 跳变"（最严，T1F1 重进等内部 clear 不归档）</li>
 *   <li>归档时把当前 session 整体（damage/kill/taken/stage）打包推进 {@code history[]}</li>
 *   <li>history[] 上限 20 条（超出弃最早），v1 不暴露 UI</li>
 *   <li>同关多次进出 stage bucket 累加（保持内存默认行为）</li>
 *   <li>9 层归属计数 layerCounts[] 一并持久化（L 键诊断面板重启不丢）</li>
 * </ul>
 */
public final class StatsPersistenceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-stats-persist");

    public static final int VERSION = 1;
    /** 周期写盘节流（用户拍板 60s）。 */
    public static final long SAVE_INTERVAL_MS = 60_000L;
    /** history[] 容量上限（用户拍板 20）。 */
    public static final int HISTORY_CAP = 20;
    /** 相对世界根的文件路径（与 plan §4.4 一致）。 */
    public static final String FILE_REL_PATH = "data/ctt_stats.dat";

    private static volatile MinecraftServer server;
    private static volatile long lastSaveMs = 0L;
    /** 仅作"是否值得写"的弱提示；当前实现不依赖此字段做正确性，只是减少无意义 IO。 */
    private static volatile boolean dirty = false;

    /**
     * history[] 内存副本。
     * <ul>
     *   <li>load() 时从 NBT 读出</li>
     *   <li>archiveAndReset() 时 push 一条（截断到 HISTORY_CAP）</li>
     *   <li>save() 时整体写回 NBT</li>
     * </ul>
     * 写访问只在 server tick 线程 / lifecycle 线程；用 LinkedList 方便头删。
     */
    private static final LinkedList<NbtCompound> historyCache = new LinkedList<>();

    private StatsPersistenceManager() {}

    // =========================================================================
    //  Lifecycle hooks
    // =========================================================================

    /**
     * 服务端启动后调用：读 NBT，把 sessionId / 三家 stats / 时间表全部还原到内存。
     * 文件不存在 / 损坏 → 留 stats 为空（CttStatsServer 已经在 SERVER_STARTED 里 start() 过 +
     * setFrozen(true)，玩家进战斗关 dispatcher 会自动解冻）。
     */
    public static void load(MinecraftServer s) {
        server = s;
        Path f = filePath();
        if (f == null || !Files.exists(f)) {
            LOGGER.info("[CTT Persist] no ctt_stats.dat at {} (fresh start)", f);
            return;
        }
        try {
            NbtCompound root = NbtIo.readCompressed(f, NbtSizeTracker.ofUnlimitedBytes());
            if (root == null || root.isEmpty()) {
                LOGGER.warn("[CTT Persist] ctt_stats.dat empty/null, fresh start.");
                return;
            }
            int v = root.contains("version") ? root.getInt("version") : 0;
            if (v != VERSION) {
                LOGGER.warn("[CTT Persist] ctt_stats.dat version {} ≠ expected {}; reading best-effort.", v, VERSION);
            }
            int gid = root.contains("currentGameId") ? root.getInt("currentGameId") : 0;
            StageBoundaryDispatcher.restoreGameId(gid);

            NbtCompound session = root.getCompound("session");
            PlayerDamageStats.fromNbt(session.getCompound("damage"));
            PlayerKillStats.fromNbt(session.getCompound("kill"));
            PlayerTakenStats.fromNbt(session.getCompound("taken"));
            StageBoundaryDispatcher.timeTablesFromNbt(session.getCompound("stage"));

            historyCache.clear();
            NbtList hist = root.getList("history", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < hist.size(); i++) {
                historyCache.add(hist.getCompound(i).copy());
            }

            LOGGER.info("[CTT Persist] loaded ctt_stats.dat: version={}, gameId={}, history={}",
                    v, gid, historyCache.size());
        } catch (IOException e) {
            LOGGER.warn("[CTT Persist] read ctt_stats.dat failed ({}); fresh start.", e.toString());
        } catch (Throwable t) {
            LOGGER.warn("[CTT Persist] decode ctt_stats.dat threw {}; fresh start.", t.toString());
        }
    }

    /**
     * END_SERVER_TICK 注册：每 {@link #SAVE_INTERVAL_MS} ms 写一次。
     * dirty 标记仅作"加速跳过"，没置 dirty 也会按时间到时落盘
     * （防御性：避免某条写入路径忘记 markDirty 导致永不持久）。
     */
    public static void onTickEnd(MinecraftServer s) {
        long now = System.currentTimeMillis();
        if (now - lastSaveMs < SAVE_INTERVAL_MS) return;
        saveNow();
    }

    /**
     * 关卡退出立即写盘（保证关结束的最后那批数据落地，避免崩服务器丢一关）。
     * 由 {@link StageBoundaryDispatcher#onStageExit} 注册的 listener 调用。
     */
    public static void onStageExit(StageKey key) {
        markDirty();
        saveNow();
    }

    /**
     * GameID 跳变 → 把当前 session 数据归档进 history[]，然后 reset 三家 stats，
     * 立即写盘。由 {@link StageBoundaryDispatcher#onSessionChange} 注册的 listener 调用。
     */
    public static void onSessionChange() {
        archiveAndReset();
        saveNow();
    }

    /** SERVER_STOPPING 收尾写盘。 */
    public static void onServerStopping(MinecraftServer s) {
        try { saveNow(); }
        finally { server = null; }
    }

    public static void markDirty() { dirty = true; }

    // =========================================================================
    //  Save / archive
    // =========================================================================

    /**
     * 立即同步写盘。
     * <p>非原子：先写到 .tmp 再 rename 太重了（这里数据量小且非游戏关键路径），
     * 直接 NbtIo.writeCompressed 即可。失败仅日志，不阻断游戏。
     */
    public static synchronized void saveNow() {
        MinecraftServer s = server;
        if (s == null) return;
        Path f = filePath();
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            NbtCompound root = buildRoot();
            NbtIo.writeCompressed(root, f);
            lastSaveMs = System.currentTimeMillis();
            dirty = false;
        } catch (IOException e) {
            LOGGER.warn("[CTT Persist] write ctt_stats.dat failed: {}", e.toString());
        } catch (Throwable t) {
            LOGGER.warn("[CTT Persist] encode ctt_stats.dat threw: {}", t.toString());
        }
    }

    /**
     * 归档：当前 session 整体（damage/kill/taken/stage）打包成 {@code history[]} 一条，
     * 截断到 {@link #HISTORY_CAP}；然后 reset 三家 stats（保持 live=true / frozen=由 dispatcher 重新评估）。
     */
    private static synchronized void archiveAndReset() {
        NbtCompound entry = new NbtCompound();
        entry.putLong("savedAtMs", System.currentTimeMillis());
        // 归档时使用"上一段"的 gameId（即 dispatcher.lastSeenGameId 跳变前的旧值）。
        // 但 dispatcher 已经把 currentGameId 推进新值了，旧 gameId 我们从 session 数据里取
        // —— 任意一个 stageKey 的 gameId 都行，没数据时存 0。
        int oldGid = guessOldGameId();
        entry.putInt("gameId", oldGid);

        NbtCompound session = new NbtCompound();
        session.put("damage", PlayerDamageStats.toNbt());
        session.put("kill",   PlayerKillStats.toNbt());
        session.put("taken",  PlayerTakenStats.toNbt());
        session.put("stage",  StageBoundaryDispatcher.timeTablesToNbt());
        entry.put("session", session);

        historyCache.addLast(entry);
        while (historyCache.size() > HISTORY_CAP) historyCache.removeFirst();

        // 三家 stats reset：clear() 会把 live=true / frozen=false / 时长归零，
        // 与 SERVER_STARTED 后玩家首次进战斗关之前的状态等价；frozen 会由
        // StageBoundaryDispatcher.updateGlobalFrozenFromPlayers 立刻按当前玩家分布重设。
        PlayerDamageStats.clear();
        StageBoundaryDispatcher.clearStageTimeTables();

        LOGGER.info("[CTT Persist] archived old session (oldGameId={}), history={}", oldGid, historyCache.size());
    }

    private static int guessOldGameId() {
        // 走当前 session entries 的任意 stageKey 找 gameId（应当全部相同；找不到就 0）。
        for (StageKey k : PlayerDamageStats.recordedStageKeys()) {
            String g = k.gameId();
            if (g != null) {
                try { return Integer.parseInt(g); }
                catch (NumberFormatException ignored) { /* fall through */ }
            }
        }
        return 0;
    }

    // =========================================================================
    //  Internal · NBT root construction
    // =========================================================================

    private static NbtCompound buildRoot() {
        NbtCompound root = new NbtCompound();
        root.putInt("version", VERSION);
        root.putLong("savedAtMs", System.currentTimeMillis());
        root.putInt("currentGameId", StageBoundaryDispatcher.currentGameIdInt());

        NbtCompound session = new NbtCompound();
        session.put("damage", PlayerDamageStats.toNbt());
        session.put("kill",   PlayerKillStats.toNbt());
        session.put("taken",  PlayerTakenStats.toNbt());
        session.put("stage",  StageBoundaryDispatcher.timeTablesToNbt());
        root.put("session", session);

        NbtList hist = new NbtList();
        for (NbtCompound e : historyCache) hist.add(e.copy());
        root.put("history", hist);

        return root;
    }

    // =========================================================================
    //  Internal · path helper
    // =========================================================================

    private static Path filePath() {
        MinecraftServer s = server;
        if (s == null) return null;
        try {
            return s.getSavePath(WorldSavePath.ROOT).resolve(FILE_REL_PATH);
        } catch (Throwable t) {
            LOGGER.warn("[CTT Persist] resolve save path failed: {}", t.toString());
            return null;
        }
    }

    /** 仅供测试 / 诊断：history[] 当前大小。 */
    public static int historySize() { return historyCache.size(); }

    /** 仅供测试 / 诊断：history[] 不可变只读视图（NBT 副本）。 */
    public static List<NbtCompound> historyView() {
        ArrayList<NbtCompound> out = new ArrayList<>(historyCache.size());
        for (NbtCompound e : historyCache) out.add(e.copy());
        return Collections.unmodifiableList(out);
    }
}
