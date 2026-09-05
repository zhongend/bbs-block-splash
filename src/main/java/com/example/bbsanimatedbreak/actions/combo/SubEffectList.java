package com.example.bbsanimatedbreak.actions.combo;

import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.core.ValueList;

import java.util.List;

/**
 * 子效果列表（ValueList<SubEffect>）
 *
 * 管理 BlockSplashComboActionClip 内的所有子效果。
 * 继承 ValueList 自动获得序列化、add/remove/sync 等能力。
 */
public class SubEffectList extends ValueList<SubEffect>
{
    public SubEffectList(String id)
    {
        super(id);
    }

    @Override
    protected SubEffect create(String id)
    {
        return new SubEffect(id);
    }

    /* === 便利方法 === */

    public SubEffect addSubEffect()
    {
        SubEffect sub = new SubEffect(String.valueOf(this.list.size()));

        this.preNotify(IValueListener.FLAG_UNMERGEABLE);
        this.list.add(sub);
        this.sync();
        this.postNotify(IValueListener.FLAG_UNMERGEABLE);

        return sub;
    }

    public void removeSubEffect(int index)
    {
        if (index < 0 || index >= this.list.size()) return;

        this.preNotify(IValueListener.FLAG_UNMERGEABLE);
        this.list.remove(index);
        this.sync();
        this.postNotify(IValueListener.FLAG_UNMERGEABLE);
    }

    public void moveSubEffect(int from, int to)
    {
        if (from < 0 || from >= this.list.size()) return;
        if (to < 0 || to >= this.list.size()) return;
        if (from == to) return;

        this.preNotify(IValueListener.FLAG_UNMERGEABLE);
        SubEffect sub = this.list.remove(from);
        this.list.add(to, sub);
        this.sync();
        this.postNotify(IValueListener.FLAG_UNMERGEABLE);
    }

    public List<SubEffect> getSubEffects()
    {
        return this.list;
    }

    public SubEffect getSubEffect(int index)
    {
        if (index < 0 || index >= this.list.size()) return null;
        return this.list.get(index);
    }

    public int getSubEffectCount()
    {
        return this.list.size();
    }

    /**
     * 计算所有子效果的总持续时间（最后一个子效果的结束 tick）。
     */
    public int calculateTotalDuration()
    {
        int max = 0;

        for (SubEffect sub : this.list)
        {
            max = Math.max(max, sub.getEndTick());
        }

        return max;
    }

    /**
     * 获取在指定 tick（相对组合 clip）命中的所有子效果。
     */
    public void getActiveSubEffects(int relativeTick, List<SubEffect> out)
    {
        out.clear();

        for (SubEffect sub : this.list)
        {
            int start = sub.startTick.get();
            int end = sub.getEndTick();

            if (relativeTick >= start && relativeTick < end)
            {
                out.add(sub);
            }
        }
    }
}
