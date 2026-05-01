package com.ctt.healthdisplay.server;

import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreHolder;

/**
 * v8.3.0 · M7 · 服务端 scoreboard 读数 null 防御小工具。
 *
 * <p>CTT 地图里所有 mob / 玩家属性都走 scoreboard；但 objective 是否存在、
 * 该 holder 是否有 score 这两步每次都要手写 null 判断，堆积在各 broadcaster 里
 * 没有意义。抽出 {@link #readOrZero} 统一成一行。
 *
 * <p>未来 M8（玩家属性）/ M9（队友血量）也走这条路径。
 */
public final class ScoreboardReader {

    private ScoreboardReader() {}

    /**
     * 读某个 holder 在某个 objective 上的整数 score；objective 不存在 / 没 score 时返回 0。
     * <p>对应 vanilla {@link Scoreboard#getScore(ScoreHolder, ScoreboardObjective)}，
     * 但 null 检查展开到这里。
     */
    public static int readOrZero(Scoreboard sb, String objectiveName, ScoreHolder holder) {
        if (sb == null || objectiveName == null || holder == null) return 0;
        ScoreboardObjective obj = sb.getNullableObjective(objectiveName);
        if (obj == null) return 0;
        ReadableScoreboardScore score = sb.getScore(holder, obj);
        if (score == null) return 0;
        return score.getScore();
    }

    /** 判断某 objective 是否存在于 scoreboard。 */
    public static boolean hasObjective(Scoreboard sb, String objectiveName) {
        return sb != null && objectiveName != null && sb.getNullableObjective(objectiveName) != null;
    }
}
