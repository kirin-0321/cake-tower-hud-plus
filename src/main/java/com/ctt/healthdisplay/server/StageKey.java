package com.ctt.healthdisplay.server;

import net.minecraft.nbt.NbtCompound;

import java.util.Objects;

/**
 * v6.4.0 · 关卡 / 会话标识符（占位阶段）。
 *
 * <p>按 `V6_STATS_DEV_PLAN.md` §0.3 的约定，阶段 ① 起，所有统计 API 的签名都带
 * {@code StageKey stageKey} 参数，当前阶段（①②③）一律传 {@code null}。阶段 ④
 * 才开始真正填五元组 {@code (gameId, tier, floor, stageType, stageNum)}，届时
 * 只需修改写入点的参数和读取端的聚合逻辑，不改 API 表面。
 *
 * <h2>设计取舍</h2>
 * <ul>
 *   <li>五个字段全部可空——"整个会话"用 {@link #NULL}（等价于 null）表示</li>
 *   <li>Record + {@link #isSession()}：前期所有写入都走 "整个会话" 分支</li>
 *   <li>字段类型都是 {@link String}，方便直接吃数据包里的 scoreboard 值（都是整数字符串）
 *       和非整数值（如 "dungeon" / "shop" 这种 stage holder），不需要两套类型</li>
 * </ul>
 */
public record StageKey(String gameId, String tier, String floor,
                       String stageType, String stageNum) {

    /** "整个会话" 的占位 key。阶段 ①②③ 所有写入都用它（或 null，两者等价）。 */
    public static final StageKey NULL = new StageKey(null, null, null, null, null);

    /**
     * @return 本 key 是否代表"整个会话"（五字段全空）。
     *         null 也被上层当作"整个会话"，所以 {@link #isSession(StageKey)} 更方便。
     */
    public boolean isSession() {
        return gameId == null && tier == null && floor == null
                && stageType == null && stageNum == null;
    }

    /** 静态版：{@code null} 也算 session，避免调用方到处写 null check。 */
    public static boolean isSession(StageKey key) {
        return key == null || key.isSession();
    }

    /** 归一化：null 和 NULL 都返回 NULL，避免作 map key 时撞出两条。 */
    public static StageKey orNull(StageKey key) {
        return key == null ? NULL : key;
    }

    /** 阶段 ④ 会替换 / 补齐，当前仅用于日志诊断。 */
    @Override
    public String toString() {
        if (isSession()) return "Session";
        return String.format("Stage(g=%s,t=%s,f=%s,s=%s,n=%s)",
                gameId, tier, floor, stageType, stageNum);
    }

    /** 仅用作 Map key 时考虑（目前所有值都在 NULL，不会真撞）。 */
    @Override
    public int hashCode() {
        return Objects.hash(gameId, tier, floor, stageType, stageNum);
    }

    // =========================================================================
    //  v6.6.1 · M2 · NBT 编解码（持久化用）
    // =========================================================================

    /**
     * 把本 StageKey 写入 NBT。null 字段写空字符串占位（fromNbt 会还原回 null）。
     * Session（全空 key）也会写入，但持久化层一般不会序列化 session key 的 stage 桶。
     */
    public NbtCompound toNbt() {
        NbtCompound t = new NbtCompound();
        t.putString("g", gameId    == null ? "" : gameId);
        t.putString("t", tier      == null ? "" : tier);
        t.putString("f", floor     == null ? "" : floor);
        t.putString("s", stageType == null ? "" : stageType);
        t.putString("n", stageNum  == null ? "" : stageNum);
        return t;
    }

    /** 从 NBT 还原。空字符串 → null。 */
    public static StageKey fromNbt(NbtCompound t) {
        if (t == null) return NULL;
        return new StageKey(
                emptyToNull(t.getString("g")),
                emptyToNull(t.getString("t")),
                emptyToNull(t.getString("f")),
                emptyToNull(t.getString("s")),
                emptyToNull(t.getString("n"))
        );
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
