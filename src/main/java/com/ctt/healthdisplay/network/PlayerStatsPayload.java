package com.ctt.healthdisplay.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v8.4.0 · 服务端 → 客户端 "玩家属性面板" 推送包。
 *
 * <h2>问题背景</h2>
 * <p>v8.3.x 及之前客户端每 N 秒发一次 {@code /trigger ViewStats} 让地图 datapack
 * 输出属性聊天行。多人服务器的反作弊 / rate limit 会把过短间隔的玩家踢线
 * （v8.3.9 起 {@link com.ctt.healthdisplay.config.ConfigScreen} 已加红色警告）。
 * 同时 datapack 的 {@code view_stats.mcfunction} 单次执行要跑 100+ 行 {@code tellraw @s}
 * （0.5~1.5 ms / 玩家），高频触发会拉低 TPS + 占用聊天通道带宽。
 *
 * <h2>v8.4.0 改造</h2>
 * <p>服务端装了本 mod 时由
 * {@link com.ctt.healthdisplay.server.PlayerStatsPushBroadcaster} 在
 * {@code END_SERVER_TICK} 钩子里做：
 * <ol>
 *   <li>{@link com.ctt.healthdisplay.server.ViewStatsBuilder#build} 走
 *       {@link com.ctt.healthdisplay.server.ViewStatsRegistry} 属性表，每个
 *       objective 调一次 {@link com.ctt.healthdisplay.server.ScoreboardReader#readOrZero}，
 *       拼出与 datapack {@code view_stats.mcfunction} 视觉等价的 {@link Text} 行；</li>
 *   <li>把四色心数值与 List&lt;Text&gt; 一起打成本 payload 推给该玩家自己。</li>
 * </ol>
 * <p>客户端 receiver 直接调
 * {@link com.ctt.healthdisplay.health.StatsData#applyServerSnapshot(int, int, int, int, java.util.List)}
 * 灌进字段，{@link com.ctt.healthdisplay.client.ClientStatsPushCache#recordPush()} 进
 * fresh 状态。{@link com.ctt.healthdisplay.CttHealthDisplay} 的 auto refresh 路径据此跳过
 * 自发 {@code /trigger ViewStats}，命令完全由服务端 mod 旁路解决，绕开反作弊审计。
 *
 * <h2>性能（4 人队 1 Hz）</h2>
 * <ul>
 *   <li>服务端 CPU：~0.4 ms/s（vs datapack 路径的 2~6 ms/s）</li>
 *   <li>网络字节：~12 KB/s（vs datapack 路径的 40+ KB/s 多包 chat）</li>
 *   <li>包数：4 包/s（vs 400+ chat 包/s）</li>
 * </ul>
 *
 * <h2>无服务端 mod 的兜底</h2>
 * <p>纯 vanilla / 仅装客户端 mod 的玩家永远收不到这条包，
 * {@code ClientStatsPushCache.isFresh()} 持续 false，客户端 auto refresh 退到
 * v8.3.x 老路径自己发 {@code /trigger ViewStats}（行为完全保留）。
 *
 * <h2>协议</h2>
 * <ul>
 *   <li>{@code version}：当前 1；编码末尾 drain 余字节保留向后兼容</li>
 *   <li>{@code redHearts / soulHearts / blackHearts / blueHearts}：VarInt 心数</li>
 *   <li>{@code lines}：VarInt 长度 + 逐行 {@link TextCodecs#REGISTRY_PACKET_CODEC}</li>
 * </ul>
 */
public record PlayerStatsPayload(
        byte version,
        int redHearts,
        int soulHearts,
        int blackHearts,
        int blueHearts,
        List<Text> lines
) implements CustomPayload {

    /** 当前协议版本。未来追加字段时 bump 并在 codec read 分支兼容旧 ver。 */
    public static final byte CURRENT_VERSION = 1;

    /** 单 payload 最多 lines 数（防御失控构造导致 OOM；datapack 当前 ~100 行）。 */
    private static final int MAX_LINES = 256;

    public static final CustomPayload.Id<PlayerStatsPayload> ID = new CustomPayload.Id<>(
            Identifier.of("ctt-health-display", "player_stats")
    );

    public static final PacketCodec<RegistryByteBuf, PlayerStatsPayload> CODEC = PacketCodec.of(
            PlayerStatsPayload::write,
            PlayerStatsPayload::read
    );

    private static void write(PlayerStatsPayload p, RegistryByteBuf buf) {
        buf.writeByte(p.version);
        buf.writeVarInt(p.redHearts);
        buf.writeVarInt(p.soulHearts);
        buf.writeVarInt(p.blackHearts);
        buf.writeVarInt(p.blueHearts);
        List<Text> lines = p.lines == null ? Collections.emptyList() : p.lines;
        buf.writeVarInt(lines.size());
        for (Text line : lines) {
            TextCodecs.REGISTRY_PACKET_CODEC.encode(buf, line == null ? Text.empty() : line);
        }
    }

    private static PlayerStatsPayload read(RegistryByteBuf buf) {
        byte ver = buf.readByte();
        int verU = Byte.toUnsignedInt(ver);
        if (verU > Byte.toUnsignedInt(CURRENT_VERSION)) {
            // 未来版本：drain 丢弃，receiver 据 version 比较跳过
            buf.skipBytes(buf.readableBytes());
            return new PlayerStatsPayload(ver, 0, 0, 0, 0, Collections.emptyList());
        }
        int red   = buf.readVarInt();
        int soul  = buf.readVarInt();
        int black = buf.readVarInt();
        int blue  = buf.readVarInt();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_LINES) {
            // 非法值兜底：drain 清残字节避免 NegativeArraySizeException / 越界
            buf.skipBytes(buf.readableBytes());
            return new PlayerStatsPayload(ver, red, soul, black, blue, Collections.emptyList());
        }
        List<Text> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(TextCodecs.REGISTRY_PACKET_CODEC.decode(buf));
        }
        // 与 StagePayload 同款防御：把 slice 残字节读干，未来版本扩展字段不会踢线
        if (buf.isReadable()) {
            buf.skipBytes(buf.readableBytes());
        }
        return new PlayerStatsPayload(ver, red, soul, black, blue, lines);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
