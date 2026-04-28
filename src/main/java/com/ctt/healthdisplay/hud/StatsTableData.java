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
     *
     * <p>v7.1.1 · 服务端三家 stats 都没数据但 CDP 有数据时，注入一行 {@code 全部伤害粒子}
     * fallback：{@code dealt=globalTotal · kills=globalKills · ⏱=cdp 自维护 sessionDurationMs}。
     * 与分关表 fallback 行（§{@link #buildStage}）语义对称——CDP 是无归属全场聚合。
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

        // v7.1.1 · 客户端 fallback：服务端三家 stats 全空 → 注入 CDP 单行
        //   名字="全部伤害粒子"、UUID=GHOST_UUID（头像空白、不算离线、不会被识为 self）
        //   dealt = globalTotal · kills = globalKills · ⏱ = cdp.getSessionDurationMs()
        // 同步进 [全队] 行的 sum，以避免出现"主行有数字但全队全 0"的视觉错位
        if (rows.isEmpty()) {
            var cdp = com.ctt.healthdisplay.client.ClientDamageProbe.INSTANCE;
            long fbDealt = cdp.getGlobalTotal();
            long fbKills = cdp.getGlobalKills();
            long fbDur   = cdp.getSessionDurationMs();
            if (fbDealt > 0 || fbKills > 0 || fbDur > 0) {
                rows.add(new PlayerRow(GHOST_UUID, "\u5168\u90e8\u4f24\u5bb3\u7c92\u5b50",
                        fbDealt, 0L, (int) fbKills, 0, 0, fbDur));
                sumDealt += fbDealt;
                sumKills += (int) fbKills;
                if (sessionDur <= 0L) sessionDur = fbDur; // 服务端 sessionDur=0 时用 CDP 的
            }
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
        // v7.0.16 · 改为时间序：先把 CDP 历史 LinkedHashMap 的迭代序（= 切关顺序）作为主轴，
        // 当前桶 append 到末尾；服务端三家 stats 提供的额外 key（极少出现于纯客户端模式）按
        // (T,F,n) 排序后追加到末尾，保证不丢数据但不破坏时间轴。
        var cdp = com.ctt.healthdisplay.client.ClientDamageProbe.INSTANCE;
        java.util.Map<StageKey, Long> cdpHistory = cdp.getStageHistoryDealt();
        java.util.Map<StageKey, Long> cdpDurHistory = cdp.getStageHistoryDurationMs();
        StageKey cdpCurrent = cdp.getCurrentStageKey();
        long cdpCurrentTotal = cdp.getStageTotal();
        long cdpCurrentDurMs = cdp.getCurrentStageDurationMs();
        // fallback 触发条件放宽：只要 detector 进过任何关 / 当前在某关 → 启用
        boolean cdpFallbackActive = !cdpHistory.isEmpty() || !cdpDurHistory.isEmpty() || cdpCurrent != null;

        LinkedHashSet<StageKey> orderedKeys = new LinkedHashSet<>();
        if (cdpFallbackActive) {
            // dur 历史 = dealt 历史的超集（dealt=0 但停留时间>0 的关也要列出来，例如休息室、过场）
            orderedKeys.addAll(cdpDurHistory.keySet());
            orderedKeys.addAll(cdpHistory.keySet());
            if (cdpCurrent != null) orderedKeys.add(cdpCurrent); // 进行中桶在末尾
        }
        // 兜底：服务端三家 stats 里 detector 没见过的 key，按 (T,F,n) 排序追加
        Set<StageKey> serverKeys = new HashSet<>(ClientStatsCache.recordedStageKeys());
        serverKeys.removeAll(orderedKeys);
        if (!serverKeys.isEmpty()) {
            List<StageKey> serverOrdered = new ArrayList<>(serverKeys);
            serverOrdered.sort(Comparator
                    .comparingInt((StageKey k) -> parseIntOrZero(k.tier()))
                    .thenComparingInt(k -> parseIntOrZero(k.floor()))
                    .thenComparingInt(k -> parseIntOrZero(k.stageNum())));
            orderedKeys.addAll(serverOrdered);
        }

        // v7.0.22 · 客户端 fallback 单行用 ghost UUID + 固定名"全部伤害粒子"——
        // 强调 CDP 是无归属的全场粒子聚合，不属于任何具体玩家：
        //   ↳ 头像列空白（StatsTableScreen.drawHead 见 GHOST_UUID 直接跳过）
        //   ↳ 名字列固定"全部伤害粒子"，且不会被加 [离线] 后缀
        //   ↳ 不会被识别成 self（真实玩家 UUID 不可能为全 0）
        UUID selfUuidForFallback = GHOST_UUID;
        String selfNameForFallback = "\u5168\u90e8\u4f24\u5bb3\u7c92\u5b50";

        List<StageBlock> blocks = new ArrayList<>(orderedKeys.size());
        for (StageKey key : orderedKeys) {
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
            // v7.0.17 · 服务端没数据时用 CDP 自维护的时长（当前关实时增长 / 已完成关固定值）
            if (stageDur <= 0L && cdpFallbackActive) {
                if (key.equals(cdpCurrent)) {
                    stageDur = cdpCurrentDurMs;
                } else {
                    Long h = cdpDurHistory.get(key);
                    if (h != null) stageDur = h;
                }
            }
            for (UUID u : seen) {
                long dealt = ClientStatsCache.getDealtAt(u, key);
                long taken = ClientStatsCache.getTakenAt(u, key);
                int kills  = ClientStatsCache.getKillsAt(u, key);
                int boss   = ClientStatsCache.getBossKillsAt(u, key);
                int assist = ClientStatsCache.getAssistsAt(u, key);
                rows.add(new PlayerRow(u, names.getOrDefault(u, "?"),
                        dealt, taken, kills, boss, assist, stageDur));
            }
            // v7.0.15 · 三家服务端 stats 此 key 没数据 → 注入 CDP 单行 fallback
            // v7.0.17 · 放宽：dealt=0 但 stageDur>0（例如休息室停留 / 路过关卡）也保留行
            // v7.1.0 · fallback 行的 kills 列也由 CDP 填（getStageKillsAt），与 ☠ HUD 段一致
            if (rows.isEmpty() && cdpFallbackActive) {
                long fallbackDealt;
                if (key.equals(cdpCurrent)) {
                    fallbackDealt = cdpCurrentTotal;
                } else {
                    Long h = cdpHistory.get(key);
                    fallbackDealt = h == null ? 0L : h;
                }
                int fallbackKills = cdp.getStageKillsAt(key);
                if ((fallbackDealt > 0 || stageDur > 0 || fallbackKills > 0) && selfUuidForFallback != null) {
                    rows.add(new PlayerRow(selfUuidForFallback, selfNameForFallback,
                            fallbackDealt, 0L, fallbackKills, 0, 0, stageDur));
                }
            }

            rows.sort(Comparator.comparingLong(PlayerRow::dealt).reversed());

            String tierFloor = String.format("T%sF%s",
                    safe(key.tier()), safe(key.floor()));
            String name = localizeStageName(key);
            // v7.0.15 · CDP fallback 桶的"进行中"判定：当前桶 = inProgress
            boolean inProgress = ClientStatsCache.isStageInProgress(key)
                    || (cdpFallbackActive && key.equals(cdpCurrent));

            blocks.add(new StageBlock(key, tierFloor, name, inProgress, stageDur,
                    Collections.unmodifiableList(rows)));
        }
        // v7.0.16 · 不再二次排序：orderedKeys 已经是时间序（CDP 历史 → 当前 → 服务端兜底）

        return new Stage(Collections.unmodifiableList(blocks),
                ClientStatsCache.sessionDurationMs());
    }

    /**
     * 关卡名本地化。
     * <ol>
     *   <li>v7.0.15 · stageType 末尾带 {@code "@<name>"}（detector 客户端 fallback 路径写入）→
     *       直接取 @ 后字符串（已是 vanilla title 副标题，例如 "荣耀道场 [基础]" / "高塔"）</li>
     *   <li>命中失败 → 走 {@link StageNameRegistry#localizedName}（服务端 payload 老路径）</li>
     *   <li>仍失败 → 用 {@code stageType + stageNum}（"Boss12"/"Dungeon7"）兜底</li>
     * </ol>
     */
    private static String localizeStageName(StageKey key) {
        if (key == null) return "?";
        String type = key.stageType();
        if (type != null) {
            int at = type.indexOf('@');
            if (at >= 0 && at < type.length() - 1) {
                return type.substring(at + 1);
            }
        }
        StageLocation.Kind kind = stageTypeToKind(type);
        int num = parseIntOrZero(key.stageNum());
        if (kind != null && num > 0) {
            String name = StageNameRegistry.localizedName(kind, num);
            if (name != null && !name.isEmpty()) return name;
        }
        // Fallback：类型名 + 编号
        String pretty = type == null ? "?" : switch (type) {
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
        if (uuid.equals(GHOST_UUID)) return false; // ghost 行不算离线
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return true;
        for (var e : mc.getNetworkHandler().getPlayerList()) {
            if (Objects.equals(e.getProfile().getId(), uuid)) return false;
        }
        return true;
    }

    /**
     * v7.0.22 · "全部伤害粒子"无归属行的 sentinel UUID（全 0）。
     * <p>{@link StatsTableScreen#drawHead} 见到此 UUID 直接 return（不画头像 / 灰块），
     * {@link #isOffline} 见到此 UUID 直接返回 false（避免被加 [离线] 后缀），
     * {@code isSelf} 比较绝不会命中（真实玩家 UUID 不可能为全 0）。
     */
    public static final UUID GHOST_UUID = new UUID(0L, 0L);
}
