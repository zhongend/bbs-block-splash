package com.example.bbsanimatedbreak.actions.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理方块实体管理器
 *
 * 维护所有活跃的 PhysicsBlockEntity，供方块间碰撞检测使用。
 * 实体在服务端首次 tick 时注册，discard 时注销。
 *
 * 性能：用 ConcurrentHashMap，getNearby 是 O(N) 遍历，
 * 对于典型飞溅场景（100-300 方块）性能足够。
 */
public final class PhysicsEntityManager
{
    private static final Map<UUID, PhysicsBlockEntity> entities = new ConcurrentHashMap<>();

    private PhysicsEntityManager() {}

    public static void register(PhysicsBlockEntity entity)
    {
        entities.put(entity.method_5667(), entity);
    }

    public static void unregister(PhysicsBlockEntity entity)
    {
        entities.remove(entity.method_5667());
    }

    /**
     * 获取指定位置附近的活跃物理方块
     *
     * @param x 中心 X（米，方块中心坐标）
     * @param y 中心 Y
     * @param z 中心 Z
     * @param radius 搜索半径（米）
     * @return 附近方块列表（可能包含自身）
     */
    public static List<PhysicsBlockEntity> getNearby(double x, double y, double z, double radius)
    {
        double r2 = radius * radius;
        List<PhysicsBlockEntity> result = new ArrayList<>();
        for (PhysicsBlockEntity e : entities.values())
        {
            double dx = e.getCenterX() - x;
            double dy = e.getCenterY() - y;
            double dz = e.getCenterZ() - z;
            if (dx * dx + dy * dy + dz * dz < r2)
            {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * 清理所有记录
     */
    public static void clearAll()
    {
        entities.clear();
    }
}
