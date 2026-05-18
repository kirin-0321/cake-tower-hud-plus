**Cake Tower HUD Plus** is a HUD overhaul and optional **server-side combat analytics** mod built for the community map **Cake Team Towers · Chapter 3**. It turns boss-bar text and `/trigger ViewStats` spam into readable bars, panels, and live team stats—without editing the map or needing OP. With v8.4+ on the server, the stats panel is delivered via a direct S2C push and the legacy `/trigger ViewStats` chat-capture path is no longer needed.

> **Map-specific:** designed for *Cake Team Towers Chapter 3*. On other worlds or servers the UI **stays hidden** automatically.

> **专为地图设计：** 仅在 *Cake Team Towers Chapter 3* 上启用，其他世界 / 服务器 UI **自动隐藏**。v8.4+ 起服务端也装本 mod 时，属性面板走服务端直接 push（旁路 `/trigger ViewStats` 聊天捕捉路径）。

---

## Features

### Status & hearts · 状态与生命

- **Custom health bar** with gradient and low-HP warning (flashes below ~25%).
- **All four heart types** (red / soul / black / blue) stacked and updated live—sourced via S2C push on v8.4+ servers, chat-capture fallback otherwise.
- **Mana, coins, lives, momentum** on separate bars (bottom-right).
- **Class-aware UI** (e.g. vampire blood gauge, jelly dash icon) when you switch class.

> 自定义渐变血条 + 低血量闪烁；红 / 灵魂 / 黑 / 蓝四类心一并叠加显示（v8.4+ 服务端模式由 S2C 推送驱动，否则走聊天捕捉兜底）；魔力 / 金币 / 生命 / 势能独立条；按职业动态切换 UI（吸血鬼血槽、果冻冲刺图标等）。

### Team & mobs · 队友与怪物

- **Left roster**: skin, name, HP bar, lives; sorted by lives; **you're always top row (gold name)**. Draggable; horizontal or vertical layout.
- **3D overhead HP** for teammates (see through walls).
- **3D overhead HP** for every boss-bar mob; **closest target** gets a yellow **▶** marker; enraged/elite suffixes preserved; mob name colors match the map.
- **Teammate 4-color hearts (v8.4+, server-side mod required)** — side-panel roster, 3D overhead bars, and vanilla nameplates **all render the same red/soul/black/blue layered overlay** as your own main bar. Falls back to single-color OVERFLOW slots when the server doesn't have this mod. Client toggle `showTeammateLayeredHearts` (default on).

> 左侧队友栏：头像 / 名字 / HP 条 / 命数，按命数排序，自己永远在顶部（金色名字）；可拖拽、横竖排切换。3D 头顶血量队友穿墙可见，怪物头顶 HP 显示，最近目标自动标 ▶；狂暴 / 精英后缀和颜色与地图一致。**v8.4+ 起**（需服务端也装本 mod）：侧栏队友 / 3D 头顶血条 / vanilla 名牌三处队友血条**全部升级到与玩家自己主血条相同的 4 色叠加渲染**（红 / 灵魂 / 黑 / 蓝），服务端没装时自动回落到单色 OVERFLOW；客户端开关 `showTeammateLayeredHearts`（默认开）。

### Mob HP source · 怪物血量获取 (v8.3+)

The 3D overhead HP for hostiles is fed by **two complementary paths**, picked automatically per-frame depending on what's installed.

- **Server-authoritative pipeline (preferred, v8.3+)** — when this mod is also installed on the server, `MobHealthBroadcaster` runs on `END_SERVER_TICK` at **4 Hz (every 5 ticks)**. For each online player it scans a **48-block** radius for live `LivingEntity` (players / armor stands excluded), reads `RedHearts` / `MaxHP` directly off the scoreboard via `ScoreboardReader`, sorts by squared distance, and pushes the closest **32** as a `MobHealthPayload` (UUID / name / hp / maxHp / nameColor / `targetted`). The nearest entry is flagged `targetted=true`, which is what drives the yellow **▶** marker. Snapshots are diffed against the previous send so steady-state traffic is ~0; on the client `ClientMobHealthCache.isFresh()` trusts the snapshot for **5 s**. This path **does not depend on vanilla boss bars**, so it fixes the long-standing "Boss champion present → other mobs' HP glitches" bug end-to-end.
- **Client-side bossbar approximation (fallback, legacy)** — when the server doesn't have this mod (vanilla / datapack-only / older versions), `isFresh()` stays `false` and the client falls back to its original `updateMobTracking` path: parse vanilla boss bar lines, then for each visible same-name mob copy the bar's HP / maxHp over and pick the closest as the ▶ target. Limited by the vanilla rule that only one boss bar can show at a time, so when a Boss champion takes the bar slot, normal mobs lose their HP source until it releases. Behaviorally identical to v8.2 — install on server side to opt into the authoritative path.

> v8.3+ 起，敌人 3D 头顶血条由**两条互补管道**供数据，每帧根据"服务端是否也装了本 mod"自动选择。
> - **服务端权威推送（首选 · v8.3+）**：服务端 `MobHealthBroadcaster` 挂在 `END_SERVER_TICK` 上 **4 Hz（每 5 tick）** 触发；以每个在线玩家为中心 **48 格半径**扫活 `LivingEntity`（排除玩家和盔甲架），通过 `ScoreboardReader` 直接读 `RedHearts` / `MaxHP` scoreboard，按距离平方排序取最近 **32** 条打成 `MobHealthPayload`（UUID / 名字 / 当前血 / 最大血 / 名字颜色 / `targetted`）下发；最近那一只标 `targetted=true`，就是头顶 ▶ 黄色箭头的来源。差量发送，稳态网络流量约 0；客户端 `ClientMobHealthCache.isFresh()` 在 **5 秒**内信任该快照。**完全绕开 vanilla bossbar**，从根上修掉 "Boss 冠军存在时其他怪头顶血条错位"的老问题。
> - **客户端 bossbar 近似（兜底 · 旧路径）**：服务端没装本 mod（纯 vanilla / 纯数据包 / 老版本）时 `isFresh()` 持续为 false，客户端自动回落到老的 `updateMobTracking` —— 解析 vanilla bossbar 行，把里面的 HP / maxHp 抄给场景里同名最近的怪，并选最近一只作为 ▶ 目标。受 vanilla "同一时刻最多一条 boss bar" 限制，Boss 冠军占用 bar 时普通怪会暂时失去血量来源；行为与 v8.2 完全一致 —— 装在服务端即可升级到权威路径。

### Player stats source · 玩家属性数据获取 (v8.4+)

The right-side stats panel (the long *ViewStats* output) is fed by **two complementary paths**, picked automatically depending on whether the server has this mod.

- **Server-authoritative push (preferred, v8.4+)** — when this mod is installed on the server, `PlayerStatsPushBroadcaster` runs on `END_SERVER_TICK` at **1 Hz** (every 20 ticks). `ViewStatsBuilder` walks the scoreboard directly and constructs the full panel in Java (`ViewStatsRegistry.ENTRIES` ~33 standard stat lines + 4 hearts + Cracked/Pink/NegMax + Size/Gravity/SpeedAmplifier + CelestialKarma + Skulls + `STATUS_EFFECTS` 30+ tag-based status lines like Berserk/Burnt/Dizzy/Cursed/...). The result is sent as a `PlayerStatsPayload` (4 heart counts + `List<Text>` lines) per-player. The client's `StatsData.applyServerSnapshot` consumes it directly — **the chat-capture path is fully bypassed** and `/trigger ViewStats` is no longer triggered while the push stays fresh (`ClientStatsPushCache.isFresh()`, **10 s** window). Effective cost: **~0.1 ms/s + ~12 KB/s** for a 4-player party (~30× cheaper than the datapack path). **No anti-cheat kicks** from rapid `/trigger` self-execution. Status-effect text uses `Text.translatable(key)` so the **Cake Team Towers resource pack lang file translates strings like `Berserk` → `癫狂`**, matching datapack chat behavior 1:1.
- **Legacy `/trigger ViewStats` chat capture (fallback)** — when the server doesn't have this mod, `isFresh()` stays `false` and the client auto-falls back to the v8.3.x path: periodically (and on big HP changes) send `/trigger ViewStats`, intercept the resulting chat lines, parse the panel out. The datapack's `function misc/view_stats.mcfunction` is ~0.5–1.5 ms per execution per player on the server side — workable on small teams but anti-cheat sometimes kicks for "too-frequent triggers" at short intervals (the config screen now shows a red warning when set too low). Install this mod on the server side to opt out of the chat-capture path entirely.

> v8.4+ 起，玩家属性面板（右侧"长 ViewStats"那一坨）由**两条互补管道**供数据，按"服务端是否也装本 mod"自动选择。
> - **服务端权威推送（首选 · v8.4+）**：`PlayerStatsPushBroadcaster` 挂在 `END_SERVER_TICK` 上 **1 Hz**（每 20 tick）触发；`ViewStatsBuilder` 直接读 scoreboard 走表（`ViewStatsRegistry.ENTRIES` ~33 条标准属性 + 4 色心 + CrackedHearts/PinkHearts/NegMaxHealth + Size/Gravity/SpeedAmplifier + CelestialKarma + Skulls + `STATUS_EFFECTS` 30+ 个 tag-based 状态如"癫狂 / 燃烧 / 眩晕 / 诅咒 / ..."），用 Java 直接拼 `List<Text>` 推成 `PlayerStatsPayload`（4 心数字 + 行列表）给该玩家。客户端 `StatsData.applyServerSnapshot` 直灌字段，**完全旁路聊天捕捉路径**，刷新窗口（`ClientStatsPushCache.isFresh()`，**10 秒**）内不再自发 `/trigger ViewStats`。4 人队 ~0.1 ms/s + ~12 KB/s（比 datapack 路径快约 30 倍），**彻底消除"高频 /trigger 自发命令被反作弊踢线"**。状态效果用 `Text.translatable(key)` 输出，依赖 **Cake Team Towers 资源包**的 lang 文件把 `Berserk` 翻成 `癫狂`，与 datapack chat 路径行为完全一致。
> - **客户端聊天捕捉（兜底 · 旧路径）**：服务端没装本 mod 时 `isFresh()` 持续为 false，客户端自动回落到 v8.3.x 路径：周期性（+ 大幅血量变化触发）自发 `/trigger ViewStats`，截取地图回显的 chat 行，正则解析出属性面板。datapack 的 `function misc/view_stats.mcfunction` 服务端单次 ~0.5–1.5 ms / 玩家，小队伍能跑，但短间隔有被反作弊踢线的风险（设置界面在间隔过短时已加红色警告）。装在服务端即可升级到推送路径，彻底告别这条 chat 捕捉链。

### Stats & boss bars · 属性与 Boss 栏

- Long **ViewStats** output becomes a **two-column panel** with tooltips preserved. Modes: always on / only when inventory open / hidden. On v8.4+ servers fed by `PlayerStatsPayload` push; otherwise by legacy chat capture (see *Player stats source* above).
- **Independent toggles** for self / party / mob boss bars—**compatible with other boss-bar mods** (still test your pack).
- **Automation**: refresh on damage / big heals; auto periodic `/trigger ViewStats` only used in the **chat-capture fallback** path (v8.4+ server push removes the need entirely). "Your Stats:" header spam swallowed. Optional auto `TogglePartyBossbar` with **circuit breaker** to avoid rate-limit kicks.

> 冗长的 `ViewStats` 输出变成双列属性面板（保留 tooltip），三档可见性。v8.4+ 服务端模式由 `PlayerStatsPayload` 推送驱动，否则走旧的聊天捕捉路径（见上面 *Player stats source · 玩家属性数据获取*）。自身 / 队友 / 怪物 boss 栏独立开关，兼容其他 boss 栏 mod。受伤 / 大回血即时刷新；周期性 `/trigger ViewStats` **仅在聊天捕捉兜底路径下**触发（v8.4+ 服务端 push 模式完全不发自发命令）。自动 `TogglePartyBossbar` 带熔断防止被踢。

### Combat analytics — server side (v6+, ⚠ experimental) · 服务端战斗分析（实验性）

> ⚠ **Experimental.** Combat analytics (damage attribution / damage panel L / K-D-A panel N / per-stage chat report) can still misbehave in edge cases. The HUD path itself (health bars, stats panel, teammates, mob HP) is stable.

Install on the **server** (integrated singleplayer counts too). Tested on normal dedicated servers.

- **9 damage types** tracked per player with attribution (Melee / Bullet / Force / Fire / Water / Ice / Dark / Light / Electric)—handles teammates, summons, and DoT-style carryover sensibly.
- **Kills / assists / damage taken**; optional chat broadcasts (configurable).
- **Live lines** on the team panel: damage, taken, kills, assists, **rolling 5s DPS**; modes: off / current stage / session / both.
- **Per-stage chat report (v8.3.9+, subscription-based)**: end-of-stage chat summary no longer floods everyone by default. Opt in per-player via `/ctthd broadcast stage_report on` (or `all on` for all 4 channels); ops can still force-broadcast via `broadcastStageReportInChat` JSON flag.
- **Auto-save**: up to **20 past sessions** on disk; periodic + stage transitions + shutdown.
- **v8 performance**: server-side hot paths fully overhauled—boss-fight TPS recovered to vanilla territory.
- **v8.1 Magum Trials**: per-floor stats inside MT (~30 floors per difficulty), isolated from main-tower stages so same-id floors don't bucket-collide. Toggle: `collectMagumTrials` (default on).

> 装在服务端（集成单机也算）。⚠ **此段功能仍为实验性**：伤害归属、伤害面板 (L)、K-D-A 面板 (N)、每关战绩广播在边缘场景仍可能出错；HUD 本体（血条 / 属性面板 / 队友 / 怪物血量）已稳定。9 类伤害分类归属（近战 / 子弹 / 冲击 / 火 / 水 / 冰 / 暗 / 光 / 电），处理队友 / 召唤物 / DoT；击杀 / 助攻 / 承伤一并统计，可开关聊天广播。队友面板嵌入式 KPI 行（4 段开关），含 5s 滚动 DPS。**v8.3.9+ 起每关战绩聊天广播改为 per-player 订阅制**：默认不再刷给所有玩家，玩家自己执行 `/ctthd broadcast stage_report on`（或 `all on` 一键全订阅）即可订阅；OP 仍可改 `broadcastStageReportInChat` JSON 字段强制全员广播。最多保留 20 局历史到磁盘，按周期 / 切关 / 关服自动落盘。**v8 起服务端 hot path 全面重构，boss 战 TPS 回到接近原版水平。v8.1 起 Magum Trials 副本内逐层独立成桶（每难度约 30 层），与大厅塔同 ID 子关命名空间隔离不合桶；开关 `collectMagumTrials`（默认开）。**

### Client-only mode (v7+) · 纯客户端模式

No server mod required. Independently samples `DamageShower` particles to keep stats alive.

- **HUD aggregate row**: ⚔ global / ⚔ current stage / ☠ global / ☠ current stage / ⚡ 5s DPS.
- **Per-stage history** with client-side stage detection (titles + bossbars), **rest rooms excluded** from global totals.
- **Kill counter** based on `ScoreboardHP` drop + entity destroy (works under map's special HP rules).
- **K-key stats table** with full-session totals and per-stage breakdown.
- **Persistence (v8)**: `config/ctt-health-display-cdp.json`, saved on stage change / disconnect / quit; loaded on startup. **[Clear]** button in K-table top bar with confirm dialog.
- **MT limitation**: MT (Magum Trials) per-floor split requires the **server-side** mod. In client-only mode MT runs are bucketed as generic `MINIGAME`.

> 无需服务端 mod。独立采集 `DamageShower` 粒子保留战绩。HUD 顶栏聚合行：⚔ 全局 / ⚔ 本关 / ☠ 全局 / ☠ 本关 / ⚡ 5s DPS。客户端通过 title + bossbar 自动识别关卡，做分关历史，**休息室不计全局**。基于 `ScoreboardHP` 跌零 + entity 销毁双判定的客户端击杀计数（适配地图特殊血量规则）。K 键统计表含总表 + 分关表。**v8 起数据持久化到 `config/ctt-health-display-cdp.json`，切关 / 断线 / 退出自动写盘，启动加载；K 表新增 [清空] 二次确认按钮。MT 分关需要服务端也装本 mod；纯客户端模式下 MT 全程归一个 `MINIGAME` 桶。**

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

> **Version compatibility · 版本兼容性**:
> - **v8.1.x ↔ v8.0.x**: `StagePayload` wire format changed; **both client and server must be on v8.1.x** or newer. Mixing with v8.0.x or earlier will disconnect on packet decode error. Upgrade in lockstep.
> - **v8.4.x ↔ v8.3.x**: new S2C payloads (`PlayerStatsPayload`, `TeamHeartsPayload`) added with **explicit version fields and forward-compatible drain**. Mixing v8.4 server + v8.3 client is **safe** — the v8.3 client simply ignores unknown payloads and falls back to the legacy `/trigger ViewStats` path (no degradation, no kicks). Mixing v8.3 server + v8.4 client is also safe — `ClientStatsPushCache.isFresh()` stays `false` and the v8.4 client falls back to the same legacy path. **For the full feature set (zero-cost stats panel + 4-color teammate hearts), install v8.4.3 on both sides.**
>
> **版本兼容性**：
> - **v8.1.x ↔ v8.0.x 不兼容**：`StagePayload` 协议变化，客户端与服务端必须**同步升级**到 v8.1.x，混用会在解包时被踢线。
> - **v8.4.x ↔ v8.3.x 兼容**：v8.4 新增的 S2C payload（`PlayerStatsPayload` / `TeamHeartsPayload`）带版本字段 + 安全 drain，**混版不断线**。v8.4 服务端 + v8.3 客户端：旧客户端忽略未知 payload，自动走老的 `/trigger ViewStats` 路径，无功能退化；v8.3 服务端 + v8.4 客户端：fresh 窗口持续 false，v8.4 客户端同样回落老路径。**想获得完整功能（零成本属性面板 + 队友 4 色心叠加）需双端都装 v8.4.3。**

---

## Safety & compatibility · 安全性与兼容性

- **No datapack edits**, no OP, no arbitrary save writes for the map itself. On v8.4+ servers with this mod, the stats panel is read directly from the scoreboard (read-only) and pushed via custom S2C payloads — **no commands are sent on the player's behalf**. On servers without this mod the client falls back to the map's own **`/trigger`** flow (read-only, same path vanilla survival uses).
- **Auto-hides** outside the CTT map context so vanilla survival / unrelated servers stay clean.

> 不改地图数据包、不需要 OP、不向地图存档写入。v8.4+ 服务端模式下：属性面板**直接从 scoreboard 只读**（服务端 push 自定义 S2C payload），**不代玩家发送任何命令**。服务端没装本 mod 时回落到地图自带的 `/trigger` 流程（也是只读，与原版生存路径一致）。在非 CTT 地图 / 服务器自动隐藏，不污染原版 / 无关服务器。

---

## Credits

- **Author:** Kirin0321 (麒麟)
- **License:** MIT
