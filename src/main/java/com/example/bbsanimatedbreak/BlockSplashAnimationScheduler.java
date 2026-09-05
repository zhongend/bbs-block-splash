package com.example.bbsanimatedbreak;

import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.class_1540;
import net.minecraft.class_3218;

/**
 * 方块飞溅动画调度器（非实体化模式）
 *
 * === 工作原理 ===
 * 当 BlockSplashActionClip 的 solidify=false 时，方块飞溅后不变成实体方块，
 * 而是保持 FallingBlockEntity 动画形式，依据真实物理模拟旋转移动。
 * 到达用户配置的动画持续时间后，用非线性动画丝滑缩小消失。
 *
 * === 流程 ===
 * 1. scheduleAnimation：注册动画任务，记录持续时间和起始 age
 * 2. ANIMATING 阶段：方块正常物理模拟（由 FallingBlockEntityPhysicsMixin 处理）
 * 3. SHRINKING 阶段：到达持续时间前 1 秒开始非线性缩小（ease-in quadratic）
 * 4. DONE：缩小完成后 discard 实体
 *
 * === 非线性缩小公式 ===
 * progress = shrinkElapsed / shrinkDuration (0.0 ~ 1.0)
 * scale = 1.0 - progress * progress (ease-in quadratic)
 * 特点：开始缩小慢，后期加速消失，丝滑自然
 */
public class BlockSplashAnimationScheduler
{
    /** 缩小动画持续时间（tick，1秒 = 20 tick） */
    private static final int SHRINK_DURATION_TICKS = 20;

    /**
     * 动画任务
     */
    private static class AnimationTask
    {
        final class_3218 world;
        final UUID entityUuid;
        final int totalDurationTicks;  /* 总动画持续时间（含缩小阶段） */
        final int shrinkStartTick;     /* 开始缩小的 tick（总时间 - 缩小时间） */
        final int startAge;            /* 注册时实体的 age */

        int shrinkElapsed;             /* 缩小阶段已过 tick */
        boolean shrinking;             /* 是否在缩小阶段 */
        boolean done;

        AnimationTask(class_3218 world, UUID entityUuid, double durationSeconds, int startAge)
        {
            this.world = world;
            this.entityUuid = entityUuid;
            this.totalDurationTicks = (int) Math.max(SHRINK_DURATION_TICKS + 10, durationSeconds * 20.0);
            this.shrinkStartTick = this.totalDurationTicks - SHRINK_DURATION_TICKS;
            this.startAge = startAge;
            this.shrinkElapsed = 0;
            this.shrinking = false;
            this.done = false;
        }
    }

    /** 所有活跃的动画任务 */
    private static final CopyOnWriteArrayList<AnimationTask> tasks = new CopyOnWriteArrayList<>();

    /**
     * 注册一个非实体化方块动画任务
     *
     * @param world 服务端世界
     * @param falling 下落方块实体
     * @param durationSeconds 动画持续时间（秒）
     */
    public static void scheduleAnimation(class_3218 world, class_1540 falling, double durationSeconds)
    {
        if (world == null || falling == null) return;

        AnimationTask task = new AnimationTask(world, falling.method_5667(), durationSeconds, falling.field_6012);
        tasks.add(task);
    }

    /**
     * 每 tick 更新所有动画任务（由 ServerTickEvents 调用）
     */
    public static void tick()
    {
        if (tasks.isEmpty()) return;

        Iterator<AnimationTask> iterator = tasks.iterator();
        while (iterator.hasNext())
        {
            AnimationTask task = iterator.next();
            try
            {
                updateTask(task);
            }
            catch (Exception e)
            {
                task.done = true;
            }

            if (task.done)
            {
                tasks.remove(task);
            }
        }
    }

    /**
     * 更新单个动画任务
     */
    private static void updateTask(AnimationTask task)
    {
        /* 通过 UUID 查找实体 */
        net.minecraft.class_1297 entity = task.world.method_14190(task.entityUuid);

        /* 实体已消失（被其他逻辑移除） */
        if (entity == null || entity.method_31481())
        {
            task.done = true;
            return;
        }

        if (!(entity instanceof class_1540))
        {
            task.done = true;
            return;
        }

        class_1540 falling = (class_1540) entity;
        int elapsedTicks = falling.field_6012 - task.startAge;

        /* === 判断是否进入缩小阶段 === */
        if (!task.shrinking && elapsedTicks >= task.shrinkStartTick)
        {
            task.shrinking = true;
            task.shrinkElapsed = 0;
        }

        /* === 缩小阶段：更新 SHRINK_PROGRESS === */
        if (task.shrinking)
        {
            task.shrinkElapsed++;

            /* 非线性进度：ease-in quadratic
             * progress = shrinkElapsed / SHRINK_DURATION_TICKS
             * 实际缩小值 = progress^2（开始慢，后期快）
             * SHRINK_PROGRESS 表示消失进度（0=正常，1=完全消失） */
            float progress = (float) task.shrinkElapsed / (float) SHRINK_DURATION_TICKS;
            if (progress > 1.0F) progress = 1.0F;

            /* ease-in quadratic：开始缩小慢，后期加速 */
            float shrinkProgress = progress * progress;

            try
            {
                falling.method_5841().method_12778(FallingBlockRotationData.SHRINK_PROGRESS, shrinkProgress);
            }
            catch (Exception e)
            {
                /* 忽略 */
            }

            /* 缩小完成，discard 实体 */
            if (progress >= 1.0F)
            {
                /* 清理旋转物理状态 */
                RotatingFallingBlockManager.removePhysics(falling.method_5667());
                falling.method_31472();
                task.done = true;
            }
        }

        /* === 安全超时保护 ===
         * 如果超过了总时间 + 100 tick 还没完成，强制 discard */
        if (elapsedTicks > task.totalDurationTicks + 100)
        {
            RotatingFallingBlockManager.removePhysics(falling.method_5667());
            falling.method_31472();
            task.done = true;
        }
    }

    /**
     * 清除所有动画任务（服务器停止时调用）
     */
    public static void clearAll()
    {
        for (AnimationTask task : tasks)
        {
            try
            {
                net.minecraft.class_1297 entity = task.world.method_14190(task.entityUuid);
                if (entity != null && !entity.method_31481())
                {
                    entity.method_31472();
                }
            }
            catch (Exception e)
            {
                /* 忽略 */
            }
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
