# v7 客户端伤害探针 · 开发履历与知识库

> **本文档性质**：v7.0.0 ~ v7.1.0 这一波开发的"事后档案"——记录每一版做了什么、为什么这么做、踩过哪些坑、最后落地的架构。
> **配套设计稿**：`CLIENT_SIDE_STATS_PROPOSAL.md`、`CLIENT_SIDE_DATA_REFERENCE.md`、`MAP_DATAPACK_ANALYSIS.md`、`MAP_DATAPACK_REFERENCE.md`
> **截至版本**：v7.1.0（2026-04-28）
> **目标读者**：未来想看懂"为什么 ClientStageDetector 长这样"、"为什么 stageType 字段编了 `@<name>`"、"为什么 HUD 在休息室也能跳数字但全局不涨"、"为什么击杀计数走 ScoreboardHP 而不是 LivingEntity.getHealth()"的人。

---

## 0. 一句话定位

**v7 = 服务端没装 mod 也能用的"纯客户端伤害可见层"**。

v6 系列是 server-authoritative：服务端 `DamageProbe` 监听 scoreboard 写入，归属 + 过滤后通过 `StatsSnapshotPayload` 推到客户端。专用服务器 / 集成服务器都需要装本 mod，否则客户端 K 表 / HUD 一片空白。

v7 在客户端旁边开了一条**完全独立**的数据流：扫描 `text_display` 实体（vanilla 协议同步过来的 `DamageShower` 粒子）→ 增量累加 → 当前关分桶 → HUD / 面板 / K 表展示。**与服务端 mod 共存**：装了用服务端权威数据，没装用客户端 fallback。

---

## 1. 总览：版本-改动一眼看

| 版本 | 主线主题 | 关键产物 |
|---|---|---|
| v7.0.0 | 立项实现 | `ClientDamageProbe`、HUD 顶部 `⚔200·⚔100⚡21/s` 行、面板"清空数据"按钮联动、K 表 CDP 横幅 |
| v7.0.1 | 应急修复 | 服务端没 mod 时面板可打开；scoreboard 主路 + 文本兜底双源；周期性诊断 |
| v7.0.2 | 配置 UI | 移除 `/cttcdp` 命令；`clientDamageHudHeader` / `clientDamageDebugChat` 两个开关进 `ConfigScreen` |
| v7.0.3 | 描述补全 | `describeVictim()` 加 `PlayerEntity` 分支 + 方向指示符 |
| v7.0.4 | 颜色过滤 | 只滤绿色（治疗）粒子；默认背景"打怪"粒子允许进入候选 |
| v7.0.5 | 调试输出 | 加 `[CDP-skip]` 跳过原因消息 |
| v7.0.6 | 全量 dump | 新增 `CdpDumpWriter`；把所有 `text_display` 写入 `logs/ctt-cdp-dump.log` |
| v7.0.7 | 文本兜底放宽 | 收紧 `DAMAGE_TEXT_PATTERN`；**移除红背景前提**让默认背景的打怪粒子也走 text fallback |
| v7.0.8 | 方向分离 | 新增 `DamageDirection`（DEALT/TAKEN）；HUD 只统计 dealt；taken 仅旁路计数 |
| v7.0.9 | 启发式判向 | `nearAnyPlayer && delta < 64 → TAKEN` 否则 `DEALT`（地图把所有近距粒子染红，颜色判向失效） |
| v7.0.10 | 客户端关卡探针 | 新增 `ClientStageProbe` 直读客户端 scoreboard；`ClientStageLocation` 双源合流 |
| v7.0.11 | CSP 诊断 | 新增 `CspDumpWriter`，写 `logs/ctt-csp-dump.log`：所有 objective + 14 个 CTT 假玩家分数 |
| v7.0.13 | bossbar 诊断 | dump 加 bossbar 列表（含 floor 进度条），指纹去重避免刷屏 |
| v7.0.14 | 状态机重构 | `InGameHudTitleMixin` 拦 title/subtitle；`ClientStageDetector` 状态机；CTT scoreboard 缺失时让 detector 接管 |
| v7.0.15 | 名字回流 | `Snapshot` 加 `stageName` 字段；`StageKey.stageType` 编 `@<name>` 后缀；`localizeStageName` 优先取 `@` 后字符串；CDP 加 `stageHistoryDealt` 桶 + buildStage fallback 单行 |
| v7.0.16 | 时间序排序 | 分关表改用 `LinkedHashMap` 迭代序（切关时间序），废弃 `(T,F,n)` 排序 |
| v7.0.17 | 时长记入 | CDP 加 `stageHistoryDurationMs` + `currentStageStartMs`；fallback 路径下时长写入 PlayerRow.durationMs |
| v7.0.18 | 名字带括号 | 休息室 stageName 改为完整 bossbar 文本 `"高塔 (21/30)"`，formatted 去掉重复 floor 后缀 |
| v7.0.19 | （由 v7.0.18 撤销引发的中间版本） | 短暂存在的"全局排除休息室"被立刻撤销 |
| v7.0.20 | HUD 简化 + 全局排除 | HUD CDP 行去掉全局，只剩本层 + DPS；`isInBreakRoom()` 路径下 globalTotal 跳累加，stageTotal/DPS 照常 |
| v7.0.21 | 行名改写 | fallback 行 PlayerRow.name 固定 `"全部伤害粒子"` |
| v7.0.22 | 头像空白 | `GHOST_UUID = (0,0)` 哨兵：drawHead 跳过、isOffline 永远 false、isSelf 不会命中 |
| **v7.1.0** | **客户端击杀计数** | `scanKillsAndUpdate()` · `ScoreboardHP[uuid]` 跌零 + entity destroy 双判定；HUD 加 ☠ 段；K 表 fallback 行填 kills；计数始终运行（无开关），仅 `clientKillDebugChat` 一个开关控制聊天日志 |

---

## 2. 设计原则与不变量

1. **单工方向**：客户端探针只读 vanilla 同步过来的实体 / scoreboard / bossbar / title，**不向服务端发任何包**——保证装在专用服务器上不破坏游戏体验。
2. **会话级数据**：CDP 累加器（global / stage / dpsRing / stageHistoryDealt / stageHistoryDurationMs）**不做 NBT 持久化**，断线即清。
3. **可叠加**：装了服务端 mod 时，CDP fallback 路径自动让位（K 表用三家 stats，HUD CDP 行只在 `hasAnyData()==true` 时叠加显示）。
4. **稳定 key**：`StageKey` 字段不变（5 个 String），所有"客户端独有"信息塞到 `stageType` 末尾的 `@<name>`，不破坏服务端 NBT 兼容。
5. **零外部依赖**：detector 不读地图 datapack 文件、不依赖任何地图特定 scoreboard objective（只识别 `text_display` 红/默认背景 + bossbar 文本格式 + title 主标题正则）。

---

## 3. 数据源全图

| 信号 | 来源 | 用法 | 关键代码 |
|---|---|---|---|
| `text_display` 实体 | vanilla EntitySpawn S2C 包 | 提取 `DamageShower` score 增量 / text 数字 → 一次伤害 | `ClientDamageProbe.onClientTick` |
| 实体邻近扫描 | client world `getEntities()` | `nearAnyPlayer` 判定方向 DEALT/TAKEN | `ClientDamageProbe.scanProximity` |
| 客户端 scoreboard | vanilla scoreboard 同步 | `CTT` / `GameID` / `#Tier` 等假玩家分数（可能不同步） | `ClientStageProbe.tick` |
| Floor 进度 bossbar | vanilla BossBar S2C 包 | 文本 `"高塔 (21/30)"` → BREAK_ROOM 桶 | `ClientStageDetector.onBossbarsScanned` |
| 关卡 title | vanilla Title S2C 包 → `InGameHud.setTitle` | 主标题 `"2-7"` / `"2-$"` 等 → STAGE_* 桶 | `InGameHudTitleMixin` → `ClientStageDetector.onTitle` |
| 关卡 subtitle | vanilla Subtitle S2C 包 → `InGameHud.setSubtitle` | 已被本地化的 translate 文本（如 `"荣耀道场 [基础]"`）→ stageName | `InGameHudTitleMixin` → `ClientStageDetector.onSubtitle` |
| 4 层心数 delta | （未启用）vanilla scoreboard 增量 | TAKEN 旁路统计预留 | TBD |

---

## 4. 关键知识点

### 4.1 客户端 scoreboard 不可靠

- vanilla 客户端只同步**当前显示在 sidebar / list / belowname 的 objective**——其他 objective 即使服务端有写入也不会下发。
- 地图 `Cake Team Towers` 在大部分时间只显示 `ScoreboardHP` / `S12_Highscore` / `Lives` / `ShopDisplay`，**`CTT` 和 `GameID` 不在 sidebar 里**，所以客户端永远看不到。
- v7.0.10 ~ v7.0.13 一直在死磕这个：试图让 `ClientStageProbe` 直接读 CTT 假玩家分数，全部失败（`obj=缺` 在 dump 里满天飞）。
- v7.0.14 才放弃这条路，转向 vanilla title / bossbar 协议——这两个**100% 同步**。

### 4.2 客户端玩家 commandTags 不同步

- `ClientPlayerEntity#getCommandTags()` 永远返回空集合——vanilla 协议根本没有这个字段。
- v6.5.6 注释里已经记录："vanilla 不同步玩家 scoreboardTags 到客户端，导致 isCtt 永远 false"。
- 所以服务端 `tag=CTT` 的判定（用于过滤"在局玩家"）在客户端无解。v7 直接放弃这条路。

### 4.3 vanilla 协议同步什么

| 内容 | 同步 | 备注 |
|---|---|---|
| `text_display` 实体 + 文本 + 背景色 + scoreboard score | ✅ | 服务端 `summon text_display` 后跟 `data merge` / `scoreboard players set`，客户端能看到 |
| Bossbar 名字 + 颜色 + style + percent | ✅ | `bossbar add ... <name>` 立即下发 |
| Title / Subtitle 文本（已 translate） | ✅ | `title @a title <text>` 服务端会先用客户端 locale render 再发 |
| Scoreboard objective（仅 sidebar/list/belowname 三槽） | ⚠️ | 不在三槽中的 objective 不下发 |
| Player commandTags / scoreboardTags | ❌ | 永不下发 |
| Player team membership | ✅ | 经过 PlayerListS2CPacket |

### 4.4 地图 datapack 关于 title 的约定

由 `MAP_DATAPACK_ANALYSIS.md` 和 `_floor_universal.mcfunction` 反推：

- 主标题：`"<Tier>-<Floor>"`（普通副本）/ `"<Tier>-$"`（商店）/ `"<Tier>-☠"`（boss）/ `"<Tier>-❤"`（盟友）
- 副标题：translate key（客户端按语言渲染成 `"荣耀道场 [基础]"` / `"主商店"` 等）
- 同 tick 发出 → detector 用 250ms 配对窗口足够

detector 的核心正则：

```regex
^(\d+)-(\d+|\$|☠|❤)$
```

### 4.5 地图 floor 进度 bossbar 格式

- 在休息室时，bossbar 文本固定为 `"<塔名> (<已完成>/<总数>)"`，例如 `"高塔 (21/30)"` / `"The Tower (12/30)"`
- 进入战斗关时这条 bossbar 消失，被关卡 boss 血条 / 进度条替代
- detector 用此特征断定"在休息室 + 当前 floor"

正则：

```regex
^(.+?) \((\d+)/(\d+)\)$
```

排除"血条样"误匹配（`"Kirin0321 (100000/100000)"`）的兜底：`total > 50 && floor == total → 跳过`。

### 4.6 地图把粒子全染红的边界

`damage.mcfunction:1027` 大致逻辑：玩家受伤时**给附近所有 text_display 粒子**染红色背景。这意味着"红背景 = 受伤"这个 v7.0.0 的假设彻底崩盘——v7.0.7 之后用「位置距任何玩家 < 2m && delta < 64」作 TAKEN 判定的启发式。这条规则不完美但够用。

### 4.7 Mixin 注入点选择

`@Inject(method = "setTitle", at = @At("HEAD"))` 在 `InGameHud.setTitle(Text)` 上：

- HEAD 不修改 vanilla 行为，纯被动观察
- 早于 vanilla 写 `currentTitle` 字段，确保 detector 拿到的是"刚解码完的 Text"，文本已本地化
- `setSubtitle` 同理

为什么不直接在 `TitleS2CPacket` handler 上 inject？因为 vanilla 早于 1.20 没有 cleanly 暴露的 packet handler，且 `setTitle` 是 `TitleS2CPacket.apply` → `ClientPlayNetworkHandler.onTitle` → `InGameHud.setTitle` 的最后一站，最干净。

---

## 5. 各阶段问题与对策（精简）

### 5.1 v7.0.0：什么都不显示

- **症状**：HUD 没数字，K 表"清空"按钮在没装服务端 mod 的存档里灰着不能点
- **根因**：`DamagePanelRenderer.drawHud` 只在服务端 stats 非空时才绘制
- **修复（v7.0.1）**：放宽为 `hasServerData || ClientDamageProbe.hasAnyData()`

### 5.2 v7.0.4 ~ v7.0.7：打怪伤害一条没采到

- **症状**：诊断显示 `obj=MISSING`，玩家"对怪 144·144·68 三次伤害"完全不进 stats
- **根因 1**：`DamageShower` objective 不在客户端 scoreboard 三槽 → score 路径全 miss
- **根因 2**：地图给"打怪"粒子用默认背景（不是 red），text fallback 的 `bg == DAMAGE_BG` 前提把它们全跳过
- **根因 3**：`DAMAGE_TEXT_PATTERN` 太宽，匹配到不相干 text_display
- **修复（v7.0.7）**：收紧正则 `^[X\\s]*(\\d+)[X\\s]*$`，**移除背景前提**

### 5.3 v7.0.8 ~ v7.0.9：dealt/taken 颜色判向失败

- **症状**：受伤粒子和打怪粒子混色，按颜色滤完全失效
- **根因**：地图给玩家附近所有粒子染红
- **修复（v7.0.9）**：`(scan.nearAnyPlayer && delta < HIT_DELTA_THRESHOLD)` 启发式

### 5.4 v7.0.10 ~ v7.0.13：客户端读不到 CTT scoreboard

- **症状**：HUD "位置: 未知"；csp-dump 显示 `CTT obj=缺 GameID obj=缺`
- **根因**：见 §4.1，地图不把 CTT 放进 sidebar
- **死路探索**：v7.0.10 ~ v7.0.13 一直在加诊断，最后接受现实

### 5.5 v7.0.14：用 vanilla title + bossbar 重写

- 引入 `InGameHudTitleMixin` + `ClientStageDetector` 状态机
- detector commit 链路完全跑通（dump 显示 `title:2-7 / 荣耀道场 [基础]` 等）
- **遗留问题**：HUD 显示了错误的关卡名（"水漫地牢"而不是"荣耀道场"），分关表"尚无数据"——见 v7.0.15 修复

### 5.6 v7.0.15：HUD 关卡名错 + 分关表空

- **HUD 关卡名错**：`Snapshot.formatted()` 的 `stageLine()` 走 `StageNameRegistry.localizedName(kind, stageNum)` 查预定义表，DUNGEON #7 被预定义为"水漫地牢"，detector 的 subtitle 完全没用上
- **分关表空**：`StatsTableData.buildStage` 数据来自 `ClientStatsCache.recordedStageKeys()`（三家服务端 stats），纯客户端模式永远空；CDP 只有单一 stageTotal，切关清零无历史
- **修复（v7.0.15）**：
  1. `Snapshot` 加第 9 字段 `stageName`，formatted 优先用它（无 #编号）
  2. `StageKey.stageType` 编 `@<stageName>` 后缀，让 buildStage / equals / hashCode 自然按名称分桶
  3. `localizeStageName` 优先取 `@` 后字符串
  4. CDP 加 `stageHistoryDealt: LinkedHashMap<StageKey, Long>`，`onStageChanged` 时把上一关 (key, total) 存入
  5. `buildStage` 在 union 空时从 CDP 历史 + 当前桶构造单行 PlayerRow

### 5.7 v7.0.16：分关表顺序

- **症状**：进了花园后回休息室再进实验室，分关表里实验室排在花园前面
- **根因**：`Comparator(T,F,n)` 升序排序与玩家实际游玩时间序无关
- **修复**：用 `LinkedHashSet`，`cdpHistory.keySet()`（切关时间序）→ `cdpCurrent`（进行中）→ 服务端兜底 keys（按 T/F/n）的顺序拼接，废弃 `blocks.sort`

### 5.8 v7.0.17：时长记入

- **症状**：分关表 ⏱ 列全是 `00:00`（CDP fallback 路径没设 stageDur）
- **修复**：CDP 加 `stageHistoryDurationMs` + `currentStageStartMs`，`getCurrentStageDurationMs()` 实时计算；buildStage 在服务端无数据时用 CDP 时长；放宽 fallback 行插入条件为 `dealt>0 || stageDur>0`，让"路过休息室"行也保留

### 5.9 v7.0.18：休息室名字带括号 + 撤销 v7.0.18(原计划) "排除休息室伤害"

- 用户先要求"休息室伤害不算入总伤害"（v7.0.18 实施了），又要求撤销
- 同时要求休息室名字带 `(21/30)` 进度
- **结果**：detector stageName 改为完整 bossbar 文本（如 `"高塔 (21/30)"`），formatted 去掉重复 floor 后缀

### 5.10 v7.0.20：HUD 简化 + 重做"全局排除休息室"

- HUD CDP 行只剩本层 + DPS（去全局）
- 全局总额排除休息室伤害（**只跳 globalTotal 累加，stageTotal/DPS 照常**）
- 新增 `isInBreakRoom()`：`stageType.startsWith("BREAK_ROOM") || equals("break_room")`，等价于"floor bossbar 触发的桶"
- `hasAnyData()` 改为 `globalTotal>0 || stageTotal>0`，避免"只在休息室造伤"时 HUD CDP 行不显示

### 5.11 v7.0.21 ~ v7.0.22：行名 + 头像

- fallback 单行的 player 列从 `"Kirin0321"` 改为 `"全部伤害粒子"`
- 引入 `GHOST_UUID = (0,0)` 哨兵：drawHead 直接 return（空白）；isOffline 永远 false；isSelf 永不命中

---

## 6. 当前架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                       vanilla 客户端                            │
│                                                                 │
│  EntitySpawnS2C → world.getEntities()                           │
│       │                                                         │
│       ▼                                                         │
│  ClientDamageProbe.onClientTick                                 │
│    1. advanceDpsRing                                            │
│    2. 检测 ClientStatsCache.representativeStageKey 变化         │
│       → onStageChanged: 旧关 (total,dur) → history 桶          │
│    3. 扫 text_display：score 路径 + text 路径双源               │
│    4. ingest(eid, delta, ...)                                   │
│       ├─ DEALT 且非休息室: globalTotal += delta                 │
│       ├─ DEALT 任意: stageTotal/dpsRing += delta                │
│       └─ TAKEN: takenGlobal/takenCount, ClientStageDetector.   │
│                  onTakenDamage()                                │
│                                                                 │
│  TitleS2C → InGameHud.setTitle (HEAD inject by mixin)           │
│       │                                                         │
│       ▼                                                         │
│  InGameHudTitleMixin.ctt$onSetTitle                             │
│       │                                                         │
│       ▼                                                         │
│  ClientStageDetector.onTitle / onSubtitle                       │
│    ├─ 正则匹配 "Tier-Floor/$/☠/❤" → STAGE_* 桶                │
│    └─ subtitle 250ms 配对 → stageName                           │
│                                                                 │
│  BossBarS2C → InGameHud.bossBarHud                              │
│       │                                                         │
│       ▼                                                         │
│  ClientStageProbe.tick (每 20 tick)                             │
│       │                                                         │
│       ▼                                                         │
│  ClientStageDetector.onBossbarsScanned                          │
│    └─ 正则 "<name> (X/Y)" → BREAK_ROOM 桶                      │
│                                                                 │
│  detector.commit(newKey, snap)                                  │
│       │                                                         │
│       ▼                                                         │
│  ClientStageLocation.setFromClientProbe                         │
│       │                                                         │
│       ▼                                                         │
│  ClientStatsCache.representativeStageKey                        │
│    ├─ isIntegrated → 服务端权威                                 │
│    ├─ latest payload → 服务端 push                              │
│    └─ ClientStageLocation.clientFallbackStageKey ← detector    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                          展示层                                 │
│                                                                 │
│  HealthBarRenderer.drawCdpHeaderRow                             │
│    └─ ⚔ <stageTotal> ⚡ <DPS>/s                                │
│                                                                 │
│  DamagePanelRenderer.drawCdpHeaderRow                           │
│    └─ ⚔ <global> · ⚔ <stage> ⚡ <DPS>/s                        │
│                                                                 │
│  StatsTableScreen.drawCdpHeaderBar                              │
│    └─ 客户端可见伤害（无归属）⚔ 全局 N · ⚔ 当前关 N ⚡ N/s   │
│                                                                 │
│  StatsTableData.buildStage (分关表)                             │
│    └─ orderedKeys = cdpDur历史 ∪ cdpDealt历史 ∪ {cdpCurrent}   │
│         ∪ 服务端 keys 兜底                                     │
│       每行：if union 此 key 无数据 → 注入 GHOST 单行             │
│              "全部伤害粒子" + dealt + duration                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 7. 仍未完成 / 已知边界

| 项 | 说明 | 优先级 |
|---|---|---|
| 多人归属 | CDP 完全无归属，所有数据归 GHOST 单行——队友的伤害看不到 | 中（v8 接入 AttributionHook 时再做） |
| 承伤 KPI 展示 | `takenGlobal` / `takenCount` 已采集但 HUD 不展示 | 低（用户明确要求"先只统计伤害"） |
| 假人识别 | "测试假人 (生命值 997960/1000000)" 这种工具人 boss 也会进 HUD 数字 | 低（休息室排除已经覆盖大部分场景） |
| 小关无 title | 部分小关 datapack 不发 title（直接传送），detector 维持上一桶 | 中（依赖地图改进） |
| 对话 title 误判 | 任何 `^\d+-\d+$` 形式的 dialog 都会被识别成关卡——目前未观察到地图有这种 dialog | 低（出问题再加白名单） |
| commit 防抖 | 短时间内同 key 反复 commit 会触发额外 dump 行（无功能影响） | 低 |

---

## 8. 关键文件地图

| 文件 | 角色 |
|---|---|
| `client/ClientDamageProbe.java` | 主累加器：粒子扫描、增量计算、双向分流、history 桶 |
| `client/ClientStageProbe.java` | 客户端 scoreboard 扫描（v7.0.10 遗留，CTT 缺失时无输出，仅供 bossbar 喂入 detector） |
| `client/ClientStageDetector.java` | title/bossbar 状态机，commit StageKey + Snapshot |
| `mixin/InGameHudTitleMixin.java` | 拦 vanilla title/subtitle 写入 |
| `client/CdpDumpWriter.java` | `logs/ctt-cdp-dump.log` 写入 |
| `client/CspDumpWriter.java` | `logs/ctt-csp-dump.log` 写入（探针 + detector commit） |
| `hud/ClientStageLocation.java` | 双源（server payload / client probe）合流 |
| `hud/StageLocation.java` | `Snapshot` 记录 + `formatted()` |
| `hud/StatsTableData.java` | K 表数据装配；fallback 单行 + 时间序 |
| `hud/StatsTableScreen.java` | K 表渲染；GHOST_UUID 头像跳过 |
| `hud/HealthBarRenderer.java` | HUD CDP header 行（`drawCdpHeaderRow`） |
| `hud/DamagePanelRenderer.java` | L 键面板 CDP header 行 |
| `config/ConfigScreen.java` | `clientDamageHudHeader` / `clientDamageDebugChat` 两开关 |

---

## 9. 调试技巧

1. **打开 debug chat**：配置界面打勾 `clientDamageDebugChat` → 每条粒子打印 `[CDP/DMG]` 或 `[CDP/HIT]` 行；detector commit 写入 csp-dump
2. **CDP dump**：`logs/ctt-cdp-dump.log` 每 20 tick 写一次所有 text_display 实体的完整状态
3. **CSP dump**：`logs/ctt-csp-dump.log` 写 detector commit 决策、bossbar 扫描结果、可见 objective
4. **测试地图特征**：
   - 进休息室：bossbar 应有 `"<塔名> (X/Y)"`
   - 进战斗关：屏幕中央应弹 `"Tier-Floor"` 主标题 + 副标题（如不弹，detector 维持休息室桶）
   - 打练习木桩：HUD 数字应跳，但 K 表"全局"不动
   - 离开休息室进新关：上一关 stageTotal=0 但 stageDuration>0 → fallback 行应出现

---

## 10. 关键设计取舍记录

| 决策 | 取舍 |
|---|---|
| 不写 NBT | 客户端模式数据少，断线重来代价低；持久化反而招麻烦（multi-session 合并语义难定） |
| `stageType` 编 `@<name>` 而不加新字段到 `StageKey` | 保 record 签名稳定，避免影响服务端 NBT；解析成本一次 `indexOf('@')` 可忽略 |
| 用 `GHOST_UUID(0,0)` 而不是 `Optional<UUID>` | 改 `PlayerRow` record 影响面更大；哨兵语义清晰，三处特判即可 |
| 时间序而非 `(T,F,n)` | 用户明确要求"按经过先后"；`LinkedHashMap` 天然保序，零成本 |
| 全局排除休息室但本层照算 | 保留休息室的"练习反馈"语义（HUD 数字跳动让人有打感），又不让它污染战斗成绩 |
| HUD 去全局保留本层 | 用户明确："HUD 上更关心当前在打这关的进度"；全局留在 K 表 / 面板做"复盘" |
| 击杀走 `ScoreboardHP` 而非 `LivingEntity.getHealth()` | 该地图血量是 datapack 维护的自定义 score，vanilla `getHealth()` 读出来不是真实血量；csp-dump 已实证 `ScoreboardHP` 在客户端 list 槽可读 |
| 击杀双判定（路径 A score 跌零 + 路径 B destroy 后再读） | 死亡序列里 score 写 0 与 entity destroy 可能同 tick 到达，单看 score 跌零会被 `retainAll` 漏掉一部分；双判定覆盖两种顺序 |
| 击杀计数本身**不暴露开关** · 仅暴露聊天报告开关 | 计数开销极低（每 tick 一次活体扫描，~100 entity）；关闭后 HUD ☠ 段恒为 0 反而让人困惑——v7.1.0 实施时简化为"计数始终运行 + `clientKillDebugChat` 单一开关" |

---

## 11. v7.1.0 · 客户端击杀计数（详细设计）

### 11.1 立项缘由

用户要求"统计全局怪物击杀"。最初提议基于 `LivingEntity.getHealth()`——客户端 vanilla API 直接可读、写一行就完事。但用户立刻打回：

> "这个地图怪物血量不是原版而是一套特殊规则，且该数值客户端无法读取。"

意思是：地图用 datapack 维护一套自定义血量（`RedHearts` / `BlackHearts` / `BlueHearts` / `SoulHearts` / `MaxHP`），怪物身上的 vanilla `health` 始终是固定占位值（通常 max），不参与战斗结算。`getHealth()` 跌零完全不会发生。

候选信号源重新评估：

| 候选 | 客户端可见 | 是否真实血量 | 决定 |
|---|---|---|---|
| ~~`LivingEntity.getHealth()`~~ | ✅ | ❌ 占位值 | **作废** |
| **`ScoreboardHP` 假玩家 score** | ✅（csp-dump 实证：list 槽，holder=entity UUID 字符串，153 个 holder） | ✅ 这就是地图真实血量 | **采用** |
| `RedHearts` / `BlackHearts` / `MaxHP` 多层心数 | ❌ 不在客户端三槽 | ✅ | 不可用 |
| `EntitiesDestroyS2CPacket` | ✅ | — | 不能区分死亡 vs despawn vs chunk 卸载 |

### 11.2 双路径判定算法

```
每 tick:
  resolveHpObjective(sb)  // ScoreboardHP / Health / RedHearts 顺位
  if obj == null: 清 cache 并 return（熔断，不计死也不维护 baseline）
  seenLivingThisTick.clear()

  // 路径 A · entity 仍在 world：score 跌零
  for entity in world.getEntities():
    if not LivingEntity || PlayerEntity || !alive: continue
    uuid = entity.getUuidAsString()
    seenLivingThisTick.add(uuid)
    prev = lastHpByUuid.get(uuid)
    curr = readHpScore(sb, obj, uuid)
    if prev != null && prev > 0 && (curr == null || curr <= 0):
      onKillDetected(...)
    if curr != null:
      lastHpByUuid.put(uuid, curr)
      lastNameByUuid.put(uuid, entity.name)

  // 路径 B · cache 里有但本 tick 不在 world：destroy 后再读 score
  for (uuid, prev) in lastHpByUuid - seenLivingThisTick:
    curr = readHpScore(sb, obj, uuid)
    if curr == null || curr <= 0:
      onKillDetected(...)
    // 不论是否击杀都从 cache 移除（destroy 后这条记录无意义）
```

**两条路径**对应的实战场景：

| 死亡序列时序 | 路径 A 命中？ | 路径 B 命中？ |
|---|---|---|
| score 写 0 → 下一 tick destroy | ✓（写 0 那 tick 命中 prev>0 + curr=0） | — |
| score 写 0 与 destroy 同 tick 到达 | retainAll 后 cache 没了 → 漏 | ✓（先于 retainAll 检查） |
| destroy 先 + score 0 在下一 tick | retainAll 把 cache 删了 → 漏 | ✓（同 tick 路径 B 见到 destroy 后立即查 score） |
| despawn / chunk 卸载（score 不变） | — | ✗（curr 仍 > 0，不计） |

### 11.3 边界规则

| 场景 | 行为 |
|---|---|
| 玩家死亡 | 跳过（首个 `instanceof PlayerEntity` 守卫） |
| 怪物正常死亡 | **计入**（路径 A 或 B 二选一命中） |
| 离开渲染范围 | 不计（destroy 但 score 仍正） |
| Boss 多阶段 score 重置 | 计多次（每次 score 跌零都算一次，用户接受） |
| `/kill` 清场 | 视地图实现：若 datapack 同步清 ScoreboardHP → 计入；否则不计 |
| 关卡传送结束 | 看地图：若只 destroy 不清 score → 不计（理想） |
| 休息室练习木桩 | `stageKills` 增、`globalKills` 不增（与 stageTotal 对齐） |
| 第一次 tick（cache 空） | prev=null 不触发；先建 baseline |
| Objective 整个缺失 | 清 cache、整段熔断；下 tick 重试 resolve |

### 11.4 性能

每 tick 约 100 个活体（典型 CTT 副本场景）。
- 路径 A：1 次 `world.getEntities()` 遍历 + 100 次 `ScoreHolder.fromName()` lambda 分配 + 100 次 ConcurrentHashMap 查找
- 路径 B：cache 里通常 ≤ 100 entry，扣掉 `seenLivingThisTick` 一般只剩 0–几条
- 总开销：≈ 200 allocations/tick = 4k/s，GC 完全可忽略

### 11.5 配置开关

| key | 默认 | 含义 |
|---|---|---|
| `clientKillDebugChat` | `false` | 击杀报告聊天日志；开启后每次死亡打 `[CDP/KILL] tick=N ☠ <名> uuid=<8位> | stage=N global=N` |

> **设计调整**（v7.1.0 final）：原本设计了 `clientKillCounter` 总开关，但实测开销极低
> （每 tick 一次活体扫描，~100 entity，4k allocations/s 可忽略），且关闭后 HUD ☠ 段
> 数字恒为 0 反而让人疑惑。**最终决定计数本身始终运行，不暴露开关**。
> 用户只需要决定"要不要把死亡日志打到聊天里"——即 `clientKillDebugChat` 一个开关。
位于 `ConfigScreen` 主屏（紧邻 `clientDamageDebugChat`）。

### 11.6 展示

| 位置 | 内容 |
|---|---|
| HUD CDP 行 | `⚔ <stageTotal> ☠ <stageKills> ⚡ <DPS>/s`（v7.0.20 起 HUD 不展示全局） |
| K 表 CDP 横幅 | `⚔ 全局 / ⚔ 当前关 / ☠ 全局 / ☠ 当前关 / ⚡ DPS`（5 段，整体右对齐） |
| K 表分关行 ☠ 列 | fallback 行从 `cdp.getStageKillsAt(key)` 取 |
| 击杀消息 | `[CDP/KILL] tick=12345 ☠ 石头僵尸 (uuid=4a0206ac) | stage=3 global=42` |

### 11.7 已知限制

- **boss 多阶段会被多次记入**：脚本重置 score 回满 → 视为"复活" → 下次跌零再计 1。误差 +50% 是 boss 关常态，标签里就明确写"无归属·无过滤·仅供参考"。
- **`/kill` 清场可能漏计或多计**：完全看地图 datapack 是否在 `kill` 命令里同时清 `ScoreboardHP` holder。
- **驯服宠物死亡也计入**：客户端没有 ownership 概念，简化版接受。
- **HP objective 名 hardcoded**：候选 `{ScoreboardHP, Health, RedHearts}`，换图遇到不同命名只能改源码（后续可考虑抽到 ServerConfig，但 ServerConfig 是服务端的，纯客户端模式拿不到——这是个未解的小坑）。

---

*文档维护：每次 v7 主线版本 bump 时同步追加一行到 §1 表格 + 一段到 §5（或像 v7.1.0 这样有大新增功能时另起 §11+ 章节）。设计原则若有变动，更新 §2。*
