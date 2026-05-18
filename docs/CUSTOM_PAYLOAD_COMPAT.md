# Custom Payload 双向兼容性 · 事故复盘与修复笔记

> **状态**：已实施（v8.1.1）
> **关联模块**：`StagePayload` / `StatsSnapshotPayload` / `CttHealthDisplay#onInitializeClient`
> **背景版本**：v8.1.1 修复（2026-04-30），起因来自 v8.1.0 引入 `inMagumTrials` 字段后未做线路层兼容
>
> 本文档**只**记录这一类协议演进失误的：
> 1. 事故现象与堆栈
> 2. Minecraft `class_9136` payload 框架对 codec 的契约
> 3. 双向（旧↔新 × 客户端↔服务端）失败矩阵
> 4. 三处具体修复改动
> 5. 后续 payload 演进的硬规范
>
> 不涉及功能逻辑，不与 `MAGUM_TRIALS_STAGE_TRACKING.md` 重叠（那篇是 MT 分关采集的功能设计，本篇是其新增字段引发的协议事故）。

---

## 1. 事故现象

玩家装着 `ctt-health-display 8.1.1` 客户端进未升级的服务端时，Minecraft 弹出红字断连：

```
Internal Exception: io.netty.handler.codec.DecoderException:
Failed to decode packet 'clientbound/minecraft:custom_payload'
```

崩溃报告（`debug/disconnect-2026-04-30_13.41.29-client.txt`）的 root cause 帧：

```
Caused by: java.lang.IndexOutOfBoundsException:
    readerIndex(44) + length(1) exceeds writerIndex(44):
    PooledSlicedByteBuf(ridx: 44, widx: 44, cap: 44/44, ...)
    at net.minecraft.class_2540.readBoolean(class_2540.java:1203)
    at com.ctt.healthdisplay.network.StagePayload.lambda$static$1(StagePayload.java:78)
```

读到第 78 行 `inMagumTrials` 这个 boolean 时，44 字节的 slice 已经被前 8 个字段全部读完，再读 1 字节直接越界。

---

## 2. Minecraft Payload 框架契约

报告里的 `PooledSlicedByteBuf(ridx:44, widx:44, cap:44/44, ...)` 是关键证据 —— 框架（`net.minecraft.class_9136` aka `PayloadCodec`）会**预先按外层包头声明的长度切出一个 SlicedByteBuf**，再交给我们的 codec 解码。这个 slice 的容量等于 payload 字节数，于是有两条隐含契约：

| 契约 | 违反时框架行为 | 直观后果 |
|------|----------------|----------|
| **不能读越界**（`ridx > widx`） | netty 抛 `IndexOutOfBoundsException` → `class_9136.method_56425` 包成 `DecoderException` | 客户端立即断连 |
| **必须读完整个 slice**（解码后 `ridx == widx`） | `class_9136` 校验未读完，抛 `DecoderException` | 客户端立即断连 |

**两条都是死刑**。所以一次 codec 改动如果只想到"加字段我服务端写了，客户端读了"，就会在两个版本错位的方向上踩雷：

```
            服务端写 N 字段           服务端写 N+1 字段
客户端读 N    OK                       违反契约 #2（剩 1 字节）
客户端读 N+1  违反契约 #1（缺 1 字节）  OK
```

`StagePayload` 的 javadoc 一开始写的"协议向后兼容：旧版客户端无视新字段"**根本没有实现** —— 解码器是无条件 `readBoolean()`，第一种错位就崩。`compat()` 工厂只在构造侧给默认值，对线路层无效。

---

## 3. 失败矩阵（修复前 vs 修复后）

### 3.1 `StagePayload`（无 version 字节，纯字段串）

| 场景 | 修复前 | 修复后 |
|------|--------|--------|
| 新客户端 ← 旧服务端（少 1 字段） | 越界 → 断连 | `isReadable()` 探测，缺则 `inMagumTrials=false` |
| 旧客户端 ← 新服务端（多 1 字段） | slice 残字节 → 断连 | 末尾 `skipBytes(readableBytes())` 排干，丢弃未知字段 |
| 任一字段中段插入/重排/删除 | 都会崩 | 仍会崩（无版本号无从识别） |

### 3.2 `StatsSnapshotPayload`（开头有 `byte version`）

| 场景 | 修复前 | 修复后 |
|------|--------|--------|
| `ver < CURRENT_VERSION`（旧服务端） | 已 OK：`(ver >= 2) ? readVarLong : 0L` 跳过新字段 | 不变 |
| `ver == CURRENT_VERSION` 且末尾追加新字段（误升） | slice 残字节 → 断连 | 末尾 drain 兜住 |
| `ver > CURRENT_VERSION` 且嵌套结构（StageRow/PlayerEntry）变了 | 错位读 → varInt 变垃圾 → 大概率 `nRows` 天文数字 → OOM/OOB → 断连 | 早退：drain 后返回空快照，receiver 识别空壳后丢弃，UI 沿用上一帧 |

### 3.3 嵌套结构演进的本质限制

**末尾 drain 救不了嵌套追加**。例：v3 服务端给 `StageRow` 里加一个新 long，写出 `nRows × 11 字段`；v2 客户端按 `nRows × 10 字段` 读，第二行的开头会读到第一行的尾部多余字段，varInt 错位之后 `nRows` 都可能被读成天文数字。所以**嵌套结构改动只能靠 version gate 早退**，不能靠 drain 兜底。这也是 `StatsSnapshotPayload` 必须保留 `byte version` 的原因。

---

## 4. 三处修复改动

### 4.1 `StagePayload.java` · 双向防御

```57:88:src/main/java/com/ctt/healthdisplay/network/StagePayload.java
    public static final PacketCodec<RegistryByteBuf, StagePayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeByte(payload.kind);
                buf.writeVarInt(payload.tier);
                buf.writeVarInt(payload.floor);
                buf.writeVarInt(payload.stageNum);
                buf.writeByte(payload.breakRoomId);
                buf.writeByte(payload.miniGameId);
                buf.writeByte(payload.gameOverPhase);
                buf.writeBoolean(payload.checkpoint);
                buf.writeBoolean(payload.inMagumTrials);
            },
            buf -> {
                byte kind        = buf.readByte();
                int tier         = buf.readVarInt();
                int floor        = buf.readVarInt();
                int stageNum     = buf.readVarInt();
                byte breakRoomId = buf.readByte();
                byte miniGameId  = buf.readByte();
                byte gameOver    = buf.readByte();
                boolean checkpt  = buf.readBoolean();
                boolean inMt = buf.isReadable() && buf.readBoolean();
                if (buf.isReadable()) {
                    buf.skipBytes(buf.readableBytes());
                }
                return new StagePayload(kind, tier, floor, stageNum,
                        breakRoomId, miniGameId, gameOver, checkpt, inMt);
            }
    );
```

- `inMt` 用 `isReadable() && readBoolean()`：兼容旧服务端不写第 9 字段
- 末尾 `skipBytes(readableBytes())`：兼容未来服务端在末尾追加新字段

### 4.2 `StatsSnapshotPayload.java` · version-gated 早退 + 末尾保险阀

`read(...)` 头部插入早退：

```222:231:src/main/java/com/ctt/healthdisplay/network/StatsSnapshotPayload.java
        if (Byte.toUnsignedInt(ver) > Byte.toUnsignedInt(CURRENT_VERSION)) {
            buf.skipBytes(buf.readableBytes());
            return new StatsSnapshotPayload(
                    ver, 0L, 0L, 0L,
                    false, false,
                    0, -1,
                    Collections.emptyList(),
                    Collections.emptyList()
            );
        }
```

`read(...)` 末尾插入兜底 drain：

```298:303:src/main/java/com/ctt/healthdisplay/network/StatsSnapshotPayload.java
        if (buf.isReadable()) {
            buf.skipBytes(buf.readableBytes());
        }
```

- 早退用 **无符号比较**（`Byte.toUnsignedInt`）：避免 `byte` 升 `int` 时的符号位陷阱（虽然短期内 version 数值不会逼近 0x80，但写一次写对就少一处坑）
- 早退返回的空快照 `ver` 字段沿用原始未来版本号（`8`、`9`、…），下游 receiver 据此识别

### 4.3 `CttHealthDisplay.java` · receiver 识别空快照不写缓存

```109:117:src/main/java/com/ctt/healthdisplay/CttHealthDisplay.java
        ClientPlayNetworking.registerGlobalReceiver(StatsSnapshotPayload.ID, (payload, ctx) -> {
            if (Byte.toUnsignedInt(payload.version()) > Byte.toUnsignedInt(StatsSnapshotPayload.CURRENT_VERSION)) {
                return;
            }
            ClientStatsCache.update(payload);
        });
```

receiver 不能盲目 `update(latest = payload)`，否则未来版本快照（codec 已降级为空）会把上一帧真实数据冲掉，UI 间歇性闪空。这里直接丢弃整帧，等服务端降级或客户端升级后自动恢复。

---

## 5. 后续 Payload 演进硬规范

> 把 §2 / §3 的教训沉淀成 5 条规则，新增/改 payload 时**全部满足**。

### R1 · 任何新 `CustomPayload` **必须**以 `byte version` 起头

哪怕字段只有一个，没有 version 字段就等同于把演进通道焊死。`StagePayload` 是历史遗留，不要再向它学。

### R2 · codec 解码器**必须**包含三段防御

```java
buf -> {
    byte ver = buf.readByte();

    // R2.a · 未来版本早退：drain + 返回空/降级对象
    if (Byte.toUnsignedInt(ver) > Byte.toUnsignedInt(CURRENT_VERSION)) {
        buf.skipBytes(buf.readableBytes());
        return /* 空降级对象，version 字段保留原 ver */;
    }

    // R2.b · 旧版本字段缺省 / version-gated 读
    long legacy = ...readXxx();
    long v2only = (ver >= 2) ? buf.readXxx() : DEFAULT;
    long v3only = (ver >= 3) ? buf.readXxx() : DEFAULT;

    // R2.c · 末尾兜底 drain（防止"忘了 bump version" 又末尾追加字段的疏漏）
    if (buf.isReadable()) {
        buf.skipBytes(buf.readableBytes());
    }
    return ...;
}
```

### R3 · 嵌套结构（list 内的 record）字段变更**必须** bump `CURRENT_VERSION`

末尾 drain 救不了嵌套追加，会让后续 row 错位读。规则：**`StageRow` / `PlayerEntry` 这类嵌套 record 加任何字段，version 必须 +1**，并在 reader 里 `(ver >= N) ? read : default`。否则旧客户端读出垃圾值后大概率被 `nRows = Integer.MAX_VALUE / 2` 这种垃圾长度搞崩。

### R4 · 破坏性变更（删字段、字段类型变、字段语义变）**必须**换新的 payload Identifier

例如把 `StagePayload` 的 `tier` 从 `varInt` 改成 `string`，不要在原 ID 上演进。直接换名 → `Identifier.of("ctt-health-display", "stage_location_v2")`。
- 旧客户端不识别新 ID，Fabric Networking 默认会忽略而不踢人
- 服务端可基于 `ServerPlayNetworking.canSend(player, NEW_ID)` 决定推新还是推旧
- 新旧两份 codec 各自独立简单，比在一份 codec 里塞 if/else 干净得多

### R5 · receiver **必须**校验 `version > CURRENT_VERSION` 直接返回，不要更新缓存

codec 已经把不可解析的帧降级为空对象（`Collections.emptyList()` 等），但 receiver 不识别这个语义就会继续 `latest = payload` 把上一帧好数据冲掉。**判定 + return 是两行代码的事**，所有 payload 的 receiver 都要做。

### R6 · 双向兼容性测试矩阵

CI / 手测时应覆盖（哪怕只是手动执行一次）：

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | 旧客户端 + 新服务端，进服 | 不踢线，旧字段功能正常，新字段 UI 显示默认值 |
| 2 | 新客户端 + 旧服务端，进服 | 不踢线，旧字段功能正常，新字段 fallback |
| 3 | 同版本，进服 | 全字段正常 |
| 4 | 服务端中途热重载升级 | 不踢线，UI 在下一帧自然切到新数据 |

---

## 6. 历史变更

| 版本 | 日期 | 变更 |
|------|------|------|
| v8.1.0 | 2026-04-30 | 引入 `StagePayload.inMagumTrials`，**未做线路层兼容**，导致跨版本进服必崩 |
| v8.1.1 | 2026-04-30 | 本次修复：`StagePayload` 双向防御 + `StatsSnapshotPayload` version 早退 + receiver 空快照丢弃 |

---

## 7. 相关文档

- `MAGUM_TRIALS_STAGE_TRACKING.md` —— 引入 `inMagumTrials` 字段的功能设计（**未涉及**协议兼容议题，本篇是其遗漏的补丁）
- `CODE_REVIEW.md` §8 「潜在风险与改进建议」 —— 此次事故验证了"协议演进规范缺失"是真实风险，可在下一次 review 时把本文规范并入
- `ROADMAP.md` —— 后续 payload 改动需先翻本文 §5 R1-R6 自检
