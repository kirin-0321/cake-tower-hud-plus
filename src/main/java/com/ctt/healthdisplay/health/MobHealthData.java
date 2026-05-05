package com.ctt.healthdisplay.health;

import net.minecraft.text.Text;

/**
 * 头顶血条数据载体。v8.3.6 起 {@code name} 字段从 {@link String} 升级为 {@link Text}
 * （字段名 {@code nameText}），让服务端权威路径 / 客户端 bossbar 兜底路径都能保留
 * translate 键 + Style，客户端按本地 lang 自动渲染中文/英文。
 *
 * <p>派生 getter {@link #name()} 返回 {@code nameText.getString()}，向后兼容
 * {@link com.ctt.healthdisplay.mixin.TeammateHealthMixin} 的字符串匹配
 * （{@code findMobWithHealth} 用渲染后的串去匹配 vanilla 名牌）。
 *
 * <p>{@code nameColor} 字段保留为兼容字段，渲染端不再读取——颜色已写入
 * {@code nameText} 的 Style 中。
 */
public class MobHealthData {

    public Text nameText;
    public Text suffixText;
    public int hp;
    public int maxHP;
    public long lastUpdateTick;
    public boolean targetted;
    public int nameColor = 0xFFFFFFFF;

    public MobHealthData(Text nameText, Text suffixText, int hp, int maxHP, long tick) {
        this.nameText = nameText != null ? nameText : Text.empty();
        this.suffixText = suffixText != null ? suffixText : Text.empty();
        this.hp = hp;
        this.maxHP = maxHP;
        this.lastUpdateTick = tick;
        this.targetted = false;
    }

    /** 派生 getter：渲染后的名字串，用于跟 vanilla 名牌做字符串匹配。 */
    public String name() {
        return nameText != null ? nameText.getString() : "";
    }

    public int getPercent() {
        if (maxHP <= 0) return 100;
        return Math.max(0, Math.min(100, Math.round((float) hp * 100 / maxHP)));
    }
}
