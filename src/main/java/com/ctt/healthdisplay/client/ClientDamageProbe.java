package com.ctt.healthdisplay.client;

import com.ctt.healthdisplay.client.probe.AttributionHook;
import com.ctt.healthdisplay.client.probe.FilterHook;
import com.ctt.healthdisplay.client.probe.ParticleObservation;
import com.ctt.healthdisplay.config.ModConfig;
import com.ctt.healthdisplay.server.StageKey;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v6.7.x · P0 · 纯客户端伤害粒子探针（"客户端可见伤害"采集链路核心）。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>每 client tick 扫描世界里的 {@link DisplayEntity.TextDisplayEntity} 实体，识别
 *       "DamageShower 红字伤害粒子"——依据 {@code CLIENT_SIDE_DATA_REFERENCE.md} §5.10 的
 *       background 颜色启发（红 = 伤害，绿 = 治疗，本探针仅取红色）。</li>
 *   <li>从 vanilla scoreboard 里读 {@code DamageShower} objective 在该粒子（实体作为
 *       {@code ScoreHolder}）上的 score——同一粒子的 score 单调上涨，每 tick 只累加增量
 *       （{@code delta = current - lastSeen}）以避免重复计数。详见 §5.6。</li>
 *   <li>v7.0.9 起按方向分流：粒子 ±2m 内有玩家 <b>且</b> {@code delta &lt; 64} → 视为
 *       {@code TAKEN}（吃伤），仅累加进旁路计数 {@link #takenGlobal} / {@link #takenCount}，
 *       HUD <b>不</b>展示；其它情况 → 视为 {@code DEALT}（打怪），全套累加进
 *       {@link #globalTotal} / {@link #stageTotal} / {@link #dpsRing}（5s = 100 tick 滑窗），
 *       HUD / 面板 / 表格展示这一份。承伤侧 KPI 留待 v8 阶段接入。可选发到本地聊天
 *       调试日志（DMG / HIT 两类标签区分）。<br>
 *       <em>注：bg 颜色（红 / 黄 / 灰）不能区分方向——{@code damage.mcfunction} 染红逻辑
 *       会把"玩家附近所有粒子"都染色，颜色与"我打 / 吃伤"无对应关系。</em></li>
 *   <li>不归属、不过滤、不持久化——这些能力靠 {@link AttributionHook} / {@link FilterHook}
 *       预留接口在 v8 阶段接入。本阶段两 hook 均为 NOOP / PASSTHROUGH。</li>
 * </ul>
 *
 * <h2>数据来源（可靠性次序）</h2>
 * <ol>
 *   <li><b>首选</b>：{@code scoreboard.getScore(textDisplay, "DamageShower")} 读整数 score，
 *       永远准确（即便资源包改造了显示文本）。</li>
 *   <li><b>识别启发</b>：{@code background} = {@code -65536}（红色）的 text_display 被认为
 *       是伤害粒子。新生粒子 spawn 后 1 帧才染红——本类轮询在 {@code END_CLIENT_TICK}，已经
 *       过了那 1 帧，无需特殊处理。</li>
 *   <li><b>不</b>解析 {@code text} 字段——{@code damage_shower.mcfunction} 每 tick 重写文字
 *       为分级颜色 / obfuscated 字符（详见 §5.7），文本不可靠。</li>
 * </ol>
 *
 * <h2>与服务端 mod 的关系</h2>
 * <p>本探针是<b>独立通道</b>，与 {@link ClientStatsCache}（接收服务端 S2C 的 stats 镜像）互不
 * 干扰。服务端 mod 装着时两条数据流并存：服务端版有完整归属和过滤，客户端探针提供
 * "全场聚合"裸数据作为对照。服务端 mod 没装时，客户端探针是唯一伤害数据源。
 *
 * <h2>线程模型</h2>
 * <p>所有操作都在 client tick 线程或 render 线程发生：扫描 / 累加在 tick 线程；HUD / 面板 /
 * 表格的 getter（{@link #getGlobalTotal} 等）在 render 线程。读路径是单字段非原子读，
 * 体感上数字偶尔比真实值落后 1 tick 完全可接受，无需加锁。
 */
public final class ClientDamageProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-client-damage-probe");

    public static final ClientDamageProbe INSTANCE = new ClientDamageProbe();

    /** {@code damage.mcfunction} 给 DamageShower 实体写的 score 所属 objective 名。 */
    private static final String DAMAGE_SHOWER_OBJ = "DamageShower";

    /**
     * §5.10 启发：红色背景的 text_display 视为伤害粒子（{@code -65536} = ARGB 全红）。
     * 但地图 {@code damage.mcfunction:1027} 的染红 {@code execute at @a[scores={Damage=1..}]}
     * <b>只在玩家受伤时</b>触发——怪物头上的"打怪"粒子保持默认背景（半透明灰），
     * 不会变红。所以用 {@code bg != HEAL_BG} 作为白名单更合理（v7.0.4 修复）。
     */
    private static final int DAMAGE_BG = -65536;

    /** §5.10 启发：绿色背景视为治疗粒子，本阶段忽略。 */
    private static final int HEAL_BG = -16515325;

    /** 5 秒 DPS 滑窗的 tick 长度（20 tps × 5s）。 */
    private static final int DPS_RING_TICKS = 100;

    /** 5 秒 DPS 的除数（{@link #DPS_RING_TICKS} / 20 tps）。 */
    private static final int DPS_RING_SECONDS = 5;

    /** victim 推断半径（米，平方）：与 {@code AttackerProbe.findVictimByDistance} 对齐。 */
    private static final double VICTIM_LOOKUP_R_SQ = 4.0; // 2m × 2m

    /**
     * v7.0.9 · 吃伤判定·"粒子靠近玩家"半径（米，平方）。dump 实测：
     * 玩家挨揍粒子大多 spawn 在玩家身侧 1–1.5m 内，2m 是经验阈值。
     */
    private static final double NEAR_PLAYER_R_SQ = 4.0; // 2m × 2m

    /**
     * v7.0.9 · 吃伤判定·伤害值下限。dump 实测：
     * <ul>
     *   <li>吃伤典型值：1 / 3 / 5 / 8 / 12 — 都小于 64</li>
     *   <li>打怪典型值：60 / 72 / 80 / 98 / 112 / 144 / 160 / 188 / 240 — 多数 ≥ 64</li>
     * </ul>
     * 阈值 64 是经验取值。bg 颜色因 {@code damage.mcfunction} 染红逻辑会把
     * "玩家附近的所有粒子"都染红——颜色不能区分方向，必须靠数值 + 距离。
     * <p>会有边缘 case：玩家近身贴怪时若打出 &lt; 64 的小数值（DOT / debuff 等），
     * 会被误判为 TAKEN。但这种情况罕见，可接受。
     */
    private static final int HIT_DELTA_THRESHOLD = 64;

    /**
     * §5.7 的 DamageShower 文本格式（严格匹配，避免 NPC 名牌误识别）：
     *
     * <ul>
     *   <li>纯数字（&lt; 400）：{@code "144"} / {@code "8"}</li>
     *   <li>混淆包裹（400-600+）：{@code "XX 144 XX"} / {@code "XXX 9999 XXX"}</li>
     * </ul>
     *
     * <p>合法前后缀字符 = {@code [X 空白 控制字符]}。拒绝 NPC 名牌（含中文 / 字母 / 标点
     * 等其他字符）的误识别。例："骷髅勇士5"、"玩家2" 都不会匹配。
     *
     * <p><b>v7.1.6 关键扩展</b>：原正则只允许 {@code [X\s]} 作前后缀，但地图 ctt_lang 的
     * 中文 lang 文件 {@code zh_cn.json} 把 {@code translate "XX"} 映射成 {@code "\bXX"}
     * （U+0008 退格符 + XX），导致大额伤害（≥400）的渲染字符串变成
     * {@code "XX 470\bXX"} ——尾部 {@code \b} 不在 {@code \s} 范围内，正则 anchor 失败。
     * 视觉上 {@code \b} 是不可见控制字符，但 {@code Text.getString()} 完整保留。
     * 扩展为 {@code [X\s\p{Cntrl}]} 后既覆盖控制字符场景，又保持窄匹配语义不放任意非数字。
     */
    private static final Pattern DAMAGE_TEXT_PATTERN =
            Pattern.compile("^[X\\s\\p{Cntrl}]*(\\d+)[X\\s\\p{Cntrl}]*$");

    // =========================================================================
    //  累加器（S2 数据源）
    // =========================================================================

    /**
     * v7.0.8 起严格语义 = "造成的伤害"（dealt only）。<br>
     * 判定依据：粒子位置 ±2m 内最近活体非玩家。<br>
     * 承伤（taken）的累加器见 {@link #takenGlobal} / {@link #takenCount}，HUD 不展示。
     */
    private long globalTotal;
    private long stageTotal;
    private final long[] dpsRing = new long[DPS_RING_TICKS];

    /** dpsRing 当前写入位（环形）。每 tick 把当前 slot 清零再写入本 tick 的 delta sum。 */
    private int dpsRingTick;

    /** 上一 tick 的 client tick counter——用于检测连续 tick / 漏 tick 时的环形推进。 */
    private long lastTickCounter = -1L;

    /**
     * v7.0.8 · 承伤旁路累加器：仅做轻量计数（总额 + 命中次数），不维护 stage / DPS。
     * 现阶段不显示在 HUD / 面板 / 表格上——为 v8 阶段接入"承伤侧 KPI"预留接口。
     * 调试日志（{@code [CDP]}）仍会输出"→ 我 (吃伤) +N"行，方便观察生效情况。
     */
    private long takenGlobal;
    private int  takenCount;

    // =========================================================================
    //  粒子去重 / 增量
    // =========================================================================

    /** {@code entityId -> 上次见到的 DamageShower score}。粒子 despawn 时按 entity id 缺失自然清理。 */
    private final Map<Integer, Integer> entityToLastScore = new HashMap<>();

    /** 上次扫描见过的 entity id 集合（本 tick 重建），用于检测 despawn 后从 cache 删除。 */
    private final java.util.Set<Integer> seenThisTick = new java.util.HashSet<>(32);

    // =========================================================================
    //  Stage 切换跟踪（S4 数据源）
    // =========================================================================

    /** 当前关 stageKey 缓存——{@link #onClientTick} 每 tick 与 {@link ClientStatsCache#representativeStageKey()} 对比。 */
    private StageKey currentStageKey;

    // =========================================================================
    //  预留接口（S5）
    // =========================================================================

    private AttributionHook attribution = AttributionHook.NOOP;
    private FilterHook filter = FilterHook.PASSTHROUGH;

    // =========================================================================
    //  内部 tick 计数（聊天日志展示用，与 client tick 对齐但持久不溢出）
    // =========================================================================
    private long clientTickCounter;

    // =========================================================================
    //  自我诊断（v7.0.0 hotfix · 调试"探针没数据"问题用）
    // =========================================================================

    /** 本 tick 看到的 text_display 实体总数（不论颜色 / 是否伤害粒子）。 */
    private int diagTextDisplayCount;
    /**
     * 本 tick 进入候选的 text_display 数（即非治疗背景者）。
     * v7.0.4 起含义从"红色背景"扩展为"非绿色"——把怪物头上的默认背景"打怪"粒子
     * 也纳入候选，避免漏统计；具体过滤交给后续 score / text 路径。
     */
    private int diagRedBgCount;
    /** 本 tick 走 scoreboard 路径成功取到 score > 0 的次数。 */
    private int diagScoreHitCount;
    /** 本 tick 走 scoreboard 路径但 score = null/0 的次数。 */
    private int diagScoreMissCount;
    /** 本 tick 走 text fallback 路径解析出数字的次数。 */
    private int diagTextHitCount;
    /** 本 tick 走 text fallback 但仍解析不到数字的次数。 */
    private int diagTextMissCount;
    /** 上一次输出诊断的 tick——避免刷屏，每 100 tick (5s) 一次。 */
    private long lastDiagTick = -100L;

    /** 上一次 verbose dump 的 tick——每 20 tick (1s) 一次，避免刷屏。 */
    private long lastDumpTick = -20L;

    // =========================================================================
    //  v7.1.0 · 客户端击杀计数（"score 跌零 + entity 同 tick destroy" 双判定）
    // =========================================================================

    /**
     * 地图真实血量 objective 候选名（按命中优先级排）。
     * <p>该地图（Cake Team Towers）实测使用 {@code ScoreboardHP}——csp-dump 已确认
     * 它在客户端 list 槽，holder 是 entity UUID 字符串、score 即真实血量。
     * <p>{@code Health} / {@code RedHearts} 是泛化兜底，便于换图时无需改代码。
     */
    private static final String[] HP_OBJECTIVE_CANDIDATES = {"ScoreboardHP", "Health", "RedHearts"};

    /**
     * 上 tick 见到的每个非玩家 LivingEntity 的 HP score 快照——key = {@code entity.getUuidAsString()}。
     * <p>路径 A（entity 仍在 world）和 路径 B（entity 已 destroy）都读这张表的 prev 值判跌零。
     * <p>{@link #onClientTick} 末尾的 {@code retainAll} 把"本 tick 既不在 world 也不在 holder 表的 uuid"清掉，
     * 防止常驻内存累积（地图换关 / 切服时还会被 {@link #clearAll} 显式清空）。
     */
    private final Map<String, Integer> lastHpByUuid = new HashMap<>();

    /** prev → 显示名缓存：路径 B（entity destroy 后）拿不到 entity.getName()，靠这张表回溯名字。 */
    private final Map<String, String> lastNameByUuid = new HashMap<>();

    /** 本 tick 见到的非玩家 LivingEntity uuid 集合（每 tick 重建），路径 B 用 keySet 减它得"已 destroy 的"。 */
    private final java.util.Set<String> seenLivingThisTick = new java.util.HashSet<>(64);

    /** 全局击杀（不含休息室——休息室练习木桩不算战斗成绩）。 */
    private long globalKills;

    /** 本关击杀（含休息室——HUD / 表格里的"本层"语义和 stageTotal 对齐，含休息室练习数）。 */
    private int stageKills;

    /**
     * 已完成关卡的 kills 累计桶（{@link java.util.LinkedHashMap} 保插入序，与 dealt 桶语义一致）。
     * {@link #onStageChanged} 把上一关的 stageKills 落到这里；{@link com.ctt.healthdisplay.hud.StatsTableData#buildStage}
     * 在 fallback 行渲染时读这张表。
     */
    private final java.util.LinkedHashMap<StageKey, Integer> stageHistoryKills = new java.util.LinkedHashMap<>();

    /** 当前 tick 解析出的 HP objective 名（命中候选之一），失败 = null。每 tick 懒求一次。 */
    private String cachedHpObjName;

    /**
     * v7.1.1 · 客户端会话开始时刻（{@link System#currentTimeMillis}）。
     * <p>用于纯客户端模式下 K 表"总表" fallback 行的 ⏱ 列——服务端 mod 没装时
     * {@code ClientStatsCache.sessionDurationMs()} 恒 0，需要客户端自维护一个会话计时。
     * <p>{@code -1L} = 未初始化（首个 onClientTick 时设为 now）；
     * {@link #clearAll} / {@link #resetForDisconnect} 重置回 -1。
     */
    private long sessionStartMs = -1L;

    private ClientDamageProbe() {}

    // =========================================================================
    //  Lifecycle hooks（由 CttHealthDisplay 调度）
    // =========================================================================

    /**
     * 每 client tick 入口。{@code CttHealthDisplay.onInitializeClient} 注册的
     * {@code ClientTickEvents.END_CLIENT_TICK} 调用本方法。
     */
    public void onClientTick(MinecraftClient client) {
        if (client == null) return;
        ClientWorld world = client.world;
        if (world == null) return;

        clientTickCounter++;
        // v7.1.1 · 首个 tick 锚定 session 起点（断线 reset 后会重置为 -1，下次进世界再次锚定）
        if (sessionStartMs < 0L) sessionStartMs = System.currentTimeMillis();

        // 1. 滑窗推进：当前 slot 清零，下次累加只写新 delta
        advanceDpsRing();

        // 2. Stage 切换检测
        StageKey rep = ClientStatsCache.representativeStageKey();
        if (!Objects.equals(rep, currentStageKey)) {
            onStageChanged(rep);
        }

        // 3. 扫描 text_display 实体
        Scoreboard sb = world.getScoreboard();
        ScoreboardObjective obj = sb.getNullableObjective(DAMAGE_SHOWER_OBJ);
        // v7.0.0 hotfix · 即便 objective 缺失也不立刻 return：进 text fallback 路径
        // （地图删除 DamageShower objective 但仍生成 text_display 红字粒子的极端场景）

        // 重置本 tick 诊断计数
        diagTextDisplayCount = 0;
        diagRedBgCount = 0;
        diagScoreHitCount = 0;
        diagScoreMissCount = 0;
        diagTextHitCount = 0;
        diagTextMissCount = 0;

        seenThisTick.clear();
        for (Entity e : world.getEntities()) {
            if (!(e instanceof DisplayEntity.TextDisplayEntity td)) continue;
            diagTextDisplayCount++;
            int bg = td.getBackground();
            // v7.0.4 · 关键修复：地图 damage.mcfunction:1027 只在玩家受伤时染红附近粒子，
            // 怪物头上的"打怪"粒子保持默认背景。所以判定改为"非治疗即候选"，
            // 由下面的 DamageShower score > 0 真正区分"伤害粒子 vs 无关 text_display"。
            if (bg == HEAL_BG) {
                continue;  // 治疗粒子（绿色背景），v7 阶段不统计
            }
            diagRedBgCount++;
            int eid = td.getId();
            seenThisTick.add(eid);

            // 取 currentScore：分两条路径——
            //   主路径：scoreboard.getScore(td, "DamageShower")。§5.6 推荐。
            //     这是判定"是不是伤害粒子"的主判据——任何 text_display 在 DamageShower
            //     objective 上有 score>0 就是伤害粒子（damage.mcfunction:1028 是唯一
            //     写入点）。无误判风险，可信任。
            //   Fallback：解析 td.getText().getString() 提取数字。
            //     仅在 bg == DAMAGE_BG（红色，玩家挨揍）时启用——红色背景几乎肯定是
            //     伤害粒子，安全；默认背景的 text_display 可能是 NPC 名牌等无关实体，
            //     文本含数字会误识别（如"玩家2"），所以默认背景下不走 fallback。
            int currentScore = -1;
            if (obj != null) {
                ReadableScoreboardScore scoreEntry = sb.getScore(td, obj);
                if (scoreEntry != null) {
                    int s = scoreEntry.getScore();
                    if (s > 0) {
                        currentScore = s;
                        diagScoreHitCount++;
                    } else {
                        diagScoreMissCount++;
                    }
                } else {
                    diagScoreMissCount++;
                }
            }
            // v7.0.7 · 关键修复：去掉 "bg == DAMAGE_BG" 限制，让默认背景的"打怪"
            // 粒子也走 text 解析。配合上面的严格正则（^[X\s]*\d+[X\s]*$），无关
            // text_display（NPC 名牌等）不会被误识别。
            //
            // 该地图的客户端拿不到 DamageShower objective（score=obj=null）——score
            // 路径整段失效，text 路径成为唯一可信来源。dump 实测：text 字段渲染后
            // 直接就是 "144" / "68" / "XX 999 XX" 等纯数字格式。
            if (currentScore < 0) {
                int parsed = parseDamageFromText(td);
                if (parsed > 0) {
                    currentScore = parsed;
                    diagTextHitCount++;
                } else {
                    diagTextMissCount++;
                }
            }
            if (currentScore < 0) {
                // 没拿到——这是无关 text_display（如 NPC 名牌、UI 提示），跳过。
                // 不再发 [CDP-skip] 聊天日志（v7.0.5 那条会刷屏，且大部分场景下
                // 跳过都是预期行为）；详细信息仍在 ctt-cdp-dump.log 文件里。
                continue;
            }

            Integer last = entityToLastScore.get(eid);
            int delta;
            if (last == null) {
                // 首次见到：把当前 score 视为本次贡献增量
                delta = currentScore;
            } else if (currentScore > last) {
                delta = currentScore - last;
            } else {
                // score 没动 / 倒退（理论不该）→ 不记入
                entityToLastScore.put(eid, currentScore);
                continue;
            }
            entityToLastScore.put(eid, currentScore);

            Vec3d pos = td.getPos();
            // v7.0.9 · 一次 O(n) 扫描同时拿"最近活体（label 用）+ 是否靠近玩家（方向判定用）"
            // 方向判定改成"近玩家 && 小伤害"才算 TAKEN：
            //   bg=HEAL_BG（绿）          → 上面已 continue（治疗，不统计）
            //   2m 内有玩家 && delta<64  → TAKEN（典型吃伤特征：玩家近身 + 小数值）
            //   其它                     → DEALT（打怪 / 远程 / 大伤害）
            // 不再以 bg 颜色或 nearest 类型判定——dump 实测打怪粒子可能是红/黄/灰任意色，
            // 而吃伤粒子也可能 nearest 是怪（怪贴脸时被怪挡近）。距离 + 数值更稳。
            ScanResult scan = scanNearestAndPlayerProximity(world, pos);
            DamageDirection dir =
                    (scan.nearAnyPlayer && delta < HIT_DELTA_THRESHOLD)
                            ? DamageDirection.TAKEN : DamageDirection.DEALT;
            ingest(client, world, eid, delta, currentScore, pos, dir, scan.nearest);
        }

        // 4. despawn 清理：上 tick 见过但本 tick 没见的 entity id 从 cache 删除
        if (!entityToLastScore.isEmpty()) {
            entityToLastScore.keySet().removeIf(id -> !seenThisTick.contains(id));
        }

        // 4.5 v7.1.0 · 客户端击杀计数（HP score 跌零 + entity destroy 双判定）
        // 始终运行——开销低（每 tick 一次活体扫描，~100 entity），不暴露开关
        scanKillsAndUpdate(client, world, sb);

        // 5. 自我诊断输出：每 100 tick (~5s) 一次，仅当 globalTotal=0 且
        //    确实在场上看到红色 text_display 时输出（避免大厅 / 无战斗时刷屏）。
        //    一旦累加成功（globalTotal > 0）即静默——证明数据通路 OK。
        maybeEmitDiagnostics(client, obj == null);

        // 6. v7.0.6 · Verbose dump：调试聊天开启时，每秒把场上所有 text_display
        //    详细信息全打到聊天栏——便于诊断"哪些粒子是真伤害、哪些是 NPC 名牌、
        //    bg 颜色到底是多少"等问题。不做过滤，全部输出。
        maybeDumpAllTextDisplays(client, world);
    }

    /**
     * v7.0.0 hotfix · text 字段 fallback：当 scoreboard score 不可用时，
     * 从 {@code td.getText().getString()} 提取第一个数字作为 score。
     * 渲染后字符串例：{@code "85"} / {@code "XX99999XX"} / {@code "150"}。
     */
    private static int parseDamageFromText(DisplayEntity.TextDisplayEntity td) {
        Text text = td.getText();
        if (text == null) return -1;
        String raw = text.getString();
        if (raw == null || raw.isEmpty()) return -1;
        Matcher m = DAMAGE_TEXT_PATTERN.matcher(raw);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * v7.0.0 hotfix · 自我诊断输出（5s 一次，dealt 累加 = 0 时才出）。
     * 一旦 dealt 累加成功就静默。出问题时玩家能直接看到出错点。
     *
     * <p>v7.0.8 起增加 {@code taken=N} 提示：dealt=0 但 taken>0 时，意味着探针
     * 数据通路 OK，只是用户暂时没打到怪——这是正常情况，不是 bug。
     *
     * <p>v7.1.4 · <b>受 {@link ModConfig#clientDamageDebugChat} 守卫</b>：
     * 否则朋友拿到 mod 进游戏后，"还没打第一发"+"场上有 text_display"=每 5s
     * 强行刷一行 {@code [CDP 诊断] ...}，导致用户误以为聊天默认是开的。
     * 关闭聊天广播后仍写 LOGGER（latest.log 可查），调试链路不受影响。
     */
    private void maybeEmitDiagnostics(MinecraftClient client, boolean objMissing) {
        if (globalTotal > 0L) return;                       // dealt 已经在累加，静默
        if (clientTickCounter - lastDiagTick < 100) return; // 节流 5s
        if (diagTextDisplayCount == 0) return;              // 视野里没任何 text_display，正常
        lastDiagTick = clientTickCounter;
        if (client.player == null) return;
        String takenInfo = (takenGlobal > 0L)
                ? String.format(" | taken=%d (\u5403\u4f24\u901a\u8def\u6b63\u5e38)", takenGlobal)
                : "";
        String msg = String.format(
                "[CDP \u8bca\u65ad] text_display=%d \u5019\u9009=%d | score \u547d\u4e2d=%d \u672a\u547d\u4e2d=%d | text \u547d\u4e2d=%d \u672a\u547d\u4e2d=%d%s%s",
                diagTextDisplayCount, diagRedBgCount,
                diagScoreHitCount, diagScoreMissCount,
                diagTextHitCount, diagTextMissCount,
                objMissing ? " | obj=MISSING" : "",
                takenInfo);
        // v7.1.4 · 默认仅写日志；开启"聊天栏粒子流水"后才发到聊天。
        if (ModConfig.INSTANCE.clientDamageDebugChat) {
            client.player.sendMessage(Text.literal(msg), false);
        }
        LOGGER.info(msg);
    }

    /**
     * v7.0.15 · 已完成关卡的 dealt 累计桶。
     * <p>{@link #onStageChanged} 把上一关 {@code (oldKey, stageTotal)} 落到这里，
     * 后续切回同一 key（极少见，例如玩家来回穿梭休息室同一 floor）会被 {@link Long#sum} 合并。
     * <p>用 {@link java.util.LinkedHashMap} 保插入顺序，便于"分关表按经过先后渲染"的可选未来需求。
     * <p>无 NBT 持久化（{@code resetForDisconnect} 全清）——会话级数据。
     */
    private final java.util.LinkedHashMap<StageKey, Long> stageHistoryDealt = new java.util.LinkedHashMap<>();

    /**
     * v7.0.17 · 已完成关卡的持续时长桶（毫秒）。
     * <p>每次 {@link #onStageChanged} 把上一关 {@code (now - currentStageStartMs)} 落到这里，
     * 后续切回同一 key 会被 {@link Long#sum} 合并（与 dealt 桶语义一致）。
     */
    private final java.util.LinkedHashMap<StageKey, Long> stageHistoryDurationMs = new java.util.LinkedHashMap<>();

    /**
     * v7.0.17 · 当前关进入时刻（{@link System#currentTimeMillis}）。
     * <p>{@code currentStageKey == null} 时无意义；切关瞬间先用旧值算上一关时长，再覆盖为 now。
     */
    private long currentStageStartMs = 0L;

    /**
     * Stage 切换时调用：把上一关的 {@code stageTotal} + 时长封冻进历史桶，
     * 然后清当前 {@code stageTotal} 并刷新 {@link #currentStageStartMs}；
     * 保留 entityToLastScore（粒子可能跨 stage 短暂存活）。
     */
    public void onStageChanged(StageKey newKey) {
        long now = System.currentTimeMillis();
        if (this.currentStageKey != null) {
            if (this.stageTotal > 0L) {
                stageHistoryDealt.merge(this.currentStageKey, this.stageTotal, Long::sum);
            }
            // v7.1.0 · 击杀也按相同语义封存（与 stageTotal 对齐——这里"含休息室"，
            // 因为 globalKills 已经在累计点处剔除休息室，分关桶要保留休息室练习数）
            if (this.stageKills > 0) {
                stageHistoryKills.merge(this.currentStageKey, this.stageKills, Integer::sum);
            }
            // 时长无论 dealt=0 都要记——大厅 / 休息室停留也是有意义的"经过"
            if (this.currentStageStartMs > 0L) {
                long elapsed = Math.max(0L, now - this.currentStageStartMs);
                stageHistoryDurationMs.merge(this.currentStageKey, elapsed, Long::sum);
            }
        }
        this.currentStageKey = newKey;
        this.stageTotal = 0L;
        this.stageKills = 0;
        this.currentStageStartMs = (newKey == null) ? 0L : now;
    }

    /** 由 HUD 面板"清空数据"按钮 / 断线 hook 调用。 */
    public void clearAll() {
        globalTotal = 0L;
        stageTotal = 0L;
        java.util.Arrays.fill(dpsRing, 0L);
        dpsRingTick = 0;
        takenGlobal = 0L;
        takenCount = 0;
        entityToLastScore.clear();
        stageHistoryDealt.clear();
        stageHistoryDurationMs.clear();
        // v7.1.0 · 击杀计数 + HP 缓存一并清空
        globalKills = 0L;
        stageKills = 0;
        stageHistoryKills.clear();
        lastHpByUuid.clear();
        lastNameByUuid.clear();
        // 当前关仍然在跑 → start 时间重置为 now，已用时长归零
        currentStageStartMs = (currentStageKey == null) ? 0L : System.currentTimeMillis();
        // v7.1.1 · 会话计时也归零；下个 tick 自动重锚（不影响 session 仍在跑的语义）
        sessionStartMs = System.currentTimeMillis();
    }

    /** 切服 / 断线时调用：累加器 + cache 全清，stageKey 也清空。 */
    public void resetForDisconnect() {
        clearAll();
        currentStageKey = null;
        currentStageStartMs = 0L;
        lastTickCounter = -1L;
        clientTickCounter = 0L;
        // v7.1.1 · 断线后下次进世界（onClientTick）会重新锚定 sessionStartMs
        sessionStartMs = -1L;
    }

    // =========================================================================
    //  Getters（HUD / 面板 / 表格读这些）
    // =========================================================================

    /** v7.0.8 起严格语义 = "造成的伤害"全局累计（dealt only）。 */
    public long getGlobalTotal() { return globalTotal; }

    /** v7.0.8 起严格语义 = "造成的伤害"本关累计（dealt only）。 */
    public long getStageTotal()  { return stageTotal; }

    /** v7.0.8 · 承伤总额（taken）——HUD 不展示，调试 / v8 KPI 用。 */
    public long getTakenGlobal() { return takenGlobal; }

    /** v7.0.8 · 承伤命中次数——HUD 不展示，调试 / v8 KPI 用。 */
    public int  getTakenCount()  { return takenCount; }

    /**
     * 最近 5 秒 DPS = sum(dpsRing) / 5。{@code dpsRing} 容量 100 tick = 5s @ 20tps。
     * 服务器 lag 时 client tick 会跟着掉到 &lt; 20 tps，DPS 数字也会按比例失真——这是
     * 客户端无法救的物理事实。
     */
    public int getRecent5sDps() {
        long sum = 0L;
        for (long v : dpsRing) sum += v;
        return (int) (sum / DPS_RING_SECONDS);
    }

    /** 当前 stage key（可能为 null = 大厅 / 未在任何关）。 */
    public StageKey getCurrentStageKey() { return currentStageKey; }

    /**
     * v7.0.15 · 已完成关卡的 dealt 累计快照（不可变副本）。
     * <p>{@link com.ctt.healthdisplay.hud.StatsTableData#buildStage} 在服务端三家 stats 都没
     * 数据的纯客户端模式下，会读这个 + {@link #getStageTotal} 做 fallback 单行渲染。
     */
    public java.util.Map<StageKey, Long> getStageHistoryDealt() {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(stageHistoryDealt));
    }

    /**
     * v7.0.17 · 已完成关卡的时长快照（毫秒，不可变副本）。
     * 与 {@link #getStageHistoryDealt} 配对，{@link com.ctt.healthdisplay.hud.StatsTableData#buildStage}
     * 在 fallback 路径下用来填 PlayerRow.durationMs 字段。
     */
    public java.util.Map<StageKey, Long> getStageHistoryDurationMs() {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(stageHistoryDurationMs));
    }

    /**
     * v7.0.17 · 当前关已用时长（毫秒）。{@code currentStageKey == null} 时返回 0。
     * 实时计算 {@code now - currentStageStartMs}，每次调用都会增长——分关表里的"进行中"行用它。
     */
    public long getCurrentStageDurationMs() {
        if (currentStageKey == null || currentStageStartMs <= 0L) return 0L;
        return Math.max(0L, System.currentTimeMillis() - currentStageStartMs);
    }

    /**
     * v7.0.20 · 当前是否身处休息室。
     * <p>判定依据：detector commit BREAK_ROOM 桶时把 stageType 写成 {@code "BREAK_ROOM@<name>"}，
     * 而 detector 进入 BREAK_ROOM 的唯一触发条件就是看到了 floor 进度 bossbar
     * （例如 {@code "高塔 (21/30)"}）。
     * <p>服务端 payload 兼容：老约定 stageType 为小写 {@code "break_room"}。
     * <p>用法：{@link #ingest} 在 DEALT 分支用它跳过 globalTotal 累加。
     */
    public boolean isInBreakRoom() {
        StageKey k = currentStageKey;
        if (k == null) return false;
        String t = k.stageType();
        if (t == null) return false;
        return t.startsWith("BREAK_ROOM") || t.equals("break_room");
    }

    /**
     * 是否有任何累计数据——HUD / 面板 / K 表决定要不要绘制顶部行用。
     * <p>v7.0.20 · 兼顾"只在休息室造伤"的场景：globalTotal 不会涨但 stageTotal 涨——
     * 此时 HUD CDP 行（只显示本层 + DPS）仍应可见。
     * <p>v7.1.0 · 仅有击杀（伤害=0，例如机制斩 / 路过见死亡）也算"有数据"。
     */
    public boolean hasAnyData() {
        return globalTotal > 0L || stageTotal > 0L
                || globalKills > 0L || stageKills > 0;
    }

    /** v7.1.0 · 全局击杀（不含休息室）。 */
    public long getGlobalKills() { return globalKills; }

    /** v7.1.0 · 当前关击杀（含休息室）。 */
    public int getStageKills() { return stageKills; }

    /**
     * v7.1.0 · 按 stageKey 取击杀数：当前关 → 实时 stageKills；历史关 → 桶里冻结的值；缺则 0。
     * 配合 {@link com.ctt.healthdisplay.hud.StatsTableData#buildStage} fallback 行使用。
     */
    public int getStageKillsAt(StageKey key) {
        if (key == null) return 0;
        if (key.equals(currentStageKey)) return stageKills;
        Integer v = stageHistoryKills.get(key);
        return v == null ? 0 : v;
    }

    /** v7.1.0 · 已完成关卡击杀桶快照（不可变副本）。 */
    public java.util.Map<StageKey, Integer> getStageHistoryKills() {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(stageHistoryKills));
    }

    /** v7.1.0 · 当前命中的 HP objective 名（未命中 = null）。诊断用。 */
    public String getCachedHpObjName() { return cachedHpObjName; }

    /**
     * v7.1.1 · 客户端会话已用时长（毫秒）。
     * <p>纯客户端模式下 K 表"总表" fallback 行的 ⏱ 列读这个值——服务端 mod 没装时
     * {@link com.ctt.healthdisplay.client.ClientStatsCache#sessionDurationMs()} 恒 0，需要客户端自维护。
     * <p>{@link #sessionStartMs} 未初始化（首个 tick 之前 / 断线刚 reset）时返回 0。
     */
    public long getSessionDurationMs() {
        if (sessionStartMs < 0L) return 0L;
        return Math.max(0L, System.currentTimeMillis() - sessionStartMs);
    }

    // =========================================================================
    //  Hook setters（v8 接入时由配置注入）
    // =========================================================================

    public void setAttributionHook(AttributionHook hook) {
        this.attribution = hook == null ? AttributionHook.NOOP : hook;
    }

    public void setFilterHook(FilterHook hook) {
        this.filter = hook == null ? FilterHook.PASSTHROUGH : hook;
    }

    // =========================================================================
    //  内部
    // =========================================================================

    /**
     * v7.0.8 · 方向化 ingest。<br>
     * <ul>
     *   <li>{@link DamageDirection#DEALT}：全套累加（global / stage / dpsRing），HUD 展示</li>
     *   <li>{@link DamageDirection#TAKEN}：仅 {@link #takenGlobal} / {@link #takenCount}，
     *       HUD 不展示——为 v8 接入承伤 KPI 预留接口</li>
     * </ul>
     * 调试日志（{@code [CDP]}）两类都会输出，方便观察方向判定是否正确。
     */
    private void ingest(MinecraftClient client, ClientWorld world,
                        int eid, int delta, int totalScore, Vec3d pos,
                        DamageDirection dir, LivingEntity nearest) {
        if (delta <= 0) return;

        // FilterHook：本阶段 PASSTHROUGH，恒 true
        if (!filter.accept(eid, delta, pos)) return;

        // AttributionHook：本阶段 NOOP，恒 null。结果暂未用到，但调一次保证 hook 接入完整
        attribution.attribute(eid, delta, pos);

        if (dir == DamageDirection.DEALT) {
            // v7.0.20 · 全局总额排除休息室伤害（练习木桩 / 测试假人不算战斗成绩），
            // 但本层（stageTotal）和 DPS 照常累加——休息室 HUD 行仍会刷动数字 / DPS。
            // 判定依据：detector 的 stageType 以 "BREAK_ROOM" 开头（floor bossbar 触发）。
            if (!isInBreakRoom()) {
                globalTotal += delta;
            }
            stageTotal  += delta;
            dpsRing[dpsRingTick] += delta;
        } else {
            // TAKEN——只做轻量计数，不进 HUD / 面板 / 表格
            takenGlobal += delta;
            takenCount++;
            // v7.0.14 · 通知关卡 detector：在大厅状态被打了 → 切到"未知关卡"桶
            ClientStageDetector.onTakenDamage();
        }

        if (ModConfig.INSTANCE.clientDamageDebugChat) {
            sendDebugChat(client, world,
                    new ParticleObservation(eid, delta, totalScore, pos, clientTickCounter),
                    dir, nearest);
        }
    }

    /** v7.0.8 · 粒子方向枚举。判定依据见 {@link #ingest} 调用处的注释。 */
    private enum DamageDirection { DEALT, TAKEN }

    // =========================================================================
    //  v7.1.0 · 击杀扫描（HP score 跌零 + entity destroy 双判定）
    // =========================================================================

    /**
     * 击杀扫描·主循环。每 client tick 调用一次：
     *
     * <ol>
     *   <li><b>HP objective 解析</b>：按 {@link #HP_OBJECTIVE_CANDIDATES} 顺序找首个存在的 objective，
     *       命中名缓存到 {@link #cachedHpObjName}。全部失败 → 整个击杀通道熔断（return）。</li>
     *   <li><b>路径 A · entity 仍在 world</b>：扫所有非玩家活体 → uuid → 读 {@code ScoreboardHP[uuid]}
     *       的 score；若上一 tick 有正 score 而本 tick null/≤0 → 计为击杀（datapack 已写入死亡值）。</li>
     *   <li><b>路径 B · entity 已 destroy</b>：上 tick cache 里有但本 tick 不在 world 的 uuid，
     *       再读一次 score：仍 null/≤0 → 计为击杀；&gt;0 → 是 despawn / 视野外，不计。</li>
     *   <li>清理：cache 只保留本 tick 见过的 uuid。</li>
     * </ol>
     *
     * <h4>边界</h4>
     * <ul>
     *   <li>玩家死亡：被首个 {@code instanceof PlayerEntity} 跳过</li>
     *   <li>首次见到（prev=null）：仅写入 baseline，不计死亡 → 避免空 cache 误报</li>
     *   <li>休息室练习：{@code globalKills} 不增；{@code stageKills} 增（与 {@code stageTotal} 对齐）</li>
     *   <li>HP objective 缺失：{@link #cachedHpObjName} 留 null，下一 tick 重新尝试解析</li>
     * </ul>
     *
     * <h4>性能</h4>
     * <p>每 entity 一次 {@code ScoreHolder.fromName} lambda 分配 + 一次 ConcurrentHashMap 查找。
     * 典型场景 100 个活体 / tick → 2k allocations/s，可忽略 GC。
     */
    private void scanKillsAndUpdate(MinecraftClient client, ClientWorld world, Scoreboard sb) {
        ScoreboardObjective hpObj = resolveHpObjective(sb);
        cachedHpObjName = hpObj == null ? null : hpObj.getName();
        if (hpObj == null) {
            // objective 整个缺失 → 不计、不维护 cache（避免下次 objective 突然出现时全员"复活又被杀"误报）
            lastHpByUuid.clear();
            lastNameByUuid.clear();
            return;
        }

        seenLivingThisTick.clear();

        // --- 路径 A：entity 仍在 world ---
        for (Entity e : world.getEntities()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (e instanceof PlayerEntity) continue;          // 玩家死亡不计
            if (!le.isAlive()) continue;                       // vanilla 死亡帧——同样跳过，等 destroy 后路径 B 处理
            String uuid = e.getUuidAsString();
            seenLivingThisTick.add(uuid);

            Integer prev = lastHpByUuid.get(uuid);
            Integer curr = readHpScore(sb, hpObj, uuid);

            if (prev != null && prev > 0 && (curr == null || curr <= 0)) {
                onKillDetected(client, uuid, e.getName().getString());
            }

            if (curr != null) {
                lastHpByUuid.put(uuid, curr);
                String name = e.getName().getString();
                if (name != null && !name.isEmpty()) lastNameByUuid.put(uuid, name);
            }
        }

        // --- 路径 B：cache 里有但本 tick 不在 world 的 uuid（entity 已 destroy） ---
        if (!lastHpByUuid.isEmpty()) {
            // 用迭代器以便边遍历边删（reatinAll 也行，但要先判定再删，迭代器更清晰）
            java.util.Iterator<Map.Entry<String, Integer>> it = lastHpByUuid.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Integer> en = it.next();
                String uuid = en.getKey();
                if (seenLivingThisTick.contains(uuid)) continue;
                Integer prev = en.getValue();
                if (prev == null || prev <= 0) {
                    it.remove();
                    lastNameByUuid.remove(uuid);
                    continue;
                }
                Integer curr = readHpScore(sb, hpObj, uuid);
                if (curr == null || curr <= 0) {
                    String name = lastNameByUuid.getOrDefault(uuid, uuid.substring(0, 8));
                    onKillDetected(client, uuid, name);
                }
                // 不论是否击杀，都从 cache 移除——destroy 后这条记录已无意义
                it.remove();
                lastNameByUuid.remove(uuid);
            }
        }
    }

    /** 单次击杀计数 + 可选聊天广播（{@link ModConfig#clientKillDebugChat}）。 */
    private void onKillDetected(MinecraftClient client, String uuid, String displayName) {
        if (!isInBreakRoom()) {
            globalKills++;
        }
        stageKills++;

        if (ModConfig.INSTANCE.clientKillDebugChat && client.player != null) {
            String shortUuid = uuid.length() >= 8 ? uuid.substring(0, 8) : uuid;
            String name = (displayName == null || displayName.isEmpty()) ? "?" : displayName;
            String msg = String.format(
                    "[CDP/KILL] tick=%d \u2620 %s (uuid=%s) | stage=%d global=%d",
                    clientTickCounter, name, shortUuid, stageKills, globalKills);
            client.player.sendMessage(Text.literal(msg), false);
        }
    }

    /** 顺位探测 HP objective 名；命中则返回，全部失败返回 null。 */
    private static ScoreboardObjective resolveHpObjective(Scoreboard sb) {
        for (String n : HP_OBJECTIVE_CANDIDATES) {
            ScoreboardObjective o = sb.getNullableObjective(n);
            if (o != null) return o;
        }
        return null;
    }

    /** 读 {@code obj[uuid]} 的 score；holder 不存在时返回 null（注意区别于 score=0）。 */
    private static Integer readHpScore(Scoreboard sb, ScoreboardObjective obj, String uuid) {
        ReadableScoreboardScore s = sb.getScore(ScoreHolder.fromName(uuid), obj);
        return s == null ? null : s.getScore();
    }

    private void advanceDpsRing() {
        long now = clientTickCounter;
        if (lastTickCounter < 0) {
            lastTickCounter = now;
            dpsRingTick = 0;
            dpsRing[0] = 0L;
            return;
        }
        long ticksElapsed = now - lastTickCounter;
        if (ticksElapsed <= 0) return;
        // 推进环形缓冲——若一次跳了多 tick（比如游戏 lag），把中间 slot 也归零
        for (long i = 0; i < ticksElapsed; i++) {
            dpsRingTick = (dpsRingTick + 1) % DPS_RING_TICKS;
            dpsRing[dpsRingTick] = 0L;
        }
        lastTickCounter = now;
    }

    private void sendDebugChat(MinecraftClient client, ClientWorld world,
                               ParticleObservation obs,
                               DamageDirection dir, LivingEntity nearest) {
        if (client.player == null) return;
        // v7.0.8 · victim 描述复用主循环已扫到的 nearest（省一次 O(n)），
        //   并以 dir 为权威方向（避免 nearest=null 时退化成 "?" 后无法判向）
        String victim = formatVictimLabel(client, nearest, obs.pos(), dir);
        // v7.0.8 · 标签：DMG = 造成伤害（计入 HUD），HIT = 承受伤害（旁路计数）
        String tag = (dir == DamageDirection.DEALT) ? "DMG" : "HIT";
        String msg = String.format("[CDP/%s] tick=%d %s +%d (total=%d)",
                tag, obs.clientTick(), victim, obs.delta(), obs.totalScore());
        client.player.sendMessage(Text.literal(msg), false);
    }

    /**
     * v7.0.9 · 一次 O(n) 扫描同时返回：
     * <ul>
     *   <li>{@code nearest}：粒子 ±2m 内最近的存活 LivingEntity（含玩家），用于 victim label</li>
     *   <li>{@code nearAnyPlayer}：粒子 ±2m 内是否存在任意 PlayerEntity（含自己 / 队友），
     *       用于"吃伤"启发判定</li>
     * </ul>
     * 比之前两次独立扫描省一遍 entity 遍历。{@code nearAnyPlayer} 跟 {@code nearest is PlayerEntity}
     * 不等价——怪物贴脸打玩家时怪可能更近，但玩家仍在 2m 内，本字段才能正确表达"近玩家"语义。
     */
    private static ScanResult scanNearestAndPlayerProximity(ClientWorld world, Vec3d pos) {
        LivingEntity nearest = null;
        double bestSq = VICTIM_LOOKUP_R_SQ;
        boolean nearPlayer = false;
        for (Entity e : world.getEntities()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (!le.isAlive()) continue;
            double d = e.getPos().squaredDistanceTo(pos);
            if (d < bestSq) {
                bestSq = d;
                nearest = le;
            }
            if (!nearPlayer && (le instanceof PlayerEntity) && d <= NEAR_PLAYER_R_SQ) {
                nearPlayer = true;
            }
        }
        return new ScanResult(nearest, nearPlayer);
    }

    /** v7.0.9 · {@link #scanNearestAndPlayerProximity} 返回值。 */
    private record ScanResult(LivingEntity nearest, boolean nearAnyPlayer) {}

    /**
     * 启发式 victim 标签（仅调试日志，不参与统计语义）。v7.0.9 起方向后缀 100%
     * 由 {@link DamageDirection} 决定（不再依据 nearest 类型推方向）：
     * <ul>
     *   <li>nearest == 你自己 → {@code "→ 我 (打怪/吃伤)"}</li>
     *   <li>nearest 是其他玩家 → {@code "→ <名> (打怪/吃伤)"}</li>
     *   <li>nearest 是怪物 → {@code "→ <名> (打怪/吃伤)"}</li>
     *   <li>nearest = null → 输出粒子坐标</li>
     * </ul>
     *
     * <p>注意"打怪"在多人场景下不一定是"我"打的——可能是队友打的。客户端没有归属
     * 信息（v7 阶段 AttributionHook = NOOP），无法分辨。
     */
    private static String formatVictimLabel(MinecraftClient client, LivingEntity nearest,
                                            Vec3d pos, DamageDirection dir) {
        String dirSuffix = (dir == DamageDirection.DEALT)
                ? " (\u6253\u602a)"     // (打怪)
                : " (\u5403\u4f24)";    // (吃伤)
        if (nearest == null) {
            return String.format("? [%.1f,%.1f,%.1f]%s", pos.x, pos.y, pos.z, dirSuffix);
        }
        String name = (nearest == client.player) ? "\u6211" : nearest.getName().getString();
        return "\u2192 " + name + dirSuffix;
    }

    // =========================================================================
    //  v7.0.6 · Verbose dump：把场上所有 text_display 详细信息写到日志文件
    // =========================================================================

    /** 用于检测 debugChat "从关到开"的边沿——开启瞬间写一行 session header。 */
    private boolean lastDebugChatState;

    /**
     * 每秒一次，把场上所有 text_display 的全量详细信息写到 {@code logs/ctt-cdp-dump.log}：
     * 每行格式 {@code #N eid=E bg=#AARRGGBB pos=(x,y,z) text="..." nearest=NAME (Dm)}。
     *
     * <p>调试场景：用户开启"聊天栏粒子流水"开关 → 进战斗 → 打开 {@code logs/ctt-cdp-dump.log}
     * 看到每秒一次的全场粒子快照，从中识别"打怪"粒子 vs "玩家挨揍"粒子的真实特征
     * （bg 颜色 / text 内容 / 最近实体 ...），找出哪些字段可作为可靠识别 key。
     */
    private void maybeDumpAllTextDisplays(MinecraftClient client, ClientWorld world) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (!cfg.clientDamageDebugChat) {
            lastDebugChatState = false;
            return;
        }
        // session header：从关到开的边沿瞬间写
        if (!lastDebugChatState) {
            CdpDumpWriter.INSTANCE.resetFailureFlag();
            CdpDumpWriter.INSTANCE.writeSessionHeader("debugChat opened");
            if (client.player != null) {
                client.player.sendMessage(Text.literal(
                        "[CDP] dump \u5199\u5165 " + CdpDumpWriter.INSTANCE.getLogPath()), false);
            }
            lastDebugChatState = true;
        }
        // 节流：每 20 tick (1s) 一次
        if (clientTickCounter - lastDumpTick < 20) return;
        lastDumpTick = clientTickCounter;

        java.util.List<String> lines = new java.util.ArrayList<>(16);
        int idx = 0;
        for (Entity e : world.getEntities()) {
            if (!(e instanceof DisplayEntity.TextDisplayEntity td)) continue;
            idx++;
            int bg = td.getBackground();
            Vec3d pos = td.getPos();
            String text = "";
            try {
                text = td.getText() != null ? td.getText().getString() : "";
            } catch (Throwable ignored) {}
            if (text.length() > 64) text = text.substring(0, 64) + "...";
            text = text.replace("\n", "\\n");

            // 最近活体 + 距离（不论玩家 / 怪物，全部纳入）
            LivingEntity nearest = null;
            double bestSq = 25.0; // 5m 内
            for (Entity e2 : world.getEntities()) {
                if (!(e2 instanceof LivingEntity le)) continue;
                if (!le.isAlive()) continue;
                double d = e2.getPos().squaredDistanceTo(pos);
                if (d < bestSq) {
                    bestSq = d;
                    nearest = le;
                }
            }
            String nearStr = nearest == null ? "<\u65e0>"
                    : nearest.getName().getString() + " (" + String.format("%.1fm", Math.sqrt(bestSq)) + ")";

            // 尝试拿 score（即便 obj 为 null 也试一下）
            String scoreStr = "?";
            try {
                ScoreboardObjective obj = world.getScoreboard().getNullableObjective(DAMAGE_SHOWER_OBJ);
                if (obj == null) {
                    scoreStr = "obj=null";
                } else {
                    ReadableScoreboardScore se = world.getScoreboard().getScore(td, obj);
                    scoreStr = (se == null) ? "miss" : String.valueOf(se.getScore());
                }
            } catch (Throwable t) {
                scoreStr = "ex:" + t.getClass().getSimpleName();
            }

            lines.add(String.format(
                    "  #%d eid=%d bg=#%08X pos=(%.1f,%.1f,%.1f) score=%s text=\"%s\" nearest=%s",
                    idx, td.getId(), bg, pos.x, pos.y, pos.z, scoreStr, text, nearStr));
        }
        if (idx == 0) return; // 场上没 text_display，不写

        String header = String.format("[tick=%d] text_display \u603b\u6570=%d",
                clientTickCounter, idx);
        String[] all = new String[lines.size() + 1];
        all[0] = header;
        for (int i = 0; i < lines.size(); i++) all[i + 1] = lines.get(i);
        CdpDumpWriter.INSTANCE.writeLines(all);
    }
}
