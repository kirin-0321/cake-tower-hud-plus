package com.ctt.healthdisplay.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * v6.5.6 · 服务端 → 客户端关卡位置同步包。
 *
 * <p>Vanilla 不会同步玩家的 {@code scoreboardTags}（即 {@code /tag} 命令打的 tag）到客户端，
 * 因此客户端上 {@code ClientPlayerEntity#getCommandTags()} 永远空 ——
 * 这就是为什么 v6.5.5 直接客户端读 scoreboard 的方案在休息室和关卡里都误判为"主大厅"。
 *
 * <p>修正方案：服务端权威地读 {@link net.minecraft.scoreboard.Scoreboard} 与玩家 tag，
 * 计算出 {@link com.ctt.healthdisplay.hud.StageLocation.Snapshot}，差量推送给本玩家。
 *
 * <p>字段编码：所有枚举走 byte (ordinal)，tier/floor/stageNum 走 VarInt（值域 0..1024 足够），
 * checkpoint 一个 boolean。整包 ~10 bytes。变化时才推，玩家加入时强制推一次完整。
 *
 * <h2>v8.1.0 · MT 分关</h2>
 * <p>新增 {@link #inMagumTrials} 标记：服务端探测到 {@code #LobbyMiniGame CTT == 4} 且
 * {@link com.ctt.healthdisplay.config.ServerConfig#collectMagumTrials} 开启时，payload 不再
 * 一刀切走 MINIGAME 路径，而是 fall-through 到标准 stage 检测，并把此 flag 置 true，下游
 * （{@link com.ctt.healthdisplay.server.StageBoundaryDispatcher} / 客户端 {@link
 * com.ctt.healthdisplay.hud.StageLocation.Snapshot}）据此做 stageType 命名空间隔离与文案前缀。
 *
 * <p>协议向后兼容：旧版客户端无视新字段；新版客户端收到旧服务端 payload（无新字段）时，由
 * {@link #compat(byte, int, int, int, byte, byte, byte, boolean)} 工厂默认置 false，行为退回老逻辑。
 *
 * @param kind            {@link com.ctt.healthdisplay.hud.StageLocation.Kind} 的 ordinal
 * @param tier            {@code #Tier CTT}（MT 路径下覆写为 {@code #MagumTrialDifficulty GameScores}）
 * @param floor           {@code #Floor CTT}
 * @param stageNum        当前 stage holder 的值（仅 STAGE_*）
 * @param breakRoomId     {@code #BreakRoomID CTT}（休息室类型 0..6）
 * @param miniGameId      {@code #LobbyMiniGame CTT}（0..8）
 * @param gameOverPhase   {@link com.ctt.healthdisplay.hud.StageLocation.GameOverPhase} 的 ordinal
 * @param checkpoint      {@code #CheckPoint CTT == 1}
 * @param inMagumTrials   v8.1.0 · 是否处于 Magum Trials 上下文（影响下游 stageType 命名空间与渲染前缀）
 */
public record StagePayload(
        byte kind,
        int tier,
        int floor,
        int stageNum,
        byte breakRoomId,
        byte miniGameId,
        byte gameOverPhase,
        boolean checkpoint,
        boolean inMagumTrials
) implements CustomPayload {

    public static final CustomPayload.Id<StagePayload> ID = new CustomPayload.Id<>(
            Identifier.of("ctt-health-display", "stage_location")
    );

    public static final PacketCodec<RegistryByteBuf, StagePayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeByte(payload.kind);
                buf.writeVarInt(payload.tier);
                buf.writeVarInt(payload.floor);
                buf.writeVarInt(payload.stageNum);
                buf.writeByte(payload.breakRoomId);
                buf.writeByte(payload.miniGameId);
                buf.writeByte(payload.gameOverPhase);
                buf.writeBoolean(payload.checkpoint);
                buf.writeBoolean(payload.inMagumTrials);
            },
            buf -> {
                byte kind        = buf.readByte();
                int tier         = buf.readVarInt();
                int floor        = buf.readVarInt();
                int stageNum     = buf.readVarInt();
                byte breakRoomId = buf.readByte();
                byte miniGameId  = buf.readByte();
                byte gameOver    = buf.readByte();
                boolean checkpt  = buf.readBoolean();
                // v8.1.1 · 双向兼容防御。
                //
                // 背景：custom_payload 外层会把整段 payload 切成一个固定长度的 SlicedByteBuf
                // 交给本 lambda；上层 class_9136 在 lambda 返回后还会校验 slice 是否被完全读完，
                // 任一方向越界都会抛 DecoderException 把客户端踢掉。所以 codec 必须同时防：
                //
                //   (1) 旧服务端 (≤v8.0.x) → 新客户端：payload 比预期短，缺 inMagumTrials。
                //       isReadable() 探测，缺则默认 false，等价老逻辑。
                //   (2) 旧客户端  ← 新服务端 (≥v8.2.0)：未来版本可能在末尾再追加字段，
                //       本版本不识别但也绝不能留残字节给 class_9136 校验，否则同样被踢。
                //       readAllNew() 风格不可行（不知道字段类型），改为读完后排干 slice。
                //
                // 注意：(2) 的兜底只对"末尾追加 + 编码顺序与本版前缀完全一致"的演进生效；
                // 任何字段插入/重排/删除仍属破坏性变更，正确做法是 bump payload ID 而非靠这里。
                boolean inMt = buf.isReadable() && buf.readBoolean();
                if (buf.isReadable()) {
                    buf.skipBytes(buf.readableBytes());
                }
                return new StagePayload(kind, tier, floor, stageNum,
                        breakRoomId, miniGameId, gameOver, checkpt, inMt);
            }
    );

    /**
     * v8.1.0 兼容工厂：旧调用点不传 {@code inMagumTrials} 时默认 false，等价老行为。
     */
    public static StagePayload compat(byte kind, int tier, int floor, int stageNum,
                                      byte breakRoomId, byte miniGameId, byte gameOverPhase,
                                      boolean checkpoint) {
        return new StagePayload(kind, tier, floor, stageNum, breakRoomId, miniGameId,
                gameOverPhase, checkpoint, false);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
