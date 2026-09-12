package com.example.bbsanimatedbreak.actions.physics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * 原生库加载器
 *
 * 从 JAR 内的 /natives/{os}/ 目录提取 .dll/.so/.dylib 到稳定路径，然后 System.load。
 * 参考 Sable 的 NativeLibraryLoader 实现。
 *
 * ⚠️ 铁律：一个原生库在进程里只允许加载一次。
 *
 * 旧实现用 {@code createTempFile(name + "_" + System.nanoTime(), ext)} 每次都生成
 * 一个新路径，于是每次调用都会把同一个 DLL 再加载一份。JNI 原生库不是普通的资源：
 * 同一份代码在进程里出现多份，静态全局状态与 JNI 符号绑定就会互相错位 ——
 * 轻则各副本状态不同步，重则和 JoltRuntime 遇到的一样直接 EXCEPTION_ACCESS_VIOLATION
 * （而且 Java 层 try/catch 接不住，见 hs_err_pid7064.log 的教训）。
 *
 * 所以这里改成：
 *  ① 进程内用 Set 记录已加载的库名，重复 load 直接返回；
 *  ② 提取目标用**稳定文件名**（而不是 nanoTime），这样 JVM 按路径去重，
 *     即便重复 System.load 同一路径也是安全的 no-op；
 *  ③ 用内容长度判断是否需要重新提取（同版本直接复用，与 JoltRuntime 相同的策略）。
 */
public class NativeLibraryLoader
{
    /** 已在本进程加载过的库名（原生库只加载一次） */
    private static final Set<String> LOADED = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * 加载原生库
     *
     * 优先从 java.library.path 加载（测试用，直接指向 target/release 目录），
     * 失败则从 JAR 内的 /natives/{os}/ 提取到稳定路径加载（生产用）。
     *
     * @param name 库名（不含扩展名），如 "bbs_physics"
     */
    public static synchronized void load(String name) {
        /* 幂等闸门：同一进程里同一个库只加载一次 */
        if (LOADED.contains(name)) {
            return;
        }

        // 1. 先尝试 System.loadLibrary（使用 java.library.path，测试环境）
        try {
            System.loadLibrary(name);
            LOADED.add(name);
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
            Path file = extractionTarget(name, ext);

            byte[] library = readAll(in);
            in.close();

            /* 同尺寸即复用：同版本不需要重新提取 */
            if (!(Files.isRegularFile(file) && Files.size(file) == library.length)) {
                Files.createDirectories(file.getParent());

                /* 先写 .part 再原子改名：两个游戏实例同时启动时，
                 * 一个实例不会读到另一个实例正在写的半个文件 */
                Path partial = Files.createTempFile(file.getParent(), name, ".part");

                try {
                    Files.write(partial, library);
                    Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(partial);
                }
            }

            System.load(file.toAbsolutePath().toString());
            LOADED.add(name);
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract native library '" + name
                + "': " + e.getMessage());
        }
    }

    /**
     * 提取目标路径 —— 必须**稳定**
     *
     * 旧实现用 nanoTime 生成临时文件名，导致每次调用都加载一份新副本。
     * 这里改成游戏目录下的固定文件名（.bbsblocksplash/natives/<os>_<name>.<ext>），
     * JVM 按路径去重，同一文件重复 System.load 是安全的 no-op。
     */
    private static Path extractionTarget(String name, String ext) throws IOException {
        Path directory;

        try {
            net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            directory = loader.getGameDir().resolve("bbsblocksplash").resolve("natives");
        } catch (Throwable t) {
            /* 非 Fabric 环境（单元测试等）兜底到临时目录 */
            directory = Files.createTempDirectory("bbsblocksplash-natives");
        }

        String osName = System.getProperty("os.name").toLowerCase();

        String osTag = osName.contains("win") ? "windows"
                     : osName.contains("mac") ? "macos" : "linux";

        return directory.resolve(osTag + "_" + name + ext);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            return in.readAllBytes();
        } finally {
            in.close();
        }
    }
}
