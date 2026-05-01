package com.ctt.healthdisplay.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * v8.3.0 · M7 · 服务端 → 客户端 "怪物血量表" 推送包。
 *
 * <h2>问题背景</h2>
 * <p>Cake Team Towers 中 vanilla bossbar 同一时间只会锁定 <b>一只</b> 怪（Boss 出现
 * 后永远锁 Boss），客户端只能看见这一条 bossbar 里的 {@code (HP cur/max)}。
 * 结果：多个同名精英 / 普通怪环绕时，头顶 HP 条只有 Boss 那只是正确的，
 * 其余都靠"bossbar 锁谁就把谁的 HP 复制给最近同名怪"的近似，Boss champion
 * 场景下（反馈 2026-05-01）会大面积错位闪烁。
 *
 * <h2>解决思路</h2>
 * <p>服务端本来就有 CTT 数据包维护的 {@code RedHearts} / {@code MaxHP} scoreboard，
 * 直接 per-player 扫视野 48 格内所有活 LivingEntity、按距离排序取前 32 条，
 * 每 5 tick (4 Hz) 打成一包推给该玩家。客户端缓存 5 s 内视作 fresh，
 * 头顶血条渲染直接读 cache；5 s 内没收到 (服务端没装 mod) → 自动回落旧 bossbar
 * 解析链路，老行为完全保留。
 *
 * <h2>Entry 字段</h2>
 * <ul>
 *   <li>{@code uuid}：与客户端实体侧匹配的 entity UUID</li>
 *   <li>{@code name}：显示名（服务端 {@code entity.getDisplayName().getString()}）</li>
 *   <li>{@code suffix}：状态后缀（如冠军徽章）；v1 初版恒空串，后续扩展</li>
 *   <li>{@code nameColor}：ARGB，0 表示未指定 → 客户端兜底白色</li>
 *   <li>{@code hp / maxHp}：来自 scoreboard，maxHp=0 时不会出现（服务端已丢弃）</li>
 *   <li>{@code targetted}：服务端选定的"最近那只"，用来驱动 NEAREST 档与 ▶ 箭头</li>
 * </ul>
 *
 * <h2>版本协商</h2>
 * <p>{@link #read} 对"未来版本"防御：{@code ver > CURRENT_VERSION} 时直接 drain 返回
 * 空包，让 client receiver 本 tick 跳过，UI 沿用上一帧或回落本地。{@code n} 超过
 * 256 的非法值同样 drain 清包，避免恶意或损坏数据触发 {@code NegativeArraySizeException}。
 *
 * @see com.ctt.healthdisplay.server.MobHealthBroadcaster
 * @see com.ctt.healthdisplay.client.ClientMobHealthCache
 */
public record MobHealthPayload(
        byte version,
        List<Entry> entries
) implements CustomPayload {

    public static final byte CURRENT_VERSION = 1;

    public static final CustomPayload.Id<MobHealthPayload> ID = new CustomPayload.Id<>(
            Identifier.of("ctt-health-display", "mob_health")
    );

    /**
     * 单个怪物血条条目。不带客户端本地的 {@code lastUpdateTick}（那个字段仅本地
     * stale-cleanup 用，服务端不需要 snapshot）。
     */
    public record Entry(
            UUID uuid,
            String name,
            String suffix,
            int nameColor,
            int hp,
            int maxHp,
            boolean targetted
    ) {}

    public static final PacketCodec<RegistryByteBuf, MobHealthPayload> CODEC = PacketCodec.of(
            MobHealthPayload::write,
            MobHealthPayload::read
    );

    private static void write(MobHealthPayload p, RegistryByteBuf buf) {
        buf.writeByte(p.version);
        buf.writeVarInt(p.entries.size());
        for (Entry e : p.entries) {
            buf.writeUuid(e.uuid);
            writeShortString(buf, e.name == null ? "" : e.name);
            writeShortString(buf, e.suffix == null ? "" : e.suffix);
            buf.writeVarInt(e.nameColor);
            buf.writeVarInt(e.hp);
            buf.writeVarInt(e.maxHp);
            buf.writeBoolean(e.targetted);
        }
    }

    private static MobHealthPayload read(RegistryByteBuf buf) {
        byte ver = buf.readByte();
        if (Byte.toUnsignedInt(ver) > Byte.toUnsignedInt(CURRENT_VERSION)) {
            // 未来版本：drain 丢弃，UI 沿用上一帧
            buf.skipBytes(buf.readableBytes());
            return new MobHealthPayload(ver, Collections.emptyList());
        }
        int n = buf.readVarInt();
        // 非法 / 失控值保险：服务端硬上限 32，给 256 余量后直接丢
        if (n < 0 || n > 256) {
            buf.skipBytes(buf.readableBytes());
            return new MobHealthPayload(ver, Collections.emptyList());
        }
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID uuid = buf.readUuid();
            String name = readShortString(buf);
            String suffix = readShortString(buf);
            int nameColor = buf.readVarInt();
            int hp = buf.readVarInt();
            int maxHp = buf.readVarInt();
            boolean targetted = buf.readBoolean();
            list.add(new Entry(uuid, name, suffix, nameColor, hp, maxHp, targetted));
        }
        // 末尾残字节保险阀（同 StatsSnapshotPayload 注释）：未来仅追加的末尾标量字段
        // 忘记 bump version 时，drain 一下不至于 codec 报 "buffer not fully consumed" 踢线
        if (buf.isReadable()) {
            buf.skipBytes(buf.readableBytes());
        }
        return new MobHealthPayload(ver, Collections.unmodifiableList(list));
    }

    private static void writeShortString(PacketByteBuf buf, String s) {
        buf.writeString(s == null ? "" : s, 256);
    }

    private static String readShortString(PacketByteBuf buf) {
        return buf.readString(256);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
