# CTT 伤害 / 击杀统计系统 · 目标与进度

> 本文档只跟踪 **v6.0.0 立项的"伤害 / 击杀统计"大功能**这一条主线。
> v5.x 及之前的 HUD 基础（coin / mana / HP / 多色心 / 队友血 / Joey Blood / Jelly Swan Momentum / ViewStats 自动刷新 / Boss 栏屏蔽 / 3D 血条 等）视为**已完备的前置功能**，在 `FEATURES.md` 中维护，本文不涉及。
>
> - **起点**：v6.0.0（`CttStatsServer` 骨架首次落地）
> - **当前**：v6.5.2
> - **最后更新**：2026-04-25

---

## 0 · 大功能总目标（v6.0.0 立项时的原始愿景）

> 在 **Cake Team Towers** 地图上做出一套"能把每一下**伤害** / 每一次**击杀** 准确归到对应玩家，并实时显示在客户端**可交互面板**"上的**混合客户端 + 服务端**系统。

三条子目标贯穿整个大功能：

| 编号 | 子目标 | 说明 |
|---|---|---|
| **A** | 攻击者归属（Attribution） | 伤害/击杀事件 → 确认"是谁干的"；覆盖全武器链（近战 / 子弹 / DoT / 召唤物 / 弓箭…） |
| **B** | 数值采集（Value） | 从 `*DMG` 计分板 delta + `DamageShower` 文本实体 双路取**真实单次伤害**，非累计值 |
| **C** | 外观 / 交互（Display） | 客户端面板实时显示、可拖拽、可点击、支持清空 / 暂停 / 快照 |

---

## 1 · 伤害统计 · 功能清单

### 1.1 追踪的伤害计分板

**9 种来源类型** + **1 条总线**：

| 计分板 | 类别 | 可 DoT carry |
|---|---|---|
| `MeleeDMG` | 近战 | ❌（一次性命中） |
| `BulletDMG` | 子弹 / 弓箭 | ❌ |
| `ForceDMG` | 力学 / 爆炸 | ❌ |
| `FireDMG` | 元素 · 火 | ✅ |
| `WaterDMG` | 元素 · 水 | ✅ |
| `IceDMG` | 元素 · 冰 | ✅ |
| `ElectricDMG` | 元素 · 电 | ✅ |
| `LightDMG` | 元素 · 光 | ✅ |
| `DarkDMG` | 元素 · 暗 | ✅ |
| `AllDMG` | **总线**（归属结果专用，不由地图写入） | — |

武器映射由 `scripts/gen_weapon_damage_map.py` 扫描地图数据包生成的 `weapon_damage_seed.json`：**601 个武器条目，242 个有伤害类型映射**，外加硬编码的原版 `bow / crossbow / trident → BulletDMG`（三叉戟同时有 `MeleeDMG`）。

### 1.2 归属层（Sub-goal A · v6.5.2 重构：八层硬归属 + L9 三子层未分类）

> 原则：**宁可漏，不可错**。L1 ~ L8 全部为"硬归属"计入玩家确认伤害（grandTotal 分母）；L9 三子层全部归入"未分类"桶，不计入个人、不进 grandTotal。

| 层 | 名称 | 规则摘要 | 武器守卫 | 归属目标 | 状态 |
|---|---|---|---|---|---|
| L1 | `WEAPON_MATCH` | 持有匹配武器 + 10s 内扣扳机 + 40m 内 | — | 玩家（硬） | ✅ |
| L2 | `STAT_TICK` | 本 tick `damage_dealt` 增长 | ✅ | 玩家（硬） | ✅ |
| L3 | `MARKER_NEAR` | 3m marker / projectile + PlayerID | ✅ | 玩家（硬） | ✅ |
| L4 | `MARKER_FAR` | 40m marker / projectile + PlayerID | ✅ | 玩家（硬） | ✅ |
| L5 | `STAT_WINDOW` | `damage_dealt` 5 tick 窗口 | ✅ | 玩家（硬） | ✅ |
| L6 | `FIRE_WINDOW` | 20 tick RightClick + Tier 打分 | ✅ | 玩家（硬） | ✅ |
| **L7** | `BOW_RELEASE` | **2s 内**弓 / 弩 / 三叉戟释放 + 40m 内 | ❌（释放后可换武器） | 玩家（硬） | ✅（v6.3.7 立 L7b，v6.5.2 升位 L7） |
| **L8** | `LAST_HITTER` | victim×T 续归属缓存（20s TTL） | ✅ | 玩家（硬） | ✅（v6.5.2 由 L7 降为 L8 + 改硬归属） |
| L9-NONE | `NONE` | 兜底无归属 | — | 未分类（不进 grandTotal） | ✅ |
| **L9-FILTER** | `INIT_HP_JUMP` | 黑名单数值（1000 / 10000 / 100000）：怪物初始化 / 形态切换造成的负 delta 假伤害 | — | 未分类（不进 grandTotal） | ✅ v6.5.2 |
| **L9-HEAL** | `HEAL_PARTICLE` | 绿色 background 回血粒子 | — | 未分类（不进 grandTotal） | ✅ v6.5.2 |

**v6.5.2 删除**：原 `L8_SUMMON_FALLBACK`（40m 唯一召唤物持有者）/ `L8B_SUMMON_SHARED`（多持有者按数量分摊）—— 误归属率高，整层删除。`PlayerInventoryIndex.summonItemCountOf` / `WeaponDamageRegistry.allSummonKeys()` 保留为 dead API 以便回滚。

### 1.3 元素 DoT carry

6 种元素伤害共享一个 `VictimDamageSourceCache`：最近一次归属成功时记录 `(victim, objective) → attacker`，200 tick（10s）内后续 DoT tick 回查该缓存。非元素伤害（近战 / 子弹 / 力学）不 carry，避免"A 射了一枪、B 接着打"被错挂。

### 1.4 数值采集（Sub-goal B）

| 组件 | 作用 | 版本 |
|---|---|---|
| `ScoreDeltaTracker` | 把 `*DMG` 计分板的**累计值**转成**单次 delta**；杜绝 v6.3.2 前的"巨额假伤害" | v6.3.2 |
| `DamageShower` text_display 解析 | 一次写入语义，`PlayerDamageStats` 累加的**唯一真源** | v6.3.3 |
| `VictimTypeCache` | `*DMG` 路与 `DamageShower` 路打通，聊天栏能显示具体类型（`FireDMG` 等）+ 精确层号 | v6.3.3 |

### 1.5 输出（v6.5.2 重新定义）

- **每玩家**：硬确认累计（L1~L8）/ 事件数 / 单次最高 / 各层命中次数
- **未分类桶**：L9-NONE + L9-FILT + L9-HEAL，**不计入** `grandTotal`、不计入个人占比；仅在面板与聊天栏作为诊断信息展示
- **`grandTotal`**：仅 L1~L8 之和（"已分类总伤害"）；玩家占比分母 = `grandTotal`
- **聊天栏**：开发 / 测试通道，实时单条广播**所有**事件（含 L9 三子层），带短标签 `[L1] / [L7] / [L8] / [L9-NONE] / [L9-FILT] / [L9-HEAL]` 便于排查
- **ActionBar (`DamageProbe`)**：单次伤害原始值（不受 `ScoreDeltaTracker` 影响，作为对照源）

### 1.6 击杀 / 助攻 / 受到伤害（v6.4 ~ v6.5）

| 维度 | 数据源 | 实现 | 版本 |
|---|---|---|---|
| 击杀（kill） | `VictimLethalCandidate` 锁定致命一击 + `VictimTombstone` 死亡确认 | `PlayerKillStats.recordKill` | v6.4.x |
| 助攻（assist） | `VictimDamageContributors` 贡献过任意硬归属伤害的玩家 | `PlayerKillStats.recordAssist` | v6.4.x |
| 未归属击杀 | victim 死亡时无玩家贡献 | `PlayerKillStats.unattributedKills` | v6.4.x |
| 受到伤害（damage taken） | ` Weltkrieg` scoreboard delta | `PlayerTakenProbe` 每 tick 扫描 → `PlayerTakenStats` | **v6.5.0** |
| 回血粒子过滤 | `text_display` `background=-16515325` | `DamageProbe.record` → 路由 `L9_HEAL` | v6.5.1 立 / v6.5.2 改路由 |
| 黑名单数值过滤 | `ModConfig.initHpJumpValues={1000, 10000, 100000}` | `DamageProbe.recordFromRedHearts` → 路由 `L9_FILTER` | **v6.5.2** |

---

## 2 · 击杀统计 · 功能清单（v6.4 已落地）

> **状态：核心通路已落地（v6.4.x），与伤害归属栈复用。**

### 2.1 击杀归属策略

- [x] **复用伤害归属栈**：`VictimLethalCandidate` 在致命一击 tick 锁定 attacker（来自 `AttackerProbe.attribute()`）→ `VictimTombstone` 在 death 事件确认 → `PlayerKillStats.recordKill`
- [x] **助攻**：`VictimDamageContributors` 记录过去 N tick 对该 victim 贡献过任意硬归属伤害（L1~L8）的玩家集合，killer 之外全部计入 `recordAssist`
- [x] **未归属击杀（unattributed kills）**：victim 死亡时无任何硬归属候选者 → 计入 `PlayerKillStats.unattributedKills`（不挂任何玩家）

### 2.2 击杀目标分类

- [x] 普通怪 / BOSS：通过 entity tag `Boss` 区分，已分别在 `PlayerKillStats.Entry` 中累计
- [ ] PvP（地图基本无此场景，暂不实现）

### 2.3 统计维度

- [x] 每玩家总击杀数 / BOSS 击杀数 / 助攻数 / 未归属击杀
- [ ] 最高连杀 / 击杀时间线（暂搁置）

### 2.4 数据来源

- [x] 监听 `ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY` + `LivingEntity` death tick 边沿 → `VictimTombstone.markDead`
- [x] 复用 `AttackerProbe.attribute()` 结果挂载到 `VictimLethalCandidate`

### 2.5 显示整合

- [x] 在伤害面板每玩家行追加：`击杀 / BOSS / 助攻`（`DamagePanelRenderer.drawPlayerRow`）
- [x] 在面板 footer 显示"未归属击杀"计数
- [x] 受击伤害（damage taken）独立列（v6.5.0）

---

## 3 · 外观 / 交互 · 功能清单（Sub-goal C）

| 功能 | 状态 | 版本 |
|---|---|---|
| 伤害分配面板（`DamagePanelRenderer` + `DamagePanelScreen`） | ✅ | v6.3.0 |
| L 键打开交互面板 | ✅ | v6.3.0 |
| HUD 常驻只读渲染（可配置） | ✅ | v6.3.0 |
| 面板可拖拽 | ✅ | v6.3.0 |
| 面板内按钮（▶ 暂停 / ✕ 清空 / ⚑ 快照 / ⚙ 设置） | ✅ | v6.3.0 |
| 聊天栏 T 键时鼠标仍可点击面板 | ✅ | v6.3.1 |
| 去灰底 · 半透明文字 | ✅ | v6.3.1 |
| 每玩家行：硬确认 / 事件 / 最高 | ✅ | v6.3.3 |
| 未分类桶 + 总百分比 grandTotal 分母 | ✅ | v6.3.3 |
| 聊天栏实时伤害广播（`DamageShower` 源，含攻击者 + 类型标签） | ✅ | v6.3.3 |
| 每玩家行：击杀 / BOSS / 助攻 列 | ✅ | v6.4.x |
| 每玩家行：受到伤害（damage taken）列 | ✅ | v6.5.0 |
| L9 三子层分项展示（NONE / FILT / HEAL）+ 详细模式可展开 | ✅ | v6.5.2 |
| 黑名单数值（1000/10000/100000）过滤 → L9-FILT | ✅ | v6.5.2 |
| 回血粒子（绿色 background）路由 → L9-HEAL | ✅ | v6.5.2 |
| **面板样式配置 UI**（字号 / 透明度 / 锚点） | ⬜ 未规划 | — |

---

## 4 · 进度时间线

> 只列面向大功能的里程碑，内部修补压成单行。版本倒序。

| 版本 | 子目标 | 主要改动 |
|---|---|---|
| **v6.5.2** | A, B, C | **归属层重构**：删 L8/L8b 召唤物层；L7b → L7 硬归属；原 L7 → L8 硬归属；L9 拆 NONE/FILTER/HEAL 三子层。黑名单数值（1000/10000/100000）路由 L9-FILTER；回血粒子路由 L9-HEAL。`grandTotal` 重定义为仅 L1~L8，L9 不计入玩家占比。聊天栏作开发通道全量显示（含 L9 子标签） |
| v6.5.1 | B | 回血粒子识别（绿色 `background=-16515325`）+ 临时 return 过滤（v6.5.2 改路由） |
| v6.5.0 | A, C | 受到伤害统计（`PlayerTakenProbe` + `PlayerTakenStats`）：扫描 ` Weltkrieg` scoreboard delta；面板增加 damage-taken 列 |
| v6.4.x | A, C | 击杀 / 助攻 / 未归属击杀（`VictimLethalCandidate` + `VictimTombstone` + `VictimDamageContributors` + `PlayerKillStats`）；面板每玩家行追加击杀维度列 |
| v6.3.10 | — | 回滚未完成 `DamageAnomalyFilter`，清理 `ModConfig` 对应字段 |
| v6.3.8 | A | L7b 弓窗口 3s → 2s |
| v6.3.7 | A | 新增 L7b_BOW_RELEASE（解决弓手射完换武器 L1~L6 失败） |
| v6.3.6 | A | `WeaponDamageRegistry` 硬编码 `VANILLA_ITEM_DMG`（弓 / 弩 / 三叉戟） |
| v6.3.3 | B, C | 聊天栏只广播实时伤害；L7+ 全归未分类；引入 `VictimTypeCache` |
| v6.3.2 | B | `ScoreDeltaTracker` 修复 DoT 巨额累计值 |
| v6.3.1 | C | 聊天栏 T 键时鼠标可交互 + 去灰底 |
| v6.3.0 | C | 伤害分配面板 + L 键 + 累积 / 清空 |
| v6.2.1 | A | L8b_SUMMON_SHARED（多召唤物按数量分摊） |
| v6.2.0 | A | 九层归属栈重构（40m + 武器守卫） |
| v6.1.x | A | DoT 误归属、石剑漏算 等修补（合并条目） |
| v6.0.x | A, B | 攻击者归属首版 + 高频武器 / 队友 / 神器误归属首轮修补 |
| **v6.0.0** | 立项 | `CttStatsServer` 骨架；混合客户端 + 服务端架构奠基 |

---

## 5 · 已知死路（Won't）

记录**已经尝试或思考后决定不做**的方向，防止绕圈。

- ❌ **全局统一 HARD_CAP 异常过滤**：高阶 BOSS 阶段真实伤害也可能爆表，硬上限会误伤
- ❌ **victim 维度均值过滤**：BOSS 首次登场样本为空，阈值不可用
- ❌ **把 L7 及以下层重新计入玩家确认伤害**：违背"宁可漏，不可错"总原则
- ❌ **客户端纯解析 `DamageShower` 不走服务端归属**：无法拿到 `PlayerInventoryIndex` / `PlayerFireLog` 等服务端状态，归属准确度会塌方

---

## 6 · 待办（Next）

> 按优先级排列；每条需**先确认细则再动手**，避免 v6.3.9 / v6.3.10 的返工。

1. [ ] **L9-FILTER 黑名单观察期**：v6.5.2 上线后跑几局副本，确认 1000 / 10000 / 100000 命中率与误伤率，按需调 `ModConfig.initHpJumpValues`
2. [ ] **击杀连杀线 / 时间线**（§2.3 余项）——可选增强
3. [ ] **面板样式配置 UI**（字号 / 透明度 / 锚点）——可选
4. [ ] （你后续添加）

---

## 7 · 文件约定

- 本文件是**目标 / 进度 / 待办的单一入口**，与 `FEATURES.md`（面向用户的功能使用手册）并列。
- 勾选语义：`[x]` 已落地 ； `[ ]` 已立项未实现 ； `⬜` 已规划但细则未敲定。
- 大功能完成、进入维护期后，把已落地部分整编进 `FEATURES.md`，本文仅保留滚动进度。
- 每次 `gradle.properties` 版本号变更若涉及大功能，需在 §4 追加一行。
