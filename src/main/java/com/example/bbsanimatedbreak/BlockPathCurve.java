package com.example.bbsanimatedbreak;

import mchorse.bbs_mod.utils.interps.Lerps;

import java.util.ArrayList;
import java.util.List;

/**
 * 方块路径曲线计算工具
 *
 * 根据路径点列表和插值类型，计算曲线上的位置。
 * 支持 4 种插值算法：
 * - linear:      线性插值，直线连接
 * - catmull_rom: Catmull-Rom 样条（Hermite），穿过所有控制点，丝滑
 * - b_spline:    B 样条，不穿过控制点但更平滑
 * - cubic:       三次插值，穿过控制点
 *
 * 参照 BBS 的 PathClip 实现：
 * - 4 点法插值，端点用首/末点复制扩展（clamp）
 * - index 范围 0 到 size-2，t 是段内进度 0~1
 */
public class BlockPathCurve
{
    /**
     * 计算曲线上 t 处的位置（t 从 0 到 1）
     *
     * @param points 路径点列表，每个点是 [x, y, z]
     * @param t      沿曲线的进度 0~1
     * @param type   插值类型: linear / catmull_rom / b_spline / cubic
     * @return [x, y, z] 位置
     */
    public static double[] interpolate(List<double[]> points, double t, String type)
    {
        if (points == null || points.isEmpty())
        {
            return new double[]{0, 0, 0};
        }
        if (points.size() == 1)
        {
            return points.get(0).clone();
        }

        t = Math.max(0, Math.min(1, t));

        /* 参照 BBS PathClip：index 范围 0 到 size-2 */
        int segments = points.size() - 1;
        double scaledT = t * segments;
        int index = (int) Math.floor(scaledT);
        if (index >= segments) index = segments - 1;
        if (index < 0) index = 0;
        double localT = scaledT - index;

        return interpolateSegment(points, index, localT, type);
    }

    /**
     * 获取点，越界时 clamp 到首/末点（参照 BBS PathClip.getPoint）
     */
    private static double[] getPointClamped(List<double[]> points, int index)
    {
        int size = points.size();
        if (size == 0) return new double[]{0, 0, 0};
        if (index >= size) return points.get(size - 1);
        if (index < 0) return points.get(0);
        return points.get(index);
    }

    /**
     * 计算段 index 处、段内进度 localT 的位置
     * 使用 4 点法：p0=index-1, p1=index, p2=index+1, p3=index+2
     * 端点处用 clamp 扩展（首/末点复制）
     */
    private static double[] interpolateSegment(List<double[]> points, int index, double t, String type)
    {
        /* 4 个控制点，越界时 clamp */
        double[] p0 = getPointClamped(points, index - 1);
        double[] p1 = getPointClamped(points, index);
        double[] p2 = getPointClamped(points, index + 1);
        double[] p3 = getPointClamped(points, index + 2);

        double x, y, z;

        switch (type)
        {
            case "linear":
                x = Lerps.lerp(p1[0], p2[0], t);
                y = Lerps.lerp(p1[1], p2[1], t);
                z = Lerps.lerp(p1[2], p2[2], t);
                break;
            case "b_spline":
                x = Lerps.bSpline(p0[0], p1[0], p2[0], p3[0], t);
                y = Lerps.bSpline(p0[1], p1[1], p2[1], p3[1], t);
                z = Lerps.bSpline(p0[2], p1[2], p2[2], p3[2], t);
                break;
            case "cubic":
                x = Lerps.cubic(p0[0], p1[0], p2[0], p3[0], (float) t);
                y = Lerps.cubic(p0[1], p1[1], p2[1], p3[1], (float) t);
                z = Lerps.cubic(p0[2], p1[2], p2[2], p3[2], (float) t);
                break;
            case "catmull_rom":
            default:
                x = Lerps.cubicHermite(p0[0], p1[0], p2[0], p3[0], t);
                y = Lerps.cubicHermite(p0[1], p1[1], p2[1], p3[1], t);
                z = Lerps.cubicHermite(p0[2], p1[2], p2[2], p3[2], t);
                break;
        }

        return new double[]{x, y, z};
    }

    /**
     * 生成曲线的密集采样点（用于渲染曲线和计算长度）
     *
     * @param points            路径控制点
     * @param type              插值类型
     * @param samplesPerSegment 每段的采样数
     * @return 采样点列表
     */
    public static List<double[]> sample(List<double[]> points, String type, int samplesPerSegment)
    {
        List<double[]> samples = new ArrayList<>();
        if (points == null || points.isEmpty()) return samples;
        if (points.size() == 1)
        {
            samples.add(points.get(0).clone());
            return samples;
        }

        int segments = points.size() - 1;
        int totalSamples = segments * samplesPerSegment;

        for (int i = 0; i <= totalSamples; i++)
        {
            double t = (double) i / totalSamples;
            samples.add(interpolate(points, t, type));
        }

        return samples;
    }

    /**
     * 计算曲线总长度（用采样近似）
     */
    public static double getCurveLength(List<double[]> points, String type)
    {
        if (points == null || points.size() < 2) return 0;

        List<double[]> samples = sample(points, type, 20);
        double length = 0;
        for (int i = 1; i < samples.size(); i++)
        {
            double[] a = samples.get(i - 1);
            double[] b = samples.get(i);
            length += Math.sqrt(
                (b[0] - a[0]) * (b[0] - a[0]) +
                (b[1] - a[1]) * (b[1] - a[1]) +
                (b[2] - a[2]) * (b[2] - a[2]));
        }
        return length;
    }
}
