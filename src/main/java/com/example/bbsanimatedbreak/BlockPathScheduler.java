package com.example.bbsanimatedbreak;

import com.example.bbsanimatedbreak.mixin.EntityAccessor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.class_1540;
import net.minecraft.class_2338;
import net.minecraft.class_2680;
import net.minecraft.class_3218;

/**
 * 方块路径运动调度器
 *
 * 驱动 FallingBlockEntity 沿 3D 曲线路径运动。
 *
 * === 离散程度（concentration）===
 * concentration = 1（集中）：贪吃蛇效果，方块紧挨着排队沿曲线前进
 * concentration = 0（离散）：风暴效果，方块沿曲线运动但散落在曲线周围
 *   - 每个方块有自己的随机偏移角度和半径
 *   - 偏移随时间旋转（风暴旋转效果）
 *   - 方块整体仍沿曲线移动
 *
 * === 165Hz 流畅度 ===
 * 使用 setPositionWithPrev 手动管理 prevPos，确保高刷新率下渲染插值连续。
 * 路径运动方块跳过 mixin 物理处理（pathMovement=true），避免抖动。
 *
 * === 物理模拟 ===
 * - 旋转：复用 RotatingFallingBlockManager，但跳过弹跳/摩擦
 * - 碰撞：通过 noClip 控制（enableCollision=true 时启用原版碰撞）
 */
public class BlockPathScheduler
{
    private static final CopyOnWriteArrayList<PathTask> tasks = new CopyOnWriteArrayList<>();

    /**
     * 注册一个路径运动任务
     */
    public static void schedulePath(
        class_3218 world, class_1540 falling, class_2338 originalPos, class_2680 originalState,
        List<double[]> pathPoints, String interpolation, double speed, boolean loop,
        double concentration, double scatterRadius, int queueIndex,
        boolean enableRotation, boolean enableCollision,
        boolean smoothRotationStop, double rotationStopDistance, int rotationResetDuration
    )
    {
        PathTask task = new PathTask();
        task.world = world;
        task.entityUuid = falling.method_5667();
        task.entity = falling;
        task.originalPos = originalPos;
        task.originalState = originalState;

        /* 方块起始位置（实体坐标，Y 是底面） */
        task.startX = falling.method_23317();
        task.startY = falling.method_23318();
        task.startZ = falling.method_23321();

        /* 路径数据 */
        task.pathPoints = new ArrayList<>(pathPoints);
        task.interpolation = interpolation;
        task.speed = speed;
        task.loop = loop;
        task.concentration = concentration;
        task.scatterRadius = scatterRadius;
        task.queueIndex = queueIndex;
        task.curveLength = BlockPathCurve.getCurveLength(pathPoints, interpolation);

        /* === 计算排队偏移 ===
         * concentration = 1（集中）：间距 = 1 方块距离，贪吃蛇效果
         * concentration = 0（离散）：间距 = 0，方块散落在曲线周围（风暴效果）
         */
        double spacing = 1.0;
        task.queueOffset = queueIndex * spacing * concentration;

        /* === 离散模式的随机偏移 ===
         * concentration 越低，离散半径越大（最大为 scatterRadius）
         * 每个方块有自己的随机角度和高度偏移 */
        double actualScatterRadius = (1.0 - concentration) * scatterRadius;
        task.scatterAngle = (queueIndex * 2.39996) % (Math.PI * 2); /* 黄金角分布 */
        task.scatterRadius = actualScatterRadius;
        task.scatterHeight = ((queueIndex * 7) % 100 / 100.0 - 0.5) * actualScatterRadius * 0.5;

        /* 路径起点 */
        task.pathStartX = pathPoints.get(0)[0];
        task.pathStartY = pathPoints.get(0)[1];
        task.pathStartZ = pathPoints.get(0)[2];

        /* 初始化 */
        task.currentDistance = 0;
        task.currentTick = 0;
        task.done = false;

        /* 物理参数 */
        task.enableRotation = enableRotation;
        task.enableCollision = enableCollision;
        task.smoothRotationStop = smoothRotationStop;
        task.rotationStopDistance = rotationStopDistance;
        task.rotationResetDuration = rotationResetDuration;

        /* 设置实体属性 */
        falling.method_5875(true);
        falling.field_5960 = !enableCollision;
        falling.method_18800(0, 0, 0);
        falling.field_6037 = true;

        /* 标记为路径运动方块（mixin 跳过物理处理，避免抖动） */
        RotatingFallingBlockManager.PhysicsState state = RotatingFallingBlockManager.getPhysicsState(falling.method_5667());
        if (state != null)
        {
            state.pathMovement = true;
            state.disableCollision = true; /* 路径运动期间禁用方块间碰撞，避免推开 */
        }

        /* === 设置 PATH_MOVEMENT DataTracker 标志 ===
         * 客户端 mixin 读取此标志，跳过 tick() 防止 prevPos = pos 破坏渲染插值 */
        try
        {
            falling.method_5841().method_12778(FallingBlockRotationData.PATH_MOVEMENT, true);
        }
        catch (Exception e)
        {
            /* 忽略 */
        }

        tasks.add(task);
    }

    /**
     * 每 tick 调用，驱动所有路径运动任务
     */
    public static void tick()
    {
        if (tasks.isEmpty()) return;

        Iterator<PathTask> it = tasks.iterator();
        while (it.hasNext())
        {
            PathTask task = it.next();
            if (task.done)
            {
                tasks.remove(task);
                continue;
            }

            updateTask(task);
        }
    }

    private static void updateTask(PathTask task)
    {
        class_1540 falling = task.entity;
        if (falling == null || falling.method_31481())
        {
            task.done = true;
            return;
        }

        task.currentTick++;

        /* === 计算方块在曲线上的进度 ===
         *
         * concentration = 1（集中）：贪吃蛇效果，方块紧挨着排队沿曲线前进
         * concentration = 0（离散）：风暴效果，方块沿曲线运动但散落在曲线周围
         */
        double queueOffset = task.queueOffset;
        double totalDistance = task.currentDistance + queueOffset;
        double t = 0;
        if (task.curveLength > 0)
        {
            t = totalDistance / task.curveLength;
        }

        if (t >= 1.0)
        {
            if (task.loop)
            {
                t = t % 1.0;
            }
            else
            {
                finishTask(task);
                return;
            }
        }

        /* 计算路径在 t 处的位置 */
        double[] pathPos = BlockPathCurve.interpolate(task.pathPoints, t, task.interpolation);

        double currentX = pathPos[0];
        double currentY = pathPos[1];
        double currentZ = pathPos[2];

        /* === 离散模式：在曲线周围添加旋转偏移（风暴效果） ===
         * concentration 越低，偏移越大
         * 偏移随时间旋转，形成风暴旋转效果 */
        if (task.scatterRadius > 0.01)
        {
            /* 风暴旋转角度随时间变化 */
            double stormAngle = task.scatterAngle + task.currentTick * 0.15;

            double offsetX = Math.cos(stormAngle) * task.scatterRadius;
            double offsetZ = Math.sin(stormAngle) * task.scatterRadius;
            double offsetY = task.scatterHeight + Math.sin(task.currentTick * 0.1 + task.scatterAngle) * 0.3;

            currentX += offsetX;
            currentY += offsetY;
            currentZ += offsetZ;
        }

        /* 设置位置并同步 prevPos（165Hz 流畅度） */
        setPositionWithPrev(falling, currentX, currentY, currentZ);
        falling.method_18800(0, 0, 0);
        falling.field_6037 = true;
        falling.method_5875(true);
        falling.field_5960 = !task.enableCollision;

        /* 更新旋转物理 */
        if (task.enableRotation)
        {
            updateRotation(task, t);
        }

        /* 推进距离 */
        task.currentDistance += task.speed;
    }

    /**
     * 更新旋转物理
     */
    private static void updateRotation(PathTask task, double t)
    {
        class_1540 falling = task.entity;
        if (falling == null) return;

        RotatingFallingBlockManager.PhysicsState state = RotatingFallingBlockManager.getPhysicsState(falling.method_5667());
        if (state == null) return;

        /* 接近终点时减速旋转 */
        double remaining = 1.0 - t;
        if (remaining < 0.15 && !task.loop)
        {
            /* 接近终点：减速旋转 */
            double factor = remaining / 0.15;
            state.angularVelocityX *= factor;
            state.angularVelocityY *= factor;
            state.angularVelocityZ *= factor;

            if (remaining < 0.05)
            {
                state.angularVelocityX = 0;
                state.angularVelocityY = 0;
                state.angularVelocityZ = 0;
            }
        }

        /* 同步到 DataTracker */
        try
        {
            falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_X, (float) state.angularVelocityX);
            falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Y, (float) state.angularVelocityY);
            falling.method_5841().method_12778(FallingBlockRotationData.ANGULAR_VEL_Z, (float) state.angularVelocityZ);
        }
        catch (Exception e)
        {
            /* 忽略 */
        }
    }

    /**
     * 任务完成：在终点位置变回方块
     */
    private static void finishTask(PathTask task)
    {
        class_1540 falling = task.entity;
        if (falling == null)
        {
            task.done = true;
            return;
        }

        /* 计算终点位置（曲线终点） */
        double[] endPathPos = BlockPathCurve.interpolate(task.pathPoints, 1.0, task.interpolation);

        double finalX = endPathPos[0];
        double finalY = endPathPos[1];
        double finalZ = endPathPos[2];

        /* 归零旋转 */
        if (task.enableRotation)
        {
            forceZeroRotation(falling);
        }

        /* 在终点位置放置方块 */
        class_2338 placePos = new class_2338(
            (int) Math.floor(finalX),
            (int) Math.floor(finalY),
            (int) Math.floor(finalZ)
        );

        try
        {
            task.world.method_8501(placePos, task.originalState);
        }
        catch (Exception e)
        {
            /* 忽略放置错误 */
        }

        /* 移除实体 */
        falling.method_31472();
        task.done = true;
    }

    /**
     * 强制归零旋转
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
            /* 忽略 */
        }

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
     */
    private static void setPositionWithPrev(class_1540 falling, double x, double y, double z)
    {
        EntityAccessor accessor = (EntityAccessor) falling;
        accessor.setPrevX(falling.method_23317());
        accessor.setPrevY(falling.method_23318());
        accessor.setPrevZ(falling.method_23321());
        falling.method_5814(x, y, z);
    }

    /**
     * 清除所有任务（服务器停止时调用）
     */
    public static void clearAll()
    {
        tasks.clear();
    }

    /**
     * 路径运动任务
     */
    public static class PathTask
    {
        public class_3218 world;
        public UUID entityUuid;
        public class_1540 entity;
        public class_2338 originalPos;
        public class_2680 originalState;

        /* 方块起始位置（实体坐标） */
        public double startX, startY, startZ;

        /* 路径数据 */
        public List<double[]> pathPoints;
        public String interpolation;
        public double speed;
        public boolean loop;
        public double concentration;
        public double scatterRange; /* 用户配置的分散程度（最大散落半径） */
        public int queueIndex;
        public double queueOffset; /* 沿曲线的距离偏移（由 concentration 和 queueIndex 计算） */
        public double curveLength;

        /* 离散模式参数（风暴效果） */
        public double scatterAngle;  /* 方块在风暴中的初始角度 */
        public double scatterRadius; /* 实际离散半径（concentration 越低越大） */
        public double scatterHeight; /* 高度偏移 */

        /* 路径起点 */
        public double pathStartX, pathStartY, pathStartZ;

        /* 运动状态 */
        public double currentDistance;
        public int currentTick;
        public boolean done;

        /* 物理参数 */
        public boolean enableRotation;
        public boolean enableCollision;
        public boolean smoothRotationStop;
        public double rotationStopDistance;
        public int rotationResetDuration;
    }
}
