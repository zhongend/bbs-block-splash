package com.example.bbsanimatedbreak.composer;

/**
 * 方块静态属性缓存 —— 规范 §64-69
 *
 * === 第一性原理 ===
 * 一个方块相对于选区中心的距离、高度比、随机数……**不随时间变化**。
 * 它们只取决于"方块是谁"和"选区长什么样"。
 *
 * 所以它们必须在 Selection Bake 阶段**算一次**，而不是每 tick 重算。
 * 规范 §66 的反例正是最常见的性能杀手：
 *
 *     每 tick   distance() + hash() + height() + selection()
 *     1000 blocks × 1200 ticks × 4 次计算  =  480 万次无用计算
 *
 * === 为什么用 SoA（Structure of Arrays）===
 * 热路径遍历的是"所有方块的某一个属性"，而不是"一个方块的所有属性"。
 * `double[] distance` 连续内存 → 一次 cache line 装 8 个方块的距离；
 * 若是 `BlockAttributes[]`（AoS），每次访问要跨过 100+ 字节的对象头与其它字段，
 * 1000 个方块就是 1000 次 cache miss。
 *
 * 规范 §68-69 还要求把 `stableBlockId → dense int index` 索引化：
 * 热路径只传 `int index`，不碰 HashMap、不碰 BlockPos。
 *
 * === 不可变性 ===
 * 构建完成后所有数组不再修改（字段是 final 引用）。这是缓存的前提：
 * 多个消费者（Effect 求值、物理初始化、烘焙）可以安全共享一份。
 */
public final class BlockAttributes
{
    /** 方块数量（= 所有数组的长度） */
    public final int count;

    /* === 身份（规范 §4 Source Target：只保留最基本信息）=== */

    /** 稳定方块 ID（{@link Deterministic#stableBlockId}），跨会话不变 */
    public final long[] stableId;

    /** 原始方块坐标（世界坐标） */
    public final int[] blockX;
    public final int[] blockY;
    public final int[] blockZ;

    /** 方块中心的世界坐标（x+0.5, y+0.5, z+0.5）—— 物理与烘焙用这个 */
    public final double[] centerX;
    public final double[] centerY;
    public final double[] centerZ;

    /* === 几何属性 === */

    /** 到选区中心的三维距离（方块） */
    public final double[] distance;

    /** distance / maxDistance，夹在 [0,1]；maxDistance 为 0 时全 0 */
    public final float[] normalizedDistance;

    /** 高度比 [0,1]：选区最低层 = 0，最高层 = 1（用于"从下往上"这类效果） */
    public final float[] height01;

    /** 选区内的归一化位置 [0,1]^3（用于方向性效果） */
    public final float[] normalizedX;
    public final float[] normalizedY;
    public final float[] normalizedZ;

    /* === 确定性随机（规范 §67）=== */

    /** [0,1) */
    public final float[] random01;

    /** [-1,1) */
    public final float[] randomSigned;

    /* === 选区权重 === */

    /**
     * 该方块被选中的权重 [0,1]
     *
     * 圆形/三角形等不规则选区里，边缘方块可以带部分权重（羽化），
     * 让效果边界不是一刀切。矩形选区时恒为 1。
     */
    public final float[] selectionWeight;

    /** 选区包围盒（用于把世界坐标映射到 [0,1]） */
    public final int minX, minY, minZ, maxX, maxY, maxZ;

    /** 选区中心 */
    public final double centerOfMassX, centerOfMassY, centerOfMassZ;

    private BlockAttributes(int count,
                            long[] stableId, int[] blockX, int[] blockY, int[] blockZ,
                            double[] centerX, double[] centerY, double[] centerZ,
                            double[] distance, float[] normalizedDistance, float[] height01,
                            float[] normalizedX, float[] normalizedY, float[] normalizedZ,
                            float[] random01, float[] randomSigned, float[] selectionWeight,
                            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                            double centerOfMassX, double centerOfMassY, double centerOfMassZ)
    {
        this.count = count;
        this.stableId = stableId;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.distance = distance;
        this.normalizedDistance = normalizedDistance;
        this.height01 = height01;
        this.normalizedX = normalizedX;
        this.normalizedY = normalizedY;
        this.normalizedZ = normalizedZ;
        this.random01 = random01;
        this.randomSigned = randomSigned;
        this.selectionWeight = selectionWeight;
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        this.centerOfMassX = centerOfMassX;
        this.centerOfMassY = centerOfMassY;
        this.centerOfMassZ = centerOfMassZ;
    }

    /**
     * 构建器 —— 只有它能填数组，构建完就冻结
     *
     * 用构建器而不是静态工厂里的局部变量，是为了让"计算一次"这件事
     * 在类型上就看得见：BlockAttributes 没有 setter。
     */
    public static final class Builder
    {
        private final int count;
        private final long seed;
        private final int effectId;

        private final long[] stableId;
        private final int[] blockX, blockY, blockZ;
        private final double[] centerX, centerY, centerZ;
        private final double[] distance;
        private final float[] normalizedDistance;
        private final float[] height01;
        private final float[] normalizedX, normalizedY, normalizedZ;
        private final float[] random01, randomSigned, selectionWeight;

        private int cursor;
        private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        private double sumX, sumY, sumZ;

        public Builder(int count, long seed, int effectId)
        {
            this.count = count;
            this.seed = seed;
            this.effectId = effectId;

            this.stableId = new long[count];
            this.blockX = new int[count];
            this.blockY = new int[count];
            this.blockZ = new int[count];
            this.centerX = new double[count];
            this.centerY = new double[count];
            this.centerZ = new double[count];
            this.distance = new double[count];
            this.normalizedDistance = new float[count];
            this.height01 = new float[count];
            this.normalizedX = new float[count];
            this.normalizedY = new float[count];
            this.normalizedZ = new float[count];
            this.random01 = new float[count];
            this.randomSigned = new float[count];
            this.selectionWeight = new float[count];
        }

        /** 追加一个方块（dense index 就是追加顺序，规范 §68） */
        public int add(int x, int y, int z, float weight)
        {
            int i = this.cursor++;

            long id = Deterministic.stableBlockId(x, y, z);

            this.stableId[i] = id;
            this.blockX[i] = x;
            this.blockY[i] = y;
            this.blockZ[i] = z;
            this.centerX[i] = x + 0.5D;
            this.centerY[i] = y + 0.5D;
            this.centerZ[i] = z + 0.5D;

            this.random01[i] = Deterministic.unit(this.seed, id, this.effectId);
            this.randomSigned[i] = this.random01[i] * 2F - 1F;
            this.selectionWeight[i] = weight;

            if (x < this.minX) this.minX = x;
            if (y < this.minY) this.minY = y;
            if (z < this.minZ) this.minZ = z;
            if (x > this.maxX) this.maxX = x;
            if (y > this.maxY) this.maxY = y;
            if (z > this.maxZ) this.maxZ = z;

            this.sumX += x + 0.5D;
            this.sumY += y + 0.5D;
            this.sumZ += z + 0.5D;

            return i;
        }

        /**
         * 第二遍：依赖包围盒的属性（距离、归一化坐标）必须等所有方块都进来才能算
         *
         * 两遍是刻意的：单遍算距离就得先知道包围盒，而包围盒要遍历完才知道。
         * 试图"边扫边修正"会让距离在扫的过程中不断变化 —— 那就不是确定性属性了。
         */
        public BlockAttributes build()
        {
            int n = this.cursor;

            if (n == 0)
            {
                return new BlockAttributes(0, this.stableId, this.blockX, this.blockY, this.blockZ,
                    this.centerX, this.centerY, this.centerZ, this.distance, this.normalizedDistance,
                    this.height01, this.normalizedX, this.normalizedY, this.normalizedZ,
                    this.random01, this.randomSigned, this.selectionWeight,
                    0, 0, 0, 0, 0, 0, 0D, 0D, 0D);
            }

            double cx = this.sumX / n;
            double cy = this.sumY / n;
            double cz = this.sumZ / n;

            double maxDistance = 0D;

            for (int i = 0; i < n; i++)
            {
                double dx = this.centerX[i] - cx;
                double dy = this.centerY[i] - cy;
                double dz = this.centerZ[i] - cz;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);

                this.distance[i] = d;

                if (d > maxDistance)
                {
                    maxDistance = d;
                }
            }

            double invMax = maxDistance > 1.0E-9D ? 1D / maxDistance : 0D;

            int spanX = Math.max(1, this.maxX - this.minX);
            int spanY = Math.max(1, this.maxY - this.minY);
            int spanZ = Math.max(1, this.maxZ - this.minZ);
            double invSpanX = 1D / spanX;
            double invSpanY = 1D / spanY;
            double invSpanZ = 1D / spanZ;

            for (int i = 0; i < n; i++)
            {
                this.normalizedDistance[i] = (float) (this.distance[i] * invMax);
                this.height01[i] = (float) ((this.blockY[i] - this.minY) * invSpanY);
                this.normalizedX[i] = (float) ((this.blockX[i] - this.minX) * invSpanX);
                this.normalizedY[i] = (float) ((this.blockY[i] - this.minY) * invSpanY);
                this.normalizedZ[i] = (float) ((this.blockZ[i] - this.minZ) * invSpanZ);
            }

            return new BlockAttributes(n, this.stableId, this.blockX, this.blockY, this.blockZ,
                this.centerX, this.centerY, this.centerZ, this.distance, this.normalizedDistance,
                this.height01, this.normalizedX, this.normalizedY, this.normalizedZ,
                this.random01, this.randomSigned, this.selectionWeight,
                this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ, cx, cy, cz);
        }
    }

    /**
     * 供调试与报告用的粗略内存占用（字节）
     *
     * 规范 §30 要求 UI 能显示数据量；这是"轻量轨道"是不是真的轻量的自检手段。
     */
    public long estimatedBytes()
    {
        int n = this.count;

        // long 8 + int 3×4 + double 6×8 + float 9×4 = 8+12+48+36 = 104 字节/方块
        return (long) n * 104L;
    }
}
