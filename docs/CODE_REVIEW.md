# Cake Tower HUD Plus · 项目代码评审

> **文档定位**：项目的整体性 Code Review。  
> 与现有文档分工：`INTRODUCTION.md` 面向玩家与开发者讲"是什么"，`FEATURES.md` 是按版本编年的迭代台账，`V6_STATS_DESIGN.md` / `V6_STATS_STATUS.md` / `ROADMAP.md` 聚焦 v6 战绩系统的设计与差距，`MAP_DATAPACK_ANALYSIS.md` 解析地图侧契约 —— 本文则**横向**回顾全局架构、模块边界、数据流、代码质量与潜在风险，便于新贡献者 30 分钟内建立心智模型。  
> **截至版本**：v6.6.10（2026-04-26）  
> **作者视角**：只读评审，不改代码。

---

## 目录

1. [总评](#1-总评)
2. [运行栈 & 工程信息](#2-运行栈--工程信息)
3. [包与模块划分](#3-包与模块划分)
4. [核心数据流](#4-核心数据流)
5. [关键设计取舍](#5-关键设计取舍)
6. [代码质量观察](#6-代码质量观察)
7. [优势](#7-优势)
8. [潜在风险与改进建议](#8-潜在风险与改进建议)
9. [模块依赖速查](#9-模块依赖速查)
10. [新人导航：30 分钟入门路径](#10-新人导航30-分钟入门路径)

---

## 1. 总评

| 维度 | 评分（个人） | 备注 |
|------|------------|------|
| 工程结构清晰度 | A | 包按职责分层（client / server / hud / mixin / network / health / config），混合服与单机走同一份代码 |
| 代码可读性 | A | 几乎每个类、每个非平凡分支都有中文 JavaDoc / 行注释，并标注引入版本（`v6.x.x`）及修订原因 |
| 设计文档完备度 | A | 7 份 docs（含 60K 的地图数据包逆向分析），状态文档主动暴露与原设计的差距，工程纪律罕见 |
| 健壮性 / 容错 | B+ | 大量针对地图边缘行为（癫狂、形态切换、剧情 set、回血粒子）的针对性 hack；防御性兜底覆盖到位 |
| 复杂度控制 | B | `AttackerProbe` 九层归属栈 + L9 三子层的策略模式较重，但因每层带"硬证据 vs 软层"二维标记，复杂度还在掌控范围内 |
| 测试覆盖 | C- | 仓库未发现单元测试目录；验证靠服务器实战 + actionbar 对照源 + L 键诊断面板三板斧 |
| 性能 | B+ | 高频路径（伤害事件、tick 扫描）的日志已降级为 DEBUG、武器查询走 5 tick 缓存索引，无显式 hot-path 分析但常识性优化齐全 |

**一句话总评**：一个"地图特化"型 mod 罕见地把工程纪律做到了主流公共 mod 的水准 —— 大量针对地图行为的 hack 都在注释中讲清了"为什么这样"，演化痕迹（v5 → v6 → v6.6）清晰可追，文档体系反映了一个长期负责任地维护的项目。

---

## 2. 运行栈 & 工程信息

| 项 | 值 |
|----|----|
| Minecraft | 1.21.4（Yarn `1.21.4+build.8`） |
| Loader | Fabric Loader ≥ 0.16.9 |
| API | fabric-api `0.113.0+1.21.4` |
| Java | 21（Gradle toolchain 锁定） |
| 构建 | Gradle + Loom 1.9-SNAPSHOT |
| 当前版本 | `mod_version=6.6.10` |
| Mod ID | `ctt-health-display` |
| Archive | `cake-tower-hud-plus-<ver>.jar` |
| License | MIT |
| 必装依赖 | `fabricloader` / `fabric-api` |
| 可选依赖 | ModMenu（仅 compileOnly） |

**Gradle 自动化亮点**：
- `deployToMods` 任务：build 后自动复制到 `mods/`，并智能清理同 mod-id 旧 jar；旧 jar 被运行中游戏占用只警告不让 build 失败（避免跑游戏调试时构建中断）。
- `remapJar.doLast` 自动版本号补丁号 +1：仅在 remapJar 实际产出新文件（非 UP-TO-DATE）时触发，源码未改不会空涨版本号 —— 这是少见的"实用主义自动化"细节。

---

## 3. 包与模块划分

```
com.ctt.healthdisplay/
├── CttHealthDisplay.java         ← 客户端 entrypoint（HUD / 按键 / Auto ViewStats / TogglePartyBossbar）
├── ModMenuIntegration.java       ← ModMenu 入口，桥接 ConfigScreen
│
├── client/
│   └── ClientStatsCache.java     ← 集成服直读 vs 专用服 payload 镜像 的统一接入点
│
├── config/
│   ├── ModConfig.java            ← 客户端 HUD 偏好（位置 / 显隐 / 布局 / 嵌入式 HUD 模式）
│   ├── ServerConfig.java         ← v6.6.4 拆出的服务端策略（聊天广播 / 黑名单 / 数据源切换）
│   ├── ConfigScreen.java         ← 主屏（拖拽预览 + 数值滑块 + 模式切换）
│   └── ServerConfigScreen.java   ← 二级屏（聊天广播开关 + 阈值，专用服只读展示数组型字段）
│
├── health/
│   ├── HealthData.java           ← Boss 栏文本解析 → HP/法力/鲜血/动量/队友/怪物血量
│   ├── StatsData.java            ← /trigger ViewStats 输出捕获器（双语兼容、心数据解析、过期 TTL）
│   └── MobHealthData.java        ← 单条怪物血量记录（含 targetted / lastUpdateTick）
│
├── hud/                          ← 纯客户端渲染层（不直接读 server 类）
│   ├── HealthBarRenderer.java    ← 主 HUD：血条 / 法力 / 鲜血 / 动量 / 金币 / 命数 / 队友面板
│   ├── StatsRenderer.java        ← 属性面板（双列复刻 ViewStats 文本 + tooltip）
│   ├── StatsTableScreen.java     ← N 键：全屏 K/D/A 表格（Tab + 列头排序 + 滚轮）
│   ├── StatsTableData.java       ← 表格数据装配（合并三家 stats 快照为 PlayerRow）
│   ├── DamagePanelRenderer.java  ← L 键：实验性伤害分配面板（含拖拽 + 详略切换 + Stage scope）
│   ├── DamagePanelScreen.java    ← 面板交互模式：自绘 + 拖拽
│   ├── TeammateWorldRenderer.java← 3D 透墙队友/怪物头顶血条
│   ├── TeammateStatsLine.java    ← 队友面板下方"关:/局: ⚔ ⛨ ☠ 🤝"行装配
│   ├── StageNameRegistry.java    ← stage_name_map.json 加载 + 中英定位
│   ├── StageLocation.java        ← Kind/GameOverPhase 枚举 + Snapshot/formatted（仅依赖 ClientStageLocation）
│   └── ClientStageLocation.java  ← StagePayload 接收的 volatile 缓存（网络线程写、渲染线程读）
│
├── mixin/                        ← 客户端 mixin
│   ├── BossBarHudAccessor.java         ← 暴露 BossBarHud.bossBars Map 给解析器
│   ├── BossBarHudRenderMixin.java      ← HEAD/RETURN 临时移除 hiddenBarUUIDs 中的条
│   ├── WorldRendererMixin.java         ← renderEntity TAIL 注入头顶血条
│   ├── TeammateHealthMixin.java        ← 取消 vanilla 队友/怪物上的"数字+心"显示
│   └── VanillaHealthHiderMixin.java    ← 取消玩家自身的 vanilla 血量数字
│
├── network/                      ← 自定义 payload (双端注册)
│   ├── StagePayload.java         ← S2C：玩家当前关卡位置（每 tick diff 推送）
│   └── StatsSnapshotPayload.java ← S2C：1 Hz 全量战绩快照（v2 含 5s DPS 滑窗）
│
└── server/                       ← 服务端入口、探针、累计器、持久化
    ├── CttStatsServer.java       ← ModInitializer：注册 lifecycle / tick / payload，bow objective auto-start
    ├── DamageProbe.java          ← DamageShower text_display 解析 + RedHearts tally + heal/init-hp 过滤
    ├── ScoreDeltaTracker.java    ← *DMG 累计值 → 单次 delta（修复 v6.3.2 前的"巨额假伤害"）
    │
    ├── AttackerProbe.java        ← ⭐ 九层归属栈 (L1~L8 硬归属 + L9 NONE/FILTER/HEAL 三子层)
    ├── AttackerResolver.java     ← Marker / projectile 扫描（PlayerID 反查）
    ├── PlayerHitLog.java         ← vanilla damage_dealt stat 增量日志（L2/L5）
    ├── PlayerFireLog.java        ← carrot_on_a_stick 右键 + bow used 释放窗口（L1/L6/L7）
    ├── PlayerInventoryIndex.java ← 5 tick 一刷的玩家主手 + 背包键集快照（武器守卫 O(1)）
    ├── PlayerRecentAttributionLog.java ← 近期硬归属类型集合（L6 Tier 打分用）
    ├── WeaponDamageRegistry.java ← weapon_damage_seed.json + vanilla 弓弩三叉戟硬编码
    ├── VictimLastHitter.java     ← (victim, type) → attacker，20s TTL，L8 续归属
    ├── VictimDamageSourceCache.java ← 元素 DoT 专道 carry（仅元素类型 + 硬层写入）
    ├── VictimTypeCache.java      ← *DMG 路 → DamageShower 路打通（聊天栏类型显示）
    ├── VictimLethalCandidate.java← 致命一击候选（击杀归属 step 1）
    ├── VictimDamageContributors.java ← 助攻贡献者集合（30 s 窗口）
    ├── VictimTombstone.java      ← death tick 边沿确认 → PlayerKillStats.recordKill
    │
    ├── PlayerDamageStats.java    ← ⭐ 玩家造伤累计 (session + stage 双桶 / L8 carry 独立桶)
    ├── PlayerKillStats.java      ← 击杀 / boss / 助攻 / 未归属击杀
    ├── PlayerTakenStats.java     ← 承伤累计（同上分桶）
    ├── PlayerTakenProbe.java     ← DamageTook scoreboard delta 扫描入口
    ├── PlayerDpsTracker.java     ← 每玩家 5×1s 滑窗 ring（HUD 关行 5s DPS）
    │
    ├── StageKey.java             ← record(gameId, tier, floor, stageType, stageNum)
    ├── StageProbeServer.java     ← 每 tick 算每个玩家的 StagePayload + diff 推送
    ├── StageBoundaryDispatcher.java ← 关卡进 / 出 / session 切换的事件派发；is​Collecting 铁律
    ├── StageReportBroadcaster.java  ← 出关时全服聊天战报（按观察者高亮自己）
    ├── StatsSnapshotBroadcaster.java← 1 Hz 全量快照打包 + 玩家 JOIN baseline
    ├── StatsPersistenceManager.java ← world/data/ctt_stats.dat NBT 持久化（节流 + 出关 + GameID 归档）
    │
    └── mixin/
        └── ScoreboardUpdateMixin.java ← 服务端 Scoreboard.updateScore 入口分派器
```

**资源**（`src/main/resources/`）：

| 文件 | 作用 |
|------|------|
| `fabric.mod.json` | 双 entrypoint（client + main + modmenu）；mixins 分客户端 / 服务端两套 |
| `ctt-health-display.mixins.json` | 5 个 client-only mixins |
| `ctt-health-display-server.mixins.json` | 1 个服务端 mixin（`ScoreboardUpdateMixin`） |
| `weapon_damage_seed.json` | 由 `scripts/gen_weapon_damage_map.py` 离线扫描地图 datapack 生成的 601 武器映射表 |
| `stage_name_map.json` | 关卡 ID → 中英本地化名 |
| `assets/ctt-health-display/lang/{en_us,zh_cn}.json` | 完整双语 |
| `assets/ctt-health-display/textures/custom/{tower_token,velocity_icon}.png` | 金币 / 动量自绘图标 |

---

## 4. 核心数据流

### 4.1 伤害事件链（服务端权威）

```
地图 datapack 写 scoreboard
        │
        ▼
ScoreboardUpdateMixin (server)  ←── 唯一闸门，按 objective 分派
        │
        ├── RedHearts delta < 0  ─→ DamageProbe.recordFromRedHearts ──┐
        │     ├── 命中 initHpJumpValues 黑名单 → forceLayer = L9_FILTER
        │     └── 普通 → 走完整归属                                    │
        │                                                              │
        ├── DamageShower text_display ─→ DamageProbe.record ──────────┤
        │     ├── background = -16515325 → forceLayer = L9_HEAL       │
        │     └── 普通 → 走完整归属                                    │
        │                                                              │
        ├── *DMG (9 种元素 / 物理) ─→ AttackerProbe.record           │
        │     ScoreDeltaTracker 把累计值还原为单次 delta              │
        │     不写 PlayerDamageStats，只缓存 (VictimTypeCache 等)      │
        │                                                              │
        ├── damage_dealt           ─→ PlayerHitLog.record (L2/L5 数据源)│
        └── carrot_on_a_stick      ─→ PlayerFireLog.record (L1/L6 数据源)│
                                                                       ▼
                                                         AttackerProbe.attribute
                                                         L1 WEAPON_MATCH (持武器+开火+40m)
                                                         L2 STAT_TICK (本 tick damage_dealt)
                                                         L3 MARKER_NEAR (3m PlayerID marker)
                                                         L4 MARKER_FAR (40m PlayerID marker)
                                                         L5 STAT_WINDOW (近 5t damage_dealt)
                                                         L6 FIRE_WINDOW (近 20t 右键 + Tier 打分)
                                                         L7 BOW_RELEASE (近 2s 弓释放 + 40m)
                                                         L8 LAST_HITTER (victim×type 20s 续归属)
                                                         L9 NONE / FILTER / HEAL (未分类三子层)
                                                                       │
                                                                       ▼
                                                         feedStats(Result, damage)
                                                         │
                                                         ├── L1~L7 → PlayerDamageStats.add
                                                         │     ├ 检查 StageBoundaryDispatcher.isCollecting
                                                         │     ├ session 总累计 + stage bucket 双写
                                                         │     └ PlayerDpsTracker.onDealt（5s 滑窗）
                                                         ├── L8     → PlayerDamageStats.addCarry（不进玩家账户）
                                                         ├── L9_*   → PlayerDamageStats.addUnclassified
                                                         │
                                                         同时：
                                                         ├── VictimLethalCandidate.remember （击杀候选）
                                                         ├── VictimDamageContributors.add （助攻贡献）
                                                         ├── VictimLastHitter.remember （L8 续归属种子）
                                                         └── VictimDamageSourceCache.remember （元素 DoT 种子）

        END_SERVER_TICK：
        ├── DamageProbe.flushTick（处理 pending text_display 事件队列）
        ├── PlayerInventoryIndex.tickRefresh（每 5 tick 一刷玩家主手/背包键集）
        ├── PlayerTakenProbe.tickEnd（DamageTook scoreboard delta → PlayerTakenStats）
        ├── VictimTombstone.tickEnd（实体死亡 → PlayerKillStats.recordKill）
        ├── AttackerProbe.gcTick（清理过期日志 / 缓存）
        ├── StageProbeServer.tick（每玩家 StagePayload diff 推送）
        ├── StatsSnapshotBroadcaster.tickPushIfDue（1 Hz 全量快照广播）
        └── StatsPersistenceManager.onTickEnd（60 s 节流写盘检查）
```

### 4.2 客户端 HUD 显示链

```
                     ┌─── HealthData.update（每 tick）
                     │      读 BossBarHud.bossBars + 计分板 + StatsData fallback
                     │      解析出 HP/法力/鲜血/动量/队友/怪物条
                     │
HudRenderCallback ───┼─── HealthBarRenderer.render
                     │      主 HUD 渲染（血条 + 队友面板含嵌入式统计行）
                     │      └── TeammateStatsLine.ofStage / ofSession ──┐
                     │                                                   │
                     ├─── StatsRenderer.render（属性面板）                │
                     │                                                   │
                     └─── DamagePanelRenderer.drawHud（实验性 L 键面板） │
                                ↓                                        │
                                currentScopedSnapshot()                  │
                                ↓                                        │
                     ┌──────────────────────────────────────────────────┘
                     ▼
              ClientStatsCache    ←── 统一接入点（v6.6.5 引入）
                     │
       ┌─── isIntegrated()? ─── true ──→ 直接读 server 静态类（同 JVM）
       │                                  PlayerDamageStats / PlayerKillStats / ...
       │
       └──── false ──→ 读 latest StatsSnapshotPayload 缓存
                       构造 server-shape 的 Snapshot record 返还
                       （unattributed* / globalLayerCounts 等开发字段恒 0）

Server → Client networking：
    StagePayload         ──S2C每tick diff──→ ClientStageLocation.onPayload
    StatsSnapshotPayload ──S2C每20 tick──→ ClientStatsCache.update
                         ──玩家 JOIN baseline──↗
```

### 4.3 持久化链

```
SERVER_STARTED  → PlayerDamageStats.start() + setFrozen(true)
                  └── StatsPersistenceManager.load(server)
                       └── 读 world/data/ctt_stats.dat（gzip NBT）
                            ├── session: damage / kill / taken / stage 各 fromNbt
                            └── history[]: 最近 20 局归档

END_SERVER_TICK → StatsPersistenceManager.onTickEnd（每 60s 节流写盘）
StageBoundaryDispatcher.onStageExit → onStageExit 立刻写盘
StageBoundaryDispatcher.onSessionChange → archive 进 history[] + reset 当前 session + 写盘
SERVER_STOPPING → onServerStopping 收尾写盘
```

---

## 5. 关键设计取舍

### 5.1 双入口同包

`fabric.mod.json` 同时声明 `client` / `main` 两个 entrypoint，且 `environment="*"`。这意味着：
- **集成单机**：双入口都在同一 JVM 跑，client 与 server 类直接互访（`PlayerDamageStats` 静态字段触手可及）。
- **专用服务器**：仅 `main` 入口跑，HUD 类不被加载，但 server 的 stats 链路完整工作。
- **远程客户端**：仅 `client` 入口跑，server 端静态字段是空的 —— 必须走 `StatsSnapshotPayload` + `ClientStatsCache` 镜像。

`ClientStatsCache.isIntegrated()` 通过 `CttStatsServer.getServer() != null` 区分两条路径，所有 HUD 调用方写一遍代码即可同时支持三种部署形态 —— **设计干净、调用方零分支**，是这个项目最舒服的一处架构。

### 5.2 "宁可漏，不可错"的归属哲学

`AttackerProbe` 九层归属栈：每层都有"武器守卫"（玩家手上必须持有能造该类型伤害的武器）。这导致归属命中率被刻意压低，但代价是 `L9_NONE`（真未分类）也会比简单方案多 —— 从注释看作者明显反复权衡过：

- L8 `LAST_HITTER` 在 v6.5.9 之后**不再进玩家账户**，转沉到独立 `unattributedCarry` 桶 —— 因为剧情 set 假伤害实测会被错误 carry。
- L1~L7 的归属凭据进玩家账户 + 击杀凭据；L8 仅作为击杀维度的兜底；L9 三子层完全不进。
- `grandTotal = sum(L1..L7)`：玩家百分比分母只看真实归属，不被未分类拉低。

这种"二维分级"（伤害账户 vs 击杀凭据）在 `isHardLayer` / `isAttributionClassified` 两个 predicate 上落地，注释里反复警告"不要混用" —— 维护者已经踩过坑。

### 5.3 服务端权威 + 客户端只读镜像

整个 v6 战绩系统的权威数据全部在服务端：
- HUD 队友面板的"⚔ 344 ⛨ 155 ☠ 2"等数字 → 走 `ClientStatsCache` → 实际是 `StatsSnapshotPayload`。
- L 键面板和 N 键全屏表格也走同一接入点。
- 客户端**不做归属**，只做展示。

这避免了"客户端解析 DamageShower 但拿不到 PlayerInventoryIndex 等服务端状态"导致的归属准确度塌方（`V6_STATS_STATUS.md` §"已知死路"明确写了这条）。

### 5.4 主数据源切换：RedHearts vs DamageShower

`ServerConfig.useRedHeartsTally` 默认 true：
- **新路径（v6.6.0+）**：直接读实体 `RedHearts` 计分板下降量 = 实扣血量，无粒子数量上限，对地图 limit=10 天然免疫。
- **老路径**：读 `text_display`（tag=DamageShower）粒子，受 limit=10/tick 限制；保留作 fallback 以防新路径有未发现的 corner case。

切换成本只是一行配置；这种"双数据源 + 切换开关"是项目对地图行为不确定性的优秀工程响应。

### 5.5 StageKey 索引化序列化

`StatsSnapshotPayload` 内 `StageKey` 不重复编码 5 个 String，而是统一进顶层 `stages[]`，玩家行只引索引（`lastSeenStageIdx` / `stageRows[].stageIdx`）。4 玩家 × 20 关实测包大小约 3 KB，1 Hz 流量完全可承受 —— 这是面向 LAN / 公网混合部署的实用主义优化。

### 5.6 配置二级拆分（v6.6.4+）

| 文件 | 谁读 | 谁能改 |
|------|------|-------|
| `config/ctt-health-display.json` (`ModConfig`) | 客户端 | 单机集成下 ConfigScreen / 玩家手改 |
| `config/ctt-health-display-server.json` (`ServerConfig`) | 服务端 | 集成下 ConfigScreen 二级屏；专用服只能管理员手改 JSON |

迁移逻辑：旧版本玩家升级时 `ServerConfig.tryMigrateFromLegacy()` 从老的 `ctt-health-display.json` 抽取服端字段。这是"配置职责分离"的标准操作，专用服管理员一眼能看清自己该改哪份。

---

## 6. 代码质量观察

### 6.1 注释风格 · 极佳

- 几乎每个非显然分支都有"为什么这样写"的注释，并标注引入版本与修订原因。
- 关键修复（如 v5.3.1 sticky 字段、v6.5.9 L8 carry 剥离、v6.6.6 持久化不还原 live flag）都在原地讲了清来龙去脉。
- 极少看到"// 增加计数"这类无信息量注释 —— 注释纪律明显胜过大多数公共 mod。

### 6.2 防御性编程 · 到位

- `parseBossBarData` 用 `try-catch (Exception ignored)` 兜底（混服环境下 BossBar 文本可能被任意 mod 干扰）。
- `tryMigrateFromLegacy` 对每个字段单独判 `obj.has(...)` 再读 —— 不会因为旧版字段缺失整体失败。
- `PlayerDamageStats.fromNbt` v6.6.6 hotfix 显式不还原 `live`/`frozen` flag，并在注释里讲清"这两个 flag 由 lifecycle 管，不该由持久化参与" —— 教科书级的"持久化责任边界"教训。
- 网络 payload 的 `read` 不抛异常而靠上游 try-catch 兜底，并对 `version` 留通道。

### 6.3 并发处理 · 务实

- 高频跨线程结构：`ConcurrentHashMap` / `AtomicLong` / `AtomicInteger` / `volatile` 大量使用。
- `pendingBroadcasts` 用 `ConcurrentLinkedQueue`：mixin 在哪条线程都安全。
- `PlayerDamageStats.Entry.layerLock` 用 synchronized 而不是 atomic：因为是 int[] 整体 snapshot 而非单 int +1，这个选择正确。
- `latest` payload 单 `volatile` 引用 + 整对象替换，避免读到撕裂状态。

### 6.4 I/O 节流 · 合格

- 持久化写盘：60 s 节流（`onTickEnd`）+ 关键边界即时写（`onStageExit` / `onSessionChange` / `SERVER_STOPPING`）。
- 网络快照：1 Hz；玩家 JOIN 立刻推一次 baseline。
- 高频日志：v6.6.1 hotfix 把 INFO 降为 DEBUG 后短路，避免高频 DoT 拖垮 TPS。

### 6.5 命名 · 一致

- `StageKey` / `StageBoundaryDispatcher` / `StageReportBroadcaster` 等名词一致。
- 归属层用 `L1_WEAPON_MATCH` / `L7_BOW_RELEASE` 等"序号 + 含义"双标签，长写名 + `shortTag()`（"L7"）双形态对应日志 vs 聊天栏。
- "snapshot" 一词在三家 stats + payload 镜像层语义对齐（不可变拷贝）。

### 6.6 不太理想的点

- **常量字面量散落**：3 m、40 m、20 tick、200 tick 等魔法数字虽然多数有相邻常量定义，但部分 helper（如 `MAX_DISTANCE_M`）作用域可以再收敛。
- **`HealthData.update` 单方法 ~80 行**：两段强制清零、解析、lerp 平滑、可见性派生 —— 内聚足够但拆 4 个 `step*` 子方法可读性会更好。
- **类间隐式依赖**：`StageNameRegistry.currentLangCode()` 调 `MinecraftClient`（即便服务端也能 load 因为只走数据查询路径），这种"客户端类被服务端复用"的灰色地带在注释里讲清了，但理想做法是把"语言决议"再下沉一层接口让服务端注入 `"zh_cn"` 默认值。
- **没有单元测试**：当前验证靠"打几局副本看 actionbar / L 键面板对照源"。`AttackerProbe` 这种纯 stateful 的归属逻辑理论上完全可以脱机回放测试 —— 缺测试是项目唯一明显的短板。
- **`scripts/` 目录**：里面是 datapack 扫描脚本（`gen_weapon_damage_map.py` 等），README 没单独提及；新贡献者要知道"weapon_damage_seed.json 是离线生成的、不该手改"得从注释里捡。

---

## 7. 优势

1. **稀有的"地图特化型 mod 工程纪律"**：大部分地图特化 mod 都是一团 `if (!world.endsWith("cake_team_tower")) return;` 加几个硬编码偏移，这个项目却有完整的探针系统、分层归属、持久化与回归测试基线。
2. **演化路径透明**：v5 → v6 的边界很清晰（v5 = 纯 HUD，v6 = 引入服务端探针）；版本号在注释里大量出现，方便回溯修复理由。
3. **三种部署形态零分支调用**：`ClientStatsCache` 把"集成单机 / 专用服 / LAN 远程客户端"三种环境收敛到统一接口，HUD 调用方写一遍代码三处都跑。
4. **配置 / 文档 / 翻译**齐全：双语完整，配置二级拆分，docs 7 份还专门写了一份诚实的"设计 vs 实现差距"对照表（`V6_STATS_STATUS.md`）—— 这种自省文档罕见。
5. **对地图边缘行为的容错**：癫狂状态 sticky 字段、形态切换黑名单、回血粒子识别、L8 carry 剥离 …… 每个都是真实踩坑后的针对性修复，注释里都讲了"原 bug 现象"。
6. **构建工具链友好**：`deployToMods` 自动部署 + 跑游戏中只警告不失败、`remapJar` 仅在源码改动时自动版本号 +1 —— 开发节奏舒服。

---

## 8. 潜在风险与改进建议

> 按"投入 / 收益"由高到低排序。

### 8.1 高优先级

| # | 项 | 现状 | 建议 |
|---|----|------|------|
| 1 | **缺少单元测试** | 0 测试 | 至少给 `AttackerProbe.attribute`、`ScoreDeltaTracker`、`StageKey` 加纯 JUnit 用例 —— 这三处的逻辑完全可以离开 MC 跑（纯数据 in / out） |
| 2 | **`AttackerProbe.attribute` 单方法 160 行** | 9 个 if 分支顺排 | 抽 `tryL1...tryL9` 私有方法 + 用 `Optional<Result>` 链式调用，可读性 ↑ + 测试更容易 |
| 3 | **持久化 schema 没显式版本字段** | NBT 直接 putXxx | `t.putByte("v", 1)` + `fromNbt` 头判版本，未来加字段时不破坏向后兼容 |
| 4 | **`ScoreboardUpdateMixin` 里硬编码 objective 名称** | `if ("RedHearts".equals(...))` 等 | 与 `AttackerProbe.TRACKED_OBJECTIVES` 等共享一份常量来源 |

### 8.2 中优先级

| # | 项 | 现状 | 建议 |
|---|----|------|------|
| 5 | **`HealthBarRenderer` 估计很长** | 单文件做主血条 + 队友面板 + 嵌入式 stats 行 | 拆成 `MainBarRenderer` + `TeammatePanelRenderer` + `EmbeddedStatsRenderer` 三类，模块边界明确 |
| 6 | **魔法数常量** | 散在多处（如 `MAX_DISTANCE_M`、`L1_FIRE_FRESH_TICKS` 等） | 集中到 `AttributionConstants` 或带 javadoc 的内部 record 类，方便调参 |
| 7 | **`PlayerDamageStats` 内 `addUnattributed` 等 deprecated 方法保留** | 三个 deprecated overload | 已经委托到新签名了，加上 `@SuppressWarnings("DeprecationFlavor")` 或让 IDE 提示弱化 |
| 8 | **没有 mixin 优先级显式声明** | 默认 priority | 与其它操作 BossBar / EntityRenderer 的 mod 兼容时可能出现 mixin 顺序问题，建议显式 `@Mixin(priority=...)` |
| 9 | **`StatsSnapshotPayload` 未对玩家数量设上限** | `nPlayers` 只 readVarInt | 4 人塔够用，但理论上 dedicated 服可能上百玩家。读侧 `if (nPlayers > 256) throw` 防御一下 |

### 8.3 低优先级（锦上添花）

| # | 项 | 建议 |
|---|----|------|
| 10 | `scripts/` README | 写一段说明 `gen_weapon_damage_map.py` 何时跑、为何离线生成 |
| 11 | `FEATURES.md` 153K | 太长了；考虑按大版本拆 `FEATURES_v5.md` / `FEATURES_v6.md` |
| 12 | 可选 GitHub Actions | 只跑 `./gradlew compileJava` 检查不破坏构建即可 |
| 13 | `weapon_damage_seed.json` 校验 | 启动 load 时校验未知字段 / kind 值，防地图 datapack 升级后扫描脚本未跟进 |

### 8.4 不建议改的东西

- **九层归属栈不要"简化"**：`V6_STATS_STATUS.md` 列出过"5 tick 滑窗简单方案"是已被否决的死路。
- **不要追求 100% 归属命中率**：`L9_NONE` 的存在是设计决策，不是 bug。
- **不要把客户端做成主数据源**：架构上不可行（拿不到服务端状态），代码已删除过相关尝试。

---

## 9. 模块依赖速查

```
                     ┌─────────────────────────────────────┐
                     │ 地图 datapack (cake_team_tower)      │
                     │   * scoreboard objectives            │
                     │   * DamageShower text_display 粒子   │
                     │   * stage holders (#Boss/#Floor/...) │
                     │   * /trigger ViewStats / TogglePartyBossbar │
                     └────────────────┬────────────────────┘
                                      │
              ┌───────────────────────┴─────────────────────────┐
              ▼                                                 ▼
    [Server-side: ScoreboardUpdateMixin]              [Client-side: BossBar 文本]
              │                                                 │
              ▼                                                 ▼
    Probes (DamageProbe / AttackerProbe / ...)        HealthData / StatsData
              │                                                 │
              ▼                                                 │
    Stats Accumulators (PlayerDamageStats / ...)               │
              │                                                 │
              ▼                                                 │
    Persistence + Broadcasters                                  │
              │                                                 │
              ▼                                                 │
    StatsSnapshotPayload + StagePayload (S2C)                  │
              │                                                 │
              ▼                                                 │
    ClientStatsCache (统一接入点)                                │
              │                                                 │
              └──────────────────┬──────────────────────────────┘
                                 ▼
               HUD Renderers (HealthBar / Stats / DamagePanel / StatsTable)
                                 │
                                 ▼
                         Player's screen
```

---

## 10. 新人导航：30 分钟入门路径

按这个顺序读，能快速建立心智模型：

1. **`fabric.mod.json`**（2 min） — 知道有几个 entrypoint、几套 mixin。
2. **`docs/INTRODUCTION.md` §三. 技术版**（5 min） — 一段式建立全局观。
3. **`CttHealthDisplay.java` 的 `onInitializeClient`**（5 min） — 看 HUD / 按键 / 网络注册全景。
4. **`server/CttStatsServer.java` 的 `onInitialize`**（5 min） — 看服务端 lifecycle / tick 钩子全景。
5. **`server/AttackerProbe.java` 类级 javadoc + `attribute` 方法**（8 min） — 整个项目最复杂的逻辑就这一处。
6. **`network/StatsSnapshotPayload.java` 的 schema 注释 + `client/ClientStatsCache.java` 的类级 javadoc**（5 min） — 理解集成 vs 专用 vs 远程三态。
7. 视任务深入：
   - 改 HUD 渲染 → 读 `hud/HealthBarRenderer.java` + `hud/TeammateStatsLine.java`。
   - 改归属逻辑 → 重读 `AttackerProbe` 全部 + `WeaponDamageRegistry` + `PlayerInventoryIndex`。
   - 改持久化 → 读 `StatsPersistenceManager` + `StageBoundaryDispatcher`。
   - 改配置项 → `config/ModConfig.java` 加字段 → `ConfigScreen` 加控件 → 翻译文件加 key。

附加资源：
- 地图侧契约不清楚时查 `docs/MAP_DATAPACK_ANALYSIS.md`（60K，地图扣血栈 + scoreboard 分类全析）。
- 想知道为什么 v6 现状是这样，看 `docs/V6_STATS_STATUS.md`（设计 vs 实现 gap 表）。
- 调归属命中率，看 `docs/ROADMAP.md` 的 §1.2 归属层表 + §5 已知死路。

---

## 文档约定

- 本文是横向评审，**不**取代 `INTRODUCTION.md` / `FEATURES.md` / `ROADMAP.md`，而是与之互补。
- 评分（A/B/C）只是个人感觉，不是公允指标。
- "改进建议"全部是只读评审建议，未实际验证落地成本；提交前请配合具体场景再评估。
- 若发现本文与代码现状的偏离，以代码为准；可在对应章节就地修订并保留 diff。

**评审完毕。**
