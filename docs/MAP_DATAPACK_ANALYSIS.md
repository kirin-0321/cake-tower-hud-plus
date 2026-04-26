# Cake Team Towers 数据包深度分析

> **目的**：为 `ctt-health-display` v6 "统计伤害/击杀/承受" 功能（双端 mod，需同时在服务端和客户端安装）的服务端侧数据采集设计提供事实依据。本文档只记录**对 mod 有意义的**数据包实现细节——我们不关心 voice acting、cosmetics、roof endings 等纯剧情/美化的内容。
>
> **地图版本**：Cake Team Towers Chapter 3 Update #4.0.12 (The Heart of Otherside) (Premium)
> **读取日期**：2026-04
>
> 目录：
>
> 1. 全景：数据包结构与 tick 入口
> 2. 玩家生命周期（`CTT` / `CTTAll` / `Respawning`）
> 3. 全局游戏状态（`GameID` / `GameOver` / `LobbyMiniGame`）
> 4. 关卡状态（`#Tier` / `#Floor` / 6 个 stage holder / `#BreakRoomID`）
> 5. 关卡切换触发链路（休息室自增机制）
> 6. 伤害/治疗/击杀的服务端事件路径
> 7. 敌人与玩家的 tag 体系（`E` / `Boss` / `TestDummy` / ...）
> 8. 对 v6 统计 mod 的设计结论（核心章节）
> 10. 【v6 修订】伤害的权威定义与最终采集方案
> 11. 【v6.0.1 订正】切换到 DamageShower 粒子
> 12. 【v6.0.4】攻击者归属：从 PlayerID 约定反推

---

## 1. 全景：数据包结构与 tick 入口

### 1.1 数据包物理结构

地图 `datapacks/` 目录下有三个包：

| 包 | 作用 |
|---|---|
| `CakeTeamPack` | 主数据包，包含 CTT 全部游戏逻辑 |
| `Misc` | 辅助模组（`zt-var` 数学库） |
| `bukkit` | 空壳（只有 pack.mcmeta） |

`CakeTeamPack` 的 `data/` 下共 10 个命名空间。**和 CTT 游戏相关的只有两个**：

- `cake_team_tower` —— CTT 核心逻辑（gamestart、floors、lobby、items、misc/、enemies/ 等）
- `misc` —— 跨游戏通用库（server_main.mcfunction、scoreboards、clear_tags 等）

其他命名空间（`cake_wars` / `smashcraft` / `mario_party` / `survival_twists` / `crafting` / `animated_java` / `attackspeed`）和我们无关。

### 1.2 Tick 入口链路

关键事实：**`minecraft:tick` 函数标签里根本没挂 `cake_team_tower:main`**。

`data/minecraft/tags/function/tick.json`：

```json
{
  "values": [
    "animated_java:global/on_tick",
    "attackspeed:main",
    "attackspeed:main_1",
    "attackspeed:main_2"
  ]
}
```

CTT 的主循环通过 **`cake_team_tower:misc/pause_game`**（命令方块驱动，不在 tick.json 中）守护并触发 `cake_team_tower:main`。对 mod 来说这细节不重要——**只需假设 `cake_team_tower:main` 在游戏进行中每 tick 被调用一次**。

### 1.3 主循环函数链

```
cake_team_tower:misc/pause_game
  ├── (score #PauseGame CTT == 0) → cake_team_tower:main
  └── (score #PauseGame CTT >= 1) → 暂停逻辑（title "Game Paused"）

cake_team_tower:main   ← 每 tick 核心入口
  ├── function misc:server_main            // 跨游戏通用 tick（JumpPressed / XYZ / Undead tag 等）
  ├── [unless CTT] cake_team_tower:lobby/lobby_main
  ├── function cake_team_tower:misc/misc   // 巨型总汇 (1400+ 行)
  ├── [Dungeon/Shop/MBoss/Boss/Ally/Misc 任一>=1] cake_team_tower:floors/_floor_universal
  ├── function cake_team_tower:misc/health
  ├── function cake_team_tower:misc/damage_universal
  ├── [BossbarID=1001..1010] cake_team_tower:misc/bossbars/p{N}_bossbar
  ├── function cake_team_tower:misc/bossbars/bossbars_universal
  ├── function cake_team_tower:misc/stats           // ViewStats 文本构造
  ├── function cake_team_tower:misc/stats_done
  └── ...
```

一次性函数（非 tick）：

- `cake_team_tower:gamestart` —— 从 Lobby 进入游戏时触发一次
- `cake_team_tower:floors/scenes/break_room_universal` —— 进入休息室时触发（由 `_floor_universal` 条件调用）

---

## 2. 玩家生命周期

### 2.1 tag `CTT` = 正在玩的玩家

- **授予点**：`cake_team_tower:gamestart:6`
  ```
  tag @a[tag=ReadyToPlay] add CTT
  ```
  玩家在 Lobby 按下"开始游戏"后获得 `ReadyToPlay` tag → `clear_tags` 清掉一切残留 → `add CTT`。

- **移除点**：
  - 死亡并无剩余生命时，由 `cake_team_tower:misc/misc/warp_lobby` 相关分支触发（实际代码里玩家死亡主要是 `add Dead` + `Lives=0`，然后被传送回 lobby 并 `remove CTT`）。
  - 玩家 `/trigger WarpLobby` → 分支到 `cake_team_tower:misc/warp_lobby`。

- **对 mod 的意义**：`tag=CTT` 是"当前局内玩家"的权威标识。**统计采集只对 `CTT` 玩家进行**，spectator/lobby 玩家忽略。

### 2.2 tag `CTTAll` = 游戏相关实体总集

`cake_team_tower:misc/misc:30-31`：

```
tag @a[tag=CTT] add CTTAll
tag @e[tag=E] add CTTAll
tag @a[tag=SpectatingGame] add CTTAll
execute if score #20Tick CT matches 1 run tag @e[tag=F] add CTTAll
```

**不是"所有玩家"，而是"所有与本局相关的实体"**（玩家 + 敌人 + 旁观者 + F 标记友军）。mod 不该直接用它过滤玩家。

### 2.3 tag `Respawning` = 等待复活

- 玩家死亡（`AllHearts <= 0`）被 `add Dead`，随即进入 respawn 流程并获得 `Respawning`。
- `#GameOver CTT` 的语义（见 §3.2）会和 Respawning 互动。
- **统计意义**：Respawning 玩家的伤害/击杀数据应当**继续累计到其 session**，因为他只是处于死亡倒计时，不是下线。

### 2.4 tag `Dead`（单 tick 脉冲）

`cake_team_tower:misc/misc:40`：

```
tag @a[tag=CTT,scores={AllHearts=..0,Lives=1..}] add Dead
```

Dead tag 只在死亡瞬间挂一 tick，用于触发 VA 音效等，对统计没用——**死亡作为事件更适合通过 `Lives` 计分板的值变化检测**。

---

## 3. 全局游戏状态

### 3.1 `GameID`（Session ID，单调递增）

由 `cake_team_tower:gamestart:139-140` 管理：

```
scoreboard players add #CTT GameID 1
scoreboard players operation @a[tag=CTT] GameID = #CTT GameID
```

- `#CTT GameID`：全服累计启动游戏次数（永不重置）。
- `@player GameID`：玩家所在局的 ID。
- **判定"换新局"唯一可靠信号**：`#CTT GameID` 发生变化 → 所有 per-session 统计应当 flush 并清零。

### 3.2 `#GameOver CTT` 状态机

| 值 | 含义 | 触发点 |
|---|---|---|
| `0` | 未结束 | 初始 / 新局 / `/trigger Continue` |
| `1..98` | "死亡结算中"——所有玩家都挂了，倒计时 | `misc/misc:387` 当无活人且 misc ≠ 5 时 `add 1` |
| `99` | "可继续"屏（显示 Continue/Heart of Otherside 等续命道具） | `misc/misc:569` 当 `#GameOver == 100 && !NoContinue && ContinueScreen < 200` 时设回 99 |
| `100` | 最终 GAME OVER（锁定屏） | `99` 自然推进或 `NoContinue == 1` 直接跳过 |

- **Session 结束信号**：`#GameOver >= 1` 首次出现 → 可以视作 stage 结束但 session 未结束；真正的 session 结束要看 `#CTT GameID` 变化（见 §3.1）。
- 玩家可以 `/trigger Continue set 1` 在 99 时付费续命，此时 `#GameOver` 回到 0。

### 3.3 `#LobbyMiniGame CTT`（小游戏模式）

`gamestart:37-45`：

```
scoreboard players set #LobbyMiniGame CTT 0
execute if entity @a[tag=LazysBowTraining]     run scoreboard players set #LobbyMiniGame CTT 1
execute if entity @a[tag=HorseRaceMinigame]    run scoreboard players set #LobbyMiniGame CTT 2
execute if entity @a[tag=JellyTrialsMinigame]  run scoreboard players set #LobbyMiniGame CTT 3
execute if entity @a[tag=MagumTrials]          run scoreboard players set #LobbyMiniGame CTT 4
execute if entity @a[tag=SnowballCivilization] run scoreboard players set #LobbyMiniGame CTT 5
execute if entity @a[tag=HeartOfOtherSide]     run scoreboard players set #LobbyMiniGame CTT 6
execute if entity @a[tag=MuckSurvivor]         run scoreboard players set #LobbyMiniGame CTT 7
execute if entity @a[tag=LoveIsABattlefield]   run scoreboard players set #LobbyMiniGame CTT 8
```

| 值 | 模式 |
|---|---|
| `0` | 主剧情（标准 Tower 模式，我们的核心关注点） |
| `1..8` | 各类小游戏（可能没有标准的 #Floor/#Boss 流程） |

v1 mod 建议**只在 `#LobbyMiniGame CTT == 0` 时启用统计**，其他模式作为"特殊类"聚合或跳过。

### 3.4 `BossbarID`（玩家专属 bossbar 槽位）

`gamestart:265-275`：给每个 CTT 玩家随机分配 `1001..1010`，用于 `cake_team_tower:misc/bossbars/p{N}_bossbar` 路由——和统计无直接关系，但解释了当前 mod 读 bossbar 的原理。

---

## 4. 关卡状态（核心）

### 4.1 `#Tier CTT`（大关/章节）

- 值域：`1..∞`（实际最多到 3-5）
- `gamestart:100` 初始化为 `1`
- `break_room_universal:10, 13` 每过 10 floor（FloorAmount=1）或 5 floor（FloorAmount=3）`add 1`
- **意义**：大章节号，对统计分组有帮助（总表里可显示 `T1 / T2 / T3`）

### 4.2 `#Floor CTT`（小关序号）

- 值域：`1..10`（对主模式）、`1..5`（对 Compact 模式）
- `gamestart:62` `reset #Floor CTT`
- **自增唯一点**：`break_room_universal:5`：
  ```
  execute if score #BreakRoom CTT matches 1 unless score #CheckPoint CTT matches 1
  unless score #BR2_CameFromShop GameScores matches 1
  run scoreboard players add #Floor CTT 1
  ```
  即：**每次进入休息室（`#BreakRoom == 1` 单 tick 脉冲）时 `#Floor += 1`**。
- 越界回绕：`#Floor >= 11` → set 1 + `#Tier += 1`。
- **意义**：这是"当前在第几关"的权威信号。

### 4.3 六个 stage holder（关卡类型）

在 `break_room_universal:17-32` 每次进入休息室时**全部清零**（除非 `#CheckPoint == 1`）：

```
scoreboard players set #Dungeon  CTT 0
scoreboard players set #Shop     CTT 0
scoreboard players set #MBoss    CTT 0
scoreboard players set #Boss     CTT 0
scoreboard players set #Ally     CTT 0
scoreboard players set #Misc     CTT 0
scoreboard players set #FloorT   CTT -101
```

进入具体关卡时，某个 holder 被设为**关卡编号**（例如 `#Dungeon CTT 47` = The Race 关卡）。

| holder | 关卡类型 | 已知编号范围 |
|---|---|---|
| `#Dungeon` | 普通副本 | 1..~100（如 47=The Race） |
| `#Shop` | 商店层 | 1..~30 |
| `#MBoss` | 小 boss 层 | 1..~30 |
| `#Boss` | 大 boss 层 | 1..~15 |
| `#Ally` | 盟友/NPC 层 | 1..~15 |
| `#Misc` | 杂项（教程、练习、Lazys Bow 等） | 1..~20（4=Lazys Bow, 5=Tutorial, 6=Jelly Trials, 8=Snowball Civ, 11=Training Grounds） |
| `#FloorT` | 过渡阶段计时（负值=过场，正值=在战） | -101..∞ |

**任一 holder ≥ 1 表示"当前正在战斗层"**：

```
cake_team_tower:main:46-55
execute if score #Dungeon CTT matches 1.. if entity @a[tag=CTT] run function cake_team_tower:floors/_floor_universal
execute if score #Shop    CTT matches 1.. ...
execute if score #MBoss   CTT matches 1.. ...
execute if score #Boss    CTT matches 1.. ...
execute if score #Ally    CTT matches 1.. ...
execute if score #Misc    CTT matches 1.. ... (+MuckSpeed/#4Tick 条件)
```

### 4.4 `#BreakRoom CTT`（休息室计数脉冲）

- 初始化：`gamestart:61` reset。
- 每 tick 在 `break_room_universal:2` `add 1`（只要玩家在休息室区域内）。
- **`#BreakRoom == 1`** 是**上关结束的单 tick 脉冲**，会触发 `#Floor += 1`、所有 stage holder 清零、金币奖励、XP 奖励等。
- 离开休息室：`break_room_universal:299` 当无 CTT 玩家时 `reset #BreakRoom`。

**这是 mod 最关键的事件点**：`#BreakRoom CTT == 1` 的上升沿 = "刚打完一关"。

### 4.5 `#BreakRoomID CTT`（休息室类型）

`break_room_universal:395-407`（从 bossbar 设置反推）：

| 值 | 休息室 |
|---|---|
| `0` | The Tower（主塔，默认） |
| `1` | Arced Void（副塔） |
| `2` | 特殊模式（不可存档） |
| `3` | World War Bee（副塔） |
| `4` | Oculus Forest（副塔，固定 5 floor） |
| `5` | 特殊（不可存档） |
| `6` | Magum Trials（特殊） |

### 4.6 `#FloorsCompleted` 和 `#TotalFloors`（计算出的进度）

`break_room_universal:372-392` 每次在休息室重新计算：

```
#FloorsCompleted = (#Tier - 1) * (5 if FloorAmount==3 else 10) + #Floor
#TotalFloors = 30 (FloorAmount=1) / 50 (FloorAmount=2) / 15 (FloorAmount=3)
              + {0,5,5,-45} 根据 BreakRoomID
```

这两个值会显示在顶部 `floor` bossbar（mod 可以直接复用字符串："The Tower (12/30)"）。

### 4.7 `#CheckPoint CTT`（存档点）

- `#CheckPoint == 1` 时 `break_room_universal` 会**跳过大部分清理**（不清 Dungeon/Shop/…，不加 Floor）。
- 意义：`/trigger SaveGame` 或到了存档点，不算"完成一关"。
- **mod 侧**：`#BreakRoom == 1 && #CheckPoint == 0` 才算真正的关卡完成。

---

## 5. 关卡切换触发链路（可视化）

### 5.1 典型一局流程（时序）

```
Lobby 中
  └─ 玩家进入游戏起点 → 一次性 function cake_team_tower:gamestart
     ├─ #CTT GameID += 1
     ├─ tag @a[tag=ReadyToPlay] add CTT
     ├─ scoreboard players reset #Floor/#Boss/#MBoss/#Dungeon/#Shop/#Ally/#Misc CTT
     ├─ scoreboard players set #FloorT CTT -101
     ├─ scoreboard players set #BreakRoomID CTT 0
     └─ scoreboard players set #LobbyMiniGame CTT 0..8

[tick 循环开始]

Tier=1, Floor=?, 在休息室中
  └─ #BreakRoom 从 0 逐 tick 自增
     ├─ #BreakRoom == 1 (上升沿) → break_room_universal:
     │   ├─ #Floor += 1       ← 关卡号递增
     │   ├─ all stage holders = 0
     │   ├─ #FloorT = -101
     │   └─ 金币/XP/奖励
     └─ #BreakRoom >= 2 时维持休息室状态

玩家传送进战斗层 → 某个 stage holder 被设置（例如 #Dungeon CTT 47）
  └─ cake_team_tower:floors/_floor_universal 根据 holder 分派到具体关卡函数
     └─ #FloorT 逐步从负值推进到正值（过场→进入战斗）

玩家击杀所有敌人/达成目标 → 传送回休息室（不同关卡有不同出口逻辑）
  └─ reset #BreakRoom 或清除休息室 tag → 回到 "Tier=1, Floor=?, 在休息室中" 循环

...

所有玩家死亡（每个 CTT 玩家 Lives=0 && AllHearts<=0）
  └─ #GameOver CTT 1..98 (倒计时)
     └─ #GameOver CTT 99 (Continue 屏)
        ├─ 玩家 /trigger Continue → #GameOver = 0, 回到战斗
        └─ 超时/放弃 → #GameOver = 100 (最终结束)
           └─ 玩家回 lobby → remove CTT (此时 #CTT GameID 不变，直到新局 gamestart)
```

### 5.2 Mod 采集的关键事件（时序匹配）

| Mod 事件 | 数据包信号 | 检测方式 |
|---|---|---|
| "新局开始" | `#CTT GameID` 值递增 | 每 tick 读，变化即触发 |
| "上一关完成" | `#BreakRoom CTT` 从 0 → 1 | 上升沿 + `#CheckPoint == 0` + `#BR2_CameFromShop == 0` |
| "进入新关" | stage holder 任一 `0 → ≥1` | 上升沿（任一 holder 变为非零） |
| "返回大休息室" | 玩家 `remove CTT` 或在休息室且 holder 全 0 | 持续检测 |
| "本局结束" | `#GameOver CTT` 首次 ≥ 1 | 上升沿 |
| "继续一条命" | `#GameOver CTT` 从 ≥1 → 0 | 下降沿 |
| "最终结束" | `#GameOver CTT` = 100 | 值检测 |
| "玩家死亡" | 玩家 `Lives` 递减 或 `tag=Dead` 脉冲 | 差值或 tag 检测 |

---

## 6. 伤害 / 治疗 / 击杀的服务端事件路径

### 6.1 原始伤害管线

`cake_team_tower:main:66` → `cake_team_tower:misc/damage_universal`：

```
# damage_universal.mcfunction（共 24 行）
execute if entity @e[scores={Pierce_Damage=1..}] run function cake_team_tower:misc/damage
execute if entity @e[scores={HealDMG=1..}]       run function cake_team_tower:misc/damage
execute if entity @e[scores={FireDMG=1..}]       run function cake_team_tower:misc/damage
execute if entity @e[scores={MeleeDMG=1..}]      run function cake_team_tower:misc/damage
...14 种伤害类型
```

每种伤害是一个**独立 scoreboard objective**（`MeleeDMG` / `FireDMG` / `IceDMG` / `DarkDMG` / `SoulDMG` / `BlackDMG` / `ForceDMG` / `LightDMG` / `BulletDMG` / `ElectricDMG` / `Pierce_Damage` / `WaterDMG` / `RandomElementDMG` / `HealDMG`）。

流程是：**施法者/武器代码把预期伤害值 set 到受害者的这些计分板，`damage_universal` 每 tick 轮询处理并应用到实际 HP**。

### 6.2 原版统计 objective（`minecraft.custom`）

`scoreboards.mcfunction` + `scoreboards_part_2.mcfunction` 中，地图给**每把特殊武器**都挂了一个独立 objective：

```
scoreboard objectives add CupidsSpearDMG    minecraft.custom:minecraft.damage_dealt
scoreboard objectives add FireAspectDMG     minecraft.custom:minecraft.damage_dealt
scoreboard objectives add JellySwordDMG     minecraft.custom:minecraft.damage_dealt
...约 100+ 个
```

- 但**没有一个全局的 `damage_dealt` / `damage_taken` / `mob_kills` objective**可供 mod 使用。
- 这些按武器拆分的 objective 单位是"原版伤害分"（1 damage = 10 units），且每把武器各自累计——**不适合作为统计源**。

### 6.3 击杀检测

地图本身**没有全局 kill tracker**。最可靠的方式：Fabric Mixin `LivingEntity.onKilled` / `ServerPlayerEntity.onAttackedBy`，在服务端 mod 内自己记录。

### 6.4 治疗检测

`HealDMG` objective 被用于"治疗 = 负伤害"语义（见 `break_room_universal:244`：`+{HealDMG}♥`）。若玩家用治疗道具治疗队友，治疗者的行为应当由 mod 通过 Mixin `LivingEntity.heal` 或 item use callback 捕获——**不要指望数据包信号**。

### 6.5 Mod 侧的伤害采集结论

**不要使用数据包计分板作为主数据源。** 理由：
1. 14 种伤害类型 objective + 100+ 武器 objective = 极度碎片化，难以聚合。
2. 单位是原版分（*10），易出现 off-by-one。
3. 部分伤害是在"将要造成"阶段被拦截（盾、闪避）→ 计分板值不等于最终 HP 变化。

**正确做法**：服务端 mod 使用 Mixin 挂到：

- `LivingEntity.damage(DamageSource, float)` —— 捕获**最终应用**的伤害。参数里可取到 `attacker`（通过 `source.getAttacker()`）。
- `LivingEntity.heal(float)` —— 捕获实际治疗。
- `LivingEntity.onKilled(DamageSource)` —— 捕获击杀事件。

配合本文 §2-§4 的状态读取来做归属和分组：

```
onDamage(victim, source, amount):
    attacker = source.getAttacker()
    if attacker is ServerPlayerEntity and attacker hasTag "CTT":
        if #GameOver CTT != 0 or not inActiveStage(): return  # 过滤
        stageKey = computeStageKey()  # 见 §8.3
        stats[attacker][stageKey].dmgDealt += amount
    if victim is ServerPlayerEntity and victim hasTag "CTT":
        stats[victim][stageKey].dmgTaken += amount
```

---

## 7. Tag 语义速查表

| Tag | 挂在谁身上 | 含义 | 对统计的相关性 |
|---|---|---|---|
| `CTT` | 玩家 | 当前局内玩家（核心标识） | **主过滤条件** |
| `CTTAll` | 玩家+敌人+旁观者+F | "本局全部相关实体" | 不用 |
| `Respawning` | 玩家 | 等待复活 | 统计继续累计 |
| `Dead` | 玩家 | 刚死的单 tick 脉冲 | 可选：死亡计数源 |
| `SpectatingGame` | 玩家 | 旁观本局 | 统计不采集 |
| `ReadyToPlay` | 玩家 | 即将进入游戏（gamestart 前 1 tick） | 过滤新局初始化 |
| `E` | 生物 | 敌人通用 tag | **被击杀对象白名单** |
| `Boss` | 生物 | boss 级敌人 | 可选：boss kill 单独计数 |
| `F` | 生物 | 友军（ally 关卡里的 NPC） | 伤害不计入 dmg_dealt |
| `TestDummy` | 生物 | 假人（主要在休息室） | 不用特别过滤——会被"休息室停止统计"规则覆盖 |
| `NPC` | 生物 | 对话 NPC | 不计入击杀 |
| `OP` | 玩家 | 管理员/调试 | 可能伤害异常，视需要过滤 |
| `Debug` | 玩家 | Debug 模式 | 视需要过滤 |

---

## 8. 对 v6 统计 mod 的设计结论（核心）

### 8.1 数据采集：Mixin 而非计分板

- 服务端 mod 通过 Mixin 拦截 `LivingEntity.damage` / `.heal` / `.onKilled`，直接取数值。
- 状态读取（stage key、是否在关中、session ID）通过 `ServerScoreboard` API 读取 `#CTT GameID`、`#Floor CTT`、stage holders、`#BreakRoom CTT`、`#GameOver CTT`、`#LobbyMiniGame CTT`、`#BreakRoomID CTT`。

### 8.2 采集开关（is_stat_enabled）

伪代码：

```java
boolean isStatEnabled(ServerPlayerEntity p) {
    if (!p.hasTag("CTT")) return false;
    if (p.hasTag("SpectatingGame")) return false;
    int gameOver = getScore("#GameOver", "CTT");
    if (gameOver >= 1 && gameOver <= 98) return false;  // 死亡倒计时不采集
    if (gameOver >= 100) return false;                   // 最终结束不采集
    if (isInLobby()) return false;                       // 在主 lobby 不采集
    if (isInBreakRoom()) return false;                   // 在休息室不采集
    int lobbyMini = getScore("#LobbyMiniGame", "CTT");
    if (lobbyMini != 0) return false;                    // v1 只采集主剧情
    return true;
}

boolean isInBreakRoom() {
    // break room 判定：所有 stage holder 都 <= 0
    return getScore("#Dungeon", "CTT") <= 0
        && getScore("#Shop",    "CTT") <= 0
        && getScore("#MBoss",   "CTT") <= 0
        && getScore("#Boss",    "CTT") <= 0
        && getScore("#Ally",    "CTT") <= 0
        && getScore("#Misc",    "CTT") <= 0;
}
```

**关键**：休息室判定用"所有 stage holder 都 == 0"即可，**无需依赖 `#BreakRoom` 计数器**，也**自动过滤测试假人**（假人在休息室内被攻击时 stage holders 都为 0）。

### 8.3 Stage Key（stage 唯一键）

`StageKey = (GameID, Tier, Floor, StageType, StageNum)`

```java
StageKey key(ServerPlayerEntity p) {
    int gameId = p.getScore("GameID");  // 玩家个人 GameID（gamestart 赋值）
    int tier   = getScore("#Tier",  "CTT");
    int floor  = getScore("#Floor", "CTT");
    int stageType, stageNum;
    if ((stageNum = getScore("#Boss",    "CTT")) > 0) stageType = BOSS;
    else if ((stageNum = getScore("#MBoss",   "CTT")) > 0) stageType = MBOSS;
    else if ((stageNum = getScore("#Dungeon", "CTT")) > 0) stageType = DUNGEON;
    else if ((stageNum = getScore("#Shop",    "CTT")) > 0) stageType = SHOP;
    else if ((stageNum = getScore("#Ally",    "CTT")) > 0) stageType = ALLY;
    else if ((stageNum = getScore("#Misc",    "CTT")) > 0) stageType = MISC;
    else return null;  // in break room
    return new StageKey(gameId, tier, floor, stageType, stageNum);
}
```

7 类 stage 全部作为独立 stage 记录（Floor / Boss / MBoss / Dungeon / Shop / Ally / Misc）。

### 8.4 Session 生命周期

- **Session = 一个 GameID 的完整生命周期**。
- 开始信号：`#CTT GameID` 发生递增（在 tick 循环中检测值变化）。
- 结束信号：**不显式结束**——session 一直保持，直到下一次 `#CTT GameID` 递增时被归档。
  - `#GameOver CTT == 100` → 标记 session "已 GAME_OVER"，但数据仍保留在面板上（符合用户决定"新局才清"）。
  - 玩家 `/trigger Continue` → session 继续。

### 8.5 数据模型（草稿）

```java
record StageRecord(
    long gameId,       // 对应 #CTT GameID
    int  tier,
    int  floor,
    int  stageType,    // 0=Floor 1=Boss 2=MBoss 3=Dungeon 4=Shop 5=Ally 6=Misc
    int  stageNum,     // holder 里的值
    // per-player stats:
    Map<UUID, PlayerStageStats> players
);

record PlayerStageStats(
    UUID  uuid,
    String name,
    int   dmgDealt,    // 累计
    int   dmgTaken,
    int   kills,
    int   boss_kills,  // 可选子分类
    int   heals,       // v3 再上
    long  durationMs   // 在该 stage 停留时长
);
```

### 8.6 持久化（服务端写 `world/data/ctt_stats.dat`）

- 使用 `PersistentState` API（`ServerWorld#getPersistentStateManager().getOrCreate(...)`)。
- 写入时机：
  - 每个 stage 结束（`#BreakRoom == 1` 上升沿）→ 归档该 stage 数据。
  - `#GameOver == 100` → 归档整局。
  - 服务器关闭时 → NBT flush（由 Vanilla PersistentState 自动触发）。
- 数据结构（NBT）：
  ```
  ctt_stats.dat
    └─ sessions: [
         { gameId: 123L,
           startTime: ...,
           endTime: ...,
           gameOverStatus: 100,
           stages: [ { tier:1, floor:2, stageType:"Boss", stageNum:1, players:[ {uuid:..., ...} ] }, ... ]
         },
         ...
       ]
  ```

### 8.7 同步到客户端（Custom Payload）

- 服务端每 N tick（建议 5-10 tick）把**当前 session 的活跃数据** + 本次/累计聚合数据包发给每个 CTT 客户端。
- 包类型：
  1. `StatsFullSyncPayload` —— 玩家加入/打开面板时，一次性下发当前 session 全部数据。
  2. `StatsDeltaPayload` —— 每 N tick 下发"上次同步以来的 delta"（按玩家 + stage key）。
  3. `StageEventPayload` —— stage 开始/结束事件（驱动客户端"刷入下一 stage 行"）。
- 离线玩家在服务端保留 `UUID→StageStats`，但客户端面板以"现有在线玩家 + 数据中有记录的历史玩家"合并展示，历史玩家加灰 `[离线]`。

### 8.8 坑点与边界场景清单

1. **`#Misc` 的双重语义**：既是"主剧情 misc 关卡"（Misc=11 Training Grounds），又是小游戏的 stage holder。若 `#LobbyMiniGame != 0`，`#Misc` 值语义不同——v1 只统计 `#LobbyMiniGame == 0` 即可绕过。
2. **CheckPoint 存档**：`#CheckPoint == 1` 时休息室不清 holder 不增 Floor——**mod 不能靠 `#BreakRoom == 1` 单独判断"关卡完成"**，必须附加 `#CheckPoint == 0` 条件。
3. **CameFromShop**：商店层离开时 `#BR2_CameFromShop GameScores == 1` 会禁用 `#Floor++`——**"从商店返回"不算打完一关**，Stage Table 不额外记一行。
4. **Double stages**（`double_boss` / `double_mboss` / `double_dungeon`）：会在一个休息室转场内**额外 `#Floor += 1`** 然后 set 特定 stage holder。mod 的 stage key 自动跟随（因为它读实时 holder+floor），但要注意短时间内会出现 stage key 跳变。
5. **Respawning 玩家**：`tag=Respawning` 时玩家是 gamemode=spectator 不能造成伤害——dmg_dealt 自然为 0，dmg_taken 也为 0（对幽灵状态），**不需特殊处理**。
6. **离线玩家**：服务端保留 `UUID → stats` 直到 `#CTT GameID` 递增。重新上线且在同一 session 中时，按 UUID 恢复累计数据继续采集。
7. **Lobby MiniGame**：v1 建议直接跳过（`#LobbyMiniGame != 0`）。v2 可以单独设计其 stage 定义。
8. **TestDummy 假人**：在主休息室被攻击时 stage holders 全 0 → 自动不采集。但若有特殊关卡（如训练场 `#Misc == 11`）中有假人，则**会被采集**——这符合用户"训练场是一个独立 stage"的语义。
9. **Spectator**：`gamemode=spectator` 的 CTT 玩家（Respawning 或 SpectatingGame）不会造成伤害——自然 filter。
10. **PauseGame**：`#PauseGame CTT >= 1` 时 `cake_team_tower:main` 不运行，但 Fabric Mixin 的 `damage()` 依然会触发——此时伤害事件实际不可能发生（所有生物 NoAI=1b，且玩家 weakness 255），**无需特殊过滤**。

---

## 附录 A：完整 scoreboard 速查（mod 需读的 objective）

| Objective | Holder | 读取方式 | 用途 |
|---|---|---|---|
| `GameID` | `#CTT` (fake player) | dummy | session id |
| `GameID` | 玩家本身 | dummy | 玩家所属 session |
| `CTT`    | `#GameOver` | dummy | game over 状态机 |
| `CTT`    | `#Tier` | dummy | 大关 |
| `CTT`    | `#Floor` | dummy | 小关 |
| `CTT`    | `#Boss / #MBoss / #Dungeon / #Shop / #Ally / #Misc` | dummy | stage holder |
| `CTT`    | `#BreakRoom` | dummy | 休息室计数脉冲 |
| `CTT`    | `#BreakRoomID` | dummy | 休息室类型 |
| `CTT`    | `#CheckPoint` | dummy | 存档点标记 |
| `CTT`    | `#LobbyMiniGame` | dummy | 模式过滤 |
| `CTT`    | `#FloorsCompleted / #TotalFloors` | dummy | 显示进度（可选） |
| `GameScores` | `#BR2_CameFromShop` | dummy | 商店返回过滤 |
| `Lives`  | 玩家 | dummy（sidebar 显示） | 死亡检测辅助 |
| `AllHearts` | 玩家 | dummy | 血量（已用于 HUD） |

所有都是 dummy objective，服务端代码通过 `MinecraftServer#getScoreboard()` 直接读。

---

## 附录 B：调用图（关键函数引用）

```
cake_team_tower:gamestart  (一次性, Lobby → Game 过渡)
  └→ misc:scoreboards_part_2
  └→ misc:clear_tags    (清理所有残留 tag)
  └→ cake_team_tower:misc/advancement
  └→ misc:set_numbers   (设置常量 scoreboards 如 Number0..Number100)
  └→ misc:scoreboards
  └→ cake_team_tower:token_shop/randomize_all_cosmetics

cake_team_tower:main  (每 tick)
  └→ misc:server_main
  └→ cake_team_tower:lobby/lobby_main  (条件: !CTT)
  └→ cake_team_tower:misc/misc          ← 巨型分发
  └→ cake_team_tower:floors/_floor_universal  (条件: any holder >= 1)
  └→ cake_team_tower:misc/health
  └→ cake_team_tower:misc/damage_universal  ← 14 种伤害调 misc/damage
  └→ cake_team_tower:misc/bossbars/p{1..10}_bossbar
  └→ cake_team_tower:misc/bossbars/bossbars_universal
  └→ cake_team_tower:misc/stats
  └→ cake_team_tower:misc/view_stats   (条件: player's ViewStats==1)

cake_team_tower:floors/_floor_universal  (条件: any holder >= 1)
  └→ 分派到 cake_team_tower:floors/dungeon{N}_{name}.mcfunction / boss{N}_{name} / ...
  └→ 当战斗结束 → 触发 cake_team_tower:floors/scenes/break_room_universal
     └→ #BreakRoom++
     └→ #BreakRoom==1 → #Floor++ + stage holders reset + 奖励
```

---

**结论**：本地图的数据包架构对我们的统计 mod **非常友好**。所有关键状态（session ID、stage holder、game over、休息室判定）都通过清晰的 scoreboard + 全服 fake player 暴露，mod 只需在服务端每 tick 读取即可精确重建 stage 边界。**但伤害/治疗/击杀的数据源需要一次重大修订——见 §10**。

---

## 10. 【v6 修订】关于"伤害"的权威定义与最终采集方案

> 本节基于玩家指南《护甲与抗性系统玩家指南.md》+ `cake_team_tower:misc/damage.mcfunction` 第 1020~1062 行的直接反推。
> **这些结论直接覆盖 §6 中"Fabric Mixin `LivingEntity.damage()` 收集伤害"的早期建议**，是 v6 架构的基石。

### 10.1 用户定义

> 「伤害为最终对怪物造成的血量减少量」

即：经过**所有减伤/增伤管线末端**之后，实际从该实体的血量栈上扣掉的数值。

### 10.2 地图的血量系统不是 Minecraft 原版

`damage.mcfunction` 末端揭示了 CTT 地图的**真正**血量系统：

```mcfunction
# line 1032~1058 节选
execute as @e[scores={Damage=1..,BlueHearts=1..}] run scoreboard players operation @s BlueHearts1 = @s BlueHearts
execute as @e[scores={Damage=1..,BlueHearts=1..}] run scoreboard players operation @s BlueHearts -= @s Damage
execute as @e[scores={Damage=1..}]                 run scoreboard players operation @s Damage -= @s BlueHearts1
...  # BlackHearts、SoulHearts 同款流程
execute as @e[scores={Damage=1..}]                 run scoreboard players operation @s RedHearts -= @s Damage
scoreboard players reset @e Damage  # line 1062：每 tick 清零
```

**四层血量**（扣血顺序）：
1. **蓝心 BlueHearts**（吸血层 1）
2. **黑心 BlackHearts**（吸血层 2，还触发暗反伤）
3. **魂心 SoulHearts**（吸血层 3）
4. **红心 RedHearts**（真血，归零即死）

玩家和大部分 CTT 实体的"实际血量"是这**四个 scoreboard 之和**，和 Minecraft 原版的 `Entity#health` 字段几乎解耦。

### 10.3 `LivingEntity#damage()` Mixin **不可用**（颠覆 §6）

- 地图的攻击动作（`misc/damage_universal` → `misc/damage`）**不调用** `LivingEntity#damage(DamageSource, float)`，而是直接操作 `Damage` scoreboard → 四层血 scoreboard
- 原版 `health` 字段和 `damage()` 事件在 CTT 玩家身上基本无信号
- 即便少数怪物走原版（罕见），也会和地图自管的 Damage 管线形成双路径、难以统一

**结论：整条 `LivingEntity#damage / heal / onKilled` 的 Mixin 链路对本地图失效，v6 必须另辟路径。**

### 10.4 正确采集方案（v6 最终版）

核心思路：**`Damage` scoreboard = 权威伤害值，攻击者归属 = Mixin 攻击动作 + 短窗口配对**。

#### 数据采集流水线

```
① Mixin PlayerEntity.attack(Entity target)：
   每当 CTT 玩家发起近战/投掷/射击等攻击动作，在服务端记录：
   { tick=serverTick, attacker=UUID, target=UUID }
   → 放入 attackerIntentMap（TTL = 5 tick，覆盖命中 + 伤害结算延迟）

② ServerTickEvents.END_SERVER_TICK（**在 cake_team_tower:main 之后、damage reset 之前**）：
   遍历所有带 tag=E 的敌对实体，读取 scoreboard Damage：
     - 若 Damage >= 1，则这是一次「实际血量减少 Damage」事件
     - 查 attackerIntentMap（target=UUID，且 tick 在 [now-2, now] 内）
       → 找到匹配项：归属于该 attacker
       → 找不到：暂存为 unattributed（可聚合到 "环境/陷阱/队友" 兜底）

③ 同 tick 并行：读取 RedHearts / BlueHearts / BlackHearts / SoulHearts，
   - 若实体的 RedHearts 从 >0 跌到 <=0 → 记一次 kill（归属同上）
   - 若实体带 tag=Boss/MBoss → kills_boss；否则 kills_mob

④ 治疗（v3）：
   - HealDMG scoreboard：直接对 RedHearts 做加法（见 damage.mcfunction line 54）
   - 玩家对队友治疗：Mixin 玩家的治疗技能/物品使用，+ 目标玩家 RedHearts/BlueHearts 等正增量匹配
```

#### Tick 窗口时序图

```
tick N 开始:
  datapack: cake_team_tower:main → misc/damage_universal → misc/damage
      (Damage scoreboard 被 set 成最终值)
      (四层血 scoreboard 被扣减)
      (line 1062: Damage reset @e)  ← 清零
  Fabric: ServerTickEvents.END_SERVER_TICK  ← ❌ 太晚了，Damage 已被清零
```

**致命陷阱**：`END_SERVER_TICK` 发生在所有数据包 tick 之后，此时 `Damage` 已被 reset。

**解法二选一**：

- **方案 A（推荐）**：在 `damage.mcfunction` 执行"reset Damage"**之前**插入一个 tick hook。
  - 通过向地图发一个辅助数据包 `ctt_stats_probe`，tick 优先级略高于 `cake_team_tower:main`，专门负责把当前所有实体的 Damage 拷贝到一个**我们自己**的 scoreboard `cttstats.CapturedDMG`。
  - 服务端 mod 每 tick 末读 `cttstats.CapturedDMG`（此时已是当 tick 最终值）。
- **方案 B**：直接 Mixin `ServerScoreboard#setScore`（或 `updateScore`），拦截所有对 `Damage` objective 的写入，在写入发生时记录。
  - 但地图对 `Damage` 有多次中间写入（初始化 + 各减伤段 operation），必须只在**最后一次**的时机捕获——复杂且易错。
  - 放弃。

**最终选 A**：辅助数据包 `ctt_stats_probe`（由 mod 自动安装到 `world/datapacks/` 或玩家手工装），内容极其简单：

```mcfunction
# ctt_stats_probe:tick  (priority higher than cake_team_tower:main)
# 时机: cake_team_tower:main 执行完，但在 "scoreboard players reset @e Damage" 之前无法精准控制；
# 改为：数据包钩在 cake_team_tower:main 执行结束前的最后一个可插入点——
# 实现方式：fabric 侧 Mixin cake_team_tower:main 函数调用末尾（通过 CommandExecutionContext hook）
# OR：直接在每 tick 伊始读取上一 tick 末的值（需要地图把"最终值"存留到下一 tick）
```

再进一步简化：**mod 自带的 probe 数据包不改地图行为**，只做"在 Damage reset 之前快照一份"——具体插入点可以是：

```
function cake_team_tower:main       ← 地图每 tick
function ctt_stats_probe:after_main ← 紧跟其后（通过 function 标签 ctt_stats_probe:main_after）
```

这需要我们把 `ctt_stats_probe:after_main` 注入到 `cake_team_tower:main` 的**倒数第二步**（在 Damage reset 之前）。但地图的 `main` 是固定文件，我们改不了。

**真正可行的解法是 C**：

- **方案 C（最终选型）**：mod 不依赖 `Damage` 末态，而是**订阅每一次对 `Damage` 的写入**：
  - Fabric Mixin `net.minecraft.scoreboard.ServerScoreboard#setScore(ScoreHolder, ScoreboardObjective, ScoreAccess)` 等所有写入方法
  - 对 objective 名为 `Damage` 的写入逐一记录（附带被写入实体 UUID + 值 + tick）
  - 在 `END_SERVER_TICK` 时，每个实体取**本 tick 内 Damage 的最后一次非零值**作为"最终伤害"
  - 同一 tick 没写过 Damage（命中但未致伤 / 闪避 / 盾挡）的实体 → 不计
- **额外校验**：同时监听四层血 scoreboard 的负增量作为 ground-truth，若 `ΔBlueHearts + ΔBlackHearts + ΔSoulHearts + ΔRedHearts` 与 `Damage` 值不一致（例如溢出或 kill tick），以**血量层真实减少量**为准——严格符合用户定义。

### 10.5 归属算法细节

**匹配窗口**：`[attack_tick, attack_tick + 2]` tick 内，目标实体 UUID 相符。

**多 attacker 同 tick 打同一 target**（AoE、多人连招）：

- 如果窗口内只有一个候选 → 100% 归属
- 如果有多个候选：按"最新 attack tick"优先；仍平局则按**距离最近**优先
- 都不行则均分（record 成 `fractional_credit = 1 / candidate_count`）

**远程/投射物**：Mixin `PersistentProjectileEntity#onEntityHit` 等可拿到 shooter + target，天然记入 attackerIntentMap。

**召唤物/陷阱**：通过 `owner` NBT 字段追溯（Minecraft 原生支持 `owner`）；追不到归 "unattributed"。

**未归属的伤害**：聚合到 session/stage 的 `unattributed_dmg` 字段，保留供复盘，不计入玩家个人数据。

### 10.6 修订后的 v6 架构摘要

| 组件 | 实现 | 新增/修改 |
|---|---|---|
| **服务端入口** | `com.ctt.healthdisplay.server.CttStatsServer`（`main` entrypoint） | 新增 |
| **伤害采集** | Mixin `ServerScoreboard#setScore` 筛 `Damage` objective | 新增 |
| **归属采集** | Mixin `PlayerEntity#attack` + `ProjectileEntity#onHit` | 新增 |
| **击杀判定** | END_SERVER_TICK 扫描 RedHearts 跌穿 0 | 新增 |
| **治疗判定（v3）** | Mixin `HealDMG` 写入 + RedHearts 正 delta | v3 才做 |
| **Stage 追踪** | `ServerScoreboard` 读 `#CTT GameID / #Floor CTT / ...`（§4 ~ §5） | 不变 |
| **持久化** | `PersistentState` → `world/data/ctt_stats.dat` | 不变 |
| **客户端同步** | `CustomPayload`（StatsFullSync / StatsDelta / StageEvent） | 不变 |
| **客户端 HUD** | 现有队友 HUD 扩展 + 新增 K 键表格面板 | 不变 |
| **打包形态** | **单 jar，双 entrypoint（client + main），environment=`*`** | 由"双 jar" → "单 jar" |

### 10.7 "先做本地存档测试" 的落地路径

单人存档 = Fabric **integrated server**，客户端与服务端 mod 跑在同一个 JVM，这意味着：

1. **不需要独立的 Fabric dedicated server 进程**就能验证整个服务端采集链路
2. 客户端改 HUD + 服务端改采集 → 同一次 F3+T reload 就能联调
3. 真正需要外部专用服务器时（未来多人测试），同一个 jar 直接丢到服务器的 `mods/` 即可，**无需重新打包**（`environment: "*"` 已保证）

**v6 开发节奏建议**：

```
里程碑 1 (v6.0.x)  服务端采集链路 + 本地存档验证
  ├─ [v6.0.1] ServerScoreboard Mixin 捕获 Damage
  ├─ [v6.0.2] PlayerEntity.attack Mixin + 归属窗口
  ├─ [v6.0.3] Stage 追踪（GameID/Floor/Tier/6 holder）
  ├─ [v6.0.4] PersistentState → ctt_stats.dat 读写
  └─ [v6.0.5] 单人存档：把累计值直接打印到服务端日志，人肉对照验证

里程碑 2 (v6.1.x)  客户端同步 + 嵌入 HUD
  ├─ [v6.1.0] CustomPayload 通道 + StatsFullSync
  ├─ [v6.1.1] 队友 HUD 扩展两行（当局 / 全局）
  └─ [v6.1.x] 归属算法调优

里程碑 3 (v6.2.x)  表格面板
  ├─ K 键 toggle、Tab 式半透覆盖
  ├─ 总表 + 分关表
  └─ 离线玩家 [离线] 标签

里程碑 4 (v6.3.x)  治疗统计 + 打磨
  └─ Heal 路径、最终 polish
```

### 10.8 对 §6 的正式勘误

**§6 结论（作废）**：
> 数据包没有全局 DMG / 击杀 / 治疗计数器。推荐方案：Fabric Mixin `LivingEntity.damage()` / `heal()` / `onKilled()`，反正简单可靠。

**修订（本节 §10）**：
> 数据包走**自己的四层血 scoreboard**，Minecraft 原版 damage/heal/onKilled 几乎不触发。
> 正确方案：Mixin `ServerScoreboard#setScore` 拦截 `Damage` objective 写入 + Mixin `PlayerEntity#attack` 做归属 + END_SERVER_TICK 读四层血做校验。

---

**v6 开发下一步**：已完成脚手架（`mod_version=6.0.0`、`environment="*"`、`main` entrypoint 占位 `CttStatsServer`）。下一步按上述里程碑 1 的节奏，从 Mixin `ServerScoreboard` 起步。

— 产出于 ctt-health-display v6.0.0 大版本开启时刻。若后续地图更新打破本文结论，记得回头修订。

---

## 11. 【v6.0.1 订正】数据源从「四层血 delta」切换到「DamageShower 粒子」

> §10 里拟定的"Mixin 监听 `Damage` objective + 四层血 delta 校验"方案被用户两条反馈推翻了。本节记录定型的 v6.0.1 POC 方案。

### 11.1 触发条件（来自用户）

1. 「怪物在被攻击时有点会回血」——意味着 `ΔHP = dmg - heal`，单靠四层血 delta 不能精确算伤害
2. 「玩家造成伤害时会显示数值粒子」——直接指向地图自带的 `DamageShower` 机制

### 11.2 `damage.mcfunction` line 1021~1028 的完整机制

```mcfunction
#Damage Shower
execute if score #ServerLag CT matches 0 if score #DamageNumbers CTT matches 1 run
    execute at @e[scores={Damage=1..},limit=10,sort=random,tag=CTTAll] run
        summon text_display ~1 ~1 ~ {alignment:"left",billboard:"center",Tags:["DamageShower","Prop"],shadow:0b,see_through:1b}
# (2/3/4 方向的粒子生成略)

execute if score #ServerLag CT matches 0 run
    execute at @a[scores={Damage=1..}] as @e[tag=DamageShower,limit=1,sort=nearest,distance=..1.5]
        unless score @s DamageShower matches 0.. run data merge entity @s {background:-65536}

execute at @e[scores={Damage=1..}] run
    execute as @e[tag=DamageShower,distance=..1.5]
        unless score @s DamageShower matches 0.. run
            scoreboard players operation @s DamageShower = @e[scores={Damage=1..},limit=1,sort=nearest] Damage
```

**关键语义**：
- 每个受伤的 `CTTAll` 实体 → 地图召唤一个 `text_display` 子实体（tag=DamageShower）
- `scoreboard players operation @s DamageShower = ... Damage`：把粒子附近最近受害者的 `Damage` 值拷贝给粒子自己的 `DamageShower` 分数
- `unless score @s DamageShower matches 0..`：**write-once**，粒子只在第一次被匹配时才写入，之后的 tick 不会被覆盖
- 粒子活几 tick 后被 `kill @e[tag=DamageShower,limit=1,sort=random]` 慢慢清掉

### 11.3 为什么这是「最终伤害」的完美定义契合

- `Damage` 在 line 1028 被拷贝时，是**已经经过所有管线**（元素加护/真·盔甲/防御/难度/Buff）的结果，且**尚未**被吸血层（BlueHearts/BlackHearts/SoulHearts）扣减中间值污染
- 粒子的 `DamageShower` 分数是 write-once，不受后续 tick 的任何 operation 干扰
- 怪物回血（HealDMG）是独立管线，**完全不触达** `Damage`，天然隔离

这就是用户原话「最终对怪物造成的血量减少量」的精确对应——甚至比我们自己算 `ΔHP` 还准。

### 11.4 采集路径（v6.0.1 实际实现）

```
地图数据包 tick 内：
  Scoreboard.updateScore(textDisplayHolder, "DamageShower" objective, score=N)
            ↓ (Mixin @Inject RETURN)
  ScoreboardUpdateMixin.ctt$onScoreUpdate
            ↓
  if (this instanceof ServerScoreboard && objective.getName() == "DamageShower")
      DamageProbe.record(holder, value)
            ↓
  ConcurrentLinkedQueue<RawEvent>.add(RawEvent(tick, textDisplayUuid, damage))

Fabric END_SERVER_TICK：
  DamageProbe.flushTick(server)
      ↓ 消费队列
  对每个 RawEvent:
      1. server.getWorlds() 遍历找 textDisplay entity by UUID
      2. Box.of(pos, 3, 3, 3) 在粒子位置 1.5m 半径内查实体
      3. 过滤 tag=CTTAll && tag!=DamageShower && 非 text_display
      4. 选平方距离 <= 2.25 的最近一个作为 victim
      5. LOGGER.info("[CTT Stats] tick={} dmg={} victim={}", ...)
```

### 11.5 Mixin 目标的 Yarn 1.21.4 映射确认

从 `mappings.tiny` 查到：
- `Scoreboard` = `fcg` = `net.minecraft.class_269`
- `Scoreboard.updateScore(ScoreHolder, ScoreboardObjective, ScoreboardScore)`:
  - intermediary: `method_1176`
  - obfuscated: `a`
  - descriptor: `(Lfcf;Lfby;Lfcd;)V`
- `ServerScoreboard` = `alo` = `net.minecraft.class_2995`，**未重写** `updateScore`
  - 所以 Mixin 打在基类 `Scoreboard.class` 上，ServerScoreboard 通过继承自动生效
  - 客户端 ClientWorld 的 Scoreboard 实例也会进 Mixin，但被 `instanceof ServerScoreboard` 守卫拒绝

### 11.6 已知限制（诚实记录，v6.0.1 不处理）

1. **AoE >10 目标漏采**：地图 `limit=10,sort=random` 只生成最多 10 个粒子；v6.0.x 后续可加 `Damage` objective 的**直接监听**作兜底（但需解决"多次 operation 污染"问题——方案：读 `Damage` objective 的**每个实体每 tick 的首次非零写入**值，即 `misc/damage` 管线入口阶段的值）
2. **服务器卡顿漏采**：`damage.mcfunction` line 1022~1025 有 `#ServerLag CT matches 0` 条件，卡服时不生成粒子；本地单人存档测试不会触发
3. **受害者紧密重叠归属错误**：粒子 `sort=nearest` 在多受害者 <1.5m 时可能指错，但不影响总伤害，只影响 per-victim 归属
4. **text_display 已被 kill**：若 `DamageProbe.flushTick` 执行时粒子已不在（理论上同 tick 内生成和写入分数后粒子还没被 kill，但为保险仍做兜底日志）

### 11.7 对比 v6.0.0 规划中 §10 的修订

| 项 | §10 设想（v6.0.0） | §11 定型（v6.0.1） |
|---|---|---|
| 主数据源 | Mixin `Damage` 写入最后一次值 | Mixin `DamageShower` 的 write-once 事件 |
| 辅助数据源 | 四层血 delta ground-truth | 无需，DamageShower 已是 ground-truth |
| 治疗干扰 | 需剥离 HealDMG | 天然规避 |
| 归属算法 | `attackerIntentMap` + 窗口配对 | 同，但推到 v6.0.2+ |
| v6.0.1 输出 | 未定义 | 日志：`[CTT Stats] tick=<t> dmg=<n> victim=<desc>` |

### 11.8 v6.0.1 成功判据

用户在休息室攻击测试假人（假人有地图自带伤害统计）时，`.minecraft/logs/latest.log` 中 `[CTT Stats]` 行的 `dmg` 数值**必须**与地图自带的数值完全一致；若有偏差，说明方案仍有盲点需要修订。

— v6.0.1 POC 记录结束。若验证通过，v6.0.2 起进入攻击者归属阶段。

---

## 12. 【v6.0.4】攻击者归属：从 PlayerID 约定反推

### 12.1 问题背景

v6.0.2 按键区间统计上线后，用户在单人打假人场景里验证成功（`268 == 268`）。但在联机场景下发现：**session 会把队友造成的伤害也算进自己的区间统计**。

原因：`DamageShower` 粒子只记录受害者位置和最终伤害值，攻击者身份在地图的伤害管线末端已经完全丢失：

```
玩家/marker 开火  →  *DMG（写在 victim 身上）   [攻击者在 marker 上]
                ↓
        Damage += *DMG                         [护甲前汇总]
                ↓
       Damage ×= 各种修饰（护甲/crit/buff）     [修饰过程]
                ↓
     DamageShower = Damage                     [护甲后最终值 ★我们采集的点]
                                               [攻击者身份已丢]
```

把采集点下移到 `DamageShower` 确实能拿到准确的"最终伤害"，但失去了攻击者。必须在**更早的阶段**同时做归属，最后 pair matching。

### 12.2 关键发现：PlayerID 是地图的全局玩家 ID

`data/misc/function/server_main.mcfunction:125`：

```mcfunction
execute as @a run execute store result score @s PlayerID run data get entity @s UUID[0]
```

这行每 tick 对每个玩家执行一次，把玩家 UUID 的**第 0 个 int（前 32 位）**存为 `PlayerID`。UUID 是永久不变的，所以每个玩家都有一个稳定的数字 ID。

### 12.3 关键发现：所有攻击 marker 都携带 PlayerID

抽查 AK47 的 marker 生成逻辑（`ranged2_03_ak_47.mcfunction:32-34`）：

```mcfunction
execute at @a[scores={AK47Shoot=1..}] run summon minecraft:marker ~ ~ ~ {Tags:["AK47ShootAI"]}
execute at @a[scores={AK47Shoot=1..}] run scoreboard players operation @e[tag=AK47ShootAI] PlayerID = @a[scores={AK47Shoot=1..},limit=1,sort=nearest] PlayerID
execute at @a[scores={AK47Shoot=1..}] run scoreboard players operation @e[tag=AK47ShootAI] Archery = @a[scores={AK47Shoot=1..},limit=1,sort=nearest] Archery
```

每发子弹 summon 一个 `marker`，立即把玩家的 `PlayerID` 和 `Archery`（攻击力）拷到 marker 上。这是地图用于：
- **防止打到自己**：命中检测会 `unless score @s PlayerID = @e[...] PlayerID`，即跳过同 PlayerID 的实体
- **防友伤**：连锁效应，玩家 marker 不伤害同 PlayerID 的对象

### 12.4 推论：归属策略的通用性

地图里所有"远程攻击 marker"应该都遵循相同模式（因为防友伤是所有武器都需要的能力）。对采集而言意味着：

> 在 `*DMG` 写入 victim 的那一瞬间，victim 周围 3 米内必有至少一个 `PlayerID != 0` 的实体：
> - **近战场景**：玩家本人就在 victim 身边（因为近战要贴身）
> - **远程场景**：弹道 / 投射物 marker 在 victim 身边（因为命中检测用 distance=..1.5/2.5）
> - **法术场景**：召唤物 marker（Boomerang/Fang/Amethyst）同样带 PlayerID

**取最近的那个 `PlayerID != 0` 实体 = 攻击者**。

### 12.5 9 种前置伤害 scoreboard

从 `misc/damage.mcfunction` 抓出所有 `Damage += @s XxxDMG` 行：

| 行号 | scoreboard | 代表武器类型 |
|---|---|---|
| 312 | `FireDMG` | 火系 |
| 366 | `WaterDMG` | 水系 |
| 393 | `IceDMG` | 冰系 |
| 413 | `MeleeDMG` | 近战 |
| 436 | `DarkDMG` | 暗系 |
| 474-478 | `LightDMG` | 光系（可叠加 SoulHearts 时双倍） |
| 500 | `ElectricDMG` | 电系 |
| 545-555 | `BulletDMG` | 枪械 / 弓 / 弹弓 |
| 875 | `ForceDMG` | 南瓜刀斩杀、某些法术 |

这 9 个 scoreboard 覆盖了玩家→敌人的**所有**伤害来源。本 mod v6.0.4 全部监听。

### 12.6 Mixin 拦截时机（主线程安全性）

Mixin 注册在 `Scoreboard.updateScore` `@At("RETURN")`，此时：

- Scoreboard set 操作本身运行在**服务器主线程**（Minecraft tick 在主线程执行 datapack），所以拦截回调也在主线程
- 回调执行期间，victim entity 肯定还活着（它刚在同一个 execute 语句中被访问）
- marker entity 在下一 tick 才可能被 kill，所以回调期间也 100% 存活
- 可以**同步**调 `world.getOtherEntities()` 扫描，无需队列化

### 12.7 归属冲突处理

**多候选场景**：两个玩家同时近战同一个敌人，两人都在 3 米范围内。按距离升序取最近。若前两个距离差 &lt; 0.5m，日志标记 `[AMBIGUOUS]` —— 用户可判断是否要加更细致的规则。

**零候选场景**：没有任何 `PlayerID != 0` 实体 —— 归为 `env/unknown`（环境伤害、陷阱、敌人 AoE、Bleed/Poison 持续伤害）。这些本就不该计入"玩家输出"。

### 12.8 接下来的 v6.0.5

v6.0.4 只打诊断日志。真正的接入需要：

1. **Pair matching**：`AttackerProbe` 记录 `(tick, victim_uuid) → attacker_uuid`，`DamageShower` 事件来时查最近 ±2 tick 的归属
2. **Session 精确累加**：只累加 `attacker == self` 的 DamageShower 事件
3. **UI 文案**：从"全队总输出"改回"我的区间伤害"
4. **可选**：分人头统计，结束时展示全队每人输出

v6.0.5 的前置条件是 v6.0.4 在真实联机环境里跑一轮，确认归属算法覆盖率符合预期。

— v6.0.4 归属探针记录结束。

---

## 13. 【v6.0.5】五层归属堆栈：应对远程武器 marker 短命周期

### 13.1 v6.0.4 实测暴露的问题

用户联机测试 v6.0.4（单层 3m PlayerID 扫描），结果：

| 武器 | `*DMG` 类型 | 归属成功率 | 原因 |
|---|---|---|---|
| AK47 | BulletDMG | **100%** | `AK47ShootAI` marker 与 victim 距离 ~1.81m，刚好落在 3m 内 |
| Nut Laser (坚果激光，法器类) | MeleeDMG | **0%** | `NSLaserAI` marker 只存在 1 tick；被 kill 时玩家本人在 20+ 米外 |

用户反馈："远程武器如 AK47 这种攻击距离一般很远，3m 不够，至少 30m。"

### 13.2 关键二次发现：70+ 个 vanilla damage_dealt stat objective

`scoreboards_part_2.mcfunction` 注册了大量 vanilla 统计型 objective：

```mcfunction
scoreboard objectives add SwansLustDMG minecraft.custom:minecraft.damage_dealt
scoreboard objectives add PumpkinCarverKnifeDMG minecraft.custom:minecraft.damage_dealt
scoreboard objectives add JellySwordDMG minecraft.custom:minecraft.damage_dealt
scoreboard objectives add HourHandDMG minecraft.custom:minecraft.damage_dealt
scoreboard objectives add BlacksmithDamageDealt minecraft.custom:minecraft.damage_dealt
...（共 70+ 条）
```

**vanilla 自动行为**：当**玩家**通过 `LivingEntity.damage()`（vanilla 原版伤害入口）成功对任何实体造成 N 点伤害时，`PlayerStatHandler.increment()` 会给**该玩家**的**所有** criterion == `minecraft.custom:minecraft.damage_dealt` 的 objective 全部 += (N × 10)。

- holder = 攻击者玩家名（100% 可靠，不会是别人）
- objective name = 武器名字（直接告诉我们武器类型）

**这是一个 100% 准确的攻击者信号**，无需任何空间扫描。

**覆盖范围**：70+ 种走 vanilla damage 的近战武器（剑 / 斧 / 匕首 / 拳套 / 铁匠锤等）。不包括：走 carrot-on-a-stick 右键触发 + 自定义 damage 函数的"远程法器 / 枪械"（AK47, Nut Laser, Jelly Dash 等）。

### 13.3 关键二次发现：RightClick 右键 objective

```mcfunction
scoreboard objectives add RightClick minecraft.used:minecraft.carrot_on_a_stick
```

**vanilla 自动行为**：持有 `carrot_on_a_stick` 的玩家每按一次右键，该 objective += 1。

- 由于地图所有自定义武器都以 `carrot_on_a_stick` 为实体载体（通过 `CustomModelData` 区分），任何开火动作都会触发 RightClick 写入
- holder = 开火玩家（稳定）
- 无法区分具体武器，但能可靠确认"这个玩家这 tick 开了一次武器"

**用途**：作为"远程武器的兜底信号"。即使 marker 已被 kill、玩家在 25m 外，只要最近 20 tick 内这个玩家开过火且距离 victim 不太离谱，就能合理推断是他造成的伤害。

### 13.4 方案 X：五层优先级堆栈

```
┌─ *DMG 写入 victim ─────────────────────────────────┐
│                                                    │
│  L1  本 tick damage_dealt stat 写入的玩家           │
│      ↓ 未命中                                       │
│  L2a 3m Box 扫 PlayerID != 0 实体（任何）           │
│      ↓ 未命中                                       │
│  L2b 30m Box 扫 PlayerID != 0 实体 AND 带攻击证据   │
│      ↓ 未命中                                       │
│  L3  近 5 tick damage_dealt stat 窗口               │
│      ↓ 未命中                                       │
│  L4  近 20 tick RightClick + 30m 距离加权           │
│      ↓ 未命中                                       │
│  L5  <unattributed>（环境 / 敌人 AoE / 未知）        │
│                                                    │
└────────────────────────────────────────────────────┘
```

**各层设计理由**：

- **L1**：最高置信度。本 tick 有玩家触发了 vanilla damage_dealt stat → 一定是他攻击导致的。单人场景用 objective name 匹配武器；多人同 tick 触发则用"离 victim 最近"破除歧义
- **L2a**：保留 v6.0.4 的 3m PlayerID 扫描 —— 对 AK47 这种 marker 就在命中点的场景完美
- **L2b** *（v6.0.5 新增）*：扩大到 30m，但强制要求候选带"攻击证据 tag"（Charge / Hold / Use / Shoot / Cast / Fire / Target / Hit / Atk / Attack 等子串）。用于 Nut Laser 这种玩家本人带 `NSLaserCharge` 且距离 victim 超过 3m 的场景
- **L3**：有些近战武器会延迟 1-5 tick 造成后续伤害（毒、燃烧、eternaldamage），窗口查询能兜住
- **L4** *（v6.0.5 新增）*：终极兜底。玩家在过去 20 tick 内开过火、且距离 victim ≤ 30m → 最有可能是他。多玩家竞争时按距离最近者优先

### 13.5 L2b 攻击证据 tag 白名单

为了避免把远处 30m 外挂机队友误归属，FAR 层对玩家类候选额外要求带以下关键字之一：

```java
tag.contains("Charge") ||
tag.contains("Hold")   ||
tag.contains("Use")    ||
tag.contains("Shoot")  ||
tag.contains("Cast")   ||
tag.contains("Fire")   ||
tag.contains("Target") ||
tag.contains("Hit")    ||
tag.contains("Atk")    ||
tag.contains("Attack")
```

实际含这些子串的已知 tag 包括：`NSLaserCharge`, `NSLaserAI`, `NSLaserHit`, `PumpkinCarverKnifeHold`, `PumpkinCarverKnifeUse`, `PumpkinCarverKnifeHit`, `PumpkinCarverKnifeTarget`, `AK47ShootAI`, `PistolShoot`, `SwansLustTarget` 等。

MARKER / PROJECTILE 类候选不做此过滤（marker 只会由攻击行为生成，不可能是"挂机"状态）。

### 13.6 实现结构

```
v6.0.5 server/
  CttStatsServer           ← 挂 END_SERVER_TICK: DamageProbe::flushTick, AttackerProbe::gcTick
  DamageProbe              ← 收 DamageShower, 维护服务器 tick 计数 (currentTick())
  AttackerProbe            ← 五层堆栈查询 (record)；层级命中计数 (countLx)
  AttackerResolver         ← scan(NEAR=3m) + scan(FAR=30m) + hasAttackEvidence 过滤
  PlayerHitLog             ← damage_dealt stat 写入日志，Deque per player, TTL=400 tick
  PlayerFireLog            ← RightClick 事件日志，含位置（用于 L4 距离加权）
  mixin/
    ScoreboardUpdateMixin  ← 基于 criterion 类型动态分派到 DamageProbe / AttackerProbe / PlayerHitLog / PlayerFireLog
```

### 13.7 为什么 v6.0.5 仍然只打日志

- 各武器的"落在哪一层"需要联机真实测试才能确认
- v6.0.6 把"按 attacker 分桶 session"纳入前，要先确认 L1~L4 整体覆盖率 ≥ 95%
- 诊断日志字段 `layer=L1_STAT_TICK | L2A_NEAR | L2B_FAR | L3_STAT_WINDOW | L4_FIRE_WINDOW | L5_NONE` 直接告诉用户每次事件命中了哪层

### 13.8 诊断日志字段定义

```
[CTT Attrib#N] type=<*DMG> victim=<name> (<entity type>) pre=<value>
               layer=<Lx> attacker=<Player(...)/Unknown(...)/<unattributed>>
               detail=<额外信息：武器 / 距离 / 窗口延迟 / 候选数等>
```

- `pre` = 伤害经过护甲/抗性前的值（`*DMG` 是护甲前；`DamageShower` 才是最终值）
- `layer` = 命中层，用来做覆盖率统计
- `detail` 根据 layer 不同含义不同：L1/L3 含 weapon + delta；L2 含 source + tag + distance；L4 含 item + distance + age + candidate count

### 13.9 接下来的 v6.0.6

等用户测完确认覆盖率，v6.0.6 负责：

1. `AttackerProbe.record` 不光打日志，还要把 `(victim_uuid, attacker_uuid)` 写入短寿命 map（TTL ~5 tick）
2. `DamageProbe.record` (DamageShower 管线) 在累加 session 前查这张 map → 只累加 `attacker == self` 的条目
3. 多人联机时分人头统计：`AttackerStats.add(attacker_uuid, damage)` 代替 `sessionTotal.add(damage)`
4. UI 面板改为"自己区间 / 队伍分人头区间"双列显示

— v6.0.5 五层归属堆栈记录结束。

