package com.example.bbsanimatedbreak.actions.combo;

import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.camera.values.ValuePosition;
import mchorse.bbs_mod.camera.values.ValuePositions;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueDouble;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import net.minecraft.class_1937;
import net.minecraft.class_2338;
import mchorse.bbs_mod.settings.values.core.ValueString;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 飞溅组合的方块选区数据
 *
 * 支持 5 种选择模式：
 * - rectangle：矩形（2 对角点）
 * - circle：圆形（中心点 + 边界点，由两点距离决定半径）
 * - triangle：三角形（3 顶点）
 * - polygon：不规则多边形（N 个顶点 + 顶点高度范围）
 * - random：随机散点（中心点 + 半径 + 密度种子）
 *
 * 选区存储方式（用户已选择"预烘焙 BlockPos 列表"方案）：
 * - vertices：选区顶点（编辑器中用选择工具拾取的点）
 * - bakedBlocks：预烘焙的 BlockPos 列表（编辑器确认后收集并存入）
 * - anchor：锚点偏移（运行时把 bakedBlocks 整体偏移到 clip 触发位置）
 *
 * 运行时还原：
 * - 不重新计算形状，直接遍历 bakedBlocks + anchor 偏移得到实际 BlockPos
 * - 这样圆形/三角形/不规则选择的还原结果和编辑器预览完全一致
 */
public class BlockSelection extends ValueGroup
{
    /* 选区形状：rectangle / circle / triangle / polygon / random */
    public final ValueString shape = new ValueString("shape", "rectangle");

    /* 选区顶点（编辑器拾取的点，矩形=2, 圆=2, 三角=3, 多边形=N, 随机=1中心点） */
    public final ValuePositions vertices = new ValuePositions("vertices");

    /* 预烘焙的 BlockPos 列表（编辑器确认后存入，运行时直接用） */
    public final ValuePositions bakedBlocks = new ValuePositions("bakedBlocks");

    /* 锚点偏移（运行时把 bakedBlocks 整体偏移到此锚点附近） */
    public final ValuePosition anchor = new ValuePosition("anchor");

    /* 随机选择参数 */
    public final ValueInt randomSeed = new ValueInt("randomSeed", 0);
    public final ValueDouble randomDensity = new ValueDouble("randomDensity", 0.5D, 0.05D, 1D);

    /* 多边形高度范围（不规则选择时，顶点定义水平面投影，这两个值定义 Y 范围） */
    public final ValueInt polygonMinY = new ValueInt("polygonMinY", 0);
    public final ValueInt polygonMaxY = new ValueInt("polygonMaxY", 0);

    public BlockSelection(String id)
    {
        super(id);

        this.add(this.shape);
        this.add(this.vertices);
        this.add(this.bakedBlocks);
        this.add(this.anchor);
        this.add(this.randomSeed);
        this.add(this.randomDensity);
        this.add(this.polygonMinY);
        this.add(this.polygonMaxY);
    }

    /**
     * 根据当前形状和顶点，从世界收集 BlockPos 并烘焙到 bakedBlocks。
     *
     * 在编辑器中用户点击"确认选区"时调用。
     *
     * @param world 用于查询方块（验证非空气）
     * @param includeAir 是否包含空气方块
     */
    public void bake(class_1937 world, boolean includeAir)
    {
        List<class_2338> blocks = collectBlocksByShape(world, includeAir);

        this.bakedBlocks.reset();

        for (class_2338 pos : blocks)
        {
            Position p = new Position();
            p.point.x = pos.method_10263();
            p.point.y = pos.method_10264();
            p.point.z = pos.method_10260();
            this.bakedBlocks.add(p);
        }
    }

    /**
     * 根据形状和顶点收集 BlockPos（不烘焙，仅返回列表）。
     * 5 种选择算法的入口。
     */
    public List<class_2338> collectBlocksByShape(class_1937 world, boolean includeAir)
    {
        String s = this.shape.get();
        List<Position> verts = new ArrayList<>();

        for (int i = 0; i < this.vertices.size(); i++)
        {
            verts.add(this.vertices.get(i));
        }

        if (verts.isEmpty())
        {
            return new ArrayList<>();
        }

        switch (s)
        {
            case "circle":
                return collectCircle(verts, world, includeAir);
            case "triangle":
                return collectTriangle(verts, world, includeAir);
            case "polygon":
                return collectPolygon(verts, world, includeAir);
            case "random":
                return collectRandom(verts, world, includeAir);
            case "rectangle":
            default:
                return collectRectangle(verts, world, includeAir);
        }
    }

    /* === 5 种选择算法 === */

    /** 矩形选择：两个对角点定义 AABB */
    private List<class_2338> collectRectangle(List<Position> verts, class_1937 world, boolean includeAir)
    {
        if (verts.size() < 2) return new ArrayList<>();

        Position p1 = verts.get(0);
        Position p2 = verts.get(1);

        int minX = Math.min((int) p1.point.x, (int) p2.point.x);
        int minY = Math.min((int) p1.point.y, (int) p2.point.y);
        int minZ = Math.min((int) p1.point.z, (int) p2.point.z);
        int maxX = Math.max((int) p1.point.x, (int) p2.point.x);
        int maxY = Math.max((int) p1.point.y, (int) p2.point.y);
        int maxZ = Math.max((int) p1.point.z, (int) p2.point.z);

        List<class_2338> out = new ArrayList<>();

        for (int x = minX; x <= maxX; x++)
        {
            for (int y = minY; y <= maxY; y++)
            {
                for (int z = minZ; z <= maxZ; z++)
                {
                    class_2338 pos = new class_2338(x, y, z);

                    if (includeAir || !world.method_8320(pos).method_26215())
                    {
                        out.add(pos);
                    }
                }
            }
        }

        return out;
    }

    /** 圆形选择：两点定义圆心 + 半径（水平面圆，Y 范围由两点 Y 决定） */
    private List<class_2338> collectCircle(List<Position> verts, class_1937 world, boolean includeAir)
    {
        if (verts.size() < 2) return new ArrayList<>();

        Position center = verts.get(0);
        Position edge = verts.get(1);

        int cx = (int) center.point.x;
        int cy = (int) center.point.y;
        int cz = (int) center.point.z;

        double radius = Math.sqrt(
            Math.pow(edge.point.x - center.point.x, 2) +
            Math.pow(edge.point.z - center.point.z, 2)
        );

        int r = (int) Math.ceil(radius);
        int minY = Math.min((int) center.point.y, (int) edge.point.y);
        int maxY = Math.max((int) center.point.y, (int) edge.point.y);

        List<class_2338> out = new ArrayList<>();
        double rSq = radius * radius;

        for (int x = cx - r; x <= cx + r; x++)
        {
            for (int z = cz - r; z <= cz + r; z++)
            {
                double distSq = (x - cx) * (x - cx) + (z - cz) * (z - cz);

                if (distSq <= rSq)
                {
                    for (int y = minY; y <= maxY; y++)
                    {
                        class_2338 pos = new class_2338(x, y, z);

                        if (includeAir || !world.method_8320(pos).method_26215())
                        {
                            out.add(pos);
                        }
                    }
                }
            }
        }

        return out;
    }

    /** 三角形选择：3 顶点定义三角形棱柱（水平面三角形 + Y 范围） */
    private List<class_2338> collectTriangle(List<Position> verts, class_1937 world, boolean includeAir)
    {
        if (verts.size() < 3) return new ArrayList<>();

        Position v0 = verts.get(0);
        Position v1 = verts.get(1);
        Position v2 = verts.get(2);

        int minY = (int) Math.min(Math.min(v0.point.y, v1.point.y), v2.point.y);
        int maxY = (int) Math.max(Math.max(v0.point.y, v1.point.y), v2.point.y);

        int minX = (int) Math.min(Math.min(v0.point.x, v1.point.x), v2.point.x);
        int maxX = (int) Math.max(Math.max(v0.point.x, v1.point.x), v2.point.x);
        int minZ = (int) Math.min(Math.min(v0.point.z, v1.point.z), v2.point.z);
        int maxZ = (int) Math.max(Math.max(v0.point.z, v1.point.z), v2.point.z);

        List<class_2338> out = new ArrayList<>();

        for (int y = minY; y <= maxY; y++)
        {
            for (int x = minX; x <= maxX; x++)
            {
                for (int z = minZ; z <= maxZ; z++)
                {
                    if (pointInTriangle(x, z, v0, v1, v2))
                    {
                        class_2338 pos = new class_2338(x, y, z);

                        if (includeAir || !world.method_8320(pos).method_26215())
                        {
                            out.add(pos);
                        }
                    }
                }
            }
        }

        return out;
    }

    /** 不规则多边形选择：N 顶点定义水平面多边形 + polygonMinY/polygonMaxY 定义 Y 范围 */
    private List<class_2338> collectPolygon(List<Position> verts, class_1937 world, boolean includeAir)
    {
        if (verts.size() < 3) return new ArrayList<>();

        int minY = Math.min(this.polygonMinY.get(), this.polygonMaxY.get());
        int maxY = Math.max(this.polygonMinY.get(), this.polygonMaxY.get());

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (Position p : verts)
        {
            minX = Math.min(minX, (int) p.point.x);
            maxX = Math.max(maxX, (int) p.point.x);
            minZ = Math.min(minZ, (int) p.point.z);
            maxZ = Math.max(maxZ, (int) p.point.z);
        }

        List<class_2338> out = new ArrayList<>();

        for (int y = minY; y <= maxY; y++)
        {
            for (int x = minX; x <= maxX; x++)
            {
                for (int z = minZ; z <= maxZ; z++)
                {
                    if (pointInPolygon(x, z, verts))
                    {
                        class_2338 pos = new class_2338(x, y, z);

                        if (includeAir || !world.method_8320(pos).method_26215())
                        {
                            out.add(pos);
                        }
                    }
                }
            }
        }

        return out;
    }

    /** 随机选择：中心点 + 半径 + 密度种子，随机散点 */
    private List<class_2338> collectRandom(List<Position> verts, class_1937 world, boolean includeAir)
    {
        if (verts.isEmpty()) return new ArrayList<>();

        Position center = verts.get(0);
        int cx = (int) center.point.x;
        int cy = (int) center.point.y;
        int cz = (int) center.point.z;

        /* 半径默认 10，如果有多于 1 个顶点，用第二点距离作为半径 */
        int radius = 10;

        if (verts.size() >= 2)
        {
            Position edge = verts.get(1);
            radius = (int) Math.ceil(Math.sqrt(
                Math.pow(edge.point.x - center.point.x, 2) +
                Math.pow(edge.point.z - center.point.z, 2)
            ));
        }

        Random rng = new Random(this.randomSeed.get());
        double density = this.randomDensity.get();

        List<class_2338> out = new ArrayList<>();
        Set<Long> visited = new HashSet<>();

        /* 在圆形范围内按密度随机选取方块 */
        int targetCount = (int) (Math.PI * radius * radius * density);

        for (int i = 0; i < targetCount * 3 && out.size() < targetCount; i++)
        {
            int dx = rng.nextInt(radius * 2 + 1) - radius;
            int dz = rng.nextInt(radius * 2 + 1) - radius;

            if (dx * dx + dz * dz > radius * radius) continue;

            int x = cx + dx;
            int z = cz + dz;

            /* Y 范围：中心点上下各 radius/2 */
            int yRange = Math.max(1, radius / 2);

            for (int dy = -yRange; dy <= yRange; dy++)
            {
                int y = cy + dy;
                long key = class_2338.method_10064(x, y, z);

                if (visited.contains(key)) continue;
                visited.add(key);

                class_2338 pos = new class_2338(x, y, z);

                if (includeAir || !world.method_8320(pos).method_26215())
                {
                    out.add(pos);
                }
            }
        }

        return out;
    }

    /* === 几何辅助 === */

    /** 点在三角形内（重心坐标法） */
    private boolean pointInTriangle(int px, int pz, Position v0, Position v1, Position v2)
    {
        double x0 = v0.point.x, z0 = v0.point.z;
        double x1 = v1.point.x, z1 = v1.point.z;
        double x2 = v2.point.x, z2 = v2.point.z;

        double denom = (z1 - z2) * (x0 - x2) + (x2 - x1) * (z0 - z2);
        if (Math.abs(denom) < 1e-10) return false;

        double a = ((z1 - z2) * (px - x2) + (x2 - x1) * (pz - z2)) / denom;
        double b = ((z2 - z0) * (px - x2) + (x0 - x2) * (pz - z2)) / denom;
        double c = 1 - a - b;

        return a >= 0 && b >= 0 && c >= 0;
    }

    /** 点在多边形内（射线法） */
    private boolean pointInPolygon(int px, int pz, List<Position> verts)
    {
        int n = verts.size();
        boolean inside = false;

        for (int i = 0, j = n - 1; i < n; j = i++)
        {
            double xi = verts.get(i).point.x, zi = verts.get(i).point.z;
            double xj = verts.get(j).point.x, zj = verts.get(j).point.z;

            if ((zi > pz) != (zj > pz))
            {
                double xIntersect = (zj - zi) * (px - xi) / (zj - zi + 1e-10) + xi;

                if (px < xIntersect)
                {
                    inside = !inside;
                }
            }
        }

        return inside;
    }

    /* === 运行时还原：从 bakedBlocks + anchor 得到实际 BlockPos 列表 === */

    /**
     * 运行时还原选区为 BlockPos 列表。
     *
     * 把 bakedBlocks 里的每个点 + anchor 偏移得到实际世界坐标。
     * anchor 用于把编辑器里选的方块"移动"到 clip 触发位置。
     *
     * @return 偏移后的 BlockPos 列表
     */
    public List<class_2338> getBlocksAtAnchor()
    {
        List<class_2338> out = new ArrayList<>();

        int ax = (int) this.anchor.get().point.x;
        int ay = (int) this.anchor.get().point.y;
        int az = (int) this.anchor.get().point.z;

        for (int i = 0; i < this.bakedBlocks.size(); i++)
        {
            Position p = this.bakedBlocks.get(i);

            out.add(new class_2338(
                (int) p.point.x + ax,
                (int) p.point.y + ay,
                (int) p.point.z + az
            ));
        }

        return out;
    }

    /**
     * 把 bakedBlocks 整体偏移到指定世界坐标（覆盖 anchor）。
     * 用于运行时根据 clip 触发位置动态设置锚点。
     */
    public void setAnchorTo(int x, int y, int z)
    {
        Position p = this.anchor.get();
        p.point.x = x;
        p.point.y = y;
        p.point.z = z;
    }

    /**
     * 计算选区的中心点（基于 bakedBlocks）。
     * 用于运行时把选区对齐到目标位置。
     */
    public class_2338 getBakedCenter()
    {
        if (this.bakedBlocks.size() == 0) return class_2338.field_10980;

        long sumX = 0, sumY = 0, sumZ = 0;

        for (int i = 0; i < this.bakedBlocks.size(); i++)
        {
            Position p = this.bakedBlocks.get(i);
            sumX += (int) p.point.x;
            sumY += (int) p.point.y;
            sumZ += (int) p.point.z;
        }

        int n = this.bakedBlocks.size();

        return new class_2338((int) (sumX / n), (int) (sumY / n), (int) (sumZ / n));
    }

    public boolean hasBakedBlocks()
    {
        return this.bakedBlocks.size() > 0;
    }

    public int getBakedCount()
    {
        return this.bakedBlocks.size();
    }

    /**
     * 返回烘焙方块的 Set 形式（含锚点偏移），用于 IBlockFilterable 过滤。
     * 如果没有烘焙方块，返回 null（表示不过滤，使用包围盒）。
     */
    public Set<class_2338> getBlockFilterSet()
    {
        if (!this.hasBakedBlocks()) return null;

        List<class_2338> blocks = this.getBlocksAtAnchor();
        Set<class_2338> set = new HashSet<>(blocks.size());

        set.addAll(blocks);

        return set;
    }

    @Override
    public BaseType toData()
    {
        MapType data = (MapType) super.toData();

        /* 确保锚点被序列化（ValuePosition 可能有特殊处理） */
        return data;
    }
}
