package com.ctt.healthdisplay.client.probe;

import net.minecraft.util.math.Vec3d;

/**
 * v6.7.x · P0 · 客户端 DamageShower 粒子的"单次增量观测"事件载体。
 *
 * <p>由 {@link com.ctt.healthdisplay.client.ClientDamageProbe#onClientTick} 在每 tick 扫描到
 * "已知粒子的 score 上涨"或"新粒子首次见到 score &gt; 0"时构造。{@link #delta} 永远 &gt; 0，
 * 表示本 tick 该粒子贡献的伤害增量；{@link #totalScore} 是观测时刻该粒子的累计 score
 * （{@code damage_shower.mcfunction} 单调累加，最终 = 该粒子整生命周期的总伤害）。
 *
 * @param entityId    粒子的 client-side entity id（{@link net.minecraft.entity.Entity#getId}）
 * @param delta       本次增量（&gt; 0）
 * @param totalScore  该粒子当前累计 DamageShower score
 * @param pos         粒子当前世界坐标
 * @param clientTick  观测时的客户端 tick 计数（非 server tick）
 */
public record ParticleObservation(int entityId, int delta, int totalScore,
                                  Vec3d pos, long clientTick) {}
