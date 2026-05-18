package com.ctt.healthdisplay.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v8.4.0 · 服务端 → 所有在线客户端 "全队四色心摘要" 推送包。
 *
 * <h2>用途</h2>
 * <p>主血条 {@link com.ctt.healthdisplay.hud.HealthBarRenderer#drawLayeredBar} 早就支持
 * RedHearts / SoulHearts / BlackHearts / BlueHearts 四层叠加，但只有玩家自己有这 4 个
 * scoreboard 字段（来自 {@code /trigger ViewStats} 解析）。队友头顶条
 * （{@link com.ctt.healthdisplay.hud.HealthBarRenderer#drawTeammateBar}、
 * {@link com.ctt.healthdisplay.hud.TeammateWorldRenderer#renderHealthBar}、
 * {@link com.ctt.healthdisplay.mixin.TeammateHealthMixin}）只能用 vanilla 团队 bossbar 文本
 * 解析出的 {@code Name (hp/maxHp)} 单色条，没法叠 Soul/Black/Blue。
 *
 * <p>本 payload 由服务端 {@link com.ctt.healthdisplay.server.TeamHeartsBroadcaster}
 * 每 4 tick (5 Hz) 扫所有在线玩家、用 {@link com.ctt.healthdisplay.server.ScoreboardReader#readOrZero}
 * 读 5 + 1 个 objective（4 色心 + MaxHP + Lives），全量广播给每个在线玩家。
 * 客户端 {@link com.ctt.healthdisplay.client.ClientTeamHeartsCache} 维护 {@code name -> Entry}
 * 映射，{@link com.ctt.healthdisplay.health.HealthData#parseTeamBar} 拼
 * {@code TeammateData} 时优先用 cache 的四色心 / maxHP / lives，三个队友渲染入口直接
 * 调 {@code drawLayeredBar} 叠彩条。
 *
 * <h2>无 mod 服务端兜底</h2>
 * <p>客户端 cache.isFresh() 持续 false → TeammateData 退到 v8.3.x 老路径：bossbar 正则
 * + 客户端本地 scoreboard 读 Lives，外观就是旧的 OVERFLOW_COLORS 单色多槽条。
 *
 * <h2>带宽估算（4 人队 5 Hz）</h2>
 * <ul>
 *   <li>单 Entry：~28 字节（name varInt 长度 + 7 个 VarInt 数字）</li>
 *   <li>单 payload：1 byte ver + VarInt n + n × 28 ≈ 120 字节（4 人）</li>
 *   <li>每秒：5 × 4 玩家 × 120 ≈ 2.4 KB/s 总 = 600 B/s 每客户端</li>
 * </ul>
 *
 * <h2>协议</h2>
 * <ul>
 *   <li>{@code version}：当前 1</li>
 *   <li>{@code entries}：VarInt 长度 + 逐 Entry</li>
 *   <li>Entry：name (UTF-8, max 16) + red/soul/black/blue/maxHp/lives (VarInt × 6)</li>
 * </ul>
 */
public record TeamHeartsPayload(
        byte version,
        List<Entry> entries
) implements CustomPayload {

    public static final byte CURRENT_VERSION = 1;

    /** 单 payload 最多 entries 数（vanilla 队伍最多 16 人，给 64 余量）。 */
    private static final int MAX_ENTRIES = 64;

    /** vanilla 玩家名上限 16 字符；UTF-8 编码最大 64 字节足够。 */
    private static final int MAX_NAME_LEN = 64;

    public static final CustomPayload.Id<TeamHeartsPayload> ID = new CustomPayload.Id<>(
            Identifier.of("ctt-health-display", "team_hearts")
    );

    /**
     * 单玩家心数据条目。{@code name} 用 vanilla 16 字符限制下的 UTF-8 字符串
     * （和 {@code TEAM_PLAYER_PATTERN} 正则匹配出的同一份名字对齐），
     * 不带 UUID—客户端 {@link com.ctt.healthdisplay.health.HealthData.TeammateData}
     * 本来就是按 name 查 cache。
     */
    public record Entry(
            String name,
            int redHearts,
            int soulHearts,
            int blackHearts,
            int blueHearts,
            int maxHp,
            int lives
    ) {}

    public static final PacketCodec<RegistryByteBuf, TeamHeartsPayload> CODEC = PacketCodec.of(
            TeamHeartsPayload::write,
            TeamHeartsPayload::read
    );

    private static void write(TeamHeartsPayload p, RegistryByteBuf buf) {
        buf.writeByte(p.version);
        List<Entry> entries = p.entries == null ? Collections.emptyList() : p.entries;
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            writeShortString(buf, e.name);
            buf.writeVarInt(e.redHearts);
            buf.writeVarInt(e.soulHearts);
            buf.writeVarInt(e.blackHearts);
            buf.writeVarInt(e.blueHearts);
            buf.writeVarInt(e.maxHp);
            buf.writeVarInt(e.lives);
        }
    }

    private static TeamHeartsPayload read(RegistryByteBuf buf) {
        byte ver = buf.readByte();
        int verU = Byte.toUnsignedInt(ver);
        if (verU > Byte.toUnsignedInt(CURRENT_VERSION)) {
            buf.skipBytes(buf.readableBytes());
            return new TeamHeartsPayload(ver, Collections.emptyList());
        }
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_ENTRIES) {
            buf.skipBytes(buf.readableBytes());
            return new TeamHeartsPayload(ver, Collections.emptyList());
        }
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name  = readShortString(buf);
            int red      = buf.readVarInt();
            int soul     = buf.readVarInt();
            int black    = buf.readVarInt();
            int blue     = buf.readVarInt();
            int maxHp    = buf.readVarInt();
            int lives    = buf.readVarInt();
            list.add(new Entry(name, red, soul, black, blue, maxHp, lives));
        }
        // 末尾残字节保险阀
        if (buf.isReadable()) {
            buf.skipBytes(buf.readableBytes());
        }
        return new TeamHeartsPayload(ver, Collections.unmodifiableList(list));
    }

    private static void writeShortString(PacketByteBuf buf, String s) {
        buf.writeString(s == null ? "" : s, MAX_NAME_LEN);
    }

    private static String readShortString(PacketByteBuf buf) {
        return buf.readString(MAX_NAME_LEN);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
