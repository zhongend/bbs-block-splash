package com.example.bbsanimatedbreak.actions.physics;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import net.minecraft.class_1937;
import net.minecraft.class_2338;
import net.minecraft.class_2680;

/**
 * Sable 风格物理引擎（参考 https://github.com/ryanhcode/sable）
 *
 * Sable 实际物理用 Rapier3d（Rust），本类用纯 Java 模拟其物理参数和算法。
 *
 * === v1.9.3 关键修复 ===
 * 1. 卡顿下落 bug：添加 grounded 接地状态，接地时跳过重力和 Y 轴向下碰撞，
 *    避免"推回→重力→碰撞→推回"的抖动循环（参考 Sable 的 IslandManager 接地判定）
 * 2. 方块重叠 bug：实体碰撞在每个子步都处理，不再只在 tick 末尾
 * 3. 方块遁地 bug：Y 轴碰撞优先处理，碰撞推回精确贴合 + 接地状态防止穿透
 * 4. 落地无滚动 bug：落地后保留角速度，用滚动摩擦缓慢衰减，让方块能滚动
 *
 * === 参考 Sable 的物理参数 ===
 * - 重力：11.0 m/s²（向下，Sable 游戏调校值）
 * - 线性阻尼（linear_damping）：0.09/秒（Sable 的 DEFAULT_UNIVERSAL_DRAG）
 * - 角阻尼（angular_damping）：0.5/秒（空中）
 * - 滚动摩擦：0.3/秒（接地时角速度衰减，比空中慢，让方块能滚动）
 * - 默认弹性：0.0（Sable 默认无反弹）
 * - 默认摩擦：1.0（Sable 摩擦乘数，1.0=完全摩擦）
 * - 默认质量：1.0 kg
 * - 单方块惯性：m/6
 * - 默认子步：4（增加子步减少穿透）
 *
 * === 算法 ===
 * - 积分：半隐式 Euler（辛 Euler）
 * - 线性阻尼：v *= exp(-damping * dt)（Rapier 风格）
 * - 碰撞：AABB vs 世界方块 + AABB vs 其他物理方块（每子步处理）
 * - 旋转：四元数积分 q' = q * exp(ω·dt/2)
 * - 接地：grounded 状态跳过重力，避免抖动
 * - 滚动：接地时保留角速度，用滚动摩擦缓慢衰减
 * - 休眠：连续 N 帧低速 + 接地 → 休眠
 *
 * === 单位 ===
 * 位置：米 | 速度：m/s | 角速度：rad/s | 质量：kg | 时间：秒
 */
public class PhysicsEngine
{
    /* === Sable 物理常量 === */

    /** 重力加速度（m/s²，Sable 用 11.0） */
    public static final double GRAVITY = -11.0;

    /** 线性阻尼（每秒，Sable 的 DEFAULT_UNIVERSAL_DRAG = 0.09） */
    public static final double LINEAR_DAMPING = 0.09;

    /** 角阻尼（每秒，空中） */
    public static final double ANGULAR_DAMPING = 0.5;

    /**
     * 滚动摩擦（每秒，接地时角速度衰减系数）
     * 比空中角阻尼小，让方块落地后能继续滚动一段距离
     * exp(-0.3) ≈ 0.741（每秒衰减 26%）
     */
    public static final double ROLLING_FRICTION = 0.3;

    /** 默认弹性系数（Sable 默认 0.0 = 无反弹） */
    public static final float DEFAULT_RESTITUTION = 0.0F;

    /** 默认摩擦系数（Sable 默认 1.0 = 完全摩擦） */
    public static final float DEFAULT_FRICTION = 1.0F;

    /** 默认重力缩放（1.0 = 正常重力） */
    public static final float DEFAULT_GRAVITY_SCALE = 1.0F;

    /** 默认质量（Sable 的 PhysicsBlockPropertyTypes.MASS 默认 1.0） */
    public static final float DEFAULT_MASS = 1.0F;

    /** 单方块惯性因子（Sable 假设单位立方体，I = m/6） */
    public static final float BLOCK_INERTIA_FACTOR = 1.0F / 6.0F;

    /** 方块半边长（AABB 的 half-extent） */
    public static final float HALF_EXTENT = 0.48F;

    /** 最大生命（tick），超过后销毁（60 秒 = 1200 tick） */
    public static final int MAX_LIFE = 1200;

    /** 休眠速度阈值（m/s） */
    public static final double SLEEP_THRESHOLD = 0.05;

    /** 休眠所需的连续低速+接触帧数（40 帧 = 2 秒） */
    public static final int SLEEP_DELAY_TICKS = 40;

    /** 最大速度（m/s，防止穿透，降低以减少高速穿透） */
    public static final double MAX_VELOCITY = 30.0;

    /** 接触距离阈值（米，休眠检查用） */
    public static final double CONTACT_THRESHOLD = 0.02;

    /**
     * 物理步进（参考 Sable 的 PhysicsPipeline.physicsTick）
     *
     * 每个子步都处理实体间碰撞，避免方块重叠。
     *
     * @param state       物理状态
     * @param world       世界（用于碰撞检测）
     * @param dt          总时间步（秒，通常 1/20）
     * @param substeps    子步数（Sable 默认 2，本模组用 4 减少穿透）
     * @param nearby      附近的物理方块列表（用于实体碰撞，可为 null）
     * @param selfUuid    当前方块的 UUID（用于避免自碰撞和双重处理）
     */
    public static void step(PhysicsState state, class_1937 world, double dt, int substeps,
                            List<PhysicsBlockEntity> nearby, java.util.UUID selfUuid)
    {
        if (state.sleeping) return;

        double subDt = dt / substeps;
        for (int i = 0; i < substeps; i++)
        {
            PhysicsEngine.substep(state, world, subDt);

            // 每个子步处理实体间碰撞（修复方块重叠 bug）
            if (nearby != null && selfUuid != null)
            {
                for (PhysicsBlockEntity other : nearby)
                {
                    if (other.method_5667().equals(selfUuid)) continue;
                    // 只处理 UUID 较小的，避免同一对碰撞被双重处理
                    if (selfUuid.compareTo(other.method_5667()) > 0) continue;
                    PhysicsEngine.resolveEntityCollision(state, other.getPhysicsState());
                }
            }
        }

        PhysicsEngine.checkSleep(state, world);
    }

    /**
     * 单个子步的物理计算（半隐式 Euler + AABB 碰撞）
     */
    private static void substep(PhysicsState state, class_1937 world, double dt)
    {
        // === 1. 重力（仅当未接地）===
        // 接地时跳过重力，避免"推回→重力→碰撞→推回"的抖动循环
        if (!state.grounded)
        {
            state.vy += GRAVITY * state.gravityScale * dt;
        }

        // === 2. 阻尼 ===

        // 2.1 线性阻尼（Rapier 风格：v *= exp(-damping * dt)）
        double linDrag = Math.exp(-LINEAR_DAMPING * dt);
        state.vx *= linDrag;
        if (!state.grounded) state.vy *= linDrag;  // 接地时 Y 速度不衰减（已经是 0）
        state.vz *= linDrag;

        // 2.2 角阻尼（接地时用滚动摩擦，衰减更慢，让方块能滚动）
        double angDragRate = state.grounded ? ROLLING_FRICTION : ANGULAR_DAMPING;
        float angDrag = (float) Math.exp(-angDragRate * dt);
        state.angularVelocity.mul(angDrag);

        // 2.3 速度上限（防止穿透）
        double speedSq = state.vx * state.vx + state.vy * state.vy + state.vz * state.vz;
        if (speedSq > MAX_VELOCITY * MAX_VELOCITY)
        {
            double scale = MAX_VELOCITY / Math.sqrt(speedSq);
            state.vx *= scale;
            state.vy *= scale;
            state.vz *= scale;
        }

        // === 3. 更新位置 ===
        state.x += state.vx * dt;
        state.y += state.vy * dt;
        state.z += state.vz * dt;

        // === 4. 碰撞检测与响应（AABB vs 世界方块） ===
        PhysicsEngine.handleWorldCollisions(state, world);

        // === 5. 接地状态检查 ===
        // 接地时检查下方是否还有方块，无方块则解除接地（开始下落）
        if (state.grounded)
        {
            if (!PhysicsEngine.isOnGround(state, world))
            {
                state.grounded = false;
            }
        }

        // === 6. 角速度积分：四元数 q' = q * exp(ω * dt / 2) ===
        if (state.angularVelocity.lengthSquared() > 1e-8)
        {
            Vector3f w = state.angularVelocity;
            float halfDt = (float) (dt * 0.5);
            Quaternionf dq = new Quaternionf(
                w.x * halfDt, w.y * halfDt, w.z * halfDt, 1.0F
            ).normalize();
            state.rotation.mul(dq).normalize();
        }
    }

    /**
     * AABB vs 世界方块碰撞检测与响应
     *
     * 参考 Sable 的 VoxelColliderData.addBox。
     * 改进：
     * - Y 轴碰撞优先处理（防止穿透地面）
     * - 落地时设置 grounded=true，不衰减角速度（让方块能滚动）
     * - 精确推回到方块边外
     */
    private static void handleWorldCollisions(PhysicsState state, class_1937 world)
    {
        double cx = state.x;
        double cy = state.y;
        double cz = state.z;

        double minX = cx - HALF_EXTENT;
        double maxX = cx + HALF_EXTENT;
        double minY = cy - HALF_EXTENT;
        double maxY = cy + HALF_EXTENT;
        double minZ = cz - HALF_EXTENT;
        double maxZ = cz + HALF_EXTENT;

        int blockMinX = (int) Math.floor(minX) - 1;
        int blockMaxX = (int) Math.floor(maxX) + 1;
        int blockMinY = (int) Math.floor(minY) - 1;
        int blockMaxY = (int) Math.floor(maxY) + 1;
        int blockMinZ = (int) Math.floor(minZ) - 1;
        int blockMaxZ = (int) Math.floor(maxZ) + 1;

        boolean collided = false;

        for (int bx = blockMinX; bx <= blockMaxX; bx++)
        {
            for (int by = blockMinY; by <= blockMaxY; by++)
            {
                for (int bz = blockMinZ; bz <= blockMaxZ; bz++)
                {
                    class_2338 pos = new class_2338(bx, by, bz);
                    class_2680 blockState = world.method_8320(pos);

                    if (blockState.method_26215()) continue;

                    double overlapX = Math.min(maxX, bx + 1) - Math.max(minX, bx);
                    double overlapY = Math.min(maxY, by + 1) - Math.max(minY, by);
                    double overlapZ = Math.min(maxZ, bz + 1) - Math.max(minZ, bz);

                    if (overlapX <= 0 || overlapY <= 0 || overlapZ <= 0) continue;

                    collided = true;

                    // Y 轴碰撞优先处理（防止穿透地面，解决遁地 bug）
                    if (overlapY <= overlapX && overlapY <= overlapZ)
                    {
                        // Y 轴穿透
                        double sign = (cy > by + 0.5) ? 1 : -1;
                        if (sign > 0)
                        {
                            // 方块在下方上方，推到方块顶上方（落地）
                            state.y = by + 1 + HALF_EXTENT;
                            // 落地检测：原速度向下 → 接地
                            if (state.vy <= 0)
                            {
                                state.grounded = true;
                            }
                        }
                        else
                        {
                            // 方块在下方下方，推到方块底下（撞天花板）
                            state.y = by - HALF_EXTENT;
                        }
                        state.vy = -state.vy * state.restitution;
                        // 切向摩擦（衰减水平速度）
                        state.vx *= (1 - state.friction * 0.5);
                        state.vz *= (1 - state.friction * 0.5);
                    }
                    else if (overlapX <= overlapZ)
                    {
                        // X 轴穿透
                        double sign = (cx > bx + 0.5) ? 1 : -1;
                        if (sign > 0) {
                            state.x = bx + 1 + HALF_EXTENT;
                        } else {
                            state.x = bx - HALF_EXTENT;
                        }
                        state.vx = -state.vx * state.restitution;
                        state.vy *= (1 - state.friction * 0.5);
                        state.vz *= (1 - state.friction * 0.5);
                        // 水平碰撞解除接地（方块可能被推下边缘）
                        state.grounded = false;
                    }
                    else
                    {
                        // Z 轴穿透
                        double sign = (cz > bz + 0.5) ? 1 : -1;
                        if (sign > 0) {
                            state.z = bz + 1 + HALF_EXTENT;
                        } else {
                            state.z = bz - HALF_EXTENT;
                        }
                        state.vz = -state.vz * state.restitution;
                        state.vx *= (1 - state.friction * 0.5);
                        state.vy *= (1 - state.friction * 0.5);
                        state.grounded = false;
                    }
                }
            }
        }

        // 注意：不再在碰撞时衰减角速度！
        // 落地后方块应该能继续旋转和滚动（参考 Sable 的滚动物理）
        // 角速度由滚动摩擦（ROLLING_FRICTION）缓慢衰减
    }

    /**
     * 检查方块是否接地（下方有方块支撑）
     *
     * @return true 如果方块底部紧贴下方方块顶部
     */
    private static boolean isOnGround(PhysicsState state, class_1937 world)
    {
        double bottomY = state.y - HALF_EXTENT;
        int belowX = (int) Math.floor(state.x);
        int belowZ = (int) Math.floor(state.z);

        // 检查底部下方 0.02m 范围内是否有方块
        int belowY = (int) Math.floor(bottomY - CONTACT_THRESHOLD);

        class_2338 below = new class_2338(belowX, belowY, belowZ);
        class_2680 belowState = world.method_8320(below);

        if (belowState.method_26215()) return false;

        // 精确距离检查
        double blockTopY = belowY + 1;
        double gap = bottomY - blockTopY;
        return gap < CONTACT_THRESHOLD;
    }

    /**
     * 两个物理方块之间的碰撞响应（AABB vs AABB）
     *
     * 检测两个方块的 AABB 重叠，沿最小穿透轴推开，
     * 根据相对速度和质量计算冲量反弹，切向摩擦衰减。
     * 双方都被修改（位置 + 速度 + 角速度）。
     * 碰撞会解除双方的接地状态（被撞击的方块重新进入完整物理模拟）。
     *
     * @param a 方块 A 的物理状态
     * @param b 方块 B 的物理状态
     */
    public static void resolveEntityCollision(PhysicsState a, PhysicsState b)
    {
        double aMinX = a.x - HALF_EXTENT, aMaxX = a.x + HALF_EXTENT;
        double aMinY = a.y - HALF_EXTENT, aMaxY = a.y + HALF_EXTENT;
        double aMinZ = a.z - HALF_EXTENT, aMaxZ = a.z + HALF_EXTENT;

        double bMinX = b.x - HALF_EXTENT, bMaxX = b.x + HALF_EXTENT;
        double bMinY = b.y - HALF_EXTENT, bMaxY = b.y + HALF_EXTENT;
        double bMinZ = b.z - HALF_EXTENT, bMaxZ = b.z + HALF_EXTENT;

        double overlapX = Math.min(aMaxX, bMaxX) - Math.max(aMinX, bMinX);
        double overlapY = Math.min(aMaxY, bMaxY) - Math.max(aMinY, bMinY);
        double overlapZ = Math.min(aMaxZ, bMaxZ) - Math.max(aMinZ, bMinZ);

        if (overlapX <= 0 || overlapY <= 0 || overlapZ <= 0) return;

        // 质量倒数
        float invMassA = 1.0F / a.mass;
        float invMassB = 1.0F / b.mass;
        float invMassSum = invMassA + invMassB;
        if (invMassSum < 1e-6) return;

        float e = Math.min(a.restitution, b.restitution);
        float fric = (a.friction + b.friction) * 0.5F;

        // 碰撞会解除接地状态（被撞击的方块重新进入完整物理模拟）
        boolean wasGroundedA = a.grounded;
        boolean wasGroundedB = b.grounded;

        // Y 轴碰撞优先（防止穿透）
        if (overlapY <= overlapX && overlapY <= overlapZ)
        {
            // Y 轴碰撞
            double ny = (a.y < b.y) ? 1 : -1;  // 法向量从 a 指向 b

            a.y -= ny * overlapY * (invMassA / invMassSum);
            b.y += ny * overlapY * (invMassB / invMassSum);

            double relVel = (b.vy - a.vy) * ny;
            if (relVel < 0)
            {
                double j = -(1 + e) * relVel / invMassSum;
                a.vy -= j * ny * invMassA;
                b.vy += j * ny * invMassB;
            }
            // 切向摩擦
            a.vx *= (1 - fric * 0.5);
            a.vz *= (1 - fric * 0.5);
            b.vx *= (1 - fric * 0.5);
            b.vz *= (1 - fric * 0.5);
        }
        else if (overlapX <= overlapZ)
        {
            // X 轴碰撞
            double nx = (a.x < b.x) ? 1 : -1;

            a.x -= nx * overlapX * (invMassA / invMassSum);
            b.x += nx * overlapX * (invMassB / invMassSum);

            double relVel = (b.vx - a.vx) * nx;
            if (relVel < 0)
            {
                double j = -(1 + e) * relVel / invMassSum;
                a.vx -= j * nx * invMassA;
                b.vx += j * nx * invMassB;
            }
            a.vy *= (1 - fric * 0.5);
            a.vz *= (1 - fric * 0.5);
            b.vy *= (1 - fric * 0.5);
            b.vz *= (1 - fric * 0.5);

            // 水平碰撞解除接地
            a.grounded = false;
            b.grounded = false;
        }
        else
        {
            // Z 轴碰撞
            double nz = (a.z < b.z) ? 1 : -1;

            a.z -= nz * overlapZ * (invMassA / invMassSum);
            b.z += nz * overlapZ * (invMassB / invMassSum);

            double relVel = (b.vz - a.vz) * nz;
            if (relVel < 0)
            {
                double j = -(1 + e) * relVel / invMassSum;
                a.vz -= j * nz * invMassA;
                b.vz += j * nz * invMassB;
            }
            a.vx *= (1 - fric * 0.5);
            a.vy *= (1 - fric * 0.5);
            b.vx *= (1 - fric * 0.5);
            b.vy *= (1 - fric * 0.5);

            a.grounded = false;
            b.grounded = false;
        }

        // 碰撞时轻微衰减角速度（但不强制停止，让方块能滚动）
        a.angularVelocity.mul(0.9F);
        b.angularVelocity.mul(0.9F);
    }

    /**
     * 检查休眠条件（参考 Sable 的 IslandManager 休眠机制）
     *
     * 必须连续 N 帧低速 + 接地才休眠。
     * 休眠后精确贴合地面，防止悬浮。
     */
    private static void checkSleep(PhysicsState state, class_1937 world)
    {
        double speedSq = state.vx * state.vx + state.vy * state.vy + state.vz * state.vz;
        double angSpeedSq = state.angularVelocity.lengthSquared();

        boolean lowSpeed = speedSq < SLEEP_THRESHOLD * SLEEP_THRESHOLD
            && angSpeedSq < SLEEP_THRESHOLD * SLEEP_THRESHOLD;

        if (!lowSpeed || !state.grounded)
        {
            state.sleepCounter = 0;
            return;
        }

        state.sleepCounter++;
        if (state.sleepCounter >= SLEEP_DELAY_TICKS)
        {
            state.sleeping = true;
            state.vx = 0;
            state.vy = 0;
            state.vz = 0;
            state.angularVelocity.set(0, 0, 0);
            // 精确贴合地面
            double bottomY = state.y - HALF_EXTENT;
            int belowY = (int) Math.floor(bottomY - CONTACT_THRESHOLD);
            state.y = belowY + 1 + HALF_EXTENT;
        }
    }
}
