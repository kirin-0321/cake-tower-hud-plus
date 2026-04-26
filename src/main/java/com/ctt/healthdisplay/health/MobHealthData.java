package com.ctt.healthdisplay.health;

import net.minecraft.text.Text;

public class MobHealthData {

    public final String name;
    public Text suffixText;
    public int hp;
    public int maxHP;
    public long lastUpdateTick;
    public boolean targetted;
    public int nameColor = 0xFFFFFFFF;

    public MobHealthData(String name, Text suffixText, int hp, int maxHP, long tick) {
        this.name = name;
        this.suffixText = suffixText;
        this.hp = hp;
        this.maxHP = maxHP;
        this.lastUpdateTick = tick;
        this.targetted = false;
    }

    public int getPercent() {
        if (maxHP <= 0) return 100;
        return Math.max(0, Math.min(100, Math.round((float) hp * 100 / maxHP)));
    }
}
