package com.example.bbsanimatedbreak.client.mixin;

import com.example.bbsanimatedbreak.client.IFolderView;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_mod.ui.film.replays.UIReplaysListPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.utils.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * UIReplaysListPanel Mixin - 在顶部工具栏添加文件夹操作按钮
 *
 * === 无 refmap 设计 ===
 * 不使用 @Shadow，改用 @Accessor 接口（UIReplaysListPanelAccessor）
 * 和直接调用 public 方法。
 */
@Mixin(UIReplaysListPanel.class)
public abstract class UIReplaysListPanelMixin
{
    /* === 本 Mixin 新增的按钮 === */
    @Unique
    private UIIcon bbs$newFolder;

    @Unique
    private UIIcon bbs$moveToFolder;

    @Unique
    private UIIcon bbs$moveOutFolder;

    @Unique
    private UIIcon bbs$backToRoot;

    @Unique
    private UIElement bbs$rightBar;

    @Unique
    private static final int BBS_BAR_ICON_SIZE = 20;

    @Unique
    private static final int BBS_BAR_ICON_MARGIN = 2;

    /* === 辅助方法：通过 Accessor 访问字段 ===
     * 注意：这些方法名不能和 UIReplaysListPanelAccessor 的方法重名，
     * 否则 Mixin 会因方法冲突跳过 @Accessor 的实现，导致 AbstractMethodError。
     * 这里用 bbs$xxx 而非 bbs$getXxx 命名以避免冲突。
     */

    @Unique
    private UIElement bbs$bar()
    {
        return ((UIReplaysListPanelAccessor) this).bbs$getBar();
    }

    @Unique
    private UIReplayList bbs$replays()
    {
        return ((UIReplaysListPanelAccessor) this).bbs$getReplays();
    }

    @Unique
    private UIFilmPanel bbs$filmPanel()
    {
        return ((UIReplaysListPanelAccessor) this).bbs$getFilmPanel();
    }

    /**
     * 在 UIReplaysListPanel 构造函数末尾注入，添加右侧按钮组。
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs$onInit(CallbackInfo ci)
    {
        // === 创建右侧按钮组 ===
        this.bbs$rightBar = new UIElement();

        int rightW = BBS_BAR_ICON_SIZE * 4 + BBS_BAR_ICON_MARGIN * 3;

        UIElement bar = this.bbs$bar();
        UIReplayList replays = this.bbs$replays();

        // rightBar 靠右对齐在 bar 内
        this.bbs$rightBar.relative(bar).x(1F, -rightW).y(0).w(rightW).h(20)
            .row(BBS_BAR_ICON_MARGIN).height(20);

        // === 创建 4 个按钮 ===

        // 1. 新建文件夹
        this.bbs$newFolder = new UIIcon(Icons.FOLDER, (b) ->
        {
            ((UIReplayListAccessor) replays).bbs$invokeOpenAddCategoryOverlay();
        });
        this.bbs$newFolder.w(BBS_BAR_ICON_SIZE);
        this.bbs$newFolder.tooltip(IKey.raw("新建文件夹"), Direction.TOP);

        // 2. 移入文件夹
        this.bbs$moveToFolder = new UIIcon(Icons.SHIFT_TO, (b) ->
        {
            ((UIReplayListAccessor) replays).bbs$invokeOpenMoveToCategoryContextMenu();
        });
        this.bbs$moveToFolder.w(BBS_BAR_ICON_SIZE);
        this.bbs$moveToFolder.tooltip(IKey.raw("移入文件夹"), Direction.TOP);

        // 3. 移出文件夹
        this.bbs$moveOutFolder = new UIIcon(Icons.ARROW_UP, (b) ->
        {
            ((IFolderView) replays).bbs$openMoveOutContextMenu();
        });
        this.bbs$moveOutFolder.w(BBS_BAR_ICON_SIZE);
        this.bbs$moveOutFolder.tooltip(IKey.raw("移出文件夹（置顶）"), Direction.TOP);

        // 4. 返回根视图（默认隐藏，仅在文件夹视图时显示）
        this.bbs$backToRoot = new UIIcon(Icons.ARROW_LEFT, (b) ->
        {
            ((IFolderView) replays).bbs$exitFolder();
        });
        this.bbs$backToRoot.w(BBS_BAR_ICON_SIZE);
        this.bbs$backToRoot.tooltip(IKey.raw("返回根目录"), Direction.TOP);
        this.bbs$backToRoot.setVisible(false);

        // 添加到 rightBar
        this.bbs$rightBar.add(this.bbs$newFolder, this.bbs$moveToFolder,
            this.bbs$moveOutFolder, this.bbs$backToRoot);

        // 添加 rightBar 到 bar
        bar.add(this.bbs$rightBar);
    }

    /**
     * 在 render 方法中更新按钮状态。
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void bbs$updateButtonsState(UIContext context, CallbackInfo ci)
    {
        UIFilmPanel filmPanel = this.bbs$filmPanel();
        UIReplayList replays = this.bbs$replays();

        Film film = filmPanel.getData();
        boolean hasFilm = film != null;
        boolean hasSelection = replays.hasReplaySelection();

        // 检查是否有文件夹存在
        boolean hasCategories = false;
        if (hasFilm)
        {
            hasCategories = !((UIReplayListAccessor) replays).bbs$invokeCollectCategoryNames(film).isEmpty();
        }

        // 检查选中的 replay 是否在文件夹内
        boolean selectionInFolder = false;
        if (hasSelection)
        {
            List<Replay> selected = replays.getSelectedReplays();
            if (!selected.isEmpty())
            {
                String cat = Replay.normalizeCategory(selected.get(0).category.get());
                selectionInFolder = !cat.isEmpty();
            }
        }

        // 检查是否在文件夹视图
        boolean inFolderView = ((IFolderView) replays).bbs$getEnteredFolder() != null;

        // 更新按钮启用/可见状态
        this.bbs$newFolder.setEnabled(hasFilm);
        this.bbs$moveToFolder.setEnabled(hasSelection && hasCategories);
        this.bbs$moveOutFolder.setEnabled(hasSelection && selectionInFolder);
        this.bbs$backToRoot.setVisible(inFolderView);
    }
}
