package com.example.bbsanimatedbreak.actions.physics;

import com.example.bbsanimatedbreak.BlockSplashAddon;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.class_1299;
import net.minecraft.class_1311;
import net.minecraft.class_2378;
import net.minecraft.class_2960;
import net.minecraft.class_4048;
import net.minecraft.class_7923;

/**
 * PhysicsBlockEntity 的实体类型注册
 *
 * 在 BlockSplashAddon.onInitialize() 中调用 PhysicsBlockEntityTypes.register() 触发注册。
 */
public final class PhysicsBlockEntityTypes
{
    public static final class_1299<PhysicsBlockEntity> PHYSICS_BLOCK = class_2378.method_10230(
        class_7923.field_41177,
        new class_2960(BlockSplashAddon.MOD_ID, "physics_block"),
        FabricEntityTypeBuilder.<PhysicsBlockEntity>create(class_1311.field_17715, PhysicsBlockEntity::new)
            .dimensions(class_4048.method_18385(0.98f, 0.98f))
            .trackRangeBlocks(160)
            .trackedUpdateRate(1)
            .build()
    );

    /**
     * 空方法，引用此类以触发静态初始化
     */
    public static void register() {}
}
