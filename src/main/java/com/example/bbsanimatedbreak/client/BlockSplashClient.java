package com.example.bbsanimatedbreak.client;

import com.example.bbsanimatedbreak.BlockSplashAddon;
import com.example.bbsanimatedbreak.RegionSelectionCache;
import com.example.bbsanimatedbreak.actions.BlockPathActionClip;
import com.example.bbsanimatedbreak.actions.BlockShockwaveActionClip;
import com.example.bbsanimatedbreak.actions.BlockSplashActionClip;
import com.example.bbsanimatedbreak.actions.BlockSplashReverseActionClip;
import com.example.bbsanimatedbreak.actions.physics.PhysicsBlockEntityTypes;
import com.example.bbsanimatedbreak.items.RegionSelectorItem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.class_1269;
import net.minecraft.class_1657;
import net.minecraft.class_1937;
import net.minecraft.class_2338;
import net.minecraft.class_2398;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_3417;
import net.minecraft.class_638;

/**
 * 客户端入口类
 *
 * 1. 把 BlockSplashActionClip 的编辑界面注册到 BBS 的 UIClip 工厂
 * 2. 注册本 mod 的语言文件源
 * 3. 注册区域选择木棍的左键事件（选择第一个坐标点 pos1，不破坏方块）
 * 4. 注册客户端 tick 事件，绘制已选区域的粒子描边
 */
public class BlockSplashClient implements ClientModInitializer
{
    @Override
    public void onInitializeClient()
    {
        // 注册物理方块实体的渲染器（Sable 风格旋转渲染）
        EntityRendererRegistry.register(PhysicsBlockEntityTypes.PHYSICS_BLOCK, PhysicsBlockEntityRenderer::new);

        // === 3.0 Physics Bake：独立动画文档的回放渲染（规范 §47） ===
        // START 重置本帧捕获标志；AFTER_ENTITIES 求值并渲染文档轨道。
        // 捕获本身由 BaseFilmControllerMixin 在 startRenderFrame HEAD 完成。
        WorldRenderEvents.START.register((context) -> DocumentPlaybackRenderer.onFrameReset());
        WorldRenderEvents.AFTER_ENTITIES.register(DocumentPlaybackRenderer::renderAfterEntities);

        // 注册编辑面板
        UIClip.register(BlockSplashActionClip.class, UIBlockSplashActionClip::new);
        UIClip.register(BlockShockwaveActionClip.class, UIBlockShockwaveActionClip::new);
        UIClip.register(BlockSplashReverseActionClip.class, UIBlockSplashReverseActionClip::new);
        UIClip.register(BlockPathActionClip.class, UIBlockPathActionClip::new);

        // 注册语言文件源并重新加载
        try
        {
            L10n l10n = BBSModClient.getL10n();

            if (l10n != null)
            {
                l10n.registerOne(lang -> new Link(BlockSplashAddon.MOD_ID, "strings/" + lang + ".json"));
                String currentLang = BBSModClient.getLanguageKey();
                BBSModClient.reloadLanguage(currentLang);
            }
        }
        catch (Exception e)
        {
            System.err.println("[BlockSplash] Failed to register translations: " + e.getMessage());
        }

        // 注册区域选择木棍的左键事件
        // 左键方块时选择第一个坐标点（pos1），并阻止方块破坏
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
        {
            // 只在客户端处理
            if (!world.field_9236 || player == null)
            {
                return class_1269.field_5811;
            }

            // 检查玩家手中拿的是否是区域选择木棍
            if (player.method_5998(hand).method_7909() instanceof RegionSelectorItem)
            {
                // 选择第一个坐标点
                RegionSelectionCache.setPos1(pos);
                player.method_7353(class_2561.method_43470("§b[方块飞溅] §a已选择第一个点 (pos1): §f" + pos.method_10263() + ", " + pos.method_10264() + ", " + pos.method_10260()), true);

                // 播放经验吸收音效作为选择反馈
                player.method_5783(class_3417.field_14627, 0.1F, 1.0F);

                // 如果两个点都选了，显示方块数量
                if (RegionSelectionCache.hasPos2())
                {
                    class_2338 pos2 = RegionSelectionCache.getPos2();
                    int count = this.countBlocksInArea(pos, pos2, world);
                    player.method_7353(class_2561.method_43470("§b[方块飞溅] §e区域共 §f" + count + " §e个方块（已剔除空气），可在编辑面板粘贴"), true);
                }

                // 返回 SUCCESS 阻止方块被破坏（同时触发挥手动画作为反馈）
                return class_1269.field_5812;
            }

            return class_1269.field_5811;
        });

        // 注册客户端 tick 事件，绘制已选区域的粒子描边
        ClientTickEvents.END_CLIENT_TICK.register(this::drawRegionOutline);
    }

    /**
     * 绘制已选区域的粒子描边
     * 当玩家手中拿着区域选择木棍，且至少选了一个点时，每 tick 绘制粒子边框
     */
    private void drawRegionOutline(class_310 client)
    {
        class_1657 player = client.field_1724;
        class_638 world = client.field_1687;

        if (player == null || world == null)
        {
            return;
        }

        // 只在玩家手持区域选择木棍时显示描边
        boolean holdingStick = player.method_6047().method_7909() instanceof RegionSelectorItem
                            || player.method_6079().method_7909() instanceof RegionSelectorItem;

        if (!holdingStick)
        {
            return;
        }

        class_2338 pos1 = RegionSelectionCache.getPos1();
        class_2338 pos2 = RegionSelectionCache.getPos2();

        // 只有一个点时，画单点标记
        if (pos1 != null && pos2 == null)
        {
            this.drawPointMarker(world, pos1);
            return;
        }

        if (pos2 != null && pos1 == null)
        {
            this.drawPointMarker(world, pos2);
            return;
        }

        // 两个点都有时，画立方体边框
        if (pos1 != null && pos2 != null)
        {
            this.drawCuboidOutline(world, pos1, pos2);
        }
    }

    /**
     * 在单个方块位置画粒子标记（顶点 8 个角）
     */
    private void drawPointMarker(class_638 world, class_2338 pos)
    {
        double x = pos.method_10263();
        double y = pos.method_10264();
        double z = pos.method_10260();

        // 画方块的 8 个角
        for (int dx = 0; dx <= 1; dx++)
        {
            for (int dy = 0; dy <= 1; dy++)
            {
                for (int dz = 0; dz <= 1; dz++)
                {
                    world.method_8406(class_2398.field_11207,
                        x + dx, y + dy, z + dz,
                        0, 0, 0);
                }
            }
        }
    }

    /**
     * 画立方体的 12 条边的粒子描边
     */
    private void drawCuboidOutline(class_638 world, class_2338 pos1, class_2338 pos2)
    {
        double minX = Math.min(pos1.method_10263(), pos2.method_10263());
        double minY = Math.min(pos1.method_10264(), pos2.method_10264());
        double minZ = Math.min(pos1.method_10260(), pos2.method_10260());
        double maxX = Math.max(pos1.method_10263(), pos2.method_10263()) + 1; // +1 让边框贴齐方块外缘
        double maxY = Math.max(pos1.method_10264(), pos2.method_10264()) + 1;
        double maxZ = Math.max(pos1.method_10260(), pos2.method_10260()) + 1;

        double step = 0.5; // 粒子间距

        // 12 条边
        // 沿 X 轴的 4 条边（底面 2 条 + 顶面 2 条）
        this.drawEdge(world, minX, minY, minZ, maxX, minY, minZ, step);
        this.drawEdge(world, minX, minY, maxZ, maxX, minY, maxZ, step);
        this.drawEdge(world, minX, maxY, minZ, maxX, maxY, minZ, step);
        this.drawEdge(world, minX, maxY, maxZ, maxX, maxY, maxZ, step);

        // 沿 Y 轴的 4 条边
        this.drawEdge(world, minX, minY, minZ, minX, maxY, minZ, step);
        this.drawEdge(world, maxX, minY, minZ, maxX, maxY, minZ, step);
        this.drawEdge(world, minX, minY, maxZ, minX, maxY, maxZ, step);
        this.drawEdge(world, maxX, minY, maxZ, maxX, maxY, maxZ, step);

        // 沿 Z 轴的 4 条边
        this.drawEdge(world, minX, minY, minZ, minX, minY, maxZ, step);
        this.drawEdge(world, maxX, minY, minZ, maxX, minY, maxZ, step);
        this.drawEdge(world, minX, maxY, minZ, minX, maxY, maxZ, step);
        this.drawEdge(world, maxX, maxY, minZ, maxX, maxY, maxZ, step);
    }

    /**
     * 沿一条边画粒子
     */
    private void drawEdge(class_638 world, double x1, double y1, double z1,
                          double x2, double y2, double z2, double step)
    {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (length < 0.01)
        {
            return;
        }

        int count = (int) Math.ceil(length / step);
        for (int i = 0; i <= count; i++)
        {
            double t = (i * step) / length;
            if (t > 1.0) t = 1.0;

            world.method_8406(class_2398.field_11207,
                x1 + dx * t, y1 + dy * t, z1 + dz * t,
                0, 0, 0);
        }
    }

    /**
     * 计算区域内非空气方块数量（剔除空气方块）
     */
    private int countBlocksInArea(class_2338 pos1, class_2338 pos2, class_1937 world)
    {
        int minX = Math.min(pos1.method_10263(), pos2.method_10263());
        int minY = Math.min(pos1.method_10264(), pos2.method_10264());
        int minZ = Math.min(pos1.method_10260(), pos2.method_10260());
        int maxX = Math.max(pos1.method_10263(), pos2.method_10263());
        int maxY = Math.max(pos1.method_10264(), pos2.method_10264());
        int maxZ = Math.max(pos1.method_10260(), pos2.method_10260());

        int count = 0;
        for (int bx = minX; bx <= maxX; bx++)
        {
            for (int by = minY; by <= maxY; by++)
            {
                for (int bz = minZ; bz <= maxZ; bz++)
                {
                    if (!world.method_8320(new class_2338(bx, by, bz)).method_26215())
                    {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
