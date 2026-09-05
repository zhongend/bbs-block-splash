package com.example.bbsanimatedbreak.client;

import com.example.bbsanimatedbreak.RegionSelectionCache;
import com.example.bbsanimatedbreak.actions.BlockSplashReverseActionClip;
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
 * 方块飞溅（反向版本）ActionClip 的编辑面板
 *
 * 提供详细的反向散落+建造控制：
 * - 区域对角点
 * - 散落半径（随机模式下方块散落多远）
 * - 恢复速度（方块飞回原位的速度）
 * - 恢复延迟（建造波浪间隔）
 * - 恢复顺序（由近及远/由远及近/随机）
 * - 是否随机散落开关
 *   - 是 → 方块随机散落在空中
 *   - 否 → 显示集中坐标输入界面（所有方块从该坐标飞出建造）
 * - 是否启用旋转物理
 */
public class UIBlockSplashReverseActionClip extends UIActionClip<BlockSplashReverseActionClip>
{
    /* 恢复顺序选项 */
    private static final String[] ORDER_VALUES = {"near_to_far", "far_to_near", "random"};
    private static final String[] ORDER_LABELS = {"由近及远（波浪建造）", "由远及近", "随机顺序"};

    /* 区域第一个对角点 */
    public UITrackpad x;
    public UITrackpad y;
    public UITrackpad z;

    /* 区域第二个对角点 */
    public UITrackpad x2;
    public UITrackpad y2;
    public UITrackpad z2;

    /* 散落半径 */
    public UITrackpad scatterRadius;

    /* 恢复速度 */
    public UITrackpad recoverySpeed;

    /* 恢复延迟 */
    public UITrackpad recoveryDelay;

    /* 恢复顺序选择器 */
    public UICirculate recoveryOrder;

    /* 是否随机散落开关 */
    public UIToggle randomSplash;

    /* 集中坐标 */
    public UITrackpad concentrateX;
    public UITrackpad concentrateY;
    public UITrackpad concentrateZ;

    /* 集中坐标相关的 UI 元素引用（用于整体显示/隐藏） */
    private UIElement concentrateLabel;
    private UIElement concentrateRow;
    private UIElement concentrateTip;
    private UIElement concentratePasteBtn;

    /* 是否启用旋转物理 */
    public UIToggle enableRotation;

    /* 归位旋转平滑过渡开关 */
    public UIToggle smoothRotationStop;

    /* 旋转减速距离 */
    public UITrackpad rotationStopDistance;

    /* 归零动画持续时间 */
    public UITrackpad rotationResetDuration;

    /* 恢复时暂停方块间碰撞模拟开关 */
    public UIToggle disableCollisionDuringRecovery;

    /* 保留动画方块时长（修复闪烁） */
    public UITrackpad animationKeepDuration;

    /* 旋转相关 UI 元素引用（用于整体显示/隐藏，仅启用旋转时显示） */
    private UIElement smoothRotationLabel;
    private UIElement smoothRotationRow;
    private UIElement smoothRotationTip;
    private UIElement stopDistLabel;
    private UIElement stopDistRow;
    private UIElement stopDistTip;
    private UIElement resetDurLabel;
    private UIElement resetDurRow;
    private UIElement resetDurTip;

    /* 粘贴区域坐标按钮 */
    public UIButton pasteCoords;

    public UIBlockSplashReverseActionClip(BlockSplashReverseActionClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        // 区域第一个对角点
        this.x = new UITrackpad((v) -> this.clip.x.set(v.intValue())).integer().block();
        this.y = new UITrackpad((v) -> this.clip.y.set(v.intValue())).integer().block();
        this.z = new UITrackpad((v) -> this.clip.z.set(v.intValue())).integer().block();

        // 区域第二个对角点
        this.x2 = new UITrackpad((v) -> this.clip.x2.set(v.intValue())).integer().block();
        this.y2 = new UITrackpad((v) -> this.clip.y2.set(v.intValue())).integer().block();
        this.z2 = new UITrackpad((v) -> this.clip.z2.set(v.intValue())).integer().block();

        // 散落半径
        this.scatterRadius = new UITrackpad((v) -> this.clip.scatterRadius.set(v));
        this.scatterRadius.limit(1D, 20D);

        // 恢复速度
        this.recoverySpeed = new UITrackpad((v) -> this.clip.recoverySpeed.set(v));
        this.recoverySpeed.limit(0.1D, 5D);

        // 恢复延迟
        this.recoveryDelay = new UITrackpad((v) -> this.clip.recoveryDelay.set(v.intValue())).integer();
        this.recoveryDelay.limit(0D, 20D);

        // 恢复顺序选择器
        this.recoveryOrder = new UICirculate((circulate) ->
        {
            int index = circulate.getValue();
            if (index >= 0 && index < ORDER_VALUES.length)
            {
                this.clip.recoveryOrder.set(ORDER_VALUES[index]);
            }
        });
        for (String label : ORDER_LABELS)
        {
            this.recoveryOrder.addLabel(IKey.raw(label));
        }

        // 是否随机散落开关
        this.randomSplash = new UIToggle(IKey.raw("随机散落"), (toggle) ->
        {
            boolean isRandom = toggle.getValue();
            this.clip.randomSplash.set(isRandom);

            // 关键：根据开关值动态显示/隐藏集中坐标界面
            // 开启=随机散落，隐藏集中坐标
            // 关闭=集中散落，显示集中坐标
            this.updateConcentrateUIVisibility(!isRandom);
        });

        // 集中坐标
        this.concentrateX = new UITrackpad((v) -> this.clip.concentrateX.set(v.intValue())).integer().block();
        this.concentrateY = new UITrackpad((v) -> this.clip.concentrateY.set(v.intValue())).integer().block();
        this.concentrateZ = new UITrackpad((v) -> this.clip.concentrateZ.set(v.intValue())).integer().block();

        // 是否启用旋转物理
        this.enableRotation = new UIToggle(IKey.raw("启用旋转物理"), (toggle) ->
        {
            boolean enabled = toggle.getValue();
            this.clip.enableRotation.set(enabled);
            // 根据旋转开关动态显示/隐藏旋转平滑过渡选项
            this.updateRotationUIVisibility(enabled);
        });

        // 归位旋转平滑过渡开关
        this.smoothRotationStop = new UIToggle(IKey.raw("归位旋转平滑过渡"), (toggle) ->
        {
            this.clip.smoothRotationStop.set(toggle.getValue());
        });

        // 旋转减速距离
        this.rotationStopDistance = new UITrackpad((v) -> this.clip.rotationStopDistance.set(v));
        this.rotationStopDistance.limit(0.5D, 10D);

        // 归零动画持续时间
        this.rotationResetDuration = new UITrackpad((v) -> this.clip.rotationResetDuration.set(v.intValue())).integer();
        this.rotationResetDuration.limit(5D, 200D);

        // 恢复时暂停方块间碰撞模拟开关
        this.disableCollisionDuringRecovery = new UIToggle(IKey.raw("恢复时暂停碰撞"), (toggle) ->
        {
            this.clip.disableCollisionDuringRecovery.set(toggle.getValue());
        });

        // 保留动画方块时长（修复闪烁）
        this.animationKeepDuration = new UITrackpad((v) -> this.clip.animationKeepDuration.set(v.intValue())).integer();
        this.animationKeepDuration.limit(5D, 200D);

        // 粘贴区域坐标按钮
        this.pasteCoords = new UIButton(IKey.raw("粘贴木棍选择的坐标"), (btn) ->
        {
            this.pasteSelectedCoords();
        });
    }

    /**
     * 更新旋转平滑过渡界面的可见性（仅启用旋转物理时显示）
     */
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
     * 更新集中坐标界面的可见性
     */
    private void updateConcentrateUIVisibility(boolean visible)
    {
        if (this.concentrateLabel != null)
        {
            this.concentrateLabel.setVisible(visible);
        }
        if (this.concentrateRow != null)
        {
            this.concentrateRow.setVisible(visible);
        }
        if (this.concentrateTip != null)
        {
            this.concentrateTip.setVisible(visible);
        }
        if (this.concentratePasteBtn != null)
        {
            this.concentratePasteBtn.setVisible(visible);
        }
    }

    /**
     * 从 RegionSelectionCache 读取木棍选择的坐标，填入区域输入框
     */
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
                msg = "坐标已粘贴！区域共 " + count + " 个方块";
            }
            else if (pos1 != null)
            {
                msg = "第一个点已粘贴，请选择第二个点";
            }
            else if (pos2 != null)
            {
                msg = "第二个点已粘贴，请选择第一个点";
            }
            this.getContext().notifySuccess(IKey.raw(msg));
        }
    }

    /**
     * 把木棍选择的第一个点填入集中坐标
     */
    private void pasteConcentrateCoords()
    {
        class_2338 pos1 = RegionSelectionCache.getPos1();

        if (pos1 == null)
        {
            this.getContext().notifyError(IKey.raw("请先用木棍左键选择集中坐标点！"));
            return;
        }

        this.clip.concentrateX.set(pos1.method_10263());
        this.clip.concentrateY.set(pos1.method_10264());
        this.clip.concentrateZ.set(pos1.method_10260());
        this.concentrateX.setValue(pos1.method_10263());
        this.concentrateY.setValue(pos1.method_10264());
        this.concentrateZ.setValue(pos1.method_10260());

        this.getContext().notifySuccess(IKey.raw("集中坐标已粘贴：" + pos1.method_10263() + ", " + pos1.method_10264() + ", " + pos1.method_10260()));
    }

    /**
     * 计算区域内方块数量
     */
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

        // 散落半径
        this.panels.add(UI.label(IKey.raw("散落半径")).marginBottom(4).marginTop(8));
        this.panels.add(this.scatterRadius);
        this.panels.add(UI.label(IKey.raw("3=近, 6=中, 10=远")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("随机模式下方块散落多远")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("仅在\"随机散落\"开启时生效")).marginTop(4));

        // 恢复速度
        this.panels.add(UI.label(IKey.raw("恢复速度")).marginBottom(4).marginTop(8));
        this.panels.add(this.recoverySpeed);
        this.panels.add(UI.label(IKey.raw("0.6=慢, 1.5=中, 2.5=快")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("方块飞回原位的速度")).marginTop(4));

        // 恢复延迟
        this.panels.add(UI.label(IKey.raw("恢复延迟")).marginBottom(4).marginTop(8));
        this.panels.add(this.recoveryDelay);
        this.panels.add(UI.label(IKey.raw("0=同时恢复, 2=波浪, 5=明显波浪")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("每个方块恢复的间隔 tick")).marginTop(4));

        // 恢复顺序
        this.panels.add(UI.label(IKey.raw("恢复顺序")).marginBottom(4).marginTop(8));
        this.panels.add(this.recoveryOrder);
        this.panels.add(UI.label(IKey.raw("由近及远=从起点波浪建造")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("由远及近=反向, 随机=乱序")).marginTop(4));

        // 是否随机散落
        this.panels.add(UI.label(IKey.raw("散落模式")).marginBottom(4).marginTop(8));
        this.panels.add(this.randomSplash);
        this.panels.add(UI.label(IKey.raw("开启=方块随机散落各处后飞回")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("关闭=从集中坐标飞出建造")).marginTop(4));

        // 集中坐标（仅 randomSplash=false 时显示，默认隐藏）
        this.concentrateLabel = UI.label(IKey.raw("集中坐标 (X/Y/Z)")).marginBottom(4).marginTop(8);
        this.concentrateRow = UI.row(this.concentrateX, this.concentrateY, this.concentrateZ);
        this.concentrateTip = UI.label(IKey.raw("所有方块从此处飞向原位")).marginTop(4);
        this.concentratePasteBtn = new UIButton(IKey.raw("用木棍第一个点作为集中坐标"), (btn) ->
        {
            this.pasteConcentrateCoords();
        });

        this.panels.add(this.concentrateLabel);
        this.panels.add(this.concentrateRow);
        this.panels.add(this.concentrateTip);
        this.panels.add(this.concentratePasteBtn);

        // 默认隐藏集中坐标界面（randomSplash 默认为 true）
        this.updateConcentrateUIVisibility(false);

        // 旋转物理
        this.panels.add(UI.label(IKey.raw("旋转物理")).marginBottom(4).marginTop(8));
        this.panels.add(this.enableRotation);
        this.panels.add(UI.label(IKey.raw("开启后方块在恢复过程中会旋转")).marginTop(4));

        // 归位旋转平滑过渡（仅启用旋转物理时显示）
        this.smoothRotationLabel = UI.label(IKey.raw("归位旋转平滑过渡")).marginBottom(4).marginTop(8);
        this.smoothRotationRow = this.smoothRotationStop;
        this.smoothRotationTip = UI.label(IKey.raw("接近原位时旋转减速并归零")).marginTop(4);

        this.panels.add(this.smoothRotationLabel);
        this.panels.add(this.smoothRotationRow);
        this.panels.add(this.smoothRotationTip);

        // 旋转减速距离
        this.panels.add(UI.label(IKey.raw("旋转减速距离")).marginBottom(4).marginTop(8));
        this.panels.add(this.rotationStopDistance);
        this.panels.add(UI.label(IKey.raw("1.5=近, 3=中, 5=远")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("距离越大减速越早越平滑")).marginTop(4));

        // 归零动画持续时间
        this.resetDurLabel = UI.label(IKey.raw("归零动画持续时间 (tick)")).marginBottom(4).marginTop(8);
        this.resetDurRow = this.rotationResetDuration;
        this.resetDurTip = UI.label(IKey.raw("40=2秒, 越大归零越慢越平滑")).marginTop(4);

        this.panels.add(this.resetDurLabel);
        this.panels.add(this.resetDurRow);
        this.panels.add(this.resetDurTip);

        // 默认隐藏旋转平滑过渡选项（enableRotation 默认为 false）
        this.updateRotationUIVisibility(false);

        // 恢复时暂停方块间碰撞模拟（修复多块飞回相近位置时互相推开错位）
        this.panels.add(UI.label(IKey.raw("碰撞控制")).marginBottom(4).marginTop(8));
        this.panels.add(this.disableCollisionDuringRecovery);
        this.panels.add(UI.label(IKey.raw("开启=恢复中暂停方块间碰撞")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("避免多块飞回原位时互相推开错位")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("关闭=保留物理碰撞（可能错位）")).marginTop(4));

        // 保留动画方块时长（修复变方块瞬间闪烁）
        this.panels.add(UI.label(IKey.raw("保留动画时长 (tick)")).marginBottom(4).marginTop(8));
        this.panels.add(this.animationKeepDuration);
        this.panels.add(UI.label(IKey.raw("60=3秒, 越大越不容易闪烁")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("动画方块到达原位后保留的时间")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("期间与方块实体重叠，修复空档闪烁")).marginTop(4));

        // 使用提示
        this.panels.add(UI.label(IKey.raw("提示：效果一开始方块就已散落")).marginTop(12));
        this.panels.add(UI.label(IKey.raw("无需飞溅散开动画")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("散落方块按顺序飞回原位")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("类似自动建造效果")).marginTop(4));
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

        this.scatterRadius.setValue(this.clip.scatterRadius.get());
        this.recoverySpeed.setValue(this.clip.recoverySpeed.get());
        this.recoveryDelay.setValue(this.clip.recoveryDelay.get());

        // 填充恢复顺序
        String currentOrder = (String) this.clip.recoveryOrder.get();
        int orderIndex = 0;
        for (int i = 0; i < ORDER_VALUES.length; i++)
        {
            if (ORDER_VALUES[i].equals(currentOrder))
            {
                orderIndex = i;
                break;
            }
        }
        this.recoveryOrder.setValue(orderIndex, 1);

        // 填充随机散落开关
        boolean isRandom = (Boolean) this.clip.randomSplash.get();
        this.randomSplash.setValue(isRandom);

        // 填充集中坐标
        this.concentrateX.setValue(this.clip.concentrateX.get());
        this.concentrateY.setValue(this.clip.concentrateY.get());
        this.concentrateZ.setValue(this.clip.concentrateZ.get());

        // 根据随机散落开关同步集中坐标可见性
        this.updateConcentrateUIVisibility(!isRandom);

        // 填充旋转物理开关
        boolean enableRotation = (Boolean) this.clip.enableRotation.get();
        this.enableRotation.setValue(enableRotation);

        // 填充归位旋转平滑过渡开关
        this.smoothRotationStop.setValue((Boolean) this.clip.smoothRotationStop.get());

        // 填充旋转减速距离
        this.rotationStopDistance.setValue(this.clip.rotationStopDistance.get());

        // 填充归零动画持续时间
        this.rotationResetDuration.setValue(this.clip.rotationResetDuration.get());

        // 填充恢复时暂停碰撞开关
        this.disableCollisionDuringRecovery.setValue((Boolean) this.clip.disableCollisionDuringRecovery.get());

        // 填充保留动画方块时长
        this.animationKeepDuration.setValue(this.clip.animationKeepDuration.get());

        // 根据旋转物理开关同步旋转平滑过渡界面可见性
        this.updateRotationUIVisibility(enableRotation);
    }
}