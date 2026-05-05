package com.ctt.healthdisplay.client;

import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 屏蔽服务器（地图脚本 / datapack / {@code /say} 等）发送的固定噪声聊天行。
 *
 * <p>背景（反馈 2026-05-01）：CTT 地图会反复对全服 broadcast 形如 {@code [@] L} 的单字噪声
 * （服务端用 {@code chat.type.announcement} 模板，sender="@" message="L"），房间里几秒就刷一屏。
 * 本地装的"聊天合并"类 mod 还会在末尾再贴一个金黄色 {@code " (x181)"} 合并后缀，进一步淹没
 * 真实聊天。
 *
 * <h2>匹配策略 · 双层防御</h2>
 * <ol>
 *   <li><strong>结构化匹配（主路径）</strong>：递归遍历 {@link Text} 树，找到任意一个
 *       {@link TranslatableTextContent} 节点 ↔ 比对 {@code translate} key + {@code with[]} 参数。
 *       这一层<strong>不依赖渲染后的字符串</strong>，不受时间戳 mod 在前面塞 hover 装饰、
 *       不受合并 mod 在末尾追加 {@code " (xN)"}、不受语言文件（en/zh）翻译差异影响——
 *       服务端原始包里的 {@code (sender, message)} 是什么就是什么。</li>
 *   <li><strong>字符串兜底（备路径）</strong>：剥掉末尾合并后缀 {@code (xN)}、trim 后做
 *       字面量黑名单 endsWith/equals 比对，处理服务端用 {@code tellraw} 直接发裸文本
 *       （不走 announcement 模板）的特殊情况。</li>
 * </ol>
 *
 * <p>只影响本地聊天 HUD 渲染，不改变服务端记账。
 *
 * <p>与 {@link TriggerEchoFilter} 互不干扰：那一份只针对本模组自发的 /trigger 命令回显，
 * 这一份只针对服务器主动 broadcast 的固定字面噪声。
 *
 * <h2>历史踩坑</h2>
 * <ul>
 *   <li>v8.3.3 初版只用 {@code body.equals(muted)} → 漏掉 chat.type.text / admin 广播壳</li>
 *   <li>v8.3.4 加 endsWith 兜玩家说话/admin 广播 → 但被合并 mod 加 hover 装饰前缀的
 *       announcement 包仍然漏（前缀不在拼接末尾，但 raw 里多出隐形 hover 字段会让某些
 *       mod 实现把它渲染成可见空白，equals 失效）</li>
 *   <li>v8.3.5 起改走 Text 结构 + with[] 直读，绕开所有渲染层装饰，最稳</li>
 * </ul>
 */
public final class ServerChatNoiseFilter {

    private ServerChatNoiseFilter() {}

    /**
     * vanilla 服务端 announcement / 玩家聊天 / OP admin 广播 都走 translate 模板，
     * 模板的 {@code with[]} 一律是 {@code [sender, message]}。
     *
     * <p>把要屏蔽的 {@code (sender, message)} 二元组写在这里，主路径精确匹配——
     * 不会因为别人手打 "L" 就被误杀（必须 sender 也吻合）。
     */
    private static final String[][] MUTED_ANNOUNCEMENTS = {
            {"@", "L"},
    };

    /**
     * 我们关心的所有 vanilla 聊天 translate key。三个模板的 with 参数顺序都是
     * {@code [sender, message]}，所以匹配逻辑可以共用同一份黑名单。
     */
    private static final String[] CHAT_TRANSLATE_KEYS = {
            "chat.type.announcement", // /say & 服务端公告
            "chat.type.text",         // 玩家普通聊天 <sender> message
            "chat.type.admin",        // OP 看到的 admin 广播 [sender: command]
    };

    /** 字符串兜底层用：精确 / endsWith 比对的字面消息体（仅 message 段，不含 sender 壳）。 */
    private static final String[] MUTED_LINES = {
            "[@] L",
    };

    /** 末尾合并后缀，例如 {@code " (x181)"}。 */
    private static final Pattern MERGE_SUFFIX = Pattern.compile("\\s*\\(x\\d+\\)\\s*$");

    /**
     * @param overlay true 表示 action bar，那条路径不走聊天历史，不需要拦
     * @return true = 这条消息是服务端固定噪声，应当从聊天里隐藏
     */
    public static boolean shouldHide(Text message, boolean overlay) {
        if (overlay) return false;
        if (message == null) return false;

        if (matchesByStructure(message)) return true;

        String raw = message.getString();
        if (raw.isEmpty()) return false;
        String body = MERGE_SUFFIX.matcher(raw).replaceFirst("").trim();
        if (body.isEmpty()) return false;
        for (String muted : MUTED_LINES) {
            if (matchesByString(body, muted)) return true;
        }
        return false;
    }

    /**
     * 递归找一个匹配黑名单的 {@code TranslatableTextContent} 节点。
     * 一条 announcement 消息往往是 root + 若干 sibling 拼起来，外面还可能套时间戳/合并装饰，
     * 所以要遍历整棵树，命中任一节点就拦。
     */
    private static boolean matchesByStructure(Text node) {
        if (node == null) return false;
        TextContent content = node.getContent();
        if (content instanceof TranslatableTextContent t && isMutedTranslate(t)) {
            return true;
        }
        List<Text> siblings = node.getSiblings();
        if (siblings != null) {
            for (Text sib : siblings) {
                if (matchesByStructure(sib)) return true;
            }
        }
        return false;
    }

    private static boolean isMutedTranslate(TranslatableTextContent t) {
        String key = t.getKey();
        if (key == null) return false;
        boolean isChatKey = false;
        for (String k : CHAT_TRANSLATE_KEYS) {
            if (k.equals(key)) { isChatKey = true; break; }
        }
        if (!isChatKey) return false;

        Object[] args = t.getArgs();
        if (args == null || args.length < 2) return false;
        String sender = argToString(args[0]);
        String msg = argToString(args[1]);

        for (String[] pair : MUTED_ANNOUNCEMENTS) {
            if (pair[0].equals(sender) && pair[1].equals(msg)) return true;
        }
        return false;
    }

    private static String argToString(Object arg) {
        if (arg == null) return "";
        if (arg instanceof Text t) return t.getString();
        return arg.toString();
    }

    /**
     * 同一条噪声若服务端用 {@code tellraw} 直接发裸文本（不走 announcement 模板），
     * 客户端拿到的就是纯字符串，三种壳都兜底。
     */
    private static boolean matchesByString(String body, String muted) {
        if (body.equals(muted)) return true;
        if (body.endsWith(" " + muted)) return true;
        if (body.endsWith(": " + muted + "]")) return true;
        return false;
    }
}
