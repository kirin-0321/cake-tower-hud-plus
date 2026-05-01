package com.ctt.healthdisplay.server.filter;

import java.util.Arrays;

/**
 * v8.x · 异常伤害过滤器 G7b 训练样本的固定容量滑窗（per-(player, weapon) 一份）。
 *
 * <h2>语义</h2>
 * <p>容量 N（默认 100）的双结构 ring：
 * <ul>
 *   <li>{@code sorted[]} —— 已排序数组，{@link #p95} 直接索引取第 95 大元素</li>
 *   <li>{@code insertionOrder[]} —— 插入顺序环形缓冲，达到容量后挤掉最老元素</li>
 * </ul>
 *
 * <h2>性能</h2>
 * <p>{@link #observe} 是 O(log N) 二分定位 + O(N) {@code arraycopy} 移动 ≈ 50 ns/op。
 * {@link #p95} 是 O(1) 索引读 ≈ 5 ns/op。CTT 高强度战斗 200 events/s × 4 玩家
 * × 多武器 ≈ 800 ops/s，总开销 ~40 µs/s——可忽略。
 *
 * <h2>线程安全</h2>
 * <p>**非线程安全**——上层 {@link PerPlayerWeaponP95Registry} 在 server tick 主线程独占调用。
 * 测试代码可加 synchronized 包装。
 *
 * <h2>P95 算法</h2>
 * <p>{@code p95 = sorted[(int) Math.ceil(0.95 × size) - 1]}——保守取整（满 100 时取第 95 大；
 * 不足 100 时按比例）。当 {@code size < minSamples} 时 {@link #p95(int)} 返回 -1，
 * 调用方据此跳过 outlier 判定（详见 {@code DAMAGE_FILTER_DESIGN_V2.md} §6.4）。
 */
public final class RollingWindow {

    private final int capacity;
    private final int[] sorted;
    private final int[] insertionOrder;

    private int size = 0;
    private int insertHead = 0;

    public RollingWindow(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.sorted = new int[capacity];
        this.insertionOrder = new int[capacity];
    }

    /** 当前样本数（≤ capacity）。 */
    public int size() { return size; }

    /** 容量（构造时固定）。 */
    public int capacity() { return capacity; }

    /**
     * 加入一个样本（damage 必须 &gt; 0）。
     * <p>满容量时挤掉最老的一个：从 sorted 中二分定位并 arraycopy 移除，再二分定位插入新值。
     * 未满时直接二分插入。
     */
    public void observe(int damage) {
        if (damage <= 0) return;

        if (size == capacity) {
            int oldest = insertionOrder[insertHead];
            removeFromSorted(oldest);
        } else {
            size++;
        }

        insertIntoSorted(damage);
        insertionOrder[insertHead] = damage;
        insertHead = (insertHead + 1) % capacity;
    }

    /**
     * 返回 P95 值（保守版本：第 ⌈0.95 × size⌉ 大的样本）。
     *
     * @param minSamples 最少样本要求；当前 size &lt; minSamples 时返回 -1
     * @return P95 值；样本不足或窗口为空时返回 -1
     */
    public int p95(int minSamples) {
        if (size < minSamples || size == 0) return -1;
        // ceil(0.95 × size)：满 100 时 = 95（取第 95 大，sorted 索引 94）
        //                   size=20 时 = 19（取第 19 大，sorted 索引 18）
        int rank = (int) Math.ceil(0.95 * size);
        if (rank < 1) rank = 1;
        if (rank > size) rank = size;
        return sorted[rank - 1];
    }

    /** 测试 / 诊断用：拷贝当前已排序快照。 */
    public int[] sortedSnapshot() {
        int[] copy = new int[size];
        System.arraycopy(sorted, 0, copy, 0, size);
        return copy;
    }

    /** 清空窗口（玩家 DISCONNECT / start 联动调）。 */
    public void clear() {
        Arrays.fill(sorted, 0, size, 0);
        Arrays.fill(insertionOrder, 0);
        size = 0;
        insertHead = 0;
    }

    // =========================================================================
    //  内部：sorted 数组的二分插入 / 删除
    // =========================================================================

    private void insertIntoSorted(int value) {
        // 在 sorted[0..size-1]（注意 size 已经在调用前 +1 或保持满容量）找插入点
        int upperBound = (size == capacity) ? capacity - 1 : size - 1;
        int idx = binarySearchInsertionPoint(value, upperBound);
        // 把 [idx..upperBound-1] 整体往后挪一格腾出 idx 位
        if (idx <= upperBound - 1) {
            System.arraycopy(sorted, idx, sorted, idx + 1, upperBound - idx);
        }
        sorted[idx] = value;
    }

    private void removeFromSorted(int value) {
        // 在 sorted[0..size-1]（size 在调用前未递减；满容量时 size==capacity）二分找出第一个等于 value 的位置
        int idx = binarySearchValue(value);
        if (idx < 0) return; // 不应发生——insertionOrder 与 sorted 对齐
        // 把 [idx+1..size-1] 整体往前挪一格盖掉 idx
        if (idx < size - 1) {
            System.arraycopy(sorted, idx + 1, sorted, idx, size - 1 - idx);
        }
        sorted[size - 1] = 0;
    }

    /** 在 sorted[0..upperBound] 找最左插入点（保持升序）。 */
    private int binarySearchInsertionPoint(int value, int upperBound) {
        int lo = 0, hi = upperBound + 1; // hi 是 exclusive 上界
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < value) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** 在 sorted[0..size-1] 找第一个 == value 的索引；找不到返回 -1。 */
    private int binarySearchValue(int value) {
        int lo = 0, hi = size - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cur = sorted[mid];
            if (cur < value) lo = mid + 1;
            else if (cur > value) hi = mid - 1;
            else {
                // 命中，但可能有重复——往左探到第一个
                while (mid > 0 && sorted[mid - 1] == value) mid--;
                return mid;
            }
        }
        return -1;
    }
}
