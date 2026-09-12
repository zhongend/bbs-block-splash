package com.example.bbsanimatedbreak.composer.anim;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 动画文档仓库 —— 规范 §三十二~§三十三、§九十五~§九十七
 *
 * === 职责边界（规范 §九十二~§九十三，这是最容易混的一点）===
 *
 *     Physics Cache      = 计算缓存（TrajectoryStore，随模拟生灭）
 *     Animation Document = 创作资产（这里，长期保存）
 *
 * 两者**不能混**。混了会发生什么：改一下物理参数，作者手工调过的关键帧
 * 就跟着变了 —— 那是非破坏式工作流的反面（规范 §三十七）。
 *
 * === 保存位置 ===
 * {@code <gameDir>/bbsblocksplash/animations/<filmId>.bsanim}
 *
 * 与影片解耦但按影片归档：一个影片可以有自己的动画文档，
 * 而文档本身是独立文件（规范 §三十二），可以 Save As / Duplicate / 单独备份。
 */
public final class AnimationDocumentStore
{
    private static final Map<String, BlockSplashAnimationDocument> cache = new ConcurrentHashMap<>();

    private AnimationDocumentStore()
    {
    }

    private static Path directory()
    {
        try
        {
            return FabricLoader.getInstance().getGameDir()
                .resolve("bbsblocksplash").resolve("animations");
        }
        catch (Throwable t)
        {
            /* 非 Fabric 环境兜底 */
            try
            {
                return Files.createTempDirectory("bbsblocksplash-anim");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private static Path fileFor(String filmId)
    {
        String safe = filmId == null ? "untitled" : filmId.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_");

        return directory().resolve(safe + ".bsanim");
    }

    /** 读取（带内存缓存：编辑器打开/关闭不反复走磁盘） */
    public static synchronized BlockSplashAnimationDocument load(String filmId)
    {
        BlockSplashAnimationDocument cached = cache.get(String.valueOf(filmId));

        if (cached != null)
        {
            return cached;
        }

        Path file = fileFor(filmId);

        if (!Files.isRegularFile(file))
        {
            return null;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file))))
        {
            BlockSplashAnimationDocument doc = BlockSplashAnimationDocument.read(in);

            cache.put(String.valueOf(filmId), doc);

            return doc;
        }
        catch (IOException e)
        {
            System.out.println("[BBS-Splash] 读取动画文档失败 " + file + ": " + e);
            return null;
        }
    }

    /** 保存（先写 .part 再原子改名，与 JoltRuntime 的提取策略一致） */
    public static synchronized void save(String filmId, BlockSplashAnimationDocument doc) throws IOException
    {
        Path file = fileFor(filmId);

        Files.createDirectories(file.getParent());

        Path partial = Files.createTempFile(file.getParent(), filmId, ".part");

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(partial))))
        {
            doc.write(out);
        }

        Files.move(partial, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        cache.put(String.valueOf(filmId), doc);
    }

    /** 现有文档（没有就建一个空的），烘焙流程用它拿"当前要挂图层的文档" */
    public static BlockSplashAnimationDocument getOrCreate(String filmId, String compositionId)
    {
        BlockSplashAnimationDocument doc = load(filmId);

        if (doc == null)
        {
            doc = new BlockSplashAnimationDocument();
            doc.documentId = String.valueOf(filmId);
            doc.sourceCompositionId = compositionId == null ? "" : compositionId;
        }

        if (doc.documentId == null || doc.documentId.isEmpty())
        {
            doc.documentId = String.valueOf(filmId);
        }

        return doc;
    }

    /** 已保存的文档数（调试/统计） */
    public static int cachedCount()
    {
        return cache.size();
    }

    /** 某影片的图层摘要（UI 用） */
    public static List<String> layerSummary(BlockSplashAnimationDocument doc)
    {
        List<String> lines = new ArrayList<>();

        if (doc == null)
        {
            return lines;
        }

        for (BlockSplashAnimationDocument.AnimationLayer layer : doc.layers)
        {
            int tracks = 0;

            for (BlockSplashAnimationDocument.BlockTrack track : doc.tracks)
            {
                if (track.layer == doc.layers.indexOf(layer))
                {
                    tracks++;
                }
            }

            lines.add(layer.name + " (" + tracks + " tracks"
                + (layer.muted ? ", muted" : "")
                + (layer.locked ? ", locked" : "") + ")");
        }

        return lines;
    }
}
