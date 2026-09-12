package com.example.bbsanimatedbreak.actions.physics;

import com.example.bbsanimatedbreak.FallingBlockRotationData;
import com.example.bbsanimatedbreak.mixin.FallingBlockEntityAccessor;
import org.joml.Quaternionf;

import java.util.UUID;
import net.minecraft.class_1299;
import net.minecraft.class_1540;
import net.minecraft.class_1937;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_243;
import net.minecraft.class_2680;
import net.minecraft.class_2940;

/**
 * 物理方块实体（刚体物理驱动：Rapier / Jolt）
 *
 * 继承 FallingBlockEntity 以复用渲染器，但 tick() 完全从刚体读取变换
 * （位置 + 四元数旋转），不做任何 Java 端物理计算。
 *
 * 物理步进由 {@link PhysicsWorldRegistry} 按 <b>BBS 回放时钟</b> 驱动
 * （见 BlockSplashActionClip#applyRange），本实体只负责
 * 读取变换 → 同步到 Minecraft 实体 → 记录。
 *
 * ==================================================================
 * 第一性原理：客户端为什么不能再做「物理预测」
 * ==================================================================
 * 旧实现让客户端每帧用「同步来的速度 + 重力」开环积分预测位置，
 * 每 tick 再用服务端位置做 20% 校正。这在数学上是一个正反馈回路：
 *
 *     误差 e  →  积分放大  →  校正不足(只收敛20%)  →  误差更大
 *
 * 而且在「预测值」和「lerp 值」之间每帧切换渲染源，两者数值不同，
 * 于是方块看起来「不跟随物理运动、原地随意抽搐」。
 *
 * 更根本的问题是：客户端根本没有权威信息。
 *   - 服务端 20 TPS 给出权威样本（每 50ms 一个）
 *   - 渲染 60~165 Hz 需要在样本之间取值
 *   - 网络会丢包/合并/乱序（本机代理下延迟≈0，但结构性风险在）
 * 所以客户端唯一正确的做法是 <b>插值</b>：把「两个已确认的权威样本」
 * 之间用一条曲线连起来，而不是自己往前「猜」。
 *
 * 插值曲线的选择也由物理决定：自由落体是二次曲线，
 * 线性 lerp 会看出折线（"20 帧感"）。因此这里取
 * <b>最近 3 个权威 tick 样本做二次拉格朗日插值</b>——
 * 对恒定加速度运动它是精确解，且不需要额外同步速度：
 *
 *     样本位于 t = -1, 0, +1（单位 tick），取值为 a, b, c
 *     τ = tickDelta ∈ [0,1]，渲染 t ∈ [0,1] 之间
 *
 *     p(τ) = a·τ(τ-1)/2 + b·(1-τ²) + c·τ(τ+1)/2
 *
 * 性质：
 *   - τ=0 → b，τ=1 → c（与 MC 的 lerp 约定一致：显示"上一 tick + τ"）
 *   - 恒定加速度下与真实抛物线完全重合（误差 0，而不是线性插值的 O(T²)）
 *   - 纯函数、无内部累积状态 → 不漂移、不抖动、逐帧可复现
 *   - 任意帧率、任意时刻拖动时间轴都得到同一结果（BBS 导出必需）
 *
 * 旋转同理：取最近两个权威四元数做 slerp（含符号修正）。
 *
 * ==================================================================
 * BlockState 同步策略
 * ==================================================================
 * FallingBlockEntity 的 block 字段是 private，且 initDataTracker() 读 block
 * 造成 NPE。修复：在 initDataTracker() 覆写中，调用 super 之前先用
 * Accessor 设置 block=AIR。
 *
 * ==================================================================
 * 单位约定
 * ==================================================================
 * - 刚体位置：方块几何中心（米）
 * - Entity.setPosition(x, y, z)：y 是脚部（feet），feet = center - 0.5
 * - 刚体速度：m/s（与重力 -11.0 m/s² 配套）
 * - Entity.setVelocity：blocks/tick，需 / 20.0 转换
 *
 * ==================================================================
 * native 安全（避免 use-after-free）
 * ==================================================================
 * 物理世界可能在实体仍存活时被销毁（空世界/超时/回放停止）。一旦
 * PhysicsBackendWorld.isValid() 为 false，其 worldPtr 已被释放，
 * 任何 native 调用都是 use-after-free —— 表现为方块瞬移到垃圾坐标、
 * 疯狂抽搐甚至游戏崩溃。因此本类在所有 native 调用点前都先检查
 * isValid()，一旦失效立即自毁。
 */
public class PhysicsBlockEntity extends class_1540
{
    /* === DataTracker：旋转四元数（定义在 FallingBlockRotationData，用 FallingBlockEntity.class 注册避免 ID 冲突） === */
    private static final class_2940<Float> ROT_X = FallingBlockRotationData.PHYSICS_ROT_X;
    private static final class_2940<Float> ROT_Y = FallingBlockRotationData.PHYSICS_ROT_Y;
    private static final class_2940<Float> ROT_Z = FallingBlockRotationData.PHYSICS_ROT_Z;
    private static final class_2940<Float> ROT_W = FallingBlockRotationData.PHYSICS_ROT_W;

    /** 最大存活 tick（60 秒，作为兜底超时；正常由回放停止/空世界清理） */
    private static final int MAX_LIFE = 1200;

    /* === 物理刚体句柄与所属世界（Rapier / Jolt 双后端统一走 PhysicsBackendWorld） === */
    private long bodyHandle = 0;
    private PhysicsBackendWorld physicsWorld = null;
    private UUID worldId = null;

    /** 所属回放世界的注册键（由 PhysicsWorldRegistry 分配，回退重放时保持稳定） */
    private String worldKey = null;

    /* === 本地保存的 BlockState（服务端构造时设置，客户端从 spawn 包同步） === */
    private class_2680 physicsBlockState = null;

    /* === 记录系统关联 === */
    private int blockId = -1;
    private UUID replayId = null;
    private int physicsTick = 0;

    /* === GC 优化：复用数组，避免每 tick 创建临时数组 === */
    private final double[] _tmpPos = new double[3];
    private final float[] _tmpRot = new float[4];
    private final double[] _tmpVel = new double[3];
    private final float[] _tmpAngVel = new float[3];

    // === 落地稳定检测 ===
    // 方块静止后刚体求解器仍会有亚毫米级微抖，如果继续每 tick 同步，
    // 客户端会把这点微抖当真实位移插值出来 → 方块在静止时"抽搐"。
    // 连续 10 tick 低速即判定为已稳定，停止同步（保留最后位置）。
    private boolean settled = false;
    private int lowSpeedTicks = 0;

    /* ================================================================
     * 客户端插值状态（纯样本缓存，不做任何积分/预测）
     * ================================================================ */

    /** 最近 3 个权威 tick 的方块中心坐标（[0]=最旧 t=-1，[2]=最新 t=+1） */
    private final double[] sampleCenterX = new double[3];
    private final double[] sampleCenterY = new double[3];
    private final double[] sampleCenterZ = new double[3];
    private int sampleCount = 0;

    /** 最近 2 个权威 tick 的旋转四元数 */
    private final Quaternionf prevQuat = new Quaternionf(0, 0, 0, 1);
    private final Quaternionf currQuat = new Quaternionf(0, 0, 0, 1);
    private boolean quatInitialized = false;

    /**
     * 工厂构造函数（由 EntityType 调用，客户端 spawn 包走这里）
     */
    public PhysicsBlockEntity(class_1299<?> type, class_1937 world)
    {
        super((class_1299<? extends class_1540>) type, world);
    }

    /**
     * 直接创建构造函数（由 ActionClip 在服务端调用）
     *
     * @param world  世界
     * @param x      方块中心 X（米）
     * @param y      方块脚部 Y（feet，米）
     * @param z      方块中心 Z（米）
     * @param state  方块状态
     */
    public PhysicsBlockEntity(class_1937 world, double x, double y, double z, class_2680 state)
    {
        super(PhysicsBlockEntityTypes.PHYSICS_BLOCK, world);
        this.field_23807 = true;
        this.method_5814(x, y, z);
        this.method_18799(class_243.field_1353);
        this.field_6014 = x;
        this.field_6036 = y;
        this.field_5969 = z;
        this.physicsBlockState = state;
        try
        {
            ((FallingBlockEntityAccessor) this).bbs$setBlock(state);
        }
        catch (Throwable t) { /* Accessor Mixin 未生效，忽略 */ }
    }

    /**
     * 覆写 initDataTracker：在调用 super 之前设置 block=AIR，避免 NPE
     *
     * 旋转四元数字段已在 FallingBlockEntityDataMixin 中注册。
     * 初始化 ROT_W = 1.0（单位四元数），避免客户端收到 spawn 包时
     * 四元数为 (0,0,0,0) 无效值导致渲染闪烁。
     */
    @Override
    protected void method_5693()
    {
        try
        {
            ((FallingBlockEntityAccessor) this).bbs$setBlock(class_2246.field_10124.method_9564());
        }
        catch (Throwable t) { /* Accessor Mixin 未生效，忽略 */ }

        try
        {
            super.method_5693();
        }
        catch (Throwable t) { /* 忽略，继续 */ }

        try
        {
            this.field_6011.method_12778(ROT_W, 1.0f);
        }
        catch (Throwable t) { /* DataTracker 尚未就绪，忽略 */ }
    }

    /**
     * 覆写 getBlockState()：返回本地保存的 BlockState
     */
    @Override
    public class_2680 method_6962()
    {
        if (this.physicsBlockState != null)
        {
            return this.physicsBlockState;
        }
        return super.method_6962();
    }

    /* ================================================================
     * 刚体绑定
     * ================================================================ */

    /**
     * 注入刚体句柄和物理世界引用（由 BlockSplashActionClip 创建刚体后调用）
     */
    public void setBodyHandle(long handle, PhysicsBackendWorld world, UUID worldId)
    {
        this.bodyHandle = handle;
        this.physicsWorld = world;
        this.worldId = worldId;
    }

    /** 设置所属回放世界注册键（用于回退重放后的句柄重绑定） */
    public void setWorldKey(String key) { this.worldKey = key; }
    public String getWorldKey() { return this.worldKey; }

    /**
     * 世界被销毁时的回调：断开 native 引用，避免任何后续 native 调用
     */
    public void detachPhysicsWorld()
    {
        this.bodyHandle = 0;
        this.physicsWorld = null;
        this.worldId = null;
    }

    /* ================================================================
     * tick
     * ================================================================ */

    @Override
    public void method_5773()
    {
        if (this.method_37908().field_9236)
        {
            // 客户端：只做样本采集（下面 getRender* 是纯函数）
            this.updateClientSamples();
            return;
        }

        // 服务端：维护 prevX/Y/Z（位置包以 tick 为粒度）
        this.field_6014 = this.method_23317();
        this.field_6036 = this.method_23318();
        this.field_5969 = this.method_23321();

        if (this.bodyHandle == 0 || this.physicsWorld == null)
        {
            return;
        }

        // === native 安全闸门 ===
        // 世界已被销毁（空世界 / 超时 / 回放停止）时 worldPtr 已释放，
        // 继续调用就是 use-after-free。立即自毁，绝不再触碰 native。
        if (!this.physicsWorld.isValid())
        {
            this.method_31472();
            return;
        }

        // === 从刚体读取变换（复用数组，零 GC 分配） ===
        this.physicsWorld.getBodyTransform(this.bodyHandle, _tmpPos, _tmpRot);
        this.physicsWorld.getBodyVelocity(this.bodyHandle, _tmpVel);

        // === 落地稳定检测 ===
        double speedSq = _tmpVel[0] * _tmpVel[0] + _tmpVel[1] * _tmpVel[1] + _tmpVel[2] * _tmpVel[2];
        if (speedSq < 0.04) // 速度 < 0.2 m/s
        {
            this.lowSpeedTicks++;
            if (this.lowSpeedTicks > 10 && !this.settled)
            {
                this.settled = true;
            }
        }
        else
        {
            this.lowSpeedTicks = 0;
            this.settled = false;
        }

        // 已稳定的方块不再同步（求解器微抖不传播到客户端）
        if (!this.settled)
        {
            // 同步到实体（center → feet，减 0.5）
            this.method_5814(_tmpPos[0], _tmpPos[1] - 0.5, _tmpPos[2]);

            // 同步旋转四元数到 DataTracker
            this.field_6011.method_12778(ROT_X, _tmpRot[0]);
            this.field_6011.method_12778(ROT_Y, _tmpRot[1]);
            this.field_6011.method_12778(ROT_Z, _tmpRot[2]);
            this.field_6011.method_12778(ROT_W, _tmpRot[3]);

            // m/s → blocks/tick
            this.method_18800(_tmpVel[0] / 20.0, _tmpVel[1] / 20.0, _tmpVel[2] / 20.0);
        }

        // === 记录物理状态（供后续动画回放 / 物理烘焙使用） ===
        if (this.replayId != null)
        {
            this.physicsWorld.getBodyAngularVelocity(this.bodyHandle, _tmpAngVel);
            PhysicsRecordingManager.record(this.replayId, new PhysicsRecording(
                this.physicsTick,
                this.blockId,
                _tmpPos[0], _tmpPos[1], _tmpPos[2],
                _tmpVel[0], _tmpVel[1], _tmpVel[2],
                _tmpRot[0], _tmpRot[1], _tmpRot[2], _tmpRot[3],
                _tmpAngVel[0], _tmpAngVel[1], _tmpAngVel[2],
                this.method_6962(),
                this.settled
            ));
        }

        // === 生命管理 ===
        this.physicsTick++;
        this.field_6012++;

        if (this.physicsTick > MAX_LIFE)
        {
            this.method_31472();
        }
    }

    /* ================================================================
     * 客户端样本采集 + 插值渲染（无预测、无累积状态）
     * ================================================================ */

    /**
     * 客户端每 tick 采集一个权威样本
     *
     * 由 tick() 调用（20Hz）。采集的是服务端通过位置包 / DataTracker
     * 同步过来的权威状态，不做任何推算。
     */
    private void updateClientSamples()
    {
        // 位置样本用方块中心（center = feet + 0.5），向后挪一格
        this.sampleCenterX[0] = this.sampleCenterX[1];
        this.sampleCenterY[0] = this.sampleCenterY[1];
        this.sampleCenterZ[0] = this.sampleCenterZ[1];
        this.sampleCenterX[1] = this.sampleCenterX[2];
        this.sampleCenterY[1] = this.sampleCenterY[2];
        this.sampleCenterZ[1] = this.sampleCenterZ[2];
        this.sampleCenterX[2] = this.method_23317();
        this.sampleCenterY[2] = this.method_23318() + 0.5;
        this.sampleCenterZ[2] = this.method_23321();

        if (this.sampleCount < 3)
        {
            // 样本不足时用最新值补齐，避免插值出现 0 坐标
            if (this.sampleCount == 0)
            {
                this.sampleCenterX[0] = this.sampleCenterX[1] = this.sampleCenterX[2];
                this.sampleCenterY[0] = this.sampleCenterY[1] = this.sampleCenterY[2];
                this.sampleCenterZ[0] = this.sampleCenterZ[1] = this.sampleCenterZ[2];
            }
            else if (this.sampleCount == 1)
            {
                this.sampleCenterX[0] = this.sampleCenterX[1];
                this.sampleCenterY[0] = this.sampleCenterY[1];
                this.sampleCenterZ[0] = this.sampleCenterZ[1];
            }
            this.sampleCount++;
        }

        // 旋转样本（含 slerp 符号修正：q 与 -q 表示同一旋转，
        // 若点积为负需翻转符号，否则 slerp 会走远路导致旋转抽帧）
        float qx = this.field_6011.method_12789(ROT_X);
        float qy = this.field_6011.method_12789(ROT_Y);
        float qz = this.field_6011.method_12789(ROT_Z);
        float qw = this.field_6011.method_12789(ROT_W);

        float mag = qx * qx + qy * qy + qz * qz + qw * qw;
        if (mag < 1e-8f)
        {
            // 无效四元数：保持上一帧，避免渲染闪烁
            qx = 0; qy = 0; qz = 0; qw = 1;
        }

        if (!this.quatInitialized)
        {
            this.prevQuat.set(qx, qy, qz, qw);
            this.currQuat.set(qx, qy, qz, qw);
            this.quatInitialized = true;
            return;
        }

        this.prevQuat.set(this.currQuat);

        float dot = this.prevQuat.x * qx + this.prevQuat.y * qy
                  + this.prevQuat.z * qz + this.prevQuat.w * qw;
        if (dot < 0.0f)
        {
            qx = -qx; qy = -qy; qz = -qz; qw = -qw;
        }

        this.currQuat.set(qx, qy, qz, qw);
    }

    /**
     * 二次拉格朗日插值（节点位于 -1, 0, +1）
     *
     * 对恒定加速度运动（自由落体）是精确解。
     * τ = 0 → b，τ = 1 → c。
     */
    private static double quadratic(double a, double b, double c, float tau)
    {
        double t = tau;
        double l0 = t * (t - 1.0) * 0.5;   // 节点 -1
        double l1 = (1.0 - t) * (1.0 + t); // 节点  0
        double l2 = t * (t + 1.0) * 0.5;   // 节点 +1
        return a * l0 + b * l1 + c * l2;
    }

    /** 渲染用方块中心 X（米） */
    public double getRenderCenterX(float tickDelta)
    {
        if (this.sampleCount < 2)
        {
            return this.method_23317();
        }
        return quadratic(this.sampleCenterX[0], this.sampleCenterX[1], this.sampleCenterX[2], tickDelta);
    }

    /** 渲染用方块中心 Y（米） */
    public double getRenderCenterY(float tickDelta)
    {
        if (this.sampleCount < 2)
        {
            return this.method_23318() + 0.5;
        }
        return quadratic(this.sampleCenterY[0], this.sampleCenterY[1], this.sampleCenterY[2], tickDelta);
    }

    /** 渲染用方块中心 Z（米） */
    public double getRenderCenterZ(float tickDelta)
    {
        if (this.sampleCount < 2)
        {
            return this.method_23321();
        }
        return quadratic(this.sampleCenterZ[0], this.sampleCenterZ[1], this.sampleCenterZ[2], tickDelta);
    }

    /**
     * 渲染用旋转四元数（两样本 slerp，调用方负责 copy）
     */
    public void getRenderRotation(float tickDelta, Quaternionf out)
    {
        if (!this.quatInitialized)
        {
            // 退回到 DataTracker 当前值
            float qx = this.field_6011.method_12789(ROT_X);
            float qy = this.field_6011.method_12789(ROT_Y);
            float qz = this.field_6011.method_12789(ROT_Z);
            float qw = this.field_6011.method_12789(ROT_W);
            if (qx * qx + qy * qy + qz * qz + qw * qw < 1e-8f)
            {
                out.identity();
            }
            else
            {
                out.set(qx, qy, qz, qw).normalize();
            }
            return;
        }

        if (tickDelta <= 0.0f)
        {
            out.set(this.prevQuat);
        }
        else if (tickDelta >= 1.0f)
        {
            out.set(this.currQuat);
        }
        else
        {
            this.prevQuat.slerp(this.currQuat, tickDelta, out);
        }
    }

    /* === 物理参数设置 === */

    /**
     * 设置初始线速度（blocks/tick，Minecraft 速度单位）
     */
    public void setInitialVelocity(double vx, double vy, double vz)
    {
        if (this.physicsWorld != null && this.physicsWorld.isValid() && this.bodyHandle != 0)
        {
            this.physicsWorld.setBodyVelocity(this.bodyHandle, vx * 20.0, vy * 20.0, vz * 20.0);
        }
        this.method_18800(vx, vy, vz);
    }

    /**
     * 设置随机角速度（rad/s）
     *
     * 用确定性随机（基于 bodyHandle）保证回放一致。
     */
    public void setRandomAngularVelocity(float magnitude)
    {
        if (this.physicsWorld != null && this.physicsWorld.isValid() && this.bodyHandle != 0)
        {
            java.util.Random rand = new java.util.Random(this.bodyHandle);
            float scale = magnitude * 2.0F;
            this.physicsWorld.setBodyAngularVelocity(this.bodyHandle,
                (rand.nextFloat() - 0.5f) * scale,
                (rand.nextFloat() - 0.5f) * scale,
                (rand.nextFloat() - 0.5f) * scale
            );
        }
    }

    /** 直接设置角速度（rad/s） */
    public void setAngularVelocity(float ax, float ay, float az)
    {
        if (this.physicsWorld != null && this.physicsWorld.isValid() && this.bodyHandle != 0)
        {
            this.physicsWorld.setBodyAngularVelocity(this.bodyHandle, ax, ay, az);
        }
    }

    /** 施加冲量（N·s） */
    public void applyImpulse(double ix, double iy, double iz)
    {
        if (this.physicsWorld != null && this.physicsWorld.isValid() && this.bodyHandle != 0)
        {
            this.physicsWorld.applyImpulse(this.bodyHandle, ix, iy, iz);
        }
    }

    public void setBlockId(int blockId) { this.blockId = blockId; }
    public void setReplayId(UUID replayId) { this.replayId = replayId; }
    public int getBlockId() { return this.blockId; }

    /** 当前已模拟 tick 数（物理世界用于生命周期管理） */
    public int getPhysicsTick() { return this.physicsTick; }

    /** 刚体是否休眠 */
    public boolean isSleeping()
    {
        if (this.physicsWorld != null && this.physicsWorld.isValid() && this.bodyHandle != 0)
        {
            return this.physicsWorld.isBodySleeping(this.bodyHandle);
        }
        return false;
    }

    /* === 供 PhysicsEntityManager / PhysicsEngine 使用的坐标 getter（方块中心） === */

    /**
     * @deprecated 已迁移到刚体物理，此方法返回 null
     */
    @Deprecated
    public PhysicsState getPhysicsState() { return null; }

    public double getCenterX()
    {
        if (this.physicsWorld != null && this.physicsWorld.isValid() && this.bodyHandle != 0)
        {
            this.physicsWorld.getBodyTransform(this.bodyHandle, _tmpPos, _tmpRot);
            return _tmpPos[0];
        }
        return this.method_23317();
    }

    public double getCenterY()
    {
        if (this.physicsWorld != null && this.physicsWorld.isValid() && this.bodyHandle != 0)
        {
            this.physicsWorld.getBodyTransform(this.bodyHandle, _tmpPos, _tmpRot);
            return _tmpPos[1];
        }
        return this.method_23318() + 0.5;
    }

    public double getCenterZ()
    {
        if (this.physicsWorld != null && this.physicsWorld.isValid() && this.bodyHandle != 0)
        {
            this.physicsWorld.getBodyTransform(this.bodyHandle, _tmpPos, _tmpRot);
            return _tmpPos[2];
        }
        return this.method_23321();
    }

    /**
     * 覆写 remove：从物理世界移除刚体（释放 native 内存）
     *
     * 关键：世界已销毁时绝不能再调 native（use-after-free）。
     */
    @Override
    public void method_5650(class_5529 reason)
    {
        if (this.physicsWorld != null && this.physicsWorld.isValid() && this.bodyHandle != 0)
        {
            this.physicsWorld.removeBody(this.bodyHandle);
        }
        this.bodyHandle = 0;
        this.physicsWorld = null;
        super.method_5650(reason);
    }
}
