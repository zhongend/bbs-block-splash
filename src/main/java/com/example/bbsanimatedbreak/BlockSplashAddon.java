package com.example.bbsanimatedbreak;

import com.example.bbsanimatedbreak.actions.BlockPathActionClip;
import com.example.bbsanimatedbreak.actions.BlockShockwaveActionClip;
import com.example.bbsanimatedbreak.actions.BlockSplashActionClip;
import com.example.bbsanimatedbreak.actions.BlockSplashComboActionClip;
import com.example.bbsanimatedbreak.actions.BlockSplashReverseActionClip;
import com.example.bbsanimatedbreak.actions.physics.PhysicsBlockEntityTypes;
import com.example.bbsanimatedbreak.actions.physics.PhysicsWorldRegistry;
import com.example.bbsanimatedbreak.items.RegionSelectorItem;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterSettingsEvent;
import mchorse.bbs_mod.events.register.RegisterSourcePacksEvent;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.resources.packs.InternalAssetsSourcePack;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.class_1792;
import net.minecraft.class_2378;
import net.minecraft.class_2960;
import net.minecraft.class_5321;
import net.minecraft.class_7706;
import net.minecraft.class_7923;
import net.minecraft.class_7924;

/**
 * BBS Block Splash addon 入口类
 *
 * 1. 把 BlockSplashActionClip 注册到 BBS 的 action clip 工厂
 * 2. 注册区域选择木棍物品并添加到 BBS 物品栏
 */
public class BlockSplashAddon implements ModInitializer, BBSAddonMod
{
    public static final String MOD_ID = "bbsblocksplash";

    /* 区域选择木棍物品实例 */
    public static final RegionSelectorItem REGION_SELECTOR_STICK =
        new RegionSelectorItem(new class_1792.class_1793().method_7889(1));

    @Override
    public void onInitialize()
    {
        // 注册区域选择木棍物品
        class_2378.method_10230(class_7923.field_41178, new class_2960(MOD_ID, "region_selector"), REGION_SELECTOR_STICK);

        // 添加到 BBS 的创造模式物品栏（itemGroup.bbs.main）
        // 如果 BBS 物品栏未注册，会自动回退到原版工具栏
        class_5321<net.minecraft.class_1761> bbsGroupKey = class_5321.method_29179(class_7924.field_44688, new class_2960("bbs", "main"));
        ItemGroupEvents.modifyEntriesEvent(bbsGroupKey).register(entries ->
        {
            entries.method_45421(REGION_SELECTOR_STICK);
        });

        // 同时添加到原版工具栏，确保玩家能拿到
        ItemGroupEvents.modifyEntriesEvent(class_7706.field_41060).register(entries ->
        {
            entries.method_45421(REGION_SELECTOR_STICK);
        });

        // === 注册 Sable 物理方块实体类型 ===
        // 触发 PhysicsBlockEntityTypes 类的静态初始化（Registry.register 在静态块中）
        PhysicsBlockEntityTypes.register();

        // === 注册服务器停止事件：恢复飞溅方块，防止退出导致存档损坏 ===
        // 如果方块飞溅动画播放途中退出游戏，BBS 的 DamageControl 来不及恢复，
        // 会导致原区域方块被永久设为空气，飞溅实体落地变成新方块。
        // 这里在服务器停止时强制恢复所有被飞溅的方块，保护玩家存档。
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
        {
            // 清理振波调度器（恢复所有未完成的震动方块）
            BlockShockwaveScheduler.clearAll();
            // 清理反向飞溅调度器（恢复所有未完成的飞溅方块）
            BlockSplashReverseScheduler.clearAll();
            // 清理非实体化方块动画调度器（移除所有未消失的动画方块）
            BlockSplashAnimationScheduler.clearAll();
            // 销毁所有原生物理世界（释放 native 内存 + 连带清理 PhysicsRecording）
            // 必须在 BlockSplashRecoveryManager.restoreAll 之前调用，
            // 因为 clearAll 不清理 Recovery 记录，restoreAll 仍能找到记录恢复方块
            PhysicsWorldRegistry.clearAll();
            // 恢复所有飞溅方块（用 Recovery 记录兜底恢复）
            BlockSplashRecoveryManager.restoreAll(server);
            // 清空 Recovery 记录（restoreAll 已完成，记录不再需要）
            BlockSplashRecoveryManager.clearAll();
        });

        // === 注册服务端 tick 事件：驱动调度器 ===
        // 振波调度器、反向飞溅调度器、非实体化方块动画调度器都需要每 tick 更新
        ServerTickEvents.END_SERVER_TICK.register(server ->
        {
            BlockShockwaveScheduler.tick();
            BlockSplashReverseScheduler.tick();
            BlockPathScheduler.tick();
            BlockSplashAnimationScheduler.tick();
            // 步进所有原生物理世界（Rapier3d pipeline）
            PhysicsWorldRegistry.tickAll(1.0 / 20.0);
        });
    }

    @Subscribe
    public void onRegisterSettings(RegisterSettingsEvent event)
    {
        // 注册方块飞溅
        BBSMod.getFactoryActionClips().register(
            Link.bbs("block_splash"),
            BlockSplashActionClip.class,
            new ClipFactoryData(Icons.BLOCK, Colors.RED)
        );

        // 注册方块振波
        BBSMod.getFactoryActionClips().register(
            Link.bbs("block_shockwave"),
            BlockShockwaveActionClip.class,
            new ClipFactoryData(Icons.BLOCK, Colors.ORANGE)
        );

        // 注册方块飞溅（反向版本）
        BBSMod.getFactoryActionClips().register(
            Link.bbs("block_splash_reverse"),
            BlockSplashReverseActionClip.class,
            new ClipFactoryData(Icons.BLOCK, Colors.GREEN)
        );

        // 注册方块路径运动
        BBSMod.getFactoryActionClips().register(
            Link.bbs("block_path"),
            BlockPathActionClip.class,
            new ClipFactoryData(Icons.BLOCK, Colors.BLUE)
        );

        // 注册飞溅组合（多效果组合 + 丝滑过渡）
        BBSMod.getFactoryActionClips().register(
            Link.bbs("block_splash_combo"),
            BlockSplashComboActionClip.class,
            new ClipFactoryData(Icons.BLOCK, Colors.MAGENTA)
        );
    }

    /**
     * 注册本 mod 的资源源包，使 BBS 的 AssetProvider 能解析
     * source="bbsblocksplash" 的 Link，从而加载 strings 翻译文件。
     */
    @Subscribe
    public void onRegisterSourcePacks(RegisterSourcePacksEvent event)
    {
        AssetProvider provider = event.provider;
        provider.register(new InternalAssetsSourcePack(
            MOD_ID,
            "assets/" + MOD_ID,
            BlockSplashAddon.class
        ));
    }
}
