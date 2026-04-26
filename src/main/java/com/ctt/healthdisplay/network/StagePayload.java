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
 * @param kind          {@link com.ctt.healthdisplay.hud.StageLocation.Kind} 的 ordinal
 * @param tier          {@code #Tier CTT}
 * @param floor         {@code #Floor CTT}
 * @param stageNum      当前 stage holder 的值（仅 STAGE_*）
 * @param breakRoomId   {@code #BreakRoomID CTT}（休息室类型 0..6）
 * @param miniGameId    {@code #LobbyMiniGame CTT}（0..8）
 * @param gameOverPhase {@link com.ctt.healthdisplay.hud.StageLocation.GameOverPhase} 的 ordinal
 * @param checkpoint    {@code #CheckPoint CTT == 1}
 */
public record StagePayload(
        byte kind,
        int tier,
        int floor,
        int stageNum,
        byte breakRoomId,
        byte miniGameId,
        byte gameOverPhase,
        boolean checkpoint
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
            },
            buf -> new StagePayload(
                    buf.readByte(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readByte(),
                    buf.readByte(),
                    buf.readByte(),
                    buf.readBoolean()
            )
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
