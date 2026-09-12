package com.example.bbsanimatedbreak;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.class_1540;
import net.minecraft.class_1937;
import net.minecraft.class_238;
import net.minecraft.class_243;

/**
 * 旋转下落方块管理器 —— 详细物理模拟
 *
 * 实现了一套接近真实的刚体物理：
 * 1. 三轴角速度 + 角阻尼（空气阻力对旋转的衰减）
 * 2. 弹跳物理：恢复系数（restitution）控制反弹能量，每次弹跳衰减
 * 3. 摩擦力：地面摩擦让水平速度逐渐降低
 * 4. 碰撞响应：碰撞时根据冲量调整线速度和角速度（模拟力臂效应）
 * 5. 实体间碰撞：使用 MC 内置的 getOtherEntities 做近邻查询（已优化）
 * 6. 睡眠机制：方块速度和角速度都很小时进入睡眠，停止物理计算以节省性能
 *
 * 旋转角度通过 DataTracker 同步到客户端（见 FallingBlockEntityDataMixin），
 * 保证客户端和服务端旋转完全一致。
 *
 * 命令标签 "bbs_splash_rotating" 标记实体需要物理旋转，
 * 命令标签会通过 NBT 自动同步到客户端。
 */
public class RotatingFallingBlockManager
{
    /** 标记实体需要物理旋转的命令标签 */
    public static final String ROTATION_TAG = "bbs_splash_rotating";

    /**
     * 物理状态 —— 每个飞溅方块的完整物理描述
     *
     * 所有角度单位：度（degree）
     * 所有角速度单位：度/tick
     */
    public static class PhysicsState
    {
        /**
         * 确定性随机源状态（xorshift64* 内部状态）
         *
         * 碰撞响应里的"随机力矩"必须可复现（同一回放两次播放结果一致），
         * 因此不能用 new Random()（纳秒时间播种），
         * 改用由实体 UUID 派生的固定种子。
         */
        public long rngSeed = 0x9E3779B97F4A7C15L;

        /** 确定性随机数 [0,1)（xorshift64*，零分配、无外部状态） */
        private float randUnit()
        {
            long x = this.rngSeed;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            this.rngSeed = x;

            return ((x >>> 40) & 0xFFFFFFL) / (float) 0x1000000;
        }

        /** 确定性随机力矩（居中：-0.5 ~ +0.5 倍幅值） */
        private float randTorque(float amplitude)
        {
            return (this.randUnit() - 0.5F) * amplitude;
        }

        // === 旋转状态 ===
        public float angularVelocityX;
        public float angularVelocityY;
        public float angularVelocityZ;
        public float rotationX;
        public float rotationY;
        public float rotationZ;

        // === 物理参数 ===
        /** 角阻尼（空气阻力对旋转的衰减，每 tick 乘以此值） */
        public float angularDamping;
        /** 恢复系数（弹跳能量保留比例，0=完全不弹，1=完全弹性） */
        public float restitution;
        /** 地面摩擦系数（每 tick 水平速度乘以此值） */
        public float friction;
        /** 方块质量（影响碰撞响应，默认 1.0） */
        public float mass;

        // === 状态追踪 ===
        /** 已落地次数（用于逐渐降低弹跳） */
        public int bounceCount;
        /** 是否已停止（落地稳定后停止物理计算） */
        public boolean stopped;
        /** 连续低速度 tick 数（用于判断是否进入睡眠） */
        public int lowSpeedTicks;
        /** 是否在地面（用于摩擦计算） */
        public boolean onGround;

        // === 挤压变形 ===
        /** 当前挤压变形强度（0.0=无变形，1.0=最大变形），每 tick 衰减 */
        public float squashAmount;
        /** 挤压方向（0=X方向，1=Z方向，2=垂直方向） */
        public int squashDirection;

        // === 归位旋转平滑过渡 ===
        /** 是否启用归位旋转平滑过渡（接近地面时减速旋转并归零角度） */
        public boolean smoothRotationStop;
        /** 旋转减速距离（在距离地面多远开始减速旋转） */
        public float rotationStopDistance;
        /** 归零动画持续时间（tick） */
        public int rotationResetDuration;
        /** 是否已经完成归零（变方块前不再进行旋转插值） */
        public boolean rotationSnapped;

        // === 碰撞控制 ===
        /** 暂停方块间碰撞模拟（true=不参与实体间碰撞，避免恢复时方块互相推开导致错位） */
        public boolean disableCollision;

        // === 路径运动标志 ===
        /** 是否是路径运动方块（true=跳过 mixin 的弹跳/摩擦/空气阻力等物理处理，只保留旋转） */
        public boolean pathMovement;

        public PhysicsState(float avx, float avy, float avz)
        {
            this.angularVelocityX = avx;
            this.angularVelocityY = avy;
            this.angularVelocityZ = avz;
            this.rotationX = 0;
            this.rotationY = 0;
            this.rotationZ = 0;

            this.angularDamping = 0.99F;    // 空气阻力，每 tick 衰减 1%（更慢衰减，旋转持续更久）
            this.restitution = 0.4F;         // 恢复系数：中等偏低弹跳（更真实）
            this.friction = 0.8F;            // 地面摩擦：每 tick 水平速度保留 80%
            this.mass = 1.0F;

            this.bounceCount = 0;
            this.stopped = false;
            this.lowSpeedTicks = 0;
            this.onGround = false;

            this.squashAmount = 0.0F;
            this.squashDirection = 0;

            this.smoothRotationStop = false;
            this.rotationStopDistance = 3.0F;
            this.rotationResetDuration = 40;
            this.rotationSnapped = false;

            this.disableCollision = false;
            this.pathMovement = false;
        }

        /**
         * 每 tick 更新旋转角度
         */
        public void tick()
        {
            if (this.stopped)
            {
                return;
            }

            // 累加旋转角度
            this.rotationX += this.angularVelocityX;
            this.rotationY += this.angularVelocityY;
            this.rotationZ += this.angularVelocityZ;

            // 规范化角度到 [0, 360) 避免浮点精度问题
            this.rotationX = normalizeAngle(this.rotationX);
            this.rotationY = normalizeAngle(this.rotationY);
            this.rotationZ = normalizeAngle(this.rotationZ);

            // 应用角阻尼（空气阻力让旋转逐渐减慢）
            // 在地面时阻尼更大（摩擦力）
            float damping = this.onGround ? this.angularDamping * 0.92F : this.angularDamping;
            this.angularVelocityX *= damping;
            this.angularVelocityY *= damping;
            this.angularVelocityZ *= damping;

            // 角速度过小时停止旋转（避免微小抖动）
            float totalAngularSpeed = Math.abs(this.angularVelocityX)
                                    + Math.abs(this.angularVelocityY)
                                    + Math.abs(this.angularVelocityZ);

            if (totalAngularSpeed < 0.08F)
            {
                this.lowSpeedTicks++;
                // 连续 20 tick（1 秒）低速度，进入睡眠
                if (this.lowSpeedTicks > 20 && this.onGround)
                {
                    this.angularVelocityX = 0;
                    this.angularVelocityY = 0;
                    this.angularVelocityZ = 0;
                    this.stopped = true;
                }
            }
            else
            {
                this.lowSpeedTicks = 0;
            }

            // 挤压变形衰减（每 tick 衰减 15%，约 6-7 tick 恢复）
            if (this.squashAmount > 0.001F)
            {
                this.squashAmount *= 0.85F;
                if (this.squashAmount < 0.01F)
                {
                    this.squashAmount = 0.0F;
                }
            }
        }

        /**
         * 垂直碰撞（落地）—— 弹跳 + 摩擦 + 角速度变化
         *
         * 真实物理：
         * 1. 碰撞吸收能量，角速度衰减
         * 2. 水平移动产生滚动旋转（像球滚动）
         * 3. 碰撞冲击产生随机力矩（不规则碰撞）
         *
         * @param impactSpeed 碰撞时的速度大小
         * @param horizontalSpeedX 水平 X 速度（用于滚动旋转计算）
         * @param horizontalSpeedZ 水平 Z 速度（用于滚动旋转计算）
         */
        public void onGroundCollision(float impactSpeed, float horizontalSpeedX, float horizontalSpeedZ)
        {
            this.bounceCount++;
            this.onGround = true;

            // === 落地挤压变形 ===
            // 根据落地速度设置挤压强度（速度越大变形越明显，最大 0.4）
            // 真实物理：物体落地时受冲击变形，垂直方向被压扁，水平方向膨胀（体积守恒）
            float squash = Math.min(impactSpeed * 0.15F, 0.4F);
            if (squash > this.squashAmount)
            {
                this.squashAmount = squash;
                // 根据水平速度方向决定挤压方向
                float absX = Math.abs(horizontalSpeedX);
                float absZ = Math.abs(horizontalSpeedZ);
                if (absX > absZ && absX > 0.1F)
                {
                    this.squashDirection = 0; // X 方向滚动，沿 X 挤压
                }
                else if (absZ > 0.1F)
                {
                    this.squashDirection = 1; // Z 方向滚动，沿 Z 挤压
                }
                else
                {
                    this.squashDirection = 2; // 垂直落地，垂直挤压
                }
            }

            // 碰撞时角速度衰减（碰撞吸收能量）
            float collisionFactor = Math.max(0.3F, 1.0F - impactSpeed * 0.06F);
            this.angularVelocityX *= collisionFactor;
            this.angularVelocityY *= collisionFactor;
            this.angularVelocityZ *= collisionFactor;

            // 水平移动产生滚动旋转（真实物理：滚动方向决定旋转轴）
            // 沿 X 方向滚动 → 绕 Z 轴旋转
            // 沿 Z 方向滚动 → 绕 X 轴旋转（负方向，因为滚动方向和旋转方向有右手定则关系）
            float rollFactor = 8.0F;
            this.angularVelocityZ += horizontalSpeedX * rollFactor;
            this.angularVelocityX -= horizontalSpeedZ * rollFactor;

            // 碰撞冲击产生随机力矩（不规则碰撞的真实表现）
            if (impactSpeed > 0.4F)
            {
                float torque = impactSpeed * 2.5F;
                this.angularVelocityX += this.randTorque(torque);
                this.angularVelocityZ += this.randTorque(torque);
                this.angularVelocityY += this.randTorque(torque * 0.2F);
            }
        }

        /**
         * 水平碰撞（撞墙）—— 反弹 + 角速度变化
         *
         * @param impactSpeed 碰撞时的速度大小
         * @param normalX 法线 X 分量
         * @param normalZ 法线 Z 分量
         */
        public void onWallCollision(float impactSpeed, float normalX, float normalZ)
        {
            // 碰撞衰减
            float collisionFactor = Math.max(0.3F, 1.0F - impactSpeed * 0.06F);
            this.angularVelocityX *= collisionFactor;
            this.angularVelocityY *= collisionFactor;
            this.angularVelocityZ *= collisionFactor;

            // 撞墙产生旋转（力臂效应）
            if (impactSpeed > 0.2F)
            {
                float torque = impactSpeed * 4.0F;
                // 撞墙主要产生 Y 轴旋转（偏航）和少量 X/Z 旋转
                this.angularVelocityY += this.randTorque(torque);
                this.angularVelocityX += this.randTorque(torque * 0.5F);
                this.angularVelocityZ += this.randTorque(torque * 0.5F);
            }
        }

        /**
         * 实体间碰撞 —— 两个方块相撞时的响应
         *
         * @param impactSpeed 碰撞速度
         * @param normalX 碰撞法线 X
         * @param normalY 碰撞法线 Y
         * @param normalZ 碰撞法线 Z
         */
        public void onEntityCollision(float impactSpeed, float normalX, float normalY, float normalZ)
        {
            // 实体碰撞对旋转的影响较小
            float collisionFactor = Math.max(0.5F, 1.0F - impactSpeed * 0.04F);
            this.angularVelocityX *= collisionFactor;
            this.angularVelocityY *= collisionFactor;
            this.angularVelocityZ *= collisionFactor;

            if (impactSpeed > 0.15F)
            {
                float torque = impactSpeed * 2.5F;
                this.angularVelocityX += this.randTorque(torque);
                this.angularVelocityY += this.randTorque(torque);
                this.angularVelocityZ += this.randTorque(torque);
            }
        }
    }

    /**
     * 规范化角度到 [0, 360)
     */
    private static float normalizeAngle(float angle)
    {
        angle = angle % 360F;
        if (angle < 0)
        {
            angle += 360F;
        }
        return angle;
    }

    // === 实体物理状态存储 ===
    private static final ConcurrentHashMap<UUID, PhysicsState> physicsStates = new ConcurrentHashMap<>();

    /**
     * 标记一个下落方块为需要物理旋转
     * 给它随机的三轴角速度，模拟物理旋转
     *
     * @param falling 下落方块实体
     */
    public static void markRotating(class_1540 falling)
    {
        if (falling == null || falling.method_5667() == null)
        {
            return;
        }

        // 用 DataTracker 设置旋转标志（自动同步到客户端）
        // 若写入失败，isRotating() 会恒为 false → mixin 直接 return，
        // 永远走不到 removePhysics → 下面 put 进去的状态将永久泄漏。
        // 因此写入失败时直接放弃标记。
        try
        {
            falling.method_5841().method_12778(FallingBlockRotationData.IS_ROTATING, true);
        }
        catch (Exception e)
        {
            return;
        }

        // === 真实物理旋转：根据线速度计算角速度 ===
        // 真实抛射物的旋转特点：
        // 1. 旋转轴倾向于垂直于运动方向（翻滚运动）
        // 2. 角速度大小和线速度成正比（速度越快旋转越快）
        // 3. 有少量随机性（方块形状不规则）

        class_243 vel = falling.method_18798();
        double speed = vel.method_1033();

        // 确定性种子：只依赖实体 UUID，不用 System.nanoTime()
        // （否则同一回放两次播放的初始角速度不同，轨迹无法复现）
        Random rng = new Random(falling.method_5667().getMostSignificantBits()
                              ^ falling.method_5667().getLeastSignificantBits());

        float avx, avy, avz;

        if (speed > 0.15)
        {
            // 速度方向（归一化）
            double vx = vel.field_1352 / speed;
            double vy = vel.field_1351 / speed;
            double vz = vel.field_1350 / speed;

            // 旋转轴 = 速度方向 × 重力方向(0,1,0) 的叉积
            // 叉积结果垂直于速度方向，符合翻滚运动的物理特征
            // |i  j  k |
            // |vx vy vz|
            // |0  1  0 |
            // = i*(vy*0 - vz*1) - j*(vx*0 - vz*0) + k*(vx*1 - vy*0)
            // = (-vz, 0, vx)
            double axisX = -vz;
            double axisY = 0;
            double axisZ = vx;
            double axisLen = Math.sqrt(axisX * axisX + axisZ * axisZ);

            // 角速度大小：和线速度成正比，限制在合理范围
            // 真实物理：ω ≈ v / r，这里 r≈0.5（方块半边长），系数调优
            // 最大 15 度/tick（约 300 度/秒），看起来自然
            float angularSpeed = (float) Math.min(speed * 10.0, 15.0);

            // 加入随机扰动模拟不规则形状（0.7~1.3 倍）
            angularSpeed *= 0.7F + rng.nextFloat() * 0.6F;

            if (axisLen > 1e-6)
            {
                // 水平运动：绕水平轴翻滚
                axisX /= axisLen;
                axisZ /= axisLen;

                avx = (float) (axisX * angularSpeed);
                avy = (rng.nextFloat() - 0.5F) * angularSpeed * 0.25F; // Y轴旋转较小
                avz = (float) (axisZ * angularSpeed);
            }
            else
            {
                // 速度垂直（纯上抛/下落）：绕随机水平轴旋转
                double angle = rng.nextDouble() * Math.PI * 2;
                avx = (float) (Math.cos(angle) * angularSpeed);
                avy = (rng.nextFloat() - 0.5F) * angularSpeed * 0.25F;
                avz = (float) (Math.sin(angle) * angularSpeed);
            }

            // 加入少量三轴随机扰动（不规则形状的真实表现）
            avx += (rng.nextFloat() - 0.5F) * 3F;
            avy += (rng.nextFloat() - 0.5F) * 3F;
            avz += (rng.nextFloat() - 0.5F) * 3F;
        }
        else
        {
            // 速度很小：小角速度随机旋转
            avx = (rng.nextFloat() - 0.5F) * 8F;
            avy = (rng.nextFloat() - 0.5F) * 8F;
            avz = (rng.nextFloat() - 0.5F) * 8F;
        }

        PhysicsState state = new PhysicsState(avx, avy, avz);
        // 非零确定性种子（xorshift 在 0 处是不动点，会恒定输出 0）
        state.rngSeed = (falling.method_5667().getMostSignificantBits()
                       ^ falling.method_5667().getLeastSignificantBits()) | 1L;
        physicsStates.put(falling.method_5667(), state);
    }

    /**
     * 标记一个下落方块为需要物理旋转，并设置归位旋转平滑过渡参数
     *
     * @param falling 下落方块实体
     * @param smoothRotationStop 是否启用归位旋转平滑过渡
     * @param rotationStopDistance 旋转减速距离
     * @param rotationResetDuration 归零动画持续时间（tick）
     */
    public static void markRotating(class_1540 falling,
                                     boolean smoothRotationStop, double rotationStopDistance,
                                     int rotationResetDuration)
    {
        markRotating(falling, smoothRotationStop, rotationStopDistance, rotationResetDuration, false);
    }

    /**
     * 标记一个下落方块为需要物理旋转，并设置归位旋转平滑过渡参数 + 碰撞控制
     *
     * @param falling 下落方块实体
     * @param smoothRotationStop 是否启用归位旋转平滑过渡
     * @param rotationStopDistance 旋转减速距离
     * @param rotationResetDuration 归零动画持续时间（tick）
     * @param disableCollision 是否暂停方块间碰撞模拟（true=不参与实体间碰撞，
     *                         用于反向飞溅恢复阶段避免方块互相推开导致错位）
     */
    public static void markRotating(class_1540 falling,
                                     boolean smoothRotationStop, double rotationStopDistance,
                                     int rotationResetDuration, boolean disableCollision)
    {
        if (falling == null || falling.method_5667() == null)
        {
            return;
        }

        markRotating(falling);

        PhysicsState state = physicsStates.get(falling.method_5667());
        if (state != null)
        {
            state.smoothRotationStop = smoothRotationStop;
            state.rotationStopDistance = (float) rotationStopDistance;
            state.rotationResetDuration = rotationResetDuration;
            state.disableCollision = disableCollision;
        }
    }

    /**
     * 获取实体的物理状态
     */
    public static PhysicsState getPhysicsState(UUID uuid)
    {
        if (uuid == null)
        {
            return null;
        }
        return physicsStates.get(uuid);
    }

    /**
     * 检查实体是否需要物理旋转
     * 从 DataTracker 读取 IS_ROTATING 标志（自动从服务端同步到客户端）
     */
    public static boolean isRotating(class_1540 falling)
    {
        if (falling == null)
        {
            return false;
        }

        try
        {
            return falling.method_5841().method_12789(FallingBlockRotationData.IS_ROTATING);
        }
        catch (Exception e)
        {
            // DataTracker 可能未初始化（比如实体刚创建还没注册）
            return false;
        }
    }

    /**
     * 移除实体的物理状态（实体消失时调用）
     */
    public static void removePhysics(UUID uuid)
    {
        if (uuid != null)
        {
            physicsStates.remove(uuid);
        }
    }

    /**
     * 清空所有物理状态（回放结束时调用）
     */
    public static void clearAll()
    {
        physicsStates.clear();
    }

    /**
     * 根据实体 UUID 生成确定性的角速度（兜底方案，DataTracker 不可用时用）
     *
     * @param uuid 实体 UUID
     * @return 三轴角速度数组 [avx, avy, avz]（度/tick）
     */
    public static float[] getAngularVelocity(UUID uuid)
    {
        if (uuid == null)
        {
            return new float[] {0, 0, 0};
        }

        Random rng = new Random(uuid.getMostSignificantBits());
        float avx = (rng.nextFloat() - 0.5F) * 50F;
        float avy = (rng.nextFloat() - 0.5F) * 50F;
        float avz = (rng.nextFloat() - 0.5F) * 50F;

        return new float[] {avx, avy, avz};
    }

    // ============================
    //  实体间碰撞检测
    // ============================

    /**
     * 查询附近的旋转方块（用于碰撞检测）
     * 使用 MC 内置的 getOtherEntities 方法，已由 MC 引擎优化
     *
     * @param self 当前实体
     * @param world 世界
     * @return 附近的旋转方块列表（不包含自身）
     */
    public static List<net.minecraft.class_1297> getNearbyEntities(class_1540 self, class_1937 world)
    {
        // 用扩展的 AABB 查询附近实体（扩展 1 格以覆盖碰撞范围）
        class_238 searchBox = self.method_5829().method_1014(1.0);
        return world.method_8335(self, searchBox);
    }

    /**
     * 检测两个下落方块是否碰撞（AABB 碰撞检测）
     *
     * @param a 方块 A
     * @param b 方块 B
     * @return 是否碰撞
     */
    public static boolean checkAABBCollision(class_1540 a, class_1540 b)
    {
        class_238 boxA = a.method_5829();
        class_238 boxB = b.method_5829();

        return boxA.method_994(boxB);
    }

    /**
     * 计算两个碰撞方块之间的分离向量
     * 返回 A 应该移动的方向和距离
     *
     * @param a 方块 A
     * @param b 方块 B
     * @return 分离向量 [dx, dy, dz]，A 需要加上此向量来避免重叠
     */
    public static double[] calculateSeparation(class_1540 a, class_1540 b)
    {
        class_238 boxA = a.method_5829();
        class_238 boxB = b.method_5829();

        // 计算各轴的重叠量
        double overlapX = Math.min(boxA.field_1320 - boxB.field_1323, boxB.field_1320 - boxA.field_1323);
        double overlapY = Math.min(boxA.field_1325 - boxB.field_1322, boxB.field_1325 - boxA.field_1322);
        double overlapZ = Math.min(boxA.field_1324 - boxB.field_1321, boxB.field_1324 - boxA.field_1321);

        // 找到最小重叠轴，沿该轴分离
        if (overlapX < overlapY && overlapX < overlapZ)
        {
            // X 轴重叠最小
            double dirX = a.method_23317() - b.method_23317();
            if (dirX == 0) dirX = 1;
            dirX = Math.signum(dirX);
            return new double[] {dirX * overlapX * 0.5, 0, 0};
        }
        else if (overlapY < overlapZ)
        {
            // Y 轴重叠最小
            double dirY = a.method_23318() - b.method_23318();
            if (dirY == 0) dirY = 1;
            dirY = Math.signum(dirY);
            return new double[] {0, dirY * overlapY * 0.5, 0};
        }
        else
        {
            // Z 轴重叠最小
            double dirZ = a.method_23321() - b.method_23321();
            if (dirZ == 0) dirZ = 1;
            dirZ = Math.signum(dirZ);
            return new double[] {0, 0, dirZ * overlapZ * 0.5};
        }
    }

    /**
     * 处理两个方块的碰撞响应
     * 修改两者的速度和角速度
     *
     * @param a 方块 A
     * @param b 方块 B
     * @param stateA A 的物理状态
     * @param stateB B 的物理状态
     */
    public static void resolveCollision(class_1540 a, class_1540 b,
                                        PhysicsState stateA, PhysicsState stateB)
    {
        // 计算分离向量
        double[] sep = calculateSeparation(a, b);

        // 分离两个方块（各移动一半）
        a.method_5814(a.method_23317() + sep[0], a.method_23318() + sep[1], a.method_23321() + sep[2]);
        b.method_5814(b.method_23317() - sep[0], b.method_23318() - sep[1], b.method_23321() - sep[2]);

        // 计算碰撞法线（从 B 指向 A）
        double nx = sep[0], ny = sep[1], nz = sep[2];
        double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nLen < 1e-6) return;
        nx /= nLen; ny /= nLen; nz /= nLen;

        // 计算相对速度
        class_243 velA = a.method_18798();
        class_243 velB = b.method_18798();
        double rvx = velA.field_1352 - velB.field_1352;
        double rvy = velA.field_1351 - velB.field_1351;
        double rvz = velA.field_1350 - velB.field_1350;

        // 沿法线的相对速度
        double velAlongNormal = rvx * nx + rvy * ny + rvz * nz;

        // 如果方块正在分离，不处理
        if (velAlongNormal > 0) return;

        // 计算冲量（弹性碰撞）
        float e = 0.4F; // 恢复系数
        float invMassA = 1.0F / stateA.mass;
        float invMassB = 1.0F / stateB.mass;

        double j = -(1 + e) * velAlongNormal / (invMassA + invMassB);

        // 应用冲量到速度
        double impulseX = j * nx;
        double impulseY = j * ny;
        double impulseZ = j * nz;

        a.method_18800(
            velA.field_1352 + impulseX * invMassA,
            velA.field_1351 + impulseY * invMassA,
            velA.field_1350 + impulseZ * invMassA
        );
        a.field_6037 = true;

        b.method_18800(
            velB.field_1352 - impulseX * invMassB,
            velB.field_1351 - impulseY * invMassB,
            velB.field_1350 - impulseZ * invMassB
        );
        b.field_6037 = true;

        // 碰撞对旋转的影响
        float impactSpeed = (float) Math.abs(velAlongNormal);
        stateA.onEntityCollision(impactSpeed, (float) nx, (float) ny, (float) nz);
        stateB.onEntityCollision(impactSpeed, (float) -nx, (float) -ny, (float) -nz);
    }
}
