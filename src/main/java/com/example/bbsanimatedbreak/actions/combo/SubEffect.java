package com.example.bbsanimatedbreak.actions.combo;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueDouble;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Envelope;

/**
 * 飞溅组合的子效果
 *
 * 每个子效果内嵌一个完整的 ActionClip（方块飞溅/震动/路径/反向飞溅），
 * 并附带时间偏移、持续时间、过渡曲线。
 *
 * === 丝滑过渡方案（用户选择"混合方案A+C"） ===
 * - transitionIn：进入强度曲线（Envelope，控制子效果开始时的强度 0→1）
 * - transitionOut：退出强度曲线（Envelope，控制子效果结束时的强度 1→0）
 * - blendFactor：与相邻子效果的时间重叠比例（0~1，0.2=重叠20%）
 *
 * 运行时（见 BlockSplashComboActionClip.applyAction）：
 * - 在重叠期间，前后两个子效果同时执行
 * - 前一效果按 transitionOut 衰减强度，后一效果按 transitionIn 上升强度
 * - 两者物理结果按权重混合（方案A）
 * - 每个子效果内部参数（如飞溅 power、震动 amplitude）按强度曲线缩放（方案C）
 *
 * === 序列化 ===
 * SubEffect 继承 ValueGroup，但 actionClip 不通过 this.add() 加入 children
 * （避免 ValueGroup.toData 遍历 children 时重复序列化 actionClip）。
 * actionClip 的序列化通过 BBS 工厂的 toData/fromData 单独处理（写入 type + 数据）。
 */
public class SubEffect extends ValueGroup
{
    /* 子效果在组合内的起始 tick（相对组合 clip 的 tick） */
    public final ValueInt startTick = new ValueInt("startTick", 0, 0, Integer.MAX_VALUE);

    /* 子效果持续时间（tick） */
    public final ValueInt duration = new ValueInt("duration", 20, 1, Integer.MAX_VALUE);

    /* 进入强度曲线（0→1），控制子效果开始时的强度 */
    public final Envelope transitionIn = new Envelope("transitionIn");

    /* 退出强度曲线（1→0），控制子效果结束时的强度 */
    public final Envelope transitionOut = new Envelope("transitionOut");

    /* 与相邻子效果的时间重叠比例（0~1，0.2=重叠20%） */
    public final ValueDouble blendFactor = new ValueDouble("blendFactor", 0.2D, 0D, 0.8D);

    /* 内嵌的 ActionClip（方块飞溅/震动/路径/反向飞溅） */
    private ActionClip actionClip;

    public SubEffect(String id)
    {
        super(id);

        this.add(this.startTick);
        this.add(this.duration);
        this.add(this.transitionIn);
        this.add(this.transitionOut);
        this.add(this.blendFactor);

        /* 默认过渡曲线：线性 0→1 和 1→0 */
        this.transitionIn.enabled.set(true);
        this.transitionIn.fadeIn.set(5F);
        this.transitionIn.fadeOut.set(0F);
        this.transitionOut.enabled.set(true);
        this.transitionOut.fadeIn.set(0F);
        this.transitionOut.fadeOut.set(5F);
    }

    public ActionClip getActionClip()
    {
        return this.actionClip;
    }

    /**
     * 设置内嵌的 ActionClip。
     * actionClip 不加入 children，单独管理，避免重复序列化。
     */
    public void setActionClip(ActionClip clip)
    {
        this.actionClip = clip;

        if (clip != null)
        {
            clip.setId("actionClip");
        }
    }

    /**
     * 创建指定类型的新 ActionClip。
     *
     * @param typeLink 效果类型 Link 字符串，如 "bbs:block_splash"
     * @return 新创建的 ActionClip，或 null 如果类型无效
     */
    public ActionClip createNewActionClip(String typeLink)
    {
        try
        {
            Clip clip = BBSMod.getFactoryActionClips().create(mchorse.bbs_mod.resources.Link.create(typeLink));

            if (clip instanceof ActionClip)
            {
                this.setActionClip((ActionClip) clip);
                return this.actionClip;
            }
        }
        catch (Exception e)
        {
            /* 类型无效，忽略 */
        }

        return null;
    }

    /**
     * 获取子效果的结束 tick（startTick + duration）。
     */
    public int getEndTick()
    {
        return this.startTick.get() + this.duration.get();
    }

    /**
     * 计算在指定 tick（相对子效果 startTick）时的强度（0~1）。
     *
     * 进入阶段（0 ~ fadeIn）：transitionIn 曲线 0→1
     * 中间阶段（fadeIn ~ duration-fadeOut）：强度 1
     * 退出阶段（duration-fadeOut ~ duration）：transitionOut 曲线 1→0
     *
     * @param relativeTick 相对子效果 startTick 的 tick
     * @return 强度 0~1
     */
    public float getStrengthAt(int relativeTick)
    {
        int dur = this.duration.get();

        if (relativeTick < 0 || relativeTick >= dur) return 0F;

        int inDur = (int) this.transitionIn.fadeIn.get().floatValue();
        int outDur = (int) this.transitionOut.fadeOut.get().floatValue();
        int outStart = dur - outDur;

        /* fadeIn 和 fadeOut 重叠时，优先处理 fadeIn */
        if (inDur > 0 && relativeTick < inDur && this.transitionIn.enabled.get())
        {
            return this.transitionIn.factor(inDur, relativeTick);
        }
        else if (outDur > 0 && outStart >= 0 && relativeTick >= outStart && this.transitionOut.enabled.get())
        {
            int outTick = relativeTick - outStart;
            return 1F - this.transitionOut.factor(outDur, outTick);
        }

        return 1F;
    }

    @Override
    public BaseType toData()
    {
        MapType data = (MapType) super.toData();

        if (this.actionClip != null)
        {
            data.put("actionClip", BBSMod.getFactoryActionClips().toData(this.actionClip));
        }

        return data;
    }

    @Override
    public void fromData(BaseType base)
    {
        super.fromData(base);

        if (base.isMap())
        {
            MapType map = base.asMap();

            if (map.has("actionClip"))
            {
                try
                {
                    Clip clip = BBSMod.getFactoryActionClips().fromData(map.getMap("actionClip"));

                    if (clip instanceof ActionClip)
                    {
                        this.setActionClip((ActionClip) clip);
                    }
                }
                catch (Exception e)
                {
                    /* 反序列化失败，忽略（可能是旧版本数据或类型未注册） */
                }
            }
        }
    }

    @Override
    public void copy(BaseValueGroup group)
    {
        super.copy(group);

        if (group instanceof SubEffect)
        {
            SubEffect other = (SubEffect) group;

            if (other.actionClip != null)
            {
                this.setActionClip((ActionClip) other.actionClip.copy());
            }
        }
    }
}
