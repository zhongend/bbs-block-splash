package com.example.bbsanimatedbreak.client.mixin;

import com.example.bbsanimatedbreak.FallingBlockRotationData;
import com.example.bbsanimatedbreak.RotatingFallingBlockManager;
import com.example.bbsanimatedbreak.client.ClientRotationStateManager;
import com.example.bbsanimatedbreak.client.ClientRotationStateManager.ClientRotationState;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;
import net.minecraft.class_1540;
import net.minecraft.class_4587;
import net.minecraft.class_4597;
import net.minecraft.class_901;

/**
 * FallingBlockEntityRenderer Mixin（客户端）
 *
 * === 客户端自主旋转计算（解决高刷新率卡顿） ===
 *
 * 之前的问题：DataTracker 每 tick（50ms）同步一次角度，165hz 屏幕每帧 6ms，
 * 同一 tick 内多帧渲染的角度相同，旋转看起来像 30 帧。
 *
 * 解决方案：客户端维护自己的旋转状态（在 ClientRotationStateManager 中），
 * 每帧根据角速度累加旋转角度。
 *
 * 工作流程：
 * 1. 服务端通过 DataTracker 同步角速度（ANGULAR_VEL_X/Y/Z）
 * 2. 客户端每帧渲染时，用帧间的 tickDelta 差计算旋转增量
 *    旋转增量 = 角速度 × (当前tickDelta - 上一帧tickDelta)
 * 3. 每 tick 用服务端同步的角度校准一次，避免累积误差
 *
 * === 落地挤压变形 ===
 * 方块落地时根据冲击力做 scale 变形（体积守恒）
 *
 * 注意：旋转状态类 ClientRotationState 必须定义在 Mixin 包外面，
 * 否则 Mixin 处理器会把它当作 Mixin 内部类处理，导致 IllegalClassLoadError。
 */
@Mixin(class_901.class)
public abstract class FallingBlockEntityRendererMixin
{
    /**
     * 本帧是否由本 Mixin 压入过矩阵栈
     *
     * === 为什么需要显式标志位，而不是在 RETURN 处重新问一次 isRotating ===
     * 原先的写法是"HEAD 判 isRotating 决定 push，RETURN 再判一次决定 pop"。
     * 两次判定必须严格同值，否则矩阵栈失衡：
     * - HEAD 为 true、RETURN 为 false → 少一次 pop，栈永久残留一层，
     *   之后所有实体的渲染都被平移/旋转，整屏画面错乱；
     * - HEAD 为 false、RETURN 为 true → 多一次 pop，栈下溢，
     *   后果同样严重且更难定位。
     *
     * isRotating() 内部读 DataTracker 且 catch 后返回 false，
     * 两次调用并非必然同值（异常路径、外部修改等）。
     * 用标志位记录"我确实 push 了"，可让 pop 与 push 严格一一对应。
     *
     * @Unique 保证该字段只存在于本 Mixin，不会与目标类或其它 Mixin 冲突。
     */
    @Unique
    private boolean bbs$pushedMatrix = false;

    /**
     * 在 render 方法开始时注入，应用旋转和挤压变形
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(
        class_1540 entity,
        float yaw,
        float tickDelta,
        class_4587 matrices,
        class_4597 vertexConsumers,
        int light,
        CallbackInfo ci)
    {
        // 检查实体是否被标记为需要物理旋转
        if (!RotatingFallingBlockManager.isRotating(entity))
        {
            return;
        }

        UUID uuid = entity.method_5667();
        ClientRotationState state = ClientRotationStateManager.getOrCreate(uuid);

        // 从 DataTracker 读取服务端同步的数据
        float serverRotX = 0, serverRotY = 0, serverRotZ = 0;
        float serverAvx = 0, serverAvy = 0, serverAvz = 0;
        float squashAmount = 0;
        int squashDir = 0;
        float shrinkProgress = 0;

        try
        {
            serverRotX = entity.method_5841().method_12789(FallingBlockRotationData.ROTATION_X);
            serverRotY = entity.method_5841().method_12789(FallingBlockRotationData.ROTATION_Y);
            serverRotZ = entity.method_5841().method_12789(FallingBlockRotationData.ROTATION_Z);
            serverAvx = entity.method_5841().method_12789(FallingBlockRotationData.ANGULAR_VEL_X);
            serverAvy = entity.method_5841().method_12789(FallingBlockRotationData.ANGULAR_VEL_Y);
            serverAvz = entity.method_5841().method_12789(FallingBlockRotationData.ANGULAR_VEL_Z);
            squashAmount = entity.method_5841().method_12789(FallingBlockRotationData.SQUASH_AMOUNT);
            squashDir = entity.method_5841().method_12789(FallingBlockRotationData.SQUASH_DIRECTION);
            shrinkProgress = entity.method_5841().method_12789(FallingBlockRotationData.SHRINK_PROGRESS);
        }
        catch (Exception e)
        {
            // DataTracker 不可用，用 UUID 哈希推算角速度（兜底）
            float[] av = RotatingFallingBlockManager.getAngularVelocity(entity.method_5667());
            serverAvx = av[0];
            serverAvy = av[1];
            serverAvz = av[2];
        }

        int currentAge = entity.field_6012;

        // === 检测 tick 变化或首次初始化 ===
        // 当 entity.age 变化时，说明进入了新 tick，用服务端角度校准
        if (!state.initialized || currentAge != state.lastEntityAge)
        {
            // 新 tick：用服务端同步的角度校准（避免累积误差）
            state.rotationX = serverRotX;
            state.rotationY = serverRotY;
            state.rotationZ = serverRotZ;
            // 更新角速度（服务端每 tick 都在衰减，这里同步最新值）
            state.angularVelocityX = serverAvx;
            state.angularVelocityY = serverAvy;
            state.angularVelocityZ = serverAvz;
            state.lastEntityAge = currentAge;
            state.lastTickDelta = 0;
            state.initialized = true;
        }
        else
        {
            // 同一 tick 内：检查角速度是否更新了（DataTracker 可能在 tick 中途同步）
            if (serverAvx != state.angularVelocityX ||
                serverAvy != state.angularVelocityY ||
                serverAvz != state.angularVelocityZ)
            {
                state.angularVelocityX = serverAvx;
                state.angularVelocityY = serverAvy;
                state.angularVelocityZ = serverAvz;
            }
        }

        // === 关键：计算本帧相对于上一帧的 tickDelta 增量 ===
        // tickDelta 是 0~1 的值，表示当前 tick 已经过去的比例
        // 帧间增量 = 当前 tickDelta - 上一帧 tickDelta
        // 165hz 下每帧 tickDelta 增量约 0.033（6ms/50ms），旋转增量很小但每帧都有
        float delta = tickDelta - state.lastTickDelta;
        if (delta < 0)
        {
            // tickDelta 回退了（不应该发生，但安全处理）
            delta = 0;
        }
        state.lastTickDelta = tickDelta;

        // === 每帧累加旋转角度 ===
        // 这是解决卡顿的关键：每帧都有旋转增量，高刷新率下旋转丝滑
        state.rotationX += state.angularVelocityX * delta;
        state.rotationY += state.angularVelocityY * delta;
        state.rotationZ += state.angularVelocityZ * delta;

        float rotX = state.rotationX;
        float rotY = state.rotationY;
        float rotZ = state.rotationZ;

        // 清理过期的客户端状态
        ClientRotationStateManager.cleanupIfNeeded();

        // === 围绕方块几何中心 (0, 0.5, 0) 旋转 ===
        matrices.method_22903();
        // push 成功即立刻登记，保证 RETURN 处的 pop 与它严格一一对应
        this.bbs$pushedMatrix = true;
        matrices.method_22904(0.0, 0.5, 0.0);

        // === 非实体化模式：非线性缩小消失动画 ===
        // SHRINK_PROGRESS 由 BlockSplashAnimationScheduler 在动画结束阶段设置
        // 使用 ease-in quadratic（progress^2）实现丝滑缩小：开始慢，后期加速消失
        // scale = 1.0 - shrinkProgress，当 shrinkProgress=0 时无变化，=1 时完全消失
        if (shrinkProgress > 0.001F)
        {
            float scale = 1.0F - shrinkProgress;
            if (scale < 0.0F) scale = 0.0F;
            matrices.method_22905(scale, scale, scale);
        }

        // === 落地挤压变形 ===
        // 真实物理：物体落地时受冲击变形，体积守恒
        if (squashAmount > 0.001F)
        {
            float squash = squashAmount;
            float stretch = squash * 0.5F; // 体积守恒：压扁的量分到其他两轴

            float scaleX, scaleY, scaleZ;
            switch (squashDir)
            {
                case 0: // X 方向挤压（沿 X 滚动）
                    scaleX = 1.0F - squash;
                    scaleY = 1.0F + stretch;
                    scaleZ = 1.0F + stretch;
                    break;
                case 1: // Z 方向挤压（沿 Z 滚动）
                    scaleX = 1.0F + stretch;
                    scaleY = 1.0F + stretch;
                    scaleZ = 1.0F - squash;
                    break;
                case 2: // 垂直挤压（直接落地）
                default:
                    scaleX = 1.0F + stretch;
                    scaleY = 1.0F - squash;
                    scaleZ = 1.0F + stretch;
                    break;
            }

            matrices.method_22905(scaleX, scaleY, scaleZ);
        }

        // 应用旋转
        matrices.method_22907(new Quaternionf().rotationYXZ(
            (float) Math.toRadians(rotY),
            (float) Math.toRadians(rotX),
            (float) Math.toRadians(rotZ)
        ));

        matrices.method_22904(0.0, -0.5, 0.0);

        // 注意：不在此处置位标志——push 时已置位，若中途抛异常导致 RETURN 未执行，
        // 标志保持 true 会让"未 push 就 pop"的情况不会发生（宁少 pop 不多 pop）。
    }

    /**
     * 在 render 方法结束时 pop 矩阵栈
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderEnd(
        class_1540 entity,
        float yaw,
        float tickDelta,
        class_4587 matrices,
        class_4597 vertexConsumers,
        int light,
        CallbackInfo ci)
    {
        // 只 pop 自己 push 过的那一层（不再重复问 isRotating，避免栈失衡）
        if (this.bbs$pushedMatrix)
        {
            this.bbs$pushedMatrix = false;
            matrices.method_22909();
        }
    }
}
