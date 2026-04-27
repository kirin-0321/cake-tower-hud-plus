package com.ctt.healthdisplay.server.mixin;

import com.ctt.healthdisplay.server.AttackerProbe;
import com.ctt.healthdisplay.config.ServerConfig;
import com.ctt.healthdisplay.server.CttStatsServer;
import com.ctt.healthdisplay.server.DamageProbe;
import com.ctt.healthdisplay.server.PlayerFireLog;
import com.ctt.healthdisplay.server.PlayerHitLog;
import com.ctt.healthdisplay.server.ScoreDeltaTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardScore;
import net.minecraft.scoreboard.ServerScoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v6.0.1/v6.0.4/v6.0.5 · 分派式 scoreboard 写入 Mixin。
 *
 * <h2>分派目标</h2>
 * <ul>
 *   <li>{@code RedHearts} 下降（v6.6.0，可配置）→ {@link DamageProbe#recordFromRedHearts}
 *       —— 以计分板为源的“实扣红血”伤害（默认优先于 DamageShower 粒子线）</li>
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
 * <p>为什么 v6.0.5 不再硬编码 objective 名字？
 * 地图注册了 70+ 个武器专用 {@code damage_dealt} objective（{@code SwansLustDMG} / {@code PumpkinCarverKnifeDMG} …），
 * 名字会随更新变化。用 criterion 类型判断比维护白名单稳定得多。
 *
 * <h2>为什么合在一个 Mixin 里</h2>
 * <p>{@link Scoreboard#updateScore} 是所有 scoreboard 写入的单一汇聚点。单入口好维护。
 *
 * <p>Client/Server 双生活：{@code Scoreboard} 基类也被客户端 ClientWorld 实例化。
 * 用 {@code this instanceof ServerScoreboard} 过滤掉客户端副本。
 */
@Mixin(Scoreboard.class)
public class ScoreboardUpdateMixin {

    @Inject(
            method = "updateScore(Lnet/minecraft/scoreboard/ScoreHolder;Lnet/minecraft/scoreboard/ScoreboardObjective;Lnet/minecraft/scoreboard/ScoreboardScore;)V",
            at = @At("RETURN")
    )
    private void ctt$onScoreUpdate(ScoreHolder holder, ScoreboardObjective objective,
                                   ScoreboardScore score, CallbackInfo ci) {
        if (!((Object) this instanceof ServerScoreboard)) return;

        String objName = objective.getName();
        int value = score.getScore();

        // 0) RedHearts 下降 = 对任意实体本 tick 的“实扣红血”量（地图 damage 管线终点之一）
        if (ServerConfig.INSTANCE.useRedHeartsTally && "RedHearts".equals(objName)) {
            String holderName = holder.getNameForScoreboard();
            int delta = ScoreDeltaTracker.observeDelta(holderName, objName, value);
            if (delta < 0) {
                int dmg = -delta;
                MinecraftServer s = CttStatsServer.getServer();
                if (s != null) {
                    DamageProbe.recordFromRedHearts(s, holder, dmg, DamageProbe.currentTick());
                }
            }
            return;
        }

        // 1) DamageShower → 主管线（session 累加 + victim 解析日志）
        if ("DamageShower".equals(objName)) {
            DamageProbe.record(holder, value);
            return;
        }

        // 2) 9 种 *DMG 聚合 → 归属诊断（调起九层堆栈）
        //
        // v6.3.2 修复：mixin 拿到的 value 是 scoreboard 当前累计值而非本次增量。
        //   典型症状：FireDMG 在 DoT 期间被连续累加 20 → 40 → 60 → 2000，
        //   每次都会被误当成一次新伤害记录到聊天栏与 PlayerDamageStats。
        //   改用 ScoreDeltaTracker 计算 delta，只对 delta > 0 的事件触发归属；
        //   delta <= 0（reset 到 0 / 回收）一律忽略。
        if (AttackerProbe.isTracked(objName)) {
            String holderName = holder.getNameForScoreboard();
            int delta = ScoreDeltaTracker.observeDelta(holderName, objName, value);
            if (delta > 0) {
                AttackerProbe.record(CttStatsServer.getServer(), objName, holder, delta);
            }
            return;
        }

        // 3) vanilla damage_dealt stat（~70+ 武器特定 objective）→ PlayerHitLog
        if (PlayerHitLog.isDamageDealtStat(objective)) {
            PlayerHitLog.record(CttStatsServer.getServer(), holder, objective, value,
                    DamageProbe.currentTick());
            return;
        }

        // 4) RightClick (carrot_on_a_stick 使用) / DropCarrot (carrot_on_a_stick 丢弃) → PlayerFireLog
        //    v6.7.8 · 把 minecraft.dropped:minecraft.carrot_on_a_stick 也接进来——
        //    地图里部分武器是按 Q 丢弃 carrot_on_a_stick 触发的（不是右键），
        //    没有这条信号时它们会全部掉到 L9-NONE 或被 L8 误归属。
        //    isRightClickStat 内部已合并 used / dropped 两种 criterion，归属层不区分。
        if (PlayerFireLog.isRightClickStat(objective)) {
            PlayerFireLog.record(CttStatsServer.getServer(), holder, objective, value,
                    DamageProbe.currentTick());
            return;
        }

        // 5) v6.3.7 · 弓/弩/三叉戟释放 stat → PlayerFireLog (kind=BOW) → L7 BOW_RELEASE 归属（v6.5.2 升位）
        if (PlayerFireLog.isBowReleaseStat(objective)) {
            PlayerFireLog.recordBow(CttStatsServer.getServer(), holder, objective, value,
                    DamageProbe.currentTick());
        }
    }
}
