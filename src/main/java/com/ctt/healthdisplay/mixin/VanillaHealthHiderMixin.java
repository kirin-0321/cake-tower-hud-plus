package com.ctt.healthdisplay.mixin;

import com.ctt.healthdisplay.config.ModConfig;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityRenderer.class)
public class VanillaHealthHiderMixin {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("CTT-LabelHider");
    @Unique
    private static long lastLog = 0;

    @Inject(
            method = "renderLabelIfPresent(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void ctt_hideVanillaHealth(PlayerEntityRenderState state, Text text, MatrixStack matrices,
                                       VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (!ModConfig.INSTANCE.showTeammateHeadHP) return;

        String label = text.getString();

        long now = System.currentTimeMillis();
        if (now - lastLog > 2000) {
            lastLog = now;
            StringBuilder codepoints = new StringBuilder();
            for (int i = 0; i < Math.min(label.length(), 20); i++) {
                codepoints.append(String.format("U+%04X ", (int) label.charAt(i)));
            }
            LOGGER.info("[DEBUG] label='{}', codepoints=[{}], len={}", label, codepoints.toString().trim(), label.length());
        }

        String trimmed = label.trim();
        if (trimmed.matches("\\d+.*") && !trimmed.matches("[A-Za-z_].*")) {
            String displayName = state.displayName != null ? state.displayName.getString() : "";
            LOGGER.info("[HIDE] Cancelling vanilla health '{}' for '{}'", trimmed, displayName);
            ci.cancel();
        }
    }
}
