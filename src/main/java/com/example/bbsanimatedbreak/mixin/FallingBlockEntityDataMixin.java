package com.example.bbsanimatedbreak.mixin;

import com.example.bbsanimatedbreak.FallingBlockRotationData;
import net.minecraft.class_1540;
import net.minecraft.class_2945;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FallingBlockEntity DataTracker Mixin
 *
 * 给 FallingBlockEntity 的 DataTracker 注册旋转相关字段：
 * - IS_ROTATING：是否启用旋转物理（Boolean，服务端设置自动同步到客户端）
 * - ROTATION_X / ROTATION_Y / ROTATION_Z：当前累计旋转角度（度）
 *
 * TrackedData 字段本身定义在 FallingBlockRotationData 普通类中，
 * 因为 Mixin 规范不允许在 Mixin 类里定义非 private 的 static 字段。
 *
 * DataTracker 会自动从服务端同步到客户端，
 * 客户端渲染 Mixin 直接读取这些值应用旋转。
 *
 * 重要：之前用命令标签（addCommandTag）标记旋转，但命令标签
 * 不会自动同步到客户端。改用 DataTracker 的 Boolean 字段同步。
 */
@Mixin(class_1540.class)
public abstract class FallingBlockEntityDataMixin
{
    /**
     * 在 initDataTracker 末尾注入，注册旋转字段
     */
    @Inject(method = "initDataTracker", at = @At("RETURN"))
    private void onInitDataTracker(CallbackInfo ci)
    {
        class_1540 self = (class_1540) (Object) this;
        class_2945 tracker = self.method_5841();

        tracker.method_12784(FallingBlockRotationData.IS_ROTATING, false);
        tracker.method_12784(FallingBlockRotationData.ROTATION_X, 0.0F);
        tracker.method_12784(FallingBlockRotationData.ROTATION_Y, 0.0F);
        tracker.method_12784(FallingBlockRotationData.ROTATION_Z, 0.0F);
        tracker.method_12784(FallingBlockRotationData.ANGULAR_VEL_X, 0.0F);
        tracker.method_12784(FallingBlockRotationData.ANGULAR_VEL_Y, 0.0F);
        tracker.method_12784(FallingBlockRotationData.ANGULAR_VEL_Z, 0.0F);
        tracker.method_12784(FallingBlockRotationData.SQUASH_AMOUNT, 0.0F);
        tracker.method_12784(FallingBlockRotationData.SQUASH_DIRECTION, 0);
        tracker.method_12784(FallingBlockRotationData.PATH_MOVEMENT, false);
        tracker.method_12784(FallingBlockRotationData.NO_SOLIDIFY, false);
        tracker.method_12784(FallingBlockRotationData.SHRINK_PROGRESS, 0.0F);
        // PhysicsBlockEntity 专用旋转四元数（注册到所有 FallingBlockEntity，子类继承可用）
        tracker.method_12784(FallingBlockRotationData.PHYSICS_ROT_X, 0.0F);
        tracker.method_12784(FallingBlockRotationData.PHYSICS_ROT_Y, 0.0F);
        tracker.method_12784(FallingBlockRotationData.PHYSICS_ROT_Z, 0.0F);
        tracker.method_12784(FallingBlockRotationData.PHYSICS_ROT_W, 1.0F);
    }
}
