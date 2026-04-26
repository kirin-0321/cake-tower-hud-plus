package com.ctt.healthdisplay.mixin;

import com.ctt.healthdisplay.CttHealthDisplay;
import com.ctt.healthdisplay.health.HealthData;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mixin(BossBarHud.class)
public class BossBarHudRenderMixin {

    @Shadow
    @Final
    Map<UUID, ClientBossBar> bossBars;

    @Unique
    private final Map<UUID, ClientBossBar> ctt_removedBars = new HashMap<>();

    @Inject(method = "render", at = @At("HEAD"))
    private void ctt_beforeRender(DrawContext context, CallbackInfo ci) {
        
        ctt_removedBars.clear();
        Set<UUID> toHide = HealthData.getHiddenBarUUIDs();
        for (UUID uuid : toHide) {
            ClientBossBar bar = bossBars.remove(uuid);
            if (bar != null) {
                ctt_removedBars.put(uuid, bar);
            }
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void ctt_afterRender(DrawContext context, CallbackInfo ci) {
        if (!ctt_removedBars.isEmpty()) {
            bossBars.putAll(ctt_removedBars);
            ctt_removedBars.clear();
        }
    }
}
