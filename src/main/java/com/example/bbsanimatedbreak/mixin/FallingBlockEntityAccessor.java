package com.example.bbsanimatedbreak.mixin;

import net.minecraft.class_1540;
import net.minecraft.class_2680;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * FallingBlockEntity 字段访问器 Mixin
 *
 * FallingBlockEntity 的 `block` 字段是 private，且没有公开的 setter。
 * 唯一设置 block 的入口是私有构造函数 FallingBlockEntity(World, x, y, z, BlockState)。
 *
 * PhysicsBlockEntity 继承 FallingBlockEntity 后无法通过 super 调用该私有构造，
 * 因此用 @Accessor 暴露 block 字段，让 PhysicsBlockEntity 在构造后能设置方块状态。
 *
 * 使用方式：
 *   ((FallingBlockEntityAccessor) entity).bbs$setBlock(state);
 *   BlockState s = ((FallingBlockEntityAccessor) entity).bbs$getBlock();
 *
 * 注意：方法名带 bbs$ 前缀避免与其他 Mixin 的 @Accessor 冲突（AbstractMethodError 教训）。
 */
@Mixin(class_1540.class)
public interface FallingBlockEntityAccessor
{
    @Accessor("block")
    void bbs$setBlock(class_2680 state);

    @Accessor("block")
    class_2680 bbs$getBlock();
}
