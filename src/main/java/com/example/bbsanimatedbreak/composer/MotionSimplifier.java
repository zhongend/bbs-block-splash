package com.example.bbsanimatedbreak.composer;

/**
 * 运动感知关键帧精简器 —— 规范 §24-34
 *
 * === 第一性原理：为什么不能"每 N tick 取一个" ===
 * 规范 §26 给了反例：tick 40 与 tick 41 位置几乎相同，但**速度剧变**。
 * "每 N tick 采样"会把 41 丢掉，于是弹跳的拐点消失，
 * 回放出来是一条平滑抛物线 —— 物理感没了。
 *
 * 关键帧的意义不是"记录位置"，而是**在误差容许范围内用最少的点重建曲线**。
 * 这就是 Douglas–Peucker 的出发点：递归地保留"偏离直线最多"的点。
 * 它的三个性质恰好就是规范要的：
 *
 *   ① 高速段自然多留键（偏离大）—— §33
 *   ② 低速段自然少留键（几乎共线）—— §33
 *   ③ 完全静止段只剩两端 2 个键 —— §34
 *
 * 不需要为"高速/低速/静止"写三条规则，它们是一条规则的三个结果。
 *
 * === 误差度量必须是位置的**和**旋转的 ===
 * 规范 §25 列出要考虑的量。位置与旋转是两种不同量纲，
 * 不能相加，所以分别与各自的预算比较：**任一超预算就保留这个点**。
 *
 * 旋转用四元数夹角而不是欧拉角差：欧拉角在万向节附近会爆炸，
 * 一个 0.1° 的真实旋转可能表现为某个轴 179° 的变化，导致误判。
 *
 * === 保护点：不可被精简删除（规范 §27-28）===
 * 碰撞事件、阶段交接（Physics/Kinematic/Effect 的起止）承载的是**语义**，
 * 不只是几何。删掉碰撞那一帧，回放里方块就会穿过地面；
 * 删掉交接帧，物理段与程序段的衔接会错位。
 * 所以它们先被标记为"必留"，DP 只在它们之间分段处理。
 */
public final class MotionSimplifier
{
    /** 精简结果 */
    public static final class Result
    {
        /** 保留下来的样本下标（指向 compactByBody 给出的 order[]，升序） */
        public final int[] indices;

        /** 原始样本数（本区间） */
        public final int rawSamples;

        /** 被保护点强制保留的数量 */
        public final int protectedCount;

        /** 实际最大位置误差（方块） */
        public final double maxPositionError;

        /** 实际最大旋转误差（度） */
        public final double maxRotationError;

        Result(int[] indices, int rawSamples, int protectedCount,
               double maxPositionError, double maxRotationError)
        {
            this.indices = indices;
            this.rawSamples = rawSamples;
            this.protectedCount = protectedCount;
            this.maxPositionError = maxPositionError;
            this.maxRotationError = maxRotationError;
        }

        /** 压缩比，1.0 = 没压掉任何东西 */
        public float ratio()
        {
            return this.rawSamples == 0 ? 1F : (float) this.indices.length / (float) this.rawSamples;
        }
    }

    private MotionSimplifier()
    {
    }

    /**
     * 精简一个刚体的连续样本区间
     *
     * @param buffer    轨迹缓存
     * @param order     {@link TrajectoryBuffer#compactByBody()} 返回的重排表
     * @param from      order 中该刚体区间的起始位置（含）
     * @param to        order 中该刚体区间的结束位置（不含）
     * @param posBudget 位置误差预算（方块）
     * @param rotBudget 旋转误差预算（弧度）
     */
    public static Result simplify(TrajectoryBuffer buffer, int[] order,
                                  int from, int to,
                                  double posBudget, float rotBudget)
    {
        int n = to - from;

        if (n <= 0)
        {
            return new Result(new int[0], 0, 0, 0D, 0D);
        }

        if (n <= 2)
        {
            int[] all = new int[n];
            for (int i = 0; i < n; i++)
            {
                all[i] = from + i;
            }
            return new Result(all, n, n, 0D, 0D);
        }

        boolean[] keep = new boolean[n];

        // === 第一步：保护点（规范 §27-28）===
        int protectedCount = 0;

        keep[0] = true;
        keep[n - 1] = true;
        protectedCount = 2;

        for (int i = 1; i < n - 1; i++)
        {
            byte flags = buffer.flagsAt(order[from + i]);

            if (flags != 0)
            {
                keep[i] = true;
                protectedCount++;
            }
        }

        // === 第二步：在保护点之间分段做 Douglas–Peucker ===
        int segmentStart = 0;

        for (int i = 1; i < n; i++)
        {
            if (keep[i])
            {
                if (i - segmentStart >= 2)
                {
                    douglasPeucker(buffer, order, from, segmentStart, i, keep, posBudget, rotBudget);
                }

                segmentStart = i;
            }
        }

        // === 第三步：收集结果 + 复算真实最大误差 ===
        int count = 0;
        for (int i = 0; i < n; i++)
        {
            if (keep[i])
            {
                count++;
            }
        }

        int[] indices = new int[count];
        int k = 0;
        for (int i = 0; i < n; i++)
        {
            if (keep[i])
            {
                indices[k++] = from + i;
            }
        }

        double[] error = measure(buffer, order, indices);

        return new Result(indices, n, protectedCount, error[0], error[1]);
    }

    /**
     * Douglas–Peucker（显式栈，不递归 —— 深轨迹不会爆栈）
     *
     * 每一轮在 (a,b) 之间找"归一化误差最大"的点：误差一旦超预算就把它留成关键帧，
     * 然后对 (a,i) 与 (i,b) 继续。归一化 = 位置误差/位置预算 与 旋转误差/旋转预算
     * 取大者 —— 这样两种量纲可以直接比较，谁超得更狠就优先保留谁。
     *
     * @param keep 就地标记保留
     */
    private static void douglasPeucker(TrajectoryBuffer buffer, int[] order, int from,
                                       int start, int end, boolean[] keep,
                                       double posBudget, float rotBudget)
    {
        double inversePosition = 1D / Math.max(posBudget, 1.0E-9D);
        double inverseRotation = 1D / Math.max(rotBudget, 1.0E-9F);

        // 最坏情况需要 O(n) 层，用可增长数组模拟栈
        int[] stack = new int[64];
        int top = 0;

        stack[top++] = start;
        stack[top++] = end;

        while (top >= 2)
        {
            int b = stack[--top];
            int a = stack[--top];

            if (b - a < 2)
            {
                continue;
            }

            int tickA = buffer.tickAt(order[from + a]);
            int tickB = buffer.tickAt(order[from + b]);
            int span = tickB - tickA;

            int worst = -1;
            double worstError = 0D;

            for (int i = a + 1; i < b; i++)
            {
                int tick = buffer.tickAt(order[from + i]);
                float t = span > 0 ? (tick - tickA) / (float) span : 0F;

                double p = positionError(buffer, order[from + a], order[from + i], order[from + b], t);
                float r = rotationError(buffer, order[from + a], order[from + i], order[from + b], t);

                // 归一化后取大者：任一维度超预算，这个点就该留
                double error = Math.max(p * inversePosition, r * inverseRotation);

                if (error > worstError)
                {
                    worstError = error;
                    worst = i;
                }
            }

            // worstError <= 1 表示这一段整个都在预算之内 —— 两端点就够了
            if (worst >= 0 && worstError > 1D)
            {
                keep[worst] = true;

                if (top + 4 > stack.length)
                {
                    stack = java.util.Arrays.copyOf(stack, stack.length << 1);
                }

                stack[top++] = a;
                stack[top++] = worst;
                stack[top++] = worst;
                stack[top++] = b;
            }
        }
    }

    /**
     * 位置误差：样本到 A–B 线性插值点的距离（方块）
     *
     * 注意不是"到直线的垂距"——关键帧之间是**按 tick 插值**的，
     * 所以要比较的是"同一 tick 上，插值出来的位置 vs 真实位置"。
     */
    private static double positionError(TrajectoryBuffer buffer, int a, int i, int b, float t)
    {
        double ix = buffer.posX(a) + (buffer.posX(b) - buffer.posX(a)) * t;
        double iy = buffer.posY(a) + (buffer.posY(b) - buffer.posY(a)) * t;
        double iz = buffer.posZ(a) + (buffer.posZ(b) - buffer.posZ(a)) * t;

        double dx = buffer.posX(i) - ix;
        double dy = buffer.posY(i) - iy;
        double dz = buffer.posZ(i) - iz;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 旋转误差：样本四元数与 A–B 之间"归一化线性插值"的夹角（弧度）
     *
     * 用 nlerp 而不是 slerp 作为参考曲线，有两个理由：
     *   ① 误差度量本身不需要精确 —— 它只是用来决定"留不留这个点"，
     *      用一个始终略微低估的参考曲线是更保守的选择；
     *   ② 完全避免三角函数，热路径上没有 acos/asin。
     * 真正的插值由 BBS 关键帧曲线负责，跟这里无关。
     */
    private static float rotationError(TrajectoryBuffer buffer, int a, int i, int b, float t)
    {
        float ax = buffer.rotX(a), ay = buffer.rotY(a), az = buffer.rotZ(a), aw = buffer.rotW(a);
        float bx = buffer.rotX(b), by = buffer.rotY(b), bz = buffer.rotZ(b), bw = buffer.rotW(b);

        // 短弧：若 A·B < 0 则把 B 取反，保证走短弧
        float dotAB = ax * bx + ay * by + az * bz + aw * bw;

        if (dotAB < 0F)
        {
            bx = -bx; by = -by; bz = -bz; bw = -bw;
        }

        float ix = ax + (bx - ax) * t;
        float iy = ay + (by - ay) * t;
        float iz = az + (bz - az) * t;
        float iw = aw + (bw - aw) * t;

        float len = (float) Math.sqrt(ix * ix + iy * iy + iz * iz + iw * iw);

        if (len < 1.0E-9F)
        {
            return 0F;
        }

        ix /= len; iy /= len; iz /= len; iw /= len;

        float dot = buffer.rotX(i) * ix + buffer.rotY(i) * iy
                  + buffer.rotZ(i) * iz + buffer.rotW(i) * iw;

        float abs = Math.abs(dot);

        if (abs > 1F)
        {
            abs = 1F;
        }

        // 夹角 = 2·acos(|dot|)；用 asin(sqrt(1-dot²)) 的等价形式避免 dot 接近 1 时的精度损失
        float sine = (float) Math.sqrt(Math.max(0F, 1F - abs * abs));

        return 2F * (float) Math.asin(Math.min(1F, sine));
    }

    /**
     * 复算保留点之间的真实最大误差
     *
     * 规范 §30 要求 Bake UI 显示 "Error: 0.001 blocks"。
     * DP 判定时用的是"点 vs 端点连线"，这里复算的是"点 vs 相邻保留点连线"——
     * 后者才是烘焙进 BBS 之后回放出来的真实误差，前者只是判定依据。
     */
    private static double[] measure(TrajectoryBuffer buffer, int[] order, int[] indices)
    {
        double maxPosition = 0D;
        double maxRotation = 0D;

        for (int k = 0; k + 1 < indices.length; k++)
        {
            int a = indices[k];
            int b = indices[k + 1];
            int tickA = buffer.tickAt(order[a]);
            int tickB = buffer.tickAt(order[b]);
            int span = tickB - tickA;

            for (int i = a + 1; i < b; i++)
            {
                float t = span > 0 ? (buffer.tickAt(order[i]) - tickA) / (float) span : 0F;

                double p = positionError(buffer, order[a], order[i], order[b], t);
                double r = rotationError(buffer, order[a], order[i], order[b], t);

                if (p > maxPosition) maxPosition = p;
                if (r > maxRotation) maxRotation = r;
            }
        }

        return new double[] {maxPosition, Math.toDegrees(maxRotation)};
    }
}
