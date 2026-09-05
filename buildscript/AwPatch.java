import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * 把 fabric-api 的 transitive access widener（intermediary 命名 v2）应用到
 * tiny-remapper 重映射出的 Minecraft jar 上——手工复刻 Loom 开发环境的行为，
 * 使 javac 能按 fabric-api 运行时的可见性编译。
 *
 * 用法: java AwPatch <mc.jar> <out.jar> <accesswidener文件>
 */
public class AwPatch
{
    static Set<String> publicClasses = new HashSet<>();
    static Map<String, Set<String>> publicFields = new HashMap<>();   // class -> field name
    static Map<String, Set<String>> publicMethods = new HashMap<>();  // class -> name+desc

    public static void main(String[] args) throws Exception
    {
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        List<String> aw = Files.readAllLines(Paths.get(args[2]));

        for (String line : aw)
        {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("accessWidener")) continue;
            String[] p = line.split("\\s+");
            if (p[0].startsWith("transitive-")) p[0] = p[0].substring("transitive-".length());

            if (p[0].equals("accessible") && p[1].equals("class"))
            {
                publicClasses.add(p[2]);
            }
            else if (p[0].equals("accessible") && p[1].equals("field"))
            {
                publicFields.computeIfAbsent(p[2], k -> new HashSet<>()).add(p[3]);
            }
            else if (p[0].equals("accessible") && p[1].equals("method"))
            {
                publicMethods.computeIfAbsent(p[2], k -> new HashSet<>()).add(p[3] + p[4]);
            }
            else if (p[0].equals("extendable") && p[1].equals("class"))
            {
                publicClasses.add(p[2]);
            }
            else if (p[0].equals("extendable") && (p[1].equals("method") || p[1].equals("field")))
            {
                if (p[1].equals("method")) publicMethods.computeIfAbsent(p[2], k -> new HashSet<>()).add(p[3] + p[4]);
                else publicFields.computeIfAbsent(p[2], k -> new HashSet<>()).add(p[3]);
            }
        }

        System.out.println("classes=" + publicClasses.size() + " fields=" + publicFields.size() + " methods=" + publicMethods.size());

        try (JarInputStream jin = new JarInputStream(Files.newInputStream(in));
             JarOutputStream jout = new JarOutputStream(Files.newOutputStream(out)))
        {
            JarEntry e;
            byte[] buf = new byte[65536];
            while ((e = jin.getNextJarEntry()) != null)
            {
                String name = e.getName();
                String className = name.endsWith(".class") ? name.substring(0, name.length() - 6) : null;
                boolean touched = className != null && (publicClasses.contains(className)
                    || publicFields.containsKey(className) || publicMethods.containsKey(className));

                JarEntry oe = new JarEntry(name);
                oe.setTime(e.getTime());
                jout.putNextEntry(oe);

                if (!touched)
                {
                    int n;
                    while ((n = jin.read(buf)) > 0) jout.write(buf, 0, n);
                    jout.closeEntry();
                    continue;
                }

                byte[] data = readAll(jin);
                ClassReader cr = new ClassReader(data);
                ClassWriter cw = new ClassWriter(cr, 0);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw)
                {
                    @Override
                    public void visit(int version, int access, String n, String sig, String superName, String[] itf)
                    {
                        if (publicClasses.contains(className))
                        {
                            access |= Opcodes.ACC_PUBLIC;
                            access &= ~Opcodes.ACC_FINAL;
                        }
                        super.visit(version, access, n, sig, superName, itf);
                    }

                    @Override
                    public FieldVisitor visitField(int access, String n, String desc, String sig, Object value)
                    {
                        Set<String> s = publicFields.get(className);
                        if (s != null && s.contains(n))
                        {
                            access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
                            access |= Opcodes.ACC_PUBLIC;
                        }
                        return super.visitField(access, n, desc, sig, value);
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String n, String desc, String sig, String[] exc)
                    {
                        Set<String> s = publicMethods.get(className);
                        if (s != null && s.contains(n + desc))
                        {
                            access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
                            access |= Opcodes.ACC_PUBLIC;
                        }
                        return super.visitMethod(access, n, desc, sig, exc);
                    }
                }, 0);
                jout.write(cw.toByteArray());
                jout.closeEntry();
            }
        }
        System.out.println("patched -> " + out);
    }

    static byte[] readAll(InputStream in) throws Exception
    {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
