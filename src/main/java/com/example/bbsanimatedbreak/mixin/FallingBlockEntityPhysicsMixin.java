package com.example.bbsanimatedbreak.mixin;

import com.example.bbsanimatedbreak.BlockSplashRecoveryManager;
import com.example.bbsanimatedbreak.FallingBlockRotationData;
import com.example.bbsanimatedbreak.RotatingFallingBlockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;
import net.minecraft.class_1297;
import net.minecraft.class_1540;
import net.minecraft.class_1937;
import net.minecraft.class_2338;
import net.minecraft.class_238;
import net.minecraft.class_243;
import net.minecraft.class_265;
import net.minecraft.class_2680;

/**
 * FallingBlockEntity 服务端物理 Mixin —— 详细物理模拟
 *
 * 在 tick() 末尾注入，为被标记的飞溅方块添加完整的刚体物理：
 *
 * 1. 弹跳物理（落地反弹，恢复系数衰减）
 * 2. 地面摩擦（水平速度逐渐降低）
 * 3. 旋转物理（三轴角速度更新，碰撞产生力矩）
 * 4. 增强空气阻力（飞行时逐渐减速）
 * 5. 实体间碰撞（MC 内置 getOtherEntities + AABB 碰撞 + 冲量响应）
 * 6. 睡眠机制（稳定后停止物理计算）
 * 7. 旋转角度同步到 DataTracker（客户端渲染读取）
 * 8. 超时清理（防止实体永久存在）
 *
 * === 非实体化模式 ===
 * 当 NO_SOLIDIFY=true 时：
 * - BbsEntityMixin @Inject isOnGround()：返回 false，
 *   使原版跳过"落地变方块"逻辑（setBlockState + discard），
 *   方块保持 FallingBlockEntity 形态继续物理模拟滚动
 * - HEAD 注入：重置 timeFalling = 0，防止原版 600 tick 超时移除逻辑
 * - RETURN 注入：跳过 500 tick 超时，由 BlockSplashAnimationScheduler 控制
 *
 * 只对带有 "bbs_splash_rotating" 命令标签的实体生效，
 * 不影响原版下落方块行为。
 */
@Mixin(class_1540.class)
public abstract class FallingBlockEntityPhysicsMixin
{
    /** FallingBlockEntity.timeFalling 字段（public int） */
    @Shadow public int timeFalling;

    /**
     * HEAD 注入：非实体化方块重置 timeFalling，防止原版 600 tick 超时移除
     *
     * 注意：原版 FallingBlockEntity.tick() 的"落地变方块"条件是
     *   isOnGround() || concreteInWater
     * （不检查 timeFalling！），所以重置 timeFalling 不能阻止变方块。
     * 阻止变方块由 BbsEntityMixin 的 @Inject isOnGround() 完成。
     *
     * 这里重置 timeFalling 的作用：防止原版"未落地且 timeFalling > 600"时
     * 触发的 dropItem + discard 超时移除逻辑（让方块能存活到调度器指定时长）。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void resetTimeFallingForNoSolidify(CallbackInfo ci)
    {
        class_1540 self = (class_1540) (Object) this;
        class_1937 world = self.method_37908();

        /* 只在服务端处理 */
        if (world == null || world.field_9236) return;

        try
        {
            if (self.method_5841().method_12789(FallingBlockRotationData.NO_SOLIDIFY))
            {
                this.timeFalling = 0;
            }
        }
        catch (Exception e)
        {
            /* 忽略 */
        }
    }

    /**
     * 在 tick() 末尾注入物理逻辑
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void onTickEnd(CallbackInfo ci)
    {
        class_1540 self = (class_1540) (Object) this;
        UUID uuid = self.method_5667();

        // 只处理被标记的飞溅方块
        if (!RotatingFallingBlockManager.isRotating(self))
        {
            return;
        }

        RotatingFallingBlockManager.PhysicsState state = RotatingFallingBlockManager.getPhysicsState(uuid);

        if (state == null)
        {
            return;
        }

        // 读取非实体化标志（提前读取，isRemoved 分支也需要用）
        boolean noSolidify = false;
        try
        {
            noSolidify = self.method_5841().method_12789(FallingBlockRotationData.NO_SOLIDIFY);
        }
        catch (Exception e) { /* 忽略 */ }

        // 实体被移除时清理状态，并记录落地位置（用于退出时恢复）
        if (self.method_31481())
        {
            // === 记录落地后变成的方块 ===
            // FallingBlockEntity 落地后原版会调用 setBlockState 放置方块，然后 discard 实体
            // 我们记录实体最后所在位置的方块状态（落地后变成的方块），
            // 这样退出时可以移除这些方块，恢复原状
            //
            // 非实体化方块（noSolidify=true）不会落地变方块，由调度器缩小消失，
            // 不应该记录落地方块（否则恢复时会误删方块下方的地面）
            if (!noSolidify)
            {
                class_1937 world2 = self.method_37908();
                if (world2 != null)
                {
                    class_2338 landedPos = self.method_24515();
                    class_2680 landedState = world2.method_8320(landedPos);
                    // 记录落地位置的方块状态（这个方块是飞溅实体落地变成的）
                    // 退出恢复时，这个位置会被设为空气（因为原始方块在原区域已被记录为空气）
                    BlockSplashRecoveryManager.recordLandedBlock(world2, landedPos, landedState);
                }
            }

            RotatingFallingBlockManager.removePhysics(uuid);
            return;
        }

        // 通过 Accessor 读取 private 字段 onGround
        boolean onGround = ((EntityAccessor) self).isOnGround();

        // 更新地面状态
        state.onGround = onGround;

        // 如果已经停止，跳过大部分物理计算
        if (state.stopped)
        {
            // 同步最终旋转角度到 DataTracker
            this.syncRotationToDataTracker(self, state);

            // 清理已停止且超时的实体
            // 非实体化方块的清理由 BlockSplashAnimationScheduler 控制，跳过这里
            if (!noSolidify && self.field_6012 > 300)
            {
                self.method_31472();
                RotatingFallingBlockManager.removePhysics(uuid);
            }
            return;
        }

        // === 路径运动方块：跳过弹跳/摩擦/空气阻力/碰撞，只保留旋转同步 ===
        // 路径运动由 BlockPathScheduler 用 setPosition 直接控制位置，
        // mixin 的物理处理会干扰位置导致抖动抽搐。
        if (state.pathMovement)
        {
            // 只更新旋转角度和同步到 DataTracker
            state.tick();
            this.syncRotationToDataTracker(self, state);
            return;
        }

        class_1937 world = self.method_37908();

        // === 1. 更新旋转角度 ===
        state.tick();

        // === 2. 处理碰撞物理 ===
        class_243 velocity = self.method_18798();
        float speed = (float) velocity.method_1033();
        float horizontalSpeed = (float) Math.sqrt(velocity.field_1352 * velocity.field_1352 + velocity.field_1350 * velocity.field_1350);

        // 垂直碰撞（落地）—— 弹跳 + 摩擦
        // 放宽条件：verticalCollision 表示本帧撞到了下方方块，
        // velocity.y <= 0.1 涵盖"下落撞地"和"原版截断后接近静止"两种情况，
        // 避免高速下落时因 velocity.y 仍为负而错过反弹导致卡地
        if (self.field_5992 && onGround && velocity.field_1351 <= 0.1)
        {
            this.handleGroundBounce(self, state, velocity, speed, horizontalSpeed);
        }
        else if (!onGround)
        {
            state.onGround = false;
        }

        // 水平碰撞（撞墙）—— 反弹
        if (self.field_5976 && speed > 0.1F)
        {
            this.handleWallBounce(self, state, velocity, speed);
        }

        // === 2.5 卡住检测与推出 ===
        // 检测实体是否嵌入方块，若卡住则沿最小重叠轴推出
        // 这能修复"方块卡墙里/卡地里面"的问题
        if (world != null)
        {
            this.resolveBlockStuck(self, world);
        }

        // === 3. 地面摩擦 ===
        if (onGround && horizontalSpeed > 0.01F)
        {
            // 应用地面摩擦
            double frictionFactor = state.friction;
            self.method_18800(
                velocity.field_1352 * frictionFactor,
                velocity.field_1351,
                velocity.field_1350 * frictionFactor
            );
            self.field_6037 = true;
        }

        // === 4. 增强的空气阻力 ===
        if (!onGround && speed > 0.05F)
        {
            // 额外的空气阻力（原版已有 0.98，这里再加一点让飞行更真实）
            double drag = 0.996;
            self.method_18800(
                velocity.field_1352 * drag,
                velocity.field_1351 * 0.999,
                velocity.field_1350 * drag
            );
            self.field_6037 = true;
        }

        // === 5. 实体间碰撞检测 ===
        if (world != null && !state.stopped)
        {
            this.handleEntityCollisions(self, state, world);
        }

        // === 6. 同步旋转角度到 DataTracker ===
        // 如果启用归位旋转平滑过渡，在接近地面时减速旋转并归零角度
        // 非实体化方块不归零旋转（保持物理旋转，到时间后整体缩小消失）
        if (state.smoothRotationStop && !state.rotationSnapped && !noSolidify)
        {
            this.smoothStopRotationNearGround(self, state, world);
        }

        this.syncRotationToDataTracker(self, state);

        // === 7. 超时清理 ===
        // 非实体化方块的清理由 BlockSplashAnimationScheduler 控制，跳过 500 tick 超时
        if (!noSolidify && self.field_6012 > 500)
        {
            self.method_31472();
            RotatingFallingBlockManager.removePhysics(uuid);
        }
    }

    /**
     * 处理落地弹跳
     */
    private void handleGroundBounce(class_1540 self,
                                     RotatingFallingBlockManager.PhysicsState state,
                                     class_243 velocity, float speed, float horizontalSpeed)
    {
        state.bounceCount++;

        // 计算弹跳恢复系数：每次弹跳衰减
        float bounceFactor = Math.max(0.1F, state.restitution - state.bounceCount * 0.08F);

        // 如果速度足够大且弹跳次数未超限，反弹
        if (speed > 0.15F && state.bounceCount < 5)
        {
            // 反弹速度（Y 轴向上）
            // 用 max(|velocity.y|, speed*0.4) 确保即使原版把 Y 速度截断到 0 也能弹起
            double bounceY = Math.max(Math.abs(velocity.field_1351) * bounceFactor, speed * bounceFactor * 0.4);

            // 水平速度保留一部分（弹跳时摩擦）
            double bounceX = velocity.field_1352 * 0.7;
            double bounceZ = velocity.field_1350 * 0.7;

            self.method_18800(bounceX, bounceY, bounceZ);
            self.field_6037 = true;

            // 碰撞影响旋转（传入 X 和 Z 方向的水平速度用于滚动计算）
            state.onGroundCollision(speed, (float) velocity.field_1352, (float) velocity.field_1350);
        }
        else
        {
            // 速度太小或弹跳次数过多，停止弹跳
            // 让方块滑行（大幅降低速度）
            // 关键修复：Y 速度强制设为 0（不能保留原 velocity.y，可能是负值导致继续下钻卡地）
            self.method_18800(velocity.field_1352 * 0.3, 0, velocity.field_1350 * 0.3);
            self.field_6037 = true;

            // 滑行时仍有滚动旋转（水平速度产生滚动）
            state.onGroundCollision(speed * 0.5F, (float) velocity.field_1352, (float) velocity.field_1350);

            // 大幅降低角速度
            state.angularVelocityX *= 0.5F;
            state.angularVelocityY *= 0.5F;
            state.angularVelocityZ *= 0.5F;
        }
    }

    /**
     * 处理撞墙反弹
     */
    private void handleWallBounce(class_1540 self,
                                   RotatingFallingBlockManager.PhysicsState state,
                                   class_243 velocity, float speed)
    {
        // 水平反弹（反向 * 恢复系数）
        float wallBounce = Math.max(0.3F, state.restitution * 0.8F);
        double bounceX = -velocity.field_1352 * wallBounce;
        double bounceZ = -velocity.field_1350 * wallBounce;

        // 关键修复：撞墙后强制清除嵌入墙体的水平速度分量
        // 如果 velocity 某方向很大但反弹后该方向仍有同向分量，说明墙体在那个方向
        // 此时直接把该方向速度设为 0，防止下一帧继续往墙里钻
        if (velocity.field_1352 * bounceX > 0) // 同号说明反弹没改变方向（不应发生，但保险）
        {
            bounceX = 0;
        }
        if (velocity.field_1350 * bounceZ > 0)
        {
            bounceZ = 0;
        }

        self.method_18800(bounceX, velocity.field_1351 * 0.85, bounceZ);
        self.field_6037 = true;

        // 计算法线方向（反弹方向）
        float normalX = (float) (bounceX / (Math.abs(bounceX) + Math.abs(bounceZ) + 1e-6));
        float normalZ = (float) (bounceZ / (Math.abs(bounceX) + Math.abs(bounceZ) + 1e-6));

        // 碰撞影响旋转
        state.onWallCollision(speed, normalX, normalZ);
    }

    /**
     * 卡住检测与推出 —— 修复"方块卡墙里/卡地里面"的核心
     *
     * 检测实体 AABB 是否与任何方块的碰撞形状相交，
     * 若相交则计算三轴最小重叠量，沿该轴把实体推出方块。
     *
     * 这是对原版 move() 碰撞处理的补充：
     * - 原版 move() 在碰撞时会截断位移但可能留 0.001~0.1 格的嵌入
     * - 重力会让方块持续往下钻，每帧嵌入一点点
     * - 撞墙后反弹速度不够时实体会停在墙边并持续嵌入
     *
     * 实现：
     * 1. 遍历实体 AABB 覆盖的所有方块
     * 2. 取每个方块的碰撞形状 VoxelShape
     * 3. 与实体 AABB 求交，若有交集则计算三轴重叠量
     * 4. 取所有方块中三轴最小重叠量，沿该轴推出
     * 5. 若 Y 轴向下卡住（脚下有方块），强制把 Y 速度设为 0 防止继续下钻
     */
    private void resolveBlockStuck(class_1540 self, class_1937 world)
    {
        class_238 entityBox = self.method_5829();

        // 遍历实体覆盖的所有方块（BlockPos.stream 返回 AABB 覆盖的所有整数坐标）
        // 缩小一点点避免边缘误判（与原版 isInsideWall 类似的做法）
        class_238 checkBox = entityBox.method_1011(0.001);

        double minOverlapX = Double.MAX_VALUE;
        double minOverlapY = Double.MAX_VALUE;
        double minOverlapZ = Double.MAX_VALUE;
        boolean stuck = false;

        for (class_2338 pos : class_2338.method_10097(
            class_2338.method_49637(checkBox.field_1323, checkBox.field_1322, checkBox.field_1321),
            class_2338.method_49637(checkBox.field_1320, checkBox.field_1325, checkBox.field_1324)))
        {
            class_2680 state = world.method_8320(pos);
            if (state.method_26215()) continue;

            class_265 shape = state.method_26220(world, pos);
            if (shape.method_1110()) continue;

            // 把 VoxelShape 偏移到世界坐标
            for (class_238 blockBox : shape.method_1090())
            {
                class_238 worldBox = blockBox.method_989(pos.method_10263(), pos.method_10264(), pos.method_10260());

                // 检查是否相交
                if (!entityBox.method_994(worldBox)) continue;

                // 计算三轴重叠量
                double overlapX = Math.min(entityBox.field_1320, worldBox.field_1320) - Math.max(entityBox.field_1323, worldBox.field_1323);
                double overlapY = Math.min(entityBox.field_1325, worldBox.field_1325) - Math.max(entityBox.field_1322, worldBox.field_1322);
                double overlapZ = Math.min(entityBox.field_1324, worldBox.field_1324) - Math.max(entityBox.field_1321, worldBox.field_1321);

                if (overlapX <= 0 || overlapY <= 0 || overlapZ <= 0) continue;

                stuck = true;
                if (overlapX < minOverlapX) minOverlapX = overlapX;
                if (overlapY < minOverlapY) minOverlapY = overlapY;
                if (overlapZ < minOverlapZ) minOverlapZ = overlapZ;
            }
        }

        if (!stuck) return;

        // 找出最小重叠轴，沿该轴推出
        // 推出方向：选能让实体回到"碰撞前位置"的方向
        // 对于 Y 轴，优先向上推（避免卡地）；对于 X/Z 轴，根据速度反方向推
        double pushX = 0, pushY = 0, pushZ = 0;

        // Y 轴：若 Y 重叠最小，向上推（方块卡地面的最常见情况）
        if (minOverlapY <= minOverlapX && minOverlapY <= minOverlapZ)
        {
            pushY = minOverlapY + 0.01;
            // 同时清除向下的 Y 速度，防止重力继续把方块往地下推
            class_243 vel = self.method_18798();
            if (vel.field_1351 < 0)
            {
                self.method_18800(vel.field_1352, 0, vel.field_1350);
                self.field_6037 = true;
            }
        }
        // X 轴：根据速度反方向推
        else if (minOverlapX <= minOverlapZ)
        {
            double velX = self.method_18798().field_1352;
            pushX = (velX >= 0) ? minOverlapX + 0.01 : -(minOverlapX + 0.01);
            // 清除嵌入方向的水平速度
            class_243 vel = self.method_18798();
            self.method_18800(0, vel.field_1351, vel.field_1350);
            self.field_6037 = true;
        }
        // Z 轴
        else
        {
            double velZ = self.method_18798().field_1350;
            pushZ = (velZ >= 0) ? minOverlapZ + 0.01 : -(minOverlapZ + 0.01);
            class_243 vel = self.method_18798();
            self.method_18800(vel.field_1352, vel.field_1351, 0);
            self.field_6037 = true;
        }

        // 应用推出位移
        self.method_5814(self.method_23317() + pushX, self.method_23318() + pushY, self.method_23321() + pushZ);
    }

    /**
     * 处理实体间碰撞
     * 使用 MC 内置的 getOtherEntities 查询附近实体，做 AABB 碰撞检测和冲量响应
     */
    private void handleEntityCollisions(class_1540 self,
                                         RotatingFallingBlockManager.PhysicsState state,
                                         class_1937 world)
    {
        // === 暂停碰撞：反向飞溅恢复中 ===
        // 如果该实体标记了 disableCollision（恢复中），跳过实体间碰撞处理，
        // 防止多个方块飞回相近原位时互相推开导致错位。
        // 任务结束变方块时状态会被清理，所以不会影响后续物理行为。
        if (state.disableCollision)
        {
            return;
        }

        // 查询附近的实体（MC 引擎已优化，使用区域缓存）
        List<class_1297> nearby = RotatingFallingBlockManager.getNearbyEntities(self, world);

        if (nearby.isEmpty())
        {
            return;
        }

        // 对每个附近的实体做碰撞检测
        for (class_1297 other : nearby)
        {
            // 只处理下落方块
            if (!(other instanceof class_1540))
            {
                continue;
            }

            class_1540 otherFalling = (class_1540) other;

            // 跳过非旋转方块
            if (!RotatingFallingBlockManager.isRotating(otherFalling))
            {
                continue;
            }

            // 跳过已移除的
            if (otherFalling.method_31481())
            {
                continue;
            }

            // AABB 碰撞检测
            if (!RotatingFallingBlockManager.checkAABBCollision(self, otherFalling))
            {
                continue;
            }

            // 获取对方的物理状态
            RotatingFallingBlockManager.PhysicsState otherState =
                RotatingFallingBlockManager.getPhysicsState(otherFalling.method_5667());

            if (otherState == null)
            {
                continue;
            }

            // 跳过两个都已停止的
            if (state.stopped && otherState.stopped)
            {
                continue;
            }

            // 如果对方在反向飞溅恢复中（disableCollision），也跳过碰撞，
            // 避免单向推开导致错位
            if (otherState.disableCollision)
            {
                continue;
            }

            // 解决碰撞（分离 + 冲量响应）
            RotatingFallingBlockManager.resolveCollision(self, otherFalling, state, otherState);
        }
    }

    /**
     * 接近地面时平滑停止旋转，避免落地变方块时角度闪现
     *
     * 关键修复：
     * 1. 归零到0度（而不是90度倍数），因为方块放置时是0度，避免有方向方块闪现
     * 2. 用 rotationResetDuration 控制插值速度（每tick插值 1/duration），确保 duration tick 后完全归零
     * 3. 增大强制归零距离（1.5格），确保变方块前角度已经归零
     */
    private void smoothStopRotationNearGround(class_1540 self,
                                               RotatingFallingBlockManager.PhysicsState state,
                                               class_1937 world)
    {
        if (world == null) return;

        // 检测实体下方是否有方块（射线检测下方 rotationStopDistance 格）
        double stopDist = state.rotationStopDistance;
        class_2338 belowPos = class_2338.method_49637(self.method_23317(), self.method_23318() - stopDist, self.method_23321());
        class_2680 belowState = world.method_8320(belowPos);

        // 计算实体到下方方块的距离
        double groundDistance = self.method_23318() - (belowPos.method_10264() + 1);

        // 如果下方没有方块（空气），不处理
        if (belowState.method_26215())
        {
            return;
        }

        // 如果距离大于减速距离，不处理
        if (groundDistance > stopDist)
        {
            return;
        }

        // === 接近地面，开始减速旋转 ===

        // 计算减速因子：距离越近，角速度衰减越快
        double factor = Math.max(0.0, groundDistance / stopDist);

        // 衰减角速度
        state.angularVelocityX *= factor;
        state.angularVelocityY *= factor;
        state.angularVelocityZ *= factor;

        // 用 rotationResetDuration 控制插值速度：每 tick 插值 1/duration
        // 这样 duration tick 后角度完全归零
        int duration = Math.max(1, state.rotationResetDuration);
        double lerpFactor = 1.0 / duration;

        // 距离很近时，把旋转角度向 0 度插值
        if (groundDistance < stopDist * 0.5)
        {
            state.rotationX = lerpToZeroAngle(state.rotationX, lerpFactor);
            state.rotationY = lerpToZeroAngle(state.rotationY, lerpFactor);
            state.rotationZ = lerpToZeroAngle(state.rotationZ, lerpFactor);
        }

        // 距离较近时强制停止旋转并归零到0度（从0.5增大到1.5，确保变方块前已归零）
        if (groundDistance < 1.5)
        {
            state.angularVelocityX = 0;
            state.angularVelocityY = 0;
            state.angularVelocityZ = 0;
            state.rotationX = 0;
            state.rotationY = 0;
            state.rotationZ = 0;
            state.rotationSnapped = true;
        }
    }

    /**
     * 把角度向 0 度插值（取最短路径）
     */
    private static float lerpToZeroAngle(float currentAngle, double lerpFactor)
    {
        float target = 0F;
        float diff = target - currentAngle;
        // 处理环绕：取最短路径
        if (diff > 180) diff -= 360;
        if (diff < -180) diff += 360;

        return currentAngle + (float) (diff * lerpFactor);
    }

    /**
     * 把旋转角度、角速度、挤压变形同步到 DataTracker，客户端渲染 Mixin 会读取
     *
     * 同步角速度是为了让客户端做插值：客户端每帧渲染时用
     *   当前角度 + 角速度 × tickDelta
     * 计算插值角度，避免高帧率下旋转一卡一卡的
     */
    private void syncRotationToDataTracker(class_1540 entity,
                                            RotatingFallingBlockManager.PhysicsState state)
    {
        try
        {
            entity.method_5841().method_12778(FallingBlockRotationData.ROTATION_X, state.rotationX);
            entity.method_5841().method_12778(FallingBlockRotationData.ROTATION_Y, state.rotationY);
            entity.method_5841().method_12778(FallingBlockRotationData.ROTATION_Z, state.rotationZ);
            entity.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_X, state.angularVelocityX);
            entity.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Y, state.angularVelocityY);
            entity.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Z, state.angularVelocityZ);
            entity.method_5841().method_12778(FallingBlockRotationData.SQUASH_AMOUNT, state.squashAmount);
            entity.method_5841().method_12778(FallingBlockRotationData.SQUASH_DIRECTION, state.squashDirection);
        }
        catch (Exception e)
        {
            // DataTracker 可能未初始化，忽略
        }
    }
}
