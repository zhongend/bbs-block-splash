package com.example.bbsanimatedbreak.mixin;

import com.example.bbsanimatedbreak.FallingBlockRotationData;
import net.minecraft.class_1297;
import net.minecraft.class_1540;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity 类 Mixin —— 拦截 isOnGround() 方法
 *
 * === 非实体化模式的核心 ===
 * 原版 FallingBlockEntity.tick() 在 move() 后执行：
 *   if (isOnGround() || concreteInWater) {
 *       // 落地：速度衰减、canReplace 检查、setBlockState 放置方块、discard
 *   } else {
 *       // 未落地：检查 timeFalling > 100 && > 600 超时移除
 *   }
 *
 * 当 NO_SOLIDIFY=true 时，这里让 isOnGround() 返回 false，
 * 骗原版"未落地"，跳过整个变方块代码块（setBlockState + discard），
 * 方块保持 FallingBlockEntity 形态继续物理模拟（弹跳、滚动、摩擦）。
 *
 * === 为什么放在 Entity mixin 而不是 FallingBlockEntity mixin ===
 * isOnGround() 方法定义在 Entity 类，FallingBlockEntity 继承它。
 * Fabric Loom 在 remap 时只查目标类的直接方法表，
 * 在 FallingBlockEntity mixin 上注入 isOnGround() 会因"方法不存在"而无法映射，
 * 运行时注入失败。放在 Entity mixin 上能正确映射。
 *
 * === 性能 ===
 * 对非 FallingBlockEntity 实体，instanceof 检查快速返回，影响极小。
 * 对 NO_SOLIDIFY=false 的 FallingBlockEntity，DataTracker.get 返回 false后返回，影响小。
 * 物理 Mixin 通过 EntityAccessor 直接读 onGround 字段，不走此方法，不受影响。
 */
@Mixin(class_1297.class)
public abstract class BbsEntityMixin
{
    @Inject(method = "method_24828", at = @At("HEAD"), cancellable = true)
    private void bbs$noSolidifyIsOnGround(CallbackInfoReturnable<Boolean> cir)
    {
        class_1297 self = (class_1297) (Object) this;

        /* 只处理下落方块实体 */
        if (!(self instanceof class_1540)) return;

        try
        {
            class_1540 fbe = (class_1540) self;
            if (fbe.method_5841().method_12789(FallingBlockRotationData.NO_SOLIDIFY))
            {
                cir.setReturnValue(false);
            }
        }
        catch (Exception e)
        {
            /* 忽略 */
        }
    }
}
