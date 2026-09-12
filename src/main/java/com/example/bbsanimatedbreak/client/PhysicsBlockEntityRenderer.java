package com.example.bbsanimatedbreak.client;

import com.example.bbsanimatedbreak.actions.physics.PhysicsBlockEntity;
import org.joml.Quaternionf;

import net.minecraft.class_1540;
import net.minecraft.class_3532;
import net.minecraft.class_4587;
import net.minecraft.class_4597;
import net.minecraft.class_5617;
import net.minecraft.class_901;

/**
 * 物理方块实体渲染器
 *
 * 继承 FallingBlockEntityRenderer 复用方块模型渲染逻辑，
 * 在外层包裹刚体四元数旋转 —— 围绕方块几何中心旋转。
 *
 * ==================================================================
 * 渲染位置：为什么必须「先抵消再偏移」
 * ==================================================================
 * Minecraft 渲染实体时的矩阵原点并不是 entity.getX()，而是
 * （见 WorldRenderer#method_22977）：
 *
 *     base = lerp(tickDelta, entity.lastRenderX, entity.getX())
 *
 * 所以本渲染器在做位移前，必须用同一个 base 做抵消，
 * 否则 offset 会随 tickDelta 摆动，表现为方块持续抖动。
 *
 * 本渲染器不做任何物理预测：位置来自 PhysicsBlockEntity 的
 * 二次拉格朗日插值（最近 3 个权威 tick 样本），旋转来自
 * 两样本 slerp。两者都是纯函数，与帧率无关，因此
 * 实时预览与 BBS 导出（任意帧率、任意拖动位置）结果一致。
 *
 * 与 FallingBlockEntityRendererMixin 不冲突：
 * 该 mixin 通过 RotatingFallingBlockManager.isRotating(entity) 守门，
 * PhysicsBlockEntity 不会被标记为 IS_ROTATING，因此 mixin 跳过注入，
 * 由本渲染器接管。
 */
public class PhysicsBlockEntityRenderer extends class_901
{
    /** 复用的四元数（避免每帧每方块一次分配） */
    private final Quaternionf tmpRotation = new Quaternionf();

    public PhysicsBlockEntityRenderer(class_5617.class_5618 ctx)
    {
        super(ctx);
    }

    @Override
    public void method_3965(class_1540 entity, float yaw, float tickDelta,
                       class_4587 matrices, class_4597 vertices, int light)
    {
        if (!(entity instanceof PhysicsBlockEntity))
        {
            super.method_3965(entity, yaw, tickDelta, matrices, vertices, light);
            return;
        }

        PhysicsBlockEntity physics = (PhysicsBlockEntity) entity;

        // === 与 vanilla 完全一致的矩阵原点（抵消用） ===
        double baseX = class_3532.method_16436(tickDelta, entity.field_6038, entity.method_23317());
        double baseY = class_3532.method_16436(tickDelta, entity.field_5971, entity.method_23318());
        double baseZ = class_3532.method_16436(tickDelta, entity.field_5989, entity.method_23321());

        // 父类绘制方块时矩阵原点是 "方块中心 - (0, 0.5, 0)"：
        // 父类内部有 translate(-0.5, 0, -0.5) 且模型占 0..1，
        // 因此方块中心 = (baseX, baseY + 0.5, baseZ)。
        double offsetX = physics.getRenderCenterX(tickDelta) - baseX;
        double offsetY = physics.getRenderCenterY(tickDelta) - (baseY + 0.5);
        double offsetZ = physics.getRenderCenterZ(tickDelta) - baseZ;

        matrices.method_22903();
        matrices.method_22904(offsetX, offsetY, offsetZ);

        // 把旋转枢轴移到方块几何中心
        matrices.method_22904(0.0, 0.5, 0.0);
        physics.getRenderRotation(tickDelta, this.tmpRotation);
        matrices.method_22907(this.tmpRotation);
        matrices.method_22904(0.0, -0.5, 0.0);

        super.method_3965(entity, yaw, tickDelta, matrices, vertices, light);

        matrices.method_22909();
    }
}
