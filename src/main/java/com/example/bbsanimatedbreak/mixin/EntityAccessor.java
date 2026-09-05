package com.example.bbsanimatedbreak.mixin;

import net.minecraft.class_1297;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Entity 字段访问器 Mixin
 *
 * 用 @Accessor 接口 Mixin 暴露 Entity 类的 private 字段。
 *
 * - onGround：Entity 类的 private 字段（field_5952），
 *   FallingBlockEntity 继承自 Entity，但不能直接访问这个 private 字段。
 *   @Accessor 会生成 getter/setter 方法，通过反射访问字段。
 *
 * - prevX / prevY / prevZ：Entity 类的 private 字段，
 *   是渲染插值（prevPos + (pos - prevPos) * tickDelta）的"上一帧位置"。
 *   Entity.setPosition() 不会自动更新这些字段，所以手动管理 prevPos
 *   可以在 scheduler 中实现 165Hz 高刷新率下的流畅插值。
 *
 * 使用方式：
 *   boolean onGround = ((EntityAccessor) entity).isOnGround();
 *   ((EntityAccessor) entity).setOnGround(true);
 *   double px = ((EntityAccessor) entity).getPrevX();
 *   ((EntityAccessor) entity).setPrevX(123.0);
 */
@Mixin(class_1297.class)
public interface EntityAccessor
{
    @Accessor("onGround")
    boolean isOnGround();

    @Accessor("onGround")
    void setOnGround(boolean value);

    @Accessor("prevX")
    double getPrevX();

    @Accessor("prevX")
    void setPrevX(double value);

    @Accessor("prevY")
    double getPrevY();

    @Accessor("prevY")
    void setPrevY(double value);

    @Accessor("prevZ")
    double getPrevZ();

    @Accessor("prevZ")
    void setPrevZ(double value);
}
