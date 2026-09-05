package com.example.bbsanimatedbreak.actions.physics;

import net.minecraft.class_2680;

/**
 * 物理方块单帧记录
 *
 * 记录某个物理方块在某个 tick 的完整状态，供后续动画回放转换使用。
 * 不可变对象，线程安全。
 */
public final class PhysicsRecording
{
    public final int tick;
    public final int blockId;
    public final double x, y, z;
    public final double vx, vy, vz;
    public final float rx, ry, rz, rw;     // 旋转四元数
    public final float avx, avy, avz;      // 角速度
    public final class_2680 blockState;
    public final boolean sleeping;

    public PhysicsRecording(int tick, int blockId,
                            double x, double y, double z,
                            double vx, double vy, double vz,
                            float rx, float ry, float rz, float rw,
                            float avx, float avy, float avz,
                            class_2680 blockState, boolean sleeping)
    {
        this.tick = tick;
        this.blockId = blockId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.rw = rw;
        this.avx = avx;
        this.avy = avy;
        this.avz = avz;
        this.blockState = blockState;
        this.sleeping = sleeping;
    }
}
