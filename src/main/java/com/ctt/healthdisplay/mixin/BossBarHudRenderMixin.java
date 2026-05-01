package com.ctt.healthdisplay.mixin;

import com.ctt.healthdisplay.health.HealthData;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端侧隐藏指定 boss bar 的实现。
 *
 * <p>历史（v8.2.0 之前）：在 {@code render} 方法的 HEAD / RETURN 两头
 * 把要隐藏的 bar 从 {@code bossBars} Map 里 {@code remove} 再 {@code putAll} 塞回。
 * 这个做法有三个副作用导致队友 bossbar 与队友 HUD 闪烁（反馈 2026-05-01，{@code docs/异常.md} 问题 3）：
 * <ol>
 *   <li>{@code bossBars} 是 {@code LinkedHashMap}，remove + put 会把被隐藏的 bar 永远推到末尾，
 *       打乱 vanilla 的插入顺序，影响 bar 上下排序（例如 Tower 剩余层数 bar 的 y 坐标被挤）。</li>
 *   <li>render 方法顶部有 {@code if (bossBars.isEmpty()) return;} 判定：
 *       当前整个 HUD 只有 teamBar 这一条时，HEAD inject 把它移除后 Map 变空，
 *       vanilla 直接 early return，{@code Profilers.push("bossHealth")} 都没执行；
 *       RETURN 照样 putAll 恢复，但下一帧再 remove → 再 early return…… 状态在每一帧之间抖动。</li>
 *   <li>更严重：任何其他客户端代码（包括本模组自己的
 *       {@link HealthData#parseBossBarData} 每 tick 一次）如果恰好在 HEAD 与 RETURN 之间
 *       读一次 {@code bossBars}，会看到"空 Map" → {@code hasTeamBossBar} 跳 false
 *       → {@code teammates.clear()} → 队友 HUD 内容被清空一帧 → 跟着 boss bar 一起闪。</li>
 * </ol>
 *
 * <p>v8.2.0 起改用 {@link Redirect} 拦截 {@code render} 里对 {@code bossBars.values()} 的调用，
 * 返回一个过滤掉隐藏 UUID 的快照 List。原 Map 从不被修改，以上三个副作用全部消除。
 * {@code isEmpty()} 判定仍基于完整的原 Map（表现为"即使所有 bar 都被隐藏，render 也会走完循环体但零迭代"），
 * 唯一的额外开销是每帧一次 O(n) 构造 List，n = bossBars.size()，通常 ≤ 5，忽略不计。
 *
 * <p>基于 1.21.4 yarn_mappings 1.21.4+build.8 的字节码确认：
 * {@code render(Lnet/minecraft/client/gui/DrawContext;)V} 内部确实调用了
 * {@code this.bossBars.values()} 来获取迭代源（javap -c 验证）。
 * 未来 vanilla 若改用 {@code entrySet()} / {@code keySet()} 需要相应更新 target。
 */
@Mixin(BossBarHud.class)
public abstract class BossBarHudRenderMixin {

    @Shadow
    @Final
    Map<UUID, ClientBossBar> bossBars;

    @Redirect(
            method = "render(Lnet/minecraft/client/gui/DrawContext;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;values()Ljava/util/Collection;"
            )
    )
    private Collection<ClientBossBar> ctt_filterBossBars(Map<UUID, ClientBossBar> map) {
        Set<UUID> hidden = HealthData.getHiddenBarUUIDs();
        if (hidden.isEmpty()) {
            // 快路径：没有要隐藏的，直接返回原集合，零额外分配。
            return map.values();
        }
        List<ClientBossBar> filtered = new ArrayList<>(Math.max(0, map.size() - hidden.size()));
        for (Map.Entry<UUID, ClientBossBar> entry : map.entrySet()) {
            if (!hidden.contains(entry.getKey())) {
                filtered.add(entry.getValue());
            }
        }
        return filtered;
    }
}
