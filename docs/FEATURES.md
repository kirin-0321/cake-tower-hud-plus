# Cake Tower HUD Plus · 功能详解

> **Mod ID**: `ctt-health-display` &nbsp;·&nbsp; **显示名**: Cake Tower HUD Plus
> **当前版本**: `6.5.2`（v6.5.2 归属层重构：删 L8/L8b 召唤物层、L7b 升 L7、L7 降 L8 改硬归属、L9 拆 NONE/FILTER/HEAL 三子层；黑名单大额数值 1000/10000/100000 路由到 L9-FILT；回血粒子改路由到 L9-HEAL；`grandTotal` 仅 L1~L8，玩家占比分母对齐"已分类总伤害"）
> **产出 JAR**: `cake-tower-hud-plus-6.5.2.jar`
> **作者**: 麒麟 (Kirin0321) &nbsp;·&nbsp; **许可**: MIT
> **目标环境**: Minecraft 1.21.4 · Fabric Loader ≥ 0.16.9 · Fabric API 0.113.0+1.21.4 · Java 21
> **运行侧**: `*`（客户端 + 服务端同 jar；本地存档 integrated server 即可验证）
> **入口**:
> - `client` → `com.ctt.healthdisplay.CttHealthDisplay`（v5 全部 HUD 功能）
> - `main` → `com.ctt.healthdisplay.server.CttStatsServer`（v6.0.0 骨架，采集链路开发中）
>
> 本模组是为 **Cake Team Towers** 地图（Chapter 3 Update #4.0.12 Premium）量身定制的客户端增强 HUD，
> 依赖该地图数据包的计分板、Boss 栏、`/trigger ViewStats` 等约定格式工作。
> 在原版生存、其它服务器上不会崩溃但也不会有显示（因为检测不到对应 Boss 栏/计分板，会自动隐身）。

---

## 目录

1. [总览](#总览)
2. [核心功能](#核心功能)
   - [2.1 主血条 HUD（个人）](#21-主血条-hud个人)
   - [2.2 法力 / 金币 / 生命数栏](#22-法力--金币--生命数栏)
   - [2.3 多色心叠层（红 / 魂 / 黑 / 蓝）](#23-多色心叠层红--魂--黑--蓝)
   - [2.4 自动 ViewStats 刷新](#24-自动-viewstats-刷新)
   - [2.5 属性（Stats）面板](#25-属性stats面板)
   - [2.6 队友血量面板（2D HUD）](#26-队友血量面板2d-hud)
   - [2.7 队友头顶 3D 血条](#27-队友头顶-3d-血条)
   - [2.8 怪物头顶 3D 血条 + 追踪](#28-怪物头顶-3d-血条--追踪)
   - [2.9 原版/地图 Boss 栏隐藏](#29-原版地图-boss-栏隐藏)
   - [2.10 原版头顶"数字❤"隐藏](#210-原版头顶数字-隐藏)
   - [2.11 自动切换队伍 Boss 栏](#211-自动切换队伍-boss-栏)
3. [按键绑定](#按键绑定)
4. [配置界面与选项](#配置界面与选项)
5. [与地图数据包的对接协议](#与地图数据包的对接协议)
6. [Mixin 清单](#mixin-清单)
7. [兼容性与限制](#兼容性与限制)
8. [构建与部署](#构建与部署)

---

## 总览

Cake Tower HUD Plus 做了以下几件事：

- **读取**地图通过 **Boss 栏标题**与**计分板**广播的真实 HP / 最大 HP / 法力 / 金币 / 生命数 / 队友 HP / 怪物 HP。
- **重绘**一套美观的彩色血条、法力条、金币与生命数图标、队友头像血条面板。
- **立体渲染**：在世界空间中，队友头顶显示本方血条，被瞄准过的怪物头顶显示 Boss 栏同款血条（含后缀文字）。
- **捕获** `/trigger ViewStats` 聊天返回的多行内容，折成 1 列或 2 列的属性面板常驻屏幕。
- **自动刷新**：每 N 秒、或掉血瞬间，静默触发一次 `/trigger ViewStats`（聊天栏不露出「Triggered ViewStats」垃圾信息）。
- **自动隐藏**你不想看到的 Boss 栏（个人条 / 队伍条 / 怪物条，三档独立开关）。
- **全部配置持久化**到 `.minecraft/config/ctt-health-display.json`，支持拖拽、ModMenu 打开配置界面。

---

## 核心功能

### 2.1 主血条 HUD（个人）

<sub>`hud/HealthBarRenderer.java` · `health/HealthData.java`</sub>

- 位置：屏幕底部经验条正上方（与原版血条同位置，宽 81 × 高 5 像素）。
- 颜色随百分比从 **深红 → 橙红 → 亮红** 渐变，使用 `HP_FULL/HIGH/MID/LOW` 四段线性插值。
- 血条顶部叠加一层 `0x30000000` 透明高光，使条看起来更有立体感。
- 血条上方文字：
  - `❤ 当前HP/最大HP`（例如 `❤ 37/100`、`❤ 5/0`、`❤ -3/-10`——**恒显示原始值**，不因 `maxHP ≤ 0` 而省略）。
  - HP ≤ 50% 变黄；HP ≤ 25% 变红并 **每 400ms 闪烁一次**（`lastFlashTime / flashOn`）。
- 血条中央叠加白色带黑描边的 `37/100` 文本（见 `drawOutlinedText`）。
- **100 基线规则（v5.1 起）**：令 `effectiveMax = max(100, maxHP)`，血条填充比例 = `allHearts / effectiveMax`，再 clamp 到 `[0, 100%]`。
  - `maxHP ≥ 100`：按实际 `maxHP` 做分母，行为与旧版一致。
  - `0 < maxHP < 100`（如 30）：不会因为分母小就"看上去满血"，仍以相对 100 HP 的比例呈现。
  - `maxHP == 0 / 负数`（Cake Towers 的 `NegMaxHealth` 场景）：用 100 兜底，不会除零也不会闪回满条；当前 HP 为多少就显示多少像素（例：`HP = 5, max = -10` → 血条显示 5%）。
  - 多色心叠层模式下，红 / 魂 / 黑 / 蓝 4 层的分母**全部**换成 `effectiveMax`，保证 4 层宽度互相可比。
- 数据来源优先级：
  1. 个人 Boss 栏正则 `(HP n/m)` / `(生命值 n/m)` / `(Health n/m)` → `allHearts / maxHP`
  2. 计分板 `HealthPercent`（回退 0-100 百分比）
- 若两者都没有，此条整体不绘制（`isAvailable()` 返回 false）。

### 2.2 法力 / 金币 / 生命数栏

<sub>`HealthBarRenderer.drawManaText`</sub>

位置在屏幕右下（经验条右侧），与主血条镜像对称；仅在 Boss 栏包含 `(Mana n/m)` / `(法力值 ...)` / `(Stamina ...)` / `(体力 ...)` 时出现：

- **左**：`❤ X`（绿色）—— 当前剩余生命数（Lives），取自计分板或 Boss 栏 `(Lives n)`，支持负数。
- **中**：金色 `tower_token` 图标 + 数字（Coins）—— 图标使用 `assets/ctt-health-display/textures/custom/tower_token.png`，通过 `RenderSystem.setShaderColor` 调成金色。**允许 0 与负数**（例 `0`、`-42`）。
- **右**：`X/Y✦`（天青色）—— 当前法力 / 最大法力。**当前法力可为负数或超过最大值**（负数 → 法力条空；超上限 → 法力条满；文字区原样输出负号/大数）。最大法力按协议恒 ≥ 0。
- 蓝色法力条位于文字下方，颜色分 4 段从深蓝 `MANA_LOW` 渐变到 `MANA_FULL`。

### 2.3 多色心叠层（红 / 魂 / 黑 / 蓝）

<sub>`HealthBarRenderer.drawLayeredBar` · `StatsData.tryParseHeartLine`</sub>

当 `/trigger ViewStats` 返回里解析到 `数字❤` 带颜色的行时：

| 颜色 | 含义 | 主色（条） | 主色（文字） |
|---|---|---|---|
| `RED` | 红心 Red Hearts | `0xC0E84040` | `0xFFFF4444` |
| `YELLOW` | 魂心 Soul Hearts | `0xB0DDCC22` | `0xFFFFDD44` |
| `BLACK` | 黑心 Black Hearts | `0xB0550088` | `0xFF7722AA` |
| `BLUE` | 蓝心 Blue Hearts | `0xB04488EE` | `0xFF5599FF` |

主血条会按「红在最底、再叠魂、再叠黑、再叠蓝」顺序画 4 层半透明矩形（同色混合），能直观看到每种心各占多少宽度。同时血条上方文字区也从原来的单组 `❤ 37/100` 变成**多色并排**：
`37❤  10❤  5❤  3❤`（仅数量 > 0 的才会显示）。

4 层宽度统一按 2.1 节的 `effectiveMax = max(100, maxHP)` 做分母，保证红心 max 低于 100（或为 0 / 负数）时，魂 / 黑 / 蓝心不会因为分母被锁 `1` 而异常膨胀。

### 2.4 自动 ViewStats 刷新

<sub>`CttHealthDisplay.onInitializeClient` 中的 tick 事件</sub>

- 每 `config.autoRefreshIntervalSeconds` 秒发送一次 `/trigger ViewStats`（默认 15 秒）。
- **掉血即刷新**：`allHearts` 比上 tick 减少时立即触发（受 20 tick = 1 秒冷却保护，避免刷屏）。
- 自动触发会先调用 `statsData.markAutoTriggered()`，让回调里的 `Triggered ViewStats` / `Your Stats:` / `Hover over...` 等系统提示**不显示到聊天框**（返回 `true` 阻止原版 allow-message）。
- 游戏未开始时地图会发 `无法触发 / cannot trigger / Can't trigger`，Mod 会将 `gameNotStarted` 置 true，属性面板改显"游戏未开始"。

### 2.5 属性（Stats）面板

<sub>`hud/StatsRenderer.java` · `health/StatsData.java`</sub>

- 地图 `/trigger ViewStats` 会 `tellraw` 输出 50+ 行 `[score+icon+hoverEvent]`，Mod 全部原样缓存成 `List<Text>`（保留颜色和 hoverEvent）。
- 捕获起点：消息含 `Your Stats:` 或 `统计信息`。
- 捕获终点：消息含 `Game time` 或 `游戏时间`。
- 渲染：
  - **1 列**：逐行从上到下。
  - **2 列**：自动按行数一半分两列，列宽随最长行自动计算。
- **三档可见性**（键位 `J` 循环）：
  - `0 常显` —— 始终显示，除非按 `F1` 隐藏 HUD。
  - `1 背包显示` —— 仅打开背包（`InventoryScreen`）时显示。
  - `2 隐藏` —— 不显示。
- 尚未捕获过数据时占位显示：
  - 游戏未开始 → "游戏未开始"（灰色）
  - 其它 → "输入 /trigger ViewStats 查看属性"

### 2.6 队友血量面板（2D HUD）

<sub>`HealthBarRenderer.renderTeammates`</sub>

- 数据来自队伍 Boss 栏的 `Name (hp/max)` 多段匹配：`TEAM_PLAYER_PATTERN = ([A-Za-z0-9_]+)\s*\((\d+)/(\d+)\)`
- 过滤掉自己；按 `Lives` 计分板倒序排。
- 每个队友一条 **两行记录**：
  - 第 1 行：**玩家头像（8×8，取皮肤 head 层 + hat 层）** + 玩家名 + 右侧绿色 `❤生命数`
  - 第 2 行：彩色血条（80×7），条内居中显示 `hp/max`
- 支持两种布局（`horizontalLayout` 开关）：
  - 纵向：每条相隔 20 像素竖排；
  - 横向：每条占 100 像素宽并排。
- **溢出多血**（怪物/玩家 HP > maxHP 多倍）会用 6 档彩虹色依次覆盖显示：
  `红 → 橙 → 黄 → 绿 → 蓝 → 紫`，每一档代表一个满血周期。

### 2.7 队友头顶 3D 血条

<sub>`hud/TeammateWorldRenderer.renderHealthBar` + `mixin/WorldRendererMixin`</sub>

- 挂在 `WorldRenderer.renderEntity` 方法的 `TAIL`，每个实体渲染完后追加绘制。
- 仅对 `PlayerEntity` 且名字匹配 `HealthData.teammateMap` 中的条目（完全相等或互相 `contains`）才绘制。
- 位置：玩家头顶上方 0.5 格，面向摄像机 billboard。
- 内容：
  - 彩色血条（宽度可配置 `teammateBarHalfWidth` × 2，默认 50px）
  - 条内 HP 文本 `hp/maxHP`（小号 0.75x 缩放 + SEE_THROUGH 层，透墙可见）
  - 支持同 2.6 的 **6 档彩虹溢出色**，多血周期也能一眼看出。

### 2.8 怪物头顶 3D 血条 + 追踪

<sub>`TeammateWorldRenderer.renderMobHealthBar` · `MobHealthData` · `CttHealthDisplay.updateMobTracking`</sub>

地图的怪物 HP 通常以独立 Boss 栏展示（如 `Boss名 (HP n/m) 后缀文字`）。Mod 需要把这个 Boss 栏的数据**绑定到 3D 世界里的具体实体**。做法：

1. 每 tick 解析出所有含 `(HP n/m)` 的非玩家 Boss 栏，生成 `MobBossBarEntry(name, suffix, hp, max)`。
2. 每 10 tick 扫描世界实体：
   - 是 `LivingEntity` 且不是玩家 / 盔甲架；
   - 名字与 Boss 栏条目匹配（`equalsIgnoreCase` / 互相 contains）；
   - 按 `UUID` 加入 `mobHealthMap`。
3. 对同名怪物按**距离玩家最近**选出一只标记为 `targetted = true`（服务端 `sort=nearest` 算法的客户端镜像），其它设为 `false`。选中时同步从该实体 `getDisplayName().getStyle().getColor()` 取 `nameColor` 写入 `MobHealthData`（没 color 就留白）。
4. 每 20 tick 清理已死亡 / 已移除的怪物。

渲染效果（v5.1.6 起）：

- 头顶第一行（左）：`▶ 怪物名`。`▶` (U+25B6) 仅当 `data.targetted == true` 时出现（= 当前帧距离最近的那只），**v5.1.8 起 ▶ 独立用黄色 `0xFFFFFF55`（§e）高亮绘制**；名字本体颜色直接用 `data.nameColor`，默认白，特殊怪物从 `CustomName` NBT Style 继承真实颜色，不再 fallback 黄。
- 头顶第一行（右）：Boss 栏原样的**富文本后缀**（如红色"狂暴"、金色"精英"等，通过 `extractSuffixText` 按 Style 重建 `MutableText`）。
- 头顶第二行：彩色血条 + 居中 `hp/max`。血条颜色按百分比 4 档：`≤25% 深红 / ≤50% 橙 / ≤75% 金黄 / 其它 亮绿`。
- **Targetted 可见度增强**（仅对当前选中那一只）：条身填充 `alpha 255`（非 Targetted 是 200）、所有文字带阴影、名字带 `▶` 前缀。背景黑板对所有 mob 统一用 `alpha 100`（v5.1.7 改正：v5.1.6 曾把 Targetted 的背景抬到 220 但视觉上变成纯黑，已回退）。非 Targetted 同名 mob 保持半透明外观，与 v5.1.2 一致。
- 条宽可配置 `mobBarHalfWidth` × 2（默认 104px）。

### 2.9 原版/地图 Boss 栏隐藏

<sub>`mixin/BossBarHudRenderMixin` · `HealthData.hiddenBarUUIDs`</sub>

非破坏性实现：

1. 在 `BossBarHud.render` 的 `HEAD` 把 `HealthData.getHiddenBarUUIDs()` 中的条从 `bossBars` 里**临时摘出**，放进本 mixin 的 `ctt_removedBars`。
2. 原版的渲染方法照常跑一遍（只会画剩下的）。
3. 在 `RETURN` 时把摘出的条**原样放回**，保证状态一致、其它 Mod 仍能看到全量 Boss 栏。

三类 Boss 栏独立开关（配置界面可切，或按 `K` 键一键全开/全关）：

- `hidePersonalBar` —— 含自己名字 + `(HP n/m)` 的个人条（默认 `true`）。
- `hideTeamBar` —— 含自己名字 + `(/)`，或匹配到多个 `Name (n/m)` 的队伍条（默认 `true`）。
- `hideMobBars` —— 非玩家名字 + `(HP n/m)` 的怪物条（默认 `false`，因为你通常想看 Boss 血量）。

### 2.10 原版头顶"数字❤"隐藏

<sub>`mixin/VanillaHealthHiderMixin` · `mixin/TeammateHealthMixin`</sub>

Cake Tower 地图会给每个玩家在 `below_name` 或名字标签上挂一串 `血量❤` 作为原版血量显示，与本 Mod 的 3D 血条**重叠又难看**。本 Mod 的处理：

1. `VanillaHealthHiderMixin` 挂在 `PlayerEntityRenderer.renderLabelIfPresent` 的 `HEAD`：
   若 `showTeammateHeadHP=true` 且 label 是**纯以数字开头、且首字符非字母下划线**（例如 `113❤`），直接 `ci.cancel()` 阻止渲染。
2. `TeammateHealthMixin` 额外挂在通用 `EntityRenderer.renderLabelIfPresent`：
   - 怪物：若能匹配到 `mobHealthMap` 里的条，直接取消原版 label（Mod 自己画）；
   - 玩家：若 label 形如 `数字 心符号`，且名字在队友表里，同样取消。
3. `CttHealthDisplay` tick 里还会主动把 `ScoreboardDisplaySlot.BELOW_NAME` 上的 objective 解绑，斩草除根。
4. `TeammateHealthMixin` 在**确实**找到队友数据时，还会在 nameLabelPos 处**自己画**一份带 HP 文字 + 血条的 label（与 3D 渲染互补，适用于 `WorldRendererMixin` 未触发的场景）。

### 2.11 自动切换队伍 Boss 栏

<sub>`CttHealthDisplay.maybeAutoTogglePartyBossbar`</sub>

地图中 `/trigger TogglePartyBossbar` 可以把队伍 Boss 栏从「开」切到「关」或反之。如果队伍里其他玩家全都关闭了队伍条（只剩你自己的个人条），Mod 里队友列表会空。为了保证始终能拿到队友 HP：

- 检测到 `hasPersonalBossBar == true` 且 `hasTeamBossBar == false` 时，每 80 tick（4 秒）最多触发一次 `/trigger TogglePartyBossbar`。
- 与手动按键互不干扰：用户按 `K` 键隐藏 Boss 栏后，自动开关依然正常工作，只是**显示**被 `BossBarHudRenderMixin` 遮掉了。

---

## 按键绑定

所有按键在「设置 → 控制 → 按键绑定 → Cake Tower HUD Plus 分类」中可自定义。

| 键位 | 动作 | 默认键 |
|---|---|---|
| `key.ctt-health-display.toggle_hud` | 总开关：主血条 + 法力/金币/生命 + 队友面板 + 3D 血条 | `H` |
| `key.ctt-health-display.toggle_stats` | 属性面板显示模式循环（常显 → 背包显示 → 隐藏） | `J` |
| `key.ctt-health-display.toggle_bossbars` | 一键全显示 / 全隐藏三类 Boss 栏 | `K` |

按键触发时在 Action Bar（屏幕下方）给出提示，例如 `CTT Health Display: OFF`、`CTT 属性栏: 背包显示`。

---

## 配置界面与选项

- **入口**：ModMenu → Cake Tower HUD Plus → 齿轮图标；或手动打开 `.minecraft/config/ctt-health-display.json`。
- **界面结构**：左侧为 **实时预览区**（可直接拖动队友面板 / 属性面板调整位置），右侧为 **选项按钮列表**（支持滚轮滚动）。底部有「重置位置」与「完成」。

### 可调项（存于 `ModConfig`）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `teammateX` / `teammateY` | `float` (0~1) | `0.01 / 0.33` | 队友面板锚点（相对屏幕比例）|
| `horizontalLayout` | `bool` | `false` | 队友面板：纵向 / 横向排布 |
| `teammateBarHalfWidth` | `int` | `25` | 3D 队友血条半宽像素（可选 15/20/25/30/40/50）|
| `statsX` / `statsY` | `float` (0~1) | `0.005 / 0.005` | 属性面板锚点 |
| `statsColumns` | `int` | `2` | 属性面板列数（1 或 2）|
| `statsVisibility` | `int` | `0` | 0 常显 / 1 背包显 / 2 隐藏 |
| `autoRefreshStats` | `bool` | `true` | 是否自动刷新 ViewStats |
| `autoRefreshIntervalSeconds` | `int` | `15` | 自动刷新间隔秒（可选 1/2/3/5/10/15/30）|
| `showTeammateHeadHP` | `bool` | `true` | 是否绘制队友 3D 血条 + 隐藏原版 |
| `mobHeadHPMode` | `int` | `0` | 怪物 3D 血条三档：`0` 全部显示 / `1` 仅最近（bossbar 锁定的 targetted 那只）/ `2` 关 |
| `showMobHeadHP` | `bool` | `true` | **已弃用**，仅用于迁移旧 config：若旧 JSON 里为 `false` 且 `mobHeadHPMode` 为默认 0，会自动改写为 `2`（关）。保存时同步为 `mobHeadHPMode != 2`。 |
| `mobBarHalfWidth` | `int` | `52` | 3D 怪物血条半宽像素（可选 30/40/52/60/70/80）|
| `hidePersonalBar` | `bool` | `true` | 隐藏自己个人 Boss 栏 |
| `hideTeamBar` | `bool` | `true` | 隐藏队伍 Boss 栏 |
| `hideMobBars` | `bool` | `false` | 隐藏怪物 Boss 栏 |

### 多语言

- `assets/ctt-health-display/lang/en_us.json` （英文）
- `assets/ctt-health-display/lang/zh_cn.json` （简体中文，所有按钮、提示文本）

---

## 与地图数据包的对接协议

本 Mod **完全基于文本协议**工作，不碰任何数据包文件。地图端必须保持以下格式：

### Boss 栏标题格式（大小写敏感，括号必须保留）

所有数字段均使用 `-?\d+` 以支持负值（用于 Cake Towers 的 `NegMaxHealth` / 负心 / 负法力等场景）：

| 正则 | 解释 | 示例 |
|---|---|---|
| `\((HP|生命值|Health)\s*(-?\d+)/(-?\d+)\)` | 当前 HP / 最大 HP（均允许负数） | `… (HP 37/100) …`、`… (HP 5/0) …`、`… (HP -3/-10) …` |
| `\((Lives|生命数|生命效)\s*(-?\d+)\)` | 生命数（允许负数） | `… (Lives 3) …`、`… (Lives -1) …` |
| `\((Mana|法力值|Stamina|体力)\s*(-?\d+)/(\d+)\)` | 当前法力（含负数） / 最大法力（≥ 0） | `… (Mana 12/20) …`、`… (Mana -5/20) …`、`… (Mana 999/20) …` |
| `\((Coins|硬币|金币)\s*(-?\d+)\)` | 金币（允许 0 与负数） | `… (Coins 150) …`、`… (Coins 0) …`、`… (Coins -42) …` |
| `([A-Za-z0-9_]+)\s*\((-?\d+)/(-?\d+)\)` | 队伍条玩家项（允许负数） | `Steve (80/100) Alex (-5/100)` |
| `^(.+?)\s*\((HP|生命值|Health)` | 怪物名前缀 | `红龙 (HP 400/800) 狂暴` |
| `\((HP|生命值|Health)\s*-?\d+/-?\d+\)\s*(.+)$` | 怪物后缀文字（跳过 HP 数字段提取后缀富文本） | `… (HP 400/800) 狂暴` → `狂暴` |

### 触发命令

| 命令 | 作用 | 对应数据包文件 |
|---|---|---|
| `/trigger ViewStats` | 让地图 `tellraw` 属性列表；Mod 捕获并渲染 | `cake_team_tower/function/misc/view_stats.mcfunction` |
| `/trigger TogglePartyBossbar` | 让地图切换队伍 Boss 栏显隐 | `cake_team_tower/function/misc/misc.mcfunction` L1051-1057 |

### ViewStats 消息协议

- 起始：消息文本包含 `Your Stats:` 或 `统计信息`
- 心数行：**颜色严格**匹配 `RED / YELLOW / BLACK / BLUE`，内容形如 `^数字❤$` (`HEART_LINE_PATTERN`)
- 结束：消息文本包含 `Game time` 或 `游戏时间`
- 3 秒内未收到结束标识会自动超时
- 若收到「无法触发 / cannot trigger / Can't trigger」→ 判定为游戏未开始

### 计分板 objective

- `HealthPercent`（0-100）
- `Lives`
- 其它队友 `Lives` 通过 `getKnownScoreHolders()` 按名字反查

---

## Mixin 清单

定义在 `ctt-health-display.mixins.json`（环境 `client`、`defaultRequire=1`）：

| 类 | 挂点 | 作用 |
|---|---|---|
| `BossBarHudAccessor` | `BossBarHud.bossBars` | `@Accessor` 暴露私有 Map |
| `BossBarHudRenderMixin` | `BossBarHud.render HEAD/RETURN` | 临时摘除 & 还原要隐藏的 Boss 栏 |
| `WorldRendererMixin` | `WorldRenderer.renderEntity TAIL` | 每个实体渲染后挂接 3D 血条 |
| `TeammateHealthMixin` | `EntityRenderer.renderLabelIfPresent HEAD` | 取消匹配到的原版 label & 自绘 HP |
| `VanillaHealthHiderMixin` | `PlayerEntityRenderer.renderLabelIfPresent HEAD` | 取消形如 `数字❤` 的原版血量 label |

---

## 兼容性与限制

- **地图特定**：强依赖 Cake Team Towers 的文本协议；换其它地图/服务器时相关功能自动关闭，但 HUD 不会显示错误信息。
- **Mixin 稳定性**：
  - `WorldRendererMixin` 使用 `require = 0`，未命中不崩；
  - `BossBarHudRenderMixin` 做了摘除+还原，兼容其它 Boss 栏 Mod（如 `bossbar-customizer`）。
- **Yarn 映射**：当前锁定 `1.21.4+build.8`。若升级 MC 版本，必须同步更新 mixin 目标方法签名（尤其 `renderLabelIfPresent`、`renderEntity`）。
- **不依赖 ModMenu**：`suggests` 中声明，未安装时 `ModMenuIntegration` 不会被加载。
- **不改数据包、不改存档**：所有 tick 中向服务端发送的命令都是地图自身 `/trigger` 开关，不需要 op 权限。
- **多人兼容**：只在客户端解析别人的 Boss 栏文本，不会因为网络延迟造成服务端副作用。

---

## 构建与部署

### 环境要求

- Gradle 8.12（`gradlew` 已经配好腾讯云镜像）
- JDK 21（`gradle.properties` 指向 `jdk-21.0.9`）
- 首次构建会走 BMCLAPI / 阿里云 maven 下载依赖，约 1-2 分钟

### 构建命令

```bash
./gradlew build
```

- 输出：`build/libs/cake-tower-hud-plus-<version>.jar`（以及 `-sources.jar`）
- **`deployToMods` 任务**会在 `build` 后自动把 jar 拷到 `.minecraft/versions/1.21.4-Fabric_0.19.1/mods/` 并清理旧版 jar。

### 版本号

- `gradle.properties` 中的 `mod_version` 通过 `processResources` 注入 `fabric.mod.json` 的 `${version}` 占位符。
- **自动递增（v5.1.1 起）**：`remapJar` 任务执行完毕后，`doLast` 会读 `gradle.properties`、将 `mod_version` 的补丁号 `+1` 回写，效果如下：
  - `5.1` → `5.1.1` → `5.1.2` → … → `5.1.8` → `5.1.9` → …（两段式首次升级补 `.1`，三段式直接递增末段）
  - **仅在 `remapJar` 真正产出新 jar 时触发**。若源码未变化、`remapJar` 命中 UP-TO-DATE，不会空涨版本号。
  - 每次 `./gradlew build` 结束时，控制台会打印 `[bumpVersion] A -> B (下次构建使用)`，下一轮构建即产出 `cake-tower-hud-plus-B.jar`。
  - 如需跳过某次自增，可以临时注释 `build.gradle` 里 `tasks.named('remapJar') { doLast { … } }` 代码块；手动设版本号直接改 `gradle.properties` 即可。

### 产出清单（2026-04-19）

| 文件 | 大小 | 说明 |
|---|---|---|
| `build/libs/cake-tower-hud-plus-5.1.8.jar` | ≈ 80.6 KB | 本轮产物（remap 后，可直接发布） |
| `build/libs/cake-tower-hud-plus-5.1.8-sources.jar` | ≈ 54 KB | 源码包 |
| `mods/cake-tower-hud-plus-5.1.8.jar` | ≈ 80.6 KB | `deployToMods` 自动部署副本 |
| `gradle.properties → mod_version` | `5.1.9` | 已自动递增，下一轮构建将产出 `…-5.1.9.jar` |

---

## 变更记录

### 2026-04-25 · v6.5.2 · 归属层重构（删 L8/L8b 召唤物 · L7b 升 L7 · L7 降 L8 · L9 拆三子层）+ 黑名单数值过滤

承接 v6.5.1。本次根据用户实战观察做两件事：

1. **归属层（L1~L9）重排**：彻底删除误归属率最高的"召唤物相关"两层（原 L8 `SUMMON_FALLBACK` / L8b `SUMMON_SHARED`），并把"软归属"边界整体收紧。
2. **黑名单大额数值过滤**：用户观察到部分怪物快死亡 / 形态切换时，`RedHearts` 被地图 `set @s RedHearts <N>` 直接覆写，`ScoreDeltaTracker` 把负 delta 误判为"玩家造成的瞬间巨额伤害"，固定爆出 `1000 / 10000 / 100000` 三档。本次直接把这三个固定数值列黑名单。

#### A · 归属层重排（v6.5.2 起）

| 旧（v6.5.1） | 新（v6.5.2） | 说明 |
|---|---|---|
| L1 ~ L6 | L1 ~ L6 | 不变（硬归属） |
| **L7b** `BOW_RELEASE`（软） | **L7** `BOW_RELEASE`（**硬**） | 升位：`used:bow` 是 vanilla 直接信号，置信度足够，伤害进玩家账户 |
| **L7** `LAST_HITTER`（软） | **L8** `LAST_HITTER`（**硬**） | 降位 + 改硬：暂时按用户口径全部计入玩家个人伤害 |
| L8 `SUMMON_FALLBACK`（软） | **删除** | 召唤物归属误判率高，整层弃用 |
| L8b `SUMMON_SHARED`（软） | **删除** | 同上 |
| L9 `NONE`（软） | **L9-NONE / L9-FILTER / L9-HEAL** | 拆三子层，全部为"未分类" |

**`grandTotal` 重定义**：仅 L1~L8 之和（"已分类总伤害"）。**玩家占比分母 = grandTotal**，L9 三子层不进玩家个人、不进 grandTotal、不进玩家占比 —— 这正是用户口径"玩家伤害占比指的是已分类（L1~L8）的占比"。

**聊天栏作开发通道全量显示**：v6.5.2 起聊天栏会广播**所有**事件（含 L9 子标签），便于排查"为什么这条数值进了 L9-FILT"。短标签：`[L1] ~ [L8]` / `[L9-NONE]` / `[L9-FILT]` / `[L9-HEAL]`。

#### B · 黑名单数值过滤（L9-FILTER）

新增 `ModConfig`：

```java
public boolean filterInitHpJumps = true;
public int[] initHpJumpValues = new int[]{1000, 10000, 100000};
```

**实现路径**：`DamageProbe.recordFromRedHearts(...)` 在调用 `AttackerProbe.recordFromDamageShower(...)` 前先 `isInitHpJump(damage)` 判定：
- 命中 → `forceLayer = AttackerProbe.Layer.L9_FILTER`，强制路由到 L9 未分类桶；**不**进 `sessionTotal`、**不**进玩家个人、**不**进 grandTotal
- 仍照常在聊天栏可见（标签 `[L9-FILT]`）和 HUD 面板"未分类"区域可见（带说明 `黑名单 1000/10000/100000`）

来源（地图侧扫描）：

- `1000` · 25+ 普通怪 高难度初始化 / Cauldron 过场
- `10000` · necro_king / fury_david / warden / race_horse 等 boss 初始化
- `100000` · golden_chicken（五形态怪每形态切换）

用户可在 config 文件里增删，留空数组等同于关闭过滤。

#### C · 回血粒子改路由（v6.5.1 → v6.5.2）

v6.5.1 的处理是：识别绿色 background `-16515325` → **直接 `return`** 不入 pending、不累加。

v6.5.2 改为路由 **`forceLayer = L9_HEAL`**：仍然不进 `sessionTotal` / 个人 / grandTotal（语义和 return 等价），但事件被 `AttackerProbe` 走完正常归属流程并打上 `L9-HEAL` 标签 —— 在聊天栏 / HUD 面板可见，便于诊断"地图给了多少回血、怎么分布"。

#### 改动文件

| 文件 | 改动摘要 |
|---|---|
| `config/ModConfig.java` | 新增 `filterInitHpJumps` / `initHpJumpValues` |
| `server/AttackerProbe.java` | `Layer` 枚举重排 + `isUnclassified()`；`feedStats` 路由 L9 子层；`record` 元素 carry 不写 L8/L9；`attribute` 删 L8/L8b 召唤物分支；删 `Share` / `SummonHolder` / `scanSummonHolders` 等死代码；`buildChatLine` 加 L9 子标签；`trimAttacker` 加 `[黑名单]` / `[回血]` 显示；`recordFromDamageShower` 增 `forceLayer` 重载 |
| `server/PlayerDamageStats.java` | 删 `sharedMilli` / `l8bEvents` / `addShared`；`addUnattributed → addUnclassified(layer, dmg)` 分桶 NONE / FILTER / HEAL；`Snapshot` 字段拆三计数；`grandTotal()` 仅 L1~L8 |
| `server/DamageProbe.java` | 回血粒子改路由 `forceLayer=L9_HEAL`（不再 return）；新增 `isInitHpJump(damage)` + `initHpJumpFilteredCount`；`recordFromRedHearts` 命中黑名单 → `forceLayer=L9_FILTER` |
| `server/mixin/ScoreboardUpdateMixin.java` | javadoc 同步（L7 升位描述）|
| `server/VictimTombstone.java` | `L7_LAST_HITTER` 引用 → `L8_LAST_HITTER` |
| `hud/DamagePanelRenderer.java` | `drawSummary` 用 `grandTotal()` / `totalEvents()`（不含 L9）；`drawPlayerRow` 占比 = `confirmed / grandTotal`；`drawUnattributed` 完全重写，分行展示 L9-NONE / L9-FILT / L9-HEAL，各自计数 + 颜色 + 黑名单数值提示 |

#### 验收

| 场景 | 期望 |
|---|---|
| 普通战斗（无形态切换 / 无回血怪） | grandTotal 完全等同 v6.5.1 行为；玩家占比加和 = 100% |
| 怪物形态切换（如 golden_chicken） | 聊天栏出 `[L9-FILT] ... 100000`；玩家个人 / grandTotal 不增加；HUD"未分类"区出现 L9-FILT 行 |
| 怪物回血（治疗粒子） | 聊天栏出 `[L9-HEAL] ...`；玩家个人 / grandTotal 不增加；HUD"未分类"区出现 L9-HEAL 行 |
| 弓手射完立刻换武器 | 命中 `[L7]`（原 L7b 升位）；伤害进玩家账户 |
| 兜底无归属（攻击者全失败） | 聊天栏 `[L9-NONE]`；不进玩家 / grandTotal |

**版本号**：`6.5.1 → 6.5.2`
**产出**：`cake-tower-hud-plus-6.5.2.jar`（已自动部署，旧 6.5.1 被清理）

---

### 2026-04-25 · v6.5.1 · 过滤回血绿色 DamageShower 粒子

**现象**（用户报告）：怪物回血被错误统计进 `L7_LAST_HITTER`、`L8/L9`，面板出现无关归属；session 累加值 &gt; 真实输出。

**根因**：地图 `damage.mcfunction` 把**治疗**和**伤害**都通过同一套 `DamageShower` text_display 粒子 + 同一条 `DamageShower` scoreboard 输出：
- line 57-60：`HealDMG>=1` 时 summon 粒子，硬编码 `background:-16515325`（绿色）
- line 62：`DamageShower` score = `HealDMG` → 触发本 mod Mixin → 进入 `DamageProbe.record` → 被当成伤害累加
- line 1021-1025：`Damage>=1` 时 summon 伤害粒子（玩家 victim 在 line 1027 被 data merge 成红色 `-65536`，怪物 victim 保持默认）
- line 1028：`DamageShower` score = `Damage`

此前 `DamageProbe.record` 的注释写"治疗走独立管线"，实际不成立 —— 伤害和治疗粒子写的是**同一个 scoreboard objective**，Mixin 拦不住。

**修复**：
- 在 `DamageProbe.record` 最前面加 `isHealParticle(server, uuid)` 检查：按 UUID 查 text_display 实体、读 `DisplayEntity.TextDisplayEntity.getBackground()`
- 背景色 == `-16515325` → 判定回血 → 提前 return，不入 `pending` 队列、不累加 `sessionTotal`、不触发 `AttackerProbe` 归属
- 其它（红 `-65536` / 默认透明黑）继续走原流程
- 新增诊断计数 `healFilteredCount` 供调试

**时序论证（无 race）**：
- 地图按 mcfunction 行号顺序执行：先 `summon text_display {background:-16515325}`（line 57），再 `scoreboard ... DamageShower = HealDMG`（line 62）
- Mixin 在 line 62 写入时触发；此时粒子实体已存在，`world.getEntity(uuid)` 必返回实体、background 字段是终值

**改动文件**：
| 文件 | 改动 |
|---|---|
| `DamageProbe.java` | 新增 `HEAL_PARTICLE_BACKGROUND = -16515325` 常量、`isHealParticle()` 方法、`healFilteredCount` 诊断计数；在 `record()` 最前过滤 |

**覆盖范围**：
- `sessionTotal` / `sessionEvents` / `sessionMaxHit`（DamageProbe 自带 session）
- `PlayerDamageStats`（L 面板的硬/分摊/未归属）
- `PlayerKillStats.unattributedKills`（回血不再进候选池）
- 聊天栏 `[A#N] ... Player -N` 实时广播
- **不影响**：`PlayerTakenStats`（走独立的 `DamageTook` scoreboard 管线，天然不吃回血）

**版本号**：`6.5.0 → 6.5.1`
**产出**：`cake-tower-hud-plus-6.5.1.jar`（已自动部署，旧 6.5.0 被清理）

---

### 2026-04-25 · v6.5.0 · 玩家承伤统计（`V6_STATS_DEV_PLAN` 阶段 ③）

承接 v6.4.x 的击杀/助攻统计，开始阶段 ③ — **按玩家累计"挨了多少伤害"**。

**数据源决策**：推翻原计划的"方案 B（四层血 delta 扫描）"，改用实地复核地图代码后发现的更优方案 **F**：
- 地图 `cake_team_tower:misc/damage.mcfunction` line 983：`execute as @e[scores={Damage=1..}] run scoreboard players operation @s DamageTook = @s Damage`
- `Damage` 是护甲减免后、蓝/黑/灵/红四层血吸收前的**本 tick 应扣血量**
- `damage_universal.mcfunction` line 6 每 tick 头 `reset @e DamageTook`
- 两个时刻之间的 `DamageTook` 就是"这名玩家本 tick 挨了多少"
- Fabric `END_SERVER_TICK` 时机完美落在这个窗口

**为什么放弃其他方案**：
| 候选 | 否决原因 |
|---|---|
| B · 四层血 delta | 回血/换装/复活/`/kill` 都产生假 delta，过滤成本高 |
| C · `PlayerEntity.damage` Mixin | 是 vanilla hurt，不是地图管线末端，违反 §2 铁律 |
| D · `DamageShower` 粒子 score（player fork） | line 1028 `sort=nearest` 无 distance 上限 → 邻近怪物 `Damage` 可能污染粒子；`limit=10` 有取样损失 |
| **F · `DamageTook` END_TICK 扫描** ✅ | 地图已汇总、每实体独立、无污染、无取样损失、数值和 `DamageShower` 口径严格对称 |

**实现**：
| 文件 | 改动 |
|---|---|
| `PlayerTakenStats.java` （新） | 数据聚合器，字段 `taken / events / maxHit / lastTakenTick`。和 `PlayerKillStats` 同构，无归属栈 |
| `PlayerTakenProbe.java` （新） | END_SERVER_TICK 扫描：遍历 `hasCommandTag("CTT")` 在线玩家，读 `DamageTook`，>0 则 `addTaken` + 可选广播 |
| `CttStatsServer.java` | 注册 `PlayerTakenProbe::tickEnd`（在 `VictimTombstone` 之前，`gcTick` 之前） |
| `PlayerDamageStats.java` | `start/stop/clear/setFrozen` piggy-back 到 `PlayerTakenStats.sync*`，三个 stats 共享 session |
| `ModConfig.java` | 新增 `broadcastTakenInChat = true`、`broadcastTakenThreshold = 1` |
| `DamagePanelRenderer.java` | 详情行高 34 → 44，新增第二条 detail 行 `承伤 X · 单次峰 Y`（淡红）；摘要行末尾 `承 X` |

**聊天栏**：每次承伤 ≥ `broadcastTakenThreshold` → 广播 `[承伤] PlayerName -40`（红色标签 + 深红数值）。测试期默认全开（阈值 1），若嫌刷屏可提 `broadcastTakenThreshold` 到 10~20 抑制燃烧滴血。

**面板**（L 键 / HUD）详情行示例：
```
SimonBasil                                          12,345  54.3%
硬 12,345 · 事件 48 · 最高 500 · 击杀 3 · 助攻 1
承伤 3,200 · 单次峰 180
▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░
```
摘要行：`总 34,560  事件 120  DPS 500  平均 288  最高 500  承 12,800`

**数值一致性**：`DamageShower` 粒子（造伤口径）和 `DamageTook`（承伤口径）读的是**同一 tick 的同一个 `Damage` 寄存器**，满足 `V6_STATS_DESIGN.md §2` 的"造伤 = 承伤"铁律。

**已知局限**：
- Vanilla 环境伤（摔落 / 窒息）不走 `Damage` scoreboard → 漏记。但同样不算造伤，对称。
- 免疫/IronHeart 救回：`DamageTook` 已写入但实际未扣血 → 承伤会略夸大。语义合理。
- 假人打你：误记，阶段 ④ 休息室规则解决。
- 友伤（地图允许）：记入，符合设计 §2。

**版本号**：`6.4.1 → 6.5.0`（阶段 ③ 新 feature，非 patch）
**产出**：`cake-tower-hud-plus-6.5.0.jar`（已自动部署，旧 6.4.1 被清理）

---

### 2026-04-25 · v6.4.1 · 修复：`VictimTombstone` 扫不到死亡 → candidate-driven 扫描

**现象**（用户报告 + `logs/latest.log`）：
- v6.4.0 部署后面板伤害正常、聊天栏 `[A#N] ... L1 SimonBasil` 归属也都对
- 但**从来没出过 `[CTT Kill]` 日志**，L 面板击杀/助攻恒为 0
- 游戏里僵尸、骷髅弓箭手、粗砾僵尸等 4 次 591/897/836 伤害后都 `died: xxx被杀死了`（vanilla log 有，但 mod 识别不到）

**根因**：v6.4.0 的 `VictimTombstone` 用 `world.iterateEntities() + prevSnap` 做"消失检测"。两条脆弱链：
1. Fabric 1.21.4 的 `ServerWorld.iterateEntities()` 会漏掉部分区块的实体（与 chunk section 索引状态有关）——怪物根本没进过 `prevSnap`，diff 永不触发
2. 即使扫到，也要求"先活一整 tick 才能被首次入列"，但地图的 `/kill @e[scores={RedHearts=..0}]` 在部分怪物上与伤害 tick 同帧完成，`prevSnap` 永远没它

**修复**（candidate-driven 扫描）：
- `VictimTombstone` 彻底重写，不再用 `iterateEntities` / `prevSnap`
- 每 END_SERVER_TICK 遍历 `VictimLethalCandidate` 候选池（里面全是近期吃过归属伤害的 victim UUID）
- 对每个 UUID 用 `server.getWorlds()` + `world.getEntity(uuid)` 直接查
- 查不到 或 `isRemoved()` → 判定真死亡 → `settleKill`
- `VictimLethalCandidate.TTL_TICKS` 从 5 → 600（30s），覆盖免死救回 / 长战斗 / 延迟 `/kill` 等
- `VictimLethalCandidate` 新增 `forEach(BiConsumer)`（内部拷贝防并发修改）
- 新增诊断：每 20s 输出一次 `[CTT Kill/diag] tick={} candidates={} alive={} died_this_scan={}`

**改动文件**：
| 文件 | 改动 |
|---|---|
| `VictimLethalCandidate.java` | TTL 5 → 600；新增 `forEach` 接口 |
| `VictimTombstone.java` | 完全重写：候选驱动扫描，删除 `prevSnap/iterateEntities/collectSnap` |

**为什么此方案更可靠**：
- 完全绕开 `iterateEntities()` 的遗漏问题
- 候选池天然只含"吃过归属伤害"的 victim——它们消失 ≡ 被击杀（非战斗清场不会进候选池）
- UUID 是 Minecraft 实体唯一 ID、不会复用 → 长 TTL 不会误触发
- 候选池规模小（通常 < 20），每 tick `getEntity` 查询开销可忽略

**期望行为**：现在同一场景（591 伤害 → 怪物死）→ 下一 END_TICK 候选池扫 → `getEntity(uuid)` 返回 null → 打 `[CTT Kill] victim=僵尸 (minecraft:zombie) killer=SimonBasil layer=L1_WEAPON_MATCH kind=MOB ...`，聊天栏同步广播 `[击杀] SimonBasil 击杀了 僵尸  L1`，L 面板 `击杀 +1`。

**版本号**：`6.4.0 → 6.4.1`
**产出**：`cake-tower-hud-plus-6.4.1.jar`（已自动部署，旧 6.4.0 被清理）

---

### 2026-04-25 · v6.4.0 · 击杀 / 助攻统计（方案 C · `V6_STATS_DEV_PLAN` 阶段 ①+②）

用户 2026-04-25 拍板：
- 最后一击 = 击杀者，其他对该 victim 造成过"已分类"伤害的玩家 = 助攻者
- 测试期：L 面板显示击杀/助攻数，聊天栏广播击杀事件（含助攻名单）
- 正式 UI（设计 §5/§6）推迟到阶段 ⑤

**核心设计（方案 C · 致死归属延续 + tombstone 扫描）**：

不用 Mixin 也不扫 `RedHearts` delta。而是：

1. **伤害 tick** 归属成功时，额外把结果写入 `VictimLethalCandidate`（TTL 5 tick · 只记"最近一次"）和 `VictimDamageContributors`（per-victim 总贡献 · TTL 30s）
2. **END_SERVER_TICK 的 `VictimTombstone::tickEnd`** 对比上 tick 末实体池 vs 本 tick：
   - 实体消失 + `VictimLethalCandidate` 命中 → **真死亡**，结算
   - 实体消失 + candidate 未命中 → 非战斗清场（`/kill @e`），忽略
   - 实体仍在（`RedHearts` 被免死脚本救回）→ 不动
3. 结算时 killer 取 candidate；若 candidate 层是未分类（L7+），fallback 查 `VictimLastHitter(AllDMG)` 拿硬归属
4. assists 从 contributors 去掉 killer 自身得到（多人）

**击杀归属 ≡ 致死一击的伤害归属**，复用成熟的九层栈，不另起炉灶。

**新增文件**：

| 文件 | 作用 |
|---|---|
| `server/StageKey.java` | 关卡五元组占位（阶段 ①）。阶段 ②③ 一律传 `null` = "整个会话"，阶段 ④ 启用 |
| `server/VictimLethalCandidate.java` | 致死一击候选缓存（TTL 5 tick）；覆盖写入对应"最后一击" |
| `server/VictimDamageContributors.java` | per-victim × player 伤害贡献表（TTL 30s），助攻数据源 |
| `server/PlayerKillStats.java` | 按玩家击杀 / 助攻 / 未分类击杀累计，共享 `PlayerDamageStats` 的 live/frozen session |
| `server/VictimTombstone.java` | END_SERVER_TICK 扫描 + 真死亡识别 + 聊天广播 |

**修改文件**：

| 文件 | 改动 |
|---|---|
| `AttackerProbe.java` | ① 新增 `public static boolean isAttributionClassified(Layer)`；② `recordFromDamageShower` 归属后写 `VictimLethalCandidate` + `VictimDamageContributors`；③ `gcTick` 新增两处 gc |
| `PlayerDamageStats.java` | 三个 `add*` 方法新增 `StageKey stageKey` 参数（当前传 `null`）；`start/stop/clear/setFrozen` 联动 `PlayerKillStats.syncXxx`；旧 signature 保留为 `@Deprecated` 委托 |
| `DamagePanelRenderer.java` | 详情行追加 `击杀 X · 助攻 Y`；未归属行追加 `击杀 K` |
| `ModConfig.java` | 新增 `broadcastKillsInChat`（默认 `true`） |
| `CttStatsServer.java` | 新挂 `VictimTombstone::tickEnd`（在 `AttackerProbe::gcTick` **之前**，否则 candidate 会被提前回收） |

**期望行为表**：

| 场景 | 结果 |
|---|---|
| 一击秒杀 | 该玩家 `击杀 +1` |
| A 打残 99% + B 最后一刀 | B `击杀 +1`、A `助攻 +1` |
| 火焰长矛 DoT 烧死（点火者持续掉线时） | 致死 DoT tick 走 `VictimDamageSourceCache` carry 归点火者；归不到进"未分类击杀" |
| 玩家掉坑摔死怪 | 本 tick `VictimLethalCandidate` 未写 → 非战斗清场 → 不计 |
| 过场 `/kill @e[tag=E]` 扫场 | 同上，不计 |
| `B14_Protected` / `Imortal` 免死 | 实体仍在、`RedHearts` 被救回 → 不计 |
| 打假人死亡 | **会误记 +1**（已知；阶段 ④ 休息室规则自动解决） |

**聊天广播格式**（`broadcastKillsInChat=true` 默认）：

```
[击杀] Joey 击杀了 SkeletonKing [Boss]  助攻: Fred, Jelly  L1
[击杀] Joey 击杀了 Zombie  L5
[击杀] ??? 击杀了 Zombie  未分类  L9
```

**版本号**：`6.3.11 → 6.4.0`
**产出**：`cake-tower-hud-plus-6.4.0.jar`（已自动部署到 mods）

---

### 2026-04-24 · v6.1.1 · 修复元素 DoT 错归属（L4c 提前 + L4b 对 carryable 跳过 + carry 缓存防毒化）

- **问题现象**（用户联机测试 v6.1.0）：Kirin0321 使用火焰长矛攻击假人：
  - 初击：`FireDMG -90 → Kirin0321 L1` ✓（物理 90 的同时元素火焰首击命中 vanilla stat）
  - 后续 DoT 续 tick：`FireDMG -20 / -2000 / -20 → SimonBasil L4b` ✗（SimonBasil 只是路过，距离假人更近）
- **根因链**（三环叠加 bug）：
  1. **层级顺序错误**：`attribute()` 里 L4b（3m 内玩家位置兜底）在 L4c（DoT carry）**之前**
  2. **L4b 对元素伤害不应该生效**：火焰/冰/闪电等元素 DoT 的攻击者应靠 carry 缓存追溯，而不是"谁靠近算谁"
  3. **carry 缓存被毒化**：L4b 错归属 SimonBasil 后，`record()` 末尾的 carry `remember` 仍写入（只排除了 L4C，没排除 L4B）→ 后续 carry 查询永远返回 SimonBasil → DoT 全链条错归属
- **具体故障流程**（DoT 续 tick 的归属查询）：
  ```
  L1  damage_dealt stat 本 tick  ─ miss（DoT 不触发 vanilla stat）
  L2a 近 marker                 ─ miss（DoT 不关联物理实体）
  L2b 远 marker                 ─ miss
  L3  近 5t damage_dealt 窗口     ─ miss（续 tick 已出 5t 窗口）
  L4  近 20t RightClick 窗口     ─ miss（火焰长矛不是胡萝卜棒 RightClick 触发）
  L4b 3m 内玩家                  ─ 命中 SimonBasil ← 错归属开始
  L4c carry 缓存（FireDMG/Kirin） ─ 轮不到
  ```
- **三层修复**：
  1. **交换 L4c ↔ L4b 顺序**：DoT carry 作为"有证据的追溯"优先于"位置猜测"
  2. **L4b 对 carryable 伤害跳过**：元素伤害（Fire / Ice / Lightning / Ender / Radiant / Shadow / Vile / Toxic DMG）若 carry 过期就直接 L5（未归属），宁可无归属也不错归属
  3. **carry `remember` 排除 L4B_PLAYER_NEAR**：防御性编程，即使 L4b 今后规则放松，也不会污染 carry 源头
- **修复后流程**（同样的 DoT 续 tick）：
  ```
  L1 ~ L4                       ─ 全部 miss（同上）
  L4c carry 缓存（FireDMG/Kirin） ─ 命中 Kirin ✓
  L4b 玩家近场                   ─ 未访问（carryable 跳过）
  L5                             ─ 未访问
  ```
- **carryable 名单**（`VictimDamageSourceCache.isCarryable`）：`FireDMG` `IceDMG` `LightningDMG` `EnderDMG` `RadiantDMG` `ShadowDMG` `VileDMG` `ToxicDMG`（共 8 种元素伤害）。非元素伤害（`MeleeDMG` / `BulletDMG` / `AllDMG` 等）L4b 行为完全不变。
- **TTL 过期后的行为差异**：
  - 修复前：过期 → L4b 兜底 → 错归属现场玩家（毒化链继续）
  - 修复后：过期 → L4c miss + L4b 跳过 → **L5 无归属**（诚实，不伤无辜）
- **不影响场景**：
  - 初次命中（`L1` 硬证据）：完全不变
  - 非元素 DoT（假设存在）：现有 L4b 行为不变
  - 纯物理（vanilla 石剑）：走 AllDMG → L4b 兜底保留
- **版本号**：`6.1.0 → 6.1.1`
- **产出**：`cake-tower-hud-plus-6.1.1.jar`（需手动部署到 mods，因为游戏运行时 jar 被锁）
- **验证**：Kirin 打火焰长矛点燃假人后，SimonBasil 站在假人旁边：
  - 初击：`[A#N] FireDMG -90 → Kirin0321 L1` ✓
  - DoT 续 tick：`[A#N+1] FireDMG -20 → Kirin0321 L4c carry age=20t src=L1` ✓（SimonBasil 不再出现）

---

### 2026-04-24 · v6.1.0 · DamageShower 兜底归属（覆盖 Pierce_Damage / vanilla 左键管线）

- **问题背景**（用户联机测试反馈 v6.0.9）：vanilla 近战武器（石剑 / FrostedSpire 基础伤害 / Swans Lust 主伤害）：
  - `[CTT Stats]` session summary 正确（总伤害 240 / 3 hit / 最高单击 80）✓
  - `/trigger ViewStats` 也能显示"[测试假人] 造成的伤害：240" ✓
  - **但聊天栏零 `[A#N]` 归属广播** ✗
- **根因定位**（追溯地图数据包 `misc/health.mcfunction` L47–51）：
  ```mcfunction
  execute as @e unless score @s CTT_HP matches 0.. run execute store result score @s CTT_HP run data get entity @s Health
  execute as @e store result score @s CTT_HPCheck run data get entity @s Health
  execute as @e[tag=!ElementMelee] unless score @s CTT_HP = @s CTT_HPCheck run scoreboard players operation @s Pierce_Damage = @s CTT_HP
  execute as @e[tag=!ElementMelee] unless score @s CTT_HP = @s CTT_HPCheck run scoreboard players operation @s Pierce_Damage -= @s CTT_HPCheck
  ```
  vanilla 左键攻击触发的是 LivingEntity.damage() → Health 下降 → 地图用前后 tick `CTT_HP` 差值算出 `Pierce_Damage` → `damage.mcfunction` L228 `Damage += Pierce_Damage` → 汇总的 `Damage` 驱动 `DamageShower`。
  **全程不经过 9 种 `*DMG`**（MeleeDMG / BulletDMG / FireDMG / ...），`AttackerProbe` 的 Mixin 完全接不到。
- **对比验证**：火焰长矛（有元素词条）的 `[CTT Attrib]` 正常输出（FireDMG 路径），而 FrostedSpire 的基础物理伤害和石剑的全部伤害都走 Pierce_Damage → 都没广播。
- **方案 A：DamageShower 兜底归属**（用户选定）
  - **不追踪 Pierce_Damage scoreboard**：它两次 set + 立即 reset，Mixin 看到的中间值混乱无意义
  - 改在 `DamageProbe.resolveAndLog` 成功解析 victim 后，**直接调用 AttackerProbe 做归属**
  - 使用虚拟 objective `AllDMG`，走现有五层堆栈（L1 vanilla stat / L2a 近 marker / L2b 远 marker / L3 stat 窗口 / L4 fire 窗口 / L4b 近玩家 / L4c DoT carry / L5 未归属）
- **去重设计**（避免同一次伤害事件的 *DMG 归属 和 AllDMG 兜底 重复广播）：
  - `AttackerProbe.perTickAttributedVictims: Map<tick, Set<UUID>>`
  - 每次 `record()` 被 *DMG 触发，无论归属到哪层都**登记** `(tick, victimUuid)`
  - `recordFromDamageShower` 先查此 set —— 已登记则**跳过**（*DMG 管线信息更精确，优先保留）
  - TTL 5 tick（冗余保护 flushTick 时序）
- **时序正确性**（关键）：
  ```
  tick N 中 (Mixin 同步):
    1. MeleeDMG/FireDMG/... 被地图写入 → AttackerProbe.record → 归属 → 登记 set
    2. DamageShower 被地图写入 → DamageProbe.record → 入 pending 队列（暂不归属）
  tick N 末 (END_SERVER_TICK 按注册顺序):
    3. DamageProbe.flushTick 先执行 → resolveAndLog → recordFromDamageShower → 查 set → 跳过或归属
    4. AttackerProbe.gcTick 后执行 → 清理过期 set
  ```
  damage.mcfunction 的代码顺序保证 `*DMG` 写入**早于** DamageShower summon，所以 DamageShower 走到 recordFromDamageShower 时 set 一定已被填入 ✓
- **行为覆盖矩阵**：
  | 武器 | 经过 *DMG 管线？ | 经过 Pierce_Damage？ | 广播 objective |
  |------|--------|--------|----------|
  | 石剑（vanilla） | ✗ | ✓（左键 HP 差） | **AllDMG** ← 新覆盖 |
  | FrostedSpire 基础物理 | ✗（走 vanilla） | ✓ | **AllDMG** ← 新覆盖 |
  | FrostedSpire 元素火焰 | ✓（FireDMG） | ✗ | FireDMG（*DMG 优先） |
  | Swans Lust 主伤害 | 条件性 `MeleeDMG` | 部分走 Pierce | MeleeDMG 或 AllDMG |
  | AK47 子弹 | ✓（BulletDMG） | ✗ | BulletDMG |
  | 火焰长矛 DoT | ✓（FireDMG 续 tick） | ✗ | FireDMG（L4c carry） |
- **不写入 PlayerRecentAttributionLog**：`AllDMG` 是聚合占位，不是具体元素类型；写进去会让 L4 Tier 打分误判"该玩家最近用 AllDMG"从而干扰后续 `FireDMG` 归属。兜底路径独立闭环，不污染其他层。
- **不写入 VictimDamageSourceCache**：`AllDMG` 不在 carryable 元素集里（Carryable = Fire/Ice/Lightning/Ender/Radiant/Shadow/Vile/Toxic），物理攻击没有 DoT 语义，不需要 carry。
- **聊天栏识别**：AllDMG 会用普通 `YELLOW` 黄色显示 objective 名（与其他 *DMG 同色系）。日志 `[CTT Attrib#N]` 末尾追加 `[shower-fallback]` 标签，便于日志诊断区分。
- **不影响其他层**：L1 / L2a / L2b / L3 / L4 / L4b / L4c / L5 所有路径完全不变，仅新增 `recordFromDamageShower` 作为独立兜底入口。
- **版本号**：`6.0.9 → 6.1.0`（minor 升级，反映新增一条完整归属路径）
- **产出**：`cake-tower-hud-plus-6.1.0.jar`（136 KB，同时部署到 mods）
- **已知局限**（接受的 trade-off）：
  - 混合管线武器（如 FrostedSpire：基础走 Pierce_Damage + 附加 FireDMG）每次命中同 victim 同 tick 有 FireDMG 登记 → AllDMG 兜底被去重跳过 → 聊天栏**只显示 FireDMG 部分数值**（不含基础物理）
  - 但 `[CTT Stats]` session summary 读 DamageShower 总值，仍然正确。归属目的是"锁定攻击者"，数值细分非首要目标
  - 要彻底拆分数值需要"victim 每 tick 实际扣血累计 vs *DMG 之和做差额"机制，属后续优化范畴
- **验证**：
  1. **单人 · 纯 vanilla 石剑打假人** → `[A#N] AllDMG -X @ 测试假人 → Kirin0321 L1`（vanilla `damage_dealt` stat 命中，layer=L1）
  2. **单人 · FrostedSpire 点火假人** → 初次命中：victim 被 FireDMG 登记 → AllDMG 跳过 → 聊天栏只看到 `FireDMG`；持续燃烧 tick 只有 `FireDMG L4c`（DoT carry）
  3. **多人 · A、B 同时用石剑砍 victim** → 两人各自 `AllDMG` 归属到各自玩家（L1 `damage_dealt` stat 玩家级区分，极稳）

---

### 2026-04-24 · v6.0.9 · L4 Tier 打分（后向验证破除多玩家 RightClick 歧义）

- **问题背景**（用户提出）：多人场景下典型错归属：
  - **玩家 A** 持续用 AK47 射击 victim → 每一枪的 `BulletDMG` 被 L2a (`AK47ShootAI` marker) 100% 正确归属给 A
  - **玩家 B** 用火法器打同一 victim → 写入 `FireDMG`，但法器 marker 短命（L2a/L2b miss）、vanilla stat 不触发（L1/L3 miss）→ 落到 L4
  - L4 查询"近 20 tick RightClick + 30m 内"：A 连点开枪（每秒多次 RightClick）+ B 单点法器 → 两人都在候选
  - L4 按"距离最近"破除 → 若 A 更近，B 的 FireDMG **被误归属到 A** ✗
- **用户建议**：
  1. 前向预测 —— 检测玩家手持武器能造成的伤害类型；
  2. 武器相同时用力量 / 法强 / 剑术属性做数值反推。
- **采用方案（反方向，更简单更稳健）**：后向验证"最近归属过什么类型"
  - 不需要维护武器 → 伤害类型数据库（地图 70+ 武器，维护成本高）
  - 不依赖地图伤害公式（改系数就全乱）
  - 纯从运行时观测推导
- **实现** — `PlayerRecentAttributionLog.java`：
  - 每个玩家最近 5 tick 被**硬证据层**（L1 / L2a / L2b）归属过的 `*DMG` 类型
  - **关键：只记硬层**。L3 / L4 / L4b / L4c 都是软证据，若也记会造成"错归属 → 被日志记录 → 下次更坚定错归属"的自增强循环
  - TTL = 5 tick = 0.25 秒（显著短于 L4 RightClick 窗口 20 tick，确保持续攻击的玩家永保类型印记，切换武器后快速清空）
- **L4 改造：Tier 三级打分**
  | Tier | 条件 | 语义 |
  |------|------|------|
  | **1** | 最近 5 tick 有**该 objective** 归属 | 类型强匹配 |
  | **2** | 最近 5 tick **无任何** `*DMG` 归属 | 中性（可能刚开始用新武器） |
  | **3** | 最近 5 tick 有**其他** `*DMG` 归属 | 类型不符，降权 |

  排序：`(Tier ↑, distance ↑)`。Tier 优先级 > 距离。
- **验证问题场景**（A 持续开枪 + B 偶尔法器）：
  - A：最近 5t 归属过 `BulletDMG` → 对 `FireDMG` = **Tier 3**
  - B：最近 5t 无归属（法器 marker miss、vanilla stat 不触发）→ **Tier 2**
  - **B Tier 2 < A Tier 3 → B 胜出 ✓** 即使 A 距离更近
- **聊天栏 detail 新格式**（带 tier 诊断）：
  ```
  [A#200] FireDMG -20 @ 测试假人 → SimonBasil L4 item=...#stick d=8.3m age=15t tier=2 [SimonBasil/T2/d8.3,Kirin0321/T3/d5.2]
  ```
  - `tier=2` = 胜出者 tier
  - `[...]` = 所有候选的 tier 和距离，便于诊断
- **不完美场景**（已知局限）：
  - A 手持枪+法器交替使用：A 的最近类型可能既有 `BulletDMG` 又有 `FireDMG`，对 `FireDMG` = Tier 1；B 的法器也是 Tier 1/2 → 仍按距离破除。这种场景需要方案 B（力量/法强反推），暂缓
  - 玩家刚切换武器、还没造成任何硬归属：进入 Tier 2，按距离选 → 和 v6.0.7 行为相同
- **不影响其他层**：L1 / L2a / L2b / L3 / L4b / L4c / L5 逻辑完全不变
- **版本号**：`6.0.8 → 6.0.9`（下次构建自动 → `6.0.10`）
- **产出**：`cake-tower-hud-plus-6.0.9.jar`
- **验证**：多人联机场景，A 用 AK47 连射 victim、B 用火法器单发 victim，观察 `FireDMG` 的聊天栏归属 —— 应该归到 B，`tier=2`，detail 中能看到 A 是 T3、B 是 T2

---

### 2026-04-24 · v6.0.8 · 元素伤害 DoT carry（火焰 / 冰霜 / 闪电等持续掉血归属沿用）

- **问题背景**（用户联机测试反馈 v6.0.7）：法器点火假人后，聊天栏前几条 FireDMG 正确归属到 Kirin0321（L4，右键窗口命中），但随后 8 秒内每跳持续燃烧伤害全部落到 L5：
  ```
  [A#182] FireDMG -31900  → Kirin0321   L4  item=carrot_on_a_stick d=10.1m age=9t  n=1
  [A#183] FireDMG   -319  → Kirin0321   L4  item=carrot_on_a_stick d=10.1m age=9t  n=1
  [A#184] FireDMG    -20  → Kirin0321   L4  item=carrot_on_a_stick d=10.1m age=18t n=1
  [A#185] FireDMG  -2000  → Kirin0321   L4  item=carrot_on_a_stick d=10.1m age=18t n=1
  [A#189] FireDMG    -20  → ?           L5  hitTick=0 hitWin=0 fires=0 pNear=0
  [A#190] FireDMG  -2000  → ?           L5  hitTick=0 hitWin=0 fires=0 pNear=0
  [A#191-200+] 同上，全部 L5
  ```
- **根因**：所有 v6.0.7 的前 6 层都是"**瞬时证据**"，而火焰燃烧是持续 8 秒的 DoT：
  - marker（`NSLaserHit` 等）早已被 kill → L2 miss
  - RightClick 窗口只有 20 tick = 1 秒 → L3/L4 miss（点火后 20 tick 内有效，之后失效）
  - 玩家 Kirin0321 离 victim 10.1m（常态站位）→ L4b 3m miss
  - 结果：每跳持续伤害都 L5
- **修复：新增 L4c DoT carry 层**
  ```
  L1    本 tick vanilla damage_dealt stat       ← 不变
  L2a   3m marker/projectile                    ← 不变
  L2b   30m marker/projectile                   ← 不变
  L3    近 5 tick damage_dealt 窗口              ← 不变
  L4    近 20 tick RightClick + 30m              ← 不变
  L4b   3m 内 PlayerID 玩家本人                  ← 不变
  L4c   元素伤害 DoT carry（新）                 ← v6.0.8 新增
  L5    全军覆没                                ← 不变
  ```
- **实现** — `VictimDamageSourceCache.java`：
  - 缓存 key = `(victimUuid, objective)`；value = `(attackerUuid, tick, weaponHint)`
  - 每次 L1–L4b 成功归属 **且** objective 属于**元素伤害白名单**时，刷新缓存
  - TTL = 200 tick = 10 秒（MC 着火 8 秒 + 2 秒余量）
  - L4c 命中时**不刷新** tick（否则持续伤害会无限延长 TTL，永不过期）
- **元素伤害白名单**（可 carry）：
  - `FireDMG` / `WaterDMG` / `IceDMG` / `DarkDMG` / `LightDMG` / `ElectricDMG`
- **非 carry 名单**（一次性命中，不沿用）：
  - `MeleeDMG` / `BulletDMG` / `ForceDMG`
  - 原因：AK47 / 枪械每一枪都该独立归属；A 开一枪后 B 接着开，B 的 L4 若失败不该被错误 carry 到 A
- **聊天栏格式**（L4c detail）：
  ```
  [A#189] FireDMG -20   @ 测试假人 → Kirin0321 L4c carry Player(Kirin0321) age=34t src=L4
  ```
  - `carry Player(Kirin0321)` = carry 自该玩家
  - `age=34t` = 距离上次真归属已过 34 tick（1.7 秒）
  - `src=L4` = 被 carry 的那次原归属层级
- **缓存覆盖语义**（多玩家 / 多元素场景）：
  - 玩家 A 放火 victim → cache[(V, FireDMG)] = A
  - 玩家 B 再放一次火 victim → L4 成功，cache 被 B 覆盖 ← 新火源覆盖旧火源，合理
  - 玩家 A 放火 + 玩家 B 放冰 → cache[(V, FireDMG)] = A, cache[(V, IceDMG)] = B，互不影响
- **边缘案例**：两个玩家同时对同一 victim 放同一种元素（如都放火）
  - 只能归属到"最后一次 L4 成功"的那位；这是近似，无法精确区分两把火的贡献
  - 实测发生概率极低，如需精确需要 burn stack 建模，暂不实现
- **版本号**：`6.0.7 → 6.0.8`（下次构建自动 → `6.0.9`）
- **产出**：`cake-tower-hud-plus-6.0.8.jar`
- **验证**：用火焰法器打假人，前几条 FireDMG 应是 L4（新归属），后续持续燃烧应变成 L4c（carry），detail 里写 `carry Player(xxx)`

---

### 2026-04-24 · v6.0.7 · 归属堆栈重排（marker-first，消除"最近玩家"误归属）

- **问题背景**（用户联机测试反馈 v6.0.6）：
  - 枪械类（AK47）100% 正确 ✓
  - **法器类（Nut Laser / 坚果激光等 `FireDMG`）归属错误**：选的是"离 victim 最近的玩家"，但并非真实攻击者；两玩家走位时归属在两人之间飘移
  - 石剑（原版武器）完全没出现在聊天栏 → **非 bug，vanilla 武器不走地图 `*DMG` 管线**，本就不进入统计，详见 FAQ 节
- **日志证据**（v6.0.6 `latest.log` L1376-1418，`victim=测试假人`，两玩家 Kirin0321 / SimonBasil 都在场）：
  ```
  [A#65] FireDMG -355   → Kirin0321   L2b PLAYER/Kirin0321/d=6.32m  candidates=2
  [A#74] FireDMG -319   → SimonBasil  L2b PLAYER/SimonBasil/d=8.28m candidates=2
  ```
  两条事件 attacker 不同，仅因"最近玩家"切换——这就是算法病根。
- **根因分析**：
  - v6.0.5 的 `AttackerResolver` 把 "3m/30m 内任何 `PlayerID` 实体"合并扫描
  - 玩家身上带 `PlayerID`（代表"这是 X"），在 marker 不覆盖的远程场景里会被当成候选
  - L2b 的"攻击证据 tag 过滤"形同虚设：两个玩家都拿着 carrot_on_a_stick 自定义武器，身上都有 `Charge/Hold/Use` 类 tag，同时通过过滤 → 只能"距离最近"破除歧义 → 归属错误
- **修复方案**（marker-first 六层堆栈）：
  ```
  L1    本 tick vanilla damage_dealt stat       ← 70+ 近战武器 100% 覆盖
  L2a   3m marker/projectile（仅非玩家）         ← AK47 等近场 marker
  L2b   30m marker/projectile（仅非玩家）        ← Nut Laser 远射 marker
  L3    近 5 tick damage_dealt stat 窗口          ← 近战延续 DoT
  L4    近 20 tick RightClick + 30m 距离加权      ← 远程法器（主动右键是铁证）
  L4b   3m 内 PlayerID 玩家本人（兜底）           ← 近战贴脸 vanilla 场景
  L5    全军覆没
  ```
  **关键改动**：L2 层剔除玩家候选。玩家本人作为归属证据，降级到 L4（主动按了右键）或 L4b（贴脸 3m 内）。
- **实现**：
  - `AttackerResolver` 拆分成两个独立扫描函数：
    - `scanMarkers(world, victim, radius)` → 仅收 marker / projectile / armor_stand（`includePlayers=false`）
    - `scanPlayers(world, victim, radius)` → 仅收玩家实体（`includeNonPlayers=false`）
  - `Candidate.desc()` 新增紧凑格式：`MARKER/AK47ShootAI/pid=-1234/d=1.81m`
  - `AttackerProbe.attribute()` 按新顺序调用，detail 字段用 `describeList()` 列出前 3 个候选（超出用 `+N` 概括），方便验证
  - `AttackerProbe.Layer` 新增 `L2A_MARKER_NEAR / L2B_MARKER_FAR / L4B_PLAYER_NEAR`（旧 `L2A_NEAR / L2B_FAR` 语义变更，弃用）
- **为什么 marker 可信、玩家不可信**：
  - **marker / projectile 仅由攻击行为衍生**：它的存在本身就意味着攻击正在发生。它带的 PlayerID 是地图在 marker 生成时**显式复制**自发动攻击的玩家 UUID[0]，因果关系确定
  - **玩家身上的 PlayerID 是身份标识**，跟"正在攻击"无关——站在旁边的队友也有自己的 PlayerID
  - 法器 marker（如 `NSLaserHit`）虽然短命，但在 `*DMG` 写入那一 tick 还活着（这是 Mixin `@At("RETURN")` 的时序），扫得到
- **预期效果**：
  - 枪械场景（AK47）：不变，仍命中 L2a ✓
  - 法器场景（Nut Laser 等）：marker 被 L2a/L2b 捕获 → 归属到 marker 的 PlayerID 玩家（真实攻击者）✓
  - 没 marker 的远程场景：fallback 到 L4（RightClick 时间窗）
  - 近战贴脸 vanilla：L1 命中；若 L1 失败则 L4b 兜底
- **版本号**：`6.0.6 → 6.0.7`（下次构建自动 → `6.0.8`）
- **产出**：`cake-tower-hud-plus-6.0.7.jar`
- **验证手段**：联机再次测试，重点观察：
  1. **FireDMG / MeleeDMG 法器**：`layer` 字段应变成 `L2a / L2b / L4`，attacker 不再随走位切换
  2. **detail 字段**：现在会列出多个候选（`, +N` 分隔），看实际扫到谁
  3. **AK47**：维持 `L2a`、100% 正确
  4. 若仍命中 `L4b` 且 `candidates=2`，说明没 marker 也没 RightClick 窗口信号，场景确实无法区分——此时会选**最近玩家**（3m 内），这是唯一可靠的兜底

---

### FAQ：石剑（原版武器）为什么不显示？

**这是设计，不是 bug**。

地图的伤害管线对原版武器和自定义武器**完全分离**：

| 武器类型 | 走什么管线 | 触发 `*DMG`？ | 触发 `DamageShower`？ | 本 mod 是否统计？ |
|---------|-----------|---------------|----------------------|------------------|
| 自定义武器（carrot_on_a_stick + CustomModelData） | 地图自写的 `damage.mcfunction` | ✓ | ✓ | ✓ |
| 石剑 / 钻石剑 / 拳击等原版近战 | vanilla `LivingEntity.damage()` | ✗ | ✗ | ✗ |
| 原版弓箭 | vanilla | ✗ | ✗ | ✗ |

**所以原版武器攻击**：
- victim 原版 HP 会扣（但假人的 HP 可能被地图重写了）
- 玩家的 `damage_dealt` stat 会 +=delta×10（vanilla 自动行为，70+ objective 一齐涨）
- 但地图不认它为"玩家输出"
- `AttackerProbe` 不触发 → 聊天栏没消息
- `session` 统计也不累加

这符合地图"只有自定义武器算输出"的设计意图。若要改为"任何伤害都算"，需要额外加一个 `LivingEntity.damage` Mixin + 伤害源分析（需区分玩家 / 环境 / 敌人 AoE 反伤等），不在当前版本计划内。

---

### 2026-04-24 · v6.0.6 · 归属事件实时广播到聊天栏（测试工具）

- **需求触发**：用户希望联机测试时不用回看 `latest.log`，直接在游戏内聊天栏看到归属结果。
- **消息格式**（短到能一行塞下）：
  ```
  §8[A#42] §eBulletDMG §c-4 §7@ §fFrost §8→ §aKirin0321 §bL2a §7item=...
  ```
  字段顺序：`事件编号 · 伤害类型 · 护甲前值 · victim · → · attacker · 层 · 精简 detail`
- **颜色映射**：
  - 伤害类型：黄（`§e`）
  - 伤害数值：红（`§c`）
  - victim 名：白（`§f`）
  - attacker：绿（`§a`，识别到在线玩家）/ 金（`§6`，Unknown 但有 pid）/ 暗红（`§4`，L5 完全未归属）
  - 层标记：青（`§b`，L1/L2a/L2b/L3/L4/L5）
  - 其它前后缀：深灰（`§8`）
- **防刷屏**：同 tick 事件**聚合限流**
  - ≤ 3 条：原样逐条发
  - \> 3 条：发前 3 条 + `  +N 条归属事件已省略（本 tick）` 斜体摘要行
  - AK47 全队齐射理论上限 20-30 事件/tick，折叠后聊天栏每秒最多 ~80 行（4 行/tick × 20 tick），可读
- **实现**：
  - `AttackerProbe.Layer` 新增 `shortTag()`（`L1 / L2a / L2b / L3 / L4 / L5`）
  - `AttackerProbe.pendingBroadcasts` ConcurrentLinkedQueue 缓冲
  - `AttackerProbe.gcTick` 每 tick 末 flush：`server.getPlayerManager().broadcast(msg, false)` 以普通聊天消息（非 overlay）发送给所有在线玩家
  - `buildChatLine` 用 `MutableText.append` 拼色彩 Text
  - 运行时开关：`AttackerProbe.chatBroadcastEnabled`（`volatile boolean`，默认 `true`）
- **与 latest.log 的关系**：聊天栏只是 log 的简化镜像，完整 detail 字段（含 candidates / age / distance 等）仍在 log 中保留不变
- **关闭方式**（如需）：目前通过改代码重启（后续版本考虑加按键或 `/trigger` 切换）
- **版本号**：`6.0.5 → 6.0.6`（自动递增，下次构建产物 `6.0.7.jar`）
- **产出**：`cake-tower-hud-plus-6.0.6.jar`

---

### 2026-04-24 · v6.0.5 · 攻击者归属五层堆栈（覆盖近战 / 枪械 / 远程法器）

- **需求触发**：v6.0.4 单层 3m `PlayerID` 扫描 测试结果显示：
  - **BulletDMG (AK47)**：100% 成功 —— marker 生成在命中点附近 (~1.81m)
  - **MeleeDMG (Nut Laser / 坚果激光)**：100% 失败 `attacker=<none in 3m>`
    —— Nut Laser marker `NSLaserAI` 生命期仅 1 tick，被 kill 时玩家本人还在 25m 之外
  - 用户要求覆盖所有武器，且远程武器扫描半径至少 30m
- **关键补充发现**（数据包二次勘探）：
  1. `scoreboards_part_2.mcfunction` 注册 **70+ 个武器特定 `damage_dealt` stat objective**
     （`SwansLustDMG`, `PumpkinCarverKnifeDMG`, `JellySwordDMG`, ...），criterion 均为
     `minecraft.custom:minecraft.damage_dealt` —— vanilla 会在玩家造成伤害时自动 += delta
  2. 地图注册 `RightClick minecraft.used:minecraft.carrot_on_a_stick` —— vanilla 会在玩家右键时自动 +1
  3. 所有地图武器都以 `carrot_on_a_stick` 为实体载体 → 任何玩家开火都会触发 `RightClick` 写入
- **方案 X 五层堆栈**（`AttackerProbe`）：
  - **L1** · 本 tick vanilla `damage_dealt` stat 写入的玩家 ← 70+ 种近战武器 100% 覆盖
  - **L2a** · 3m PlayerID 扫描 ← AK47 / 近战 marker 场景
  - **L2b** · 30m PlayerID + "攻击证据 tag" ← Nut Laser 等玩家本人带 `NSLaserCharge` 的场景
  - **L3** · 近 5 tick damage_dealt stat 窗口 ← 近战延续 DoT / 效果后置
  - **L4** · 近 20 tick `RightClick` 右键开火 + 30m 距离加权 ← 远程法器终极兜底
  - **L5** · 以上全无 → `<unattributed>`（环境伤害 / 未知武器）
- **L2b 攻击证据 tag 白名单**：`Charge / Hold / Use / Shoot / Cast / Fire / Target / Hit / Atk / Attack`
  的任意包含子串 —— 避免把远处 30m 外挂机队友误归属
- **代码落地**：
  - 新增 `PlayerHitLog`：MinMax 20 秒的 vanilla `damage_dealt` stat 写入日志（每玩家一个 Deque）
  - 新增 `PlayerFireLog`：MinMax 20 秒的 `RightClick` 右键事件日志（含玩家位置用于 L4 距离加权）
  - `AttackerResolver` 拓展 NEAR(3m) / FAR(30m) 分层扫描 + "远场必须带攻击证据"过滤
  - `AttackerProbe` 重写为五层堆栈查询，日志字段 `layer=L1~L5` 显示命中层级
  - `ScoreboardUpdateMixin` 通过 **criterion 类型动态识别**（不再硬编码 70+ 个 objective 名），
    自动分派到 `PlayerHitLog` / `PlayerFireLog`
  - `DamageProbe.currentTick()` 暴露 tick 基准，三个收集器共用同一计数
  - `CttStatsServer` 新挂 `AttackerProbe.gcTick` 每 tick 清理过期日志
- **诊断日志格式示例**（v6.0.5 新增 `layer=` 字段）：
  ```
  [CTT Attrib#42] type=MeleeDMG victim=Frost (minecraft:zombie) pre=37
    layer=L4_FIRE_WINDOW attacker=Player(Kirin0321)
    detail=item=minecraft:carrot_on_a_stick d=8.4m age=3t total=1
  ```
- **本版本范围**：**仍仅打日志**。session 累加逻辑不动（仍然是"全队总输出"）。
  - 等用户确认各武器 L1~L4 命中率 ≥ 95% 后，v6.0.6 再把"按 attackerUuid 分桶 session"纳入，
    彻底解决"队友伤害被计入本机 session"的问题
- **测试清单**（用户联机验证）：
  1. 单人测不同武器：近战（剑/拳套/匕首）、AK47、Nut Laser、Jelly Dash、Swans Lust、Pumpkin Carver Knife、原版弓
     → 每种武器的 `layer=` 字段应当集中在特定层，覆盖率 100% 为理想
  2. 两人联机同时打同一 boss → 不同攻击者的日志条目 attacker 字段应该分得清
  3. 特别关注 `layer=L5_NONE` 条目，记录 `detail` 字段里 `hitLog / fireLog` 计数
     —— 为 v6.0.6 规则扩展提供证据
- **预期覆盖率**：
  - 近战武器（70+ 种 `*DMG` vanilla stat）→ L1 ≥ 99%
  - AK47 / 直接命中 marker 武器 → L2a ≥ 95%
  - Nut Laser / 远程法器 → L2b 或 L4 ≥ 90%
  - 原版投射物（箭/雪球/三叉戟）→ L2a + L4 ≥ 90%
  - 陷阱 / 敌人 AoE / 环境 → L5（属期望，归 env/unknown）
- **性能**：
  - L1 查询 O(#tick hits) 通常 < 10；L2a 3m Box 扫 < 50μs；L2b 30m Box 扫 ~200μs（实体数多时）；L3/L4 查询 O(#玩家) 均 < 20μs
  - 高频武器联机 20-30 次/tick × 最坏 L2b 路径 ≈ 6ms，仍在 1 tick 预算内
  - 日志条目 TTL 400 tick (20 秒) 自动 GC，内存稳定
- **版本号**：`6.0.4 → 6.0.5`（自动递增，下次构建产物 `6.0.6.jar`）
- **产出**：`cake-tower-hud-plus-6.0.5.jar`

---

### 2026-04-20 · v6.0.4 · 攻击者归属探针（诊断阶段，为 v6.0.5 精确个人统计铺路）

- **需求触发**：用户发现「联机 + 队友」场景下 v6.0.3 的 session 会把队友伤害也算进去
  —— 因为 `DamageShower` 粒子本身只刻了受害者，没有攻击者身份。尝试距离归属立刻被用户否决
  （AK47 是远程武器，打 boss 时队友可能在几十格外）。
- **关键发现**（数据包逆向，已写入 MAP_DATAPACK_ANALYSIS.md §12）：
  1. `server_main.mcfunction:125`：`execute as @a run execute store result score @s PlayerID run data get entity @s UUID[0]`
     → **每个玩家都有一个稳定的 `PlayerID`（= UUID 前 32 位）**
  2. `ranged2_03_ak_47.mcfunction:32-34`：AK47 每发子弹 summon 一个 marker，并立即把
     `attacker.PlayerID` 拷到 marker 上（防止打到自己，也防友伤）
  3. 近战类武器（南瓜刀、beeswax 等）直接以玩家为"攻击 marker" —— 玩家本人 `PlayerID` 就能查
  4. 原版投射物（箭/雪球/三叉戟）的 `Owner` NBT 也指向玩家 UUID
- **核心推论**：在 `*DMG`（前置伤害缓冲 scoreboard）写入 victim 的那一瞬间，
  victim 周围 3 米内**必有至少一个** `PlayerID != 0` 的实体（玩家 / marker / 投射物）
  —— 取最近的那个就是攻击者。
- **9 种伤害管线覆盖**（`damage.mcfunction` 汇总分析）：
  - 三大核心：`MeleeDMG` (line 413) / `BulletDMG` (line 545, 555) / `ForceDMG` (line 875)
  - 六种元素：`FireDMG / WaterDMG / IceDMG / DarkDMG / LightDMG / ElectricDMG`
  - 全部走同一个 Mixin（`Scoreboard.updateScore`），zero 额外开销
- **代码落地**：
  - 新增 `AttackerResolver`：在 victim 周围 3 格扫所有 `PlayerID > 0` 的实体（玩家 / marker / 投射物 / armor_stand），
    过滤掉 `DamageShower` 粒子和 `tag=E` 的其它敌人，按距离升序返回候选清单
  - 新增 `AttackerProbe`：监听 9 种 `*DMG` objective 写入，调 `AttackerResolver`，把结果打到 `latest.log`
  - 扩展 `ScoreboardUpdateMixin`：单 Mixin 分派两类目标（`DamageShower` → DamageProbe，9 种 *DMG → AttackerProbe）
  - `CttStatsServer.onInitialize` 挂 `SERVER_STARTED` 事件缓存 `MinecraftServer` 引用，供 Mixin 反查 world
- **诊断日志格式示例**：
  ```
  [CTT Attrib#42] type=BulletDMG victim=测试假人 (minecraft:zombie) pre=4
    → attacker=Player(Kirin0321)
    candidates=[AK47ShootAI[MARKER/pid=-1234567/d=0.15m], Kirin0321[PLAYER/pid=-1234567/d=2.45m]]
  ```
- **本版本范围**：**仅打日志**，不改变 session 行为。session 仍然是"全队总输出"语义。
  - 这样设计让用户能先在联机场景里跑一轮各种武器，核对归属是否正确，
    发现归属失败的场景再修订算法，最后 v6.0.5 再接入 session 和 UI。
- **测试清单**（用户验证步骤）：
  1. 单人打假人 → 日志里 attacker 应该全是自己
  2. 带 SimonBasil 联机，两人同时打同一个假人 → 日志能否分辨两人
  3. 试不同武器：近战、AK47、弓、弹弓、投掷物、召唤法术、AoE 法术
  4. 任何武器归属到 `<none in 3m>` 或 `Unknown(pid=...)` 的场景都值得记录，帮助 v6.0.5 加特殊规则
- **预期覆盖率**：
  - 近战 / 枪械 / 元素法术 ≥ 95%
  - 原版投射物（箭/雪球/三叉戟）≥ 90%
  - 陷阱 / 环境伤害 0%（属期望行为，归入 `env/unknown`）
- **性能**：每次 `*DMG` 写入同步扫 Box(3×3×3)，单次开销约 50μs。联机 4 人齐射 AK47 理论上限 20-30 次/tick，
  约 1.5ms/tick（15% 预算），实际会远低于此因地图其它 tick 工作挤占更多预算。
- **版本号**：`6.0.3 → 6.0.4`（自动递增，下次构建产物 `6.0.5.jar`）
- **产出**：`cake-tower-hud-plus-6.0.4.jar`

---

### 2026-04-20 · v6.0.3 · 修复 unresolved 粒子导致 session 漏算的 bug

- **诊断来源**：v6.0.2 单人 AK47 打假人测试
  - mod session 总伤害 **216** / 50 命中 / maxHit=4
  - 地图假人自带统计 **256**
  - 偏差 40 (≈16%)，与日志里 `<unresolved:text_display gone>` 条目的频次吻合
- **根因**：
  - 地图在下一 tick 的早期就 `kill @e[type=text_display,tag=DamageShower]` 掉粒子（视觉生命期很短）
  - v6.0.2 版本的 `resolveAndLog()` 在 `text_display == null` 时直接 `return`，
    而 session 累加逻辑写在该函数后半段 —— 粒子不见即漏算
  - Mixin 拦到的 scoreboard 写入值本身是正确的（地图已精确写好），**不是地图机制的丢失，是我们后处理 bug**
- **修复**：把 session 累加从 `resolveAndLog()` 搬到 `record()`（Mixin 拦截入口）
  - 合法性依据：`DamageShower` 粒子**只**在"对敌人造成伤害"管线产生
    （治疗 `HealDMG` 完全独立不产生 DamageShower），故"进入 DamageShower 管线 ≡ 对敌人造成伤害"
  - 因此移除 E-tag 过滤（变为冗余）
  - `resolveAndLog()` 改为**只**负责日志 victim 解析，不再参与 session 计数
- **预期效果**：单人假人测试应 ≈ 100% 匹配地图自带统计
- **未修的层**（留给未来，如确实需要）：
  - **地图 `#ServerLag` 保护**：tick > 50ms 时整段 DamageShower 管线被跳过，**地图自带统计也会一起少**
  - **Mixin 尚未在 Mixin 线程捕获粒子位置**：victim 解析还是可能 unresolved（不影响 session 计数，但日志里看不到 victim）
- **四人齐射 boss 预测**（用户疑问）：
  - A. 我们 mod 的 bug → **v6.0.3 已修，预期持平**
  - B. 地图 `#ServerLag` 触发 → 多人高频开火更容易触发，**两边一致性地少**，对照时偏差反而不大
  - C. 粒子 `limit=10,sort=random` → 只影响视觉叠加，不影响 `scoreboard set`，不构成漏采
- **版本号**：`6.0.2 → 6.0.3`（自动递增，下次构建产物 `6.0.4.jar`）
- **产出**：`cake-tower-hud-plus-6.0.3.jar`

---

### 2026-04-20 · v6.0.2 · 按键区间伤害统计（高频武器漏采验证工具）

- **需求触发**：用户在 v6.0.1 `latest.log` 看到 `[CTT Stats]` 成功刷出后担心 ——
  「玩家使用如 AK47 等高频率伤害武器时会丢失伤害」。`DamageShower` 粒子的已知限制：
  - 单 tick 单点 AoE 上限 `limit=10,sort=random`
  - `#ServerLag` 触发时整条 `DamageShower` 管线被跳过
- **解决方案**：加一个「按键区间统计」工具，用于**手动对照**地图自带的 DPS 统计 / 假人日志，
  帮助用户定位"到底在哪些场景下会丢粒子"。
- **按键**：`L`（默认，可在 键位设置 → "蛋糕塔 HUD 增强" 分类 改绑）
  - 第一次按 → 开始统计（聊天栏提示 + Actionbar 实时刷数据）
  - 再按一次 → 结束统计 → 聊天栏出总结 3 行
- **统计范围**：只统计「对敌人造成的伤害」
  - 通过检查受害者是否带 `E` 标签（地图定义的敌人 tag）筛选
  - 自动排除：队友、自残、PvP、NPC
  - 测试假人被算进去（因为假人也带 `E`）—— 符合用户测试意图
- **Actionbar 实时格式**：`◆ 统计中: 240 伤害 / 4 命中 / 1.5s`
- **结束总结示例**（聊天栏）：
  ```
  [CTT Stats] 区间统计结束
    总伤害 1200 / 20 次命中 / 5.0秒 / 240.0 DPS / 最高单击 120
    平均 60.0 / 命中 · tick 跨度 100
  ```
- **代码落地**：
  - `DamageProbe`：新增 `startSession() / stopSession()` 与 `SessionSummary` record；
    `resolveAndLog` 里当 session 激活且 victim 带 `E` 时累加（`AtomicLong total / AtomicInteger events/maxHit`）
  - `CttHealthDisplay`：注册 `toggleDmgSessionKey`；tick 回调里按下时 `handleDmgSessionToggle`；
    session 期间每 tick `renderDmgSessionActionbar`（写 actionbar）
  - `zh_cn.json` / `en_us.json`：新增 `key.ctt-health-display.toggle_dmg_session` 翻译
- **共享数据的架构说明**（单机够用、专用服务器后续补）：
  - 单机 integrated server：Client & Server 在同一 JVM / ClassLoader，
    `DamageProbe` 的 `static volatile`/`AtomicLong` 可直接被客户端线程读写 → 零延迟
  - 专用服务器 / 联机：Client 访问不到远端服务器的静态变量 —— 留给 v6.0.3
    `CustomPayload` 双向同步（`StartSession` / `StopSession` 两个 C2S 包 + `LiveUpdate` 一个 S2C 包）
- **已知限制 / 诚实声明**：
  - **不能检测漏采**：session 本身只记录"成功进入 DamageShower 管线"的事件，
    如果某发子弹的粒子被 `limit=10` 丢掉或 `#ServerLag` 跳过，session 同样看不到
  - **用途是"对照"**：把本工具的区间总伤害 × 与 地图 `/trigger viewstats` 给出的 Session Damage 或假人挨打后自身血量减少量 **对比**，才能判断是否丢采
  - **中途退出**：session 在你按开始后常驻，哪怕你下线重连 —— 按结束前不会自动终止（设计如此，方便跨关卡）
- **测试指南**（假人房挨打对照）：
  1. 进入休息室，走到测试假人旁
  2. 按 `L` 开始 session
  3. 拿 AK47 对着假人扫射 3 秒
  4. 按 `L` 结束 session
  5. 看假人头顶 boss 栏显示的「总计伤害」vs. 我们聊天栏的「总伤害」
  6. 若偏差 > 10%，基本可以确认存在漏采，此时把 `latest.log` 发回来我们能看到确切丢了几条
- **版本号**：`6.0.1 → 6.0.2`（自动递增，下次构建产物 `6.0.3.jar`）
- **产出**：`cake-tower-hud-plus-6.0.2.jar`

---

### 2026-04-20 · v6.0.1 · 最终伤害采集 POC：锁定 DamageShower 粒子为唯一数据源

- **需求触发**：用户两条关键反馈改写了 v6.0.0 原定"四层血 delta"方案
  1. 「怪物在被攻击时有点会回血」—— 若用 `ΔHP = dmg − heal` 算伤害，受 `HealDMG` 管线污染，不能精确
  2. 「玩家造成伤害时会显示数值粒子，这个有参考价值吗」—— 指向地图自带的 `DamageShower` 机制
- **关键发现**（`damage.mcfunction` line 1021~1028）：
  ```mcfunction
  # 地图为每个 Damage>=1 的 CTTAll 实体召唤一个 text_display 粒子（tag=DamageShower）
  execute if score #ServerLag CT matches 0 if score #DamageNumbers CTT matches 1 run
      execute at @e[scores={Damage=1..},limit=10,sort=random,tag=CTTAll] run
          summon text_display ~1 ~1 ~ {Tags:["DamageShower","Prop"]...}
  # 关键一行：把粒子附近受害者的 Damage 值写入粒子自己的 DamageShower 分数（write-once）
  execute at @e[scores={Damage=1..}] run
      execute as @e[tag=DamageShower,distance=..1.5] unless score @s DamageShower matches 0.. run
          scoreboard players operation @s DamageShower = @e[scores={Damage=1..},limit=1,sort=nearest] Damage
  ```
  `unless score @s DamageShower matches 0..` 提供 **write-once 语义**，确保每个粒子只被写一次——完全对应一次独立的最终伤害事件
- **方案对比**（替换掉 v6.0.0 的"四层血 delta"）：

  | 维度 | 原计划（四层血 delta） | 新方案（DamageShower 粒子） |
  |---|---|---|
  | 怪物回血污染 | ❌ 无法剥离 | ✅ 完全绕过（不碰 HP） |
  | 最终伤害定义契合 | 近似 | ✅ 100% 是地图自己"显示"的数字 |
  | `Damage` 被多次 operation 覆盖 | 不受影响 | ✅ write-once 不会被污染 |
  | `tick` 末 reset 擦除 | ✅ 四层血不 reset | ✅ DamageShower 分数粘在 text_display 上 |
  | 捕获时机 | END_SERVER_TICK | Mixin 实时捕获 |

- **代码变更**：
  - 新增 `src/main/resources/ctt-health-display-server.mixins.json`：服务端 Mixin 容器，默认 environment（client/server 都装载，通过 `instanceof ServerScoreboard` 运行时过滤）
  - `fabric.mod.json` 注册该 mixins 文件
  - 新增 Mixin `com.ctt.healthdisplay.server.mixin.ScoreboardUpdateMixin`：
    - 目标方法：`Scoreboard.updateScore(ScoreHolder, ScoreboardObjective, ScoreboardScore)`，intermediary `fcg.a(Lfcf;Lfby;Lfcd;)V`，yarn `method_1176`（1.21.4）
    - `@At("RETURN")` 注入，仅当 `this instanceof ServerScoreboard` 且 `objective.getName() == "DamageShower"` 时调用 `DamageProbe.record`
    - 为什么是 `Scoreboard` 基类而不是 `ServerScoreboard`：`ServerScoreboard` 未重写 `updateScore`，写入走父类
  - 新增 `com.ctt.healthdisplay.server.DamageProbe`（采集器单例）：
    - `record(holder, value)`：过滤 `value<=0`，把 `holder.getNameForScoreboard()` 解析为 UUID 放入 lock-free 队列
    - `flushTick(server)`：每 tick 末消费队列，对每个事件：
      1. `server.getWorlds()` 遍历找 text_display by UUID
      2. 在粒子位置取 `Box.of(pos, 3, 3, 3)` 半径 1.5 的盒子，`world.getOtherEntities(...)` 过滤 `CTTAll` 且非 `DamageShower` 的候选
      3. 选最近的（平方距离 ≤ 2.25）作为受害者
      4. 写日志：`[CTT Stats] tick=<t> dmg=<n> victim=<name>(type=<id>, uuid=<uuid>)`
  - 重写 `CttStatsServer.onInitialize`：加 `ServerTickEvents.END_SERVER_TICK.register(DamageProbe::flushTick)`
- **日志示例（预期）**：用户在休息室打假人时，latest.log 会出现类似
  ```
  [CTT Stats] tick=3421 dmg=15 victim=Test Dummy (type=minecraft:husk, uuid=xxx)
  ```
  数字应当与地图自带的假人伤害统计完全一致
- **已知限制**（诚实记录）：
  1. **AoE >10 目标会丢**：地图 `limit=10,sort=random` 只生成最多 10 个粒子，后续 v6.0.x 可加 `Damage` objective 直接监听做兜底
  2. **服务器卡顿漏采**：地图有 `#ServerLag CT matches 0` 条件，卡服时粒子不生成——本地单机不触发
  3. **多个受害者紧密重叠（<1.5m）**：粒子的 `sort=nearest` 可能指向错误受害者，但对"总伤害"无影响，只影响受害者归属
- **v6.0.1 验证路径**（用户指定）：
  - 启动游戏加载 CTT 地图 → 进休息室 → 攻击测试假人
  - 观察 `.minecraft/logs/latest.log` 中的 `[CTT Stats]` 行
  - 对照假人头顶 / 聊天栏地图自带统计，确认数值一致
- **涉及文件**：`src/main/resources/ctt-health-display-server.mixins.json`（新）、`src/main/resources/fabric.mod.json`、`src/main/java/com/ctt/healthdisplay/server/CttStatsServer.java`、`src/main/java/com/ctt/healthdisplay/server/DamageProbe.java`（新）、`src/main/java/com/ctt/healthdisplay/server/mixin/ScoreboardUpdateMixin.java`（新）、`MAP_DATAPACK_ANALYSIS.md`（追加 §11）
- **产出**：`cake-tower-hud-plus-6.0.1.jar`

### 2026-04-20 · v6.0.0 · 大版本开启：服务端伤害统计采集链路启动

- **需求（用户原话）**：
  1. 「伤害为最终对怪物造成的血量减少量」
  2. 「先做本地存档服务器的测试」
  3. 「将 mod 版本号改为 6.0.0 大版本」
- **跨度判定**：v5 整线是**纯客户端 HUD**（`environment: client`，仅 `ClientModInitializer`）。v6 首次引入**服务端采集/持久化**，架构根本性变更，走大版本跳号。
- **关键调研结论**（完整论述见 `MAP_DATAPACK_ANALYSIS.md §10`）：
  1. 读《护甲与抗性系统玩家指南.md》+ 地图 `damage.mcfunction` 末端 (line 1020~1062)，确认：
     - 地图**不用** Minecraft 原版 HP，走自己的 `BlueHearts → BlackHearts → SoulHearts → RedHearts` 四层血 scoreboard
     - 最终伤害 = 地图 `Damage` scoreboard 的 per-tick 值（经过所有元素加护/真盔甲/防御/难度/buff 管线末端）
     - 每 tick 末 `scoreboard players reset @e Damage` 清零
  2. **`LivingEntity#damage()` Mixin 彻底失效**（颠覆 §6 旧建议）——CTT 玩家的原版 `damage()` 几乎不会被调用
  3. 修订后的正确采集路径：**Mixin `ServerScoreboard#setScore` 筛 `Damage` objective** + **Mixin `PlayerEntity#attack` 做归属配对** + **END_SERVER_TICK 扫 RedHearts 跌穿判定击杀**
  4. "本地存档测试"直接对应 Fabric **integrated server**（客户端+服务端同 JVM），所以采用**单 jar + 双 entrypoint（client + main）+ `environment: "*"`**，未来真需要专用服务器不用重新打包
- **本次变更（6.0.0 仅脚手架）**：
  - `gradle.properties`: `mod_version` 从 `5.3.4 → 6.0.0`（手动跨大版本，后续构建仍走 `X.Y.Z → X.Y.(Z+1)` 规则，下次构建变 `6.0.1`）
  - `fabric.mod.json`:
    - `environment` 从 `"client" → "*"`
    - 新增 `"main"` entrypoint → `com.ctt.healthdisplay.server.CttStatsServer`
    - 原 `"client"` / `"modmenu"` 入口不变；Mixin 仍声明 `environment: "client"`，专用服务器加载时不会混入 HUD Mixin
  - 新增文件 `src/main/java/com/ctt/healthdisplay/server/CttStatsServer.java`：**空壳** `ModInitializer`，仅在 `onInitialize` 打一行 `[CTT Stats v6.0.0] server entrypoint loaded (skeleton; collection pipeline TBD).` 的日志，保证 6.0.0 在本地存档和任何服务端都能正常加载不报错
  - 新增文档章节 `MAP_DATAPACK_ANALYSIS.md §10`：完整记录护甲+伤害函数的最终结论，给出方案 C（Mixin `ServerScoreboard#setScore` + 攻击者归属）的细节、tick 窗口时序、归属算法、里程碑列表、对 §6 的正式勘误
- **v6 里程碑节奏（计划）**：
  - **v6.0.x**：服务端采集链路 + 本地存档验证（Scoreboard Mixin、attack 归属、Stage 追踪、PersistentState、日志对照）
  - **v6.1.x**：CustomPayload 同步 + 队友 HUD 两行扩展（当局 / 全局）
  - **v6.2.x**：K 键表格面板（总表 + 分关表 + 离线标签）
  - **v6.3.x**：治疗统计 + 整体打磨
- **未改动**：v5.3.3 的所有客户端 HUD 行为 0 变化；玩家只会看到 F3 日志里多一行"server entrypoint loaded"
- **涉及文件**：`gradle.properties`、`src/main/resources/fabric.mod.json`、`src/main/java/com/ctt/healthdisplay/server/CttStatsServer.java`（新）、`MAP_DATAPACK_ANALYSIS.md`（追加 §10）、`FEATURES.md`（本条）
- **产出**：`cake-tower-hud-plus-6.0.0.jar`（骨架版，无新用户可见功能；下次构建会 bump 到 `6.0.1`）

### 2026-04-20 · v5.3.3 · 队友血量 HUD 把"自己"也纳入显示

- **需求**：原"队友血量面板"只画其他玩家，自己的条只在左上角主 HUD 里出现。用户希望在队友面板里也能看到自己那一行（头像 / 名字 / HP 条 / Lives），和真队友并排对照 HP 百分比和剩余命数。
- **诊断**：`HealthData.parseTeamBar` 有一条 `if (!name.equals(selfName))` 过滤把自己从 `teammates` 列表剔除，是为了避免和左上角主血条信息冗余。现在用户明确要这份冗余。
- **方案**：
  - `TeammateData` 新增 `public final boolean isSelf` 字段。构造时由 `name.equals(selfName)` 结果传入。
  - `parseTeamBar` 去掉那条过滤，所有玩家都加入 `teammates`，`isSelf` 只对本客户端玩家为 true。
  - `HealthData.update()` 在团队 bar 排序段做两段稳定排序：先按 `lives` 降序保留旧行为，再按 `isSelf` 降序把自己强制提首位。这样打开 HUD 第一行一定是自己，下面才是真队友按 lives 排。
  - `HealthBarRenderer.renderTeammates` 绘制名字时 `mate.isSelf ? TEXT_GOLD : TEXT_WHITE`；新增常量 `TEXT_GOLD = 0xFFFFAA00`（§6 gold），和真队友（白）一眼区分。
- **没动的地方（安全性证明）**：
  - `TeammateWorldRenderer.renderHealthBar`（玩家头顶 3D 条）有 `if (player == MinecraftClient.getInstance().player) return;`，**不会**给自己画头顶条，即使 self 进了 `teammateMap`。
  - `TeammateHealthMixin.findTeammate` 通过 `teammateMap` 决定是否取消 vanilla 名牌，但 Minecraft 本身从不渲染玩家自己的名牌，自己进 map 也不会触发异常行为。
  - 只在"进入带团队 bossbar 的关卡"时才显示面板 —— 自己入列后，大厅 / 单人场景面板仍然不会出现（由 `!data.teammates.isEmpty()` 配合 `teammates.clear()` 天然满足）。
- **涉及文件**：`HealthData.java`（`TeammateData` 加字段、`parseTeamBar` 去过滤、`update()` 加二次排序）、`HealthBarRenderer.java`（`TEXT_GOLD` 常量 + 名字条件染色）。
- **产出**：`cake-tower-hud-plus-5.3.3.jar`。

### 2026-04-20 · v5.3.2 · 怪物头顶血条接入队友「栈式溢出多色」显示

- **需求**：怪物的 `hp` 可能被数据包叠加到 `> maxHP`（例如坦克类吸收层、吸血加成、BUFF 过量回血），此时条应当像队友 HUD 那样**按层叠显示多色**，而不是卡在满格绿色。
- **诊断**：原实现 `MobHealthData.getPercent()` 对 `Math.round((float) hp * 100 / maxHP)` 外层做了 `Math.min(100, …)` 的 clamp，溢出的部分被直接吞掉；`TeammateWorldRenderer.renderMobHealthBar` 拿 `pct` 去查 `getMobBarColor` 只有红/橙/黄/绿 4 档，也无法表达"第 2 层 / 第 3 层 …"。
- **方案**：**不动 `getPercent()` 的 clamp**（HUD 的其它地方仍把它当"进度 0~100"用），而是在 `renderMobHealthBar` 绘条的分支里加一道**溢出判断**，与队友（`renderHealthBar`）**共用同一套 `OVERFLOW_COLORS` 常量与算法**：
  - `hp ≤ maxHP` 原生档：保留旧的 `getMobBarColor(pct)` 红/橙/黄/绿渐变，外观零变化（用户不会察觉）。
  - `hp > maxHP` 溢出档：
    - `topIdx = (hp-1) / maxHP`：当前是第几层（0 基，溢出时 ≥1）；
    - `hpInTop = hp - topIdx * maxHP`：顶层还剩多少 HP；
    - `topFill = hpInTop/maxHP * barW`：顶层在条上的宽度；
    - 顶层颜色 `OVERFLOW_COLORS[topIdx]` 从左填到 `topFill`；
    - 下层颜色 `OVERFLOW_COLORS[topIdx-1]` 铺满 `topFill ~ barW` 的剩余条身（让玩家看到"上一层满格背底 + 当前层从左累积"）。
  - `OVERFLOW_COLORS` 6 档：红 / 橙 / 黄 / 绿 / 蓝 / 紫（与队友条完全一致，≥7 倍封顶在紫色）。
- **alpha 策略继续沿用 v5.1.7 规则**：`fillAlpha = targetted ? 255 : 200`，与非溢出档行为对齐。
- **行为对照**：

| 场景 | v5.3.1 | v5.3.2 |
|---|---|---|
| 普通怪物 `hp ≤ maxHP` | 红/橙/黄/绿渐变 | 红/橙/黄/绿渐变（零改动） |
| 怪物 `hp = maxHP + 1` ~ `2·maxHP` | 满格绿（信息丢失） | 底铺红 + 顶填橙 |
| 怪物 `hp = 2·maxHP + 1` ~ `3·maxHP` | 满格绿 | 底铺橙 + 顶填黄 |
| 怪物 `hp ≥ 6·maxHP` | 满格绿 | 底铺蓝 + 顶填紫（封顶） |
| `hp/maxHP` 数字文本 | 超额数字仍如实显示 | 同上 |

- **涉及文件**：`TeammateWorldRenderer.renderMobHealthBar`（单一分支替换）。
- **产出**：`cake-tower-hud-plus-5.3.2.jar`。

### 2026-04-20 · v5.3.1 · 癫狂状态蓝量/动量/鲜血/金币条「sticky 不闪烁」

- **问题**：玩家陷入 `Madness` 状态（`/trigger ViewStats` 可见的负面 buff）时，游戏把**个人 bossbar 文本**在"HP 数值文本"与"装饰性文本"间高速翻页伪装 —— 这本该让 bossbar 整体隐藏，但 HUD 一端实测每帧之间 `hasBossBarData` 在 `true/false` 之间跳，导致蓝量条 / 动量条 / 鲜血条 / 金币数字**交替消失出现 = 闪烁**；闪烁帧里数值是对的，只是"一闪即隐"让信息不可读。
- **根因定位**：
  1. `HealthData.parseBossBarData()` 每帧一开头就把 `mana / maxMana / hasManaField / blood / maxBlood / hasBlood / velocity / maxVelocity / hasVelocity / coins` **全部清零**，等后续流程在 bossbar 里 `find` 成功时再赋值。
  2. 癫狂装饰帧里 bossbar 文本根本没有 `(Mana x/y)` / `(Blood x/y)` / `(Velocity x/y)` / `(Coins x)` 任何一个，于是整帧这些字段都是 0 + `has*Field = false`。
  3. `HealthData.hasMana()` 的判定 `return hasBossBarData && hasManaField;` 直接随 `hasBossBarData` 起伏 → 蓝条当帧消失；动量 / 鲜血条由同样的机制决定。
- **方案（sticky 设计）**：保留 HP 层的 bossbar 严格刷新不变，只让右侧栏字段"没刷到就保持"：

| 改动点 | 旧行为 | 新行为 |
|---|---|---|
| `parseBossBarData` 开头 | 每帧强清右侧栏 10 个字段 + HP 相关 | 只清 HP 相关 (`hasBossBarData` / `allHearts` / `maxHP` 等) |
| `parsePersonalBar` 开头 | 不额外清零 | 新增：先清零右侧栏 10 个字段，再走 10 条 `find` 覆盖（职业切换 Joey→其它时 `hasBlood` 会正确变 false，不会卡） |
| `update()` 在 `parseBossBarData` 之后 | 无 | 新增守卫：`statsDataRef.isGameNotStarted()` 为 true（大厅/切场景，`/trigger ViewStats` 回收到 "cannot trigger"）时**强制清零**右侧栏字段，避免上局残留显示到大厅 |
| `hasMana()` | `return hasBossBarData && hasManaField;` | `return hasManaField;`（去掉 `hasBossBarData` 依赖，这才是蓝条不闪的**核心**） |

- **结果**：
  - 癫狂装饰帧里 `parsePersonalBar` 不会被调用（无 HP 段），右侧栏字段保留上一次正常解析的值；
  - HP 条本身仍由 v5.1.18 的 `/trigger ViewStats` 心数之和 fallback 托底；
  - 蓝量 / 动量 / 鲜血 / 金币全部**稳定显示上一帧正确值**，不闪烁、不跳回 0；
  - 退回大厅 / `ViewStats` 返回"无数据"时，守卫把这些字段清零，不会看到上局蓝条残留。
- **实现讨论（Q/A 保留给后续可能的回溯）**：
  - Q：职业切换会不会让 `hasBlood` 卡在 `true`？A：不会 —— `parsePersonalBar` 开头已经先清零，再走 `find` 覆盖，新职业没 Blood 段就自然为 false。
  - Q：Lives 要不要也做 sticky？A：不需要，Lives 读自计分板 `Lives` 目标，天生稳定，不受 bossbar 文本影响。
  - Q：要不要给 sticky 帧加视觉提示（半透明 / ❄ 图标）？A：按用户要求**不加**，要的就是"癫狂期数据无缝保持"的观感。
- **涉及文件**：`HealthData.java`（`parseBossBarData` 头部清零裁剪、`parsePersonalBar` 头部新增清零、`update()` 新增 gameNotStarted 守卫、`hasMana()` 简化）。
- **产出**：`cake-tower-hud-plus-5.3.1.jar`。

### 2026-04-20 · v5.3.0 · 版本号跃迁

- **改动**：手动将 `gradle.properties` 的 `mod_version` 从 `5.2.3`（待构建号）直接跃迁到 `5.3.0`，作为 5.3 次版本系列的起点。
- **动机**：5.2 系列累计了怪物头顶血量三档（v5.2.1）、果冻斯旺动量值显示（v5.2.2）等功能扩展，已达成 5.2 分支的阶段性完备，正式进入 5.3 阶段。
- **产出**：`cake-tower-hud-plus-5.3.0.jar`（构建后 `gradle.properties` 被自增到 `5.3.1` 以备下次）。
- **代码行为**：本次跃迁**无任何功能/逻辑改动**，仅版本号迁移。
- **涉及文件**：`gradle.properties`（`mod_version` 改 `5.2.3 → 5.3.0`）。

### 2026-04-20 · v5.2.2 · 果冻斯旺 (ClassPassive=14) 动量值显示

- **需求**：角色"果冻斯旺"（Swan Jelly，`ClassPassive=14`）的 bossbar 里多出一段 `(Velocity X/6)`（中文 `(动量 X/6)`），对应玩家当前的"墙跳积累动量"数值。希望把这个数字画在 HUD 顶行 —— 原本 `✦mana/maxMana` 的位置上。
- **bossbar 结构核对**（来自 `datapacks/.../misc/bossbars/p{1..10}_bossbar.mcfunction`）：
  ```
  <Name> (HP a/b) (Lives l) (Stamina s/ms) (Velocity <CP14_Speed>/6) (Coins c)
  ```
  - 最大值硬编码字面量 `/6`（不是 scoreboard），范围 0~6；
  - 中文翻译 `" (Velocity "` → `"(动量 "`（见 `resources/assets/ctt_lang/lang/zh_cn.json:20144`）；
  - 注意他**没有 Mana 段，但有 Stamina 段** —— 而现有 `MANA_PATTERN` 合并了 `Stamina/体力`，所以数据层会把 stamina 值当 mana 灌进去、右下蓝条其实是"体力条"。历史行为。
- **图标选型**：直接复用地图资源包 `resources/assets/minecraft/textures/item/air_dash.png`（16×16 红色双箭头，是果冻斯旺"空中冲刺"的物品贴图，语义正好对应"动量/冲劲"）。拷到 mod 内 `textures/custom/velocity_icon.png`，**保持原色**（渲染前 `RenderSystem.setShaderColor(1,1,1,1)` 中和，不做染色）。
  - 期间 v5.2.2-WIP 也试过 `⚡` 字符 + 粉色文字与 `jelly_man_passive.png`（16×96 动画 6 帧），最终按用户选择敲定为静态 `air_dash`。
- **改动文件**：
  - `health/HealthData.java`：
    - 新增字段 `int velocity / int maxVelocity / boolean hasVelocity`；
    - 新增 `VELOCITY_PATTERN = \((?:Velocity|动量)\s*(-?\d+)/(-?\d+)\)`（与 `BLOOD_PATTERN` 同风格，双语 + 负数兜底）；
    - `parseBossBarData` 里把三字段清零；
    - `parsePersonalBar` 在 blood 解析之后追加 velocity 解析分支。
  - `hud/HealthBarRenderer.java`：
    - 常量：`VELOCITY_ICON_TEXTURE`（`ctt-health-display:textures/custom/velocity_icon.png`）、`VELOCITY_ICON_SIZE = 9`、`VELOCITY_ICON_SRC = 16`、`TEXT_PINK = 0xFFFF55FF`（light_purple，对齐 bossbar 原生颜色）；
    - `drawManaText` 右上角分两条路径：
      - `!hasVelocity`：保持原 `✦mana/maxMana`（AQUA）；
      - `hasVelocity`：计算 `totalW = ICON(9) + 1 + textWidth("X/Y")`，右对齐；先 `context.draw()` → `setShaderColor(1,1,1,1)` → `drawTexture(layer, tex, iconX, textY-1, 0, 0, 9, 9, 16, 16, 16, 16)` 把 16×16 贴图缩到 9×9 画出来（保持原色），再在图标右侧写粉色 `velocity/maxVelocity`。
    - 下方蓝条（Stamina 冒充 Mana）的行为**未改**，继续作为体力条显示。
  - 资源：`src/main/resources/assets/ctt-health-display/textures/custom/velocity_icon.png`（16×16 静态 PNG，直接复制自地图资源包）。
- **行为对比**：
  | 分支 | 左侧 (hp 条上方) | 右侧 (mana 条上方) |
  |---|---|---|
  | 普通职业 | `❤lives  $coins`（居中） | `✦mana/maxMana` AQUA |
  | Joey (hasBlood=true) | 同上 | `✦mana/maxMana` AQUA（下面条换鲜血，上面文字不变） |
  | Swan Jelly (hasVelocity=true) | 同上 | `<air_dash icon>  velocity/maxVelocity` PINK |
- **没改颜色 / 没改下方条**：按用户口径 —— 图标保持 air_dash 原色，下方那条继续是"Stamina 冒充 Mana"的蓝色体力条，不动。
- **产出**：`cake-tower-hud-plus-5.2.2.jar`（构建后 `gradle.properties` 自增到 `5.2.3`）。
- **涉及文件**：`health/HealthData.java`、`hud/HealthBarRenderer.java`、`resources/assets/ctt-health-display/textures/custom/velocity_icon.png`（新增）。

### 2026-04-20 · v5.2.1 · 怪物头顶血量改为三档开关

- **需求**：怪物头顶血量原本只有 `开 / 关` 两档，战斗混乱时满屏都是 CTT 血条，视觉吵。需求加一档「显示最近」，只画 bossbar 锁定（`targetted=true`）的那只，其他同名 mob 退回 vanilla 名牌。
- **三档语义**：
  - **全部显示 (ALL / `mobHeadHPMode=0`)**：所有 `mobHealthMap` 里的 mob 都画 CTT 3D 条；匹配到的 vanilla 名牌全部 cancel。行为与 v5.2.0 的「开」一致。
  - **显示最近 (NEAREST / `mobHeadHPMode=1`)**：只有 `data.targetted == true`（即 `CttHealthDisplay.updateMobTracking` 当帧挑中的、离玩家最近的那一只）会画 CTT 条 + cancel vanilla；其他同名 mob **不画 CTT 条**，vanilla 名牌照常显示。`updateMobTracking` 仍正常跑（保持数据预热，切档即可用）。
  - **关 (OFF / `mobHeadHPMode=2`)**：完全退回 vanilla。`updateMobTracking` 也停跑，`TeammateWorldRenderer.renderMobHealthBar` 直接 return，`TeammateHealthMixin` 不 cancel 任何 vanilla 标签。
- **改动文件**：
  - `ModConfig`：
    - 新增 `public int mobHeadHPMode = 0;` 和 `MOB_HP_MODE_ALL/NEAREST/OFF = 0/1/2` 常量；
    - 新增 `isMobHeadHPEnabled()` / `isMobHeadHPNearestOnly()` 两个语义 getter；
    - 保留旧字段 `showMobHeadHP`，`load()` 后调 `migrate()`：若旧 JSON `showMobHeadHP=false` 而新字段仍是默认 0 → 翻译为 `MOB_HP_MODE_OFF`，这样 5.2.0 用户升级后「关闭怪物血条」的意图不会被静默重置成「全部显示」。
  - `CttHealthDisplay` (line 109)：`if (showMobHeadHP)` → `if (isMobHeadHPEnabled())`。ALL / NEAREST 两档都要喂 `updateMobTracking`；OFF 完全跳过（省一点开销，也让 `mobHealthMap` 自然空掉）。
  - `TeammateWorldRenderer.renderMobHealthBar`：
    - 原本 `if (!showMobHeadHP) return;` → `if (!isMobHeadHPEnabled()) return;`
    - **新增 NEAREST 过滤**：`if (isMobHeadHPNearestOnly() && !data.targetted) return;`，在拿到 `MobHealthData` 之后、真正渲染之前拦下非 targetted 的同名 mob。
  - `TeammateHealthMixin.ctt_onLabel`：找到匹配的 `MobHealthData` 后不再无脑 `ci.cancel()`，而是 `boolean shouldCancel = !isMobHeadHPNearestOnly() || matched.targetted;`。NEAREST 档下只有 targetted 那只的 vanilla 标签才会被拦截，其余 mob 的名牌保留原样。
  - `ConfigScreen`：`boolean showMobHP` → `int mobHPMode`，按钮点击改为 `(mobHPMode + 1) % 3`；`mobHPBtnText()` 用 `switch` 输出三档本地化文本；`saveAndClose()` 写回 `mobHeadHPMode`，并顺带把 `showMobHeadHP` 同步成 `mode != OFF`（给还在读旧字段的第三方脚本一点兼容）。
  - `lang/zh_cn.json` / `lang/en_us.json`：新增三个值 key：
    - `mob_hp_mode.all` = `§a全部显示` / `§aAll`
    - `mob_hp_mode.nearest` = `§e显示最近` / `§eNearest`
    - `mob_hp_mode.off` = `§c关` / `§cOff`
- **行为对比**（两只同名怪物 A、B，B 更近，bossbar 锁 B）：
  | 档位 | A 头顶 | B 头顶 |
  |---|---|---|
  | 全部显示 | CTT 条（无 ▶ 前缀，`targetted=false`）| CTT 条 + 黄色 ▶ 前缀 |
  | 显示最近 | vanilla 原版名牌（不画 CTT）| CTT 条 + 黄色 ▶ 前缀 |
  | 关 | vanilla 原版名牌 | vanilla 原版名牌 |
- **兼容性**：旧 config 里只要没写 `mobHeadHPMode` 字段，就按 `showMobHeadHP` 自动迁移。未来可直接删 `showMobHeadHP`（再次读旧 config 时需要额外兜底逻辑，届时再动）。
- **产出**：`cake-tower-hud-plus-5.2.1.jar`，构建后 `gradle.properties` 自增到 `5.2.2`。
- **涉及文件**：`config/ModConfig.java`、`config/ConfigScreen.java`、`CttHealthDisplay.java`、`hud/TeammateWorldRenderer.java`、`mixin/TeammateHealthMixin.java`、`resources/assets/ctt-health-display/lang/{zh_cn,en_us}.json`。

### 2026-04-19 · v5.2.0 · 版本号跃迁

- **改动**：手动将 `gradle.properties` 的 `mod_version` 从 `5.1.22`（待构建号）直接跃迁到 `5.2.0`，作为 5.2 次版本系列的起点。
- **动机**：5.1 系列累计了 Joey 鲜血条（v5.1.19–v5.1.21）、癫狂 HP 显示 fallback（v5.1.16–v5.1.17）、`TogglePartyBossbar` 节流 + 熔断（v5.1.13）等结构级改动，已达成 5.1 分支的功能完备性，正式进入 5.2 阶段。
- **产出**：`cake-tower-hud-plus-5.2.0.jar`（构建后 `gradle.properties` 被自增补到 `5.2.1` 以备下次）。
- **代码行为**：本次跃迁**无任何功能/逻辑改动**，仅版本号迁移。
- **涉及文件**：`gradle.properties`（`mod_version` 改 `5.1.22 → 5.2.0`）。

### 2026-04-19 · v5.1.21 · Joey 鲜血条支持中文 bossbar

- **问题**：v5.1.19/5.1.20 的 `BLOOD_PATTERN` 只匹配英文 `Blood`。客户端切到简体中文后，数据包 translate key `" (Blood "` 经 zh_cn 资源包翻译为 `"(鲜血"`，导致整段变成 `(鲜血 100/100)`，正则匹配失败 → `hasBlood=false` → 右下仍是蓝色法力条。
- **修复**：`BLOOD_PATTERN` 补 `鲜血` 关键字，改为 `\((?:Blood|鲜血)\s*(-?\d+)/(-?\d+)\)`。与 `HP_PATTERN` / `MANA_PATTERN` / `COINS_PATTERN` 等既有多语言策略保持一致。
- **已确认 zh_cn 映射**（`resources/assets/ctt_lang/lang/zh_cn.json`）：
  - `" (Blood "` → `"(鲜血"`
  - `" Blood"` → `" 鲜血"`（结尾空格前缀翻译，另一种上下文）
- **生效条件**：客户端语言切换后 bossbar 按 translate 键重新本地化，无需重启服务器/数据包。打开 Joey 职业、游戏内切中/英文，右下条都能正确显示深红鲜血条，条内读数同步切换。
- **涉及文件**：`src/main/java/com/ctt/healthdisplay/health/HealthData.java`（`BLOOD_PATTERN` 扩展中文匹配）。

### 2026-04-19 · v5.1.20 · 修复 Joey 鲜血条条内数字叠字（`85/5 0/0`）

- **问题**：v5.1.19 实装 Joey 鲜血条后，`renderBloodBar` 先在条中央画了 `blood/maxBlood`（白描边），随后调用 `drawManaText`；但 `drawManaText` 结尾又会把 `mana/maxMana` 以相同位置画一次，于是截图里出现了 `85/5` 叠在 `0/0` 上的乱码。
- **修复**：`drawManaText` 末尾的"条中央 mana/maxMana 标签"加 `if (!data.hasBlood)` 守卫。Joey 时跳过条内 Mana 文字（由 `renderBloodBar` 负责画 Blood 文字），上方 ✦<mana>/<maxMana> 行照旧。
- **回归影响**：
  - 非 Joey：行为完全不变（分支走 else，原样画 `mana/maxMana` 条中央文字）。
  - Joey：条中央只剩 Blood 文字，不再叠字。
- **涉及文件**：`src/main/java/com/ctt/healthdisplay/hud/HealthBarRenderer.java`（`drawManaText` 条内数字绘制加守卫）。

### 2026-04-19 · v5.1.19 · 新增 Joey（吸血鬼 · ClassPassive=5）鲜血值支持

- **背景**：数据包 `p1_bossbar ~ p10_bossbar` 的个人 bossbar 对 `ClassPassive=5`（Vampire / Joey）玩家的标题会**多追加一段** `(Blood <current>/<max>)`（dark_red 色），代表该职业核心资源"鲜血值"。class_select 初始化时 `Blood=100, MaxBlood=100`，同时会给 Joey `MaxMana -10`，法力基本用不上。
- **改动**：
  - **`HealthData`**
    - 新增公共字段：`int blood`, `int maxBlood`, `boolean hasBlood`。
    - 新增正则：`BLOOD_PATTERN = \(Blood\s*(-?\d+)/(-?\d+)\)`（兼顾负值/零值的写入）。
    - `parseBossBarData` 在清零列表里加上 `blood / maxBlood / hasBlood`。
    - `parsePersonalBar` 里紧跟 Mana 段之后匹配 Blood 段：有就置 `hasBlood=true` 并赋值。
  - **`HealthBarRenderer`**
    - 新增 4 档深红颜色常量 `BLOOD_FULL/HIGH/MID/LOW`（`#B00020 → #4A0009` 渐变），明显压暗于主 HP 条的亮红 `#E84040`，一眼可区分。
    - 新增私有枚举 `BarKind { HEALTH, MANA, BLOOD }` 替换原 `drawBar` 的 `boolean isHealth`，避免再加布尔参数。
    - 新增 `getBloodColor(int percent)`，按 25/50/75 分段在四档深红间插值。
    - 新增 `renderBloodBar(...)`：画深红条 + 条内居中 `blood/maxBlood` 白字描边，然后调用原有 `drawManaText` 把 Lives / Coins / ✦Mana/MaxMana 文字一起绘出——**上方 ✦ 法力值仍然展示**，只有下方槽位的条从蓝色换成深红。
    - `render` 里在 `data.isPersonalAvailable()` 分支加**优先判断**：`hasBlood` 优先走 `renderBloodBar`，否则回落 `hasMana()` 走 `renderManaBar`，不影响其他职业。
- **行为**：
  - 当前角色是 Joey（bossbar 标题含 `(Blood x/y)`）：
    - 左侧 HP 条 **不变**（仍是红系多层心）。
    - 右侧位置**上方文字不变**（`❤<lives>  🪙<coins>  ✦<mana>/<maxMana>`）。
    - 右侧位置**下方条替换**为深红鲜血条，条内文字是 `blood/maxBlood`（白色描边），占比使用 `blood / maxBlood`。
  - 其他职业：完全无变化。
- **验证路径**：
  - 非 Joey 玩家：bossbar 标题没有 `(Blood …)` 段 → `hasBlood=false` → 原法力条。
  - Joey `Blood=100, MaxBlood=100` → 深红满格，条内显示 `100/100`。
  - Joey 把鲜血喂给队友后 `Blood=0` → 条空，条内显示 `0/100`，上方 ✦ 值仍是真实法力。
  - `MaxBlood` 若异常为 0/负数（不应发生）：条长 0，数值文字仍展示原值，不会除零。
- **涉及文件**：
  - `src/main/java/com/ctt/healthdisplay/health/HealthData.java`
  - `src/main/java/com/ctt/healthdisplay/hud/HealthBarRenderer.java`

### 2026-04-19 · v5.1.18 · 法力值图标 ✦ 从右移到数值左边

- **改动**：主 HUD 右侧法力条上方的文本从 `15/15✦` 改为 `✦15/15`，图标放到数字前。
- **动机**：与左侧 `❤<数值>`（Lives）以及中间 `🪙<数值>`（Coins）的"图标 + 数值"排列保持一致，视觉对齐更统一。
- **实现**：`HealthBarRenderer.drawManaText` 把拼接顺序从 `data.mana + "/" + data.maxMana + "\u2726"` 改为 `"\u2726" + data.mana + "/" + data.maxMana`。
- **锚点不变**：整段文本仍然**右对齐到法力条右边缘**（`rightEdge - manaW`），所以 `✦` 会出现在数值左侧、但整块文字靠右；和旧版排版相比只是 `✦` 位置左右互换，不会抢占左侧 Lives 的空间。
- **Coins 居中锚点**：仍按 `(livesEndX + manaX - totalW) / 2` 计算，`manaX` 来自新拼接后的整块宽度，结果天然自适应。
- **涉及文件**：`HealthBarRenderer.java`（`drawManaText` 一行顺序调整）。

### 2026-04-19 · v5.1.17 · 修复「游戏未开始时仍显示 1/1 幻觉血条」

- **问题**：v5.1.16 引入的 stats fallback 会在 bossbar 解析失败时用 `StatsData` 的心数据合成 HP。但 `StatsData` 的心数据是持久字段——只要本 session 里跑过一次 `/trigger ViewStats`，就会一直保留（redHearts=1 等旧值），即便玩家回到大厅 / 场景重置 / 游戏未开始，fallback 也会继续用那份陈旧数据显示 `1/1` 的幻觉血条。
- **修复**（双保险）：
  1. **`StatsData.processMessage` 的"无法触发"分支里一并清心数据**：`redHearts=soulHearts=blackHearts=blueHearts=0`, `hasHeartData=false`, `lastCaptureCompleteTimeMs=0`。这样只要玩家在大厅 / 游戏未开始时触发过一次 ViewStats，之后 fallback 立刻关闭。
  2. **`HealthData.update` 的 fallback 分支加三道守卫**：
     - `!statsDataRef.isGameNotStarted()` —— 最近一次 ViewStats 不是"无法触发"状态；
     - `statsDataRef.getLastCaptureCompleteTimeMs() > 0` —— 本 session 真正成功 capture 过；
     - `now - lastCaptureCompleteTimeMs < STATS_FALLBACK_TTL_MS`（30s）—— 数据在新鲜期内。
  - `StatsData` 新增 `lastCaptureCompleteTimeMs` 字段，在 "Game time" 分支（capture 成功完成）记录当前挂钟时间；提供 `getLastCaptureCompleteTimeMs()` getter。
  - `HealthData` 新增常量 `STATS_FALLBACK_TTL_MS = 30_000`。
- **触发覆盖度**：
  - 新 session 从未 ViewStats：`lastCaptureCompleteTimeMs=0` → fallback 不激活（√）。
  - 大厅 / 游戏未开始（收到"无法触发"）：`gameNotStarted=true` 且字段被清 → fallback 不激活（√）。
  - 刚 ViewStats 完 / 癫狂正进行中：TTL 内 fallback 仍生效 → 血条正常（√）。
  - 玩家挂机 30s+ 不刷新：fallback 自动失效，避免陈旧数据 → 如需恢复可按 J 或等待自动触发（√）。
- **行为对照**：

| 场景 | v5.1.16 | v5.1.17 |
|---|---|---|
| 新启动客户端从未 ViewStats | 幻觉血条（若有残留）| 不显示（`lastCaptureCompleteTimeMs=0`）|
| 游戏未开始（大厅 / "无法触发"）| 显示残留 1/1 | 不显示（心数据已清）|
| 正常游戏 | 正常显示 | 正常显示 |
| 癫狂中 30s 内 | fallback 显示 | fallback 显示 |
| 癫狂超 30s 未刷新 stats | 继续用旧值 | fallback 自动关闭；触发回血 / 掉血会自动刷新 ViewStats 恢复 |

- **涉及文件**：`StatsData.java`（"无法触发"分支清字段 + "Game time"分支记时间戳 + 新 getter）、`HealthData.java`（fallback 分支三道守卫 + TTL 常量）。

### 2026-04-19 · v5.1.16 · 癫狂状态 HP 显示 fallback：用 ViewStats 心数据合成 HP

- **问题背景**（v5.1.13 遗留项）：癫狂效果期间个人 bossbar 文字被替换成装饰内容，`HealthData.parseBossBarData` 里 `HP_PATTERN` 匹配不到，`hasBossBarData = false`，HUD 里的 `hp/maxHP` 显示会归 0 甚至整个 HUD 隐藏（`isPersonalAvailable = false`），体验极差。
- **修复**：引入"stats 心数据 fallback"通道。
  - `HealthData` 新增 `hasStatsFallback` 字段 + `setStatsData(StatsData)` 注入点。
  - `CttHealthDisplay.onInitializeClient` 初始化时调用 `healthData.setStatsData(statsData)`，把 mod 里唯一的 `StatsData` 实例交给 `HealthData` 持有弱引用。
  - `HealthData.update()` 在 `parseBossBarData` 之后如果判定 bossbar 拿不到 HP 且 `statsData.hasHeartData()` 为 true：
    - `maxHP = StatsData.redHearts` —— 红心上限作为"满血基线"。
    - `allHearts = red + soul + black + blue` —— 所有心种累加作为"当前总 HP"。
    - `healthPercent` 同步按 `allHearts/maxHP*100` 重算，驱动显示和颜色判断。
    - 置 `hasStatsFallback = true`，`hasRawValues()` / `isPersonalAvailable()` 随之返回 true，HUD 恢复正常渲染。
- **后续刷新**：`/trigger ViewStats` 本身会被癫狂触发的多次数据变化自动唤起（v5.1.14+ 的 bigHeal/hpDropped 机制），所以 stats 心数据会以 15s 内的常规频率更新；如果用户手动 `/trigger ViewStats`（按 J 切换面板触发 capture）也能立即刷新。
- **行为对照**：

| 场景 | v5.1.15 及之前 | v5.1.16 |
|---|---|---|
| 正常游戏 | bossbar 解析 HP，正常显示 | 行为不变（`hasBossBarData=true` 优先）|
| 癫狂 + 还没跑过 ViewStats | HUD 数值 0 或整条消失 | 同（无心数据，fallback 不激活）|
| 癫狂 + 近期跑过 ViewStats | HUD 数值 0 或整条消失 | **显示 `(red+soul+black+blue)/red`**，红/黄/黑/蓝四层仍然正常叠色 |
| 癫狂结束 bossbar 恢复 | HUD 突然正常 | HUD 从 fallback 平滑切回 bossbar 真值（有 lerp）|
| 心数据过时（癫狂久了 stats 没更新）| N/A | 会显示上一次 stats 的旧值；建议搭配 v5.1.14 的瞬间治疗自动刷新或手动 ViewStats 维持新鲜度 |

- **不受影响**：`hasMana()` 仍然依赖 `hasBossBarData && hasManaField`，fallback 期间法力条不显示（因为 ViewStats 不包含当前法力值）；金币 / 生命数同理。
- **唯一涉及文件**：`HealthData.java`（字段 + `update` 分支 + 两个 getter）、`CttHealthDisplay.java`（init 时 `setStatsData`）。

### 2026-04-19 · v5.1.14 / v5.1.15 · 新增「瞬间大额治疗 ≥ 25 自动刷 ViewStats」触发条件

- **背景**：喝治疗药水、接受队友大规模治疗、拾起强力补给等瞬间回血事件会在一帧内把 `allHearts` 抬高一大截，但之前自动 `ViewStats` 刷新机制只对"掉血"敏感，回血之后的新 maxHP / 红心上限 / Soul / Black / Blue 层细节要等下一次 15s 定时刷新或者下一次掉血才能同步到面板，中间属性栏会显示旧值。
- **新增触发**（`CttHealthDisplay.onInitializeClient` 的 autoRefresh 分支）：
  - `heartsDelta = currentAllHearts - prevAllHearts`（prev 有效时）。
  - 触发条件由原来的 `hpDropped`（delta < 0）扩展为 `hpDropped || bigHeal`，其中 **`bigHeal = (heartsDelta >= BIG_HEAL_THRESHOLD)`**。
  - `BIG_HEAL_THRESHOLD = 25`（常量）。选 25 是为了只对"一次性大额治疗"敏感；Minecraft 的再生 / 饱食度慢速回血每 tick 增量远低于该阈值，不会误触发。
- **版本演进**：
  - v5.1.14：首次实现，阈值判定用 `delta > 25`（严格大于）。
  - **v5.1.15**：按用户要求把判定改为 **`delta >= 25`**（大于等于），让正好回血 25 点的药水 / 技能也能触发刷新。
- **保留的节流保护**：
  - `MIN_REFRESH_INTERVAL_TICKS = 20`（1s）冷却仍然生效——即便 bigHeal 和 hpDropped 同时命中（例如喝药回血后立刻挨打），1 秒内只会刷一次 ViewStats，不会轰炸命令。
  - `statsData.isCapturing()` 期间不重复触发。
- **行为对照**：

| 事件 | v5.1.13 及之前 | v5.1.15 |
|---|---|---|
| 挨打（delta < 0）| 立即刷 | 立即刷（一致） |
| 喝 30 HP 治疗药水 | 等下一次定时刷（最多 15s 延迟）| **立即刷**（delta=30 ≥ 25）|
| 被队友治疗 25 点 | 等定时 | **立即刷**（delta=25 ≥ 25）|
| 再生 II 每 tick ~0.5 心 | 等定时 | 等定时（delta 远 < 25）|
| 饱食度慢回 | 等定时 | 等定时 |

- **唯一涉及文件**：`CttHealthDisplay.java`（常量 `BIG_HEAL_THRESHOLD` + `tickCounter` 分支的触发判断）。

### 2026-04-19 · v5.1.13 · `TogglePartyBossbar` 自动唤出加节流 + 熔断，修复癫狂效果下被服务端踢出

- **问题背景**：地图会给玩家加一个名为「癫狂」的负面效果（可以通过 `/trigger ViewStats` 看到），效果期间：
  - 个人 bossbar 仍会出现但通常还可识别；
  - **队友 bossbar 的文字被服务端替换成装饰性内容**（例如"我只有一个想法，战……"这种），既不匹配 `HP_PATTERN` 也不匹配 `TEAM_PLAYER_PATTERN`，因此客户端把 `hasTeamBossBar` 长期判为 `false`；
  - HUD 读到的数值每 tick 变一次，血条抖动刷新；
  - 自动 `TogglePartyBossbar` 因为"有个人 bar 却没 team bar"一直命中 → 每隔 4 秒触发一次，累积数分钟后（例如用户实测的 `(x41)`）被服务端命令频率限制踢出。
- **修复**（只改 `CttHealthDisplay.maybeAutoTogglePartyBossbar` 及其相关常量）：
  1. **普通冷却**：`PARTY_BOSSBAR_TOGGLE_COOLDOWN_TICKS` 由 80 → **60 tick（3 秒）**，与用户要求一致——两次自动 toggle 之间最少间隔 3 秒。
  2. **熔断计数**：每成功发出一次 toggle，`partyBossbarFailedToggles++`；一旦 `HealthData.hasTeamBossBar` 为 true（说明 toggle 真起作用了），立刻归零。
  3. **长冷却**：计数累积到 `MAX_FAILED_PARTY_BOSSBAR_TOGGLES = 5` 次仍未出现 team bar → 进入 `PARTY_BOSSBAR_LONG_COOLDOWN_TICKS = 12000 tick ≈ 10 分钟` 的长冷却，期间**完全不再发送命令**。退出长冷却后如果问题还在（仍然 no team bar），计数重新开始累积，再尝试一轮 5 次，之后再熔 10 分钟；最坏情况 10 分钟最多 5 条命令，远低于服务端 rate limit。
  4. **熔断解除**：长冷却期间一旦 team bar 重新可见（癫狂效果结束 / 玩家切场景 / 手动 toggle），`partyBossbarFailsafeUntilTick` 被重置为 -1，计数归零，回到正常节流。
- **行为对照**：

| 场景 | v5.1.12 | v5.1.13 |
|---|---|---|
| 正常游戏（team bar 可见） | 不发命令 | 不发命令（一致） |
| 正常游戏（只有个人 bar） | 每 4s 发 1 条 | 每 3s 发 1 条，最多 5 次就停 10 分钟 |
| 癫狂持续 10 分钟 | 约 150 条 TogglePartyBossbar → 被踢出 | 最多 5 条，不会被踢 |
| 癫狂结束 team bar 自然出现 | toggle 继续触发（依赖上次冷却） | 立刻停（因为 `hasTeamBossBar=true`），熔断计数归零 |
- **唯一涉及文件**：`CttHealthDisplay.java`（常量块 + `maybeAutoTogglePartyBossbar` 方法）。

> **待观察 / 未处理**：癫狂效果期间 HUD 数值抖动刷新的问题（`parseBossBarData` 每 tick 从随机文字里解析不到值）暂时没处理，如果用户需要"癫狂期间保留上一次可信值"，可以在后续版本加一个"解析失败保留 lastKnown"的策略。

### 2026-04-19 · v5.1.12 · 个人 HP 条新增「maxHP ≤ 50 时锁 50 基线」分档

- **改动**：在 `HealthBarRenderer.renderHealthBar` 里抽出 `computeEffectiveMax(maxHP)`，把原来的 `Math.max(100, maxHP)` 二档规则扩展成三档。
- **新分档**（个人 HP 条的分母 = 100% 对应的 HP 值）：

| maxHP 区间 | effectiveMax（分母） | 说明 |
|---|---|---|
| `maxHP ≤ 50`（含 0 / 负数）| **50** | 新增档位。低血时每点 HP 占更长像素，血条更灵敏 |
| `50 < maxHP ≤ 100` | 100 | 同 v5.1.1 规则，低于 100 的常规状态 |
| `maxHP > 100` | `maxHP`（真实值）| 正常状态，按原始分母显示 |

- **红 / 灵魂 / 黑 / 蓝 四层彩色条**：`drawLayeredBar` 的所有层共用同一个 `effectiveMax` 分母，新分档自动覆盖全部四层，不用额外改。
- **边界行为**：
  - `maxHP = 0` / 负数 → 走最低档 50，避免除零；`allHearts=0` 显示 0%，`allHearts=50` 显示 100%。
  - `maxHP = 50` 精确命中最低档（100% = 50）；`maxHP = 51` 跳到中档（100% = 100）；`maxHP = 101` 跳到真实档。
- **不影响**：mana 条、队友条（`drawTeammateBar` 走 overflow 多段着色逻辑，和分母锁定无关）、怪物头顶血条（有真实 hp/maxHP，本身不存在分母锁定需求）。
- **唯一涉及文件**：`HealthBarRenderer.java`（第 95 行 + 新增 `computeEffectiveMax` 工具方法）。

### 2026-04-19 · v5.1.11 · 修复「打残 A → 切打 B 时 A 的血条血量变多」数据错位 bug

- **问题重现**：场上两只同名怪物 A / B，玩家把 A 打到残血（例如 10/100），切去打 B；按预期 A 的 3D 血条应该保持残血 10 不动，实测却变多甚至刷回满血。
- **根因**：`CttHealthDisplay.updateMobTracking` 每 10 tick（`MOB_SCAN_INTERVAL`）会扫整世界活体实体，对同名且尚未在 map 的 UUID 调 `map.put(uuid, new MobHealthData(..., bar.hp, bar.maxHP, ...))`。这里 `bar.hp/maxHP` 来自服务端当前选中的那一只 mob（= `sort=nearest`），**并不对应那些刚被扫到的其他同名实体**。后果：
  - B 第一次进视野时会被用 A 的当前血量（比如残血 10）初始化；
  - 更糟的是 A 如果被 `removeIf` 短暂删过（区块加载抖动、实体临时查不到），下次 scan 会用**当前 bossbar 的 hp**（此时可能是 B 的 100）重新初始化 A → A 的显示从 10 飙到 100。
- **修复**（重写 `updateMobTracking`）：
  1. **移除"扫全世界 + 无脑初始化"分支**。只有当某只同名 mob 被选为当前帧 closest（= bar 所对应的那只）时，才 `map.put` 或 update 它的 HP —— 此时 `bar.hp` 正好对应它，数据可信。
  2. **closest 选取改成从世界实体里找距离最近的同名活体**（对齐服务端 `sort=nearest` 规则），不再局限在"已进过 map 的"。首次被 targetted 的 mob 会在这一刻被正确写入 map。
  3. **非 closest 的同名 mob**：map 里已有记录就保持冻结血量只翻转 `targetted=false`；map 里没有就**不入库**，不显示 3D 血条（诚实做法，我们确实没它的真实 HP 数据）。
  4. **`removeIf` 放宽**：查不到实体时不立即删，给 `MOB_ENTRY_STALE_TICKS = 100`（5s）缓冲，避免区块加载抖动导致"记录丢 → 重新初始化错位"的死循环；明确死亡 / 被移除的实体继续立即清理。
  5. 删除不再用到的 `MOB_SCAN_INTERVAL` 常量。
- **影响**：
  - 打残 A 切打 B，A 的 3D 血条稳定保持残血直到 A 真的被打死或长时间不可见（>5s）才消失。
  - 新同名 mob 进入视野的瞬间**不会**直接显示错误血条，要等它第一次被服务端 bossbar 指中（= 玩家靠近它一帧）才出血条；这符合"有可信数据才显示"的原则。
  - Targetted 选中逻辑、颜色读取（`entity.getDisplayName().getStyle().getColor()`）、黄色 `▶` 前缀行为不变。

### 2026-04-19 · v5.1.10 · 怪物名字完全对齐 MC 原版玩家名牌（穿墙 + 清晰黑底）

- **v5.1.9 的回退原因**：换成 `POLYGON_OFFSET` 后名字不再穿墙（墙后看不到），且丢掉 SEE_THROUGH 带来的黑底衬托后白字在木门 / 石墙等复杂背景上反而更糊。
- **找到真正的原理**（读了 `EntityRenderer.renderLabelIfPresent` 的源码）：MC 玩家名牌之所以**"既清晰又穿墙"**，是因为：
  1. `textLayer = SEE_THROUGH`（字体穿墙显示）；
  2. `shadow = false`（不靠阴影）；
  3. **主动传入 `backgroundColor = (int)(accessibility.textBackgroundOpacity × 255) << 24`**（默认 25% 的半透明黑底板），让字体永远衬在一块统一的深色块上 → 任何背景下都锐利可读。
  4. 玩家名字字色其实是半透明白 `0x20FFFFFF`（alpha ≈ 12.5%），但因为有黑底衬托观感清晰。
- **我们之前搞错了两件事**：
  - 误以为"SEE_THROUGH 层自带黑底" —— 实际上黑底是通过第 9 参数 `backgroundColor` 显式注入的；传 `0` 就没有背景。
  - 以为黑底是脏感元凶 —— 实际上丢了黑底才糊，字色全不透明白 (`0xFFFFFFFF`) 没黑底衬反而和背景互相抢眼。
- **本版改动**（仍然只改 `TeammateWorldRenderer.renderMobHealthBar`）：
  - `textLayer` 从 `POLYGON_OFFSET` 恢复成 **`SEE_THROUGH`**（恢复穿墙）。
  - 给 header 行所有 4 段文字（`▶`、`nameStr`、`suffix`、血条内 `hp/maxHP`）统一传 `backgroundColor = (int)(options.getTextBackgroundOpacity(0.25f) * 255.0f) << 24`（= 与玩家名牌一致、受 accessibility 设置联动）。
  - `shadow=false` 保留（黑底已经承担对比度职责，阴影多余）。
  - 字色保留：`▶ = 0xFFFFFF55` 黄、`nameStr = data.nameColor` 默认白 / 特殊色、`suffix / hp = 0xFFFFFFFF` 白 —— 这些**全不透明**的字色叠在半透明黑底上比原版玩家名牌的 `0x20` 更醒目，Targetted 辨识度不会因此变弱。
- **结论对照**：

| 特性 | v5.1.8（SEE_THROUGH 无 bg） | v5.1.9（POLYGON_OFFSET 无 bg） | v5.1.10（SEE_THROUGH + bg） |
|---|---|---|---|
| 穿墙显示 | 是 | **否** | **是** |
| 背景黑底衬字 | 否 | 否 | **是**（25% alpha，跟玩家名牌同款）|
| 白字在木门 / 亮墙上清晰度 | 差（灰糊）| 差（白字直接砸在亮背景）| 好（黑底衬托）|
| 与 accessibility 设置联动 | 否 | 否 | **是**（opacity 可 0~100% 自调）|
| Targetted 可见度来源 | 阴影 + 条身 + ▶ | 条身 + ▶ | 黑底 + 条身 + ▶（黄）|

### 2026-04-19 · v5.1.9 · 怪物名字去灰白脏感：换层 + 去阴影（已在 v5.1.10 回退）

- **问题**：v5.1.8 游戏里实测，`▶ 测试假人` 这一类白字名字看起来偏灰，名字后面还能看到一块明显的深色矩形背景（即便场景是高光明亮的木门 / 石砖），和原版干净的玩家名牌观感差距明显。
- **诊断**：
  - 这块"黑背景"并非我们自绘的 `RenderLayer.getTextBackgroundSeeThrough()`（它只画在血条那一行 `barTop±` 区域，不会延伸到 header 的名字行）。
  - 真正来源：`TextRenderer.TextLayerType.SEE_THROUGH` 文字层自带一块**全局半透明黑底**，由 `accessibility.textBackgroundOpacity` 控制，默认 25%，mod 侧无法按实例关闭。
  - 白字 + 文字阴影（偏移 1 像素的暗色副本）叠在这块半透明黑底上，会被肉眼感知为"脏灰"。
- **修复**（只改 `TeammateWorldRenderer.renderMobHealthBar` 一处）：
  - 文字层 `SEE_THROUGH` → **`POLYGON_OFFSET`**（这是 MC 给玩家名牌用的层，**不带自动黑底**，仍然有轻微的深度偏移避免和实体模型 z-fighting）。
  - 所有 header 行文字（`▶`、`nameStr`、`suffix`、血条里的 `hp/maxHP`）一律 `shadow = false`，去掉让白字变灰的阴影副本。
  - Targetted 的识别度由 **黄色 `▶` + 条身 `fillAlpha=255`** 继续支撑，够用。
- **保留不动**：
  - 血条背景板（`bgAlpha=100`）和填充（`fillAlpha=targetted ? 255 : 200`）的 VertexConsumer 绘制，这部分和"文字观感"无关，不受影响。
  - 非 Targetted 同名 mob 仍然无视觉降级（只是 `▶` 不出现、`fillAlpha=200`）。
- **副作用**：`POLYGON_OFFSET` 层在被方块完全挡住时名字不再透视显示（SEE_THROUGH 会）——但头顶血条本来就在 mob 头顶，绝大多数场景不会被挡，和"名字干净"相比可接受。

### 2026-04-19 · v5.1.8 · ▶ 前缀独立用黄色高亮绘制

- 改动：之前 `▶ name` 被拼成一串整体用 `data.nameColor` 上色，如果怪物名字是白色的，▶ 也跟着白，识别度不够。
- 现在 Targetted 的名字行分两段绘制：
  - `"▶ "` 用黄色 `0xFFFFFF55`（§e 原版 yellow 色号），独立 `textRenderer.draw`；
  - 紧跟其后绘制 `nameStr`，颜色沿用 `data.nameColor`（白 / 特殊怪物色）。
  - 位置计算：`arrow` 从 `-halfW` 开始画，名字从 `-halfW + textRenderer.getWidth("▶ ")` 开始，两段紧贴无缝。
- 非 Targetted 仍然只画 `nameStr`，单段绘制，行为与 v5.1.7 一致。
- 唯一涉及文件：`TeammateWorldRenderer.renderMobHealthBar` 的 header 行绘制分支。

### 2026-04-19 · v5.1.7 · Targetted 背景板回到半透明（保留"原版高光"质感）

- **问题**：v5.1.6 把 Targetted 那一只的背景板 `alpha` 从 100 抬到 220，在实际游戏里半透明效果几乎消失，血条背后整块变成刺眼的黑条，视觉上比原版血条还糟。
- **修复**：把背景板 `alpha` 锁回 `100`（所有 mob 都用同一值，和 v5.1.2 之前一致）。
- **保留的可见度增强**：Targetted 那一只的**条身 fill 仍然 `alpha 255`**（对比非 Targetted 的 `alpha 200` 略亮），**所有文字仍然带阴影**，以及 `▶` 前缀 —— 这三点就够让选中项从一众同名 mob 中视觉跳出，而又不破坏血条的"高光"感。
- **实现细节**（`TeammateWorldRenderer.renderMobHealthBar`）：
  - `int bgAlpha = 100;`（不再按 `targetted` 分叉）
  - `int fillAlpha = targetted ? 255 : 200;`（保留）
  - `boolean textShadow = targetted;`（保留）
  - `▶` 前缀依旧挂在 `targetted` 上。

### 2026-04-19 · v5.1.6 · 怪物血条「颜色源改正 + Targetted 可见度提升 + ▶ 前缀」

#### 背景：读完地图数据包后修正了三条诊断

1. **颜色源的真相**：地图数据包里所有怪物 bossbar 的 `name` 首段都是 `{"selector":"@s"}`，**没有 `color` 字段**。怪物名字的颜色完全取决于实体自身的 `CustomName` NBT Style。绝大多数普通怪物不染色（Style.color = null）；只有特殊 boss / Daredevil 之类才带色。
2. **服务端"距离选中"规则**（`misc/bossbars/p{N}_bossbar.mcfunction` 41-44 行）：每帧从玩家位置 `sort=nearest` 抓最近的 `tag=E` 实体贴 `HealthBar{ID}` 标签；30 方块内有 `tag=Boss` 就强制换成 Boss。**没有任何"最近 vs 次近差距阈值"**，就是粗暴取最近。
3. **v5.1.2 漏洞**：`TeammateHealthMixin.findMobWithHealth` 在 renderer 回调里每帧 `mob.nameColor = labelText.getStyle().getColor()`，无值就写白；紧接着 renderer 里又 `if (nameColor == WHITE) nameColor = YELLOW`。两步叠加导致"所有怪物一律强转黄色"。

#### 本版改动（文件级）

| 文件 | 改动 |
|---|---|
| `MobHealthData.java` | `boolean confirmed` → `boolean targetted`（语义：是否为当前帧距离最近匹配到 bossbar 的那只）。`nameColor` 默认 `0xFFFFFFFF`（白）不变。 |
| `CttHealthDisplay.updateMobTracking` | 选定 `closestUUID` 后，**从 `closestEntity.getDisplayName().getStyle().getColor()` 读取颜色**写入 `MobHealthData.nameColor`（没 color 就是 WHITE）；置 `d.targetted=true`；同名其他实体 `d.targetted=false`。每帧刷新。 |
| `TeammateHealthMixin.findMobWithHealth` | 降级为纯匹配：不再读 `labelText` 的 color，也不再写 `mob.nameColor`。 |
| `TeammateWorldRenderer.renderMobHealthBar` | (1) 删除 `nameColor == 0xFFFFFFFF → 0xFFFFDD44` 的白→黄 fallback，`nameColor` 直接渲染。(2) `◆` 前缀 → `▶` (U+25B6, "瞄准指向"语义)，条件从 `data.confirmed` 改为 `data.targetted`。(3) **Targetted 那一只提升可见度**：血条填充 `alpha 200 → 255`（完全不透明），所有文字 `shadow false → true`（名字 / 后缀 / HP 数字都加阴影）。背景板保持原版 `alpha 100` 半透明不动（v5.1.6 最初抬到 220 但视觉上是纯黑块，v5.1.7 已回退）。非 Targetted 的同名 mob **渲染参数完全不变**（维持 v5.1.2 的半透明外观）。 |

#### 行为对照（之前 → 现在）

| 场景 | v5.1.5 | v5.1.6 |
|---|---|---|
| 普通怪物名字颜色 | 一律黄色（被 fallback 强转） | 白色（默认，尊重 CustomName 无色状态） |
| 特殊染色怪物名字颜色（如 boss NBT 里带 color）| 黄色 | 读到什么色显示什么色 |
| 当前 Targetted 名字前缀 | ◆ | ▶ |
| Targetted 血条在高光背景下 | 半透明容易看不清 | 条身满 alpha + 文字阴影，明显跳出（背景仍保持半透明"高光"质感）|
| 非 Targetted 同名 mob | 显示（可能陈旧 HP） | 同上，外观不变 |
| 距离歧义阈值 | 无 | 依旧无（对齐服务端 `sort=nearest` 算法） |
| 最远匹配距离 | 无 | 依旧无（对齐服务端无上限策略） |

#### 放弃的候选方案（记录以备回溯）

- 距离差阈值（最近 vs 次近差 ≥ 1.5 方块才确认）→ 否决，服务端 `sort=nearest` 本身无此规则，客户端加了反而和服务端选中不一致。
- 32 方块距离上限 → 否决，服务端无距离上限（只有 30 方块 Boss 优先和 20 方块 spectator 撤销，都不影响 per-player 血条选中）。
- 颜色永久锁定（`colorLocked` 字段）→ 否决，每帧从实体 displayName 读取本身就是"染色稳定则颜色稳定"，不需要额外状态。
- 非 Targetted 血条半透明/灰度降级 → 否决，用户要求"不能确认的同名保持不变"。

### 2026-04-19 · v5.1.5 · 回退「怪物计分板探针」+ 否决"多怪物血条/客户端直读计分板"方案

- **回退内容**：移除 v5.1.3 临时加入的 `F6` 探针按键（`probeMobScoresKey`、`runMobScoreProbe`、`truncate`）、相关 Scoreboard/Logger 导入、以及 `lang/*.json` 中的 `probe_mob_scores` 条目。回退后代码面回到 v5.1.2 的稳定状态（怪物血条仍只可靠显示 **Targetted** 那一只）。
- **为什么放弃该方案**（`logs/latest.log` 实测证据）：
  - 探针多次运行结论：**客户端 Scoreboard 根本拿不到 `AllHearts` / `MaxHP` objective**（`Objective missing: AllHearts=false, MaxHP=false`）——它们在服务端数据包里虽然被大量 `scoreboard players operation` 操作，但从未通过 `scoreboard objectives add ... dummy` 显式声明；而 Minecraft 只会把服务端"显式声明 + 加入显示槽 / 对玩家 holder 有条目"的 objective 元数据下发给客户端。
  - 即便未来在数据包里补上声明，`dummy` 类型的 `UUID-holder` 非玩家分数默认也不会主动推送给没有 `holder` 引用它的客户端，会导致首次进入区域时数据空窗。
  - 结论：**纯客户端不存在稳定读取"非玩家实体计分板分数"的路径**。"多怪物同屏 3D 血条"只能退回"bossbar 优先 + 按距离 / 名字模糊匹配"，而这条路的精度上限就是 v5.1.2 的现状——所以本次不再强行扩展，保留现状并把重点放到剩余的视觉修复上（名字颜色、`◆` 前缀、透明背景）。
- **受影响文件**：`CttHealthDisplay.java`（-探针相关代码与导入）、`lang/zh_cn.json` + `lang/en_us.json`（-`probe_mob_scores`）。

### 2026-04-19 · v5.1.3 · 临时「怪物计分板探针」调试键（已在 v5.1.5 回退）

- 新增 `F6` 按键，扫本地所有 `LivingEntity` 对 `AllHearts` / `MaxHP` 的可见性；日志 tag `CTT-MOB-PROBE`，action bar 输出 `CTT Probe: living=N hit=X partial=Y miss=Z`。
- 仅为验证用途，验证结果见上方 v5.1.5 条目；代码已删除，文档保留记录供后续同类方案评估时参考。

### 2026-04-19 · v5.1.2 · 模组描述全量重写

- **`fabric.mod.json → description` 重写**（中英双语、八段式）：按 ①~⑧ 覆盖当前全部功能模块——4 层血量条 / 法力·金币·生命数 / 属性面板 / 队友 2D+3D / 怪物追踪 / 隐藏开关 / 无感 ViewStats 刷新 / 配置持久化。ModMenu 与 Fabric 启动器现在都会显示这段新描述。
- 头部的 "产出 JAR" 同步从 5.1 → 5.1.2。

### 2026-04-19 · 构建流程（v5.1 → v5.2.0 起）

- **版本号自动递增**：`build.gradle` 新增 `tasks.named('remapJar') { doLast { … } }`，remap 完成后自动把 `gradle.properties` 的 `mod_version` 补丁号 +1。
  - 规则：`X.Y` → `X.Y.1`，`X.Y.Z` → `X.Y.(Z+1)`。
  - 仅在 `remapJar` 非 UP-TO-DATE 时触发；源码未改的空构建不会涨版本。
  - 已完成的自增序列：`5.1 → 5.1.1 → … → 5.1.20 → 5.1.21`；手动跃迁 → `5.2.0 → 5.2.1 → 5.2.2`；手动跃迁 → `5.3.0`。
  - **当前已部署版本**：`cake-tower-hud-plus-5.3.0.jar`；下一轮构建将产出 `cake-tower-hud-plus-5.3.1.jar`。

### 2026-04-18 · v5.1（文档同步点）

- **正则全面支持负数**：`HP` / `Lives` / `Mana(当前)` / `Coins` / 队伍玩家项 / 怪物后缀 6 处均改为 `-?\d+`。最大法力保持 `\d+`（协议约定恒 ≥ 0）。
- **主血条 100 基线**：`effectiveMax = max(100, maxHP)` 替换旧的 `max(1, maxHP)`；红 / 魂 / 黑 / 蓝 4 层同分母。消除除零风险，让 `RedHearts = 0 / 负数` 的场景也能正常出图。
- **HP 文本恒显 `值/最大`**：不再在 `maxHP ≤ 0` 时退化为只显示当前值，保留 `5/0`、`-3/-10` 这类直观格式。
- **Coins 图标**：`(Coins 0)` / `(Coins -42)` 现在会正常绘制图标 + 数字；之前会因为 `\d+` 不带符号被漏掉。

---

### 2026-04-18 · v6.2.1 · L8b SUMMON_SHARED（多人召唤物加权均分）

> **问题**：v6.2.0 的 L8_SUMMON_FALLBACK 只在"40m 内恰好 1 人持召唤物"时生效；≥2 人 → 直接下沉 L9 未归属。但多人副本里"几个玩家都带召唤物"是常态，落 L9 等于放弃统计 —— 用户要求改为**按持有数量加权均分嫌疑**。

#### 新层定义

| 层 | 代号 | 触发条件 | 结果 |
|---|---|---|---|
| **L8** | `L8_SUMMON_FALLBACK` | 40m 内 **恰好 1 人** 持召唤物 | 归属给他（原 v6.2.0 行为） |
| **L8b** | `L8B_SUMMON_SHARED` | 40m 内 **≥ 2 人** 持召唤物 | 按每人召唤物**格子数**加权均分 |
| L9 | `L9_NONE` | 40m 内 **0 人** 持召唤物 | 标未归属（与 v6.2.0 一致） |

L8 / L8b 互斥（根据持有人数自动选）。

#### "持有数量" 定义

- **按格子计数，不按物品堆叠**：`PlayerInventoryIndex.summonItemCountOf(uuid)`。
- 主手 + 快捷栏 9 + 主背包 27 共 37 格，每一格如果包含 **至少一个** `kind=summon` 的 `custom_data` 标识 key，计 1。
- 例：
  - 玩家 A 背包有 `skullTome` + `ghastTome` + 另一格 `skullTome` → `count=3`
  - 玩家 B 只有一个 `skullTome` → `count=1`
  - 总分摊权重 A:B = 3:1 → A 得 75%、B 得 25%
- 同一格子里多个标识 key 被重复计数（稳健起见，实际地图不会出现这种情况）。

#### 权重算法

```java
totalCount = Σ holder.summonCount
for each holder:
    share[holder] = holder.summonCount / totalCount
```

#### 结果结构扩展

`AttackerProbe.Result` 新增 `shares: List<Share>` 字段：

```java
public record Share(UUID playerUuid, String playerName, double share, int summonCount) {}
```

L8b 命中时 `shares` 非空、按权重降序排列（同权重按距离 victim 升序）；其它层仍为 null，保持向后兼容。`attackerUuid` 选"权重最高者"，供老路径（统计累加等）无感接入。

#### 聊天广播行为

- **L8**（单人）：`[A#42] FireDMG -30 @ Boss → Kirin0321 L8 sole-summoner d=12.4m count=1`
- **L8b**（多人）：`[A#43] FireDMG -80 @ Boss → KirinA*50% +KirinB*33% +KirinC*17% L8b shared n=3 total=6 [KirinA/50%/c3,KirinB/33%/c2,KirinC/17%/c1]`
  - 玩家名：淡紫色（light_purple）
  - 百分比：深紫色（dark_purple）
  - 超过 3 人时折叠为 `+N人*合计%`

#### 硬/软层约束

L8b 为软层：
- `isHardLayer(L8b) = false`
- 不写入 `VictimLastHitter`（多嫌疑人 ≠ 可信续归属源）
- 不写入 `VictimDamageSourceCache`（同理，防止污染 DoT carry）
- 不写入 `PlayerRecentAttributionLog`（多嫌疑人 ≠ 可靠的 Tier 打分依据）

#### 文件变更

- `PlayerInventoryIndex.java`：
  - `Snapshot` 新增 `summonItemCount` 字段
  - `buildSnapshot` 扫描时统计召唤物格子数（跳过主手已处理的 hotbar slot 避免重复）
  - 新增 `summonItemCountOf(uuid)` 公开 API
- `AttackerProbe.java`：
  - `Layer` enum 新增 `L8B_SUMMON_SHARED`
  - `Result` record 扩展 `shares: List<Share>`（向后兼容构造器保留）
  - 新增 `Share` record
  - `attribute()` 的 L8 逻辑改为 `scanSummonHolders` → 按人数分派 L8 / L8b
  - `describeShares` 紧凑日志格式；`buildChatLine` 特化 L8b 多人展示
  - 计数器新增 `getL8bCount`
- 无需改动 `CttStatsServer` / `DamageProbe` / 其他文件。

### 2026-04-18 · v6.2.0 · 攻击者归属系统全面重构（武器守卫九层栈）

> **背景**：v6.1.1 修掉 DoT 错归属后，仍存在系统性问题 —— 召唤物 / 持续伤害在多人场景下会把"离 victim 近但没有该类型武器"的玩家误判为攻击者。根源是旧栈依赖"位置兜底猜测"（L4b）和"距离最近胜"的近似算法，忽略了"玩家必须实际持有能造该类型伤害的武器"这一铁律。v6.2.0 把这条铁律做成每一层的强制守卫。

#### 设计原则（用户定稿）

- **宁可漏，不可错**：无法确信就标 L9 未归属，拒绝强行分配。
- **每一步都要确保伤害类型正确**：武器守卫 `hasMatchingWeapon(P, T)` 前置到每一层。
- **玩家主手持有伤害的优先级是最高的**：L1 WEAPON_MATCH 放栈顶。
- **召唤物判定依赖背包物品**：只要玩家背包任一格有召唤物即算持有（主手 / 快捷栏 / 主背包 37 格；副手不算，地图 `SelectedItem` 只读主手）。

#### 归属九层栈

| 层 | 代号 | 条件 | 扫描半径 | 守卫 | 硬/软 |
|---|---|---|---|---|---|
| L1 | WEAPON_MATCH | 玩家主手 / 背包 持匹配武器 **且** 近 10s 开过火（PlayerFireLog 右键 OR PlayerHitLog vanilla stat） | 40m | — | 软（本身就是守卫） |
| L2 | STAT_TICK | 本 tick vanilla `damage_dealt` stat 触发 | 无（所有在线） | ✓ | 硬 |
| L3 | MARKER_NEAR | 3m 内 marker/projectile 带 PlayerID | 3m | ✓ | 硬 |
| L4 | MARKER_FAR | 40m 内 marker/projectile 带 PlayerID（30m→40m，召唤物 / 远程法器 marker 漂移半径） | 40m | ✓ | 硬 |
| L5 | STAT_WINDOW | 近 5 tick vanilla `damage_dealt` 回看 | 无 | ✓ | 硬 |
| L6 | FIRE_WINDOW | 近 20 tick 右键开火 + Tier 打分 | 40m | ✓ | 软 |
| L7 | LAST_HITTER | 查 `VictimLastHitter[victim × T]`（20s TTL，所有类型）；元素伤害额外查 `VictimDamageSourceCache`（10s） | — | ✓ | 软 |
| L8 | SUMMON_FALLBACK | 40m 内 **唯一** 持召唤物玩家 → 暂归属；≥2 人 / 0 人直接下沉 L9 | 40m | — | 软 |
| L9 | NONE | 所有层皆挂 → 标未归属 | — | — | — |

**AllDMG 特例**：`DamageShower` 兜底（Pierce_Damage 路径）用 `AllDMG` 伪 objective，跳过武器守卫 —— 此类伤害无法精确定类型，只做粗归属。

#### 武器守卫 `hasMatchingWeapon(P, T)`

1. `WeaponDamageRegistry.weaponsOfType(T)` 查出能造 T 伤害的所有 custom_data key 集合。
2. 查 `PlayerInventoryIndex.get(P)` 快照：
   - `kind = weapon` 的 key：只查**主手**（地图 `SelectedItem` 机制）。
   - `kind = summon` 的 key：查**主手 + 快捷栏 0~8 + 主背包 9~35**（37 格）。
3. 任一交集即 true。

#### 核心组件

| 组件 | 职责 | 刷新 / TTL |
|---|---|---|
| `WeaponDamageRegistry` | 启动加载 `weapon_damage_seed.json`（601 武器 × 9 伤害类型），提供正向 / 反向 / 按 kind 查询 | 启动一次 |
| `PlayerInventoryIndex` | 每 5 tick 全服扫描玩家主手 + 背包 custom_data，快照到 Map&lt;UUID, Snapshot&gt; | 5 tick（0.25s） |
| `VictimLastHitter` | victim × 所有 *DMG 类型（含 AllDMG）最近硬证据归属 | 20s（400t） |
| `VictimDamageSourceCache` | 元素伤害专道 DoT carry（与 v6.0.8 相同） | 10s（200t） |
| `PlayerRecentAttributionLog` | L6 Tier 打分用的"近期归属类型"日志（与 v6.0.9 相同） | — |

#### 与 v6.1.1 的关键差异

| 行为 | v6.1.1 | v6.2.0 |
|---|---|---|
| 层数 | 6 + AllDMG | 9 + AllDMG |
| 武器守卫 | 无 | L2~L7 全覆盖（AllDMG 除外） |
| L4b 贴脸玩家兜底 | 存在（对 carryable 跳过） | **彻底删除**（`AttackerResolver.scanPlayers` API 保留但无调用） |
| marker_far 扫描 | 30m | 40m |
| 续归属覆盖 | 仅元素伤害（`VictimDamageSourceCache`） | 所有 *DMG + AllDMG（`VictimLastHitter`） |
| 召唤物兜底 | 无 | L8 唯一持有者（40m） |
| 计数器 API | `getL1/L2a/L2b/L3/L4/L4b/L4c/L5` | `getL1~L9` + `getLayerCount(Layer)` |

#### 聊天广播行为

`[A#42] FireDMG -20 @ BossZombie → Kirin0321 L1 hand=flamespear fire-age=3t d=2.1m pool=1`

- `layer=L1` 绿色：主手硬匹配，最可信。
- `layer=L7/L8` 金色：续归属 / 召唤物兜底，中等可信。
- `layer=L9` 深红 + `attacker=?`：无法归属，不强分。多人场景下召唤物同时触发会出现这种。

#### 文件变更

- 新增 `server/WeaponDamageRegistry.java`（180 行）
- 新增 `server/PlayerInventoryIndex.java`（200 行）
- 新增 `server/VictimLastHitter.java`（80 行）
- 修改 `server/AttackerProbe.java`：核心重构，Layer enum 重命名 L1~L9，`attribute()` 九层实现
- 修改 `server/AttackerResolver.java`：`MARKER_FAR 30.0 → 40.0`
- 修改 `server/PlayerFireLog.java`：新增 `latestTickOf(uuid, from, to)` API 供 L1 调用
- 修改 `server/CttStatsServer.java`：启动加载 Registry，`END_SERVER_TICK` 注册 `PlayerInventoryIndex.tickRefresh`
- 修改 `scripts/gen_weapon_damage_map.py`：v6.2.0 前置步骤，扩展 `kind` / `summoned_entities` 字段
- 修改 `src/main/resources/weapon_damage_seed.json`：新生成，新增 `kind` / `viaEntities` 字段

---

## v6.3.0 · 伤害分配面板（测试版）

> 在 AttackerProbe 九层归属基础上，把每次归属结果按玩家汇总，HUD 与交互 Screen 两路呈现。
> **定位**：开发 / 测试阶段使用，内容故意设计得详细（硬+分摊分开、L8b×N 计数、层分布），方便验证归属准确性。

### 数据层：`server/PlayerDamageStats.java`

按玩家维护一条 `Entry`：
- `confirmed`：L1~L8 硬/软归属整数伤害累加
- `sharedMilli`：L8b 分摊伤害按 `damage × share × 1000` 的千分位整数累加，snapshot 时 /1000 得到浮点值（保持分摊长期不漂移）
- `events`：被归属事件条数（L8b 每名分摊者各计 1）
- `maxHit`：单次归属最大值（分摊四舍五入后参与比较）
- `l8bEvents`：专门统计 L8b 分摊次数
- `layerCounts[Layer.len]`：按层计数（仅该玩家）

`Snapshot` record 整体不可变，字段：全局 live/frozen、会话起 tick/ms、durationMs、排序后的 PlayerRow 列表、未归属总和与事件数、全局层计数等。

### 累积入口：`AttackerProbe.feedStats(Result, damage)`

`record()` / `recordFromDamageShower()` 末尾统一调用：
- `Layer.L9_NONE` → `addUnattributed(damage)`
- `Layer.L8B_SUMMON_SHARED` → 遍历 `Result.shares`，对每名嫌疑人 `addShared(uuid, name, damage, share)`
- 其他层 + `attackerUuid != null` → `add(uuid, name, damage, layer)`

### 渲染层：`hud/DamagePanelRenderer.java`

统一绘制函数 `drawCore(ctx, tr, snap, x, y, detailed, hoveredButton, interactive)`，被 HUD 回调和 Screen 共同复用。布局：

- **标题栏 (14 px)**：`≡ CTT 伤害分配 · 45.3s · LIVE/FROZEN/IDLE`；右侧 4 按钮 `▶/|| · X · L/U · *`（各 12 px）。
- **汇总行 (12 px)**：`总 xxx  事件 yy  DPS zz  平均 ww  最高 vv`。
- **玩家行（详情 34 px / 紧凑 11 px）**：
  - 第 1 行：玩家名 · 右对齐总值 + 百分比
  - 第 2 行：`硬 xxxx · 分摊 y.y (L8b×n) · 事件 e · 最高 m`
  - 第 3 行：进度条（金色，末端紫色覆盖层表示分摊份额）
- **未归属行**：红色文本 `? 未归属  xxx  p%`，详情模式附事件数。
- **底部**（详情模式）：`L1=n L2=n ... L9=n` 全层计数。

按 `Snapshot.totalAll()` 计算占比。HUD 模式下若从未启用且无数据，完全不渲染，避免挡视野。

### 交互层：`hud/DamagePanelScreen.java`

按 L 时 `client.setScreen(new DamagePanelScreen())` 打开：

- **拖拽**：标题栏（除按钮区域外）按住左键拖动 → 实时更新 `panelX/Y`；松开时把百分比写回 `ModConfig.damagePanelX/Y` 并 `save()`。
- **按钮**：
  - ▶/|| = Start / Stop，**联动 `DamageProbe.startSession/stopSession`**（保持 actionbar 与面板同步）
  - X = Clear（同时重置 session，保证两边数字一致）
  - L/U = Freeze / Unfreeze（暂停写入，不清数据）
  - \* = 详情 / 紧凑模式切换（同步写入 `ModConfig.damagePanelDetailed`）
- **Tooltip**：鼠标悬浮按钮时，光标下方显示文字说明当前点击将触发什么（"停止并冻结"/"开始 / 清零并开启"/…）
- **关闭**：再按 L、ESC、或点击面板以外任意位置。
- **不暂停游戏**：`shouldPause()` 返回 false，战斗中可一边打一边点面板。

### 配置字段（`ModConfig`）

新增 `damagePanelHudVisible / damagePanelX / damagePanelY / damagePanelDetailed`，JSON 持久化。

### 与旧 L 键行为的关系

- v6.0.2 ~ v6.2.2：L 键直接 Start/Stop `DamageProbe` 区间统计，聊天输出文字总结。
- v6.3.0：L 键改为打开 / 关闭面板 Screen。Start/Stop 交给面板 ▶ 按钮，且按钮内部同时触发 `DamageProbe.startSession / stopSession`，行为完全向后兼容（actionbar "统计中" 仍会显示，只是在面板打开时让路）。

### 文件变更

- 新增 `server/PlayerDamageStats.java`（约 220 行）
- 新增 `hud/DamagePanelRenderer.java`（约 300 行）
- 新增 `hud/DamagePanelScreen.java`（约 210 行）
- 修改 `server/AttackerProbe.java`：两个 `record*` 路径末尾调 `feedStats()`；新增 `feedStats()` 与 `trimPlayerLabel()` 辅助
- 修改 `config/ModConfig.java`：四个新字段 + 默认值
- 修改 `CttHealthDisplay.java`：`L` 键改 `handleDmgPanelToggle`（打开 Screen）；HUD 回调末尾调 `DamagePanelRenderer.drawHud()`；actionbar 在 Screen 打开时让路
- 修改 `fabric.mod.json`：`description` 追加 v6.3.0 说明

---

<sub>文档版本：v6.3.0（伤害分配面板）/ v6.2.1（L8b 多人召唤物加权均分）/ v6.2.0（归属系统九层重构，已部署 → 被覆盖）/ v6.1.1（DoT 归属修复）· 最近同步：2026-04-18 · 由仓库代码自动整理而成，功能行为以源码为准。
维护规则：每次改完代码 → `./gradlew build` → 同步更新 FEATURES.md 的「核心功能」「对接协议」「变更记录」「产出清单」。版本号由 `remapJar` 的 `doLast` 自动 +0.0.1，无需手动维护。</sub>
