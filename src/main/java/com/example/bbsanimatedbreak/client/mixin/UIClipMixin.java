package com.example.bbsanimatedbreak.client.mixin;

import com.example.bbsanimatedbreak.actions.BlockSplashComboActionClip;
import com.example.bbsanimatedbreak.client.ui.combo.UIBlockSplashComboActionClip;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * UIClip Mixin - 注册飞溅组合的 UI 面板工厂
 *
 * UIClip 有一个静态 FACTORIES map，把 Clip 类映射到 UI 工厂。
 * BBS 内置的 ActionClip 在 static 块里注册。
 * 这里在构造函数 RETURN 注入（只执行一次），把 BlockSplashComboActionClip
 * 映射到 UIBlockSplashComboActionClip。
 *
 * 注意：不能用 @At("HEAD")，因为构造函数 super() 调用前
 * 不允许实例方法注入（必须 static），改用 @At("RETURN")。
 */
@Mixin(UIClip.class)
public abstract class UIClipMixin
{
    @Unique
    private static boolean bbs$comboRegistered = false;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs$registerComboUI(CallbackInfo ci)
    {
        if (!bbs$comboRegistered)
        {
            bbs$comboRegistered = true;
            UIClip.register(BlockSplashComboActionClip.class, UIBlockSplashComboActionClip::new);
        }
    }
}
