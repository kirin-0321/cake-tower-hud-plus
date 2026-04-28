package com.ctt.healthdisplay.hud;

import com.ctt.healthdisplay.network.StagePayload;
import com.ctt.healthdisplay.server.StageKey;

/**
 * v6.5.6 / v7.0.10 · 客户端关卡位置缓存（单条全局，仅"我"自己的位置）。
 *
 * <h2>双源合流（v7.0.10）</h2>
 * <ol>
 *   <li><b>fromServer</b>（首选）：服务端 mod 的 {@code StageProbeServer} 通过
 *       {@code StagePayload} 推过来的权威位置。装着服务端 mod 时这是数据源。<br>
 *       由 {@link com.ctt.healthdisplay.CttHealthDisplay} 在 {@code onInitializeClient} 里
 *       注册的 {@code ClientPlayNetworking.registerGlobalReceiver(StagePayload.ID, ...)}
 *       在每次收到服务端 push 时调用 {@link #onPayload(StagePayload)} 更新。</li>
 *   <li><b>fromClient</b>（兜底）：服务端没装 mod 时，{@link com.ctt.healthdisplay.client.ClientStageProbe}
 *       每秒读一次客户端 scoreboard，按与服务端等价的逻辑算出位置写入这里。</li>
 * </ol>
 *
 * <p>{@link #current()} 优先返回 fromServer，{@code UNKNOWN} 时回退 fromClient。
 * HUD {@link StageLocation#probe()} 读这里。
 *
 * <p>线程安全：写入发生在 client network thread (server) / client tick thread (client probe)，
 * 读取发生在 render thread。{@code volatile} 字段足以保证可见性，快照本身是不可变 record。
 */
public final class ClientStageLocation {

    private ClientStageLocation() {}

    private static volatile StageLocation.Snapshot fromServer = StageLocation.Snapshot.unknown();
    private static volatile StageLocation.Snapshot fromClient = StageLocation.Snapshot.unknown();
    private static volatile StageKey fromClientStageKey = null;

    /**
     * 优先返回服务端权威 snapshot；服务端无推送时回退到客户端探测的 snapshot。
     * 两侧都没数据 → {@link StageLocation.Snapshot#unknown()}。
     */
    public static StageLocation.Snapshot current() {
        StageLocation.Snapshot s = fromServer;
        if (s != null && s.kind() != StageLocation.Kind.UNKNOWN) return s;
        StageLocation.Snapshot c = fromClient;
        return c == null ? StageLocation.Snapshot.unknown() : c;
    }

    /**
     * v7.0.10 · 客户端探测的 stageKey（fromServer 不存在时供
     * {@link com.ctt.healthdisplay.client.ClientStatsCache#representativeStageKey()} 兜底用）。
     * 非战斗关 / 大厅 / GameOver 等返回 {@code null}（与 session 桶等价）。
     */
    public static StageKey clientFallbackStageKey() {
        return fromClientStageKey;
    }

    /**
     * v7.0.10 · 当前生效来源是否为客户端兜底（仅诊断 / UI 注释用）。
     */
    public static boolean isUsingClientFallback() {
        StageLocation.Snapshot s = fromServer;
        return s == null || s.kind() == StageLocation.Kind.UNKNOWN;
    }

    /** 收到服务端 payload 时调用（client network thread）。 */
    public static void onPayload(StagePayload payload) {
        fromServer = StageLocation.Snapshot.fromPayload(payload);
    }

    /**
     * v7.0.10 · 客户端 {@link com.ctt.healthdisplay.client.ClientStageProbe} 每秒一次的探测结果。
     * {@code stageKey} 为 {@code null} 表示非战斗关。
     */
    public static void setFromClientProbe(StageLocation.Snapshot snap, StageKey stageKey) {
        fromClient = snap == null ? StageLocation.Snapshot.unknown() : snap;
        fromClientStageKey = stageKey;
    }

    /** 玩家断线 / 切服时清空，避免悬挂上一局数据。 */
    public static void reset() {
        fromServer = StageLocation.Snapshot.unknown();
        fromClient = StageLocation.Snapshot.unknown();
        fromClientStageKey = null;
    }
}
