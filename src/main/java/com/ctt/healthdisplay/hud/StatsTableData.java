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
        // v8.1.0 · 排序铁律：按"进入关卡的墙钟时间戳"升序（早→晚，从上往下）。
        // 数据源优先级（高 → 低）：
        //   1) CDP stageHistoryEnterMs（客户端首次进入墙钟，最准）
        //   2) 当前关 currentStageStartMs（CDP 进行中桶）
        //   3) 服务端 ClientStatsCache.stageEnterMs（dedicated 模式 / detector 未见过的 key）
        // enterMs 全为 0（旧 v1 持久化文件 + 服务端无数据）→ 兜底用 CDP 历史 LinkedHashMap
        // 迭代序索引，仍然保持旧用户的视觉时序；最后才按 (T,F,n) 字典序兜底。
        var cdp = com.ctt.healthdisplay.client.ClientDamageProbe.INSTANCE;
        java.util.Map<StageKey, Long> cdpHistory = cdp.getStageHistoryDealt();
        java.util.Map<StageKey, Long> cdpDurHistory = cdp.getStageHistoryDurationMs();
        java.util.Map<StageKey, Long> cdpEnterMsHistory = cdp.getStageHistoryEnterMs();
        StageKey cdpCurrent = cdp.getCurrentStageKey();
        long cdpCurrentStartMs = cdp.getCurrentStageStartMs();
        long cdpCurrentTotal = cdp.getStageTotal();
        long cdpCurrentDurMs = cdp.getCurrentStageDurationMs();
        // fallback 触发条件放宽：只要 detector 进过任何关 / 当前在某关 → 启用
        boolean cdpFallbackActive = !cdpHistory.isEmpty() || !cdpDurHistory.isEmpty() || cdpCurrent != null;

        // 1) 收集所有候选 key（去重）
        LinkedHashSet<StageKey> candidateKeys = new LinkedHashSet<>();
        if (cdpFallbackActive) {
            candidateKeys.addAll(cdpDurHistory.keySet());
            candidateKeys.addAll(cdpHistory.keySet());
            candidateKeys.addAll(cdpEnterMsHistory.keySet());
            if (cdpCurrent != null) candidateKeys.add(cdpCurrent);
        }
        candidateKeys.addAll(ClientStatsCache.recordedStageKeys());

        // 2) 算每 key 的 enterMs + 旧文件兜底用的迭代序索引
        // 迭代序 = "在 cdpHistory/cdpDurHistory 中第几个出现"——纯客户端时序兜底
        java.util.Map<StageKey, Integer> legacyOrderIdx = new HashMap<>();
        int legacyIdx = 0;
        for (StageKey k : cdpDurHistory.keySet()) legacyOrderIdx.putIfAbsent(k, legacyIdx++);
        for (StageKey k : cdpHistory.keySet())    legacyOrderIdx.putIfAbsent(k, legacyIdx++);

        java.util.Map<StageKey, Long> enterMsByKey = new HashMap<>(candidateKeys.size());
        for (StageKey k : candidateKeys) {
            long t = 0L;
            // 当前进行中关：CDP currentStageStartMs 优先
            if (k.equals(cdpCurrent) && cdpCurrentStartMs > 0L) {
                t = cdpCurrentStartMs;
            }
            if (t == 0L) {
                Long h = cdpEnterMsHistory.get(k);
                if (h != null) t = h;
            }
            if (t == 0L) {
                long s = ClientStatsCache.stageEnterMs(k);
                if (s > 0L) t = s;
            }
            enterMsByKey.put(k, t);
        }

        // 3) 排序：enterMs 升序 → 旧迭代序 → (T,F,n) 字典序
        List<StageKey> sortedKeys = new ArrayList<>(candidateKeys);
        sortedKeys.sort(Comparator
                .comparingLong((StageKey k) -> {
                    long t = enterMsByKey.getOrDefault(k, 0L);
                    return t > 0L ? t : Long.MAX_VALUE;
                })
                .thenComparingInt(k -> legacyOrderIdx.getOrDefault(k, Integer.MAX_VALUE))
                .thenComparingInt((StageKey k) -> parseIntOrZero(k.tier()))
                .thenComparingInt(k -> parseIntOrZero(k.floor()))
                .thenComparingInt(k -> parseIntOrZero(k.stageNum())));
        LinkedHashSet<StageKey> orderedKeys = new LinkedHashSet<>(sortedKeys);

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

            // v8.1.0 · MT 关：tierFloor 显示成 "MT·D{difficulty}·F{floor}" 而不是 "T{tier}F{floor}"
            // 让玩家在分关表里一眼区分"主大厅 T1F8 玛古姆"和"MT 难度5·F8 玛古姆"。
            String tierFloor = isMtStageType(key.stageType())
                    ? String.format("MT\u00b7D%s\u00b7F%s", safe(key.tier()), safe(key.floor()))
                    : String.format("T%sF%s", safe(key.tier()), safe(key.floor()));
            String name = localizeStageName(key);
            // v7.0.15 · CDP fallback 桶的"进行中"判定：当前桶 = inProgress
            boolean inProgress = ClientStatsCache.isStageInProgress(key)
                    || (cdpFallbackActive && key.equals(cdpCurrent));

            blocks.add(new StageBlock(key, tierFloor, name, inProgress, stageDur,
                    Collections.unmodifiableList(rows)));
        }
        // v8.1.0 · 不再二次排序：orderedKeys 已经是 enterMs 升序（早→晚，从上往下）。
        return new Stage(Collections.unmodifiableList(blocks),
                ClientStatsCache.sessionDurationMs());
    }

    /**
     * 关卡名本地化。
     * <ol>
     *   <li><b>优先</b> {@link StageNameRegistry#localizedName(StageLocation.Kind, int)} ——
     *       按客户端语言从内置 map 取 en/zh（例如英文客户端显示 "Garden of Hope"）。</li>
     *   <li>detector 路径 {@code STAGE_DUNGEON@副标题} / 服务端 {@code dungeon} 均需能解析出
     *       {@link StageLocation.Kind} + {@code stageNum}（见 {@link #resolveKindFromStageType}）。</li>
     *   <li>注册表未命中 → 再取 {@code @} 后字符串（vanilla 副标题原文，语种随地图包）。</li>
     *   <li>仍不行 → {@code stageType + stageNum} 兜底。</li>
     * </ol>
     */
    private static String localizeStageName(StageKey key) {
        if (key == null) return "?";
        String type = key.stageType();
        int num = parseIntOrZero(key.stageNum());

        StageLocation.Kind kind = resolveKindFromStageType(type);
        if (kind != null && num > 0) {
            String name = StageNameRegistry.localizedName(kind, num);
            if (name != null && !name.isEmpty()) return name;
        }

        if (type != null) {
            int at = type.indexOf('@');
            if (at >= 0 && at < type.length() - 1) {
                return type.substring(at + 1);
            }
        }

        // Fallback：类型名 + 编号（纯服务端 stageType，无 @）
        String baseForPretty = type;
        if (type != null) {
            int at = type.indexOf('@');
            if (at > 0) baseForPretty = type.substring(0, at);
            // v8.1.0 · 剥离 mt_ 前缀，让兜底 pretty name 也归一化（MT 标记由 tierFloorLabel 单独承载）
            if (baseForPretty.startsWith("mt_")) baseForPretty = baseForPretty.substring(3);
        }
        String pretty = baseForPretty == null ? "?" : switch (baseForPretty) {
            case "boss"    -> "Boss";
            case "mboss"   -> "MiniBoss";
            case "dungeon" -> "Dungeon";
            case "shop"    -> "Shop";
            case "ally"    -> "Ally";
            case "misc"    -> "Misc";
            default        -> baseForPretty;
        };
        return pretty + (num > 0 ? Integer.toString(num) : "");
    }

    /**
     * 服务端：{@code dungeon} / {@code boss} / …；客户端 detector：{@code STAGE_DUNGEON@…} 的 {@code STAGE_DUNGEON} 前缀。
     */
    private static StageLocation.Kind resolveKindFromStageType(String type) {
        if (type == null) return null;
        int at = type.indexOf('@');
        String base = at >= 0 ? type.substring(0, at) : type;
        StageLocation.Kind k = stageTypeToKind(base);
        if (k != null) return k;
        try {
            return StageLocation.Kind.valueOf(base);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static StageLocation.Kind stageTypeToKind(String t) {
        if (t == null) return null;
        // v8.1.0 · 剥离 MT 命名空间前缀，使 mt_dungeon 复用 dungeon 翻译表
        String base = t.startsWith("mt_") ? t.substring(3) : t;
        return switch (base) {
            case "boss"    -> StageLocation.Kind.STAGE_BOSS;
            case "mboss"   -> StageLocation.Kind.STAGE_MBOSS;
            case "dungeon" -> StageLocation.Kind.STAGE_DUNGEON;
            case "shop"    -> StageLocation.Kind.STAGE_SHOP;
            case "ally"    -> StageLocation.Kind.STAGE_ALLY;
            case "misc"    -> StageLocation.Kind.STAGE_MISC;
            default        -> null;
        };
    }

    /** v8.1.0 · stageType 是否为 MT 命名空间（{@code mt_} 前缀）。 */
    private static boolean isMtStageType(String t) {
        return t != null && t.startsWith("mt_");
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
