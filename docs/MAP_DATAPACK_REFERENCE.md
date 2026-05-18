# Cake Team Towers 数据包参考手册

> **定位**：本文档是 `ctt-health-display` mod（v6.6.10 及以上）开发的**主题式速查参考**。
>
> - 与 `MAP_DATAPACK_ANALYSIS.md` 的关系：那篇是按时间线写的"我们是怎么一步步发现这些事实的"（v6.0.0 → v6.0.5 的设计演进史），保留作为决策上下文。**本文档**则是按主题组织的**当前事实清单**——在写新功能 / 新 Mixin 之前，先在这里查约定。
> - 适用版本：地图 `Cake Team Towers Chapter 3 Update #4.0.12 (The Heart of Otherside) (Premium)`，pack_format 61。
> - 本文档**只覆盖对 mod 开发有价值的部分**；voice-acting、cosmetics、roof endings、特殊楼层剧情等不在范围内。

---

## 目录

1. [数据包结构与 tick 入口](#1-数据包结构与-tick-入口)
2. [命名与查找约定](#2-命名与查找约定)
3. [玩家生命周期与身份](#3-玩家生命周期与身份)
4. [全局游戏状态](#4-全局游戏状态)
5. [关卡与 Stage 体系](#5-关卡与-stage-体系)
6. [自定义健康系统（四层心数）](#6-自定义健康系统四层心数)
7. [伤害管线](#7-伤害管线)
8. [`DamageShower` 粒子（text_display）](#8-damageshower-粒子text_display)
9. [攻击者归属约定](#9-攻击者归属约定)
10. [Boss 栏槽位](#10-boss-栏槽位)
11. [玩家属性面板（ViewStats）速查](#11-玩家属性面板viewstats速查)
12. [`/trigger` 触发器清单](#12-trigger-触发器清单)
13. [右键 / 攻击事件信号](#13-右键--攻击事件信号)
14. [职业、被动与升级](#14-职业被动与升级)
15. [游戏模式与诅咒（GameScores）](#15-游戏模式与诅咒gamescores)
16. [死亡 / 复活 / 续币](#16-死亡--复活--续币)
17. [NPC、友军、敌人 tag](#17-npc友军敌人-tag)
18. [Mod 采集时机决策表](#18-mod-采集时机决策表)
19. [Scoreboard 速查表（按主题）](#19-scoreboard-速查表按主题)
20. [Tag 速查表](#20-tag-速查表)

---

## 1. 数据包结构与 tick 入口

### 1.1 数据包目录

地图 `datapacks/` 下三个包：

| 包 | 说明 |
|---|---|
| `CakeTeamPack` | 主数据包，CTT 全部游戏逻辑 |
| `Misc` | 辅助库（`zt-var` 数学常量等） |
| `bukkit` | 空壳，只有 `pack.mcmeta` |

`CakeTeamPack` 内 10 个命名空间，**与 mod 相关的只有两个**：

- `cake_team_tower` —— CTT 核心逻辑
- `misc` —— 跨地图通用库（`misc:server_main`、`misc:scoreboards*`、`misc:clear_tags`）

其余命名空间（`cake_wars`、`smashcraft`、`mario_party`、`survival_twists`、`crafting`、`animated_java`、`attackspeed`）对 mod 无意义。

### 1.2 Tick 入口

**关键**：`minecraft:tick` 函数标签里**没有** `cake_team_tower:main`。地图通过命令方块驱动 `cake_team_tower:misc/pause_game`，并由它分派到 `cake_team_tower:main`。

对 mod：**只需假定 `cake_team_tower:main` 在游戏运行时每 tick 调用一次**，#PauseGame ≥ 1 时被冻结。

### 1.3 主循环（`cake_team_tower:main`）

```
cake_team_tower:main          ← 每 tick
  ├── misc:server_main                                         // PlayerID/JumpPressed/Frozen/各种通用
  ├── (unless CTT) cake_team_tower:lobby/lobby_main
  ├── cake_team_tower:misc/misc                                // 巨型总汇 1400+ 行
  ├── (#Dungeon/#Shop/#MBoss/#Boss/#Ally/#Misc ≥ 1) cake_team_tower:floors/_floor_universal
  ├── cake_team_tower:misc/health                              // 4 层心数汇总、armor 反算、HealthPercent
  ├── cake_team_tower:misc/damage_universal                    // 9 种 DMG → Damage → 扣血 → DamageShower
  ├── (DamageShower 实体) cake_team_tower:misc/damage_shower(2)
  ├── (BossbarID=1001..1010) cake_team_tower:misc/bossbars/p{N}_bossbar
  ├── cake_team_tower:misc/bossbars/bossbars_universal         // ArmorDisplay 切换
  ├── (SpectatingGame) cake_team_tower:misc/bossbars/targetting_enemy
  ├── cake_team_tower:enemies/enemy_abilities/_universal
  ├── cake_team_tower:misc/stats                               // 属性最终值聚合
  ├── cake_team_tower:misc/stats_done
  ├── (ViewStats=1..) cake_team_tower:misc/view_stats          // /trigger ViewStats 文本输出
  └── ...
```

### 1.4 游戏初始化（`cake_team_tower:gamestart`）

进入新一局时被调用一次：

- 给参加者打 `CTT` tag；游戏中持续存在的玩家进一步在 `damage_universal` 里被打上 `CTTAll`。
- `#CTT GameID` 自增 1，并赋给 `@a[tag=CTT] GameID` 与 `BackfillID`。**这就是 mod 检测"开新局"的边界**。
- 重置 stage holder（`#Dungeon`/`#Shop`/`#MBoss`/`#Boss`/`#Ally`/`#Misc`）和大量 GameScores。
- 初始属性：`MaxHP=200`，`RedHearts=200`，`AllHearts=200`，`Mana=15`/`MaxMana=15`，`Lives=3`，`Coins=0`，`HealPercent=10`，`ActiveCharge=15`/`MaxActiveCharge=15`，`MaxStamina=100`，`MaxAir=300`，`MaxSummons=2`，`SpeedAmplifier=100`。
- 给玩家分配 `BossbarID` 1001..1010（最多 10 人）。
- 设置 `#LobbyMiniGame CTT` (0 = 主线，1..8 见 §4.2)。

---

## 2. 命名与查找约定

### 2.1 玩家变量 vs 全局变量

| 形式 | 含义 | 例子 |
|---|---|---|
| `@a[tag=CTT] Foo` | 每个玩家各持一份 | `Lives`, `RedHearts`, `Mana`, `Coins` |
| `#Foo CTT` | 全局变量（"假玩家" `#Foo` 在 `CTT` objective 上） | `#Tier CTT`, `#Floor CTT`, `#GameOver CTT` |
| `#Foo GameScores` | 全局游戏模式开关 | `#Healthy`, `#Bloodless`, `#QuickPlay` |
| `#Foo CT` | 工具类 / 临时全局 | `#ServerLag CT`, `#4Tick CT`, `#RNG CT` |
| `#Foo DungeonScores` | 当前楼层范围内的临时全局 | `#NPCTalkHappened`, `#ItemUniversalRan` |

### 2.2 重要 objective 名称（注意拼写）

地图作者保留了**英式拼写**：`Defence`（不是 Defense），`Targetting`（不是 Targeting）。**直接用字符串匹配会被这一点坑到**——务必照抄。

### 2.3 `Number` objective

`misc:set_numbers` 为常量假玩家赋值（`#Number2 Number = 2`、`#Number100 Number = 100` 等），数据包用它做"乘除常量"。Mod 不需要写它，但在阅读 mcfunction 时常见。

---

## 3. 玩家生命周期与身份

### 3.1 Tag 生命周期

| Tag | 含义 | 何时打 / 何时摘 |
|---|---|---|
| `CTT` | 当前局活动玩家（最重要的存在性 tag） | `gamestart` 给 `ReadyToPlay` 玩家打；游戏结束/退出后清 |
| `CTTAll` | 即将进入伤害结算的玩家或允许扣血的实体（含 `tag=F`） | `damage_universal` 在准备阶段打 |
| `Respawning` | 玩家死亡后等待复活的瞬态 tag | 死亡循环管理 |
| `Dead` | 已经死亡（Lives=0 后） | tombstone 阶段 |
| `SpectatingGame` | 旁观状态（已死或自愿） | 触发 `targetting_enemy` 走查目标 boss 栏 |
| `ReadyToPlay` | `gamestart` 入口的瞬态标记 | `gamestart` 第一行打，最后一行清 |
| `OP` | 创造模式管理者（不参与战斗） | 手动配置 |
| `Debug` | 调试玩家（不参与战斗结算） | 手动配置 |

### 3.2 PlayerID（mod 攻击者归属的核心）

`misc:server_main` 在每 tick 执行：

```mcfunction
execute as @a[tag=CTT] store result score @s PlayerID run data get entity @s UUID[0]
```

- **每个玩家的 `PlayerID` = UUID 的第 0 个 int**。可能为负，可能在多人局中**冲突极小但非零**（4×10⁻¹⁰）。
- 数据包里的弹幕/召唤物/契约系统全部依赖这个 ID 关联玩家：`OwnerID`、`SoulLinkedID`、`FriendlyID` 等都是它。
- **mod 必须同样使用这个 ID 做主键**，不要换成 `Entity#getId()` 或 `Scoreboard player name`，否则会和数据包对不上。

### 3.3 GameID / BackfillID

- `#CTT GameID`：全局自增计数器，每次 `gamestart` +1。
- `@a[tag=CTT] GameID`：本局 ID 快照。中途加入的玩家由 backfill 流程匹配 `BackfillID`。
- **mod 用法**：作为"局会话 ID"，开新局时清零累计统计。`AttackerProbe` 当前已据此切换会话窗口。

### 3.4 BossbarID

`gamestart` 阶段为 `@a[tag=CTT]` 随机分配 `BossbarID` ∈ {1001..1010}。后续 `cake_team_tower:misc/bossbars/p{N}_bossbar` 按这个 ID 路由该玩家的 boss 栏槽位（详见 §10）。

---

## 4. 全局游戏状态

### 4.1 `#PauseGame CTT`

- 0 = 正在跑；≥1 = 暂停（出 "Game Paused" 标题）。
- mod 不应在暂停期间重置统计或推送增量。

### 4.2 `#LobbyMiniGame CTT`

`gamestart` 起手设置；游戏运行期间保持不变直到下一局。

| 值 | 含义 |
|---|---|
| 0 | **主线 CTT** (Tower 模式) |
| 1 | Lazy's Bow Training |
| 2 | Horse Race |
| 3 | Jelly Trials |
| 4 | Magum Trials |
| 5 | Snowball Civilization |
| 6 | **Heart of Otherside**（地图当前主推副本） |
| 7 | Muck Survivor |
| 8 | Love is a Battlefield |

主线模式 (0/6) 才有完整 4 层心数 / Lives / Stage holder 体系。其它 minigame 通常**只有部分 scoreboard 有意义**，mod 应该按 `#LobbyMiniGame` 做特例处理（参考 `view_stats.mcfunction` 在 minigame=8 时切到 `sc_view_stats`）。

### 4.3 `#Tier CTT` / `#Floor CTT` / `#FloorT CTT`

- `#Tier CTT`：当前层级（1..N）。`gamestart` 设为 1。
- `#Floor CTT`：当前楼层全局序号。
- `#FloorT CTT`：临时态（带过渡期），可能为负，`gamestart` 设为 -101。

### 4.4 `#GameOver CTT` / `#GameOverContinueScreen CTT`

- `#GameOver CTT = 1` ：本局已失败。
- `#GameOverContinueScreen CTT = 1` ：在续币界面。**mod 在此期间应停止采集**——玩家可能复活，伤害日志已经定格。

### 4.5 `#BreakRoom CTT` / `#BreakRoomID CTT` / `#CheckPoint CTT`

- `#BreakRoom CTT`：1..N 表示当前在第几个休息室。`≤2` 是开局过渡，许多语音/事件靠它过滤。
- `#BreakRoomID CTT`：**开新局时设的入口 BreakRoom**（QuickStart 等用得到）。
- `#CheckPoint CTT`：检查点序号，0 起。

### 4.6 系统标志

| 字段 | 含义 |
|---|---|
| `#ServerLag CT` | 0 = 正常；1 = 检测到 TPS 落后，地图会跳过 DamageShower 等可视化（**mod 也应据此跳过粒子采集**） |
| `#4Tick CT` / `#20Tick CT` | 4 / 20 tick 周期标记（=1 时是该周期 tick） |
| `#DamageNumbers CTT` | 1..4 循环计数器，决定 DamageShower 出现的 ±1 偏移方向 |
| `#DisableBossBars CTT` | 1 = 全部 bossbar 函数被跳过 |

---

## 5. 关卡与 Stage 体系

### 5.1 六个 stage holder

主循环按 stage holder 分派 `_floor_universal`：

| Holder | 含义 |
|---|---|
| `#Dungeon CTT` | 战斗楼层（普通敌人房） |
| `#Shop CTT` | 商店楼层 |
| `#MBoss CTT` | Mid-Boss 楼层 |
| `#Boss CTT` | Boss 楼层 |
| `#Ally CTT` | 友军楼层（招募 NPC） |
| `#Misc CTT` | 杂项楼层（minigame 等） |

**任一 holder ≥ 1 表示当前正在该种楼层。**值本身就是该种楼层在当前 Tier 内的编号（如 `#Dungeon CTT = 47` 表示在地图编号 D47 的 dungeon）。

### 5.2 Stage Key（mod 主键约定）

mod `StageKey` 用 6 元组 `(LobbyMiniGame, Tier, Dungeon, Shop, MBoss, Boss, Ally, Misc, Floor, BreakRoomID)` 来唯一标识"会话内的 stage"，但实际只需关心：**哪个 holder ≥ 1 + 该 holder 的值 + 当前 Tier**。其余作为唯一性保险。

### 5.3 边界事件

- **进入 stage**：上一 tick 所有 holder = 0，本 tick 某 holder ≥ 1。
- **离开 stage**：holder 从 ≥1 变 0（在 break room 里时全为 0）。
- **进入新局**：`GameID` 增加。

mod 据此切分统计窗口、推送 `StagePayload`。

### 5.4 DungeonScores（楼层级临时变量）

- `gamestart` 中 `scoreboard players set * DungeonScores 0`：每局开始全部清零。
- 楼层切换时同样会清零（`_floor_universal` 内部维护）。
- 包含 `#NPCTalkHappened`、`#ItemUniversalRan`、`#T131_MaxHPUp` 等"只在这层楼内有意义"的标志位。

---

## 6. 自定义健康系统（四层心数）

### 6.1 关键事实

**地图不使用 vanilla `LivingEntity#health` 来管理战斗血量。** 所有 `tag=CTTAll` 实体的"真正血量"在 4 个 dummy scoreboard 上：

| Scoreboard | 含义 | 受伤优先级（被扣顺序） |
|---|---|---|
| `BlueHearts` | 蓝心：临时盾，能挡 `Damage` | 1（最先扣） |
| `BlackHearts` | 黑心：扣完后会反弹 `DarkDMG` 给附近敌人 | 2 |
| `SoulHearts` | 灵魂心：可超出 MaxHP，但被治疗忽略 | 3 |
| `RedHearts` | 红心：主血条，治疗 (`HealDMG`) 增加它 | 4（最后扣，扣到 0 即死亡） |
| `MaxHP` | 最大红心 | — |
| `AllHearts` / `ScoreboardHP` | 4 层之和（`misc/health.mcfunction` 每 tick 重算） | — |
| `HealthPercent` | `RedHearts*100/MaxHP`（0..100） | 0..100 |

**`Entity#health` 的真实意义**：地图把它劫持来表示"vanilla 伤害源（如方块、弹射物自然伤害）的事实"——`misc/health.mcfunction` 比对前后差值得到 `Pierce_Damage`，然后并入 `Damage`：

```mcfunction
execute as @e store result score @s CTT_HPCheck run data get entity @s Health
execute as @e[tag=!ElementMelee] unless score @s CTT_HP = @s CTT_HPCheck
    run scoreboard players operation @s Pierce_Damage = @s CTT_HP
execute as @e[tag=!ElementMelee] unless score @s CTT_HP = @s CTT_HPCheck
    run scoreboard players operation @s Pierce_Damage -= @s CTT_HPCheck
```

随后立即用 `instant_health` 把 vanilla HP 拉回 100/200。**所以**：

- Mixin `LivingEntity#damage` 几乎抓不到任何战斗伤害事件——伤害是直接 `scoreboard players add` 进各种 `*DMG` 的。
- 想直接读 vanilla `health` 显示血条**完全错位**。

### 6.2 死亡判定

`misc/health.mcfunction` 末尾：

```mcfunction
execute if entity @e[scores={RedHearts=..0},tag=CTTAll,tag=!Coffin] run function cake_team_tower:misc/enemy_dies
```

- **死亡条件**：`RedHearts ≤ 0` 且非 `Coffin` 的 `CTTAll` 实体。其他三层（Blue/Black/Soul）不能让你活下去——它们必须在 `Damage` 管线里先被吃掉。
- 玩家死亡的 Lives/Continue 流程在 `cake_team_tower:misc/misc/...` 里另行处理。

### 6.3 装甲（armor）反算

`misc/health.mcfunction` 把 vanilla `armor` 属性反算成 `Defence` scoreboard，再用负 `armor` 数值（-1..-20）维持视觉护甲条。**这意味着 vanilla armor attribute 也是被劫持的**，不要拿来显示。

### 6.4 治疗

- 直接给 `@a HealDMG <n>` 即触发治疗逻辑。
- `damage.mcfunction` 第 17–35 行处理 `ExtraHealing` 加成（百分比）和 `Bloodless` 模式（折半）。
- 治疗最终通过 `scoreboard players operation @s RedHearts += @s HealDMG` 写入。
- 大于 50 的治疗会触发 voice acting 音效（`damage.mcfunction` 第 68–105 行）。
- **每个治疗也会生成 `DamageShower` 实体**，用绿色背景 `-16515325`（见 §8）。

---

## 7. 伤害管线

### 7.1 9 种伤害 scoreboard

地图把伤害按"类型"分散到多个 scoreboard，最后**全部汇入 `Damage`**：

| 类型 | scoreboard | 备注 |
|---|---|---|
| 近战 | `MeleeDMG` | |
| 弹射物 | `BulletDMG` | |
| 火 | `FireDMG` | |
| 冰 | `IceDMG` | |
| 黑暗 | `DarkDMG` | 黑心反弹也走它 |
| 光明 | `LightDMG` | |
| 电 | `ElectricDMG` | |
| 力（穿透 / 强制） | `ForceDMG` | 友伤惩罚走它 |
| 治疗 | `HealDMG` | 严格说不算"伤害"，但走同一管线 |
| 黑心攻击 | `BlackDMG` | 写入 `BlackHearts`（不经过 `Damage`） |
| 灵魂攻击 | `SoulDMG` | 写入 `SoulHearts`（不经过 `Damage`） |
| 穿刺反算 | `Pierce_Damage` | vanilla `health` 差值；**会被合并进 `Damage`** |
| 随机元素 | `RandomElementDMG` | `damage.mcfunction` 内随机选一种类型派发 |

### 7.2 管线时序（`misc/damage_universal` → `misc/damage.mcfunction`）

```
1. 加成 / 减免阶段
   ├── ExtraHealing 比例放大 HealDMG
   ├── Bloodless 模式：HealDMG/SoulDMG/BlackDMG 折半
   ├── ClassPassive 修正
   ├── SoulLink：把伤害共享给 SoulLinkedID 相同的玩家
   └── 友伤检测（FriendlyID vs PlayerID）→ 给攻击者 ForceDMG 50

2. 治疗结算
   └── HealDMG → RedHearts，刷 DamageShower (绿)，刷音效

3. SoulDMG → SoulHearts (直接加，跳过 Damage)
4. BlackDMG → BlackHearts (直接加，跳过 Damage)

5. 把 9 种 *DMG 合并到 Damage：
   Damage += MeleeDMG + BulletDMG + FireDMG + IceDMG + DarkDMG +
            LightDMG + ElectricDMG + ForceDMG + RandomElementDMG +
            Pierce_Damage

6. armor 减伤 (Defence / FireArmor / IceArmor / ...)，详细在 damage.mcfunction 中段

7. 4 层心数依次扣减（damage.mcfunction 第 1032–1058 行）：
   BlueHearts  -= Damage; Damage -= BlueHearts1
   BlackHearts -= Damage; Damage -= BlackHearts1   (并把扣减量反弹为 DarkDMG)
   SoulHearts  -= Damage; Damage -= SoulHearts1
   RedDamageTook += Damage   (累计承伤计数器)
   RedHearts   -= Damage

8. scoreboard players reset @e Damage   ← 每 tick 末重置

9. 死亡检测 (misc/health.mcfunction 末尾) 在下一 tick 的 main 循环触发
```

### 7.3 mod 关注点

- **采集"红心实际承伤"**：用 `RedDamageTook` 增量（v6.x 的 `useRedHeartsTally` 配置项）或读 `DamageShower` 实体的 `DamageShower` score（更全面但有粒子系统副作用）。
- **采集"原始伤害类型"**：在 `Damage` 合并前 Mixin `ServerScoreboard#setScore` 拦截各 `*DMG` 写入。这是当前 v6.6.10 `AttackerProbe` 的方式。
- `Damage` 每 tick 末被 reset，所以采集必须在同一 tick 内完成。
- `Pierce_Damage` 的源头是 vanilla `Health` 差值，**含义混杂**——可能是落地伤害、可能是世界外伤害、可能是 mod 触发的 vanilla damage。归类时归入 `L9_NONE`（未分类）。

### 7.4 友伤识别

```mcfunction
execute as @e[scores={Damage=1..,FriendlyID=-2147483647..2147483647}] at @s
  unless score @s FriendlyID = @a[scores={FriendlyDMG=1..},distance=..3,limit=1,sort=nearest] PlayerID
  run tag @a[scores={FriendlyDMG=1..},distance=..3,limit=1,sort=nearest] add FriendlyFirePunish
```

- **关键 scoreboard**：`FriendlyID`（被攻击对象的"主人 PlayerID"）、`FriendlyDMG`（攻击者刚刚造成伤害的标记）。
- mod 据此判断"自己打到自己宠物"——惩罚是 -10 Coins、+50 ForceDMG。

---

## 8. `DamageShower` 粒子（text_display）

### 8.1 实体形态

地图为每个生效伤害 / 治疗事件 spawn 一个 `text_display`：

```
summon text_display ~±1 ~1 ~ {alignment:"left",billboard:"center",
                              Tags:["DamageShower","Prop"],shadow:0b,see_through:1b}
```

伤害背景色 `data merge entity @s {background:-65536}`（红）；治疗用 `-16515325`（绿）。

### 8.2 关键 scoreboard

| Scoreboard | 含义 |
|---|---|
| `DamageShower` | 数值 = 这一次伤害事件的 `Damage`（或 `HealDMG`）值 |
| `DamageShower1`, `DamageShower2` | 后续动画 / 合并位 |
| `#DamageShowerAmount CTT` | 当前 DamageShower 实体计数（>10 会随机 kill 一个） |
| `#DamageNumbers CTT` | 1..4 循环，决定 spawn 偏移方向 |

### 8.3 mod 采集要点

- `text_display` 的 `DamageShower` score 在生成后**立即**被赋值（`damage.mcfunction` 第 1028 行 `at @e[scores={Damage=1..}]` 内同步执行），所以 `ServerScoreboard#setScore` Mixin 能稳定捕获。
- 实体位置 = 受伤实体附近（±1 格偏移），可用来反查"被打的实体是谁"——参考 `AttackerProbe` 的 `findVictimByDistance` 逻辑。
- `#ServerLag CT = 1` 时**不会** spawn DamageShower —— mod 也应在该 tick 跳过粒子采集，不要因为没看到粒子就漏伤害。
- 粒子只在 `tag=CTTAll`（普通伤害）/ 任意（治疗）周围生成，Boss 栏外的事件可能被 spawn 限制压掉（见 `#DamageShowerAmount CTT >= 10` 时的随机 kill）。
- 粒子背景色 `-65536` = 普通伤害，`-16515325` = 治疗。**mod 读 `background` NBT 即可区分**，不必再查 `HealDMG`/`Damage`。

---

## 9. 攻击者归属约定

### 9.1 PlayerID 链

数据包给所有"代表玩家攻击的实体"打上 `OwnerID` / `OwnerPlayerID` / 等价 score，值等于攻击者的 `PlayerID`：

| 实体类型 | 标识 score / tag |
|---|---|
| 玩家直接近战 | `PlayerHurtSound` tag（`damage.mcfunction` 第 1018 行）；攻击者 = 距受害者最近的 `@a[tag=CTT]` |
| 玩家弹射物 | `marker` 实体带 `Tags:["Bullet"]` 等，score `OwnerID`/`PlayerID` = 攻击者 PlayerID |
| 召唤物 | `tag=Summon`、score `OwnerID`，受害者扣血时通过附近召唤物反查 |
| 友军 NPC | `tag=F`，附带 `FriendlyID = PlayerID`（"我是谁的宠物"） |
| 其它 | `tag=CTTAll, tag=!CTT`（敌方非玩家）：攻击者通常是数据包逻辑本身，归 `L9_NONE` |

### 9.2 `AttackerProbe` 哲学（mod 侧实现）

- **"少漏少错"**（leak less, err less）：宁可归到 `L9_NONE` 也不胡乱归人。
- 8 层硬归属（L1..L8）+ 3 子层未分类（L9_NONE / L9_FILTER / L9_HEAL）。
- DoT carry：火 / 中毒等持续伤害在 1.5s 内继承上一个攻击者。
- 详细层定义见 `ROADMAP.md`、实现见 `AttackerProbe.java`。

### 9.3 黑名单 / 过滤值

- `Damage = 0`、`HealDMG = 0`、`*DMG = 0` 不进入 `damage.mcfunction` 主循环（用 `scores={X=1..}` 过滤）。
- mod 同样应忽略 0 / 负值 / 极端大值（默认上限 100000）。

---

## 10. Boss 栏槽位

### 10.1 玩家槽位 1001..1010

`gamestart` 给每个 `@a[tag=CTT]` 随机分配 `BossbarID` 1001..1010。`main` 循环按 ID 调用 `cake_team_tower:misc/bossbars/p{N}_bossbar`（N = 1..10）。

每个 `p{N}_bossbar` 维护一对 bossbar：

- `bossbar add p{1000+N}_onlyup "test"` —— `gamestart` 注册时是"test"，运行中改名 / 改色。

### 10.2 公共 bossbar

| ID | 用途 |
|---|---|
| `vt_train_hp` | "Skibidi Toilet Rizz"（Love is a Battlefield 关卡专用） |
| `skulls` | 全队骷髅头计数 |
| `targetting` | 旁观者锁定的 boss 实体血条（由 `targetting_enemy.mcfunction` 维护） |
| `party_bossbar` | 队伍统一 boss bar（部分 boss 战使用） |

### 10.3 `ArmorDisplay`（boss 栏切换护甲展示）

`bossbars_universal.mcfunction` 维护：

- 1=Fire, 2=Ice, 3=Dark, 4=Light, 5=Bullet(=TrueArmor), 6=Water, 7=Electric
- 每 40 tick 切换下一个，跳过为 0 的项。

mod 不需要写入它，但显示玩家护甲时可以读这个值同步切换。

---

## 11. 玩家属性面板（ViewStats）速查

`view_stats.mcfunction` 是 mod 想"展示玩家属性"时**最直接的清单来源**。下面按主题列出它读到的所有 scoreboard。

### 11.1 心数 / 生命

`RedHearts`, `MaxHP`, `BlueHearts`, `BlackHearts`, `SoulHearts`, `MaxSoulHearts`, `MaxBlackHearts`, `MaxBlueHearts`, `CrackedHearts`, `PinkHearts`, `NegMaxHealth`, `Lives`, `HealthPercent`

### 11.2 资源 / 货币

`Coins`, `Mana`, `MaxMana`, `Tokens`, `XP`, `Level`, `ActiveCharge`, `MaxActiveCharge`, `ActiveSpeed`, `Stamina`, `MaxStamina`, `Air`, `MaxAir`

### 11.3 攻击 / 防御属性

`Strength`, `Archery`, `AttackSpeed`, `AttackRange`, `Defence`, `TrueArmor`, `FireArmor`, `WaterArmor`, `ElectricArmor`, `IceArmor`, `DarkArmor`, `LightArmor`, `TrueFireArmor`, `LightArmor`

### 11.4 移动 / 体型

`MaxSpeed`, `SpeedAmplifier`, `SpeedRaw`, `StaminaSpeed`, `Size`, `Gravity`, `Jump`, `DoubleJump`

### 11.5 治疗 / 法术

`Healing`, `HealPercent`, `ExtraHealing`, `TowersRegen`, `ManaPower`, `ManaRechargeSpeed`

### 11.6 战斗 / 召唤 / 收集

`Summoning`, `MaxSummons`, `Fishing`, `BeePoints`, `AntPoints`, `Bitches`

### 11.7 业力 / 头骨

`LightKarma`, `NeutralKarma`, `DarkKarma`, `CelestialKarma`, `#Skulls CTT`

### 11.8 职业

`Class`, `ClassStat`, `ClassPassive`（详见 §14）

### 11.9 游戏时间

`#GT_Hours0`, `#GT_Hours`, `#GT_Mins0`, `#GT_Mins`, `#GT_Secs0`, `#GT_Secs`（每个玩家一份）

### 11.10 累计 / 历史

`DamageHealed`, `RedDamageTook`, `BlackDamageTook`, `SoulHeartsGained`, `BlackHeartsGained`, `T29_TotalDamageTook`

> 注意：以上 score 大多在 `cake_team_tower:misc/stats.mcfunction` 中由"占比类基础属性"+"装备/契约 buff"+"职业被动"层层叠加。mod 想做属性面板的话，**直接读最终值**（`Strength`、`Archery` 等无后缀的）即可，不要尝试自己重算。

---

## 12. `/trigger` 触发器清单

地图把这些 objective 设为 `trigger` 类型（玩家可主动触发，无需 OP）：

| Trigger | 用途 |
|---|---|
| `ViewStats` | 显示属性面板（→ 调 `view_stats.mcfunction`） |
| `Help` | 显示帮助 |
| `Customize` | 个性化界面 |
| `ViewProfile` | 角色资料 |
| `CraftingGuide` | 合成指南 |
| `GetDate` | 查询服务器日期 |
| `ToggleVoiceActing` | 开关语音 |

mod **不应**直接写入 trigger 类 score（除非用 OP 命令）；正确做法是 `/trigger ViewStats set 1` 或借助 mod 内部 UI。

---

## 13. 右键 / 攻击事件信号

### 13.1 右键

```mcfunction
scoreboard players set @a[scores={SG_RightClick=1..}] RightClick 1
scoreboard players set @a[scores={SG_RightClick=1..}] SG_RightClick 0
scoreboard players set @a[tag=CTT,scores={RightClick=1..},nbt=!{SelectedItem:{}}] RightClick 0
scoreboard players set @a RightClick 0   ← main 循环末尾每 tick 重置
```

- `RightClick = 1`：本 tick 玩家在持有物品的状态下右键。`Stunned ≥ 1` 时会被吃掉并提示 "You are stunned"。
- `SG_RightClick`：StackedSlot 包检测到的物品右键事件，进入 `RightClick`。
- mod 监听到玩家右键时**应同时检查 `RightClick = 1` 才与数据包一致**——避免和 GUI 屏右键冲突。

### 13.2 弓 / 箭

- `CupidArrowShoot` / `ArrowShoot` 等用 `used:minecraft.bow` 准则；地图在 main 末尾把 `@a[tag=CTT] ArrowShoot 0` 重置。
- `C121_ArrowsFired` 等累积 score 用作物品被动的计数器。

### 13.3 攻击证据 tag

数据包用大量瞬态 tag 标记"刚发生了什么"：

| Tag | 含义 |
|---|---|
| `Charge` / `Hold` / `Use` / `Cast` | 蓄力类技能阶段 |
| `Shoot` / `Fire` | 射击事件 |
| `Atk` / `Attack` | 玩家近战命中 |
| `Hit` | 命中事件（弹射物 / 近战通用） |
| `Target` | 当前敌方被锁定 |
| `ElementMelee` | 元素近战触发，让 `health` 反算跳过该实体本 tick（避免双计数） |
| `PlayerHurtSound` / `PlayerHurtSoundBig` | 玩家本 tick 受伤（用作攻击者反查） |

这些 tag 一般在下一 tick 末就被 `tag @e remove ...` 清掉，所以采集要在同一 tick 内完成。

---

## 14. 职业、被动与升级

### 14.1 `Class` / `ClassStat` / `ClassPassive`

- `Class`：玩家职业 ID（1..N，各 minigame 中含义不同）。
- `ClassStat`：职业等级数值（升级加成系数）。
- `ClassPassive`：被动技能 ID（1..26+ 见 `stats.mcfunction`）。

### 14.2 主线职业被动 ID 映射（节选）

| ClassPassive | 加成 |
|---|---|
| 1 | `Strength += ClassStat` |
| 2 | `ManaPower += ClassStat` |
| 3 | `Healing += ClassStat` |
| 4 | `Archery += ClassStat` |
| 5 | `AttackSpeed += ClassStat * 2` |
| 6 | `Strength += ClassStat`，且 `HealthPercent` 锁 100（视觉欺骗） |
| 7 | `ManaPower += ClassStat` |
| 8 | `Archery += ClassStat` |
| 9 | `Fishing` 或 `Healing`（取决于 `PactOfTheCook`） |
| 10 | `Healing` |
| 11 | `MaxHP += 50 * ClassStat` |
| 12 | `MaxMana += 5 * ClassStat` |
| 13 | `Strength` |
| 14 | `BroccoliT` 或 `Strength`（取决于 `PactOfTheFighter`） |
| 15 | `Summoning` |
| 16 | `AntPoints` |
| 17 | `StaminaSpeed += 3 * ClassStat`，`MaxStamina += 25 * ClassStat` |
| 18 | （仅 LobbyMiniGame=6）`Strength` |
| 19/20/22 | `ManaPower` |
| 21/24 | `Healing` |
| 23 | `Archery` |
| 25 | `MaxStamina += 25 * ClassStat`，`Strength += ClassStat/2` |
| 26 | `BroccoliT += ClassStat/5`，`ManaPower`/`Healing`（取决于 `PactOfTheProtector`） |

完整映射见 `cake_team_tower:misc/stats.mcfunction`。

### 14.3 升级标志

- `#LevelUp CTT = 1`：本帧某玩家升级（一帧的瞬态）。
- `#ClassSelect CTT`：是否在职业选择界面。1 = 是，main 循环里许多 stage holder 检测会跳过。
- `#ClassItem CTT`、`#ClassItemDrop CTT`：职业道具发放标志。
- `#NewPlayer` tag：第一次进 CTT 的玩家（`gamestart` 检测 `CTraining=0` 时打）。

---

## 15. 游戏模式与诅咒（GameScores）

`gamestart` 重置全部 GameScores 为 0；进副本时按规则置 1。**全部都是全局**（`#X GameScores`）。

| GameScore | 含义 |
|---|---|
| `#QuickPlay` | 快速开局（跳过 lobby 教程） |
| `#Festive` | 节日装饰（万圣节/圣诞等） |
| `#Healthy` | 敌人血量乘 1.3（boss 1.2） |
| `#GlassBones` | 玩家碎骨 |
| `#CursedBladeMode` | 诅咒之刃 |
| `#BuddySystemMode` | 必须双人合作 |
| `#ElementalMode` | 元素增强 |
| `#ChoicesMode` | 多选项随机 |
| `#SpeedRunnerMode` | 速通模式 |
| `#GreedMode` | 贪婪模式 |
| `#GlassCannonMode` | 玻璃大炮 |
| `#CarDrivePrice` | （楼层价格变量，默认 100） |

诅咒 / 全局开关（`#X CTT`）：

| 名称 | 含义 |
|---|---|
| `#Bloodless` | 治疗 / 灵魂 / 黑心 全部减半 |
| `#Haunted` | 闹鬼模式 |
| `#HardcoreMode` | 死亡 = 不能复活 |
| `#BlindCurse` | 失明诅咒 |
| `#KarmaChallengeID` | 业力挑战 ID |
| `#HeadlessHorsemagumChallenge` | 无头骑士挑战 |
| `#WaveModeMap` | 浪潮模式 |
| `#MeteroiteDungeon` | 流星地下城（拼写如此） |

mod 在显示 / 统计时应把这些挂在"局元数据"里（与 `GameID` 关联），方便回放分析。

---

## 16. 死亡 / 复活 / 续币

### 16.1 死亡链路

```
RedHearts ≤ 0 (CTTAll, !Coffin)
  ↓
cake_team_tower:misc/enemy_dies                ← 通用死亡入口（含玩家与敌人）
  ↓ 玩家分支
Lives -= 1
  ├── Lives ≥ 1 → 复活到 Spawn / CheckPoint
  └── Lives = 0 → 等待 Continue（GameOverContinueScreen）
                  ↓ 续币 → Lives = 3 (或配置)
                  ↓ 不续 → #GameOver CTT = 1, tag Dead
```

### 16.2 复活 / 续币相关 score

| 名称 | 含义 |
|---|---|
| `Lives` | 玩家剩余生命数 |
| `#ContinuePrice CTT` | 续币花费（默认 10 Coins） |
| `#GameOverContinueScreen CTT` | 1 = 续币界面打开 |
| `#GameOver CTT` | 1 = 已 GameOver（mod 应停止增量统计） |
| `ResurrectionFunc` | 复活道具持有 |
| `CrystalCount` | 复活水晶数（`damage.mcfunction` 第 1101 行：≥2 + Lives=0 + RedHearts≤0 → 自动复活） |

### 16.3 Coffin / 墓碑

- `tag=Coffin` 的实体即使 `RedHearts≤0` 也不会触发 `enemy_dies`（那是地图自己的"假尸体"实体）。
- mod 应忽略 `tag=Coffin` 的伤害事件。

---

## 17. NPC、友军、敌人 tag

### 17.1 实体角色

| Tag | 含义 | 是否打 `CTTAll` |
|---|---|---|
| `CTT` | 当前局玩家 | 是（gamestart 后打 CTT，damage_universal 把 CTT 列入 CTTAll） |
| `F` | 友军 NPC（招募的 ally / 玩家的宠物 / 召唤物） | 是 |
| `E` | 敌人（普通） | 否（除非有特殊 tag） |
| `Boss` | Boss 类敌人 | 否 |
| `MidBoss` | Mid-Boss | 否 |
| `Summon` | 玩家召唤物（含 `OwnerID`） | 视情况 |
| `NPC` | 普通 NPC（不参战） | 否 |
| `TestDummy` | 测试用木桩（参与伤害结算） | 是 |
| `Coffin` | 不可被杀的"已死"实体 | 否 |
| `Prop` | 装饰物（含 DamageShower） | 否 |
| `Spawn` | 重生点 marker | 否 |
| `LobbyProp` | 大厅装饰 marker | 否 |
| `RandomDungeon` / `RandomShop` / ... | 随机楼层入口 marker | 否 |

### 17.2 Friendly fire 链

```
攻击者 A (PlayerID = pid_A)
  → 命中 entity X (有 FriendlyID = pid_B, B != A)
  → 数据包给 A 打上 FriendlyFirePunish tag
  → A: -10 Coins, +50 ForceDMG, 收到红色提示
```

mod 不必复制此逻辑，但**承伤归属时要识别 `tag=F` 实体**——它们的死亡事件归属到 `FriendlyID` 对应的玩家而非数据包。

---

## 18. Mod 采集时机决策表

| 场景 | 应该采集吗？ | 理由 |
|---|---|---|
| `#PauseGame CTT ≥ 1` | 否 | 游戏暂停，所有伤害 / 心数事件无意义 |
| `#ServerLag CT = 1` | 部分 | DamageShower 不会 spawn，但 `Damage` / `*DMG` scoreboard 仍正确，仍可采集 |
| `#GameOverContinueScreen CTT = 1` | 否 | 续币界面，Lives=0、玩家可能复活 |
| `#GameOver CTT = 1` | 仅元数据 | 局已结束，关闭增量但保存最终统计 |
| `#LobbyMiniGame CTT = 0 或 6` | 是（完整） | 主线 / Heart of Otherside，全功能可用 |
| `#LobbyMiniGame CTT = 2/3/5/8` | 部分 | 自定义属性集，按 minigame 配置裁剪 |
| `#ClassSelect CTT = 1` | 否 | 职业选择界面，玩家无伤害事件 |
| `#BreakRoom CTT ≤ 2` | 是（除音效） | 开局过渡期，仍有事件需统计 |
| `tag=CTTAll, tag=Coffin` 实体的伤害 | 否 | 假尸体，不参与计算 |
| `Damage = 0` / `*DMG = 0` | 否 | 在 `damage.mcfunction` 主循环中已被过滤 |
| `Damage > 100000` | 否（黑名单） | 异常值，可能是 KYS / out-of-bounds 惩罚 |
| 实体 `tag=Prop` | 否 | 装饰物，不该有血量事件 |

---

## 19. Scoreboard 速查表（按主题）

> 仅列**对 mod 有意义的**条目。完整列表（5000+）在 `data/cake_team_tower/function/misc/scoreboards_part_2.mcfunction` 与 `data/misc/function/scoreboards.mcfunction`。

### 19.1 玩家身份 / 局会话

| Objective | 类型 | 含义 |
|---|---|---|
| `PlayerID` | dummy | UUID[0]，攻击者归属主键 |
| `GameID` | dummy | 玩家本局 ID |
| `BackfillID` | dummy | 中途加入时的回填 ID |
| `BossbarID` | dummy | 1001..1010，boss 栏槽位 |
| `#CTT GameID` | dummy | 全局 GameID 计数器 |

### 19.2 心数 / 健康

| Objective | 类型 | 含义 |
|---|---|---|
| `RedHearts` / `MaxHP` | dummy | 主血条 |
| `BlueHearts` / `MaxBlueHearts` | dummy | 蓝心 |
| `BlackHearts` / `MaxBlackHearts` | dummy | 黑心 |
| `SoulHearts` / `MaxSoulHearts` | dummy | 灵魂心 |
| `AllHearts` / `ScoreboardHP` | dummy | 4 层之和 |
| `HealthPercent` | dummy | 0..100 |
| `Defence` / `*Armor` | dummy | 装甲值 |
| `Lives` | dummy | 剩余生命数（侧边栏显示） |
| `RedDamageTook` | dummy | 累计红心承伤 |
| `BlackDamageTook` | dummy | 累计黑心承伤 |
| `DamageHealed` | dummy | 累计治疗 |
| `SoulHeartsGained` / `BlackHeartsGained` | dummy | 累计获得 |
| `CTT_HP` / `CTT_HPCheck` | dummy | vanilla health 反算用（不要写入） |
| `Pierce_Damage` | dummy | vanilla 伤害源差值 |

### 19.3 伤害类型（"输入"端）

| Objective | 类型 | 含义 |
|---|---|---|
| `Damage` | dummy | 综合伤害（每 tick 末重置） |
| `MeleeDMG` / `BulletDMG` / `FireDMG` / `IceDMG` / `DarkDMG` / `LightDMG` / `ElectricDMG` / `ForceDMG` | dummy | 按类型分发 |
| `HealDMG` | dummy | 治疗值 |
| `BlackDMG` / `SoulDMG` | dummy | 跳过 `Damage`，直接修黑心/灵魂心 |
| `RandomElementDMG` | dummy | 随机元素 |
| `FriendlyDMG` | dummy | 攻击者友伤标记 |
| `FriendlyID` | dummy | 受伤实体的"主人 PlayerID" |
| `OwnerID` | dummy | 弹射物 / 召唤物的所属玩家 |

### 19.4 DamageShower 粒子

| Objective | 类型 | 含义 |
|---|---|---|
| `DamageShower` | dummy | 该粒子代表的伤害值 |
| `DamageShower1` / `DamageShower2` | dummy | 动画状态 |
| `#DamageShowerAmount CTT` | dummy | 计数（>10 随机 kill） |
| `#DamageNumbers CTT` | dummy | 1..4 偏移方向循环 |

### 19.5 全局游戏状态

| Objective | 类型 | 含义 |
|---|---|---|
| `#PauseGame CTT` | dummy | 暂停标志 |
| `#GameOver CTT` | dummy | 已失败 |
| `#GameOverContinueScreen CTT` | dummy | 续币界面 |
| `#LobbyMiniGame CTT` | dummy | 0..8 模式 ID |
| `#Tier CTT` / `#Floor CTT` / `#FloorT CTT` | dummy | 关卡定位 |
| `#Dungeon` / `#Shop` / `#MBoss` / `#Boss` / `#Ally` / `#Misc` | dummy（CTT 假玩家） | Stage holder |
| `#BreakRoom CTT` / `#BreakRoomID CTT` / `#CheckPoint CTT` | dummy | 休息室与检查点 |
| `#ServerLag CT` / `#4Tick CT` / `#20Tick CT` / `#RNG CT` | dummy | 系统计时器 |

### 19.6 资源 / 货币

| Objective | 类型 | 含义 |
|---|---|---|
| `Coins` / `Tokens` / `XP` / `Level` | dummy | 货币 / 经验 |
| `Mana` / `MaxMana` / `ManaPower` / `ManaRechargeSpeed` | dummy | 法力 |
| `ActiveCharge` / `MaxActiveCharge` / `ActiveSpeed` | dummy | 主动技能充能 |
| `Stamina` / `MaxStamina` / `StaminaSpeed` | dummy | 体力 |
| `Air` / `MaxAir` | dummy | 氧气 |

### 19.7 攻击属性

| Objective | 类型 | 含义 |
|---|---|---|
| `Strength` / `Archery` / `AttackSpeed` / `AttackRange` | dummy | 主战属性 |
| `Healing` / `HealPercent` / `ExtraHealing` / `TowersRegen` | dummy | 治疗系 |
| `Summoning` / `MaxSummons` / `Fishing` / `BeePoints` / `AntPoints` / `Bitches` | dummy | 杂项属性 |

### 19.8 业力 / 头骨

| Objective | 类型 | 含义 |
|---|---|---|
| `LightKarma` / `NeutralKarma` / `DarkKarma` / `CelestialKarma` | dummy | 业力 |
| `LightKarmaCost` / `DarkKarmaCost` | dummy | 业力消耗 |
| `#Skulls CTT` | dummy | 骷髅头计数 |

### 19.9 移动

| Objective | 类型 | 含义 |
|---|---|---|
| `MaxSpeed` / `SpeedAmplifier` / `SpeedRaw` / `MaxSpeedRaw` | dummy | 速度叠加项 |
| `Jump` / `DoubleJump` | dummy | 跳跃属性 |
| `Size` / `Gravity` | dummy | 体型 / 重力 |
| `JumpPressed` / `OnGround` / `Shifting` / `MovementLocked` | dummy | 即时状态（`misc:server_main`） |
| `Frozen` / `CantMove` | dummy | 冻结 / 无法移动 |

### 19.10 触发器 / 事件

| Objective | 类型 | 含义 |
|---|---|---|
| `ViewStats` | trigger | 触发属性面板 |
| `Help` / `Customize` / `ViewProfile` / `CraftingGuide` / `GetDate` / `ToggleVoiceActing` | trigger | 玩家命令 |
| `RightClick` / `SG_RightClick` | dummy | 右键事件 |
| `Reload` | dummy | OP 触发 reload |
| `ArrowShoot` / `CupidArrowShoot`(`used:bow`) | mixed | 弓使用 |
| `DroppedEnderEye` (`minecraft.dropped:minecraft.ender_eye`) | criteria | inventory_changed 检测 |

### 19.11 武器伤害 criterion

| Objective | 类型 | 含义 |
|---|---|---|
| `CupidsSpearDMG`, `FireAspectDMG`, `va_*D` 等 | `minecraft.custom:minecraft.damage_dealt` | 各武器对玩家累计伤害（**不一定等于 mod 关注的伤害**） |
| `CupidArrowShoot`, `CupidArrowHold`, `CupidQuiverA` | mixed | 丘比特弓相关 |

> 这些 criterion 是 vanilla 计的"player did damage to mob"，**不会**记录元素分类，也**不**反映 4 层心数。仅作辅助校验，不要做主数据源。

### 19.12 GameScores / 诅咒

见 §15。

### 19.13 累计 / 历史 / 调试

| Objective | 类型 | 含义 |
|---|---|---|
| `T29_TotalDamageTook`, `T29_DamageTook` | dummy | 楼层累计承伤（特定挑战） |
| `M08_KillStreak` | dummy | 连杀记数 |
| `C121_ArrowsFired` | dummy | 累计放箭 |
| `TheHeartBurnerTotalKills` | dummy | 心烧者武器击杀计 |

---

## 20. Tag 速查表

### 20.1 实体角色

`CTT`, `CTTAll`, `Respawning`, `Dead`, `SpectatingGame`, `ReadyToPlay`, `OP`, `Debug`, `NewPlayer`, `CTT_LeftServer`

### 20.2 友/敌/中立

`F`, `E`, `Boss`, `MidBoss`, `Summon`, `NPC`, `TestDummy`, `Coffin`, `Prop`, `Undead`

### 20.3 即时事件 / 攻击证据

`Atk`, `Attack`, `Hit`, `Charge`, `Hold`, `Use`, `Cast`, `Shoot`, `Fire`, `Target`, `ElementMelee`, `PlayerHurtSound`, `PlayerHurtSoundBig`, `FriendlyFirePunish`, `Bullet`, `WP_WasPushed`, `FloorPushHasHappened`

### 20.4 召唤 / 弹射 / 标识

`Spawn`, `LobbyProp`, `RandomDungeon`, `RandomShop`, `RandomMBoss`, `RandomBoss`, `RandomAlly`, `Marker`

### 20.5 玩家个性 / 角色（VA 等）

`Swan`, `SwanJelly`, `Wazy`, `DarkSwan`, `Super`, `Moose`, `Lazy`, `Smash`, `Thew`, `Ben`, `Nut`, `Joey`, `BDash`, `JJ`, `HenryPlayer`, `Laozi`, `Nelfin`, `Timmy` —— **mod 应忽略这些**，它们只用于语音 / 皮肤分发。

### 20.6 QuickStart / Lobby 入口

`QuickStartPlayer`, `QuickStartMagumTrialsLobbyPlayer`, `QuickStartMagumTrialsPlayer`, `QuickStartHeartOfOtherside`, `QuickStartMuckSurvivor`, `LazysBowTraining`, `HorseRaceMinigame`, `JellyTrialsMinigame`, `MagumTrials`, `MagumTrialsInstaSkipFirstScene`, `SnowballCivilization`, `HeartOfOtherSide`, `HeartOfOtherSideInstaSkipFirstScene`, `MuckSurvivor`, `MuckSurvivorInstaSkipFirstScene`, `LoveIsABattlefield`

### 20.7 Boss / 楼层特定

`Spider_Spawn`, `Dharkon_Tower`, `Guard`, `StoneGolem`, `Yeti`, `Kai`, `Quartz`, `Android`, `Flameo`, `MagumInferno`, `Bug`, `HealShroom`, `FlowerGirl`, `Creeking`, `CreekingHeartSounds`, `EndCrystal`, `FlorieFlower`, `Freezer`, `Peewee`, `SpiritEnemy`, `HurtSound`, `NoHurtSound`, `MaxHealthChange`, `Targetting`, `HealthBar1001`..`HealthBar1010`, `DefenceOver20`, `DiesFromElementium`, `ElementiumHit`, `CinematicDeath`, `CinematicDeath1`, `TeethedSeekling`

> 列表不全；遇到陌生 tag 时，**只采集你能解读的**，其余按"L9_NONE / 普通敌人"处理。

---

## 附录 A：已知 mod 实现要点速查

| 需求 | 实现方式 | 出处 |
|---|---|---|
| 检测开新局 | Mixin `ServerScoreboard#setScore`，看 `#CTT GameID` 是否变化 | `ServerScoreboardMixin` |
| 检测进/出 stage | 监听六个 holder + Tier 复合 | `StageTracker` |
| 主血条 | 读 `RedHearts` / `MaxHP` 而非 `Entity#health` | `HealthData` |
| 4 层心数显示 | 直接读 `RedHearts`/`SoulHearts`/`BlackHearts`/`BlueHearts` | `HealthData` |
| 玩家承伤 | `RedDamageTook` 增量 (`useRedHeartsTally=true`) 或 `DamageShower` 粒子 | `ServerConfig.useRedHeartsTally` |
| 伤害类型分类 | Mixin `*DMG` 写入；按写入顺序 + tag 证据归 L1..L8 | `AttackerProbe` |
| 攻击者归属 | 受害者附近最近的 `tag=CTT` 玩家 + DoT carry | `AttackerProbe` |
| 友军 / 召唤物伤害 | 读 `FriendlyID` / `OwnerID` | `AttackerProbe` |
| 暂停 / GameOver | `#PauseGame CTT` / `#GameOver CTT` 检测，停止增量 | 待 / 已实现 |
| 显示属性面板 | 直接读最终值（`Strength` 等无后缀） | `StatsData` |

## 附录 B：地图作者拼写约定（避坑）

- `Defence`（**英式**，不是 Defense）
- `Targetting`（双 t，不是 Targeting）
- `MeteroiteDungeon`（拼写错误但保留，**不要纠正**）
- `Bitches`（爱称类 score，不是骂人，地图作者风格）
- `va_*` 前缀：voice acting，与 mod 无关
- `H2_*`、`S1_*`、`R1_*`、`D3_*`、`T29_*`、`M08_*`、`M83_*`、`M87_*`、`M88_*`、`C121_*`、`C129_*`、`AS08_*` 等前缀：楼层 / Boss / 物品的命名空间（H/S/R/D/T/M/C/AS = Hub/Shop/Rooftop/Dungeon/Tower/MidBoss/Cosmetic/AfterShop？地图作者未公开规则，按上下文识别即可，不需要解析前缀含义）。

---

## 维护说明

- 当地图升级（pack_format 变化或新 Tier 引入）时，**必须**重新走 `gamestart.mcfunction`、`misc/health.mcfunction`、`misc/damage.mcfunction`、`view_stats.mcfunction` 四个核心文件，对照本文 §3..§11 各小节更新。
- 当 mod 大版本（v6 → v7）跳跃时，可考虑把 `MAP_DATAPACK_ANALYSIS.md` 归档进 `docs/archive/`，本文作为唯一权威。
- `MAP_DATAPACK_ANALYSIS.md` 与本文出现冲突时，**以本文为准**。
