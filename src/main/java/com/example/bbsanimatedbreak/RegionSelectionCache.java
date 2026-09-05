package com.example.bbsanimatedbreak;

import net.minecraft.class_2338;

/**
 * 区域选择坐标缓存
 *
 * 临时存储木棍选择的两个坐标点，供 UI 面板的"粘贴坐标"按钮读取。
 * 放在 main 源集是因为物品类（服务端+客户端）需要写入，
 * UI 面板（客户端）需要读取。BlockPos 是通用类，不依赖客户端 API。
 */
public class RegionSelectionCache
{
    private static class_2338 pos1;
    private static class_2338 pos2;

    public static void setPos1(class_2338 pos)
    {
        pos1 = pos != null ? pos.method_10062() : null;
    }

    public static void setPos2(class_2338 pos)
    {
        pos2 = pos != null ? pos.method_10062() : null;
    }

    public static class_2338 getPos1()
    {
        return pos1;
    }

    public static class_2338 getPos2()
    {
        return pos2;
    }

    public static boolean hasPos1()
    {
        return pos1 != null;
    }

    public static boolean hasPos2()
    {
        return pos2 != null;
    }

    public static boolean hasBoth()
    {
        return pos1 != null && pos2 != null;
    }

    /**
     * 清空已选坐标
     */
    public static void clear()
    {
        pos1 = null;
        pos2 = null;
    }
}
