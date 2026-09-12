package com.example.bbsanimatedbreak.actions.physics;

import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.JobSystem;
import com.github.stephengold.joltjni.JobSystemSingleThreaded;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.TempAllocator;
import com.github.stephengold.joltjni.TempAllocatorImpl;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;

/**
 * Jolt Physics 刚体世界（"BBS 物理引擎"后端）
 *
 * 与 Wemppy4/bbs-physics-engine 同源的物理引擎（Jolt，经 jolt-jni 绑定），
 * 把它的引擎层用法适配到本插件的"世界方块"场景：
 * 一个世界对应一次飞溅（同 Rapier 后端），静态碰撞体 = 飞溅区域周围的
 * Minecraft 方块，动态刚体 = 飞溅的方块。
 *
 * === 与 bbs-physics-engine 一致的关键调校 ===
 * - 单位：1 方块 = 1 米（Jolt 在 10cm 以下分辨率不佳，方块作为米正好）
 * - 子步进：每 tick（50ms）拆 3 次求解（≈60Hz）——堆叠不下沉、接触不平炸
 * - CCD（LinearCast）：电影方块初速度高，50ms 内能飞出自身厚度数倍，
 *   只在步进端点检测会直接穿地（作者报告的"穿模"大头），CCD 用百分之几的
 *   步进代价根除
 * - 质量覆盖：setMass + CalculateInertia——按形状自动算惯性，方块翻滚手感
 *   像方块而不是像质点
 * - 单线程求解：Jolt 多线程结果与线程数相关，回放必须逐帧可复现
 *
 * === 与 Rapier 后端（NativePhysicsWorld）的差异 ===
 * - 阻尼语义：Rapier 是指数衰减 v *= exp(-rate·dt)；Jolt 是每步
 *   v *= 1/(1+rate·dt)。在 40~60 步/秒下两者对同速率的差别 <1%，
 *   本类直接接收 Rapier 语义的每秒速率，视觉手感一致
 * - Jolt 的 system.getNumBodies() 含静态碰撞体（最多 2000+），不能用于
 *   PhysicsWorldRegistry 的空世界检测——动态刚体计数由本类自行维护
 * - body id 是 int 且 0 可能合法：对外统一 +1 偏移，保证 handle==0 恒为
 *   "无刚体"约定（见 PhysicsBackendWorld）
 */
public class JoltPhysicsWorld implements PhysicsBackendWorld
{
    /** 对象层：静态碰撞体（世界方块） */
    private static final int LAYER_STATIC = 0;

    /** 对象层：动态方块 */
    private static final int LAYER_MOVING = 1;

    /** 每个电影 tick（50ms）内部的求解次数——3 次 ≈ 60Hz，固定值保证回放可复现 */
    private static final int COLLISION_STEPS = 3;

    /** 一个 Minecraft tick 的秒数 */
    private static final float TICK = 1F / 20F;

    /* 容量限制取 bbs-physics-engine 验证过的值：分配器单次分配随
     * MAX_BODY_PAIRS/MAX_CONTACTS 线性放大，盲目调大会让 TempAllocator 溢出 */
    private static final int MAX_BODIES = 4096;
    private static final int MAX_BODY_PAIRS = 4096;
    private static final int MAX_CONTACTS = 2048;
    private static final int TEMP_ALLOCATOR_BYTES = 12 * 1024 * 1024;
    private static final int JOB_QUEUE = 2048;

    /** Jolt 凸体圆角半径：动态方块 0.48 + 0.02 = 外沿正好 0.5，与实体视觉尺寸一致 */
    private static final float CONVEX_RADIUS = 0.02F;

    private final ObjectLayerPairFilterTable pairFilter;
    private final BroadPhaseLayerInterfaceTable broadPhaseLayers;
    private final ObjectVsBroadPhaseLayerFilterTable broadPhaseFilter;

    private final PhysicsSystem system;
    private final BodyInterface bodies;
    private final TempAllocator temp;
    private final JobSystem jobs;

    /** 共享形状：静态世界方块（半边长 0.5） */
    private final BoxShape staticBlockShape;

    /** 共享形状：动态飞溅方块（半边长 0.48 + 0.02 圆角 = 外沿 0.5） */
    private final BoxShape dynamicBlockShape;

    /** 变换读取复用对象（零 GC：getPositionAndRotation 原地填充） */
    private final RVec3 tmpPosition = new RVec3(0.0D, 0.0D, 0.0D);
    private final Quat tmpRotation = Quat.sIdentity();

    /**
     * 动态刚体句柄集合。
     * 空世界检测（PhysicsWorldRegistry）只应统计动态刚体；Jolt 的
     * getNumBodies() 含静态碰撞体，所以这里自行记账。
     */
    private final java.util.Set<Long> dynamicHandles = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * 句柄 → Body 引用。
     * Jolt 的 BodyInterface 没有按 id 取 Body 的 API，而 setBodyDamping 需要
     * Body.getMotionProperties()，所以创建时保留 Body 引用（≤ maxPhysicsBlocks 个，开销可忽略）。
     */
    private final java.util.Map<Long, Body> dynamicBodies = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean valid = true;

    public JoltPhysicsWorld(double gx, double gy, double gz)
    {
        if (!JoltRuntime.available())
        {
            throw new IllegalStateException("Jolt 不可用，无法创建 JoltPhysicsWorld");
        }

        /* 层表：STATIC↔MOVING、MOVING↔MOVING；静态间互不检测（都推不动，纯浪费） */
        this.pairFilter = new ObjectLayerPairFilterTable(2);
        this.pairFilter.enableCollision(LAYER_STATIC, LAYER_MOVING);
        this.pairFilter.enableCollision(LAYER_MOVING, LAYER_MOVING);

        /* 宽相层：动不动分两棵树，静态树不随每步重建 */
        this.broadPhaseLayers = new BroadPhaseLayerInterfaceTable(2, 2);
        this.broadPhaseLayers.mapObjectToBroadPhaseLayer(LAYER_STATIC, 0);
        this.broadPhaseLayers.mapObjectToBroadPhaseLayer(LAYER_MOVING, 1);

        /* 参数顺序注意：第二个参数是对象层数量、最后一个才是宽相层数量，
         * 颠倒会让高编号对象层永远查不到任何候选（静默失效） */
        this.broadPhaseFilter = new ObjectVsBroadPhaseLayerFilterTable(
            this.broadPhaseLayers, 2, this.pairFilter, 2);

        this.system = new PhysicsSystem();
        this.system.init(MAX_BODIES, 0, MAX_BODY_PAIRS, MAX_CONTACTS,
            this.broadPhaseLayers, this.broadPhaseFilter, this.pairFilter);
        this.system.setGravity((float) gx, (float) gy, (float) gz);
        this.bodies = this.system.getBodyInterface();

        this.temp = new TempAllocatorImpl(TEMP_ALLOCATOR_BYTES);
        this.jobs = new JobSystemSingleThreaded(JOB_QUEUE);

        this.staticBlockShape = new BoxShape(new Vec3(0.5F, 0.5F, 0.5F), CONVEX_RADIUS);
        this.dynamicBlockShape = new BoxShape(new Vec3(0.48F, 0.48F, 0.48F), CONVEX_RADIUS);
    }

    @Override
    public void addStaticBlock(int x, int y, int z)
    {
        if (!this.valid)
        {
            return;
        }

        /* 方块坐标 → 几何中心坐标（与 Rapier 后端约定一致） */
        BodyCreationSettings settings = new BodyCreationSettings(
            this.staticBlockShape,
            new RVec3(x + 0.5D, y + 0.5D, z + 0.5D),
            Quat.sIdentity(),
            EMotionType.Static,
            LAYER_STATIC);
        settings.setFriction(0.8F);
        settings.setRestitution(0.1F);

        this.bodies.createAndAddBody(settings, EActivation.DontActivate);
    }

    @Override
    public long createDynamicBlock(double x, double y, double z, float mass, float friction, float restitution)
    {
        if (!this.valid)
        {
            return 0;
        }

        BodyCreationSettings settings = new BodyCreationSettings(
            this.dynamicBlockShape,
            new RVec3(x, y, z),
            Quat.sIdentity(),
            EMotionType.Dynamic,
            LAYER_MOVING);
        settings.setFriction(friction);
        settings.setRestitution(restitution);
        settings.setGravityFactor(1.0F);

        /* CCD：高速方块防穿透（替代 Rapier 后端的 CCD 能力） */
        settings.setMotionQuality(EMotionQuality.LinearCast);

        /* 指定质量、按形状自动算惯性 */
        settings.setMassPropertiesOverride(new MassProperties().setMass(mass));
        settings.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);

        /* 两步创建（createBody + addBody）：保留 Body 引用供 setBodyDamping 使用 */
        Body body = this.bodies.createBody(settings);

        if (body == null)
        {
            return 0;
        }

        int bodyId = body.getId();
        this.bodies.addBody(body, EActivation.Activate);

        /* +1 偏移：保证对外 handle 永远不为 0（0 = 无刚体约定） */
        long handle = bodyId + 1L;
        this.dynamicHandles.add(handle);
        this.dynamicBodies.put(handle, body);

        return handle;
    }

    @Override
    public void removeBody(long handle)
    {
        if (!this.valid || handle <= 0)
        {
            return;
        }

        int bodyId = toBodyId(handle);
        this.dynamicHandles.remove(handle);
        this.dynamicBodies.remove(handle);

        try
        {
            this.bodies.removeBody(bodyId);
            this.bodies.destroyBody(bodyId);
        }
        catch (Throwable ignored)
        {
            /* 世界关闭竞态下的重复移除无害 */
        }
    }

    @Override
    public int getBodyCount()
    {
        return this.dynamicHandles.size();
    }

    @Override
    public void getBodyTransform(long handle, double[] pos, float[] rot)
    {
        /* native 安全闸门：close() 之后 PhysicsSystem 已释放，
         * 任何查询都可能读到垃圾（表现为方块瞬移/抽搐）甚至崩溃。
         * 这里返回安全默认值（原点 + 单位四元数）。 */
        if (!this.valid || handle <= 0)
        {
            pos[0] = pos[1] = pos[2] = 0D;
            rot[0] = rot[1] = rot[2] = 0F;
            rot[3] = 1F;
            return;
        }

        int bodyId = toBodyId(handle);

        /* 原地填充复用对象，避免每实体每 tick 分配 */
        this.bodies.getPositionAndRotation(bodyId, this.tmpPosition, this.tmpRotation);

        /* RVec3 的分量 getter 返回 Object（单精度构建=Float，双精度=Double），
         * 用 Number 中转兼容两种 native 构建 */
        pos[0] = ((Number) this.tmpPosition.getX()).doubleValue();
        pos[1] = ((Number) this.tmpPosition.getY()).doubleValue();
        pos[2] = ((Number) this.tmpPosition.getZ()).doubleValue();

        rot[0] = this.tmpRotation.getX();
        rot[1] = this.tmpRotation.getY();
        rot[2] = this.tmpRotation.getZ();
        rot[3] = this.tmpRotation.getW();
    }

    @Override
    public void getBodyVelocity(long handle, double[] vel)
    {
        if (!this.valid || handle <= 0)
        {
            vel[0] = vel[1] = vel[2] = 0D;
            return;
        }

        Vec3 velocity = this.bodies.getLinearVelocity(toBodyId(handle));
        vel[0] = velocity.getX();
        vel[1] = velocity.getY();
        vel[2] = velocity.getZ();
    }

    @Override
    public void getBodyAngularVelocity(long handle, float[] angVel)
    {
        if (!this.valid || handle <= 0)
        {
            angVel[0] = angVel[1] = angVel[2] = 0F;
            return;
        }

        Vec3 velocity = this.bodies.getAngularVelocity(toBodyId(handle));
        angVel[0] = velocity.getX();
        angVel[1] = velocity.getY();
        angVel[2] = velocity.getZ();
    }

    @Override
    public boolean isBodySleeping(long handle)
    {
        if (!this.valid || handle <= 0)
        {
            return true;
        }

        return !this.bodies.isActive(toBodyId(handle));
    }

    @Override
    public void setBodyVelocity(long handle, double vx, double vy, double vz)
    {
        if (!this.valid || handle <= 0)
        {
            return;
        }

        this.bodies.setLinearVelocity(toBodyId(handle), new Vec3((float) vx, (float) vy, (float) vz));
    }

    @Override
    public void setBodyAngularVelocity(long handle, float ax, float ay, float az)
    {
        if (!this.valid || handle <= 0)
        {
            return;
        }

        this.bodies.setAngularVelocity(toBodyId(handle), new Vec3(ax, ay, az));
    }

    @Override
    public void applyImpulse(long handle, double ix, double iy, double iz)
    {
        if (!this.valid || handle <= 0)
        {
            return;
        }

        this.bodies.addImpulse(toBodyId(handle), new Vec3((float) ix, (float) iy, (float) iz));
    }

    @Override
    public void setBodyDamping(long handle, double linear, double angular)
    {
        if (!this.valid || handle <= 0)
        {
            return;
        }

        Body body = this.dynamicBodies.get(handle);

        if (body == null)
        {
            return;
        }

        body.getMotionProperties().setLinearDamping((float) linear);
        body.getMotionProperties().setAngularDamping((float) angular);
    }

    @Override
    public void stepTick()
    {
        if (!this.valid)
        {
            return;
        }

        this.system.update(TICK, COLLISION_STEPS, this.temp, this.jobs);
    }

    @Override
    public boolean isValid()
    {
        return this.valid;
    }

    @Override
    public void close()
    {
        if (!this.valid)
        {
            return;
        }

        this.valid = false;
        this.dynamicHandles.clear();

        /* 与构造逆序：system 持有层表/分配器/任务系统的引用，先关 */
        this.system.close();
        this.jobs.close();
        this.temp.close();
        this.broadPhaseFilter.close();
        this.broadPhaseLayers.close();
        this.pairFilter.close();
    }

    /** 对外句柄 → Jolt body id（见 createDynamicBlock 的 +1 偏移） */
    private static int toBodyId(long handle)
    {
        return (int) (handle - 1L);
    }
}
