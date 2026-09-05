package com.example.bbsanimatedbreak;

import net.minecraft.class_1540;
import net.minecraft.class_2940;
import net.minecraft.class_2943;
import net.minecraft.class_2945;

/**
 * FallingBlockEntity 旋转数据的 TrackedData 注册表
 *
 * Mixin 规范不允许在 Mixin 类中定义非 private 的 static 字段，
 * 所以把 TrackedData 定义在这个普通工具类中。
 *
 * FallingBlockEntityDataMixin 在 initDataTracker 时注册这些字段，
 * DataTracker 会自动从服务端同步到客户端，
 * 客户端渲染 Mixin 和服务端 PhysicsMixin 都引用这些字段。
 *
 * 重要：之前用命令标签（addCommandTag）标记旋转，但命令标签
 * 不会自动同步到客户端，导致客户端 isRotating() 永远返回 false。
 * 改用 DataTracker 的 Boolean 字段同步旋转标志。
 */
public class FallingBlockRotationData
{
    /** 是否启用旋转物理（服务端设置，自动同步到客户端） */
    public static final class_2940<Boolean> IS_ROTATING =
        class_2945.method_12791(class_1540.class, class_2943.field_13323);

    /** 三轴旋转角度（度） */
    public static final class_2940<Float> ROTATION_X =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);
    public static final class_2940<Float> ROTATION_Y =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);
    public static final class_2940<Float> ROTATION_Z =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);

    /** 三轴角速度（度/tick，用于客户端插值，避免高帧率下旋转卡顿） */
    public static final class_2940<Float> ANGULAR_VEL_X =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);
    public static final class_2940<Float> ANGULAR_VEL_Y =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);
    public static final class_2940<Float> ANGULAR_VEL_Z =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);

    /**
     * 落地挤压变形强度（0.0=无变形，1.0=最大变形）
     * 服务端在落地碰撞时设置，客户端读取后做 scale 变形
     * 每帧自然衰减（客户端本地衰减，不需要服务端持续同步）
     */
    public static final class_2940<Float> SQUASH_AMOUNT =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);

    /**
     * 落地挤压方向（0=X方向挤压，1=Z方向挤压，2=垂直挤压）
     * 配合 SQUASH_AMOUNT 使用，决定挤压变形的方向
     */
    public static final class_2940<Integer> SQUASH_DIRECTION =
        class_2945.method_12791(class_1540.class, class_2943.field_13327);

    /**
     * 是否是路径运动方块（服务端设置，自动同步到客户端）
     *
     * 客户端 mixin 读取此标志，如果为 true 则跳过 FallingBlockEntity.tick()，
     * 防止客户端 tick 把 prevPos = pos 破坏渲染插值。
     *
     * 路径运动方块的位置由服务端 BlockPathScheduler 用 setPosition 直接控制，
     * 通过 EntityPositionS2CPacket 同步到客户端。
     * 如果客户端 tick 正常运行，会把 prevPos = pos，导致渲染时没有插值空间，
     * 165Hz 显示器只有 20 帧有效画面。
     */
    public static final class_2940<Boolean> PATH_MOVEMENT =
        class_2945.method_12791(class_1540.class, class_2943.field_13323);

    /**
     * 是否不实体化（服务端设置，自动同步到客户端）
     *
     * true = 方块飞溅后不变成实体方块，保持动画形式
     *        物理模拟继续（旋转、移动），到时间后非线性缩小消失
     * false = 正常行为（落地变方块）
     *
     * 服务端 FallingBlockEntityPhysicsMixin 读取此标志，
     * 在 tick HEAD 重置 timeFalling = 0，阻止原版的"落地变方块"逻辑触发。
     */
    public static final class_2940<Boolean> NO_SOLIDIFY =
        class_2945.method_12791(class_1540.class, class_2943.field_13323);

    /**
     * 缩小消失动画进度（0.0 = 正常大小，1.0 = 完全消失）
     *
     * 由 BlockSplashAnimationScheduler 在动画结束阶段设置，
     * 客户端渲染 Mixin 读取此值应用 scale 变换。
     * 使用非线性插值（ease-in quadratic）实现丝滑缩小。
     */
    public static final class_2940<Float> SHRINK_PROGRESS =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);

    /**
     * === PhysicsBlockEntity 专用：旋转四元数 ===
     *
     * 用 FallingBlockEntity.class 注册（不能用 PhysicsBlockEntity.class），
     * 否则 DataTracker 的 ID 空间会与父类已注册的字段冲突（Duplicate id value）。
     * 子类继承父类的所有 tracked data，必须共享同一个 ID 空间。
     */
    public static final class_2940<Float> PHYSICS_ROT_X =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);
    public static final class_2940<Float> PHYSICS_ROT_Y =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);
    public static final class_2940<Float> PHYSICS_ROT_Z =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);
    public static final class_2940<Float> PHYSICS_ROT_W =
        class_2945.method_12791(class_1540.class, class_2943.field_13320);
}
