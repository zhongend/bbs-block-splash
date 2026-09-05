package com.example.bbsanimatedbreak.actions.physics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 原生库加载器
 *
 * 从 JAR 内的 /natives/{os}/ 目录提取 .dll/.so/.dylib 到临时目录，然后 System.load。
 * 参考 Sable 的 NativeLibraryLoader 实现。
 */
public class NativeLibraryLoader
{
    /**
     * 加载原生库
     *
     * 优先从 java.library.path 加载（测试用，直接指向 target/release 目录），
     * 失败则从 JAR 内的 /natives/{os}/ 提取到临时目录加载（生产用）。
     *
     * @param name 库名（不含扩展名），如 "bbs_physics"
     */
    public static void load(String name) {
        // 1. 先尝试 System.loadLibrary（使用 java.library.path，测试环境）
        try {
            System.loadLibrary(name);
            return;
        } catch (UnsatisfiedLinkError ignored) {
            // java.library.path 中没找到，继续尝试从 JAR 提取
        }

        // 2. 从 JAR 资源提取（生产环境）
        String osName = System.getProperty("os.name").toLowerCase();
        String dir, ext;

        if (osName.contains("win")) {
            dir = "windows";
            ext = ".dll";
        } else if (osName.contains("mac")) {
            dir = "macos";
            ext = ".dylib";
        } else {
            dir = "linux";
            ext = ".so";
        }

        String resourcePath = "/natives/" + dir + "/" + name + ext;
        InputStream in = NativeLibraryLoader.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new UnsatisfiedLinkError("Native library not found: " + resourcePath
                + " (os=" + osName + ", also not in java.library.path)");
        }

        try {
            Path temp = Files.createTempFile(name + "_" + System.nanoTime(), ext);
            temp.toFile().deleteOnExit();
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            in.close();
            System.load(temp.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract native library '" + name
                + "': " + e.getMessage());
        }
    }
}
