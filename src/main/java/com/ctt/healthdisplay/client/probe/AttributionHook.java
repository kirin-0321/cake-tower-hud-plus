package com.ctt.healthdisplay.client.probe;

import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * v6.7.x · P0 · 客户端伤害归属预留接口（本阶段全 NOOP）。
 *
 * <p>{@link com.ctt.healthdisplay.client.ClientDamageProbe} 持有一个实例字段，在每次观测到
 * 粒子时调用 {@link #attribute}。返回 attacker UUID 表示已识别归属；返回 null 表示未归属。
 * 本阶段（"P0 客户端探针骨架"）选定 scope = "全场聚合"语义——攻击者维度暂不参与统计裁切，
 * 因此始终绑定 {@link #NOOP}。
 *
 * <p>未来 v8 阶段引入 attribution 实现时，可考虑：
 * <ul>
 *   <li>粒子位置 + 自己玩家手持武器（{@code WeaponDamageRegistry}）匹配</li>
 *   <li>客户端版 {@code PlayerFireLog}（监听自己/他人的右键开火事件）</li>
 *   <li>距离 / 时序启发——参考服务端 {@code AttackerProbe.attribute} 的九层归属逻辑</li>
 * </ul>
 */
public interface AttributionHook {

    /**
     * 试图把本次粒子归属到某个玩家。
     *
     * @param entityId 粒子的 client-side entity id
     * @param delta    本次增量伤害（&gt; 0）
     * @param pos      粒子的当前世界坐标
     * @return attacker UUID；返回 null 表示"未归属"——调用方按"全场计入"语义继续累加
     */
    @Nullable UUID attribute(int entityId, int delta, Vec3d pos);

    /** 默认实现：永远返回 null。 */
    AttributionHook NOOP = (id, d, p) -> null;
}
