package com.example.bbsanimatedbreak.client;

import com.example.bbsanimatedbreak.BlockPathCurve;
import com.example.bbsanimatedbreak.actions.BlockPathActionClip;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.OrbitCamera;
import mchorse.bbs_mod.camera.controller.OrbitCameraController;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.utils.UIOrbitCamera;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIRenderingContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Colors;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.class_243;
import net.minecraft.class_310;
import net.minecraft.class_746;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 方块路径编辑器 - 独立全屏 GUI
 *
 * === 操作方式 ===
 * - WASD：飞行移动（Space上升，Shift下降）
 * - 左键拖拽：旋转视角
 * - 右键点击：在相机当前位置添加路径节点
 * - 滚轮：调整飞行速度
 *
 * === WASD 修复 ===
 * UIOrbitCamera.keyPressed() 返回 null，不会调用 orbit.keyPressed(context)。
 * 所以在 handleKey 中手动调用 orbit.keyPressed(context) 把按键状态喂给相机。
 */
public class UIPathEditorMenu extends UIBaseMenu
{
    /* 关联的 ActionClip */
    private BlockPathActionClip clip;

    /* 路径标记点（客户端编辑用，保存时写回 clip.pathPoints） */
    private List<double[]> points = new ArrayList<>();

    /* 视角控制 */
    private UIOrbitCamera uiOrbitCamera;
    private OrbitCamera orbit;
    private OrbitCameraController orbitCameraController;

    /* UI 控件 */
    private UIScrollView sidePanel;
    private UICirculate interpSelector;
    private UITrackpad pointX, pointY, pointZ;
    private UIButton addPointBtn, removePointBtn, saveBtn, cancelBtn;
    private UIElement pointListContainer;
    private int selectedIndex = -1;

    /* 插值类型选项 */
    private static final String[] INTERP_VALUES = {"catmull_rom", "b_spline", "linear", "cubic"};
    private static final String[] INTERP_LABELS = {"Catmull-Rom（丝滑）", "B样条（平滑）", "线性（直线）", "三次（锐利）"};

    public UIPathEditorMenu(BlockPathActionClip clip)
    {
        this.clip = clip;

        /* 从 clip 加载现有路径点 */
        for (int i = 0; i < clip.pathPoints.size(); i++)
        {
            Position pos = clip.pathPoints.get(i);
            this.points.add(new double[]{pos.point.x, pos.point.y, pos.point.z});
        }

        /* 初始化视角：用 OrbitCamera 支持 WASD 飞行 */
        this.orbit = new OrbitCamera();
        this.orbit.setFovRoll(false); /* 禁用右键 roll，让右键可以添加节点 */

        this.uiOrbitCamera = new UIOrbitCamera();
        this.uiOrbitCamera.setControl(true);
        this.uiOrbitCamera.orbit = this.orbit;

        this.orbitCameraController = new OrbitCameraController(this.orbit);

        /* 用玩家位置和朝向初始化相机 */
        class_746 player = class_310.method_1551().field_1724;
        if (player != null)
        {
            Position initPos = new Position(
                (float) player.method_23317(),
                (float) (player.method_23318() + 1.0),
                (float) player.method_23321(),
                player.method_36454(),
                player.method_36455()
            );
            this.orbit.from(initPos);
        }

        /* 注册视角控制器到 BBS */
        BBSModClient.getCameraController().add(this.orbitCameraController);

        this.createUI();
    }

    private void createUI()
    {
        this.main.removeAll();

        /* 侧边栏（右侧） */
        this.sidePanel = UI.scrollView(5, 10);
        this.sidePanel.relative(this.viewport).x(1F).w(240).h(1F).anchorX(1F);

        /* 标题 */
        this.sidePanel.add(UI.label(IKey.raw("=== 路径编辑器 ===")).background().marginTop(0));
        this.sidePanel.add(UI.label(IKey.raw("WASD: 飞行移动")).marginTop(6));
        this.sidePanel.add(UI.label(IKey.raw("Space: 上升  Shift: 下降")).marginTop(2));
        this.sidePanel.add(UI.label(IKey.raw("左键拖拽: 旋转视角")).marginTop(2));
        this.sidePanel.add(UI.label(IKey.raw("右键: 添加节点")).marginTop(2));
        this.sidePanel.add(UI.label(IKey.raw("滚轮: 调整飞行速度")).marginTop(2));

        /* 插值类型 */
        this.sidePanel.add(UI.label(IKey.raw("曲线插值类型")).marginBottom(4).marginTop(10));
        this.interpSelector = new UICirculate((c) ->
        {
            int idx = c.getValue();
            if (idx >= 0 && idx < INTERP_VALUES.length)
            {
                this.clip.interpolation.set(INTERP_VALUES[idx]);
            }
        });
        for (String label : INTERP_LABELS)
        {
            this.interpSelector.addLabel(IKey.raw(label));
        }
        this.sidePanel.add(this.interpSelector);

        /* 设置当前插值类型 */
        String currentInterp = (String) this.clip.interpolation.get();
        int interpIdx = 0;
        for (int i = 0; i < INTERP_VALUES.length; i++)
        {
            if (INTERP_VALUES[i].equals(currentInterp)) { interpIdx = i; break; }
        }
        this.interpSelector.setValue(interpIdx, 1);

        /* 节点列表 */
        this.sidePanel.add(UI.label(IKey.raw("=== 节点列表 ===")).marginBottom(4).marginTop(10));
        /* 用 UI.column() 创建容器，自动垂直排列子元素 + stretch 拉伸宽度 */
        this.pointListContainer = UI.column(2);
        this.pointListContainer.w(1F);
        this.sidePanel.add(this.pointListContainer);
        this.refreshPointList();

        /* 选中节点坐标编辑 */
        this.sidePanel.add(UI.label(IKey.raw("选中节点坐标")).marginBottom(4).marginTop(10));
        this.pointX = new UITrackpad((v) -> this.updateSelectedPoint(v, 0)).integer();
        this.pointY = new UITrackpad((v) -> this.updateSelectedPoint(v, 1)).integer();
        this.pointZ = new UITrackpad((v) -> this.updateSelectedPoint(v, 2)).integer();
        this.sidePanel.add(UI.row(this.pointX, this.pointY, this.pointZ));

        /* 添加/删除按钮 */
        this.addPointBtn = new UIButton(IKey.raw("在相机位置添加节点"), (b) -> this.addPointAtCamera());
        this.removePointBtn = new UIButton(IKey.raw("删除选中节点"), (b) -> this.removeSelectedPoint());
        this.sidePanel.add(this.addPointBtn);
        this.sidePanel.add(this.removePointBtn);

        /* 保存/取消 */
        this.sidePanel.add(UI.label(IKey.raw("=== 操作 ===")).marginBottom(4).marginTop(10));
        this.saveBtn = new UIButton(IKey.raw("保存并关闭"), (b) -> this.saveAndClose());
        this.cancelBtn = new UIButton(IKey.raw("取消"), (b) -> this.closeMenu());
        this.sidePanel.add(this.saveBtn);
        this.sidePanel.add(this.cancelBtn);

        /* 提示 */
        this.sidePanel.add(UI.label(IKey.raw("提示：至少需要2个节点")).marginTop(8));
        this.sidePanel.add(UI.label(IKey.raw("绿色=曲线  黄色=节点")).marginTop(2));
        this.sidePanel.add(UI.label(IKey.raw("橙色=选中  绿色=起点")).marginTop(2));
        this.sidePanel.add(UI.label(IKey.raw("红色=终点")).marginTop(2));

        /* 添加 UI 元素到 main */
        this.main.add(this.uiOrbitCamera);
        this.main.add(this.sidePanel);
    }

    /**
     * === 关键修复：手动把键盘事件喂给 OrbitCamera ===
     *
     * UIOrbitCamera.keyPressed() 返回 null，不会调用 orbit.keyPressed(context)。
     * 所以这里在 UIBaseMenu.handleKey 分发完 UI 事件后，
     * 手动调用 orbit.keyPressed(context) 把 WASD 按键状态喂给相机。
     *
     * orbit.keyPressed 用 context.isPressed/isReleased 追踪按键状态，
     * 然后 orbit.update(context)（在 render 中每帧调用）根据状态移动相机。
     */
    @Override
    public boolean handleKey(int key, int scanCode, int action, int mods)
    {
        /* 先让 UI 处理（侧边栏按钮、文本框等） */
        boolean result = super.handleKey(key, scanCode, action, mods);

        /* 如果 UI 没消费事件，把按键喂给 OrbitCamera（WASD 飞行） */
        if (!result && this.orbit != null && this.context != null)
        {
            /* ESC 由 super.handleKey 处理关闭菜单，不喂给相机 */
            if (key != GLFW.GLFW_KEY_ESCAPE)
            {
                this.orbit.keyPressed(this.context);
            }
        }

        return result;
    }

    /**
     * 刷新节点列表 UI
     */
    private void refreshPointList()
    {
        this.pointListContainer.removeAll();

        if (this.points.isEmpty())
        {
            this.pointListContainer.add(UI.label(IKey.raw("（暂无节点，右键添加）")).marginTop(4));
            this.pointListContainer.resize();
            this.sidePanel.resize();
            return;
        }

        for (int i = 0; i < this.points.size(); i++)
        {
            final int index = i;
            double[] p = this.points.get(i);
            UIButton btn = new UIButton(IKey.raw("节点 " + (i + 1) + ": " +
                (int) p[0] + ", " + (int) p[1] + ", " + (int) p[2]), (b) ->
            {
                this.selectPoint(index);
            });

            if (i == this.selectedIndex)
            {
                btn.color(Colors.A75);
            }

            this.pointListContainer.add(btn);
        }

        this.pointListContainer.resize();
        this.sidePanel.resize();
    }

    /**
     * 选中一个节点
     */
    private void selectPoint(int index)
    {
        if (index < 0 || index >= this.points.size()) return;

        this.selectedIndex = index;
        double[] p = this.points.get(index);
        this.pointX.setValue((int) p[0]);
        this.pointY.setValue((int) p[1]);
        this.pointZ.setValue((int) p[2]);
        this.refreshPointList();
    }

    /**
     * 更新选中节点的坐标
     */
    private void updateSelectedPoint(double value, int axis)
    {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.points.size()) return;

        double[] p = this.points.get(this.selectedIndex);
        p[axis] = value;
        this.refreshPointList();
    }

    /**
     * 在相机当前位置添加节点
     */
    private void addPointAtCamera()
    {
        class_243 pos = class_310.method_1551().field_1773.method_19418().method_19326();
        this.points.add(new double[]{pos.field_1352, pos.field_1351, pos.field_1350});
        this.selectedIndex = this.points.size() - 1;
        this.refreshPointList();
    }

    /**
     * 删除选中节点
     */
    private void removeSelectedPoint()
    {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.points.size()) return;

        this.points.remove(this.selectedIndex);
        if (this.selectedIndex >= this.points.size())
        {
            this.selectedIndex = this.points.size() - 1;
        }
        this.refreshPointList();

        if (this.selectedIndex >= 0)
        {
            this.selectPoint(this.selectedIndex);
        }
    }

    /**
     * 保存路径点到 ActionClip 并关闭
     */
    private void saveAndClose()
    {
        /* 清除现有路径点 */
        this.clip.pathPoints.reset();

        /* 写入新路径点 */
        for (double[] p : this.points)
        {
            Position pos = new Position();
            pos.point.set((float) p[0], (float) p[1], (float) p[2]);
            this.clip.pathPoints.add(pos);
        }

        this.closeMenu();
    }

    @Override
    public boolean canHideHUD()
    {
        return true; /* 隐藏 HUD（物品栏、手部等） */
    }

    @Override
    public boolean canPause()
    {
        return false; /* 不暂停游戏，让 WASD 飞行生效 */
    }

    @Override
    public void onClose(UIBaseMenu nextMenu)
    {
        super.onClose(nextMenu);

        /* 移除视角控制器，恢复玩家视角 */
        BBSModClient.getCameraController().remove(this.orbitCameraController);
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton)
    {
        /* 先让 UI 处理点击（侧边栏按钮等） */
        boolean result = super.mouseClicked(mouseX, mouseY, mouseButton);

        /* 如果 UI 没处理，且是右键点击，则在相机位置添加节点 */
        if (!result && mouseButton == 1)
        {
            this.addPointAtCamera();
            return true;
        }

        return result;
    }

    /**
     * 侧边栏半透明背景（突出按钮）
     */
    @Override
    protected void preRenderMenu(UIRenderingContext context)
    {
        if (this.sidePanel != null && this.sidePanel.area.w > 0)
        {
            /* 侧边栏区域半透明黑色背景 */
            context.batcher.box(this.sidePanel.area.x - 8, 0, this.width, this.height, 0xDD000000);
            /* 左边缘渐变阴影 */
            context.batcher.box(this.sidePanel.area.x - 8, 0, this.sidePanel.area.x, this.height, 0x88000000);
        }
    }

    /**
     * 世界内渲染：绘制标记点和曲线
     *
     * Draw.renderBox 自己管理 BufferBuilder begin/draw 和 shader，
     * 所以直接调用即可，不需要手动 begin/draw。
     */
    @Override
    public void renderInWorld(WorldRenderContext context)
    {
        super.renderInWorld(context);

        if (this.points.isEmpty()) return;

        class_243 cameraPos = context.camera().method_19326();

        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();

        /* 1. 绘制曲线（采样点小方块） */
        if (this.points.size() >= 2)
        {
            String interpType = (String) this.clip.interpolation.get();
            List<double[]> samples = BlockPathCurve.sample(this.points, interpType, 25);

            for (double[] p : samples)
            {
                double x = p[0] - cameraPos.field_1352 - 0.04;
                double y = p[1] - cameraPos.field_1351 - 0.04;
                double z = p[2] - cameraPos.field_1350 - 0.04;

                /* 绿色小方块表示曲线 */
                Draw.renderBox(context.matrixStack(), x, y, z, 0.08, 0.08, 0.08, 0F, 1F, 0F, 0.8F);
            }
        }

        /* 2. 绘制标记点（彩色大方块） */
        for (int i = 0; i < this.points.size(); i++)
        {
            double[] p = this.points.get(i);
            double x = p[0] - cameraPos.field_1352 - 0.15;
            double y = p[1] - cameraPos.field_1351 - 0.15;
            double z = p[2] - cameraPos.field_1350 - 0.15;

            float r = 1F, g = 1F, b = 0F; /* 默认黄色 */
            if (i == this.selectedIndex) { r = 1F; g = 0.5F; b = 0F; } /* 选中：橙色 */
            else if (i == 0) { r = 0F; g = 1F; b = 0F; } /* 起点：绿色 */
            else if (i == this.points.size() - 1) { r = 1F; g = 0F; b = 0F; } /* 终点：红色 */

            Draw.renderBox(context.matrixStack(), x, y, z, 0.3, 0.3, 0.3, r, g, b);
        }

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
    }
}
