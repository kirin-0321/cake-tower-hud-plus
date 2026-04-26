# Cake Tower HUD Plus · 模组介绍

> 这份文档给玩家 / 整合包 / 商店页用「卖点」文案；**技术版**给开发者、地图联调、贡献者看架构与数据流。更细的类级说明见 `FEATURES.md` 与源码内 JavaDoc。

---

## 目录

1. [玩家版（中文 · 推荐）](#一玩家版中文--推荐)
2. [精简版（中文 · 整合包 / mod 描述栏）](#二精简版中文--整合包--mod-描述栏)
3. [技术版（中文 · 开发者）](#三技术版中文--开发者)（**§3.1** · `AttackerProbe`：L1–L8 + **L9 三子层**）
4. [英文版](#四英文版用于英文社区--curseforge--modrinth)
5. [一行短文](#五一行短文用于推广--朋友圈)

---

## 一、玩家版（中文 · 推荐）

> # Cake Tower HUD Plus
>
> **专为「蛋糕塔 Chapter 3 · Cake Team Towers」做的画面增强 + 战绩统计模组。**
> 让你打塔时不用再眯眼看 4 条 Boss 栏顶上几行小字 —— 血量、队友、怪物、伤害排名一眼到位。

### 它能让你看到什么

#### 屏幕底部 · 全新的状态条
- **彩色血条**：底下自带渐变血条，掉到一半变黄、四分之一变红还会闪 —— 危险时一眼就知道。
- **多色心叠加显示**：红心、魂心、黑心、蓝心 4 种心数实时刷新堆在一起，再也不用打 `ViewStats` 看一长串文字。
- **法力 / 金币 / 命数 / 动量**全部独立成条放在右下，看着像正经游戏 UI 而不是命令方块拼出来的。
- **职业自适应**：玩 Joey 吸血鬼自动把法力换成深红鲜血条；玩 Swan 果冻自动换成粉色冲刺动量图标 —— 你切职业它就跟着变。
- **抗"癫狂"刷屏**：塔里那种把 Boss 栏文字替换成乱七八糟装饰的瞬间，下面这些条不会跟着 0 / 正常来回闪。

#### 队友 · 一目了然
- **左侧队友面板**：每个队友头像 + 名字 + 血条 + 命数；按命数排序，**自己永远第一行 + 金色名字**。可拖拽，能横排也能竖排。
- **队友头顶 3D 血条**：透墙可见，再也不用喊"我血多少多少快来奶我"。

#### 怪物 · 谁是谁、剩多少血
- **所有 Boss 栏怪自动加头顶 3D 血条**，连"狂暴""精英"这种后缀都保留。
- **离你最近的那只**会自动加个黄色 ▶ 标记 —— 群怪混战不会再分不清你正在打哪只。
- 怪物名字保留地图原本的颜色，远看一眼就能区分阵营。

#### 属性面板 · 直接看清楚
- 那一长条 50 多行的 `/trigger ViewStats` 文字，自动收成一个干净的双列面板，鼠标悬停照样有详细 tooltip。
- 三档显示模式：常显 / 只在按 E 时显 / 不显示。

#### Boss 栏 · 想藏就藏
- 自己 / 队伍 / 怪物三种 Boss 栏分开开关，**不和别的 Boss 栏 mod 打架**。

#### 自动化 · 替你按命令
- 每 30 秒自动按一次 `ViewStats`，受伤或大额回血还会立刻刷新；这些刷新带来的"Your Stats:"提示会被自动吃掉，不刷你聊天栏。
- 队伍 Boss 栏掉了会自动帮你 `TogglePartyBossbar`；带熔断保护，**不会因为狂按命令被服务器踢**。

---

### 战绩统计（v6 起 · 集成单机 / 服务器通用）

> 不管你是单机存档还是和朋友联机的小服务器，只要**服务端装了这个 mod**就直接工作 —— 普通服务器也通过实测。

#### 谁打了多少伤害，模组帮你算清楚
塔里 9 种伤害类型（近战 / 子弹 / 火 / 冰 / 雷 / 末影 / 神圣 / 暗影 / 邪秽）全部分开统计，**而且能正确分给真正开火的那个玩家**。哪怕你和队友站一起、用召唤物在打，模组也会用一套优先级（拿什么武器、谁刚开了火、召唤物是谁的、有没有续伤害）来判断 —— 不会出现"伤害全算到一个人头上"的情况。

#### 击杀 / 助攻 / 承伤
- **击杀**：致死那一下是谁打的就算谁，被免疫 / 救回 / 管理员一键清场都不会乱算。
- **助攻**：30 秒内打过它的其他人都给计上。
- **承伤**：你被怎么打掉的血也精确分玩家记录，可选在聊天栏播报"队友 A 这关吃了多少伤"。

#### 嵌入到队友面板的实时战绩
队友面板每个人下面会多一行小字：

```
══════ T1F4 · 摩天大楼 · 00:46 ══════
 SimonBasil ⚔ 344 ⛨ 155 ☠ 2 🤝 0
 Kirin0321  ⚔ 180 ⛨  27 ☠ 1 🤝 0 · 89/s
 [全队]    ⚔ 524 ⛨ 182 ☠ 3 🤝 0
══════════ 全局 05:46 ══════════
 ...
 [全队]    ⚔ 524 ⛨ 182 ☠ 3 🤝 0
```

⚔ 造成的伤害 · ⛨ 承受的伤害 · ☠ 击杀 · 🤝 助攻 · `89/s` 是**最近 5 秒 DPS**。  
四种显示模式可选：不显示 / 只显示当前这关 / 只显示整局累计 / 两者都显示；没数据时自动隐藏不挡视野。

#### 自动存档 · 历史回看
- 整局结束自动归档，最多保留 20 局历史。
- 中途服务器崩溃也不会丢 —— 每分钟自动写一次盘 + 关卡切换时立刻写。

#### 详细伤害分配面板（实验性 · 默认未绑定）
按 L（需先去按键设置里绑定）弹出一个可拖拽窗口（不会暂停游戏），看到每个玩家的硬伤、召唤物分摊、最高单击、击杀 / 助攻 / 承伤明细。给好奇"今天我到底打了什么"的玩家用。

#### 整局 K/D/A 表格
按 N 随时调出整局战绩表格视图。

---

### 配置 · 双级菜单

- ModMenu → 找到本模组 → 齿轮进入。
- **主屏**：HUD 位置、布局方向、刷新频率、血条宽度、各种显示开关。
- **服务器配置 ▶ 子屏**：聊天广播开关与阈值、数据过滤选项。
- 所有 HUD 面板都能**直接拖动到你想放的位置**。
- 完整 **简体中文 + 英文** 翻译。

### 按键

| 键 | 功能 |
|---|---|
| `H` | 一键开关整个 HUD（不删配置，纯隐藏） |
| `J` | 切换属性面板可见性 |
| `K` | 一键开关全部 Boss 栏 |
| `L` | 详细伤害分配面板（默认未绑定，要用请自行绑定） |
| `N` | 整局 K/D/A 表格 |

### 兼容性 & 安全性

- **Minecraft 1.21.4 · Fabric · Java 21**
- 离开蛋糕塔地图时**自动隐身**：不会在原版生存或其它服务器多出任何东西。
- 不改地图、不改存档、不需要 OP；所有命令都走地图自带的 `/trigger`。
- 不依赖 ModMenu，但建议装一个方便配置。

### 致谢

- **作者**：麒麟（Kirin0321）
- **v6.0+ 服务端战绩功能赞助**：OnlyScoutCat 
- **协议**：MIT

---

## 二、精简版（中文 · 整合包 / mod 描述栏）

> **Cake Tower HUD Plus** —— 蛋糕塔 Chapter 3 (Cake Team Towers) 专用增强模组。
>
> **画面增强**：底部彩色血条 + 红 / 魂 / 黑 / 蓝四色心实时叠加显示；法力 / 金币 / 命数 / 动量独立成条；Joey 吸血鬼鲜血条、Swan 果冻冲刺动量自动切换；属性面板自动收成可拖拽的双列窗；左侧队友面板（头像 + 血条 + 命数，自己置顶 + 金色名字）；队友与所有 Boss 栏怪都加头顶 3D 血条（透墙可见，最近的那只 ▶ 黄色高亮）；自身 / 队伍 / 怪物 Boss 栏分开开关。
>
> **战绩统计**（v6 起 · 集成单机和普通服务器都通过实测）：9 种伤害类型按玩家精确归属（含召唤物 / 持续伤害的复杂场景）；击杀 / 助攻 / 承伤分别记录；队友面板每行下方追加"⚔ 伤害 ⛨ 承伤 ☠ 击杀 🤝 助攻 · 5 秒 DPS"实时小字，按关 / 按局两档；整局自动归档，保留最多 20 局历史回看。
>
> **快捷键**：`H` 总开关 / `J` 属性面板 / `K` Boss 栏一键开关 / `L` 伤害分配面板（默认未绑定）/ `N` 战绩表格。  
> **配置**：ModMenu 双级菜单，所有面板可拖动。  
> **离开本地图自动隐身，不改存档不要 OP。**
>
> MC 1.21.4 · Fabric · Java 21 · 服务端战绩功能由 **OnlyScoutCat** 赞助（服务器 `sc4.i9idc.com:12439`） · 作者 麒麟（Kirin0321）· MIT。

---

## 三、技术版（中文 · 开发者）

> 供维护者、PR 审阅、与 CTT 数据包/记分板**接口对齐**时查阅；不面向终端玩家。术语与 `FEATURES.md` / `MAP_DATAPACK_ANALYSIS.md` 一致时优先以代码为准。

### 1. 运行栈与工程划分

| 项 | 值 |
|----|----|
| Minecraft | 1.21.4（Yarn 映射） |
| Loader | Fabric ≥ 0.16.9 |
| API | `fabric-api`（随 `gradle.properties` 锁定） |
| Java | 21 |
| 双入口 | 客户端 `com.ctt.healthdisplay.CttHealthDisplay` · 服务端 `com.ctt.healthdisplay.server.CttStatsServer`（`fabric.mod.json` 中 `entrypoints` 分离） |

- **v5 系**：以 HUD / 输入 / 渲染为主，不依赖本世界存档。  
- **v6 系**：服务端探针 + 全图统计 + 网络快照 + 可选 NBT 持久化；**客户端仅展示**（或缓存），权威数据在服。

### 2. 客户端（HUD 与表现）

- **状态来源**：主读 Boss 栏标题文本 + `ViewStats`（`/trigger ViewStats`）经 tellraw 解析的 `List<Text>`，保留 `Style` / `HoverEvent` 供 Stats 面板复刻。
- **Boss 栏**：对 `BossBarHud` 做**非破坏**拦截（`HEAD` / `RETURN` 临时隐藏），三档分自身 / 队伍 / 怪；与多数 Boss 栏类 mod 可共存（仍建议实测）。
- **自动命令**：`ClientTick` / 伤害事件驱动自动 `/trigger ViewStats`；队伍条丢失时 `TogglePartyBossbar` 带熔断。  
- **3D 条 / 名字**：世界渲染 + Entity 上 billboard，与 Boss 栏 HP 子串 `(HP n/m)` 绑定。  
- **键位**：`CttHealthDisplay` 中注册；`L` 伤害面板默认 `InputUtil.UNKNOWN_KEY`（未绑定）。  
- **配置（纯客户端）**：`config/ctt-health-display.json` → `ModConfig`；ModMenu 主屏编辑。

**客户端战绩缓存**：`ClientStatsCache` 等消费 `StatsSnapshotPayload` 的**镜像**；不装服或旧服未推包时，部分字段回落为 0 或走本地既有 static 路径（以代码分支为准）。

### 3. 服务端（探针、阶段、统计）

#### 3.1 `AttackerProbe`：L1–L8 硬归属 + **L9 三子层**

- **跟踪的计分板目标名**（`TRACKED_OBJECTIVES`，共 9 类元素伤害）：`MeleeDMG`、`BulletDMG`、`ForceDMG`、`FireDMG`、`WaterDMG`、`IceDMG`、`DarkDMG`、`LightDMG`、`ElectricDMG`。另有 `AllDMG` / `Pierce_Damage` 等作 vanilla 物理伤害与兜底通道（详见 `AttackerProbe` 与 `DamageProbe`）。
- **枚举** `AttackerProbe.Layer`：每层有 `shortTag()`（如 `L9-FILT`），并维护 `layerCounts[]` 供 L 键诊断面板与 NBT 持久化。

**L1–L8（按优先级从高到低尝试；多数层带武器类型守卫）**

| 层 | 枚举常量 | 含义（摘要） |
|----|----------|----------------|
| L1 | `L1_WEAPON_MATCH` | 主手武器类型匹配 + 近 10 s 内开过火，硬证据最强 |
| L2 | `L2_STAT_TICK` | 本 tick vanilla `damage_dealt` stat |
| L3 | `L3_MARKER_NEAR` | 约 3 m 内带 `PlayerID` 的 marker / 弹射物 |
| L4 | `L4_MARKER_FAR` | 约 40 m 内带 `PlayerID` 的 marker / 弹射物 |
| L5 | `L5_STAT_WINDOW` | 近若干 tick 的 `damage_dealt` 窗口延续 |
| L6 | `L6_FIRE_WINDOW` | 近 20 tick 右键开火 + 距离 + Tier 打分（远程法器歧义消解） |
| L7 | `L7_BOW_RELEASE` | 弓 / 弩 / 三叉戟 `used:bow` 刚释放窗口 + victim 距离 |
| L8 | `L8_LAST_HITTER` | victim×伤害类型 **续归属**（`VictimLastHitter`，约 20 s TTL），用于召唤物 / DoT 延续 |

- **`grandTotal`（玩家占比分母）**：设计上仅 **sum(L1…L8)**；**L9 整体不计入**（玩家百分比 = 自身已分类伤害 / `grandTotal`，与未分类桶解耦）。
- **`isHardLayer`**：仅部分层会写入 `VictimLastHitter` / 近期归属日志作「续击种子」；L9 任意子层**不可**作种子（避免错链）。
- **L8 与伤害账户（v6.5.9）**：因剧情 set 等假伤害经 L8 误进账户的问题，**计入统计的伤害**可改走独立 **L9-CARRY** 桶（与 `PlayerDamageStats.addCarry` 等配合）；击杀维度上 Tombstone 等仍可能用 L8 作 killer 兜底（详见类内 JavaDoc）。实现细节以 `AttackerProbe` / `PlayerDamageStats` 当前代码为准。

**L9 — 未分类大类（三子层，独立计数、UI 上分色）**

| 子层 | 枚举常量 | 标签 | 典型触发 |
|------|----------|------|----------|
| L9-NONE | `L9_NONE` | `L9-NONE` | L1–L8 全部未命中后的**真未归属** |
| L9-FILTER | `L9_FILTER` | `L9-FILT` | **黑名单数值**：怪物初始化 / 形态切换等假跳血，由 `ScoreboardUpdateMixin` 侧 RedHearts delta 命中 `ServerConfig.initHpJumpValues`（及可疑 victim 等过滤逻辑）时 **`forceLayer` 强制路由** |
| L9-HEAL | `L9_HEAL` | `L9-HEAL` | `DamageProbe` 识别**绿色回血**类事件（如 text_display 背景色 `background:-16515325` 等）时强制路由 |

- L9 子层伤害 **不进各玩家账户**、**不进 `grandTotal`**，计入 **未分类诊断**（`layerCounts` 索引按 `Layer` 枚举全长，L 键面板细分三子层；`getL9Count()` = `L9_NONE` + `L9_FILTER` + `L9_HEAL` 之和，另有 `getL9NoneCount` / `getL9FilterCount` / `getL9HealCount`）。  
- **强制路由**：`recordFromDamageShower(..., forceLayer)` 中若 `forceLayer != null` 且 `forceLayer.isUnclassified()`，则**跳过**完整 `attribute()` 链，直接记该 L9 子层（`ScoreboardUpdateMixin` → `L9_FILTER`，`DamageProbe` → `L9_HEAL`）。`L9_NONE` 通常由归属链走完仍无攻击者时落入。  
- **聊天行颜色**（短标签）：`L9_NONE` 偏红、`L9_FILTER` 偏紫、`L9_HEAL` 偏绿（见 `AttackerProbe` 内格式化逻辑）。

---

- **PlayerKillStats**：`END_SERVER_TICK` 扫描 `VictimLethalCandidate` 池；真死才计击杀；助攻 = 时间窗内非致死伤害贡献者。  
- **PlayerTakenStats**：`tag=CTT` 玩家里读地图维护的 `DamageTook` 寄存器，与「造成伤害」同口径的**承伤**侧。  
- **阶段**：`StageKey` = record `(gameId, tier, floor, stageType, stageNum)` 五段字符串，由 `StageProbeServer` 从计分板（如 `#CTT GameID`、`#CTT` 系 `T`/`F` 等）拼装；`StageBoundaryDispatcher` 管进出关、`onSessionChange` 在 **GameID 跳变**时触发会话归档。  
- **关内桶**：同 `StageKey` 多段进出**累加**；换 GameID 时整 session 进 `history`（见持久化）。  
- **聊天广播**（默认关）：`AttackerProbe` / `VictimTombstone` / `PlayerTakenProbe` 等读 `ServerConfig` 的 `broadcast*InChat`。

### 4. 网络层（M6 快照）

- **Payload ID**：`ctt-health-display:stats_snapshot`。  
- **编解码**：`StatsSnapshotPayload` + `RegistryByteBuf` + `PacketCodec.of(encode, decode)`（`writeString` / `readUuid` / `writeVarInt` 等，与 `StagePayload` 同风格）。  
- **节奏**：`SERVER_STARTED` 后于 `END_SERVER_TICK` 约 **1 Hz** 向全体在线玩家广播同一份全量快照；**玩家加入**时立刻推一次（baseline，HUD 不空窗）。  
- **字段设计**：`StageKey` 在包内**索引化**到顶层 `stages[]`，减重复串；`activeDurationMs` 由**服务端**给出，避免客户端时钟漂移。  
- **显式不载**：L 键开发向字段（`unattributed*`、`globalLayerCounts` 等）不进入 payload，dedicated 上可为 0；集成单机有服时可走原 static。  
- **v2+**：`PlayerEntry` 带 `recent5sSum`（varLong）→ HUD **5 秒 DPS**。

### 5. 持久化（M2 · NBT）

- **文件**：`<世界根>/data/ctt_stats.dat`（`NbtIo.writeCompressed` gzip）。  
- **根结构**：`version` / `savedAtMs` / `currentGameId` + `session`（`damage` / `kill` / `taken` / `stage` 各 `toNbt`）+ `history[]`（每条约 `{savedAtMs, gameId, session}`)。  
- **写盘时机**：开服后 `load`；周期 **60 s** 节流 + **出关**立即 + **GameID 变更**归档后立刻 + **关服**收尾。  
- **history 上限**：**20** 条，FIFO 丢最旧。  
- **layerCounts[]**：九层诊断计数一并写入，L 键面板重启不丢（若开持久化且地图一致）。

### 6. 配置拆分（v6.6.4+）

| 文件 | 用途 |
|------|------|
| `config/ctt-health-display.json` | `ModConfig`：HUD 布局、条宽、自动刷新、面板显隐、按键引用等**纯客户端**项 |
| `config/ctt-health-display-server.json` | `ServerConfig`：聊天广播、RedHearts 数据源、初始 HP 跳变过滤、可疑名单与阈值等；**服端读取**，集成单机同路径即生效，普通服需将文件放到**服务端** `config/` 或由管理员改模板 |

- 从旧单文件迁移：`ServerConfig.tryMigrateFromLegacy()` 会尝试从旧 `ctt-health-display.json` 中抽出服端字段。  
- 游戏内 **ModMenu → 主屏 + 服务器配置 ▶ 子屏** 与上述 JSON 对应（子屏只写 `ServerConfig`）。

### 7. 延伸阅读

- `FEATURES.md`：全量按版本迭代的功能/实现清单。  
- `V6_STATS_DESIGN.md` / `V6_STATS_STATUS.md` / `V6_STATS_DEV_PLAN.md`：统计系统设计、进度、里程碑。  
- `MAP_DATAPACK_ANALYSIS.md`：地图计分板、触发器、与 mod 的对接点。  

---

## 四、英文版（用于英文社区 / CurseForge / Modrinth）

> # Cake Tower HUD Plus
>
> **A HUD overhaul + combat analytics mod, purpose-built for the *Cake Team Towers · Chapter 3* map.**
> Stop squinting at 4 boss bars and tellraw spam — your HP, teammates, mobs, and damage charts are right there in front of you.

### What you'll see in-game

#### A real-looking status rail
- **Custom health bar** with red gradient that flashes red below 25%.
- **All four heart types** (Red / Soul / Black / Blue) stacked on one bar, live-updated — no more reading walls of `ViewStats` text.
- **Mana / Coins / Lives / Velocity** each on their own bar in the bottom-right.
- **Class auto-adapts**: Joey vampire's blood gauge and Swan jelly's air-dash icon swap in automatically when you switch class.
- **Madness-proof**: bars don't flicker between 0 and your real value when boss bar text gets replaced with decorations.

#### Teammates at a glance
- **Left-side roster panel**: head + name + bar + lives, sorted by lives, **you're always the top row in gold**. Draggable, horizontal or vertical.
- **3D overhead bars** for teammates, see-through walls.

#### Mobs — who's who, how much HP
- Every boss-bar mob gets a **3D overhead bar** (with "Enraged" / "Elite" suffixes preserved).
- The **closest matching mob** is highlighted with a yellow ▶ — no more confusion in mob piles.
- Mob names keep the map's original color so you can tell factions apart from a distance.

#### Stats panel done right
- That 50-line `/trigger ViewStats` wall becomes a clean two-column panel with hover tooltips intact.
- Three modes: always-on / only when inventory is open / hidden.

#### Boss bars: hide what you don't want
- Independent toggles for self / party / mob bars. **Plays nice with other boss-bar mods.**

#### Automation that does the typing for you
- Auto-`ViewStats` every 30 s + on damage / big heals; the resulting "Your Stats:" spam is silently swallowed.
- Auto-`TogglePartyBossbar` when the party bar drops, with a circuit breaker so **you won't get rate-limit-kicked**.

---

### Combat analytics (v6+, integrated client AND dedicated servers)

> Tested on standard public/private servers — install on the server side and it just works.

#### Damage attributed to the right player
All 9 damage types (Melee / Bullet / Fire / Ice / Lightning / Ender / Radiant / Shadow / Vile) are tracked per player. Even when you and a teammate stand on the same spot or fight with summons, the mod uses weapon-in-hand, recent fire timing, summon ownership and DoT carry-over to figure out **who actually dealt that damage**.

#### Kills / Assists / Damage taken
- **Kills**: whoever landed the lethal hit gets it. Immune kills, IronHeart revives, and `/kill` mass-clears don't count.
- **Assists**: anyone who damaged the victim within the last 30 s.
- **Damage taken** is recorded per player too, with optional chat broadcast above a threshold.

#### Live combat lines on the teammate panel

```
══════ T1F4 · Skyscraper · 00:46 ══════
 SimonBasil ⚔ 344 ⛨ 155 ☠ 2 🤝 0
 Kirin0321  ⚔ 180 ⛨  27 ☠ 1 🤝 0 · 89/s
 [Team]     ⚔ 524 ⛨ 182 ☠ 3 🤝 0
══════════ Session 05:46 ══════════
 ...
 [Team]     ⚔ 524 ⛨ 182 ☠ 3 🤝 0
```

⚔ damage dealt · ⛨ damage taken · ☠ kills · 🤝 assists · `89/s` is **rolling 5-second DPS**.  
Four display modes (off / current stage only / session only / both); auto-hides when there's nothing to show.

#### Auto-archive — review past sessions
- Last **20 sessions** are kept on disk.
- Saved every 60 s + on every stage transition + on server stop, so a crash won't lose progress.

#### Detailed damage panel (experimental, unbound by default)
Press **L** (after binding it yourself) to pop a draggable window — without pausing the game — showing each player's hard-attributed damage, summon-share, biggest hit, kills / assists / damage taken.

#### Session K/D/A table
Press **N** any time for a full-session table.

---

### Configuration · two-tier menu

ModMenu → main screen for HUD prefs (positions, layouts, refresh rate, bar widths, visibility toggles); **Server Config ▶** sub-screen for chat-broadcast toggles, threshold, and data filters. Every HUD panel is draggable. Full English + Simplified Chinese localization.

### Keys

| Key | Action |
|---|---|
| `H` | Toggle the entire HUD |
| `J` | Cycle stats panel visibility |
| `K` | Toggle all boss bars at once |
| `L` | Detailed damage panel (unbound by default) |
| `N` | Session K/D/A table |

### Compatibility & safety

- **Minecraft 1.21.4 · Fabric · Java 21**
- Auto-hides on non-CTT worlds — won't show up in vanilla survival or other servers.
- No datapack edits, no save-data writes, no OP needed. Only uses the map's own `/trigger` commands.

### Credits

Author **Kirin0321** · v6.0+ server-side analytics sponsored by **OnlyScoutCat** (`sc4.i9idc.com:12439`) · MIT licensed.

---

## 五、一行短文（用于推广 / 朋友圈）

> 蛋糕塔 Chapter 3 专用增强：彩色血条 + 队友 / 怪物 3D 血条 + 9 种伤害精确分玩家（含击杀 / 助攻 / 承伤 + 5 秒 DPS + 整局历史回看），双级配置菜单，离场自动隐身，集成单机与普通服务器通用，MC 1.21.4 Fabric · 由 OnlyScoutCat 赞助。
