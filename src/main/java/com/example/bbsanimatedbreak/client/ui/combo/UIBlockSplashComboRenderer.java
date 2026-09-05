package com.example.bbsanimatedbreak.client.ui.combo;

import com.example.bbsanimatedbreak.actions.BlockSplashComboActionClip;
import com.example.bbsanimatedbreak.actions.combo.SubEffect;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.film.clips.renderer.IUIClipRenderer;
import mchorse.bbs_mod.ui.film.clips.renderer.UIClipRenderer;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * 飞溅组合 Clip 的自定义渲染器
 *
 * === 视觉效果 ===
 * 1. 先用默认渲染器画 clip 背景（图标、文字、envelope、选中框）
 * 2. 然后在 clip 矩形右半部分画"子轨道预览"：
 *    - 显示所有子效果的时间块（按 startTick/duration 比例排列）
 *    - 每个子效果块用不同颜色 + 编号
 *    - 中间用分隔线分开左右两半
 *
 * === 子轨道预览（只读） ===
 * 这个预览只是视觉显示，不能直接编辑。
 * 用户需要点击"打开编辑器"按钮进入独立编辑器修改子效果。
 *
 * === 布局 ===
 * 左半部分：clip 标题、图标、envelope 预览（默认渲染器画）
 * 右半部分：子效果时间块（本渲染器画）
 * 中间：垂直分隔线
 */
public class UIBlockSplashComboRenderer extends UIClipRenderer<BlockSplashComboActionClip>
{
    /* 子效果块的颜色调色板（循环使用） */
    private static final int[] SUB_COLORS = {
        0xff4059, 0x59d940, 0x4073ff, 0xff8822,
        0xff66ff, 0x33ffff, 0xffff33, 0xff9da1
    };

    /* 分隔线颜色 */
    private static final int DIVIDER_COLOR = Colors.A50;

    /* 子效果块边框颜色 */
    private static final int SUB_BORDER = Colors.A75;

    @Override
    public void renderClip(UIContext context, UIClips clips, BlockSplashComboActionClip clip, Area area, boolean selected, boolean current)
    {
        /* 1. 先调用默认渲染器画背景（图标、文字、envelope、选中框） */
        super.renderClip(context, clips, clip, area, selected, current);

        /* 2. 在右半部分画子轨道预览 */
        int totalDuration = clip.calculateComboDuration();

        if (totalDuration <= 0) return;

        /* 计算右半部分区域 */
        int halfW = area.w / 2;
        int rightX = area.x + halfW;
        int rightW = area.w - halfW;

        /* 如果区域太窄，不画子轨道 */
        if (rightW < 20) return;

        /* 画中间分隔线 */
        context.batcher.box(rightX, area.y, rightX + 1, area.ey(), DIVIDER_COLOR);

        /* 画子效果块 */
        FontRenderer font = context.batcher.getFont();
        int subCount = clip.getSubEffectCount();

        for (int i = 0; i < subCount; i++)
        {
            SubEffect sub = clip.subEffects.getSubEffect(i);

            if (sub == null) continue;

            int start = sub.startTick.get();
            int dur = sub.duration.get();

            /* 计算 subEffect 在右半区域的屏幕坐标 */
            int subX = rightX + 2 + (int) ((double) start / totalDuration * (rightW - 4));
            int subW = Math.max(3, (int) ((double) dur / totalDuration * (rightW - 4)));
            int subY = area.y + 2;
            int subH = area.h - 4;

            /* 防止超出右边界 */
            if (subX + subW > area.ex() - 1)
            {
                subW = area.ex() - 1 - subX;
            }

            if (subW <= 0) continue;

            /* 选择颜色（循环调色板） */
            int color = SUB_COLORS[i % SUB_COLORS.length];
            int bgColor = Colors.A75 | color;

            /* 画子效果块背景 */
            context.batcher.box(subX, subY, subX + subW, subY + subH, bgColor);

            /* 画边框 */
            context.batcher.outline(subX, subY, subX + subW, subY + subH, SUB_BORDER);

            /* 画编号（如果块足够宽） */
            if (subW >= 12 && subH >= 10)
            {
                String label = String.valueOf(i + 1);
                int textX = subX + (subW - font.getWidth(label)) / 2;
                int textY = subY + (subH - font.getHeight()) / 2;

                context.batcher.textShadow(label, textX, textY);
            }
        }

        /* 3. 如果选中的话，在右半区域顶部画一个"打开编辑器"提示图标 */
        if (current && rightW >= 30)
        {
            /* 在右半区域右上角画一个小图标提示可以打开编辑器 */
            context.batcher.textShadow("...", rightX + rightW - 20, area.y + 2, Colors.WHITE);
        }
    }

    @Override
    public String getDefaultLabel(UIClips clips, BlockSplashComboActionClip clip)
    {
        int count = clip.getSubEffectCount();
        int blocks = clip.selection.getBakedCount();

        return "组合 (" + count + "效果, " + blocks + "方块)";
    }
}
