package com.example.bbsanimatedbreak.actions;

import com.example.bbsanimatedbreak.BlockPathCurve;
import com.example.bbsanimatedbreak.BlockPathScheduler;
import com.example.bbsanimatedbreak.BlockSplashRecoveryManager;
import com.example.bbsanimatedbreak.RotatingFallingBlockManager;
import com.example.bbsanimatedbreak.actions.combo.IBlockFilterable;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.camera.values.ValuePositions;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueDouble;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.class_1309;
import net.minecraft.class_1540;
import net.minecraft.class_2338;
import net.minecraft.class_2680;
import net.minecraft.class_3218;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 方块路径运动 ActionClip（行为编辑器第四个效果）
 *
 * === 效果说明 ===
 * 区域内的所有方块变成 FallingBlockEntity，沿用户定义的 3D 曲线路径运动。
 * 支持多种插值算法生成丝滑曲线，支持循环运动和物理模拟（旋转+碰撞）。
 *
 * === 路径定义 ===
 * 用户在路径编辑器中添加标记点（右键点击世界方块），
 * 系统根据标记点用插值算法（Catmull-Rom/B样条/线性/三次）自动生成丝滑曲线。
 *
 * === 运动方式 ===
 * 所有方块沿相同的路径"位移"运动，保持各自的相对位置：
 *   方块当前位置 = 方块起始位置 + (路径当前位置 - 路径起点位置)
 *
 * === 物理模拟 ===
 * - enableRotation：方块运动时旋转，接近终点时平滑停止
 * - enableCollision：方块间碰撞检测，防止重叠
 *
 * === 自定义参数 ===
 * - speed：运动速度（每 tick 沿曲线前进的方块距离）
 * - loop：是否循环运动
 * - interpolation：插值类型（catmull_rom/b_spline/linear/cubic）
 * - enableRotation：是否启用旋转物理
 * - enableCollision：是否启用碰撞检测
 */
public class BlockPathActionClip extends ActionClip implements IBlockFilterable
{
    /* 方块过滤器（由飞溅组合设置，null 表示不过滤） */
    private Set<class_2338> blockFilter = null;
    /* 区域第一个对角点 */
    public final ValueInt x = new ValueInt("x", 0);
    public final ValueInt y = new ValueInt("y", 0);
    public final ValueInt z = new ValueInt("z", 0);

    /* 区域第二个对角点 */
    public final ValueInt x2 = new ValueInt("x2", 0);
    public final ValueInt y2 = new ValueInt("y2", 0);
    public final ValueInt z2 = new ValueInt("z2", 0);

    /* 运动速度（每 tick 沿曲线前进的方块距离，0.5=慢, 1=中, 3=快） */
    public final ValueDouble speed = new ValueDouble("speed", 1D, 0.1D, 10D);

    /* 是否循环运动（true=到终点后回到起点继续，false=到终点后变回方块） */
    public final ValueBoolean loop = new ValueBoolean("loop", false);

    /* 方块集中程度（0~1）
     * 0=离散：方块保持区域形状沿曲线位移运动
     * 1=集中：所有方块在同一条曲线上排队前进（贪吃蛇效果）
     * 中间值=线性过渡 */
    public final ValueDouble concentration = new ValueDouble("concentration", 0.5D, 0D, 1D);

    /* 分散程度（散落半径，0~20）
     * 控制离散模式下方块在曲线周围的散落范围
     * 0=不散落（完全跟随曲线）
     * 20=最大散落范围（方块散落在曲线周围 20 格内）
     * 实际散落半径 = (1 - concentration) * scatterRadius
     * concentration 越低 + scatterRadius 越大 = 越散乱的风暴效果 */
    public final ValueDouble scatterRadius = new ValueDouble("scatterRadius", 3D, 0D, 20D);

    /* 插值类型：catmull_rom=丝滑穿过所有点, b_spline=更平滑不穿过点, linear=直线, cubic=三次 */
    public final ValueString interpolation = new ValueString("interpolation", "catmull_rom");

    /* 是否启用旋转物理 */
    public final ValueBoolean enableRotation = new ValueBoolean("enableRotation", true);

    /* 是否启用碰撞检测（防止方块重叠） */
    public final ValueBoolean enableCollision = new ValueBoolean("enableCollision", true);

    /* 旋转平滑停止 */
    public final ValueBoolean smoothRotationStop = new ValueBoolean("smoothRotationStop", true);

    /* 旋转停止距离（离终点多远开始减速旋转） */
    public final ValueDouble rotationStopDistance = new ValueDouble("rotationStopDistance", 3D, 0.5D, 10D);

    /* 旋转归零时长（变回方块前的旋转归零持续 tick） */
    public final ValueInt rotationResetDuration = new ValueInt("rotationResetDuration", 40, 5, 200);

    /* 路径点列表（存储 Position，只用 point 部分） */
    public final ValuePositions pathPoints = new ValuePositions("pathPoints");

    public BlockPathActionClip()
    {
        super();

        this.add(this.x);
        this.add(this.y);
        this.add(this.z);
        this.add(this.x2);
        this.add(this.y2);
        this.add(this.z2);
        this.add(this.speed);
        this.add(this.loop);
        this.add(this.concentration);
        this.add(this.scatterRadius);
        this.add(this.interpolation);
        this.add(this.enableRotation);
        this.add(this.enableCollision);
        this.add(this.smoothRotationStop);
        this.add(this.rotationStopDistance);
        this.add(this.rotationResetDuration);
        this.add(this.pathPoints);
    }

    @Override
    public void applyAction(class_1309 entity, SuperFakePlayer fakePlayer, Film film, Replay replay, int tick)
    {
        if (fakePlayer == null || fakePlayer.method_37908() == null) return;
        if (!(fakePlayer.method_37908() instanceof class_3218)) return;

        class_3218 world = (class_3218) fakePlayer.method_37908();

        /* 收集路径点 */
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < this.pathPoints.size(); i++)
        {
            Position pos = this.pathPoints.get(i);
            points.add(new double[]{pos.point.x, pos.point.y, pos.point.z});
        }

        if (points.size() < 2)
        {
            return;
        }

        /* 收集区域内方块 */
        List<class_2338> blocks = this.collectBlocksInArea();
        if (blocks.isEmpty() || blocks.size() > 30000) return;

        String interpType = (String) this.interpolation.get();
        double speed = (Double) this.speed.get();
        boolean isLoop = (Boolean) this.loop.get();
        double concentration = (Double) this.concentration.get();
        double scatterRadius = (Double) this.scatterRadius.get();
        boolean rotation = (Boolean) this.enableRotation.get();
        boolean collision = (Boolean) this.enableCollision.get();
        boolean smoothStop = (Boolean) this.smoothRotationStop.get();
        double stopDist = (Double) this.rotationStopDistance.get();
        int resetDuration = (Integer) this.rotationResetDuration.get();

        int blockIndex = 0;
        for (class_2338 pos : blocks)
        {
            class_2680 state = world.method_8320(pos);
            if (state.method_26215() || state.method_26204().method_36555() < 0) continue;

            /* 记录原始方块状态（用于退出时恢复） */
            BlockSplashRecoveryManager.recordBlock(world, pos, state);

            /* 创建下落方块实体 */
            class_1540 falling = class_1540.method_40005(world, pos, state);
            BlockSplashRecoveryManager.recordEntity(world, falling);

            falling.field_7193 = false;
            falling.field_6037 = true;

            if (rotation)
            {
                RotatingFallingBlockManager.markRotating(falling, smoothStop, stopDist, resetDuration);
            }

            /* 通过调度器注册路径运动任务 */
            BlockPathScheduler.schedulePath(
                world, falling, pos, state,
                points, interpType, speed, isLoop, concentration, scatterRadius, blockIndex,
                rotation, collision, smoothStop, stopDist, resetDuration
            );
            blockIndex++;
        }
    }

    /**
     * 收集区域内的所有方块位置
     */
    private List<class_2338> collectBlocksInArea()
    {
        /* 如果有方块过滤器，直接返回过滤器中的方块（精确选区） */
        if (this.blockFilter != null && !this.blockFilter.isEmpty())
        {
            return new ArrayList<>(this.blockFilter);
        }

        List<class_2338> blocks = new ArrayList<>();

        int minX = Math.min((Integer) this.x.get(), (Integer) this.x2.get());
        int minY = Math.min((Integer) this.y.get(), (Integer) this.y2.get());
        int minZ = Math.min((Integer) this.z.get(), (Integer) this.z2.get());
        int maxX = Math.max((Integer) this.x.get(), (Integer) this.x2.get());
        int maxY = Math.max((Integer) this.y.get(), (Integer) this.y2.get());
        int maxZ = Math.max((Integer) this.z.get(), (Integer) this.z2.get());

        for (int bx = minX; bx <= maxX; bx++)
        {
            for (int by = minY; by <= maxY; by++)
            {
                for (int bz = minZ; bz <= maxZ; bz++)
                {
                    blocks.add(new class_2338(bx, by, bz));
                }
            }
        }

        return blocks;
    }

    /* === IBlockFilterable 实现 === */

    @Override
    public void setBlockFilter(Set<class_2338> filter)
    {
        this.blockFilter = filter;
    }

    @Override
    public Set<class_2338> getBlockFilter()
    {
        return this.blockFilter;
    }

    @Override
    public void shift(double dx, double dy, double dz)
    {
        this.x.set(this.x.get() + (int) dx);
        this.y.set(this.y.get() + (int) dy);
        this.z.set(this.z.get() + (int) dz);
        this.x2.set(this.x2.get() + (int) dx);
        this.y2.set(this.y2.get() + (int) dy);
        this.z2.set(this.z2.get() + (int) dz);
    }

    @Override
    protected Clip create()
    {
        return new BlockPathActionClip();
    }
}
