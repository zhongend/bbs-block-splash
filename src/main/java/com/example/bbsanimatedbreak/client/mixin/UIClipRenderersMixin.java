package com.example.bbsanimatedbreak.client.mixin;

import com.example.bbsanimatedbreak.actions.BlockSplashComboActionClip;
import com.example.bbsanimatedbreak.client.ui.combo.UIBlockSplashComboRenderer;
import mchorse.bbs_mod.ui.film.clips.renderer.UIClipRenderers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * UIClipRenderers Mixin - 注册飞溅组合的自定义渲染器
 *
 * 在 UIClipRenderers 构造函数末尾注入，把 BlockSplashComboActionClip
 * 映射到 UIBlockSplashComboRenderer。
 *
 * 这样 BBS 主轨道渲染飞溅组合 clip 时，会用我们的自定义渲染器
 * （在 clip 右半部分画子轨道预览），而不是默认渲染器。
 */
@Mixin(UIClipRenderers.class)
public abstract class UIClipRenderersMixin
{
    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs$registerComboRenderer(CallbackInfo ci)
    {
        UIClipRenderers self = (UIClipRenderers) (Object) this;

        self.register(BlockSplashComboActionClip.class, new UIBlockSplashComboRenderer());
    }
}
