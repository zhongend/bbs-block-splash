package com.example.bbsanimatedbreak;

import com.example.bbsanimatedbreak.mixin.EntityAccessor;
import java.util.Iterator;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.class_1540;
import net.minecraft.class_2338;
import net.minecraft.class_2680;
import net.minecraft.class_3218;

/**
 * 方块飞溅（反向版本）调度器
 *
 * === 工作原理 ===
 * 效果一开始方块就是已散落状态（无飞溅散开动画）。
 * 调度器在 scheduleReverse 中立即移除原位方块并在散落位置创建静止的 FallingBlockEntity，
 * 之后每 tick 驱动恢复流程。
 *
 * === 流程 ===
 * 1. scheduleReverse：立即移除原位方块，在散落位置创建 FallingBlockEntity（静止悬浮）
 * 2. WAITING 阶段：等待 recoveryDelay tick
 * 3. RECOVERING 阶段：实体关闭重力，直线飞向原位
 * 4. DONE：实体到达原位，变回方块
 *
 * === 散落位置 ===
 * - 随机模式：原位 + 随机偏移（散落半径控制），Y 始终高于原位
 * - 集中模式：集中坐标
 *
 * === 性能优化 ===
 * - 使用 CopyOnWriteArrayList 避免并发修改异常
 * - 任务完成后从调度列表移除
 * - 最多 30000 个方块
 */
public class BlockSplashReverseScheduler
{
    /**
     * 任务阶段
     */
    private enum Phase
    {
        WAITING,     // 等待阶段：实体静止悬浮，等待恢复延迟
        RECOVERING,  // 恢复阶段：实体飞向原位
        SNAPPING,    // 归零等待阶段：实体悬停在原位附近，角度逐渐归零
        DONE         // 完成
    }

    /**
     * 单个方块的反向散落+恢复任务
     */
    private static class ReverseTask
    {
        // 基本属性
        final class_3218 world;
        final class_2338 pos;               // 原位（方块要飞回的位置）
        final class_2680 originalState;
        final double recoverySpeed;
        final int recoveryDelay;
        final boolean enableRotation;
        final boolean smoothRotationStop;  // 归位时旋转平滑过渡
        final double rotationStopDistance; // 旋转减速距离
        final int rotationResetDuration;   // 归零动画持续时间（tick）
        final boolean disableCollisionDuringRecovery; // 恢复时暂停方块间碰撞
        final int animationKeepDuration;   // 保留动画方块时长（tick，修复闪烁）

        // 运行时状态
        Phase phase;
        int currentTick;
        int snappingTicks;     // 归零等待阶段已经过的 tick 数
        UUID entityUuid;      // 散落/恢复阶段的实体 UUID
        boolean blockRemoved;  // 原位方块是否已被移除
        boolean finished;

        // 进入 SNAPPING 阶段时的初始角度（用于线性插值归零，确保 discard 时角度正好为 0，无跳跃）
        float initialRotationX;
        float initialRotationY;
        float initialRotationZ;

        // 恢复阶段起点（进入 RECOVERING 时记录，用于插值动画）
        double recoverStartX;
        double recoverStartY;
        double recoverStartZ;
        int recoverStartTick;
        double recoverStartDistance;

        ReverseTask(class_3218 world, class_2338 pos, class_2680 originalState,
                    double recoverySpeed, int recoveryDelay, boolean enableRotation,
                    boolean smoothRotationStop, double rotationStopDistance,
                    int rotationResetDuration, boolean disableCollisionDuringRecovery,
                    int animationKeepDuration)
        {
            this.world = world;
            this.pos = pos.method_10062();
            this.originalState = originalState;
            this.recoverySpeed = recoverySpeed;
            this.recoveryDelay = recoveryDelay;
            this.enableRotation = enableRotation;
            this.smoothRotationStop = smoothRotationStop;
            this.rotationStopDistance = rotationStopDistance;
            this.rotationResetDuration = rotationResetDuration;
            this.disableCollisionDuringRecovery = disableCollisionDuringRecovery;
            this.animationKeepDuration = animationKeepDuration;

            this.phase = Phase.WAITING;
            this.currentTick = 0;
            this.snappingTicks = 0;
            this.entityUuid = null;
            this.blockRemoved = false;
            this.finished = false;

            this.initialRotationX = 0F;
            this.initialRotationY = 0F;
            this.initialRotationZ = 0F;

            this.recoverStartX = 0D;
            this.recoverStartY = 0D;
            this.recoverStartZ = 0D;
            this.recoverStartTick = 0;
            this.recoverStartDistance = 0D;
        }
    }

    /** 所有活跃的反向散落+恢复任务 */
    private static final CopyOnWriteArrayList<ReverseTask> tasks = new CopyOnWriteArrayList<>();

    /**
     * 调度一个方块的反向散落+恢复任务
     *
     * 调用此方法时立即移除原位方块，在散落位置创建 FallingBlockEntity。
     * 实体一开始静止悬浮，等待 recoveryDelay 后飞向原位。
     */
    public static void scheduleReverse(class_3218 world, class_2338 pos, class_2680 originalState,
                                       double scatterRadius, double recoverySpeed,
                                       int recoveryDelay, boolean isRandom, class_2338 concentratePos,
                                       boolean enableRotation,
                                       boolean smoothRotationStop, double rotationStopDistance,
                                       int rotationResetDuration,
                                       boolean disableCollisionDuringRecovery,
                                       int animationKeepDuration)
    {
        if (world == null || pos == null || originalState == null) return;

        ReverseTask task = new ReverseTask(
            world, pos, originalState, recoverySpeed, recoveryDelay, enableRotation,
            smoothRotationStop, rotationStopDistance, rotationResetDuration,
            disableCollisionDuringRecovery, animationKeepDuration
        );

        // 立即创建散落实体
        createScatteredEntity(task, scatterRadius, isRandom, concentratePos);

        tasks.add(task);
    }

    /**
     * 立即在散落位置创建 FallingBlockEntity
     *
     * 不在原位调用 spawnFromBlock（那样会产生飞溅动画），而是：
     * 1. 手动移除原位方块
     * 2. 在散落位置临时放置方块
     * 3. 在散落位置调用 spawnFromBlock 创建实体（实体直接出生在散落位置）
     * 4. 恢复散落位置原状
     */
    private static void createScatteredEntity(ReverseTask task, double scatterRadius,
                                              boolean isRandom, class_2338 concentratePos)
    {
        // 检查原方块是否还在
        class_2680 currentState = task.world.method_8320(task.pos);
        if (currentState.method_26215())
        {
            task.finished = true;
            return;
        }

        // === 计算散落位置 ===
        Random random = new Random(task.pos.hashCode());
        double scatterX, scatterY, scatterZ;

        if (isRandom)
        {
            // 随机模式：散落在原位周围的随机位置
            // Y 始终高于原位（至少 scatterRadius * 0.3 以上），确保实体在空中
            double radius = scatterRadius;
            scatterX = task.pos.method_10263() + 0.5 + (random.nextDouble() - 0.5) * 2 * radius;
            scatterY = task.pos.method_10264() + 0.5 + radius * (0.3 + random.nextDouble() * 0.7);
            scatterZ = task.pos.method_10260() + 0.5 + (random.nextDouble() - 0.5) * 2 * radius;
        }
        else
        {
            // 集中模式：所有方块聚集在集中坐标
            scatterX = concentratePos.method_10263() + 0.5;
            scatterY = concentratePos.method_10264() + 0.5;
            scatterZ = concentratePos.method_10260() + 0.5;
        }

        // 散落位置的 BlockPos（spawnFromBlock 需要的整数坐标）
        class_2338 scatterBlockPos = new class_2338(
            (int) Math.floor(scatterX),
            (int) Math.floor(scatterY),
            (int) Math.floor(scatterZ)
        );

        // === 手动移除原位方块（不产生飞溅动画） ===
        task.world.method_8650(task.pos, false);
        task.blockRemoved = true;

        // === 在散落位置临时放置方块，用 spawnFromBlock 创建实体 ===
        // 保存散落位置原来的方块状态
        class_2680 scatterOriginalState = task.world.method_8320(scatterBlockPos);

        // 临时放置目标方块
        task.world.method_8652(scatterBlockPos, task.originalState, 2);

        // 在散落位置调用 spawnFromBlock，实体直接出生在散落位置
        class_1540 falling = class_1540.method_40005(
            task.world, scatterBlockPos, task.originalState);

        // 恢复散落位置原来的方块状态
        task.world.method_8652(scatterBlockPos, scatterOriginalState, 2);

        if (falling == null)
        {
            // 转换失败，直接恢复原位方块
            task.world.method_8652(task.pos, task.originalState, 0x12);
            task.blockRemoved = false;
            task.finished = true;
            return;
        }

        // === 微调实体位置到精确的散落坐标 ===
        // FallingBlockEntity 实体 Y 坐标的含义是「方块底面 Y」，渲染时 +0.5 偏移到「方块视觉中心 Y」
        // spawnFromBlock 把实体放在 (blockPos.x + 0.5, blockPos.y, blockPos.z + 0.5)
        // 我们需要方块视觉中心在 (scatterX, scatterY, scatterZ)，所以实体 Y = scatterY - 0.5
        setPositionWithPrev(falling, scatterX, scatterY - 0.5, scatterZ);
        falling.method_18800(0, 0, 0);
        falling.field_6037 = true;
        falling.field_7193 = false;
        falling.field_5960 = true;

        // 关闭重力，让实体静止悬浮在散落位置
        falling.method_5875(true);

        // 旋转物理
        if (task.enableRotation)
        {
            // 反向飞溅时如果启用了「恢复时暂停碰撞」，把 disableCollision 传过去，
            // 这样在 RECOVERING 和 SNAPPING 阶段方块之间不会互相推开，避免错位
            RotatingFallingBlockManager.markRotating(
                falling,
                task.smoothRotationStop,
                task.rotationStopDistance,
                task.rotationResetDuration,
                task.disableCollisionDuringRecovery
            );
        }

        // 记录实体
        task.entityUuid = falling.method_5667();

        // 记录实体（用于退出时清理）
        BlockSplashRecoveryManager.recordEntity(task.world, falling);
    }

    /**
     * 每 tick 更新所有任务（由 ServerTickEvents 调用）
     */
    public static void tick()
    {
        if (tasks.isEmpty()) return;

        Iterator<ReverseTask> iterator = tasks.iterator();
        while (iterator.hasNext())
        {
            ReverseTask task = iterator.next();
            try
            {
                updateTask(task);
            }
            catch (Exception e)
            {
                tryRestoreBlock(task);
                task.finished = true;
            }

            if (task.finished)
            {
                tasks.remove(task);
            }
        }
    }

    /**
     * 更新单个任务
     */
    private static void updateTask(ReverseTask task)
    {
        task.currentTick++;

        switch (task.phase)
        {
            case WAITING:
                updateWaitingPhase(task);
                break;

            case RECOVERING:
                updateRecoveringPhase(task);
                break;

            case SNAPPING:
                updateSnappingPhase(task);
                break;

            case DONE:
            default:
                task.finished = true;
                break;
        }

        // 超时保护（考虑 SNAPPING 阶段的持续时间）
        int maxDuration = task.recoveryDelay + 300 + task.rotationResetDuration + 20;
        if (task.currentTick > maxDuration)
        {
            tryRestoreBlock(task);
            task.finished = true;
        }
    }

    /**
     * 等待阶段：等待 recoveryDelay tick 后开始恢复
     */
    private static void updateWaitingPhase(ReverseTask task)
    {
        // 检查实体是否还在
        if (task.entityUuid != null)
        {
            net.minecraft.class_1297 entity = task.world.method_14190(task.entityUuid);
            if (entity == null || entity.method_31481())
            {
                // 实体消失了，直接恢复方块
                tryRestoreBlock(task);
                task.phase = Phase.DONE;
                return;
            }
        }

        // 等待恢复延迟
        if (task.currentTick >= task.recoveryDelay)
        {
            startRecovery(task);
            task.phase = Phase.RECOVERING;
        }
    }

    /**
     * 开始恢复：记录起点信息，updateRecoveringPhase 用 tick-based 插值动画
     */
    private static void startRecovery(ReverseTask task)
    {
        if (task.entityUuid == null)
        {
            tryRestoreBlock(task);
            task.phase = Phase.DONE;
            return;
        }

        net.minecraft.class_1297 entity = task.world.method_14190(task.entityUuid);
        if (entity == null || entity.method_31481())
        {
            tryRestoreBlock(task);
            task.phase = Phase.DONE;
            return;
        }

        if (!(entity instanceof class_1540))
        {
            tryRestoreBlock(task);
            task.phase = Phase.DONE;
            return;
        }

        class_1540 falling = (class_1540) entity;

        // 确保原位是空气
        if (!task.world.method_8320(task.pos).method_26215())
        {
            task.world.method_8650(task.pos, false);
        }

        // === 关键优化：用 tick-based 插值动画代替物理速度 ===
        // 之前用 setVelocity 依赖物理 tick 控制方块位置，物理 tick 受 mixin/碰撞/重力等干扰
        // 容易产生顿挫感（每 tick 移动距离不连续）
        //
        // 现在改用插值动画：
        // 1. 记录当前实体位置为起点 (recoverStartX/Y/Z)
        // 2. 记录当前 tick 为起点 (recoverStartTick)
        // 3. 计算到原位的距离 (recoverStartDistance)
        // 4. updateRecoveringPhase 用 eased 函数 (1 - (1-t)^2) 计算每 tick 位置
        // 5. 直接 setPosition 绕过物理 tick
        // 这样位置变化连续平滑，无顿挫感

        double targetX = task.pos.method_10263() + 0.5;
        double targetY = task.pos.method_10264();
        double targetZ = task.pos.method_10260() + 0.5;

        double dx = targetX - falling.method_23317();
        double dy = targetY - falling.method_23318();
        double dz = targetZ - falling.method_23321();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (len < 0.1)
        {
            // 太近了，直接变回方块
            // 先 setBlockState 放置方块，再 discard 实体（避免空档闪烁）
            task.world.method_8652(task.pos, task.originalState, 0x12);
            task.blockRemoved = false;
            falling.method_31472();
            task.entityUuid = null;
            task.phase = Phase.DONE;
            return;
        }

        // 保存起点信息
        task.recoverStartX = falling.method_23317();
        task.recoverStartY = falling.method_23318();
        task.recoverStartZ = falling.method_23321();
        task.recoverStartTick = task.currentTick;
        task.recoverStartDistance = len;

        // 初始速度方向（仅用于首 tick 视觉提示，后续由 setPosition 控制）
        falling.method_18800(0, 0, 0);
        falling.field_6037 = true;
        falling.field_5960 = true;
        falling.method_5875(true);
    }

    /**
     * 恢复阶段：实体持续飞向原位
     *
     * 关键修复：接近原位时不再立即变方块，而是进入 SNAPPING（归零等待）阶段，
     * 让方块悬停在原位附近，用一段时间让旋转角度完全归零后再变方块。
     */
    private static void updateRecoveringPhase(ReverseTask task)
    {
        if (task.entityUuid == null)
        {
            if (task.blockRemoved)
            {
                tryRestoreBlock(task);
            }
            task.phase = Phase.DONE;
            return;
        }

        net.minecraft.class_1297 entity = task.world.method_14190(task.entityUuid);

        if (entity == null || entity.method_31481())
        {
            tryRestoreBlock(task);
            task.entityUuid = null;
            task.phase = Phase.DONE;
            return;
        }

        if (entity instanceof class_1540)
        {
            class_1540 falling = (class_1540) entity;

            // === 关键优化：tick-based 插值动画 ===
            // 不依赖物理 tick，直接用 eased 函数计算每 tick 位置
            // 这样位置变化连续平滑，无顿挫感

            double targetX = task.pos.method_10263() + 0.5;
            double targetY = task.pos.method_10264();
            double targetZ = task.pos.method_10260() + 0.5;

            // 进度 t = (currentTick - recoverStartTick) / 预估总 tick
            // 预估总 tick = startDistance / recoverySpeed
            int elapsed = task.currentTick - task.recoverStartTick;
            double totalTicks = Math.max(1.0, task.recoverStartDistance / Math.max(0.1, task.recoverySpeed));
            double t = Math.min(1.0, elapsed / totalTicks);

            // === 关键：quadratic ease-out ===
            // 公式: eased = 1 - (1 - t)^2
            // - t=0: eased=0 (在起点)
            // - t=0.5: eased=0.75 (走完 75% 距离，速度还在)
            // - t=1: eased=1 (到目标)
            // 这样保证接近目标时减速感明显（"飞过去"的感觉），且位置变化连续无顿挫
            double invT = 1.0 - t;
            double eased = 1.0 - invT * invT;

            // 计算当前应该的位置
            double currentX = task.recoverStartX + (targetX - task.recoverStartX) * eased;
            double currentY = task.recoverStartY + (targetY - task.recoverStartY) * eased;
            double currentZ = task.recoverStartZ + (targetZ - task.recoverStartZ) * eased;

            // === 关键：手动同步 prevPos（修复 165Hz 卡顿）===
            // Entity.setPosition() 不会更新 prevX/Y/Z，物理 tick 也因为 noClip/noGravity 被跳过
            // 导致渲染时 prevPos 一直是初始值（startRecovery 时的 pos），currentPos 跳到新位置
            // 玩家在 165Hz 显示器上看到：方块每 tick 从起点重新插值到当前位置，像是 15-20 帧
            //
            // 现在用 setPositionWithPrev：setPosition 之前先 setPrevX/Y/Z(当前 pos)，
            // 这样 prevPos 始终是"上一帧的 pos"，渲染插值 (prevPos + (pos-prevPos)*tickDelta) 连续平滑
            setPositionWithPrev(falling, currentX, currentY, currentZ);
            falling.method_18800(0, 0, 0);
            falling.field_6037 = true;
            falling.field_5960 = true;
            falling.method_5875(true);

            // 计算到原位的实际距离（基于 eased 位置）
            double dx = targetX - currentX;
            double dy = targetY - currentY;
            double dz = targetZ - currentZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

            // === 旋转平滑过渡（接近原位时减速旋转） ===
            if (task.enableRotation && task.smoothRotationStop)
            {
                smoothStopRotation(task, falling, distance);
            }

            // 接近原位（距离<1.5格）：
            // - 如果启用了旋转平滑过渡，进入 SNAPPING 阶段，悬停归零角度
            // - 否则直接变回方块
            if (distance < 1.5)
            {
                if (task.enableRotation && task.smoothRotationStop)
                {
                    // 进入归零等待阶段：把实体瞬移到精确的原位
                    // 实体 Y = 方块底面 Y = pos.y（不是 pos.y+0.5）
                    // 这样方块视觉中心 = (pos.x+0.5, pos.y+0.5, pos.z+0.5) = 原方块中心 ✓
                    setPositionWithPrev(falling, targetX, targetY, targetZ);
                    falling.method_18800(0, 0, 0);
                    falling.field_6037 = true;
                    falling.method_5875(true);

                    // === 关键：保存当前角度作为 SNAPPING 阶段的初始角度 ===
                    // 之后用线性插值从 initialRotation 归零到 0，
                    // 确保 animationKeepDuration tick 后角度正好为 0，discard 时无角度跳跃闪烁
                    RotatingFallingBlockManager.PhysicsState snapState =
                        RotatingFallingBlockManager.getPhysicsState(falling.method_5667());
                    if (snapState != null)
                    {
                        task.initialRotationX = snapState.rotationX;
                        task.initialRotationY = snapState.rotationY;
                        task.initialRotationZ = snapState.rotationZ;
                    }

                    task.phase = Phase.SNAPPING;
                    task.snappingTicks = 0;
                }
                else
                {
                    // 未启用旋转平滑过渡，直接变回方块
                    // 先 setBlockState 放置方块，再 discard 实体（避免空档闪烁）
                    task.world.method_8652(task.pos, task.originalState, 0x12);
                    task.blockRemoved = false;
                    falling.method_31472();
                    task.entityUuid = null;
                    task.phase = Phase.DONE;
                }
            }
            // 距离 >= 1.5 时由上方的 setPosition + eased 函数控制位置
            // 这里不需要再 setVelocity（位置已由 setPosition 直接控制）
        }
        else
        {
            // 实体类型不对，恢复方块
            tryRestoreBlock(task);
            task.phase = Phase.DONE;
        }
    }

    /**
     * 归零等待阶段：实体悬停在原位附近，保留动画方块存在 animationKeepDuration tick。
     *
     * 修复角度跳跃 + 闪烁空档期的关键阶段：
     *
     * 1. 角度归零（线性插值，无跳跃）：
     *    - 进入 SNAPPING 时保存当前角度为 initialRotation
     *    - 每 tick 用线性插值：state.rotation = initialRotation * (1 - snappingTicks/animationKeepDuration)
     *    - 这样 animationKeepDuration tick 后角度正好为 0 度，discard 时不会出现角度跳跃
     *    - 之前的 lerpToZeroAngle 是指数衰减（40 tick 后还剩 10.9 度），现在改成线性归零
     *
     * 2. 闪烁修复（动画和方块重叠保留）：
     *    - SNAPPING 第一帧就 setBlockState 放置方块（用 0x13 flag = NOTIFY_ALL | SEND_TO_CLIENTS）
     *    - 动画方块继续存在 animationKeepDuration tick（默认 60 = 3 秒）
     *    - 期间动画方块覆盖在实体方块上，玩家看不到空档
     *    - 最后 discard 动画方块，方块已在原位，无闪烁
     *
     * 3. setBlockState 用 0x13 flag 强制同步：
     *    - 0x12 (NOTIFY_NEIGHBORS | SEND_TO_CLIENTS) 可能延迟几 tick 同步 chunk 数据
     *    - 0x13 (NOTIFY_ALL | SEND_TO_CLIENTS) 多了 NOTIFY_LISTENERS，强制立即同步
     *    - 配合 animationKeepDuration = 60 (3秒) 重叠时间，setBlockState 同步完全稳定后 discard
     */
    private static void updateSnappingPhase(ReverseTask task)
    {
        if (task.entityUuid == null)
        {
            if (task.blockRemoved)
            {
                tryRestoreBlock(task);
            }
            task.phase = Phase.DONE;
            return;
        }

        net.minecraft.class_1297 entity = task.world.method_14190(task.entityUuid);

        if (entity == null || entity.method_31481())
        {
            tryRestoreBlock(task);
            task.entityUuid = null;
            task.phase = Phase.DONE;
            return;
        }

        if (!(entity instanceof class_1540))
        {
            tryRestoreBlock(task);
            task.phase = Phase.DONE;
            return;
        }

        class_1540 falling = (class_1540) entity;
        task.snappingTicks++;

        // === 关键修复闪烁：第一帧就先把实体方块放上去 ===
        // 动画方块和实体方块重叠（视觉上是动画方块覆盖在原位上），
        // 这样原位方块在整个 SNAPPING 阶段都存在，最后再 discard 动画方块，
        // 不会出现「动画方块消失 → 实体方块出现」中间的 1 帧空档闪烁。
        if (task.snappingTicks == 1)
        {
            // 用 0x13 flag (NOTIFY_ALL | SEND_TO_CLIENTS) 强制同步到客户端
            // 0x12 缺少 NOTIFY_LISTENERS，可能延迟几 tick 同步 chunk
            task.world.method_8652(task.pos, task.originalState, 0x13);
            // 标记方块已恢复（避免后续 tryRestoreBlock 重复放置）
            task.blockRemoved = false;
        }

        // === 线性插值归零（修复角度跳跃）===
        // 公式：state.rotation = initialRotation * (1 - snappingTicks/animationKeepDuration)
        // 这样 animationKeepDuration tick 后角度正好为 0，discard 时无跳跃
        int keepDuration = Math.max(1, task.animationKeepDuration);
        float t = (float) task.snappingTicks / (float) keepDuration;
        if (t > 1.0F) t = 1.0F;

        // 获取物理状态
        RotatingFallingBlockManager.PhysicsState state = RotatingFallingBlockManager.getPhysicsState(falling.method_5667());

        if (state != null)
        {
            // 角速度归零
            state.angularVelocityX = 0;
            state.angularVelocityY = 0;
            state.angularVelocityZ = 0;

            // === 关键：线性插值归零 ===
            // 不用 lerpToZeroAngle（那是指数衰减，duration tick 后还有残余角度）
            // 直接用 (1 - t) 比例缩放初始角度，确保 t=1 时角度正好为 0
            state.rotationX = task.initialRotationX * (1.0F - t);
            state.rotationY = task.initialRotationY * (1.0F - t);
            state.rotationZ = task.initialRotationZ * (1.0F - t);
        }

        // 直接设置 DataTracker，确保客户端立即看到归零
        try
        {
            float currentRotX = state != null ? state.rotationX : 0F;
            float currentRotY = state != null ? state.rotationY : 0F;
            float currentRotZ = state != null ? state.rotationZ : 0F;

            falling.method_5841().method_12778(FallingBlockRotationData.ROTATION_X, currentRotX);
            falling.method_5841().method_12778(FallingBlockRotationData.ROTATION_Y, currentRotY);
            falling.method_5841().method_12778(FallingBlockRotationData.ROTATION_Z, currentRotZ);
            falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_X, 0F);
            falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Y, 0F);
            falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Z, 0F);
        }
        catch (Exception e)
        {
            // 忽略
        }

        // 保持实体静止
        falling.method_18800(0, 0, 0);
        falling.field_6037 = true;
        falling.method_5875(true);

        // === 移除动画方块的条件（只用时间控制，不用响应式）===
        // 之前用 "blockInPlace && snappingTicks >= 2" 的响应式条件，
        // 但 setBlockState 后 blockInPlace 立即为 true，导致第 2 tick 就 discard 了
        // （与第 1 tick setBlockState 之间没有重叠，玩家能看到动画方块消失→方块出现的空档）
        //
        // 现在只用 animationKeepDuration 时间控制：
        // - SNAPPING 第 1 tick: setBlockState 放方块
        // - SNAPPING 第 1~keepDuration tick: 动画方块和实体方块重叠（3秒）
        // - SNAPPING 第 keepDuration tick: 角度正好 0（线性插值），discard 动画方块
        // - 玩家看到：动画方块 3 秒 → 方块（无空档、无角度跳跃）
        if (task.snappingTicks >= task.animationKeepDuration)
        {
            // 角度应该已经完全归零到 0（线性插值保证），无需再 forceZero
            // 只清零角速度（防御性）
            try
            {
                falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_X, 0F);
                falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Y, 0F);
                falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Z, 0F);
            }
            catch (Exception e)
            {
                // 忽略
            }

            if (state != null)
            {
                state.angularVelocityX = 0;
                state.angularVelocityY = 0;
                state.angularVelocityZ = 0;
                // 不动 state.rotation（已经线性归零到 0）
            }

            // discard 动画方块，方块已经在原位（SNAPPING 第一帧就放了）
            falling.method_31472();
            task.entityUuid = null;
            task.phase = Phase.DONE;
        }
    }

    /**
     * 平滑停止旋转：接近原位时逐渐减小角速度
     *
     * 关键修复（修复角度跳跃）：
     * 1. 之前在 distance < 1.5 时强制 angle = 0，导致 RECOVERING→SNAPPING 边界时角度瞬变
     * 2. 现在只清零角速度，角度保持当前值（可能是任意值）
     * 3. SNAPPING 阶段读取当前 rotation 作为 initialRotation，然后用线性插值归零
     *    - 这样进入 SNAPPING 时 initialRotation 是当前值
     *    - SNAPPING 第 1 tick: angle = initial * (1 - 1/keepDuration) ≈ initial * 0.983
     *    - SNAPPING 第 keepDuration tick: angle = 0
     *    - 整个过程角度平滑过渡，discard 时无跳跃
     *
     * @param task 任务
     * @param falling 下落方块实体
     * @param distance 到原位的距离
     */
    private static void smoothStopRotation(ReverseTask task, class_1540 falling, double distance)
    {
        RotatingFallingBlockManager.PhysicsState state = RotatingFallingBlockManager.getPhysicsState(falling.method_5667());
        if (state == null)
        {
            return;
        }

        double stopDist = task.rotationStopDistance;

        if (distance < stopDist)
        {
            // 计算减速因子：距离越近，角速度衰减越快
            double factor = Math.max(0.0, distance / stopDist);

            // 衰减角速度
            state.angularVelocityX *= factor;
            state.angularVelocityY *= factor;
            state.angularVelocityZ *= factor;

            // 距离很近时（< stopDist * 0.5），把旋转角度向 0 度缓慢插值
            // 注意：这里用 lerpToZeroAngle 是渐进式插值（指数衰减），不是强制归零
            // SNAPPING 阶段会用线性插值从当前角度归零到 0
            if (distance < stopDist * 0.5)
            {
                // 插值速度：距离越近插值越快
                double lerpFactor = 1.0 - (distance / (stopDist * 0.5));
                lerpFactor = Math.max(0.0, Math.min(1.0, lerpFactor)) * 0.3;

                state.rotationX = lerpToZeroAngle(state.rotationX, lerpFactor);
                state.rotationY = lerpToZeroAngle(state.rotationY, lerpFactor);
                state.rotationZ = lerpToZeroAngle(state.rotationZ, lerpFactor);
            }

            // 距离很近时（< 1.5格）只清零角速度，不动 angle
            // 角度归零交给 SNAPPING 阶段的线性插值，避免 RECOVERING→SNAPPING 边界角度跳跃
            if (distance < 1.5)
            {
                state.angularVelocityX = 0;
                state.angularVelocityY = 0;
                state.angularVelocityZ = 0;
                // 不动 state.rotation（保留当前角度，让 SNAPPING 阶段用线性插值归零）
                state.rotationSnapped = false;  // 标记为未归零（由 SNAPPING 阶段处理）

                // 只同步角速度归零到客户端（不动 rotation）
                // rotation 的同步由 SNAPPING 阶段的 updateSnappingPhase 通过线性插值完成
                try
                {
                    falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_X, 0F);
                    falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Y, 0F);
                    falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Z, 0F);
                }
                catch (Exception e)
                {
                    // 忽略
                }
            }
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
     * 强制把旋转角度归零（变回方块前调用）
     */
    private static void forceZeroRotation(class_1540 falling)
    {
        try
        {
            falling.method_5841().method_12778(FallingBlockRotationData.ROTATION_X, 0F);
            falling.method_5841().method_12778(FallingBlockRotationData.ROTATION_Y, 0F);
            falling.method_5841().method_12778(FallingBlockRotationData.ROTATION_Z, 0F);
            falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_X, 0F);
            falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Y, 0F);
            falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Z, 0F);
        }
        catch (Exception e)
        {
            // 忽略
        }

        // 同时更新 PhysicsState
        RotatingFallingBlockManager.PhysicsState state = RotatingFallingBlockManager.getPhysicsState(falling.method_5667());
        if (state != null)
        {
            state.rotationX = 0;
            state.rotationY = 0;
            state.rotationZ = 0;
            state.angularVelocityX = 0;
            state.angularVelocityY = 0;
            state.angularVelocityZ = 0;
            state.stopped = true;
        }
    }

    /**
     * 设置实体位置并同步 prevPos（修复 165Hz 高刷新率下位置卡顿）
     *
     * === 为什么需要这个方法 ===
     * MC 1.20.1 的渲染用 prevPos + (pos - prevPos) * tickDelta 插值。
     * 渲染线程每帧都重新计算位置，所以高刷新率（165Hz）下需要 prevPos 持续更新。
     *
     * 但是 Entity.setPosition() 不会更新 prevX/Y/Z。
     * 物理 tick（move()）会更新 prevPos，但 noClip=true / noGravity=true 时 move() 内部仍用 setPosition
     * （movement 累加到 pos），不会更新 prevPos。
     *
     * 结果：scheduler 每 server tick（20 TPS）才更新一次 pos 和 prevPos（物理 tick 后），
     * 165Hz 显示器渲染时 8 帧共享同一对 prevPos/pos，tickDelta 0~1 之间均匀插值 → 看起来流畅
     * 但下一 server tick 时 prevPos 没动，pos 跳到新位置 → 渲染从旧 prevPos 插值到新 pos → 跳变
     * 用户感受：每 tick 一次"跳回再插值"，像 15-20 帧
     *
     * === 修复方法 ===
     * 在 setPosition 之前，把当前 pos 写入 prevPos。
     * 这样 prevPos 始终是"上一帧的 pos"，渲染插值始终是连续的。
     *
     * 注意：setPosition 不会自动调用 setBoundingBox，所以这里先 setPosition 再 setBoundingBox。
     */
    private static void setPositionWithPrev(class_1540 falling, double x, double y, double z)
    {
        EntityAccessor accessor = (EntityAccessor) falling;

        // 1. 把"当前帧的 pos"（即将被覆盖）写入 prevPos
        //    这样 prevPos = 上一帧的 pos
        accessor.setPrevX(falling.method_23317());
        accessor.setPrevY(falling.method_23318());
        accessor.setPrevZ(falling.method_23321());

        // 2. 设置新的 pos
        falling.method_5814(x, y, z);
    }

    /**
     * 尝试恢复方块到原位（出错时的保护）
     */
    private static void tryRestoreBlock(ReverseTask task)
    {
        try
        {
            // 移除残留的实体
            if (task.entityUuid != null)
            {
                net.minecraft.class_1297 e = task.world.method_14190(task.entityUuid);
                if (e != null && !e.method_31481()) e.method_31472();
                task.entityUuid = null;
            }

            // 恢复原位方块
            task.world.method_8652(task.pos, task.originalState, 0x12);
            task.blockRemoved = false;
        }
        catch (Exception e)
        {
            // 忽略恢复失败
        }
    }

    /**
     * 清除所有任务（退出时调用）
     */
    public static void clearAll()
    {
        for (ReverseTask task : tasks)
        {
            tryRestoreBlock(task);
        }
        tasks.clear();
    }

    /**
     * 获取活跃任务数量（调试用）
     */
    public static int getActiveTaskCount()
    {
        return tasks.size();
    }
}