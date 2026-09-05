package com.example.bbsanimatedbreak.client.mixin;

import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_mod.ui.film.replays.UIReplaysListPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * UIReplaysListPanel 的 @Accessor 接口
 *
 * 暴露 UIReplaysListPanel 的字段，不依赖 refmap。
 */
@Mixin(UIReplaysListPanel.class)
public interface UIReplaysListPanelAccessor
{
    @Accessor("bar")
    UIElement bbs$getBar();

    @Accessor("leftBar")
    UIElement bbs$getLeftBar();

    @Accessor("addReplay")
    UIIcon bbs$getAddReplay();

    @Accessor("dupeReplay")
    UIIcon bbs$getDupeReplay();

    @Accessor("removeReplay")
    UIIcon bbs$getRemoveReplay();

    @Accessor("replays")
    UIReplayList bbs$getReplays();

    @Accessor("filmPanel")
    UIFilmPanel bbs$getFilmPanel();
}
