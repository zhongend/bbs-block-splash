package com.example.bbsanimatedbreak.actions;

import com.example.bbsanimatedbreak.BlockSplashRecoveryManager;
import com.example.bbsanimatedbreak.BlockSplashReverseScheduler;
import com.example.bbsanimatedbreak.actions.combo.IBlockFilterable;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueDouble;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.class_1309;
import net.minecraft.class_2338;
import net.minecraft.class_2680;
import net.minecraft.class_3218;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 方块飞溅（反向版本）ActionClip
 *
 * === 效果说明 ===
 * 效果一开始方块就是已散落状态（不显示飞溅散开动画），
 * 然后这些散落的方块逐渐飞回原位，恢复成完整方块（自动建造效果）。
 *
 * === 两阶段流程 ===
 * 阶段1 - 散落状态：方块已变成 FallingBlockEntity 散落在空中，等待恢复
 * 阶段2 - 恢复阶段：每个方块按延迟顺序飞回原位变回方块
 *   - 随机模式（randomSplash=true）：方块散落在随机位置，从散落位置飞回原位
 *   - 集中模式（randomSplash=false）：所有方块从指定坐标飞向原位（建造效果）
 *
 * === 恢复顺序 ===
 * - near_to_far：从离参考点最近的方块开始恢复（波浪式建造）
 * - far_to_near：从离参考点最远的方块开始恢复
 * - random：随机顺序恢复
 *
 * === 自定义参数 ===
 * - scatterRadius：散落半径（随机模式下方块散落多远，3=近, 6=中, 10=远）
 * - recoverySpeed：恢复速度（方块飞回原位的速度）
 * - recoveryDelay：恢复延迟（每个方块恢复的间隔 tick，形成建造波浪）
 * - recoveryOrder：恢复顺序
 * - randomSplash：是否随机散落
 *   - true：方块随机散落在空中（散落半径控制距离）
 *   - false：方块集中在指定坐标（从该坐标飞向原位，建造效果）
 * - concentrateX/Y/Z：集中坐标（仅 randomSplash=false 时使用）
 * - enableRotation：是否启用旋转物理
 */
public class BlockSplashReverseActionClip extends ActionClip implements IBlockFilterable
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

    /* 散落半径（随机模式下方块散落多远，3=近, 6=中, 10=远） */
    public final ValueDouble scatterRadius = new ValueDouble("scatterRadius", 6D, 1D, 20D);

    /* 恢复速度（方块飞回原位的速度，0.6=慢, 1.5=中, 2.5=快） */
    public final ValueDouble recoverySpeed = new ValueDouble("recoverySpeed", 2.5D, 0.1D, 5D);

    /* 恢复延迟（每个方块恢复的间隔 tick，形成建造波浪，0=同时, 2=波浪, 5=明显波浪） */
    public final ValueInt recoveryDelay = new ValueInt("recoveryDelay", 2, 0, 20);

    /* 恢复顺序：near_to_far=由近及远, far_to_near=由远及近, random=随机 */
    public final ValueString recoveryOrder = new ValueString("recoveryOrder", "near_to_far");

    /* 是否随机散落（true=随机散落, false=从集中坐标飞出建造） */
    public final ValueBoolean randomSplash = new ValueBoolean("randomSplash", true);

    /* 集中坐标 X/Y/Z（仅 randomSplash=false 时使用，方块从此坐标飞向原位） */
    public final ValueInt concentrateX = new ValueInt("concentrateX", 0);
    public final ValueInt concentrateY = new ValueInt("concentrateY", 0);
    public final ValueInt concentrateZ = new ValueInt("concentrateZ", 0);

    /* 是否启用旋转物理（恢复时方块会旋转） */
    public final ValueBoolean enableRotation = new ValueBoolean("enableRotation", true);

    /* 归位旋转平滑过渡（true=接近原位时旋转逐渐减速并归零，避免角度闪现） */
    public final ValueBoolean smoothRotationStop = new ValueBoolean("smoothRotationStop", true);

    /* 旋转减速距离（在距离原位多远开始减速旋转，1.5=近, 3=中, 5=远） */
    public final ValueDouble rotationStopDistance = new ValueDouble("rotationStopDistance", 3D, 0.5D, 10D);

    /* 归零动画持续时间（tick，方块悬停在原位附近归零角度的时间，40=2秒，默认值足以修复角度闪现） */
    public final ValueInt rotationResetDuration = new ValueInt("rotationResetDuration", 40, 5, 200);

    /* 恢复时暂停方块间碰撞模拟（true=多个方块飞回原位时不会互相推开，避免错位；false=保留物理碰撞） */
    public final ValueBoolean disableCollisionDuringRecovery = new ValueBoolean("disableCollisionDuringRecovery", true);

    /* 保留动画方块时长（tick，方块到达原位后保留动画方块存在的时间，期间恢复方块实体）
       关键修复闪烁：保留时间内动画方块和实体方块重叠，最后动画方块消失，不会出现空档闪烁 */
    public final ValueInt animationKeepDuration = new ValueInt("animationKeepDuration", 60, 5, 200);

    public BlockSplashReverseActionClip()
    {
        super();

        this.add(this.x);
        this.add(this.y);
        this.add(this.z);
        this.add(this.x2);
        this.add(this.y2);
        this.add(this.z2);
        this.add(this.scatterRadius);
        this.add(this.recoverySpeed);
        this.add(this.recoveryDelay);
        this.add(this.recoveryOrder);
        this.add(this.randomSplash);
        this.add(this.concentrateX);
        this.add(this.concentrateY);
        this.add(this.concentrateZ);
        this.add(this.enableRotation);
        this.add(this.smoothRotationStop);
        this.add(this.rotationStopDistance);
        this.add(this.rotationResetDuration);
        this.add(this.disableCollisionDuringRecovery);
        this.add(this.animationKeepDuration);
    }

    @Override
    public void applyAction(class_1309 entity, SuperFakePlayer fakePlayer, Film film, Replay replay, int tick)
    {
        if (fakePlayer == null || fakePlayer.method_37908() == null)
        {
            return;
        }

        if (!(fakePlayer.method_37908() instanceof class_3218))
        {
            return;
        }

        class_3218 world = (class_3218) fakePlayer.method_37908();

        // 收集区域内所有方块坐标
        List<class_2338> blocks = this.collectBlocksInArea(
            this.x.get(), this.y.get(), this.z.get(),
            this.x2.get(), this.y2.get(), this.z2.get()
        );

        // 限制区域大小（最多 30000 个方块）
        if (blocks.isEmpty() || blocks.size() > 30000)
        {
            return;
        }

        double scatterRadius = this.scatterRadius.get().doubleValue();
        double recoverySpeed = this.recoverySpeed.get().doubleValue();
        int recoveryDelay = this.recoveryDelay.get();
        String recoveryOrder = (String) this.recoveryOrder.get();
        boolean isRandom = (Boolean) this.randomSplash.get();
        boolean enableRotation = (Boolean) this.enableRotation.get();
        boolean smoothRotationStop = (Boolean) this.smoothRotationStop.get();
        double rotationStopDistance = this.rotationStopDistance.get().doubleValue();
        int rotationResetDuration = this.rotationResetDuration.get();
        boolean disableCollisionDuringRecovery = (Boolean) this.disableCollisionDuringRecovery.get();
        int animationKeepDuration = this.animationKeepDuration.get();

        // 集中坐标（仅非随机模式使用）
        class_2338 concentratePos = new class_2338(
            this.concentrateX.get(),
            this.concentrateY.get(),
            this.concentrateZ.get()
        );

        // 计算恢复排序的参考点
        // 随机模式：参考点为区域中心
        // 集中模式：参考点为集中坐标
        double refX, refY, refZ;
        if (isRandom)
        {
            refX = 0; refY = 0; refZ = 0;
            for (class_2338 p : blocks)
            {
                refX += p.method_10263();
                refY += p.method_10264();
                refZ += p.method_10260();
            }
            refX /= blocks.size();
            refY /= blocks.size();
            refZ /= blocks.size();
        }
        else
        {
            refX = concentratePos.method_10263();
            refY = concentratePos.method_10264();
            refZ = concentratePos.method_10260();
        }

        // 收集要散落的方块
        List<ReverseBlock> reverseBlocks = new ArrayList<>();
        Random random = new Random(42L);

        for (class_2338 pos : blocks)
        {
            class_2680 state = world.method_8320(pos);

            if (state.method_26215() || state.method_26204().method_36555() < 0)
            {
                continue;
            }

            // 计算到参考点的距离（用于恢复排序）
            double distance = Math.sqrt(
                Math.pow(pos.method_10263() - refX, 2) +
                Math.pow(pos.method_10264() - refY, 2) +
                Math.pow(pos.method_10260() - refZ, 2)
            );

            reverseBlocks.add(new ReverseBlock(pos, state, distance));
        }

        // 按恢复顺序排序
        this.sortByRecoveryOrder(reverseBlocks, recoveryOrder, random);

        // === 通过调度器注册散落+恢复任务 ===
        // 效果一开始方块就是已散落状态，调度器会立即：
        // 1. 移除原位方块
        // 2. 在散落位置创建 FallingBlockEntity（静止悬浮）
        // 3. 等待 recoveryDelay 后开始飞回原位
        int index = 0;
        for (ReverseBlock rb : reverseBlocks)
        {
            // 计算每个方块的恢复延迟（形成建造波浪）
            int recoveryDelayTick = recoveryDelay * index;

            // 记录原始方块状态（用于退出时恢复）
            BlockSplashRecoveryManager.recordBlock(world, rb.pos, rb.state);

            // 调度散落+恢复任务
            BlockSplashReverseScheduler.scheduleReverse(
                world,
                rb.pos,
                rb.state,
                scatterRadius,
                recoverySpeed,
                recoveryDelayTick,
                isRandom,
                concentratePos,
                enableRotation,
                smoothRotationStop,
                rotationStopDistance,
                rotationResetDuration,
                disableCollisionDuringRecovery,
                animationKeepDuration
            );

            index++;
        }
    }

    /**
     * 按恢复顺序排序方块列表
     */
    private void sortByRecoveryOrder(List<ReverseBlock> blocks, String order, Random random)
    {
        switch (order)
        {
            case "far_to_near":
                blocks.sort(Comparator.comparingDouble((ReverseBlock rb) -> rb.distance).reversed());
                break;

            case "random":
                blocks.sort(Comparator.comparingInt(rb -> random.nextInt()));
                break;

            case "near_to_far":
            default:
                blocks.sort(Comparator.comparingDouble(rb -> rb.distance));
                break;
        }
    }

    /**
     * 收集区域内所有方块坐标
     */
    private List<class_2338> collectBlocksInArea(int x1, int y1, int z1, int x2, int y2, int z2)
    {
        /* 如果有方块过滤器，直接返回过滤器中的方块（精确选区） */
        if (this.blockFilter != null && !this.blockFilter.isEmpty())
        {
            return new ArrayList<>(this.blockFilter);
        }

        int minX = Math.min(x1, x2);
        int minY = Math.min(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2);
        int maxY = Math.max(y1, y2);
        int maxZ = Math.max(z1, z2);

        List<class_2338> blocks = new ArrayList<>();

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

    /**
     * 反向飞溅方块数据结构
     */
    private static class ReverseBlock
    {
        final class_2338 pos;
        final class_2680 state;
        final double distance;

        ReverseBlock(class_2338 pos, class_2680 state, double distance)
        {
            this.pos = pos;
            this.state = state;
            this.distance = distance;
        }
    }

    @Override
    public void shift(double dx, double dy, double dz)
    {
        super.shift(dx, dy, dz);

        this.x.set((int) (this.x.get() + dx));
        this.y.set((int) (this.y.get() + dy));
        this.z.set((int) (this.z.get() + dz));
        this.x2.set((int) (this.x2.get() + dx));
        this.y2.set((int) (this.y2.get() + dy));
        this.z2.set((int) (this.z2.get() + dz));
        this.concentrateX.set((int) (this.concentrateX.get() + dx));
        this.concentrateY.set((int) (this.concentrateY.get() + dy));
        this.concentrateZ.set((int) (this.concentrateZ.get() + dz));
    }

    @Override
    protected Clip create()
    {
        return new BlockSplashReverseActionClip();
    }
}