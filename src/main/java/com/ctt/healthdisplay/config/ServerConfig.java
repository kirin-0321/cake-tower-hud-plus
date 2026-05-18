package com.ctt.healthdisplay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端逻辑相关的配置。从 {@link ModConfig} 中拆出的纯服务端字段：
 * 聊天栏广播 3 开关、过滤黑名单、可疑 victim 列表、计分板数据源切换等。
 *
 * <h3>分离的动机</h3>
 * <ul>
 *   <li><b>客户端 vs 服务端职责分离</b> —— {@link ModConfig} 留作客户端 HUD 偏好
 *       （位置 / 大小 / 颜色 / 显示模式），运维向的服务端策略 / 调试开关搬到这里。</li>
 *   <li><b>专用服务器</b> 没有 GUI 配置界面，只能编辑 JSON。把它独立成
 *       {@code config/ctt-health-display-server.json} 后管理员一眼就能看清自己该改哪些。</li>
 *   <li><b>客户端 ConfigScreen</b> 仍然可以在集成单机模式下写这里 —— 同 JVM，
 *       对 {@link #INSTANCE} 的修改即时生效。专用服务器的远程客户端写这里不会同步到服务端。</li>
 * </ul>
 *
 * <h3>迁移</h3>
 * <p>首次加载时若文件不存在，会读旧的 {@code config/ctt-health-display.json} 把这些字段
 * 复制过来，然后写一份新的 {@code config/ctt-health-display-server.json}。
 * 玩家从老版本升级时不会丢配置。
 */
public class ServerConfig {

    /**
     * v6.7.4 起引入的配置版本号。每次某个字段的"默认值发生破坏性变化"（即旧 JSON 里持久化的旧默认值
     * 在新版本下会造成误伤）就把 {@link #CURRENT_CONFIG_VERSION} 加 1，并在 {@link #migrate} 里加一个
     * "if (configVersion &lt; N) { ... }" 分支强制覆写问题字段。
     *
     * <p>v6.7.3 的默认 {@code filterStateBoundary=true} 在 BOSS 战中误伤合法 victim
     * （疑似 Prop / NPC 标签命中），v6.7.4 改默认 false——但已经持久化 JSON 的 true 值
     * 不会因仅改字段默认而被覆盖，必须靠版本号迁移强制覆写。
     */
    public static final int CURRENT_CONFIG_VERSION = 6;

    public static ServerConfig INSTANCE = new ServerConfig();

    /**
     * 该 JSON 文件被生成时的 {@link #CURRENT_CONFIG_VERSION}。v6.7.3 及之前的 JSON 此字段缺失，
     * 反序列化为 0；v6.7.4 起首次写入时为 {@link #CURRENT_CONFIG_VERSION}。
     * {@link #migrate} 据此跳过已迁移过的版本。
     */
    public int configVersion = 0;

    // ===== 聊天栏开发广播 =====
    /** 是否在聊天栏广播伤害事件 {@code [A#N] type victim dmg attacker [layer]}。默认关。 */
    public boolean broadcastDamageInChat = false;
    /** 是否在聊天栏广播击杀消息（含助攻名单）。默认关。 */
    public boolean broadcastKillsInChat = false;
    /** 是否在聊天栏广播承伤事件 {@code [承伤] Player -40}。默认关。 */
    public boolean broadcastTakenInChat = false;
    /**
     * 每关结束聊天栏战绩面板（{@link com.ctt.healthdisplay.server.StageReportBroadcaster}）
     * 的<b>全局兜底</b>开关。默认 {@code false}：
     * <ul>
     *   <li>默认走 per-player 订阅路径（玩家用 {@code /ctthd broadcast stage_report on}
     *       或配置界面订阅后才会收到自己那份战报），照顾新玩家不被刷屏。</li>
     *   <li>设为 {@code true} = 退回 v8.0.x 之前的旧行为，所有在线玩家都会收到，
     *       适合赛事 / 教学场景或运维想统一推广播给全员。</li>
     * </ul>
     * v8.x 迁移分支 v6 把"旧 JSON 没这字段"的玩家锁定到 {@code false}（已经是默认值），
     * 不会保留旧版本的隐式 "全员广播" 行为。
     */
    public boolean broadcastStageReportInChat = false;
    /**
     * 承伤广播阈值：本 tick DamageTook &lt; 该值时不广播。默认 0（全部广播）。
     * v8.x · 默认从 1 改为 0 —— 0 与 1 在 PlayerTakenProbe 的 {@code v &gt; 0} 前置守卫下行为相同，
     * 但 0 的语义更清晰直白（"零阈值 = 全广播"），与 {@link #broadcastDamageThreshold} 对齐。
     * v4 配置版本迁移：旧 JSON 里若仍是默认值 1，会被强制改为 0；用户改过的值（如 5/10）保留。
     */
    public int broadcastTakenThreshold = 0;
    /**
     * 伤害广播阈值（v8.x 引入）：单次 damage &lt; 该值时不入队广播。默认 0（全部广播）。
     * 设为 100 即只显示大额伤害事件（{@code damage &gt;= 100}）。负数（{@link com.ctt.healthdisplay.server.AttackerProbe.Layer#L9_HEAL}
     * 治疗）一律 &lt; 任何非负阈值，自动被过滤；想看治疗事件请保持阈值 0。
     */
    public int broadcastDamageThreshold = 0;

    // ===== 主数据源切换 =====
    /**
     * 为 <b>true</b> 时，从计分板 {@code RedHearts} 下降量取最终伤害；
     * 为 <b>false</b> 时回退 {@code DamageShower} 粒子线（旧路径）。
     */
    public boolean useRedHeartsTally = true;

    // ===== v8.4.0 · 服务端属性 push（旁路 /trigger ViewStats） =====
    /**
     * 是否让服务端主动构造每个玩家的属性面板（{@link com.ctt.healthdisplay.server.ViewStatsBuilder}）
     * 并通过 {@link com.ctt.healthdisplay.network.PlayerStatsPayload} 直接推给该玩家客户端，
     * 让客户端完全跳过自发 {@code /trigger ViewStats} 命令。默认 {@code true}。
     *
     * <p>关掉的场景：服务端管理员调试，或想看客户端走老 chat 解析路径定位 bug。
     * 关掉后客户端会自动 fallback 到 v8.3.x 的命令触发路径（{@code autoRefreshIntervalSeconds}
     * 秒级别再次发命令），与客户端独装一致。
     */
    public boolean serverPushStatsEnabled = true;

    /**
     * 服务端属性 push 的 tick 间隔。默认 20 tick = 1 Hz。
     *
     * <p>本字段 vs 客户端 {@code autoRefreshIntervalSeconds} 完全独立：
     * 服务端走的是直接读 scoreboard 拼 Text，绕过 datapack 的
     * {@code function view_stats.mcfunction}（后者每次执行 0.5–1.5 ms / 玩家），
     * 哪怕 1 tick (50 ms) 也不影响 TPS。{@link com.ctt.healthdisplay.server.ViewStatsBuilder#build}
     * 单次 ~25 µs / 玩家，4 人队 5 Hz 仅占 0.5 ms/s 服务端 CPU。
     * <p>合理区间：5–60 tick (4 Hz–0.33 Hz)。低于 5 tick 没意义 —— 玩家面板感知不到 200 ms 与 50 ms 差异。
     */
    public int serverPushStatsIntervalTicks = 20;

    /**
     * 是否广播全队四色心摘要（{@link com.ctt.healthdisplay.server.TeamHeartsBroadcaster}）。
     * 默认 {@code true} —— 客户端开 {@code showTeammateLayeredHearts} 后，队友头顶 / 侧栏 HUD
     * 直接显示与玩家自己主血条一致的 4 色心叠加。关掉后队友血条退回 v8.3.x 单色多槽。
     */
    public boolean serverPushTeamHeartsEnabled = true;

    /**
     * 全队四色心广播的 tick 间隔。默认 4 tick = 5 Hz。
     *
     * <p>5 Hz 让队友 hp / 心数变化几乎实时（vs vanilla bossbar 同步频率），
     * 但远低于客户端 100 ms 帧间隔，不会造成视觉卡顿。
     * 单次广播 ~1.2 µs/s 服务端 CPU，4 人队 5 Hz 网络 ~2.4 KB/s 总流量，
     * 差量吞噬下稳态零流量。
     * <p>合理区间：2–20 tick (10 Hz–1 Hz)。
     */
    public int serverPushTeamHeartsIntervalTicks = 4;

    // ===== 异常伤害过滤器（v6.7.0+，统一总闸） =====
    /**
     * 异常伤害过滤器总闸。
     *
     * <p>关闭后所有规则旁路（{@link com.ctt.healthdisplay.server.filter.DamageFilterPipeline#applyFilters}
     * 直接返回 {@code pass()}）—— 含 {@link #filterInitHpJumps} 与 {@link #filterSuspectVictims}
     * 在内的全部子规则都不会触发。dev mode 调试 / 数据校准时可临时关掉。
     */
    public boolean filterEnabled = true;

    /**
     * G_low · 低伤害噪声地板。本次 damage 低于该值时**且** victim 不是高护甲怪
     * （{@code Defence ≤ defenceExclusionThreshold}）时直接路由到
     * {@link com.ctt.healthdisplay.server.AttackerProbe.Layer#L9_FILTER}（reason=low-noise）。
     *
     * <p>用途：滤掉高频 1~2 点 DoT / 反伤碎片，避免它们污染未来 P95 训练样本。
     * 默认 3；设为 0 即完全禁用本条规则（仍可独立于 {@link #filterEnabled}）。
     *
     * <p>v6.7.1 · 加 Defence 豁免——石头僵尸 / 远古卫士这类高护甲怪被玩家普通武器打后
     * 减伤后正常输出就是 1~4 点，不是噪声，不应被本规则过滤。
     *
     * <p>v6.7.7 · 默认从 5 降到 3——v6.7.6 实测 BOSS 战 3 点高频伤害（武器白名单已豁免）
     * 之外仍有不属于白名单武器的合法 3~4 点输出（如某些近战 / 召唤物伤害），把地板降到
     * 3 可同时保留 1~2 点 DoT 切除能力 + 放过 3~4 点合法伤害。
     */
    public int lowDamageFloor = 3;

    /**
     * 高护甲怪豁免阈值。{@code victim.Defence} 计分板值大于该阈值时：
     * <ul>
     *   <li>{@link #lowDamageFloor} (G_low) 不触发，伤害正常进玩家账户</li>
     *   <li>未来 P95 训练样本不收录该次伤害（避免拉低均值），但伤害**仍计入玩家账户**
     *       （详见 {@code DAMAGE_FILTER_DESIGN.md} §5.1）</li>
     * </ul>
     *
     * <p>默认 50。地图无 {@code Defence} 计分板时（getNullableObjective 返回 null），
     * 等价于 victim.Defence = 0，该豁免不会触发——降级到原 G_low 行为。
     */
    public int defenceExclusionThreshold = 50;

    /**
     * G_low 武器白名单（v6.7.6 引入）。当{@link #lowDamageFloor} 即将触发，且 victim 周围
     * {@link #lowNoiseWhitelistRadius} 米内存在任一玩家——其主手 custom_data key 或 vanilla
     * item id 与本数组任一元素发生子串匹配——则**豁免** G_low（伤害正常进账户）。
     *
     * <p>动机：CTT 高频低伤武器（如 {@code nutStickLaser} 激光、{@code ak47} 连发）每 tick
     * 对同一目标输出 1~4 点合法伤害。BOSS 实体（"大炮" 等）{@code Defence} 一般为 0，原本的
     * Defence 豁免救不上——这类合法连击会被 G_low 一刀切误过滤（v6.7.5 实测 `reason=low-noise
     * value=3 victim=大炮 (x24)`）。武器白名单在玩家在场时绕开 G_low，让 BOSS 战伤害正常计入。
     *
     * <p>匹配规则：对**主手物品**做 contains 子串匹配（大小写敏感）。比对池有两个：
     * <ul>
     *   <li>{@code Snapshot.mainHand} 集合（CTT custom_data key，如 {@code "nutStickLaser"}）</li>
     *   <li>{@code Snapshot.mainHandItemId} 字符串（vanilla item id，如 {@code "minecraft:bow"}）</li>
     * </ul>
     * 任一池中任一字符串 contains 本数组任一元素 → 即视为命中。
     *
     * <p>默认 {@code ["nutStickLaser", "ak47"]}——用户截图直接确认了 nutStickLaser 的存在；
     * ak47 的精确 key 待用户从聊天 {@code hand=} 字段反馈后再调整。空数组 = 关闭白名单功能。
     *
     * <p>性能：每个 G_low 候选事件遍历在线玩家（一般 ≤ 4），主手 set 大小一般 ≤ 9（custom_data
     * key 数）+ 1 vanilla id；白名单长度一般 ≤ 5。常数级开销，可忽略。
     */
    public String[] lowNoiseWeaponWhitelist = new String[]{"nutStickLaser", "ak47"};

    /**
     * G_low 武器白名单半径（米，v6.7.6 引入）。仅检查 victim 周围本半径内的玩家——
     * 跨地图 / 远距离的玩家不参与豁免判定。默认 16 米——CTT 武器射程一般 ≤ 40 米但
     * 玩家通常贴近 BOSS 输出，16 米半径既能覆盖近战 / 中距离武器，又能避免远端举武器
     * 的玩家误豁免本地 victim 的机制噪声。
     *
     * <p>设为 0 或负数 = 关闭白名单功能（与 {@code lowNoiseWeaponWhitelist=[]} 等效）。
     */
    public double lowNoiseWhitelistRadius = 16.0;

    /**
     * G7a · 物理地板（v6.7.2 引入）。本次 damage 大于
     * {@code victim.MaxHP × physicalCeilMultiplier} 时直接路由到
     * {@link com.ctt.healthdisplay.server.AttackerProbe.Layer#L9_FILTER}：
     * <ul>
     *   <li>受击后 {@code victim.RedHearts ≤ 0} 或实体已 isRemoved → reason = lethal-mechanism
     *       （未来 PendingDamageBuffer 接入后击杀仍归属攻击者，P1 阶段先按 init-hp-jump 同款
     *       不写 contributors）</li>
     *   <li>否则 → reason = oversize（异常大额非致死）</li>
     * </ul>
     *
     * <p>用途：兜底归属失败 / 拿不到 P95 的极端大额事件——CTT 怪物 4 层心数 + 各种吸收，
     * 单击伤害打掉 3 倍 MaxHP 已超出任何合理设计，几乎只能是地图脚本 set 出来的"伪值"。
     * 默认开。
     */
    public boolean filterPhysicalCeil = true;

    /**
     * G7a 物理地板倍率。{@code damage > victim.MaxHP × physicalCeilMultiplier} 时触发。
     * 默认 3——大 BOSS 真实暴击通常在 1~2 倍 MaxHP 附近，3 倍以上属于反常。
     * 设为 0 或负数等价于禁用 G7a（同 {@link #filterPhysicalCeil} = false）。
     */
    public int physicalCeilMultiplier = 3;

    /**
     * G2 · 状态机边界守卫总闸（v6.7.3 引入；v6.7.4 默认改 false）。开启时三个子条件之一命中即过滤：
     * <ul>
     *   <li>{@code #PauseGame CTT > 0} → reason = {@code paused}（地图暂停，所有 score 变动无意义）</li>
     *   <li>{@code tick - lastGameIdChangeTick < sessionBoundaryGuardTicks} → reason = {@code session-boundary}
     *       （局结束 / 新局开始的清场期）</li>
     *   <li>victim {@code commandTags} 含 {@link #suspectVictimTags} 之一 → reason = {@code suspect-tag}
     *       （Coffin 假尸体 / Prop / TestDummy / NPC 等不该有伤害事件的实体）</li>
     * </ul>
     *
     * <p>设计依据 {@code DAMAGE_FILTER_DESIGN.md} §4.5。
     *
     * <p><b>v6.7.4 默认关闭原因</b>：v6.7.3 上线后实测发现 BOSS 战正常 victim（大炮 / 触须 / 小枪海怪）
     * 的伤害也被全部过滤——疑似 CTT 地图给 BOSS 机制怪打了 {@code Prop} / {@code NPC} 标签
     * （DamageShower 粒子带 {@code Prop} 标签是已知事实，见 {@code MAP_DATAPACK_REFERENCE.md} §8.1，
     * 其它 BOSS 实体待确认）。在 reason 暴露到聊天广播前先临时关闭此规则，避免误伤合法战斗事件。
     * 用户在 {@code config/ctt-health-display-server.json} 手动设为 true 即可启用并诊断具体 reason。
     */
    public boolean filterStateBoundary = false;

    /**
     * G2 · {@code session-boundary} 子规则的窗口长度（tick）。{@link StageBoundaryDispatcher#updateGameId}
     * 检测到 GameID 跳变（局结束 / 新局开始）后，往后 N tick 内的所有伤害事件被视为清场而非战斗，
     * 一律过滤。默认 5——CTT 关卡切换瞬间的脚本批量 set RedHearts 通常 1~3 tick 内完成，留 5 tick 余量。
     * 设为 0 等价禁用本子规则。
     */
    public int sessionBoundaryGuardTicks = 5;

    /**
     * G2 · {@code suspect-tag} 子规则的 tag 黑名单。victim 的 {@code commandTags}（datapack 通过
     * {@code /tag @e add ...} 加上的标签）含其中任一即过滤。
     *
     * <p>默认列表来自 {@code MAP_DATAPACK_REFERENCE.md}：
     * <ul>
     *   <li>{@code Coffin} —— 地图特有"假尸体"实体，{@code RedHearts ≤ 0} 但 NoAI 不死，无意义伤害源</li>
     *   <li>{@code Prop} —— 装饰物 / 道具实体（荷尔等 marker）</li>
     *   <li>{@code NPC} —— 剧情 NPC，被打不该计入战斗统计</li>
     *   <li>{@code TestDummy} —— 测试用木桩</li>
     *   <li>{@code Debug} —— 调试用实体</li>
     * </ul>
     */
    public String[] suspectVictimTags = new String[]{"Coffin", "Prop", "NPC", "TestDummy", "Debug"};

    /**
     * G6 · 重放守卫总闸（v6.7.3 引入；v6.7.5 默认改 false）。开启时若本次 {@code (victim UUID, damage)}
     * 与上一 tick 完全相同（即同一 victim 上一 tick 已记录过同样数值的伤害事件且被放行），本次直接路由到
     * {@link com.ctt.healthdisplay.server.AttackerProbe.Layer#L9_FILTER}（reason = {@code duplicate}）。
     *
     * <p><b>v6.7.5 默认关闭原因</b>：实测发现 CTT 激光 / 连发武器（如 {@code nutStickLaser}）每 tick
     * 对同一目标输出固定伤害值——多 tick 持续输出时全部被本规则误判为重放。例：
     * <pre>
     * [伤害] Kirin0321 MeleeDMG L1 -7 大炮 hand=nutStickLaser ...    ← 合法首击
     * [伤害] [黑名单] AllDMG L9-FILT -7 大炮 reason=duplicate ... (x34) ← 误伤
     * </pre>
     *
     * <p>根因：P1 简化版只能从 pipeline 入口拿到 {@code (victim UUID, damage, tick)} 三元组，
     * 缺少 attacker 维度——无法区分"同一 attacker 的合法连击"与"同一假伤害事件的重放"。
     * 完整版 G6 必须在 {@code PendingDamageBuffer} 里做：buffer 持有 attacker 解析结果，
     * 三元组扩展为 {@code (victim UUID, attacker UUID, damage, tick)} 即可正确判定。
     * P1 阶段先临时关闭，等 P3~P4 buffer 上线后重新启用。
     *
     * <p>用户在 {@code config/ctt-health-display-server.json} 手动设为 true 即可启用——
     * 但仅适用于"确认服务端没有连发武器"的特殊环境。
     */
    public boolean filterDuplicateReplay = false;

    // ===== 大额数值黑名单过滤 =====
    /** 是否过滤"特定大额数值"为伪伤害。 */
    public boolean filterInitHpJumps = true;
    /**
     * 黑名单数值。来自地图侧扫描：
     * <ul>
     *   <li>1000 · 25+ 普通怪初始化 / Cauldron 过场</li>
     *   <li>9000 · 荷尔等 prop / marker 类初始化（v6.6.9 补） —
     *       例：{@code [伤害] AllDMG L4 -9000 荷尔 MARKER/Prop/pid=...}</li>
     *   <li>10000 · necro_king / fury_david / warden / race_horse 等 boss 初始化</li>
     *   <li>100000 · golden_chicken（五形态怪每形态切换）</li>
     * </ul>
     */
    public int[] initHpJumpValues = new int[]{1000, 9000, 10000, 100000};

    // ===== 关卡级黑名单（v6.6.8） =====
    /**
     * 关卡黑名单：处于这些关时不采集任何 stats（伤害 / 击杀 / 承伤）。
     *
     * <p>条目格式：{@code "stageType:stageNum"}，与 {@link com.ctt.healthdisplay.server.StageKey}
     * 第 4/5 字段对齐：
     * <ul>
     *   <li>stageType ∈ {@code boss / mboss / dungeon / shop / ally / misc}</li>
     *   <li>stageNum 是地图侧 {@code #Boss/#MBoss/#Dungeon/...} scoreboard 的具体值</li>
     * </ul>
     *
     * <p>默认屏蔽：
     * <ul>
     *   <li>{@code dungeon:47} · The Race / 赛马 — 关卡脚本会
     *       {@code scoreboard players add ... RedHearts 600} 给坐骑刷血，
     *       模组的 RedHearts 通道会把这视作 600 点的"伤害事件"，
     *       叠加马匹高频心跳还会导致刷屏 → 直接屏蔽该关</li>
     * </ul>
     *
     * <p>命中机制：{@link com.ctt.healthdisplay.server.StageBoundaryDispatcher#computeState}
     * 把该关视为"非战斗关"，三家 stats 的 {@code isCollecting} 检查直接返回 false。
     */
    public String[] blockedStages = new String[]{"dungeon:47"};

    // ===== Magum Trials 分关采集（v8.1.0 引入）=====
    /**
     * 是否把 Magum Trials（{@code #LobbyMiniGame CTT == 4}）按"30 关分关"采集，而不是
     * 一刀切打成 MINIGAME 黑箱。
     *
     * <ul>
     *   <li><b>true（默认）</b>：MT 内每一关（含 0 数据的纯路过 shop / ally）独立成 stageKey 桶，
     *       正常 ENTER / EXIT / 广播 / 持久化。stageType 加 {@code mt_} 前缀避免与大厅塔同 ID
     *       子关合桶；tier 维度用 {@code #MagumTrialDifficulty GameScores}（1..10）替代 {@code #Tier CTT}。</li>
     *   <li><b>false</b>：完全回到 v8.0.x 老行为——MT 全程 MINIGAME 黑箱、不采集、frozen。
     *       线上出问题时的快速回退闸。</li>
     * </ul>
     *
     * <p>详见 {@code docs/MAGUM_TRIALS_STAGE_TRACKING.md}。
     *
     * <p>v8.1.0 引入；旧 JSON 升级时缺字段，Gson 自动落默认值 true，无需 migrate 分支。
     */
    public boolean collectMagumTrials = true;

    // ===== 可疑 victim 过滤（按显示名子串 + 阈值）=====
    /** 是否过滤"特定怪物 + 单次伤害 ≥ {@link #suspectVictimDamageThreshold}"组合。 */
    public boolean filterSuspectVictims = true;
    /** 可疑 victim 显示名关键字列表（子串匹配，区分大小写）。默认两条："幽匿骷髅" / "幽匿僵尸"。 */
    public String[] suspectVictims = new String[]{"\u5e7d\u533f\u9ab7\u9ac5", "\u5e7d\u533f\u50f5\u5c38"};
    /** 仅当本次记录的伤害 &ge; 此阈值时才触发"可疑 victim"过滤。默认 800。 */
    public int suspectVictimDamageThreshold = 800;

    // ===== v8.x · G7b 三指标 AND 异常过滤 + PendingDamageBuffer =====

    /**
     * G7b 总闸（含 PendingDamageBuffer + per-(player, weapon) P95 + DPS_active 双桶）。
     *
     * <p>关闭后 {@link com.ctt.healthdisplay.server.filter.DamageFilterPipeline#applyFilters}
     * 跑完同步规则栈直接 {@code pass()}，不入 buffer、不查 P95 / DPS、不触发 outlier / lethal-mechanism。
     * 退化到 v6.7.x 现状（仅 G_low / G2 / G3 / G4 / G7a / G6）。
     *
     * <p>设计依据：{@code 大额伤害过滤器.md} §三道门 + V2.1 文档 §0.3 / §7.5。
     */
    public boolean useDamageBuffer = true;

    /**
     * G7b · PendingDamageBuffer 出队延迟（tick 数）。事件入队后等待 N tick 再 flush，
     * 让"几乎同时到达的 RedHearts 致死信号"能在 flush 前被 {@code wasLethalAtBuffer} 标记。
     *
     * <p>默认 2——CTT 实测脚本 set RedHearts 与 *DMG 粒子的间隔通常 ≤ 1 tick；2 tick 留 50 ms 余量。
     * 设为 0 等价同步 flush（失去致死性识别能力）。
     */
    public int bufferDelayTicks = 2;

    /**
     * G7b · PendingDamageBuffer 容量上限。溢出时丢最老 entry，{@code overflowDropped}
     * 计数 +1。默认 4096——CTT 高强度战斗 ≤ 200 events/s × 2 tick delay ≈ 20 entries 同时驻留，
     * 4096 足够 5 倍冗余。
     */
    public int bufferMaxSize = 4096;

    /**
     * G7b · 三指标 AND 子规则总闸。{@code useDamageBuffer=true} 但本字段 false 时
     * buffer 仍跑（用于诊断 + flush 时仍按"无致死性识别"路径发回 normal 归属），
     * 但不触发 outlier / lethal-mechanism 路由。
     */
    public boolean p95OutlierEnabled = true;

    /**
     * G7b · per-(player, weapon) P95 滑窗容量 N。默认 100——20~50 数量级偏小波动大；
     * 200 数量级响应慢且内存多倍。100 在响应 / 稳定之间最佳。
     */
    public int p95WindowSize = 100;

    /**
     * G7b · 启用 P95 判定的最小样本数。{@code window.size &lt; minSamples} 时
     * {@code p95(uuid, weaponId)} 返回 -1，AND 关系下 P95 维度判定跳过——降级为
     * "纯地板门 + DPS 门"双指标 AND（对应 minSamples 期间纯 G7a 兜底场景，
     * 详见 V2.1 §6.4 / 大白话 §四）。默认 20。
     */
    public int p95MinSamples = 20;

    /**
     * G7b · P95 维度阈值乘数 K_p：阈值 = {@code P95 × K_p}。
     *
     * <p>默认 3——配合 {@link #outlierAbsoluteFloor} = 800 + ABC 三武器场景下：
     * <ul>
     *   <li>合法 600 单刀：被 floor=800 拦在前，永远不触发</li>
     *   <li>异常 1000 + B 武器主导（P95=300）：阈值 900 ✓ 命中</li>
     *   <li>异常 1000 + A 武器主导（P95=10）：阈值 30 ✓ 命中</li>
     * </ul>
     * 详见 {@code 大额伤害过滤器.md} §四。
     */
    public int p95OutlierMultiplier = 3;

    /**
     * G7b · DPS 维度阈值乘数 M_d：阈值 = {@code DPS_active × M_d}。
     * 用 {@code int * 0.5} 表达半数倍——内部按 {@code damage > dps × M_d} 整数运算时
     * 用 {@code damage * 2 > dps × (2 × M_d)}，避免浮点。
     *
     * <p>默认 1.5（内部为 {@code dpsActiveMultiplierTimes2 = 3}，即 ×1.5）。
     */
    public double dpsActiveMultiplier = 1.5;

    /**
     * G7b · 三指标 AND 第三项·绝对地板。{@code damage &gt; outlierAbsoluteFloor} 才进 AND 后续判定，
     * 否则直接放行。
     *
     * <p>默认 800——介于"已知合法最大单刀 600"（C 武器）与"已知异常 1000"之间。
     * 调参原则：
     * <ul>
     *   <li>过低（&lt; 600）→ 误伤合法单刀</li>
     *   <li>过高（&gt; 1000）→ 漏过异常</li>
     *   <li>800 = 中点 + 33% 余量，最稳</li>
     * </ul>
     *
     * <p>设为 0 等价禁用本道门——AND 退化为 V2.1 双指标（容易在 B 武器主导时漏过 1000）。
     */
    public int outlierAbsoluteFloor = 800;

    // ===== I/O =====
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SERVER_CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("ctt-health-display-server.json");
    private static final Path LEGACY_CLIENT_CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("ctt-health-display.json");

    public void save() {
        try (Writer writer = Files.newBufferedWriter(SERVER_CONFIG_PATH)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void load() {
        if (Files.exists(SERVER_CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(SERVER_CONFIG_PATH)) {
                ServerConfig loaded = GSON.fromJson(reader, ServerConfig.class);
                if (loaded != null) {
                    boolean migrated = loaded.migrate();
                    loaded.normalize();
                    INSTANCE = loaded;
                    if (migrated) INSTANCE.save();
                    return;
                }
            } catch (Exception e) {
                INSTANCE = new ServerConfig();
            }
            return;
        }

        // 首次加载：从老的合并 JSON 迁移已知字段，再落盘
        ServerConfig fresh = new ServerConfig();
        tryMigrateFromLegacy(fresh);
        fresh.configVersion = CURRENT_CONFIG_VERSION;
        fresh.normalize();
        INSTANCE = fresh;
        INSTANCE.save();
    }

    /**
     * 按 {@link #configVersion} 逐级迁移到 {@link #CURRENT_CONFIG_VERSION}。
     *
     * @return 是否发生了任何迁移（true 时 caller 应保存文件以固化新版本号）
     */
    private boolean migrate() {
        boolean changed = false;
        if (configVersion < 1) {
            // v6.7.4 · G2 状态机边界守卫默认改 false：v6.7.3 上线后实测 BOSS 战正常 victim
            //   被全部过滤（疑似 commandTags 含 Prop / NPC，与 suspectVictimTags 默认列表撞车）。
            //   在 reason 暴露到聊天广播 + 用户回报具体子规则之前，强制覆盖旧 JSON 的 true 默认。
            //   用户需要重新启用时手动改 JSON 即可——后续版本号不会再触发本分支。
            this.filterStateBoundary = false;
            changed = true;
        }
        if (configVersion < 2) {
            // v6.7.5 · G6 重放守卫默认改 false：v6.7.4 上线后实测 CTT 激光 / 连发武器
            //   每 tick 输出固定伤害值（如 nutStickLaser 7 点连击 x34），全部被 G6 误判为
            //   duplicate。P1 简化版无 attacker 维度，无法区分"合法连击"与"假伤害重放"。
            //   等 P3~P4 PendingDamageBuffer 上线后再重新启用（届时三元组扩展为
            //   victim+attacker+damage 即可正确判定）。
            this.filterDuplicateReplay = false;
            changed = true;
        }
        if (configVersion < 3) {
            // v6.7.8 · G_low 默认地板从 5 降到 3（v6.7.7 改的代码默认）。条件覆写：仅当
            //   旧值正好是 v6.7.6 之前的默认值 5 时才覆盖——若用户特意改过（如 4 / 6 / 0），
            //   保留用户配置。意图：让"从未动过 lowDamageFloor 的用户"自动跟上新默认；
            //   "调过 lowDamageFloor 的用户"配置不被覆盖。
            if (this.lowDamageFloor == 5) {
                this.lowDamageFloor = 3;
                changed = true;
            }
        }
        if (configVersion < 4) {
            // v8.x · 承伤广播阈值默认从 1 改为 0。条件覆写：旧值正好是默认 1 时才改 0，
            //   用户改过（5 / 10 / ...）的值保留。
            //   broadcastDamageThreshold 是 v8.x 新字段，旧 JSON 没有，反序列化为默认值 0,
            //   不需要专门处理。
            if (this.broadcastTakenThreshold == 1) {
                this.broadcastTakenThreshold = 0;
                changed = true;
            }
        }
        if (configVersion < 5) {
            // v8.x · G7b 三指标 AND + PendingDamageBuffer 引入。
            //   useDamageBuffer / bufferDelayTicks / bufferMaxSize / p95OutlierEnabled /
            //   p95WindowSize / p95MinSamples / p95OutlierMultiplier / dpsActiveMultiplier /
            //   outlierAbsoluteFloor 都是新字段，旧 JSON 反序列化为默认值——直接用即可，
            //   不需要专门覆写。本分支仅作为版本号升级占位，让 migrate 链能跨版本递推。
            changed = true;
        }
        if (configVersion < 6) {
            // v8.4.0 · 服务端属性 push + 全队四色心广播引入。
            //   serverPushStatsEnabled / serverPushStatsIntervalTicks /
            //   serverPushTeamHeartsEnabled / serverPushTeamHeartsIntervalTicks
            //   都是新字段，旧 JSON 反序列化为 false / 0。
            //   对老用户而言"升级 = 默认启用"是期望行为（性能正面提升 + 队友 4 色心新功能），
            //   所以这里强制覆写一次到推荐默认值；用户改过的非默认值会被覆盖但属可接受
            //   ——本字段是 v8.4.0 才存在，"用户改过的旧值"逻辑上不存在。
            serverPushStatsEnabled = true;
            if (serverPushStatsIntervalTicks <= 0) serverPushStatsIntervalTicks = 20;
            serverPushTeamHeartsEnabled = true;
            if (serverPushTeamHeartsIntervalTicks <= 0) serverPushTeamHeartsIntervalTicks = 4;
            changed = true;
        }
        // 任何迁移分支跑完后把版本号顶到当前。
        if (configVersion < CURRENT_CONFIG_VERSION) {
            configVersion = CURRENT_CONFIG_VERSION;
            changed = true;
        }
        return changed;
    }

    /**
     * 从旧 {@code config/ctt-health-display.json} 中读取 v6.5.x 时期遗留的服务端字段。
     * 找到就拷贝；找不到不报错（就用默认值）。
     */
    private static void tryMigrateFromLegacy(ServerConfig dst) {
        if (!Files.exists(LEGACY_CLIENT_CONFIG_PATH)) return;
        try (Reader reader = Files.newBufferedReader(LEGACY_CLIENT_CONFIG_PATH)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) return;
            JsonObject obj = root.getAsJsonObject();

            if (obj.has("broadcastDamageInChat"))
                dst.broadcastDamageInChat = obj.get("broadcastDamageInChat").getAsBoolean();
            if (obj.has("broadcastKillsInChat"))
                dst.broadcastKillsInChat = obj.get("broadcastKillsInChat").getAsBoolean();
            if (obj.has("broadcastTakenInChat"))
                dst.broadcastTakenInChat = obj.get("broadcastTakenInChat").getAsBoolean();
            if (obj.has("broadcastTakenThreshold"))
                dst.broadcastTakenThreshold = obj.get("broadcastTakenThreshold").getAsInt();
            if (obj.has("broadcastDamageThreshold"))
                dst.broadcastDamageThreshold = obj.get("broadcastDamageThreshold").getAsInt();

            if (obj.has("useRedHeartsTally"))
                dst.useRedHeartsTally = obj.get("useRedHeartsTally").getAsBoolean();

            if (obj.has("filterEnabled"))
                dst.filterEnabled = obj.get("filterEnabled").getAsBoolean();
            if (obj.has("lowDamageFloor"))
                dst.lowDamageFloor = obj.get("lowDamageFloor").getAsInt();
            if (obj.has("defenceExclusionThreshold"))
                dst.defenceExclusionThreshold = obj.get("defenceExclusionThreshold").getAsInt();

            if (obj.has("filterPhysicalCeil"))
                dst.filterPhysicalCeil = obj.get("filterPhysicalCeil").getAsBoolean();
            if (obj.has("physicalCeilMultiplier"))
                dst.physicalCeilMultiplier = obj.get("physicalCeilMultiplier").getAsInt();

            if (obj.has("filterStateBoundary"))
                dst.filterStateBoundary = obj.get("filterStateBoundary").getAsBoolean();
            if (obj.has("sessionBoundaryGuardTicks"))
                dst.sessionBoundaryGuardTicks = obj.get("sessionBoundaryGuardTicks").getAsInt();
            if (obj.has("suspectVictimTags") && obj.get("suspectVictimTags").isJsonArray()) {
                List<String> tmp = new ArrayList<>();
                for (JsonElement e : obj.get("suspectVictimTags").getAsJsonArray()) {
                    try { tmp.add(e.getAsString()); } catch (Exception ignored) {}
                }
                if (!tmp.isEmpty()) {
                    dst.suspectVictimTags = tmp.toArray(new String[0]);
                }
            }

            if (obj.has("filterDuplicateReplay"))
                dst.filterDuplicateReplay = obj.get("filterDuplicateReplay").getAsBoolean();

            if (obj.has("filterInitHpJumps"))
                dst.filterInitHpJumps = obj.get("filterInitHpJumps").getAsBoolean();
            if (obj.has("initHpJumpValues") && obj.get("initHpJumpValues").isJsonArray()) {
                List<Integer> tmp = new ArrayList<>();
                for (JsonElement e : obj.get("initHpJumpValues").getAsJsonArray()) {
                    try { tmp.add(e.getAsInt()); } catch (Exception ignored) {}
                }
                if (!tmp.isEmpty()) {
                    dst.initHpJumpValues = tmp.stream().mapToInt(Integer::intValue).toArray();
                }
            }

            if (obj.has("filterSuspectVictims"))
                dst.filterSuspectVictims = obj.get("filterSuspectVictims").getAsBoolean();
            if (obj.has("suspectVictims") && obj.get("suspectVictims").isJsonArray()) {
                List<String> tmp = new ArrayList<>();
                for (JsonElement e : obj.get("suspectVictims").getAsJsonArray()) {
                    try { tmp.add(e.getAsString()); } catch (Exception ignored) {}
                }
                if (!tmp.isEmpty()) {
                    dst.suspectVictims = tmp.toArray(new String[0]);
                }
            }
            if (obj.has("suspectVictimDamageThreshold"))
                dst.suspectVictimDamageThreshold = obj.get("suspectVictimDamageThreshold").getAsInt();

            // ===== v8.x · G7b 三指标 AND（首次 legacy 迁移路径不会有这些字段，但加上保险） =====
            if (obj.has("useDamageBuffer"))
                dst.useDamageBuffer = obj.get("useDamageBuffer").getAsBoolean();
            if (obj.has("bufferDelayTicks"))
                dst.bufferDelayTicks = obj.get("bufferDelayTicks").getAsInt();
            if (obj.has("bufferMaxSize"))
                dst.bufferMaxSize = obj.get("bufferMaxSize").getAsInt();
            if (obj.has("p95OutlierEnabled"))
                dst.p95OutlierEnabled = obj.get("p95OutlierEnabled").getAsBoolean();
            if (obj.has("p95WindowSize"))
                dst.p95WindowSize = obj.get("p95WindowSize").getAsInt();
            if (obj.has("p95MinSamples"))
                dst.p95MinSamples = obj.get("p95MinSamples").getAsInt();
            if (obj.has("p95OutlierMultiplier"))
                dst.p95OutlierMultiplier = obj.get("p95OutlierMultiplier").getAsInt();
            if (obj.has("dpsActiveMultiplier"))
                dst.dpsActiveMultiplier = obj.get("dpsActiveMultiplier").getAsDouble();
            if (obj.has("outlierAbsoluteFloor"))
                dst.outlierAbsoluteFloor = obj.get("outlierAbsoluteFloor").getAsInt();
        } catch (Exception ignored) {
        }
    }

    /** 修补反序列化后的非法值（数组为 null 等），保证调用方读到的字段不会 NPE。 */
    private void normalize() {
        if (initHpJumpValues == null) initHpJumpValues = new int[0];
        if (suspectVictims == null) suspectVictims = new String[0];
        if (suspectVictimTags == null) suspectVictimTags = new String[0];
        if (blockedStages == null) blockedStages = new String[0];
        if (lowNoiseWeaponWhitelist == null) lowNoiseWeaponWhitelist = new String[0];
        if (broadcastTakenThreshold < 0) broadcastTakenThreshold = 0;
        if (broadcastDamageThreshold < 0) broadcastDamageThreshold = 0;
        if (suspectVictimDamageThreshold < 0) suspectVictimDamageThreshold = 0;
        if (lowDamageFloor < 0) lowDamageFloor = 0;
        if (defenceExclusionThreshold < 0) defenceExclusionThreshold = 0;
        if (physicalCeilMultiplier < 0) physicalCeilMultiplier = 0;
        if (sessionBoundaryGuardTicks < 0) sessionBoundaryGuardTicks = 0;
        if (lowNoiseWhitelistRadius < 0) lowNoiseWhitelistRadius = 0.0;

        // ===== v8.x · G7b 三指标 AND 兜底 =====
        if (bufferDelayTicks < 0) bufferDelayTicks = 0;
        if (bufferMaxSize < 16) bufferMaxSize = 4096;
        if (p95WindowSize < 1) p95WindowSize = 100;
        if (p95MinSamples < 1) p95MinSamples = 20;
        if (p95MinSamples > p95WindowSize) p95MinSamples = p95WindowSize;
        if (p95OutlierMultiplier < 1) p95OutlierMultiplier = 3;
        if (dpsActiveMultiplier < 0) dpsActiveMultiplier = 0;
        if (outlierAbsoluteFloor < 0) outlierAbsoluteFloor = 0;

        // ===== v8.4.0 · 服务端 push 字段兜底 =====
        if (serverPushStatsIntervalTicks <= 0) serverPushStatsIntervalTicks = 20;
        if (serverPushTeamHeartsIntervalTicks <= 0) serverPushTeamHeartsIntervalTicks = 4;
    }
}
