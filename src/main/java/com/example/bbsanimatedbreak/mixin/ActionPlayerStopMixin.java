package com.example.bbsanimatedbreak.mixin;

import com.example.bbsanimatedbreak.BlockSplashReplayHook;
import mchorse.bbs_mod.actions.ActionPlayer;
import mchorse.bbs_mod.film.Film;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ActionPlayer 停止钩子
 *
 * 在 BBS 停止回放的唯一汇聚点（ActionPlayer#stop）注入，
 * 让本插件销毁为该影片建立的物理世界与物理方块实体。
 *
 * 为什么注入 HEAD 而不是 RETURN：
 * stop() 内部会调用 DamageControl 的 stopDamage 恢复方块；
 * 我们的物理实体必须在方块恢复之前先消失，否则会被方块"顶"出来，
 * 或者在恢复瞬间读到不一致的世界状态。HEAD 注入保证顺序正确。
 *
 * 使用 try/catch 包裹：即使本插件清理失败，也绝不能影响 BBS 的回放停止。
 */
@Mixin(ActionPlayer.class)
public abstract class ActionPlayerStopMixin
{
    @Inject(method = "stop", at = @At("HEAD"))
    private void bbs$onPlaybackStopped(CallbackInfo ci)
    {
        try
        {
            Film film = ((ActionPlayer) (Object) this).film;

            BlockSplashReplayHook.onPlaybackStopped(film);
        }
        catch (Throwable t)
        {
            /* 静默：不影响 BBS 流程 */
        }
    }
}
