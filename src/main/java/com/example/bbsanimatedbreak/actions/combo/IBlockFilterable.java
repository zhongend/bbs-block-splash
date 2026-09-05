package com.example.bbsanimatedbreak.actions.combo;

import java.util.Set;
import net.minecraft.class_2338;

/**
 * 支持方块过滤器的 ActionClip 接口。
 *
 * 飞溅组合（BlockSplashComboActionClip）在触发子效果前，
 * 会通过此接口把选区的精确方块列表传给子效果。
 *
 * 子效果在 collectBlocksInArea 时，如果 blockFilter 非空，
 * 则只保留在 filter 中的方块，实现圆形/三角形等不规则选区的精确过滤。
 */
public interface IBlockFilterable
{
    /**
     * 设置方块过滤器（null 表示不过滤，使用包围盒内所有方块）。
     * 注意：调用方应传入不可变 Set 或不再修改的 Set。
     */
    void setBlockFilter(Set<class_2338> filter);

    /**
     * 获取当前方块过滤器。
     */
    Set<class_2338> getBlockFilter();
}
