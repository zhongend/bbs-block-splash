package com.example.bbsanimatedbreak.client.mixin;

import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * UIList 的 @Accessor 接口
 *
 * 暴露 UIList 的 protected 字段，供 UIReplayListMixin 使用。
 * 使用 @Accessor 而非 @Shadow，因为它不依赖 refmap。
 */
@Mixin(UIList.class)
public interface UIListAccessor<T>
{
    @Accessor("list")
    List<T> bbs$getList();

    @Accessor("list")
    void bbs$setList(List<T> list);

    @Accessor("current")
    List<Integer> bbs$getCurrent();
}
