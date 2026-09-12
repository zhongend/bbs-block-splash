package com.example.bbsanimatedbreak.composer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.class_2680;

/**
 * 轨迹仓库 —— 规范 §14（Simulation Cache）、§21（Recording 分层）、§38（烘焙后释放）
 *
 * === 第一性原理：为什么轨迹必须活在物理世界之外 ===
 * 物理世界（Rapier/Jolt 求解器）是**瞬时**的：回放停止就销毁。
 * 而轨迹是**产物**：作者要拿它去烘焙成关键帧，可能是在回放停止之后。
 *
 * 规范 §21 的分层正是这个意思：
 *
 *     Raw Simulation → Transient Buffer → Simplified Trajectory → Bake Candidate → BBS Keyframe
 *                （随物理世界生灭）      （脱离物理存续）
 *
 * 所以这里把"精简后的轨迹"提升为独立于物理世界的实体。
 * 物理世界销毁 ≠ 轨迹消失 —— 这正是规范 §38 能喊出 "Physics CPU = 0" 的前提：
 * 求解器可以整片拔掉，剩下的只是几百 KB 的数组。
 *
 * === 缓存键（规范 §14）===
 *     compositionHash + selectionHash + physicsSettingsHash + seed + startTick + endTick + backend
 * 只要这些不变，"相同输入 = 相同模拟"。所以精简结果可以缓存：
 * 用户反复按 Bake 不应该重算，UI 拖动也不应该触发重算。
 * 键变了（改选区、改预算、改种子）才失效 —— 这是显式失效，不是靠时间猜。
 */
public final class TrajectoryStore
{
    /** 一个方块的轨迹槽位 */
    public static final class Track
    {
        /** dense index（与物理世界的 BodyBinding 下标一致，规范 §68） */
        public final int index;

        public final long stableBlockId;

        /** 原始方块坐标 */
        public final int blockX, blockY, blockZ;

        /** 原始方块状态 —— 烘焙成 BlockForm 时要用（规范 §4 的 originalBlockState） */
        public final class_2680 blockState;

        public Track(int index, long stableBlockId, int x, int y, int z, class_2680 blockState)
        {
            this.index = index;
            this.stableBlockId = stableBlockId;
            this.blockX = x;
            this.blockY = y;
            this.blockZ = z;
            this.blockState = blockState;
        }
    }

    /** 一个 composition 的整套轨迹 */
    public static final class Set
    {
        public final String worldKey;
        public final String filmId;
        public final String replayId;
        public final int clipStartTick;

        /** 确定性种子（规范 §67） */
        public final long seed;

        /** 物理后端标识（参与缓存键，规范 §14） */
        public final String backend;

        public final List<Track> tracks = new ArrayList<>();

        public TrajectoryBuffer buffer;

        /* === 缓存：精简结果（规范 §14）=== */

        /** 上次精简用的缓存键；与当前键相同则直接复用 {@link #simplified} */
        public String simplifiedKey;

        /** 每个 track 的精简结果（与 tracks 同序） */
        public MotionSimplifier.Result[] simplified;

        /** 精简后的总关键帧数 */
        public int simplifiedKeys;

        Set(String worldKey, String filmId, String replayId, int clipStartTick,
            long seed, String backend)
        {
            this.worldKey = worldKey;
            this.filmId = filmId;
            this.replayId = replayId;
            this.clipStartTick = clipStartTick;
            this.seed = seed;
            this.backend = backend;
        }

        /**
         * 规范 §14 的缓存键
         *
         * 注意这里刻意用"内容"而不是"对象身份"参与哈希：
         * 物理设置用具体数值、选区用 worldKey 的一部分、后端用名字。
         * 这样即便用户重建了一次同样的 composition，键还是一样的，缓存仍然命中。
         */
        public String computeCacheKey(BakeOptions options)
        {
            return this.backend
                 + '|' + this.seed
                 + '|' + this.clipStartTick
                 + '|' + options.positionError
                 + '|' + options.rotationErrorDegrees
                 + '|' + options.capturePositionEpsilon
                 + '|' + this.tracks.size();
        }
    }

    private static final Map<String, Set> sets = new ConcurrentHashMap<>();

    private TrajectoryStore()
    {
    }

    /* ================================================================
     * 生命周期
     * ================================================================ */

    /**
     * 开始（或复用）一套轨迹
     *
     * 幂等：同一个 worldKey 重复调用返回同一个 Set。
     * 这与物理世界注册表的幂等（规范 §80）是同一个思想 ——
     * ActionPlayer 拖动时间轴会反复触发片段起点，不能每次都新建缓冲。
     */
    public static Set begin(String worldKey, String filmId, String replayId,
                            int clipStartTick, long seed, String backend)
    {
        Set existing = sets.get(worldKey);

        if (existing != null)
        {
            return existing;
        }

        Set set = new Set(worldKey, filmId, replayId, clipStartTick, seed, backend);

        sets.put(worldKey, set);

        return set;
    }

    public static Set get(String worldKey)
    {
        return sets.get(worldKey);
    }

    public static boolean has(String worldKey)
    {
        return sets.containsKey(worldKey);
    }

    /** 清除一套轨迹（烘焙完成、或回放停止后不再需要） */
    public static void clear(String worldKey)
    {
        sets.remove(worldKey);
    }

    /** 清除某个影片的全部轨迹（回放停止钩子调用，规范 §38） */
    public static void clearForFilm(String filmId)
    {
        if (filmId == null)
        {
            return;
        }

        String prefix = filmId + "@";

        for (String key : new ArrayList<>(sets.keySet()))
        {
            if (key.startsWith(prefix))
            {
                sets.remove(key);
            }
        }
    }

    public static void clearAll()
    {
        sets.clear();
    }

    /**
     * 某影片的全部轨迹
     *
     * 烘焙入口用它而不是精确 worldKey：UI 这一层只知道 filmId
     * （来自 editor.getFilm().getId()），不知道回放 ID、片段起始 tick 和区域标签。
     * 把 worldKey 的拼装细节留在服务端采集侧，UI 就不必复制那套派生逻辑
     * —— 复制逻辑正是"两处不一致"类 bug 的温床。
     */
    public static List<Set> forFilm(String filmId)
    {
        List<Set> result = new ArrayList<>();

        if (filmId == null)
        {
            return result;
        }

        String prefix = filmId + "@";

        for (Set set : sets.values())
        {
            if (set.worldKey.startsWith(prefix))
            {
                result.add(set);
            }
        }

        return result;
    }

    public static int size()
    {
        return sets.size();
    }

    /* ================================================================
     * 采集
     * ================================================================ */

    /** 声明一个方块并分配 dense index；重复调用同一 index 是幂等的 */
    public static Track declare(Set set, int index, int x, int y, int z, class_2680 blockState)
    {
        while (set.tracks.size() <= index)
        {
            set.tracks.add(null);
        }

        Track existing = set.tracks.get(index);

        if (existing != null)
        {
            return existing;
        }

        Track track = new Track(index, Deterministic.stableBlockId(x, y, z), x, y, z, blockState);

        set.tracks.set(index, track);

        return track;
    }

    /** 确保轨迹缓冲存在（第一次采集时创建） */
    public static TrajectoryBuffer buffer(Set set, int bodyCount, BakeOptions options)
    {
        if (set.buffer == null)
        {
            set.buffer = new TrajectoryBuffer(bodyCount);
            set.buffer.positionEpsilon = options.capturePositionEpsilon;
            set.buffer.rotationEpsilon = options.captureRotationEpsilonRadians();
        }
        else
        {
            set.buffer.ensureBodies(bodyCount);
        }

        return set.buffer;
    }

    /* ================================================================
     * 统计（规范 §30：UI 要显示 Raw / Reduced / Error）
     * ================================================================ */

    public static final class Report
    {
        public int blocks;
        public int rawSamples;
        public int reducedKeys;
        public double maxPositionError;
        public double maxRotationError;

        public long trajectoryBytes;
        public long attributesBytes;

        public float ratio()
        {
            return this.rawSamples == 0 ? 1F : (float) this.reducedKeys / (float) this.rawSamples;
        }
    }

    /** 汇总一套轨迹的报告 */
    public static Report report(Set set)
    {
        Report report = new Report();

        if (set == null)
        {
            return report;
        }

        report.blocks = set.tracks.size();

        if (set.buffer != null)
        {
            report.rawSamples = set.buffer.size();
            report.trajectoryBytes = set.buffer.estimatedBytes();
        }

        if (set.simplified != null)
        {
            for (MotionSimplifier.Result result : set.simplified)
            {
                if (result == null)
                {
                    continue;
                }

                report.reducedKeys += result.indices.length;

                if (result.maxPositionError > report.maxPositionError)
                {
                    report.maxPositionError = result.maxPositionError;
                }

                if (result.maxRotationError > report.maxRotationError)
                {
                    report.maxRotationError = result.maxRotationError;
                }
            }
        }

        return report;
    }
}
