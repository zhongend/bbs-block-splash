package com.example.bbsanimatedbreak.composer;

/**
 * 确定性原语 —— 规范 §67 / §82-83
 *
 * === 第一性原理 ===
 * 回放必须可复现：同一份 composition + selection + physicsSettings + seed，
 * 无论播放多少次、在哪台机器上，模拟结果必须逐位相同。
 * 这就要求"随机"不能来自时间/全局状态，只能来自**稳定的身份**：
 *
 *     compositionId + effectId + stableBlockId + seed  →  deterministic random
 *
 * 三者都是结构性的（不是运行期的），所以任何一次运行都能重建同一个随机序列。
 *
 * 用 splitmix64 作为混合函数：
 * - 无对象分配、无内部状态（纯函数），可以放心放在热路径；
 * - 雪崩性好，相邻坐标（x, x+1）会产生完全不同的输出；
 * - 输入哪怕是 0 也不会退化（对比 xorshift 在 0 处是不动点）。
 *
 * @see BlockAttributes
 */
public final class Deterministic
{
    private Deterministic()
    {
    }

    /** splitmix64 混合（Stafford 变体 13） */
    public static long mix(long z)
    {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * 由方块坐标派生 stableBlockId
     *
     * 为什么不用 BlockPos.hashCode()：
     * - BlockPos 是可变的（mutable），且 Minecraft 的 hashCode 在不同版本间不保证稳定；
     * - 这里要的是"同一世界坐标永远对应同一 ID"，与运行环境无关。
     *
     * 编码方式与 Minecraft 的 BlockPos.asLong 一致（26/12/26 位）：
     * 世界边界 ±30,000,000 且 y ∈ [-64, 320]，各字段都在位宽内且互不重叠，
     * 因此这是一个**双射**，不会有两个不同坐标得到同一个 ID。
     */
    public static long stableBlockId(int x, int y, int z)
    {
        return ((long) x & 0x3FFFFFFL) << 38
             | ((long) z & 0x3FFFFFFL) << 12
             | ((long) y & 0xFFFL);
    }

    /** 从 stableBlockId 还原坐标（调试 / 回写方块状态时用） */
    public static int[] decodeBlockId(long id)
    {
        int x = (int) (id >> 38);
        int y = (int) (id << 52 >> 52);
        int z = (int) (id << 26 >> 38);

        return new int[] {x, y, z};
    }

    /**
     * 确定性 [0,1) 随机数
     *
     * @param seed     全局种子（整个 composition 一个）
     * @param stableId {@link #stableBlockId}
     * @param effectId 效果的稳定 ID（规范 §82）
     */
    public static float unit(long seed, long stableId, int effectId)
    {
        return (mix(seed ^ mix(stableId * 0x9E3779B97F4A7C15L + effectId)) >>> 40) / (float) 0x1000000;
    }

    /** 确定性 [-1,1) 随机数 */
    public static float signed(long seed, long stableId, int effectId)
    {
        return unit(seed, stableId, effectId) * 2F - 1F;
    }

    /**
     * 稳定字符串 ID → long
     *
     * 用于把 compositionId / effectId 这类人类可读标识转成种子。
     * 不用 String.hashCode()：它在 Java 里是规定的，但只有 32 位，
     * 且对短字符串（"a"/"b"）雪崩性差。这里用 FNV-1a 64。
     */
    public static long hashString(CharSequence s)
    {
        long h = 0xCBF29CE484222325L;

        for (int i = 0; i < s.length(); i++)
        {
            h ^= s.charAt(i);
            h *= 0x100000001B3L;
        }

        return h;
    }
}
