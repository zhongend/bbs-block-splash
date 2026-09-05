package com.example.bbsanimatedbreak.actions.physics;

/**
 * 物理后端世界的统一抽象
 *
 * 本插件支持两种刚体物理后端驱动 BlockSplash 物理效果：
 * - "sable"：Rapier3d（Rust 编译的 bbs_physics.dll，经 JNI 调用）—— NativePhysicsWorld
 * - "jolt"  ：Jolt Physics（jolt-jni 绑定，与 Wemppy4/bbs-physics-engine 同源引擎）—— JoltPhysicsWorld
 *
 * 两种后端共享同一套调用约定（由 BlockSplashActionClip 的物理路径与
 * PhysicsBlockEntity 使用）：
 * - 位置单位：方块（1 方块 = 1 米）
 * - 坐标语义：动态方块刚体的位置 = 方块几何中心；MC 实体坐标 = 脚部（center - 0.5）
 * - 速度单位：m/s（调用方负责与 blocks/tick 换算，×20 或 ÷20）
 * - 旋转：四元数 [qx, qy, qz, qw]
 * - handle == 0 永远表示"无刚体"（Jolt 的 body id 0 会被 +1 偏移，保证 0 永不冲突）
 *
 * 生命周期由 PhysicsWorldRegistry 统一管理（创建 / 每 tick 步进 /
 * 空世界销毁 / 超时销毁 / 服务器停止清理），两种后端行为一致。
 */
public interface PhysicsBackendWorld extends AutoCloseable
{
    /**
     * 创建一个动态方块刚体（1×1×1 立方体）
     *
     * @param x          初始位置 X（方块中心，米）
     * @param y          初始位置 Y（方块中心，米）
     * @param z          初始位置 Z（方块中心，米）
     * @param mass       质量（kg）
     * @param friction   摩擦系数（0.8 = 能滑动不打滑）
     * @param restitution 弹性系数（0.1 = 轻微弹跳）
     * @return 刚体句柄（永不返回 0；失败返回 0，调用方以 0 判定失败）
     */
    long createDynamicBlock(double x, double y, double z, float mass, float friction, float restitution);

    /**
     * 添加静态方块碰撞体（地面/墙壁），中心在 (x+0.5, y+0.5, z+0.5)
     */
    void addStaticBlock(int x, int y, int z);

    /** 移除并销毁刚体（释放 native 资源） */
    void removeBody(long handle);

    /**
     * 动态刚体数量（不含静态碰撞体）
     * PhysicsWorldRegistry 以此检测空世界并提前销毁
     */
    int getBodyCount();

    /**
     * 读取刚体变换
     *
     * @param pos 输出 [x, y, z]（方块中心，米）
     * @param rot 输出 [qx, qy, qz, qw] 四元数
     */
    void getBodyTransform(long handle, double[] pos, float[] rot);

    /** 读取线速度 [vx, vy, vz]（m/s） */
    void getBodyVelocity(long handle, double[] vel);

    /** 读取角速度 [ax, ay, az]（rad/s） */
    void getBodyAngularVelocity(long handle, float[] angVel);

    /** 刚体是否已休眠 */
    boolean isBodySleeping(long handle);

    /** 设置线速度（m/s） */
    void setBodyVelocity(long handle, double vx, double vy, double vz);

    /** 设置角速度（rad/s） */
    void setBodyAngularVelocity(long handle, float ax, float ay, float az);

    /** 施加冲量（N·s） */
    void applyImpulse(long handle, double ix, double iy, double iz);

    /** 设置线性/角阻尼（每秒速率，Rapier 语义 v *= exp(-rate·dt)） */
    void setBodyDamping(long handle, double linear, double angular);

    /**
     * 推进一个 Minecraft tick（1/20 秒）
     * 子步进策略由各后端自定（Rapier 2 子步 / Jolt 3 子步），
     * 由 PhysicsWorldRegistry 每 tick 对每个世界调用一次。
     */
    void stepTick();

    /** 世界是否仍然有效（未 close / 未失效） */
    boolean isValid();

    @Override
    void close();
}
