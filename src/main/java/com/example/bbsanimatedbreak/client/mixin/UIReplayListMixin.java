package com.example.bbsanimatedbreak.client.mixin;

import com.example.bbsanimatedbreak.client.IFolderView;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.Replays;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.ReplayListEntry;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.l10n.keys.IKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * UIReplayList Mixin - 补充 BBS 原生文件夹功能的差异部分
 *
 * === 无 refmap 设计 ===
 * 不使用 @Shadow，改用 @Accessor 接口（UIReplayListAccessor/UIListAccessor）
 * 和直接调用 public 方法。这样 Mixin 不依赖 refmap 即可正常工作。
 *
 * === 新增功能 ===
 * 1. 双击文件夹行进入"文件夹视图"（只显示该文件夹内的 replay）
 * 2. 移出文件夹时把 replay 置顶到 film.replays 列表最前面
 * 3. 提供打开"移出文件夹"二级菜单的方法
 */
@Mixin(UIReplayList.class)
public abstract class UIReplayListMixin implements IFolderView
{
    /* === Unique 字段（本 Mixin 新增的状态） === */

    @Unique
    private String bbs$enteredFolder = null;

    @Unique
    private String bbs$lastClickedFolder = null;

    @Unique
    private long bbs$lastFolderClickTime = 0L;

    @Unique
    private static final long BBS_DOUBLE_CLICK_THRESHOLD = 500L;

    /* === 辅助方法：通过 Accessor 访问字段 ===
     * 注意：这些方法名不能和 UIReplayListAccessor/UIListAccessor 的方法重名，
     * 否则 Mixin 会因方法冲突跳过 @Accessor 的实现，导致 AbstractMethodError。
     * 这里用 bbs$xxx 而非 bbs$getXxx 命名以避免冲突。
     */

    @Unique
    private UIFilmPanel bbs$panel()
    {
        return ((UIReplayListAccessor) this).bbs$getPanel();
    }

    @Unique
    @SuppressWarnings("unchecked")
    private List<ReplayListEntry> bbs$list()
    {
        return ((UIListAccessor<ReplayListEntry>) (Object) this).bbs$getList();
    }

    @Unique
    @SuppressWarnings("unchecked")
    private List<Integer> bbs$current()
    {
        return ((UIListAccessor<ReplayListEntry>) (Object) this).bbs$getCurrent();
    }

    /* === @Inject：双击文件夹进入文件夹视图 === */

    @Inject(method = "subMouseClicked", at = @At("HEAD"), cancellable = true)
    private void bbs$onSubMouseClicked(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (this.bbs$enteredFolder != null) return;

        if (context.mouseButton != 0) return;

        UIReplayList self = (UIReplayList) (Object) this;

        int index = self.scroll.getIndex(context.mouseX, context.mouseY);

        if (!self.exists(index))
        {
            this.bbs$lastClickedFolder = null;
            return;
        }

        ReplayListEntry entry = this.bbs$list().get(index);

        if (entry.isFolder())
        {
            String folderName = Replay.normalizeCategory(entry.folderName);
            long now = System.currentTimeMillis();

            if (folderName.equals(this.bbs$lastClickedFolder)
                && (now - this.bbs$lastFolderClickTime) < BBS_DOUBLE_CLICK_THRESHOLD)
            {
                this.bbs$enterFolder(folderName);
                this.bbs$lastClickedFolder = null;
                cir.setReturnValue(true);
                return;
            }

            this.bbs$lastClickedFolder = folderName;
            this.bbs$lastFolderClickTime = now;
        }
        else
        {
            this.bbs$lastClickedFolder = null;
        }
    }

    /* === @Inject：文件夹视图过滤 === */

    @Inject(method = "refreshReplayList", at = @At("HEAD"), cancellable = true)
    private void bbs$onRefreshReplayList(CallbackInfo ci)
    {
        if (this.bbs$enteredFolder == null) return;

        Film film = this.bbs$panel().getData();

        if (film == null)
        {
            UIReplayList self = (UIReplayList) (Object) this;
            self.clear();
            ci.cancel();
            return;
        }

        String targetFolder = this.bbs$enteredFolder;
        List<Replay> all = film.replays.getList();
        List<ReplayListEntry> entries = new ArrayList<>();

        for (Replay r : all)
        {
            if (targetFolder.equals(Replay.normalizeCategory(r.category.get())))
            {
                entries.add(ReplayListEntry.replay(r));
            }
        }

        UIReplayList self = (UIReplayList) (Object) this;
        self.setList(entries);
        ci.cancel();
    }

    /* === @Unique：文件夹视图操作方法 === */

    @Unique
    private void bbs$enterFolder(String folderName)
    {
        this.bbs$enteredFolder = folderName;
        UIReplayList self = (UIReplayList) (Object) this;
        this.bbs$current().clear();
        self.refreshReplayList();
        self.update();
    }

    @Override
    @Unique
    public void bbs$exitFolder()
    {
        this.bbs$enteredFolder = null;
        UIReplayList self = (UIReplayList) (Object) this;
        this.bbs$current().clear();
        self.refreshReplayList();
        self.update();
    }

    @Override
    @Unique
    public String bbs$getEnteredFolder()
    {
        return this.bbs$enteredFolder;
    }

    /* === @Unique：移出文件夹并置顶 === */

    @Unique
    private void bbs$moveOutAndTop(List<Replay> selected)
    {
        if (selected == null || selected.isEmpty()) return;

        Film film = this.bbs$panel().getData();

        if (film == null) return;

        Replays replays = film.replays;

        film.preNotify(IValueListener.FLAG_UNMERGEABLE);

        List<Integer> globalIndices = new ArrayList<>();
        for (Replay r : selected)
        {
            int idx = replays.getList().indexOf(r);
            if (idx >= 0) globalIndices.add(idx);
        }

        globalIndices.sort((a, b) -> Integer.compare(b, a));

        for (int idx : globalIndices)
        {
            Replay value = replays.getList().get(idx);
            replays.remove(value);
            value.category.set("");
            replays.add(0, value);
        }

        replays.sync();

        film.postNotify(IValueListener.FLAG_UNMERGEABLE);

        if (this.bbs$enteredFolder != null)
        {
            this.bbs$exitFolder();
        }
        else
        {
            UIReplayList self = (UIReplayList) (Object) this;
            self.refreshReplayList();
        }

        UIReplayList self = (UIReplayList) (Object) this;
        ((UIReplayListAccessor) self).bbs$invokeUpdateFilmEditor();

        this.bbs$current().clear();
        List<ReplayListEntry> list = this.bbs$list();
        for (int i = 0; i < Math.min(selected.size(), list.size()); i++)
        {
            ReplayListEntry e = list.get(i);
            if (e.isReplay() && selected.contains(e.replay))
            {
                this.bbs$current().add(i);
            }
        }
    }

    /* === @Unique：打开移出文件夹菜单 === */

    @Unique
    public void bbs$openMoveOutContextMenu()
    {
        UIReplayList self = (UIReplayList) (Object) this;

        if (!self.hasReplaySelection()) return;

        Film film = this.bbs$panel().getData();

        if (film == null) return;

        List<Replay> selected = new ArrayList<>(self.getSelectedReplays());

        if (selected.isEmpty()) return;

        UIContext context = self.getContext();

        if (context == null) return;

        Replay firstReplay = selected.get(0);
        final String currentFolder = Replay.normalizeCategory(firstReplay.category.get());

        context.replaceContextMenu((manager) ->
        {
            manager.action(Icons.ARROW_UP, IKey.raw("移出到根目录（置顶）"), () ->
            {
                this.bbs$moveOutAndTop(selected);
            });

            TreeSet<String> allCategories = ((UIReplayListAccessor) self).bbs$invokeCollectCategoryNames(film);

            for (String cat : allCategories)
            {
                if (cat.equals(currentFolder)) continue;

                final String targetCat = cat;
                manager.action(Icons.FOLDER, IKey.raw("移入: " + targetCat), () ->
                {
                    ((UIReplayListAccessor) self).bbs$invokeApplyReplayCategory(selected, targetCat);
                });
            }
        });
    }
}
