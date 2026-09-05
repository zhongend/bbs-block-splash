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
 * 物理方块实体（Rapier3d 原生物理驱动）
 *
 * 继承 FallingBlockEntity 以复用渲染器，但 tick() 完全从 native Rapier
 * 刚体读取变换（位置 + 四元数旋转），不做任何 Java 端物理计算。
 *
 * 物理步进由 PhysicsWorldRegistry.tickAll() 集中处理（每 tick 一次），
 * 本实体只负责读取变换 → 同步到 Minecraft 实体 → 记录。
 *
 * === BlockState 同步策略（关键，避免渲染空气的 bug） ===
 * FallingBlockEntity 的 block 字段是 private，且 initDataTracker() 读 block.isAir()。
 * 如果 block 为 null（公开构造函数不设置），会 NPE 导致构造失败。
 * 修复：在 initDataTracker() 覆写中，调用 super 之前先用 Accessor 设置 block=AIR。
 *
 * === 单位约定 ===
 * - native 位置：方块几何中心（米）
 * - Entity.setPosition(x, y, z)：y 是脚部位置（feet），feet = center - 0.5
 * - native 速度：m/s（与重力 -11.0 m/s² 配套，参考 Sable）
 * - Entity.setVelocity：blocks/tick，需 / 20.0 转换
 */
public class PhysicsBlockEntity extends class_1540
{
    /* === DataTracker：旋转四元数（定义在 FallingBlockRotationData，用 FallingBlockEntity.class 注册避免 ID 冲突） === */
    private static final class_2940<Float> ROT_X = FallingBlockRotationData.PHYSICS_ROT_X;
    private static final class_2940<Float> ROT_Y = FallingBlockRotationData.PHYSICS_ROT_Y;
    private static final class_2940<Float> ROT_Z = FallingBlockRotationData.PHYSICS_ROT_Z;
    private static final class_2940<Float> ROT_W = FallingBlockRotationData.PHYSICS_ROT_W;

    /** 最大存活 tick（60 秒） */
    private static final int MAX_LIFE = 1200;

    /* === Native Rapier 刚体句柄 === */
    private long bodyHandle = 0;
    private NativePhysicsWorld physicsWorld = null;
    private UUID worldId = null;

    /* === 本地保存的 BlockState（服务端构造时设置，客户端从 spawn 包同步） === */
    private class_2680 physicsBlockState = null;

    /* === 记录系统关联 === */
    private int blockId = -1;
    private UUID replayId = null;
    private int physicsTick = 0;

    /* === 客户端插值用 === */
    private Quaternionf prevRenderRotation = new Quaternionf(0, 0, 0, 1);
    private Quaternionf renderRotation = new Quaternionf(0, 0, 0, 1);

    // === 客户端自主位置插值 ===
    // Minecraft 的 prevX/Y/Z 机制在 PhysicsBlockEntity 中不生效（不调用 super.tick()），
    // 位置包在 tick 之前更新 entity.x，导致 prevX = x 时两者相同，lerp 插值无效。
    // 这里维护独立的客户端插值位置，在渲染器中使用。
    private double prevClientX, prevClientY, prevClientZ;
    private double clientX, clientY, clientZ;
    private boolean clientPosInitialized = false;

    // === GC 优化：复用数组，避免每 tick 创建临时数组 ===
    // 之前每 tick 创建 new double[3] + new float[4] + new float[3] = 3 个数组
    // 500 个方块 = 1500 个数组/tick = 30000 个数组/秒 → GC 风暴
    // 改为实例字段复用，零分配
    private final double[] _tmpPos = new double[3];
    private final float[] _tmpRot = new float[4];
    private final double[] _tmpVel = new double[3];
    private final float[] _tmpAngVel = new float[3];

    // === 落地稳定检测 ===
    // 当方块速度很小且在地面附近时，标记为已稳定，停止物理同步以避免震荡闪烁
    private boolean settled = false;
    private int lowSpeedTicks = 0;

    // === 客户端物理预测（高刷新率渲染核心） ===
    // 问题：客户端 tick=20Hz，渲染=165Hz，lerp 在两个 tick 锚点间线性插值，
    //       但物理下落是二次曲线（重力加速），lerp 直线导致运动不自然 + 看起来像 20fps。
    // 方案：每帧用当前速度+重力积分预测位置，渲染预测位置而非 lerp。
    //       位置包到达时做轻微校正（lerp 10%）避免预测发散。
    private double predX, predY, predZ;           // 预测位置（方块中心，米）
    private double predVx, predVy, predVz;        // 预测速度（m/s）
    private float predRotX, predRotY, predRotZ, predRotW;  // 预测旋转四元数
    private float predAngVx, predAngVy, predAngVz;         // 预测角速度（rad/s）
    private boolean predInitialized = false;
    private float clientGravity = -11.0f;          // 客户端重力（m/s²，从服务端同步或默认）
    private float clientLinearDamping = 0.04f;     // 客户端线性阻尼
    private float clientAngularDamping = 0.3f;     // 客户端角阻尼
    // lastTickDelta 存储在每个实体实例上（非渲染器单例），确保多方块场景下
    // 每个方块都能独立计算帧间增量并更新预测，避免慢动作 bug
    private float lastTickDelta = 0f;              // 上一帧的 tickDelta（每实体独立）

    // === BBS 导出 i=0 帧检测 ===
    // BBS 导出时 RenderTickCounterMixin 每帧累加 tickDelta，整数部分 i = 应推进的 tick 数。
    // 当 i=0 时 tick 未推进（updateClientData 未调用），predVx/Vy/Vz 是陈旧的。
    // 如果继续累积积分，位置会发散（飞出去）。
    // 解决：updateClientData 调用时设置 tickAdvanced=true，updatePredictionFromTickDelta 检测
    // 该标志，未推进时跳过积分并走 lerp fallback（不发散）。
    private boolean tickAdvanced = false;          // 本帧是否有 tick 推进（由 updateClientData 设置）
    private boolean predictionSkippedThisFrame = false;  // i=0 帧跳过了预测积分

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
        // 保存 BlockState 到本地字段（getBlockState() 优先返回它）
        this.physicsBlockState = state;
        // 同时设置 FallingBlockEntity 的 private block 字段（供 createSpawnPacket 序列化）
        try
        {
            ((FallingBlockEntityAccessor) this).bbs$setBlock(state);
        }
        catch (Throwable t) { /* Accessor Mixin 未生效，忽略 */ }
    }

    /**
     * 覆写 initDataTracker：在调用 super 之前设置 block=AIR，避免 NPE
     *
     * 旋转四元数字段（ROT_X/Y/Z/W）已在 FallingBlockEntityDataMixin 中注册，
     * 不需要在这里再 startTracking。
     *
     * 关键修复：初始化 ROT_W = 1.0（单位四元数），避免客户端收到 spawn 包时
     * 四元数为 (0,0,0,0) 无效值导致渲染闪烁。
     */
    @Override
    protected void method_5693()
    {
        // 用 Accessor 设置 block=AIR，避免 super.initDataTracker() 的 NPE
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

        // 初始化旋转四元数为单位四元数 (0, 0, 0, 1)，防止客户端渲染闪烁
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

    /**
     * 注入 native 刚体句柄和物理世界引用
     *
     * 由 BlockSplashActionClip 在创建刚体后调用。
     *
     * @param handle  Rapier 刚体句柄
     * @param world   所属原生物理世界
     * @param worldId 世界注册 ID
     */
    public void setBodyHandle(long handle, NativePhysicsWorld world, UUID worldId)
    {
        this.bodyHandle = handle;
        this.physicsWorld = world;
        this.worldId = worldId;
    }

    /**
     * 覆写 tick()：从 native 读取变换，同步到实体
     *
     * 物理步进已由 PhysicsWorldRegistry.tickAll() 集中完成，
     * 此处只读取结果。
     *
     * 动态地面检测：当方块下落且下方有 Minecraft 实心方块但 Rapier 中没有对应
     * 静态碰撞体时，补加碰撞体，防止方块穿透地面遁地。
     */
    @Override
    public void method_5773()
    {
        // 服务端：更新 prevX/Y/Z（用于位置包同步）
        // 客户端：不设 prevX/Y/Z，由自主插值机制处理（见 updateClientData）
        if (!this.method_37908().field_9236)
        {
            this.field_6014 = this.method_23317();
            this.field_6036 = this.method_23318();
            this.field_5969 = this.method_23321();
        }

        if (this.method_37908().field_9236)
        {
            this.updateClientData();
            return;
        }

        // 未注入 native 句柄时直接返回（不应发生，防御性检查）
        if (this.bodyHandle == 0 || this.physicsWorld == null)
        {
            return;
        }

        // === 从 native 读取变换（复用数组，零 GC 分配） ===
        this.physicsWorld.getBodyTransform(this.bodyHandle, _tmpPos, _tmpRot);

        // === 读取速度 ===
        this.physicsWorld.getBodyVelocity(this.bodyHandle, _tmpVel);

        // === 落地稳定检测 ===
        // 当方块速度很小且持续多 tick 时，标记为已稳定，
        // 跳过物理同步避免 Rapier 微小震荡导致的闪烁
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

        // === 动态地面检测（防遁地） ===
        if (_tmpVel[1] < -0.5)
        {
            this.checkAndInjectGroundCollision(_tmpPos);
        }

        // 同步到实体（center → feet，减 0.5）
        this.method_5814(_tmpPos[0], _tmpPos[1] - 0.5, _tmpPos[2]);

        // 同步旋转四元数到 DataTracker
        this.field_6011.method_12778(ROT_X, _tmpRot[0]);
        this.field_6011.method_12778(ROT_Y, _tmpRot[1]);
        this.field_6011.method_12778(ROT_Z, _tmpRot[2]);
        this.field_6011.method_12778(ROT_W, _tmpRot[3]);

        // m/s → blocks/tick
        this.method_18800(_tmpVel[0] / 20.0, _tmpVel[1] / 20.0, _tmpVel[2] / 20.0);

        // === 记录物理状态（供后续动画回放转换） ===
        // 角速度和休眠状态只在 recording 时才需要读取（减少 JNI 调用）
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
                this.physicsWorld.isBodySleeping(this.bodyHandle)
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

    /**
     * 动态地面碰撞检测：检测方块下方 2 格内是否有 MC 实心方块，
     * 如果有则补加 Rapier 静态碰撞体，防止方块穿透地面遁地。
     *
     * 仅在方块下落时调用，且每 5 tick 才检测一次以减少性能开销。
     */
    private void checkAndInjectGroundCollision(double[] pos)
    {
        // 每 5 tick 检测一次，减少性能开销
        if (this.physicsTick % 5 != 0) return;

        class_1937 world = this.method_37908();
        if (world == null) return;

        // 检测方块下方 2 格
        int cx = (int) Math.floor(pos[0]);
        int cz = (int) Math.floor(pos[2]);

        for (int dy = 0; dy <= 2; dy++)
        {
            int cy = (int) Math.floor(pos[1]) - dy;
            class_2338 blockPos = new class_2338(cx, cy, cz);
            class_2680 state = world.method_8320(blockPos);

            if (!state.method_26215() && state.method_26204().method_36555() >= 0)
            {
                // 下方有实心方块，补加 Rapier 静态碰撞体
                this.physicsWorld.addStaticBlock(blockPos.method_10263(), blockPos.method_10264(), blockPos.method_10260());
            }
        }
    }

    /**
     * 客户端每 tick 更新插值数据（位置 + 旋转）
     *
     * 关键修复：
     * 1. 位置插值：维护独立的 prevClientX/clientX，避免 prevX==x 导致 lerp 无效
     * 2. slerp 符号修正：四元数 q 和 -q 表示同一旋转，但 slerp 走不同路径。
     *    当新四元数与旧四元数点积为负时，翻转新四元数符号，确保 slerp 走最短路径。
     *    这解决了 Rapier 输出四元数偶尔符号翻转导致的旋转闪烁。
     * 3. 客户端物理预测：从 DataTracker 同步到预测状态，每帧用速度+重力积分。
     */
    private void updateClientData()
    {
        // === 位置插值（保留作为 fallback） ===
        if (!this.clientPosInitialized)
        {
            this.prevClientX = this.method_23317();
            this.prevClientY = this.method_23318();
            this.prevClientZ = this.method_23321();
            this.clientX = this.method_23317();
            this.clientY = this.method_23318();
            this.clientZ = this.method_23321();
            this.clientPosInitialized = true;
        }
        else
        {
            this.prevClientX = this.clientX;
            this.prevClientY = this.clientY;
            this.prevClientZ = this.clientZ;
            this.clientX = this.method_23317();
            this.clientY = this.method_23318();
            this.clientZ = this.method_23321();
        }

        // === 旋转插值（含 slerp 符号修正） ===
        this.prevRenderRotation.set(this.renderRotation);

        float qx = this.field_6011.method_12789(ROT_X);
        float qy = this.field_6011.method_12789(ROT_Y);
        float qz = this.field_6011.method_12789(ROT_Z);
        float qw = this.field_6011.method_12789(ROT_W);

        // slerp 符号修正：如果新旧四元数点积 < 0，翻转新四元数符号
        float dot = this.prevRenderRotation.x * qx
                  + this.prevRenderRotation.y * qy
                  + this.prevRenderRotation.z * qz
                  + this.prevRenderRotation.w * qw;
        if (dot < 0.0f)
        {
            qx = -qx;
            qy = -qy;
            qz = -qz;
            qw = -qw;
        }

        this.renderRotation.set(qx, qy, qz, qw);

        // === 客户端物理预测同步 ===
        // 每 tick（20Hz）从 DataTracker 同步锚点位置和旋转到预测状态
        // 渲染时每帧（165Hz）调用 updatePrediction(dt) 用速度+重力积分预测中间帧
        if (!this.predInitialized)
        {
            // 首次初始化：预测位置 = entity 位置（feet → center 加 0.5）
            this.predX = this.method_23317();
            this.predY = this.method_23318() + 0.5;
            this.predZ = this.method_23321();
            this.predVx = 0;
            this.predVy = 0;
            this.predVz = 0;
            this.predRotX = qx;
            this.predRotY = qy;
            this.predRotZ = qz;
            this.predRotW = qw;
            this.predAngVx = 0;
            this.predAngVy = 0;
            this.predAngVz = 0;
            this.predInitialized = true;
        }
        else
        {
            // 后续同步：用服务端位置校正预测位置（lerp 20% 避免发散但保留预测平滑性）
            double targetX = this.method_23317();
            double targetY = this.method_23318() + 0.5;
            double targetZ = this.method_23321();
            this.predX += (targetX - this.predX) * 0.2;
            this.predY += (targetY - this.predY) * 0.2;
            this.predZ += (targetZ - this.predZ) * 0.2;

            // 从位置差反推速度（20Hz，dt=0.05s）
            double dt = 0.05;
            this.predVx = (targetX - this.prevClientX) / dt;
            this.predVy = (targetY - (this.prevClientY + 0.5)) / dt;
            this.predVz = (targetZ - this.prevClientZ) / dt;

            // 旋转同步：直接采用服务端四元数
            this.predRotX = qx;
            this.predRotY = qy;
            this.predRotZ = qz;
            this.predRotW = qw;
        }

        // 标记本 tick 已推进（供 updatePredictionFromTickDelta 检测 i=0 帧）
        this.tickAdvanced = true;
    }

    /**
     * 从 tickDelta 计算帧间增量并推进预测（每实体独立 lastTickDelta）
     *
     * 由渲染器每帧调用，把 Minecraft 提供的 tickDelta（0~1，当前 tick 已经过去的比例）
     * 转换为帧间时间增量（秒），再委托给 updatePrediction(dt)。
     *
     * === BBS 导出 i=0 帧检测 ===
     * BBS 导出时 RenderTickCounterMixin 每帧累加 tickDelta，整数部分 i = 应推进的 tick 数。
     * 当 i=0 时 tick 未推进（updateClientData 未调用），tickAdvanced=false。
     * 此时跳过预测积分（predVx/Vy/Vz 是陈旧的，积分会发散），让 getRenderX 走 lerp fallback。
     * 当 i>=1 时 tick 推进，updateClientData 已调用，tickAdvanced=true，正常预测。
     *
     * @param tickDelta Minecraft 提供的当前 tick 进度（0~1）
     */
    public void updatePredictionFromTickDelta(float tickDelta)
    {
        // === BBS 导出 i=0 帧检测 ===
        // tickAdvanced 由 updateClientData 设置，每 tick（20Hz）调用一次时设为 true。
        // 如果本帧 tickAdvanced=false，说明 tick 未推进（i=0 帧），跳过预测积分避免发散。
        if (this.predInitialized && !this.tickAdvanced)
        {
            this.predictionSkippedThisFrame = true;
            return;
        }
        this.predictionSkippedThisFrame = false;
        this.tickAdvanced = false;  // 消费标志，为下一帧准备

        if (!this.predInitialized)
        {
            // 首帧：仅记录 tickDelta，不更新预测（避免大跳变）
            this.lastTickDelta = tickDelta;
            return;
        }

        // 帧间增量 = 当前 tickDelta - 上一帧 tickDelta
        // 若为负，表示跨 tick（新 tick 从 0 开始），+1 补偿
        float delta = tickDelta - this.lastTickDelta;
        if (delta < 0f)
        {
            delta += 1f;
        }
        this.lastTickDelta = tickDelta;

        // dt = tickDelta 增量 × 0.05（1 tick = 0.05s）
        float dt = delta * 0.05f;

        if (dt > 0f)
        {
            updatePrediction(dt);
        }
    }

    /**
     * 客户端每帧物理预测（高刷新率渲染核心）
     *
     * 由 updatePredictionFromTickDelta 委托调用，用当前速度+重力积分预测位置，
     * 用角速度积分预测旋转。这样 165Hz 渲染时每帧都有新位置，而非 lerp 两个 20Hz 锚点。
     *
     * 物理模型：
     * - 位置：p += v*dt + 0.5*g*dt²（含重力的二次积分）
     * - 速度：v = v*(1-damping*dt) + g*dt（阻尼+重力）
     * - 旋转：q' = q * delta_q(angVel*dt)（角速度积分）
     *
     * @param dt 距离上一帧的时间（秒），通常 1/165 ≈ 0.006s
     */
    public void updatePrediction(float dt)
    {
        if (!this.predInitialized || dt <= 0 || dt > 0.1f) return;

        // === 位置预测：含重力的二次积分 ===
        double halfDtSq = 0.5 * clientGravity * dt * dt;
        this.predX += this.predVx * dt;
        this.predY += this.predVy * dt + halfDtSq;
        this.predZ += this.predVz * dt;

        // === 速度预测：阻尼 + 重力 ===
        double linDampFactor = 1.0 - clientLinearDamping * dt;
        if (linDampFactor < 0) linDampFactor = 0;
        this.predVx *= linDampFactor;
        this.predVy = this.predVy * linDampFactor + clientGravity * dt;
        this.predVz *= linDampFactor;

        // === 旋转预测：角速度积分 ===
        float halfAngX = predAngVx * dt * 0.5f;
        float halfAngY = predAngVy * dt * 0.5f;
        float halfAngZ = predAngVz * dt * 0.5f;
        float halfAngMag = (float) Math.sqrt(halfAngX * halfAngX + halfAngY * halfAngY + halfAngZ * halfAngZ);

        if (halfAngMag > 1e-6f)
        {
            float s = (float) Math.sin(halfAngMag) / halfAngMag;
            float dqx = halfAngX * s;
            float dqy = halfAngY * s;
            float dqz = halfAngZ * s;
            float dqw = (float) Math.cos(halfAngMag);

            // 归一化增量四元数
            float invMag = 1.0f / (float) Math.sqrt(dqx * dqx + dqy * dqy + dqz * dqz + dqw * dqw);
            dqx *= invMag; dqy *= invMag; dqz *= invMag; dqw *= invMag;

            // q' = delta_q * q（左乘）
            float nx = dqw * predRotX + dqx * predRotW + dqy * predRotZ - dqz * predRotY;
            float ny = dqw * predRotY - dqx * predRotZ + dqy * predRotW + dqz * predRotX;
            float nz = dqw * predRotZ + dqx * predRotY - dqy * predRotX + dqz * predRotW;
            float nw = dqw * predRotW - dqx * predRotX - dqy * predRotY - dqz * predRotZ;
            predRotX = nx; predRotY = ny; predRotZ = nz; predRotW = nw;

            // 角速度阻尼
            float angDampFactor = 1.0f - clientAngularDamping * dt;
            if (angDampFactor < 0) angDampFactor = 0;
            predAngVx *= angDampFactor;
            predAngVy *= angDampFactor;
            predAngVz *= angDampFactor;
        }
    }

    /**
     * 设置客户端物理预测参数（由 ActionClip 在创建时调用，同步服务端配置）
     */
    public void setClientPhysicsParams(float gravity, float linearDamping, float angularDamping,
                                       float angVx, float angVy, float angVz)
    {
        this.clientGravity = gravity;
        this.clientLinearDamping = linearDamping;
        this.clientAngularDamping = angularDamping;
        this.predAngVx = angVx;
        this.predAngVy = angVy;
        this.predAngVz = angVz;
    }

    /**
     * 获取渲染插值位置 X（客户端渲染器使用）
     *
     * 优先返回客户端物理预测位置（165Hz 平滑），fallback 到 lerp（20Hz）
     * BBS 导出视频时 tickDelta 可能固定，预测位置同样适用
     */
    public double getRenderX(float tickDelta)
    {
        if (this.predInitialized && !this.predictionSkippedThisFrame)
        {
            return this.predX;
        }
        // Fallback：lerp（i=0 帧或 predInitialized=false 时使用）
        if (tickDelta <= 0.0f) return this.prevClientX;
        if (tickDelta >= 1.0f) return this.clientX;
        return this.prevClientX + (this.clientX - this.prevClientX) * tickDelta;
    }

    /**
     * 获取渲染插值位置 Y（客户端渲染器使用）
     */
    public double getRenderY(float tickDelta)
    {
        if (this.predInitialized && !this.predictionSkippedThisFrame)
        {
            return this.predY;
        }
        if (tickDelta <= 0.0f) return this.prevClientY;
        if (tickDelta >= 1.0f) return this.clientY;
        return this.prevClientY + (this.clientY - this.prevClientY) * tickDelta;
    }

    /**
     * 获取渲染插值位置 Z（客户端渲染器使用）
     */
    public double getRenderZ(float tickDelta)
    {
        if (this.predInitialized && !this.predictionSkippedThisFrame)
        {
            return this.predZ;
        }
        if (tickDelta <= 0.0f) return this.prevClientZ;
        if (tickDelta >= 1.0f) return this.clientZ;
        return this.prevClientZ + (this.clientZ - this.prevClientZ) * tickDelta;
    }

    /**
     * 获取渲染插值旋转四元数
     *
     * 优先返回客户端物理预测旋转（165Hz 角速度积分），fallback 到 slerp
     */
    public Quaternionf getRenderRotation(float tickDelta)
    {
        Quaternionf result = new Quaternionf();
        if (this.predInitialized && !this.predictionSkippedThisFrame)
        {
            result.set(this.predRotX, this.predRotY, this.predRotZ, this.predRotW);
            return result;
        }
        // Fallback：slerp
        if (tickDelta <= 0.0f)
        {
            result.set(this.prevRenderRotation);
        }
        else if (tickDelta >= 1.0f)
        {
            result.set(this.renderRotation);
        }
        else
        {
            this.prevRenderRotation.slerp(this.renderRotation, tickDelta, result);
        }
        return result;
    }

    /* === 物理参数设置 === */

    /**
     * 设置初始线速度
     *
     * @param vx blocks/tick（Minecraft 速度单位）
     * @param vy blocks/tick
     * @param vz blocks/tick
     */
    public void setInitialVelocity(double vx, double vy, double vz)
    {
        if (this.physicsWorld != null && this.bodyHandle != 0)
        {
            // blocks/tick → m/s
            this.physicsWorld.setBodyVelocity(this.bodyHandle, vx * 20.0, vy * 20.0, vz * 20.0);
        }
        this.method_18800(vx, vy, vz);
    }

    /**
     * 设置随机角速度（rad/s）
     *
     * @param magnitude 角速度强度（视觉值，会乘以系数转为 rad/s）
     */
    public void setRandomAngularVelocity(float magnitude)
    {
        if (this.physicsWorld != null && this.bodyHandle != 0)
        {
            // magnitude 是视觉强度，转为 rad/s
            // 用确定性随机基于 bodyHandle，保证回放一致
            java.util.Random rand = new java.util.Random(this.bodyHandle);
            float scale = magnitude * 2.0F;
            this.physicsWorld.setBodyAngularVelocity(this.bodyHandle,
                (rand.nextFloat() - 0.5f) * scale,
                (rand.nextFloat() - 0.5f) * scale,
                (rand.nextFloat() - 0.5f) * scale
            );
        }
    }

    /**
     * 直接设置角速度（rad/s）
     */
    public void setAngularVelocity(float ax, float ay, float az)
    {
        if (this.physicsWorld != null && this.bodyHandle != 0)
        {
            this.physicsWorld.setBodyAngularVelocity(this.bodyHandle, ax, ay, az);
        }
    }

    /**
     * 施加冲量（N·s）
     */
    public void applyImpulse(double ix, double iy, double iz)
    {
        if (this.physicsWorld != null && this.bodyHandle != 0)
        {
            this.physicsWorld.applyImpulse(this.bodyHandle, ix, iy, iz);
        }
    }

    // 以下方法保留为 no-op（Rust 端在创建刚体时已设置 friction/restitution）
    public void setRestitution(float restitution) { /* 在 createDynamicBlock 时设置 */ }
    public void setFriction(float friction) { /* 在 createDynamicBlock 时设置 */ }
    public void setGravityScale(float gravityScale) { /* Rapier 默认重力，暂不支持单独设置 */ }

    public void setBlockId(int blockId) { this.blockId = blockId; }
    public void setReplayId(UUID replayId) { this.replayId = replayId; }
    public int getBlockId() { return this.blockId; }

    /**
     * 判断刚体是否休眠
     */
    public boolean isSleeping()
    {
        if (this.physicsWorld != null && this.bodyHandle != 0)
        {
            return this.physicsWorld.isBodySleeping(this.bodyHandle);
        }
        return false;
    }

    /* === 供 PhysicsEntityManager 使用的坐标 getter（返回方块中心，从 native 读取） === */

    /**
     * @deprecated 已迁移到 native Rapier 物理，此方法返回 null（仅供旧 PhysicsEngine 编译）
     */
    @Deprecated
    public PhysicsState getPhysicsState() { return null; }

    public double getCenterX()
    {
        if (this.physicsWorld != null && this.bodyHandle != 0)
        {
            this.physicsWorld.getBodyTransform(this.bodyHandle, _tmpPos, _tmpRot);
            return _tmpPos[0];
        }
        return this.method_23317();
    }

    public double getCenterY()
    {
        if (this.physicsWorld != null && this.bodyHandle != 0)
        {
            this.physicsWorld.getBodyTransform(this.bodyHandle, _tmpPos, _tmpRot);
            return _tmpPos[1];
        }
        return this.method_23318() + 0.5;
    }

    public double getCenterZ()
    {
        if (this.physicsWorld != null && this.bodyHandle != 0)
        {
            this.physicsWorld.getBodyTransform(this.bodyHandle, _tmpPos, _tmpRot);
            return _tmpPos[2];
        }
        return this.method_23321();
    }

    /**
     * 覆写 remove：从 native world 移除刚体（释放 native 内存）
     *
     * discard() 是 final，但它内部调用 remove()，所以覆写 remove 能覆盖所有移除路径。
     * 不销毁物理世界（世界由 PhysicsWorldRegistry 超时/clearAll 管理）。
     */
    @Override
    public void method_5650(class_5529 reason)
    {
        if (this.physicsWorld != null && this.bodyHandle != 0)
        {
            this.physicsWorld.removeBody(this.bodyHandle);
            this.bodyHandle = 0;
        }
        super.method_5650(reason);
    }
}
