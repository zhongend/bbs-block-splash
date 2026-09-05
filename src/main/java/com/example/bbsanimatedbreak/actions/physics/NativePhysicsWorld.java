package com.example.bbsanimatedbreak.actions.physics;

/**
 * 原生物理世界的 Java 端管理器
 *
 * 每个 BBS 回放（replay）对应一个 NativePhysicsWorld 实例。
 * 在回放开始时创建世界，添加静态方块碰撞体，创建动态方块刚体。
 * 每 tick 步进一次，读取刚体变换更新实体。
 * 回放结束时销毁世界释放 native 内存。
 *
 * 使用 Rapier3d 提供完整的刚体物理：
 * - PGS 约束求解器（顺序冲量法）→ 稳定堆叠，无方块聚团
 * - 库仑摩擦锥 → 真实摩擦和滚动（角速度与线速度自然耦合）
 * - 接触流形（多点接触）→ 无穿透
 * - IslandManager 休眠 → 自动休眠，无悬浮
 * - CCD（连续碰撞检测）→ 高速无穿透
 */
public class NativePhysicsWorld implements AutoCloseable
{
    private final long worldPtr;
    private boolean valid = true;

    /**
     * 创建原生物理世界（默认重力 -11.0 m/s²，Sable 调校值）
     */
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

    /**
     * 步进物理（每 tick 调用，dt=1/20）
     *
     * Rapier 内部会做约束求解迭代，通常 4 次迭代足够稳定。
     * 不需要像纯 Java 版那样做 4 子步——Rapier 的 PGS 求解器已经处理了稳定性。
     */
    public void step(double dt) {
        if (!valid) return;
        NativePhysicsLibrary.nStep(worldPtr, dt);
    }

    /**
     * 添加静态方块碰撞体（从 Minecraft 世界读取）
     * 方块中心在 (x+0.5, y+0.5, z+0.5)
     */
    public void addStaticBlock(int x, int y, int z) {
        NativePhysicsLibrary.nAddStaticBlock(worldPtr, x, y, z);
    }

    /**
     * 添加静态 AABB 碰撞体（用于非完整方块，如楼梯、台阶）
     */
    public void addStaticBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        NativePhysicsLibrary.nAddStaticBox(worldPtr, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * 创建动态方块刚体，返回 body handle
     *
     * @param x          初始位置 X（方块中心，米）
     * @param y          初始位置 Y（方块中心，米）
     * @param z          初始位置 Z（方块中心，米）
     * @param mass       质量（kg，Sable 默认 1.0）
     * @param friction   摩擦系数（0.8 = 方块能滑动但不打滑）
     * @param restitution 弹性系数（0.1 = 轻微弹跳）
     * @return body handle（long），用于后续操作
     */
    public long createDynamicBlock(double x, double y, double z, float mass, float friction, float restitution) {
        return NativePhysicsLibrary.nCreateDynamicBlock(worldPtr, x, y, z, mass, friction, restitution);
    }

    /** 移除刚体 */
    public void removeBody(long bodyHandle) {
        NativePhysicsLibrary.nRemoveBody(worldPtr, bodyHandle);
    }

    /**
     * 获取当前世界中的刚体总数（含已休眠的，不含已 removeBody 的）
     *
     * 用于检测空世界并自动销毁，修复回放结束未清理世界的资源泄漏。
     * 当所有方块被 discard/removeBody 后，刚体数归零，世界可立即销毁，
     * 不必等 60 秒超时。
     */
    public int getBodyCount() {
        return NativePhysicsLibrary.nGetBodyCount(worldPtr);
    }

    /**
     * 获取刚体变换（位置+旋转四元数）
     *
     * @param bodyHandle 刚体句柄
     * @param pos        输出位置 [x, y, z]（方块中心，米）
     * @param rot        输出旋转四元数 [qx, qy, qz, qw]
     */
    public void getBodyTransform(long bodyHandle, double[] pos, float[] rot) {
        NativePhysicsLibrary.nGetBodyTransform(worldPtr, bodyHandle, pos, rot);
    }

    /** 获取刚体线速度 [vx, vy, vz]（m/s） */
    public void getBodyVelocity(long bodyHandle, double[] vel) {
        NativePhysicsLibrary.nGetBodyVelocity(worldPtr, bodyHandle, vel);
    }

    /** 获取刚体角速度 [ax, ay, az]（rad/s） */
    public void getBodyAngularVelocity(long bodyHandle, float[] angVel) {
        NativePhysicsLibrary.nGetBodyAngularVelocity(worldPtr, bodyHandle, angVel);
    }

    /** 判断刚体是否休眠 */
    public boolean isBodySleeping(long bodyHandle) {
        return NativePhysicsLibrary.nIsBodySleeping(worldPtr, bodyHandle);
    }

    /** 唤醒刚体 */
    public void wakeUpBody(long bodyHandle) {
        NativePhysicsLibrary.nWakeUpBody(worldPtr, bodyHandle);
    }

    /** 设置刚体速度（m/s） */
    public void setBodyVelocity(long bodyHandle, double vx, double vy, double vz) {
        NativePhysicsLibrary.nSetBodyVelocity(worldPtr, bodyHandle, vx, vy, vz);
    }

    /** 设置刚体角速度（rad/s） */
    public void setBodyAngularVelocity(long bodyHandle, float ax, float ay, float az) {
        NativePhysicsLibrary.nSetBodyAngularVelocity(worldPtr, bodyHandle, ax, ay, az);
    }

    /** 施加冲量（N·s） */
    public void applyImpulse(long bodyHandle, double ix, double iy, double iz) {
        NativePhysicsLibrary.nApplyImpulse(worldPtr, bodyHandle, ix, iy, iz);
    }

    /** 设置刚体线性和角阻尼（自定义物理手感） */
    public void setBodyDamping(long bodyHandle, double linear, double angular) {
        NativePhysicsLibrary.nSetBodyDamping(worldPtr, bodyHandle, linear, angular);
    }

    /** 清除所有刚体（保留世界） */
    public void clear() {
        NativePhysicsLibrary.nClearWorld(worldPtr);
    }

    public boolean isValid() { return valid; }
    public long getWorldPtr() { return worldPtr; }

    @Override
    public void close() {
        if (valid && worldPtr != 0) {
            NativePhysicsLibrary.nDestroyWorld(worldPtr);
            valid = false;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }
}
