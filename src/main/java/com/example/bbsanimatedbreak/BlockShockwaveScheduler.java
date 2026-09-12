package com.example.bbsanimatedbreak;

import com.example.bbsanimatedbreak.mixin.EntityAccessor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.class_1540;
import net.minecraft.class_2338;
import net.minecraft.class_2680;
import net.minecraft.class_3218;

/**
 * 方块振波调度器
 *
 * === 工作原理 ===
 * BlockShockwaveActionClip 在 applyAction 中调用 scheduleShake 注册所有方块的震动计划。
 * 调度器在服务端每 tick 更新，按计划分批震动方块。
 *
 * === 震动流程（每个方块） ===
 * 1. 等待 delay tick（波浪传播延迟）
 * 2. 到达震动开始时间后，把方块变成 FallingBlockEntity
 * 3. 给方块向上的初速度（让它跳起）
 * 4. 方块受重力下落
 * 5. 落地后变回原来的方块（回到原位）
 * 6. 如果 shakeFrequency > 1，间隔一段时间再次跳起（多次震动）
 * 7. 震动结束后，确保方块回到原位
 *
 * === 原地震动保证 ===
 * 关键：方块只上下震动，不水平移动。
 * - FallingBlockEntity 的水平速度设为 0
 * - 落地后强制把方块放回原位（setBlockState）
 * - 如果 FallingBlockEntity 飞偏了，震动结束后强制恢复
 *
 * === 性能优化 ===
 * - 使用 CopyOnWriteArrayList 避免并发修改异常
 * - 震动结束后从调度列表移除
 * - 最多 30000 个方块，每个方块震动约 20-60 tick，总调度开销可接受
 */
public class BlockShockwaveScheduler
{
    /**
     * 单个方块的震动任务
     */
    private static class ShakeTask
    {
        // 基本属性
        final class_3218 world;
        final class_2338 pos;
        final class_2680 originalState;
        final double amplitude;
        final int shakeDuration;
        final int shakeFrequency;
        final double decay;
        final boolean shouldRestore;

        // === 真实化角度参数 ===
        final boolean useRealisticAngle;  // 是否启用倾斜角度
        final double impactForce;          // 冲击力度
        final double centerX, centerY, centerZ;  // 震源中心坐标（用于计算倾斜方向）
        final double maxDistance;          // 区域最大半径（用于距离衰减计算）

        // === 方向 & 模式 ===
        final boolean fromCenter;          // true=从中心震动, false=从方向震动
        final String direction;            // north/south/east/west（仅 fromCenter=false 时生效）
        final String shakeMode;            // circle/rectangle/cross/diamond/earthquake/ripple/chaos

        // 运行时状态
        int currentTick;          // 已经过的 tick 数
        int currentShakeCount;    // 已震动次数
        int nextShakeTick;        // 下次震动开始的 tick
        boolean finished;         // 是否已完成所有震动
        UUID activeEntityUuid;    // 当前活跃的 FallingBlockEntity UUID
        boolean blockRemoved;     // 方块是否已被移除（变成实体）
        boolean entityLanded;     // 实体是否已落地冻结（真实化角度模式下保留倾斜）
        float lastTiltX, lastTiltZ; // 上次设置的倾斜角度（用于落地后保持倾斜）

        // === 非线性旋转动画 ===
        float targetTiltX, targetTiltZ;  // 目标倾斜角度
        int tiltAnimTick;                 // 倾斜动画已进行 tick 数
        int tiltAnimDuration;             // 倾斜动画总时长（tick）
        boolean tiltAnimating;            // 是否正在执行倾斜动画

        // === 地震/混沌模式：随机震动状态 ===
        int nextRandomShakeTick;          // 下次随机震动 tick（earthquake/chaos 用）
        float randomTiltX, randomTiltZ;   // 随机倾斜角度（chaos 用）

        ShakeTask(class_3218 world, class_2338 pos, class_2680 originalState,
                  int delay, double amplitude, int shakeDuration,
                  int shakeFrequency, double decay, boolean shouldRestore,
                  boolean useRealisticAngle, double impactForce,
                  double centerX, double centerY, double centerZ, double maxDistance,
                  boolean fromCenter, String direction, String shakeMode)
        {
            this.world = world;
            this.pos = pos.method_10062();
            this.originalState = originalState;
            this.amplitude = amplitude;
            this.shakeDuration = shakeDuration;
            this.shakeFrequency = shakeFrequency;
            this.decay = decay;
            this.shouldRestore = shouldRestore;

            this.useRealisticAngle = useRealisticAngle;
            this.impactForce = impactForce;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.maxDistance = maxDistance;

            this.fromCenter = fromCenter;
            this.direction = direction;
            this.shakeMode = shakeMode;

            this.currentTick = 0;
            this.currentShakeCount = 0;
            this.nextShakeTick = delay; // 第一次震动在 delay tick 后
            this.finished = false;
            this.activeEntityUuid = null;
            this.blockRemoved = false;
            this.entityLanded = false;
            this.lastTiltX = 0;
            this.lastTiltZ = 0;

            this.targetTiltX = 0;
            this.targetTiltZ = 0;
            this.tiltAnimTick = 0;
            this.tiltAnimDuration = 8; // 默认 8 tick 完成倾斜渐变（约 0.4 秒）
            this.tiltAnimating = false;

            this.nextRandomShakeTick = 0;
            this.randomTiltX = 0;
            this.randomTiltZ = 0;
        }
    }

    /** 所有活跃的震动任务 */
    private static final CopyOnWriteArrayList<ShakeTask> tasks = new CopyOnWriteArrayList<>();

    /** 当前活跃的 FallingBlockEntity 和对应的任务（用于追踪实体落地） */
    private static final ConcurrentHashMap<UUID, ShakeTask> entityToTask = new ConcurrentHashMap<>();

    /**
     * 已排入队列、尚未结束的震动任务位置（按世界分组）
     *
     * 用于 applyAction 的幂等：BBS 拖动时间轴时会逐 tick 重放 applyAction，
     * 若不去重，同一坐标会被排入多个任务，之后各自 startShake / 恢复，
     * 对该 pos 交替 setBlockState 与生成实体 → 方块重复、错位、抽搐。
     * 任务结束时释放该位置，允许后续片段再次震动同一方块。
     */
    private static final java.util.Map<class_3218, java.util.Set<class_2338>> scheduledPositions
        = new ConcurrentHashMap<>();

    /**
     * 调度一个方块的震动任务
     *
     * @param world 世界
     * @param pos 方块位置
     * @param originalState 原始方块状态
     * @param delay 震动延迟（tick，波浪传播延迟）
     * @param amplitude 震动幅度（跳起高度）
     * @param shakeDuration 单次震动持续时间（tick）
     * @param shakeFrequency 震动次数
     * @param decay 每次震动衰减比例
     * @param shouldRestore 震动后是否恢复原状
     * @param useRealisticAngle 是否启用真实化角度倾斜
     * @param impactForce 冲击力度（控制倾斜角度大小）
     * @param centerX/Y/Z 震源中心坐标（用于计算倾斜方向和距离衰减）
     * @param maxDistance 区域最大半径（用于距离衰减归一化）
     * @param fromCenter true=从中心震动, false=从方向震动
     * @param direction north/south/east/west（仅 fromCenter=false 时生效）
     * @param shakeMode circle/rectangle/cross/diamond/earthquake/ripple/chaos
     */
    public static void scheduleShake(class_3218 world, class_2338 pos, class_2680 originalState,
                                      int delay, double amplitude, int shakeDuration,
                                      int shakeFrequency, double decay, boolean shouldRestore,
                                      boolean useRealisticAngle, double impactForce,
                                      double centerX, double centerY, double centerZ,
                                      double maxDistance,
                                      boolean fromCenter, String direction, String shakeMode)
    {
        if (world == null || pos == null || originalState == null) return;

        // === 幂等去重（关键） ===
        java.util.Set<class_2338> scheduled = scheduledPositions
            .computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet());

        if (!scheduled.add(pos.method_10062()))
        {
            return;
        }

        ShakeTask task = new ShakeTask(
            world, pos, originalState, delay, amplitude,
            shakeDuration, shakeFrequency, decay, shouldRestore,
            useRealisticAngle, impactForce,
            centerX, centerY, centerZ, maxDistance,
            fromCenter, direction, shakeMode
        );
        tasks.add(task);
    }

    /**
     * 每 tick 更新所有震动任务（由 ServerTickEvents 调用）
     */
    public static void tick()
    {
        if (tasks.isEmpty()) return;

        Iterator<ShakeTask> iterator = tasks.iterator();
        while (iterator.hasNext())
        {
            ShakeTask task = iterator.next();
            try
            {
                updateTask(task);
            }
            catch (Exception e)
            {
                // 出错时尝试恢复方块，避免方块丢失
                tryRestoreBlock(task);
                task.finished = true;
            }

            if (task.finished)
            {
                tasks.remove(task);
                releaseScheduled(task);
            }
        }
    }

    /**
     * 更新单个震动任务
     */
    private static void updateTask(ShakeTask task)
    {
        task.currentTick++;

        // === 非线性倾斜动画更新 ===
        // 角度从 0 渐变到目标角度，使用 ease-out 曲线（1 - (1-t)^2）让动画丝滑
        if (task.tiltAnimating && task.activeEntityUuid != null)
        {
            updateTiltAnimation(task);
        }

        // 检查当前活跃的实体是否已落地
        if (task.activeEntityUuid != null)
        {
            checkEntityLanded(task);
        }

        // 检查是否到了下次震动时间
        // 启动条件：未完成 + 还有震动次数 + 到时间 + (无活跃实体 或 实体已落地冻结可复用)
        if (!task.finished && task.currentShakeCount < task.shakeFrequency
            && task.currentTick >= task.nextShakeTick
            && (task.activeEntityUuid == null || task.entityLanded))
        {
            startShake(task);
        }

        // 检查是否所有震动都完成了
        // 完成条件：震动次数达标 + (无活跃实体 或 实体已落地冻结)
        if (task.currentShakeCount >= task.shakeFrequency
            && (task.activeEntityUuid == null || task.entityLanded))
        {
            // 所有震动完成
            if (task.shouldRestore)
            {
                // 恢复原状：discard 冻结的实体（如果有）+ setBlockState 恢复原方块（直立）
                discardActiveEntity(task);
                tryRestoreBlock(task);
            }
            else if (task.useRealisticAngle && task.activeEntityUuid != null)
            {
                // 不恢复原状 + 真实化角度：保持冻结的倾斜实体（永久冲击坑效果）
                // 实体已经落地冻结，保留倾斜角度，不 discard
                // 清理 entityToTask 映射但保留实体
                entityToTask.remove(task.activeEntityUuid);
                task.activeEntityUuid = null; // 任务结束，不再追踪
            }
            else
            {
                // 不恢复原状 + 无真实化角度：discard 实体，不恢复方块（原行为）
                discardActiveEntity(task);
            }
            task.finished = true;
        }

        // 超时保护：如果任务运行时间过长，强制结束
        int maxDuration = task.nextShakeTick + task.shakeFrequency * (task.shakeDuration + 10) + 100;
        if (task.currentTick > maxDuration)
        {
            // 超时时也根据 shouldRestore 和 useRealisticAngle 决定是否保留倾斜实体
            if (task.shouldRestore)
            {
                discardActiveEntity(task);
                tryRestoreBlock(task);
            }
            task.finished = true;
        }
    }

    /**
     * 丢弃当前活跃的 FallingBlockEntity（如果存在且未移除）
     */
    private static void discardActiveEntity(ShakeTask task)
    {
        if (task.activeEntityUuid == null) return;

        try
        {
            net.minecraft.class_1297 entity = task.world.method_14190(task.activeEntityUuid);
            if (entity != null && !entity.method_31481())
            {
                entity.method_31472();
            }
        }
        catch (Exception e) { /* 忽略 */ }

        entityToTask.remove(task.activeEntityUuid);
        task.activeEntityUuid = null;
        task.entityLanded = false;
    }

    /**
     * 开始一次震动：把方块变成 FallingBlockEntity 并向上跳起
     *
     * 真实化角度模式下：
     * - 如果有冻结的实体（entityLanded=true），复用它重新跳起
     * - 否则创建新的 FallingBlockEntity
     */
    private static void startShake(ShakeTask task)
    {
        // 计算这次震动的幅度
        double currentAmplitude;

        // 确定性随机源（替代 World 的全局随机源 world.getRandom()）：
        // 全局随机源的消费序列取决于同 tick 其它随机调用的次数，
        // 会让同一回放两次播放的幅度/间隔不同，破坏"回放可复现"。
        long rngSeed = task.pos.method_10063() * 0x9E3779B97F4A7C15L
                     ^ (long) task.currentShakeCount * 0xBF58476D1CE4E5B9L;

        // 倾斜扰动的确定性种子（与幅度用不同常量错开，避免两者序列相关）
        long tiltSeed = rngSeed ^ 0xA0761D6478BD642FL;

        if ("earthquake".equals(task.shakeMode))
        {
            // 地震模式：幅度随机波动（不衰减，模拟持续地震活动）
            // 幅度在 0.5×~1.3× 之间随机
            currentAmplitude = task.amplitude * (0.5 + detUnit(rngSeed) * 0.8);
        }
        else if ("chaos".equals(task.shakeMode))
        {
            // 混沌模式：幅度完全随机（可能很大也可能很小）
            currentAmplitude = task.amplitude * detUnit(rngSeed ^ 0x2545F4914F6CDD1DL) * 1.5;
        }
        else if ("ripple".equals(task.shakeMode))
        {
            // 涟漪模式：幅度按正弦波变化（像水波纹起伏）
            // sin(currentShakeCount * 0.8) 范围 -1~1，取绝对值得 0~1，再乘以衰减
            double wave = Math.abs(Math.sin(task.currentShakeCount * 0.8));
            currentAmplitude = task.amplitude * wave * Math.pow(task.decay, task.currentShakeCount);
        }
        else
        {
            // 普通模式：幅度每次衰减
            currentAmplitude = task.amplitude * Math.pow(task.decay, task.currentShakeCount);
        }

        // 如果幅度太小，跳过这次震动
        if (currentAmplitude < 0.05)
        {
            task.currentShakeCount++;
            task.nextShakeTick = task.currentTick + task.shakeDuration;
            return;
        }

        class_1540 falling = null;
        boolean reusedEntity = false;

        // === 真实化角度模式：尝试复用冻结的实体 ===
        if (task.useRealisticAngle && task.activeEntityUuid != null && task.entityLanded)
        {
            try
            {
                net.minecraft.class_1297 entity = task.world.method_14190(task.activeEntityUuid);
                if (entity instanceof class_1540 && !entity.method_31481())
                {
                    falling = (class_1540) entity;
                    reusedEntity = true;
                    task.entityLanded = false;
                }
            }
            catch (Exception e) { /* 忽略，下面创建新实体 */ }
        }

        // === 创建新的 FallingBlockEntity（首次震动或复用失败时） ===
        if (falling == null)
        {
            // 如果有旧的冻结实体但复用失败，先清理
            if (task.activeEntityUuid != null)
            {
                discardActiveEntity(task);
            }

            // 检查方块是否还在（可能被其他操作破坏了）
            class_2680 currentState = task.world.method_8320(task.pos);
            if (currentState.method_26215())
            {
                // 方块已经不在了，直接恢复
                tryRestoreBlock(task);
                task.currentShakeCount++;
                task.nextShakeTick = task.currentTick + task.shakeDuration;
                return;
            }

            // 用原版 API 把方块变成下落方块实体
            falling = class_1540.method_40005(task.world, task.pos, task.originalState);

            if (falling == null)
            {
                // 转换失败，直接恢复
                tryRestoreBlock(task);
                task.currentShakeCount++;
                task.nextShakeTick = task.currentTick + task.shakeDuration;
                return;
            }
        }

        // === 关键：只给向上的速度，水平速度为 0（原地震动） ===
        falling.method_18800(0, currentAmplitude, 0);
        falling.field_6037 = true;
        falling.field_7193 = false;
        falling.field_5960 = false;

        // === 真实化角度模式：参考方块飞溅动画模式 ===
        // 在 spawnFromBlock 之后立即设置 NO_SOLIDIFY=true，让实体从第一个 tick 就被保护，
        // 原版 isOnGround() 检查会被 BbsEntityMixin 拦截返回 false，永远不会变方块。
        // 这样方块保持 FallingBlockEntity 形态，落地后由 freezeLandedEntity 冻结保留倾斜。
        //
        // 同时无条件调用 markRotating，确保实体被标记为旋转方块（即使 maxDistance 很小），
        // 这样 BbsEntityMixin 的 instanceof 检查 + DataTracker 读取一定能生效。
        if (task.useRealisticAngle)
        {
            try
            {
                // 先标记为旋转方块（注册 PhysicsState，同步到客户端渲染）
                // 用不带参数的 markRotating（默认不启用归位旋转）
                RotatingFallingBlockManager.markRotating(falling);

                // 再设置 NO_SOLIDIFY=true（必须在 markRotating 之后，确保 DataTracker 已就绪）
                falling.method_5841().method_12778(FallingBlockRotationData.NO_SOLIDIFY, true);

                // PhysicsState 启用 pathMovement，跳过物理 Mixin 的弹跳干扰
                RotatingFallingBlockManager.PhysicsState pstate =
                    RotatingFallingBlockManager.getPhysicsState(falling.method_5667());
                if (pstate != null)
                {
                    pstate.pathMovement = true;
                    pstate.smoothRotationStop = false;
                }
            }
            catch (Exception e)
            {
                // DataTracker 不可用，忽略
            }
        }

        // === 真实化角度：计算目标倾斜角度（冲击坑效果） ===
        // 根据模式不同，倾斜方向和大小不同：
        // - fromCenter=true：方块朝震源中心倾斜，越近倾斜越大
        // - fromCenter=false：方块朝震动起始方向倾斜（北→向南倒，像被从北边推）
        // - earthquake/chaos 模式：随机方向倾斜
        if (task.useRealisticAngle)
        {
            float tiltX = 0, tiltZ = 0;

            // 距离衰减计算（所有模式通用）
            double dy = task.centerY - task.pos.method_10264();
            double dist3D;
            double falloff;

            if (task.fromCenter)
            {
                double dx = task.centerX - task.pos.method_10263();
                double dz = task.centerZ - task.pos.method_10260();
                dist3D = Math.sqrt(dx * dx + dy * dy + dz * dz);
                falloff = 1.0 - Math.min(1.0, dist3D / Math.max(0.001, task.maxDistance));
            }
            else
            {
                // 方向模式：用方向距离做衰减（起始边 falloff=1.0，远端 falloff=0.0）
                // 这样起始边的方块倾斜最大，远端逐渐变小
                falloff = 1.0 - Math.min(1.0, task.maxDistance > 0.001 ? 1.0 : 0.0);
                // 方向模式下所有方块都给满倾斜（falloff=1.0），因为方向模式没有"中心"概念
                falloff = 1.0;
            }
            // 让衰减更陡峭（中心明显倾斜，外围轻微）
            falloff = falloff * falloff;

            // 基础倾斜角度
            float baseTilt = (float) (task.impactForce * 15.0 * falloff);
            // 加少量随机扰动
            baseTilt *= 0.85F + (float) detUnit(tiltSeed) * 0.3F;
            if (baseTilt > 60.0F) baseTilt = 60.0F;

            // === 根据模式计算倾斜方向 ===
            if ("earthquake".equals(task.shakeMode) || "chaos".equals(task.shakeMode))
            {
                // 地震/混沌模式：完全随机方向倾斜
                double angle = detUnit(tiltSeed ^ 0xE7037ED1A0B428DBL) * Math.PI * 2;
                // chaos 模式倾斜更大更混乱
                float chaosMultiplier = "chaos".equals(task.shakeMode) ? 1.5F : 1.0F;
                tiltX = (float) (Math.cos(angle) * baseTilt * chaosMultiplier);
                tiltZ = (float) (Math.sin(angle) * baseTilt * chaosMultiplier);
                if (tiltX > 60.0F) tiltX = 60.0F;
                if (tiltZ > 60.0F) tiltZ = 60.0F;
            }
            else if (task.fromCenter)
            {
                // 中心模式：方块朝震源中心倾斜
                double dx = task.centerX - task.pos.method_10263();
                double dz = task.centerZ - task.pos.method_10260();
                double horizDist = Math.sqrt(dx * dx + dz * dz);

                if (horizDist > 0.001)
                {
                    double dirX = dx / horizDist;
                    double dirZ = dz / horizDist;
                    tiltX = (float) (dirZ * baseTilt);
                    tiltZ = (float) (-dirX * baseTilt);
                }
                else
                {
                    // 在震源中心正上方：随机方向倾斜
                    double angle = detUnit(tiltSeed ^ 0x8EBC6AF09C88C6E3L) * Math.PI * 2;
                    tiltX = (float) (Math.cos(angle) * baseTilt);
                    tiltZ = (float) (Math.sin(angle) * baseTilt);
                }
            }
            else
            {
                // 方向模式：方块朝震动起始方向倾斜
                // north（从北边开始震动）→ 方块向北倾斜（像被从北边推倒，倒向北方）
                // south → 向南倒, east → 向东倒, west → 向西倒
                //
                // 坐标系：
                // 北 = -Z 方向，南 = +Z 方向，东 = +X 方向，西 = -X 方向
                // 绕 X 轴旋转控制 Z 方向倾斜（正 ROTATION_X = 向 +Z 倒 = 向南倒）
                // 绕 Z 轴旋转控制 X 方向倾斜（负 ROTATION_Z = 向 +X 倒 = 向东倒）
                switch (task.direction)
                {
                    case "north":
                        // 向北倒（-Z 方向）：ROTATION_X 为负
                        tiltX = -baseTilt;
                        break;
                    case "south":
                        // 向南倒（+Z 方向）：ROTATION_X 为正
                        tiltX = baseTilt;
                        break;
                    case "east":
                        // 向东倒（+X 方向）：ROTATION_Z 为负
                        tiltZ = -baseTilt;
                        break;
                    case "west":
                        // 向西倒（-X 方向）：ROTATION_Z 为正
                        tiltZ = baseTilt;
                        break;
                }
            }

            // === 启动非线性倾斜动画 ===
            // 角度从当前值（0）渐变到目标值，使用 ease-out 曲线让动画丝滑
            task.targetTiltX = tiltX;
            task.targetTiltZ = tiltZ;
            task.tiltAnimTick = 0;
            task.tiltAnimating = true;
            // 动画时长根据力度调整：力度越大动画越慢（更丝滑）
            task.tiltAnimDuration = Math.max(4, (int) (task.impactForce * 6));

            // 立即设置初始角度为 0（从直立开始渐变）
            try
            {
                falling.method_5841().method_12778(FallingBlockRotationData.ROTATION_X, 0F);
                falling.method_5841().method_12778(FallingBlockRotationData.ROTATION_Z, 0F);
                falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_X, 0F);
                falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Y, 0F);
                falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Z, 0F);

                RotatingFallingBlockManager.PhysicsState pstate =
                    RotatingFallingBlockManager.getPhysicsState(falling.method_5667());
                if (pstate != null)
                {
                    pstate.angularVelocityX = 0;
                    pstate.angularVelocityY = 0;
                    pstate.angularVelocityZ = 0;
                    pstate.rotationX = 0;
                    pstate.rotationZ = 0;
                }
            }
            catch (Exception e)
            {
                // DataTracker 不可用，忽略
            }
        }

        // 记录实体 UUID 用于追踪落地（仅创建新实体时需要，复用时已记录）
        if (!reusedEntity)
        {
            task.activeEntityUuid = falling.method_5667();
            task.blockRemoved = true;
            entityToTask.put(falling.method_5667(), task);

            // 记录飞溅实体（用于退出时清理）
            BlockSplashRecoveryManager.recordEntity(task.world, falling);
        }

        task.currentShakeCount++;
        // 下次震动时间 = 当前时间 + 震动持续时间
        // earthquake 模式震动更频繁（间隔减半），模拟持续地震
        int interval = task.shakeDuration;
        if ("earthquake".equals(task.shakeMode))
        {
            interval = Math.max(2, task.shakeDuration / 2);
        }
        else if ("chaos".equals(task.shakeMode))
        {
            // chaos 模式间隔随机（2~duration）
            interval = Math.max(2, detInt(task.pos.method_10063() ^ ((long) task.currentShakeCount << 32),
                                          Math.max(1, task.shakeDuration)) + 2);
        }
        task.nextShakeTick = task.currentTick + interval;
    }

    /**
     * 更新非线性倾斜动画
     *
     * 使用 ease-out 曲线：progress = 1 - (1-t)^2
     * - t=0 时 progress=0（角度=0，直立）
     * - t=0.5 时 progress=0.75（快速到达大部分角度）
     * - t=1 时 progress=1（角度=目标值，完成）
     *
     * 这种曲线让方块跳起时快速倾斜，然后缓慢稳定到目标角度，视觉上更丝滑自然
     */
    private static void updateTiltAnimation(ShakeTask task)
    {
        if (task.activeEntityUuid == null) return;

        try
        {
            net.minecraft.class_1297 entity = task.world.method_14190(task.activeEntityUuid);
            if (!(entity instanceof class_1540)) return;
            class_1540 falling = (class_1540) entity;
            if (falling.method_31481()) return;

            task.tiltAnimTick++;
            float t = (float) task.tiltAnimTick / task.tiltAnimDuration;
            if (t >= 1.0F)
            {
                t = 1.0F;
                task.tiltAnimating = false; // 动画结束
            }

            // ease-out 二次曲线：1 - (1-t)^2
            float progress = 1.0F - (1.0F - t) * (1.0F - t);

            // 当前角度 = 目标角度 × progress
            float currentTiltX = task.targetTiltX * progress;
            float currentTiltZ = task.targetTiltZ * progress;

            // 同步到 DataTracker（客户端渲染读取）
            falling.method_5841().method_12778(FallingBlockRotationData.ROTATION_X, currentTiltX);
            falling.method_5841().method_12778(FallingBlockRotationData.ROTATION_Z, currentTiltZ);

            // 同步到 PhysicsState（防止物理 Mixin 覆盖）
            RotatingFallingBlockManager.PhysicsState pstate =
                RotatingFallingBlockManager.getPhysicsState(falling.method_5667());
            if (pstate != null)
            {
                pstate.rotationX = currentTiltX;
                pstate.rotationZ = currentTiltZ;
            }

            // 记录最新角度（用于落地冻结时保持）
            task.lastTiltX = currentTiltX;
            task.lastTiltZ = currentTiltZ;
        }
        catch (Exception e)
        {
            // 忽略
        }
    }

    /**
     * 检查活跃的 FallingBlockEntity 是否已落地
     *
     * 真实化角度模式下：落地后冻结实体（保留倾斜角度），不 discard
     * 普通模式下：落地后 discard 实体并恢复原方块
     */
    private static void checkEntityLanded(ShakeTask task)
    {
        if (task.activeEntityUuid == null) return;

        // 已落地冻结的实体不再检查（避免重复处理）
        if (task.entityLanded) return;

        // 通过 UUID 查找实体
        net.minecraft.class_1297 entity = task.world.method_14190(task.activeEntityUuid);

        if (entity == null || entity.method_31481())
        {
            // 实体已消失（可能被原版变方块或其他原因移除）
            if (task.useRealisticAngle)
            {
                // 真实化角度模式：实体意外消失，标记为落地冻结状态
                // （原版可能把它变方块了，但角度已丢失，这里只是清理状态）
                task.entityLanded = true;
                entityToTask.remove(task.activeEntityUuid);
            }
            else
            {
                // 普通模式：确保方块回到原位
                ensureBlockAtOriginalPosition(task);
                // 必须先 remove 再置 null：ConcurrentHashMap 不允许 null 键，
                // remove(null) 会抛 NPE 并被 tick() 的 catch 吞掉，
                // 该映射条目就永久泄漏（表只增不减）。
                entityToTask.remove(task.activeEntityUuid);
                task.activeEntityUuid = null;
            }
        }
        else if (entity instanceof class_1540)
        {
            class_1540 falling = (class_1540) entity;

            // 检查是否落地（onGround 或垂直速度接近 0 且 age 较大）
            // bbs$ 前缀：读的是 onGround 字段本身，而不是被 BbsEntityMixin
            // 改写成恒 false 的 vanilla Entity.isOnGround()
            boolean onGround = ((EntityAccessor) falling).bbs$isOnGround();
            if (onGround || (falling.field_6012 > 40 && falling.method_18798().field_1351 > -0.01))
            {
                // 实体已落地
                if (task.useRealisticAngle)
                {
                    // === 真实化角度模式：冻结实体，保留倾斜角度 ===
                    // 不 discard 实体，让它以 FallingBlockEntity 形态保留在落地位置
                    // 保持倾斜角度，形成永久冲击坑效果
                    freezeLandedEntity(task, falling);
                    task.entityLanded = true;
                    // 注意：不设 activeEntityUuid = null，保留引用以便复用或最终清理
                    // entityToTask 映射保留，方便后续震动复用
                }
                else
                {
                    // === 普通模式：discard 实体并恢复原方块 ===
                    falling.method_31472();
                    ensureBlockAtOriginalPosition(task);

                    // 同前：先 remove 再置 null，避免 ConcurrentHashMap.remove(null) 抛 NPE
                    entityToTask.remove(task.activeEntityUuid);
                    task.activeEntityUuid = null;
                }
            }
        }
    }

    /**
     * 冻结已落地的 FallingBlockEntity（保留倾斜角度）
     *
     * NO_SOLIDIFY=true 已在 startShake spawnFromBlock 后立即设置，
     * 原版永远不会把这个实体变方块，这里只负责冻结物理状态。
     *
     * - 把实体位置重置回原位（避免漂浮或偏移，防止遁地）
     *   关键：FallingBlockEntity 坐标 Y 是实体底部，原版 spawnFromBlock 传 pos.getY()
     *   所以归位时也用 pos.getY()，让实体底部对齐方块底面，不会陷入下方方块
     * - 速度归零（停止物理运动）
     * - noClip=true（避免被其他实体推动）
     * - 保留倾斜角度（不重置 ROTATION_X/Z）
     */
    private static void freezeLandedEntity(ShakeTask task, class_1540 falling)
    {
        try
        {
            // 把实体位置重置回原位
            // FallingBlockEntity 坐标 Y = 实体底部 = 方块底面 Y = pos.getY()
            // spawnFromBlock 时 Y 就是 pos.getY()，这里归位保持一致
            // X/Z 加 0.5 让实体在方块中心（spawnFromBlock 也是 X+0.5, Z+0.5）
            double targetX = task.pos.method_10263() + 0.5;
            double targetY = task.pos.method_10264();
            double targetZ = task.pos.method_10260() + 0.5;

            // 只在位置偏离较大时才强制归位（避免微调导致抖动）
            double dx = falling.method_23317() - targetX;
            double dy = falling.method_23318() - targetY;
            double dz = falling.method_23321() - targetZ;
            if (dx * dx + dy * dy + dz * dz > 0.04) // 偏离 > 0.2 格
            {
                falling.method_5814(targetX, targetY, targetZ);
                falling.field_6037 = true;
            }

            // 速度归零（无论是否归位都要清零速度）
            falling.method_18800(0, 0, 0);

            // 注意：不设 noClip=true！
            // noClip=true 会让原版 move() 跳过碰撞检测，配合原版每 tick 施加的重力
            // 会导致实体穿透地面遁地。保持 noClip=false，让原版 move() 的碰撞检测
            // 阻止实体下穿（每 tick 重力把实体往下推一点，碰撞检测又停下，稳定在地面）。
            // 原版 isOnGround() 被 BbsEntityMixin 拦截返回 false，所以不会变方块。
            // falling.noClip = true;  // 已移除

            // 保留倾斜角度（不重置 ROTATION_X/Z）
            // 确保 PhysicsState 的角速度为 0（不要动态旋转）
            RotatingFallingBlockManager.PhysicsState pstate =
                RotatingFallingBlockManager.getPhysicsState(falling.method_5667());
            if (pstate != null)
            {
                pstate.angularVelocityX = 0;
                pstate.angularVelocityY = 0;
                pstate.angularVelocityZ = 0;
                // 标记为已停止，避免 PhysicsState.tick() 继续累加旋转
                pstate.stopped = true;
                // 保持当前倾斜角度
                pstate.rotationX = task.lastTiltX;
                pstate.rotationZ = task.lastTiltZ;
                pstate.onGround = true;
                // 保持路径运动模式（跳过物理 Mixin 的弹跳等处理）
                pstate.pathMovement = true;
            }
        }
        catch (Exception e)
        {
            // 忽略
        }
    }

    /**
     * 确保方块回到原位
     *
     * 关键：方块震动后可能落到了别的位置，这里强制把原位恢复成原来的方块，
     * 并清除落地位置可能多出来的方块。
     */
    private static void ensureBlockAtOriginalPosition(ShakeTask task)
    {
        // 1. 把原位恢复成原来的方块
        class_2680 currentAtPos = task.world.method_8320(task.pos);

        // 如果原位是空气或不是原来的方块，恢复原状
        if (currentAtPos.method_26215() || !currentAtPos.equals(task.originalState))
        {
            task.world.method_8652(task.pos, task.originalState, 0x12);
        }

        task.blockRemoved = false;
    }

    /**
     * 尝试恢复方块到原位（出错时的保护）
     */
    private static void tryRestoreBlock(ShakeTask task)
    {
        try
        {
            // 如果方块被移除了，恢复原状
            if (task.blockRemoved)
            {
                task.world.method_8652(task.pos, task.originalState, 0x12);
                task.blockRemoved = false;
            }
            else
            {
                // 方块还在，检查是否是原来的方块
                class_2680 current = task.world.method_8320(task.pos);
                if (!current.equals(task.originalState))
                {
                    task.world.method_8652(task.pos, task.originalState, 0x12);
                }
            }
        }
        catch (Exception e)
        {
            // 忽略恢复失败
        }
    }

    /**
     * 清除所有震动任务（退出时调用）
     *
     * 退出时恢复原状：
     * - discard 所有冻结的倾斜实体（永久冲击坑效果也会被清理）
     * - 恢复所有方块到原位
     */
    public static void clearAll()
    {
        for (ShakeTask task : tasks)
        {
            // 先 discard 冻结的实体（如果有）
            discardActiveEntity(task);
            // 再恢复方块到原位
            tryRestoreBlock(task);
        }
        tasks.clear();
        entityToTask.clear();
        scheduledPositions.clear();
    }

    /**
     * 释放一个已结束任务占用的去重位
     */
    private static void releaseScheduled(ShakeTask task)
    {
        java.util.Set<class_2338> scheduled = scheduledPositions.get(task.world);

        if (scheduled != null)
        {
            scheduled.remove(task.pos);

            if (scheduled.isEmpty())
            {
                scheduledPositions.remove(task.world);
            }
        }
    }

    /* === 确定性伪随机（xorshift64*） ===
     * 替代 World 的全局随机源，保证同一回放两次播放结果一致；
     * 无对象分配、无内部状态，种子由方块坐标与震动序号派生。 */

    private static long detMix(long z)
    {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double detUnit(long seed)
    {
        return (detMix(seed) >>> 11) / (double) (1L << 53);
    }

    private static int detInt(long seed, int bound)
    {
        if (bound <= 0)
        {
            return 0;
        }

        return (int) Math.floorMod(detMix(seed), (long) bound);
    }

    /**
     * 获取活跃任务数量（调试用）
     */
    public static int getActiveTaskCount()
    {
        return tasks.size();
    }
}
