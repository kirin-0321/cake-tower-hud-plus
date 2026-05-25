package com.ctt.healthdisplay.client;

import com.ctt.healthdisplay.config.ModConfig;
import com.ctt.healthdisplay.health.StatsData;

/**
 * 癫狂（Berserk）期间是否抑制本模组 HUD 的统一判定。
 *
 * <p>调用链：各渲染入口 → {@link #shouldSuppressModHud(StatsData)}
 * → {@link ModConfig#hideHudOnBerserk} + {@link StatsData#isBerserkActive()}。
 */
public final class HudRenderGate {

    private HudRenderGate() {}

    /**
     * @param statsData 当前会话属性数据（通常为 {@link com.ctt.healthdisplay.CttHealthDisplay#statsData}）
     * @return {@code true} 时应跳过本模组全部 HUD 绘制（2D + 3D 头顶条）
     */
    public static boolean shouldSuppressModHud(StatsData statsData) {
        if (!ModConfig.INSTANCE.hideHudOnBerserk) return false;
        return BerserkDetector.isActive();
    }
}
