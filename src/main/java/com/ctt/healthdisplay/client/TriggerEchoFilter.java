package com.ctt.healthdisplay.client;

import net.minecraft.text.Text;

/**
 * 屏蔽由本模组主动发送的 /trigger 命令在聊天框里的成功回显。
 *
 * <p>背景（反馈 2026-05-01，见 {@code docs/异常.md} 问题 2）：
 * 客户端会在 {@link com.ctt.healthdisplay.CttHealthDisplay} 的 tick 循环里
 * 自动发送 {@code /trigger ViewStats}（刷属性栏）和 {@code /trigger TogglePartyBossbar}
 * （切队伍血条），这些命令每执行一次都会在聊天里留下一条 "Triggered [XXX]" 回显；
 * OP 玩家还会看到队友触发命令的 admin 广播格式 {@code [PlayerName: Triggered [XXX]]}，
 * 房间里每过几秒就会被这种噪声刷屏，淹没真正的聊天。
 *
 * <p>处理策略：在客户端收到 game 消息时，若文本同时包含
 * "Triggered" / "已触发" 关键字 + 本模组自己会发的 trigger 命令名（见 {@link #MUTED_TRIGGERS}），
 * 就从聊天 HUD 抹掉这条。只影响本地渲染，不改变服务端记账。
 *
 * <p>注意：本模组原先在 {@link com.ctt.healthdisplay.health.StatsData#processMessage}
 * 里也有一条类似判定，但它只在"自己刚主动触发 + 正在抓属性"状态下 ({@code hideCapture=true})
 * 才隐藏，无法覆盖 OP 端看到的队友 admin 广播、也不覆盖玩家手动输入的回显。
 * 本过滤器做无条件兜底，与 StatsData 的判定互不干扰。
 */
public final class TriggerEchoFilter {

    private TriggerEchoFilter() {}

    /**
     * 本模组会主动发送的 trigger 名字。未来新增自动触发命令时在这里追加即可。
     * <ul>
     *   <li>{@code ViewStats} —— CttHealthDisplay 第 477 行，刷属性栏</li>
     *   <li>{@code TogglePartyBossbar} —— CttHealthDisplay 第 500 行，切队伍血条</li>
     * </ul>
     */
    private static final String[] MUTED_TRIGGERS = {
            "ViewStats",
            "TogglePartyBossbar",
    };

    /**
     * @return true = 这条消息是本模组发出的命令回显噪声，应当从聊天里隐藏
     */
    public static boolean shouldHide(Text message, boolean overlay) {
        if (overlay) return false;
        if (message == null) return false;
        String raw = message.getString();
        if (raw.isEmpty()) return false;

        // Minecraft 原版 "commands.trigger.simple/add/set" 三个成功键在
        // zh_cn 都以 "已触发" 开头，en_us 都以 "Triggered" 开头；
        // admin 广播 "chat.type.admin" 只是把上面那行套一层 "[玩家: ...]"，关键字不变。
        boolean hasTrigger = raw.contains("Triggered") || raw.contains("\u5df2\u89e6\u53d1");
        if (!hasTrigger) return false;

        for (String name : MUTED_TRIGGERS) {
            if (raw.contains(name)) return true;
        }
        return false;
    }
}
