package com.ctt.healthdisplay.server.mixin;

import com.ctt.healthdisplay.server.AttackerProbe;
import com.ctt.healthdisplay.config.ServerConfig;
import com.ctt.healthdisplay.server.CttStatsServer;
import com.ctt.healthdisplay.server.DamageProbe;
import com.ctt.healthdisplay.server.PlayerFireLog;
import com.ctt.healthdisplay.server.PlayerHitLog;
import com.ctt.healthdisplay.server.ScoreDeltaTracker;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardScore;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.0.1/v6.0.4/v6.0.5 · 分派式 scoreboard 写入 Mixin。
 *
 * <h2>分派目标</h2>
 * <ul>
 *   <li>{@code RedHearts} 下降（v6.6.0，可配置）→ {@link DamageProbe#recordFromRedHearts}
 *       —— 以计分板为源的"实扣红血"伤害（默认优先于 DamageShower 粒子线）</li>
 *   <li>{@code DamageShower}（v6.0.1）→ {@link DamageProbe#record}
 *       —— 最终伤害（粒子），{@code useRedHeartsTally} 为真时跳过</li>
 *   <li>{@code MeleeDMG / BulletDMG / ForceDMG / FireDMG / WaterDMG /
 *       IceDMG / DarkDMG / LightDMG / ElectricDMG}（v6.0.4）→
 *       {@link AttackerProbe#record} —— 攻击者归属诊断</li>
 *   <li>任何 criterion = {@code minecraft.custom:minecraft.damage_dealt} 的 objective（v6.0.5）
 *       → {@link PlayerHitLog#record} —— 近战 vanilla hit 信号（70+ 种武器自动覆盖）</li>
 *   <li>任何 criterion = {@code minecraft.used:minecraft.carrot_on_a_stick} 或
 *       {@code minecraft.dropped:minecraft.carrot_on_a_stick} 的 objective（v6.0.5 / v6.7.8）
 *       → {@link PlayerFireLog#record} —— 右键 / Q 丢弃开火信号（远程武器兜底，含按 Q 触发的武器）</li>
 * </ul>
 *
 * <h2>v8.0.0 性能修复</h2>
 * <p>{@link Scoreboard#updateScore} 是<b>所有</b> scoreboard 写入的单一汇聚点：CTT 地图大量
 * 使用 {@code execute as @e[scores={...=1..}]} 类批量赋值，本 mixin 每 tick 可被回调
 * 几百到上千次。原版本每次都做 4-5 次 {@code String.equals} + {@code criterion.getName()}
 * 字符串比较，在专用服务器上是 TPS 下降的明显单点。
 *
 * <p>修复：用 {@link #OBJ_CLASSIFY} 把 {@link ScoreboardObjective} 实例 → 分类码 byte 永久
 * 缓存（vanilla {@code ScoreboardObjective} 不覆写 {@code equals/hashCode} → 默认 identity，
 * {@code ConcurrentHashMap} 等价 IdentityHashMap 行为且无锁并发安全）。第二次见到同 objective
 * 实例直接 O(1) 哈希查 → byte switch dispatch。99% 调用是 {@code ignore} 路径直接 return。
 *
 * <h2>为什么合在一个 Mixin 里</h2>
 * <p>{@link Scoreboard#updateScore} 是所有 scoreboard 写入的单一汇聚点。单入口好维护。
 *
 * <p>Client/Server 双生活：{@code Scoreboard} 基类也被客户端 ClientWorld 实例化。
 * 用 {@code this instanceof ServerScoreboard} 过滤掉客户端副本。
 */
@Mixin(Scoreboard.class)
public class ScoreboardUpdateMixin {

    // v8.0.0 · objective 分类码。0 = 不关心，直接 return（最热路径）。
    private static final byte CLS_IGNORE        = 0;
    private static final byte CLS_REDHEARTS     = 1;
    private static final byte CLS_DAMAGESHOWER  = 2;
    private static final byte CLS_TRACKED_DMG   = 3;
    private static final byte CLS_DAMAGE_DEALT  = 4;
    private static final byte CLS_CARROT        = 5;
    private static final byte CLS_BOW           = 6;

    /**
     * v8.0.0 · objective → 分类码缓存。
     *
     * <ul>
     *   <li>vanilla {@link ScoreboardObjective} 默认 {@code equals/hashCode} = Object identity，
     *       {@link ConcurrentHashMap} 等价于 IdentityHashMap 行为，且支持无锁并发读
     *       （与 {@code Collections.synchronizedMap} + IdentityHashMap 相比，热路径性能高一档）。</li>
     *   <li>cache 永不主动失效：CTT 地图 objective 数量稳定（70+ 个武器 *DMG +
     *       几个核心 RedHearts/DamageShower 等），entrySet 上限 ~100。即使 objective
     *       被 remove + 同名重建，新实例下次会被重新分类，旧实例自然被 GC（被 Scoreboard 解引用后）。</li>
     * </ul>
     */
    private static final Map<ScoreboardObjective, Byte> OBJ_CLASSIFY = new ConcurrentHashMap<>();

    /**
     * 第一次见到某 objective 时跑一遍完整字符串比对，把分类码存入 {@link #OBJ_CLASSIFY}。
     * 之后所有同实例的写入直接走 cache。
     */
    private static byte classify(ScoreboardObjective objective) {
        String objName = objective.getName();
        if ("RedHearts".equals(objName))        return CLS_REDHEARTS;
        if ("DamageShower".equals(objName))     return CLS_DAMAGESHOWER;
        if (AttackerProbe.isTracked(objName))   return CLS_TRACKED_DMG;
        // criterion 类匹配：覆盖 70+ 个动态命名的武器 *DMG / used:bow / used:carrot 等 stat objective
        if (PlayerHitLog.isDamageDealtStat(objective)) return CLS_DAMAGE_DEALT;
        if (PlayerFireLog.isRightClickStat(objective)) return CLS_CARROT;
        if (PlayerFireLog.isBowReleaseStat(objective)) return CLS_BOW;
        return CLS_IGNORE;
    }

    @Inject(
            method = "updateScore(Lnet/minecraft/scoreboard/ScoreHolder;Lnet/minecraft/scoreboard/ScoreboardObjective;Lnet/minecraft/scoreboard/ScoreboardScore;)V",
            at = @At("RETURN")
    )
    private void ctt$onScoreUpdate(ScoreHolder holder, ScoreboardObjective objective,
                                   ScoreboardScore score, CallbackInfo ci) {
        if (!((Object) this instanceof ServerScoreboard)) return;

        // v8.0.0 · 热路径：先查 cache。99% 的 scoreboard 写入会走"未命中 cache → IGNORE → return"
        // 或"命中 cache → IGNORE → return"短路径，零字符串比较。
        Byte clsBoxed = OBJ_CLASSIFY.get(objective);
        byte cls;
        if (clsBoxed == null) {
            cls = classify(objective);
            OBJ_CLASSIFY.put(objective, cls);
        } else {
            cls = clsBoxed;
        }
        if (cls == CLS_IGNORE) return;

        int value = score.getScore();
        long tick = DamageProbe.currentTick();
        MinecraftServer server = CttStatsServer.getServer();

        switch (cls) {
            case CLS_REDHEARTS -> {
                // useRedHeartsTally 是动态配置：cache 不能绑死，运行时再判
                if (!ServerConfig.INSTANCE.useRedHeartsTally) return;
                String holderName = holder.getNameForScoreboard();
                int delta = ScoreDeltaTracker.observeDelta(holderName, "RedHearts", value);
                if (delta < 0) {
                    int dmg = -delta;
                    if (server != null) {
                        DamageProbe.recordFromRedHearts(server, holder, dmg, tick);
                    }
                }
            }
            case CLS_DAMAGESHOWER -> DamageProbe.record(holder, value);
            case CLS_TRACKED_DMG -> {
                // v6.3.2 修复：mixin 拿到的 value 是 scoreboard 当前累计值而非本次增量。
                //   典型症状：FireDMG 在 DoT 期间被连续累加 20 → 40 → 60 → 2000，
                //   每次都会被误当成一次新伤害记录到聊天栏与 PlayerDamageStats。
                //   改用 ScoreDeltaTracker 计算 delta，只对 delta > 0 的事件触发归属；
                //   delta <= 0（reset 到 0 / 回收）一律忽略。
                String holderName = holder.getNameForScoreboard();
                String objName = objective.getName();
                int delta = ScoreDeltaTracker.observeDelta(holderName, objName, value);
                if (delta > 0) {
                    AttackerProbe.record(server, objName, holder, delta);
                }
            }
            case CLS_DAMAGE_DEALT -> PlayerHitLog.record(server, holder, objective, value, tick);
            case CLS_CARROT -> PlayerFireLog.record(server, holder, objective, value, tick);
            case CLS_BOW -> PlayerFireLog.recordBow(server, holder, objective, value, tick);
            default -> { /* unreachable */ }
        }
    }
}
