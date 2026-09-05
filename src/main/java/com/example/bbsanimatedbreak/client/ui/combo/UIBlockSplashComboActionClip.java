package com.example.bbsanimatedbreak.client.ui.combo;

import com.example.bbsanimatedbreak.actions.BlockSplashComboActionClip;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.clips.actions.UIActionClip;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * 飞溅组合 ActionClip 的属性面板
 *
 * 在 BBS 编辑器右侧属性面板显示：
 * 1. "打开组合编辑器"按钮 - 打开 PS 风格的独立编辑器覆盖层
 * 2. 选区信息（形状 + 烘焙方块数）
 * 3. 子效果数量
 * 4. 过渡混合开关
 *
 * 详细的子效果编辑、选区编辑、过渡曲线编辑都在独立编辑器里完成。
 */
public class UIBlockSplashComboActionClip extends UIActionClip<BlockSplashComboActionClip>
{
    private UIButton openEditor;
    private UILabel selectionInfo;
    private UILabel subEffectInfo;

    public UIBlockSplashComboActionClip(BlockSplashComboActionClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.openEditor = new UIButton(IKey.raw("打开组合编辑器"), (b) -> this.openComboEditor());

        this.selectionInfo = UI.label(IKey.raw(""));
        this.subEffectInfo = UI.label(IKey.raw(""));
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        /* BBS v2.2.1 没有 UISection/section 方法，直接用 label + 元素 */
        this.panels.add(UI.label(IKey.raw("飞溅组合"), 20, Colors.ACTIVE));
        this.panels.add(this.openEditor);
        this.panels.add(UI.label(IKey.raw("选区"), 20, Colors.ACTIVE));
        this.panels.add(this.selectionInfo);
        this.panels.add(UI.label(IKey.raw("子效果"), 20, Colors.ACTIVE));
        this.panels.add(this.subEffectInfo);
        this.panels.add(this.createAddSubEffectButton());
    }

    private UIButton createAddSubEffectButton()
    {
        return new UIButton(IKey.raw("添加子效果"), (b) ->
        {
            this.clip.subEffects.addSubEffect();
            this.fillData();
        });
    }

    @Override
    public void fillData()
    {
        super.fillData();

        String shape = this.clip.selection.shape.get();
        int blockCount = this.clip.selection.getBakedCount();
        int subCount = this.clip.getSubEffectCount();

        this.selectionInfo.label = IKey.raw("形状: " + shape + " | 方块: " + blockCount);
        this.subEffectInfo.label = IKey.raw("数量: " + subCount);
    }

    /**
     * 打开 PS 风格的独立编辑器覆盖层。
     */
    private void openComboEditor()
    {
        UIContext context = this.getContext();

        if (context == null) return;

        UIComboEditorOverlay overlay = new UIComboEditorOverlay(context, this.clip, () ->
        {
            /* 编辑器关闭后刷新面板 */
            this.fillData();
            this.editor.editMultiple(this.clip.duration, (d) -> d.set(this.clip.calculateComboDuration()));
        });

        UIOverlay.addOverlay(context, overlay, 0.8F, 0.9F);
    }
}
