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

        class_3218 world = (class_3218) player.method_37908();

        /* 检查选区是否有烘焙的方块 */
        if (!this.selection.hasBakedBlocks()) return;

        /* 计算相对 tick */
        int relative = tick - this.tick.get();

        /* 计算选区包围盒（用于设置子效果 actionClip 的坐标） */
        this.calculateSelectionBounds();

        /* 遍历命中的子效果 */
        List<SubEffect> active = new ArrayList<>();
        this.subEffects.getActiveSubEffects(relative, active);

        for (SubEffect sub : active)
        {
            ActionClip subClip = sub.getActionClip();

            if (subClip == null) continue;

            /* 检查是否是子效果的触发 tick（相对 comboClip） */
            int subRelative = relative - sub.startTick.get();

            /* 仅在子效果的 startTick 触发（frequency=0 模式） */
            if (subRelative != 0) continue;

            /* 设置子效果 actionClip 的时间 */
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
            float strength = sub.getStrengthAt(subRelative);
            this.applyStrengthToClip(subClip, strength);

            /* 触发子效果（不检查强度，因为 startTick 时强度可能为0但效果需要启动） */
            subClip.applyAction(actor, player, film, replay, this.tick.get() + sub.startTick.get());
        }
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
     */
    private void applyStrengthToClip(ActionClip clip, float strength)
    {
        /* 强度至少0.05，避免完全无效果导致触发异常 */
        float s = Math.max(0.05F, strength);

        if (clip instanceof BlockSplashActionClip splash)
        {
            /* 飞溅力度按强度缩放 */
            double origPower = splash.power.get();
            splash.power.set(origPower * s);
        }
        else if (clip instanceof BlockShockwaveActionClip shockwave)
        {
            /* 振幅和冲击力按强度缩放 */
            double origAmp = shockwave.amplitude.get();
            double origForce = shockwave.impactForce.get();
            shockwave.amplitude.set(origAmp * s);
            shockwave.impactForce.set(origForce * s);
        }
        else if (clip instanceof BlockSplashReverseActionClip reverse)
        {
            /* 反向飞溅的散射半径按强度缩放 */
            double origRadius = reverse.scatterRadius.get();
            reverse.scatterRadius.set(origRadius * s);
        }
        /* BlockPathActionClip 不缩放（路径速度不应随强度变化） */
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
