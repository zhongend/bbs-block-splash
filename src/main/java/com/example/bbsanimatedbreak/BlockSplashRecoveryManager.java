package com.example.bbsanimatedbreak;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.class_1540;
import net.minecraft.class_1937;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_2680;
import net.minecraft.class_3218;

/**
 * 方块飞溅恢复管理器
 *
 * === 解决的问题 ===
 * 如果方块飞溅动画播放途中退出游戏，BBS 的 DamageControl 来不及恢复，
 * 导致原区域方块被永久设为空气，飞溅的方块落地后变成新方块，
 * 飞行中的 FallingBlockEntity 也会被保存到存档。
 * 再次进入游戏时存档已被破坏，无法恢复飞溅前的模样。
 *
 * === 解决方案 ===
 * BlockSplashActionClip 在飞溅时把每个方块的原始状态记录到这里，
 * 同时记录生成的 FallingBlockEntity 的 UUID。
 * 在服务器停止时（ServerLifecycleEvents.SERVER_STOPPING），
 * 遍历所有记录，恢复方块原状并移除飞溅实体。
 *
 * 这样即使回放途中退出，存档也不会被破坏。
 *
 * === 数据结构 ===
 * - originalBlocks: 记录每个被飞溅的方块位置和原始状态
 * - fallingEntities: 记录所有生成的 FallingBlockEntity 的 UUID
 * - activeWorlds: 记录哪些世界有活跃的飞溅（用于恢复时遍历）
 *
 * === 性能考虑 ===
 * 最多 30000 个方块，每个记录约 40 字节（BlockPos + BlockState 引用），
 * 总内存约 1.2MB，可接受。
 * 恢复时遍历 30000 个方块调用 setBlockState，约 300ms，退出时可接受。
 */
public class BlockSplashRecoveryManager
{
    /**
     * 每个世界的飞溅记录
     */
    private static class WorldRecord
    {
        /**
         * 被飞溅的方块原始状态（退出时恢复为原始状态）
         *
         * 用「位置 → 原始状态」的 Map 而不是 List：
         * - 同一个方块被多次飞溅时只保留<b>第一次</b>的原始状态（那才是真正的"原状"）
         * - 内存被"不同位置数"封顶，不会随回放次数无限增长
         *   （旧实现用 List 追加，反复拍摄同一场景会让记录线性膨胀）
         */
        final java.util.Map<class_2338, class_2680> blocks = new java.util.LinkedHashMap<>();
        /** 飞溅实体落地后变成的方块位置（退出时设为空气） */
        final Set<class_2338> landedBlocks = new HashSet<>();
        /** 生成的 FallingBlockEntity UUID（去重） */
        final Set<UUID> entityUuids = new java.util.LinkedHashSet<>();
        /** 世界维度 ID（用于恢复时找到对应世界） */
        final net.minecraft.class_5321<class_1937> dimensionKey;
        /** 记录创建时间（用于超时清理） */
        final long createTime;

        WorldRecord(net.minecraft.class_5321<class_1937> dimensionKey)
        {
            this.dimensionKey = dimensionKey;
            this.createTime = System.currentTimeMillis();
        }
    }

    /**
     * 所有世界的飞溅记录
     * key = 世界实例（用 identity 比较）
     */
    private static final ConcurrentHashMap<net.minecraft.class_5321<class_1937>, WorldRecord> records = new ConcurrentHashMap<>();

    /**
     * 记录一个被飞溅的方块的原始状态
     *
     * @param world 世界
     * @param pos 方块位置
     * @param originalState 原始方块状态（飞溅前的状态）
     */
    public static void recordBlock(class_1937 world, class_2338 pos, class_2680 originalState)
    {
        if (world == null || pos == null || originalState == null) return;

        WorldRecord record = records.computeIfAbsent(world.method_27983(), WorldRecord::new);
        // putIfAbsent：同一位置反复飞溅时保留最初的原始状态，并防止记录无限增长
        record.blocks.putIfAbsent(pos.method_10062(), originalState); // 必须 toImmutable，BlockPos 可能是可变的
    }

    /**
     * 记录一个生成的 FallingBlockEntity
     *
     * @param world 世界
     * @param entity 飞溅实体
     */
    public static void recordEntity(class_1937 world, class_1540 entity)
    {
        if (world == null || entity == null) return;

        WorldRecord record = records.computeIfAbsent(world.method_27983(), WorldRecord::new);
        record.entityUuids.add(entity.method_5667());
    }

    /**
     * 记录飞溅实体落地后变成的方块位置
     *
     * FallingBlockEntity 落地后原版会调用 setBlockState 放置方块。
     * 我们记录这个位置，退出时设为空气（恢复原状）。
     *
     * 注意：如果落地位置正好在原始方块区域内，原始方块恢复会覆盖这个设置，
     * 所以这里只记录位置，恢复时先设为空气，再恢复原始方块。
     *
     * @param world 世界
     * @param pos 落地位置
     * @param landedState 落地后的方块状态（用于判断是否是飞溅方块）
     */
    public static void recordLandedBlock(class_1937 world, class_2338 pos, class_2680 landedState)
    {
        if (world == null || pos == null || landedState == null) return;
        // 跳过空气（落地位置可能没放置方块）
        if (landedState.method_26215()) return;

        WorldRecord record = records.computeIfAbsent(world.method_27983(), WorldRecord::new);
        record.landedBlocks.add(pos.method_10062());
    }

    /**
     * 恢复指定世界的所有飞溅修改
     *
     * 在服务器停止时调用，遍历所有记录的方块，恢复原始状态，
     * 并移除所有飞溅实体。
     *
     * 恢复顺序：
     * 1. 先移除所有飞溅实体（防止它们在恢复后继续落地变成方块）
     * 2. 先清除落地后变成的方块（设为空气）
     * 3. 再恢复原始方块（把原区域设回原状）
     *
     * @param world 要恢复的世界
     */
    public static void restoreWorld(class_3218 world)
    {
        if (world == null) return;

        WorldRecord record = records.remove(world.method_27983());
        if (record == null) return;

        // 1. 移除所有飞溅实体（防止恢复后继续落地）
        for (UUID uuid : record.entityUuids)
        {
            try
            {
                net.minecraft.class_1297 entity = world.method_14190(uuid);
                if (entity != null)
                {
                    entity.method_31472();
                }
            }
            catch (Exception e)
            {
                // 忽略单个实体移除失败
            }
        }

        // 2. 清除飞溅实体落地后变成的方块（设为空气）
        for (class_2338 pos : record.landedBlocks)
        {
            try
            {
                world.method_8652(pos, class_2246.field_10124.method_9564(), 0x12);
            }
            catch (Exception e)
            {
                // 忽略单个方块清除失败
            }
        }

        // 3. 恢复原始方块状态（把原区域设回原状）
        for (java.util.Map.Entry<class_2338, class_2680> br : record.blocks.entrySet())
        {
            try
            {
                // 直接设置方块状态，不触发方块更新（避免连锁反应）
                // force=false, notifyListeners=false（第 3 个参数）
                world.method_8652(br.getKey(), br.getValue(), 0x12);
            }
            catch (Exception e)
            {
                // 忽略单个方块恢复失败
            }
        }
    }

    /**
     * 恢复所有世界的所有飞溅修改
     *
     * 在服务器停止时调用，遍历所有有记录的世界。
     *
     * @param server Minecraft 服务器实例
     */
    public static void restoreAll(net.minecraft.server.MinecraftServer server)
    {
        if (server == null) return;

        // 遍历所有世界（主世界、下界、末地）
        for (class_3218 world : server.method_3738())
        {
            restoreWorld(world);
        }

        // 清理所有记录（防止内存泄漏）
        records.clear();
    }

    /**
     * 清理指定世界的所有记录（回放正常结束时调用）
     *
     * 如果 BBS 的 DamageControl 正常恢复了方块，
     * 调用此方法清理我们的记录，避免退出时重复恢复。
     *
     * @param world 世界
     */
    public static void clearWorld(class_1937 world)
    {
        if (world == null) return;
        records.remove(world.method_27983());
    }

    /**
     * 清理指定维度的所有记录（按 RegistryKey 索引，不需要 World 实例）
     *
     * 由 PhysicsWorldRegistry 在物理世界销毁时连带调用，修复
     * "回放次数越多越卡"的资源泄漏：blocks/entityUuids 只增不减，
     * 若不清理会随回放次数累积，导致内存增长 + 退出时 restoreAll 遍历膨胀。
     *
     * 安全性：此清理只删除我们自己的"恢复备份记录"，不影响 BBS DamageControl
     * 的恢复逻辑。即使恢复记录被清，方块原状已由 setBlockState 设为空气，
     * BBS 的 DamageControl 自己也有独立的方块变更记录。
     *
     * @param dimensionKey 世界维度 key
     */
    public static void clearWorldByKey(net.minecraft.class_5321<class_1937> dimensionKey)
    {
        if (dimensionKey == null) return;
        records.remove(dimensionKey);
    }

    /**
     * 清理所有记录
     */
    public static void clearAll()
    {
        records.clear();
    }

    /**
     * 获取当前活跃的飞溅记录数量（用于调试）
     */
    public static int getActiveRecordCount()
    {
        return records.size();
    }
}
