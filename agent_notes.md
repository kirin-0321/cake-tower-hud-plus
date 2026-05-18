# Agent Notes · ctt-health-display

供后续协作快速对齐。只记**开发规范、踩过的坑、通用问题**，不记 changelog。

---

## 编译

```powershell
.\gradlew.bat build 2>&1 | Tee-Object -FilePath build.log
```

- 必须用 `.\gradlew.bat`（PowerShell 下 `.\gradlew` 会进交互式 bash 路径）。
- build 任务自带 `bumpVersion`：成功后会把 `gradle.properties` 的 `mod_version` 自动 +0.0.1，**当前 build 使用的版本号是 bump 之前的值**（即写在 `gradle.properties` 里的那个值）。
- jar 输出：`build/libs/cake-tower-hud-plus-<version>.jar`，并自动 deploy 到 mods/ 与二级 mods/。每次修复后必须执行编译验证不破坏构建。

## 项目语义速查

- 客户端 entrypoint：`com.ctt.healthdisplay.CttHealthDisplay`
- 服务端 entrypoint：`com.ctt.healthdisplay.server.CttStatsServer`（main，集成/专用都跑）
- Mixin：客户端 `ctt-health-display.mixins.json`，服务端 `ctt-health-display-server.mixins.json`（仅 `ScoreboardUpdateMixin`）
- 服务端配置：`config/ctt-health-display-server.json`（`ServerConfig`，带 `configVersion` 迁移）
- 客户端配置：`config/ctt-health-display.json`（`ModConfig`）
- 持久化文件：`<world>/data/ctt_stats.dat`（gzip NBT，由 `StatsPersistenceManager` 维护）

## 服务端热路径概念图

```
ScoreboardUpdateMixin (任何 scoreboard 写入)
  ├─ RedHearts↓        → DamageProbe.recordFromRedHearts → DamageFilterPipeline → AttackerProbe.recordFromDamageShower
  │                                                          → feedStats / VictimLethalCandidate / VictimDamageContributors
  ├─ DamageShower      → DamageProbe.record (默认旁路，useRedHeartsTally=true 时)
  ├─ MeleeDMG/...DMG   → AttackerProbe.record (delta + 9 层归属)
  ├─ damage_dealt stat → PlayerHitLog.record  (vanilla L1/L2/L5)
  ├─ used:carrot...    → PlayerFireLog.record (L1/L6)
  └─ used:bow/...      → PlayerFireLog.recordBow (L7)

END_SERVER_TICK 顺序：
  DamageProbe.flushTick → PlayerInventoryIndex.tickRefresh (5 tick 节流)
  → PlayerTakenProbe.tickEnd → VictimTombstone.tickEnd → AttackerProbe.gcTick
  → StageProbeServer.tick (内部驱 StageBoundaryDispatcher)
  → StatsSnapshotBroadcaster.tickPushIfDue (20 tick / 1 Hz)
  → MobHealthBroadcaster.tickPushIfDue (5 tick / 4 Hz)
  → PlayerStatsPushBroadcaster.tickPushIfDue (20 tick / 1 Hz)  ← v8.4.0
  → TeamHeartsBroadcaster.tickPushIfDue (4 tick / 5 Hz)        ← v8.4.0
  → StatsPersistenceManager.onTickEnd (60 s 节流)
```

## 服务端 → 客户端 S2C payload 一览（v8.4.0）

| Payload | 触发频率 | 用途 | 客户端 cache |
|---|---|---|---|
| `StagePayload` | enter/exit + JOIN | 关卡位置文字 | `ClientStageLocation` |
| `StatsSnapshotPayload` | 20 tick (1 Hz) | 全量 stats 快照（侧栏/K 表）| `ClientStatsCache` |
| `MobHealthPayload` | 5 tick (4 Hz) | 视野 32 条 mob HP / 元素抗性 | `ClientMobHealthCache` |
| `PlayerStatsPayload` | 20 tick (1 Hz) ← v8.4.0 | 玩家属性面板 (替代 `/trigger ViewStats`) | `ClientStatsPushCache` (+ `StatsData`) |
| `TeamHeartsPayload` | 4 tick (5 Hz) ← v8.4.0 | 全队 4 色心摘要 (队友层叠血条) | `ClientTeamHeartsCache` |

**所有 payload 都遵循同款规则**：
1. 服务端注册 `PayloadTypeRegistry.playS2C().register(...)` 一次；客户端 `try-catch IllegalArgumentException` 重复注册（集成服务器单 JVM 下 server entrypoint 已注册过）。
2. codec `read` 末尾 `if (buf.isReadable()) buf.skipBytes(...)` 做"未来仅追加末尾字段"的兼容保险，避免老客户端 codec 报"buffer not fully consumed"踢线。
3. 版本字段 `byte version`，receiver 端 `Byte.toUnsignedInt(payload.version()) > CURRENT_VERSION` 时丢弃，让 fresh 窗口顺其自然过期 → 客户端回落老路径。
4. 客户端 cache 都有 `isFresh()` 接口 + `reset()` 接口；`DISCONNECT` 钩子统一清缓存。
5. **服务端没装 mod 的兜底**：客户端永远收不到包，`isFresh()=false`，自动 fallback 到 v8.3.x 行为；纯 vanilla 服务端 / 别的地图世界完全静默零开销。

## 性能踩坑（**重要**）

### 1. 不要用 `Scoreboard.getKnownScoreHolders()` 做查询
该 API 返回所有 holder 集合（玩家 UUID + 实体 + fakeplayer），CTT 地图 holder 数可达数百。
查 fakeplayer 用 `ScoreHolder.fromName(name)` + `sb.getScore(holder, obj)` (哈希 O(1))。
查实体直接 `sb.getScore(entity, obj)`。

### 2. NbtComponent 读取要小心
`stack.get(DataComponentTypes.CUSTOM_DATA).copyNbt()` 每次都拷贝整个 Compound。
高频路径必须做物品签名比对，未变化时跳过 copyNbt。
推荐：先比 `Item` 引用（==），再比 `NbtComponent` 引用（==），引用不同再 fallback `Objects.equals()`。
`PlayerInventoryIndex` 已实现 `SlotFingerprint` 快路径（每玩家 9 格指纹，命中即复用 Snapshot）。

### 2b. PlayerInventoryIndex 只看主手 + 快捷栏 0~8
**铁律**：武器签名扫描范围限定在 9 格快捷栏。**不要**为了"完整性"把主背包 9~35 / 副手 / 护甲塞回去：
- 地图武器识别只读 SelectedItem（主手），召唤物函数只对快捷栏即时位生效。
- 把主背包纳入会放大误归属（玩家把武器塞背包也会算他干的）。
- 每格扫描都是一次潜在的 `copyNbt()`，27 个非快捷栏槽是纯浪费。

### 3. 服务端主线程写文件 = 直接卡 TPS
任何 NBT / gzip / 文件 IO 严禁同步在 server tick 线程跑。
`StatsPersistenceManager.saveNow()` 已实现：主线程构造 NBT root（必须，stats 只能主线程读），
gzip + 磁盘写丢给单线程 daemon executor (`ctt-stats-persist-io`)。
**注意**：`onServerStopping` 必须 `awaitTermination`（默认 5s），否则刚提交的最后一次写会被
shutdown 丢弃 → 玩家拔电前的最后 60s 数据丢失。新加同类持久化代码遵守这套模式：
**主线程拍快照（同步） → executor 串行写（异步）**。

### 4. Mixin `@At("RETURN")` 注入 = 每次写入都进 Java
`ScoreboardUpdateMixin` 每 tick 可能被回调几百次（CTT 地图大量 scoreboard 操作）。
入口必须最便宜：避免重复 `objective.getName()`、提前用 `instanceof` / 引用比较短路、把不关心的 objective 标记缓存（`IdentityHashMap<ScoreboardObjective, Boolean>`）。

### 5. 实体 box scan 是昂贵的
`world.getOtherEntities(victim, box, predicate)` 在 40m × 40m × 40m box 内可能扫几百实体。
不要在每条伤害事件路径上无脑触发；先用便宜信号（typecache / VictimLastHitter）短路。
`AttackerResolver.scanMarkers` 已加 per-tick `(victim, radius)` 缓存：同 tick 内同 victim 的多次 scan 共享结果。
**调用方约定：scanMarkers 返回的 list 只读**（缓存共享同一 ArrayList 引用，外部 add/remove/sort 会污染下次命中）。

### 6. 每条伤害事件遍历所有 worlds 反查实体
`for (ServerWorld w : server.getWorlds()) { Entity e = w.getEntity(uuid); }` × 每条事件。
能拿到 victimWorldKey 时只查那个 world；候选缓存（`VictimLethalCandidate` 等）应同时记 worldKey。

### 7. AtomicLong/Int 数组里 `get()` 不是免费
日志降级到 DEBUG 用 `LOGGER.isDebugEnabled()` 短路；构造 String.format / new HashMap 等放在 if 内。
`AttackerProbe` / `DamageProbe` 已修，新代码注意保持。

## 设计约束

### Stage / Session 分桶
- **三家 stats**（`PlayerDamageStats` / `PlayerKillStats` / `PlayerTakenStats`）写入入口必须先查 `StageBoundaryDispatcher.isCollecting(uuid)`：休息室 / 大厅 / Game Over / MiniGame **一律不采集**（设计 §3.1 铁律）。
- `stageKey == null` 时入口自动从 `dispatcher.currentStageKey()` 取，调用方传 null 即可。
- L8_LAST_HITTER 与 L9 三子层**不分桶**，stage 切片视图里这些字段恒为 0。

### 配置版本号
`ServerConfig.configVersion` 强制覆写默认值改动（默认值修改不会影响已持久化的旧 JSON）。
新加破坏性默认值修改要加分支到 `migrate()`，并把 `CURRENT_CONFIG_VERSION` +1。

### Persistence 还原不写 live/frozen
`PlayerDamageStats.fromNbt` 不还原 `live` / `frozen` 字段（持久化只负责数据，不参与"是否在采集"状态）。否则旧 `live=false` 灌回会导致永远不再记录。

### Magum Trials 分关采集（v8.1.0）
- 入口：`StageProbeServer.computePayload` 检测 `#LobbyMiniGame == 4` + `ServerConfig.collectMagumTrials`，走 MT 分支：tier 用 `#MagumTrialDifficulty GameScores`，stage holder 复用标准检测，payload `inMagumTrials = true`。
- 命名空间隔离：`StageBoundaryDispatcher.computeState` 给 MT stage 的 `stageType` 加 `mt_` 前缀（`mt_boss / mt_dungeon / mt_shop / ...`），与大厅塔 `boss=18` 同 ID 子关物理隔离不合桶。
- `#Floor CTT` 在 MT 内永不复位（datapack 的 `break_room_universal` 显式排除 BreakRoomID 6），所以 ~30 层每层独立 stageKey。
- `floor <= 0` 防御：MT 中央选关区瞬间归 0 时不入桶，避免污染。
- `isT1F1` 自动清零（主游戏 T1F1 重开 = wipe 旧数据）排除 `mt_*`。
- 文案：i18n key `ctt-health-display.stage_report.header.stage_mt`（zh：`MT 难度N·FM`，en：`MT DN·FM`）；`StageLocation.Snapshot.formatted()` 在 `inMagumTrials=true` 时给 STAGE_* 行用 `MT D{tier}·F{floor}` 头、给 BREAK_ROOM/GAME_OVER 加 `[MT]` 前缀。
- 详细设计：`docs/MAGUM_TRIALS_STAGE_TRACKING.md`。

### 分关表排序铁律
**两端一致**：纯客户端 / 服务端模式分关表都按 "**首次进入墙钟时间戳** 升序" 从上往下排列（早→晚）。
- 服务端 `STAGE_ENTER_MS`（`StageBoundaryDispatcher` 维护，已 NBT 持久化），在 `StatsSnapshotBroadcaster.build` 排序 stages 时使用。
- 客户端 `stageHistoryEnterMs`（`ClientDamageProbe` 维护，CDP 持久化 schema v2），在 `StatsTableData.buildStage` 排序时优先使用。
- 优先级：CDP enterMs → 当前关 `currentStageStartMs` → server payload `stageEnterMs` → 旧 LinkedHashMap 迭代序兜底（仅 v1 文件） → (T,F,n) 字典序最终兜底。
- 切回旧关用 `putIfAbsent`，**保留首次进入时序**——不要被"切回"重置位置。
- 任何新关数据源（未来加 mob kill 桶等）也要带 enterMs，否则会被排到末尾。

### CDP 持久化 schema 升版
- 当前 schema = v2（StageEntry 新增 `enterMs`）。
- 新加字段必须：① bump `schemaVersion`；② StageEntry 加 public 字段；③ 旧文件加载时该字段为 0/默认值，调用方在 `applySnapshot` 内做兜底；④ `captureSnapshot` 总是写新字段。
- 严禁删字段或改字段类型——Gson 反序列化老版本会硬崩或静默吞掉数据。新版本必须能读旧版 JSON。

## 调试

- `broadcastDamageInChat` / `broadcastKillsInChat` / `broadcastTakenInChat` 都默认关，开启后每事件聊天栏一行。
- 运行时开关：`/ctthd broadcast <damage|kill|taken|all> <on|off>` `/ctthd broadcast status` `/ctthd broadcast <damage_threshold|taken_threshold> <int>`。命令 requires=true（任意权限可用），仅玩家可用。
- 两个阈值（damage / taken）默认都是 0 = 全显，是全局配置，会写盘 `server.json`，per-player 共享。`damage` 阈值过滤在 `AttackerProbe` 入队前（治疗负数自动 < 任何非负阈值，也被过滤）。
- **per-player 订阅模式**（v8.0.10+）：命令开关只影响**执行者自己**，不会刷屏其他玩家。订阅状态存于 `BroadcastSubscribers`（in-memory），断线 / 重启即清空。
- `ServerConfig.broadcastXxxInChat` 三个全局字段保留作"运维兜底"——编辑 JSON 启用时给所有玩家广播。命令不修改这些字段（除 `taken_threshold` 仍是全局）。
- 三个广播点（`AttackerProbe.flushBroadcasts` / `VictimTombstone` / `PlayerTakenProbe`）走双路由：global=true → `getPlayerManager().broadcast()`，否则 `BroadcastSubscribers.sendTo(channel, ...)`。
- `LOGGER.isDebugEnabled()` + `-Dctt.debug.log=true` 或调 logback 阈值能看到完整事件流。
- `DamageFilterPipeline` 命中后 `FilterReason` 会写到聊天广播（v6.7.4+）便于诊断。

## i18n 规范（HUD / Screen 文案）

- 任何会渲染到 HUD / Screen 上的中文文案**必须**走 `Text.translatable("...key")`，禁止 `Text.literal("中文")` 或 `"\uXXXX"` 字面量。
- key 命名跟随现有结构：`ctt-health-display.<面板>.<语义>`，两份 lang 文件 (`assets/ctt-health-display/lang/{en_us,zh_cn}.json`) 同步加。
- 字符串拼接里要用翻译过的字符串时用 `I18n.translate("key")`（client 端 `net.minecraft.client.resource.language.I18n`）。
- `TextRenderer.getWidth(...)` 接受 `String` / `Text` / `OrderedText` / `StringVisitable`，宽度计算别为了 i18n 退回字符串拼接。
- 列头之类的 `record` 字段也用 `Text` 装，`Text.translatable(...)` 在 `static final` 里安全（惰性求值，渲染时才查 lang）。
- **服务端发聊天**：用 `Text.translatable`，不要用服务端拼中文/英文字面量；关卡友好名用 `StageNameRegistry.localizedName(kind, id, lang)`，`lang` 取 `ServerPlayerEntity#getClientOptions().language()` 再归一化（见 `StageReportBroadcaster.normalizeLang`）。
- **分关表关卡名列**：detector 把 `stageType` 编成 `STAGE_DUNGEON@副标题` 时，不能只展示 `@` 后中文副标题；必须先 `(resolveKindFromStageType + stageNum)` 命中 `StageNameRegistry`（随客户端语言），未命中再退回副标题（`StatsTableData.localizeStageName`）。

## 服务端命令注册约定

- 用 `CommandRegistrationCallback.EVENT.register(...)` + `CommandManager.literal(...)`。
- 给所有玩家可用的命令必须显式 `requires(src -> true)`（默认就是 true，但写出来便于 review）。**不要**用 `source.hasPermissionLevel(N)`，会把无 OP 玩家挡掉。
- 入口约定 `/ctthd ...`（mod id 缩写），子命令分组用 literal，避免污染顶层命名空间。
- 反馈用 `source.sendFeedback(() -> text, false)`（broadcastToOps=false），不要广播给全服。

## v8.4.0 · 服务端属性 push（替代 /trigger ViewStats）

**问题背景**：v8.3.x 及之前客户端每 N 秒发 `/trigger ViewStats`，命中两个问题：
1. **反作弊误踢**：多人服务器对短间隔自发命令会踢线（v8.3.9 起 `ConfigScreen` 已加红色警告）。
2. **datapack 性能**：地图 `function misc/view_stats.mcfunction` 单次 ~100 行 `tellraw @s`，**0.5–1.5 ms / 玩家**；高频触发拉低 TPS。

**v8.4.0 旁路方案**：服务端走 `ViewStatsBuilder.build(player)` 路径 —— 直接读 scoreboard 拼 `List<Text>` + 4 色心数字 → 通过 `PlayerStatsPayload` 推给该玩家自己。

- **`ViewStatsRegistry`**：把 datapack 的 ~80 行 `tellraw` 抽成 `List<StatEntry>` 常量表（objective + icon + posColor + negColor）。datapack 改字段 = 改这张表 + 重编。
- **`ViewStatsBuilder`**：走表 + 硬编码处理特殊条目（CrackedHearts/PinkHearts、NegMaxHealth、SpeedAmplifier 双向 bold、Size/Gravity 双向 icon、CelestialKarma 受 `#ServerTier CT==3` 门控、`#Skulls CTT` fake player）。**跳过**：状态效果 Tag 行、Game Time 行（与 HUD 渲染无关）。
- **`PlayerStatsPushBroadcaster`**：默认 20 tick (1 Hz)，差量吞噬，单玩家 ~25 µs/调用，4 人队 ~0.1 ms/s 服务端 CPU。
- **客户端入口**：`StatsData.applyServerSnapshot(red, soul, black, blue, lines)` —— 完全旁路 chat capture 路径；`ClientStatsPushCache.isFresh()` 在 `CttHealthDisplay.END_CLIENT_TICK` 的 auto refresh 节点判断"是否跳过 `/trigger ViewStats`"。
- **fresh 窗口**：客户端 10 s（服务端 1 Hz 推 + 差量静默几秒），失鲜回落老路径自发命令。

**性能（4 人队）**：服务端 ~0.1 ms/s CPU + ~12 KB/s 网络 vs datapack 路径 2-6 ms/s + 40 KB/s 多包 chat。

## v8.4.0 · 队友 4 色心叠加渲染

**问题背景**：玩家自己主血条早就用 `drawLayeredBar(red/soul/black/blue)` 4 层叠加（依赖 `/trigger ViewStats` 解析自己 4 个 objective），但**队友只有 RedHearts**（vanilla 团队 bossbar 文本正则只解析 `Name (hp/maxHp)`）。

**v8.4.0 方案**：服务端 `TeamHeartsBroadcaster` 每 4 tick (5 Hz) 扫所有在线玩家，读 6 个 objective (Red/Soul/Black/Blue/MaxHP/Lives)，全量广播给每个在线玩家。客户端 `ClientTeamHeartsCache` 按 name 索引，`HealthData.parseTeamBar` 拼 `TeammateData` 时优先用 cache 填充新增 `soulHearts / blackHearts / blueHearts` 字段，三个队友渲染入口 (`HealthBarRenderer.drawTeammateBar` / `TeammateWorldRenderer.renderHealthBar` / `TeammateHealthMixin.ctt_onLabel`) 检测 `mate.hasLayeredHearts()` → 走 `drawLayeredBar` 同款叠加路径；不满足 → fallback 老的 OVERFLOW_COLORS 单色多槽。

**客户端开关**：`ModConfig.showTeammateLayeredHearts`（默认 `true`，可手动关回老外观）。

**带宽**：4 人队 5 Hz × ~120 字节/包 = 2.4 KB/s 总流量，差量吞噬下稳态零流量。

**注意**：队友 `hp` / `maxHP` 字段仍走 vanilla bossbar 解析（实时性比 5 Hz 服务端推高），cache 只补 4 色心 + 兜底 maxHP/lives。bossbar 解析在 vanilla 服务端也工作，确保"无 mod 服务端 + 客户端开关 on"=旧的 OVERFLOW_COLORS 路径，不退化。
