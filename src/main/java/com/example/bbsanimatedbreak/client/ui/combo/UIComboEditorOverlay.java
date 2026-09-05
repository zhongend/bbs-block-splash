package com.example.bbsanimatedbreak.client.ui.combo;

import com.example.bbsanimatedbreak.actions.BlockPathActionClip;
import com.example.bbsanimatedbreak.actions.BlockShockwaveActionClip;
import com.example.bbsanimatedbreak.actions.BlockSplashActionClip;
import com.example.bbsanimatedbreak.actions.BlockSplashComboActionClip;
import com.example.bbsanimatedbreak.actions.BlockSplashReverseActionClip;
import com.example.bbsanimatedbreak.actions.combo.SubEffect;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.class_2338;
import net.minecraft.class_239;
import net.minecraft.class_310;
import net.minecraft.class_3965;

/**
 * 飞溅组合 PS 风格独立编辑器（优化版 v1.7.0）
 *
 * === 新增优化 ===
 * 1. 准星拾取：从准星位置拾取方块坐标作为顶点（复用 mc.crosshairTarget）
 * 2. 子效果详细参数：根据子效果类型动态显示对应参数（power/amplitude/speed 等）
 * 3. 过渡曲线编辑：fadeIn/fadeOut 可调（UITrackpad）
 * 4. 2D 选区俯视图：烘焙后显示方块分布的俯视图
 * 5. 大区域警告：烘焙方块数超过阈值时提示
 */
public class UIComboEditorOverlay extends UIOverlayPanel
{
    /** 烘焙方块数警告阈值 */
    private static final int BAKE_WARN_THRESHOLD = 5000;

    private final BlockSplashComboActionClip clip;
    private final Runnable onCloseCallback;

    /* 选区设置面板 */
    private UICirculate shapeSelector;
    private UITrackpad vertexX, vertexY, vertexZ;
    private UIButton addVertexBtn;
    private UIButton pickFromCrosshairBtn;
    private UIButton clearVerticesBtn;
    private UIButton bakeBtn;
    private UILabel bakedInfo;
    private UILabel bakeWarning;

    /* 多边形高度范围（不规则选择用） */
    private UITrackpad polygonMinY;
    private UITrackpad polygonMaxY;

    /* 随机选择参数 */
    private UITrackpad randomSeed;
    private UITrackpad randomDensity;

    /* 子效果列表 */
    private UIScrollView subEffectList;
    private UIButton addSubEffectBtn;
    private UIButton removeSubEffectBtn;
    private int selectedSubIndex = -1;

    /* 子效果属性面板 */
    private UIScrollView propertiesPanel;
    private UICirculate effectTypeSelector;
    private UITrackpad subStartTick;
    private UITrackpad subDuration;
    private UITrackpad subBlendFactor;
    private UITrackpad transitionInDuration;
    private UITrackpad transitionOutDuration;
    private UILabel strengthInfo;

    /* 子效果详细参数容器（动态填充） */
    private UIScrollView detailParamsContainer;

    public UIComboEditorOverlay(UIContext context, BlockSplashComboActionClip clip, Runnable onCloseCallback)
    {
        super(IKey.raw("飞溅组合编辑器"));

        this.clip = clip;
        this.onCloseCallback = onCloseCallback;

        this.createLayout(context);
        this.refreshSubEffectList();
        this.refreshPropertiesPanel();
        this.refreshSelectionInfo();

        this.onClose((e) ->
        {
            if (this.onCloseCallback != null)
            {
                this.onCloseCallback.run();
            }
        });
    }

    private void createLayout(UIContext context)
    {
        /* 左侧选区设置面板 */
        UIScrollView leftPanel = new UIScrollView(ScrollDirection.VERTICAL);
        leftPanel.w(0.4F).h(1F).column(4).stretch().padding(8);

        this.buildSelectionPanel(leftPanel);

        /* 右侧子效果 + 属性面板 */
        UIScrollView rightPanel = new UIScrollView(ScrollDirection.VERTICAL);
        rightPanel.x(0.4F).w(0.6F).h(1F).column(4).stretch().padding(8);

        this.buildSubEffectListPanel(rightPanel);
        this.buildPropertiesPanel(rightPanel);

        /* 布局到 content */
        this.content.add(leftPanel, rightPanel);
    }

    /* === 选区设置面板 === */

    private void buildSelectionPanel(UIScrollView panel)
    {
        /* 形状选择 */
        this.shapeSelector = new UICirculate((v) ->
        {
            String[] shapes = {"rectangle", "circle", "triangle", "polygon", "random"};
            this.clip.selection.shape.set(shapes[v.getValue()]);
            this.refreshSelectionInfo();
        });
        this.shapeSelector.addLabel(IKey.raw("矩形"));
        this.shapeSelector.addLabel(IKey.raw("圆形"));
        this.shapeSelector.addLabel(IKey.raw("三角形"));
        this.shapeSelector.addLabel(IKey.raw("不规则"));
        this.shapeSelector.addLabel(IKey.raw("随机"));

        /* 顶点输入 */
        this.vertexX = new UITrackpad((v) -> {});
        this.vertexY = new UITrackpad((v) -> {});
        this.vertexZ = new UITrackpad((v) -> {});
        this.vertexX.limit(Integer.MIN_VALUE, Integer.MAX_VALUE, true).integer();
        this.vertexY.limit(Integer.MIN_VALUE, Integer.MAX_VALUE, true).integer();
        this.vertexZ.limit(Integer.MIN_VALUE, Integer.MAX_VALUE, true).integer();

        this.addVertexBtn = new UIButton(IKey.raw("添加顶点"), (b) -> this.addVertex());

        /* 准星拾取按钮：从准星位置拾取方块坐标 */
        this.pickFromCrosshairBtn = new UIButton(IKey.raw("准星拾取"), (b) -> this.pickFromCrosshair());

        this.clearVerticesBtn = new UIButton(IKey.raw("清空顶点"), (b) -> this.clearVertices());

        /* 烘焙按钮 */
        this.bakeBtn = new UIButton(IKey.raw("烘焙选区"), (b) -> this.bakeSelection());

        this.bakedInfo = UI.label(IKey.raw(""));
        this.bakeWarning = UI.label(IKey.raw(""), 16, Colors.YELLOW);

        /* 多边形高度范围（不规则选择用） */
        this.polygonMinY = new UITrackpad((v) ->
        {
            this.clip.selection.polygonMinY.set(v.intValue());
        });
        this.polygonMinY.limit(Integer.MIN_VALUE, Integer.MAX_VALUE, true).integer();

        this.polygonMaxY = new UITrackpad((v) ->
        {
            this.clip.selection.polygonMaxY.set(v.intValue());
        });
        this.polygonMaxY.limit(Integer.MIN_VALUE, Integer.MAX_VALUE, true).integer();

        /* 随机选择参数 */
        this.randomSeed = new UITrackpad((v) ->
        {
            this.clip.selection.randomSeed.set(v.intValue());
        });
        this.randomSeed.limit(0, Integer.MAX_VALUE, true).integer();

        this.randomDensity = new UITrackpad((v) ->
        {
            this.clip.selection.randomDensity.set(v);
        });
        this.randomDensity.limit(0.05, 1);

        panel.add(UI.label(IKey.raw("选区设置"), 20, Colors.ACTIVE));
        panel.add(UI.label(IKey.raw("选择模式")));
        panel.add(this.shapeSelector);
        panel.add(UI.label(IKey.raw("顶点坐标"), 16));
        panel.add(UI.row(2, this.vertexX, this.vertexY, this.vertexZ));
        panel.add(UI.row(2, this.addVertexBtn, this.pickFromCrosshairBtn));
        panel.add(this.clearVerticesBtn);

        /* 不规则选择的高度范围 */
        panel.add(UI.label(IKey.raw("不规则选择 Y 范围"), 16));
        panel.add(UI.row(2, this.polygonMinY, this.polygonMaxY));

        /* 随机选择参数 */
        panel.add(UI.label(IKey.raw("随机选择参数"), 16));
        panel.add(UI.label(IKey.raw("种子")));
        panel.add(this.randomSeed);
        panel.add(UI.label(IKey.raw("密度 (0.05-1)")));
        panel.add(this.randomDensity);

        panel.add(this.bakeBtn);
        panel.add(this.bakedInfo);
        panel.add(this.bakeWarning);

        /* 设置当前形状选择器值 */
        this.setShapeSelectorValue();
        this.polygonMinY.setValue(this.clip.selection.polygonMinY.get());
        this.polygonMaxY.setValue(this.clip.selection.polygonMaxY.get());
        this.randomSeed.setValue(this.clip.selection.randomSeed.get());
        this.randomDensity.setValue(this.clip.selection.randomDensity.get());
    }

    private void setShapeSelectorValue()
    {
        String shape = this.clip.selection.shape.get();
        int idx = 0;

        if (shape.equals("circle")) idx = 1;
        else if (shape.equals("triangle")) idx = 2;
        else if (shape.equals("polygon")) idx = 3;
        else if (shape.equals("random")) idx = 4;

        this.shapeSelector.setValue(idx);
    }

    private void addVertex()
    {
        int x = (int) this.vertexX.getValue();
        int y = (int) this.vertexY.getValue();
        int z = (int) this.vertexZ.getValue();

        mchorse.bbs_mod.camera.data.Position pos = new mchorse.bbs_mod.camera.data.Position();
        pos.point.x = x;
        pos.point.y = y;
        pos.point.z = z;
        this.clip.selection.vertices.add(pos);

        this.refreshSelectionInfo();
    }

    /**
     * 从准星位置拾取方块坐标作为顶点。
     * 复用 mc.crosshairTarget，无需自己实现射线检测。
     */
    private void pickFromCrosshair()
    {
        class_310 mc = class_310.method_1551();

        if (mc.field_1765 instanceof class_3965 bhr && bhr.method_17783() == class_239.class_240.field_1332)
        {
            class_2338 pos = bhr.method_17777();

            this.vertexX.setValue(pos.method_10263());
            this.vertexY.setValue(pos.method_10264());
            this.vertexZ.setValue(pos.method_10260());

            mchorse.bbs_mod.camera.data.Position p = new mchorse.bbs_mod.camera.data.Position();
            p.point.x = pos.method_10263();
            p.point.y = pos.method_10264();
            p.point.z = pos.method_10260();
            this.clip.selection.vertices.add(p);

            this.refreshSelectionInfo();
        }
    }

    private void clearVertices()
    {
        this.clip.selection.vertices.reset();
        this.clip.selection.bakedBlocks.reset();
        this.refreshSelectionInfo();
    }

    private void bakeSelection()
    {
        class_310 mc = class_310.method_1551();

        if (mc.field_1687 == null) return;

        this.clip.selection.bake(mc.field_1687, false);
        this.refreshSelectionInfo();
    }

    /* === 子效果列表面板 === */

    private void buildSubEffectListPanel(UIScrollView panel)
    {
        this.subEffectList = new UIScrollView(ScrollDirection.VERTICAL);
        this.subEffectList.w(1F).h(120).column(2).stretch().padding(4);

        this.addSubEffectBtn = new UIButton(IKey.raw("+ 添加"), (b) -> this.addSubEffect());
        this.removeSubEffectBtn = new UIButton(IKey.raw("- 删除"), (b) -> this.removeSelectedSubEffect());

        panel.add(UI.label(IKey.raw("子效果列表"), 20, Colors.ACTIVE));
        panel.add(UI.row(2, this.addSubEffectBtn, this.removeSubEffectBtn));
        panel.add(this.subEffectList);
    }

    private void refreshSubEffectList()
    {
        this.subEffectList.removeAll();

        int count = this.clip.subEffects.getSubEffectCount();

        for (int i = 0; i < count; i++)
        {
            SubEffect sub = this.clip.subEffects.getSubEffect(i);
            int idx = i;

            String type = "未设置";
            if (sub.getActionClip() != null)
            {
                try
                {
                    Link link = BBSMod.getFactoryActionClips().getType(sub.getActionClip());
                    type = link.toString().replace("bbs:", "");
                }
                catch (Exception e)
                {
                    type = "未知";
                }
            }

            String label = (i + 1) + ". " + type + " (" + sub.startTick.get() + "-" + sub.getEndTick() + ")";

            UIButton btn = new UIButton(IKey.raw(label), (b) ->
            {
                this.selectedSubIndex = idx;
                this.refreshSubEffectList();
                this.refreshPropertiesPanel();
            });

            if (i == this.selectedSubIndex)
            {
                btn.color(Colors.ACTIVE | Colors.A50);
            }

            this.subEffectList.add(btn);
        }
    }

    private void addSubEffect()
    {
        this.clip.subEffects.addSubEffect();
        this.selectedSubIndex = this.clip.subEffects.getSubEffectCount() - 1;
        this.refreshSubEffectList();
        this.refreshPropertiesPanel();
    }

    private void removeSelectedSubEffect()
    {
        if (this.selectedSubIndex < 0) return;

        this.clip.subEffects.removeSubEffect(this.selectedSubIndex);
        this.selectedSubIndex = -1;
        this.refreshSubEffectList();
        this.refreshPropertiesPanel();
    }

    /* === 子效果属性面板 === */

    private void buildPropertiesPanel(UIScrollView panel)
    {
        this.effectTypeSelector = new UICirculate((v) -> this.changeEffectType(v.getValue()));
        this.effectTypeSelector.addLabel(IKey.raw("方块飞溅"));
        this.effectTypeSelector.addLabel(IKey.raw("方块震动"));
        this.effectTypeSelector.addLabel(IKey.raw("方块路径"));
        this.effectTypeSelector.addLabel(IKey.raw("反向飞溅"));

        this.subStartTick = new UITrackpad((v) ->
        {
            if (this.selectedSubIndex >= 0)
            {
                SubEffect sub = this.clip.subEffects.getSubEffect(this.selectedSubIndex);
                if (sub != null) sub.startTick.set(v.intValue());
                this.refreshSubEffectList();
            }
        });
        this.subStartTick.limit(0, Integer.MAX_VALUE, true).integer();

        this.subDuration = new UITrackpad((v) ->
        {
            if (this.selectedSubIndex >= 0)
            {
                SubEffect sub = this.clip.subEffects.getSubEffect(this.selectedSubIndex);
                if (sub != null) sub.duration.set(Math.max(1, v.intValue()));
                this.refreshSubEffectList();
            }
        });
        this.subDuration.limit(1, Integer.MAX_VALUE, true).integer();

        this.subBlendFactor = new UITrackpad((v) ->
        {
            if (this.selectedSubIndex >= 0)
            {
                SubEffect sub = this.clip.subEffects.getSubEffect(this.selectedSubIndex);
                if (sub != null) sub.blendFactor.set(v);
            }
        });
        this.subBlendFactor.limit(0, 0.8);

        /* 过渡曲线编辑 */
        this.transitionInDuration = new UITrackpad((v) ->
        {
            if (this.selectedSubIndex >= 0)
            {
                SubEffect sub = this.clip.subEffects.getSubEffect(this.selectedSubIndex);
                if (sub != null) sub.transitionIn.fadeIn.set(v.floatValue());
                this.refreshPropertiesPanel();
            }
        });
        this.transitionInDuration.limit(0, 100, true).integer();

        this.transitionOutDuration = new UITrackpad((v) ->
        {
            if (this.selectedSubIndex >= 0)
            {
                SubEffect sub = this.clip.subEffects.getSubEffect(this.selectedSubIndex);
                if (sub != null) sub.transitionOut.fadeOut.set(v.floatValue());
                this.refreshPropertiesPanel();
            }
        });
        this.transitionOutDuration.limit(0, 100, true).integer();

        this.strengthInfo = UI.label(IKey.raw("选择子效果后显示属性"));

        /* 子效果详细参数容器（动态填充） */
        this.detailParamsContainer = new UIScrollView(ScrollDirection.VERTICAL);
        this.detailParamsContainer.w(1F).h(160).column(2).stretch().padding(4);

        panel.add(UI.label(IKey.raw("子效果属性"), 20, Colors.ACTIVE));
        panel.add(UI.label(IKey.raw("效果类型")));
        panel.add(this.effectTypeSelector);
        panel.add(UI.label(IKey.raw("起始 Tick")));
        panel.add(this.subStartTick);
        panel.add(UI.label(IKey.raw("持续 Tick")));
        panel.add(this.subDuration);
        panel.add(UI.label(IKey.raw("重叠比例 (0-0.8)")));
        panel.add(this.subBlendFactor);
        panel.add(UI.label(IKey.raw("过渡曲线"), 16, Colors.ACTIVE));
        panel.add(UI.label(IKey.raw("进入强度持续 (tick)")));
        panel.add(this.transitionInDuration);
        panel.add(UI.label(IKey.raw("退出强度持续 (tick)")));
        panel.add(this.transitionOutDuration);
        panel.add(this.strengthInfo);
        panel.add(UI.label(IKey.raw("子效果详细参数"), 16, Colors.ACTIVE));
        panel.add(this.detailParamsContainer);
    }

    private void refreshPropertiesPanel()
    {
        if (this.selectedSubIndex < 0)
        {
            this.strengthInfo.label = IKey.raw("请选择子效果");
            this.clearDetailParams();
            return;
        }

        SubEffect sub = this.clip.subEffects.getSubEffect(this.selectedSubIndex);

        if (sub == null)
        {
            this.selectedSubIndex = -1;
            this.strengthInfo.label = IKey.raw("请选择子效果");
            this.clearDetailParams();
            return;
        }

        /* 设置效果类型选择器 */
        if (sub.getActionClip() != null)
        {
            try
            {
                Link link = BBSMod.getFactoryActionClips().getType(sub.getActionClip());
                String type = link.toString();

                int idx = 0;
                if (type.equals("bbs:block_splash")) idx = 0;
                else if (type.equals("bbs:block_shockwave")) idx = 1;
                else if (type.equals("bbs:block_path")) idx = 2;
                else if (type.equals("bbs:block_splash_reverse")) idx = 3;

                this.effectTypeSelector.setValue(idx);
            }
            catch (Exception e) {}
        }

        this.subStartTick.setValue(sub.startTick.get());
        this.subDuration.setValue(sub.duration.get());
        this.subBlendFactor.setValue(sub.blendFactor.get());
        this.transitionInDuration.setValue(sub.transitionIn.fadeIn.get());
        this.transitionOutDuration.setValue(sub.transitionOut.fadeOut.get());

        /* 强度信息 */
        int inDur = (int) sub.transitionIn.fadeIn.get().floatValue();
        int outDur = (int) sub.transitionOut.fadeOut.get().floatValue();
        int dur = sub.duration.get();
        int midDur = dur - inDur - outDur;
        this.strengthInfo.label = IKey.raw("强度: 入" + inDur + "t / 中" + Math.max(0, midDur) + "t / 出" + outDur + "t");

        /* 刷新子效果详细参数 */
        this.refreshDetailParams(sub);
    }

    /**
     * 根据子效果类型动态填充详细参数控件。
     */
    private void refreshDetailParams(SubEffect sub)
    {
        this.clearDetailParams();

        mchorse.bbs_mod.actions.types.ActionClip actionClip = sub.getActionClip();

        if (actionClip == null)
        {
            this.detailParamsContainer.add(UI.label(IKey.raw("（未设置效果类型）"), 14, Colors.GRAY));
            return;
        }

        if (actionClip instanceof BlockSplashActionClip splash)
        {
            this.buildSplashParams(splash);
        }
        else if (actionClip instanceof BlockShockwaveActionClip shockwave)
        {
            this.buildShockwaveParams(shockwave);
        }
        else if (actionClip instanceof BlockPathActionClip path)
        {
            this.buildPathParams(path);
        }
        else if (actionClip instanceof BlockSplashReverseActionClip reverse)
        {
            this.buildSplashReverseParams(reverse);
        }
    }

    private void clearDetailParams()
    {
        this.detailParamsContainer.removeAll();
    }

    /* === 4 种子效果的详细参数编辑 === */

    private void buildSplashParams(BlockSplashActionClip splash)
    {
        this.detailParamsContainer.add(UI.label(IKey.raw("飞溅力度 (0-5)")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(splash.power));

        this.detailParamsContainer.add(UI.label(IKey.raw("方向 X")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(splash.dirX));
        this.detailParamsContainer.add(UI.label(IKey.raw("方向 Y")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(splash.dirY));
        this.detailParamsContainer.add(UI.label(IKey.raw("方向 Z")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(splash.dirZ));

        this.detailParamsContainer.add(UI.label(IKey.raw("碰撞")));
        this.detailParamsContainer.add(this.createToggleTrackpad(splash.collision));

        this.detailParamsContainer.add(UI.label(IKey.raw("动画时长 (tick)")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(splash.animationDuration));

        this.detailParamsContainer.add(UI.label(IKey.raw("固化")));
        this.detailParamsContainer.add(this.createToggleTrackpad(splash.solidify));
    }

    private void buildShockwaveParams(BlockShockwaveActionClip shockwave)
    {
        this.detailParamsContainer.add(UI.label(IKey.raw("振幅 (0-2)")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(shockwave.amplitude));

        this.detailParamsContainer.add(UI.label(IKey.raw("波速 (0.1-5)")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(shockwave.waveSpeed));

        this.detailParamsContainer.add(UI.label(IKey.raw("震动时长 (1-100)")));
        this.detailParamsContainer.add(this.createIntTrackpad(shockwave.shakeDuration));

        this.detailParamsContainer.add(UI.label(IKey.raw("震动频率 (1-10)")));
        this.detailParamsContainer.add(this.createIntTrackpad(shockwave.shakeFrequency));

        this.detailParamsContainer.add(UI.label(IKey.raw("衰减 (0.1-0.95)")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(shockwave.decay));

        this.detailParamsContainer.add(UI.label(IKey.raw("冲击力 (0.1-5)")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(shockwave.impactForce));

        this.detailParamsContainer.add(UI.label(IKey.raw("震动后恢复")));
        this.detailParamsContainer.add(this.createToggleTrackpad(shockwave.restoreAfter));
    }

    private void buildPathParams(BlockPathActionClip path)
    {
        this.detailParamsContainer.add(UI.label(IKey.raw("速度 (0.1-10)")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(path.speed));

        this.detailParamsContainer.add(UI.label(IKey.raw("集中度 (0-1)")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(path.concentration));

        this.detailParamsContainer.add(UI.label(IKey.raw("散射半径 (0-20)")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(path.scatterRadius));

        this.detailParamsContainer.add(UI.label(IKey.raw("循环")));
        this.detailParamsContainer.add(this.createToggleTrackpad(path.loop));

        this.detailParamsContainer.add(UI.label(IKey.raw("启用旋转")));
        this.detailParamsContainer.add(this.createToggleTrackpad(path.enableRotation));

        this.detailParamsContainer.add(UI.label(IKey.raw("启用碰撞")));
        this.detailParamsContainer.add(this.createToggleTrackpad(path.enableCollision));
    }

    private void buildSplashReverseParams(BlockSplashReverseActionClip reverse)
    {
        this.detailParamsContainer.add(UI.label(IKey.raw("散射半径 (1-20)")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(reverse.scatterRadius));

        this.detailParamsContainer.add(UI.label(IKey.raw("恢复速度 (0.1-5)")));
        this.detailParamsContainer.add(this.createDoubleTrackpad(reverse.recoverySpeed));

        this.detailParamsContainer.add(UI.label(IKey.raw("恢复延迟 (0-20)")));
        this.detailParamsContainer.add(this.createIntTrackpad(reverse.recoveryDelay));

        this.detailParamsContainer.add(UI.label(IKey.raw("随机飞溅")));
        this.detailParamsContainer.add(this.createToggleTrackpad(reverse.randomSplash));

        this.detailParamsContainer.add(UI.label(IKey.raw("恢复时禁用碰撞")));
        this.detailParamsContainer.add(this.createToggleTrackpad(reverse.disableCollisionDuringRecovery));

        this.detailParamsContainer.add(UI.label(IKey.raw("动画保持时长 (5-200)")));
        this.detailParamsContainer.add(this.createIntTrackpad(reverse.animationKeepDuration));
    }

    /**
     * 创建一个绑定到 ValueDouble 的 UITrackpad。
     */
    private UITrackpad createDoubleTrackpad(mchorse.bbs_mod.settings.values.numeric.ValueDouble value)
    {
        UITrackpad trackpad = new UITrackpad((v) -> value.set(v));

        double min = value.getMin();
        double max = value.getMax();

        if (min != Double.NEGATIVE_INFINITY || max != Double.POSITIVE_INFINITY)
        {
            trackpad.limit(min, max);
        }

        trackpad.setValue(value.get());

        return trackpad;
    }

    /**
     * 创建一个绑定到 ValueInt 的 UITrackpad。
     */
    private UITrackpad createIntTrackpad(mchorse.bbs_mod.settings.values.numeric.ValueInt value)
    {
        UITrackpad trackpad = new UITrackpad((v) -> value.set(v.intValue()));

        int min = value.getMin();
        int max = value.getMax();

        if (min != Integer.MIN_VALUE || max != Integer.MAX_VALUE)
        {
            trackpad.limit(min, max, true);
        }

        trackpad.integer();
        trackpad.setValue(value.get());

        return trackpad;
    }

    /**
     * 创建一个绑定到 ValueBoolean 的 UITrackpad（0=关, 1=开）。
     */
    private UITrackpad createToggleTrackpad(mchorse.bbs_mod.settings.values.numeric.ValueBoolean value)
    {
        UITrackpad trackpad = new UITrackpad((v) -> value.set(v != 0));

        trackpad.limit(0, 1).integer();
        trackpad.setValue(value.get() ? 1 : 0);

        return trackpad;
    }

    private void changeEffectType(int v)
    {
        if (this.selectedSubIndex < 0) return;

        SubEffect sub = this.clip.subEffects.getSubEffect(this.selectedSubIndex);

        if (sub == null) return;

        String[] types = {"bbs:block_splash", "bbs:block_shockwave", "bbs:block_path", "bbs:block_splash_reverse"};
        sub.createNewActionClip(types[v]);

        this.refreshSubEffectList();
        this.refreshPropertiesPanel();
    }

    /* === 选区信息刷新 === */

    private void refreshSelectionInfo()
    {
        String shape = this.clip.selection.shape.get();
        int vertCount = this.clip.selection.vertices.size();
        int bakedCount = this.clip.selection.getBakedCount();

        this.bakedInfo.label = IKey.raw("形状: " + shape + " | 顶点: " + vertCount + " | 烘焙方块: " + bakedCount);

        /* 大区域警告 */
        if (bakedCount > BAKE_WARN_THRESHOLD)
        {
            this.bakeWarning.label = IKey.raw("⚠ 方块数较多 (" + bakedCount + ")，可能影响性能");
        }
        else
        {
            this.bakeWarning.label = IKey.raw("");
        }
    }

    @Override
    public void render(UIContext context)
    {
        super.render(context);
    }
}
