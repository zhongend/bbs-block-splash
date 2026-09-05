package com.example.bbsanimatedbreak.actions.physics;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 物理方块的状态数据（服务端权威，不同步到客户端）
 *
 * 借鉴 Sable 的物理状态表示：
 * - 位置（double，米）
 * - 线速度（double，m/s）
 * - 角速度（Vector3f，rad/s）
 * - 旋转四元数（Quaternionf，单位化）
 * - 质量参数（单方块常数，但保留为字段以便扩展）
 */
public class PhysicsState
{
    /* === 位置 === */
    public double x, y, z;

    /* === 线速度 === */
    public double vx, vy, vz;

    /* === 角速度（rad/s） === */
    public Vector3f angularVelocity = new Vector3f(0, 0, 0);

    /* === 旋转四元数 === */
    public Quaternionf rotation = new Quaternionf(0, 0, 0, 1);

    /* === 物理参数 === */
    public float mass = 1.0F;
    public float restitution = PhysicsEngine.DEFAULT_RESTITUTION;
    public float friction = PhysicsEngine.DEFAULT_FRICTION;
    public float gravityScale = PhysicsEngine.DEFAULT_GRAVITY_SCALE;

    /* === 运行时状态 === */
    public boolean sleeping = false;
    public int life = 0;

    /**
     * 是否接地（Y 轴向下碰撞后置 true）
     *
     * 关键修复（参考 Sable 的 IslandManager 接地判定）：
     * 接地状态下跳过重力和 Y 轴向下碰撞检测，避免"推回→重力→碰撞→推回"的抖动循环。
     * 接地时仍保留线速度（vx, vz）和角速度，让方块能滑动和滚动。
     * 每帧检查下方是否有方块，无方块时解除接地。
     * 被其他方块撞击时也解除接地（重新进入完整物理模拟）。
     */
    public boolean grounded = false;

    /**
     * 休眠计数器：连续低速+接触地面的帧数
     * 必须达到阈值才真正休眠，避免抛物线顶点被误判休眠导致悬浮
     */
    public int sleepCounter = 0;

    /**
     * 设置初始位置
     */
    public void setPosition(double x, double y, double z)
    {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * 设置初始线速度
     */
    public void setVelocity(double vx, double vy, double vz)
    {
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
    }

    /**
     * 设置随机角速度（用于视觉效果，让方块在飞行中旋转）
     *
     * @param magnitude 角速度大小（rad/s）
     */
    public void setRandomAngularVelocity(float magnitude)
    {
        this.angularVelocity.set(
            (float) (Math.random() - 0.5) * 2 * magnitude,
            (float) (Math.random() - 0.5) * 2 * magnitude,
            (float) (Math.random() - 0.5) * 2 * magnitude
        );
    }
}
