package com.example.bbsanimatedbreak.client;

import com.example.bbsanimatedbreak.RegionSelectionCache;
import com.example.bbsanimatedbreak.actions.BlockSplashActionClip;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.clips.actions.UIActionClip;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import net.minecraft.class_2338;

/**
 * 区域方块飞溅 ActionClip 的编辑面板
 */
public class UIBlockSplashActionClip extends UIActionClip<BlockSplashActionClip>
{
    private static final String[] SHAPE_VALUES = {"ray", "spiral", "sphere", "arc"};
    private static final String[] SHAPE_LABELS = {"射线型", "螺旋型", "球型", "弧型"};

    public UITrackpad x;
    public UITrackpad y;
    public UITrackpad z;
    public UITrackpad x2;
    public UITrackpad y2;
    public UITrackpad z2;
    public UITrackpad power;
    public UITrackpad dirX;
    public UITrackpad dirY;
    public UITrackpad dirZ;
    public UIToggle collision;
    public UICirculate shape;
    public UIToggle rotation;
    public UIToggle smoothRotationStop;
    public UITrackpad rotationStopDistance;
    public UITrackpad rotationResetDuration;
    public UIButton pasteCoords;

    /* 方块实体化开关 + 动画持续时间 */
    public UIToggle solidify;
    public UITrackpad animationDuration;

    /* Sable 物理模拟开关（开启时使用 PhysicsBlockEntity 跑真实刚体物理） */
    public UIToggle sable;

    /* 旋转相关 UI 元素引用（仅启用旋转时显示） */
    private UIElement smoothRotationLabel;
    private UIElement smoothRotationRow;
    private UIElement smoothRotationTip;
    private UIElement stopDistLabel;
    private UIElement stopDistRow;
    private UIElement stopDistTip;
    private UIElement resetDurLabel;
    private UIElement resetDurRow;
    private UIElement resetDurTip;

    /* 动画持续时间 UI 元素引用（仅 solidify=false 时显示） */
    private UIElement animDurLabel;
    private UIElement animDurRow;
    private UIElement animDurTip;

    public UIBlockSplashActionClip(BlockSplashActionClip clip, IUIClipsDelegate editor)
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

        this.power = new UITrackpad((v) -> this.clip.power.set(v));
        this.power.limit(0D, 5D);

        this.dirX = new UITrackpad((v) -> this.clip.dirX.set(v));
        this.dirY = new UITrackpad((v) -> this.clip.dirY.set(v));
        this.dirZ = new UITrackpad((v) -> this.clip.dirZ.set(v));

        this.collision = new UIToggle(IKey.raw("启用实体碰撞"), (toggle) ->
        {
            this.clip.collision.set(toggle.getValue());
        });

        this.shape = new UICirculate((circulate) ->
        {
            int index = circulate.getValue();
            if (index >= 0 && index < SHAPE_VALUES.length)
            {
                this.clip.shape.set(SHAPE_VALUES[index]);
            }
        });
        for (String label : SHAPE_LABELS)
        {
            this.shape.addLabel(IKey.raw(label));
        }

        this.rotation = new UIToggle(IKey.raw("启用旋转物理"), (toggle) ->
        {
            boolean enabled = toggle.getValue();
            this.clip.rotation.set(enabled);
            this.updateRotationUIVisibility(enabled);
        });

        this.smoothRotationStop = new UIToggle(IKey.raw("归位旋转平滑过渡"), (toggle) ->
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

        // 方块实体化开关（true=飞溅后变回实体方块，false=保持动画形式后缩小消失）
        this.solidify = new UIToggle(IKey.raw("方块实体化"), (toggle) ->
        {
            boolean solidifyEnabled = toggle.getValue();
            this.clip.solidify.set(solidifyEnabled);
            // solidify=false 时显示动画持续时间调节
            this.updateSolidifyUIVisibility(!solidifyEnabled);
        });

        // 方块动画持续时间（秒，仅在 solidify=false 时显示）
        this.animationDuration = new UITrackpad((v) -> this.clip.animationDuration.set(v));
        this.animationDuration.limit(0D, 9999D);

        // Sable 物理模拟开关
        this.sable = new UIToggle(IKey.raw("Sable 物理模拟"), (toggle) ->
        {
            this.clip.sableEnabled.set(toggle.getValue());
        });
    }

    private void updateRotationUIVisibility(boolean visible)
    {
        if (this.smoothRotationLabel != null) this.smoothRotationLabel.setVisible(visible);
        if (this.smoothRotationRow != null) this.smoothRotationRow.setVisible(visible);
        if (this.smoothRotationTip != null) this.smoothRotationTip.setVisible(visible);
        if (this.stopDistLabel != null) this.stopDistLabel.setVisible(visible);
        if (this.stopDistRow != null) this.stopDistRow.setVisible(visible);
        if (this.stopDistTip != null) this.stopDistTip.setVisible(visible);
        if (this.resetDurLabel != null) this.resetDurLabel.setVisible(visible);
        if (this.resetDurRow != null) this.resetDurRow.setVisible(visible);
        if (this.resetDurTip != null) this.resetDurTip.setVisible(visible);
    }

    /**
     * 控制动画持续时间 UI 的可见性
     * 仅在 solidify=false（不实体化）时显示
     */
    private void updateSolidifyUIVisibility(boolean visible)
    {
        if (this.animDurLabel != null) this.animDurLabel.setVisible(visible);
        if (this.animDurRow != null) this.animDurRow.setVisible(visible);
        if (this.animDurTip != null) this.animDurTip.setVisible(visible);
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

        boolean pastedAny = false;

        if (pos1 != null)
        {
            this.clip.x.set(pos1.method_10263());
            this.clip.y.set(pos1.method_10264());
            this.clip.z.set(pos1.method_10260());
            this.x.setValue(pos1.method_10263());
            this.y.setValue(pos1.method_10264());
            this.z.setValue(pos1.method_10260());
            pastedAny = true;
        }

        if (pos2 != null)
        {
            this.clip.x2.set(pos2.method_10263());
            this.clip.y2.set(pos2.method_10264());
            this.clip.z2.set(pos2.method_10260());
            this.x2.setValue(pos2.method_10263());
            this.y2.setValue(pos2.method_10264());
            this.z2.setValue(pos2.method_10260());
            pastedAny = true;
        }

        if (pastedAny)
        {
            String msg = "坐标已粘贴！";
            if (pos1 != null && pos2 != null)
            {
                int count = this.countBlocksInArea(pos1, pos2);
                msg = "已粘贴！区域共 " + count + " 个方块";
            }
            else if (pos1 != null)
            {
                msg = "第一个点已粘贴，请选第二个点";
            }
            else if (pos2 != null)
            {
                msg = "第二个点已粘贴，请选第一个点";
            }
            this.getContext().notifySuccess(IKey.raw(msg));
        }
    }

    private int countBlocksInArea(class_2338 pos1, class_2338 pos2)
    {
        int minX = Math.min(pos1.method_10263(), pos2.method_10263());
        int minY = Math.min(pos1.method_10264(), pos2.method_10264());
        int minZ = Math.min(pos1.method_10260(), pos2.method_10260());
        int maxX = Math.max(pos1.method_10263(), pos2.method_10263());
        int maxY = Math.max(pos1.method_10264(), pos2.method_10264());
        int maxZ = Math.max(pos1.method_10260(), pos2.method_10260());

        return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        // 区域第一个对角点
        this.panels.add(UI.label(IKey.raw("区域对角点 1 (X/Y/Z)")).marginBottom(4));
        this.panels.add(UI.row(this.x, this.y, this.z));

        // 区域第二个对角点
        this.panels.add(UI.label(IKey.raw("区域对角点 2 (X/Y/Z)")).marginBottom(4).marginTop(8));
        this.panels.add(UI.row(this.x2, this.y2, this.z2));

        // 粘贴坐标按钮
        this.panels.add(UI.label(IKey.raw("快捷操作")).marginBottom(4).marginTop(8));
        this.panels.add(this.pasteCoords);
        this.panels.add(UI.label(IKey.raw("提示：先从创造栏拿")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("\"方块飞溅区域选择木棍\"")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("左键选第一个点，右键选第二个点")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("再点上面按钮粘贴")).marginTop(4));

        // 飞溅强度
        this.panels.add(UI.label(IKey.raw("飞溅强度")).marginBottom(4).marginTop(8));
        this.panels.add(this.power);
        this.panels.add(UI.label(IKey.raw("0.5=轻微, 1.0=中等, 2.0=猛烈")).marginTop(4));

        // 飞溅方向
        this.panels.add(UI.label(IKey.raw("飞溅方向 (dirX/dirY/dirZ)")).marginBottom(4).marginTop(8));
        this.panels.add(UI.row(this.dirX, this.dirY, this.dirZ));
        this.panels.add(UI.label(IKey.raw("(0,1,0)=向上, (1,0,0)=向东")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("方向会被归一化")).marginTop(4));

        // 实体碰撞开关
        this.panels.add(UI.label(IKey.raw("实体碰撞")).marginBottom(4).marginTop(8));
        this.panels.add(this.collision);
        this.panels.add(UI.label(IKey.raw("防止方块重叠穿透")).marginTop(4));

        // 飞溅形状选择
        this.panels.add(UI.label(IKey.raw("飞溅形状")).marginBottom(4).marginTop(8));
        this.panels.add(this.shape);
        this.panels.add(UI.label(IKey.raw("射线型=沿方向飞")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("螺旋型=绕轴旋转")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("球型=四面扩散, 弧型=抛物线")).marginTop(4));

        // 旋转物理开关
        this.panels.add(UI.label(IKey.raw("旋转物理")).marginBottom(4).marginTop(8));
        this.panels.add(this.rotation);
        this.panels.add(UI.label(IKey.raw("开启后方块飞行时会旋转")).marginTop(4));

        // 归位旋转平滑过渡（仅启用旋转时显示）
        this.smoothRotationLabel = UI.label(IKey.raw("归位旋转平滑过渡")).marginBottom(4).marginTop(8);
        this.smoothRotationRow = this.smoothRotationStop;
        this.smoothRotationTip = UI.label(IKey.raw("接近地面时旋转减速并归零")).marginTop(4);

        this.panels.add(this.smoothRotationLabel);
        this.panels.add(this.smoothRotationRow);
        this.panels.add(this.smoothRotationTip);

        // 旋转减速距离
        this.stopDistLabel = UI.label(IKey.raw("旋转减速距离")).marginBottom(4).marginTop(8);
        this.stopDistRow = this.rotationStopDistance;
        this.stopDistTip = UI.label(IKey.raw("1.5=近, 3=中, 5=远")).marginTop(4);

        this.panels.add(this.stopDistLabel);
        this.panels.add(this.stopDistRow);
        this.panels.add(this.stopDistTip);

        // 归零动画持续时间
        this.resetDurLabel = UI.label(IKey.raw("归零动画持续时间 (tick)")).marginBottom(4).marginTop(8);
        this.resetDurRow = this.rotationResetDuration;
        this.resetDurTip = UI.label(IKey.raw("40=2秒, 越大归零越慢越平滑")).marginTop(4);

        this.panels.add(this.resetDurLabel);
        this.panels.add(this.resetDurRow);
        this.panels.add(this.resetDurTip);

        // 默认隐藏旋转平滑过渡选项
        this.updateRotationUIVisibility(false);

        // 方块实体化开关
        this.panels.add(UI.label(IKey.raw("方块实体化")).marginBottom(4).marginTop(8));
        this.panels.add(this.solidify);
        this.panels.add(UI.label(IKey.raw("开启=飞溅后变回实体方块")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("关闭=保持动画形式，物理模拟后缩小消失")).marginTop(4));

        // 方块动画持续时间（仅在 solidify=false 时显示）
        this.animDurLabel = UI.label(IKey.raw("方块动画持续时间 (秒)")).marginBottom(4).marginTop(8);
        this.animDurRow = this.animationDuration;
        this.animDurTip = UI.label(IKey.raw("默认60秒，上限9999秒，到时间后缩小消失")).marginTop(4);

        this.panels.add(this.animDurLabel);
        this.panels.add(this.animDurRow);
        this.panels.add(this.animDurTip);

        // 默认隐藏动画持续时间（因为 solidify 默认 false，所以默认显示）
        // solidify 默认 false → 不实体化 → 显示动画持续时间
        this.updateSolidifyUIVisibility(true);

        // Sable 物理模拟开关
        this.panels.add(UI.label(IKey.raw("Sable 物理模拟")).marginBottom(4).marginTop(8));
        this.panels.add(this.sable);
        this.panels.add(UI.label(IKey.raw("开启=真实刚体物理（重力+AABB碰撞+弹跳+四元数旋转）")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("关闭=原版下落方块（仅下落+落地变方块）")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("Sable 开启时会记录每帧物理状态，供后续动画回放")).marginTop(4));

        // 使用提示
        this.panels.add(UI.label(IKey.raw("回放结束后方块自动恢复")).marginTop(12));
        this.panels.add(UI.label(IKey.raw("区域最多 30000 个方块")).marginTop(4));
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

        this.power.setValue(this.clip.power.get());

        this.dirX.setValue(this.clip.dirX.get());
        this.dirY.setValue(this.clip.dirY.get());
        this.dirZ.setValue(this.clip.dirZ.get());

        this.collision.setValue((Boolean) this.clip.collision.get());

        boolean enableRotation = (Boolean) this.clip.rotation.get();
        this.rotation.setValue(enableRotation);

        this.smoothRotationStop.setValue((Boolean) this.clip.smoothRotationStop.get());
        this.rotationStopDistance.setValue(this.clip.rotationStopDistance.get());
        this.rotationResetDuration.setValue(this.clip.rotationResetDuration.get());

        // 根据旋转开关同步旋转平滑过渡界面可见性
        this.updateRotationUIVisibility(enableRotation);

        // 方块实体化 + 动画持续时间
        boolean solidifyEnabled = (Boolean) this.clip.solidify.get();
        this.solidify.setValue(solidifyEnabled);
        this.animationDuration.setValue(this.clip.animationDuration.get());
        // solidify=false → 不实体化 → 显示动画持续时间
        this.updateSolidifyUIVisibility(!solidifyEnabled);

        String currentShape = (String) this.clip.shape.get();
        int shapeIndex = 0;
        for (int i = 0; i < SHAPE_VALUES.length; i++)
        {
            if (SHAPE_VALUES[i].equals(currentShape))
            {
                shapeIndex = i;
                break;
            }
        }
        this.shape.setValue(shapeIndex, 1);

        // Sable 物理模拟开关
        this.sable.setValue((Boolean) this.clip.sableEnabled.get());
    }
}
