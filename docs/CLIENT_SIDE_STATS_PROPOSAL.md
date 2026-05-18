# 纯客户端伤害统计 · 可行性研究与设计建议

> **本文档定位**：在不依赖服务端 mod 的前提下，能否仅靠客户端可见的伤害粒子 / 计分板 / 实体数据，重建出当前服务端 mod 提供的"伤害 / 击杀 / 承伤"统计？如果可以，**怎么做、有什么损耗、需要哪些妥协**。
>
> **关联文档**：
> - `CLIENT_SIDE_DATA_REFERENCE.md` —— "客户端能读到什么"清单（前置阅读）
> - `MAP_DATAPACK_REFERENCE.md` —— 地图侧数据源定义
> - `CODE_REVIEW.md` —— 现有服务端实现的架构总览
> - `V6_STATS_STATUS.md` —— v6 设计 vs 实现 gap 分析
>
> **目标读者**：本 mod 维护者、想把 mod 下放到公服环境的二次开发者。
>
> **结论先行**：**完全可行，但必须采用"双轨数据源"策略**——对怪输出走 DamageShower 粒子事件流（§4.6），玩家承伤走 4 层心数 delta（§4.2 第 2 档）。**不能简单复制服务端的 RedHearts-only 主源**——那会因地图侧 4 层级联吸收特性导致"对怪输出"统计系统性偏低。建议作为 v7 核心特性分阶段引入，与现有服务端管线并存而非替换。

---

## 目录

1. [动机](#1-动机)
2. [现状回顾](#2-现状回顾)
3. [可行性结论速览](#3-可行性结论速览)
4. [核心数据通路设计](#4-核心数据通路设计)
5. [归属层映射（关键章节）](#5-归属层映射关键章节)
6. [Stage / Session / 持久化的客户端化](#6-stage--session--持久化的客户端化)
7. [双端共存策略](#7-双端共存策略)
8. [分阶段实施计划](#8-分阶段实施计划)
9. [建议的代码组织](#9-建议的代码组织)
10. [风险、限制与不建议做的事](#10-风险限制与不建议做的事)
11. [未决问题清单](#11-未决问题清单)
12. [P0 客户端探针骨架（v7.0.0 实装）](#12-p0-客户端探针骨架v700-实装)

---

## 1. 动机

### 1.1 当前的部署门槛

`ctt-health-display` v6.x 是**双端 mod**：

- 客户端入口 `CttHealthDisplay`（HUD / 输入 / ModMenu）
- 服务端入口 `CttStatsServer`（Mixin 计分板 / 9 层归属 / NBT 持久化 / S2C 广播）

任何"伤害 / 击杀 / 承伤"统计都由**服务端权威算出**，再通过 `StatsSnapshotPayload` 1Hz 下发。这意味着：

| 部署形态 | 体验 |
|---|---|
| 集成单机（host 自开 LAN） | 满血，数据直读 server 静态字段 |
| 专用服务器装本 mod + 客户端装本 mod | 满血，走 S2C payload |
| **公服 / 朋友的 vanilla 服务器** | **HUD 能正常显示心数（计分板可见），但伤害统计、K 表、L 面板全是 0** |
| 客户端装、服务端没装 | 同上 |

第三种场景是 CTT 玩家的**主流处境**——绝大多数玩家加入的是别人办的服务器，没有权限装服务端 mod。当前 mod 在这一场景下"只有壳子，没有数据"。

### 1.2 客户端其实"看得见"伤害

`CLIENT_SIDE_DATA_REFERENCE.md` §2、§5 已经系统证明了：

- **`DamageShower` 实体**（地图飘字数字粒子）的 `text` / `background` / 实体上的 `DamageShower` scoreboard 值，都通过 vanilla S2C 完整下发到客户端。
- **`RedHearts` / `*DMG` / `RedDamageTook` / `BlackDamageTook`** 等关键 objective 的 score 更新，全部走 `ScoreboardScoreUpdateS2CPacket`，客户端缓存里实时可见。
- **`PlayerID = UUID[0]`** 在客户端可以同样重建，作为攻击者归属的主键。

也就是说：**当前服务端在 `ScoreboardUpdateMixin` 里看到的几乎所有"伤害事件"，客户端都能看到一份**——只是少了几个无法跨网络同步的实体 tag。

### 1.3 不替代、是补充

本提案**不是**让"客户端版"替代现有服务端实现。而是：

- 让 mod 多一条**降级数据路径**，在没有服务端 mod 的环境下也能输出有意义（即使精度略低）的统计。
- 当双端都存在时，**优先服务端**（精度更高）；客户端版退化为"自我校验来源"。
- 长期目标：让 `ctt-health-display` 既能服务"私服玩家自办联机"，也能服务"公服日常玩家"。

---

## 2. 现状回顾

### 2.1 服务端伤害统计调用栈（简化）

```
地图 datapack 写计分板（damage.mcfunction）
  ↓
ServerScoreboard#updateScore
  ↓ Mixin
ScoreboardUpdateMixin#ctt$onScoreUpdate（按 objective 名分派）
  ├─ "RedHearts" + delta<0 → DamageProbe.recordFromRedHearts          [v6.6.0 默认主源]
  ├─ "DamageShower"        → DamageProbe.record                       [备源 / 历史]
  ├─ *DMG (9 种)           → AttackerProbe.record                      [归属诊断]
  ├─ damage_dealt criterion → PlayerHitLog.record                      [近战兜底]
  └─ used:carrot / bow     → PlayerFireLog.record / recordBow          [远程兜底]
  ↓
AttackerProbe.attribute(...) 9 层归属（L1..L9）
  ↓
PlayerDamageStats.add / addUnclassified / addCarry
PlayerKillStats.kill / assist
PlayerTakenStats.addTaken
  ↓
END_SERVER_TICK
  ├─ DamageProbe.flushTick               (text_display 解析 victim)
  ├─ PlayerTakenProbe.tickEnd            (DamageTook scoreboard 累加)
  ├─ StatsPersistenceManager.onTickEnd   (节流写 ctt_stats.dat)
  └─ StatsSnapshotBroadcaster.tick       (≈1Hz 推 StatsSnapshotPayload)
```

### 2.2 关键依赖（决定客户端能否复刻）

| 服务端依赖 | 客户端能否替代 | 备注 |
|---|---|---|
| `Scoreboard#updateScore` Mixin | ✅ 替换为 `ClientPlayNetworkHandler#onScoreboardScoreUpdate` Mixin | 客户端的对应入口 |
| 实体 `getCommandTags()` 含 `"E"` / `"CTTAll"` / `"Boss"` | ❌ 不可同步 | **最大限制**，需要计分板启发式替代 |
| `WeaponDamageRegistry`（玩家持物 → 武器类型） | ⚠ 部分可替代 | 自己持物全可见；他人仅手持槽 + 装备槽，不能扫背包 |
| `PlayerInventoryIndex`（5 tick 扫所有玩家背包） | ⚠ 同上 | 客户端扫他人背包 = 不可能；只能扫自己 + 监听他人装备包 |
| `ServerWorld#getEntity(UUID)` 反查 victim | ✅ `ClientWorld#getEntity` | 完全等价 |
| `PlayerID` 反查 attacker | ✅ 完全可重建 | UUID[0] 算法跨端一致 |
| `AttackerResolver.scanMarkers`（按 marker tag 优先级） | ⚠ 降级 | 客户端拿不到 marker 的具体 tag，只能按"实体类型 + 距离 + 是否带 PlayerID" 启发 |
| `damage_dealt` / `used:carrot_on_a_stick` 等 criterion | ✅ 全可读 | 计分板 score 更新本来就广播 |
| `END_SERVER_TICK` 时机 | ✅ 用 `ClientTickEvents.END_CLIENT_TICK` | 客户端 tick 频率与服务端基本一致（除 lag 时） |
| 服务端持久化 `world/data/ctt_stats.dat` | ❌ 客户端无世界目录写权限 | 必须改写到 `config/` 或 `cache/` |

---

## 3. 可行性结论速览

### 3.1 完全可复刻（无损耗）

- ✅ **每个玩家 4 层心数 / 法力 / 货币 / 装备的实时显示**（HUD 早就这么做了）
- ✅ **Stage / Floor / Tier / Boss / Shop / GameOver 状态**（`#X CTT` 假玩家全部可读）
- ✅ **GameID 跳变检测 → session 边界**
- ✅ **5 秒滑窗 DPS**（自己维护 ring buffer）
- ✅ **关卡级分桶（StageKey 桶）**
- ✅ **治疗识别**（DamageShower 绿底背景 + HealDMG 文本数字）

### 3.2 可复刻、精度按方向不同

> **关键设计原则**：地图侧"对怪伤害真值"在多重 modifier（Tier 缩放、元素 RNG、Random ElementMatch、装甲减免）之下本就是模型计算结果不是物理量；**玩家在屏幕上看到的红色飘字数字（DamageShower）才是"输出感"的真理**。所以本提案区分两条路径，分别选最合适的源——详见 §4.3。

#### 3.2.1 对怪物造成的伤害（K 表"输出"列、DPS、单击最大）

- ✅ **总伤害 / 单击最大 / 事件次数**（**主源 = DamageShower 粒子事件流**）
  - DamageShower 的值 = post-armor pre-cascade 的 `Damage`（`damage.mcfunction:1028`），即玩家屏幕看到的飘字数字。**这是与玩家视觉感受 1:1 对应的指标**。
  - 损耗：`#ServerLag CT = 1` 帧不生成 + 全清；全场 ≤10 上限（爆发 AOE 时部分粒子被随机 kill）；同 tick 多怪 ≤1.5m 贴近时 1.5m 邻域绑定可能错挂 victim。
  - 兜底：用 4 层 `BlueHearts/BlackHearts/SoulHearts/RedHearts` 的负 delta 总和（与 victim 逐帧绑定）做 ServerLag / cap 满帧的填补，详见 §4.3。
- ⚠ **攻击者归属**：从 9 层降到 7~8 层。损失主要在：
  - L2/L5 的 vanilla `damage_dealt` 仍能用，但**不能精确知道是谁打的怪**——criterion stat 更新只告诉你"这个玩家累计造成 X 伤害"，并不直接关联 victim。需要靠"距离最近的受伤怪 + tick 同步"启发。
  - L3/L4 的 marker / projectile 归属：客户端能看到 marker 实体存在和它的 `PlayerID` score，但**看不到 marker 的 tag**（如 `AK47ShootAI`/`NSLaserAI`），所以无法用 tag 优先级排序，只能按距离。
  - **同 tick 多怪同时受击 + 多玩家同时输出**场景下，L2/L3/L4 精度大幅下降——典型多人混战分输出场景预估精度 ~80%。
- ⚠ **L1 武器守卫**：自己的武器 100% 可见（背包 + 装备槽），他人**只能看主手 + 装备槽**，不能扫背包。"他人在背包里有武器但没拿在手上"会导致 L1 误判（落到 L9_NONE，方向正确但归属丢失）。

#### 3.2.2 玩家自己承受的伤害（K 表"承伤"列、死亡分析）

- ✅ **红心承伤累计**：`RedDamageTook` 是地图原生累加器（`damage.mcfunction:1056`），客户端直接 delta 累积即可。
- ✅ **黑心承伤累计**：`BlackDamageTook` 同理（line 1038）。
- ⚠ **蓝心 / 灵魂心承伤**：地图**没有** `BlueDamageTook` / `SoulDamageTook` 累加器，必须靠 `BlueHearts` / `SoulHearts` 的负 delta 自行累计（中间被治疗补回会破坏纯 delta 累加，需要在 HealDMG 事件帧扣除回填量）。
- ✅ **治疗识别**：`RedHearts` 正向 delta（且同 tick 该玩家 `HealDMG > 0`）= 接受了多少治疗。

#### 3.2.3 击杀 / 助攻判定

- ⚠ 击杀：依赖归属链上溯，归属精度下降时少量误判（参考 §3.2.1 的归属精度估计）。
- ⚠ 助攻：从 contributors 累计读，受 attribution 链精度影响。
- ⚠ Coffin 类实体（`tag=Coffin`，RedHearts ≤ 0 但不死）客户端无法直接区分，只能用启发"RedHearts ≤ 0 但持续多 tick 没收到 EntitiesDestroy → 是 Coffin"。

### 3.3 完全无法复刻

- ❌ **基于实体 tag 的精确分类**（`tag=Boss` vs `tag=E` vs `tag=Coffin` vs `tag=NPC`）。客户端只能用"该实体在 `MaxHP`/`RedHearts` 等 objective 上的 score 是否合理"+ 启发反推。
- ❌ **服务端权威的 `Atk` / `Hit` / `PlayerHurtSound` 瞬态 tag 链**——这些是 datapack 内部"刚刚发生过攻击"的标志位，每 tick 末 reset，vanilla S2C 不同步。
- ❌ **跨服持久化合并**——客户端只能写本地，跨服归档需要服务端 IP hash 区分（见 §11.8）。
- ❌ **修复地图 bug**——客户端无 OP 权限，所有"修复"只能停留在显示层（mod 自己重算后覆盖渲染）。

### 3.4 客户端独有的优势

- ⭐ **L0_SELF_INPUT 100% 可信**：自己的攻击键按下、swing 动画、弓拉满释放，客户端比服务端更早知道。**自己的伤害归属精度反而比服务端版还高**（不依赖 datapack tag 时序）。
- ⭐ **零服务端开销**：所有逻辑跑在玩家自己的 PC，不影响别人。
- ⭐ **不依赖私有协议**：mod 升级不需要服主配合升级服务端版本。
- ⭐ **粒子值 = 玩家视觉感受**：K 表的"造成伤害"与玩家屏幕看到的飘字 1:1 对应，不会出现"我看到 999 但 K 表只算 250（被吸收 749）"这种困惑。

---

## 4. 核心数据通路设计

### 4.1 入口 Mixin

把现有 `server/mixin/ScoreboardUpdateMixin` 的思路复刻到客户端入口包。建议新增：

```java
// 新文件：src/main/java/com/ctt/healthdisplay/client/mixin/ClientScoreboardScoreMixin.java
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientScoreboardScoreMixin {
    @Inject(method = "onScoreboardScoreUpdate", at = @At("HEAD"))
    private void cttHd$onScore(ScoreboardScoreUpdateS2CPacket p, CallbackInfo ci) {
        ClientDamageProbe.handleScoreUpdate(
            p.scoreHolderName(),  // 持有者：玩家名 / 假玩家名 / UUID 字符串
            p.objectiveName(),    // 例如 "RedHearts" / "MeleeDMG"
            p.score()             // 新值（绝对值，不是 delta）
        );
    }

    @Inject(method = "onScoreboardScoreReset", at = @At("HEAD"))
    private void cttHd$onScoreReset(ScoreboardScoreResetS2CPacket p, CallbackInfo ci) {
        ClientDamageProbe.handleScoreReset(p.owner(), p.objectiveName());
    }
}
```

> **注意**：方法名以 Yarn 1.21.4 当前映射为准，编码时请在 IDE 中确认。`ScoreboardScoreUpdateS2CPacket` 的字段访问器在不同小版本可能是 record accessor (`p.score()`) 也可能是 getter (`p.getScore()`)。

### 4.2 ClientDamageProbe（客户端版 DamageProbe）

```java
public final class ClientDamageProbe {
    // 与服务端 DamageProbe 几乎对称的 API；但分派顺序按"事件流方向"排列
    public static void handleScoreUpdate(String holder, String objective, int newValue) {
        // 0) #X CTT 假玩家更新 → 转发给 ClientStageProbe
        if (StageProbeClient.isStageHolder(holder, objective)) {
            StageProbeClient.observe(holder, objective, newValue);
            return;
        }

        // 1) DamageShower（实体 UUID 持有）→ 对怪输出主源（详见 §4.3 + §4.6）
        //    DamageShower 走"实体 spawn → text=DamageShower score → 1.5m 邻域绑 victim"的事件流，
        //    必须配合 EntitySpawn / EntityTrackerUpdate / EntitiesDestroy 一起处理，
        //    所以这里只做注册标记，主逻辑在 ClientDamageShowerStream。
        if ("DamageShower".equals(objective)) {
            ClientDamageShowerStream.observeScore(holder, newValue);
            return;
        }

        // 2) 4 层心数 delta → 玩家承伤主源（详见 §4.3）
        //    顺序：BlueHearts → BlackHearts → SoulHearts → RedHearts（与 damage.mcfunction 串联级联同序）
        if (ClientHeartsTracker.isHeartObjective(objective)) {
            int delta = ClientScoreDeltaTracker.observeDelta(holder, objective, newValue);
            if (delta < 0) {
                ClientHeartsTracker.recordHeartLoss(holder, objective, -delta);
            } else if (delta > 0) {
                ClientHeartsTracker.recordHeartGain(holder, objective, delta);
            }
            return;
        }

        // 3) RedDamageTook / BlackDamageTook → 玩家承伤累加器（地图原生提供）
        if ("RedDamageTook".equals(objective) || "BlackDamageTook".equals(objective)) {
            int delta = ClientScoreDeltaTracker.observeDelta(holder, objective, newValue);
            if (delta > 0) ClientPlayerTakenStats.add(holder, objective, delta);
            return;
        }

        // 4) *DMG (9 种) → 归属诊断信号
        //    ⚠ 重要：地图侧每 tick 末 `scoreboard players reset @e Damage`（damage.mcfunction:1057），
        //    所以这里看到的 *DMG newValue 永远是"刚刚记下来的瞬时增量"——
        //    只在 newValue>0 时记录，遇到 reset 走 onScoreReset 不要把 0 回传。
        if (ClientAttackerProbe.isTrackedDmgObjective(objective)) {
            if (newValue > 0) ClientAttackerProbe.record(objective, holder, newValue);
            return;
        }

        // 5) damage_dealt criterion → 客户端 PlayerHitLog
        if (ClientPlayerHitLog.isDamageDealtStat(holder, objective)) {
            ClientPlayerHitLog.record(holder, objective, newValue);
            return;
        }
        // 6) carrot_on_a_stick / bow stat → 客户端 PlayerFireLog
        // ...
    }
}
```

**与服务端版的关键区别**：

1. `holder` 是字符串而非 `ScoreHolder` 对象。客户端的 `ScoreboardScoreUpdateS2CPacket` 直接给字符串。
2. 反查 victim 实体时用 `MinecraftClient.getInstance().world.getEntity(uuid)`，而非遍历 `server.getWorlds()`。
3. **不依赖** `victim.getCommandTags().contains("E")`——必须用 `MaxHP > 0` + `RedHearts ≥ 0` 这种"计分板特征"启发判定。
4. **`*DMG` / `Damage` 永远不要靠"轮询当前值"读**——这些 score 在 `damage.mcfunction` 末尾每 tick 都被 reset，必须以**事件流（packet 到达瞬间）**为唯一切入口。
5. **`HealDMG` 单独一档**：HealDMG > 0 那一帧 `RedHearts` 会上升 = 治疗事件，不计入承伤；详见 §4.3。

### 4.3 双轨数据源策略（核心设计）

**地图侧两类伤害事件性质不同，必须用两套不同的源**：

| 事件类型 | 主源 | 兜底源 | 理由 |
|---|---|---|---|
| **玩家对怪造成伤害**（K 表"输出"列、DPS） | **DamageShower 粒子值** | 4 层心数（Blue+Black+Soul+Red）合并 delta | DamageShower = post-armor pre-cascade 的 `Damage`（`damage.mcfunction:1028`），与玩家屏幕飘字数字 1:1；而怪物的 4 层心数是后端记账，玩家无视觉对应 |
| **玩家自己承受伤害**（承伤列、死亡分析） | **4 层心数 delta**（BlueHearts/BlackHearts/SoulHearts/RedHearts 负向） | `RedDamageTook` / `BlackDamageTook` 累加器交叉验证 | 玩家心数变化即玩家屏幕看到的真实损耗；DamageShower 在玩家身上同样会出，但由于"4 层级联吸收"特性，单 DamageShower 数字会大于实际扣的某层心数，不能直接累加 |
| **治疗事件**（HUD 治疗提示、统计排除） | DamageShower 绿底 + HealDMG 文本 | RedHearts 正向 delta + 同 tick HealDMG > 0 | 二者必须双重确认，避免把"被吸收回血"误判成被治疗 |

#### 4.3.1 为什么"对怪伤害"必须走粒子而不是心数？

地图侧的级联吸收：

```mcfunction
@e[scores={Damage=1..,BlueHearts=1..}] BlueHearts -= Damage   ← 蓝心先吸
@e[scores={Damage=1..,BlackHearts=1..}] BlackHearts -= Damage  ← 然后黑心
@e[scores={Damage=1..,SoulHearts=1..}] SoulHearts -= Damage    ← 然后灵魂
@e[scores={Damage=1..}] RedHearts -= Damage                    ← 最后红心
```

→ 一次 `Damage=300` 的输出，可能被分解成 `BlueHearts -100 / BlackHearts -100 / SoulHearts -100 / RedHearts 0`，**屏幕飘字仍是 300**。如果客户端只看 RedHearts delta，会把这 300 完全漏算。

DamageShower 直接复制的就是 `Damage` 这个未级联的原始值，所以：

```
对怪输出累计 = Σ DamageShower(victim).text  对所有 background=红色 的事件
```

#### 4.3.2 为什么"对己承伤"必须走心数而不是粒子？

玩家自己也在 CTTAll 集合里，DamageShower 同样会落在玩家身上。但：

- 玩家**关心**"我刚才掉了多少血/盾"——这正是心数 delta。
- DamageShower 数字会被装备 `DR%` / `Damage Reduction` modifier 修正前后混淆——同一帧粒子上飘出的 50 可能其实只扣了 30 心数（地图代码里有 modifier 链）。
- 心数 delta 与玩家 HUD 显示完全一致，无歧义。

唯一注意：**治疗回血**会让 RedHearts 反向 delta（+），必须在 HealDMG > 0 同 tick 把它视为治疗而不是"回血等于负承伤"。

#### 4.3.3 兜底链

主源失败时回落到兜底源的判据：

| 条件 | 主源 | 兜底动作 |
|---|---|---|
| `#ServerLag CT = 1` 帧 | DamageShower 全清 | 改用 victim 4 层心数 negative delta 估算"对怪输出" |
| 全场已有 10 个 DamageShower | 新粒子被随机 kill | 同上：补回 4 层心数 delta - 已观察到的粒子和 |
| 1.5m 邻域同时 ≥2 个怪（绑定歧义） | 多个 DamageShower 抢一个 victim | 用最近一只 + 标"模糊"flag，L 面板显示置信度 |
| `RedDamageTook` 累加值与心数 delta 不一致（差超 5%） | 主源（心数 delta） | 视作信号源损坏，记录 warning + 切到累加器读数 |

#### 4.3.4 实现配置

```
ClientModConfig.damageDealtSource: PARTICLE_PRIMARY (默认) | HEARTS_PRIMARY | DUAL_AVERAGE
ClientModConfig.damageTakenSource: HEARTS_PRIMARY (默认) | TOOK_OBJECTIVE_PRIMARY
ClientModConfig.developerMode:    false                    [开后 L 面板显示主源/兜底源差值]
```

> **不要做 `useRedHeartsTally` 这种简单二选一开关**——这是服务端旧设计，客户端版必须双轨同时维护、并行写入两个 `PlayerDamageStats` 记账，主源失败时无缝切换。

### 4.4 victim 反查（无 `tag=CTTAll` 时的替代）

服务端代码：

```java
foundWorld.getOtherEntities(textDisplay, searchBox, c ->
    !(c instanceof DisplayEntity.TextDisplayEntity)
    && !c.getCommandTags().contains("DamageShower")
    && c.getCommandTags().contains("CTTAll")  // ← 客户端拿不到
);
```

客户端版必须替换 `CTTAll` 检查。建议：

```java
private static boolean looksLikeCttVictim(Entity e, ClientWorld world) {
    if (e instanceof DisplayEntity.TextDisplayEntity) return false;
    Scoreboard sb = world.getScoreboard();
    ScoreboardObjective maxHp = sb.getNullableObjective("MaxHP");
    if (maxHp == null) return false;
    int mh = sb.getOrCreateScore(e, maxHp).getScore();
    if (mh <= 0) return false;            // 没 MaxHP 一定不是 CTTAll
    // 玩家自己也是 CTTAll 一员；伤害粒子有可能落在玩家身上（PvP / 友伤）
    return true;
}
```

**精度差异**：服务端的 `CTTAll` 判定是确定性的；客户端的"`MaxHP > 0`"是必要条件不是充分条件——理论上某些 NPC 也可能有 MaxHP 但不是 CTTAll。实测中应该是 CTTAll 的超集。可以接受。

### 4.5 攻击者反查（PlayerID）

完全照搬服务端 `AttackerResolver.lookupPlayerByPlayerId` 的逻辑，但用 `ClientWorld#getPlayers()`：

```java
public static PlayerEntity lookupPlayerByPlayerId(ClientWorld world, int playerId) {
    for (PlayerEntity p : world.getPlayers()) {
        int pid = (int) (p.getUuid().getMostSignificantBits() >> 32);
        if (pid == playerId) return p;
    }
    return null;
}
```

`ClientWorld#getPlayers()` 包含所有当前可见的玩家（含远程玩家）。**完全可信**——PlayerID 算法两端一致。

### 4.6 ClientDamageShowerStream（粒子事件流）

这是"对怪输出"主源的核心实现。地图侧 `damage.mcfunction:1062-1115` 决定了客户端能观测的事件序列：

```
帧 N      packet              客户端动作
─────     ──────────────      ─────────────────────────────────
N        EntitySpawnS2C       text_display 出现，先按 1.5m 邻域候选 victim
                              （scoreboard 上还没 DamageShower score）
N        EntityTrackerUpdate  text 字段下来 → 此时确认它是 DamageShower
                              （text 模板含 "objective":"DamageShower"）
N        ScoreboardScoreUpd.  DamageShower=value → 这就是飘字数字本身
N+1      EntityTrackerUpdate  background 从默认 → -65536（红=damage） / -16515325（绿=heal）
N+1      EntityTrackerUpdate  see_through:1b（damage）追加确认
N..N+L   teleport / position  粒子缓慢上飘
N+L      EntitiesDestroy      粒子被 kill（生命周期默认 ~30~60 tick，或被 cap-10 随机踢）
```

实现要点（Fabric 1.21.4）：

```java
public final class ClientDamageShowerStream {
    /** 候选粒子（score 还没到/background 还没到） */
    private static final Map<UUID, PendingShower> pending = new ConcurrentHashMap<>();
    /** 已确认绑定 victim 的活跃粒子（用于跨 tick 重定向） */
    private static final Map<UUID, ActiveShower> active = new ConcurrentHashMap<>();

    record PendingShower(UUID id, BlockPos spawnPos, long tickAtSpawn,
                         List<UUID> nearbyVictimsAtSpawn,
                         OptionalInt scoreObserved,
                         OptionalInt backgroundObserved) {}

    record ActiveShower(UUID id, UUID victim, int amount, boolean heal,
                        long tickConfirmed, double confidence) {}

    /** 由 ClientEntitySpawnMixin / EntityTrackerUpdateMixin 调用 */
    public static void onTextDisplaySpawn(UUID id, BlockPos pos, ClientWorld world) {
        // 在 spawn 帧立即抓 1.5m 内 CTTAll 候选（顺位会写进 active）
        List<UUID> candidates = world.getEntitiesByClass(LivingEntity.class,
            new Box(pos).expand(1.5),
            e -> ClientCttHeuristic.looksLikeCttVictim(e, world)
        ).stream().map(Entity::getUuid).toList();
        pending.put(id, new PendingShower(id, pos, world.getTime(),
            candidates, OptionalInt.empty(), OptionalInt.empty()));
    }

    /** 由 §4.2 ClientDamageProbe.handleScoreUpdate("DamageShower", uuid, value) 调用 */
    public static void observeScore(String holder, int value) {
        UUID id = tryParseUuid(holder); if (id == null) return;
        PendingShower p = pending.get(id);
        if (p == null) return;                          // 没 spawn 事件 → 远 view 距外不计
        pending.put(id, withScore(p, value));
        tryConfirm(id);
    }

    /** 由 EntityTrackerUpdateMixin 看到 background 字段时调用 */
    public static void observeBackground(UUID id, int bg) {
        PendingShower p = pending.get(id);
        if (p == null) return;
        pending.put(id, withBackground(p, bg));
        tryConfirm(id);
    }

    /** score 与 background 都到位 → 写入 active，分派给 PlayerDamageStats */
    private static void tryConfirm(UUID id) {
        PendingShower p = pending.get(id);
        if (p.scoreObserved.isEmpty() || p.backgroundObserved.isEmpty()) return;

        int amount = p.scoreObserved.getAsInt();
        int bg = p.backgroundObserved.getAsInt();
        boolean heal = (bg == -16515325);
        boolean dmg  = (bg == -65536);
        if (!heal && !dmg) return;                      // 还没确认 → 等下一帧

        // 1.5m 邻域绑定：找最优 victim（最近的活体 CTTAll 怪；若不止一个 → 标 confidence < 1.0）
        UUID victim = pickBestVictim(p);
        double conf = p.nearbyVictimsAtSpawn.size() == 1 ? 1.0 :
                      p.nearbyVictimsAtSpawn.size() == 0 ? 0.5 : 0.7;

        active.put(id, new ActiveShower(id, victim, amount, heal,
            MinecraftClient.getInstance().world.getTime(), conf));
        pending.remove(id);

        if (heal) {
            ClientHealEventBus.dispatch(victim, amount, conf);
        } else {
            ClientAttackerProbe.recordDamageDealt(victim, amount, conf);  // 走归属链
        }
    }

    /** 由 ClientPlayerListenerMixin 在 EntitiesDestroyS2CPacket 时调用 */
    public static void onDestroy(UUID id) {
        pending.remove(id);
        active.remove(id);
    }

    /** 由 ScoreboardObjectiveUpdate 监听到 #ServerLag CT = 1 时调用 */
    public static void onServerLag() {
        // 地图代码：execute if score #ServerLag CT matches 1.. run kill @e[tag=DamageShower]
        // 此时所有粒子立即被清，pending 中"已观察 score 但未观察到 destroy 包"的要主动丢弃
        pending.clear();
        active.clear();
        ClientAttackerProbe.flagServerLagFrame();        // 触发兜底：本帧改用心数 delta
    }
}
```

**关键边界**：

- **必须等到 background 字段更新**才能区分"伤害 vs 治疗"。Spawn 帧只有 score=0 和默认 background；§5 表 §C-D 已记录这个 1 tick 延迟。
- **`#ServerLag CT = 1` 帧**全 kill，本帧的 4 层心数 delta 是兜底——`onServerLag()` 之后让 `ClientAttackerProbe` 标记本帧为 fallback frame。
- **粒子全场 ≤10 cap**：当 spawn 速率超过 cap，旧粒子被 random kill。客户端**会丢失**这部分事件——只能依赖兜底心数 delta 补差。
- **Spawn pos 偏移**：地图代码用 `~±1 ~1 ~` 等四个方向循环偏移粒子位置（`#DamageNumbers CTT` mod 4），不是 victim 实际位置。所以"1.5m 邻域"用 spawn pos 计算时，需要把这 ≤2m 的偏移考虑进去——实际搜索半径建议 **3.0m**。

---

## 5. 归属层映射（关键章节）

下表是服务端 9 层归属栈（`AttackerProbe.Layer`）到客户端版的映射。这是本提案最关键的设计决策。

> **本节归属链的输入数据，全部来自 §4.3 双轨主源**：粒子事件（对怪 amount + victim）由 §4.6 `ClientDamageShowerStream` 推入；玩家承伤心数事件由 §4.2 第 2 档 `ClientHeartsTracker` 推入。归属链不直接读 score，所以下表所有"客户端可用信号"列默认是在"已经知道 amount + victim"前提下排查 attacker。

### 5.1 总览表

| 层 | 服务端依据 | 客户端可用信号 | 精度 | 客户端实现要点 |
|---|---|---|---|---|
| **L1 WEAPON_MATCH** | 玩家持武器（含背包）+ 10s 内开火 + 40m 内 victim | 自己：100% 可见；他人：只能看主手 + 装备槽 | ⭐⭐⭐ 自己更高 / 他人下降 | 自己的攻击事件作为最高优先信号；他人退化为"主手匹配 + 距离" |
| **L2 STAT_TICK** | 本 tick `damage_dealt` 类 stat 增量 | ✅ 客户端能 100% 监听 stat 增量 | ⭐⭐ 几乎一致 | 关键：stat 更新到达时反查"本 tick 哪个怪 RedHearts 跌了" |
| **L3 MARKER_NEAR** | victim 3m 内 marker/projectile 的 PlayerID | ✅ 实体可见、PlayerID 可读；但**marker 的 tag 不可见** | ⭐ 中下 | 按距离排序，无 tag 优先级；projectile (vanilla 弓箭) 用 `ProjectileEntity#getOwner()` 直接取 |
| **L4 MARKER_FAR** | 同上 40m 半径 | ✅ 同 L3 | ⭐ 中下 | 同 L3 |
| **L5 STAT_WINDOW** | 近 5 tick stat 窗口 | ✅ 客户端版 PlayerHitLog 可独立维护 | ⭐⭐ 几乎一致 | 直接复刻 |
| **L6 FIRE_WINDOW** | 近 20 tick 右键 + 40m + Tier 打分 | ⚠ 自己的右键最强；他人需要 `used:carrot_on_a_stick` stat 推断 | ⭐⭐ 自己 / ⭐ 他人 | 自己用 client `UseItemCallback`；他人靠 stat 增量监听 |
| **L7 BOW_RELEASE** | 近 2s `used:bow` stat | ✅ 客户端能监听该 stat | ⭐⭐ 一致 | 直接复刻 |
| **L8 LAST_HITTER** | victim×T 续归属 (20s TTL) | ✅ 仅依赖前层结果 | 同前层 | 直接复刻 |
| **L9 NONE** | 兜底 | ✅ | ⭐⭐⭐ | 直接复刻 |
| **L9 FILTER** | 黑名单数值 | ✅ 客户端配置同样有效 | ⭐⭐⭐ | 直接复刻 |
| **L9 HEAL** | 绿色 background 粒子 | ✅ 客户端能直接读 background | ⭐⭐⭐ | 直接复刻 |

### 5.2 客户端独有的"L0_SELF_INPUT"层（强化）

由于客户端能 100% 可信地知道"我刚做了什么动作"，建议在客户端版本中**新增 L0 层**，置于 L1 之前：

```
L0_SELF_INPUT  自己的客户端输入 + 1 tick 内 victim 受伤
  条件：
    - client.player 在最近 1-2 tick 内有 attack key / use key 触发，且
    - victim 在 attack 那一帧距离 ≤ 6m（近战）/ 60m（远程，如果当前持远程武器）
  优先级：最高（高于服务端 L1）
```

这一层在**只有自己装 mod 时**最有用——其他玩家的输入完全不可见，他们的归属仍然要走 L1~L8 启发；但自己的伤害可以**精确归到自己头上**。

### 5.3 server `Atk` / `Hit` tag 链的损失

服务端 `AttackerProbe` 重度依赖地图 datapack 在 `damage.mcfunction` 内部打的瞬态 tag：

- `tag=Atk`：本 tick 该玩家发起了攻击
- `tag=Hit`：本 tick 该实体受了来自玩家的攻击
- `tag=PlayerHurtSound`：本 tick 该玩家受到了来自怪物的攻击

这些 tag 在 vanilla S2C 协议里**完全不同步**。客户端版必须接受这一信息缺失。

**实践影响**：
- 服务端 L2_STAT_TICK 利用"本 tick 哪个 victim 有 `tag=Hit`"做精确锁定。
- 客户端 L2_STAT_TICK 退化为"本 tick 哪个 victim 触发了 DamageShower（§4.6） / 哪个 victim 4 层心数 delta 同号"——精度足够 90%+ 场景，但同 tick 多怪同时受击时会有少量错挂。

**Cross-validation 兜底**：当 L2 和 L1（`damage_dealt` stat 增量）同 tick 都到达且**指向不同 victim** 时，优先信任 §4.6 的粒子事件——`damage_dealt` stat 是"本玩家累计"，缺乏 victim 维度；`DamageShower` 同帧的 1.5m 邻域是真正"victim ↔ 数额"的连结。**这与服务端的优先级正好相反**（服务端有 `tag=Hit` 做精确锁定，所以那边 stat tick 优先）。

### 5.4 `WeaponDamageRegistry` 的部分降级

服务端 `WeaponDamageRegistry` 维护"武器 → 它能造哪类伤害"的映射，靠 `PlayerInventoryIndex` 每 5 tick 扫所有玩家**整个背包 + 装备 + 主手**。

客户端能扫的：

| 玩家 | 主手 | 副手 | 装备槽 | 快捷栏 | 主背包 |
|---|---|---|---|---|---|
| 自己 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 他人 | ✅ | ✅ | ✅ | ❌ | ❌ |

> 他人的主手 / 副手 / 装备槽通过 `EntityEquipmentUpdateS2CPacket` 同步，客户端 `LivingEntity#getEquippedStack(EquipmentSlot)` 可读。

**改造建议**：

1. 把 `PlayerInventoryIndex.Snapshot` 拆成两层：`heldOnly`（手持槽，所有玩家都能填）和 `fullBag`（仅自己）。
2. `AttackerProbe` 的 L1 守卫规则在客户端版改为：
   - 候选玩家是自己 → 用 `fullBag` 守卫（与服务端等同）。
   - 候选玩家是他人 → 仅用 `heldOnly` 守卫；漏检（"他在背包里"）按 L1 失败处理，落到 L6/L7/L8。
3. 副作用：会有一定比例的"明明是他打的但归不到他头上"——这部分会自然下沉到 L9_NONE 桶，不会**错挂**到别人头上，方向正确。

---

## 6. Stage / Session / 持久化的客户端化

### 6.1 ClientStageProbe

服务端 `StageProbeServer` 每 tick 读 14 个 holder 算 `StagePayload` 然后下发。客户端版只需把同一组 holder 在客户端 `Scoreboard` 缓存上读一遍：

```java
public final class ClientStageProbe {
    public static StageLocation.Snapshot tick(MinecraftClient mc) {
        Scoreboard sb = mc.world.getScoreboard();
        int tier = readScore(sb, "#Tier", "CTT");
        int floor = readScore(sb, "#Floor", "CTT");
        int boss = readScore(sb, "#Boss", "CTT");
        // ... 完全镜像 StageProbeServer.tick 的 14 行 readScore
        boolean hasCttTag = false; /* ⚠ 自己有没有 tag=CTT 也读不到 ——
                                       退而求其次：用 team=PVE 反查（CLIENT_SIDE_DATA_REFERENCE §9） */
        Team pve = sb.getTeam("PVE");
        hasCttTag = pve != null && pve.getPlayerList().contains(mc.player.getName().getString());
        // ...
    }
}
```

**精度差异**：

- 服务端用 `player.getCommandTags().contains("CTT")` 判断玩家是否在局；客户端用 `team=PVE` 替代——`gamestart.mcfunction` 给 `tag=CTT` 玩家加入 `team=PVE`，所以两者**几乎等价**。
- ⚠ **TempNoTeam 周期**：地图侧某些动画/特效会临时把玩家从 `PVE` 移除（`team join TempNoTeam @s` → 几 tick 后还原），如果这一帧客户端读到 `team≠PVE` 误以为玩家"出局"，会丢一帧的事件。
  - 缓解：客户端用**滞后判定**——`hasCttTag` 状态变 `false` 后 **保持 20 tick (1s)** 才确认离场；连续 20 tick 都不在 PVE 才认为真出局。
  - 也可双重判定：`team=PVE` **OR** 玩家最近 5 tick 内 `RedHearts > 0` 有更新（活跃心数 = 在局）。
- 兜底：`#CTT GameID` > 0 是局存在的全局信号；玩家持有 `Lives ≥ 1` 是另一个候选（局结束时 `Lives` 被清）。

### 6.2 ClientSession（局边界）

服务端 `StageBoundaryDispatcher` 监听 `#CTT GameID` 跳变 → 触发 session 结束 → 归档历史。客户端可以**完全照搬**这个逻辑：

```java
public final class ClientSession {
    private static int lastGameId = 0;
    
    public static void onGameIdUpdate(int newGameId) {
        if (lastGameId == 0 && newGameId > 0) startSession(newGameId);
        else if (newGameId != lastGameId && lastGameId > 0) {
            archiveSession(lastGameId);
            startSession(newGameId);
        }
        lastGameId = newGameId;
    }
}
```

GameID 通过 `#CTT` 假玩家在 `GameID` objective 上的 score 同步——客户端完全可见。

### 6.3 持久化路径

服务端把 stats 持久化到 `world/data/ctt_stats.dat`（同地图存档）。客户端没有这个权限，建议：

```
.minecraft/config/ctt-health-display/
  ├── stats/
  │   ├── current.nbt         # 当前 session（崩溃恢复用）
  │   └── history/
  │       ├── 2026-04-26_g42.nbt  # 局结束归档（按日期 + GameID）
  │       └── 2026-04-26_g43.nbt
  └── settings.json           # 现有 ModConfig
```

复用现有 `PlayerDamageStats.toNbt` / `fromNbt`，只是 I/O 路径改到客户端 config 目录。文件不大（一个 session 大约 < 10KB）。

**跨服合并**问题留给 §11.

### 6.4 游戏模式 flags（影响伤害真实值的元数据）

地图侧有一组全局 flags 会影响"屏幕飘字"与"实际心数 delta"的关系，统计时必须把这些 flag 一并写入 session 元数据：

| Flag | 含义 | 数据效应 |
|---|---|---|
| `#Bloodless CTT` | 无血肉模式 | 所有 RedHearts 永远不掉，承伤记账只能走 BlackHearts/SoulHearts/BlueHearts delta |
| `#Hardcore CTT` | 困难模式 | 怪物伤害 ×N，治疗效率 ÷N（具体倍率见地图）|
| `#GlassCannon CTT` | 玻璃大炮 | 玩家造成伤害大幅提高 + 自身受击大幅提高 |
| `#GlassBones CTT` | 玻璃骨头 | 玩家承伤直接放大 |
| `#DamageNumbers CTT` | 飘字开关 | =0 时**完全无 DamageShower 粒子**——客户端必须切到心数 delta 主源 |
| `#PauseGame CTT` | 暂停 | 一切 score 不变化，客户端不应把这段时间计入 DPS 分母 |

#### 实现要点

```java
public final class ClientGameModeFlags {
    public record Snapshot(
        boolean bloodless, boolean hardcore, boolean glassCannon, boolean glassBones,
        boolean damageNumbers, boolean paused
    ) {}

    public static Snapshot read(Scoreboard sb) {
        return new Snapshot(
            readScore(sb, "#Bloodless",     "CTT") > 0,
            readScore(sb, "#Hardcore",      "CTT") > 0,
            readScore(sb, "#GlassCannon",   "CTT") > 0,
            readScore(sb, "#GlassBones",    "CTT") > 0,
            readScore(sb, "#DamageNumbers", "CTT") != 0,
            readScore(sb, "#PauseGame",     "CTT") > 0
        );
    }
}
```

在 `ClientSession` 开局时读一次写进 NBT（`mode_flags` 字段），全局变化时更新；`#DamageNumbers CTT = 0` 时把 `ClientModConfig.damageDealtSource` 强制覆盖为 `HEARTS_PRIMARY`，否则统计会全空。

`#PauseGame CTT > 0` 时停 DPS 计时器（保持 ring buffer 时间戳冻结）。

---

## 7. 双端共存策略

### 7.1 三种部署形态

| 形态 | 客户端 mod | 服务端 mod | 行为 |
|---|---|---|---|
| A | 装 | 装 | **现状**：服务端权威，HUD 通过 `ClientStatsCache` 读服务端数据 |
| B | 装 | 不装 | **新场景**：客户端 mod 自己跑 ClientDamageProbe，全套统计本地算 |
| C | 装 | 装但版本不匹配 | 优雅降级：能收到 `StagePayload`（v1 兼容）但收不到 `StatsSnapshotPayload v2` 时回退到 B |

### 7.2 自动检测机制

由 `ClientStatsCache` 的 `latest` 字段是否在合理时间窗内被更新过来决定。**注意：客户端 mod 不能直接引用 `CttStatsServer.getServer()`** —— 该类位于 `server` 子包，在客户端运行时（专用客户端启动）会触发 `NoClassDefFoundError`。改用 vanilla `MinecraftClient#getServer()` 判断"集成单机"形态：

```java
public enum DataMode { SERVER_AUTHORITATIVE, CLIENT_FALLBACK, INTEGRATED }

public static DataMode currentMode() {
    MinecraftClient mc = MinecraftClient.getInstance();
    // INTEGRATED：当前是集成单机（host 自开 LAN），同 JVM 跑着内嵌服务器
    //   且本端 mod 与服务端 mod 同时存在 → 服务端探针权威，本地探针进入 silent 校验模式
    if (mc.getServer() != null && CttModEnvironment.serverModLoaded()) {
        return INTEGRATED;
    }
    // SERVER_AUTHORITATIVE：远程服务器装了本 mod，最近 10s 收到过 StatsSnapshotPayload
    StatsSnapshotPayload p = ClientStatsCache.latest();
    long now = System.currentTimeMillis();
    if (p != null && now - p.startMs() < 10_000) {
        return SERVER_AUTHORITATIVE;
    }
    // CLIENT_FALLBACK：纯 vanilla 服务器或服务端 mod 已离线
    return CLIENT_FALLBACK;
}
```

辅助类 `CttModEnvironment.serverModLoaded()` 应只检测 Fabric `FabricLoader.getInstance().isModLoaded("ctt-health-display-server")`（如果以后拆 sub-mod 了），或反射尝试 `Class.forName("...CttStatsServer")` 并 catch `NoClassDefFoundError`。**禁止在 client 包的任何代码里直接 import server 子包的类。**

并在玩家加入服务器后给一个 5~10 秒的"等待窗口"，如果迟迟收不到 payload 就切换到 `CLIENT_FALLBACK` 模式启动客户端探针。

### 7.3 数据来源标识

UI 上明确告诉用户当前数据是从哪儿来的：

- HUD 角落：`[S]` = Server, `[C]` = Client, `[I]` = Integrated（小图标即可，不必占大空间）。
- L 面板顶部：用一行说明文字（"数据源：客户端本地计算（精度 ~85%）"）。
- K 表标题：附带数据源徽标。

### 7.4 双端模式下的精度对照（可选高级特性）

模式 A 下，客户端探针**仍然在跑**（不消耗很多资源），但只作为**校验源**——把客户端算出的总伤害 vs 服务端 payload 的总伤害打到 L 面板调试栏，差值过大时输出 warning。这能帮 mod 开发者发现归属链漏洞。

> 默认关闭（dev-only），用户配置打开。

---

## 8. 分阶段实施计划

按"先建数据骨架，再做归属"的顺序排（与服务端开发史相反，因为客户端版的关卡/局边界比归属更基础——没有正确的 session 边界，归属算出来也无处归档）：

### Phase 0 · 模式开关基础设施（1~2 天）

- [ ] 在 `ClientModConfig` 加 `clientFallbackMode: AUTO | FORCE_ON | FORCE_OFF`。
- [ ] 加 `damageDealtSource` / `damageTakenSource` 双源开关（§4.3.4）。
- [ ] 在 `ClientStatsCache` 加 `currentMode()` 自动判定 + 状态切换钩子（reset client probe / reset payload mirror）。
- [ ] **`CttModEnvironment.serverModLoaded()` 反射检测**——绝不直接 import server 包（§7.2）。
- [ ] 客户端 HUD 上加 mode 标识（角落小图标）。

### Phase 1 · 客户端 ScoreboardUpdateMixin（1 天）

- [ ] 新建 `client/mixin/ClientScoreboardScoreMixin.java`。
- [ ] 加到 `ctt-health-display.mixins.json`。
- [ ] 仅做日志输出（"客户端看到 holder=X obj=Y val=N"），先用 INFO 看一遍量级，再降为 TRACE。

### Phase 2 · ClientStageProbe + ClientSession + GameModeFlags（2~3 天，关卡骨架优先）

- [ ] 复刻 `StageProbeServer` 14 个 holder 读取逻辑。
- [ ] 复刻 `StageBoundaryDispatcher` 的 GameID 跳变检测。
- [ ] 实现 `ClientGameModeFlags.read()`（§6.4 表）+ `#PauseGame` 暂停 DPS 计时。
- [ ] 复用现有 `StageKey` / `StageNameRegistry`。
- [ ] 验收：进各类型关卡（dungeon / shop / boss / break_room）后 stageKey 切换正确；`#DamageNumbers CTT = 0` 时正确切到 `HEARTS_PRIMARY`。

### Phase 3 · ClientHeartsTracker + 玩家承伤（2~3 天）

- [ ] 实现 `ClientScoreDeltaTracker`（与服务端 `ScoreDeltaTracker` 对称）。
- [ ] 实现 §4.2 第 2 档：4 层心数 delta → `ClientPlayerTakenStats.addTaken`。
- [ ] 监听自己玩家名上 `RedDamageTook` / `BlackDamageTook` 的 score 增量做交叉验证。
- [ ] 处理 `#Bloodless CTT` 时跳过 RedHearts 一层（地图配置对齐）。
- [ ] 验收：在 fallback 模式下打副本，玩家承伤总数与对照源（按 K 表自身 / 死亡时 RedHearts 归零）误差 < 3%。

### Phase 4 · ClientDamageShowerStream + 对怪输出（4~5 天）

- [ ] 新增 `client/mixin/ClientEntitySpawnMixin`、`ClientEntityTrackerUpdateMixin`、`ClientEntitiesDestroyMixin`。
- [ ] 实现 §4.6 `ClientDamageShowerStream`（spawn → score → background 三段确认）。
- [ ] 实现 1.5m / 实际 3m 邻域绑定 + confidence 标记。
- [ ] 监听 `#ServerLag CT` → fallback 心数 delta 这一帧。
- [ ] 接入现有 `PlayerDamageStats.add`（API 已经存在，只是改成客户端线程调用）。
- [ ] 验收：在 fallback 模式下打副本，对怪输出总数 vs 屏幕飘字累加（手动数值校验）误差 < 5%。

### Phase 5 · ClientAttackerProbe（5~7 天，工作量最大）

- [ ] 实现 L1~L8 简化版（按 §5.1 表）。
- [ ] 实现 L0_SELF_INPUT（自己的攻击键 / 释放键事件）。
- [ ] L7_BOW_RELEASE 复刻（监听弓 stat）。
- [ ] L8_LAST_HITTER 复刻（数据结构与服务端 `VictimLastHitter` 完全对称）。
- [ ] 把 §4.6 的 victim+amount 推入 attacker 链做最终归属。
- [ ] 验收：与 INTEGRATED 模式（同 JVM 服务端 + 客户端）下的归属结果对比，重叠率 ≥ 80%。

### Phase 6 · ClientPlayerKillStats（2~3 天）

- [ ] 击杀判定：监听实体 `RedHearts ≤ 0` + `EntitiesDestroyS2CPacket` 同 tick 出现（注意 Coffin 启发：≤ 0 但持续 ≥ 5 tick 仍存活 = Coffin 不计死亡）。
- [ ] 助攻判定：从 ClientAttackerProbe 的 contributors 累计中读取。
- [ ] 验收：与 INTEGRATED 模式数据对比。

### Phase 7 · 客户端持久化（2~3 天）

- [ ] 持久化路径迁移到 `config/ctt-health-display/stats/`。
- [ ] 复用 `toNbt` / `fromNbt`（无需改动 schema）；新增 `mode_flags` 字段（§6.4）。
- [ ] 启动时 `load`、tick 末节流写、关游戏时 force flush。
- [ ] **新增**：history 归档加上服务器 IP 标识（`server-ip-hash` 字段，不存原始 IP）让用户可以分服浏览。
- [ ] **新增**：`current.nbt` 用于崩溃恢复（每分钟自动落盘，启动时若发现是不正常退出残留则尝试恢复 + 提示用户）。

### Phase 8 · UI 标识与文档（1 天）

- [ ] HUD 数据源徽标。
- [ ] K 表 / L 面板的 mode 提示 + 数据置信度标记（来自 §4.6 confidence）。
- [ ] 更新 `INTRODUCTION.md` 和 `FEATURES.md` 加入 v7 章节。
- [ ] 写 `CLIENT_FALLBACK_USER_GUIDE.md`（玩家手册）。

**总工作量估计**：18~28 天单人开发，对应 v7.0 ~ v7.x 几个小版本。比原估算多 4 天，主要来自 §4.6 粒子流的 mixin 复杂度与 §6.4 模式 flag 集成。

---

## 9. 建议的代码组织

### 9.1 新增包结构

```
src/main/java/com/ctt/healthdisplay/client/
  ├── ClientStatsCache.java              [现有，加 mode 判定]
  ├── mode/
  │   ├── DataMode.java                  [enum: SERVER_AUTHORITATIVE / CLIENT_FALLBACK / INTEGRATED]
  │   └── DataModeDispatcher.java        [自动检测 + 模式切换钩子]
  └── probe/
      ├── ClientScoreDeltaTracker.java   [对称服务端 ScoreDeltaTracker]
      ├── ClientDamageProbe.java         [对称 DamageProbe]
      ├── ClientAttackerProbe.java       [对称 AttackerProbe，简化层]
      ├── ClientAttackerResolver.java    [对称 AttackerResolver]
      ├── ClientPlayerHitLog.java        [对称 PlayerHitLog]
      ├── ClientPlayerFireLog.java       [对称 PlayerFireLog]
      ├── ClientPlayerInventoryIndex.java [对称，但他人只扫装备槽]
      ├── ClientPlayerSelfInputTracker.java [新：监听自己 attack/use 键]
      ├── ClientStageProbe.java          [对称 StageProbeServer]
      ├── ClientSession.java             [对称 StageBoundaryDispatcher 的 GameID 部分]
      └── ClientStatsPersistence.java    [对称 StatsPersistenceManager，路径不同]

src/main/java/com/ctt/healthdisplay/client/mixin/
  └── ClientScoreboardScoreMixin.java    [新 Mixin]
```

### 9.2 复用而非重写

`PlayerDamageStats` / `PlayerKillStats` / `PlayerTakenStats` / `StageKey` / `StageBoundaryDispatcher` 这些**纯数据结构 / 业务逻辑类**，建议**直接复用**而不是再造一份。具体做法：

- 把它们从 `server` 包**搬到 `common` 包**（新增）：

  ```
  src/main/java/com/ctt/healthdisplay/common/stats/
    ├── PlayerDamageStats.java
    ├── PlayerKillStats.java
    ├── PlayerTakenStats.java
    ├── StageKey.java
    └── ...
  ```

- 服务端版 `DamageProbe` 调它们；客户端版 `ClientDamageProbe` 也调它们。
- 这样一份 NBT schema、一份 snapshot 形状、一份单元测试。

> **要点**：现有这些类用到了一些 server-only 类（如 `MinecraftServer`），需要把那部分剥到 server 端的 wrapper 里，common 包只留纯逻辑。这是一个 **3~5 天的额外重构成本**，但带来后续维护的巨大简化。**强烈推荐**。

### 9.3 Mixin 优先级

客户端版 Mixin 与现有客户端 Mixin（`BossBarHudRenderMixin` 等）共存。建议显式声明 priority，避免与其他 mod 冲突：

```json
// ctt-health-display.mixins.json
{
  "mixins": [
    { "name": "BossBarHudRenderMixin", "priority": 1000 },
    { "name": "ClientScoreboardScoreMixin", "priority": 900 }
  ]
}
```

---

## 10. 风险、限制与不建议做的事

### 10.1 不要做

- ❌ **不要 parse `text_display` 的 text 字段**取伤害值。颜色分级 / obfuscated 字符 / 资源包改造都会破坏。**直接读实体上的 `DamageShower` scoreboard score**。
- ❌ **不要轮询 `Damage` / `*DMG` score 的当前值**。地图侧 `damage.mcfunction` 末尾每 tick 都执行 `scoreboard players reset @e Damage`——你以为读到的"当前 Damage=0"其实是"刚被 reset"。这些 score 必须以 `ScoreboardScoreUpdateS2CPacket` 到达瞬间为唯一信号源。
- ❌ **不要把 RedHearts 一层 delta 当作"对怪输出"**。地图侧 4 层级联吸收特性下，单层 delta 系统性偏低。对怪输出**必须**走 §4.6 粒子事件流。
- ❌ **不要把 DamageShower 的 amount 当作"玩家承伤"**。粒子值是 post-armor pre-cascade 的原始 `Damage`，玩家身上的多层心吸收后实际损耗会小于这个值；玩家承伤**必须**走 §4.2 第 2 档心数 delta。
- ❌ **不要假设客户端 tick 与服务端 tick 严格对齐**。用"自己的时间窗"判断陈旧度，不要依赖 tick 数字本身的精确同步。
- ❌ **不要让 INTEGRATED 模式同时跑两套探针并双倍累加**。`DataModeDispatcher` 必须确保同一时刻只有一份数据源在写 stats（除非显式开启 §7.4 校验模式，且校验模式不写主统计）。
- ❌ **不要在客户端伪造 `tag=Atk` 等 datapack 内部状态**。无意义且会出 bug。
- ❌ **不要试图通过自定义 plugin message 反向通知服务端**。纯原版服务端不响应（`CLIENT_SIDE_DATA_REFERENCE §13.1`）。
- ❌ **客户端代码绝不直接 import 任何 `com.ctt.healthdisplay.server.*` 类**。会触发专用客户端启动时的 `NoClassDefFoundError`，必须通过 `Class.forName` + catch 反射访问。

### 10.2 已知精度让步

#### 10.2.1 对怪输出（粒子主源）

| 场景 | 服务端版精度 | 客户端版精度 | 备注 |
|---|---|---|---|
| 单玩家击杀 BOSS（输出量） | 99% | **99%** | DamageShower 与玩家屏幕飘字 1:1 等价 |
| 单玩家击杀 BOSS（归属到自己） | 95% | **99%** | 客户端 L0 比服务端任何一层都更直接 |
| 多人混战 - 输出量 | 99% | 95% | DamageShower cap=10 时漏粒子，靠心数 delta 兜底 |
| 多人混战 - 归属（attacker） | 95% | 80% | marker tag 缺失 + 同 tick 多怪受击 |
| AOE 大爆发 / DoT 群伤 | 95% | 85% | cap=10 限制 + 1.5m 邻域多 victim 绑定模糊 |
| `#ServerLag CT = 1` 帧 | 95% | 70% | 粒子全清，仅靠心数 delta 兜底，单次精度下降 |
| `#DamageNumbers CTT = 0` | 95% | 70% | 粒子完全不生成，全程走心数 delta，多人场景归属差 |
| 治疗识别 | 95% | 95% | 都用 background 颜色判定，等价 |

#### 10.2.2 玩家承伤（心数主源）

| 场景 | 服务端版精度 | 客户端版精度 | 备注 |
|---|---|---|---|
| RedHearts 承伤 | 100% | 100% | `RedDamageTook` 累加器 + delta 双重确认 |
| BlackHearts 承伤 | 100% | 100% | `BlackDamageTook` 累加器 + delta 双重确认 |
| BlueHearts / SoulHearts 承伤 | 100% | 95% | 地图无累加器，需扣除"被治疗回填"部分，HealDMG 同 tick 推断 |
| 死亡时承伤总和 | 100% | 99% | session 内累计，与 4 层 delta 完全对等 |
| 元素 vs 物理承伤分类 | 80% | 50% | 客户端无 `Hit` tag 序列，靠粒子文本兜底（v7.x+） |

### 10.3 性能成本

客户端探针在 fallback 模式下需要每 tick 做 5~10 次 scoreboard 查询 + 1 次实体扫描（半径 40m 内的玩家与 marker）。在 8 玩家满编副本 + 高频 boss DoT 场景下，预估增量：

- 客户端单帧 CPU：+0.2~0.5 ms（占总帧时间 ~3%）。可接受。
- 内存：+10~20 MB（VictimLastHitter / VictimDamageContributors 等缓存，TTL 20s）。可接受。
- GC 压力：每 tick 创建几个 record 对象，与现有渲染管线相比可忽略。

### 10.4 与第三方反作弊的关系

某些公服会装反作弊插件，对"频繁查询计分板"或"频繁监听网络包"类客户端 mod 有 ban 风险。客户端版**不发任何 C2S 包**（只是被动监听 S2C 缓存），理论上等同于"客户端读 vanilla 数据 + 本地计算"，应该不会触发反作弊。但建议：

- 第一版上线后**主动联系几家有反作弊的 CTT 公服**确认兼容。
- 在 `INTRODUCTION.md` / `README.md` 显著位置写明"客户端 mod 完全只读，不向服务端发任何自定义请求"。

---

## 11. 未决问题清单

以下问题需要本 mod 维护者（或用户调研）做出决策，再继续推进设计：

### 11.1 跨服合并

客户端持久化的 stats 是按 GameID 切分的。**同一玩家在不同服务器上的 stats 是否合并展示？**

- 选项 A：完全不合并，按服务器 IP hash 分仓存储，K 表只看当前服务器的历史。
- 选项 B：合并展示，玩家有"个人战绩"概念（跨服）。
- 选项 C：用户可选，默认 A。

> 推荐 **C**。但实现复杂度 A < C < B。

### 11.2 客户端版的 history 归档上限

服务端版 `StatsPersistenceManager` 限制 history 保留 20 条。客户端有用户自己的硬盘空间，是否放宽？

- 选项 A：保持 20 条。
- 选项 B：放宽到 100 条 / 365 天（按日期滚动）。
- 选项 C：用户可配置。

> 推荐 **C**，默认 50 条。

### 11.3 双端都装时是否做交叉校验

服务端权威结果 vs 客户端独立计算结果可能有差异。**是否在 L 面板暴露这个差异？**

- 选项 A：不暴露，避免用户困惑。
- 选项 B：dev mode（`ModConfig.developerMode=true`）下暴露。
- 选项 C：永远暴露，作为透明度卖点。

> 推荐 **B**。dev mode 是更适合该信息的受众。

### 11.4 公服检测 / 自动模式判定的等待窗口

`DataModeDispatcher` 加入服务器后等多久没收到 payload 就切到 fallback？

- 5 秒：误判风险（网络抖动）
- 10 秒：保守，稍卡顿
- 用户可配：复杂度高

> 推荐**默认 8 秒** + 配置项可改。

### 11.5 客户端版本号策略

客户端 fallback 数据的 schema 与 server-authoritative payload schema 应该如何对齐？

- 选项 A：完全对齐（`PlayerDamageStats.Snapshot` 同形状）。
- 选项 B：客户端用独立 record（如 `ClientPlayerDamageSnapshot`），通过 adapter 转换后给 UI。

> 推荐 **A** + §9.2 的 common 包重构。这样 K 表 / L 面板代码完全无需改动。

### 11.6 是否同时接管 HUD 显示

当前 HUD 在公服上只能显示心数 / 法力 / 货币（这些计分板本来就客户端可见）。新的客户端 fallback 模式上线后，HUD 应该：

- 选项 A：保持现状（HUD 不变），只增加 K/L 表的数据。
- 选项 B：同时接管 HUD 上的"伤害广播条"（v6 服务端会聊天广播单击伤害）——但客户端版广播只能给自己看，不能广播。
- 选项 C：新增"自己的实时输出 DPS"在 HUD 角落。

> 推荐 **A + C**。

### 11.7 模式 flags 写入元数据 vs 影响计算

§6.4 列出的 6 个 flags 中：

- `#PauseGame` / `#DamageNumbers` 必须**实时影响计算**（暂停时停 DPS、关粒子时切心数源）——已在 §6.4 落实。
- `#Bloodless` / `#Hardcore` / `#GlassCannon` / `#GlassBones` 是否参与计算？

  - 选项 A：仅作为元数据写入 session NBT，K 表显示模式徽章，统计数字保持原样。
  - 选项 B：自动按地图倍率反推"原始伤害"——但倍率值客户端无法读到（在 datapack 不可见的 modifier 里）。
  - 选项 C：在 K 表数字上叠加模式徽标 + 模式滤镜（玩家可勾"只看 GlassCannon 局"）。

> 推荐 **A + C**。B 在没有逆向倍率清单的情况下不可行。

### 11.8 跨服 GameID 冲突

`#CTT GameID` 是地图侧的本地计数器，每开局 +1。**不同服务器的 GameID 命名空间会冲突**——服 A 的 g42 与服 B 的 g42 是两个完全不同的 session。

- 选项 A：归档文件名加上 `server-ip-hash`（§11.1 推荐 C 的实现要求）。
- 选项 B：用客户端启动以来的全局自增 ID 替代 GameID 作主键，GameID 仅作"地图内部局编号"展示。
- 选项 C：A + B 双 ID（`(serverHash, gameId, localSeq)` 三元组）。

> 推荐 **C**。`localSeq` 由客户端维护，避免任何冲突；`gameId` 用于 K 表显示"第 X 局"；`serverHash` 用于跨服分仓。

### 11.9 崩溃恢复 / 异常退出残留

客户端 mod 在副本进行中可能崩溃 / 强退 / 网络断开。`current.nbt` 里的中间状态如何处理？

- 选项 A：启动时若发现 `current.nbt` 存在 = 视为"上次没正常归档"，直接归档为 `aborted_<timestamp>.nbt` 并清空。
- 选项 B：启动时若发现，弹窗问玩家"是否恢复上次的中间状态"。
- 选项 C：自动尝试重连同一服务器（IP hash 匹配）+ 同一 GameID，若都吻合则继续累加；否则归档。

> 推荐 **C** —— 既不丢数据也不让用户做选择。仅在 IP hash 不匹配时降级到 A。

---

## 附录 A · 可行性评分

| 维度 | 评分 | 说明 |
|---|---|---|
| 技术可行性 | ★★★★★ | 所有依赖的客户端 API 都存在且稳定 |
| 工作量 | ★★★ | 18~28 天单人，含粒子流 mixin + common 包重构 |
| 维护代价 | ★★★ | common 包重构后基本是单点维护，否则双倍；双轨主源额外 ~+15% 复杂度 |
| 用户价值 | ★★★★★ | 让公服 90% 的玩家能用 |
| 精度风险 - 输出量 | ★★★★ | 与玩家屏幕飘字 1:1 等价，**比服务端 RedHearts-only 主源还准** |
| 精度风险 - 归属链 | ★★ | 归属层精度下降到 80~85%（多人混战），单人/自身 99% |
| 精度风险 - 承伤 | ★★★★★ | 4 层 delta + 累加器双重确认，与服务端等价 |
| 反作弊兼容 | ★★★★ | 完全只读，理论上无风险 |
| **综合推荐度** | **★★★★** | **应该做，但要分阶段、采用双轨主源、与服务端版共存** |

## 附录 B · 与现有文档的关系

本提案在文档体系中的定位：

```
INTRODUCTION.md ───────────────────► 玩家入门
FEATURES.md ───────────────────────► 版本特性
ROADMAP.md ────────────────────────► v6 进度
V6_STATS_DESIGN.md ────────────────► v6 服务端设计
V6_STATS_STATUS.md ────────────────► v6 设计 vs 实现 gap
MAP_DATAPACK_REFERENCE.md ─────────► 地图数据源
CLIENT_SIDE_DATA_REFERENCE.md ─────► 客户端可读数据清单
CODE_REVIEW.md ────────────────────► 现有架构总览
架构与数据流摘要.md ────────────────► 中文架构摘要
CLIENT_SIDE_STATS_PROPOSAL.md ─────► ★ 本文档（v7 设计提案）
```

后续若进入实施期，应在 `ROADMAP.md` 新增 v7 章节、并随实施进度更新本文档的 §8 阶段勾选状态。

---

## 维护说明

- 各 Phase 完成后请在 §8 勾选并加上 commit 引用。
- 实施过程发现的精度数据应回填到 §10.2 表格。
- §11 的未决问题决策定下后请回填具体选项。
- 若服务端 `AttackerProbe` 后续有大版本变化（如 L10 新增），应同步检查 §5.1 是否需要扩展。

---

## 12. P0 客户端探针骨架（v7.0.0 实装）

### 12.1 范围（与用户对齐）

| 项 | 决定 |
|---|---|
| 三段数字语义 | **全场聚合**——累加所有 DamageShower 粒子数值，不做归属 |
| 持久化 | **session-only**——断开 / 退出全清零，无 NBT / config 落盘 |
| 与服务端 mod 关系 | **完全独立**通道——服务端版数据继续走 `ClientStatsCache`（S2C payload），互不干扰；公服无服务端 mod 时本探针是唯一伤害数据源 |
| 分配 / 过滤 | **本阶段不做**，仅留 `AttributionHook` / `FilterHook` 空接口签名给 v8 接入 |

### 12.2 模块清单

| 文件 | 职责 |
|---|---|
| `client/ClientDamageProbe.java` | 核心单例。`onClientTick` 扫 `text_display` 实体；维护 `entityToLastScore` cache 算 score delta；累加 `globalTotal` / `stageTotal` / `dpsRing[100]`；轮询 `representativeStageKey` 触发 stage 切换；调用 hook |
| `client/probe/AttributionHook.java` | 空接口，`NOOP` 默认实现恒返回 null |
| `client/probe/FilterHook.java` | 空接口，`PASSTHROUGH` 默认实现恒返回 true |
| `client/probe/ParticleObservation.java` | 单次粒子增量观测的 record 载体（聊天调试日志用） |

### 12.3 数据通路

```
text_display 粒子 (red bg)
       │
       ▼  每 client tick (END_CLIENT_TICK)
ClientDamageProbe.onClientTick
       │
       ├── advanceDpsRing()                                  // 5s 滑窗推进
       ├── stageKey 轮询 → onStageChanged → stageTotal=0     // 分关重置
       ├── 扫 world.getEntities() → TextDisplayEntity + bg=-65536
       ├── scoreboard.getScore(td, "DamageShower") → currentScore
       ├── delta = currentScore - entityToLastScore.get(eid)
       ├── filter.accept(...)         (PASSTHROUGH = always true)
       ├── attribution.attribute(...) (NOOP        = always null)
       ├── globalTotal/stageTotal/dpsRing[idx] += delta
       └── 若 ModConfig.clientDamageDebugChat=true → 本地聊天 [CDP] 日志
       │
       ▼ despawn 检测
entityToLastScore.removeIf(id 不在 seenThisTick)
```

### 12.4 UI 接入点

| 位置 | 行为 |
|---|---|
| `HealthBarRenderer.renderTeammates` 顶部 | `⚔ <全局> · ⚔ <当前关> ⚡ <5sDPS>/s`，仅 `clientDamageHudHeader=true` + 非水平布局 + `hasAnyData()` 时绘制 |
| `DamagePanelRenderer.handleButton(case 0)` | "清空数据"按钮同步调 `ClientDamageProbe.clearAll()` |
| `StatsTableScreen` 顶栏第二行（Session 行下方） | `客户端可见伤害（无归属）  ⚔ 全局 N    ⚔ 当前关 N    ⚡ N/s`，总表分表共用 |
| `CttHealthDisplay.DISCONNECT` | 调 `resetForDisconnect()` 清三段累加 + entityToLastScore + stageKey |
| `CttHealthDisplay.END_CLIENT_TICK` | 调 `onClientTick(client)`——所有扫描和累加的入口 |

### 12.5 配置项（追加到 `ModConfig`）

| 字段 | 默认 | 说明 |
|---|---|---|
| `clientDamageDebugChat` | `false` | 本地聊天打 `[CDP] tick=N +D (total=T) victim≈X` 流水，调试用 |
| `clientDamageHudHeader` | `true` | HUD 顶部聚合行总开关 |

### 12.6 接口预留（v8 接入点）

```java
// 本阶段两个 hook 都是 NOOP / PASSTHROUGH，不参与统计。
ClientDamageProbe.INSTANCE.setAttributionHook(...);  // 接入"识别 attacker"
ClientDamageProbe.INSTANCE.setFilterHook(...);       // 接入客户端版异常过滤
```

`AttributionHook` 实装时可基于：粒子位置 + 玩家手持武器 + 客户端版 `PlayerFireLog` + 距离 / 时序启发。

`FilterHook` 实装时可复刻 `DamageFilterPipeline`（服务端）的 G_low / G7a / G3 / G4 等规则，用纯客户端可见的 scoreboard（`MaxHP` / `Defence` / `RedHearts`）做判定。

### 12.7 与 §8 阶段计划的对应

本节实现的是 §8 的 **Phase 0** 之外的"骨架阶段"，介于 §3 可行性结论与 §8 Phase 1 之间——目的是**先证明数据通路可行 + UI 接入完成**，再决定 v8 是否要真正补 attribution 和 filter。后续 Phase 1~6 推进时，本探针的累加器结构、聊天日志格式、HUD/面板/表格接入点都可直接复用，仅替换两个 hook 的实现即可。

### 12.8 已知限制

| 限制 | 影响 | 缓解 |
|---|---|---|
| 多人同打同怪 | 队友的输出也算进 `globalTotal` | 这是"全场聚合"语义的明确含义；接 `AttributionHook` 后可裁出"自己造成"那部分 |
| 服务端 `#ServerLag CT=1` | 那一帧粒子全 kill，事件流空白 | 客户端不可救（地图侧物理事实） |
| 单帧粒子上限 10 | 高频战斗下漏数 | 地图 datapack 强制限制，客户端不可救 |
| 异常大数 | 机制斩杀 99999 等会原样进总 | 等 `FilterHook` 接入后可裁；当前阶段是裸数据 |
| Stage 切换识别延迟 | 依赖 `representativeStageKey()` 的更新——dedicated 模式下走 1Hz S2C payload | 集成服务器场景实时；dedicated 场景最多 1 秒延迟，stage 内 `stageTotal` 多统计 ≤ 1 秒数据 |

