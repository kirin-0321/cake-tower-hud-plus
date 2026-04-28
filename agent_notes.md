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
  → StatsPersistenceManager.onTickEnd (60 s 节流)
```

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

## 调试

- `broadcastDamageInChat` / `broadcastKillsInChat` / `broadcastTakenInChat` 都默认关，开启后每事件聊天栏一行。
- `LOGGER.isDebugEnabled()` + `-Dctt.debug.log=true` 或调 logback 阈值能看到完整事件流。
- `DamageFilterPipeline` 命中后 `FilterReason` 会写到聊天广播（v6.7.4+）便于诊断。
