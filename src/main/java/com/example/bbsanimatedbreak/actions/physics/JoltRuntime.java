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
 *
 * ⚠️ 铁律：同一进程里绝不允许出现第二份 joltjni（详见 available() 的注释）。
 * 如果别的模组（bbs_physics）已经把 Jolt 加载并初始化好了，本模组必须复用，
 * 绝不重复 System.load，也绝不重建 factory —— 否则 JVM 原生崩溃且无法捕获。
 */
public final class JoltRuntime
{
    private static boolean attempted;
    private static boolean available;

    private JoltRuntime()
    {}

    /**
     * Jolt 是否可用（首次调用时初始化，之后直接返回缓存结果）
     *
     * ⚠️⚠️ 本次修复的关键：同一进程里绝不允许出现第二份 joltjni ⚠️⚠️
     *
     * 场景：用户同时安装了 Wemppy4 的 BBS 物理引擎（bbs_physics）。
     * 它在启动时就把 joltjni.dll 加载进 JVM 并完成了完整初始化
     * （allocator / callbacks / newFactory / registerTypes）。
     * 如果本模组再 System.load 一份同一个 DLL：
     *
     *   ① 进程里出现两份 joltjni 的原生代码，各自持有独立的 C++ 全局状态；
     *   ② JVM 对静态 native 方法的符号解析会在这些库之间二选一；
     *   ③ 结果是"Java 侧 newFactory/registerTypes 作用在 A 份上，
     *      而 MassProperties.createMassProperties() 这类静态 native
     *      被绑定到 B 份"—— 两边状态互不知晓；
     *   ④ 调用即跳到空函数指针 → EXCEPTION_ACCESS_VIOLATION at 0x0，
     *      **JVM 直接崩溃，Java 层 try/catch 根本接不住**。
     *
     * 这正是用户报告的 hs_err_pid7064.log 的崩溃点：
     *   j  ...joltjni.MassProperties.createMassProperties()J+0
     *   j  ...joltjni.MassProperties.<init>()V+4
     *   j  ...JoltPhysicsWorld.createDynamicBlock(DDDFFF)J+78
     *
     * 所以这里改成**先探测、后决策**：
     *   - 原生库已在进程内 → 复用，绝不重复 System.load，也绝不重建 factory；
     *   - 原生库不在 → 我们自己加载并完成完整初始化（独自安装本模组时的正常路径）。
     *
     * 探测手段：Jolt.versionString() 是一个**静态 native** 且只读一个版本字符串，
     * 不依赖任何全局初始化状态（不需要 factory/types）。库没加载时它抛
     * UnsatisfiedLinkError，加载了则一定成功 —— 这是唯一既便宜又安全的探测点。
     */
    public static synchronized boolean available()
    {
        if (!attempted)
        {
            attempted = true;

            try
            {
                boolean reused = isNativeAlreadyInProcess();

                if (reused)
                {
                    /* 别的模组（通常是 bbs_physics）已经加载并初始化了 Jolt。
                     * 它的 available() 在客户端启动阶段就把整个序列跑完了，
                     * 这里绝不能重复 —— 重复就是本次崩溃的根因。 */
                    System.out.println("[BBS-Splash] 检测到 JVM 里已有 Jolt 运行时（可能是 bbs_physics 加载的），"
                        + "本模组复用 " + com.github.stephengold.joltjni.Jolt.versionString()
                        + " (" + com.github.stephengold.joltjni.Jolt.buildType() + ")，不重复加载。");
                }
                else
                {
                    initialize();

                    System.out.println("[BBS-Splash] Jolt Physics " + com.github.stephengold.joltjni.Jolt.versionString()
                        + " (" + com.github.stephengold.joltjni.Jolt.buildType() + ") 已就绪，BBS 物理引擎后端可用。");
                }

                available = true;
            }
            catch (Throwable e)
            {
                System.out.println("[BBS-Splash] Jolt 初始化失败，BBS 物理引擎后端不可用，将回退到原版下落方块: "
                    + e.getClass().getName() + ": " + e.getMessage());
            }
        }

        return available;
    }

    /**
     * Jolt 原生库是否已经被本进程加载（无论加载者是本模组还是其它模组）
     *
     * 用 versionString() 探测的理由：它是静态 native，JVM 用懒绑定 ——
     * 库没加载时抛 UnsatisfiedLinkError；加载了则一定成功。
     * 而且它只读一个常量字符串，不触碰 allocator/factory/types 这些全局状态，
     * 所以在"还没初始化"或"已由别的模组初始化"两种情况下都是安全的。
     */
    private static boolean isNativeAlreadyInProcess()
    {
        try
        {
            com.github.stephengold.joltjni.Jolt.versionString();
            com.github.stephengold.joltjni.Jolt.buildType();

            return true;
        }
        catch (Throwable t)
        {
            /* UnsatisfiedLinkError / NoClassDefFoundError：进程里还没有可用的 Jolt 原生库 */
            return false;
        }
    }

    /**
     * 完整初始化序列
     *
     * 顺序与 Wemppy4/bbs-physics-engine 的 JoltEngine 完全一致 ——
     * 这不是巧合，而是 jolt-jni 的硬性要求：registerTypes() 之前创建任何形状
     * 都是未定义行为（会崩溃 JVM 而不是抛异常）。
     */
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
