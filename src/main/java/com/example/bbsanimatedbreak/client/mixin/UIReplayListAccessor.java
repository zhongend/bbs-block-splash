package com.example.bbsanimatedbreak.client.mixin;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * UIReplayList 的 @Accessor + @Invoker 接口
 *
 * 暴露 UIReplayList 中的字段和 private 方法。
 * 使用 @Accessor/@Invoker 而非 @Shadow，因为这些注解
 * 直接通过名称定位成员，不依赖 refmap。
 */
@Mixin(UIReplayList.class)
public interface UIReplayListAccessor
{
    /* === @Accessor 字段访问器 === */

    @Accessor("panel")
    UIFilmPanel bbs$getPanel();

    @Accessor("panel")
    void bbs$setPanel(UIFilmPanel panel);

    @Accessor("collapsedCategories")
    Set<String> bbs$getCollapsedCategories();

    /* === @Invoker 方法访问器 === */

    /** 打开"新建文件夹"对话框 */
    @Invoker("openAddCategoryOverlay")
    void bbs$invokeOpenAddCategoryOverlay();

    /** 打开"移动到文件夹"二级菜单 */
    @Invoker("openMoveToCategoryContextMenu")
    void bbs$invokeOpenMoveToCategoryContextMenu();

    /**
     * 应用文件夹变更（把指定 replay 列表移动到指定文件夹）
     */
    @Invoker("applyReplayCategory")
    void bbs$invokeApplyReplayCategory(List<Replay> selected, String rawCategory);

    /** 收集所有文件夹名（含显式空文件夹 + replay 引用的文件夹） */
    @Invoker("collectCategoryNames")
    TreeSet<String> bbs$invokeCollectCategoryNames(Film film);

    /** 更新 Film 编辑器（重建实体 + 刷新关键帧通道） */
    @Invoker("updateFilmEditor")
    void bbs$invokeUpdateFilmEditor();
}
