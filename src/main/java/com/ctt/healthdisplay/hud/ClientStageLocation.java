package com.ctt.healthdisplay.hud;

import com.ctt.healthdisplay.network.StagePayload;

/**
 * v6.5.6 · 客户端关卡位置缓存（单条全局，仅"我"自己的位置）。
 *
 * <p>由 {@link com.ctt.healthdisplay.CttHealthDisplay} 在 {@code onInitializeClient} 里
 * 注册的 {@code ClientPlayNetworking.registerGlobalReceiver(StagePayload.ID, ...)}
 * 在每次收到服务端 push 时调用 {@link #onPayload(StagePayload)} 更新。
 *
 * <p>HUD 渲染在 {@code StageLocation.probe()} 里读 {@link #current()}.
 *
 * <p>线程安全：写入发生在 client network thread，读取发生在 render thread。
 * 我们用 {@code volatile} 字段避免竞态——快照本身是不可变 record。
 */
public final class ClientStageLocation {

    private ClientStageLocation() {}

    private static volatile StageLocation.Snapshot current = StageLocation.Snapshot.unknown();

    public static StageLocation.Snapshot current() {
        return current;
    }

    /** 收到服务端 payload 时调用（client network thread）。 */
    public static void onPayload(StagePayload payload) {
        current = StageLocation.Snapshot.fromPayload(payload);
    }

    /** 玩家断线 / 切服时清空，避免悬挂上一局数据。 */
    public static void reset() {
        current = StageLocation.Snapshot.unknown();
    }
}
