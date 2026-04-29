**Cake Tower HUD Plus** is a HUD overhaul and optional **server-side combat analytics** mod built for the community map **Cake Team Towers · Chapter 3**. It turns boss-bar text and `/trigger ViewStats` spam into readable bars, panels, and live team stats—without editing the map or needing OP.

> **Map-specific:** designed for *Cake Team Towers Chapter 3*. On other worlds or servers the UI **stays hidden** automatically.

> **专为地图设计：** 仅在 *Cake Team Towers Chapter 3* 上启用，其他世界 / 服务器 UI **自动隐藏**。

---

## Features

### Status & hearts · 状态与生命

- **Custom health bar** with gradient and low-HP warning (flashes below ~25%).
- **All four heart types** (red / soul / black / blue) stacked and updated live—no wall of `ViewStats` text.
- **Mana, coins, lives, momentum** on separate bars (bottom-right).
- **Class-aware UI** (e.g. vampire blood gauge, jelly dash icon) when you switch class.

> 自定义渐变血条 + 低血量闪烁；红 / 灵魂 / 黑 / 蓝四类心一并叠加显示；魔力 / 金币 / 生命 / 势能独立条；按职业动态切换 UI（吸血鬼血槽、果冻冲刺图标等）。

### Team & mobs · 队友与怪物

- **Left roster**: skin, name, HP bar, lives; sorted by lives; **you're always top row (gold name)**. Draggable; horizontal or vertical layout.
- **3D overhead HP** for teammates (see through walls).
- **3D overhead HP** for every boss-bar mob; **closest target** gets a yellow **▶** marker; enraged/elite suffixes preserved; mob name colors match the map.

> 左侧队友栏：头像 / 名字 / HP 条 / 命数，按命数排序，自己永远在顶部（金色名字）；可拖拽、横竖排切换。3D 头顶血量队友穿墙可见，怪物头顶 HP 显示，最近目标自动标 ▶；狂暴 / 精英后缀和颜色与地图一致。

### Stats & boss bars · 属性与 Boss 栏

- Long **ViewStats** output becomes a **two-column panel** with tooltips preserved. Modes: always on / only when inventory open / hidden.
- **Independent toggles** for self / party / mob boss bars—**compatible with other boss-bar mods** (still test your pack).
- **Automation**: periodic `ViewStats`, refresh on damage/big heals; "Your Stats:" spam swallowed; optional auto `TogglePartyBossbar` with **circuit breaker** to avoid rate-limit kicks.

> 冗长的 `ViewStats` 输出变成双列属性面板（保留 tooltip），三档可见性。自身 / 队友 / 怪物 boss 栏独立开关，兼容其他 boss 栏 mod。自动周期性触发 `ViewStats`，受伤 / 大回血即时刷新；自动 `TogglePartyBossbar` 带熔断防止被踢。

### Combat analytics — server side (v6+) · 服务端战斗分析

Install on the **server** (integrated singleplayer counts too). Tested on normal dedicated servers.

- **9 damage types** tracked per player with attribution (Melee / Bullet / Force / Fire / Water / Ice / Dark / Light / Electric)—handles teammates, summons, and DoT-style carryover sensibly.
- **Kills / assists / damage taken**; optional chat broadcasts (configurable).
- **Live lines** on the team panel: damage, taken, kills, assists, **rolling 5s DPS**; modes: off / current stage / session / both.
- **Auto-save**: up to **20 past sessions** on disk; periodic + stage transitions + shutdown.
- **v8 performance**: server-side hot paths fully overhauled—boss-fight TPS recovered to vanilla territory.

> 装在服务端（集成单机也算）。9 类伤害分类归属（近战 / 子弹 / 冲击 / 火 / 水 / 冰 / 暗 / 光 / 电），处理队友 / 召唤物 / DoT；击杀 / 助攻 / 承伤一并统计，可开关聊天广播。队友面板嵌入式 KPI 行（4 段开关），含 5s 滚动 DPS。最多保留 20 局历史到磁盘，按周期 / 切关 / 关服自动落盘。**v8 起服务端 hot path 全面重构，boss 战 TPS 回到接近原版水平。**

### Client-only mode (v7+) · 纯客户端模式

No server mod required. Independently samples `DamageShower` particles to keep stats alive.

- **HUD aggregate row**: ⚔ global / ⚔ current stage / ☠ global / ☠ current stage / ⚡ 5s DPS.
- **Per-stage history** with client-side stage detection (titles + bossbars), **rest rooms excluded** from global totals.
- **Kill counter** based on `ScoreboardHP` drop + entity destroy (works under map's special HP rules).
- **K-key stats table** with full-session totals and per-stage breakdown.
- **Persistence (v8)**: `config/ctt-health-display-cdp.json`, saved on stage change / disconnect / quit; loaded on startup. **[Clear]** button in K-table top bar with confirm dialog.

> 无需服务端 mod。独立采集 `DamageShower` 粒子保留战绩。HUD 顶栏聚合行：⚔ 全局 / ⚔ 本关 / ☠ 全局 / ☠ 本关 / ⚡ 5s DPS。客户端通过 title + bossbar 自动识别关卡，做分关历史，**休息室不计全局**。基于 `ScoreboardHP` 跌零 + entity 销毁双判定的客户端击杀计数（适配地图特殊血量规则）。K 键统计表含总表 + 分关表。**v8 起数据持久化到 `config/ctt-health-display-cdp.json`，切关 / 断线 / 退出自动写盘，启动加载；K 表新增 [清空] 二次确认按钮。**

### Config & localization · 配置与本地化

- **ModMenu** (optional but recommended): main HUD screen + **Server config** sub-screen.
- All major HUD panels **draggable**.
- **English** + **简体中文**.

> 推荐配合 ModMenu：主屏 + 服务器配置子屏。所有主面板可拖拽。支持 English / 简体中文。

---

## Default keybinds · 默认按键

| Key | Action · 功能 |
|-----|--------|
| **H** | Toggle entire HUD · 总开关 |
| **J** | Cycle stats panel visibility · 属性面板可见性 |
| **K** | Toggle all boss bars · Boss 栏总开关 |
| **L** | Detailed damage panel *(unbound by default)* · 详细伤害面板（默认未绑） |
| **N** | Stats table — totals + per-stage breakdown + [Clear] · 统计表（总表 / 分关表 / 清空按钮） |

---

## Requirements · 环境要求

- **Minecraft** `1.21.4`
- **Fabric Loader** `≥ 0.16.9`
- **Java** `21+`
- **Fabric API**

**Analytics:** for full server-side stats install this mod on the **server** as well as the client. **Client-only** is fully supported since v7 — you still get the HUD, client-side aggregate damage, kill counter, per-stage history, and persistent stats.

> **战斗分析：** 服务端也装可获完整归属 / 过滤 / 广播。**纯客户端**自 v7 起完整可用——HUD、客户端聚合伤害、击杀计数、分关历史、持久化数据全保留。

---

## Safety & compatibility · 安全性与兼容性

- **No datapack edits**, no OP, no arbitrary save writes for the map itself; uses the map's own **`/trigger`** flow.
- **Auto-hides** outside the CTT map context so vanilla survival / unrelated servers stay clean.

> 不改地图数据包、不需要 OP、不向地图存档写入；走地图自带的 `/trigger` 流程。在非 CTT 地图 / 服务器自动隐藏，不污染原版 / 无关服务器。

---

## Credits

- **Author:** Kirin0321 (麒麟)
- **License:** MIT
