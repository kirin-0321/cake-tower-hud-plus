package com.ctt.healthdisplay.server;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.2.0 · 玩家主手 / 背包 custom_data 签名索引。
 *
 * <h2>用途</h2>
 * <p>攻击者归属每一层都要回答："P 当前是否有能造 T 类型伤害的武器？"
 * 每事件都扫 P 的 inventory 太贵；改成周期性快照，事件只读缓存。
 *
 * <h2>刷新频率</h2>
 * <p>每 {@link #REFRESH_INTERVAL_TICKS} tick（默认 5 tick = 250ms）全服扫描一次。
 * 玩家切换武器的最坏响应延迟 = 5 tick，对秒级伤害事件足够精准。
 *
 * <h2>两类签名</h2>
 * <ul>
 *   <li>{@link Snapshot#mainHand} —— 主手当前持有的 custom_data key 集合。
 *       用于 {@link WeaponDamageRegistry.Kind#WEAPON} 类武器匹配（必须主手持有才能攻击）。</li>
 *   <li>{@link Snapshot#inventoryAny} —— 主手 + 快捷栏 9 + 主背包 27 = 37 格内所有 key。
 *       用于 {@link WeaponDamageRegistry.Kind#SUMMON} 类召唤物匹配
 *       （召唤物放背包里也能持续攻击，不必在主手）。</li>
 * </ul>
 *
 * <h2>为什么忽略副手</h2>
 * <p>地图的自定义武器体系靠 {@code SelectedItem} NBT 识别，SelectedItem 只读主手。
 * 副手的 carrot_on_a_stick 不会触发任何武器函数 —— 副手忽略。
 */
public final class PlayerInventoryIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-inv-idx");

    /** 扫描周期。玩家切换武器到索引刷新的最坏延迟 = 此值。 */
    public static final int REFRESH_INTERVAL_TICKS = 5;

    /**
     * 一位玩家的快照（不可变视图 → 读无需锁）。
     *
     * @param mainHand           主手当前持有的 custom_data key 集合（去重）
     * @param inventoryAny       主手 + 快捷栏 + 主背包 37 格内的所有 key 集合（去重）
     * @param summonItemCount    召唤物格子数（按"格"计数，不去重）——
     *                           同一玩家不同格子里的召唤物合计，用于 L8b 加权均分
     * @param mainHandItemId     v6.3.4 · 主手 vanilla item id（形如 {@code "minecraft:bow"}，空手为 null）。
     *                           用于 vanilla 弓/弩/三叉戟的守卫兜底（seed 无法静态分析到这些）。
     * @param tick               快照生成 tick
     */
    public record Snapshot(Set<String> mainHand, Set<String> inventoryAny,
                           int summonItemCount, String mainHandItemId, long tick) {
        public static final Snapshot EMPTY = new Snapshot(Set.of(), Set.of(), 0, null, -1L);

        public boolean mainHandHas(String key) {
            return mainHand.contains(key);
        }

        public boolean inventoryHas(String key) {
            return inventoryAny.contains(key);
        }

        public boolean hasAny(Set<String> keys, boolean mainHandOnly) {
            Set<String> pool = mainHandOnly ? mainHand : inventoryAny;
            if (pool.isEmpty() || keys.isEmpty()) return false;
            Set<String> small = pool.size() <= keys.size() ? pool : keys;
            Set<String> big = pool.size() <= keys.size() ? keys : pool;
            for (String k : small) if (big.contains(k)) return true;
            return false;
        }
    }

    private static final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private static volatile long lastRefreshTick = -1L;

    private PlayerInventoryIndex() {}

    /**
     * 必须由 END_SERVER_TICK 调用。内部做节流：超过 {@link #REFRESH_INTERVAL_TICKS}
     * 才真正全服扫描一次。
     */
    public static void tickRefresh(MinecraftServer server) {
        if (server == null) return;
        long tick = DamageProbe.currentTick();
        if (tick - lastRefreshTick < REFRESH_INTERVAL_TICKS) return;
        lastRefreshTick = tick;

        Set<UUID> onlineUuids = new HashSet<>();
        for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
            UUID uuid = sp.getUuid();
            onlineUuids.add(uuid);
            Snapshot snap = buildSnapshot(sp, tick);
            snapshots.put(uuid, snap);
        }

        // GC 已下线的玩家
        snapshots.keySet().retainAll(onlineUuids);
    }

    private static Snapshot buildSnapshot(ServerPlayerEntity sp, long tick) {
        PlayerInventory inv = sp.getInventory();
        ItemStack mainHandStack = sp.getMainHandStack();

        Set<String> mainHand = extractKeys(mainHandStack);
        String mainHandItemId = mainHandStack.isEmpty() ? null
                : net.minecraft.registry.Registries.ITEM.getId(mainHandStack.getItem()).toString();
        int summonCount = 0;

        Set<String> any = new HashSet<>();
        any.addAll(mainHand);
        summonCount += countSummonKeys(mainHand);

        // 快捷栏 0~8
        for (int i = 0; i <= 8; i++) {
            if (i == sp.getInventory().selectedSlot) continue; // 主手已处理
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            Set<String> k = extractKeys(s);
            if (k.isEmpty()) continue;
            any.addAll(k);
            summonCount += countSummonKeys(k);
        }
        // 主背包 9~35
        for (int i = 9; i <= 35; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            Set<String> k = extractKeys(s);
            if (k.isEmpty()) continue;
            any.addAll(k);
            summonCount += countSummonKeys(k);
        }
        // 副手（40）与护甲（36~39）不看 —— 地图武器识别只读主手 SelectedItem

        return new Snapshot(
                mainHand.isEmpty() ? Set.of() : Collections.unmodifiableSet(mainHand),
                any.isEmpty() ? Set.of() : Collections.unmodifiableSet(any),
                summonCount,
                mainHandItemId,
                tick
        );
    }

    /**
     * 计一个 stack 的所有 key 里有多少是 summon kind —— 用于 L8b 权重统计。
     * <p>一个 stack 通常只有 1 个标识 key；但为稳健起见全量计数。
     */
    private static int countSummonKeys(Set<String> keys) {
        if (keys.isEmpty()) return 0;
        int n = 0;
        for (String k : keys) {
            WeaponDamageRegistry.WeaponInfo info = WeaponDamageRegistry.getInfo(k);
            if (info != null && info.kind() == WeaponDamageRegistry.Kind.SUMMON) n++;
        }
        return n;
    }

    /** 从一个 ItemStack 抽取所有 "byte == 1" 的 custom_data 顶层 key。 */
    private static Set<String> extractKeys(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Set.of();
        NbtComponent cd = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (cd == null) return Set.of();
        NbtCompound nbt = cd.copyNbt();
        if (nbt == null || nbt.isEmpty()) return Set.of();

        Set<String> out = null;
        for (String k : nbt.getKeys()) {
            NbtElement el = nbt.get(k);
            if (el == null) continue;
            // 地图约定：标识位 = 1b（Byte）。接受任意非零数值。
            byte t = el.getType();
            if (t == NbtElement.BYTE_TYPE || t == NbtElement.SHORT_TYPE
                    || t == NbtElement.INT_TYPE || t == NbtElement.LONG_TYPE) {
                long v;
                try {
                    v = ((net.minecraft.nbt.AbstractNbtNumber) el).longValue();
                } catch (ClassCastException e) {
                    continue;
                }
                if (v != 0L) {
                    if (out == null) out = new HashSet<>(2);
                    out.add(k);
                }
            }
        }
        return out == null ? Set.of() : out;
    }

    /** 读快照。玩家离线或从未刷新过返回 EMPTY。 */
    public static Snapshot get(UUID playerUuid) {
        Snapshot s = snapshots.get(playerUuid);
        return s == null ? Snapshot.EMPTY : s;
    }

    /**
     * 该玩家是否持有能造成 {@code damageType} 类型伤害的武器（含召唤物）。
     *
     * <ul>
     *   <li>weapon kind：主手命中即可</li>
     *   <li>summon kind：主手 / 快捷栏 / 主背包 任一格命中即可</li>
     * </ul>
     *
     * <p>返回 true 不等价于"就是他干的"——只是"他有能干这事的家伙"。归属决策还需
     * 配合 L1/L5/L6/L7 的时序证据。
     */
    public static boolean hasMatchingWeapon(UUID playerUuid, String damageType) {
        if (playerUuid == null || damageType == null) return false;
        Snapshot snap = get(playerUuid);
        if (snap == Snapshot.EMPTY) return false;

        // v6.3.4 · vanilla 武器兜底：主手是弓/弩/三叉戟 → 对 BulletDMG/MeleeDMG 直接承认。
        // 放在自定义查询之前，因为 vanilla 武器没有 custom_data key，seed 里也查不到。
        if (snap.mainHandItemId() != null
                && WeaponDamageRegistry.canVanillaProduce(snap.mainHandItemId(), damageType)) {
            return true;
        }

        Set<String> candidates = WeaponDamageRegistry.weaponsOfType(damageType);
        if (candidates.isEmpty()) return false;

        // 先走 weapon kind（严格主手）
        for (String k : candidates) {
            WeaponDamageRegistry.WeaponInfo info = WeaponDamageRegistry.getInfo(k);
            if (info == null) continue;
            if (info.kind() == WeaponDamageRegistry.Kind.WEAPON) {
                if (snap.mainHandHas(k)) return true;
            } else {
                if (snap.inventoryHas(k)) return true;
            }
        }
        return false;
    }

    /**
     * 该玩家是否持有任一召唤物（不限伤害类型）。用于 L8_SUMMON_FALLBACK：
     * 某条伤害所有层都匹配失败，且全场只有一个玩家持有召唤物 → 暂归属给他。
     */
    public static boolean hasAnySummonItem(UUID playerUuid) {
        return summonItemCountOf(playerUuid) > 0;
    }

    /**
     * 玩家召唤物件数（按格子计数，不去重）。用于 L8b_SUMMON_SHARED 权重均分。
     * <ul>
     *   <li>背包里有 skullTome + ghastTome + 另一格 skullTome → 返回 3</li>
     *   <li>只有 skullTome → 返回 1</li>
     *   <li>没有任何召唤物 → 返回 0</li>
     * </ul>
     */
    public static int summonItemCountOf(UUID playerUuid) {
        if (playerUuid == null) return 0;
        Snapshot snap = get(playerUuid);
        if (snap == Snapshot.EMPTY) return 0;
        return snap.summonItemCount();
    }

    public static int trackedPlayerCount() {
        return snapshots.size();
    }

    /** 调试：给定玩家当前主手持有的 key。 */
    public static Set<String> dumpMainHand(UUID playerUuid) {
        return get(playerUuid).mainHand();
    }
}
