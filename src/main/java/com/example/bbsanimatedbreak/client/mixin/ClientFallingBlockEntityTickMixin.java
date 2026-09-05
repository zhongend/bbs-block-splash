package com.example.bbsanimatedbreak.client.mixin;

import com.example.bbsanimatedbreak.FallingBlockRotationData;
import net.minecraft.class_1540;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 FallingBlockEntity Tick Mixin
 *
 * === 作用 ===
 * 跳过路径运动方块的客户端 tick()，修复 165Hz 高刷新率下位置卡顿。
 *
 * === 根因 ===
 * 路径运动方块的位置由服务端 BlockPathScheduler 用 setPosition 直接控制，
 * 通过 EntityPositionS2CPacket 每 server tick（20 TPS）同步到客户端。
 *
 * 客户端收到同步包后设置 pos（不更新 prevPos），渲染时用
 *   prevPos + (pos - prevPos) * tickDelta
 * 插值，理论上可以平滑。
 *
 * 但客户端 FallingBlockEntity.tick() 每 client tick 会执行
 *   prevX = getX(); prevY = getY(); prevZ = getZ();
 * 把 prevPos 设为 pos，破坏渲染插值。
 *
 * 结果：165Hz 显示器渲染 165 帧/秒，但只有 20 帧有不同位置 → 看起来像 20 帧。
 *
 * === 修复 ===
 * 在 tick() 开头检查 PATH_MOVEMENT DataTracker 标志，
 * 如果为 true 则 cancel() 跳过整个 tick()，
 * 让 prevPos 保持同步包设置的值，渲染时可以正确插值。
 *
 * 旋转不受影响：ClientRotationStateManager 每帧累加旋转，不依赖 tick。
 */
@Mixin(class_1540.class)
public abstract class ClientFallingBlockEntityTickMixin
{
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void skipTickForPathMovement(CallbackInfo ci)
    {
        class_1540 self = (class_1540) (Object) this;

        /* 只在客户端执行 */
        if (!self.method_37908().field_9236) return;

        try
        {
            /* 检查是否是路径运动方块 */
            if (self.method_5841().method_12789(FallingBlockRotationData.PATH_MOVEMENT))
            {
                /* 跳过整个 tick()，防止 prevPos = pos 破坏渲染插值 */
                ci.cancel();
            }
        }
        catch (Exception e)
        {
            /* DataTracker 可能未初始化，忽略 */
        }
    }
}
