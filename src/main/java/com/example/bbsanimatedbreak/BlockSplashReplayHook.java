package com.example.bbsanimatedbreak;

import com.example.bbsanimatedbreak.actions.physics.PhysicsWorldRegistry;
import mchorse.bbs_mod.film.Film;

/**
 * BBS 回放生命周期钩子
 *
 * 由 {@code ActionPlayerStopMixin} 在 BBS 的 ActionPlayer#stop() 处调用。
 *
 * === 为什么必须有一个停止钩子 ===
 * BBS 的 DamageControl 只记录「方块变更」与「ActionRecorder 录下的实体」，
 * 它<b>不会</b>记录本插件自己 spawn 的 PhysicsBlockEntity。因此回放停止时
 * BBS 把方块恢复了，但我们的物理实体仍留在世界里继续下落/堆叠：
 *   - 下一次拍摄会在同一位置生成第二套方块 → 两套方块互相挤压 → 抽搐；
 *   - 物理世界在 60 秒超时后被销毁，遗留实体仍持有已释放的 worldPtr
 *     → use-after-free → 方块瞬移到垃圾坐标 / 游戏崩溃。
 *
 * ActionPlayer#stop() 是所有回放结束路径的唯一汇聚点
 * （正常播完、编辑器停止、玩家断线、服务器关闭都走这里），
 * 因此在这里统一销毁本插件为该影片建立的全部物理世界。
 */
public final class BlockSplashReplayHook
{
    private BlockSplashReplayHook() {}

    /**
     * 影片回放停止
     *
     * @param film 停止的影片（可能为 null）
     */
    public static void onPlaybackStopped(Film film)
    {
        if (film == null)
        {
            return;
        }

        try
        {
            PhysicsWorldRegistry.destroyForFilm(film.getId());
        }
        catch (Throwable t)
        {
            /* 清理失败不应影响 BBS 自身的回放停止流程 */
        }
    }
}
