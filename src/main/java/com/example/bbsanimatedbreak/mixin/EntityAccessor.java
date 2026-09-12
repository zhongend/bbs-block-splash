package com.example.bbsanimatedbreak.mixin;

import net.minecraft.class_1297;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Entity 的私有字段访问器（Mixin @Accessor）
 *
 * === 为什么方法名必须带 `bbs$` 前缀（重要，勿"清理"掉） ===
 * Mixin 的 @Accessor 会生成一个与目标类字段同名语义的方法实现。
 * 如果接口方法名与目标类<b>已有的方法</b>同名同签名，Mixin 不会覆盖它——
 * 该接口方法会被目标类的既有方法满足，@Accessor 形同不存在。
 *
 * 历史教训：本访问器最初把读取方法命名为 `isOnGround()`，与
 * `Entity.isOnGround()`（class_1297 上真实存在的方法）完全重名，
 * 于是 `((EntityAccessor) x).isOnGround()` 实际调用的是 vanilla 方法，
 * 而不是读取 `onGround` 字段。
 *
 * 后果很隐蔽：`BbsEntityMixin` 会在 vanilla 的 `isOnGround()` 上对
 * NO_SOLIDIFY 实体强制返回 false（用来骗过"落地变方块"判定），
 * 于是物理层读到的 onGround 恒为 false → 落地检测失效 →
 * 弹跳、地面摩擦、旋转平滑归零全部不生效，方块看起来"不受物理控制"。
 *
 * 同样的问题在 FallingBlockEntityAccessor 上也踩过一次，故本仓库统一约定：
 * 访问器方法一律加 `bbs$` 前缀。
 *
 * 使用方式：
 *   boolean onGround = ((EntityAccessor) entity).bbs$isOnGround();
 *   ((EntityAccessor) entity).bbs$setOnGround(true);
 *   double px = ((EntityAccessor) entity).getPrevX();
 *   ((EntityAccessor) entity).setPrevX(123.0);
 */
@Mixin(class_1297.class)
public interface EntityAccessor
{
    /* 注意：方法名不能叫 isOnGround()（与 vanilla 方法冲突，@Accessor 会失效） */
    @Accessor("onGround")
    boolean bbs$isOnGround();

    @Accessor("onGround")
    void bbs$setOnGround(boolean value);

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
