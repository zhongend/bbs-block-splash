package com.example.bbsanimatedbreak.actions;

import com.example.bbsanimatedbreak.actions.combo.BlockSelection;
import com.example.bbsanimatedbreak.actions.combo.IBlockFilterable;
import com.example.bbsanimatedbreak.actions.combo.SubEffect;
import com.example.bbsanimatedbreak.actions.combo.SubEffectList;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueDouble;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.class_1309;
import net.minecraft.class_2338;
import net.minecraft.class_3218;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 飞溅组合 ActionClip
 *
 * 把多个方块效果（飞溅/震动/路径/反向飞溅）组合成一个 clip，
 * 子效果按时间顺序执行，过渡期间丝滑渐变。
 *
 * === 核心字段 ===
 * - selection：方块选区（5 种选择模式 + 预烘焙 BlockPos 列表）
 * - subEffects：子效果列表（每个内嵌一个完整 ActionClip + 时间 + 过渡曲线）
 * - overlapBlend：是否启用重叠混合（方案A+C）
 *
 * === 子轨道小窗口（自定义渲染器） ===
 * 选中此 clip 时，UIBlockSplashComboRenderer 会在 clip 矩形右半部分
 * 画一个迷你子轨道预览，显示所有子效果的时间块。
 * 点击"打开编辑器"按钮进入独立编辑器（UIComboEditorOverlay）。
 *
 * === 运行时触发逻辑 ===
 * 1. 计算 relative tick（相对 comboClip.tick）
 * 2. 遍历 subEffects，找出当前 tick 命中的子效果
 * 3. 对每个命中的子效果，检查是否是它的 startTick（触发点）
 * 4. 设置子效果 actionClip 的坐标为选区包围盒
 * 5. 调用 actionClip.applyAction 触发效果
 *
 * === 丝滑过渡（简化版，完整版需后续优化） ===
 * 当前实现：子效果按时间顺序触发，过渡曲线影响"强度"但暂未应用到参数缩放。
 * 后续优化：在重叠期间同时触发两个子效果，按强度曲线混合物理结果。
 *
 * === 选区坐标传递 ===
 * 子效果 actionClip 的 x/y/z/x2/y2/z2 被临时设置为选区包围盒（min/max 坐标）。
 * 已知限制：对于圆形/三角形/不规则选择，包围盒会包含选区外的方块。
 * 后续优化：通过 Mixin 给 ActionClip 加 filterBlocks 字段，精确过滤。
 */
public class BlockSplashComboActionClip extends ActionClip
{
    /* 方块选区（5 种选择模式 + 预烘焙 BlockPos 列表） */
    public final BlockSelection selection = new BlockSelection("selection");

    /* 子效果列表 */
    public final SubEffectList subEffects = new SubEffectList("subEffects");

    /* 是否启用重叠混合（方案A：时间重叠期同时执行两效果） */
    public final ValueBoolean overlapBlend = new ValueBoolean("overlapBlend", true);

    /* 临时变量：用于传递选区坐标给子效果 */
    private transient int lastMinX, lastMinY, lastMinZ, lastMaxX, lastMaxY, lastMaxZ;

    public BlockSplashComboActionClip()
    {
        super();

        this.add(this.selection);
        this.add(this.subEffects);
        this.add(this.overlapBlend);
    }

    @Override
    public void applyAction(class_1309 actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        if (!(player.method_37908() instanceof class_3218)) return;
        if (!this.selection.hasBakedBlocks()) return;

        /* 计算相对 tick */
        int relative = tick - this.tick.get();

        List<SubEffect> active = new ArrayList<>();
        this.subEffects.getActiveSubEffects(relative, active);

        for (SubEffect sub : active)
        {
            /* 仅在子效果的 startTick 触发（frequency=0 模式） */
            if (relative - sub.startTick.get() != 0) continue;

            ActionClip subClip = this.prepareSubClip(sub);

            if (subClip == null) continue;

            /* 触发子效果（不检查强度，因为 startTick 时强度可能为0但效果需要启动） */
            subClip.applyAction(actor, player, film, replay, this.tick.get() + sub.startTick.get());
        }
    }

    /**
     * 把回放时钟转发给子片段（BBS 回放规则）
     *
     * === 为什么必须转发 ===
     * BBS 只认识 combo 片段本身，子片段是通过 subClip.applyAction() 直接调用的，
     * 因此 BBS <b>不会</b>替子片段调用 applyRange。
     *
     * 而物理类子效果（block_splash 的 Rapier/Jolt 刚体）的步进正挂在
     * BlockSplashActionClip#applyRange 上——若不转发，子效果的物理世界会永远停在
     * localTick = 0，方块僵在半空不动，直到回放停止才被清理。
     *
     * 传入的是<b>当前回放绝对 tick</b>：子片段会自行做
     * {@code tick - subClip.tick} 的换算（subClip.tick 已在 applyAction 时定位到
     * combo 起点 + 子效果偏移），从而得到正确的局部 tick。
     *
     * 注意：这里刻意不做参数准备（选区/强度缩放）。
     * prepareSubClip 里的强度缩放不是幂等的（会连乘），若每 tick 调用会指数放大力度；
     * 而且子片段的选区与 tick 在 applyAction 时已经固化，applyRange 只需转发时钟。
     */
    @Override
    public void applyRange(class_1309 actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        if (!(player.method_37908() instanceof class_3218)) return;
        if (!this.selection.hasBakedBlocks()) return;

        int relative = tick - this.tick.get();

        List<SubEffect> active = new ArrayList<>();
        this.subEffects.getActiveSubEffects(relative, active);

        for (SubEffect sub : active)
        {
            ActionClip subClip = sub.getActionClip();

            if (subClip == null) continue;

            subClip.applyRange(actor, player, film, replay, tick);
        }
    }

    /**
     * 准备一个子片段：同步时间、坐标、选区过滤器、强度曲线
     *
     * @return 可用的子片段；无效时返回 null
     */
    private ActionClip prepareSubClip(SubEffect sub)
    {
        ActionClip subClip = sub.getActionClip();

        if (subClip == null)
        {
            return null;
        }

        /* 计算选区包围盒（用于设置子效果 actionClip 的坐标） */
        this.calculateSelectionBounds();

        /* 设置子效果 actionClip 的时间：绝对起始 tick 由 combo 起点 + 子效果偏移决定 */
        subClip.tick.set(this.tick.get() + sub.startTick.get());
        subClip.duration.set(sub.duration.get());

        /* 设置子效果 actionClip 的坐标为选区包围盒 */
        this.applySelectionBoundsToClip(subClip);

        /* 设置方块过滤器（圆形/三角形等不规则选区精确过滤） */
        if (subClip instanceof IBlockFilterable filterable)
        {
            Set<class_2338> filter = this.selection.getBlockFilterSet();
            filterable.setBlockFilter(filter);
        }

        /* 根据强度缩放子效果参数（强度=1时原值，强度<1时减弱） */
        float strength = sub.getStrengthAt(0);
        this.applyStrengthToClip(subClip, strength);

        return subClip;
    }

    /**
     * 计算选区包围盒（min/max 坐标）。
     * 基于 bakedBlocks 的实际方块坐标。
     */
    private void calculateSelectionBounds()
    {
        if (!this.selection.hasBakedBlocks())
        {
            this.lastMinX = this.lastMinY = this.lastMinZ = 0;
            this.lastMaxX = this.lastMaxY = this.lastMaxZ = 0;
            return;
        }

        List<class_2338> blocks = this.selection.getBlocksAtAnchor();

        if (blocks.isEmpty())
        {
            this.lastMinX = this.lastMinY = this.lastMinZ = 0;
            this.lastMaxX = this.lastMaxY = this.lastMaxZ = 0;
            return;
        }

        this.lastMinX = this.lastMaxX = blocks.get(0).method_10263();
        this.lastMinY = this.lastMaxY = blocks.get(0).method_10264();
        this.lastMinZ = this.lastMaxZ = blocks.get(0).method_10260();

        for (class_2338 pos : blocks)
        {
            this.lastMinX = Math.min(this.lastMinX, pos.method_10263());
            this.lastMinY = Math.min(this.lastMinY, pos.method_10264());
            this.lastMinZ = Math.min(this.lastMinZ, pos.method_10260());
            this.lastMaxX = Math.max(this.lastMaxX, pos.method_10263());
            this.lastMaxY = Math.max(this.lastMaxY, pos.method_10264());
            this.lastMaxZ = Math.max(this.lastMaxZ, pos.method_10260());
        }
    }

    /**
     * 把选区包围盒设置到子效果 actionClip 的 x/y/z/x2/y2/z2 字段。
     * 支持 4 种 ActionClip 类型。
     */
    private void applySelectionBoundsToClip(ActionClip clip)
    {
        if (clip instanceof BlockSplashActionClip)
        {
            BlockSplashActionClip c = (BlockSplashActionClip) clip;
            c.x.set(this.lastMinX); c.y.set(this.lastMinY); c.z.set(this.lastMinZ);
            c.x2.set(this.lastMaxX); c.y2.set(this.lastMaxY); c.z2.set(this.lastMaxZ);
        }
        else if (clip instanceof BlockShockwaveActionClip)
        {
            BlockShockwaveActionClip c = (BlockShockwaveActionClip) clip;
            c.x.set(this.lastMinX); c.y.set(this.lastMinY); c.z.set(this.lastMinZ);
            c.x2.set(this.lastMaxX); c.y2.set(this.lastMaxY); c.z2.set(this.lastMaxZ);
        }
        else if (clip instanceof BlockPathActionClip)
        {
            BlockPathActionClip c = (BlockPathActionClip) clip;
            c.x.set(this.lastMinX); c.y.set(this.lastMinY); c.z.set(this.lastMinZ);
            c.x2.set(this.lastMaxX); c.y2.set(this.lastMaxY); c.z2.set(this.lastMaxZ);
        }
        else if (clip instanceof BlockSplashReverseActionClip)
        {
            BlockSplashReverseActionClip c = (BlockSplashReverseActionClip) clip;
            c.x.set(this.lastMinX); c.y.set(this.lastMinY); c.z.set(this.lastMinZ);
            c.x2.set(this.lastMaxX); c.y2.set(this.lastMaxY); c.z2.set(this.lastMaxZ);
        }
    }

    /**
     * 根据强度缩放子效果的关键参数。
     * strength=1 时保持原值，strength<1 时按比例减弱。
     * 这实现了"丝滑过渡"：子效果A淡出时参数减弱，子效果B淡入时参数增强。
     *
     * === 幂等性（重要） ===
     * 子片段是<b>持久实例</b>（内嵌在 SubEffect 里、随影片存档），而 applyAction
     * 每次回放/每次拖动时间轴都会被重新触发。若直接 {@code value = value * s}，
     * 力度就会随触发次数连乘 —— 播放 5 次后飞溅会猛烈到失控。
     * 因此这里用「基准值缓存」：记住原始值，只在检测到外部修改
     * （当前值 ≠ 上次写入值，说明用户在 UI 里改过）时才刷新基准。
     */
    private void applyStrengthToClip(ActionClip clip, float strength)
    {
        /* 强度至少0.05，避免完全无效果导致触发异常 */
        float s = Math.max(0.05F, strength);

        if (clip instanceof BlockSplashActionClip splash)
        {
            /* 飞溅力度按强度缩放 */
            this.scaleParam(splash.power, s);
        }
        else if (clip instanceof BlockShockwaveActionClip shockwave)
        {
            /* 振幅和冲击力按强度缩放 */
            this.scaleParam(shockwave.amplitude, s);
            this.scaleParam(shockwave.impactForce, s);
        }
        else if (clip instanceof BlockSplashReverseActionClip reverse)
        {
            /* 反向飞溅的散射半径按强度缩放 */
            this.scaleParam(reverse.scatterRadius, s);
        }
        /* BlockPathActionClip 不缩放（路径速度不应随强度变化） */
    }

    /** 强度缩放的基准值缓存（以 ValueDouble 实例身份为键，随 combo clip 生命周期存活） */
    private final java.util.Map<ValueDouble, Double> strengthBase = new java.util.IdentityHashMap<>();
    private final java.util.Map<ValueDouble, Double> strengthLast = new java.util.IdentityHashMap<>();

    /**
     * 幂等地把一个参数按强度缩放（见 applyStrengthToClip 的注释）
     *
     * @param value 目标参数
     * @param s     强度（>= 0.05）
     */
    private void scaleParam(ValueDouble value, float s)
    {
        double current = value.get();
        Double base = this.strengthBase.get(value);
        Double last = this.strengthLast.get(value);

        if (base == null || last == null || Math.abs(current - last) > 1.0E-9D)
        {
            /* 首次调用，或用户在 UI 里改过值 → 以当前值为新基准 */
            base = current;
        }

        double scaled = base * s;

        this.strengthBase.put(value, base);
        this.strengthLast.put(value, scaled);

        value.set(scaled);
    }

    @Override
    public boolean isGlobal()
    {
        return false;
    }

    @Override
    protected Clip create()
    {
        return new BlockSplashComboActionClip();
    }

    @Override
    public void shift(double dx, double dy, double dz)
    {
        if (!this.selection.hasBakedBlocks()) return;

        /* 平移选区的锚点 */
        class_2338 anchor = this.selection.getBakedCenter();
        this.selection.setAnchorTo(
            (int) (anchor.method_10263() + dx),
            (int) (anchor.method_10264() + dy),
            (int) (anchor.method_10260() + dz)
        );
    }

    /**
     * 获取选区方块列表（运行时用于子效果触发）。
     */
    public List<class_2338> getSelectionBlocks()
    {
        return this.selection.getBlocksAtAnchor();
    }

    /**
     * 获取子效果数量。
     */
    public int getSubEffectCount()
    {
        return this.subEffects.getSubEffectCount();
    }

    /**
     * 计算组合 clip 的总持续时间（基于子效果）。
     */
    public int calculateComboDuration()
    {
        return this.subEffects.calculateTotalDuration();
    }
}
