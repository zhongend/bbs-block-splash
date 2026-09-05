package com.example.bbsanimatedbreak.actions;

import com.example.bbsanimatedbreak.BlockShockwaveScheduler;
import com.example.bbsanimatedbreak.BlockSplashRecoveryManager;
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
import java.util.Set;

/**
 * 方块振波 ActionClip
 *
 * 像冲击波一样从中心向外扩散震动，方块原地上下震动（不移动位置）。
 *
 * === 振波模式 ===
 * - circle（圆形振波）：欧几里得距离，圆形扩散
 * - rectangle（长方形振波）：切比雪夫距离，矩形扩散
 * - cross（十字振波）：只沿 X/Z 轴十字扩散
 * - diamond（菱形振波）：曼哈顿距离，菱形扩散
 * - earthquake（模拟地震）：持续随机震动，幅度随机波动不衰减，震动间隔减半
 * - ripple（涟漪波纹）：幅度按正弦波变化，像水波纹起伏
 * - chaos（混沌乱震）：完全随机方向和力度，震动间隔随机
 *
 * === 震动行为（关键改进） ===
 * 方块只原地上下震动，不移动水平位置：
 * 1. 方块按距离中心的远近，通过调度器分批延迟震动（真正的波浪传播）
 * 2. 每个方块震动时向上跳起，受重力下落
 * 3. 落地后变回原来的方块（回到原位）
 * 4. 不再有水平扩散力，方块不会飞走
 *
 * === 自定义参数 ===
 * - amplitude：震动幅度（跳起高度）
 * - waveSpeed：波浪传播速度（每 tick 前进多少格）
 * - shakeDuration：单个方块震动持续时间（tick）
 * - shakeFrequency：震动频率（震动期间跳起次数）
 * - decay：震动衰减（每次跳起高度衰减比例）
 * - restoreAfter：震动后恢复原状（方块回到原位）
 * - fromCenter：是否从中心点开始震动
 *   - true：从区域中心向外扩散（原行为）
 *   - false：从指定方向的边开始震动（需配合 direction 使用）
 * - direction：震动起始方向（仅 fromCenter=false 时生效）
 *   - north（北, -Z）：从最北边（minZ）开始向南扩散
 *   - south（南, +Z）：从最南边（maxZ）开始向北扩散
 *   - east（东, +X）：从最东边（maxX）开始向西扩散
 *   - west（西, -X）：从最西边（minX）开始向东扩散
 */
public class BlockShockwaveActionClip extends ActionClip implements IBlockFilterable
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

    /* 震动幅度（控制方块跳起高度，0.2=轻微, 0.5=中等, 1.0=猛烈） */
    public final ValueDouble amplitude = new ValueDouble("amplitude", 0.5D, 0D, 2D);

    /* 波浪传播速度（每 tick 波浪前进的距离，0.5=慢, 1.0=中, 2.0=快） */
    public final ValueDouble waveSpeed = new ValueDouble("waveSpeed", 1.0D, 0.1D, 5D);

    /* 单个方块震动持续时间（tick，10=短, 30=中, 60=长） */
    public final ValueInt shakeDuration = new ValueInt("shakeDuration", 20, 1, 100);

    /* 震动频率（震动期间跳起次数，1=单次回弹, 2=两次, 3=多次）
     * 默认 1：只回弹一次（避免多余回弹） */
    public final ValueInt shakeFrequency = new ValueInt("shakeFrequency", 1, 1, 10);

    /* 震动衰减（每次跳起高度衰减比例，0.3=快速衰减, 0.6=中, 0.9=缓慢衰减） */
    public final ValueDouble decay = new ValueDouble("decay", 0.6D, 0.1D, 0.95D);

    /* 振波模式：circle=圆形, rectangle=长方形, cross=十字, diamond=菱形 */
    public final ValueString mode = new ValueString("mode", "circle");

    /* 是否从中心点开始震动（true=从中心, false=从指定方向开始） */
    public final ValueBoolean fromCenter = new ValueBoolean("fromCenter", true);

    /* 震动起始方向：north=北, south=南, east=东, west=西（仅 fromCenter=false 时生效） */
    public final ValueString direction = new ValueString("direction", "north");

    /* 震动后是否恢复原状（方块回到原位）
     * 默认 false：震动结束后方块保持当前状态（落地冻结的实体保留倾斜角度，形成永久冲击坑） */
    public final ValueBoolean restoreAfter = new ValueBoolean("restoreAfter", false);

    /* 真实化角度（true=方块随冲击波力度倾斜，形成冲击坑效果，像小行星撞击地面）
     * 默认 true：开启后方块跳起时按力度倾斜，越靠近震源中心倾斜越大
     * 落地后保持倾斜角度（形成坑洼地貌），震动恢复原状时角度归零 */
    public final ValueBoolean realisticAngle = new ValueBoolean("realisticAngle", true);

    /* 冲击力度（仅在 realisticAngle=true 时生效）
     * 控制方块倾斜角度的剧烈程度，1.0=轻度倾斜(约15度), 2.0=中度(约30度), 3.0=猛烈(约45度)
     * 越靠近震源中心的方块倾斜越大，越远的方块倾斜越小 */
    public final ValueDouble impactForce = new ValueDouble("impactForce", 1.0D, 0.1D, 5.0D);

    public BlockShockwaveActionClip()
    {
        super();

        this.add(this.x);
        this.add(this.y);
        this.add(this.z);
        this.add(this.x2);
        this.add(this.y2);
        this.add(this.z2);
        this.add(this.amplitude);
        this.add(this.waveSpeed);
        this.add(this.shakeDuration);
        this.add(this.shakeFrequency);
        this.add(this.decay);
        this.add(this.mode);
        this.add(this.fromCenter);
        this.add(this.direction);
        this.add(this.restoreAfter);
        this.add(this.realisticAngle);
        this.add(this.impactForce);
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

        if (blocks.isEmpty() || blocks.size() > 30000)
        {
            return;
        }

        double amplitude = this.amplitude.get().doubleValue();
        double waveSpeed = this.waveSpeed.get().doubleValue();
        int shakeDuration = this.shakeDuration.get();
        int shakeFrequency = this.shakeFrequency.get();
        double decay = this.decay.get().doubleValue();
        String shockwaveMode = (String) this.mode.get();
        boolean shouldRestore = (Boolean) this.restoreAfter.get();
        boolean useCenter = (Boolean) this.fromCenter.get();
        String direction = (String) this.direction.get();
        boolean useRealisticAngle = (Boolean) this.realisticAngle.get();
        double impactForce = this.impactForce.get().doubleValue();

        // 计算区域边界
        int minX = Math.min(this.x.get(), this.x2.get());
        int minY = Math.min(this.y.get(), this.y2.get());
        int minZ = Math.min(this.z.get(), this.z2.get());
        int maxX = Math.max(this.x.get(), this.x2.get());
        int maxY = Math.max(this.y.get(), this.y2.get());
        int maxZ = Math.max(this.z.get(), this.z2.get());

        // 计算区域中心点（用于波浪扩散和角度倾斜方向计算）
        // 即使 fromCenter=false（从方向开始震动），也用区域中心作为震源
        // 用于计算"方块到震源的距离"决定倾斜程度
        double centerX = 0, centerY = 0, centerZ = 0;
        for (class_2338 p : blocks)
        {
            centerX += p.method_10263();
            centerY += p.method_10264();
            centerZ += p.method_10260();
        }
        centerX /= blocks.size();
        centerY /= blocks.size();
        centerZ /= blocks.size();

        // 计算区域最大半径（中心到最远方块的距离，用于角度倾斜的距离衰减归一化）
        double maxDistance = 0;
        for (class_2338 p : blocks)
        {
            double dx = p.method_10263() - centerX;
            double dy = p.method_10264() - centerY;
            double dz = p.method_10260() - centerZ;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > maxDistance) maxDistance = d;
        }
        if (maxDistance < 1.0) maxDistance = 1.0; // 避免除零

        // 收集要震动的方块，计算每个方块的震动延迟距离
        List<WaveBlock> waveBlocks = new ArrayList<>();

        for (class_2338 pos : blocks)
        {
            class_2680 state = world.method_8320(pos);

            if (state.method_26215() || state.method_26204().method_36555() < 0)
            {
                continue;
            }

            double distance;

            if (useCenter)
            {
                // 从中心点开始震动：使用中心距离
                distance = this.calculateDistanceFromCenter(pos, centerX, centerY, centerZ, shockwaveMode);

                // 十字振波：只保留轴线上的方块
                if ("cross".equals(shockwaveMode))
                {
                    int dx = pos.method_10263() - (int) Math.round(centerX);
                    int dz = pos.method_10260() - (int) Math.round(centerZ);
                    if (Math.abs(dx) > 0 && Math.abs(dz) > 0)
                    {
                        continue;
                    }
                }
            }
            else
            {
                // 从指定方向开始震动：使用方向距离
                distance = this.calculateDistanceFromDirection(pos, direction, minX, maxX, minZ, maxZ);

                // 十字振波 + 方向：只保留沿该方向轴的方块
                if ("cross".equals(shockwaveMode))
                {
                    if ("north".equals(direction) || "south".equals(direction))
                    {
                        // 沿 Z 轴：只保留 X 等于区域中心 X 的方块
                        int centerAxisX = (minX + maxX) / 2;
                        if (pos.method_10263() != centerAxisX)
                        {
                            continue;
                        }
                    }
                    else // east / west
                    {
                        // 沿 X 轴：只保留 Z 等于区域中心 Z 的方块
                        int centerAxisZ = (minZ + maxZ) / 2;
                        if (pos.method_10260() != centerAxisZ)
                        {
                            continue;
                        }
                    }
                }
            }

            waveBlocks.add(new WaveBlock(pos, state, distance));
        }

        // 按距离排序（从近到远，形成从起点向外的波浪）
        waveBlocks.sort(Comparator.comparingDouble(wb -> wb.distance));

        // === 通过调度器分批延迟震动（真正的波浪传播） ===
        // 距离越远的方块，震动开始时间越晚
        // delay = distance / waveSpeed（tick）
        // 调度器会在指定 tick 后执行震动
        for (WaveBlock wb : waveBlocks)
        {
            // 计算震动延迟（tick）：距离越远延迟越大
            int delay = (int) Math.round(wb.distance / Math.max(0.1, waveSpeed));

            // === 无条件记录原始方块状态（用于退出时恢复） ===
            // 关键修复：不管 shouldRestore 是 true 还是 false，都记录方块原始状态。
            // 之前只在 shouldRestore=true 时记录，但 restoreAfter 默认为 false，
            // 导致回放途中退出游戏时，原位置方块被设为空气后无法恢复（地形永久破坏）。
            // 现在无条件记录，退出时 BlockSplashRecoveryManager.restoreAll() 会恢复所有方块。
            // 即使 shouldRestore=false 保留倾斜冲击坑效果，退出时也会恢复地形（保护存档）。
            BlockSplashRecoveryManager.recordBlock(world, wb.pos, wb.state);

            // 调度震动任务
            BlockShockwaveScheduler.scheduleShake(
                world,
                wb.pos,
                wb.state,
                delay,
                amplitude,
                shakeDuration,
                shakeFrequency,
                decay,
                shouldRestore,
                useRealisticAngle,
                impactForce,
                centerX, centerY, centerZ,
                maxDistance,
                useCenter,
                direction,
                shockwaveMode
            );
        }
    }

    /**
     * 从中心点开始震动时，根据振波模式计算方块到中心的距离
     *
     * 模式说明：
     * - circle/earthquake/ripple/chaos：欧几里得距离，圆形扩散
     *   earthquake/ripple/chaos 复用 circle 的距离计算，但在 Scheduler 中有不同震动行为
     * - rectangle：切比雪夫距离，矩形扩散
     * - diamond：曼哈顿距离，菱形扩散
     * - cross：欧几里得距离 + 只保留轴线方块（在调用前过滤）
     */
    private double calculateDistanceFromCenter(class_2338 pos, double centerX, double centerY, double centerZ, String mode)
    {
        double dx = pos.method_10263() - centerX;
        double dy = pos.method_10264() - centerY;
        double dz = pos.method_10260() - centerZ;

        switch (mode)
        {
            case "rectangle":
                return Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));

            case "diamond":
                return Math.abs(dx) + Math.abs(dy) + Math.abs(dz);

            case "cross":
            case "earthquake":
            case "ripple":
            case "chaos":
            case "circle":
            default:
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * 从指定方向开始震动时，计算方块到该方向起始边的距离
     *
     * Minecraft 坐标系：
     * - north（北, -Z 方向）：起始边是 minZ，向南（+Z）扩散
     * - south（南, +Z 方向）：起始边是 maxZ，向北（-Z）扩散
     * - east （东, +X 方向）：起始边是 maxX，向西（-X）扩散
     * - west （西, -X 方向）：起始边是 minX，向东（+X）扩散
     *
     * 距离 = 方块到起始边的格数（起始边上的方块 distance=0）
     */
    private double calculateDistanceFromDirection(class_2338 pos, String direction, int minX, int maxX, int minZ, int maxZ)
    {
        switch (direction)
        {
            case "south":
                // 从南边（maxZ）开始，向北扩散
                return maxZ - pos.method_10260();

            case "east":
                // 从东边（maxX）开始，向西扩散
                return maxX - pos.method_10263();

            case "west":
                // 从西边（minX）开始，向东扩散
                return pos.method_10263() - minX;

            case "north":
            default:
                // 从北边（minZ）开始，向南扩散
                return pos.method_10260() - minZ;
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
     * 振波方块数据结构
     */
    private static class WaveBlock
    {
        final class_2338 pos;
        final class_2680 state;
        final double distance;

        WaveBlock(class_2338 pos, class_2680 state, double distance)
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
    }

    @Override
    protected Clip create()
    {
        return new BlockShockwaveActionClip();
    }
}
