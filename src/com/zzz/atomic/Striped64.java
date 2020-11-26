/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package com.zzz.atomic;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleBinaryOperator;
import java.util.function.LongBinaryOperator;

/**
 * A package-local class holding common representation and mechanics
 * for classes supporting dynamic striping on 64bit values. The class
 * extends Number so that concrete subclasses must publicly do so.
 */
@SuppressWarnings("serial")
abstract class Striped64 extends Number {
    /*
     * This class maintains a lazily-initialized table of atomically
     * updated variables, plus an extra "base" field. The table size
     * is a power of two. Indexing uses masked per-thread hash codes.
     * Nearly all declarations in this class are package-private,
     * accessed directly by subclasses.
     *
     * Table entries are of class Cell; a variant of AtomicLong padded
     * (via @sun.misc.Contended) to reduce cache contention. Padding
     * is overkill for most Atomics because they are usually
     * irregularly scattered in memory and thus don't interfere much
     * with each other. But Atomic objects residing in arrays will
     * tend to be placed adjacent to each other, and so will most
     * often share cache lines (with a huge negative performance
     * impact) without this precaution.
     *
     * In part because Cells are relatively large, we avoid creating
     * them until they are needed.  When there is no contention, all
     * updates are made to the base field.  Upon first contention (a
     * failed CAS on base update), the table is initialized to size 2.
     * The table size is doubled upon further contention until
     * reaching the nearest power of two greater than or equal to the
     * number of CPUS. Table slots remain empty (null) until they are
     * needed.
     *
     * A single spinlock ("cellsBusy") is used for initializing and
     * resizing the table, as well as populating slots with new Cells.
     * There is no need for a blocking lock; when the lock is not
     * available, threads try other slots (or the base).  During these
     * retries, there is increased contention and reduced locality,
     * which is still better than alternatives.
     *
     * The Thread probe fields maintained via ThreadLocalRandom serve
     * as per-thread hash codes. We let them remain uninitialized as
     * zero (if they come in this way) until they contend at slot
     * 0. They are then initialized to values that typically do not
     * often conflict with others.  Contention and/or table collisions
     * are indicated by failed CASes when performing an update
     * operation. Upon a collision, if the table size is less than
     * the capacity, it is doubled in size unless some other thread
     * holds the lock. If a hashed slot is empty, and lock is
     * available, a new Cell is created. Otherwise, if the slot
     * exists, a CAS is tried.  Retries proceed by "double hashing",
     * using a secondary hash (Marsaglia XorShift) to try to find a
     * free slot.
     *
     * The table size is capped because, when there are more threads
     * than CPUs, supposing that each thread were bound to a CPU,
     * there would exist a perfect hash function mapping threads to
     * slots that eliminates collisions. When we reach capacity, we
     * search for this mapping by randomly varying the hash codes of
     * colliding threads.  Because search is random, and collisions
     * only become known via CAS failures, convergence can be slow,
     * and because threads are typically not bound to CPUS forever,
     * may not occur at all. However, despite these limitations,
     * observed contention rates are typically low in these cases.
     *
     * It is possible for a Cell to become unused when threads that
     * once hashed to it terminate, as well as in the case where
     * doubling the table causes no thread to hash to it under
     * expanded mask.  We do not try to detect or remove such cells,
     * under the assumption that for long-running instances, observed
     * contention levels will recur, so the cells will eventually be
     * needed again; and for short-lived ones, it does not matter.
     */

    /**
     * Padded variant of AtomicLong supporting only raw accesses plus CAS.
     *
     * JVM intrinsics note: It would be possible to use a release-only
     * form of CAS here, if it were provided.
     */
    @sun.misc.Contended static final class Cell {
        volatile long value;
        Cell(long x) { value = x; }

        //CAS 更新 value 的值，更新成功返回 true
        final boolean cas(long cmp, long val) {
            return UNSAFE.compareAndSwapLong(this, valueOffset, cmp, val);
        }

        // Unsafe mechanics
        //Unsafe 实例，用于 CAS 操作
        private static final sun.misc.Unsafe UNSAFE;
        //value 字段的内存偏移量
        private static final long valueOffset;
        static {
            //初始化 value 字段的内存地址偏移量
            try {
                //获取 unsafe 实例
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> ak = Cell.class;
                valueOffset = UNSAFE.objectFieldOffset
                    (ak.getDeclaredField("value"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /** Number of CPUS, to place bound on table size */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * Table of cells. When non-null, size is a power of 2.
     */
    //计数数组，数组长度为2的 n 次幂
    transient volatile Cell[] cells;

    /**
     * Base value, used mainly when there is no contention, but also as
     * a fallback during table initialization races. Updated via CAS.
     */
    //计数基值，在无线程竞争时，cells 未初始化前或者 cells 初始化时其他线程会累加到 base 上
    transient volatile long base;

    /**
     * Spinlock (locked via CAS) used when resizing and/or creating Cells.
     */
    //锁标识 cellsBusy = 0 代表锁空闲，cellsBusy = 1 代表锁被占用
    transient volatile int cellsBusy;

    /**
     * Package-private default constructor
     */
    Striped64() {
    }

    /**
     * CASes the base field.
     */
    final boolean casBase(long cmp, long val) {
        return UNSAFE.compareAndSwapLong(this, BASE, cmp, val);
    }

    /**
     * CASes the cellsBusy field from 0 to 1 to acquire lock.
     */
    final boolean casCellsBusy() {
        return UNSAFE.compareAndSwapInt(this, CELLSBUSY, 0, 1);
    }

    /**
     * Returns the probe value for the current thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     */
    static final int getProbe() {
        return UNSAFE.getInt(Thread.currentThread(), PROBE);
    }

    /**
     * Pseudo-randomly advances and records the given probe value for the
     * given thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     */
    static final int advanceProbe(int probe) {
        probe ^= probe << 13;   // xorshift
        probe ^= probe >>> 17;
        probe ^= probe << 5;
        UNSAFE.putInt(Thread.currentThread(), PROBE, probe);
        return probe;
    }

    /**
     * Handles cases of updates involving initialization, resizing,
     * creating new Cells, and/or contention. See above for
     * explanation. This method suffers the usual non-modularity
     * problems of optimistic retry code, relying on rechecked sets of
     * reads.
     *
     * @param x the value
     * @param fn the update function, or null for add (this convention
     * avoids the need for an extra field or function in LongAdder).
     * @param wasUncontended false if CAS failed before call
     */
    final void longAccumulate(long x, LongBinaryOperator fn,
                              boolean wasUncontended) {
        //线程 hash 值
        int h;
        //获取线程的非0的hash值
        if ((h = getProbe()) == 0) {     //如果线程的 hash 值为0，重新获取 hash 值
            ThreadLocalRandom.current(); //强制初始化
            h = getProbe();              //重新获取 hash 值
            wasUncontended = true;
        }
        boolean collide = false;         //如果最后一个槽也是非 null 的，为 true
        for (;;) {                       //自旋循环，CAS 累加值直至成功
            Cell[] as; Cell a; int n; long v;
            //cells 已经初始化完毕
            if ((as = cells) != null && (n = as.length) > 0) {
                //如果索引位置还未初始化
                if ((a = as[(n - 1) & h]) == null) {
                    //锁空闲
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        //新建 Cell 实例并赋初值 x，用于后面初始化槽
                        Cell r = new Cell(x);   // Optimistically create
                        //先加锁，加锁成功将值 x 累加到对应槽中
                        if (cellsBusy == 0 && casCellsBusy()) {
                            //是否成功初始化槽标识
                            boolean created = false;
                            //加锁成功后，需再次检查之前判断
                            try {               // Recheck under lock
                                Cell[] rs; int m, j;
                                if ((rs = cells) != null && //cells 已经初始化
                                    (m = rs.length) > 0 &&  //cells 已经初始化
                                    rs[j = (m - 1) & h] == null) {  //槽未初始化
                                    rs[j] = r;  //初始化槽
                                    created = true; //初始化槽成功
                                }
                            } finally {
                                cellsBusy = 0;  //释放锁，单线程，不需要使用 CAS 更新
                            }
                            //累加成功，直接退出
                            if (created)
                                break;
                            //初始化槽，需要自旋重试
                            continue;           // Slot is now non-empty
                        }
                    }
                    //cellsBusy 锁正在被其他线程使用
                    collide = false;
                }
                //CAS 更新失败
                else if (!wasUncontended)       // CAS already known to fail
                    //重新计算线程 hash 值后重试
                    wasUncontended = true;      // Continue after rehash
                //CAS 更新槽 CAS 失败，向下继续
                else if (a.cas(v = a.value, ((fn == null) ? v + x :
                                             fn.applyAsLong(v, x))))
                    //CAS 成功，退出
                    break;
                //扩容判断
                else if (n >= NCPU || cells != as)
                    //cells 已达到最大容量或者 as 指针过期说明其他线程抢先完成扩容
                    collide = false;            // At max size or stale
                //线程执行到这里，说明当前线程具备扩容条件
                else if (!collide)
                    //下次自旋开始扩容，在正式扩容前，会再次尝试一次 CAS 更新
                    collide = true;
                //开始扩容
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        //再次校验，如果不相等说明有其他线程完成了扩容操作
                        if (cells == as) {      // Expand table unless stale
                            //扩容两倍
                            Cell[] rs = new Cell[n << 1];
                            //原数组内元素迁移到新数组中相同位置
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        //解锁
                        cellsBusy = 0;
                    }
                    collide = false;
                    //扩容后，自旋重试
                    continue;                   // Retry with expanded table
                }
                //重新计算 hash 值
                h = advanceProbe(h);
            }
            //cell 还未初始化且加锁成功
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                //cells 初始化标识，true 代表初始化成功
                boolean init = false;
                //初始化数组 cells
                try {                           // Initialize table
                    //再次校验，如果 cells == as，说明 cells 还未初始化
                    if (cells == as) {
                        //初始化容量为2
                        Cell[] rs = new Cell[2];
                        //使用 h & 1 计算索引位置，初始化索引位置并赋初值（x 要累加的值）
                        rs[h & 1] = new Cell(x);
                        cells = rs;
                        //初始化成功
                        init = true;
                    }
                } finally {
                    //释放锁
                    cellsBusy = 0;
                }
                //如果初始化成功，说明值已经更新到 cells 数组中。直接退出方法
                if (init)
                    break;
            }
            //正在初始化 cells
            else if (casBase(v = base, ((fn == null) ? v + x :
                                        fn.applyAsLong(v, x))))
                //CAS 尝试将值 x 累加到 base 上，如果 CAS 更新成功，直接退出，否则转为自旋重试
                break;                          // Fall back on using base
        }
    }

    /**
     * Same as longAccumulate, but injecting long/double conversions
     * in too many places to sensibly merge with long version, given
     * the low-overhead requirements of this class. So must instead be
     * maintained by copy/paste/adapt.
     */
    final void doubleAccumulate(double x, DoubleBinaryOperator fn,
                                boolean wasUncontended) {
        int h;
        if ((h = getProbe()) == 0) {
            ThreadLocalRandom.current(); // force initialization
            h = getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            Cell[] as; Cell a; int n; long v;
            if ((as = cells) != null && (n = as.length) > 0) {
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        Cell r = new Cell(Double.doubleToRawLongBits(x));
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // Recheck under lock
                                Cell[] rs; int m, j;
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                else if (a.cas(v = a.value,
                               ((fn == null) ?
                                Double.doubleToRawLongBits
                                (Double.longBitsToDouble(v) + x) :
                                Double.doubleToRawLongBits
                                (fn.applyAsDouble
                                 (Double.longBitsToDouble(v), x)))))
                    break;
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = advanceProbe(h);
            }
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(Double.doubleToRawLongBits(x));
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            else if (casBase(v = base,
                             ((fn == null) ?
                              Double.doubleToRawLongBits
                              (Double.longBitsToDouble(v) + x) :
                              Double.doubleToRawLongBits
                              (fn.applyAsDouble
                               (Double.longBitsToDouble(v), x)))))
                break;                          // Fall back on using base
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    //base 字段的内存偏移量
    private static final long BASE;
    //cellsBusy 字段的内存偏移量
    private static final long CELLSBUSY;
    //线程的 threadLocalRandomProbe 字段的内存偏移量
    private static final long PROBE;
    static {
        //初始化字段的内存地址偏移量
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> sk = java.util.concurrent.atomic.Striped64.class;
            BASE = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("base"));
            CELLSBUSY = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("cellsBusy"));
            Class<?> tk = Thread.class;
            PROBE = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("threadLocalRandomProbe"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
