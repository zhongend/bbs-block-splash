package com.example.bbsanimatedbreak.actions.physics;

/**
 * Rapier3d 原生物理库 JNI 绑定
 *
 * 所有 native 方法通过 bbs_physics.dll (Rust编译) 实现。
 * 原生库在模组初始化时加载一次。
 *
 * 参考 Sable (https://github.com/ryanhcode/sable) 和 rigid-body (https://github.com/Polari-Stars-MC/rigid-body)
 * 使用 rapier3d-f64 0.33.0 提供真实刚体物理模拟。
 *
 * 关键特性：
 * - PGS 约束求解器 → 稳定堆叠，无方块聚团
 * - 库仑摩擦锥 → 真实摩擦和滚动
 * - 接触流形（多点接触）→ 无穿透
 * - IslandManager 休眠 → 自动休眠，无悬浮
 * - CCD（连续碰撞检测）→ 高速无穿透
 */
public class NativePhysicsLibrary
{
    private static boolean loaded = false;

    // === 世界管理 ===
    public static native long nCreateWorld(double gx, double gy, double gz);
    public static native void nDestroyWorld(long worldPtr);
    public static native void nStep(long worldPtr, double dt);
    public static native void nClearWorld(long worldPtr);

    // === 静态碰撞体（Minecraft 方块）===
    public static native void nAddStaticBlock(long worldPtr, int x, int y, int z);
    public static native void nAddStaticBox(long worldPtr, double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

    // === 动态刚体（物理方块）===
    public static native long nCreateDynamicBlock(long worldPtr, double x, double y, double z, float mass, float friction, float restitution);
    public static native void nRemoveBody(long worldPtr, long bodyHandle);
    public static native int nGetBodyCount(long worldPtr);

    // === 刚体状态查询 ===
    public static native void nGetBodyTransform(long worldPtr, long bodyHandle, double[] pos, float[] rot);
    public static native void nGetBodyVelocity(long worldPtr, long bodyHandle, double[] vel);
    public static native void nGetBodyAngularVelocity(long worldPtr, long bodyHandle, float[] angVel);
    public static native boolean nIsBodySleeping(long worldPtr, long bodyHandle);
    public static native void nWakeUpBody(long worldPtr, long bodyHandle);

    // === 刚体状态设置 ===
    public static native void nSetBodyVelocity(long worldPtr, long bodyHandle, double vx, double vy, double vz);
    public static native void nSetBodyAngularVelocity(long worldPtr, long bodyHandle, float ax, float ay, float az);
    public static native void nApplyImpulse(long worldPtr, long bodyHandle, double ix, double iy, double iz);
    public static native void nSetBodyTransform(long worldPtr, long bodyHandle, double x, double y, double z, float qx, float qy, float qz, float qw);
    public static native void nSetBodyDamping(long worldPtr, long bodyHandle, double linear, double angular);

    // === 库加载 ===
    public static synchronized void load() {
        if (loaded) return;
        NativeLibraryLoader.load("bbs_physics");
        loaded = true;
    }

    public static boolean isLoaded() { return loaded; }
}
