package com.ctt.healthdisplay.server.command;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v8.x · 聊天广播 per-player 订阅集合（in-memory，不持久化）。
 *
 * <h2>设计动机</h2>
 * <p>v8.0.9 引入 {@code /ctthd broadcast} 命令时，把开关挂在 {@code ServerConfig} 全局字段上 ——
 * 结果任意玩家开启后所有玩家都被刷屏。这违反"只有开启者自己看到"的调试语义，多人服现场体验差。
 *
 * <p>v8.x 改造：命令切换的是 <b>per-player 订阅</b>。每条广播事件路由路径变成：
 * <pre>
 *   if (globalEnabled || hasAnySubscriber(channel)) 构造文本
 *       全局开 → server.broadcast()      // 兜底，仅 JSON 启用
 *       全局关 → 仅给订阅集合中的玩家发    // 命令开关的常规路径
 * </pre>
 *
 * <h2>持久化</h2>
 * <p>不持久化。玩家断线 / 服务器重启即清空 —— 调试性质本就是临时行为，重启后默认安静最合理。
 *
 * <h2>线程安全</h2>
 * <p>{@link ConcurrentHashMap#newKeySet()} 支持并发读写，命令线程 add/remove，
 * tick 线程 forEach 取值，无需额外加锁。
 */
public final class BroadcastSubscribers {

    public enum Channel { DAMAGE, KILL, TAKEN }

    private static final Set<UUID> DAMAGE = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> KILL   = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> TAKEN  = ConcurrentHashMap.newKeySet();

    private BroadcastSubscribers() {}

    private static Set<UUID> setOf(Channel ch) {
        return switch (ch) {
            case DAMAGE -> DAMAGE;
            case KILL   -> KILL;
            case TAKEN  -> TAKEN;
        };
    }

    public static void subscribe(Channel ch, UUID uuid) {
        if (uuid != null) setOf(ch).add(uuid);
    }

    public static void unsubscribe(Channel ch, UUID uuid) {
        if (uuid != null) setOf(ch).remove(uuid);
    }

    public static boolean isSubscribed(Channel ch, UUID uuid) {
        return uuid != null && setOf(ch).contains(uuid);
    }

    public static boolean hasAnySubscriber(Channel ch) {
        return !setOf(ch).isEmpty();
    }

    /** 玩家断线时清理三档订阅（CttStatsServer DISCONNECT 钩子调）。 */
    public static void onPlayerDisconnect(UUID uuid) {
        if (uuid == null) return;
        DAMAGE.remove(uuid);
        KILL.remove(uuid);
        TAKEN.remove(uuid);
    }

    /**
     * 把 {@code msg} 仅发送给订阅了 {@code ch} 通道的在线玩家。
     * 用 {@link ServerPlayerEntity#sendMessage(Text, boolean)} 而非 broadcast，
     * 不会走 ops-only 通道，普通玩家也能收到自己订阅的消息。
     */
    public static void sendTo(MinecraftServer server, Channel ch, Text msg) {
        if (server == null || msg == null) return;
        Set<UUID> set = setOf(ch);
        if (set.isEmpty()) return;
        for (UUID u : set) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
            if (p != null) p.sendMessage(msg, false);
        }
    }

    public static int subscriberCount(Channel ch) {
        return setOf(ch).size();
    }
}
