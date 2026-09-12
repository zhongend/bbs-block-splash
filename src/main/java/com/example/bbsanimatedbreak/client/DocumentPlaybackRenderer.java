package com.example.bbsanimatedbreak.client;

import com.example.bbsanimatedbreak.composer.anim.AnimationDocumentStore;
import com.example.bbsanimatedbreak.composer.anim.BlockSplashAnimationDocument;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.class_2338;
import net.minecraft.class_243;
import net.minecraft.class_2680;
import net.minecraft.class_310;
import net.minecraft.class_4608;
import net.minecraft.class_638;
import org.joml.Quaternionf;

/**
 * 独立动画文档的回放渲染器（规范 §47：文档在 BBS 回放时钟上的无物理播放）
 *
 * === 工作原理 ===
 *
 * 1. {@code BaseFilmControllerMixin} 在 {@code startRenderFrame} 的 HEAD 捕获
 *    「当前正在渲染的影片 id + 回放 tick + tickDelta」。这个方法编辑器预览
 *    （FilmEditorController）和世界播放（WorldFilmController，导出视频同路径）
 *    都会走，是唯一的逐帧汇聚点 —— 所以我们的渲染自动与 BBS 回放时钟同步，
 *    导出视频时每帧也被正确求值。
 *
 * 2. 我们在 {@code WorldRenderEvents.AFTER_ENTITIES} 里对文档做**纯关键帧求值**：
 *    二分找键 → 线性插值位置 / 四元数 slerp 旋转 → 直接画方块模型。
 *    没有实体、没有物理、没有任何模拟 —— 烘焙结果就是权威，
 *    与当年模拟时的帧率、机器性能完全无关（确定性，规范 §13）。
 *
 * === 时间轴换算 ===
 *
 * 烘焙写入的是物理世界的局部 tick；轨道带 {@code timeOffset}（片段起始 tick），
 * 求值时间 = 回放 tick + tickDelta + timeOffset。
 *
 * === 前后段语义 ===
 *
 * - timeOffset 之前（物理还没开始）：**不渲染** —— 世界里原版方块还在原位；
 * - 末键之后（物理已结束）：**保持在末键位置** —— 等价于"落地后定格"。
 *
 * === 性能（规范 §57，弱 GPU 一体机）===
 *
 * - 距离剔除（160 格外不画）+ 每轨道 O(log n) 二分；
 * - 零持久分配：求值写进复用的 scratch 数组；仅每方块一个小的确定性 Random
 *   （Xoroshiro，24 字节，只为方块随机变体确定性服务）；
 * - 图层可见性每帧预计算一次，而不是每轨道判断。
 */
public final class DocumentPlaybackRenderer
{
    /** 渲染距离上限（格）。超出后方块肉眼不可辨，直接剔除。 */
    private static final double MAX_RENDER_DISTANCE_SQ = 160.0D * 160.0D;

    /* === 每帧捕获（由 Mixin 写入） === */

    private static boolean frameCaptured;
    private static String activeFilmId;
    private static int activeTick;
    private static float activeTransition;

    /* === 求值 scratch（零分配热路径） === */

    private static final double[] SCRATCH_POS = new double[3];
    private static final float[] SCRATCH_QUAT = new float[4];
    private static final Quaternionf SCRATCH_ROT = new Quaternionf();
    private static final Quaternionf SCRATCH_ROT_B = new Quaternionf();
    private static boolean[] scratchLayerVisible = new boolean[8];

    private DocumentPlaybackRenderer()
    {}

    /** Mixin 调用：某影片控制器的渲染帧开始 */
    public static void onFrameStart(String filmId, int tick, float transition)
    {
        frameCaptured = true;
        activeFilmId = filmId;
        activeTick = tick;
        activeTransition = transition;
    }

    /** WorldRenderEvents.START：重置捕获标志（这一帧没有控制器渲染就不画） */
    public static void onFrameReset()
    {
        frameCaptured = false;
    }

    /** WorldRenderEvents.AFTER_ENTITIES：渲染当前影片的文档轨道 */
    public static void renderAfterEntities(WorldRenderContext context)
    {
        if (!frameCaptured || activeFilmId == null)
        {
            return;
        }

        class_310 mc = class_310.method_1551();
        class_638 world = mc.field_1687;

        if (world == null)
        {
            return;
        }

        BlockSplashAnimationDocument doc = AnimationDocumentStore.load(activeFilmId);

        if (doc == null || doc.tracks.isEmpty())
        {
            return;
        }

        /* === 图层可见性（每帧一次） ===
         * Physics 图层只画 bakeIndex 最高的那个：Re-Bake 会生成新图层
         * （规范 §40），旧物理图层默认不再画，避免同一方块重复渲染。
         * 手动/程序图层全部尊重 mute/solo。 */
        int layerCount = doc.layers.size();

        if (scratchLayerVisible.length < layerCount)
        {
            scratchLayerVisible = new boolean[layerCount];
        }

        boolean anySolo = false;
        int newestPhysicsBake = -1;

        for (int i = 0; i < layerCount; i++)
        {
            BlockSplashAnimationDocument.AnimationLayer layer = doc.layers.get(i);

            if (layer.solo) anySolo = true;

            if (layer.kind == BlockSplashAnimationDocument.LAYER_PHYSICS
                && layer.bakeIndex > newestPhysicsBake)
            {
                newestPhysicsBake = layer.bakeIndex;
            }
        }

        for (int i = 0; i < layerCount; i++)
        {
            BlockSplashAnimationDocument.AnimationLayer layer = doc.layers.get(i);
            boolean visible = !layer.muted && (!anySolo || layer.solo);

            if (visible && layer.kind == BlockSplashAnimationDocument.LAYER_PHYSICS)
            {
                visible = layer.bakeIndex == newestPhysicsBake;
            }

            scratchLayerVisible[i] = visible;
        }

        /* === 逐轨道求值渲染 === */
        float time = activeTick + activeTransition;
        class_243 camPos = context.camera().method_19326();
        double camX = camPos.field_1352;
        double camY = camPos.field_1351;
        double camZ = camPos.field_1350;

        net.minecraft.class_4587 stack = context.matrixStack();
        net.minecraft.class_4597 consumers = context.consumers();
        net.minecraft.class_776 blockRenderManager = mc.method_1541();

        for (int t = 0, count = doc.tracks.size(); t < count; t++)
        {
            BlockSplashAnimationDocument.BlockTrack track = doc.tracks.get(t);

            if (track.layer < 0 || track.layer >= layerCount || !scratchLayerVisible[track.layer])
            {
                continue;
            }

            if (!(track.blockState instanceof class_2680 state))
            {
                continue;
            }

            float trackTime = time + track.timeOffset;

            if (!evaluatePosition(track, trackTime))
            {
                continue;
            }

            double px = SCRATCH_POS[0];
            double py = SCRATCH_POS[1];
            double pz = SCRATCH_POS[2];

            /* 距离剔除 */
            double dx = px - camX;
            double dy = py - camY;
            double dz = pz - camZ;

            if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQ)
            {
                continue;
            }

            boolean hasRotation = evaluateRotation(track, trackTime);

            stack.method_22903();

            stack.method_22904(px - camX, py - camY, pz - camZ);

            if (hasRotation)
            {
                SCRATCH_ROT.set(SCRATCH_QUAT[0], SCRATCH_QUAT[1], SCRATCH_QUAT[2], SCRATCH_QUAT[3]);
                SCRATCH_ROT.normalize();
                stack.method_22907(SCRATCH_ROT);
            }

            /* 光照取方块当前位置的整包光照（天光<<4 | 方块光），随位置连续变化。
             * 渲染路径与 BBS 自己的 BlockFormRenderer 完全一致（renderBlockAsEntity）：
             * 实体式方块渲染，内部自动居中，箱子/床等动画方块也能正确画。 */
            int light = world.method_22339(new class_2338(
                (int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz)));

            blockRenderManager.method_3353(state, stack, consumers, light, class_4608.field_21444);

            stack.method_22909();
        }
    }

    /* ================= 求值 ================= */

    /**
     * 位置求值（线性插值，静止段已被精简器折叠成两个键，插值自然静止）
     *
     * 时间在首键之前 → 不渲染（原版方块还在）；末键之后 → 保持末键。
     */
    private static boolean evaluatePosition(BlockSplashAnimationDocument.BlockTrack track, float time)
    {
        float[] ticks = track.posTick;
        int n = ticks == null ? 0 : ticks.length;

        if (n == 0)
        {
            return false;
        }

        int hi = upperBound(ticks, time);

        if (hi == 0)
        {
            if (time < ticks[0])
            {
                return false; /* 物理开始前 */
            }

            SCRATCH_POS[0] = track.posX[0];
            SCRATCH_POS[1] = track.posY[0];
            SCRATCH_POS[2] = track.posZ[0];

            return true;
        }

        if (hi == n)
        {
            int last = n - 1;

            SCRATCH_POS[0] = track.posX[last];
            SCRATCH_POS[1] = track.posY[last];
            SCRATCH_POS[2] = track.posZ[last];

            return true;
        }

        int a = hi - 1;
        int b = hi;

        if (track.posInterp != null && track.posInterp[b] == BlockSplashAnimationDocument.INTERP_STEP)
        {
            SCRATCH_POS[0] = track.posX[a];
            SCRATCH_POS[1] = track.posY[a];
            SCRATCH_POS[2] = track.posZ[a];

            return true;
        }

        float span = ticks[b] - ticks[a];
        float alpha = span > 0.0F ? (time - ticks[a]) / span : 0.0F;

        SCRATCH_POS[0] = track.posX[a] + (track.posX[b] - track.posX[a]) * alpha;
        SCRATCH_POS[1] = track.posY[a] + (track.posY[b] - track.posY[a]) * alpha;
        SCRATCH_POS[2] = track.posZ[a] + (track.posZ[b] - track.posZ[a]) * alpha;

        return true;
    }

    /** 旋转求值（四元数 slerp + q/-q 符号修正；JOML 的 slerp 走最短路径） */
    private static boolean evaluateRotation(BlockSplashAnimationDocument.BlockTrack track, float time)
    {
        float[] ticks = track.rotTick;
        int n = ticks == null ? 0 : ticks.length;

        if (n == 0)
        {
            return false;
        }

        int hi = upperBound(ticks, time);

        if (hi == 0)
        {
            if (time < ticks[0])
            {
                return false;
            }

            SCRATCH_QUAT[0] = track.rotX[0];
            SCRATCH_QUAT[1] = track.rotY[0];
            SCRATCH_QUAT[2] = track.rotZ[0];
            SCRATCH_QUAT[3] = track.rotW[0];

            return true;
        }

        if (hi == n)
        {
            int last = n - 1;

            SCRATCH_QUAT[0] = track.rotX[last];
            SCRATCH_QUAT[1] = track.rotY[last];
            SCRATCH_QUAT[2] = track.rotZ[last];
            SCRATCH_QUAT[3] = track.rotW[last];

            return true;
        }

        int a = hi - 1;
        int b = hi;

        if (track.rotInterp != null && track.rotInterp[b] == BlockSplashAnimationDocument.INTERP_STEP)
        {
            SCRATCH_QUAT[0] = track.rotX[a];
            SCRATCH_QUAT[1] = track.rotY[a];
            SCRATCH_QUAT[2] = track.rotZ[a];
            SCRATCH_QUAT[3] = track.rotW[a];

            return true;
        }

        float span = ticks[b] - ticks[a];
        float alpha = span > 0.0F ? (time - ticks[a]) / span : 0.0F;

        SCRATCH_ROT.set(track.rotX[a], track.rotY[a], track.rotZ[a], track.rotW[a]).normalize();
        SCRATCH_ROT_B.set(track.rotX[b], track.rotY[b], track.rotZ[b], track.rotW[b]).normalize();

        /* q 与 -q 表示同一旋转；JOML slerp 内部处理最短路径，无需手动翻转 */
        SCRATCH_ROT.slerp(SCRATCH_ROT_B, alpha);

        SCRATCH_QUAT[0] = SCRATCH_ROT.x;
        SCRATCH_QUAT[1] = SCRATCH_ROT.y;
        SCRATCH_QUAT[2] = SCRATCH_ROT.z;
        SCRATCH_QUAT[3] = SCRATCH_ROT.w;

        return true;
    }

    /** 第一个 tick 严格大于 time 的下标（数组按 tick 升序） */
    private static int upperBound(float[] ticks, float time)
    {
        int lo = 0;
        int hi = ticks.length;

        while (lo < hi)
        {
            int mid = (lo + hi) >>> 1;

            if (ticks[mid] <= time)
            {
                lo = mid + 1;
            }
            else
            {
                hi = mid;
            }
        }

        return lo;
    }
}
