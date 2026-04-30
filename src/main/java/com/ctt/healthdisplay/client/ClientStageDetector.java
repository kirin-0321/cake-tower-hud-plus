package com.ctt.healthdisplay.client;

import com.ctt.healthdisplay.config.ModConfig;
import com.ctt.healthdisplay.hud.ClientStageLocation;
import com.ctt.healthdisplay.hud.StageLocation;
import com.ctt.healthdisplay.server.StageKey;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v7.0.14 · 客户端关卡探测状态机（vanilla 协议唯一信号源）。
 *
 * <h2>三类信号</h2>
 * <ul>
 *   <li><b>关卡 title</b>（{@link InGameHudTitleMixin} 推送）：主标题匹配
 *       {@code ^\d+-(?:\d+|\$|☠|❤)$} 即视为"进入新关卡"，副标题（已被客户端
 *       本地化的 translate 文本，例如"蔓生之地 [秋季]"）拼到桶 key 的 stageNum 字段。</li>
 *   <li><b>Floor bossbar</b>（{@link ClientStageProbe#tick} 推送）：bossbar 文本匹配
 *       {@code ^.+ \(\d+/\d+\)$}（例如"高塔 (13/30)"）即视为"在休息室 + 当前 floor"，
 *       不同 floor 各算一桶。</li>
 *   <li><b>TAKEN 伤害</b>（{@link ClientDamageProbe} 推送）：尚未确认任何关卡 / 休息室时
 *       受到伤害 → 切到"未知关卡"桶（{@code UNKNOWN}），免得伤害无家可归。</li>
 * </ul>
 *
 * <h2>状态转移规则</h2>
 * 任何信号生成的桶 key 与当前 key 不同 → {@link #commit} 切桶；相同 → 维持。
 * 这天然支持"同 title 多次进入合并"、"同 floor 多次扫描合并"、"过场期间不动桶"。
 *
 * <h2>线程安全</h2>
 * 所有写入发生在 client tick 或 client network thread；{@code volatile} + 不可变 record 足以保证可见性。
 */
public final class ClientStageDetector {

    private ClientStageDetector() {}

    // ---- 正则 ----
    /** 关卡 title 主标题。详见 datapack {@code _floor_universal.mcfunction:255-259}。 */
    private static final Pattern STAGE_TITLE =
            Pattern.compile("^(\\d+)-(\\d+|\\$|\\u2620|\\u2764)$");
    /** Floor 进度 bossbar 文本，例如"高塔 (13/30)"或"The Tower (12/30)"。 */
    private static final Pattern FLOOR_BAR =
            Pattern.compile("^(.+?) \\((\\d+)/(\\d+)\\)$");

    /** subtitle ↔ title 配对窗口；datapack 同 tick 发，宽松到 250ms 兜住网络抖动。 */
    private static final long PAIR_WINDOW_MS = 250L;

    /** 排除明显不是 floor bar 的"血条样"匹配（如 "Kirin0321 (100000/100000)"）。 */
    private static final int FLOOR_NUM_MAX = 999;
    private static final int FLOOR_TOTAL_MAX = 200;
    private static final int TOWER_NAME_MAX_LEN = 30;

    // ---- 当前状态 ----
    private static volatile StageKey currentKey = null;
    private static volatile StageLocation.Snapshot currentSnap = StageLocation.Snapshot.unknown();

    // ---- subtitle 缓存（等下一个 title 来配对）----
    private static volatile String pendingSubtitle = null;
    private static volatile long pendingSubtitleAtMs = 0L;

    // ---- 最近一次 floor bossbar（让 STAGE 桶也能携带 floor 数字）----
    private static volatile String lastFloorTowerName = null;
    private static volatile int lastFloorNum = -1;

    // =========================================================================
    //  公开入口
    // =========================================================================

    /** 由 {@link InGameHudTitleMixin} 在 vanilla 写主标题前调用。 */
    public static void onTitle(Text t) {
        if (t == null) return;
        String text = stripText(t);
        if (text.isEmpty()) return;
        Matcher m = STAGE_TITLE.matcher(text);
        if (!m.matches()) return;            // 对话 title / 其它 title，忽略
        int tier;
        try { tier = Integer.parseInt(m.group(1)); }
        catch (NumberFormatException e) { return; }
        String suffix = m.group(2);

        StageLocation.Kind kind;
        int stageNumInt = 0;
        switch (suffix) {
            case "$":              kind = StageLocation.Kind.STAGE_SHOP;  break;
            case "\u2620":         kind = StageLocation.Kind.STAGE_MBOSS; break; // ☠ 大小 boss 暂统一
            case "\u2764":         kind = StageLocation.Kind.STAGE_ALLY;  break; // ❤
            default:
                kind = StageLocation.Kind.STAGE_DUNGEON;
                try { stageNumInt = Integer.parseInt(suffix); }
                catch (NumberFormatException e) { return; }
        }

        // 配对 subtitle：title 来时回查最近 PAIR_WINDOW_MS 的缓存
        String subtitle = "";
        long nowMs = System.currentTimeMillis();
        String pending = pendingSubtitle;
        if (pending != null && (nowMs - pendingSubtitleAtMs) <= PAIR_WINDOW_MS) {
            subtitle = pending;
        }

        // 关卡名优先用 subtitle；无 subtitle 时用 "Tier-Suffix" 原始文本兜底，避免空名
        String stageName = subtitle.isEmpty() ? text : subtitle;
        int floor = stageNumInt > 0 ? stageNumInt : Math.max(0, lastFloorNum);

        // StageKey.stageType 编码为 "<KIND>@<stageName>" —— 让 buildStage / 比较仍用稳定 key，
        // 同时把"同名同 kind 视作同一桶"的语义直接落到 hashCode/equals 上。
        StageKey key = new StageKey(
                "client",
                String.valueOf(tier),
                String.valueOf(floor),
                kind.name() + "@" + stageName,
                String.valueOf(stageNumInt)
        );
        StageLocation.Snapshot snap = new StageLocation.Snapshot(
                kind, tier, floor, stageNumInt,
                0, 0,
                StageLocation.GameOverPhase.NONE, false,
                stageName,
                false /* v8.1.0 · 客户端 detector 路径不识别 MT 上下文 */
        );
        commit(key, snap, "title:" + text + (subtitle.isEmpty() ? "" : " / " + subtitle));
    }

    /** 由 {@link InGameHudTitleMixin} 在 vanilla 写副标题前调用。 */
    public static void onSubtitle(Text t) {
        if (t == null) return;
        String text = stripText(t);
        if (text.isEmpty()) return;
        pendingSubtitle = text;
        pendingSubtitleAtMs = System.currentTimeMillis();
    }

    /**
     * 由 {@link ClientStageProbe#tick} 每秒一次推过来当前所有 bossbar 文本。
     * 扫到 floor 进度条 → 切 BREAK_ROOM 桶；没有 → 维持。
     */
    public static void onBossbarsScanned(List<String> bossbarTexts) {
        if (bossbarTexts == null || bossbarTexts.isEmpty()) return;
        for (String s : bossbarTexts) {
            if (s == null) continue;
            Matcher m = FLOOR_BAR.matcher(s);
            if (!m.matches()) continue;
            String name;
            int floor;
            int total;
            try {
                name = m.group(1).trim();
                floor = Integer.parseInt(m.group(2));
                total = Integer.parseInt(m.group(3));
            } catch (NumberFormatException e) { continue; }
            if (name.isEmpty() || name.length() > TOWER_NAME_MAX_LEN) continue;
            if (floor < 0 || floor > FLOOR_NUM_MAX) continue;
            if (total < 1 || total > FLOOR_TOTAL_MAX) continue;
            // 排除"X/X 全血"型玩家/怪物血条（floor==total 且 total 较大）
            if (total > 50 && floor == total) continue;

            lastFloorTowerName = name;
            lastFloorNum = floor;

            // v7.0.18 · 休息室关卡名带 floor 进度括号，如 "高塔 (21/30)"——
            // bossbar 文本本身就是这个格式，直接复用。
            String stageName = String.format("%s (%d/%d)", name, floor, total);

            // BREAK_ROOM 桶按 (towerName, floor) 唯一；同 floor 多次扫合并、不同 floor 各算一桶。
            StageKey key = new StageKey(
                    "client", "0", String.valueOf(floor),
                    "BREAK_ROOM@" + stageName,
                    String.valueOf(floor)
            );
            StageLocation.Snapshot snap = new StageLocation.Snapshot(
                    StageLocation.Kind.BREAK_ROOM,
                    0, floor, 0,
                    breakRoomIdFromName(name),
                    0,
                    StageLocation.GameOverPhase.NONE, false,
                    stageName,
                    false /* v8.1.0 · floor bar fallback 不识别 MT 上下文 */
            );
            commit(key, snap, "floorBar:" + s);
            return; // 一次扫描只 commit 一个 floor bar
        }
    }

    /**
     * 由 {@link ClientDamageProbe} 在 TAKEN 分支调用：当前未进任何桶时切到"未知关卡"桶，
     * 让大厅里被攻击的伤害有个去处。
     */
    public static void onTakenDamage() {
        if (currentKey != null) return;
        StageKey key = new StageKey("client", "0", "0",
                "UNKNOWN@\u672a\u77e5\u5173\u5361", "0");
        StageLocation.Snapshot snap = new StageLocation.Snapshot(
                StageLocation.Kind.UNKNOWN, 0, 0, 0, 0, 0,
                StageLocation.GameOverPhase.NONE, false,
                "\u672a\u77e5\u5173\u5361",
                false /* v8.1.0 · UNKNOWN fallback 不识别 MT 上下文 */
        );
        commit(key, snap, "takenDamage");
    }

    /** 玩家断线 / 切服 → 全部清空，由 {@link ClientStageProbe} 或 disconnect 钩子调。 */
    public static void onDisconnect() {
        currentKey = null;
        currentSnap = StageLocation.Snapshot.unknown();
        pendingSubtitle = null;
        pendingSubtitleAtMs = 0L;
        lastFloorTowerName = null;
        lastFloorNum = -1;
        ClientStageLocation.setFromClientProbe(StageLocation.Snapshot.unknown(), null);
    }

    public static StageKey currentStageKey() { return currentKey; }
    public static StageLocation.Snapshot currentSnapshot() { return currentSnap; }

    // =========================================================================
    //  内部
    // =========================================================================

    private static void commit(StageKey newKey, StageLocation.Snapshot snap, String reason) {
        StageKey old = currentKey;
        if (Objects.equals(newKey, old)) return;
        currentKey = newKey;
        currentSnap = snap;
        ClientStageLocation.setFromClientProbe(snap, newKey);

        if (ModConfig.INSTANCE.clientDamageDebugChat) {
            CspDumpWriter.INSTANCE.writeLines(
                    String.format("[detector] commit reason=%s", reason),
                    String.format("    old=%s", old),
                    String.format("    new=%s  snap.kind=%s floor=%d tier=%d stageNum=%d",
                            newKey, snap.kind(), snap.floor(), snap.tier(), snap.stageNum())
            );
        }
    }

    private static String stripText(Text t) {
        try {
            String s = t.getString();
            return s == null ? "" : s.trim();
        } catch (Throwable th) {
            return "";
        }
    }

    /**
     * 楼层名 → BreakRoomID 映射（与服务端 {@code StageProbeServer} 常量一致）。
     * 中文名为客户端本地化文本（地图自带中文翻译包），未匹配的统一回 0（主塔）。
     */
    private static int breakRoomIdFromName(String name) {
        if (name == null) return 0;
        return switch (name) {
            case "\u4e3b\u5854", "The Tower", "\u9ad8\u5854" -> 0; // 主塔 / 高塔
            case "Arced Void"     -> 1;
            case "World War Bee"  -> 3;
            case "Oculus Forest"  -> 4;
            case "Magum Trials"   -> 6;
            default               -> 0;
        };
    }
}
