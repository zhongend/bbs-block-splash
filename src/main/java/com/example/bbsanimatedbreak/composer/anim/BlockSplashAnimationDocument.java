package com.example.bbsanimatedbreak.composer.anim;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Block Splash 独立动画文档 —— 规范 §三~§五、§三十二、§三十九、§八十一~§八十二
 *
 * === 为什么它必须存在（第一性原理）===
 * 上一轮我把烘焙结果直接写进了 BBS 原生 Replay/Keyframe。那份实现能跑，
 * 但它违反了这条规范里最重要的一个区分：
 *
 *     BBS Native K-Frame ≠ Block Splash K-Frame
 *
 * 两者不是竞争关系，而是**不同层级**：前者管角色/原生动画，后者管方块碎片/物理结果。
 * 把 3000 个方块的轨道塞进原版编辑器，会产生 3000×N 条 Track ——
 * 原版时间轴瞬间不可用，而且两者会互相污染（规范 §一、§五十七）。
 *
 * 所以烘焙的正确落点是**独立的创作资产**：
 *
 *     Physics → Trajectory → Reduction → BlockSplashAnimationDocument
 *
 * 而"导出到 BBS 原生动画"是**显式的高级操作**，不是默认行为（规范 §五十六、§六十五）。
 *
 * === 与 Composition 的关系（规范 §三十四~§三十七）===
 * 文档记录 sourceCompositionId 以便关联，但生命周期**独立**：
 * 改了 Composition 不会偷偷改已烘焙的动画，必须显式 Re-Bake（§三十六），
 * 且 Re-Bake 生成**新图层**而不是覆盖手工编辑（§三十八~§四十）。
 * 这就是非破坏式工作流。
 *
 * === 内存模型（规范 §八十一~§八十二）===
 * 不能因为 1000 blocks × 1000 ticks 就存百万级 Java 对象。
 * 所以这里全部用 **primitive arrays**：
 *
 *     一条位置轨道 = float[] ticks + double[] x/y/z
 *     一条旋转轨道 = float[] ticks + float[] qx/qy/qz/qw
 *
 * 1000 方块 × 30 键 ≈ 1000 × (30×(4+24) + 30×(4+16)) ≈ 1.5 MB —— 可接受。
 * 对比"每键一个对象"（≈100 字节/键，且全部进堆）：省 5~10 倍且无 GC 压力。
 *
 * === 图层（规范 §三十九、§五十一~§五十三）===
 * Base / Physics Bake / Manual / Procedural。每条 BlockTrack 挂在一个图层上。
 * 图层可以 mute/solo/lock；Re-Bake 生成新图层，旧的保留 —— 手工编辑不会被冲掉。
 */
public final class BlockSplashAnimationDocument
{
    public static final int SCHEMA_VERSION = 2;

    /* === 图层类型（规范 §五十二）=== */
    public static final int LAYER_BASE = 0;
    public static final int LAYER_PHYSICS = 1;
    public static final int LAYER_MANUAL = 2;
    public static final int LAYER_PROCEDURAL = 3;

    /** 关键帧来源（规范 §六十九：编辑器用不同视觉标识） */
    public static final int SOURCE_PHYSICS = 0;
    public static final int SOURCE_MANUAL = 1;
    public static final int SOURCE_PROCEDURAL = 2;

    /* === 插值（规范 §二十九：优先适配 BBS 已有的 Envelope/Interpolation）=== */
    public static final byte INTERP_LINEAR = 0;
    public static final byte INTERP_STEP = 1;
    public static final byte INTERP_SMOOTH = 2;

    /* ================================================================
     * 图层
     * ================================================================ */
    public static final class AnimationLayer
    {
        public String id;
        public String name;
        public int kind;
        /** Re-Bake 时递增，用于"Physics Bake 001 / 002"这种命名（规范 §四十） */
        public int bakeIndex;

        /** 规范 §五十三：图层可单独关闭 —— 关掉 Physics 就看得到 Manual */
        public boolean muted;
        public boolean solo;
        public boolean locked;

        public AnimationLayer(String id, String name, int kind, int bakeIndex)
        {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.bakeIndex = bakeIndex;
        }
    }

    /* ================================================================
     * 方块轨道（规范 §五、§六）
     * ================================================================ */
    public static final class BlockTrack
    {
        /** 所属图层（layers 的下标） */
        public int layer;

        /** 稳定方块 ID（规范 §八十三 的身份锚点） */
        public long stableBlockId;

        /** 源方块坐标（烘焙前的位置） */
        public int sourceX, sourceY, sourceZ;

        /** 源方块状态（运行期持有；持久化见 toData） */
        public transient Object blockState;

        /** 方块 ID 字符串（持久化用，如 "minecraft:oak_stairs"） */
        public String blockId;

        /* --- 位置轨道：SoA 原始数组 --- */
        public float[] posTick;
        public double[] posX, posY, posZ;
        public byte[] posSource;
        public byte[] posInterp;

        /* --- 旋转轨道（四元数，三轴无万向节锁）--- */
        public float[] rotTick;
        public float[] rotX, rotY, rotZ, rotW;
        public byte[] rotSource;
        public byte[] rotInterp;

        /* --- 元数据（规范 §六十七：来源/后端/tick 区间/键数，只在本编辑器显示）--- */
        public String sourceLabel;
        public String backendLabel;

        /**
         * 轨道时间轴到影片时间轴的偏移（tick）
         *
         * 烘焙写入的是物理世界的局部 tick（0 = 片段起始），播放要挂到 BBS 回放时钟
         * （编辑器游标 / 播放头是影片绝对时间），所以求值时必须加回片段起始 tick。
         */
        public int timeOffset;

        public int keyCount()
        {
            return this.posTick == null ? 0 : this.posTick.length;
        }
    }

    /* ================================================================
     * 文档本体
     * ================================================================ */

    public int schemaVersion = SCHEMA_VERSION;

    /** 文档 ID（保存文件名的基础） */
    public String documentId = "";

    /** 来源 composition（规范 §三十四：可关联，但生命周期独立） */
    public String sourceCompositionId = "";

    /** 片段总长（tick） */
    public int duration;

    public final List<AnimationLayer> layers = new ArrayList<>();

    public final List<BlockTrack> tracks = new ArrayList<>();

    /** 当前启用（未 mute 且被 solo 规则允许）的图层集合，Flatten 时用 */
    public int activeLayerCount()
    {
        int n = 0;
        boolean anySolo = false;

        for (AnimationLayer layer : this.layers)
        {
            if (layer.solo) anySolo = true;
        }

        for (AnimationLayer layer : this.layers)
        {
            if (anySolo ? layer.solo : !layer.muted)
            {
                n++;
            }
        }

        return n;
    }

    /** 新建一个图层并返回下标 */
    public int addLayer(String name, int kind, int bakeIndex)
    {
        String id = "layer_" + (this.layers.size() + 1);

        this.layers.add(new AnimationLayer(id, name, kind, bakeIndex));

        return this.layers.size() - 1;
    }

    /** 找到"最近的 Physics 图层"，Re-Bake 时判断要不要新建（规范 §三十八~§四十） */
    public int findPhysicsLayer(int bakeIndex)
    {
        for (int i = 0; i < this.layers.size(); i++)
        {
            AnimationLayer layer = this.layers.get(i);

            if (layer.kind == LAYER_PHYSICS && layer.bakeIndex == bakeIndex)
            {
                return i;
            }
        }

        return -1;
    }

    /** 全文档关键帧总数（规范 §三十 的 UI 统计） */
    public int totalKeys()
    {
        int n = 0;

        for (BlockTrack track : this.tracks)
        {
            n += track.keyCount();
        }

        return n;
    }

    /** 估算内存占用（字节）—— 规范 §八十一 要求它必须"轻量" */
    public long estimatedBytes()
    {
        long bytes = 0;

        for (BlockTrack track : this.tracks)
        {
            int posKeys = track.keyCount();
            int rotKeys = track.rotTick == null ? 0 : track.rotTick.length;

            // pos: 4 + 3*8 + 1 + 1 = 30 字节/键；rot: 4 + 4*4 + 1 + 1 = 22 字节/键
            bytes += (long) posKeys * 30L + (long) rotKeys * 22L + 96L;
        }

        return bytes + (long) this.layers.size() * 64L;
    }

    /* ================================================================
     * 持久化（规范 §三十二~§三十三）
     *
     * 用二进制而不是 JSON：轨道本体是 primitive 数组，二进制写法既紧凑
     * （无文本开销）又快（无字符串解析）。规范 §三十二 说"实际路径和格式
     * 必须结合 BBS 项目已有的资源/存档体系设计"—— BBS 的 FilmManager 用的
     * 也是 CompressedDataStorage（二进制），所以这里遵循同一惯例。
     * ================================================================ */

    public void write(DataOutput out) throws IOException
    {
        out.writeInt(this.schemaVersion);
        out.writeUTF(orEmpty(this.documentId));
        out.writeUTF(orEmpty(this.sourceCompositionId));
        out.writeInt(this.duration);

        out.writeInt(this.layers.size());
        for (AnimationLayer layer : this.layers)
        {
            out.writeUTF(orEmpty(layer.id));
            out.writeUTF(orEmpty(layer.name));
            out.writeInt(layer.kind);
            out.writeInt(layer.bakeIndex);
            out.writeBoolean(layer.muted);
            out.writeBoolean(layer.solo);
            out.writeBoolean(layer.locked);
        }

        out.writeInt(this.tracks.size());
        for (BlockTrack track : this.tracks)
        {
            out.writeInt(track.layer);
            out.writeLong(track.stableBlockId);
            out.writeInt(track.sourceX);
            out.writeInt(track.sourceY);
            out.writeInt(track.sourceZ);
            out.writeUTF(orEmpty(track.blockId));
            out.writeUTF(orEmpty(track.sourceLabel));
            out.writeUTF(orEmpty(track.backendLabel));

            /* 方块状态：复用 BBS 自己的 BLOCK_STATE 关键帧工厂做序列化，
             * 属性（楼梯朝向、台阶半砖等）一并保留 —— 这是唯一能
             * 无损往返 class_2680 的现成路径。 */
            byte[] stateBytes = null;

            if (track.blockState instanceof net.minecraft.class_2680 state)
            {
                try { stateBytes = mchorse.bbs_mod.data.DataStorageUtils.writeToBytes(
                    mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories.BLOCK_STATE.toData(state)); }
                catch (Throwable t) { stateBytes = null; }
            }

            writeByteArray(out, stateBytes);

            if (this.schemaVersion >= 2)
            {
                out.writeInt(track.timeOffset);
            }

            writeFloatArray(out, track.posTick);
            writeDoubleArray(out, track.posX);
            writeDoubleArray(out, track.posY);
            writeDoubleArray(out, track.posZ);
            writeByteArray(out, track.posSource);
            writeByteArray(out, track.posInterp);

            writeFloatArray(out, track.rotTick);
            writeFloatArray(out, track.rotX);
            writeFloatArray(out, track.rotY);
            writeFloatArray(out, track.rotZ);
            writeFloatArray(out, track.rotW);
            writeByteArray(out, track.rotSource);
            writeByteArray(out, track.rotInterp);
        }
    }

    public static BlockSplashAnimationDocument read(DataInput in) throws IOException
    {
        BlockSplashAnimationDocument doc = new BlockSplashAnimationDocument();

        doc.schemaVersion = in.readInt();
        doc.documentId = in.readUTF();
        doc.sourceCompositionId = in.readUTF();
        doc.duration = in.readInt();

        int layerCount = in.readInt();
        for (int i = 0; i < layerCount; i++)
        {
            AnimationLayer layer = new AnimationLayer(in.readUTF(), in.readUTF(), in.readInt(), in.readInt());
            layer.muted = in.readBoolean();
            layer.solo = in.readBoolean();
            layer.locked = in.readBoolean();
            doc.layers.add(layer);
        }

        int trackCount = in.readInt();
        for (int i = 0; i < trackCount; i++)
        {
            BlockTrack track = new BlockTrack();

            track.layer = in.readInt();
            track.stableBlockId = in.readLong();
            track.sourceX = in.readInt();
            track.sourceY = in.readInt();
            track.sourceZ = in.readInt();
            track.blockId = in.readUTF();
            track.sourceLabel = in.readUTF();
            track.backendLabel = in.readUTF();

            byte[] stateBytes = readByteArray(in);

            if (stateBytes != null && stateBytes.length > 0)
            {
                try
                {
                    Object state = mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories.BLOCK_STATE
                        .fromData(mchorse.bbs_mod.data.DataStorageUtils.readFromBytes(stateBytes));

                    if (state != null) track.blockState = state;
                }
                catch (Throwable t) { /* 旧档/跨版本：状态缺失就用默认态 */ }
            }

            if (doc.schemaVersion >= 2)
            {
                track.timeOffset = in.readInt();
            }

            track.posTick = readFloatArray(in);
            track.posX = readDoubleArray(in);
            track.posY = readDoubleArray(in);
            track.posZ = readDoubleArray(in);
            track.posSource = readByteArray(in);
            track.posInterp = readByteArray(in);

            track.rotTick = readFloatArray(in);
            track.rotX = readFloatArray(in);
            track.rotY = readFloatArray(in);
            track.rotZ = readFloatArray(in);
            track.rotW = readFloatArray(in);
            track.rotSource = readByteArray(in);
            track.rotInterp = readByteArray(in);

            doc.tracks.add(track);
        }

        return doc;
    }

    private static String orEmpty(String s)
    {
        return s == null ? "" : s;
    }

    private static void writeFloatArray(DataOutput out, float[] a) throws IOException
    {
        int n = a == null ? 0 : a.length;
        out.writeInt(n);
        for (int i = 0; i < n; i++) out.writeFloat(a[i]);
    }

    private static void writeDoubleArray(DataOutput out, double[] a) throws IOException
    {
        int n = a == null ? 0 : a.length;
        out.writeInt(n);
        for (int i = 0; i < n; i++) out.writeDouble(a[i]);
    }

    private static void writeByteArray(DataOutput out, byte[] a) throws IOException
    {
        int n = a == null ? 0 : a.length;
        out.writeInt(n);
        if (n > 0) out.write(a);
    }

    private static float[] readFloatArray(DataInput in) throws IOException
    {
        int n = in.readInt();
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = in.readFloat();
        return a;
    }

    private static double[] readDoubleArray(DataInput in) throws IOException
    {
        int n = in.readInt();
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = in.readDouble();
        return a;
    }

    private static byte[] readByteArray(DataInput in) throws IOException
    {
        int n = in.readInt();
        byte[] a = new byte[n];
        in.readFully(a, 0, n);
        return a;
    }
}
