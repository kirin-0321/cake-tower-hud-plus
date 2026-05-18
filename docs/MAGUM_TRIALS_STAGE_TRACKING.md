# Magum Trials 分关采集 · 设计与实施笔记

> **状态**：设计已定稿，待实施
> **目标版本**：v8.x（待 bump）
> **关联模块**：`StagePayload` / `StageProbeServer` / `StageBoundaryDispatcher` / `StageKey` / `StageNameRegistry` / `ServerConfig` / `StageLocation`
>
> 本文档**只**记录 MT 分关采集这一专项功能的：
> 1. 数据包侧的关键事实（含原文引用）
> 2. 现状问题
> 3. 设计方案
> 4. 边界条件与防御
> 5. 已拍板决策
> 6. 实施清单
>
> 不记录 changelog，不记录通用规范（去看 `agent_notes.md` / `MAP_DATAPACK_ANALYSIS.md` / `ROADMAP.md`）。

---

## 1. 背景与目标

Magum Trials（地图脚本拼写就是 **Magum**，不是 Magnum）是 CTT 大厅可启动的小游戏之一，玩法类似杀戮尖塔：选择难度（1~10）→ 在 MT 中央桥头堡板子上挑下一关 → 进入子关战斗 → 回中央 → 选下一关 → 直到打掉 Magum 决战。一局 MT 实际包含 **~30 关**（含路过型商店/盟友关）。

**当前服务端把整局 MT 视作 `MINIGAME` 黑箱**，三家 stats 全部不采集，分关表里完全看不到 MT 这一段。

**目标**：让 MT 内每一关（含 0 数据的纯路过关）都进入独立 stage 桶，正常 ENTER/EXIT/广播/持久化，与大厅塔关数据互不污染。

---

## 2. 数据包侧关键事实

### 2.1 MT 模式标记

`gamestart.mcfunction:36-45` 在玩家按 Magum Trials 按钮启动游戏时：

```
execute if entity @a[tag=MagumTrials] run scoreboard players set #LobbyMiniGame CTT 4
```

**整局 MT 期间 `#LobbyMiniGame CTT` 恒为 4**，直到玩家用 `/trigger Warplobby` 退出 → 0。

### 2.2 MT 内复用主游戏 stage holder

MT 子关使用的是和大厅塔**完全相同**的 6 个 stage holder：

| holder | 在 MT 中的语义 |
|---|---|
| `#Boss CTT` | 子关 boss 编号（`18 = Magum` 决战） |
| `#MBoss CTT` | 子关 mboss 编号 |
| `#Dungeon CTT` | 子关 dungeon 编号（板子上抽出来的随机 dungeon，对应大厅 dungeon ID 同语义） |
| `#Shop CTT` | 子关 shop 编号（halfway shop / Main Shop）|
| `#Ally CTT` | 子关 ally 编号 |
| `#Misc CTT` | 子关 misc 编号 |

证据：`gamemodes/magum_trials/load_dungeons.mcfunction:91-114` 直接给 RandomBoss / RandomShop 的 score 赋值；这些 score 和大厅塔同一通路写入 `#Boss CTT` / `#Shop CTT` 等。

### 2.3 MT 维度的 tier / floor

| score | MT 中的语义 |
|---|---|
| `#MagumTrialDifficulty GameScores` | MT 难度 1~10（局间通过 `MagumTrialDifficultyChange` 增减），**视为 MT 维度的 tier** |
| `#Floor CTT` | **MT 内部 floor 编号**，每完成一关 +1（`break_room06_magum_trials.mcfunction:64`） |
| `#Tier CTT` | MT 内部仍会写但语义无意义（不要用） |
| `#BreakRoomID CTT` | 恒 6（标记 MT 中央） |
| `#BreakRoom CTT` | MT 中央时 1..700（计时/对话节拍）；进子关时 0 |

### 2.4 关键证据 · `#Floor CTT` 在 MT 内永不归零

```
break_room_universal.mcfunction:76
execute if score #BreakRoom CTT matches 1
        unless score #BreakRoomID CTT matches 6
        if score #Floor CTT matches 11..
        run scoreboard players set #Floor CTT 1
```

`unless score #BreakRoomID CTT matches 6` 显式把 MT 排除在"floor>=11 自动归 1 + tier+1"的标准塔规则外。同样的排除也出现在 `floor_type.mcfunction:20`。

**结论**：MT 内 `#Floor CTT` 单调递增 1 → 2 → ... → 30+，直到打掉 Magum 整局结束。这意味着**每个 floor 编号在一局 MT 内是唯一的**，不会出现 floor 复用导致的桶冲突。

### 2.5 MT 局结束信号

`magum_trials_win.mcfunction:1`：`scoreboard players add #MagumTrialsWin GameScores 1`（boss=18 被击杀触发）。
mod 不需要监听 `#MagumTrialsWin`——`#LobbyMiniGame CTT` 由 4 → 0 的瞬间就是局结束信号（玩家 warp 回大厅）。

### 2.6 死亡 / Continue

`#GameOver CTT >= 1` 走标准 GAME_OVER 路径，与大厅塔一致。MT 死亡不切 `#CTT GameID`。Continue 后 holder 不变 → 重 ENTER 同 stageKey → 桶继续累加。

---

## 3. 现状问题

```
StageProbeServer.computePayload:148-151
        if (miniGame > 0) {
            return new StagePayload(K_MINIGAME, tier, floor, 0,
                    (byte) breakRoomId, (byte) miniGame, GO_NONE, cp);
        }
```

`miniGame == 4` 短路成 `MINIGAME` → `StageBoundaryDispatcher.computeState` 不在 `isStage` 白名单 → `NULL_STATE` → `isCollecting = false` → 三家 stats 全部不采集。

后果：
- 30 关数据**全部丢弃**
- 分关表看不到 MT 任何条目
- StageReportBroadcaster 不广播任何 MT 战绩
- 全员都在 MT 时 `frozen=true`，K 表 ⏱ 暂停

---

## 4. 设计方案

### 4.1 总思路

让 `miniGame == 4` 不再短路成 MINIGAME，而是 fall-through 走标准 stage 检测分支，同时给 payload 打一个 `inMagumTrials = true` flag，让下游知道这是 MT 上下文。

其余 8 个 minigame ID（`1/2/3/5/6/7/8`）保持原 MINIGAME 黑箱行为不动。

### 4.2 `StagePayload` 协议扩展

新增字段 `boolean inMagumTrials`。网络包向后兼容：客户端旧版本读不到新字段时默认 false，行为退回老逻辑。

### 4.3 `StageProbeServer` 路由改造

伪代码：

```
读 14 个常规 holder
+ 新增读 #MagumTrialDifficulty GameScores → mtDifficulty

if (!isCtt) → LOBBY  (不变)

if (miniGame > 0):
    if (miniGame == 4 && cfg.collectMagumTrials):
        // ===== MT 专用路径 =====
        // 用 mtDifficulty 替代 tier 维度
        int mtTier  = mtDifficulty;
        int mtFloor = floor;       // #Floor CTT 直接用

        if (gameOver >= 1) → GAME_OVER (带 inMagumTrials=true)
        else if (boss > 0)    → STAGE_BOSS    (inMT=true, tier=mtTier, floor=mtFloor)
        else if (mboss > 0)   → STAGE_MBOSS   (inMT=true, ...)
        else if (dungeon > 0) → STAGE_DUNGEON (inMT=true, ...)
        else if (shop > 0)    → STAGE_SHOP    (inMT=true, ...)
        else if (ally > 0)    → STAGE_ALLY    (inMT=true, ...)
        else if (misc > 0)    → STAGE_MISC    (inMT=true, ...)
        else                  → BREAK_ROOM    (inMT=true, MT 中央)
        return
    else:
        → MINIGAME  (老逻辑，inMagumTrials=false)

if (gameOver >= 1) → GAME_OVER  (老逻辑)
... boss / mboss / ... 老分支
```

### 4.4 `StageBoundaryDispatcher.computeState` 命名隔离

```
boolean inMT = payload.inMagumTrials();
String stageType = stageTypeFromKind(k);
if (inMT && isStage(k)) {
    stageType = "mt_" + stageType;   // mt_boss / mt_mboss / mt_dungeon / mt_shop / mt_ally / mt_misc
}
StageKey key = new StageKey(gameIdStr, tierStr, floorStr, stageType, stageNumStr);
```

为什么要前缀：MT 难度5 第8关的 boss=18（Magum）和大厅塔 T1F18 boss=18（同样是 Magum）是完全不同的玩法情境，不能合桶。`mt_*` 前缀做命名空间隔离。

### 4.5 `floor <= 0` 防御短路

datapack 在某些瞬间可能把 `#Floor CTT` 临时归 0（虽然 MT 内显式排除归 1 规则，但保险）。`computeState` 在 `inMT && floor <= 0` 时直接返回 NULL_STATE，避免空 floor 桶污染。

### 4.6 T1F1 自动清零兼容

```
StageBoundaryDispatcher.maybeAutoClearForNewRun
private static boolean isT1F1(StageKey k) {
    if (k == null) return false;
    if (k.stageType() != null && k.stageType().startsWith("mt_")) return false;  // 新增
    return "1".equals(k.tier()) && "1".equals(k.floor());
}
```

MT 难度1·第1关 stageKey = `(gid, "1", "1", "mt_dungeon", "...")` 不应触发主游戏清零。

### 4.7 全员 frozen 重新评估

```
StageBoundaryDispatcher.updateGlobalFrozenFromPlayers
// 当前白名单只判 BREAK_ROOM / STAGE_*
// 改：MT 上下文下的 MINIGAME 不存在了——只要 inMT，无论是 MT-BREAK_ROOM 还是 mt_stage，都算"在玩"，不冻
```

实际上由于 4.3 设计里 MT 已经 fall-through 到 BREAK_ROOM / STAGE_* 路径，`updateGlobalFrozenFromPlayers` 现有白名单天然包含这些 kind，**无需改动**。

### 4.8 `StageNameRegistry` 翻译回退

不复制翻译表。在 `localizedName(kind, id, lang)` 入口加一条：

```
if (kind starts with "mt_") {
    String baseKind = kind.substring(3);
    String base = lookup(baseKind, id, lang);  // 例如 "mt_dungeon" → "dungeon" → "Electro Circus"
    return base != null ? "[MT] " + base : null;
}
```

或在调用方（`StageReportBroadcaster` / `StatsTableData.localizeStageName`）拼接前缀，由设计者择一处统一。

### 4.9 客户端 `StageLocation.formatted()`

收到 `inMagumTrials = true` 的 payload 时，前缀字符串改：
- `STAGE_BOSS` → `"Magum试炼·Boss · ..."`
- `STAGE_DUNGEON` → `"Magum试炼·副本 · ..."`
- `BREAK_ROOM` → `"Magum试炼·中央 · ..."`
- ...

`Snapshot` record 加 `boolean inMagumTrials` 字段，`fromPayload` 读 `p.inMagumTrials()`。

### 4.10 `ServerConfig` 配置项

```
public boolean collectMagumTrials = true;  // 默认开
```

`configVersion` +1，`migrate()` 加分支：旧版本配置升上来时强制把这个字段设为 true（默认开启新功能）。

线上回退路径：用户编辑 JSON 改成 false → 行为完全回到老逻辑（MT 全程 MINIGAME 黑箱）。

---

## 5. 边界条件与防御

### 5.1 跨难度局衔接

玩家不退 MT 直接续局（难度 5 → 6）：
- `#MagumTrialDifficulty GameScores` 5 → 6
- `#CTT GameID` 跳变（每局 +1，由 datapack 维护）→ 触发 `StageBoundaryDispatcher.SESSION_LISTENERS`
- StageKey 的 gameId 字段变 → 自然分桶
- 即使 `#Floor CTT` 偶然不归零（datapack 没显式 reset），新难度的 floor 走 31, 32... 仍然唯一

### 5.2 死亡 → Continue → 同关重打

`#GameOver` 1+ → GAME_OVER 路径 → EXIT 派发 → broadcaster 广播本关战绩。
Continue 后 `#GameOver = 0` + holders 不变 → 重 ENTER 同 stageKey → 桶继续累加。
**这就是为什么 stageKey 用 `(gid, tier, floor, type, num)` 5 元组而不带时间戳**：让"死了复活继续打同一关"自然回到同一桶。

### 5.3 同一 floor 内子关切换的 1:1 保证

datapack 设计上每个 floor 编号只能进一个 tile（板子上选一个 dungeon/shop/ally/boss 进去）。出来 → floor +1 → 下一个 tile。
不存在"同 floor 反复进出不同 tile"的可能，stageNum 不会抖动。

### 5.4 0 数据关也记录（**用户决策**）

路过型短关（`mt_shop`、`mt_ally`、`mt_misc` 等可能没有任何伤害/击杀/受伤）**仍然要进桶并广播**：
- 进桶：让分关表 30 行齐全、按 enterMs 升序排列时序完整
- 广播：用户决策"0 数据也可以记录"

不加 `skipEmptyMtStages` 这种过滤配置项，30 关全部入分关表。

### 5.5 断线 / 退服中途

`StageBoundaryDispatcher.onDisconnect` 已实现：触发 EXIT + 写 `STAGE_EXIT_MS`。MT 中途断线那一关的桶已经在 ENTER 时建好且累计中，断线只是定格 ⏱。重连后回到同一 stageKey 还能继续累加。

### 5.6 MT 中央 = BREAK_ROOM（非战斗）

MT 中央桥头堡视作 BREAK_ROOM 路径：
- `isCollecting = false` → 选关时不会有 stats 写入
- 不建 stageKey（中央不入分关表）
- "上一关切片" HUD 行（`lastSeenStageKey`）正常显示上一关数据
- 进入下一个 mt_* 子关瞬间清零 + 开始新关切片

### 5.7 持久化兼容

`StageKey.stageType` 是自由字符串，旧 NBT 文件没有 `mt_*` 桶，新数据自然落新桶。CDP / server NBT 都不需要 schema bump。

---

## 6. 已拍板决策

| # | 项 | 决策 |
|---|---|---|
| 1 | stageType 命名方案 | `mt_*` 前缀（不新开 Kind 枚举，避免破坏客户端 ordinal 对齐） |
| 2 | tier 维度数据源 | `#MagumTrialDifficulty GameScores`（1~10 MT 难度），不用 `#Tier CTT` |
| 3 | MT 中央归类 | 复用 `BREAK_ROOM` 路径（`isCollecting=false`，不建 stageKey），不另开 Kind |
| 4 | 客户端纯 fallback 模式 | 本期不做。纯客户端 detector 仍把 MT 识别成 MINIGAME，由 mod 文档说明"双端装才能看分关"。 |
| 5 | GameOver 处理 | 复用现有 GAME_OVER 路径，无需 MT 特殊化 |
| 6 | 0 数据关广播 | **必须广播 + 必须入分关表**（用户明确决策："0 数据也可以记录"） |
| 7 | 配置回退闸 | `ServerConfig.collectMagumTrials = true`，false 时回老黑箱行为 |

---

## 7. 实施清单

按依赖顺序：

### 7.1 协议层

- [ ] `StagePayload` record 新增字段 `boolean inMagumTrials`
- [ ] PacketCodec 序列化 / 反序列化更新
- [ ] 客户端 receiver 老版本兼容（无字段时默认 false）

### 7.2 服务端探测

- [ ] `ServerConfig` 新增 `collectMagumTrials` 字段，bump `CURRENT_CONFIG_VERSION`，加 `migrate()` 分支
- [ ] `StageProbeServer.tick` 新增读 `#MagumTrialDifficulty GameScores`
- [ ] `StageProbeServer.computePayload` 拆分 MT 专用分支，覆盖 4.3 全部 6 个 stage kind + BREAK_ROOM + GAME_OVER
- [ ] payload 上 `inMagumTrials` flag 正确填充

### 7.3 边界派发

- [ ] `StageBoundaryDispatcher.computeState` 加 `mt_*` 前缀逻辑
- [ ] `floor <= 0 && inMT` 短路防御
- [ ] `maybeAutoClearForNewRun.isT1F1` 排除 `mt_*` stageType

### 7.4 翻译与渲染

- [ ] `StageNameRegistry.localizedName` 加 `mt_*` 前缀回退
- [ ] `StageReportBroadcaster` 文案前缀 `[MT 难度N·FM]`
- [ ] `StatsTableData.localizeStageName` 同步处理 `mt_*`
- [ ] 客户端 `StageLocation.Snapshot` 加 `inMagumTrials` 字段
- [ ] `StageLocation.Snapshot.formatted()` MT 上下文前缀渲染

### 7.5 i18n

- [ ] `assets/ctt-health-display/lang/{en_us,zh_cn}.json` 新增 MT 相关 key（前缀文本、中央选关区文案等）
- [ ] 走 `Text.translatable`，禁止字面量

### 7.6 验证

- [ ] 编译通过 `.\gradlew.bat build`
- [ ] 手动验证：进 MT 难度1 → 打 5 关 → 退 → 检查分关表是否有 5 行 `mt_*`
- [ ] 手动验证：MT 内死亡 → Continue → 同关数据是否累加
- [ ] 手动验证：`collectMagumTrials = false` 配置下 MT 仍然是 MINIGAME 黑箱
- [ ] 手动验证：MT 中跨难度连续打 → gameId 跳变 → 难度6 数据落新桶不污染难度5

### 7.7 文档同步

- [ ] `agent_notes.md` 在"设计约束"段补一条 MT 分关说明
- [ ] `MAP_DATAPACK_ANALYSIS.md` 第 4 章补 MT 部分（`#MagumTrialDifficulty` / MT 内 `#Floor CTT` 不归零规则）
- [ ] `ROADMAP.md` 新增 v8.x · "MT 分关采集"条目

---

## 8. 待办与未决项

### 8.1 跨难度局自动清零

类似主游戏 T1F1 自动清零，MT 难度1·F1 是否也应当触发"新一轮 MT 自动清零旧 MT 数据"？

- 若是：`maybeAutoClearForNewRun` 加 `isMtFirstStage(next, prev)` 分支
- 若否：靠 gameId 维度天然分桶，旧难度数据保留进 history[]

**当前默认**：不做自动清零，让 gameId 分桶 + 用户手动清。等实施后跑一局看分关表实际效果再回头决定。

### 8.2 MT history 归档独立性

主游戏 session 切换时 `StageBoundaryDispatcher.SESSION_LISTENERS` 触发持久化层归档。
MT 跨难度续局也会触发 session 切换。
但 MT 里的 stageKey 和大厅塔 stageKey 在同一 NBT 文件里——是否需要分文件持久化？

**当前默认**：共用 `ctt_stats.dat`，stageType `mt_*` 前缀已经做了逻辑隔离，物理隔离暂不必要。等实施后看 NBT 文件大小增长情况决定。

### 8.3 MT 战绩广播是否带难度后缀

例：`[MT 难度5·F12·熔岩湖] 战绩` vs `[MT·F12·熔岩湖] 战绩`。

带难度更清晰，但跨难度续局时文案会变；不带难度则纯靠 floor 区分。

**当前默认**：带难度。实施时直接做。

### 8.4 客户端纯 fallback 模式 detector

如果未来用户在没装服务端 mod 的服上玩 MT 也想看分关，需要客户端 `ClientStageDetector` 增加 MT 子关识别——通过解析 sidebar / actionbar 文本。

**当前默认**：本期不做。MT 玩家通常是自建本地存档（集成服务器，本机就有 mod），需求度低。

### 8.5 检查 `#Tier CTT` 在 MT 内是否被脚本污染

我们用 `#MagumTrialDifficulty` 作为 tier 维度，但 `#Tier CTT` 仍然会被 datapack 写——理论上不影响 stageKey（不读它），但 payload 里仍然带 `tier` 字段透传给客户端 HUD 文本——会不会让客户端 HUD 显示出诡异的 "T?"？

**待实施时验证**：如果会，payload 在 MT 路径下把 tier 字段也覆盖成 `mtDifficulty` 即可。

---

## 9. 改完之后玩家看到什么（实际效果速查）

### 9.1 进 MT 中央选关

```
HUD 位置行: Magum试炼·中央 · T5·F1
HUD "关:" 行: 上一关切片（首次进入空）
分关表: 上一关那行还在
计时: 不 frozen
```

### 9.2 选了 dungeon=33 进入

```
[CTT BD] entered stage StageKey(gid=12, tier=5, floor=1, type=mt_dungeon, num=33)

HUD 位置行: Magum试炼·副本 · T5·F1 · #33  ◇电光马戏团
HUD "关:" 行: 伤害 0 / 击杀 0 / 受伤 0  ⏱ 0:00:00 ⭐
分关表: + [⭐进行中] [MT 难度5·F1] 副本·电光马戏团
```

### 9.3 打通回中央

```
▌ [MT 难度5·F1·电光马戏团] 战绩
 伤害: 你 8542 (DPS 87) / 队友A 4321
 击杀: 你 12 / 队友A 8
 挨打: 你 1850 (30 红心) ⏱ 1:38

分关表那行 ⏱ 定格 1:38
"关:" 行进入"上一关切片"显示模式
```

### 9.4 打到第 30 关 Magum 决战胜利

整局 30 行按 enterMs 升序排列，含 0 数据的 shop / ally 路过关：

```
[MT 难度5·F1] 副本·电光马戏团       1:38   D 8542  K 12  T 1850
[MT 难度5·F2] 副本·熊蜂大乱斗       2:11   D 12K   K 18  T 2400
[MT 难度5·F3] 商店·主商店           0:25   D 0     K 0   T 0      ← 0 数据也记录
[MT 难度5·F4] 副本·恶灵村落         3:02   D 15K   K 25  T 3100
[MT 难度5·F5] 盟友·斯麦什 NPC       0:18   D 0     K 0   T 0      ← 0 数据也记录
... (~25 行)
[MT 难度5·F30] Boss·玛古姆          4:12   D 22K   K 1   T 5500
```

混在大厅塔关卡之间不会乱：`mt_*` 前缀 + gameId 双重隔离，MT 难度5·F30·玛古姆 和大厅塔 T1F18·玛古姆 boss 是两条独立行。
