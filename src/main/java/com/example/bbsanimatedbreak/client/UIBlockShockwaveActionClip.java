package com.example.bbsanimatedbreak.client;

import com.example.bbsanimatedbreak.RegionSelectionCache;
import com.example.bbsanimatedbreak.actions.BlockShockwaveActionClip;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.clips.actions.UIActionClip;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.UI;
import net.minecraft.class_2338;

/**
 * 方块振波 ActionClip 的编辑面板
 *
 * 提供详细的振波控制：
 * - 区域对角点
 * - 振波模式（圆形/长方形/十字/菱形/模拟地震/涟漪波纹/混沌乱震）
 * - 震动幅度（跳起高度）
 * - 波浪传播速度
 * - 震动持续时间
 * - 震动频率（跳起次数）
 * - 震动衰减
 * - 震动后恢复原状
 */
public class UIBlockShockwaveActionClip extends UIActionClip<BlockShockwaveActionClip>
{
    /* 振波模式选项 */
    private static final String[] MODE_VALUES = {"circle", "rectangle", "cross", "diamond", "earthquake", "ripple", "chaos"};
    private static final String[] MODE_LABELS = {"圆形振波", "长方形振波", "十字振波", "菱形振波", "模拟地震", "涟漪波纹", "混沌乱震"};

    /* 震动起始方向选项（仅 fromCenter=false 时生效） */
    private static final String[] DIR_VALUES = {"north", "south", "east", "west"};
    private static final String[] DIR_LABELS = {"北 (从北边开始)", "南 (从南边开始)", "东 (从东边开始)", "西 (从西边开始)"};

    /* 区域第一个对角点 */
    public UITrackpad x;
    public UITrackpad y;
    public UITrackpad z;

    /* 区域第二个对角点 */
    public UITrackpad x2;
    public UITrackpad y2;
    public UITrackpad z2;

    /* 震动幅度 */
    public UITrackpad amplitude;

    /* 波浪速度 */
    public UITrackpad waveSpeed;

    /* 震动持续时间 */
    public UITrackpad shakeDuration;

    /* 震动频率 */
    public UITrackpad shakeFrequency;

    /* 震动衰减 */
    public UITrackpad decay;

    /* 振波模式选择器 */
    public UICirculate mode;

    /* 是否从中心点开始震动开关 */
    public UIToggle fromCenter;

    /* 震动起始方向选择器（仅 fromCenter=false 时显示） */
    public UICirculate direction;

    /* 方向选择器相关的 UI 元素引用（用于整体显示/隐藏） */
    private UIElement directionLabel;
    private UIElement directionRow;
    private UIElement directionTip;

    /* 震动后恢复开关 */
    public UIToggle restoreAfter;

    /* 真实化角度开关（方块随冲击波力度倾斜，形成冲击坑效果） */
    public UIToggle realisticAngle;

    /* 冲击力度（控制倾斜角度大小，仅在 realisticAngle=true 时显示） */
    public UITrackpad impactForce;

    /* 力度调节相关的 UI 元素引用（用于整体显示/隐藏） */
    private UIElement impactForceLabel;
    private UIElement impactForceRow;
    private UIElement impactForceTip;

    /* 粘贴选择坐标按钮 */
    public UIButton pasteCoords;

    public UIBlockShockwaveActionClip(BlockShockwaveActionClip clip, IUIClipsDelegate editor)
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

        // 震动幅度
        this.amplitude = new UITrackpad((v) -> this.clip.amplitude.set(v));
        this.amplitude.limit(0D, 2D);

        // 波浪速度
        this.waveSpeed = new UITrackpad((v) -> this.clip.waveSpeed.set(v));
        this.waveSpeed.limit(0.1D, 5D);

        // 震动持续时间
        this.shakeDuration = new UITrackpad((v) -> this.clip.shakeDuration.set(v.intValue())).integer();
        this.shakeDuration.limit(1D, 100D);

        // 震动频率
        this.shakeFrequency = new UITrackpad((v) -> this.clip.shakeFrequency.set(v.intValue())).integer();
        this.shakeFrequency.limit(1D, 10D);

        // 震动衰减
        this.decay = new UITrackpad((v) -> this.clip.decay.set(v));
        this.decay.limit(0.1D, 0.95D);

        // 振波模式选择器
        this.mode = new UICirculate((circulate) ->
        {
            int index = circulate.getValue();
            if (index >= 0 && index < MODE_VALUES.length)
            {
                this.clip.mode.set(MODE_VALUES[index]);
            }
        });
        for (String label : MODE_LABELS)
        {
            this.mode.addLabel(IKey.raw(label));
        }

        // 是否从中心点开始震动开关
        this.fromCenter = new UIToggle(IKey.raw("从中心点开始震动"), (toggle) ->
        {
            boolean useCenter = toggle.getValue();
            this.clip.fromCenter.set(useCenter);

            // 关键：根据开关值动态显示/隐藏方向选择器界面
            // 开启=从中心震动，隐藏方向选择
            // 关闭=从方向震动，显示方向选择
            this.updateDirectionUIVisibility(!useCenter);
        });

        // 震动起始方向选择器（仅 fromCenter=false 时显示）
        this.direction = new UICirculate((circulate) ->
        {
            int index = circulate.getValue();
            if (index >= 0 && index < DIR_VALUES.length)
            {
                this.clip.direction.set(DIR_VALUES[index]);
            }
        });
        for (String label : DIR_LABELS)
        {
            this.direction.addLabel(IKey.raw(label));
        }

        // 震动后恢复开关
        this.restoreAfter = new UIToggle(IKey.raw("震动后恢复原状"), (toggle) ->
        {
            this.clip.restoreAfter.set(toggle.getValue());
        });

        // 真实化角度开关（方块随冲击波力度倾斜，形成冲击坑效果）
        this.realisticAngle = new UIToggle(IKey.raw("真实化角度"), (toggle) ->
        {
            boolean enabled = toggle.getValue();
            this.clip.realisticAngle.set(enabled);
            // 根据开关值动态显示/隐藏力度调节界面
            this.updateImpactForceUIVisibility(enabled);
        });

        // 冲击力度（控制倾斜角度大小）
        this.impactForce = new UITrackpad((v) -> this.clip.impactForce.set(v));
        this.impactForce.limit(0.1D, 5.0D);

        // 粘贴选择坐标按钮
        this.pasteCoords = new UIButton(IKey.raw("粘贴木棍选择的坐标"), (btn) ->
        {
            this.pasteSelectedCoords();
        });
    }

    /**
     * 更新力度调节界面的可见性
     *
     * @param visible true=显示力度调节界面（realisticAngle=true 时），false=隐藏（realisticAngle=false 时）
     */
    private void updateImpactForceUIVisibility(boolean visible)
    {
        if (this.impactForceLabel != null)
        {
            this.impactForceLabel.setVisible(visible);
        }
        if (this.impactForceRow != null)
        {
            this.impactForceRow.setVisible(visible);
        }
        if (this.impactForceTip != null)
        {
            this.impactForceTip.setVisible(visible);
        }
    }

    /**
     * 更新方向选择器界面的可见性
     *
     * @param visible true=显示方向选择界面（fromCenter=false 时），false=隐藏（fromCenter=true 时）
     */
    private void updateDirectionUIVisibility(boolean visible)
    {
        if (this.directionLabel != null)
        {
            this.directionLabel.setVisible(visible);
        }
        if (this.directionRow != null)
        {
            this.directionRow.setVisible(visible);
        }
        if (this.directionTip != null)
        {
            this.directionTip.setVisible(visible);
        }
    }

    /**
     * 从 RegionSelectionCache 读取木棍选择的坐标，填入输入框
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

        // 振波模式
        this.panels.add(UI.label(IKey.raw("振波模式")).marginBottom(4).marginTop(8));
        this.panels.add(this.mode);
        this.panels.add(UI.label(IKey.raw("圆形/长方形/十字/菱形=扩散形状")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("模拟地震=持续随机震动")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("涟漪波纹=正弦波幅度起伏")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("混沌乱震=完全随机方向和力度")).marginTop(4));

        // 是否从中心点开始震动
        this.panels.add(UI.label(IKey.raw("震动起点")).marginBottom(4).marginTop(8));
        this.panels.add(this.fromCenter);
        this.panels.add(UI.label(IKey.raw("开启=从区域中心向外扩散")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("关闭=从指定方向的边开始震动")).marginTop(4));

        // 震动起始方向（仅 fromCenter=false 时显示，默认隐藏）
        this.directionLabel = UI.label(IKey.raw("震动起始方向")).marginBottom(4).marginTop(8);
        this.directionRow = this.direction;
        this.directionTip = UI.label(IKey.raw("北=从北边(minZ)开始, 南=从南边(maxZ)开始")).marginTop(4);

        this.panels.add(this.directionLabel);
        this.panels.add(this.directionRow);
        this.panels.add(this.directionTip);
        this.panels.add(UI.label(IKey.raw("东=从东边(maxX)开始, 西=从西边(minX)开始")).marginTop(4));

        // 默认隐藏方向选择界面（fromCenter 默认为 true）
        this.updateDirectionUIVisibility(false);

        // 震动幅度
        this.panels.add(UI.label(IKey.raw("震动幅度")).marginBottom(4).marginTop(8));
        this.panels.add(this.amplitude);
        this.panels.add(UI.label(IKey.raw("0.2=轻微, 0.5=中等, 1.0=猛烈")).marginTop(4));

        // 波浪速度
        this.panels.add(UI.label(IKey.raw("波浪传播速度")).marginBottom(4).marginTop(8));
        this.panels.add(this.waveSpeed);
        this.panels.add(UI.label(IKey.raw("0.5=慢, 1.0=中, 2.0=快")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("速度越大波浪扩散越快")).marginTop(4));

        // 震动持续时间
        this.panels.add(UI.label(IKey.raw("震动持续时间 (tick)")).marginBottom(4).marginTop(8));
        this.panels.add(this.shakeDuration);
        this.panels.add(UI.label(IKey.raw("10=短, 20=中, 40=长")).marginTop(4));

        // 震动频率
        this.panels.add(UI.label(IKey.raw("震动频率")).marginBottom(4).marginTop(8));
        this.panels.add(this.shakeFrequency);
        this.panels.add(UI.label(IKey.raw("每个方块跳起次数")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("1=只回弹一次, 2=两次, 3=多次")).marginTop(4));

        // 震动衰减
        this.panels.add(UI.label(IKey.raw("震动衰减")).marginBottom(4).marginTop(8));
        this.panels.add(this.decay);
        this.panels.add(UI.label(IKey.raw("0.3=快速停止, 0.6=中, 0.9=缓慢停止")).marginTop(4));

        // 震动后恢复
        this.panels.add(UI.label(IKey.raw("震动后恢复原状")).marginBottom(4).marginTop(8));
        this.panels.add(this.restoreAfter);
        this.panels.add(UI.label(IKey.raw("关闭=保留倾斜冲击坑效果")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("开启=震动完恢复直立方块")).marginTop(4));

        // 真实化角度开关
        this.panels.add(UI.label(IKey.raw("真实化角度")).marginBottom(4).marginTop(8));
        this.panels.add(this.realisticAngle);
        this.panels.add(UI.label(IKey.raw("开启后方块随冲击波力度倾斜")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("形成冲击坑效果（像小行星撞击）")).marginTop(4));

        // 冲击力度（仅 realisticAngle=true 时显示，默认显示因为默认开启）
        this.impactForceLabel = UI.label(IKey.raw("冲击力度")).marginBottom(4).marginTop(8);
        this.impactForceRow = this.impactForce;
        this.impactForceTip = UI.label(IKey.raw("1.0=轻度(15度), 2.0=中度(30度), 3.0=猛烈(45度)")).marginTop(4);

        this.panels.add(this.impactForceLabel);
        this.panels.add(this.impactForceRow);
        this.panels.add(this.impactForceTip);
        this.panels.add(UI.label(IKey.raw("越靠近震源中心倾斜越大")).marginTop(4));

        // 使用提示
        this.panels.add(UI.label(IKey.raw("提示：振波从中心或指定方向扩散")).marginTop(12));
        this.panels.add(UI.label(IKey.raw("方块只原地上下震动")).marginTop(4));
        this.panels.add(UI.label(IKey.raw("不会水平移动")).marginTop(4));
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

        this.amplitude.setValue(this.clip.amplitude.get());
        this.waveSpeed.setValue(this.clip.waveSpeed.get());
        this.shakeDuration.setValue(this.clip.shakeDuration.get());
        this.shakeFrequency.setValue(this.clip.shakeFrequency.get());
        this.decay.setValue(this.clip.decay.get());

        // 填充振波模式
        String currentMode = (String) this.clip.mode.get();
        int modeIndex = 0;
        for (int i = 0; i < MODE_VALUES.length; i++)
        {
            if (MODE_VALUES[i].equals(currentMode))
            {
                modeIndex = i;
                break;
            }
        }
        this.mode.setValue(modeIndex, 1);

        // 填充从中心震动开关
        boolean useCenter = (Boolean) this.clip.fromCenter.get();
        this.fromCenter.setValue(useCenter);

        // 填充震动起始方向
        String currentDir = (String) this.clip.direction.get();
        int dirIndex = 0;
        for (int i = 0; i < DIR_VALUES.length; i++)
        {
            if (DIR_VALUES[i].equals(currentDir))
            {
                dirIndex = i;
                break;
            }
        }
        this.direction.setValue(dirIndex, 1);

        // 根据从中心震动开关同步方向选择器可见性
        // useCenter=true → 隐藏方向选择；useCenter=false → 显示方向选择
        this.updateDirectionUIVisibility(!useCenter);

        // 填充恢复开关
        this.restoreAfter.setValue((Boolean) this.clip.restoreAfter.get());

        // 填充真实化角度开关
        boolean angleEnabled = (Boolean) this.clip.realisticAngle.get();
        this.realisticAngle.setValue(angleEnabled);

        // 填充冲击力度
        this.impactForce.setValue(this.clip.impactForce.get());

        // 根据角度开关同步力度调节界面可见性
        this.updateImpactForceUIVisibility(angleEnabled);
    }
}
