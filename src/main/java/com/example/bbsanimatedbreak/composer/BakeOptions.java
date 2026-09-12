package com.example.bbsanimatedbreak.composer;

/**
 * 烘焙选项 —— 规范 §9-12（Physics Budget）、§11（Preview/Bake 双档）、§30-31（误差预算）
 *
 * === 第一性原理：Preview 与 Bake 的数据压力模型不同 ===
 * 规范 §11 说得很准：
 *
 *   Preview 要的是：低延迟、低内存、可实时
 *   Bake    要的是：高精度、可重建、最终轨迹正确
 *
 * 这两件事的约束方向是**相反**的 —— 预览想少算，烘焙想算准。
 * 所以物理预算必须分成两档，而不是一个全局值：
 * 预览用 300 个刚体，烘焙用 1000 个，两者算出来的轨迹**本来就不同**，
 * 这不是缺陷，是设计。用户在预览里看到的是"草稿"，烘焙产出的才是"成片"。
 *
 * === 误差预算为什么要做参数而不是硬编码 ===
 * 规范 §31 明确写："实际默认值必须通过测试校准，而不是随便硬编码"。
 * 位置误差 0.001 方块在 1×1×1 的方块上是 1/1000 个方块 —— 视觉上完全无感；
 * 但旋转误差 0.1° 在远处可能就看得见。两者的"无感阈值"不在一个量级，
 * 所以分开设，并且允许 UI 调。
 */
public final class BakeOptions
{
    /**
     * 物理预算档位（规范 §9-10）
     *
     * 内部一律折算成 maxPhysicsBlocks —— 档位只是给人看的，
     * 求解器只认数字。这样以后加"根据硬件自动"时，
     * 只需要让 Auto 返回另一个数字，不用改任何下游代码。
     */
    public enum PhysicsBudget
    {
        /** 自动：第一版固定 300，后续按方块数/时长/硬件估算（规范 §10） */
        AUTO(300),
        LOW(100),
        MEDIUM(300),
        HIGH(500),
        ULTRA(1000);

        public final int maxPhysicsBlocks;

        PhysicsBudget(int maxPhysicsBlocks)
        {
            this.maxPhysicsBlocks = maxPhysicsBlocks;
        }
    }

    /**
     * 预览质量档（规范 §12）
     *
     * Draft 只给 100 个刚体 —— 作者拖时间轴时看的是"大概会怎么飞"，
     * 不是"每个碎片的精确落点"。把预览压到 1/3 的成本，
     * 换来的是拖动时的帧率。
     */
    public enum PreviewQuality
    {
        DRAFT(100),
        NORMAL(300),
        HIGH(500),
        /** 预览就等于烘焙质量（慢，但所见即所得） */
        BAKE(1000);

        public final int maxPhysicsBlocks;

        PreviewQuality(int maxPhysicsBlocks)
        {
            this.maxPhysicsBlocks = maxPhysicsBlocks;
        }
    }

    /* === 物理预算 === */

    /** 预览用预算 */
    public PhysicsBudget preview = PhysicsBudget.AUTO;

    /** 烘焙用预算（规范 §11：Bake 可以提高） */
    public PhysicsBudget bake = PhysicsBudget.ULTRA;

    /* === 误差预算（规范 §31）=== */

    /** 位置误差预算（方块）。默认 0.002 ≈ 1/500 方块。 */
    public double positionError = 0.002D;

    /** 旋转误差预算（度）。默认 0.2°。 */
    public float rotationErrorDegrees = 0.2F;

    /* === 采样阈值（规范 §22）=== */

    /** 采集时的位置漂移阈值（方块）—— 低于它视为静止，不记样本 */
    public double capturePositionEpsilon = 0.002D;

    /** 采集时的旋转漂移阈值（度）—— 低于它视为静止 */
    public float captureRotationEpsilonDegrees = 0.115F;

    /* === 身份（规范 §81-83）=== */

    /** compositionId —— 稳定标识，参与确定性随机与缓存键 */
    public String compositionId = "";

    /** seed —— 由 compositionId 派生则稳定；显式给出则覆盖 */
    public long seed = 0L;

    public boolean hasExplicitSeed = false;

    /* === 输出（规范 §41-44）=== */

    /** 烘焙后是否立即销毁物理世界（§38：Physics CPU = 0） */
    public boolean destroyPhysicsAfterBake = true;

    /** 烘焙出的轨道放进哪个分类（BBS replay category），便于在回放列表里折叠 */
    public String category = "Physics Bake";

    /** 是否给烘焙出的每个方块轨道写 label（Block #001） */
    public boolean labelTracks = true;

    /** 旋转误差预算转弧度 */
    public float rotationErrorRadians()
    {
        return (float) Math.toRadians(this.rotationErrorDegrees);
    }

    /** 采集旋转阈值转弧度 */
    public float captureRotationEpsilonRadians()
    {
        return (float) Math.toRadians(this.captureRotationEpsilonDegrees);
    }

    /**
     * 解析种子
     *
     * 规范 §67：随机必须由 seed + stableBlockId + effectId 决定。
     * 若调用方没给显式 seed，就从 compositionId 派生 —— 于是
     * "同一个 composition 永远得到同一串随机数"，不需要用户操心。
     */
    public long resolveSeed()
    {
        return this.hasExplicitSeed ? this.seed : Deterministic.hashString(this.compositionId);
    }

    /* === 预设 === */

    /** 预览默认：中档预算 + 采集阈值放宽（省内存） */
    public static BakeOptions previewDefaults()
    {
        BakeOptions options = new BakeOptions();

        options.preview = PhysicsBudget.MEDIUM;
        options.bake = PhysicsBudget.ULTRA;
        options.capturePositionEpsilon = 0.005D;
        options.captureRotationEpsilonDegrees = 0.3F;

        return options;
    }

    /** 烘焙默认：高预算 + 精细阈值 */
    public static BakeOptions bakeDefaults()
    {
        BakeOptions options = new BakeOptions();

        options.preview = PhysicsBudget.MEDIUM;
        options.bake = PhysicsBudget.ULTRA;
        options.capturePositionEpsilon = 0.001D;
        options.captureRotationEpsilonDegrees = 0.05F;
        options.positionError = 0.001D;
        options.rotationErrorDegrees = 0.1F;

        return options;
    }
}
