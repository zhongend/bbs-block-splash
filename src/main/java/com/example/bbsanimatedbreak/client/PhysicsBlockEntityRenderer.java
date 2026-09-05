package com.example.bbsanimatedbreak.client;

import com.example.bbsanimatedbreak.actions.physics.PhysicsBlockEntity;
import net.minecraft.class_1540;
import net.minecraft.class_4587;
import net.minecraft.class_4597;
import net.minecraft.class_5617;
import net.minecraft.class_901;
import org.joml.Quaternionf;

/**
 * 物理方块实体渲染器（Sable 风格的旋转渲染）
 *
 * 继承 FallingBlockEntityRenderer 复用方块模型渲染逻辑，
 * 在外层包裹四元数旋转 —— 围绕方块几何中心 (0, 0.5, 0) 旋转。
 *
 * 高刷新率优化（165Hz+）+ 视频导出兼容：
 * - 每帧调用 updatePredictionFromTickDelta(tickDelta) 用速度+重力积分预测位置和旋转
 * - dt 来源是 tickDelta 增量（非 System.nanoTime()），与 Minecraft 物理时间严格同步
 *   这样视频导出（非实时渲染）和实时游戏都能正确插值，不会因 nanoTime 与物理时间脱钩而顿挫
 * - lastTickDelta 存储在每个 PhysicsBlockEntity 实例上（非渲染器单例），
 *   确保 300 个方块每帧都能独立计算增量并更新预测
 * - 渲染预测位置而非 lerp，运动平滑度由帧率决定而非 tick 率（20Hz）
 * - 客户端物理预测在 updateClientData() 中用服务端位置校正（lerp 20%）避免发散
 *
 * 不与 FallingBlockEntityRendererMixin 冲突：
 * 该 mixin 通过 RotatingFallingBlockManager.isRotating(entity) 守门，
 * PhysicsBlockEntity 不会被标记为 IS_ROTATING（DataTracker 字段未设置），
 * 因此 mixin 跳过旋转注入，由本渲染器接管。
 */
public class PhysicsBlockEntityRenderer extends class_901
{
    public PhysicsBlockEntityRenderer(class_5617.class_5618 ctx)
    {
        super(ctx);
    }

    @Override
    public void method_3965(class_1540 entity, float yaw, float tickDelta,
                       class_4587 matrices, class_4597 vertices, int light)
    {
        // PhysicsBlockEntity 跑四元数物理，这里围绕方块几何中心 (0, 0.5, 0) 旋转
        if (entity instanceof PhysicsBlockEntity)
        {
            PhysicsBlockEntity physics = (PhysicsBlockEntity) entity;

            // === 每帧物理预测（高刷新率 + 视频导出兼容） ===
            // 把 tickDelta 传给实体，由实体内部计算帧间增量（每实体独立 lastTickDelta）
            // 这样 300 个方块每帧都能正确更新预测，不会因渲染器单例共享 lastTickDelta
            // 导致只有第一个方块更新、其余冻结的慢动作 bug
            physics.updatePredictionFromTickDelta(tickDelta);

            // === 位置偏移 ===
            // 预测位置是方块中心（center），entity 位置是脚部（feet），渲染器需要偏移补偿
            // 渲染器坐标系：entity 位置是原点，方块模型从 y=0 到 y=1
            // 预测位置是 center（y+0.5），所以偏移 = predPos - entityPos - 0.5（feet→center 减 0.5）
            double renderX = physics.getRenderX(tickDelta);
            double renderY = physics.getRenderY(tickDelta);
            double renderZ = physics.getRenderZ(tickDelta);
            double offsetX = renderX - entity.method_23317();
            double offsetY = renderY - entity.method_23318() - 0.5;
            double offsetZ = renderZ - entity.method_23321();

            matrices.method_22903();
            matrices.method_22904(offsetX, offsetY, offsetZ);
            matrices.method_22904(0.0, 0.5, 0.0);
            Quaternionf rotation = physics.getRenderRotation(tickDelta);
            matrices.method_22907(rotation);
            matrices.method_22904(0.0, -0.5, 0.0);

            // 父类完成方块模型渲染
            super.method_3965(entity, yaw, tickDelta, matrices, vertices, light);

            matrices.method_22909();
        }
        else
        {
            super.method_3965(entity, yaw, tickDelta, matrices, vertices, light);
        }
    }
}
