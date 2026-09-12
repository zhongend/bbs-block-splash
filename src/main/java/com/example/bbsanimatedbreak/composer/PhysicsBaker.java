package com.example.bbsanimatedbreak.composer;

import java.util.ArrayList;
import java.util.List;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;
import com.example.bbsanimatedbreak.composer.anim.AnimationDocumentStore;
import com.example.bbsanimatedbreak.composer.anim.BlockSplashAnimationDocument;

/**
 * 物理烘焙器 —— 规范 §29-38
 *
 * 把精简后的物理轨迹写进 BBS 的**原生**动画数据：
 *
 *     Physics trajectory → Simplifier → BBS KeyframeWriter
 *
 * === 第一性原理：为什么载体必须是 BBS 自己的 Replay ===
 * 规范 §45 是这条链的验收标准：
 *
 *     Flatten 后完全不需要这个插件的 Runtime 才能播放。
 *
 * 唯一能满足它的做法，是把运动写进 BBS 自己认识的格式 ——
 * 一个 `Replay`（actor）+ `BlockForm`（BBS 自带的方块形态）+ `KeyframeChannel`。
 * 写完之后，这段动画就是普通 BBS 动画：
 *   ① 不需要本插件在场也能播放；
 *   ② 每一个关键帧都能像手 K 的一样拖动、删除、改插值曲线；
 *   ③ 物理求解器可以整片拔掉 —— Physics CPU = 0（规范 §38-39）。
 *
 * === 一个方块 = 一个 Replay ===
 * 规范 §35 要求逐方块独立轨道。BBS 里"一条轨道"的自然载体就是一个 Replay，
 * 所以 1000 个方块 = 1000 个 Replay。
 *
 * 但 §36 紧接着警告："独立轨道"不等于"独立对象"—— 不能因此造出
 * 1000 个物理刚体、1000 个调度器。这里烘焙出来的每个 Replay 只是
 * **数据**（一个 ValueGroup + 几条关键帧通道），没有任何运行时实体：
 * 不 spawn 实体、不注册调度器、不占物理 CPU。
 *
 * === 三轴旋转为什么走 form 的 transform 通道 ===
 * `ReplayKeyframes` 只有 yaw/pitch/headYaw/bodyYaw，**没有 roll** ——
 * 而自由落体的方块会绕三个轴翻滚。BBS 的 form 有一个 `transform`
 * 属性（`ValueTransform`），它的 `RotationMode.QUATERNION` 分支存的是
 * 四元数，没有万向节锁，也没有角度展开问题。
 * 所以：位置写 replay 的 x/y/z 通道（作者在时间轴上最常拖的三个），
 * 旋转写 form 的 transform 通道（四元数）。
 */
public final class PhysicsBaker
{
    /** 烘焙结果（规范 §30：UI 要显示的数字） */
    public static final class Result
    {
        public final int blocks;
        public final int rawSamples;
        public final int keys;
        public final int skippedBlocks;
        public final double maxPositionError;
        public final double maxRotationError;
        public final long millis;
        public final String message;

        Result(int blocks, int rawSamples, int keys, int skippedBlocks,
               double maxPositionError, double maxRotationError, long millis, String message)
        {
            this.blocks = blocks;
            this.rawSamples = rawSamples;
            this.keys = keys;
            this.skippedBlocks = skippedBlocks;
            this.maxPositionError = maxPositionError;
            this.maxRotationError = maxRotationError;
            this.millis = millis;
            this.message = message;
        }
    }

    private PhysicsBaker()
    {
    }

    /**
     * 把一套轨迹烘焙成 BBS 原生逐方块轨道
     *
     * @param film     目标影片（**必须是客户端正在编辑的那个** —— 服务端侧的 Film
     *                 是从磁盘另载的副本，写它不会反映到编辑器）
     * @param worldKey 物理世界键（= 轨迹仓库键）
     * @param options  烘焙选项
     */
    public static Result bake(Film film, String worldKey, BakeOptions options)
    {
        if (film == null)
        {
            return fail("没有打开的影片");
        }

        TrajectoryStore.Set set = TrajectoryStore.get(worldKey);

        if (set == null)
        {
            return fail("没有可烘焙的轨迹（worldKey=" + worldKey + "）—— 先播放一次该片段让物理跑完");
        }

        if (set.buffer == null || set.buffer.size() == 0)
        {
            return fail("轨迹为空 —— 物理世界还没步进过");
        }

        /* lambda 捕获要求 effectively final：在这里解析出最终选项 */
        final BakeOptions opts = options == null ? BakeOptions.bakeDefaults() : options;

        long start = System.currentTimeMillis();

        TrajectoryBuffer buffer = set.buffer;
        int bodyCount = buffer.bodyCount();

        /* === 1. 把交错样本按方块聚成连续区间（一次 O(n)）=== */
        int[] order = buffer.compactByBody();

        /* === 2. 精简（带缓存：规范 §14，同输入不重算）=== */
        String cacheKey = set.computeCacheKey(opts);

        if (set.simplified == null || set.simplified.length != set.tracks.size()
            || !cacheKey.equals(set.simplifiedKey))
        {
            set.simplified = new MotionSimplifier.Result[set.tracks.size()];

            int keys = 0;

            for (int b = 0; b < set.tracks.size(); b++)
            {
                TrajectoryStore.Track track = set.tracks.get(b);

                if (track == null)
                {
                    continue;
                }

                int from = buffer.firstSampleOf(b);
                int to = from + set.buffer.samplesOf(b);

                MotionSimplifier.Result result = MotionSimplifier.simplify(
                    buffer, order, from, to,
                    opts.positionError, opts.rotationErrorRadians());

                set.simplified[b] = result;
                keys += result.indices.length;
            }

            set.simplifiedKey = cacheKey;
            set.simplifiedKeys = keys;
        }

        /* === 3. 写入 BBS（规范 §38：整个 bake 是一次撤销）=== */
        int[] counters = new int[2];

        BaseValue.edit(film, (f) ->
        {
            writeAll(f, set, buffer, order, opts, counters);
        });

        long millis = System.currentTimeMillis() - start;

        TrajectoryStore.Report report = TrajectoryStore.report(set);

        String message = "烘焙 " + counters[0] + " 个方块 / " + report.reducedKeys + " 个关键帧"
            + "（原始 " + report.rawSamples + " 样本，压缩比 "
            + String.format("%.1f%%", report.ratio() * 100F) + "）";

        return new Result(counters[0], report.rawSamples, report.reducedKeys,
            counters[1], report.maxPositionError, report.maxRotationError, millis, message);
    }

    /**
     * 烘焙一个影片的**全部**物理轨迹
     *
     * 这是 UI 的入口：作者按一次"烘焙"，影片里所有跑过物理的片段都转成关键帧。
     * 一个影片可能有多个物理片段（不同区域/不同起始 tick），每个对应一套轨迹。
     */
    public static Result bakeAll(Film film, BakeOptions options)
    {
        String filmId = film == null ? null : film.getId();

        List<TrajectoryStore.Set> sets = TrajectoryStore.forFilm(filmId);

        if (sets.isEmpty())
        {
            return fail("没有可烘焙的轨迹 —— 先播放一次含物理的片段，再回来点烘焙");
        }

        final BakeOptions opts = options == null ? BakeOptions.bakeDefaults() : options;

        int blocks = 0;
        int keys = 0;
        int raw = 0;
        int skipped = 0;
        double positionError = 0D;
        double rotationError = 0D;
        long millis = 0L;
        StringBuilder problems = new StringBuilder();

        for (TrajectoryStore.Set set : sets)
        {
            Result result = bake(film, set.worldKey, opts);

            if (result.blocks <= 0)
            {
                if (problems.length() > 0)
                {
                    problems.append("；");
                }

                problems.append(result.message);
                continue;
            }

            blocks += result.blocks;
            keys += result.keys;
            raw += result.rawSamples;
            skipped += result.skippedBlocks;
            millis += result.millis;

            if (result.maxPositionError > positionError) positionError = result.maxPositionError;
            if (result.maxRotationError > rotationError) rotationError = result.maxRotationError;
        }

        if (blocks <= 0)
        {
            return fail(problems.length() > 0 ? problems.toString() : "没有可烘焙的轨迹");
        }

        String message = "烘焙 " + blocks + " 个方块 / " + keys + " 个关键帧"
            + "（原始 " + raw + " 样本，压缩比 "
            + String.format("%.1f%%", raw == 0 ? 100F : keys * 100F / raw) + "）";

        if (problems.length() > 0)
        {
            message += "；部分片段未烘焙：" + problems;
        }

        return new Result(blocks, raw, keys, skipped, positionError, rotationError, millis, message);
    }

    /**
     * 把物理轨迹烘焙进**独立的 Block Splash 动画文档**（规范 §三十）
     *
     * 这是 3.0 的**默认**烘焙路径。上一轮"直接写 BBS 原生 Replay"的实现之所以被
     * 替换，是因为它违反了这条规范最核心的区分（§一/§四十二）：
     *
     *     BBS Native K-Frame 不等于 Block Splash K-Frame
     *
     * 3000 个方块的轨道塞进原版编辑器，会让原版时间轴不可用（§五十七）。
     * 所以：烘焙进独立文档；"导出到 BBS 原生"是显式的高级操作（§五十六）。
     *
     * Re-Bake 语义（规范 §三十八~§四十）：每次烘焙都新建一个 Physics 图层
     * （Physics Bake 001 / 002...），不覆盖手工编辑 —— 非破坏式工作流。
     */
    public static Result bakeToDocument(Film film, BakeOptions options)
    {
        String filmId = film == null ? null : film.getId();

        List<TrajectoryStore.Set> sets = TrajectoryStore.forFilm(filmId);

        if (sets.isEmpty())
        {
            return fail("没有可烘焙的轨迹 —— 先播放一次含物理的片段，再回来点烘焙");
        }

        final BakeOptions opts = options == null ? BakeOptions.bakeDefaults() : options;

        long start = System.currentTimeMillis();

        BlockSplashAnimationDocument doc = AnimationDocumentStore.getOrCreate(filmId, filmId);

        /* Re-Bake = 新图层（规范 §四十） */
        int bakeIndex = 1;

        for (BlockSplashAnimationDocument.AnimationLayer layer : doc.layers)
        {
            if (layer.kind == BlockSplashAnimationDocument.LAYER_PHYSICS && layer.bakeIndex >= bakeIndex)
            {
                bakeIndex = layer.bakeIndex + 1;
            }
        }

        int layerIndex = doc.addLayer(
            "Physics Bake " + String.format("%03d", bakeIndex),
            BlockSplashAnimationDocument.LAYER_PHYSICS, bakeIndex);

        doc.duration = 0;

        int blocks = 0;
        int keys = 0;
        int raw = 0;
        double positionError = 0D;
        double rotationError = 0D;

        for (TrajectoryStore.Set set : sets)
        {
            TrajectoryBuffer buffer = set.buffer;

            if (buffer == null || buffer.size() == 0)
            {
                continue;
            }

            int[] order = buffer.compactByBody();

            for (int b = 0; b < set.tracks.size(); b++)
            {
                TrajectoryStore.Track track = set.tracks.get(b);

                if (track == null)
                {
                    continue;
                }

                int from = buffer.firstSampleOf(b);
                int to = from + buffer.samplesOf(b);

                MotionSimplifier.Result result = MotionSimplifier.simplify(
                    buffer, order, from, to,
                    opts.positionError, opts.rotationErrorRadians());

                if (result.indices.length == 0)
                {
                    continue;
                }

                BlockSplashAnimationDocument.BlockTrack out =
                    new BlockSplashAnimationDocument.BlockTrack();

                out.layer = layerIndex;
                out.stableBlockId = track.stableBlockId;
                out.sourceX = track.blockX;
                out.sourceY = track.blockY;
                out.sourceZ = track.blockZ;
                out.blockState = track.blockState;
                out.sourceLabel = "Physics Bake " + String.format("%03d", bakeIndex);
                out.backendLabel = set.backend;

                int n = result.indices.length;

                out.posTick = new float[n];
                out.posX = new double[n];
                out.posY = new double[n];
                out.posZ = new double[n];
                out.posSource = new byte[n];
                out.posInterp = new byte[n];

                out.rotTick = new float[n];
                out.rotX = new float[n];
                out.rotY = new float[n];
                out.rotZ = new float[n];
                out.rotW = new float[n];
                out.rotSource = new byte[n];
                out.rotInterp = new byte[n];

                for (int k = 0; k < n; k++)
                {
                    int sample = order[result.indices[k]];
                    float tick = buffer.tickAt(sample);

                    out.posTick[k] = tick;
                    out.posX[k] = buffer.posX(sample);
                    out.posY[k] = buffer.posY(sample);
                    out.posZ[k] = buffer.posZ(sample);
                    out.posSource[k] = BlockSplashAnimationDocument.SOURCE_PHYSICS;
                    out.posInterp[k] = BlockSplashAnimationDocument.INTERP_LINEAR;

                    out.rotTick[k] = tick;

                    /* 归一化：长轨迹插值会放大轻微的非单位四元数误差 */
                    Quaternionf quat = new Quaternionf(
                        buffer.rotX(sample), buffer.rotY(sample),
                        buffer.rotZ(sample), buffer.rotW(sample)).normalize();

                    out.rotX[k] = quat.x;
                    out.rotY[k] = quat.y;
                    out.rotZ[k] = quat.z;
                    out.rotW[k] = quat.w;
                    out.rotSource[k] = BlockSplashAnimationDocument.SOURCE_PHYSICS;
                    out.rotInterp[k] = BlockSplashAnimationDocument.INTERP_LINEAR;

                    if (tick > doc.duration)
                    {
                        doc.duration = (int) Math.ceil(tick);
                    }
                }

                doc.tracks.add(out);

                blocks++;
                keys += n;
                raw += result.rawSamples;

                if (result.maxPositionError > positionError) positionError = result.maxPositionError;
                if (result.maxRotationError > rotationError) rotationError = result.maxRotationError;
            }
        }

        if (blocks <= 0)
        {
            return fail("没有可烘焙的方块轨迹");
        }

        try
        {
            AnimationDocumentStore.save(filmId, doc);
        }
        catch (Exception e)
        {
            return fail("文档已生成但保存失败: " + e);
        }

        long millis = System.currentTimeMillis() - start;

        String message = "烘焙 " + blocks + " 个方块 / " + keys + " 个关键帧"
            + "（原始 " + raw + " 样本，压缩比 "
            + String.format("%.1f%%", raw == 0 ? 100F : keys * 100F / raw) + "）到 "
            + doc.layers.get(layerIndex).name;

        return new Result(blocks, raw, keys, 0, positionError, rotationError, millis, message);
    }

    /** 烘焙失败时的空结果 */
    private static Result fail(String message)
    {
        return new Result(0, 0, 0, 0, 0D, 0D, 0L, message);
    }

    /**
     * 把所有方块写进影片（在 BaseValue.edit 的回调里执行）
     *
     * @param counters counters[0] = 实际写入的方块数，counters[1] = 跳过数
     */
    private static void writeAll(Film film, TrajectoryStore.Set set,
                                 TrajectoryBuffer buffer, int[] order, BakeOptions opts,
                                 int[] counters)
    {
        for (int b = 0; b < set.tracks.size(); b++)
        {
            TrajectoryStore.Track track = set.tracks.get(b);

            if (track == null)
            {
                counters[1]++;
                continue;
            }

            MotionSimplifier.Result result = set.simplified != null && b < set.simplified.length
                ? set.simplified[b]
                : null;

            if (result == null || result.indices.length == 0)
            {
                counters[1]++;
                continue;
            }

            try
            {
                writeOne(film, buffer, order, b, track, result, opts);
                counters[0]++;
            }
            catch (Throwable t)
            {
                /* 单个方块失败不影响其余方块 —— 半个建筑总比整个失败好 */
                System.out.println("[BBS-Splash] 烘焙方块 #" + b + " 失败: " + t);
                counters[1]++;
            }
        }
    }

    /**
     * 写入单个方块的轨道
     *
     * 效率模式与参考实现一致：**先**把 Replay 建好、塞满键（此刻它还是游离对象，
     * 没挂在影片上，insert 的通知走不到 UI），**最后**才 add 进影片 ——
     * 每键一次通知在长片上是几千次回调（规范 §71 的教训），这里只有 1 次。
     */
    private static void writeOne(Film film, TrajectoryBuffer buffer, int[] order, int bodyIndex,
                                 TrajectoryStore.Track track, MotionSimplifier.Result result,
                                 BakeOptions options)
    {
        int id = film.replays.getAll().size();

        Replay replay = new Replay(String.valueOf(id));

        /* === BlockForm：BBS 原生的方块形态 === */
        BlockForm form = new BlockForm();
        form.blockState.set(track.blockState);

        replay.form.set(form);

        if (options.labelTracks)
        {
            replay.label.set("Block #" + String.format("%03d", bodyIndex + 1));
        }

        replay.category.set(options.category == null ? "" : options.category);
        replay.shadow.set(false);

        /* === 旋转通道：先取出来（游离状态下取，挂在影片上后取也一样）=== */
        KeyframeChannel transformChannel = replay.properties.getOrCreate(form, "transform");

        /* === 位置：replay 的 x/y/z 通道（作者最常编辑的三个）=== */
        KeyframeChannel<Double> cx = replay.keyframes.x;
        KeyframeChannel<Double> cy = replay.keyframes.y;
        KeyframeChannel<Double> cz = replay.keyframes.z;

        List<Transform> rotations = new ArrayList<>(result.indices.length);

        for (int k = 0; k < result.indices.length; k++)
        {
            int sample = order[result.indices[k]];

            double x = buffer.posX(sample);
            double y = buffer.posY(sample);
            double z = buffer.posZ(sample);
            float tick = buffer.tickAt(sample);

            /* 物理读出的是方块**中心**；BBS 的 BlockForm 渲染时以自身中心为原点，
             * 所以中心坐标直接对应 actor 的 x/y/z。 */
            cx.insert(tick, x);
            cy.insert(tick, y);
            cz.insert(tick, z);

            if (transformChannel != null)
            {
                Transform transform = new Transform();

                transform.rotationMode = Transform.RotationMode.QUATERNION;
                transform.translate.set(0F, 0F, 0F);
                transform.scale.set(1F, 1F, 1F);

                Quaternionf quat = new Quaternionf(
                    buffer.rotX(sample), buffer.rotY(sample),
                    buffer.rotZ(sample), buffer.rotW(sample));

                /* 归一化：物理引擎偶尔会输出轻微非单位四元数，
                 * 长轨迹插值会把这点误差放大成肉眼可见的形变 */
                quat.normalize();

                transform.quat.set(quat);

                rotations.add(transform);
            }
        }

        if (transformChannel != null)
        {
            for (int k = 0; k < rotations.size(); k++)
            {
                int sample = order[result.indices[k]];

                transformChannel.insert(buffer.tickAt(sample), rotations.get(k));
            }
        }

        /* === 最后才挂进影片：上面所有 insert 都发生在游离对象上，通知次数最少 === */
        film.replays.add(replay);
    }
}
