package com.ctt.healthdisplay.server;

import com.ctt.healthdisplay.hud.StageLocation;
import com.ctt.healthdisplay.hud.StageNameRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.6.0 hotfix · 关卡退出后聊天栏战绩广播（候选 B · 紧凑多行）。
 *
 * <h2>触发</h2>
 * <p>由 {@link StageBoundaryDispatcher} 在以下两种 transition 末尾调
 * {@link #onStageExit(StageKey)}：
 * <ul>
 *   <li>战斗关 → 大厅 / 休息室 / Game Over（标准退关）</li>
 *   <li>战斗关 → 另一个战斗关（无休息室直切，e.g. T1F1 → T1F1 重打）</li>
 * </ul>
 * 对应用户决策：<i>"切换关卡时，包括 T1F1 到 T1F1 这种情况，还有 T1F1 到休息室"</i>。
 *
 * <h2>接收方</h2>
 * <p>{@link MinecraftServer#getPlayerManager()} 全员（用户决策 B2 · 全服在线玩家）。
 * 每个玩家收到的内容相同但"自己那行"会被高亮成金色——这步在 {@link #renderLines}
 * 里按 {@code viewerUuid} 定制，不需要客户端 mod 配合。
 *
 * <h2>样式（v6.6.7 · 双块布局）</h2>
 * <pre>
 * ══════ T1F1 · 紫晶迷宫 · 02:34 ══════
 *  Simon          ⚔ 1,240  ⛨   230  ☠ 12  🤝  4
 *  Kirin          ⚔   980  ⛨   180  ☠  8  🤝  6  [离线]
 *  烤蛋糕         ⚔   650  ⛨   410  ☠  5  🤝 11      ← 收件人自己 = 金色
 *  [全队·关]      ⚔ 2,870  ⛨   820  ☠ 25  🤝 21
 * ══════════ 全局 05:46 ══════════
 *  Simon          ⚔ 5,120  ⛨   980  ☠ 30  🤝 12
 *  Kirin          ⚔ 4,200  ⛨   720  ☠ 22  🤝 14
 *  烤蛋糕         ⚔ 2,100  ⛨ 1,200  ☠ 12  🤝 18
 *  [全队·局]      ⚔11,420  ⛨ 2,900  ☠ 64  🤝 44
 * </pre>
 *
 * <h2>去重</h2>
 * <p>四人队伍同关同时退场会触发 4 次 EXIT listener。用 {@link #LAST_BROADCAST}
 * 按 {@link StageKey} 维度去重：同一 stageKey 在 {@link #DEDUPE_MS} 内只发一条。
 * 死亡多次（用户决策 C2 · 死亡也广播）的情况：每次死亡间隔通常 &gt; {@code DEDUPE_MS}，
 * 因此每条独立广播。同一 tick 内多人触发去重保留最早一条。
 *
 * <h2>不显示 Boss 击杀</h2>
 * <p>用户决策 D3。{@code ☠B} 列省略，节省一列宽度。
 *
 * <h2>压缩数字</h2>
 * <p>{@link #formatNum(long)}：&lt; 10000 用 {@code "%,d"}（带千分逗号），&lt; 1M 用
 * {@code "12.3k"}，&ge; 1M 用 {@code "1.2M"}，与 K 表 / HUD 嵌入式行的口径一致。
 *
 * <h2>关卡名本地化</h2>
 * <p>服务端不知客户端语言，统一传 {@code preferLang="zh_cn"} 给
 * {@link StageNameRegistry#localizedName(StageLocation.Kind, int, String)}：
 * Cake Team Towers 中文社区主流，专用服务器场景未来可由 ConfigScreen 切换。
 */
public final class StageReportBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-stage-report");

    /** 同 stageKey 去重窗口（ms）。1.5 秒覆盖 4 人队伍同 tick 退关 + 多次死亡间隔。 */
    private static final long DEDUPE_MS = 1500L;

    /** stageKey → 上次广播墙钟。{@link ConcurrentHashMap} 防 listener 多线程并发写。 */
    private static final Map<StageKey, Long> LAST_BROADCAST = new ConcurrentHashMap<>();

    private StageReportBroadcaster() {}

    /**
     * 由 {@link StageBoundaryDispatcher} EXIT / SWITCH 分支末尾调用。
     * 内部完成数据收集 + 去重 + 按玩家定制金色高亮 + 全员广播。
     */
    public static void onStageExit(StageKey key) {
        if (key == null) return;
        long now = System.currentTimeMillis();
        Long last = LAST_BROADCAST.get(key);
        if (last != null && now - last < DEDUPE_MS) {
            LOGGER.debug("[CTT report] dedupe skip {} (within {} ms)", key, DEDUPE_MS);
            return;
        }
        LAST_BROADCAST.put(key, now);

        MinecraftServer server = CttStatsServer.getServer();
        if (server == null) {
            LOGGER.warn("[CTT report] no server reference; skip broadcast for {}", key);
            return;
        }

        // 收集行数据一次（与观看者无关），按玩家定制颜色时只重组 Text。
        StageReport report = buildReport(key);
        if (report == null || report.rows.isEmpty()) {
            LOGGER.info("[CTT report] no row data for {}; skip broadcast.", key);
            return;
        }

        Set<UUID> online = onlineUuids(server);
        // v6.6.7 · 双块布局：关 header + n 玩家 + 全队·关 + 局 header + n 玩家 + 全队·局 = 2n + 4
        int linesPerViewer = report.rows.size() * 2 + 4;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            List<Text> lines = renderLines(report, p.getUuid(), online);
            for (Text line : lines) p.sendMessage(line, false);
        }
        LOGGER.info("[CTT report] broadcasted stage {} ({} players, {} lines/viewer)",
                key, report.rows.size(), linesPerViewer);
    }

    // ------------------------------------------------------------------
    //  数据收集（与观看者无关）
    // ------------------------------------------------------------------

    /**
     * 单玩家的<b>本关 + 整局</b>汇总行。
     * <p>v6.6.6 起新增 4 个 {@code s*} 字段（session 累计）支撑双行块布局——
     * 每行同名玩家在聊天栏会输出"关:" / "局:" 两条 Text。
     */
    private record Row(UUID uuid, String name,
                       long dealt,  long taken,  int kills,  int assists,
                       long sDealt, long sTaken, int sKills, int sAssists) {}

    /**
     * 整张报告。
     * <p>v6.6.6 起 {@code sumS*} = 全队整局累计；{@code sessionMs} 取自
     * {@link PlayerKillStats#sessionDurationMs()}，用于在 [全队·局] 行尾显示。
     */
    private record StageReport(StageKey key, String stageName, long durationMs,
                               List<Row> rows,
                               long sumDealt, long sumTaken,
                               int sumKills, int sumAssists,
                               long sumSDealt, long sumSTaken,
                               int sumSKills, int sumSAssists,
                               long sessionMs) {}

    /**
     * 合并三家 stats 的 stageKey 切片：
     * <ol>
     *   <li>三个 snapshotOf(key) 取 union of UUID（玩家可能只打了人没挨打）</li>
     *   <li>每个 UUID 走 getDealtAt / getTakenAt / getKillsAt / getAssistsAt 直读</li>
     *   <li>按 dealt 降序排（用户决策"按伤害排序"）</li>
     * </ol>
     */
    private static StageReport buildReport(StageKey key) {
        // union UUID + name
        Set<UUID> uuids = new LinkedHashSet<>();
        Map<UUID, String> names = new HashMap<>();

        PlayerDamageStats.Snapshot dmg = PlayerDamageStats.snapshotOf(key);
        for (PlayerDamageStats.PlayerRow r : dmg.players()) {
            uuids.add(r.uuid());
            names.putIfAbsent(r.uuid(), r.name());
        }
        PlayerKillStats.Snapshot kil = PlayerKillStats.snapshotOf(key);
        for (PlayerKillStats.PlayerRow r : kil.players()) {
            uuids.add(r.uuid());
            names.putIfAbsent(r.uuid(), r.name());
        }
        PlayerTakenStats.Snapshot tak = PlayerTakenStats.snapshotOf(key);
        for (PlayerTakenStats.PlayerRow r : tak.players()) {
            uuids.add(r.uuid());
            names.putIfAbsent(r.uuid(), r.name());
        }
        if (uuids.isEmpty()) return null;

        List<Row> rows = new ArrayList<>(uuids.size());
        long sumDealt = 0L, sumTaken = 0L;
        int sumKills = 0, sumAssists = 0;
        long sumSDealt = 0L, sumSTaken = 0L;
        int sumSKills = 0, sumSAssists = 0;
        for (UUID u : uuids) {
            long dealt = PlayerDamageStats.getDealtAt(u, key);
            long taken = PlayerTakenStats.getTakenAt(u, key);
            int kills  = PlayerKillStats.getKillsAt(u, key);
            int assist = PlayerKillStats.getAssistsAt(u, key);
            // v6.6.6 · 本关参战玩家的整局累计（不带 stageKey 的 API）
            long sDealt = PlayerDamageStats.getDealt(u);
            long sTaken = PlayerTakenStats.getTaken(u);
            int  sKills = PlayerKillStats.getKills(u);
            int  sAssist = PlayerKillStats.getAssists(u);
            rows.add(new Row(u, names.getOrDefault(u, "?"),
                    dealt, taken, kills, assist,
                    sDealt, sTaken, sKills, sAssist));
            sumDealt += dealt;
            sumTaken += taken;
            sumKills += kills;
            sumAssists += assist;
            sumSDealt += sDealt;
            sumSTaken += sTaken;
            sumSKills += sKills;
            sumSAssists += sAssist;
        }
        rows.sort(Comparator.comparingLong(Row::dealt).reversed());

        return new StageReport(
                key,
                lookupStageName(key),
                StageBoundaryDispatcher.stageDurationMs(key),
                rows,
                sumDealt, sumTaken, sumKills, sumAssists,
                sumSDealt, sumSTaken, sumSKills, sumSAssists,
                PlayerKillStats.sessionDurationMs()
        );
    }

    // ------------------------------------------------------------------
    //  渲染（与观看者相关：自己那行金色）
    // ------------------------------------------------------------------

    // ── 列对齐参数（像素，Minecraft default 字体）──
    // 名字段右边界：90 px ≈ 15 ASCII 字符 / 10 中文，覆盖常见 ID + [全队·关] 标签。
    // 数字段固定字符宽（数字本身等宽 6px，因此用字符 padding 即可对齐到 ±0px）。
    private static final int NAME_COL_PX = 90;
    private static final int DEALT_PAD   = 6;   // 形如 "1,234" / "12.3k" / "1.2M"
    private static final int TAKEN_PAD   = 6;
    private static final int KILLS_PAD   = 3;   // 0~999
    private static final int ASSIST_PAD  = 3;

    /**
     * 构造给 {@code viewer} 看的行序列（v6.6.7 · 双块布局）。
     * <p>布局：关 header + n 玩家·关 + [全队·关] + 局 header + n 玩家·局 + [全队·局]，
     * 4 人队总行数 = 4 + 2n = 12。
     * <p>对齐：名字段统一 padding 到 {@link #NAME_COL_PX}（按 Minecraft 字体宽度估算 +
     * 4px 粒度空格补齐），数字段用字符 padding（数字字体本身等宽）。
     * <p>颜色：每玩家行——自己 GOLD / 在线 WHITE / 离线 DARK_GRAY；
     * [全队·X] 行 YELLOW；header GOLD。
     */
    private static List<Text> renderLines(StageReport rep, UUID viewer, Set<UUID> online) {
        List<Text> out = new ArrayList<>(rep.rows.size() * 2 + 4);

        // ── 块 1 · 本关 ──
        String header1 = String.format("══════ T%sF%s · %s · %s ══════",
                str(rep.key.tier()), str(rep.key.floor()),
                rep.stageName, formatDuration(rep.durationMs));
        out.add(Text.literal(header1).formatted(Formatting.GOLD));

        for (Row r : rep.rows) {
            out.add(renderPlayerLine(r.uuid, r.name,
                    r.dealt, r.taken, r.kills, r.assists,
                    viewer, online));
        }
        out.add(renderTeamLine("[全队·关]",
                rep.sumDealt, rep.sumTaken, rep.sumKills, rep.sumAssists));

        // ── 块 2 · 整局 ──
        String header2 = String.format("══════════ 全局 %s ══════════",
                formatDuration(rep.sessionMs));
        out.add(Text.literal(header2).formatted(Formatting.GOLD));

        for (Row r : rep.rows) {
            out.add(renderPlayerLine(r.uuid, r.name,
                    r.sDealt, r.sTaken, r.sKills, r.sAssists,
                    viewer, online));
        }
        out.add(renderTeamLine("[全队·局]",
                rep.sumSDealt, rep.sumSTaken, rep.sumSKills, rep.sumSAssists));

        return out;
    }

    /** 单个玩家行：自己 GOLD / 在线 WHITE / 离线 DARK_GRAY + 末尾 "[离线]"。 */
    private static MutableText renderPlayerLine(UUID uuid, String name,
                                                long dealt, long taken,
                                                int kills, int assists,
                                                UUID viewer, Set<UUID> online) {
        boolean isSelf  = Objects.equals(uuid, viewer);
        boolean offline = !online.contains(uuid);
        Formatting color;
        if (isSelf) color = Formatting.GOLD;
        else if (offline) color = Formatting.DARK_GRAY;
        else color = Formatting.WHITE;

        String body = formatStatsBody(" " + name, dealt, taken, kills, assists);
        MutableText line = Text.literal(body).formatted(color);
        if (offline) {
            line.append(Text.literal("  [离线]").formatted(Formatting.DARK_GRAY));
        }
        return line;
    }

    /** 全队汇总行：YELLOW。label 形如 "[全队·关]" / "[全队·局]"。 */
    private static MutableText renderTeamLine(String label,
                                              long dealt, long taken,
                                              int kills, int assists) {
        String body = formatStatsBody(" " + label, dealt, taken, kills, assists);
        return Text.literal(body).formatted(Formatting.YELLOW);
    }

    /**
     * 把名字 / 标签 + 4 列数字组合成对齐后的字符串。
     * <p>名字列 padding 到 {@link #NAME_COL_PX} 像素，数字列用字符 padding（数字字体
     * 等宽）。整体输出形如 {@code " Simon          ⚔ 1,240  ⛨   230  ☠ 12  🤝  4"}。
     */
    private static String formatStatsBody(String namePart,
                                          long dealt, long taken,
                                          int kills, int assists) {
        return padRightToPx(namePart, NAME_COL_PX)
                + " ⚔" + pad(formatNum(dealt), DEALT_PAD)
                + "  ⛨" + pad(formatNum(taken), TAKEN_PAD)
                + "  ☠" + pad(Integer.toString(kills), KILLS_PAD)
                + "  🤝" + pad(Integer.toString(assists), ASSIST_PAD);
    }

    // ------------------------------------------------------------------
    //  helpers
    // ------------------------------------------------------------------

    private static Set<UUID> onlineUuids(MinecraftServer server) {
        Set<UUID> ids = new HashSet<>();
        if (server == null) return ids;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ids.add(p.getUuid());
        }
        return ids;
    }

    /** 关卡 friendly 名：StageNameRegistry 命中→中文名；否则兜底 "Boss12" 之类。 */
    private static String lookupStageName(StageKey key) {
        StageLocation.Kind kind = stageTypeToKind(key.stageType());
        int num = parseInt(key.stageNum());
        if (kind != null && num > 0) {
            String n = StageNameRegistry.localizedName(kind, num, "zh_cn");
            if (n != null && !n.isEmpty()) return n;
        }
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
        return num > 0 ? pretty + num : pretty;
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

    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static String str(String s) { return s == null ? "?" : s; }

    /** 短数字带千分位；4 位以下精确，5~6 位 12.3k，7+ 1.2M。与 HUD compact 一致。 */
    private static String formatNum(long v) {
        if (v < 0) v = 0;
        if (v < 10_000)     return String.format("%,d", v);
        if (v < 1_000_000)  return String.format("%.1fk", v / 1_000.0);
        return String.format("%.1fM", v / 1_000_000.0);
    }

    /** 数字列左 padding 到固定字符宽度（数字字体等宽，字符 padding = 像素 padding）。 */
    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }

    /**
     * v6.6.7 · 像素级近似 padding：把字符串右补空格让其渲染宽度接近 {@code targetPx}。
     * <p>Minecraft default 字体每字符像素：ASCII 字母数字 ≈ 6px，CJK / 全角 / emoji ≈ 9px，
     * 窄字符 (i / l / ! / [ / ]) ≈ 4-5px，空格 4px。本方法按 4px 粒度补齐——
     * 玩家名（中英文混排）的右边界会有 ±2px 偏差，但数字列因数字本身等宽仍能精确对齐。
     */
    private static String padRightToPx(String s, int targetPx) {
        if (s == null) s = "?";
        int curr = pxWidth(s);
        if (curr >= targetPx) return s;
        int spaces = (targetPx - curr) / 4;  // 空格 4px，向下取整避免超出
        return s + " ".repeat(spaces);
    }

    /** 估算字符串在 Minecraft default 字体下的渲染像素宽度（含字符间 1px 间隔）。 */
    private static int pxWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += charPx(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    /** 单字符像素宽度估算（含右侧 1px 间隔）。粗粒度——足以做 4px 级 padding。 */
    private static int charPx(int cp) {
        if (cp == ' ') return 4;
        // 窄字符：i / l / I / 1 / ! / . / , / ' / ` / [ / ] / ( / ) / : / ; / |
        if ("Iil1!.,'`[]():;|".indexOf(cp) >= 0) return 5;
        // CJK / 中日韩 / 全角符号 / emoji 类：统一按 9px
        if (cp >= 0x2E80) return 9;
        // 默认 ASCII 字母数字、常规标点
        return 6;
    }

    private static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long sec = ms / 1000;
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }
}
