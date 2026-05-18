# 纯客户端 Mod 可用数据参考

> **定位**：本文档是 `MAP_DATAPACK_REFERENCE.md` 的"客户端视角投影"。它回答**唯一一个问题**：在原版服务端 + Cake Team Towers 数据包的环境下，**纯客户端 mod 通过原版 S2C 协议能读到哪些数据、读不到哪些数据**？
>
> - **目标读者**：想做"只装在客户端、对服务端零侵入"的 mod 开发者。
> - **前置阅读**：先看 `MAP_DATAPACK_REFERENCE.md` 了解地图发送的"原始信息"，再看本文档了解"哪些信息真的能跨网络到达客户端"。
> - **基础假设**：
>   1. 服务端**纯原版**（无 mod，无 Bukkit/Spigot/Paper），仅加载本地图的 datapack。
>   2. 客户端运行 Fabric/Forge mod，可使用 Mixin、ClientPlayNetworkHandler、Scoreboard 客户端缓存等。
>   3. **客户端是普通玩家权限**（`op-level=0`）——这是本文核心约束之一，详见 §13。
>   4. 适用版本：MC 1.21.4。
> - **本文覆盖**：S2C 信息读取 + 无 OP 客户端能向服务端发起的有限输入（§13）。
> - **本文不覆盖**：自定义 plugin message（vanilla 服务端不会响应）、需要 OP 才能用的 `/scoreboard`/`/give`/`/tp`/`/tellraw`/`/title`/`/reload`/`/data`/`/execute`/`/function` 等命令（**全部不可用**）。

---

## 目录

1. [核心结论](#1-核心结论)
2. [Scoreboard 通道（最关键）](#2-scoreboard-通道最关键)
3. [Bossbar 通道](#3-bossbar-通道)
4. [实体生成与 DataTracker](#4-实体生成与-datatracker)
5. [`text_display`（DamageShower）的客户端解读](#5-text_displaydamageshower-的客户端解读)
6. [Title / Subtitle / Actionbar](#6-title--subtitle--actionbar)
7. [聊天 / 系统消息（tellraw）](#7-聊天--系统消息tellraw)
8. [声音事件](#8-声音事件)
9. [团队 / 玩家列表（TAB）](#9-团队--玩家列表tab)
10. [玩家自身（自我观测）](#10-玩家自身自我观测)
11. [成就（Advancement）](#11-成就advancement)
12. [客户端"读不到"的清单](#12-客户端读不到的清单)
13. [客户端权限边界（无 OP / 输出能力）](#13-客户端权限边界无-op--输出能力)
14. [客户端 mod 重建关键功能的策略](#14-客户端-mod-重建关键功能的策略)
15. [陷阱与时序](#15-陷阱与时序)
16. [可读 Scoreboard 速查表](#16-可读-scoreboard-速查表)
17. [可读 Bossbar 清单](#17-可读-bossbar-清单)

---

## 1. 核心结论

| 类别 | 客户端可读？ | 简述 |
|---|---|---|
| **Scoreboard 全量** | ✅ | 所有 objective 定义 + 所有持有者（玩家名/假玩家/实体 UUID）的 score |
| **Bossbar** | ✅ | ID（文本，如 `minecraft:player_1001`）、name（含 `score`/`selector` 占位符）、value/max、color、style |
| **`text_display` 实体** | ✅ | 位置、`text` NBT、`background`、`alignment`、`billboard` 全部 DataTracker 同步 |
| **Title / Subtitle / Actionbar** | ✅ | `TitleS2CPacket` / `SubtitleS2CPacket` / `OverlayMessageS2CPacket` |
| **`tellraw` 输出** | ✅ | 作为 `GameMessageS2CPacket` 进 `chat`/`system` 队列 |
| **声音事件** | ✅ | `PlaySoundFromEntityS2CPacket` / `PlaySoundS2CPacket` 含 sound id、位置 |
| **实体生成 / 销毁** | ✅ | 所有实体 spawn / despawn |
| **玩家清单（TAB）** | ✅ | 用户名、UUID、ping、gamemode、自定义名 |
| **团队（Team）成员** | ✅ | 玩家被加入到 `PVE` 等 team（`@a[team=PVE]`） |
| **成就** | ✅ | 解锁 / 进度 |
| **物品 / 持物 / 装备** | ✅ | 自己的全部；其他玩家的装备槽（手持 + 4 件防具） |
| **状态效果（potion）** | ✅ | 自己的全部；其他实体的部分（受 `EntityStatusEffectS2CPacket` 限制） |
| **Vanilla `Health` / `Attribute`** | ✅ 可读但**已被劫持** | 数据包用它做 `Pierce_Damage` 反算和负 armor 美化，**不是真血量** |
| **实体 tag**（`CTT`/`E`/`Boss`/`F`/...） | ❌ | **vanilla 不同步实体 tag**——这是最大限制 |
| **NBT 自定义字段**（非 DataTracker） | ❌ | `Tags`、`data` 等服务端 NBT 不下发 |
| **Stage / Floor 状态**（间接） | ✅ | 通过假玩家 scoreboard `#Tier`/`#Floor`/`#Dungeon` 等 |

**最关键的两个"是"**：

1. **Scoreboard 通道是金矿**——地图 4 层心数、所有玩家属性、所有 stage 状态、Game ID 全部可读。
2. **`text_display` 是伤害事件可读源**——客户端能通过 `DamageShower` 实体的 `text`/`background` 完整重建伤害数字流。

**最关键的两个"否"**：

1. **实体 tag 不同步**——客户端无法直接知道"这只僵尸是 `tag=E` 还是 `tag=Boss`"。要靠"该实体在 scoreboard 上的关键 score 是否存在"间接判断。
2. **服务端 NBT `data` / `Tags` 不下发**——任何写在 NBT 里的自定义数据都不可见。

---

## 2. Scoreboard 通道（最关键）

### 2.1 vanilla S2C scoreboard 协议事实

MC 1.21.4 的 `ServerScoreboard` 在以下时刻给**所有在线玩家**广播包：

| 事件 | 包 | 触发条件 |
|---|---|---|
| `scoreboard objectives add Foo dummy` | `ScoreboardObjectiveUpdateS2CPacket(ADD)` | 任意 objective 创建 |
| `scoreboard objectives modify Foo displayname ...` | `ScoreboardObjectiveUpdateS2CPacket(UPDATE)` | objective 修改 |
| `scoreboard objectives remove Foo` | `ScoreboardObjectiveUpdateS2CPacket(REMOVE)` | objective 删除 |
| `scoreboard objectives setdisplay sidebar Foo` | `ScoreboardDisplayS2CPacket` | 显示槽变更 |
| `scoreboard players set <holder> Foo <n>` | `ScoreboardScoreUpdateS2CPacket` | **任何**持有者 score 更新（含假玩家、含实体 UUID） |
| `scoreboard players reset <holder> Foo` | `ScoreboardScoreResetS2CPacket` | 持有者 score 重置 |

**关键**：

- **不需要** objective 在显示槽里。只要 `scoreboard objectives add` 创建过，所有该 objective 的 score 更新都会同步到客户端缓存。
- **假玩家**（fake player，名字以 `#` 开头那种）的 score 也会下发。`#CTT GameID`、`#Tier CTT`、`#Floor CTT`、`#Dungeon CTT` 这些**全部**到客户端。
- **实体（mob）**的 score 也下发，holder 名是该实体的 UUID 字符串（带连字符）。客户端可用 UUID 反查正在追踪的实体。
- 更新频率 = 数据包写入频率 = **每 tick 多次**（因为 `cake_team_tower:main` 在每 tick 改写大量 score）。

### 2.2 客户端缓存 API

Fabric 1.21.4 客户端可直接用：

```java
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreHolder;

Scoreboard sb = MinecraftClient.getInstance().world.getScoreboard();

// 1) 取 objective
ScoreboardObjective obj = sb.getNullableObjective("RedHearts");

// 2) 玩家 score（在线玩家用名字）
int hp = sb.getOrCreateScore(ScoreHolder.fromName(playerName), obj).getScore();

// 3) 假玩家 score
int gameId = sb.getOrCreateScore(ScoreHolder.fromName("#CTT"), 
                                  sb.getNullableObjective("GameID")).getScore();

// 4) 实体 score（实体作为 ScoreHolder）
int redHearts = sb.getOrCreateScore(zombieEntity, obj).getScore();
```

**注意 1.21+ 的 ScoreHolder 抽象**：

- `LivingEntity implements ScoreHolder`：直接传实体即可。
- `ScoreHolder.fromName(String)`：传任意名字（玩家名 / 假玩家名）。
- 客户端 `ClientWorld` 持有的 `Scoreboard` 实例已被 vanilla 自动同步——**mod 不需要写任何包监听**就能直接读。

### 2.3 通过 Mixin 监听 score 变化（更主动）

读缓存被动，监听 `ScoreboardScoreUpdateS2CPacket` 主动得多：

```java
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientNetMixin {
    @Inject(method = "onScoreboardScoreUpdate", at = @At("HEAD"))
    private void cttHd$onScore(ScoreboardScoreUpdateS2CPacket p, CallbackInfo ci) {
        String holder    = p.owner();         // 玩家名 或 #CTT 或 UUID 字符串
        String objective = p.objectiveName(); // 例如 "RedHearts"
        int    score     = p.score();
        // 这里就拿到了"差量事件"，可直接用作 mod 内部事件源。
    }
}
```

**这是当前 `ctt-health-display` v6 用到的**核心采集机制——不过当前实现挂在服务端 `ServerScoreboard` 上。**纯客户端版本只需把同样思路搬到 `ClientPlayNetworkHandler`**。

### 2.4 与 mod 的对接：把"地图 score 写入"当事件流

数据包 `damage.mcfunction` 在第 7 步合并 `Damage` 之前会写入各 `*DMG`，第 7 步写 `Damage`，第 8 步重置 `Damage`。所有这些都通过 `ServerScoreboard.updateScore` 路径，所以**全部到客户端**。客户端能完整重建：

- 每 tick 哪些玩家被加了多少 `MeleeDMG` / `BulletDMG` / `FireDMG` / ...
- 每 tick 哪些玩家被加了 `HealDMG`
- 每 tick `Damage` 怎么从 `*DMG` 之和走到红心扣减
- 每 tick `RedHearts`/`SoulHearts`/`BlackHearts`/`BlueHearts` 怎么变化
- 每 tick `RedDamageTook`/`BlackDamageTook` 累计了多少

**唯一缺失**：实体 tag。客户端不知道一个 score 持有者实体到底是 `tag=E` 还是 `tag=Boss`——但这能用启发式补上（见 §14.1）。

---

## 3. Bossbar 通道

### 3.1 vanilla S2C bossbar 协议

服务端用 `bossbar add <id>`/`set ...` 维护的 bossbar 由 `BossBarS2CPacket` 同步：

| 子包 | 含义 |
|---|---|
| ADD | 给某 bossbar 添加观察者；包含 name、value、max、color、style |
| REMOVE | 从某 bossbar 摘除观察者 |
| UPDATE_PCT | value 变化 |
| UPDATE_NAME | name 变化（Text 组件） |
| UPDATE_STYLE | color / divisions 变化 |
| UPDATE_PROPERTIES | dark sky / boss music / fog 等 flag |

**关键**：

- 客户端只在自己**被加入观察者**（`bossbar set ... players @a[scores={BossbarID=1001}]`）时才收到 ADD 包；摘除时收 REMOVE。
- name 是完整 `Text` 组件，包含 `selector` / `score` 子元素的，**会被服务端预解析**——也就是说客户端拿到的就是最终渲染好的字符串（如 `Player1 (HP 180/200) (Lives 3) (Mana 12/15) (Coins 245)`）。
- 但同样的数据 mod 也能直接从 scoreboard 读到，**不要从 bossbar name 字符串解析**——容易因 ClassPassive 改变 name 模板而碎掉。

### 3.2 地图相关 bossbar 完整列表

| Bossbar ID | 槽位用途 | 客户端识别要点 |
|---|---|---|
| `minecraft:player_1001`..`player_1010` | 各玩家自己的属性（HP/Lives/Mana/Coins...） | 显示给所有人看，`BossbarID=1001..1010` 对应槽位 |
| `minecraft:targetting_1001`..`1010` | 各玩家锁定敌人的血条（HealthBar1001..1010） | 与 `player_1001..1010` 配对 |
| `minecraft:p1001_drowning`..`p1010_drowning` | 各玩家溺水进度 | |
| `minecraft:p1001_onlyup`..`p1010_onlyup` | Only Up 模式各玩家进度 | |
| `minecraft:targetting` | 旁观者锁定 boss 血条 | `SpectatingGame` tag 玩家可见 |
| `minecraft:boss` | 关卡 boss 血条 | |
| `minecraft:boss_rush_boss_bar` | Boss Rush 进度 | |
| `minecraft:boss_warping` | Boss 出场动画进度 | |
| `minecraft:skulls` | 全队骷髅头计数 | name 含 `#Skulls CTT` |
| `minecraft:advancement` | 进度提示 | |
| `minecraft:vote` | 投票计时 | |
| `minecraft:continue` | 续币倒计时 | |
| `minecraft:cake_bossbar` | 蛋糕（治疗食物）状态 | |
| `minecraft:floor` | 当前楼层指示 | |
| `minecraft:warping` / `minecraft:warpwait` | 楼层切换 | |
| `minecraft:s12_timer` | Shop 12 (Petal Park) 计时 | |
| `minecraft:debug_floor` | 调试用 | |
| `minecraft:vt_train_hp` ("Skibidi Toilet Rizz") | Love is a Battlefield 关卡 | |
| `minecraft:m04_bow_training` | Lazy's Bow Training | |
| `minecraft:hoosact6_bossbar` | Heart of Otherside Act 6 | |
| `minecraft:b24_bossbar` | Boss 24 专用 | |
| `minecraft:cake_wars` / `mob` / `roll` / `shop` / `pickwait` / 等 | 其他游戏模式 / 子系统 | |

> 完整列表见 `data/misc/function/scoreboards_part_2.mcfunction` 第 1587–1656 行（一次性 `bossbar add` 之）。

### 3.3 客户端 mod 读 bossbar

```java
@Mixin(ClientPlayNetworkHandler.class)
public abstract class BossbarMixin {
    @Inject(method = "onBossBar", at = @At("HEAD"))
    private void cttHd$onBossbar(BossBarS2CPacket p, CallbackInfo ci) {
        // p.getUuid()      — bossbar 的内部 UUID（不是 ID 字符串）
        // p.getType()       — ADD / REMOVE / UPDATE_PCT / UPDATE_NAME / ...
        // 在 ADD 包里：name、value、max、color、style 都能读
    }
}
```

**注意**：`BossBarS2CPacket` 用的是 bossbar 的**运行时 UUID**，不是 `bossbar add <id>` 的 namespaced ID。要同时记录 vanilla 客户端 `BossBarHud` 维护的 UUID → 名称映射，或在 ADD 包里通过 `name` 字符串模式识别。

更简单的办法：直接读 `MinecraftClient.getInstance().inGameHud.getBossBarHud()` 的内部 map（需要 accessor mixin）。

---

## 4. 实体生成与 DataTracker

### 4.1 客户端可见的实体字段

通过 `EntitySpawnS2CPacket` + `EntityTrackerUpdateS2CPacket`，客户端能拿到每个实体的：

| 字段 | 来源 | 客户端可读？ |
|---|---|---|
| EntityType（类型 ID） | spawn 包 | ✅ |
| UUID | spawn 包 | ✅ |
| 位置（x/y/z）+ rotation | spawn 包 + `EntityPositionS2CPacket` | ✅ |
| 速度 | `EntityVelocityUpdateS2CPacket` | ✅ |
| Health（vanilla） | DataTracker | ✅（**但被劫持**） |
| MaxHealth attribute | `EntityAttributesS2CPacket` | ✅（被劫持） |
| Armor attribute | 同上 | ✅（被劫持） |
| 自定义名（`CustomName`） | DataTracker | ✅ |
| 是否着火、是否潜行、是否游泳 | DataTracker bitfield | ✅ |
| 装备槽（手持 / 4 件防具 / 副手） | `EntityEquipmentUpdateS2CPacket` | ✅ |
| Pose（站立/睡觉/游泳） | DataTracker | ✅ |
| 状态效果（药水图标） | `EntityStatusEffectS2CPacket` | ✅（限自己） |
| `text_display` 的 text 内容 | DataTracker（详见 §5） | ✅ |
| `text_display` 的 background | DataTracker | ✅ |
| Marker 实体的所有 NBT | ❌ | ❌ |
| **Entity 的 `Tags` NBT** | ❌ 不在 DataTracker | ❌ |
| **`data` 自定义 NBT** | ❌ | ❌ |

### 4.2 实体识别困难

地图大量使用 `tag=E` / `tag=Boss` / `tag=F` / `tag=NPC` / `tag=Summon` / ... 来分类实体。**客户端拿不到这些**。识别方法：

| 想识别 | 客户端启发式 |
|---|---|
| 是不是 CTT 玩家 | 该 player 的 `GameID` score 是否等于 `#CTT GameID` |
| 是不是敌人（`tag=E`） | 该实体在 `MaxHP`/`RedHearts` objective 上**有**有效 score |
| 是不是 Boss | 看是否有对应的 `targetting_*` bossbar 把它列为 `HealthBar1001..1010` 的 score holder（间接） |
| 是不是友军（`tag=F`） | 实体的 `FriendlyID` score 不为空且匹配某玩家 PlayerID |
| 是不是召唤物 | 实体的 `OwnerID` score 存在 |
| 是不是 NPC | 实体周围近期有没有 `tellraw` "talk" 流出（启发） |
| 是不是 `Coffin` | 实体 `RedHearts` ≤ 0 但还活着且贴有特殊命名（启发） |
| 是不是 DamageShower | EntityType == `text_display` 且文字是数字（最直接） |

**经验**：直接通过"**该实体的关键 score 是否存在/在合理范围**"来判定它的角色。`MaxHP` 不为 0 + `RedHearts` 在 1..MaxHP 之间，几乎肯定是 `tag=E`/`tag=F`/`tag=CTT` 的某一种。

---

## 5. `text_display`（DamageShower）的客户端解读

> 地图唯一一套"飘字数字粒子"系统。**所有**有数字的 text_display 都来自 `damage.mcfunction`，挂 `Tags:["DamageShower","Prop"]`。下面 5.1 列出全部生成形态、5.7 列出文字颜色分级、5.8 列出生命周期与不生成的边界条件。

### 5.1 实体生成形态（伤害 vs 治疗两类）

**伤害粒子**（`damage.mcfunction:1022–1027`）：

```mcfunction
# spawn 时无 background；下一帧 data merge 改红
summon text_display ~±1 ~1 ~ {alignment:"left",billboard:"center",Tags:["DamageShower","Prop"],shadow:0b,see_through:1b}
data merge entity @s {background:-65536}            # 红色 #FF0000
```

**治疗粒子**（`damage.mcfunction:57–60`）：

```mcfunction
# spawn 时即带绿背景，无 see_through
summon text_display ~±1 ~1 ~ {alignment:"left",billboard:"center",Tags:["DamageShower","Prop"],shadow:0b,background:-16515325}
                                                    # 绿色 #0F1F03
```

**生成位置偏移**由全局 `#DamageNumbers CTT` 决定（每 tick `+1` mod 4）：

| `#DamageNumbers CTT` | 偏移 |
|---|---|
| 1 | `~+1 ~+1 ~` |
| 2 | `~-1 ~+1 ~` |
| 3 | `~ ~+1 ~+1` |
| 4 | `~ ~+1 ~-1` |

伤害粒子额外要求受害实体 `tag=CTTAll`（散落道具/装饰品受伤不飘数字）；治疗粒子无此过滤。

### 5.2 客户端可读 NBT（DataTracker）

`net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity` 的所有 DataTracker 项都同步：

| 字段 | 类型 | 客户端可读？ | 用途 |
|---|---|---|---|
| `text` | Text 组件 | ✅ | 显示的文字（即"伤害数字"或"治疗数字"），含 `score:DamageShower` 占位符；颜色按数值分级见 §5.7 |
| `text_opacity` | byte | ✅ | |
| `background` | int | ✅ | **`-65536` = 伤害（红 `#FF0000`）；`-16515325` = 治疗（绿 `#0F1F03`）；伤害的红色在 spawn 后**下一帧**才覆盖，期间是默认半透明灰** |
| `line_width` | int | ✅ | |
| `flags` | byte | ✅ | shadow、see_through、default_background、alignment |
| `billboard_constraints` | Pose 等 | ✅ | |
| `view_range` | float | ✅ | |
| `shadow_radius` | float | ✅ | |
| `glow_color_override` | int | ✅ | |
| `transformation` | Affine3 | ✅ | |
| `interpolation_*` | int / float | ✅ | |
| **`Tags` NBT** | string list | ❌ | 不同步——但反正是 `["DamageShower","Prop"]`，可由 EntityType + `text` 数字模式识别 |

### 5.3 客户端 mod 监听 DamageShower 出现

```java
@Mixin(ClientPlayNetworkHandler.class)
public abstract class SpawnMixin {
    @Inject(method = "onEntitySpawn", at = @At("RETURN"))
    private void cttHd$onSpawn(EntitySpawnS2CPacket p, CallbackInfo ci) {
        Entity e = client.world.getEntityById(p.getEntityId());
        if (e instanceof DisplayEntity.TextDisplayEntity td) {
            // 此时 text/background 可能还没填好，需要等下一 tick 或挂 tracker 监听
        }
    }
}
```

更稳妥做法：**每 tick 扫描所有 `text_display` 实体**，识别"刚出现且 `text` 是纯数字"的，记入伤害事件流。

### 5.4 归属推断（victim 是谁）

`damage.mcfunction` 第 1028 行的写入：

```mcfunction
execute at @e[scores={Damage=1..}] run execute as @e[tag=DamageShower,distance=..1.5]
  unless score @s DamageShower matches 0..
  run scoreboard players operation @s DamageShower = @e[scores={Damage=1..},limit=1,sort=nearest] Damage
```

意味着：**DamageShower 实体生成位置 ≈ 受伤实体位置 ±1.5 格**。客户端能用：

```java
double minDist = Double.MAX_VALUE;
LivingEntity victim = null;
for (Entity e : client.world.getEntities()) {
    if (!(e instanceof LivingEntity le)) continue;
    if (le.distanceTo(damageShower) > 2.0) continue;
    // 是 tag=CTTAll 候选：MaxHP > 0 + RedHearts >= 0 (从 scoreboard 反查)
    int maxHP = scoreboardLookup(le.getUuid(), "MaxHP");
    if (maxHP <= 0) continue;
    double d = le.distanceTo(damageShower);
    if (d < minDist) { minDist = d; victim = le; }
}
```

这与 `AttackerProbe.findVictimByDistance` 的策略一致，可直接照搬到客户端。

### 5.5 DamageShower 数值读取

伤害值 = DataTracker 的 `text` 字段渲染出的字符串（数字）+ background 颜色判断伤害/治疗。**也可以直接读它的 `DamageShower` scoreboard score**（§5.6）——更可靠，因为 `text` 在某些 mod / 资源包下可能被改造。

### 5.6 实体 UUID → DamageShower score

text_display 实体本身就是 score holder，holder 名 = 它的 UUID 字符串。`damage.mcfunction` 第 1028 行 `scoreboard players operation @s DamageShower = ... Damage` 把数值直接写到该实体的 `DamageShower` objective 上。客户端：

```java
ScoreboardObjective obj = sb.getNullableObjective("DamageShower");
int dmgValue = sb.getOrCreateScore(textDisplay, obj).getScore();  // 实体作为 ScoreHolder
```

**这是读取数值的首选方式**——比解析 `text` 字段渲染出的字符串可靠，因为：

- `text` 字段每 tick 被 `damage_shower.mcfunction` 重写（按数值分级换颜色 / 加乱码字符）。
- 资源包 / 客户端 mod 可能改造文本渲染。
- scoreboard score 是纯整数，不会被改造。

### 5.7 文字颜色分级（按 `DamageShower` 数值）

由 `cake_team_tower:misc/damage_shower` 主循环每 tick 把 `text` 字段刷成对应等级（同一个粒子在累计伤害的过程中会随等级变色）：

| 数值 | 颜色 | 样式 | 备注 |
|---|---|---|---|
| 1 – 40 | `gray` | 普通 | 微伤 |
| 41 – 60 | `white` | 普通 | 小伤 |
| 61 – 90 | `blue` | 普通 | 中伤 |
| 91 – 120 | `yellow` | 普通 | 大伤 |
| 121 – 150 | `red` | 普通 | 重伤 |
| 151 – 200 | `gold` | 普通 | 极重伤 |
| 201 – 399 | `dark_red` | **粗体** | 致命级 |
| 400 – 500 | `dark_red` 主体 + 两侧 `XX` `XX` 字符 `dark_blue` | 粗体 + obfuscated | 灾难级 |
| 501 – 600 | `dark_red` 主体 + 两侧 `XX` `XX` 字符 `dark_red` | 粗体 + obfuscated | 灾难级 |
| 601 + | `dark_red` 主体 + 两侧 `XXX` `XXX` 字符 `dark_red` | 粗体 + obfuscated | 末日级 |

**客户端识别建议**：判定"伤害还是治疗"看 `background`，判定"威力等级"看主文字 `color`。两者交叉验证可识别绝大多数场景。

### 5.8 生命周期 / 不生成的边界条件

**生命周期**（`damage_shower2.mcfunction` + `main.mcfunction`）：

```mcfunction
scoreboard players add @e[tag=DamageShower] DamageShower1 1
execute at @e[tag=DamageShower] run tp @e[tag=DamageShower,limit=1,sort=nearest] ~ ~0.025 ~
```

- 每 tick **上飘 0.025 格**（无重力，纯 `tp` 累积）。
- **不会自然消失**——靠"全场上限 10"淘汰机制：`damage.mcfunction:1065–1099` 重复 10 次清理代码块，每次见到 `≥10` 就随机 kill 1 个。最终全场始终保持 ≤ 10 个 DamageShower。
- **`#ServerLag CT = 1`** 时（`misc.mcfunction:933`）整批 `kill @e[tag=DamageShower]`，且当帧不再生成新粒子。

**不会生成的情况**：

| 触发条件不满足 | 原因 |
|---|---|
| `#ServerLag CT = 1` | 服务端卡顿降级 |
| 实体没 `Damage ≥ 1` 或 `HealDMG ≥ 1` | 无伤害事件 |
| 受害实体没有 `tag=CTTAll`（伤害） | 散落道具 / 箭 / 装饰品受伤不飘红字 |
| 实体被打上 `tag=CinematicDeath1, tag=!DiesFromElementium` | `HealDMG` 被提前 reset，不飘绿字 |
| 当帧已生成 10 个 | `limit=10,sort=random` 单帧硬上限 |

### 5.9 特殊覆盖（少数关卡会改 DamageShower 外观）

| 来源 | 触发条件 | 覆盖效果 |
|---|---|---|
| `boss12_electrium`（电之王二阶段） | `#Phase2 DungeonScores ∈ 620..625` | 最近的 DamageShower 加上 `CustomNameVisible:1b` + `CustomName: "XXX MASSIVE DAMAGE XXX"`（金 + 暗红 + obfuscated） |
| `misc_17_trident.mcfunction`（三叉戟） | 击杀帧 | 给所有 DamageShower 加 `tag=TridentKiller`（**仅 tag，外观不变；客户端读不到 tag**） |

### 5.10 客户端识别启发（无 tag 同步的兜底）

由于 `Tags` NBT 不同步给客户端（见 §12.1），不能靠 `tag=DamageShower` 直接筛实体。可用以下启发：

```java
boolean isDamageShower(Entity e) {
    if (!(e instanceof DisplayEntity.TextDisplayEntity td)) return false;
    int bg = td.getBackground();
    if (bg != -65536 && bg != -16515325) return false;        // 红 or 绿
    if (td.getBillboardMode() != BillboardMode.CENTER) return false;
    if (td.getAlignment() != TextAlignment.LEFT) return false;
    // 进一步：渲染 text 后应是数字 / "XX 数字 XX" 模式
    return true;
}

boolean isDamage = (td.getBackground() == -65536);     // 红色 = 伤害
boolean isHeal   = (td.getBackground() == -16515325);  // 绿色 = 治疗
```

**伤害粒子的红色有 1 tick 延迟**（spawn 时是默认半透明灰，下一帧才变红）——如果只在 spawn 包到达瞬间判定颜色会**误判为不是 DamageShower**。建议：

- 监听 `EntityTrackerUpdateS2CPacket`（DataTracker 增量）等 1–2 tick 再判定。
- 或者：spawn 时记下这个 text_display ID，1 tick 后再查 background。

---

## 6. Title / Subtitle / Actionbar

### 6.1 协议

| 包 | 含义 |
|---|---|
| `TitleS2CPacket` | 大标题文字 |
| `SubtitleS2CPacket` | 小标题文字 |
| `OverlayMessageS2CPacket` | actionbar（hotbar 上方文字） |
| `TitleFadeS2CPacket` | 淡入/停留/淡出时长 |
| `ClearTitleS2CPacket` | 清空 |

### 6.2 客户端可读

```java
@Mixin(ClientPlayNetworkHandler.class)
public abstract class TitleMixin {
    @Inject(method = "onTitle", at = @At("HEAD"))
    private void cttHd$onTitle(TitleS2CPacket p, CallbackInfo ci) {
        Text t = p.text();   // 完整 Text 组件
    }
    @Inject(method = "onOverlayMessage", at = @At("HEAD"))
    private void cttHd$onActionbar(OverlayMessageS2CPacket p, CallbackInfo ci) {
        Text t = p.message();
    }
}
```

### 6.3 地图侧典型用例

数据包用 title 显示 "Game Paused"、"GAME OVER"、关卡过场字幕、boss 出场字。Mod 可以拦截并替换/隐藏，或作为关卡边界的辅助信号源。

---

## 7. 聊天 / 系统消息（tellraw）

### 7.1 协议

| 包 | 来源 |
|---|---|
| `GameMessageS2CPacket` | `tellraw` / 服务端 system chat |
| `ChatMessageS2CPacket` | 玩家间 chat（带签名） |
| `ProfilelessChatMessageS2CPacket` | 控制台 / 命令行 chat |

### 7.2 地图侧典型 tellraw

| 文本片段 | 来源 / 含义 |
|---|---|
| `"Friendly fire towards other players pets..."` | `damage.mcfunction:1008`，friendly fire 惩罚 |
| `"Your Light Armor Blocked The Damage"` | `damage.mcfunction:643` |
| `"Thew Barrier blocked the damage!"` | `damage.mcfunction:855` |
| `"The Amethyst Domain blocked the damage!"` | `damage.mcfunction:863` |
| `"You are cursed by the sword..."` | `damage.mcfunction:879`，MessyCodeCursed 诅咒提示 |
| `"Your Resurrection Revived you"` | `damage.mcfunction:1103`，复活道具触发 |
| `"You are stunned and cannot use abilities"` | `server_main.mcfunction:8`，眩晕拒绝右键 |
| `"Ow, I got water in my eyes"` | `damage.mcfunction:363`，水暴击 |

### 7.3 客户端 mod 用法

- 把这些文本作为"事件信号"——服务端通过 `tag` 触发 tellraw，所以 tellraw 出现 ≈ 对应事件发生。
- 注意 `tellraw @a[tag=...]` 只对**有那个 tag 的玩家**发，所以"我看到了"⇔"我有那个 tag"。这是**唯一能间接探测自己 tag 的方法**——如果某 tellraw 是给 `@a[tag=FriendlyFirePunish]` 的，看到这条消息说明你刚被打了 `FriendlyFirePunish` tag。
- mod 用正则匹配文本时，**必须考虑翻译**——许多 tellraw 用 `{"translate":"..."}` 而不是 `{"text":"..."}`，文本会随客户端语言变化。建议匹配 `Text#getString()` 而不是结构。

---

## 8. 声音事件

### 8.1 协议

| 包 | 含义 |
|---|---|
| `PlaySoundS2CPacket` | 在指定坐标播放命名声音 |
| `PlaySoundFromEntityS2CPacket` | 从某实体身上播放 |
| `StopSoundS2CPacket` | 停止 |

### 8.2 地图侧识别

地图大量用 `playsound minecraft:custom.sound_fx.va_*`（语音），`minecraft:custom.sound_fx.soul_heart`（灵魂心受损），`minecraft:block.note_block.chime`（治疗），`minecraft:entity.player.burp`（大量治疗），等等。Mod 可识别这些声音作为事件信号。

详见 `damage.mcfunction` 第 50–113 行的声音表。

### 8.3 注意

- 声音受**距离过滤**——服务端只在玩家 `distance=..N` 时 `playsound`，且必须在视野内，所以远端事件可能听不到。
- 把声音当**辅助信号源**而非权威——scoreboard 才是权威。

---

## 9. 团队 / 玩家列表（TAB）

### 9.1 Team

`gamestart.mcfunction:48`：`team join PVE @a[tag=CTT]`。`team modify PVE nametagVisibility always` 等。

`TeamS2CPacket` 完全同步——客户端可：

```java
Scoreboard sb = client.world.getScoreboard();
Team pve = sb.getTeam("PVE");
Collection<String> members = pve.getPlayerList();  // 玩家名集合
```

**这是客户端识别"哪些玩家是 `tag=CTT`"的二级信号**——`team=PVE` 的玩家几乎等价于 `tag=CTT`。

### 9.2 Player List（TAB）

`PlayerListS2CPacket` 含每个玩家的 UUID、用户名、ping、gamemode、自定义名。Mod 可读。

---

## 10. 玩家自身（自我观测）

### 10.1 客户端独有数据

这部分只有"自己"能看到，但不依赖服务端发送：

| 信息 | 客户端 API |
|---|---|
| 自己的 UUID | `client.player.getUuid()` |
| 自己的位置 / 朝向 / 速度 | `client.player.getPos()` 等 |
| 自己的 `PlayerID = UUID[0]` | `(int)(uuid.getMostSignificantBits() >> 32)` |
| 当前选中的 hotbar slot | `client.player.getInventory().selectedSlot` |
| 当前持物 | `client.player.getMainHandStack()` |
| 自己的状态效果（potion） | `client.player.getStatusEffects()` |
| 攻击键按下 / swing 动画 | 可挂 `MinecraftClient#handleInputEvents` / `attackKey.wasPressed()` |
| 自己的 hurtTime（受伤红屏） | `client.player.hurtTime > 0` |
| 自己的 vanilla health | `client.player.getHealth()`（**但被劫持**） |
| 自己看向的实体（raycast） | `client.crosshairTarget` |

### 10.2 客户端能"听见"自己刚做了什么

- 攻击：`AttackEntityCallback.EVENT.register(...)` 或 mixin `MinecraftClient#doAttack`。
- 右键：`UseItemCallback` / `ClientPlayerInteractionManager#interactItem`。
- 弓箭释放：监听 `ServerboundReleaseUseItemPacket` 发送瞬间。
- 走路 / 跳跃：客户端自己模拟，可直接读 `player.isOnGround`、`player.jumping` 等。

**这给客户端 mod 的"攻击者归属"提供了独立证据链**——不依赖服务端 score。

### 10.3 PlayerID 重构（关键）

地图的 `PlayerID = UUID[0]` 在客户端**完全可重建**：

```java
public static int playerIdOf(UUID uuid) {
    return (int) (uuid.getMostSignificantBits() >> 32);  // UUID 的第 0 个 int
}
```

- **每个客户端能算出每个玩家（包括自己和他人）的 PlayerID**。
- 然后用它去匹配 `FriendlyID` / `OwnerID` / `SoulLinkedID` 等 scoreboard 字段。
- **这是客户端做归属的最强基础**——和服务端 mod 用同样的主键。

---

## 11. 成就（Advancement）

### 11.1 协议

`AdvancementUpdateS2CPacket` 同步成就树定义、进度、解锁。

### 11.2 地图相关成就

| Advancement | 触发 |
|---|---|
| `cake_team_tower:into_the_fire` | Magum Trials 进入 |
| `cake_team_tower:inventory_changed` | 检测物品栏变动（每帧） |

### 11.3 用法

`inventory_changed` 是地图用来**让 mod / 自己感知物品变化**的钩子——服务端在玩家丢出 `ender_eye` 时给 `DroppedEnderEye` 加 1 然后发 advancement。客户端 mod 可监听这个 advancement 作为"当前帧物品栏变了"的信号。

---

## 12. 客户端"读不到"的清单

### 12.1 实体 `Tags` NBT（最大限制）

vanilla 不同步 `Entity#getCommandTags()`。所以**所有形如 `tag=CTT`、`tag=E`、`tag=Boss`、`tag=F`、`tag=PlayerHurtSound`、`tag=Charge`、`tag=Hit`、`tag=ElementMelee`** 的检测客户端都做不到。

**绕过策略**：

1. **从 scoreboard 反查**：`tag=CTT` ⇔ 玩家 `GameID = #CTT GameID`；`tag=E` ⇔ 实体 `MaxHP > 0`；`tag=F` ⇔ 实体 `FriendlyID` 存在等。
2. **从 team 反查**：`team=PVE` ≈ `tag=CTT`。
3. **从 bossbar 观察者反查**：`p1001_onlyup` 等 bossbar 把"自己"加进观察者，所以"我能看到 player_1001 bossbar" ⇔ "某 CTT 玩家有 BossbarID=1001"（这能判断队伍构成）。
4. **从行为推断**：`tag=Charge` 这种瞬态 tag 完全不同步，但攻击动作的客户端表现（swing animation、弓拉满）足以替代。

### 12.2 服务端 NBT `data` 字段

不同步。

### 12.3 数据包函数 / 命令 / 选择器内容

不下发——客户端不知道服务端正在跑什么 `function`。

### 12.4 计分板 `played` 类 vanilla criterion 的累计

`scoreboard objectives add Foo minecraft.custom:minecraft.damage_dealt` 这种 criterion 是**服务端自动维护**的，下发的只是结果 score。客户端能读到结果，但读不到"哪一次"加的。

### 12.5 玩家间私聊 / 系统命令日志

视服务器配置，部分聊天可能不到客户端。

### 12.6 离线 / 远距离的事件

服务端用 `[distance=..N]` 限制了部分 `playsound` / DamageShower 的目标范围——超出范围的客户端收不到。

---

## 13. 客户端权限边界（无 OP / 输出能力）

> **核心约束**：纯客户端 mod 默认运行在**普通玩家权限**下（`op-level=0`）。这意味着 mod **完全只能"看"，不能"写"** —— 唯一允许的"反向通道"是地图 datapack 主动解锁的 `/trigger`。本节系统列出"客户端能做什么、不能做什么"。

### 13.1 客户端能向服务端发起的所有事

| 渠道 | 可用？ | 说明 |
|---|---|---|
| 移动 / 跳跃 / 蹲伏 / 视角 | ✅ | C2S 位置包，自然交互 |
| 物品交互（左键 / 右键 / 丢弃 / 拾取 / 切换槽位） | ✅ | C2S 物品包；地图通过 `minecraft.dropped:*`、`minecraft.used:*` 等 criterion 间接感知 |
| 攻击键（攻击实体） | ✅ | C2S 实体动作包；地图用 `Atk` tag + 距离启发判定攻击者 |
| 公共聊天 | ✅ | C2S chat 包；地图基本不解析聊天内容 |
| `/me` / `/msg` / `/teammsg` / `/help` | ✅（视服务器配置） | 与地图无交互 |
| **`/trigger <obj>`** / `/trigger <obj> set <n>` / `/trigger <obj> add <n>` | ✅ | **唯一能向地图 datapack 发"结构化指令"的官方渠道** |
| `/scoreboard` 等 OP 命令 | ❌ | 服务端拒绝（`level=2/3`） |
| `/give` / `/tp` / `/data` / `/execute` | ❌ | 拒绝 |
| `/tellraw` / `/title` / `/playsound` / `/effect` | ❌ | 拒绝 |
| `/reload` | ❌ | 拒绝 |
| `/function <ns>:<name>` 直接调用 | ❌ | 拒绝 |
| 自定义 plugin message（custom payload） | ❌ | 纯原版服务端不会响应任何未知 channel |

### 13.2 当前地图为非 OP 玩家解锁的全部 `/trigger`

> 数据来自 `cake_team_tower:gamestart` / `misc/misc.mcfunction` / `lobby/lobby_main.mcfunction` / `floors/scenes/break_room_universal.mcfunction` 等的 `scoreboard players enable` 调用。

#### 给所有 `tag=CTT` 玩家解锁

| Trigger | 用法示例 | 触发条件 / 数据包反应 |
|---|---|---|
| `ViewStats` | `/trigger ViewStats` | gamestart + 每个 break room；调 `view_stats.mcfunction`，输出整页 tellraw 属性面板 |
| `Continue` | `/trigger Continue` | 仅当 `#GameOver CTT = 100`（续币界面）；扣 `#ContinuePrice CTT` 个 Coins，恢复 Lives |
| `Suicide` | `/trigger Suicide` | 非 `#ClassSelect` 期间；玩家自杀（用于卡关时主动重生） |
| `Kick` | `/trigger Kick` | 仅 2+ 玩家；发起踢人投票 |
| `Weld` | `/trigger Weld` | 非 `#NoWelding` 模式；将物品焊接（合成） |
| `UnWeld` | `/trigger UnWeld` | 同上；拆焊 |
| `ViewMissions` | `/trigger ViewMissions` | 显示当前任务 / 进度 |
| `ToggleHealthCrystalExchange` | `/trigger ToggleHealthCrystalExchange` | 开关血晶兑换 |
| `Bank` | `/trigger Bank` | 非 `#NoWelding`；银行操作 |
| `Fuse` | `/trigger Fuse` | 道具熔合 |
| `TogglePartyBossbar` | `/trigger TogglePartyBossbar` | 开关队伍 boss 栏（个人偏好） |
| `Spray` | `/trigger Spray` | 喷漆 / 标记装饰 |

#### 给所有 `@a`（含旁观者 / 非 CTT）解锁

| Trigger | 何时被 enable | 用途 |
|---|---|---|
| `GetDate` | 每 tick（`misc.mcfunction:1463`） | 输出当前服务器日期 |
| `ToggleVoiceActing` | 4 月起每 tick | 开关全局语音 |
| `ToggleSoulAmmo` | 每 tick | 开关灵魂弹药 |

#### 条件性解锁（特定 tag / score）

| Trigger | 条件 | 用途 |
|---|---|---|
| `ToggleCinematic` | `tag=SpectatingGame` | 旁观者切换电影模式 |
| `Backfill` / `Spectate` | `BackfillMessage ≥ 20` | 中途加入 / 切到旁观 |
| `WarpTokenShop` | `tag=!CTT` | 在大厅传送到代币商店 |
| `Talk` | `NPCTalk ≥ 1` | 与 NPC 对话推进 |
| `Softlocked` | `tag=SoftLockPerms` | 报告卡关 |
| `M1_Fast` / `M1_Slow` | `#Gamemode CTT = 2` 且 `#ClassSelect CTT = 120` | 个人速度选项 |
| `D27_GiveD10` | 楼层 27 内 `D10Func ≥ 1` | 关卡专用奖励 |

#### **OP 限定（mod 不可用）**

`Reload`, `DevContinue`, `QuickStart`（这些数据包用 `scoreboard players enable @a[tag=OP] X` 解锁，普通客户端没有 `tag=OP`，调用会失败）。

### 13.3 `/trigger` 的客户端调用方式

```java
// Fabric 客户端
client.getNetworkHandler().sendChatCommand("trigger ViewStats");
// 或者
client.player.networkHandler.sendCommand("trigger ViewStats");
```

**注意**：

1. **每次 trigger 用一次会被自动 disable**——服务端必须再次 `scoreboard players enable` 才能再次触发。地图基本是"每 tick / 每场景重新 enable"模式，所以一般立即可再用。
2. **trigger 只能改"自己"的 score**，不能改其他玩家或假玩家。
3. **频率限制是 mod 自己的责任**——服务端无限制，但用户体验上**不要**自动连发，否则会刷屏 / 干扰其他玩家。
4. 如果 trigger 当前未被 enable，命令会**静默失败**（服务端仅返回错误消息，不影响游戏）。可在调用前用 `scoreboard.getOrCreateScore(self, obj).getScore()` 检查 trigger 是否启用——但 trigger 启用状态**不通过 scoreboard 同步给客户端**，所以无法从客户端探测，只能靠"看到我能用 → 试一次"启发。

### 13.4 间接信号：地图通过哪些事件感知客户端行为

即使 mod 不主动调 `/trigger`，地图 datapack 也能从以下渠道感知客户端：

| 客户端动作 | 地图侧 criterion / 检测 |
|---|---|
| 丢出末影之眼 | `DroppedEnderEye` (`minecraft.dropped:minecraft.ender_eye`) → 触发 `inventory_changed` advancement |
| 使用弓 | `CupidArrowShoot` 等 (`minecraft.used:minecraft.bow`) |
| 武器击杀 | `CupidsSpearDMG`、`FireAspectDMG` 等 (`minecraft.custom:minecraft.damage_dealt`) |
| 右键持物 | `SG_RightClick`（StackedSlot 包检测）→ `RightClick` |
| 走路距离 / 跳跃次数 | 任意 `minecraft.custom:*` criterion |
| 完成 advancement | `cake_team_tower:into_the_fire` 等 |

**这些 criterion-based 通道是"无 OP 也能让数据包看到客户端"的副渠道**，但都是 vanilla 行为，mod 不能伪造，也不应自动化（构成作弊）。

### 13.5 这对 mod 设计意味着什么

#### 必须接受的强约束

1. **完全只读**：mod 不能写 score、不能改实体 NBT、不能 spawn 实体、不能改世界、不能改其他玩家显示。
2. **无法注入修复**：地图 bug、误判、错算 都只能**在 mod 显示层面**绕过（如 mod 自己重算一次再覆盖渲染），不能修复源头。
3. **无法跨玩家通信**：mod 不能"通知队友"——除非走 vanilla 公屏聊天，但那等同于让服务端看到。
4. **无服务端持久化**：所有 mod 状态保存在客户端 config / cache，重新连服后从零开始重建（参考 §15.5）。

#### 可绕过的软约束

1. **想给数据包打信号** → 通过 `/trigger`（仅限 §13.2 列表内）。
2. **想检测自己的输入 / 物品 / 移动** → 客户端独立完成，不必通过服务端。
3. **想做 HUD / 通知 / 音效叠加** → 完全本地，无任何服务端涉入。
4. **想保留长期统计** → mod 自己存到 config / 数据库（如赛季统计、个人 PR）。

#### 反模式（绝对不要做）

1. ❌ **客户端伪造 C2S 包**冒充其他玩家行动（构成作弊）。
2. ❌ **批量自动 `/trigger`** 高频调用（`/trigger Suicide`、`/trigger Continue` 等若被 mod 滥用会卡服务端）。
3. ❌ **修改 vanilla 命令本地校验**绕过权限检查（即使本地能跳过，服务端依旧拒绝，且违反 EULA）。
4. ❌ **借 vanilla 公屏聊天**在玩家间传输 mod 状态（用户可见，会被误识为作弊 / 刷屏）。

### 13.6 与"双端 mod"的功能差距清单

| 功能 | 双端 mod (服务端 mod 装在 server 上) | 纯客户端 (无 OP) |
|---|---|---|
| 显示心数 / 属性 / Stage | ✅ | ✅ |
| 累计统计（伤害 / 击杀 / 承伤） | ✅ | ✅ |
| 攻击者归属 | ✅ 高精度 | ⚠️ 中精度（缺 `Atk`/`Hit`/`PlayerHurtSound` tag） |
| 局元数据 / 复盘记录 | ✅ 持久 | ⚠️ 仅本机 |
| 修复地图判定 bug | ✅ | ❌ |
| 自定义 / 注入新 objective 给所有玩家用 | ✅ | ❌ |
| 服务端持久化（`PersistentState`） | ✅ | ❌（只能存本地 config） |
| 跨玩家广播自定义事件 | ✅ | ❌ |
| 为其他玩家修改显示 | ✅ | ❌ |
| 与第三方 mod 服务端整合 | ✅ | ❌ |
| 修改 / 接管 vanilla 命令 | ✅ | ❌ |

### 13.7 一个常见误解的澄清

> "我作为 mod 作者，难道不能在客户端**模拟**一次 OP 命令吗？"

**不能**。OP 等级由服务端持有（保存在 server `ops.json`），所有命令最终在 **服务端 `ServerCommandSource.hasPermissionLevel(N)`** 处校验。客户端无论如何重写本地命令分派，发出去的命令包在服务端依然按"普通玩家"权限执行——拒绝。

唯一例外：**"客户端命令"** —— Fabric API 的 `ClientCommandRegistrationCallback` 允许在客户端注册纯本地命令（如 `/cttHd config`），这些命令**完全不发往服务端**，可任意定义。这是 mod 给用户做"本地 GUI 入口"的标准做法，但**不能影响服务端状态**。

---

## 14. 客户端 mod 重建关键功能的策略

### 14.1 识别"哪些玩家正在玩 CTT"

```java
// 优先级：
// 1) GameID 匹配
int globalGameId = clientScore("#CTT", "GameID");
boolean isCtt = (clientScore(playerName, "GameID") == globalGameId && globalGameId != 0);

// 2) Team 备用
isCtt = isCtt || ("PVE".equals(player.getScoreboardTeam().getName()));
```

### 14.2 4 层心数读取

```java
String name = player.getNameForScoreboard();
int red   = score(name, "RedHearts");
int max   = score(name, "MaxHP");
int soul  = score(name, "SoulHearts");
int black = score(name, "BlackHearts");
int blue  = score(name, "BlueHearts");
int allHp = score(name, "AllHearts");      // = red+soul+black+blue（每 tick 服务端重算）
int hpPct = score(name, "HealthPercent");  // 0..100
```

### 14.3 攻击者归属（客户端启发式）

| 层 | 信号 | 来源 |
|---|---|---|
| L1 自己刚右键 / 攻击 | mod 内部 `attackTickStamp` | 客户端键盘事件 |
| L2 弹射物归属 | `ProjectileEntity#getOwner()` | vanilla DataTracker（弓箭等） |
| L3 召唤物归属 | 实体 `OwnerID` score = 攻击者 PlayerID | scoreboard |
| L4 友军归属 | 实体 `FriendlyID` score = 主人 PlayerID | scoreboard |
| L5 SoulLink | `SoulLinkedID` 链 | scoreboard |
| L6 距离最近的 CTT | DamageShower 周围 ±3 格内的 CTT 玩家 | 实体距离 |
| L7 自己刚被攻击 (hurtTime) | `client.player.hurtTime > 0` | vanilla |
| L9 未分类 | 兜底 | — |

**对比服务端版本**（`AttackerProbe`）：

- 服务端有 `Atk`/`Hit`/`PlayerHurtSound` tag——客户端**没有**，所以归属精度天然下降一档。
- 服务端可在 `damage.mcfunction` 写入瞬间介入；客户端只能事后看 score 增量，**有 1 tick 延迟**。
- 但客户端 100% 可信地知道"我刚做了什么动作"——L1 比服务端的"附近最近玩家 + 时序 tag"更稳。

### 14.4 DamageShower 数据流（客户端版）

每 tick：

1. 扫 `client.world.getEntities()` 找新出现的 `text_display` 且 `text` 是数字。
2. 读 `background` 判定伤害（红 `-65536`） / 治疗（绿 `-16515325`）。
3. 用位置近邻 + scoreboard `MaxHP` 反查 victim。
4. 读 victim 的 `RedDamageTook`/`BlackDamageTook` 增量做交叉验证。
5. 用 §14.3 的归属链找 attacker。
6. 写入本地伤害事件流。

### 14.5 Stage / Floor / 局会话边界

```java
int gameId = score("#CTT", "GameID");
int tier   = score("#Tier", "CTT");
int floor  = score("#Floor", "CTT");
int dungeon= score("#Dungeon", "CTT");
int shop   = score("#Shop", "CTT");
int mboss  = score("#MBoss", "CTT");
int boss   = score("#Boss", "CTT");
int ally   = score("#Ally", "CTT");
int misc   = score("#Misc", "CTT");
int breakRoom = score("#BreakRoom", "CTT");
int gameOver  = score("#GameOver", "CTT");
int pause     = score("#PauseGame", "CTT");
```

Stage 进入：上 tick `dungeon+shop+mboss+boss+ally+misc == 0`，本 tick > 0。

新局：`gameId` 变化。

### 14.6 击杀 / 死亡检测

- 玩家死亡：监听到 `Lives` 减 1 + `RedHearts` 重置。
- 敌人死亡：实体被 vanilla `EntitiesDestroyS2CPacket` 销毁，且最后一帧它的 `RedHearts ≤ 0`。
- 注意 `Coffin` tag 的实体即使 `RedHearts ≤ 0` 也不死亡——客户端无法区分 `tag=Coffin`，但通常 Coffin 实体不会被销毁（一直留在原地），**不会触发 destroy 包**——可用"实体 `RedHearts ≤ 0` 持续多 tick 还没销毁 → 是 Coffin"启发。

### 14.7 玩家属性面板

直接读 `Strength`, `Archery`, `Defence`, `FireArmor`, `IceArmor`, `Mana`, `MaxMana`, `Lives`, `Coins`, `Tokens`, `XP`, `Level` 等等（见 §16）。

### 14.8 boss 栏增强

```java
@Mixin(BossBarHud.class)
public abstract class BossbarHudMixin {
    @Shadow @Final private Map<UUID, ClientBossBar> bossBars;
    // ...
}
```

读取 `bossBars` map 里所有 `ClientBossBar` 的 `name`/`percent`/`color`。匹配 `name` 字符串模式即可识别哪个槽位是哪个玩家。**或者**直接对每个 1001..1010 的 `BossbarID` 玩家读 scoreboard 做自己的渲染。

---

## 15. 陷阱与时序

### 15.1 客户端 scoreboard 缓存的延迟

- 服务端 `damage.mcfunction` 在第 N tick 写入 `MeleeDMG`，到客户端一般是第 N+1 tick 早期才能看到 score 更新（取决于网络）。
- `Damage` 在第 N tick 末就被 `reset` 了，但客户端可能在 N+1 才看到 `Damage = X` 然后 `Damage = 0`——**关键事件可能"压扁"成一个**。

**对策**：mod 自己维护 score 增量历史（环形 buffer），每个 tick 取 deltas 而不是绝对值。

### 15.2 "我"是不是 CTT 的判断时机

`gamestart` 给 `tag=CTT` 后，下一 tick `GameID` 才更新；客户端可能再延 1 tick 才收到。**进局后 2~3 tick 内不要做 CTT 判断**。

### 15.3 entity UUID 同步时序

实体 spawn → 收到 spawn 包 → 客户端建实体；scoreboard score 可能**在实体 spawn 包到达之前**就到了（因为 server 把 score 加到一个 UUID 字符串持有者，再 spawn 实体）。读不到时**容忍 1-2 tick 延迟**。

### 15.4 `text_display` 出现的非确定性

- 服务端在 `#ServerLag CT = 1` 时**不 spawn** DamageShower。客户端就完全看不到这一帧的伤害可视化——**但 scoreboard 上 `Damage`/`*DMG`/`RedHearts` 仍正确**。
- 服务端最多保留 10 个 DamageShower（超出随机 kill），所以**爆发性伤害下会丢部分粒子事件**。
- **结论**：DamageShower 当**辅助/可视化数据源**，主数据源走 scoreboard。

### 15.5 离开服务器时的清理

`tag=CTT_LeftServer` 标志在服务端打，但客户端**不感知**——只能靠 `client.player.getNetworkHandler().getPlayerList()` 检测玩家离线。

### 15.6 跨维度 / 跨 World

地图全部在 overworld 里跑，跨维度不是问题；但要小心：进入主菜单/再进入服务器时 `ClientWorld` 重建，scoreboard 缓存全清——**mod 必须在 `ClientPlayConnectionEvents.JOIN/DISCONNECT` 重置内部状态**。

---

## 16. 可读 Scoreboard 速查表

> 全部都可以纯客户端读到（vanilla S2C 自动同步）。"持有者"列说明该 score 关联的是谁。

### 16.1 全局假玩家（`#X CTT` / `#X GameScores` / `#X CT` / `#X DungeonScores`）

| Holder | Objective | 含义 |
|---|---|---|
| `#CTT` | `GameID` | 全局自增局数 |
| `#Tier` | `CTT` | 当前 Tier |
| `#Floor` / `#FloorT` | `CTT` | 当前 Floor / Floor 临时态 |
| `#Dungeon` / `#Shop` / `#MBoss` / `#Boss` / `#Ally` / `#Misc` | `CTT` | 6 个 stage holder |
| `#BreakRoom` / `#BreakRoomID` / `#CheckPoint` | `CTT` | 休息室与检查点 |
| `#GameOver` / `#GameOverContinueScreen` | `CTT` | 失败 / 续币 |
| `#PauseGame` | `CTT` | 暂停标志 |
| `#LobbyMiniGame` | `CTT` | 0..8 模式 |
| `#ServerLag` / `#4Tick` / `#20Tick` / `#RNG` | `CT` | 系统计时器（tick 同步） |
| `#DamageNumbers` / `#DamageShowerAmount` | `CTT` | 粒子状态 |
| `#DisableBossBars` | `CTT` | 关闭 boss 栏标志 |
| `#Healthy` / `#Bloodless` / `#QuickPlay` / `#Festive` / `#GlassBones` / `#CursedBladeMode` / `#BuddySystemMode` / `#ElementalMode` / `#ChoicesMode` / `#SpeedRunnerMode` / `#GreedMode` / `#GlassCannonMode` | `GameScores` | 模式开关 |
| `#Hardcore` / `#Haunted` / `#BlindCurse` / `#KarmaChallengeID` / `#HeadlessHorsemagumChallenge` / `#WaveModeMap` / `#MeteroiteDungeon` | `CTT` | 诅咒 / 全局开关 |
| `#NPCTalkHappened` / `#ItemUniversalRan` / `#T131_MaxHPUp` / `#NPCTalkDisabled` 等 | `DungeonScores` | 楼层级临时变量 |
| `#Skulls` | `CTT` | 全队骷髅 |

### 16.2 玩家持有（holder = 玩家用户名）

#### 健康 / 心数

| Objective | 含义 |
|---|---|
| `RedHearts` / `MaxHP` | 主血 |
| `BlueHearts` / `MaxBlueHearts` | 蓝心 |
| `BlackHearts` / `MaxBlackHearts` | 黑心 |
| `SoulHearts` / `MaxSoulHearts` | 灵魂心 |
| `AllHearts` / `ScoreboardHP` | 4 层之和 |
| `HealthPercent` | 0..100 |
| `Defence` / `FireArmor` / `WaterArmor` / `IceArmor` / `DarkArmor` / `LightArmor` / `ElectricArmor` / `TrueArmor` / `TrueFireArmor` | 装甲 |
| `Lives` | 剩余生命 |
| `RedDamageTook` / `BlackDamageTook` | 累计承伤（关键，**主血伤害数据源**） |
| `DamageHealed` | 累计治疗 |
| `SoulHeartsGained` / `BlackHeartsGained` | 累计获得 |
| `CrackedHearts` / `PinkHearts` / `NegMaxHealth` | 特殊心 |

#### 资源 / 货币

| Objective | 含义 |
|---|---|
| `Coins` / `Tokens` / `XP` / `Level` | 货币 / 经验 |
| `Mana` / `MaxMana` / `ManaPower` / `ManaRechargeSpeed` | 法力 |
| `ActiveCharge` / `MaxActiveCharge` / `ActiveSpeed` | 主动充能 |
| `Stamina` / `MaxStamina` / `StaminaSpeed` | 体力 |
| `Air` / `MaxAir` | 氧气 |

#### 攻击属性

| Objective | 含义 |
|---|---|
| `Strength` / `Archery` / `AttackSpeed` / `AttackRange` | 主战 |
| `Healing` / `HealPercent` / `ExtraHealing` / `TowersRegen` | 治疗系 |
| `Summoning` / `MaxSummons` / `Fishing` / `BeePoints` / `AntPoints` / `Bitches` | 杂项 |

#### 移动

| Objective | 含义 |
|---|---|
| `MaxSpeed` / `SpeedAmplifier` / `SpeedRaw` / `MaxSpeedRaw` | 速度 |
| `Jump` / `DoubleJump` | 跳跃 |
| `Size` / `Gravity` | 体型 / 重力 |
| `JumpPressed` / `OnGround` / `Shifting` / `MovementLocked` / `Frozen` / `CantMove` | 即时状态 |

#### 业力 / 头骨

| Objective | 含义 |
|---|---|
| `LightKarma` / `NeutralKarma` / `DarkKarma` / `CelestialKarma` | 业力 |
| `LightKarmaCost` / `DarkKarmaCost` | 消耗 |

#### 身份 / 路由

| Objective | 含义 |
|---|---|
| `PlayerID` | UUID[0] |
| `GameID` / `BackfillID` | 局 ID |
| `BossbarID` | 1001..1010 |
| `Class` / `ClassStat` / `ClassPassive` | 职业 |
| `HeartValue` | 1..4，bossbar 当前显示哪种心 |

#### 即时事件 / 输入

| Objective | 含义 |
|---|---|
| `RightClick` / `SG_RightClick` | 右键事件（每 tick 末重置） |
| `ArrowShoot` / `CupidArrowShoot` | 弓使用 |
| `ViewStats` (trigger) | 查询面板 |
| `Reload` | OP reload 标志 |
| `DroppedEnderEye` (criterion) | 物品栏变更检测 |
| `Stunned` | 眩晕 |

#### 传入伤害分类（每 tick 写入再合并）

| Objective | 含义 |
|---|---|
| `MeleeDMG` / `BulletDMG` / `FireDMG` / `IceDMG` / `DarkDMG` / `LightDMG` / `ElectricDMG` / `ForceDMG` | 9 种来源 |
| `HealDMG` | 治疗 |
| `BlackDMG` / `SoulDMG` | 直写黑心/灵魂心 |
| `RandomElementDMG` | 随机元素 |
| `Pierce_Damage` | vanilla 反算 |
| `Damage` | 综合（每 tick 末重置为 0） |
| `FriendlyDMG` / `FriendlyID` | 友伤 |

### 16.3 实体持有（holder = 实体 UUID 字符串）

敌人 / 友军 / 召唤物 / DamageShower 实体的同名 score（`RedHearts`, `MaxHP`, `Damage`, `*DMG`, `OwnerID`, `FriendlyID`, `DamageShower`, `HurtSound`, `Defence`, `*Armor`, ...）。客户端通过实体 UUID 查询。

### 16.4 显示槽 objective（侧边栏 / 名字下方 / TAB）

| 槽位 | 默认 objective | 何时切换 |
|---|---|---|
| sidebar | `Lives` | gamestart |
| below_name | `HealthPercent` | gamestart |
| list (TAB) | `HealthPercent` | gamestart；break room 切到 `ScoreboardHP` |

部分关卡会临时把 sidebar 切到 `S12_Points`、`OnlyUpHighscore`、`MarioCoins`、`M08_KillStreak`、`PP_Highscore` 等关卡专用 objective——客户端可通过 `ScoreboardDisplayS2CPacket` 监听。

---

## 17. 可读 Bossbar 清单

> 全部 bossbar 名称都已在 `data/misc/function/scoreboards_part_2.mcfunction` 里 `bossbar add`，客户端在被加入观察者时收到完整 ADD 包。

### 17.1 玩家个体（每个 1001..1010 玩家一份）

- `minecraft:player_1001` ... `player_1010` —— 各玩家自己的属性栏（HP/Lives/Mana/Coins）
- `minecraft:targetting_1001` ... `targetting_1010` —— 各玩家锁定敌人血条
- `minecraft:p1001_drowning` ... `p1010_drowning`
- `minecraft:p1001_onlyup` ... `p1010_onlyup`

### 17.2 全队公共

- `minecraft:skulls`（骷髅头计数，name 含 `#Skulls CTT`）
- `minecraft:targetting`（旁观者锁定 boss）
- `minecraft:boss` / `boss_rush_boss_bar` / `boss_warping`
- `minecraft:advancement` / `vote` / `continue` / `floor` / `warping` / `warpwait`
- `minecraft:cake_bossbar` / `m04_bow_training` / `s12_timer` / `debug_floor`
- `minecraft:vt_train_hp` ("Skibidi Toilet Rizz" / Love is a Battlefield)
- `minecraft:hoosact6_bossbar` / `b24_bossbar` / `daredevil` / `minigame_bossbar` / `breadwinners` / `barry` / `tag` / `hc` / `oh_bossbar`

### 17.3 派生 bossbar（其他游戏模式）

`m6_total_minigames`, `m6_timer`, `turns_left`, `roll`, `aang_final`, `shop`, `practice_mode`, `spectator_bossbar`, `m4_4_time_stop`, `cake_wars`, `vote`, `mob`, `moose`, `muck`, `pickwait`, `ready_up`, `sc_ready_up`, `cap_final`, `change_game`, `level2`, `d48_bossbar` 等。

### 17.4 用法建议

- **不要**从 bossbar `name` 字符串解析 HP/Mana 值——name 模板会随 `ClassPassive`、`Berserk` 等动态变。
- **要**用 `BossbarID = 1001..1010` 反查玩家，再读那个玩家的 scoreboard 拿到原始数据。
- bossbar 在客户端的真正用途是：**识别游戏阶段**（哪些 bossbar 当前可见 + 它们的 percent 是多少），以及**追踪 boss 血条**（`targetting_*` + `boss` + `boss_rush_boss_bar`）。

---

## 附录 A：纯客户端 mod 的可行功能矩阵

| 功能 | 可行？ | 实现要点 |
|---|---|---|
| 4 层心数 HUD | ✅ 完美 | 读 `RedHearts`/`MaxHP`/`Soul/Black/Blue` |
| 玩家属性面板 | ✅ 完美 | 读 `Strength`/`Defence`/`Mana`/... |
| Lives / Coins / Tokens / XP HUD | ✅ 完美 | 玩家 score |
| Stage / Floor / Tier 显示 | ✅ 完美 | `#X CTT` 假玩家 |
| 当前关卡名 | ✅ 良好 | 由 `(#Dungeon, Tier)` 等组合反查（mod 内置 ID→名称表） |
| 队伍 boss 栏增强 | ✅ 良好 | bossbar 包 + 各玩家 score |
| 累计承伤 / 治疗统计 | ✅ 良好 | `RedDamageTook` / `BlackDamageTook` / `DamageHealed` 增量 |
| 伤害类型分类（输出） | ✅ 良好 | `*DMG` 写入序 + DamageShower |
| 攻击者归属 | ⚠️ 较好 | L1 自己输入 + L2-L5 弹射物/召唤物归属；缺 `Atk`/`Hit` tag 致精度略降 |
| 击杀 / 助攻统计 | ⚠️ 较好 | 实体死亡 + 攻击者归属链 |
| 友伤识别 | ⚠️ 较好 | `FriendlyID` + `FriendlyDMG`；惩罚发生时通过 `FriendlyFirePunish` tellraw 间接探测 |
| 探测自身 tag | ⚠️ 启发 | tellraw 文本匹配（`@a[tag=X]` 收到 ⇔ "我有 tag X"） |
| 服务端 `function` 调用监听 | ❌ | 不可能 |
| `tag=Coffin` 实体过滤 | ⚠️ 启发 | "RedHearts≤0 仍存活多 tick" |
| 客户端控制游戏 | ❌ | 无 C2S 自定义渠道（不该做） |

---

## 附录 B：与服务端 mod (`ctt-health-display` v6.x) 的对比

| 维度 | 服务端 mod (v6.6.10) | 纯客户端 mod |
|---|---|---|
| 安装位置 | 必须服务端 + 客户端都装 | 只装客户端 |
| 目标用户 | 私服 / 自办 | 任何加入官方服务器的玩家 |
| 核心数据源 | Mixin `ServerScoreboard#setScore` + 服务端 entity tags + DamageShower | 客户端 `ScoreboardScoreUpdateS2CPacket` + 客户端 entity tracking + DamageShower |
| 攻击者归属 | 8 层 + 3 子层（`AttackerProbe`） | 6-7 层（缺 `Atk`/`Hit`/`PlayerHurtSound` tag） |
| 时序精度 | 与游戏 tick 同步 | +1 tick 网络延迟 |
| 玩家 tag 探测 | 直接读 | tellraw 启发 |
| Friendly fire 识别 | `FriendlyFirePunish` tag | `FriendlyFirePunish` tellraw 文本 |
| 适用面 | 适合"私服管理者"调研、回放 | 适合"主流玩家"日常增强 HUD |
| 性能 | 服务端开销可控 | 完全本地，无服务器压力 |
| 兼容风险 | 服务端版本升级 / 数据包改动需要追 | **更稳**——只依赖原版 S2C 协议 |

**结论**：纯客户端 mod 在 90% 的"显示 / 统计 / HUD"场景能达到与服务端 mod **相当的体验**，主要损失在攻击者归属精度和某些瞬态事件捕获。对于公共服务器玩家，**这是唯一可行路径**。

---

## 附录 C：Mixin 入口点速查

| 用途 | Mixin 目标 | 方法 |
|---|---|---|
| 监听 score 更新 | `ClientPlayNetworkHandler` | `onScoreboardScoreUpdate(ScoreboardScoreUpdateS2CPacket)` |
| 监听 score 重置 | `ClientPlayNetworkHandler` | `onScoreboardScoreReset(ScoreboardScoreResetS2CPacket)` |
| 监听 objective 增删改 | `ClientPlayNetworkHandler` | `onScoreboardObjectiveUpdate(ScoreboardObjectiveUpdateS2CPacket)` |
| 监听 显示槽 | `ClientPlayNetworkHandler` | `onScoreboardDisplay(ScoreboardDisplayS2CPacket)` |
| 监听 bossbar | `ClientPlayNetworkHandler` | `onBossBar(BossBarS2CPacket)` |
| 监听 tellraw | `ClientPlayNetworkHandler` | `onGameMessage(GameMessageS2CPacket)` |
| 监听 title | `ClientPlayNetworkHandler` | `onTitle(TitleS2CPacket)` / `onSubtitle` / `onOverlayMessage` |
| 监听 sound | `ClientPlayNetworkHandler` | `onPlaySound(PlaySoundS2CPacket)` / `onPlaySoundFromEntity` |
| 监听 实体 spawn | `ClientPlayNetworkHandler` | `onEntitySpawn(EntitySpawnS2CPacket)` |
| 监听 实体追踪数据 | `ClientPlayNetworkHandler` | `onEntityTrackerUpdate(EntityTrackerUpdateS2CPacket)` |
| 监听 实体属性 | `ClientPlayNetworkHandler` | `onEntityAttributes(EntityAttributesS2CPacket)` |
| 监听 装备槽 | `ClientPlayNetworkHandler` | `onEntityEquipmentUpdate(EntityEquipmentUpdateS2CPacket)` |
| 监听 实体销毁 | `ClientPlayNetworkHandler` | `onEntitiesDestroy(EntitiesDestroyS2CPacket)` |
| 监听 自己攻击键 | `MinecraftClient` | `doAttack` |
| 监听 自己交互键 | `MinecraftClient` | `doItemUse` |
| 渲染 HUD | `InGameHud` | `render` |

> 具体方法名以当前 Yarn / Mojang 映射为准，请在 IDE 中确认。

---

## 维护说明

- 当 vanilla MC 协议升级（如新版本 1.22 / 1.23 修改 scoreboard packet 结构）时，§2.1 需更新。
- 当数据包新增 / 删除 bossbar 或 objective 时，§3.2 / §16 / §17 需追加。
- 当数据包新增 / 删除 `/trigger` enable 调用时，§13.2 需追加。
- 若服务器从纯原版变成 Spigot/Paper 等带 mod 的服务端，本文 §1 的"基础假设 1"破坏，整篇需重新评估（Paper 的 entity tag 同步插件会改变 §12.1 的结论）。
- 与 `MAP_DATAPACK_REFERENCE.md` 出现冲突时，**§2..§4 以 vanilla MC 源代码为准；§13、§16、§17 以 `MAP_DATAPACK_REFERENCE.md` 为准**（地图侧定义是源头）。
