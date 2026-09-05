package com.example.bbsanimatedbreak.client;

import com.example.bbsanimatedbreak.RegionSelectionCache;
import com.example.bbsanimatedbreak.actions.BlockPathActionClip;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.clips.actions.UIActionClip;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import net.minecraft.class_2338;

/**
 * 方块路径运动 ActionClip 的编辑面板
 *
 * 提供路径运动控制：
 * - 区域对角点（方块来源）
 * - 运动速度、是否循环
 * - 插值类型选择（Catmull-Rom/B样条/线性/三次）
 * - 物理模拟（旋转+碰撞）
 * - "编辑路径"按钮 → 打开独立全屏路径编辑器
 */
public class UIBlockPathActionClip extends UIActionClip<BlockPathActionClip>
{
    /* 插值类型选项 */
    private static final String[] INTERP_VALUES = {"catmull_rom", "b_spline", "linear", "cubic"};
    private static final String[] INTERP_LABELS = {"Catmull-Rom（丝滑穿过）", "B样条（更平滑）", "线性（直线）", "三次（锐利）"};

    /* 区域第一个对角点 */
    public UITrackpad x, y, z;

    /* 区域第二个对角点 */
    public UITrackpad x2, y2, z2;

    /* 运动速度 */
    public UITrackpad speed;

    /* 是否循环 */
    public UIToggle loop;

    /* 方块集中程度 */
    public UITrackpad concentration;

    /* 分散程度（散落半径） */
    public UITrackpad scatterRadius;

    /* 插值类型选择器 */
    public UICirculate interpolation;

    /* 是否启用旋转物理 */
    public UIToggle enableRotation;

    /* 是否启用碰撞检测 */
    public UIToggle enableCollision;

    /* 旋转平滑停止 */
    public UIToggle smoothRotationStop;

    /* 旋转停止距离 */
    public UITrackpad rotationStopDistance;

    /* 旋转归零时长 */
    public UITrackpad rotationResetDuration;

    /* 粘贴区域坐标按钮 */
    public UIButton pasteCoords;

    /* 编辑路径按钮 */
    public UIButton editPath;

    /* 旋转相关 UI 元素引用 */
    private UIElement rotationDetailLabel;
    private UIElement rotationDetailRow1;
    private UIElement rotationDetailRow2;
    private UIElement rotationDetailTip;

    public UIBlockPathActionClip(BlockPathActionClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.x = new UITrackpad((v) -> this.clip.x.set(v.intValue())).integer().block();
        this.y = new UITrackpad((v) -> this.clip.y.set(v.intValue())).integer().block();
        this.z = new UITrackpad((v) -> this.clip.z.set(v.intValue())).integer().block();

        this.x2 = new UITrackpad((v) -> this.clip.x2.set(v.intValue())).integer().block();
        this.y2 = new UITrackpad((v) -> this.clip.y2.set(v.intValue())).integer().block();
        this.z2 = new UITrackpad((v) -> this.clip.z2.set(v.intValue())).integer().block();

        this.speed = new UITrackpad((v) -> this.clip.speed.set(v));
        this.speed.limit(0.1D, 10D);

        this.loop = new UIToggle(IKey.raw("循环运动"), (toggle) ->
        {
            this.clip.loop.set(toggle.getValue());
        });

        this.concentration = new UITrackpad((v) -> this.clip.concentration.set(v));
        this.concentration.limit(0D, 1D);

        this.scatterRadius = new UITrackpad((v) -> this.clip.scatterRadius.set(v));
        this.scatterRadius.limit(0D, 20D);

        this.interpolation = new UICirculate((circulate) ->
        {
            int index = circulate.getValue();
            if (index >= 0 && index < INTERP_VALUES.length)
            {
                this.clip.interpolation.set(INTERP_VALUES[index]);
            }
        });
        for (String label : INTERP_LABELS)
        {
            this.interpolation.addLabel(IKey.raw(label));
        }

        this.enableRotation = new UIToggle(IKey.raw("启用旋转物理"), (toggle) ->
        {
            boolean enabled = toggle.getValue();
            this.clip.enableRotation.set(enabled);
            this.updateRotationUIVisibility(enabled);
        });

        this.enableCollision = new UIToggle(IKey.raw("启用碰撞检测"), (toggle) ->
        {
            this.clip.enableCollision.set(toggle.getValue());
        });

        this.smoothRotationStop = new UIToggle(IKey.raw("旋转平滑停止"), (toggle) ->
        {
            this.clip.smoothRotationStop.set(toggle.getValue());
        });

        this.rotationStopDistance = new UITrackpad((v) -> this.clip.rotationStopDistance.set(v));
        this.rotationStopDistance.limit(0.5D, 10D);

        this.rotationResetDuration = new UITrackpad((v) -> this.clip.rotationResetDuration.set(v.intValue())).integer();
        this.rotationResetDuration.limit(5D, 200D);

        this.pasteCoords = new UIButton(IKey.raw("粘贴木棍选择的坐标"), (btn) ->
        {
            this.pasteSelectedCoords();
        });

        this.editPath = new UIButton(IKey.raw("编辑路径（点击进入路径编辑器）"), (btn) ->
        {
            UIScreen.open(new UIPathEditorMenu(this.clip));
        });
    }

    private void updateRotationUIVisibility(boolean visible)
    {
        if (this.rotationDetailLabel != null) this.rotationDetailLabel.setVisible(visible);
        if (this.rotationDetailRow1 != null) this.rotationDetailRow1.setVisible(visible);
        if (this.rotationDetailRow2 != null) this.rotationDetailRow2.setVisible(visible);
        if (this.rotationDetailTip != null) this.rotationDetailTip.setVisible(visible);
    }

    private void pasteSelectedCoords()
    {
        class_2338 pos1 = RegionSelectionCache.getPos1();
        class_2338 pos2 = RegionSelectionCache.getPos2();

        if (pos1 == null && pos2 == null)
        {
            this.getContext().notifyError(IKey.raw("请先用木棍选择坐标！"));
            return;
        }

        if (pos1 != null)
        {
            this.clip.x.set(pos1.method_10263());
            this.clip.y.set(pos1.method_10264());
            this.clip.z.set(pos1.method_10260());
            this.x.setValue(pos1.method_10263());
            this.y.setValue(pos1.method_10264());
            this.z.setValue(pos1.method_10260());
        }

        if (pos2 != null)
        {
            this.clip.x2.set(pos2.method_10263());
            this.clip.y2.set(pos2.method_10264());
            this.clip.z2.set(pos2.method_10260());
            this.x2.setValue(pos2.method_10263());
            this.y2.setValue(pos2.method_10264());
            this.z2.setValue(pos2.method_10260());
        }

        this.getContext().notifySuccess(IKey.raw("坐标已粘贴！"));
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(UI.label(IKey.raw("区域对角点 1 (X/Y/Z)")).marginBottom(4));
        this.panels.add(UI.row(this.x, this.y, this.z));

        this.panels.add(UI.label(IKey.raw("区域对角点 2 (X/Y/Z)")).marginBottom(4).marginTop(8));
        this.panels.add(UI.row(this.x2, this.y2, this.z2));

        this.panels.add(this.pasteCoords);
        this.panels.add(UI.label(IKey.raw("提示：用木棍左键选点1，右键选点2")).marginTop(4));

        /* 路径编辑入口 */
        this.panels.add(UI.label(IKey.raw("路径编辑")).marginBottom(4).marginTop(8));
        this.panels.add(this.editPath);
        this.panels.add(UI.label(IKey.raw("点击进入路径编辑器")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("在3D世界中添加标记点")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("系统自动生成丝滑曲线")).marginTop(4));

        /* 运动参数 */
        this.panels.add(UI.label(IKey.raw("运动速度")).marginBottom(4).marginTop(8));
        this.panels.add(this.speed);
        this.panels.add(UI.label(IKey.raw("0.5=慢, 1=中, 3=快")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("每 tick 沿曲线前进的方块距离")).marginTop(4));

        this.panels.add(UI.label(IKey.raw("循环运动")).marginBottom(4).marginTop(8));
        this.panels.add(this.loop);
        this.panels.add(UI.label(IKey.raw("开启=到终点后回到起点继续")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("关闭=到终点后变回方块")).marginTop(4));

        /* 集中程度 */
        this.panels.add(UI.label(IKey.raw("方块集中程度")).marginBottom(4).marginTop(8));
        this.panels.add(this.concentration);
        this.panels.add(UI.label(IKey.raw("1=集中：贪吃蛇排队前进")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("0=离散：风暴效果散落曲线周围")).marginTop(4));

        /* 分散程度 */
        this.panels.add(UI.label(IKey.raw("分散程度（散落半径）")).marginBottom(4).marginTop(8));
        this.panels.add(this.scatterRadius);
        this.panels.add(UI.label(IKey.raw("0=不散落，完全跟随曲线")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("20=最大散落范围")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("配合集中程度使用：集中低+分散大=风暴")).marginTop(4));

        /* 插值类型 */
        this.panels.add(UI.label(IKey.raw("曲线插值类型")).marginBottom(4).marginTop(8));
        this.panels.add(this.interpolation);
        this.panels.add(UI.label(IKey.raw("Catmull-Rom=丝滑穿过所有点")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("B样条=更平滑但不穿过点")).marginTop(4));

        /* 物理模拟 */
        this.panels.add(UI.label(IKey.raw("物理模拟")).marginBottom(4).marginTop(8));
        this.panels.add(this.enableRotation);
        this.panels.add(UI.label(IKey.raw("开启后方块运动时旋转")).marginTop(4));

        this.panels.add(this.enableCollision);
        this.panels.add(UI.label(IKey.raw("开启后方块间碰撞检测")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("防止方块重叠")).marginTop(4));

        /* 旋转详细参数 */
        this.rotationDetailLabel = UI.label(IKey.raw("旋转参数")).marginBottom(4).marginTop(8);
        this.rotationDetailRow1 = this.smoothRotationStop;
        this.rotationDetailRow2 = UI.row(this.rotationStopDistance, this.rotationResetDuration);
        this.rotationDetailTip = UI.label(IKey.raw("停止距离/归零时长(tick)")).marginTop(4);

        this.panels.add(this.rotationDetailLabel);
        this.panels.add(this.rotationDetailRow1);
        this.panels.add(this.rotationDetailRow2);
        this.panels.add(this.rotationDetailTip);

        this.updateRotationUIVisibility(false);
    }

    @Override
    public void fillData()
    {
        super.fillData();

        this.x.setValue(this.clip.x.get());
        this.y.setValue(this.clip.y.get());
        this.z.setValue(this.clip.z.get());
        this.x2.setValue(this.clip.x2.get());
        this.y2.setValue(this.clip.y2.get());
        this.z2.setValue(this.clip.z2.get());

        this.speed.setValue(this.clip.speed.get());
        this.loop.setValue((Boolean) this.clip.loop.get());
        this.concentration.setValue(this.clip.concentration.get());
        this.scatterRadius.setValue(this.clip.scatterRadius.get());

        String currentInterp = (String) this.clip.interpolation.get();
        int interpIndex = 0;
        for (int i = 0; i < INTERP_VALUES.length; i++)
        {
            if (INTERP_VALUES[i].equals(currentInterp))
            {
                interpIndex = i;
                break;
            }
        }
        this.interpolation.setValue(interpIndex, 1);

        boolean rotation = (Boolean) this.clip.enableRotation.get();
        this.enableRotation.setValue(rotation);
        this.enableCollision.setValue((Boolean) this.clip.enableCollision.get());
        this.smoothRotationStop.setValue((Boolean) this.clip.smoothRotationStop.get());
        this.rotationStopDistance.setValue(this.clip.rotationStopDistance.get());
        this.rotationResetDuration.setValue(this.clip.rotationResetDuration.get());

        this.updateRotationUIVisibility(rotation);
    }
}
