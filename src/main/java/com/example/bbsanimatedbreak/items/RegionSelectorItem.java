package com.example.bbsanimatedbreak.items;

import com.example.bbsanimatedbreak.RegionSelectionCache;
import java.util.List;
import net.minecraft.class_124;
import net.minecraft.class_1268;
import net.minecraft.class_1269;
import net.minecraft.class_1271;
import net.minecraft.class_1657;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_1836;
import net.minecraft.class_1838;
import net.minecraft.class_1937;
import net.minecraft.class_2338;
import net.minecraft.class_2561;
import net.minecraft.class_2680;
import net.minecraft.class_3417;

/**
 * 方块飞溅区域选择木棍
 *
 * 用法：
 * - 左键方块：选择第一个坐标点（pos1）—— 通过 AttackBlockCallback 拦截，不会破坏方块
 * - 右键方块：选择第二个坐标点（pos2）—— 不会触发方块交互（开门/开箱）
 * - Shift + 右键空气：查看当前已选坐标
 *
 * 选中的坐标会存到 RegionSelectionCache，
 * 在飞溅效果编辑面板点"粘贴坐标"按钮一键填入。
 */
public class RegionSelectorItem extends class_1792
{
    public RegionSelectorItem(class_1793 settings)
    {
        super(settings);
    }

    /**
     * 阻止方块破坏（参考剑的模式）
     * 剑左键不破坏方块就是通过重写此方法返回 false 实现的
     * 返回 false 后，客户端不会开始破坏进度，服务端也不会处理破坏
     */
    @Override
    public boolean method_7885(class_2680 state, class_1937 world, class_2338 pos, class_1657 miner)
    {
        return false;
    }

    /**
     * 右键点击方块 —— 选择第二个坐标点（pos2）
     * 返回 SUCCESS 阻止方块交互（不会开门/开箱/按按钮）
     */
    @Override
    public class_1269 method_7884(class_1838 context)
    {
        class_1657 player = context.method_8036();
        class_1937 world = context.method_8045();
        class_2338 pos = context.method_8037();

        if (world.field_9236 && player != null)
        {
            // Shift + 右键方块也显示查看信息（统一行为）
            if (player.method_5715())
            {
                this.showCurrentSelection(player, world);
                return class_1269.field_5812;
            }

            RegionSelectionCache.setPos2(pos);
            player.method_7353(class_2561.method_43470("§b[方块飞溅] §a已选择第二个点 (pos2): §f" + pos.method_10263() + ", " + pos.method_10264() + ", " + pos.method_10260()), true);

            // 播放经验吸收音效作为选择反馈
            player.method_5783(class_3417.field_14627, 0.1F, 1.0F);

            if (RegionSelectionCache.hasPos1())
            {
                class_2338 pos1 = RegionSelectionCache.getPos1();
                int count = this.countBlocksInArea(pos1, pos, world);
                player.method_7353(class_2561.method_43470("§b[方块飞溅] §e区域共 §f" + count + " §e个方块（已剔除空气）"), true);
            }
        }

        return class_1269.field_5812;
    }

    /**
     * 右键点击空气
     * - Shift + 右键空气：查看当前已选坐标
     * - 普通右键空气：提示用法
     */
    @Override
    public class_1271<class_1799> method_7836(class_1937 world, class_1657 user, class_1268 hand)
    {
        if (world.field_9236 && user != null)
        {
            if (user.method_5715())
            {
                // Shift + 右键：查看当前选择
                this.showCurrentSelection(user, world);
            }
            else
            {
                // 普通右键空气：提示用法
                user.method_7353(class_2561.method_43470("§b[方块飞溅] §e左键选第一个点，右键选第二个点，Shift+右键查看"), true);
            }
        }

        return class_1271.method_22427(user.method_5998(hand));
    }

    /**
     * 显示当前已选坐标
     */
    private void showCurrentSelection(class_1657 player, class_1937 world)
    {
        class_2338 pos1 = RegionSelectionCache.getPos1();
        class_2338 pos2 = RegionSelectionCache.getPos2();

        if (pos1 == null && pos2 == null)
        {
            player.method_7353(class_2561.method_43470("§b[方块飞溅] §c尚未选择任何坐标"), true);
            return;
        }

        if (pos1 != null)
        {
            player.method_7353(class_2561.method_43470("§b[方块飞溅] §a第一个点: §f" + pos1.method_10263() + ", " + pos1.method_10264() + ", " + pos1.method_10260()), true);
        }
        else
        {
            player.method_7353(class_2561.method_43470("§b[方块飞溅] §c第一个点未选"), true);
        }

        if (pos2 != null)
        {
            player.method_7353(class_2561.method_43470("§b[方块飞溅] §a第二个点: §f" + pos2.method_10263() + ", " + pos2.method_10264() + ", " + pos2.method_10260()), true);
        }
        else
        {
            player.method_7353(class_2561.method_43470("§b[方块飞溅] §c第二个点未选"), true);
        }

        if (pos1 != null && pos2 != null)
        {
            int count = this.countBlocksInArea(pos1, pos2, world);
            player.method_7353(class_2561.method_43470("§b[方块飞溅] §e区域共 §f" + count + " §e个方块（已剔除空气），可在编辑面板粘贴"), true);
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

    /**
     * 物品悬浮提示
     */
    @Override
    public void method_7851(class_1799 stack, class_1937 world, List<class_2561> tooltip, class_1836 context)
    {
        tooltip.add(class_2561.method_43470("§7左键方块: 选择第一个点 (pos1)").method_27692(class_124.field_1080));
        tooltip.add(class_2561.method_43470("§7右键方块: 选择第二个点 (pos2)").method_27692(class_124.field_1080));
        tooltip.add(class_2561.method_43470("§7Shift+右键: 查看当前选择").method_27692(class_124.field_1080));
        tooltip.add(class_2561.method_43470("§e在飞溅效果面板点\"粘贴坐标\"一键填入").method_27692(class_124.field_1054));
    }
}
