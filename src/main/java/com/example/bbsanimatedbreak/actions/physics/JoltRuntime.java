package com.example.bbsanimatedbreak.actions.physics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Jolt Physics（jolt-jni）进程级上下文
 *
 * 与 Wemppy4/bbs-physics-engine 使用同一个物理引擎（Jolt，经 stephengold 的
 * jolt-jni JNI 绑定）。此类负责：
 * 1. 把 jolt-jni 的原生库从 classpath 提取到磁盘并 System.load
 * 2. 初始化 Jolt 全局上下文（分配器 / 断言回调 / 对象工厂 / 类型注册）
 *
 * 失败是正常结果而非崩溃：异构平台、磁盘不可写、缺库等情况下，
 * available() 返回 false 并只记录一次日志，调用方（BlockSplashActionClip）
 * 会回退到原版下落方块路径——"缺一个功能"远好于"游戏无法启动"。
 *
 * 必须先 available() 再做任何 Jolt 调用：jolt-jni 在 registerTypes 之前
 * 调用其他 API 会直接崩溃 JVM 而不是抛异常。
 */
public final class JoltRuntime
{
    private static boolean attempted;
    private static boolean available;

    private JoltRuntime()
    {}

    /**
     * Jolt 是否可用（首次调用时初始化，之后直接返回缓存结果）
     */
    public static synchronized boolean available()
    {
        if (!attempted)
        {
            attempted = true;

            try
            {
                initialize();

                available = true;
                System.out.println("[BBS-Splash] Jolt Physics " + com.github.stephengold.joltjni.Jolt.versionString()
                    + " (" + com.github.stephengold.joltjni.Jolt.buildType() + ") 已就绪，BBS 物理引擎后端可用。");
            }
            catch (Throwable e)
            {
                System.out.println("[BBS-Splash] Jolt 初始化失败，BBS 物理引擎后端不可用，将回退到原版下落方块: "
                    + e.getClass().getName() + ": " + e.getMessage());
            }
        }

        return available;
    }

    private static void initialize() throws Exception
    {
        JoltNativeLoader.load();

        com.github.stephengold.joltjni.Jolt.registerDefaultAllocator();

        /* Jolt 的断言与诊断通过回调输出，安装默认实现让引擎内部错误可见 */
        com.github.stephengold.joltjni.Jolt.installDefaultAssertCallback();
        com.github.stephengold.joltjni.Jolt.installDefaultTraceCallback();

        if (!com.github.stephengold.joltjni.Jolt.newFactory())
        {
            throw new IllegalStateException("Jolt 无法创建对象工厂 (newFactory 返回 false)");
        }

        /* 向工厂注册所有形状/约束类型；不执行此步就创建任何形状都是未定义行为 */
        com.github.stephengold.joltjni.Jolt.registerTypes();
    }

    /**
     * jolt-jni 原生库加载器
     *
     * jolt-jni 各平台构件把原生库作为资源内嵌在 jar 内
     * （路径 {@code <system>/<cpu>/com/github/stephengold/joltjni.dll}）。
     * 生产环境下该 jar 是本模组的嵌套 jar（Fabric jar-in-jar），
     * 只有模组自身的 ClassLoader 能看到，所以从这里提取到磁盘再加载。
     * 提取目标按内容大小判断是否已存在，同版本只需提取一次。
     */
    static final class JoltNativeLoader
    {
        private JoltNativeLoader()
        {}

        static void load() throws IOException
        {
            String directory = directory();
            String name = name();
            String resource = directory + "/com/github/stephengold/" + name;

            InputStream in = JoltNativeLoader.class.getClassLoader().getResourceAsStream(resource);
            if (in == null)
            {
                throw new IOException("Jolt 原生库缺失: " + resource
                    + " (当前构建可能未打包该平台的 jolt-jni native)");
            }

            byte[] library = readAll(in);

            Path file = extract(library, directory.replace('/', '_') + "-" + name);

            System.load(file.toAbsolutePath().toString());
        }

        private static byte[] readAll(InputStream in) throws IOException
        {
            try
            {
                return in.readAllBytes();
            }
            finally
            {
                in.close();
            }
        }

        /** jolt-jni 的平台目录约定：{@code <system>/<cpu>} */
        private static String directory()
        {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

            String system;
            if (os.contains("win"))
            {
                system = "windows";
            }
            else if (os.contains("mac") || os.contains("darwin"))
            {
                system = "osx";
            }
            else if (os.contains("nux") || os.contains("nix"))
            {
                system = "linux";
            }
            else
            {
                throw new UnsupportedOperationException("没有适配 \"" + os + "\" 的 Jolt 库");
            }

            String cpu;
            switch (arch)
            {
                case "amd64":
                case "x86_64":
                case "x86-64":
                    cpu = "x86-64";
                    break;
                case "aarch64":
                case "arm64":
                    cpu = "aarch64";
                    break;
                default:
                    throw new UnsupportedOperationException("没有适配 \"" + arch + "\" 的 Jolt 库");
            }

            return system + "/" + cpu;
        }

        private static String name()
        {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

            if (os.contains("win"))
            {
                return "joltjni.dll";
            }

            return os.contains("mac") || os.contains("darwin") ? "libjoltjni.dylib" : "libjoltjni.so";
        }

        /** 提取到游戏目录 .bbsblocksplash/natives 下（同尺寸即复用），先写临时文件再原子改名 */
        private static Path extract(byte[] library, String name) throws IOException
        {
            Path directory;

            try
            {
                net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
                directory = loader.getGameDir().resolve("bbsblocksplash").resolve("natives");
            }
            catch (Throwable t)
            {
                /* 非 Fabric 环境（单元测试等）兜底到临时目录 */
                directory = Files.createTempDirectory("bbsblocksplash-natives");
            }

            Path file = directory.resolve(name);

            if (Files.isRegularFile(file) && Files.size(file) == library.length)
            {
                return file;
            }

            Files.createDirectories(directory);

            /* 先写 .part 再原子改名：两个游戏实例同时启动时，
             * 一个实例不会读到另一个实例正在写的半个文件 */
            Path partial = Files.createTempFile(directory, name, ".part");

            try
            {
                Files.write(partial, library);
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
            }
            finally
            {
                Files.deleteIfExists(partial);
            }

            return file;
        }
    }
}
