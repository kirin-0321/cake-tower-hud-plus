package com.ctt.healthdisplay.client;

import com.ctt.healthdisplay.health.StatsData;
import com.ctt.healthdisplay.mixin.BossBarHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import net.minecraft.text.TranslatableTextContent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 癫狂（Berserk）状态客户端探测。
 *
 * <p>主路径：{@link StatsData#isBerserkActive()}（属性面板含 Berserk / 癫狂 行）。
 * 兜底：个人 bossbar 被替换成战斗独白（{@code All I can think about, is to fight.} /
 * 中文 {@code 我只有一个想法，战…………}）—— 癫狂时 bossbar 不含玩家名，Stats 推送若滞后会漏判。
 * <p>带 2s 滞后释放，避免 1 Hz 属性推送与 bossbar 翻页之间的闪烁。
 */
public final class BerserkDetector {

    private static final String BERSERK_TRANSLATE_KEY = "Berserk";
    private static final String BERSERK_FIGHT_BAR_KEY = "All I can think about, is to fight.";
    private static final String BERSERK_ZH_PLAIN = "\u7656\u72c2";
    private static final String BERSERK_FIGHT_ZH_PLAIN = "\u6211\u53ea\u6709\u4e00\u4e2a\u60f3\u6cd5\uff0c\u6218\u2026\u2026\u2026\u2026";

    private static final int CLEAR_DELAY_TICKS = 40;

    private static volatile boolean stickyActive = false;
    private static int clearCooldownTicks = 0;

    private BerserkDetector() {}

    /** 每客户端 tick 调用，更新滞后状态。 */
    public static void tick(MinecraftClient client, StatsData statsData) {
        boolean raw = probeRaw(client, statsData);
        if (raw) {
            stickyActive = true;
            clearCooldownTicks = 0;
        } else if (stickyActive) {
            if (++clearCooldownTicks >= CLEAR_DELAY_TICKS) {
                stickyActive = false;
            }
        }
    }

    /** 当前是否应视为处于癫狂（含 sticky 窗口）。 */
    public static boolean isActive() {
        return stickyActive;
    }

    /** 断线 / 切服时清状态，避免上一局 sticky 残留。 */
    public static void reset() {
        stickyActive = false;
        clearCooldownTicks = 0;
    }

    private static boolean probeRaw(MinecraftClient client, StatsData statsData) {
        if (statsData != null && statsData.isBerserkActive()) return true;
        return probeBerserkBossbar(client);
    }

    private static boolean probeBerserkBossbar(MinecraftClient client) {
        if (client.player == null || client.inGameHud == null) return false;
        try {
            BossBarHudAccessor accessor = (BossBarHudAccessor) client.inGameHud.getBossBarHud();
            Map<UUID, ClientBossBar> bars = accessor.getBossBars();
            if (bars == null || bars.isEmpty()) return false;
            for (ClientBossBar bar : bars.values()) {
                if (textMarksBerserkFightBar(bar.getName())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean textMarksBerserkFightBar(Text text) {
        if (text == null) return false;
        if (text.getContent() instanceof TranslatableTextContent ttc) {
            String key = ttc.getKey();
            if (BERSERK_TRANSLATE_KEY.equals(key) || BERSERK_FIGHT_BAR_KEY.equals(key)) {
                return true;
            }
        }
        AtomicBoolean hit = new AtomicBoolean(false);
        text.visit((style, string) -> {
            if (string == null || string.isEmpty()) return Optional.empty();
            String t = string.trim();
            if (BERSERK_ZH_PLAIN.equals(t)
                    || BERSERK_TRANSLATE_KEY.equalsIgnoreCase(t)
                    || BERSERK_FIGHT_ZH_PLAIN.equals(t)
                    || BERSERK_FIGHT_BAR_KEY.equalsIgnoreCase(t)) {
                hit.set(true);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return hit.get();
    }
}
