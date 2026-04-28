package com.ctt.healthdisplay.client.probe;

import net.minecraft.util.math.Vec3d;

/**
 * v6.7.x · P0 · 客户端伤害过滤预留接口（本阶段全 PASSTHROUGH）。
 *
 * <p>{@link com.ctt.healthdisplay.client.ClientDamageProbe} 在累加之前调用 {@link #accept}。
 * 返回 false 视为已过滤——不入累加器、不进 chat 调试日志、也不调 attribution。
 * 本阶段（"P0 客户端探针骨架"）始终绑定 {@link #PASSTHROUGH}（不过滤）。
 *
 * <p>未来 v8 阶段接入服务端 {@code DamageFilterPipeline} 的客户端等价：用纯客户端可见数据
 * （{@code MaxHP} / {@code Defence} / {@code RedHearts} 计分板 + {@code DamageShower} 数值）
 * 复刻 G_low / G7a 物理地板 / G3 init-hp-jump / G4 suspect-victim 等规则。届时本接口的实例
 * 字段会被换成具体实现，pipeline 调用方无需任何改动。
 */
public interface FilterHook {

    /**
     * 判定本次粒子事件是否应被纳入统计。
     *
     * @param entityId 粒子的 client-side entity id
     * @param delta    本次增量伤害（&gt; 0）
     * @param pos      粒子的当前世界坐标
     * @return true = 入账；false = 过滤掉
     */
    boolean accept(int entityId, int delta, Vec3d pos);

    /** 默认实现：永远返回 true。 */
    FilterHook PASSTHROUGH = (id, d, p) -> true;
}
