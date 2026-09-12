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
 * ⚠️ 注解里的字段名必须写 **intermediary 名**（`field_XXXX`），不能写 yarn 名。
 * 本项目用 net.fabricmc:intermediary 作为开发映射，Loom 的 Mixin 注解处理器
 * 没有 named 命名空间可查 → 生成不出 refmap → 写 yarn 名会原样进入产物，
 * 运行时匹配不上目标字段（详见 build.gradle 里 mappings 一行的注释）。
 *
 * 注意：方法名带 bbs$ 前缀避免与其他 Mixin 的 @Accessor 冲突（AbstractMethodError 教训）。
 */
@Mixin(class_1540.class)
public interface FallingBlockEntityAccessor
{
    @Accessor("field_7188")
    void bbs$setBlock(class_2680 state);

    @Accessor("field_7188")
    class_2680 bbs$getBlock();

    /**
     * FallingBlockEntity.timeFalling（yarn 名）→ field_7192
     *
     * 原实现用 `@Shadow public int timeFalling;` 暴露该字段。但 @Shadow 要求
     * Java 字段名与目标字段名完全一致，也就是必须写成 `@Shadow public int field_7192;`，
     * 可读性差。改用 @Accessor 后既保留了 `timeFalling` 这个有意义的语义名，
     * 也与本仓库 UI Mixin "不用 @Shadow、改用 @Accessor 接口" 的既有做法一致。
     */
    @Accessor("field_7192")
    int bbs$getTimeFalling();

    @Accessor("field_7192")
    void bbs$setTimeFalling(int value);
}
