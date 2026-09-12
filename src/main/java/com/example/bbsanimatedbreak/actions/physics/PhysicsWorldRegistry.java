package com.example.bbsanimatedbreak.actions.physics;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.class_1937;
import net.minecraft.class_5321;

/**
 * 物理世界注册表（按 BBS 回放时钟驱动）
 *
 * ==================================================================
 * 第一性原理：物理为什么必须由回放时钟驱动
 * ==================================================================
 * 旧实现把物理步进挂在 {@code ServerTickEvents.END_SERVER_TICK} 上，
 * 这违反 BBS 的回放规则：
 *
 *   - BBS 暂停回放（ActionPlayer.playing=false）时不再推进 tick，
 *     但真实服务端 tick 仍在跑 → 暂停时方块继续下落，无法定格拍摄；
 *   - 拖动时间轴（ActionPlayer.goTo 会逐 tick 重放 clip）时，
 *     物理已经跑到了别处，画面与时间轴不对应；
 *   - 导出视频（离线按固定帧率渲染）与实时物理脱钩 → 结果不可复现。
 *
 * BBS 已经提供了正确的时钟挂载点：
 *
 *   ActionClip#applyRange(actor, player, film, replay, tick)
 *
 * 它在「clip 覆盖的每一个 tick」被调用，且只在回放时钟真正推进时调用
 * （暂停不调用，播放调用，goTo 逐 tick 拖动也调用）。因此：
 *
 *   物理世界推进次数 = 回放 tick 推进次数
 *
 * 这是本插件唯一正确的驱动方式。
 *
 * ==================================================================
 * 幂等与确定性
 * ==================================================================
 * worldKey 由「filmId + replayId + clipStartTick」派生，因此：
 *   - 同一个 clip 被重复触发（goTo 来回拖动）不会重复生成刚体与实体；
 *   - 回放重新开始（DamageControl 已恢复方块）时旧世界已随 stop 销毁，
 *     于是重建一个新的，行为与首次播放逐帧一致。
 *
 * 物理本身不可逆（回放倒放无法"倒着算"），所以向后拖动时采用
 * 「记录初始条件 → 重建刚体 → 重新模拟到目标 tick」的方式。
 * 由于固定步长 + 固定种子 + 单线程求解器，重放结果与首次一致。
 * 每 tick 的重建步数有预算上限，防止长时间倒拖卡住服务器。
 *
 * ==================================================================
 * 生命周期
 * ==================================================================
 * 创建：BlockSplashActionClip#applyAction（回放到达 clip 起点）
 * 步进：BlockSplashActionClip#applyRange（回放 tick 推进）
 * 销毁：回放停止（BlockSplashReplayHook） / 空世界 / 超时 / 服务器停止
 */
public final class PhysicsWorldRegistry
{
    /** 单次 driveTo 最多允许的模拟步数（防止长时间倒拖阻塞服务器） */
    private static final int MAX_STEPS_PER_DRIVE = 60;

    /** 绝对超时上限（tick），仅作为资源泄漏兜底，正常由回放停止清理 */
    private static final int MAX_WORLD_LIFE = 72000; // 1 小时

    /**
     * 一条「初始条件 ←→ 实体」的绑定
     *
     * 保留初始条件是为了向后拖动时能够重建刚体并重新模拟到目标 tick。
     */
    public static final class BodyBinding
    {
        public final PhysicsBlockEntity entity;

        /* 初始条件（全部为物理世界单位） */
        public final double x, y, z;
        public final float mass, friction, restitution;
        public final double linearDamping, angularDamping;
        public final double vx, vy, vz;      // m/s
        public final float avx, avy, avz;    // rad/s

        public long handle;

        public BodyBinding(PhysicsBlockEntity entity,
                           double x, double y, double z,
                           float mass, float friction, float restitution,
                           double linearDamping, double angularDamping,
                           double vx, double vy, double vz,
                           float avx, float avy, float avz)
        {
            this.entity = entity;
            this.x = x; this.y = y; this.z = z;
            this.mass = mass; this.friction = friction; this.restitution = restitution;
            this.linearDamping = linearDamping; this.angularDamping = angularDamping;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.avx = avx; this.avy = avy; this.avz = avz;
        }
    }

    /** 一个回放物理世界 */
    public static final class Entry
    {
        public final String key;
        public final UUID worldId;
        public final PhysicsBackendWorld world;
        public final double gx, gy, gz;
        public final UUID recordingId;
        public final class_5321<class_1937> dimensionKey;

        /** 已模拟到的局部 tick（相对 clip 起点） */
        public int simulatedTick = 0;

        /** 存活上限（局部 tick；0 = 直到回放停止） */
        public int lifeTicks = 0;

        public final List<BodyBinding> bodies = new ArrayList<>();
        public boolean destroyed = false;

        Entry(String key, UUID worldId, PhysicsBackendWorld world,
              double gx, double gy, double gz, UUID recordingId,
              class_5321<class_1937> dimensionKey)
        {
            this.key = key;
            this.worldId = worldId;
            this.world = world;
            this.gx = gx; this.gy = gy; this.gz = gz;
            this.recordingId = recordingId;
            this.dimensionKey = dimensionKey;
        }

        public int getBodyCount()
        {
            return this.bodies.size();
        }
    }

    private static final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    private PhysicsWorldRegistry() {}

    /* ================================================================
     * 键
     * ================================================================ */

    /**
     * 派生确定性 worldKey
     *
     * 同一个 film + replay + clip 起点 tick 永远得到同一个 key，
     * 这是 applyAction 幂等（拖动时间轴不重复生成）的基础。
     */
    public static String keyOf(String filmId, String replayId, int clipStartTick)
    {
        return keyOf(filmId, replayId, clipStartTick, null);
    }

    /**
     * 派生确定性 worldKey（带区域标签）
     *
     * regionTag 用于区分「同一起始 tick 但作用区域不同」的多个片段
     * （组合片段的多个子效果可能同时启动）。
     */
    public static String keyOf(String filmId, String replayId, int clipStartTick, String regionTag)
    {
        String f = filmId == null ? "?" : filmId;
        String r = replayId == null ? "?" : replayId;
        return f + "@" + r + "#" + clipStartTick + "!" + (regionTag == null ? "" : regionTag);
    }

    /** 该回放物理世界的 recordingId（按 replayId 派生，确定性） */
    public static UUID recordingIdOf(String replayId)
    {
        if (replayId == null) return null;
        return UUID.nameUUIDFromBytes(replayId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /* ================================================================
     * 创建 / 查询
     * ================================================================ */

    /**
     * 创建（或复用）一个回放物理世界
     *
     * @param key       由 {@link #keyOf} 派生的确定性键
     * @param engine    "sable"（Rapier 原生）/ "jolt"（BBS 物理引擎）
     * @return 已存在则返回原 Entry（幂等），否则新建
     */
    public static Entry begin(String key, double gx, double gy, double gz,
                              UUID recordingId, class_5321<class_1937> dimensionKey,
                              String engine)
    {
        Entry existing = entries.get(key);

        if (existing != null)
        {
            if (!existing.destroyed && existing.world.isValid())
            {
                return existing;
            }

            // 已失效的旧世界：先完整销毁（含实体清理），避免 native 泄漏与
            // 遗留实体持有已释放的 worldPtr
            destroy(key);
        }

        PhysicsBackendWorld world = "jolt".equals(engine)
            ? new JoltPhysicsWorld(gx, gy, gz)
            : new NativePhysicsWorld(gx, gy, gz);

        Entry entry = new Entry(key, UUID.randomUUID(), world, gx, gy, gz, recordingId, dimensionKey);
        entries.put(key, entry);
        return entry;
    }

    public static Entry get(String key)
    {
        Entry e = entries.get(key);
        return (e != null && !e.destroyed) ? e : null;
    }

    /** 该 key 是否已经建立过回放物理世界（applyAction 幂等判定） */
    public static boolean has(String key)
    {
        return get(key) != null;
    }

    public static int getSimulatedTick(String key)
    {
        Entry e = get(key);
        return e == null ? 0 : e.simulatedTick;
    }

    /* ================================================================
     * 驱动（回放时钟）
     * ================================================================ */

    /**
     * 把物理世界推进（或回退）到指定的局部 tick
     *
     * 由 BlockSplashActionClip#applyRange 每个回放 tick 调用一次。
     *
     * @param localTick 目标 tick（相对 clip 起点，>= 0）
     */
    public static void driveTo(String key, int localTick)
    {
        Entry entry = get(key);

        if (entry == null)
        {
            return;
        }

        if (!entry.world.isValid())
        {
            destroy(key);
            return;
        }

        // 清理已自行销毁（世界失效）或被外部移除的实体，
        // 避免它们残留的初始条件被 rewind 重新造出"幽灵刚体"
        entry.bodies.removeIf(b ->
        {
            if (b.entity != null && b.entity.method_31481())
            {
                b.handle = 0;
                return true;
            }

            return false;
        });

        if (localTick < 0)
        {
            localTick = 0;
        }

        // === 向后拖动：物理不可逆 → 从初始条件重建后重新模拟 ===
        if (localTick < entry.simulatedTick)
        {
            if (!rewind(entry, localTick))
            {
                // 重建失败（native 返回 0）：世界已不可用，直接销毁，避免死循环重试
                destroy(key);
                return;
            }
        }

        // === 向前推进（带步数预算，防止长时间拖动阻塞服务器） ===
        int budget = MAX_STEPS_PER_DRIVE;

        while (entry.simulatedTick < localTick && budget-- > 0)
        {
            if (!entry.world.isValid())
            {
                destroy(key);
                return;
            }

            entry.world.stepTick();
            entry.simulatedTick++;
        }

        // === 存活管理（由回放时钟计数，暂停时不计寿命） ===
        if (entry.lifeTicks > 0 && entry.simulatedTick > entry.lifeTicks)
        {
            destroy(key);
        }
    }

    /**
     * 重建刚体并重新模拟到目标 tick
     *
     * 顺序严格按 bodies 列表（创建顺序），保证与首次模拟完全一致。
     */
    private static boolean rewind(Entry entry, int targetTick)
    {
        // 1. 先断开所有实体，杜绝重建期间任何 native 访问
        for (BodyBinding b : entry.bodies)
        {
            if (b.entity != null)
            {
                b.entity.detachPhysicsWorld();
            }
        }

        // 2. 移除所有动态刚体
        for (BodyBinding b : entry.bodies)
        {
            if (b.handle != 0)
            {
                entry.world.removeBody(b.handle);
                b.handle = 0;
            }
        }

        // 3. 按初始条件重建（同一顺序 → 确定性）
        for (BodyBinding b : entry.bodies)
        {
            long handle = entry.world.createDynamicBlock(
                b.x, b.y, b.z, b.mass, b.friction, b.restitution);

            if (handle == 0)
            {
                return false;
            }

            entry.world.setBodyDamping(handle, b.linearDamping, b.angularDamping);
            entry.world.setBodyVelocity(handle, b.vx, b.vy, b.vz);
            entry.world.setBodyAngularVelocity(handle, b.avx, b.avy, b.avz);
            b.handle = handle;
        }

        // 4. 重新绑定实体
        for (BodyBinding b : entry.bodies)
        {
            if (b.entity != null)
            {
                b.entity.setBodyHandle(b.handle, entry.world, entry.worldId);
            }
        }

        entry.simulatedTick = 0;
        return true;
    }

    /* ================================================================
     * 销毁
     * ================================================================ */

    /**
     * 销毁一个回放物理世界
     *
     * 顺序至关重要：
     *   ① 断开实体 → ② 关闭 native 世界 → ③ 移除实体
     * 反过来的话，实体在 native 世界释放后仍持有指针（use-after-free）。
     */
    public static void destroy(String key)
    {
        Entry entry = entries.remove(key);

        if (entry == null || entry.destroyed)
        {
            return;
        }

        entry.destroyed = true;

        List<PhysicsBlockEntity> toRemove = new ArrayList<>();

        for (BodyBinding b : entry.bodies)
        {
            if (b.entity != null && !b.entity.method_31481())
            {
                b.entity.detachPhysicsWorld();
                toRemove.add(b.entity);
            }
        }

        entry.bodies.clear();

        try
        {
            if (entry.world.isValid())
            {
                entry.world.close();
            }
        }
        catch (Throwable t)
        {
            /* native 层异常不应中断清理 */
        }

        for (PhysicsBlockEntity entity : toRemove)
        {
            try
            {
                entity.method_31472();
            }
            catch (Throwable t)
            {
                /* 忽略单个实体移除失败 */
            }
        }

        if (entry.recordingId != null)
        {
            PhysicsRecordingManager.clearRecording(entry.recordingId);
        }

        // Recovery 记录不在运行时清理（见 BlockSplashRecoveryManager 注释），
        // 由 SERVER_STOPPING 的 restoreAll + clearAll 负责最终清理。
    }

    /**
     * 回放停止时清理该影片的所有物理世界
     *
     * 由 ActionPlayer#stop 的 Mixin 钩子调用（BlockSplashReplayHook）。
     * DamageControl 会在同一时机恢复方块，因此这里只需销毁刚体与实体。
     */
    public static void destroyForFilm(String filmId)
    {
        if (filmId == null)
        {
            return;
        }

        String prefix = filmId + "@";

        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<String, Entry> e = it.next();

            if (e.getKey().startsWith(prefix))
            {
                destroy(e.getKey());
            }
        }
    }

    /**
     * 每服务端 tick 的轻量维护（不步进物理）
     *
     * 只负责：
     * - 世界失效 → 销毁
     * - 动态刚体归零（实体已全部移除）→ 销毁
     * - 绝对超时兜底
     */
    public static void maintenance()
    {
        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<String, Entry> e = it.next();
            Entry entry = e.getValue();

            boolean drop = false;

            try
            {
                if (!entry.world.isValid())
                {
                    drop = true;
                }
                else if (entry.world.getBodyCount() == 0)
                {
                    // 所有刚体已被移除（实体全部 discard）→ 立即销毁，不等超时
                    drop = true;
                }
                else if (entry.simulatedTick > MAX_WORLD_LIFE)
                {
                    drop = true;
                }
            }
            catch (Throwable t)
            {
                // JNI 异常（DLL 版本不匹配等）：保守起见销毁世界，避免泄漏
                drop = true;
            }

            if (drop)
            {
                destroy(e.getKey());
            }
        }
    }

    /**
     * 销毁所有世界（服务器停止 / 世界卸载）
     *
     * 调用顺序约束（见 BlockSplashAddon 的 SERVER_STOPPING）：
     * 1. clearAll()（销毁物理世界 + 清 PhysicsRecording + 移除实体）
     * 2. BlockSplashRecoveryManager.restoreAll(server)
     * 3. BlockSplashRecoveryManager.clearAll()
     */
    public static void clearAll()
    {
        for (String key : new ArrayList<>(entries.keySet()))
        {
            destroy(key);
        }

        entries.clear();
        PhysicsRecordingManager.clearAll();
    }

    public static int getActiveWorldCount()
    {
        return entries.size();
    }

    /**
     * @deprecated 物理已改由回放时钟驱动（见 driveTo）。保留仅为兼容旧调用方，
     *             行为等价于 maintenance()，不会推进物理。
     */
    @Deprecated
    public static void tickAll(double dt)
    {
        maintenance();
    }

    /** 兼容旧签名：返回首个（也是唯一的默认）世界，供调试与旧代码编译 */
    @Deprecated
    public static NativePhysicsWorld createWorld(UUID id)
    {
        return createWorld(id, 0.0, -11.0, 0.0);
    }

    @Deprecated
    public static NativePhysicsWorld createWorld(UUID id, double gx, double gy, double gz)
    {
        return new NativePhysicsWorld(gx, gy, gz);
    }

    @Deprecated
    public static NativePhysicsWorld createWorld(UUID id, double gx, double gy, double gz,
                                                 UUID recordingId, class_5321<class_1937> dimensionKey)
    {
        return new NativePhysicsWorld(gx, gy, gz);
    }

    @Deprecated
    public static PhysicsBackendWorld createWorld(UUID id, double gx, double gy, double gz,
                                                  UUID recordingId, class_5321<class_1937> dimensionKey,
                                                  String engine)
    {
        return "jolt".equals(engine) ? new JoltPhysicsWorld(gx, gy, gz) : new NativePhysicsWorld(gx, gy, gz);
    }

    @Deprecated
    public static PhysicsBackendWorld getWorld(UUID id)
    {
        return null;
    }

    @Deprecated
    public static void destroyWorld(UUID id)
    {
        /* 由 key 管理，UUID 入口已废弃 */
    }
}
