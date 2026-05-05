package com.ctt.healthdisplay.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.text.TextColor;
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
 * <h2>v2 协议升级（i18n + 元素抗性护甲）</h2>
 * <p>v1 把 {@code entity.getDisplayName().getString()} 摊平成服务端 locale 的纯字符串
 * 下发，客户端拿不回 translate 键 → 客户端中文 + 服务端英文 = 头顶条恒英文名。
 * 同时 v1 硬写空 suffix，丢失了 datapack {@code targetting_enemy.mcfunction} 的
 * {@code 🛡Defence + 🔥/☃/☠/☀/🛡/⚓/⚡ 元素抗性} 后缀（8 档 ArmorDisplay 模板）。
 * v2 起：
 * <ul>
 *   <li>{@code nameText} 用 {@link TextCodecs#REGISTRY_PACKET_CODEC} 结构化下发，translate 键 +
 *       Style 都保留，客户端按本地 lang 自动渲染中文/英文；</li>
 *   <li>新增 {@code defence}（int）/ {@code armorType}（byte 0..7）/ {@code armorValue}（int）
 *       三段原子字段，客户端按 armorType 用硬编码图标 + 颜色重建 suffix Text，
 *       i18n / 颜色全部交客户端，协议保持轻量。</li>
 * </ul>
 *
 * <h2>Entry 字段（v2）</h2>
 * <ul>
 *   <li>{@code uuid}：与客户端实体侧匹配的 entity UUID</li>
 *   <li>{@code nameText}：结构化 {@link Text}，含 translate 键 + Style</li>
 *   <li>{@code hp / maxHp}：来自 scoreboard，maxHp=0 时不会出现（服务端已丢弃）</li>
 *   <li>{@code targetted}：服务端选定的"最近那只"，用来驱动 NEAREST 档与 ▶ 箭头</li>
 *   <li>{@code defence}：物理护甲值（{@code Defence} scoreboard）</li>
 *   <li>{@code armorType}：0 = 仅 Defence；1..7 分别对应
 *       FireArmor / IceArmor / DarkArmor / LightArmor / TrueArmor / WaterArmor / ElectricArmor
 *       （= datapack {@code ArmorDisplay} 0..7）</li>
 *   <li>{@code armorValue}：被选中的元素抗性 scoreboard 值；armorType=0 时恒 0</li>
 * </ul>
 *
 * <h2>版本协商</h2>
 * <p>{@link #read} 对"未来版本"防御：{@code ver > CURRENT_VERSION} 时直接 drain 返回
 * 空包，让 client receiver 本 tick 跳过，UI 沿用上一帧或回落本地。{@code n} 超过
 * 256 的非法值同样 drain 清包，避免恶意或损坏数据触发 {@code NegativeArraySizeException}。
 * <p>{@code ver == 1} 老格式（旧服务端 ↔ 新客户端）走兼容分支：读
 * {@code String name + suffix + nameColor}，name 包成 {@link Text#literal} + 颜色 Style，
 * suffix 字段读完丢弃（v1 恒空），新字段 defence/armorType/armorValue 全填 0。
 *
 * @see com.ctt.healthdisplay.server.MobHealthBroadcaster
 * @see com.ctt.healthdisplay.client.ClientMobHealthCache
 */
public record MobHealthPayload(
        byte version,
        List<Entry> entries
) implements CustomPayload {

    public static final byte CURRENT_VERSION = 2;

    public static final CustomPayload.Id<MobHealthPayload> ID = new CustomPayload.Id<>(
            Identifier.of("ctt-health-display", "mob_health")
    );

    /**
     * 单个怪物血条条目。不带客户端本地的 {@code lastUpdateTick}（那个字段仅本地
     * stale-cleanup 用，服务端不需要 snapshot）。
     */
    public record Entry(
            UUID uuid,
            Text nameText,
            int hp,
            int maxHp,
            boolean targetted,
            int defence,
            byte armorType,
            int armorValue
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
            TextCodecs.REGISTRY_PACKET_CODEC.encode(buf, e.nameText == null ? Text.empty() : e.nameText);
            buf.writeVarInt(e.hp);
            buf.writeVarInt(e.maxHp);
            buf.writeBoolean(e.targetted);
            buf.writeVarInt(e.defence);
            buf.writeByte(e.armorType);
            buf.writeVarInt(e.armorValue);
        }
    }

    private static MobHealthPayload read(RegistryByteBuf buf) {
        byte ver = buf.readByte();
        int verU = Byte.toUnsignedInt(ver);
        if (verU > Byte.toUnsignedInt(CURRENT_VERSION)) {
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
            if (verU >= 2) {
                UUID uuid = buf.readUuid();
                Text nameText = TextCodecs.REGISTRY_PACKET_CODEC.decode(buf);
                int hp = buf.readVarInt();
                int maxHp = buf.readVarInt();
                boolean targetted = buf.readBoolean();
                int defence = buf.readVarInt();
                byte armorType = buf.readByte();
                int armorValue = buf.readVarInt();
                list.add(new Entry(uuid, nameText, hp, maxHp, targetted,
                        defence, armorType, armorValue));
            } else {
                // ver == 1 兼容：旧 schema 读完转换。
                // v1 schema: uuid, shortString name, shortString suffix, varInt nameColor,
                //            varInt hp, varInt maxHp, boolean targetted
                UUID uuid = buf.readUuid();
                String name = readShortString(buf);
                /* String suffix = */ readShortString(buf); // v1 恒空，丢弃
                int nameColor = buf.readVarInt();
                int hp = buf.readVarInt();
                int maxHp = buf.readVarInt();
                boolean targetted = buf.readBoolean();
                Text nameText = literalWithColor(name, nameColor);
                list.add(new Entry(uuid, nameText, hp, maxHp, targetted, 0, (byte) 0, 0));
            }
        }
        // 末尾残字节保险阀（同 StatsSnapshotPayload 注释）：未来仅追加的末尾标量字段
        // 忘记 bump version 时，drain 一下不至于 codec 报 "buffer not fully consumed" 踢线
        if (buf.isReadable()) {
            buf.skipBytes(buf.readableBytes());
        }
        return new MobHealthPayload(ver, Collections.unmodifiableList(list));
    }

    /** v1 兼容：把"裸字符串名 + ARGB 颜色"包成最简 Text，颜色 0 时不强制覆盖默认样式。 */
    private static Text literalWithColor(String name, int argb) {
        MutableText t = Text.literal(name == null ? "" : name);
        if (argb != 0) {
            // v1 server 端写入 nameColor 时会 OR 0xFF000000（见旧 broadcaster 代码），
            // 客户端 Style 颜色只看低 24 位 RGB，保留 alpha 也不影响 TextColor.fromRgb。
            t.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(argb & 0x00FFFFFF)));
        }
        return t;
    }

    private static String readShortString(PacketByteBuf buf) {
        return buf.readString(256);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
