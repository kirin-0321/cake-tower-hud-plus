package com.ctt.healthdisplay.health;

import com.ctt.healthdisplay.config.ModConfig;
import com.ctt.healthdisplay.mixin.BossBarHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HealthData {

    public int healthPercent;
    public int lives;

    public int allHearts;
    public int maxHP;
    public int mana;
    public int maxMana;
    public int coins;
    // Joey (ClassPassive=5 / Vampire) 独有的鲜血值。数据来源：个人 bossbar 标题里多出的 `(Blood x/y)` 段。
    // 仅 Joey 的 bossbar 会写这段，所以 hasBlood 天然等价于"当前玩家是 Joey"。
    public int blood;
    public int maxBlood;
    public boolean hasBlood;

    // 果冻斯旺 (ClassPassive=14 / Swan Jelly) 独有的动量值。数据来源：个人 bossbar 标题里多出的 `(Velocity x/y)` 段。
    // 数据包里最大值是硬编码字面量 `/6`（不是 scoreboard），但我们依旧按 `-?\d+/-?\d+` 解析以保持健壮性。
    // bossbar 原生颜色 light_purple (#FF55FF)；HUD 顶行用同色 + ⚡ 图标呈现，占用原本 `✦mana/maxMana` 的位置。
    public int velocity;
    public int maxVelocity;
    public boolean hasVelocity;

    public float bossBarPercent;
    public boolean hasBossBarData;
    public boolean hasPersonalBossBar;
    public boolean hasTeamBossBar;
    public boolean hasManaField;
    // 癫狂 fallback：bossbar 文字被替换成装饰内容解析不出 HP 时，改用 /trigger ViewStats 的心数据重建。
    //   maxHP = StatsData.redHearts（红心上限）
    //   allHearts = red + soul + black + blue（所有心种的总和 = 当前总 HP）
    public boolean hasStatsFallback;
    private StatsData statsDataRef;

    public void setStatsData(StatsData s) { this.statsDataRef = s; }

    public final List<TeammateData> teammates = new ArrayList<>();

    private static volatile Map<String, TeammateData> teammateMap = Collections.emptyMap();
    private static volatile Set<UUID> hiddenBarUUIDs = Collections.emptySet();
    private static volatile Map<java.util.UUID, MobHealthData> mobHealthMap = new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile List<MobBossBarEntry> activeMobBars = Collections.emptyList();

    public static Map<String, TeammateData> getTeammateMap() {
        return teammateMap;
    }

    public static Set<UUID> getHiddenBarUUIDs() {
        return hiddenBarUUIDs;
    }

    public static Map<java.util.UUID, MobHealthData> getMobHealthMap() {
        // v8.3.0 · M7 · 服务端权威 cache 命中时返回只读 snapshot；客户端渲染端 (TeammateWorldRenderer
        // 等) 得到的 map 与本地 mobHealthMap 结构完全一致 —— 字段 (name/suffixText/hp/maxHP/
        // targetted/nameColor) 在 ClientMobHealthCache.onPayload 里已按 MobHealthData 构造完成。
        // 未命中 (服务端没装 mod / 失鲜超过 5 s) 自动回落本地 mobHealthMap，由 CttHealthDisplay.
        // updateMobTracking 负责填充，行为与 v8.2 一致。
        if (com.ctt.healthdisplay.client.ClientMobHealthCache.isFresh()) {
            return com.ctt.healthdisplay.client.ClientMobHealthCache.viewSnapshot();
        }
        return mobHealthMap;
    }

    public static boolean hasMobBar() { return !activeMobBars.isEmpty(); }
    public static List<MobBossBarEntry> getActiveMobBars() { return activeMobBars; }

    private float displayPercent;
    private float displayAllHearts;
    private float displayMaxHP;
    private float displayMana;
    private float displayMaxMana;
    private boolean personalAvailable;
    private boolean available;

    private static final float LERP_SPEED = 0.18f;
    // stats 心数据 fallback 的有效期：超过该时长未成功 capture 过 ViewStats 就不再信任，避免旧数据误显示。
    private static final long STATS_FALLBACK_TTL_MS = 30_000;

    private static final Pattern HP_PATTERN = Pattern.compile("\\((?:HP|生命值|Health)\\s*(-?\\d+)/(-?\\d+)\\)");
    private static final Pattern LIVES_PATTERN = Pattern.compile("\\((?:Lives|生命数|生命效)\\s*(-?\\d+)\\)");
    private static final Pattern MANA_PATTERN = Pattern.compile("\\((?:Mana|法力值|Stamina|体力)\\s*(-?\\d+)/(\\d+)\\)");
    // Joey 个人 bossbar 多出的 (Blood <current>/<max>) 段。负数兜底（允许 -1、0），与 HP/Mana 风格保持一致。
    // 中英双语：英文 `Blood`、简体中文 `鲜血`（客户端语言切换后数据包按 translate 键输出本地化字面量）。
    private static final Pattern BLOOD_PATTERN = Pattern.compile("\\((?:Blood|鲜血)\\s*(-?\\d+)/(-?\\d+)\\)");
    // 果冻斯旺（ClassPassive=14）独有的 (Velocity <current>/<max>) 段。
    // 中英双语：英文 `Velocity`、简体中文 `动量`（详见 ctt_lang/lang/zh_cn.json 第 20144 行）。
    private static final Pattern VELOCITY_PATTERN = Pattern.compile("\\((?:Velocity|动量)\\s*(-?\\d+)/(-?\\d+)\\)");
    private static final Pattern COINS_PATTERN = Pattern.compile("\\((?:Coins|硬币|金币)\\s*(-?\\d+)\\)");
    private static final Pattern TEAM_PLAYER_PATTERN = Pattern.compile("([A-Za-z0-9_]+)\\s*\\((-?\\d+)/(-?\\d+)\\)");
    private static final Pattern MOB_NAME_PATTERN = Pattern.compile("^(.+?)\\s*\\((?:HP|生命值|Health)");
    private static final Pattern MOB_SUFFIX_PATTERN = Pattern.compile("\\((?:HP|生命值|Health)\\s*-?\\d+/-?\\d+\\)\\s*(.+)$");

    public void update() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            available = false;
            personalAvailable = false;
            return;
        }

        Scoreboard scoreboard = client.world.getScoreboard();
        ClientPlayerEntity player = client.player;

        healthPercent = Math.max(0, Math.min(100, readScore(scoreboard, player, "HealthPercent")));
        lives = Math.max(0, readScore(scoreboard, player, "Lives"));

        parseBossBarData(client);

        // v5.3.1：右侧栏字段在 parseBossBarData 里不再每帧清零（实现蓝量/动量/鲜血 sticky 不闪烁），
        // 但"游戏未开始 / 切回大厅"场景仍需要把上一局残留的 mana/coins/blood/velocity 数据清掉，
        // 避免进入大厅还显示上局打到一半的蓝条数字。
        // 触发点：`/trigger ViewStats` 收到"cannot trigger"消息 → StatsData.isGameNotStarted() 为 true。
        if (statsDataRef != null && statsDataRef.isGameNotStarted()) {
            hasManaField = false;
            hasBlood = false;
            hasVelocity = false;
            mana = 0;
            maxMana = 0;
            blood = 0;
            maxBlood = 0;
            velocity = 0;
            maxVelocity = 0;
            coins = 0;
        }

        // 癫狂 fallback：bossbar 解析不出 HP（hasBossBarData=false）但 ViewStats 拿到过心数据时，
        // 用红心上限作 maxHP，各类心之和作当前 HP，让 HUD 仍可渲染。
        // 三道守卫避免在"游戏未开始 / 切回大厅 / 长时间没刷新"等场景下用陈旧残留值误显示 HP 条：
        //   1) 最近一次 `/trigger ViewStats` 不是"无法触发"状态；
        //   2) capture 有过成功完成（lastCaptureCompleteTimeMs > 0）；
        //   3) 自上次成功 capture 不超过 STATS_FALLBACK_TTL_MS（30s）。
        hasStatsFallback = false;
        if (!hasBossBarData
                && statsDataRef != null
                && statsDataRef.hasHeartData()
                && !statsDataRef.isGameNotStarted()
                && statsDataRef.getLastCaptureCompleteTimeMs() > 0
                && (System.currentTimeMillis() - statsDataRef.getLastCaptureCompleteTimeMs()) < STATS_FALLBACK_TTL_MS) {
            int red = statsDataRef.getRedHearts();
            if (red > 0) {
                int soul = statsDataRef.getSoulHearts();
                int black = statsDataRef.getBlackHearts();
                int blue = statsDataRef.getBlueHearts();
                maxHP = red;
                allHearts = red + soul + black + blue;
                hasStatsFallback = true;
                healthPercent = Math.round((float) allHearts * 100 / maxHP);
            }
        }

        boolean hasHealthObjective = scoreboard.getNullableObjective("HealthPercent") != null;
        personalAvailable = hasBossBarData || hasStatsFallback || (healthPercent > 0 && hasHealthObjective);
        available = personalAvailable || hasTeamBossBar;

        Map<String, TeammateData> map = new HashMap<>();
        for (TeammateData t : teammates) {
            map.put(t.name, t);
        }
        teammateMap = Collections.unmodifiableMap(map);

        displayPercent = lerp(displayPercent, healthPercent, LERP_SPEED);
        displayAllHearts = lerp(displayAllHearts, allHearts, LERP_SPEED);
        displayMaxHP = lerp(displayMaxHP, maxHP, LERP_SPEED);
        displayMana = lerp(displayMana, mana, LERP_SPEED);
        displayMaxMana = lerp(displayMaxMana, maxMana, LERP_SPEED);
    }

    private void parseBossBarData(MinecraftClient client) {
        hasBossBarData = false;
        hasPersonalBossBar = false;
        hasTeamBossBar = false;
        allHearts = 0;
        maxHP = 0;
        teammates.clear();
        // v5.3.1：右侧栏字段（mana/maxMana/hasManaField/blood/maxBlood/hasBlood/
        //   velocity/maxVelocity/hasVelocity/coins）在这里**不再清零**。
        // 原因：癫狂状态下个人 bossbar 文本在"HP 文本"与"装饰性文本"之间疯狂翻页，
        //   旧版每帧都把这些字段归零 → 当帧 personalBar=null 时不再刷新 → hasMana() 翻 false
        //   → 蓝量/动量/鲜血条交替消失 = 闪烁。
        // 新版：清零改到 parsePersonalBar 开头（只在 bossbar 真的被找到时触发），
        //   bossbar 不可见的帧自动保持上一次解析出的值（sticky），闪烁消失。
        // 大厅 / 切场景的强制清零由 update() 里的 StatsData.isGameNotStarted() 守卫负责。

        if (client.inGameHud == null) return;

        try {
            BossBarHudAccessor accessor = (BossBarHudAccessor) client.inGameHud.getBossBarHud();
            Map<UUID, ClientBossBar> bossBars = accessor.getBossBars();
            if (bossBars == null || bossBars.isEmpty()) return;

            String playerName = client.player.getName().getString();

            ClientBossBar personalBar = null;
            ClientBossBar teamBar = null;
            Set<UUID> toHide = new HashSet<>();

            List<MobBossBarEntry> mobBars = new ArrayList<>();

            ModConfig cfg = ModConfig.INSTANCE;

            for (Map.Entry<UUID, ClientBossBar> entry : bossBars.entrySet()) {
                ClientBossBar bar = entry.getValue();
                String barText = bar.getName().getString();

                if (barText.contains(playerName)) {
                    if (HP_PATTERN.matcher(barText).find()) {
                        personalBar = bar;
                        hasPersonalBossBar = true;
                        if (cfg.hidePersonalBar) toHide.add(entry.getKey());
                    } else if (barText.contains("(/)")) {
                        teamBar = bar;
                        hasTeamBossBar = true;
                        if (cfg.hideTeamBar) toHide.add(entry.getKey());
                    }
                } else if (HP_PATTERN.matcher(barText).find()) {
                    Matcher hpM = HP_PATTERN.matcher(barText);
                    if (hpM.find()) {
                        int mobHP = Integer.parseInt(hpM.group(1));
                        int mobMaxHP = Integer.parseInt(hpM.group(2));
                        Matcher nameM = MOB_NAME_PATTERN.matcher(barText);
                        String mobName = nameM.find() ? nameM.group(1).trim() : barText;
                        Text mobSuffix = extractSuffixText(bar.getName());
                        mobBars.add(new MobBossBarEntry(mobName, mobSuffix, mobHP, mobMaxHP));
                        if (cfg.hideMobBars) toHide.add(entry.getKey());
                    }
                } else if (teamBar == null && TEAM_PLAYER_PATTERN.matcher(barText).find()) {
                    teamBar = bar;
                    hasTeamBossBar = true;
                    if (cfg.hideTeamBar) toHide.add(entry.getKey());
                }
            }

            activeMobBars = Collections.unmodifiableList(mobBars);

            hiddenBarUUIDs = Collections.unmodifiableSet(toHide);

            if (personalBar != null) {
                parsePersonalBar(personalBar);
            }

            if (teamBar != null) {
                parseTeamBar(teamBar, playerName);
                // v5.3.3：两段稳定排序 —— 先按 lives 降序（旧行为），然后把"自己"强制提到首位。
                // 保证玩家打开 HUD 第一眼就看到自己的当前状态，而队友间的相对顺序仍按 lives 保留。
                teammates.sort((a, b) -> Integer.compare(b.lives, a.lives));
                teammates.sort((a, b) -> Boolean.compare(b.isSelf, a.isSelf));
            }
        } catch (Exception ignored) {
        }
    }

    private void parsePersonalBar(ClientBossBar bar) {
        String barText = bar.getName().getString();
        bossBarPercent = bar.getPercent();

        // v5.3.1：右侧栏字段在此集中清零，然后由下面的各 find 块重新赋值。
        // 这样保证 bossbar 真的出现且可解析时刷新，bossbar 消失时则保留上一次值（sticky）。
        // 清零前后行为：
        //   · 正常一帧：走完这里 → 各 matcher 从新 bossbar 读值覆盖（与旧版一致）；
        //   · 职业切换：旧 Joey 的 hasBlood=true 被清成 false，若新 bossbar 没 Blood 段就保持 false（不会卡）；
        //   · 癫狂闪烁：本方法根本不会被调用（bossbar 文本无 HP 段），所有字段保留上一帧值。
        hasManaField = false;
        hasBlood = false;
        hasVelocity = false;
        mana = 0;
        maxMana = 0;
        blood = 0;
        maxBlood = 0;
        velocity = 0;
        maxVelocity = 0;
        coins = 0;

        Matcher hpMatcher = HP_PATTERN.matcher(barText);
        if (hpMatcher.find()) {
            allHearts = Integer.parseInt(hpMatcher.group(1));
            maxHP = Integer.parseInt(hpMatcher.group(2));
            hasBossBarData = true;
        }

        Matcher livesMatcher = LIVES_PATTERN.matcher(barText);
        if (livesMatcher.find()) {
            lives = Integer.parseInt(livesMatcher.group(1));
        }

        Matcher manaMatcher = MANA_PATTERN.matcher(barText);
        if (manaMatcher.find()) {
            hasManaField = true;
            mana = Integer.parseInt(manaMatcher.group(1));
            maxMana = Integer.parseInt(manaMatcher.group(2));
        }

        // Joey 专属：鲜血值段存在时记录，用于把主 HUD 右侧"下方法力条"替换为深红鲜血条（上方 ✦ 法力文字仍保留）。
        Matcher bloodMatcher = BLOOD_PATTERN.matcher(barText);
        if (bloodMatcher.find()) {
            hasBlood = true;
            blood = Integer.parseInt(bloodMatcher.group(1));
            maxBlood = Integer.parseInt(bloodMatcher.group(2));
        }

        // 果冻斯旺专属：动量段存在时记录，用于把 HUD 顶行右侧的 `✦mana/maxMana` 替换为粉色 `⚡velocity/maxVelocity`。
        // 下方条（此时实际被 Stamina 段冒充进 data.mana）保持历史行为不动，继续作为"体力条"显示。
        Matcher velocityMatcher = VELOCITY_PATTERN.matcher(barText);
        if (velocityMatcher.find()) {
            hasVelocity = true;
            velocity = Integer.parseInt(velocityMatcher.group(1));
            maxVelocity = Integer.parseInt(velocityMatcher.group(2));
        }

        Matcher coinsMatcher = COINS_PATTERN.matcher(barText);
        if (coinsMatcher.find()) {
            coins = Integer.parseInt(coinsMatcher.group(1));
        }

        if (hasBossBarData && maxHP > 0) {
            healthPercent = Math.round((float) allHearts * 100 / maxHP);
        }
    }

    private void parseTeamBar(ClientBossBar bar, String selfName) {
        String barText = bar.getName().getString();
        Matcher matcher = TEAM_PLAYER_PATTERN.matcher(barText);
        MinecraftClient client = MinecraftClient.getInstance();
        Scoreboard scoreboard = (client.world != null) ? client.world.getScoreboard() : null;
        while (matcher.find()) {
            String name = matcher.group(1);
            int hp = Integer.parseInt(matcher.group(2));
            int maxHp = Integer.parseInt(matcher.group(3));
            // v5.3.3：本客户端玩家本身也放进 teammates 列表，在 HUD 的"队友血量面板"里可见。
            // 旧版 `if (!name.equals(selfName))` 把自己滤掉是为了给自己的主血条让位，
            // 但主 HUD 左上角的 4 层大血条和队友面板的小条代表的数据不同（前者多层心叠，后者只有 HP 百分比），
            // 让自己也出现能一眼看到自己在队伍排名的 HP / Lives 情况，和真队友形成对比。
            int mateLives = (scoreboard != null) ? readScoreByName(scoreboard, name, "Lives") : 0;
            teammates.add(new TeammateData(name, hp, maxHp, mateLives, name.equals(selfName)));
        }
    }

    public float getDisplayPercent() { return displayPercent; }
    public float getDisplayAllHearts() { return displayAllHearts; }
    public float getDisplayMaxHP() { return displayMaxHP; }
    public float getDisplayMana() { return displayMana; }
    public float getDisplayMaxMana() { return displayMaxMana; }
    public boolean isAvailable() { return available; }
    public boolean isPersonalAvailable() { return personalAvailable; }
    public boolean hasRawValues() { return hasBossBarData || hasStatsFallback; }
    // v5.3.1：去掉 `hasBossBarData &&` 依赖。原先癫狂闪烁时 hasBossBarData 会在 true/false 间疯狂跳，
    // 蓝条跟着交替画/不画 → 这正是"蓝量条跟着闪"的根本原因。
    // 新版只看 hasManaField：只要上次成功解析过 Mana 段且未被 gameNotStarted 守卫清掉，就保持 true，
    // 蓝条持续显示，数值保留上一帧的正确值。
    public boolean hasMana() { return hasManaField; }

    public int getManaPercent() {
        if (maxMana <= 0) return 0;
        return Math.round((float) mana * 100 / maxMana);
    }

    public int getEffectivePercent() {
        if (hasBossBarData) return Math.round(bossBarPercent * 100);
        if (hasStatsFallback && maxHP > 0) {
            return Math.max(0, Math.min(200, Math.round((float) allHearts * 100 / maxHP)));
        }
        return healthPercent;
    }

    private static int readScore(Scoreboard scoreboard, ClientPlayerEntity player, String objectiveName) {
        ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
        if (objective == null) return 0;
        ReadableScoreboardScore score = scoreboard.getScore(player, objective);
        if (score == null) return 0;
        return score.getScore();
    }

    private static float lerp(float current, float target, float speed) {
        if (Math.abs(current - target) < 0.5f) return target;
        return current + (target - current) * speed;
    }

    private static Text extractSuffixText(Text fullText) {
        String full = fullText.getString();
        Matcher m = MOB_SUFFIX_PATTERN.matcher(full);
        if (!m.find()) return Text.empty();

        int suffixStart = m.start(1);

        MutableText result = Text.empty();
        AtomicInteger pos = new AtomicInteger(0);
        AtomicBoolean collecting = new AtomicBoolean(false);

        fullText.visit((style, text) -> {
            int segStart = pos.get();
            int segEnd = segStart + text.length();
            pos.set(segEnd);

            if (segEnd <= suffixStart) return java.util.Optional.empty();

            if (segStart < suffixStart) {
                String part = text.substring(suffixStart - segStart);
                if (!part.isEmpty()) {
                    result.append(Text.literal(part).setStyle(style));
                    collecting.set(true);
                }
            } else {
                result.append(Text.literal(text).setStyle(style));
                collecting.set(true);
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);

        return result;
    }

    private static int readScoreByName(Scoreboard scoreboard, String playerName, String objectiveName) {
        ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
        if (objective == null) return 0;
        for (var holder : scoreboard.getKnownScoreHolders()) {
            if (holder.getNameForScoreboard().equals(playerName)) {
                ReadableScoreboardScore score = scoreboard.getScore(holder, objective);
                return score != null ? score.getScore() : 0;
            }
        }
        return 0;
    }

    public static class TeammateData {
        public final String name;
        public final int hp;
        public final int maxHP;
        public final int lives;
        // v5.3.3：标记本条是否为"自己"。用于队友 HUD 把自己排首位 + 名字用金色 §6 和真队友区分。
        // 3D 头顶血条那边通过 `player == MinecraftClient.getInstance().player` 过滤自己，不受此字段影响。
        public final boolean isSelf;

        public TeammateData(String name, int hp, int maxHP, int lives, boolean isSelf) {
            this.name = name;
            this.hp = hp;
            this.maxHP = maxHP;
            this.lives = lives;
            this.isSelf = isSelf;
        }

        public int getPercent() {
            if (maxHP <= 0) return 100;
            return Math.round((float) hp * 100 / maxHP);
        }
    }

    public static class MobBossBarEntry {
        public final String name;
        public final Text suffixText;
        public final int hp;
        public final int maxHP;

        public MobBossBarEntry(String name, Text suffixText, int hp, int maxHP) {
            this.name = name;
            this.suffixText = suffixText;
            this.hp = hp;
            this.maxHP = maxHP;
        }
    }
}
