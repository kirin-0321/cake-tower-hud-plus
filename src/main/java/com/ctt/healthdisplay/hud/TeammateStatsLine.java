package com.ctt.healthdisplay.hud;

import com.ctt.healthdisplay.client.ClientStatsCache;
import com.ctt.healthdisplay.server.StageKey;

import java.util.UUID;

/**
 * v6.6.0 · M3 · 嵌入式 HUD（队友面板第二/三/四行 stats 行）数据装配器。
 *
 * <p>HealthBarRenderer 每帧渲染队友时调用 {@link #ofSession(UUID)} / {@link #ofStage(UUID)}
 * 拿到一份 {@link Line}，再按设计 §5.4 的 {@code ⚔ <dmg> ⛨ <taken> ☠ <K>/<A>} 格式排版。
 *
 * <h3>数据源（M3 设计 §5.4 + v6.6.5 M6 sync 改造）</h3>
 * <ul>
 *   <li>⚔ Damage Dealt → {@link ClientStatsCache#getDealt(UUID)} / {@link ClientStatsCache#getDealtAt}</li>
 *   <li>⛨ Damage Taken → {@link ClientStatsCache#getTaken(UUID)} / {@link ClientStatsCache#getTakenAt}</li>
 *   <li>☠ Kills / Assists → {@link ClientStatsCache#getKills(UUID)} + {@link ClientStatsCache#getAssists}
 *       （HUD 用紧凑 {@code K/A} 合显，K 键表格分两列）</li>
 * </ul>
 *
 * <h3>休息室上一关行为（设计 §5.5）</h3>
 * <p>{@link #ofStage(UUID)} 优先取 {@link ClientStatsCache#lastSeenStageKey(UUID)}：
 * 战斗中 = 当前关；休息室 = 退出前最后一关；从未进过任何关 → 返回 {@link Line#EMPTY}。
 *
 * <h3>v6.6.5 M6 · client / server 解耦</h3>
 * <p>所有数据访问改为通过 {@link ClientStatsCache}：集成服务器（host 自己 + 单机）
 * 委托原 server static 直读（零开销零行为变化）；专用服务器 / LAN 远程客户端走
 * S2C payload 缓存。本类不再直接 import server 包。
 */
public final class TeammateStatsLine {

    private TeammateStatsLine() {}

    /**
     * 单行渲染数据。所有数字均为绝对值；UI 层自己做颜色 / 缩写。
     * <p>v6.6.7 · 加 {@code dps} 字段：HUD 关行 "· N/s" 段使用，session 行不渲染。
     * dps = 玩家最近 5 秒造成伤害量 / 5；非关行场景用 0 占位。
     */
    public record Line(long dealt, long taken, int kills, int assists, int dps, boolean hasData) {
        public static final Line EMPTY = new Line(0L, 0L, 0, 0, 0, false);
    }

    // ---------------------------------------------------------------------
    //  Session 切片（"局:" 行）
    // ---------------------------------------------------------------------

    /** 整局累计行。任何字段非零或玩家曾上过任一榜 → {@code hasData=true}。 */
    public static Line ofSession(UUID uuid) {
        if (uuid == null) return Line.EMPTY;
        long dealt = ClientStatsCache.getDealt(uuid);
        long taken = ClientStatsCache.getTaken(uuid);
        int kills  = ClientStatsCache.getKills(uuid);
        int assist = ClientStatsCache.getAssists(uuid);
        boolean has = dealt > 0 || taken > 0 || kills > 0 || assist > 0;
        // session 行不显示 DPS（用户决策：只关行加 · N/s）
        return new Line(dealt, taken, kills, assist, 0, has);
    }

    // ---------------------------------------------------------------------
    //  Stage 切片（"关:" 行 · 休息室回看上一关）
    // ---------------------------------------------------------------------

    /**
     * 当前关 / 上一关切片行。stageKey 由 dispatcher 决定：
     * <ul>
     *   <li>战斗中 → 当前 stageKey</li>
     *   <li>休息室 → 退出前最后一关</li>
     *   <li>大厅 / 从未进过战斗关 → 返回 {@link Line#EMPTY}（HUD 应隐藏此行）</li>
     * </ul>
     */
    public static Line ofStage(UUID uuid) {
        if (uuid == null) return Line.EMPTY;
        StageKey stageKey = ClientStatsCache.lastSeenStageKey(uuid);
        if (stageKey == null) return Line.EMPTY;

        long dealt = ClientStatsCache.getDealtAt(uuid, stageKey);
        long taken = ClientStatsCache.getTakenAt(uuid, stageKey);
        int kills  = ClientStatsCache.getKillsAt(uuid, stageKey);
        int assist = ClientStatsCache.getAssistsAt(uuid, stageKey);
        // v6.6.7 · 关行末尾 "· N/s" 用最近 5 秒伤害滑窗（不按 stageKey 切片，反映"近期手感"）
        int dps    = ClientStatsCache.recent5sDps(uuid);
        boolean has = dealt > 0 || taken > 0 || kills > 0 || assist > 0;
        return new Line(dealt, taken, kills, assist, dps, has);
    }

    // ---------------------------------------------------------------------
    //  紧凑数字格式化（设计 §5.4 · 4 位以上压缩）
    // ---------------------------------------------------------------------

    /**
     * 把数字按 §5.4 规则缩写：
     * <ul>
     *   <li>&lt; 10000 → 原始整数（如 {@code 1234}）</li>
     *   <li>≥ 10000 且 &lt; 1000000 → {@code XX.Yk}（如 {@code 12.3k}）</li>
     *   <li>≥ 1000000 → {@code X.YM}（如 {@code 1.2M}）</li>
     *   <li>负数取绝对值后前缀 {@code -}（罕见，理论上 stats 不会负）</li>
     * </ul>
     */
    public static String compact(long value) {
        if (value < 0) return "-" + compact(-value);
        if (value < 10_000)        return Long.toString(value);
        if (value < 1_000_000)     return formatTwoSig(value, 1_000.0,    'k');
        if (value < 1_000_000_000) return formatTwoSig(value, 1_000_000.0, 'M');
        return formatTwoSig(value, 1_000_000_000.0, 'G');
    }

    private static String formatTwoSig(long value, double divisor, char suffix) {
        double v = value / divisor;
        // 保留 1 位小数；为避免 12.3k 误读为 1.23k，整数位 ≥ 100 时省略小数（120k）。
        if (v >= 100.0) {
            return ((long) v) + Character.toString(suffix);
        }
        long oneDecimal = Math.round(v * 10.0);
        long whole = oneDecimal / 10;
        long frac  = oneDecimal % 10;
        if (frac == 0) {
            return whole + Character.toString(suffix);
        }
        return whole + "." + frac + suffix;
    }
}
