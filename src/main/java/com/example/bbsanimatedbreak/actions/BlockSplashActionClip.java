package com.example.bbsanimatedbreak.actions;

import com.example.bbsanimatedbreak.BlockSplashAnimationScheduler;
import com.example.bbsanimatedbreak.BlockSplashRecoveryManager;
import com.example.bbsanimatedbreak.FallingBlockRotationData;
import com.example.bbsanimatedbreak.RotatingFallingBlockManager;
import com.example.bbsanimatedbreak.actions.combo.IBlockFilterable;
import com.example.bbsanimatedbreak.actions.physics.NativePhysicsWorld;
import com.example.bbsanimatedbreak.actions.physics.PhysicsBlockEntity;
import com.example.bbsanimatedbreak.actions.physics.PhysicsRecordingManager;
import com.example.bbsanimatedbreak.actions.physics.PhysicsWorldRegistry;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueDouble;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.class_1309;
import net.minecraft.class_1540;
import net.minecraft.class_2338;
import net.minecraft.class_2680;
import net.minecraft.class_3218;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 区域方块飞溅 ActionClip
 *
 * 在回放到此片段时，把区域内（x,y,z 到 x2,y2,z2）的所有非空气方块
 * 转换成 FallingBlockEntity（下落方块实体），并赋予按方向+强度+形状计算的初速度，
 * 产生和原版 TNT 炸沙子一样的方块飞溅效果。
 *
 * 参数：
 * - x/y/z：区域第一个对角点
 * - x2/y2/z2：区域第二个对角点
 * - power：飞溅强度（控制初速度大小）
 * - dirX/dirY/dirZ：飞溅方向（会被归一化）
 * - collision：是否启用实体碰撞（避免方块重叠穿透）
 * - shape：飞溅形状（ray=射线型, spiral=螺旋型, sphere=球型, arc=弧型）
 *
 * 恢复机制：spawnFromBlock 内部调用 world.setBlockState 把原方块设为空气，
 * 会被 BBS 的 WorldMixin 拦截并记录到 DamageControl，
 * 回放结束时 DamageControl.restore() 会自动恢复方块原状。
 * 生成的 FallingBlockEntity 也会被 DamageControl 记录并在回放结束时移除。
 */
public class BlockSplashActionClip extends ActionClip implements IBlockFilterable
{
    /* 方块过滤器（由飞溅组合设置，null 表示不过滤） */
    private Set<class_2338> blockFilter = null;
    /* 区域第一个对角点 */
    public final ValueInt x = new ValueInt("x", 0);
    public final ValueInt y = new ValueInt("y", 0);
    public final ValueInt z = new ValueInt("z", 0);

    /* 区域第二个对角点 */
    public final ValueInt x2 = new ValueInt("x2", 0);
    public final ValueInt y2 = new ValueInt("y2", 0);
    public final ValueInt z2 = new ValueInt("z2", 0);

    /* 飞溅强度（控制初速度，0.5=轻微, 1.0=中等, 2.0=猛烈） */
    public final ValueDouble power = new ValueDouble("power", 1.0D, 0D, 5D);

    /* 飞溅方向（会被归一化） */
    public final ValueDouble dirX = new ValueDouble("dirX", 0D);
    public final ValueDouble dirY = new ValueDouble("dirY", 1D);
    public final ValueDouble dirZ = new ValueDouble("dirZ", 0D);

    /* 是否启用实体碰撞（避免方块重叠穿透和瞬移） */
    public final ValueBoolean collision = new ValueBoolean("collision", true);

    /* 飞溅形状：ray=射线型, spiral=螺旋型, sphere=球型, arc=弧型 */
    public final ValueString shape = new ValueString("shape", "ray");

    /* 是否启用旋转物理（开启后方块在飞行中会旋转） */
    public final ValueBoolean rotation = new ValueBoolean("rotation", false);

    /* 归位旋转平滑过渡（true=接近地面时旋转逐渐减速并归零，避免角度闪现） */
    public final ValueBoolean smoothRotationStop = new ValueBoolean("smoothRotationStop", true);

    /* 旋转减速距离（在距离地面多远开始减速旋转，1.5=近, 3=中, 5=远） */
    public final ValueDouble rotationStopDistance = new ValueDouble("rotationStopDistance", 3D, 0.5D, 10D);

    /* 归零动画持续时间（tick，控制角度归零的插值速度，40=2秒，默认值足以修复角度闪现） */
    public final ValueInt rotationResetDuration = new ValueInt("rotationResetDuration", 40, 5, 200);

    /* 是否方块实体化（true=飞溅后变回实体方块，false=保持动画形式，物理模拟后缩小消失）
     * 默认 false：方块飞溅后保持动画形式，依据真实物理模拟旋转移动，
     * 到时间后用非线性动画丝滑缩小消失 */
    public final ValueBoolean solidify = new ValueBoolean("solidify", false);

    /* 方块动画持续时间（秒，仅在 solidify=false 时生效）
     * 控制方块保持动画形态的总时长，到达时间后开始非线性缩小消失
     * 默认 60 秒，上限 9999 秒 */
    public final ValueDouble animationDuration = new ValueDouble("animationDuration", 60D, 0D, 9999D);

    /* 是否启用 Sable 物理模拟（默认 true）
     * 开启时使用 PhysicsBlockEntity 跑真实的刚体物理（重力 + AABB 碰撞 + 弹跳 + 四元数旋转），
     * 关闭时回退到原版 FallingBlockEntity（仅有简单的下落 + 落地变方块）。
     * Sable 物理开启时会自动记录每帧状态到 PhysicsRecordingManager，供后续动画回放转换使用。 */
    public final ValueBoolean sableEnabled = new ValueBoolean("sable", true);

    /* === Sable 物理自定义参数 === */

    /* 自定义重力 Y（m/s²，向下为负，默认 -11.0 = Sable 调校值，-9.8 = 现实地球，-24 = 月球轻飘）
     * 仅在 sable=true 时生效 */
    public final ValueDouble gravityY = new ValueDouble("gravityY", -11.0D, -50D, 50D);

    /* 自定义重力 X（m/s²，通常为 0，可用于横向力效果如风） */
    public final ValueDouble gravityX = new ValueDouble("gravityX", 0.0D, -50D, 50D);

    /* 自定义重力 Z（m/s²，通常为 0，可用于横向力效果如风） */
    public final ValueDouble gravityZ = new ValueDouble("gravityZ", 0.0D, -50D, 50D);

    /* 线性阻尼（0.0=无阻尼永远飞，0.04=轻微空气阻力，0.09=Sable 默认，0.2=强阻力）
     * 越低方块飞得越远，冲击力越强 */
    public final ValueDouble linearDamping = new ValueDouble("linearDamping", 0.04D, 0D, 1D);

    /* 角阻尼（0.0=永转，0.3=缓慢减速，0.5=Sable 默认，1.0=快速停止旋转）
     * 越低方块旋转越持久 */
    public final ValueDouble angularDamping = new ValueDouble("angularDamping", 0.3D, 0D, 2D);

    /* 冲击力倍率（1.0=原始力度，2.0=双倍冲击，0.5=柔和飞溅）
     * 直接乘到初速度上，让飞溅更猛烈或更温和 */
    public final ValueDouble impactBoost = new ValueDouble("impactBoost", 1.5D, 0.1D, 5D);

    /* 最大物理方块数（超过此数量时采样，默认 300，性能与效果平衡）
     * 未被采样的方块会被设为空气消失，不会残留为实体方块
     * i3-10105 等中端 CPU 建议 300，高端 CPU 可调到 500-1000 */
    public final ValueInt maxPhysicsBlocks = new ValueInt("maxPhysicsBlocks", 300, 50, 2000);

    /* 静态碰撞体注入半径（飞溅区域周围多少格内的方块作为地面/墙壁，默认 8）
     * 越大越防遁地，但消耗更多内存 */
    public final ValueInt collisionRadius = new ValueInt("collisionRadius", 8, 3, 20);

    public BlockSplashActionClip()
    {
        super();

        this.add(this.x);
        this.add(this.y);
        this.add(this.z);
        this.add(this.x2);
        this.add(this.y2);
        this.add(this.z2);
        this.add(this.power);
        this.add(this.dirX);
        this.add(this.dirY);
        this.add(this.dirZ);
        this.add(this.collision);
        this.add(this.shape);
        this.add(this.rotation);
        this.add(this.smoothRotationStop);
        this.add(this.rotationStopDistance);
        this.add(this.rotationResetDuration);
        this.add(this.solidify);
        this.add(this.animationDuration);
        this.add(this.sableEnabled);
        this.add(this.gravityX);
        this.add(this.gravityY);
        this.add(this.gravityZ);
        this.add(this.linearDamping);
        this.add(this.angularDamping);
        this.add(this.impactBoost);
        this.add(this.maxPhysicsBlocks);
        this.add(this.collisionRadius);
    }

    @Override
    public void applyAction(class_1309 entity, SuperFakePlayer fakePlayer, Film film, Replay replay, int tick)
    {
        // 使用 fakePlayer 获取世界，因为 entity 可能为 null
        if (fakePlayer == null || fakePlayer.method_37908() == null)
        {
            return;
        }

        if (!(fakePlayer.method_37908() instanceof class_3218))
        {
            return;
        }

        class_3218 world = (class_3218) fakePlayer.method_37908();

        // 收集区域内所有方块坐标
        List<class_2338> blocks = this.collectBlocksInArea(
            this.x.get(), this.y.get(), this.z.get(),
            this.x2.get(), this.y2.get(), this.z2.get()
        );

        // 限制区域大小，防止一次生成过多实体导致卡顿（最多 30000 个方块）
        if (blocks.isEmpty() || blocks.size() > 30000)
        {
            return;
        }

        double power = this.power.get().doubleValue();

        // 归一化方向向量（主飞溅方向）
        double dx = this.dirX.get();
        double dy = this.dirY.get();
        double dz = this.dirZ.get();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-6)
        {
            dx = 0; dy = 1; dz = 0;
        }
        else
        {
            dx /= length; dy /= length; dz /= length;
        }

        // 读取碰撞、形状和旋转设置
        boolean enableCollision = (Boolean) this.collision.get();
        String splashShape = (String) this.shape.get();
        boolean enableRotation = (Boolean) this.rotation.get();
        boolean smoothRotationStop = (Boolean) this.smoothRotationStop.get();
        double rotationStopDistance = this.rotationStopDistance.get().doubleValue();
        int rotationResetDuration = this.rotationResetDuration.get();
        boolean doSolidify = (Boolean) this.solidify.get();
        double animDurationSec = this.animationDuration.get();
        boolean sableEnabled = (Boolean) this.sableEnabled.get();

        // === Sable 物理记录：根据 replay id 派生确定性 UUID 作为记录分组键 ===
        // 仅当 sable 开启时分配，避免无谓的内存占用
        UUID recordingId = null;
        if (sableEnabled && replay != null && replay.getId() != null)
        {
            recordingId = UUID.nameUUIDFromBytes(replay.getId().getBytes(StandardCharsets.UTF_8));
        }

        // 计算区域中心点，用于生成从中心向外的扩散力
        double centerX = 0, centerY = 0, centerZ = 0;
        for (class_2338 p : blocks)
        {
            centerX += p.method_10263();
            centerY += p.method_10264();
            centerZ += p.method_10260();
        }
        centerX /= blocks.size();
        centerY /= blocks.size();
        centerZ /= blocks.size();

        // 用固定随机种子保证回放一致性
        Random random = new Random(42L);

        if (sableEnabled)
        {
            // ================================================================
            // === Sable 原生物理模式（Rapier3d） ===
            // ================================================================

            // 读取自定义物理参数
            double gx = this.gravityX.get();
            double gy = this.gravityY.get();
            double gz = this.gravityZ.get();
            double linDamp = this.linearDamping.get();
            double angDamp = this.angularDamping.get();
            double impactMult = this.impactBoost.get();
            int maxBlocks = this.maxPhysicsBlocks.get();
            int collRadius = this.collisionRadius.get();

            // 1. 创建原生物理世界（自定义重力）
            // 同时传入 recordingId 和 dimensionKey，让 PhysicsWorldRegistry 在
            // 销毁世界时连带清理 PhysicsRecording 和 BlockSplashRecoveryManager 记录，
            // 修复"回放次数越多越卡"的资源泄漏。
            UUID worldId = UUID.randomUUID();
            NativePhysicsWorld physicsWorld = PhysicsWorldRegistry.createWorld(
                worldId, gx, gy, gz,
                recordingId,
                world.method_27983()
            );

            // 2. 密度优化：大区域抽样，最多 maxBlocks 个动态方块
            List<class_2338> sampledBlocks = this.sampleBlocksForDensity(blocks, maxBlocks);
            int totalBlocks = sampledBlocks.size();

            // 3. 先把区域内所有非空气方块记录并设为空气（包括未被采样的），
            //    防止"有的方块动画化，有的还是实体方块"的 bug
            //    用临时 Map 保存原始 BlockState 供步骤 5 使用
            java.util.Map<class_2338, class_2680> originalStates = new java.util.HashMap<>();
            for (class_2338 pos : blocks)
            {
                try
                {
                    class_2680 state = world.method_8320(pos);
                    if (!state.method_26215() && state.method_26204().method_36555() >= 0)
                    {
                        BlockSplashRecoveryManager.recordBlock(world, pos, state);
                        originalStates.put(pos, state);
                        class_2680 fluidState = state.method_26227().method_15759();
                        world.method_8652(pos, fluidState, 3);
                    }
                }
                catch (Exception e) { /* 忽略单个方块错误 */ }
            }

            // 4. 注入静态碰撞体（飞溅区域周围 collRadius 格内的非空气方块作为地面/墙壁）
            //    用采样方块列表遍历以优化性能（未采样的方块已设为空气，不会误注入）
            this.injectStaticCollisionBlocks(world, physicsWorld, sampledBlocks, collRadius);

            // 5. 对每个抽样方块创建动态刚体 + 物理实体
            int blockIndex = 0;
            for (class_2338 pos : sampledBlocks)
            {
                try
                {
                    // 从临时 Map 读取原始 BlockState（步骤 3 已设为空气）
                    class_2680 state = originalStates.get(pos);
                    if (state == null) state = world.method_8320(pos);

                    // 跳过空气和不可破坏的方块（如基岩）
                    if (state.method_26215() || state.method_26204().method_36555() < 0)
                    {
                        blockIndex++;
                        continue;
                    }

                    // 根据形状计算速度（应用冲击力倍率，impactMult 同时影响主方向力度和扩散范围）
                    double[] velocity = this.calculateVelocityByShape(
                        splashShape, pos, centerX, centerY, centerZ,
                        dx, dy, dz, power * impactMult, impactMult, blockIndex, totalBlocks, random
                    );

                    // 创建 native 动态刚体（方块中心坐标）
                    long bodyHandle = physicsWorld.createDynamicBlock(
                        pos.method_10263() + 0.5, pos.method_10264() + 0.5, pos.method_10260() + 0.5,
                        1.0f,   // mass
                        0.8f,   // friction（让方块能滑动飞溅）
                        0.1f    // restitution（轻微弹跳）
                    );

                    if (bodyHandle == 0)
                    {
                        System.out.println("[BBS-Sable] Failed to create dynamic block at " + pos);
                        blockIndex++;
                        continue;
                    }

                    // 设置自定义阻尼
                    physicsWorld.setBodyDamping(bodyHandle, linDamp, angDamp);

                    // 设置初速度（blocks/tick → m/s）
                    physicsWorld.setBodyVelocity(bodyHandle,
                        velocity[0] * 20.0, velocity[1] * 20.0, velocity[2] * 20.0);

                    // 设置角速度（基于速度方向，让方块自然翻转，增强冲击感）
                    double speed = Math.sqrt(velocity[0] * velocity[0]
                                           + velocity[1] * velocity[1]
                                           + velocity[2] * velocity[2]);
                    float angularMag = (float) Math.min(speed * 6.0F, 12.0F);
                    if (angularMag < 2.0F) angularMag = 2.0F;
                    Random angRand = new Random(pos.hashCode());
                    float angVx = (angRand.nextFloat() - 0.5f) * angularMag * 2;
                    float angVy = (angRand.nextFloat() - 0.5f) * angularMag * 2;
                    float angVz = (angRand.nextFloat() - 0.5f) * angularMag * 2;
                    physicsWorld.setBodyAngularVelocity(bodyHandle, angVx, angVy, angVz);

                    // 创建物理方块实体并注入 body handle
                    PhysicsBlockEntity physics = new PhysicsBlockEntity(
                        world, pos.method_10263() + 0.5, pos.method_10264(), pos.method_10260() + 0.5, state
                    );
                    physics.setBodyHandle(bodyHandle, physicsWorld, worldId);
                    physics.setInitialVelocity(velocity[0], velocity[1], velocity[2]);

                    // 同步客户端物理预测参数（让客户端 165Hz 渲染时用相同的重力/阻尼/角速度预测）
                    physics.setClientPhysicsParams(
                        (float) gy, (float) linDamp, (float) angDamp,
                        angVx, angVy, angVz
                    );

                    // 关联记录系统
                    if (recordingId != null)
                    {
                        physics.setReplayId(recordingId);
                        physics.setBlockId(PhysicsRecordingManager.nextBlockId(recordingId));
                    }

                    // 碰撞开关
                    physics.field_5960 = !enableCollision;

                    world.method_8649(physics);
                    BlockSplashRecoveryManager.recordEntity(world, physics);

                    blockIndex++;
                }
                catch (Exception e)
                {
                    System.out.println("[BBS-Sable] ERROR spawning native block at " + pos + ": " + e.getClass().getName() + ": " + e.getMessage());
                    e.printStackTrace();
                    blockIndex++;
                }
            }
        }
        else
        {
            // ================================================================
            // === 原版 FallingBlockEntity 路径（保留所有原有逻辑） ===
            // ================================================================

            int blockIndex = 0;
            int totalBlocks = blocks.size();

            for (class_2338 pos : blocks)
            {
                try
                {
                    class_2680 state = world.method_8320(pos);

                    // 跳过空气和不可破坏的方块（如基岩）
                    if (state.method_26215() || state.method_26204().method_36555() < 0)
                    {
                        blockIndex++;
                        continue;
                    }

                    // 记录原始方块状态（用于退出时恢复）
                    BlockSplashRecoveryManager.recordBlock(world, pos, state);

                    // 根据形状计算速度（原版模式不应用 impactBoost，传 1.0）
                    double[] velocity = this.calculateVelocityByShape(
                        splashShape, pos, centerX, centerY, centerZ,
                        dx, dy, dz, power, 1.0, blockIndex, totalBlocks, random
                    );

                    // 用原版 API 把方块转成下落方块实体
                    class_1540 falling = class_1540.method_40005(world, pos, state);

                    if (falling == null)
                    {
                        blockIndex++;
                        continue;
                    }

                    // 记录生成的飞溅实体
                    BlockSplashRecoveryManager.recordEntity(world, falling);

                    falling.method_18800(velocity[0], velocity[1], velocity[2]);
                    falling.field_6037 = true;

                    if (enableCollision)
                    {
                        falling.field_5960 = false;
                    }

                    boolean shouldRotate = enableRotation || !doSolidify;
                    if (shouldRotate)
                    {
                        RotatingFallingBlockManager.markRotating(falling, smoothRotationStop, rotationStopDistance, rotationResetDuration);
                    }

                    falling.field_7193 = false;

                    if (!doSolidify)
                    {
                        try
                        {
                            falling.method_5841().method_12778(FallingBlockRotationData.NO_SOLIDIFY, true);
                        }
                        catch (Exception e)
                        {
                            /* 忽略 */
                        }

                        BlockSplashAnimationScheduler.scheduleAnimation(world, falling, animDurationSec);
                    }

                    blockIndex++;
                }
                catch (Exception e)
                {
                    System.out.println("[BBS-Sable] ERROR spawning block at " + pos + ": " + e.getClass().getName() + ": " + e.getMessage());
                    e.printStackTrace();
                    blockIndex++;
                }
            }
        }
    }

    /**
     * 密度优化：大区域抽样，减少方块数量避免挤在一起飞溅不出去
     *
     * 方块数 ≤ maxCount 时直接返回全部。
     * 方块数 > maxCount 时按 2x2x2 分组，每组取 1 个代表方块，
     * 确保抽样后方块间距 ≥ 1.5，避免初始重叠。
     *
     * @param blocks   原始方块列表
     * @param maxCount 最大方块数
     * @return 抽样后的方块列表
     */
    private List<class_2338> sampleBlocksForDensity(List<class_2338> blocks, int maxCount)
    {
        if (blocks.size() <= maxCount)
        {
            return new ArrayList<>(blocks);
        }

        // 按 2x2x2 分组，每组取中心方块
        java.util.Map<Long, class_2338> grouped = new java.util.HashMap<>();
        for (class_2338 pos : blocks)
        {
            // 把坐标除以 2 取整，同组的方块映射到同一个 key
            long gx = pos.method_10263() >> 1;
            long gy = pos.method_10264() >> 1;
            long gz = pos.method_10260() >> 1;
            long key = (gx & 0x3FFFFF) | ((gy & 0x3FFFFF) << 22) | ((gz & 0x3FFFFF) << 44);

            // 每组只保留第一个方块
            grouped.putIfAbsent(key, pos);
        }

        List<class_2338> sampled = new ArrayList<>(grouped.values());

        // 如果抽样后仍然超过 maxCount，随机选取 maxCount 个
        if (sampled.size() > maxCount)
        {
            java.util.Collections.shuffle(sampled, new Random(42L));
            sampled = new ArrayList<>(sampled.subList(0, maxCount));
        }

        return sampled;
    }

    /**
     * 注入静态碰撞体：飞溅区域周围 radius 格内的非空气方块作为地面/墙壁
     *
     * @param world        Minecraft 世界
     * @param physicsWorld 原生物理世界
     * @param splashBlocks 飞溅方块列表（这些方块会被设为空气，不注入）
     * @param radius       注入范围（格）
     */
    private void injectStaticCollisionBlocks(class_3218 world, NativePhysicsWorld physicsWorld,
                                              List<class_2338> splashBlocks, int radius)
    {
        Set<class_2338> splashSet = new HashSet<>(splashBlocks);
        Set<class_2338> injected = new HashSet<>();
        int maxStatic = 2000; // 性能保护：最多 2000 个静态碰撞体（动态地面检测会补加）

        for (class_2338 splashPos : splashBlocks)
        {
            for (int dx = -radius; dx <= radius; dx++)
            {
                for (int dy = -radius; dy <= radius; dy++)
                {
                    for (int dz = -radius; dz <= radius; dz++)
                    {
                        if (injected.size() >= maxStatic) return;

                        class_2338 pos = splashPos.method_10069(dx, dy, dz);

                        // 跳过飞溅方块本身（它们会被设为空气）
                        if (splashSet.contains(pos)) continue;
                        // 跳过已注入的
                        if (injected.contains(pos)) continue;

                        class_2680 state = world.method_8320(pos);
                        if (!state.method_26215() && state.method_26204().method_36555() >= 0)
                        {
                            physicsWorld.addStaticBlock(pos.method_10263(), pos.method_10264(), pos.method_10260());
                            injected.add(pos);
                        }
                    }
                }
            }
        }
    }

    /**
     * 根据飞溅形状计算每个方块的速度向量
     *
     * 形状说明：
     * - ray（射线型）：所有方块沿主方向飞，加少量扩散，形成射线状
     * - spiral（螺旋型）：方块沿主方向前进，同时绕主轴螺旋分布
     * - sphere（球型）：方块从中心向四面八方均匀飞散，形成球形扩散
     * - arc（弧形）：方块沿抛物线飞，先向斜上方飞，受重力下落形成弧线
     *
     * impactBoost（impactMult）的影响维度：
     * - 主方向力度：power * impactMult（调用处已乘）
     * - 扩散范围：各形状的扩散力 * impactMult（本方法内应用）
     * 这样 impactBoost 与 power 区分开：power 控制整体力度，impactBoost 控制扩散广度
     *
     * @param shape 形状名称
     * @param pos 方块位置
     * @param centerX/Y/Z 区域中心
     * @param dx/dy/dz 归一化主方向
     * @param power 飞溅强度（已含 impactBoost 主方向力度，即 power * impactMult）
     * @param impactMult 冲击力倍率（用于扩散力，让 impactBoost 同时影响扩散范围）
     * @param index 方块索引（用于螺旋相位计算）
     * @param total 方块总数
     * @param random 随机数生成器
     * @return 速度数组 [vx, vy, vz]
     */
    private double[] calculateVelocityByShape(
        String shape, class_2338 pos,
        double centerX, double centerY, double centerZ,
        double dx, double dy, double dz, double power, double impactMult,
        int index, int total, Random random)
    {
        double vx, vy, vz;

        // 方块相对中心的位置
        double offX = pos.method_10263() - centerX;
        double offY = pos.method_10264() - centerY;
        double offZ = pos.method_10260() - centerZ;

        switch (shape)
        {
            case "spiral":
            {
                // 螺旋型：方块沿主方向前进，同时绕主轴旋转
                // 螺旋角度随方块索引递增，形成螺旋分布
                // impactMult 影响螺旋半径（扩散范围）
                double angle = (index * Math.PI * 2.0) / Math.max(1, total / 4);
                double radius = power * 0.6 * impactMult;

                // 计算垂直于主方向的两个正交向量（构成旋转平面）
                // up 向量：与主方向最不平行
                double upX = 0, upY = 1, upZ = 0;
                if (Math.abs(dy) > 0.9)
                {
                    upX = 1; upY = 0; upZ = 0;
                }

                // right = up × dir（叉积）
                double rightX = upY * dz - upZ * dy;
                double rightY = upZ * dx - upX * dz;
                double rightZ = upX * dy - upY * dx;
                double rightLen = Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
                if (rightLen > 1e-6)
                {
                    rightX /= rightLen; rightY /= rightLen; rightZ /= rightLen;
                }

                // forward = dir × right（叉积，得到第三个正交向量）
                double fwdX = dy * rightZ - dz * rightY;
                double fwdY = dz * rightX - dx * rightZ;
                double fwdZ = dx * rightY - dy * rightX;

                // 螺旋偏移：在垂直于主方向的平面上做圆周运动
                double cosA = Math.cos(angle) * radius;
                double sinA = Math.sin(angle) * radius;

                // 速度 = 主方向前进 + 螺旋旋转
                vx = dx * power + rightX * cosA + fwdX * sinA;
                vy = dy * power + rightY * cosA + fwdY * sinA;
                vz = dz * power + rightZ * cosA + fwdZ * sinA;
                break;
            }

            case "sphere":
            {
                // 球型：方块从中心向四面八方均匀飞散
                // impactMult 影响从中心向外的飞散力度（扩散范围）
                double offLen = Math.sqrt(offX * offX + offY * offY + offZ * offZ);
                if (offLen < 1e-6)
                {
                    // 正好在中心的方块，用随机方向
                    double theta = random.nextDouble() * Math.PI * 2;
                    double phi = random.nextDouble() * Math.PI;
                    vx = Math.sin(phi) * Math.cos(theta) * power;
                    vy = Math.cos(phi) * power;
                    vz = Math.sin(phi) * Math.sin(theta) * power;
                }
                else
                {
                    // 从中心向外的方向（乘 impactMult 增强扩散）
                    vx = (offX / offLen) * power * impactMult;
                    vy = (offY / offLen) * power * impactMult;
                    vz = (offZ / offLen) * power * impactMult;
                }

                // 叠加少量主方向力，让整体有趋势（也乘 impactMult）
                vx += dx * power * 0.3 * impactMult;
                vy += dy * power * 0.3 * impactMult;
                vz += dz * power * 0.3 * impactMult;
                break;
            }

            case "arc":
            {
                // 弧形：方块沿抛物线飞，先向斜上方飞，受重力下落形成弧线
                // 水平方向沿主方向（XZ平面），垂直方向给较大向上速度
                double horizontalPower = power * 0.8;
                double upwardPower = power * 1.2;

                // 水平速度沿主方向的 XZ 分量
                vx = dx * horizontalPower;
                vz = dz * horizontalPower;

                // 垂直速度：主方向的 Y 分量 + 额外向上力
                vy = dy * horizontalPower + upwardPower;

                // 根据方块在区域中的位置调整：离中心越远的方块弧度越大
                double offLen = Math.sqrt(offX * offX + offZ * offZ);
                double arcFactor = 1.0 + (offLen / 10.0) * 0.3;
                vy *= arcFactor;

                // 少量水平扩散（impactMult 影响扩散范围）
                double spread = power * 0.15 * impactMult;
                vx += (random.nextDouble() - 0.5) * 2 * spread;
                vz += (random.nextDouble() - 0.5) * 2 * spread;
                break;
            }

            case "ray":
            default:
            {
                // 射线型：所有方块沿主方向飞，加从中心向外的扩散力
                double baseVx = dx * power;
                double baseVy = dy * power;
                double baseVz = dz * power;

                // 从中心向外的扩散力（避免同向飞行重叠）
                // impactMult 影响扩散范围
                double offLen = Math.sqrt(offX * offX + offY * offY + offZ * offZ);
                double spreadForce = power * 0.4 * impactMult;
                double spreadVx = 0, spreadVy = 0, spreadVz = 0;
                if (offLen > 1e-6)
                {
                    spreadVx = (offX / offLen) * spreadForce;
                    spreadVy = (offY / offLen) * spreadForce;
                    spreadVz = (offZ / offLen) * spreadForce;
                }

                // 随机扰动
                double randSpread = power * 0.3;
                double randX = (random.nextDouble() - 0.5) * 2 * randSpread;
                double randY = (random.nextDouble() - 0.5) * 2 * randSpread;
                double randZ = (random.nextDouble() - 0.5) * 2 * randSpread;

                vx = baseVx + spreadVx + randX;
                vy = baseVy + spreadVy + randY;
                vz = baseVz + spreadVz + randZ;
                break;
            }
        }

        return new double[] { vx, vy, vz };
    }

    /**
     * 收集区域内所有方块坐标
     */
    private List<class_2338> collectBlocksInArea(int x1, int y1, int z1, int x2, int y2, int z2)
    {
        /* 如果有方块过滤器，直接返回过滤器中的方块（精确选区） */
        if (this.blockFilter != null && !this.blockFilter.isEmpty())
        {
            return new ArrayList<>(this.blockFilter);
        }

        int minX = Math.min(x1, x2);
        int minY = Math.min(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2);
        int maxY = Math.max(y1, y2);
        int maxZ = Math.max(z1, z2);

        List<class_2338> blocks = new ArrayList<>();

        for (int bx = minX; bx <= maxX; bx++)
        {
            for (int by = minY; by <= maxY; by++)
            {
                for (int bz = minZ; bz <= maxZ; bz++)
                {
                    blocks.add(new class_2338(bx, by, bz));
                }
            }
        }

        return blocks;
    }

    /* === IBlockFilterable 实现 === */

    @Override
    public void setBlockFilter(Set<class_2338> filter)
    {
        this.blockFilter = filter;
    }

    @Override
    public Set<class_2338> getBlockFilter()
    {
        return this.blockFilter;
    }

    @Override
    public void shift(double dx, double dy, double dz)
    {
        super.shift(dx, dy, dz);

        this.x.set((int) (this.x.get() + dx));
        this.y.set((int) (this.y.get() + dy));
        this.z.set((int) (this.z.get() + dz));
        this.x2.set((int) (this.x2.get() + dx));
        this.y2.set((int) (this.y2.get() + dy));
        this.z2.set((int) (this.z2.get() + dz));
    }

    @Override
    protected Clip create()
    {
        return new BlockSplashActionClip();
    }
}
