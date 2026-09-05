package com.example.bbsanimatedbreak.client;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端旋转状态管理器
 *
 * 为什么需要这个类：
 * 之前在 FallingBlockEntityRendererMixin 内部定义了 ClientRotationState 内部类，
 * 但 Mixin 包下的内部类会被 Mixin 处理器特殊处理，不能被直接引用，
 * 导致 IllegalClassLoadError 崩溃。
 *
 * 所以把客户端旋转状态管理移到这个独立的普通类中。
 *
 * === 客户端自主旋转计算（解决高刷新率卡顿） ===
 *
 * 之前的问题：DataTracker 每 tick（50ms）同步一次角度，165hz 屏幕每帧 6ms，
 * 同一 tick 内多帧渲染的角度相同，旋转看起来像 30 帧。
 *
 * 解决方案：客户端维护自己的旋转状态，每帧根据角速度累加旋转角度。
 *
 * 工作流程：
 * 1. 服务端通过 DataTracker 同步角速度（ANGULAR_VEL_X/Y/Z）
 * 2. 客户端每帧渲染时，用帧间的 tickDelta 差计算旋转增量
 *    旋转增量 = 角速度 × (当前tickDelta - 上一帧tickDelta)
 * 3. 每 tick 用服务端同步的角度校准一次，避免累积误差
 *
 * 这样 165hz 下每帧都有微小旋转增量，旋转丝滑流畅。
 */
public class ClientRotationStateManager
{
    /**
     * 客户端旋转状态缓存
     * 每个旋转方块在客户端维护自己的旋转状态，每帧更新
     */
    private static final ConcurrentHashMap<UUID, ClientRotationState> clientStates = new ConcurrentHashMap<>();

    /**
     * 客户端旋转状态
     */
    public static class ClientRotationState
    {
        /** 客户端自主累加的旋转角度（度） */
        public float rotationX, rotationY, rotationZ;
        /** 当前角速度（从服务端同步） */
        public float angularVelocityX, angularVelocityY, angularVelocityZ;
        /** 上一帧渲染时的 tickDelta（用于计算帧间增量） */
        public float lastTickDelta;
        /** 上一帧渲染时的实体 age（用于检测 tick 变化） */
        public int lastEntityAge;
        /** 是否已初始化 */
        public boolean initialized;
        /** 最后一次访问的时间（用于清理过期状态） */
        public long lastAccessTime;

        public ClientRotationState()
        {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    /**
     * 获取或创建指定实体的客户端旋转状态
     */
    public static ClientRotationState getOrCreate(UUID uuid)
    {
        ClientRotationState state = clientStates.computeIfAbsent(uuid, k -> new ClientRotationState());
        state.lastAccessTime = System.currentTimeMillis();
        return state;
    }

    /**
     * 清理过期的客户端状态（超过 5 秒未访问的）
     * 避免内存泄漏，只在状态较多时清理
     */
    public static void cleanupIfNeeded()
    {
        if (clientStates.size() > 50)
        {
            long now = System.currentTimeMillis();
            clientStates.entrySet().removeIf(entry ->
                now - entry.getValue().lastAccessTime > 5000);
        }
    }
}
