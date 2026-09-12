package com.example.bbsanimatedbreak.actions.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 物理记录管理器（内存缓存）
 *
 * 当 sable 物理开启时，每个 PhysicsBlockEntity 每 tick 调用 record() 记录状态。
 * 后续的动画回放转换功能可通过 getRecording() 读取这些数据。
 *
 * 记录按 replay UUID 分组，回放结束时清理对应记录。
 * 全部存储在内存中，不写文件，不污染存档。
 */
public final class PhysicsRecordingManager
{
    /**
     * 每个回放的记录条数上限
     *
     * 记录粒度是「每方块每 tick 一条」，而目前没有任何消费者（是为后续"物理烘焙成
     * 关键帧"预留的）。若不设上限，300 方块 × 长回放会产生千万级不可变对象，
     * 堆内存持续增长直至 OOM。达到上限后停止记录：既保住"可烘焙"的用途，
     * 又让内存占用有界（12 万条 ≈ 14 MB/回放）。
     */
    private static final int MAX_RECORDS_PER_REPLAY = 120_000;

    /** 按 replayId 分组的记录列表 */
    private static final Map<UUID, List<PhysicsRecording>> recordings = new ConcurrentHashMap<>();

    /** 每个 replayId 的 blockId 计数器 */
    private static final Map<UUID, AtomicInteger> blockIdCounters = new ConcurrentHashMap<>();

    private PhysicsRecordingManager() {}

    /**
     * 为指定 replay 分配下一个 blockId
     */
    public static int nextBlockId(UUID replayId)
    {
        return blockIdCounters.computeIfAbsent(replayId, k -> new AtomicInteger(0)).getAndIncrement();
    }

    /**
     * 记录一个物理方块在某 tick 的状态
     */
    public static void record(UUID replayId, PhysicsRecording recording)
    {
        List<PhysicsRecording> list = recordings.computeIfAbsent(
            replayId, k -> Collections.synchronizedList(new ArrayList<>()));

        if (list.size() >= MAX_RECORDS_PER_REPLAY)
        {
            return; /* 已达上限：停止记录，避免无界增长 */
        }

        list.add(recording);
    }

    /**
     * 获取指定 replay 的所有物理记录
     *
     * @return 不可变列表，若无记录返回空列表
     */
    public static List<PhysicsRecording> getRecording(UUID replayId)
    {
        List<PhysicsRecording> list = recordings.get(replayId);
        return list != null ? Collections.unmodifiableList(new ArrayList<>(list)) : Collections.emptyList();
    }

    /**
     * 清理指定 replay 的所有记录
     */
    public static void clearRecording(UUID replayId)
    {
        recordings.remove(replayId);
        blockIdCounters.remove(replayId);
    }

    /**
     * 清理所有记录
     */
    public static void clearAll()
    {
        recordings.clear();
        blockIdCounters.clear();
    }
}
