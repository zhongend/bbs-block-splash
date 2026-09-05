import com.example.bbsanimatedbreak.actions.physics.JoltPhysicsWorld;

/**
 * JoltPhysicsWorld 独立冒烟测试（无 Minecraft/BBS 依赖）
 *
 * 验证：native 加载 → 世界创建 → 动态方块下落 → 落地静止 →
 *       四元数旋转读取 → 句柄偏移 → 空世界计数 → 阻尼 → 静态碰撞体。
 *
 * 运行方式（Windows，JDK17）：
 *   javac -cp joltjni-win64.jar JoltSmokeTest.java ...
 *   java -cp .;joltjni-win64-sp.jar JoltSmokeTest
 */
public class JoltSmokeTest
{
    public static void main(String[] args) throws Exception
    {
        System.out.println("=== JoltSmokeTest 开始 ===");

        JoltPhysicsWorld world = new JoltPhysicsWorld(0.0, -11.0, 0.0);

        // 静态地面：y=0 平面（方块 (0,-1,0) 的顶面）
        for (int x = -3; x <= 3; x++)
        {
            for (int z = -3; z <= 3; z++)
            {
                world.addStaticBlock(x, -1, z);
            }
        }
        System.out.println("[1] 静态地面注入完成 (49 块)");

        // 动态方块：从 (0, 5, 0) 落下，带角速度
        long handle = world.createDynamicBlock(0.5, 5.0, 0.5, 1.0f, 0.8f, 0.1f);
        check(handle > 0, "动态刚体创建，handle=" + handle + "（必须 >0，验证 0 偏移约定）");
        world.setBodyDamping(handle, 0.04, 0.3);
        world.setBodyVelocity(handle, 0.5, 0, 0);
        world.setBodyAngularVelocity(handle, 3f, 1f, 2f);
        check(world.getBodyCount() == 1, "动态刚体计数 = 1");

        // 另一个方块直接叠在静态块上方静止（测休眠/接地）
        long handle2 = world.createDynamicBlock(2.5, 0.5, 2.5, 1.0f, 0.8f, 0.1f);

        // 步进 5 秒（100 tick）
        double[] pos = new double[3];
        float[] rot = new float[4];
        double[] vel = new double[3];
        for (int i = 0; i < 100; i++)
        {
            world.stepTick();

            if (i == 10)
            {
                world.getBodyVelocity(handle, vel);
                System.out.println("[2] 10 tick 后速度 = (" + vel[0] + ", " + vel[1] + ", " + vel[2] + ")，应已下落（vy<0）");
                check(vel[1] < -1.0, "方块在重力下加速下落");
            }
        }

        world.getBodyTransform(handle, pos, rot);
        System.out.println("[3] 100 tick 后位置 = (" + pos[0] + ", " + pos[1] + ", " + pos[2] + ")，应落在地面附近（y≈0.5）");
        check(Math.abs(pos[1] - 0.5) < 0.15, "方块落到地面上（y≈0.5+反弹余量）");
        check(rot[3] != 0f, "四元数已写入（w=" + rot[3] + "）");
        double qw = Math.abs(rot[3]);
        double qlen = Math.sqrt(rot[0] * rot[0] + rot[1] * rot[1] + rot[2] * rot[2] + qw * qw);
        check(Math.abs(qlen - 1.0) < 0.01, "四元数已归一化 |q|=" + qlen);

        world.getBodyVelocity(handle, vel);
        double speed = Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]);
        System.out.println("[4] 100 tick 后速度模 = " + speed + "，应接近静止");
        check(speed < 0.5, "阻尼+摩擦下方块趋于静止");

        check(world.isBodySleeping(handle), "静止方块已被 Jolt 休眠");

        // 移除后计数归零（空世界检测依据）
        world.removeBody(handle);
        world.removeBody(handle2);
        check(world.getBodyCount() == 0, "移除后动态刚体计数 = 0（空世界自动销毁依据）");

        // 位移合理性：CCD 下高速方块不穿地
        long fast = world.createDynamicBlock(0.5, 20.0, 0.5, 1.0f, 0.8f, 0.0f);
        world.setBodyVelocity(fast, 0, -25, 0);
        for (int i = 0; i < 40; i++)
        {
            world.stepTick();
        }
        world.getBodyTransform(fast, pos, rot);
        System.out.println("[5] 高速下落方块 (-25m/s) 最终 y = " + pos[1] + "，不应穿地（y>0）");
        check(pos[1] > 0.0, "CCD 防穿透：高速方块停在地面而非 y<0");

        world.close();
        check(!world.isValid(), "世界已关闭");

        System.out.println("=== 全部检查通过 ✓ ===");
    }

    private static void check(boolean ok, String what)
    {
        System.out.println((ok ? "  PASS " : "  FAIL ") + what);

        if (!ok)
        {
            System.exit(1);
        }
    }
}
