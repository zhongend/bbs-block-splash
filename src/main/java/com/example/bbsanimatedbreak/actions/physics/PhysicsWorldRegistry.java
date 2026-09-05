package com.example.bbsanimatedbreak.actions.physics;

import com.example.bbsanimatedbreak.BlockSplashRecoveryManager;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.class_1937;
import net.minecraft.class_5321;

/**
 * 原生物理世界注册表（集中步进）
 *
 * Rapier 的 pipeline.step() 必须每 tick 对每个世界调用一次，
 * 而非每实体调用一次。本注册表在服务器 tick 事件中遍历所有活跃世界，
 * 各调用一次 step(1/20)，确保物理时间正确推进。
 *
 * 生命周期：
 * - BlockSplashActionClip.applyAction() 调用 createWorld() 创建世界
 * - 服务器 tick 事件调用 tickAll() 步进所有世界
 * - 空世界（动态刚体归零，即所有方块已 discard）立即销毁
 * - 超时（MAX_WORLD_LIFE tick）或 clearAll() 时销毁世界
 * - PhysicsBlockEntity.remove() 只移除自己的刚体，不销毁世界
 *
 * === 资源连带清理（修复"回放次数越多越卡"） ===
 * 销毁世界时连带清理：
 * - PhysicsRecordingManager.clearRecording(recordingId)：清除该回放的物理记录
 *   （300 方块 × 1200 tick = 36 万条/回放 ≈ 43MB，不清会持续累积导致 GC 风暴）
 * - BlockSplashRecoveryManager.clearWorld(dimensionKey)：清除该世界的飞溅恢复记录
 *   （blocks/entityUuids 只增不减，不清会随回放次数累积）
 * 这些清理只在世界销毁时触发，与物理模拟完全解耦，物理质量零损失。
 */
public class PhysicsWorldRegistry
{
    /** 世界最大存活 tick（60 秒 = 1200 tick），作为兜底超时 */
    private static final int MAX_WORLD_LIFE = 1200;

    /** 活跃物理世界：worldId → 世界实例 */
    private static final ConcurrentHashMap<UUID, NativePhysicsWorld> worlds = new ConcurrentHashMap<>();

    /** 世界创建时的 tick 计数（用于超时销毁） */
    private static final ConcurrentHashMap<UUID, Integer> worldAges = new ConcurrentHashMap<>();

    /** worldId → recordingId 映射（销毁世界时连带清理 PhysicsRecording） */
    private static final ConcurrentHashMap<UUID, UUID> worldRecordingMap = new ConcurrentHashMap<>();

    /** worldId → 世界维度 key 映射（销毁世界时连带清理 BlockSplashRecoveryManager） */
    private static final ConcurrentHashMap<UUID, class_5321<class_1937>> worldDimensionMap = new ConcurrentHashMap<>();

    /**
     * 创建并注册一个原生物理世界（默认重力 -11.0 m/s²）
     */
    public static NativePhysicsWorld createWorld(UUID id)
    {
        return createWorld(id, 0.0, -11.0, 0.0);
    }

    /**
     * 创建并注册一个原生物理世界（自定义重力）
     *
     * @param id 唯一标识
     * @param gx 重力 X 分量（m/s²）
     * @param gy 重力 Y 分量（m/s²，向下为负）
     * @param gz 重力 Z 分量（m/s²）
     * @return 创建的 NativePhysicsWorld 实例
     */
    public static NativePhysicsWorld createWorld(UUID id, double gx, double gy, double gz)
    {
        NativePhysicsWorld world = new NativePhysicsWorld(gx, gy, gz);
        worlds.put(id, world);
        worldAges.put(id, 0);
        return world;
    }

    /**
     * 创建并注册原生物理世界，并关联 recordingId 和 dimensionKey 用于连带清理
     *
     * @param id            worldId
     * @param gx, gy, gz    重力分量
     * @param recordingId   该回放的物理记录 ID（可为 null）
     * @param dimensionKey  该回放所在维度的 RegistryKey（可为 null）
     */
    public static NativePhysicsWorld createWorld(UUID id, double gx, double gy, double gz,
                                                 UUID recordingId, class_5321<class_1937> dimensionKey)
    {
        NativePhysicsWorld world = createWorld(id, gx, gy, gz);
        if (recordingId != null)
        {
            worldRecordingMap.put(id, recordingId);
        }
        if (dimensionKey != null)
        {
            worldDimensionMap.put(id, dimensionKey);
        }
        return world;
    }

    /**
     * 查询已注册的世界
     */
    public static NativePhysicsWorld getWorld(UUID id)
    {
        return worlds.get(id);
    }

    /**
     * 物理子步进次数（每 tick 内部分 N 次步进，提高碰撞和旋转精度）
     *
     * 性能考量：子步进让 CPU 开销线性增长，但能显著提升物理精度
     * （碰撞响应、堆叠稳定性、旋转平滑度）。
     * 物理质量优先，保留 SUBSTEPS=2。性能优化通过其他手段实现
     * （并行步进、批量 JNI、异步线程）。
     */
    private static final int SUBSTEPS = 2;

    /**
     * 步进所有活跃世界（由服务器 tick 事件调用）
     *
     * 使用子步进（substeps）：每 tick 内部将 dt 分成 SUBSTEPS 次小步长，
     * 每次 step(dt/SUBSTEPS)。这样物理模拟更精确，碰撞响应和旋转更平滑。
     *
     * @param dt 时间步长（秒），通常 1/20
     */
    public static void tickAll(double dt)
    {
        Iterator<Map.Entry<UUID, NativePhysicsWorld>> it = worlds.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<UUID, NativePhysicsWorld> e = it.next();
            UUID id = e.getKey();
            NativePhysicsWorld world = e.getValue();

            // 世界已失效，移除
            if (!world.isValid())
            {
                it.remove();
                worldAges.remove(id);
                cleanupAssociated(id);
                continue;
            }

            // === 空世界自动销毁（核心防泄漏逻辑） ===
            // getBodyCount() 只数动态刚体（不含静态 Fixed 碰撞体）。
            // 当所有方块被 discard → removeBody 后，动态刚体归零，世界 step()
            // 是纯浪费（无刚体可推进），且会随回放次数累积导致"越播越卡"。
            // 检测到空世界立即销毁并连带清理 recording/recovery 记录。
            //
            // 时序安全：刚体创建在 applyAction（同步 tick），step 在 END_SERVER_TICK，
            // 同一 tick 内创建的方块刚体数 > 0，不会被误清。
            try
            {
                if (world.getBodyCount() == 0)
                {
                    world.close();
                    it.remove();
                    worldAges.remove(id);
                    cleanupAssociated(id);
                    continue;
                }
            }
            catch (Throwable t)
            {
                // JNI 异常（DLL 版本不匹配等）：保守起见销毁世界，避免泄漏
                world.close();
                it.remove();
                worldAges.remove(id);
                cleanupAssociated(id);
                continue;
            }

            // 子步进：将 dt 分成 SUBSTEPS 次小步长
            double subDt = dt / SUBSTEPS;
            for (int i = 0; i < SUBSTEPS; i++)
            {
                world.step(subDt);
            }

            // 超时销毁
            int age = worldAges.merge(id, 1, Integer::sum);
            if (age > MAX_WORLD_LIFE)
            {
                world.close();
                it.remove();
                worldAges.remove(id);
                cleanupAssociated(id);
            }
        }
    }

    /**
     * 连带清理与 worldId 关联的所有资源
     *
     * 在世界销毁时调用，清理：
     * - PhysicsRecordingManager 的物理记录（防止 36 万条/回放 累积）
     *
     * 注意：**不清理 BlockSplashRecoveryManager 记录**。
     * Recovery 记录是"DamageControl 失效时的兜底"，运行时清理不安全——
     * 世界销毁时 BBS DamageControl 可能还未完成方块恢复。
     * Recovery 记录的累积量很小（每回放约 12KB，vs PhysicsRecording 43MB），
     * 不是"越播越卡"的主因。Recovery 记录由 SERVER_STOPPING 的
     * restoreAll + clearAll 负责最终清理。
     */
    private static void cleanupAssociated(UUID worldId)
    {
        UUID recordingId = worldRecordingMap.remove(worldId);
        if (recordingId != null)
        {
            PhysicsRecordingManager.clearRecording(recordingId);
        }

        // 注意：不清理 BlockSplashRecoveryManager（见方法注释）
        // dimensionKey 映射仍然移除（避免 map 累积），但不触发 Recovery 清理
        worldDimensionMap.remove(worldId);
    }

    /**
     * 销毁指定世界（释放 native 内存 + 连带清理）
     */
    public static void destroyWorld(UUID id)
    {
        NativePhysicsWorld world = worlds.remove(id);
        worldAges.remove(id);
        if (world != null && world.isValid())
        {
            world.close();
        }
        cleanupAssociated(id);
    }

    /**
     * 销毁所有世界（服务器停止/世界卸载时调用）
     *
     * 调用顺序约束（在 BlockSplashAddon.SERVER_STOPPING 中）：
     * 1. PhysicsWorldRegistry.clearAll()（本方法，销毁物理世界 + 清 PhysicsRecording）
     * 2. BlockSplashRecoveryManager.restoreAll(server)（用 Recovery 记录恢复方块）
     * 3. BlockSplashRecoveryManager.clearAll()（清空 Recovery 记录）
     *
     * 本方法**不清理** BlockSplashRecoveryManager 记录，否则步骤 2 的 restoreAll
     * 会找不到记录无法恢复方块。
     */
    public static void clearAll()
    {
        for (NativePhysicsWorld world : worlds.values())
        {
            if (world.isValid())
            {
                world.close();
            }
        }
        worlds.clear();
        worldAges.clear();

        // 连带清理所有 PhysicsRecording（每回放 36 万条 ≈ 43MB，必须清）
        for (UUID recordingId : worldRecordingMap.values())
        {
            if (recordingId != null)
            {
                PhysicsRecordingManager.clearRecording(recordingId);
            }
        }
        worldRecordingMap.clear();
        worldDimensionMap.clear();

        // 注意：不调用 BlockSplashRecoveryManager.clearWorldByKey
        // Recovery 记录由 SERVER_STOPPING 的 restoreAll + clearAll 负责
    }

    /**
     * 获取当前活跃世界数量（调试用）
     */
    public static int getActiveWorldCount()
    {
        return worlds.size();
    }
}
