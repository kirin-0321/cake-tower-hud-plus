package com.ctt.healthdisplay.network;

import com.ctt.healthdisplay.server.StageKey;
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
 * v6.6.5 · M6 · 服务端 → 客户端 stats 全量快照同步包。
 *
 * <h2>触发</h2>
 * <ul>
 *   <li>玩家 JOIN 时立即推一次（首次 baseline，UI 起步无延迟）</li>
 *   <li>{@code END_SERVER_TICK} 每 20 tick（≈ 1 Hz）打包一次 → 推给所有在线玩家</li>
 * </ul>
 *
 * <h2>设计取舍（v6.6.5 拍板）</h2>
 * <ul>
 *   <li>策略 A · 全量周期推送：实现最简、调试容易；4 玩家 × 20 stage 实测 ~3 KB / 包，
 *       1 Hz 流量 ~3 KB/s/玩家，LAN / 局域网完全可承受。</li>
 *   <li>频率 1 Hz：HUD 数字每秒跳一次，配合"自己金色"等辨识度足够；K 表打开时
 *       客户端不主动 pull，等下一次心跳即可。</li>
 *   <li>不传 L 键面板的开发字段（{@code unattributed* / globalLayerCounts}）：
 *       这些是开发模式诊断，dedicated server 场景显示 0 是可接受的；集成服务器
 *       下 {@link com.ctt.healthdisplay.client.ClientStatsCache} 直接委托原 server static，
 *       字段不丢。</li>
 * </ul>
 *
 * <h2>编码 schema</h2>
 * <pre>
 * byte version
 *   v1 (v6.6.5)：基础全量字段
 *   v2 (v6.6.7)：PlayerEntry 末尾追加 recent5sSum (varLong) ← HUD 关行 DPS 数据源
 *   v3 (v8.x)  ：PlayerEntry 末尾追加当前主手武器维度 4 字段：
 *                shortString currentWeaponId, varLong weaponDpsActive,
 *                varInt weaponP95, varInt weaponP95Samples
 *                ← L 面板顶部摘要 + 三道门阈值显示
 * long  startMs / activeDurationMs / startTick
 * bool  live / frozen
 * varInt unattributedKills
 * varInt representativeStageIdx (-1 if none)
 *
 * varInt nStages
 *   StageEntry[]: gameId / tier / floor / stageType / stageNum (5 strings),
 *                 enterMs / exitMs (longs), inProgress (bool)
 *
 * varInt nPlayers
 *   PlayerEntry[]: UUID, name,
 *                  dealt / taken / kills / bossKills / assists / dealtEvents /
 *                  dealtMaxHit / takenEvents / takenMaxHit,
 *                  lastSeenStageIdx (-1 if none),
 *                  varInt nStageRows
 *                    StageRow[]: stageIdx, dealt / taken / kills / bossKills / assists,
 *                                dealtEvents / dealtMaxHit / takenEvents / takenMaxHit
 *                  [v2+] varLong recent5sSum
 *                  [v3+] shortString currentWeaponId, varLong weaponDpsActive,
 *                        varInt weaponP95, varInt weaponP95Samples
 * </pre>
 *
 * <h2>StageKey 索引化</h2>
 * <p>所有 StageKey 引用（player.lastSeen / player.stageRows / representativeStage）都用
 * 索引到顶层 {@code stages[]} 列表，避免重复 5 个 String 序列化，包大小压一半左右。
 */
public record StatsSnapshotPayload(
        byte version,
        long startMs,
        long activeDurationMs,
        long startTick,
        boolean live,
        boolean frozen,
        int unattributedKills,
        int representativeStageIdx,
        List<StageEntry> stages,
        List<PlayerEntry> players
) implements CustomPayload {

    public static final byte CURRENT_VERSION = 3;

    public static final CustomPayload.Id<StatsSnapshotPayload> ID = new CustomPayload.Id<>(
            Identifier.of("ctt-health-display", "stats_snapshot")
    );

    /** 一关的元数据（在 player.stageRows 里靠索引引用）。 */
    public record StageEntry(
            String gameId,    // null 时序列化为空字符串
            String tier,
            String floor,
            String stageType,
            String stageNum,
            long enterMs,
            long exitMs,      // 0 = 未退出（仍在该关）
            boolean inProgress
    ) {
        /** 转回 server 端 {@link StageKey}。 */
        public StageKey toKey() {
            return new StageKey(
                    gameId == null || gameId.isEmpty() ? null : gameId,
                    tier, floor, stageType, stageNum
            );
        }

        public static StageEntry fromKey(StageKey k, long enterMs, long exitMs, boolean inProgress) {
            return new StageEntry(
                    k.gameId() == null ? "" : k.gameId(),
                    nullSafe(k.tier()),
                    nullSafe(k.floor()),
                    nullSafe(k.stageType()),
                    nullSafe(k.stageNum()),
                    enterMs, exitMs, inProgress
            );
        }

        private static String nullSafe(String s) { return s == null ? "" : s; }
    }

    /**
     * 一个玩家的总累计 + 各关切片行。
     * <p>v2 起追加 {@code recent5sSum}：最近 5 秒造成伤害量（HUD 关行 DPS = sum / 5）。
     * 老 v1 包反序列化时此字段恒 0。
     * <p>v3 起追加 4 个"当前主手武器维度"字段，让 L 面板顶部摘要能直接显示
     * {@code P95=10(87/100)×3=30  DPS=200×1.5=300  floor=800}。
     * 老 v1/v2 包反序列化时这些字段为空字符串 / 0 / -1。
     */
    public record PlayerEntry(
            UUID uuid,
            String name,
            long dealt,
            long taken,
            int kills,
            int bossKills,
            int assists,
            int dealtEvents,
            int dealtMaxHit,
            int takenEvents,
            int takenMaxHit,
            int lastSeenStageIdx,    // -1 = none
            List<StageRow> stageRows,
            long recent5sSum,        // v2 新增；v1 解码时 = 0
            String currentWeaponId,  // v3 新增；玩家当前主手 weaponId（"empty" 表空手）
            long weaponDpsActive,    // v3 新增；当前主手武器最近 5 秒 DPS_active
            int weaponP95,           // v3 新增；当前主手武器 P95（-1 = 样本不足）
            int weaponP95Samples     // v3 新增；当前主手武器 P95 窗口当前样本数
    ) {}

    /** 玩家在一关里的切片数据。{@code stageIdx} 索引到 {@code stages[]}。 */
    public record StageRow(
            int stageIdx,
            long dealt,
            long taken,
            int kills,
            int bossKills,
            int assists,
            int dealtEvents,
            int dealtMaxHit,
            int takenEvents,
            int takenMaxHit
    ) {}

    public static final PacketCodec<RegistryByteBuf, StatsSnapshotPayload> CODEC = PacketCodec.of(
            StatsSnapshotPayload::write,
            StatsSnapshotPayload::read
    );

    private static void write(StatsSnapshotPayload p, RegistryByteBuf buf) {
        buf.writeByte(p.version);
        buf.writeLong(p.startMs);
        buf.writeLong(p.activeDurationMs);
        buf.writeLong(p.startTick);
        buf.writeBoolean(p.live);
        buf.writeBoolean(p.frozen);
        buf.writeVarInt(p.unattributedKills);
        buf.writeVarInt(p.representativeStageIdx);

        buf.writeVarInt(p.stages.size());
        for (StageEntry s : p.stages) {
            writeShortString(buf, s.gameId);
            writeShortString(buf, s.tier);
            writeShortString(buf, s.floor);
            writeShortString(buf, s.stageType);
            writeShortString(buf, s.stageNum);
            buf.writeLong(s.enterMs);
            buf.writeLong(s.exitMs);
            buf.writeBoolean(s.inProgress);
        }

        buf.writeVarInt(p.players.size());
        for (PlayerEntry e : p.players) {
            buf.writeUuid(e.uuid);
            writeShortString(buf, e.name == null ? "" : e.name);
            buf.writeVarLong(e.dealt);
            buf.writeVarLong(e.taken);
            buf.writeVarInt(e.kills);
            buf.writeVarInt(e.bossKills);
            buf.writeVarInt(e.assists);
            buf.writeVarInt(e.dealtEvents);
            buf.writeVarInt(e.dealtMaxHit);
            buf.writeVarInt(e.takenEvents);
            buf.writeVarInt(e.takenMaxHit);
            buf.writeVarInt(e.lastSeenStageIdx);

            buf.writeVarInt(e.stageRows.size());
            for (StageRow r : e.stageRows) {
                buf.writeVarInt(r.stageIdx);
                buf.writeVarLong(r.dealt);
                buf.writeVarLong(r.taken);
                buf.writeVarInt(r.kills);
                buf.writeVarInt(r.bossKills);
                buf.writeVarInt(r.assists);
                buf.writeVarInt(r.dealtEvents);
                buf.writeVarInt(r.dealtMaxHit);
                buf.writeVarInt(r.takenEvents);
                buf.writeVarInt(r.takenMaxHit);
            }
            // v2 · HUD 关行 DPS 滑窗：最近 5 秒伤害量
            buf.writeVarLong(e.recent5sSum);
            // v3 · 当前主手武器维度（L 面板顶部摘要）
            writeShortString(buf, e.currentWeaponId == null ? "" : e.currentWeaponId);
            buf.writeVarLong(e.weaponDpsActive);
            buf.writeVarInt(e.weaponP95);
            buf.writeVarInt(e.weaponP95Samples);
        }
    }

    private static StatsSnapshotPayload read(RegistryByteBuf buf) {
        byte ver = buf.readByte();
        // v8.1.1 · 旧客户端 ← 新服务端 (ver > CURRENT_VERSION) 防御：
        // 未来若在 StageRow/PlayerEntry 等嵌套结构里追加字段，当前版本按 v2 schema
        // 顺序读取会把"下一行的开头"读成"上一行的多余字节"，varInt 错位后大概率把
        // nRows/nPlayers 读成天文数字，触发 NegativeArraySizeException / OOM 或 OOB，
        // 再被 netty pipeline 抛成 DecoderException 直接踢线（注意：codec 抛异常
        // ≠ 上游 try-catch 兜住，不存在那种兜底）。所以未知未来版本一律 drain 到底
        // 返回空快照，让 client receiver 在本 tick 跳过；UI 沿用上一帧或显示空表。
        if (Byte.toUnsignedInt(ver) > Byte.toUnsignedInt(CURRENT_VERSION)) {
            buf.skipBytes(buf.readableBytes());
            return new StatsSnapshotPayload(
                    ver, 0L, 0L, 0L,
                    false, false,
                    0, -1,
                    Collections.emptyList(),
                    Collections.emptyList()
            );
        }
        long startMs           = buf.readLong();
        long activeDurationMs  = buf.readLong();
        long startTick         = buf.readLong();
        boolean live   = buf.readBoolean();
        boolean frozen = buf.readBoolean();
        int unattributedKills    = buf.readVarInt();
        int representativeIdx    = buf.readVarInt();

        int nStages = buf.readVarInt();
        List<StageEntry> stages = new ArrayList<>(nStages);
        for (int i = 0; i < nStages; i++) {
            String gameId    = readShortString(buf);
            String tier      = readShortString(buf);
            String floor     = readShortString(buf);
            String stageType = readShortString(buf);
            String stageNum  = readShortString(buf);
            long enterMs = buf.readLong();
            long exitMs  = buf.readLong();
            boolean inProgress = buf.readBoolean();
            stages.add(new StageEntry(gameId, tier, floor, stageType, stageNum,
                    enterMs, exitMs, inProgress));
        }

        int nPlayers = buf.readVarInt();
        List<PlayerEntry> players = new ArrayList<>(nPlayers);
        for (int i = 0; i < nPlayers; i++) {
            UUID uuid = buf.readUuid();
            String name = readShortString(buf);
            long dealt = buf.readVarLong();
            long taken = buf.readVarLong();
            int kills      = buf.readVarInt();
            int bossKills  = buf.readVarInt();
            int assists    = buf.readVarInt();
            int dealtEvents= buf.readVarInt();
            int dealtMax   = buf.readVarInt();
            int takenEvents= buf.readVarInt();
            int takenMax   = buf.readVarInt();
            int lastSeenIdx= buf.readVarInt();

            int nRows = buf.readVarInt();
            List<StageRow> rows = new ArrayList<>(nRows);
            for (int j = 0; j < nRows; j++) {
                int stageIdx = buf.readVarInt();
                long rDealt = buf.readVarLong();
                long rTaken = buf.readVarLong();
                int rKills      = buf.readVarInt();
                int rBoss       = buf.readVarInt();
                int rAssists    = buf.readVarInt();
                int rDealtEv    = buf.readVarInt();
                int rDealtMax   = buf.readVarInt();
                int rTakenEv    = buf.readVarInt();
                int rTakenMax   = buf.readVarInt();
                rows.add(new StageRow(stageIdx, rDealt, rTaken,
                        rKills, rBoss, rAssists,
                        rDealtEv, rDealtMax, rTakenEv, rTakenMax));
            }
            // v2 起追加 recent5sSum；v1 包此字段缺省为 0
            long recent5sSum = (ver >= 2) ? buf.readVarLong() : 0L;
            // v3 起追加当前主手武器维度 4 字段；v1/v2 包此处缺省值
            String currentWeaponId = (ver >= 3) ? readShortString(buf) : "";
            long weaponDpsActive   = (ver >= 3) ? buf.readVarLong() : 0L;
            int  weaponP95         = (ver >= 3) ? buf.readVarInt()  : -1;
            int  weaponP95Samples  = (ver >= 3) ? buf.readVarInt()  : 0;
            players.add(new PlayerEntry(uuid, name, dealt, taken,
                    kills, bossKills, assists,
                    dealtEvents, dealtMax, takenEvents, takenMax,
                    lastSeenIdx,
                    Collections.unmodifiableList(rows),
                    recent5sSum,
                    currentWeaponId, weaponDpsActive, weaponP95, weaponP95Samples));
        }

        // v8.1.1 · 末尾保险阀：如果未来版本忘了 bump version 但只在 payload 末尾追加了
        // 与嵌套结构无关的标量字段，slice 仍会有残字节。按规范应 bump version，但万一
        // 忘了，class_9136 的"buffer not fully consumed"校验会直接踢人；这里 drain 一下
        // 让 codec 至少不会因此而断线。代价是这些新字段被丢弃，等于"看不到但不崩"。
        if (buf.isReadable()) {
            buf.skipBytes(buf.readableBytes());
        }
        return new StatsSnapshotPayload(
                ver, startMs, activeDurationMs, startTick,
                live, frozen,
                unattributedKills, representativeIdx,
                Collections.unmodifiableList(stages),
                Collections.unmodifiableList(players)
        );
    }

    /** 玩家名 / stageKey 串：限长 256，超出截断（防 DoS）。 */
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
