package com.example.bbsanimatedbreak.client.mixin;

import com.example.bbsanimatedbreak.client.DocumentPlaybackRenderer;
import mchorse.bbs_mod.film.BaseFilmController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 捕获 BBS 回放时钟的每帧状态（规范 §47：文档在回放时钟上无物理播放）
 *
 * {@code BaseFilmController#startRenderFrame(float)} 是编辑器预览
 * （FilmEditorController）与世界播放/视频导出（WorldFilmController）
 * **共同**的逐帧入口 —— 在 HEAD 捕获「影片 id + 回放 tick + tickDelta」，
 * DocumentPlaybackRenderer 就能在 AFTER_ENTITIES 里对独立动画文档
 * 做与回放时钟严格同步的纯关键帧渲染。
 *
 * 注意：
 * - BBS 的类没有被重映射，method 写真实名 "startRenderFrame"，全部 remap = false；
 * - film 是 public final 字段，直接转型访问（@Shadow final 字段在抽象类里无法初始化）。
 *
 * 已知取舍：同帧若有多个控制器渲染（极少见），最后一个写入者生效 ——
 * 编辑器一次只编辑一部影片，常规流程里世界播放与编辑器预览不会同帧并存。
 */
@Mixin(value = BaseFilmController.class, remap = false)
public abstract class BaseFilmControllerMixin
{
    @Inject(method = "startRenderFrame", at = @At("HEAD"), remap = false)
    private void bbs$capturePlaybackFrame(float transition, CallbackInfo ci)
    {
        BaseFilmController self = (BaseFilmController) (Object) this;

        DocumentPlaybackRenderer.onFrameStart(self.film.getId(), self.getTick(), transition);
    }
}
