package com.ctt.healthdisplay.hud;

import com.ctt.healthdisplay.client.ClientStatsCache;
import com.ctt.healthdisplay.server.PlayerDamageStats;
import com.ctt.healthdisplay.server.PlayerKillStats;
import com.ctt.healthdisplay.server.PlayerTakenStats;
import com.ctt.healthdisplay.server.StageKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * v6.6.0 · M4 · K 键统计表格的数据装配层。
 *
 * <p>{@link StatsTableScreen} 每帧调一次 {@link #buildTotal()} / {@link #buildStage()}
 * 把 {@link PlayerDamageStats} / {@link PlayerKillStats} / {@link PlayerTakenStats}
 * 三家的快照合并成"按玩家行" / "按关分组玩家行"两份现成行表，UI 直接画。
 *
 * <h3>合并键</h3>
 * <p>用 UUID 做主键。三家中任一玩家上榜即占一行（缺数据列填 0）。Boss 击杀来自
 * {@link PlayerKillStats}，承伤来自 {@link PlayerTakenStats}，造伤来自 {@link PlayerDamageStats}。
 *
 * <h3>排序</h3>
 * <ul>
 *   <li>总表：调用方传入 {@link SortBy} 决定主键；二级稳定（按 UUID 哈希）</li>
 *   <li>分关表：硬编码 (Tier→Floor→stageNum) 升序、组内 ⚔ 降序（设计 §6.5）</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有源数据都是 ConcurrentHashMap，读取时拍快照。返回 immutable List，
 * UI 在帧内安全消费。
 */
public final class StatsTableData {

    private StatsTableData() {}

    /** 总表 / 分关表共用的单玩家行（数据皆为绝对值）。 */
    public record PlayerRow(
            UUID uuid,
            String name,
            long dealt,
            long taken,
            int kills,
            int bossKills,
            int assists,
            long durationMs
    ) {
        public static PlayerRow zero(UUID uuid, String name) {
            return new PlayerRow(uuid, name, 0, 0, 0, 0, 0, 0);
        }
    }

    /** 分关表的"一关 + 行列表"包。 */
    public record StageBlock(
            StageKey key,
            String tierFloorLabel,   // e.g. "T3F27"
            String localizedName,    // e.g. "水漫地牢"（缺名时 = "Boss12"）
            boolean inProgress,      // ⭐ (进行中) / (休息中) / 历史 → 渲染层判断高亮
            long durationMs,         // 该关持续时间
            List<PlayerRow> rows     // 该关玩家行（按 ⚔ 降序）
    ) {}

    /** 总表排序键。 */
    public enum SortBy {
        DEALT, TAKEN, KILLS, BOSS_KILLS, ASSISTS, DURATION;
    }

    // =========================================================================
    //  总表（Total Table · 8 列 + 头像）
    // =========================================================================

    /** 装总表行 + 表底"全队"汇总。 */
    public record Total(
            List<PlayerRow> rows,
            PlayerRow teamSum,    // 表底 [全队] 行：⏱ 取平均
            long sessionDurationMs
    ) {}

    /**
     * 构造总表。从 {@link PlayerDamageStats#snapshot()} 拿 dealt 列表为主，
     * 然后用 PlayerKillStats / PlayerTakenStats 的全员条目补行（即使没造伤但有承伤的玩家也上榜）。
     */
    public static Total buildTotal(SortBy sortBy, boolean ascending) {
        // v6.6.5 M6 · 改走 ClientStatsCache（集成服务器直读 / dedicated 走 payload 缓存）
        PlayerDamageStats.Snapshot dmgSnap = ClientStatsCache.damageSnapshot();
        PlayerKillStats.Snapshot   kSnap   = ClientStatsCache.killSnapshot();
        PlayerTakenStats.Snapshot  tSnap   = ClientStatsCache.takenSnapshot();

        Map<UUID, String> names = new HashMap<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (PlayerDamageStats.PlayerRow r : dmgSnap.players()) { seen.add(r.uuid()); names.putIfAbsent(r.uuid(), r.name()); }
        for (PlayerKillStats.PlayerRow   r : kSnap.players())   { seen.add(r.uuid()); names.putIfAbsent(r.uuid(), r.name()); }
        for (PlayerTakenStats.PlayerRow  r : tSnap.players())   { seen.add(r.uuid()); names.putIfAbsent(r.uuid(), r.name()); }

        List<PlayerRow> rows = new ArrayList<>(seen.size());
        long sessionDur = dmgSnap.durationMs();
        long sumDealt = 0, sumTaken = 0;
        int sumKills = 0, sumBoss = 0, sumAssists = 0;
        for (UUID u : seen) {
            long dealt = ClientStatsCache.getDealt(u);
            long taken = ClientStatsCache.getTaken(u);
            int kills  = ClientStatsCache.getKills(u);
            int boss   = ClientStatsCache.getBossKills(u);
            int assist = ClientStatsCache.getAssists(u);
            // M4 v1：⏱ 列等于 session 总时长（设计 §6.4 上写"该玩家在线时长"，
            // 但我们暂没有 per-player 在线追踪 → 全员显示同值；M5 抛光阶段细化）。
            rows.add(new PlayerRow(u, names.getOrDefault(u, "?"),
                    dealt, taken, kills, boss, assist, sessionDur));
            sumDealt += dealt;
            sumTaken += taken;
            sumKills += kills;
            sumBoss  += boss;
            sumAssists += assist;
        }

        Comparator<PlayerRow> cmp = comparator(sortBy);
        if (!ascending) cmp = cmp.reversed();
        rows.sort(cmp);

        // 全队聚合：⏱ 用平均；rows 空时平均=0
        long avgDur = rows.isEmpty() ? 0 : sessionDur; // 全员同值时平均=该值
        PlayerRow team = new PlayerRow(
                new UUID(0L, 0L),
                "[\u5168\u961f]",
                sumDealt, sumTaken,
                sumKills, sumBoss, sumAssists,
                avgDur
        );
        return new Total(Collections.unmodifiableList(rows), team, sessionDur);
    }

    private static Comparator<PlayerRow> comparator(SortBy by) {
        return switch (by) {
            case DEALT       -> Comparator.comparingLong(PlayerRow::dealt);
            case TAKEN       -> Comparator.comparingLong(PlayerRow::taken);
            case KILLS       -> Comparator.comparingInt(PlayerRow::kills);
            case BOSS_KILLS  -> Comparator.comparingInt(PlayerRow::bossKills);
            case ASSISTS     -> Comparator.comparingInt(PlayerRow::assists);
            case DURATION    -> Comparator.comparingLong(PlayerRow::durationMs);
        };
    }

    // =========================================================================
    //  分关表（Stage Table · 9 列）
    // =========================================================================

    /** 装分关表 block 列表。组按 (Tier→Floor→stageNum) 升序，组内按 ⚔ 降序。 */
    public record Stage(
            List<StageBlock> blocks,
            long sessionDurationMs
    ) {}

    public static Stage buildStage() {
        // 收集 union(damage, kill, taken) 三家所有 stageKey（v6.6.5 M6 · ClientStatsCache 已合 union）
        Set<StageKey> allKeys = new HashSet<>(ClientStatsCache.recordedStageKeys());

        List<StageBlock> blocks = new ArrayList<>(allKeys.size());
        for (StageKey key : allKeys) {
            // 收集该关所有玩家 UUID
            PlayerDamageStats.Snapshot dmg = ClientStatsCache.damageSnapshotOf(key);
            PlayerKillStats.Snapshot   kil = ClientStatsCache.killSnapshotOf(key);
            PlayerTakenStats.Snapshot  tak = ClientStatsCache.takenSnapshotOf(key);

            Set<UUID> seen = new LinkedHashSet<>();
            Map<UUID, String> names = new HashMap<>();
            for (PlayerDamageStats.PlayerRow r : dmg.players()) { seen.add(r.uuid()); names.putIfAbsent(r.uuid(), r.name()); }
            for (PlayerKillStats.PlayerRow   r : kil.players()) { seen.add(r.uuid()); names.putIfAbsent(r.uuid(), r.name()); }
            for (PlayerTakenStats.PlayerRow  r : tak.players()) { seen.add(r.uuid()); names.putIfAbsent(r.uuid(), r.name()); }

            List<PlayerRow> rows = new ArrayList<>(seen.size());
            long stageDur = ClientStatsCache.stageDurationMs(key);
            for (UUID u : seen) {
                long dealt = ClientStatsCache.getDealtAt(u, key);
                long taken = ClientStatsCache.getTakenAt(u, key);
                int kills  = ClientStatsCache.getKillsAt(u, key);
                int boss   = ClientStatsCache.getBossKillsAt(u, key);
                int assist = ClientStatsCache.getAssistsAt(u, key);
                rows.add(new PlayerRow(u, names.getOrDefault(u, "?"),
                        dealt, taken, kills, boss, assist, stageDur));
            }
            rows.sort(Comparator.comparingLong(PlayerRow::dealt).reversed());

            String tierFloor = String.format("T%sF%s",
                    safe(key.tier()), safe(key.floor()));
            String name = localizeStageName(key);
            boolean inProgress = ClientStatsCache.isStageInProgress(key);

            blocks.add(new StageBlock(key, tierFloor, name, inProgress, stageDur,
                    Collections.unmodifiableList(rows)));
        }
        // (Tier asc, Floor asc, stageNum asc)
        blocks.sort(Comparator
                .comparingInt((StageBlock b) -> parseIntOrZero(b.key.tier()))
                .thenComparingInt(b -> parseIntOrZero(b.key.floor()))
                .thenComparingInt(b -> parseIntOrZero(b.key.stageNum())));

        return new Stage(Collections.unmodifiableList(blocks),
                ClientStatsCache.sessionDurationMs());
    }

    /**
     * 关卡名本地化（设计 §6.5 + v6.6 跨语言 fallback 拍板）：
     * <ol>
     *   <li>{@link StageNameRegistry#localizedName} 拿命中名（含 zh→en 跨语言 fallback）</li>
     *   <li>命中失败 → 用 {@code stageType + stageNum}（"Boss12"/"Dungeon7"）兜底</li>
     * </ol>
     */
    private static String localizeStageName(StageKey key) {
        if (key == null) return "?";
        StageLocation.Kind kind = stageTypeToKind(key.stageType());
        int num = parseIntOrZero(key.stageNum());
        if (kind != null && num > 0) {
            String name = StageNameRegistry.localizedName(kind, num);
            if (name != null && !name.isEmpty()) return name;
        }
        // Fallback：类型名 + 编号
        String type = key.stageType() == null ? "?" : key.stageType();
        String pretty = switch (type) {
            case "boss"    -> "Boss";
            case "mboss"   -> "MiniBoss";
            case "dungeon" -> "Dungeon";
            case "shop"    -> "Shop";
            case "ally"    -> "Ally";
            case "misc"    -> "Misc";
            default        -> type;
        };
        return pretty + (num > 0 ? Integer.toString(num) : "");
    }

    private static StageLocation.Kind stageTypeToKind(String t) {
        if (t == null) return null;
        return switch (t) {
            case "boss"    -> StageLocation.Kind.STAGE_BOSS;
            case "mboss"   -> StageLocation.Kind.STAGE_MBOSS;
            case "dungeon" -> StageLocation.Kind.STAGE_DUNGEON;
            case "shop"    -> StageLocation.Kind.STAGE_SHOP;
            case "ally"    -> StageLocation.Kind.STAGE_ALLY;
            case "misc"    -> StageLocation.Kind.STAGE_MISC;
            default        -> null;
        };
    }

    private static int parseIntOrZero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static String safe(String s) { return s == null ? "?" : s; }

    /** 把 ms 格式化为 {@code MM:SS} / {@code H:MM:SS}（不含 ms）。 */
    public static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    /** 取 client 玩家自己的 UUID（用于"自己金色"高亮判定）；未连服返回 null。 */
    public static UUID selfUuid() {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player == null) return null;
        return mc.player.getUuid();
    }

    /** 玩家是否离线（PlayerListEntry 缺失 = 离线）。 */
    public static boolean isOffline(UUID uuid) {
        if (uuid == null) return true;
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return true;
        for (var e : mc.getNetworkHandler().getPlayerList()) {
            if (Objects.equals(e.getProfile().getId(), uuid)) return false;
        }
        return true;
    }
}
