# v6 伤害 / 击杀 / 承伤 统计 · 开发流程

> **配套文档**：`V6_STATS_DESIGN.md`（原始设计）、`V6_STATS_STATUS.md`（设计 vs 现实 gap 报告）、`ROADMAP.md`（目标与进度）
> **本文档性质**：**开发执行计划**——每阶段做什么、怎么验收、阶段之间靠什么接口衔接。
> **起点**：v6.3.10
> **最后更新**：2026-04-26（v6.6 设计冻结 · 阶段 ④⑤ 一体化开工要点已写入）

---

## 0 · 总则（通读后再开任何一个阶段）

### 0.1 流程（用户 2026-04-18 定稿）

```
① 伤害统计（整理）
        ↓
② 击杀统计
        ↓
③ 承伤统计
        ↓
④ 分层统计（关卡边界 + 持久化）
        ↓
⑤ UI 完善（合并 / 重写成设计 §5 §6）
```

**每阶段闭环**：必须能通过"一次游戏内验证"拍板才进入下一阶段。中间产出的临时 UI（L 键面板、聊天广播）**保留到阶段 ⑤ 再决定去留**。

### 0.2 本轮不变的约定

| 条目 | 约定 |
|---|---|
| 归属栈（v6.5.2 起：L1~L8 硬归属 + L9 三子层未分类） | **保留**，击杀 / 承伤都通过 `AttackerProbe.attribute()` 复用。v6.5.2 删 L8/L8b 召唤物；L7b → L7；原 L7 → L8；L9 拆 NONE / FILTER / HEAL |
| 聊天栏实时伤害广播 | **保留**——当前阶段唯一的"服务端反馈通道"，调试必备 |
| L 键伤害分配面板 | **保留**——阶段 ② / ③ / ④ 会继续在它上面加临时列 |
| 测试假人 | **临时忽略**（方案 1）。阶段 ② / ③ 的击杀 / 承伤会误记进假人数据，已知缺陷；阶段 ④ 加休息室规则后自动解决 |
| `*DMG` 9 种类型拆分 | 数据层保留，UI 层不暴露（违反设计 §12 的范围限于内部） |
| 持久化 `ctt_stats.dat` | 阶段 ①②③ **不做**，阶段 ④ 一次性做 |

### 0.3 双端架构铁律（v6.0+ 全部数据功能 · 用户多次重申）

> **原文（用户 2026-04-25 重申）**：
> 服务器作为数据源，客户端作为显示端，通过自定义网络包进行同步，并且在本地存档（单机模式）中也要正常工作，当前先在本地存档完成基本功能开发，后期补上网络同步功能。

**统一架构**（违反就回工）：

```
┌─ Server (权威数据源) ──────────────┐    ┌─ Client (纯展示) ──────────────┐
│ Mixin / Probe / Tick               │    │                                │
│   ↓ 读 server.getScoreboard()      │    │                                │
│   ↓ 读 player.getCommandTags()     │    │                                │
│   ↓ 计算 / 累加 / 归属             │    │                                │
│   ↓ Snapshot                       │ S2C│  ClientPlayNetworking          │
│   └→ ServerPlayNetworking.send()   ├───→│   .registerGlobalReceiver      │
│                                     │    │   → Client*Cache (volatile)   │
│                                     │    │       ↓                        │
│                                     │    │   HUD 渲染读 Cache            │
└────────────────────────────────────┘    └────────────────────────────────┘
```

**铁律细则**：

1. **HUD 严禁直接调服务端 static 方法**（如 `PlayerDamageStats.snapshot()`）。本地存档下"侥幸能跑"是单 JVM 共享内存，专用服务器立刻崩。
2. **数据采集 100% 在服务端**：Mixin / scoreboard probe / 玩家 tag 检测，全部走 `MinecraftServer` API。
3. **payload 推送策略**：diff 检测（变化才推）+ 心跳（60 tick 兜底）+ 加入时立即推一次完整。
4. **客户端缓存全部 `volatile`**：写在 client network thread，读在 render thread。
5. **本地存档 = 主测试舞台**：Fabric IntegratedServer 用 LocalChannel 完整模拟 payload 链路；本地能跑 = 专用服务器零改动。
6. **过渡期容忍**：在 sync 还没补上的旧模块，先标记为"债务"，新功能不再新增此类直接调用。

### 0.4 当前合规状态（截至 v6.5.6）

| 模块 | 数据源位置 | 客户端访问方式 | 双端 sync 状态 |
|---|---|---|---|
| **关卡位置 (StageLocation v6.5.6)** | server scoreboard + tag | `ClientStageLocation.current()` 缓存 | ✅ **已 sync**（标准模板） |
| 伤害分配 (PlayerDamageStats) | server static | `PlayerDamageStats.snapshot()` 直调 | ⚠️ **债务** —— 仅本地存档可用 |
| 击杀 / 助攻 (PlayerKillStats) | server static | `PlayerKillStats.getKills(uuid)` 直调 | ⚠️ **债务** |
| 承伤 (PlayerTakenStats) | server static | `PlayerTakenStats.snapshot()` 直调 | ⚠️ **债务** |
| HUD 标题栏 LIVE/IDLE/FROZEN | server static | `snap.live() / snap.frozen()` | ⚠️ **债务**（伴随伤害分配 sync） |

**清欠策略**：阶段 ⑤ UI 完善时，把这 4 个旧模块统一改造成 payload 模式，复用 `StagePayload` 同款骨架（payload record + diff push + volatile cache）。在此之前**不再新增任何 client→server static 直调**。

### 0.5 跨阶段的数据结构约定 · `stageKey`

**从阶段 ① 开始**，所有统计 API 的签名就带 `stageKey` 参数，当前阶段一律填 `null`：

```text
PlayerDamageStats.add(player, stageKey, damage, layer)
PlayerKillStats .add(player, stageKey, victimKind)
PlayerTakenStats.add(player, stageKey, damage, sourceKind)
```

- 阶段 ①②③：`stageKey = null` ≡ "整个会话" ≡ 当前行为
- 阶段 ④：开始真正填五元组 `(GameID, Tier, Floor, StageType, StageNum)`
- **好处**：阶段 ④ 只需改"写入点的参数"和"读取端的聚合逻辑"，不用改一整张 `caller` 链。

**违反此约定的代价**：阶段 ④ 会在 `AttackerProbe`、`DamageProbe`、所有 mixin 的调用链上做大面积改动，返工成本极高。

---

## 阶段 ① · 伤害统计（整理 / 规范化）

### 目的

不改功能，只做"阶段 ②③④ 所需的结构铺垫"。是**代价最低、风险最低**的一步，主要是兑现 `stageKey` 约定。

### 输入

- 现有 `PlayerDamageStats` / `AttackerProbe` / `ScoreDeltaTracker` / `VictimTypeCache` 全部保留
- 现有 L 键面板保留

### 产出

- `PlayerDamageStats.add / addShared / addUnattributed` 都多一个 `StageKey stageKey` 参数（当前调用方一律传 `null`）
- `StageKey` 数据类占位（5 个字段可选全 null，含 `isSession()` 方法表示"整个会话"）
- 面板 / 聊天广播 / 归属栈 行为零变化

### 验收

- `gradle build` 通过
- 进游戏打一只怪：聊天栏伤害消息、L 键面板数字 **与 v6.3.10 完全一致**
- `grep stageKey`：在 3 个 API 签名 + `StageKey` 类中出现，其余代码不变

### 风险

- 极低。纯结构铺垫。

---

## 阶段 ② · 击杀统计

### 目的

按玩家累计"谁打死了几只"，挂到现有面板上做视觉验证。

### 数据源（用户 2026-04-25 敲定 · 方案 C）

原候选 A / B 都被放弃：

- **方案 A（Mixin onDeath）**：地图用 scoreboard 四层血自管，vanilla `onDeath` 不会被触发 → 基本不可行
- **方案 B（RedHearts delta 扫描）**：实体可能被地图 `kill @e` 过场/免死/真死混在一起，状态机复杂易误判

**方案 C · 致死一击归属延续 + tombstone 扫描**：

> 击杀归属 = 致死一击的伤害归属的延续，不另起炉灶

管线如下：

1. **伤害 tick**：`AttackerProbe.recordFromDamageShower()` 归属成功后，除了写 `PlayerDamageStats` 还写入 `VictimLethalCandidate`（新 · TTL 5 tick），登记"最近一次对该 victim 的归属结果 = killer 候选"
2. **伤害 tick**：同时写入 `VictimDamageContributors`（新 · TTL 30s），累积本场 victim 的每玩家伤害贡献（供助攻用）
3. **END_SERVER_TICK 钩子 `VictimTombstone::tickEnd`**（新）：
   - 对比 `prevSnap`（上 tick 末）和当前实体池，识别**真死亡**：`prevSnap[uuid]` 存在 + 当前实体消失 + `VictimLethalCandidate[uuid]` 命中（表示本 tick 吃过归属伤害）→ 确认击杀
   - **免死救活**：实体仍在且 `RedHearts` 被 set 回 1 → 什么都不做
   - **非战斗清场**：实体消失但 `VictimLethalCandidate` 未命中 → 视为 `/kill` 过场，不计
4. **killer 决定**：查 `VictimLethalCandidate.lookup(uuid)` → fallback `VictimTypeCache` → fallback `VictimLastHitter(AllDMG)` → 全失败则"未分类击杀"
5. **assist 决定**：`VictimDamageContributors.getContributors(uuid)` 去掉 killer 自身 → 其他贡献者全部 = 助攻者（多人）

### 助攻规则（Q3 拍板 · 用户 2026-04-25）

> "最后一击 = 击杀，其他的算助攻"

- **killer** = 致死一击的归属（最后一击）
- **assist** = 本场对该 victim 造成过**已归属**伤害的其他玩家（多人）
- L7+/L8+/L9（未分类）的伤害**不进 contributors 表**——一致性原则，未分类伤害不升格为助攻
- 同一玩家只会在一个位置（killer 或 assist），不会双记

### 数据结构

```text
PlayerKillStats.recordKill(killerUuid, killerName, stageKey, layer, victimKind)
PlayerKillStats.recordAssist(assistUuid, assistName, stageKey)
PlayerKillStats.recordUnattributedKill(stageKey, victimKind)
    ↓
Entry{ uuid, name, kills, assists, lastKillTick, lastVictimKind, layerCounts }
```

- `victimKind` 枚举暂定 `MOB / BOSS / PLAYER / UNKNOWN`——阶段 ② 先按"是否带 `Boss` tag"粗分 `MOB / BOSS`；其余统一 `MOB`
- `stageKey` 填 `null`（约定）

### 面板临时展示

在现有 L 键面板的**每个玩家行追加**（详情模式）：

```
玩家A    1,234  45.2%
         硬 1,234 · 事件 12 · 最高 300 · 击杀 5 · 助攻 3
玩家B      980  34.8%
         硬   980 · 事件 10 · 最高 280 · 击杀 3 · 助攻 7
未归属   1,500  20.0%
         事件 4 · 击杀 2
```

紧凑模式仅在名字右侧加 `· KX AY` 后缀。

### 聊天广播（测试期开启）

由 `ModConfig.broadcastKillsInChat` 控制（默认 `true`）：

```
[击杀] Joey 击杀了 SkeletonKing [Boss]  助攻: Fred, Jelly
[击杀] Joey 击杀了 Zombie
[击杀] ??? 击杀了 Zombie                未分类
```

### 验收

| 场景 | 期望行为 |
|---|---|
| 自己一击秒杀小怪 | 面板 击杀 +1 |
| 队友一击秒杀 | 只有队友那行 +1 |
| A 打残 + B 补刀 | B +1 击杀、A +1 助攻（最后一击归属 = killer） |
| DoT 致死（火焰烧死） | 致死 DoT tick 走 `VictimDamageSourceCache` carry 归点火者；归不到则进"未分类击杀" |
| 玩家死亡 | 不计任何人 +1（玩家没有 `E` tag） |
| 假人死亡 | **会误记 +1**，已知临时缺陷（阶段 ④ 休息室规则自动解决） |
| 自己掉坑摔死的怪 | 本 tick 无归属伤害 → `VictimLethalCandidate` 未写 → tombstone 视为非战斗清场，**不计** |
| 过场 `/kill @e` 清场 | 同上，**不计** |
| B14_Protected 免死 | 实体仍在、`RedHearts` 被救回 → **不计** |

### 风险

- **低**。不用 mixin，纯 tick 扫描；归属复用成熟的九层栈
- **假人 kill 会污染数据**——已知接受

### 副产品 · 死亡锚点异常伤害过滤（2026-04-25 用户提出）

> 当初 v6.3.8 想做的"均值 × K 异常过滤器"已于 v6.3.10 回滚。回滚理由：均值阈值会误伤玩家的致死/高光单击。
> **阶段 ② 完成后，击杀事件本身就是一个精确时间锚点**，用它做过滤比均值阈值干净得多。

**核心规则（初版设想，阶段 ② 接近尾声时再敲定具体参数）**：

对某 victim 的一次超大伤害（例如 >5×最近均值 或 >HARD_CAP），**延迟判定**：

| 条件 | 判定 |
|---|---|
| victim 在 death tick ±N（N≈2~5）内确实死亡 | **致死伤害**：保留，算进击杀者 |
| victim 仍活着且后续继续掉血 | **可疑累加**：丢弃，不进 `PlayerDamageStats` |
| victim HP 剩余远小于该伤害（被 clamp 到 0） | **致死伤害变种**：保留 |

**实现方式**：

- `DamageShower` / `*DMG` 入口不再立即 `feedStats`，而是进 `PendingDamageBuffer`
- buffer 中每条 `(victim, tick, damage, attacker)` 等待 N 个 tick
- 期间若 victim 死亡触发阶段 ② 的 `LivingEntity.onDeath` → 该条出 buffer 进 `PlayerDamageStats`
- 期间若 victim 仍活着且该条 damage 被判定异常 → 丢弃
- 其余正常伤害 N tick 后自动出 buffer 进 `PlayerDamageStats`（增加 ≤2~5 tick 延迟，肉眼不可见）

**因此 Q7（异常过滤器要不要重上）合并到阶段 ②**，不再作为独立待办。

---

## 阶段 ③ · 承伤统计

### 状态：v6.5.0 交付（2026-04-25）

### 目的

按玩家累计"挨了多少伤害"。数据结构和阶段 ② 同构。

### 数据源 · 最终决策：方案 F（`DamageTook` scoreboard 每 tick 末扫描）

开工时重新核对了地图 `damage.mcfunction`，发现此前"推荐 B（四层血 delta）"错过了更干净的数据源。完整候选对比：

| 方案 | 数据源 | 评估 | 结论 |
|---|---|---|---|
| A · `*DMG` 反向筛 | 9 种 `*DMG` 写入时 target 为 CTT 玩家 | 地图对玩家受击也会写（`FireDMG`/`MeleeDMG` 等），但要聚合 9 种分类、且需过滤 Mob↔Mob 内部串伤 | 口径能对，但实现复杂 |
| B · 四层血 delta | 蓝+黑+灵+红总和下降 | 回血 / 换装 / 复活 / `/kill` 复活都会产生假 delta；过滤成本高 | **否决** |
| C · `PlayerEntity.damage` Mixin | Vanilla hurt 值 | 不是地图管线末端，和造伤口径不一致 | 违反 §2 铁律 |
| D · `DamageShower` 粒子 score（player fork） | 粒子 `DamageShower` 值 | `damage.mcfunction` line 1028 sort=nearest 无 distance 上限，邻近怪物 `Damage` 可能污染粒子 score；`limit=10` 取样损失 | 有污染隐患 |
| **F · `DamageTook` scoreboard END_TICK 扫描** ✅ | line 983 `DamageTook = Damage`（护甲后、扣血链前） | 地图自己汇总、每 tick 头 reset、每实体独立、无粒子污染；END_TICK 读取时机完美 | **采用** |

**关键时序论证**：
- `damage_universal.mcfunction` line 6 每 tick **头** `reset @e DamageTook`
- `damage.mcfunction` line 983 对本 tick 受击实体写 `DamageTook = Damage`（已经过 `DamagePercent` 护甲减免）
- Fabric `END_SERVER_TICK` 触发时：line 983 已写完、下 tick 的 reset 还没跑 → 读到的是**当前 tick 真实承伤**

**造伤 ≡ 承伤**：`DamageShower` 粒子 score（line 1028）和 `DamageTook`（line 983）读的是**同一 tick 的同一个 `Damage` 寄存器**，因此阶段 ② 造伤口径和阶段 ③ 承伤口径严格对称，完全满足设计 §2 铁律。

### 实现

| 文件 | 职责 |
|---|---|
| `PlayerTakenStats.java` | 数据聚合（同构 PlayerKillStats，无归属栈）。字段：`taken / events / maxHit / lastTakenTick` |
| `PlayerTakenProbe.java` | END_SERVER_TICK 扫描：遍历 `hasCommandTag("CTT")` 的在线玩家，读 `DamageTook`，>0 则 `PlayerTakenStats.addTaken` + 可选广播 |
| `CttStatsServer.java` | 注册 `PlayerTakenProbe::tickEnd`（在 `VictimTombstone` 之前） |
| `PlayerDamageStats.java` | `start/stop/clear/setFrozen` piggy-back 调用 `PlayerTakenStats.sync*` |
| `ModConfig.java` | 新增 `broadcastTakenInChat = true`、`broadcastTakenThreshold = 1`（全部广播） |
| `DamagePanelRenderer.java` | 详情行 +10px 高度 → 第二条 detail 行 `承伤 X · 单次峰 Y`；摘要行追加 `承 X` |

### 已知局限（记录、阶段 ④ 或之后修）

1. **环境伤漏记**：vanilla 摔血/窒息等如不走 `Damage` scoreboard → 不被计入。但同样也不算造伤，对称。
2. **免疫救回算入**：`DamageTook` 已写入但后续 IronHeart 等机制让实际 RedHearts 未减 → 统计会夸大承伤。语义合理（"你挨了这么多、是被护甲救了"）。
3. **假人打你**：误记，待阶段 ④ 休息室规则解决。
4. **队友友伤**：按设计 §2 不特殊剔除，记入。

### 验收

| 场景 | 期望 | 测试方式 |
|---|---|---|
| 站怪面前挨一下 100 伤害 | 聊天栏 `[承伤] Name -100`、L 面板 `承伤 100 · 单次峰 100` | 站 FireDanger 附近 |
| 连续挨同一伤害 | taken 单调递增，events 每 tick +1 | 同上观察 |
| 吃药回血 | taken 不变（只加伤害，不减） | 持有 `Bandage` 回血 |
| `/kill` 过场 | 不产生虚假承伤 | 假人触发 |
| 数值口径 vs 造伤 | 队友 A 打 B，A 面板 `total = 100`、B 面板 `taken ≈ 100` | 友伤模式 |

---

## 阶段 ④ · 分层统计（关卡边界 + 持久化）

### 目的

让 ①②③ 的所有数据按**关卡 / 会话**切片，并持久化。

### 子任务

#### 4.1 关卡边界检测

监听 4 个计分板（参考设计 §3）：

| 计分板 | 信号 | 触发 |
|---|---|---|
| `#CTT GameID` | 跳变 | Session 切换 |
| 6 个 stage holder（`#CTTFloor` `#CTTBoss` `#CTTMBoss` `#CTTDungeon` `#CTTShop` `#CTTAlly`——**待 MAP_DATAPACK_ANALYSIS.md 核实**） | 由 0 跳非 0 | Stage 开始 |
| 全部 stage holder 回 0 | — | Stage 结束（回休息室） |
| `#CheckPoint` | =1 | 不清 Stage 数据 |
| `#LobbyMiniGame` | >0 | 整体暂停采集 |

#### 4.2 StageKey 填充

把阶段 ①②③ 的 `stageKey = null` 改为真实五元组。写入点只有 3 个（`PlayerDamageStats.add` / `PlayerKillStats.add` / `PlayerTakenStats.add`），改动集中。

#### 4.3 休息室规则

- 休息室期间**不采集**新数据（阶段 ②③ 的假人误差在此自然消失）
- L 键面板显示**上一关**数据

#### 4.4 持久化

- 文件：`world/data/ctt_stats.dat`（NBT 格式）
- 写盘时机：每 30s + 每关结束 + 服务端关闭时
- 读盘时机：服务端启动时
- Session 切换时旧数据归档到 `history[]`（v1 不暴露到 UI）

### 验收

| 场景 | 期望 |
|---|---|
| 从休息室进 Floor1 | 日志打印 Stage 开始；面板切换到"当前关"数据 |
| 回到休息室 | 面板显示"上一关"数据，不清零 |
| CheckPoint 触发 | 当前关数据保留 |
| 假人伤害 | **不再进统计**（休息室期间不采集） |
| 重启游戏 | 数据留存 |
| 新开 Game（GameID 跳变） | 旧 Session 归档、新 Session 从 0 开始 |

### 风险

- **高**。需要精确核对 MAP_DATAPACK_ANALYSIS.md §10（地图约定）。Stage holder 名字搞错会一切失效
- NBT 持久化是新代码，并发和时序要小心

---

## 阶段 ④⑤ · v6.6 一体化开工（用户 2026-04-26 拍板）

> 阶段 ④ 数据层 + 阶段 ⑤ UI 不再拆分，统一作为 **v6.6 系列**分里程碑落地。
> 设计 ground truth：`V6_STATS_DESIGN.md` v6.6 修订版（§5/6 加 🤝 + 关卡名本地化 + 休息室金边）。

### v6.6 设计冻结点（拍板结果）

| # | 决策点 | 拍板 |
|---|---|---|
| 1 | 助攻显示形式 | **HUD 紧凑** `☠ 击/助`；**表格独立列** `☠` + `🤝` |
| 2 | 分关表"关卡"列 | 用 `StageNameRegistry` 提供的本地化具体名（"紫晶迷宫"），超长截断 + tooltip |
| 3 | L 键命运 | 现阶段保留作开发调试面板（9 层归属诊断），加 `devPanelEnabled` 开关 |
| 4 | 历史 session | NBT 持久化但**不进 UI**（v6.6 只看当前 session） |
| 5 | 休息室时表格 | 上一关整组金边 + 关卡名后缀 `(休息中)`，⏱ 列冻结 |
| 6 | 关卡名缺失 | 跨语言 fallback：zh 缺用 en，en 缺用 zh，再缺用类型+编号 `Boss12` |
| 7 | 总表全队总计 | 加表底 `[全队]` 行 sum(⚔/⛨/☠/☠B/🤝)，⏱ 取均值 |
| 8 | 自助攻 | **不计**（killer 在该次击杀里只占 ☠ 不占 🤝；同设计 §2.0）|
| 9 | Boss 击杀 | 总表新增独立列 `☠B`（紫色）；不在 `☠` 总数里减除 |
| 10 | M1 验收基线 | 休息室 → 关卡 A → 休息室 → 关卡 B 流程，验证三 stats 按 stageKey 正确分桶 |

### v6.6 里程碑（每个独立可交付）

| 版本 | 主题 | 内容 | 验收 |
|---|---|---|---|
| **v6.6.0** | M1 数据层（阶段 ④ 主体） | `StageBoundaryDispatcher`（监听 `StageProbeServer`）+ 三 stats 内部按 `StageKey` 分桶 + `snapshotOf(stageKey)` API + 休息室拦截写入 + 关卡边界事件 | ✅ **已完成**（用户验收 OK）。基线流程：休息室 → 关卡 A → 休息室 → 关卡 B → L 键调试面板按"当前关/整局"切换，A 数据不会窜入 B；休息室期间数据冻结在 A |
| **v6.6.1** | M2 持久化 | `world/data/ctt_stats.dat` NBT 写盘 / 读盘 / 关卡结束时增量 / GameID 跳变归档 | ✅ **代码完成（待手测）**。**6 个用户拍板决策**：(Q1) Session 归档触发条件 = 仅 `#CTT GameID` 跳变；(Q2) 同 stage 重进 = 累加（保持内存默认行为）；(Q3) 9 层归属计数 layerCounts[] 持久化（L 键诊断面板重启不丢，每玩家 +~80 字节）；(Q4) 写盘节流 = 60s 周期 + 每关 onStageExit 立即 + SERVER_STOPPING 收尾；(Q5) history[] 容量上限 20，超出弃最早；(Q6) GameID 字段补全（StageProbeServer 顺便读 `#CTT GameID` scoreboard，holder/objective 与其他 holder 反过来：fakeplayer="#CTT", objective="GameID"）。**实现**：`StageKey.toNbt/fromNbt`（5 string + null 安全）；三家 stats 各加 `toNbt()/fromNbt()`（含 stage 桶 + session 累计 + layerCounts，UUID 走 putUuid/containsUuid/getUuid，缺字段安全默认）；`StageBoundaryDispatcher` 加 `updateGameId/restoreGameId/currentGameIdInt/timeTablesToNbt/timeTablesFromNbt/clearStageTimeTables`，`computeState` 把 `currentGameId` 填进 StageKey 第一字段，sessionId 跳变（已见过非 0 旧值）触发 `onSessionChange` listener；新增 `StatsPersistenceManager`（`load(server)` / `saveNow()` / `onTickEnd`（60s 节流）/ `onStageExit`（同步写盘）/ `onSessionChange`（archiveAndReset 把当前 session 整体推入 history[] 截断到 20 + 三家 stats clear）/ `onServerStopping`），文件路径 `<worldDir>/data/ctt_stats.dat` 走 `NbtIo.writeCompressed/readCompressed`，`unfrozenSinceMs` 重启后用 wall-clock 起算避免跨会期失真。**接入点**：`CttStatsServer` SERVER_STARTED 在 `PlayerDamageStats.start() + setFrozen(true)` 后调 `StatsPersistenceManager.load(server)` 覆盖（存档存在则恢复 live/frozen/数据；不存在维持空 + frozen），SERVER_STOPPING 调 `onServerStopping`，END_TICK 注册 `onTickEnd` 节流写，`onStageExit/onSessionChange` listener 接入 manager。Build 通过（6.5.26） |
| **v6.6.2** | M3 嵌入 HUD（设计 §5） | 队友面板每人扩展 2 行：关 ⚔/⛨/☠`击/助`、局 ⚔/⛨/☠`击/助`；自己金色 / 离线灰白 | ✅ **已完成**（待手测）。`embeddedHudMode` 4 档（OFF/ONLY_STAGE/ONLY_SESSION/BOTH=默认），`TeammateStatsLine` 提供按 UUID 单玩家直读，`compact()` 实现 12.3k/1.2M。休息室自动冻在上一关由 `StageBoundaryDispatcher.lastSeenStageKey` 提供 |
| **v6.6.3** | M4 K 键表格面板（设计 §6） | `StatsTableScreen` + Tab[总表]/[分关表] + 滚动 + 排序 + 7/9 列含 🤝 | ✅ **已完成**（待手测）。临时绑 **N 键**（K 已被 toggleBossBars 占；玩家可在控制设置重绑）。`StatsTableData` 把 dealt/taken/kills/bossKills/assists 按 UUID merge；总表 8 列按 ⚔ 默认降序、可点列头切换升降；表底 [全队] 总计行；分关表按 (Tier→Floor→stageNum) 升序、组内 ⚔ 降序、同关多人合并首行 + 进行中关金边；关卡名走 `StageNameRegistry.localizedName` 中英自动 fallback；`StageBoundaryDispatcher` 加 `stageEnterMs/stageDurationMs/isStageInProgress` 支撑 ⏱ 列 |
| **v6.6.3.x** | hotfix · 关卡退出聊天广播 | `StageReportBroadcaster`：候选 B 紧凑多行格式，标题 `══ TxFy · 关卡名 · MM:SS ══` + N 玩家行 ⚔/⛨/☠/🤝 + `─── [全队] ───`，按 ⚔ 降序，自己金色 / 离线灰白 `[离线]` | ✅ **已完成**。触发：`StageBoundaryDispatcher` EXIT 分支（含 T1F1→T1F1 切关、关→休息室、关→GAME_OVER），`StageKey` 维度去重 1.5s 窗口避 4 人同 tick 退场刷屏；接收方全服在线玩家，每人收到的"自己那行"被定制成金色；不显示 Boss 击杀（D3）；关卡名服务端走 `StageNameRegistry.localizedName(kind, id, "zh_cn")` 不依赖 `MinecraftClient`，并在 `CttStatsServer.onInitialize` 里 `StageNameRegistry.load()` 一次 |
| **v6.6.4** | M5 配置选项 + 抛光 | ConfigScreen 加 `embeddedHudMode` / `numberFormat` / `devPanelEnabled` 等 ~10 个开关；自己金色 / 离线灰白 / `12.3k` 压缩 | ModMenu 全部能切；离线队友灰白 + `[离线]` 标记 |
| **v6.6.5** | M6 sync 债务清欠 | `PlayerDamageStats / KillStats / TakenStats` 改 payload 模式（StatsPayload diff push） | ✅ **已完成**（待手测）。**协议 A · 全量周期推 · 1 Hz**：`StatsSnapshotPayload`（v=1，stage 索引化压缩，传 server 算好的 `activeDurationMs` 避免客户端时钟漂移）+ `StatsSnapshotBroadcaster`（END_TICK 每 20 tick 推全员 + JOIN 首推；单遍构造 per-stage `PlayerRow` map 避 O(players × stages) 冗余 snapshot）+ `ClientStatsCache`（volatile 缓存最新 payload，集成服务器直读 server static / dedicated 走 payload 重建 Snapshot）。**5 处 client 读路径全迁**：`TeammateStatsLine` / `DamagePanelRenderer`（仅读迁移；start/stop/clear/freeze 写操作故意保留 server static 直调，dedicated 远程客户端无效，加 javadoc 说明）/ `DamagePanelScreen`（无须改）/ `StatsTableData` / `StatsTableScreen`（顶栏 sessionMs 走 cache）。**未传字段**（`unattributed*` / `globalLayerCounts`）按用户决定不进 payload，dedicated 显示 0；`StageBoundaryDispatcher` 加 `stageExitMs(StageKey)` 公共 getter |

### 子任务追溯（与设计文档章节映射）

| 子任务 | 设计 § | 里程碑 |
|---|---|---|
| StageKey 真值生成 + 关卡边界派发 | §3 | M1 |
| 三 stats 按 stageKey 分桶 | §3 / §4 | M1 |
| 休息室不采集 | §3.1 / §8.6 | M1 |
| NBT 持久化 | §8.5 | M2 |
| 嵌入式 HUD 队友面板（含 🤝 紧凑） | §5（v6.6 修订） | M3 |
| K 键表格面板（含 🤝 独立列、关卡名本地化） | §6（v6.6 修订） | M4 |
| 配置选项 / 数字压缩 / 离线标记 | §8.3 / §9（v6.6 修订） | M5 |
| L 键面板 → 开发模式开关化 | §7（v6.6 修订） | M5 |
| sync 债务清欠（payload 改造） | 0.4 合规表 | M6 |

### 验收

- 开 K 键弹表格面板，总表可排序、分关表按层排列
- 战斗中看队友面板，每人 3 行 `HP / 关: / 局:`
- 断网 / 退出一个队友，面板出现 `[离线]` 灰白
- 6 个配置选项在 ModMenu 可改

### 风险

- 低（纯 UI 工作，数据层此时已稳定）

---

## 待澄清项（不影响当前开工，但总要回来补）

| # | 项 | 何时定 |
|---|---|---|
| Q1 | 击杀的 `victimKind` 细分（MOB / BOSS / PLAYER）标准——按地图标签还是 bossbar 判定？ | 阶段 ② 先按 `Boss` tag 粗分；阶段 ⑤ 再细化 |
| Q2 | 承伤的 `sourceKind` 细分标准 | 阶段 ⑤ |
| ~~Q3~~ | ~~助攻是否记录？~~ | **记录**（用户 2026-04-25 拍板 · 最后一击 = kill，其他贡献者 = assist） |
| Q4 | 配置 `showUnattributed` 默认值（设计 false，当前强制 ON） | 阶段 ⑤ |
| Q5 | 聊天栏广播测试期过后要不要保留？ | 阶段 ⑤ |
| Q6 | L 键测试面板要不要删？还是改名留作"开发模式" | 阶段 ⑤ |
| ~~Q7~~ | ~~异常伤害过滤器（v6.3.10 回滚那个）要不要重上？~~ | **已合并到阶段 ② 副产品（死亡锚点过滤），不再独立** |

---

## 当前进度锚点

- ✅ v6.6.1：**M2 NBT 持久化交付**（待手测）。三家 stats + dispatcher 时间表 + sessionId 全部进 NBT；GameID 跳变归档；history[] 上限 20；60s 节流 + 关结束 + 收尾三套写盘
- ✅ v6.4.0：阶段 ① + ② 代码全量交付（`StageKey` 占位 + `VictimLethalCandidate` + `VictimDamageContributors` + `PlayerKillStats` + `VictimTombstone` + 面板 / 聊天栏测试 UI）
- ✅ v6.4.1：**修复** tombstone 扫不到死亡（`iterateEntities` + `prevSnap` 漏实体）→ 改为 candidate-driven 扫描；TTL 5→600 tick
- ✅ v6.5.0：阶段 ③ 承伤统计交付（`PlayerTakenProbe` + `PlayerTakenStats`，方案 F：`DamageTook` scoreboard END_TICK 扫描）
- ✅ v6.5.1：回血粒子识别（绿色 `background=-16515325`）+ 临时 `return` 过滤（避免怪物回血污染玩家伤害）
- ✅ **v6.5.2**：归属层重构 + 异常数值过滤
  - 删除 L8_SUMMON_FALLBACK / L8B_SUMMON_SHARED（误归属率高，整层删）
  - L7b → L7_BOW_RELEASE（升位为硬归属）
  - 原 L7_LAST_HITTER → L8_LAST_HITTER（降位但改为硬归属，进玩家账户）
  - L9 拆三子层：`L9_NONE`（兜底）/ `L9_FILTER`（黑名单数值 1000 / 10000 / 100000）/ `L9_HEAL`（绿色回血粒子）
  - `grandTotal` 重定义：仅 L1~L8，玩家占比分母 = grandTotal，**L9 不进占比**
  - `DamageProbe.recordFromRedHearts` 命中 `ModConfig.initHpJumpValues` → `forceLayer=L9_FILTER`
  - 回血粒子由 v6.5.1 的 `return` 改为 `forceLayer=L9_HEAL` 路由（保留诊断可见性）
  - 聊天栏作开发通道全量显示，含 `[L9-NONE] / [L9-FILT] / [L9-HEAL]` 子标签
  - HUD 面板 L9 三子层独立分行（详情模式），`drawSummary` 用 `grandTotal` / `totalEvents`（不含 L9）

---

## 修订历史

| 日期 | 版本 | 改动 |
|---|---|---|
| 2026-04-18 | v6.3.10 | 初稿；五阶段流程 + 跨阶段 `stageKey` 约定确立 |
| 2026-04-25 | v6.3.10 | 阶段 ② 加"死亡锚点异常过滤"副产品（用户洞察：击杀事件作为时间锚点，替代均值阈值）；Q7 合并到阶段 ② |
| 2026-04-25 | v6.3.11 | 阶段 ② 数据源敲定 **方案 C**（致死归属延续 + tombstone），放弃 Mixin/RedHearts delta；Q3 拍板"记录助攻"；面板加击杀/助攻两字段；聊天栏加击杀广播 |
| 2026-04-25 | v6.4.0  | 代码交付：方案 C 全套 + `StageKey` 占位 + 击杀 / 助攻统计 |
| 2026-04-25 | v6.4.1  | **修复**：`VictimTombstone` 改为 candidate-driven 扫描（查 `VictimLethalCandidate` 候选池 + `getEntity(uuid)` 直接判消失），摆脱 `ServerWorld.iterateEntities()` 漏实体的问题；`VictimLethalCandidate.TTL_TICKS` 5→600 |
| 2026-04-25 | v6.5.0  | 阶段 ③ 承伤统计交付（方案 F · `DamageTook` END_TICK 扫描） |
| 2026-04-25 | v6.5.1  | 回血粒子（绿色 background）识别 + 临时 return 过滤 |
| 2026-04-25 | v6.5.2  | **归属层重构**：删 L8/L8b 召唤物层；L7b→L7 硬归属；L7→L8 硬归属；L9 拆 NONE/FILTER/HEAL；黑名单数值（1000/10000/100000）→ L9-FILTER；回血粒子改路由 → L9-HEAL；`grandTotal` 仅 L1~L8（玩家占比分母）|
| 2026-04-25 | v6.5.7  | **关卡名本地化**：`scripts/gen_stage_name_map.py` 扫描数据包 `floors/*.mcfunction` + ctt_lang 双语 JSON，启发式映射 slug → 翻译键，输出硬编码 `stage_name_map.json`（120/125 命中，5 个 zh 缺失项 fallback 到 en）；`StageNameRegistry` classpath 加载该表，按 `MinecraftClient` 当前语言（zh_cn / en_us）选择字符串；`StageLocation.formatted()` 在 STAGE_* 行尾追加 ` ◇<本地化关卡名>`。**不依赖** vanilla `Text.translatable` —— 翻译表来自地图自带资源包，硬编码进 mod 资源更稳定 |
| 2026-04-25 | v6.5.8  | **可疑怪物大额过滤**：`ModConfig` 新增 `filterSuspectVictims` / `suspectVictims`（默认"幽匿骷髅"/"幽匿僵尸"）/ `suspectVictimDamageThreshold`（默认 800）；`DamageProbe.recordFromRedHearts` 命中"victim 名含关键词 && damage ≥ 阈值"时强制 `forceLayer=L9_FILTER`；`AttackerProbe.L9_FILTER` chat detail 加 `filtered value=N victim=X` 便于诊断；filter 命中时日志带原因 `[L9-FILTER:init-hp-jump]` / `[L9-FILTER:suspect-victim]` |
| 2026-04-26 | v6.5.9  | **L8 carry 剥离玩家账户**：实测中 `L8_LAST_HITTER` 误命中过多（剧情 set 假伤害也会 carry 给最后命中玩家）。改动只在"伤害账户"维度生效——`AttackerProbe.feedStats()` 检测到 `L8_LAST_HITTER` 时改走 `PlayerDamageStats.addCarry()`，写入新增独立桶 `unattributedCarry`；`grandTotal` 改为 sum(L1..L7)；`Snapshot` 增 `unattributedCarry / unattributedCarryEvents`，`unattributedAll()` 把 carry 也加入；面板新增 L9-CARRY 行（灰色，UNATTR_H_DETAIL 42→52）；聊天栏 L8 行 attacker / tag 改灰色作为"已剥离"提示。**击杀维度不变**：`isAttributionClassified(L8_LAST_HITTER)` 仍返回 true，`VictimTombstone` 仍可用 L8 作为 killer 兜底；玩家维度 `Entry.layerCounts[L8]` 不再 +1，但 `globalLayerCounts[L8]` 仍保留诊断粒度 |
| 2026-04-26 | v6.6.5 | **M6 sync 债务清欠**：协议 A · 全量周期推 · 1 Hz · 一步到位完成。`StatsSnapshotPayload`（v=1，stage 索引化、`activeDurationMs` 服务端算好）+ `StatsSnapshotBroadcaster`（END_TICK 每 20 tick + JOIN 首推；per-stage `PlayerRow` map 单遍构造避 `O(players × stages)` 冗余 snapshot）+ `ClientStatsCache`（volatile 缓存，集成直读 / dedicated payload 重建 Snapshot record）。`CttStatsServer` 注册 S2C payload + END_TICK + JOIN 钩子；`CttHealthDisplay` 客户端 `try-catch IllegalArgumentException` 容忍集成服务器下重复注册 + `registerGlobalReceiver` + DISCONNECT 重置缓存。5 处 client 读路径全迁（`TeammateStatsLine` / `DamagePanelRenderer` / `StatsTableData` / `StatsTableScreen`），写操作（start/stop/clear/freeze）按用户决定保留 server static 直调（dedicated 无效，加 javadoc）。`unattributed*` / `globalLayerCounts` 不进 payload（dedicated 显 0）。`StageBoundaryDispatcher.stageExitMs(StageKey)` 加公共 getter 供 broadcaster 用。Build 通过（6.5.16） |
| 2026-04-26 | v6.6 设计冻结 | **阶段 ④⑤ 一体化为 v6.6 系列**，6 个里程碑（M1 数据层 / M2 持久化 / M3 嵌入 HUD / M4 K 表格 / M5 配置抛光 / M6 sync 债务）。5 条拍板：助攻 HUD 紧凑+表格独立列、分关表关卡名本地化、L 键现阶段保留为开发面板、历史 session 不进 UI、休息室高亮上一关。`V6_STATS_DESIGN.md` 同步修订（§5.2/5.3、§6.4/6.5、§7、§9、§13） |
| 2026-04-26 | v6.6.1 | **M2 NBT 持久化交付**。6 个决策点拍板：仅 `#CTT GameID` 跳变才归档（最严格）/ 同 stage 重进累加 / layerCounts 持久化 / 60s 周期 + onStageExit + STOPPING 写盘 / history[] 上限 20 / GameID 字段现在补。**新增**：`StatsPersistenceManager`（`load`/`saveNow`/`onTickEnd`(60s 节流)/`onStageExit`(立即同步写)/`onSessionChange`(archiveAndReset 把整 session 推入 history[] 截断 20 + 三家 stats clear)/`onServerStopping`），文件 `<worldDir>/data/ctt_stats.dat` 走 `NbtIo.writeCompressed/readCompressed` + `NbtSizeTracker.ofUnlimitedBytes()`。**修改**：`StageKey.toNbt/fromNbt`（5 string + 空串↔null）；三家 stats 各加 `toNbt()/fromNbt()`（UUID 走 putUuid/containsUuid，所有数值缺省 0/空，`unfrozenSinceMs` 重启后用 wall-clock 起算避免跨会期失真）；`StageBoundaryDispatcher` 加 `updateGameId/restoreGameId/currentGameIdInt/currentSessionIdString/timeTablesToNbt/timeTablesFromNbt/clearStageTimeTables`，`computeState` 把 `currentGameId` 填进 `StageKey.gameId`（>0 才填，避免 null 污染同关多 session 的桶 key），sessionId 跳变（已见过非 0 旧值）才触发 `onSessionChange`；`StageProbeServer.tick` 在 holder 扫描里加 `int gameId = readScore(sb, "#CTT", "GameID")`（注意 holder/objective 反向）调 `dispatcher.updateGameId(gameId)`。**接入点**：`CttStatsServer` SERVER_STARTED 在 `PlayerDamageStats.start() + setFrozen(true)` 后调 `StatsPersistenceManager.load(server)` 覆盖（存档存在则恢复 live/frozen/数据；不存在维持空 + frozen），SERVER_STOPPING 调 `onServerStopping`，END_TICK 注册 `onTickEnd` 节流写，`onStageExit/onSessionChange` listener 接入 manager。Build 通过（6.5.26） |
