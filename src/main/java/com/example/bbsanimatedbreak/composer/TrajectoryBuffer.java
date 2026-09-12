package com.example.bbsanimatedbreak.composer;

/**
 * 轨迹缓存（Compact Trajectory）—— 规范 §6 / §20-23
 *
 * === 第一性原理：为什么不存"每 tick 一个对象" ===
 * 规范 §1 给出了项目真实出现过的数字：
 *
 *     300 blocks × 1200 ticks ≈ 360,000 samples ≈ 43 MB Recording
 *
 * 43 MB / 360,000 ≈ 120 字节/样本 —— 这就是一个 Java 对象的真实代价
 * （对象头 16 + 字段 + 引用 + 对齐填充）。而且这 36 万个对象会：
 *   ① 全部进老年代，制造 GC 停顿；
 *   ② 内存不连续，烘焙时遍历等于随机访存。
 *
 * 所以这里是 **SoA（Structure of Arrays）原始数组**：
 * 每个字段一条连续数组，样本是"第 i 个"，而不是"第 i 个对象"。
 * 一个样本 75 字节、无对象头、无 GC 压力、顺序访存。
 *
 * === 第二性原理：不存"每 tick" ===
 * 规范 §22-23：连续一段 velocity≈0、angularVelocity≈0 的静止段，
 * 没有理由逐 tick 记录。真正需要记住的是**运动**。
 *
 * 于是采集规则只有一条，而且它自带误差上界：
 *
 *     仅当 |p − p_上次记录| > posEpsilon  或  ∠(q, q_上次记录) > rotEpsilon 时才记录
 *
 * 这不是"压缩"，这是**有损采样但误差有硬上界**。
 * 静止 140 个 tick 只留 2 个样本（进入静止、离开静止），
 * 而"缓慢漂移"不会偷偷溜过阈值 —— 因为比较的是**上一次记录的值**，不是上一 tick 的值。
 *
 * === 第三性原理：热路径零分配 ===
 * 规范 §71 记录了 JNI 零分配的经验（30,000 临时数组/秒 → GC 风暴）。
 * 这里的 append 只写数组、只自增计数器；容量不足时按几何级数扩容，
 * 扩容次数是 O(log n)，摊销到每次 append 是 O(1)。
 *
 * === 采样是"按 tick 交错追加"的 ===
 * 采集时按 (tick, body) 顺序 append，所以样本在数组里是交错的。
 * 烘焙前做一次**计数排序**（{@link #compactByBody}）把每个方块的样本聚成连续区间 ——
 * 这一步只在烘焙时做一次，不在热路径。
 */
public final class TrajectoryBuffer
{
    /** 样本标志：本采样点发生了碰撞（规范 §27，精简时是保护点） */
    public static final int FLAG_COLLISION = 1;

    /** 样本标志：本采样点是阶段交接（Physics/Kinematic/Effect 的 start/end，规范 §28） */
    public static final int FLAG_HANDOFF = 2;

    /** 样本标志：本采样点是"从静止重新开始运动"的第一个点 */
    public static final int FLAG_WAKE = 4;

    /** 初始容量（样本数） */
    private static final int INITIAL_CAPACITY = 4096;

    /* === 样本 SoA === */
    private int[] sampleBody;
    private int[] sampleTick;
    private byte[] sampleFlags;
    private double[] posX, posY, posZ;
    private float[] rotX, rotY, rotZ, rotW;
    private double[] velX, velY, velZ;
    private int size;

    /* === 每个刚体的采集状态 === */
    private int bodyCount;
    private int[] bodySamples;      // 该刚体已记录的样本数
    private int[] bodyFirst;        // compact() 之后：该刚体第一个样本在 order[] 中的下标
    private int[] bodyOrderStart;   // compact() 之后：该刚体在 order[] 中的起始位置

    /** 上一次**已记录**的位姿（用于漂移判定，而不是上一 tick 的位姿） */
    private double[] lastX, lastY, lastZ;
    private float[] lastQx, lastQy, lastQz, lastQw;
    private int[] lastTick;
    private boolean[] everRecorded;

    /** 位置漂移阈值（方块）：超过就记一个样本 */
    public double positionEpsilon = 0.002D;

    /** 旋转漂移阈值（弧度）：约 0.115° */
    public float rotationEpsilon = 0.002F;

    /* === 统计（规范 §30：UI 要显示 Raw / Reduced / Error）=== */
    private int totalCapturedTicks;

    public TrajectoryBuffer(int bodyCount)
    {
        this.ensureBodies(bodyCount);
        this.grow(INITIAL_CAPACITY);
    }

    /** 声明刚体数量（= 物理世界里的动态方块数，也是 dense index 的上界） */
    public void ensureBodies(int count)
    {
        if (count <= this.bodyCount)
        {
            return;
        }

        this.bodyCount = count;
        this.bodySamples = new int[count];
        this.bodyFirst = new int[count];
        this.bodyOrderStart = new int[count + 1];
        this.lastX = new double[count];
        this.lastY = new double[count];
        this.lastZ = new double[count];
        this.lastQx = new float[count];
        this.lastQy = new float[count];
        this.lastQz = new float[count];
        this.lastQw = new float[count];
        this.lastTick = new int[count];
        this.everRecorded = new boolean[count];
    }

    /* ================================================================
     * 采集（热路径）
     * ================================================================ */

    /**
     * 采集一个刚体在本 tick 的位姿
     *
     * @param body    刚体的 dense index（0..bodyCount-1）
     * @param tick    局部 tick（相对片段起点）
     * @param forced  强制记录（交接点 / 碰撞点——规范 §27-28 的保护点不允许被采样规则丢掉）
     * @return 是否真的写入了一个样本
     */
    public boolean capture(int body, int tick,
                           double x, double y, double z,
                           float qx, float qy, float qz, float qw,
                           double vx, double vy, double vz,
                           int flags, boolean forced)
    {
        if (body < 0 || body >= this.bodyCount)
        {
            return false;
        }

        this.totalCapturedTicks++;

        // 第一次采到：无论动没动都必须记（否则这个方块没有轨迹）
        boolean first = !this.everRecorded[body];
        boolean store = first || forced;

        if (!store)
        {
            double dx = x - this.lastX[body];
            double dy = y - this.lastY[body];
            double dz = z - this.lastZ[body];

            if (dx * dx + dy * dy + dz * dz > this.positionEpsilon * this.positionEpsilon)
            {
                store = true;
            }
            else
            {
                // 四元数夹角：|dot| 越接近 1 越接近同向；用 1-|dot| 避免 acos 的开销
                double dot = this.lastQx[body] * qx + this.lastQy[body] * qy
                           + this.lastQz[body] * qz + this.lastQw[body] * qw;

                if (1D - Math.abs(dot) > this.rotationEpsilon)
                {
                    store = true;
                }
            }
        }

        if (!store)
        {
            return false;
        }

        if (first)
        {
            flags |= FLAG_HANDOFF;
        }

        this.append(body, tick, x, y, z, qx, qy, qz, qw, vx, vy, vz, flags);

        // 记录"已记录的位姿"，作为下一次漂移判定的基准
        this.lastX[body] = x;
        this.lastY[body] = y;
        this.lastZ[body] = z;
        this.lastQx[body] = qx;
        this.lastQy[body] = qy;
        this.lastQz[body] = qz;
        this.lastQw[body] = qw;
        this.lastTick[body] = tick;
        this.everRecorded[body] = true;

        return true;
    }

    private void append(int body, int tick,
                        double x, double y, double z,
                        float qx, float qy, float qz, float qw,
                        double vx, double vy, double vz,
                        int flags)
    {
        if (this.size == this.sampleBody.length)
        {
            this.grow(this.sampleBody.length << 1);
        }

        int i = this.size++;

        this.sampleBody[i] = body;
        this.sampleTick[i] = tick;
        this.sampleFlags[i] = (byte) flags;
        this.posX[i] = x; this.posY[i] = y; this.posZ[i] = z;
        this.rotX[i] = qx; this.rotY[i] = qy; this.rotZ[i] = qz; this.rotW[i] = qw;
        this.velX[i] = vx; this.velY[i] = vy; this.velZ[i] = vz;

        this.bodySamples[body]++;
    }

    /** 几何级数扩容（摊销 O(1)） */
    private void grow(int capacity)
    {
        if (this.sampleBody != null && capacity <= this.sampleBody.length)
        {
            return;
        }

        int n = Math.max(INITIAL_CAPACITY, capacity);

        this.sampleBody = copyOf(this.sampleBody, n);
        this.sampleTick = copyOf(this.sampleTick, n);
        this.sampleFlags = copyOf(this.sampleFlags, n);
        this.posX = copyOf(this.posX, n);
        this.posY = copyOf(this.posY, n);
        this.posZ = copyOf(this.posZ, n);
        this.rotX = copyOf(this.rotX, n);
        this.rotY = copyOf(this.rotY, n);
        this.rotZ = copyOf(this.rotZ, n);
        this.rotW = copyOf(this.rotW, n);
        this.velX = copyOf(this.velX, n);
        this.velY = copyOf(this.velY, n);
        this.velZ = copyOf(this.velZ, n);
    }

    /* ================================================================
     * 烘焙前的重排（一次性）
     * ================================================================ */

    /**
     * 把交错的样本按刚体聚成连续区间（计数排序，O(n)）
     *
     * 返回的 order[] 是"第 k 个连续样本对应原始下标"。
     * 之后遍历某个刚体的样本就是 order[bodyOrderStart[b] .. bodyOrderStart[b+1])，
     * 全是连续内存 —— 满足了 §69 的 data-oriented 访问。
     */
    public int[] compactByBody()
    {
        int n = this.bodyCount;

        this.bodyOrderStart = new int[n + 1];
        this.bodyFirst = new int[n];

        for (int b = 0; b < n; b++)
        {
            this.bodyOrderStart[b + 1] = this.bodyOrderStart[b] + this.bodySamples[b];
        }

        int[] cursor = new int[n];
        int[] order = new int[this.size];

        for (int i = 0; i < this.size; i++)
        {
            int b = this.sampleBody[i];
            order[this.bodyOrderStart[b] + cursor[b]++] = i;
        }

        // 排序后同一刚体的样本按原始顺序（即 tick 递增）排列，因为计数排序是稳定的
        for (int b = 0; b < n; b++)
        {
            this.bodyFirst[b] = this.bodyOrderStart[b];
        }

        return order;
    }

    /* ================================================================
     * 访问器（供精简器与烘焙器使用）
     * ================================================================ */

    public int size()
    {
        return this.size;
    }

    public int bodyCount()
    {
        return this.bodyCount;
    }

    public int samplesOf(int body)
    {
        return this.bodySamples[body];
    }

    /**
     * 该刚体第一个样本在 {@link #compactByBody()} 返回的 order[] 中的下标
     *
     * 烘焙时用它定位"这个方块的样本区间"：
     * [firstSampleOf(b), firstSampleOf(b) + samplesOf(b))。
     */
    public int firstSampleOf(int body)
    {
        return this.bodyFirst[body];
    }

    public int tickAt(int i) { return this.sampleTick[i]; }
    public byte flagsAt(int i) { return this.sampleFlags[i]; }
    public double posX(int i) { return this.posX[i]; }
    public double posY(int i) { return this.posY[i]; }
    public double posZ(int i) { return this.posZ[i]; }
    public float rotX(int i) { return this.rotX[i]; }
    public float rotY(int i) { return this.rotY[i]; }
    public float rotZ(int i) { return this.rotZ[i]; }
    public float rotW(int i) { return this.rotW[i]; }
    public double velX(int i) { return this.velX[i]; }
    public double velY(int i) { return this.velY[i]; }
    public double velZ(int i) { return this.velZ[i]; }

    /** 采集过的 tick 总数（Raw Samples，规范 §30） */
    public int capturedTicks()
    {
        return this.totalCapturedTicks;
    }

    /** 估算内存占用（字节） */
    public long estimatedBytes()
    {
        int cap = this.sampleBody == null ? 0 : this.sampleBody.length;

        // int body + int tick + byte flags + 3 double + 4 float + 3 double
        // = 4 + 4 + 1 + 24 + 16 + 24 = 73 字节/样本（未计对齐填充）
        return (long) cap * 73L + (long) this.bodyCount * 64L;
    }

    /* === 数组复制小工具（避免 import java.util.Arrays 时的静态调用开销差异）=== */

    private static int[] copyOf(int[] a, int n)
    {
        int[] b = new int[n];
        if (a != null) System.arraycopy(a, 0, b, 0, Math.min(a.length, n));
        return b;
    }

    private static byte[] copyOf(byte[] a, int n)
    {
        byte[] b = new byte[n];
        if (a != null) System.arraycopy(a, 0, b, 0, Math.min(a.length, n));
        return b;
    }

    private static double[] copyOf(double[] a, int n)
    {
        double[] b = new double[n];
        if (a != null) System.arraycopy(a, 0, b, 0, Math.min(a.length, n));
        return b;
    }

    private static float[] copyOf(float[] a, int n)
    {
        float[] b = new float[n];
        if (a != null) System.arraycopy(a, 0, b, 0, Math.min(a.length, n));
        return b;
    }
}
