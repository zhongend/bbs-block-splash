package com.example.bbsanimatedbreak.actions.physics;

/**
 * 原生物理世界的 Java 端管理器（Rapier3d 后端）
 *
 * 每个 BBS 回放片段对应一个 NativePhysicsWorld 实例。
 * 物理步进由 PhysicsWorldRegistry 按 **BBS 回放时钟** 驱动。
 *
 * 使用 Rapier3d 提供完整的刚体物理：
 * - PGS 约束求解器（顺序冲量法）→ 稳定堆叠，无方块聚团
 * - 库仑摩擦锥 → 真实摩擦和滚动（角速度与线速度自然耦合）
 * - 接触流形（多点接触）→ 无穿透
 * - IslandManager 休眠 → 自动休眠，无悬浮
 * - CCD（连续碰撞检测）→ 高速无穿透
 *
 * ==================================================================
 * native 安全：close() 之后绝不触碰 worldPtr
 * ==================================================================
 * 本类是所有 native 调用的唯一出口，因此在这里做统一闸门：
 * {@code valid == false} 时（worldPtr 已释放）
 * - 设置类方法直接忽略
 * - 读取类方法写入安全默认值（位置 0、单位四元数、速度 0）
 *   —— 这样即使有遗漏的调用路径，也只是得到一个静止的默认值，
 *   而不会变成 use-after-free 导致的垃圾坐标 / 崩溃。
 */
public class NativePhysicsWorld implements PhysicsBackendWorld
{
    private final long worldPtr;
    private boolean valid = true;

    /** 每 tick 内部子步进次数 */
    private static final int SUBSTEPS = 2;

    /** 一个 Minecraft tick 的秒数 */
    private static final double TICK = 1.0 / 20.0;

    public NativePhysicsWorld() {
        this(0.0, -11.0, 0.0);
    }

    /**
     * 创建原生物理世界（自定义重力）
     *
     * @param gx 重力 X 分量（m/s²）
     * @param gy 重力 Y 分量（m/s²，向下为负）
     * @param gz 重力 Z 分量（m/s²）
     */
    public NativePhysicsWorld(double gx, double gy, double gz) {
        NativePhysicsLibrary.load();
        this.worldPtr = NativePhysicsLibrary.nCreateWorld(gx, gy, gz);
        if (this.worldPtr == 0) {
            throw new RuntimeException("Failed to create native physics world (nCreateWorld returned 0)");
        }
    }

    /** native 调用闸门：世界是否已释放 */
    private boolean dead() {
        return !this.valid || this.worldPtr == 0;
    }

    /**
     * 步进物理（dt 秒）
     */
    public void step(double dt) {
        if (dead()) return;
        NativePhysicsLibrary.nStep(worldPtr, dt);
    }

    /**
     * 推进一个 Minecraft tick（内部 2 次子步进）
     */
    @Override
    public void stepTick() {
        if (dead()) return;

        double subDt = TICK / SUBSTEPS;
        for (int i = 0; i < SUBSTEPS; i++) {
            step(subDt);
        }
    }

    /**
     * 添加静态方块碰撞体，中心在 (x+0.5, y+0.5, z+0.5)
     */
    public void addStaticBlock(int x, int y, int z) {
        if (dead()) return;
        NativePhysicsLibrary.nAddStaticBlock(worldPtr, x, y, z);
    }

    /**
     * 添加静态 AABB 碰撞体（用于非完整方块，如楼梯、台阶）
     */
    public void addStaticBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (dead()) return;
        NativePhysicsLibrary.nAddStaticBox(worldPtr, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * 创建动态方块刚体，返回 body handle
     *
     * @param x/z 初始位置（方块中心，米）
     * @param y   初始位置 Y（方块中心，米）
     * @return body handle；世界已释放时返回 0
     */
    public long createDynamicBlock(double x, double y, double z, float mass, float friction, float restitution) {
        if (dead()) return 0;
        return NativePhysicsLibrary.nCreateDynamicBlock(worldPtr, x, y, z, mass, friction, restitution);
    }

    /** 移除刚体 */
    public void removeBody(long bodyHandle) {
        if (dead() || bodyHandle == 0) return;
        NativePhysicsLibrary.nRemoveBody(worldPtr, bodyHandle);
    }

    /**
     * 当前世界中的刚体总数（含已休眠的，不含静态碰撞体）
     */
    public int getBodyCount() {
        if (dead()) return 0;
        return NativePhysicsLibrary.nGetBodyCount(worldPtr);
    }

    /**
     * 获取刚体变换（位置 + 四元数）
     */
    public void getBodyTransform(long bodyHandle, double[] pos, float[] rot) {
        if (dead() || bodyHandle == 0) {
            pos[0] = pos[1] = pos[2] = 0;
            rot[0] = rot[1] = rot[2] = 0;
            rot[3] = 1;
            return;
        }
        NativePhysicsLibrary.nGetBodyTransform(worldPtr, bodyHandle, pos, rot);
    }

    /** 获取刚体线速度 [vx, vy, vz]（m/s） */
    public void getBodyVelocity(long bodyHandle, double[] vel) {
        if (dead() || bodyHandle == 0) {
            vel[0] = vel[1] = vel[2] = 0;
            return;
        }
        NativePhysicsLibrary.nGetBodyVelocity(worldPtr, bodyHandle, vel);
    }

    /** 获取刚体角速度 [ax, ay, az]（rad/s） */
    public void getBodyAngularVelocity(long bodyHandle, float[] angVel) {
        if (dead() || bodyHandle == 0) {
            angVel[0] = angVel[1] = angVel[2] = 0;
            return;
        }
        NativePhysicsLibrary.nGetBodyAngularVelocity(worldPtr, bodyHandle, angVel);
    }

    /** 判断刚体是否休眠 */
    public boolean isBodySleeping(long bodyHandle) {
        if (dead() || bodyHandle == 0) return true;
        return NativePhysicsLibrary.nIsBodySleeping(worldPtr, bodyHandle);
    }

    /** 唤醒刚体 */
    public void wakeUpBody(long bodyHandle) {
        if (dead() || bodyHandle == 0) return;
        NativePhysicsLibrary.nWakeUpBody(worldPtr, bodyHandle);
    }

    /** 设置刚体速度（m/s） */
    public void setBodyVelocity(long bodyHandle, double vx, double vy, double vz) {
        if (dead() || bodyHandle == 0) return;
        NativePhysicsLibrary.nSetBodyVelocity(worldPtr, bodyHandle, vx, vy, vz);
    }

    /** 设置刚体角速度（rad/s） */
    public void setBodyAngularVelocity(long bodyHandle, float ax, float ay, float az) {
        if (dead() || bodyHandle == 0) return;
        NativePhysicsLibrary.nSetBodyAngularVelocity(worldPtr, bodyHandle, ax, ay, az);
    }

    /** 施加冲量（N·s） */
    public void applyImpulse(long bodyHandle, double ix, double iy, double iz) {
        if (dead() || bodyHandle == 0) return;
        NativePhysicsLibrary.nApplyImpulse(worldPtr, bodyHandle, ix, iy, iz);
    }

    /** 设置刚体线性和角阻尼 */
    public void setBodyDamping(long bodyHandle, double linear, double angular) {
        if (dead() || bodyHandle == 0) return;
        NativePhysicsLibrary.nSetBodyDamping(worldPtr, bodyHandle, linear, angular);
    }

    @Override
    public boolean isValid() { return valid; }

    public long getWorldPtr() { return worldPtr; }

    @Override
    public void close() {
        if (valid && worldPtr != 0) {
            NativePhysicsLibrary.nDestroyWorld(worldPtr);
            valid = false;
        }
    }
}
