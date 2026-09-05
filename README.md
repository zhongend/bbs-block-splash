# BBS Block Splash（方块飞溅）

> **Minecraft 1.20.1 · Fabric · BBS 模组扩展插件（Addon）**
>
> 为 [BBS 模组](https://modrinth.com/mod/bbs) 的电影/回放系统添加 **5 种方块级特效 Action Clip**：
> 区域方块飞溅、方块振波、反向飞溅（自动建造）、方块路径运动、飞溅组合。
> 内置 **Rapier3d 原生刚体物理引擎（JNI）** 与 **纯 Java 物理引擎双通道**，
> 支持真实弹跳、滚动、旋转、堆叠，并且拥有**完整的存档保护机制**（动画中途退出游戏也不会破坏地图）。

| 项目信息 | 内容 |
|---|---|
| 模组 ID | `bbsblocksplash` |
| 模组名称 | `bbs_Block_Splash` |
| 当前版本 | 2.0.4 |
| 作者 | **zhongend** |
| 许可证 | MIT |
| 前置 | Minecraft **1.20.1**（精确匹配）、Fabric Loader ≥ 0.15.0、Fabric API、**BBS 模组** |
| Java 版本 | ≥ 17 |
| 代码规模 | 约 15,000 行 Java 源码（58 个类） + Rapier3d 原生物理库（Rust 编译） |
| 参考项目 | [Wemppy4/bbs-fs](https://github.com/Wemppy4/bbs-fs)（BBS 模组源码，MIT）、[Sable](https://github.com/ryanhcode/sable)、[rigid-body](https://github.com/Polari-Stars-MC/rigid-body) |

---

## 目录

- [1. 第一性原理：这个插件为什么存在、如何思考](#1-第一性原理这个插件为什么存在如何思考)
- [2. 整体架构](#2-整体架构)
- [3. 目录结构](#3-目录结构)
- [4. 与 BBS 模组的对接方式](#4-与-bbs-模组的对接方式)
- [5. 五大特效详解](#5-五大特效详解)
- [6. 物理系统深度剖析（双引擎设计）](#6-物理系统深度剖析双引擎设计)
- [7. 服务端调度器家族](#7-服务端调度器家族)
- [8. Mixin 注入体系（14 个 Mixin）](#8-mixin-注入体系14-个-mixin)
- [9. 网络同步与 165Hz 高刷新率渲染](#9-网络同步与-165hz-高刷新率渲染)
- [10. 存档安全设计（三层恢复保险）](#10-存档安全设计三层恢复保险)
- [11. 性能设计与资源泄漏治理](#11-性能设计与资源泄漏治理)
- [12. 使用指南](#12-使用指南)
- [13. 构建、开发与二次开发](#13-构建开发与二次开发)
- [14. 已知限制与 FAQ](#14-已知限制与-faq)
- [15. 致谢与许可](#15-致谢与许可)

---

## 1. 第一性原理：这个插件为什么存在、如何思考

### 1.1 三个基本事实

从最底层出发，Minecraft 电影创作领域存在三个无法回避的事实：

**事实一：Minecraft 原版只有一种"方块运动"原语 —— 下落方块实体（`FallingBlockEntity`）。**
原版沙子、沙砾、混凝土粉末下落就是它。它有固定的行为模板：受重力下落 → 落地变回方块（或变成掉落物）。它**没有旋转、没有弹跳、没有水平飞行、不能被编排**。原版 TNT 爆炸之所以能把沙子炸飞，本质上是"把方块变成 FallingBlockEntity + 赋初速度"，这是一个 20 行代码就能复刻的原语。

**事实二：BBS 模组提供了"编排系统"，但只编排实体，不编排方块。**
BBS（原 Blockbuster 模组的后继者）是 Minecraft 电影（machinima）创作模组，核心是一套 **Clip（片段）时间轴系统**：摄影师轨道、演员回放（Replay）、动作片段（Action Clip）等。但它对**世界方块**本身没有任何动画能力——BBS 的电影里，一栋房子永远不会自己塌下来，一堵墙永远不会自己砌起来。

**事实三：电影特效的硬性要求是"确定性"与"可恢复性"。**
电影不是游戏对局，同一个回放要能反复预览、反复导出。这意味着：① 随机性必须用固定种子（本插件所有随机数都是 `new Random(42L)`）；② 世界被特效临时改变后必须能精确还原——**哪怕玩家在动画播到一半时直接关闭游戏**。任何"退出后地图永久破损"都是不可接受的。

### 1.2 推导出的设计结论

把上面三个事实作为公理，插件的全部设计都被推导出来：

1. **特效的实现载体必然是"方块 ↔ 实体的双向转换"。**
   方块不能动，实体能动。所以"让方块飞" = 把方块变回实体（`FallingBlockEntity.spawnFromBlock`）+ 赋初速度；"让方块复原" = 把实体变回方块（`setBlockState`）。整个插件的一切都围绕这个转换展开。

2. **特效的入口必然是 BBS 的 Action Clip 体系。**
   BBS 的 `ActionClip.applyAction(entity, fakePlayer, film, replay, tick)` 是回放时间轴触发的统一入口。本插件注册 5 个自定义 ActionClip，BBS 的时间轴、关键帧、UI 编辑器就全部自动为它们工作，零成本接入电影工作流。

3. **物理引擎必须自建，且要有两档。**
   原版 `FallingBlockEntity` 落地即变方块，没有弹跳/滚动/堆叠。要做出"石头砸在地上翻滚"的电影感，就需要真正的刚体物理。但依赖体积与跨平台兼容性又是现实约束——于是设计成**双引擎**：
   - **原生引擎（默认）**：Rust 编写的 Rapier3d（`rapier3d-f64 0.33.0`）编译为 `bbs_physics.dll`，通过 JNI 调用。PGS 约束求解器、库仑摩擦锥、CCD 连续碰撞、IslandManager 自动休眠——工业级刚体物理。
   - **纯 Java 引擎（回退）**：`PhysicsEngine` 类用约 500 行代码复刻 Sable 的物理调校（半隐式欧拉积分、AABB 碰撞、四元数旋转、接地检测、休眠机制），零原生依赖，任何平台可用。

4. **恢复机制必须独立于宿主模组，且要有三层保险。**
   BBS 自带 DamageControl（回放中记录方块变更，回放结束恢复），但它在"回放播到一半游戏直接退出"时无能为力。所以本插件自建 `BlockSplashRecoveryManager`，在 `SERVER_STOPPING` 事件兜底恢复——详见 [第 10 章](#10-存档安全设计三层恢复保险)。

5. **渲染必须突破 Minecraft 20 TPS 的天花板。**
   服务器 20 tick/秒，但玩家屏幕可能是 144Hz/165Hz。如果不做特殊处理，方块旋转看起来只有 20 帧。本插件为此做了一整套客户端每帧预测/插值体系——详见 [第 9 章](#9-网络同步与-165hz-高刷新率渲染)。

### 1.3 一句话总结

> **本插件 = "把 Minecraft 世界当成可编排的道具" 的电影工具：用 BBS 时间轴触发，把任意区域方块变成带真实刚体物理的飞行实体，演完（或中途退出）后世界精确复原。**

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              BBS 模组（前置）                              │
│   回放时间轴 / 关键帧 / UI 编辑器 / DamageControl / SuperFakePlayer        │
└───────────────▲─────────────────────────────────────────▲───────────────┘
                │ 注册 5 个 ActionClip + UI 面板            │ applyAction() 回调
┌───────────────┴─────────────────────────────────────────┴───────────────┐
│                      bbsblocksplash（本插件）                             │
│                                                                         │
│  ┌─────────────────── 编排层（actions/）────────────────────┐            │
│  │ BlockSplashActionClip        方块飞溅（Sable物理/原版双模式）│            │
│  │ BlockShockwaveActionClip     方块振波（7 种模式）           │            │
│  │ BlockSplashReverseActionClip 反向飞溅（自动建造）           │            │
│  │ BlockPathActionClip          路径运动（4 种样条插值）        │            │
│  │ BlockSplashComboActionClip   组合（选区+子效果+过渡）        │            │
│  └──────┬──────────────────────────────────────────┬───────┘            │
│         │ 一次性规划（收集方块/算速度/记恢复）          │ 注册长期任务         │
│  ┌──────▼──────────────────┐            ┌──────────────────▼───────────┐  │
│  │   物理层（双引擎）        │            │  调度器层（每 tick 驱动）       │  │
│  │                         │            │                              │  │
│  │ NativePhysicsWorld      │            │ BlockShockwaveScheduler      │  │
│  │  └─ JNI → bbs_physics   │            │ BlockSplashReverseScheduler  │  │
│  │      (Rapier3d, Rust)   │            │ BlockPathScheduler           │  │
│  │ PhysicsBlockEntity      │            │ BlockSplashAnimationScheduler│  │
│  │  └─ 读刚体变换→MC实体     │            │ PhysicsWorldRegistry.tickAll │  │
│  │ PhysicsEngine(纯Java回退)│            └──────────────────┬───────────┘  │
│  └─────────────────────────┘                               │              │
│                                                             │              │
│  ┌────────────────── 公共服务 ──────────────────────────────▼───────────┐  │
│  │ BlockSplashRecoveryManager  存档恢复兜底（SERVER_STOPPING 触发）        │  │
│  │ RotatingFallingBlockManager 原版实体旋转物理（弹跳/摩擦/休眠）          │  │
│  │ FallingBlockRotationData    DataTracker 同步字段注册表（15 个字段）     │  │
│  │ RegionSelectorItem + RegionSelectionCache  区域选择木棍               │  │
│  │ BlockPathCurve              样条插值（Catmull-Rom/B样条/线性/三次）      │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                         │
│  ┌────────────────── 客户端（client/）────────────────────────────────┐   │
│  │ BlockSplashClient          注册渲染器/UI面板/木棍左键/粒子描边         │   │
│  │ PhysicsBlockEntityRenderer 165Hz 物理预测渲染                        │   │
│  │ ClientRotationStateManager 每帧旋转积分状态                          │   │
│  │ UI*ActionClip × 4          各特效的 BBS 编辑面板                     │   │
│  │ UIPathEditorMenu           路径点编辑器                              │   │
│  │ UIComboEditorOverlay       组合编辑器（子轨道预览）                    │   │
│  │ 9 个客户端 Mixin            渲染旋转/挤压/缩小、跳过 tick、BBS UI 扩展 │   │
│  └────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.1 核心数据流（一次"方块飞溅"的完整生命周期）

```
用户在 BBS 时间轴上放置 block_splash 片段并配置参数
        │
        ▼ 回放播放到该片段
BlockSplashActionClip.applyAction()
        │
        ├─① collectBlocksInArea()  收集区域内方块（或用组合选区的精确过滤列表）
        ├─② sampleBlocksForDensity()  超过上限时 2×2×2 分组抽样（性能保护）
        ├─③ 把区域内方块记录到 RecoveryManager 并设为空气
        ├─④ [Sable模式] 创建 NativePhysicsWorld（Rapier3d）+ 注入静态碰撞体
        ├─⑤ calculateVelocityByShape()  按形状(ray/spiral/sphere/arc)算初速度
        ├─⑥ 创建刚体/实体并赋初速度、角速度
        │
        ▼ 之后每个服务端 tick（END_SERVER_TICK）
   [Sable模式] PhysicsWorldRegistry.tickAll() → Rapier 步进 →
               PhysicsBlockEntity 读刚体变换 → 同步到 MC 实体 → 记录到 Recording
   [原版模式]  FallingBlockEntityPhysicsMixin 在 tick 末尾注入
               RotatingFallingBlockManager 的弹跳/摩擦/旋转物理
               BlockSplashAnimationScheduler 在到期时驱动缩小消失
        │
        ▼ 回放结束 / 物理世界超时 / 空世界
   BBS DamageControl 恢复方块 + 移除实体
   PhysicsWorldRegistry 销毁世界 → 连带清理 Recording/Recovery 记录
        │
        ▼ 兜底：玩家中途强退游戏（SERVER_STOPPING）
   三大调度器 clearAll() → PhysicsWorldRegistry.clearAll()
   → BlockSplashRecoveryManager.restoreAll()  精确还原所有方块 → clearAll()
```

---

## 3. 目录结构

```
方块飞溅/
├── README.md                          ← 本文档
├── LICENSE                            ← MIT
├── releases/
│   ├── bbs-Block_Splash-2.0.4-sources.jar   ← 原始源码包（原始版本存档）
│   └── MANIFEST.MF                    ← 原始构建清单（记录 Loom/Loader 版本）
└── src/main/
    ├── java/com/example/bbsanimatedbreak/
    │   ├── BlockSplashAddon.java            【入口】主入口：注册 Clip/物品/实体/事件
    │   ├── BlockPathCurve.java              样条插值计算（4 种算法）
    │   ├── BlockPathScheduler.java          路径运动调度器（贪吃蛇/风暴模式）
    │   ├── BlockShockwaveScheduler.java     振波调度器（881 行，波浪传播核心）
    │   ├── BlockSplashAddon.java            主入口
    │   ├── BlockSplashAnimationScheduler.java  缩小消失动画调度器（ease-in quadratic）
    │   ├── BlockSplashRecoveryManager.java  存档恢复管理器（退出保护核心）
    │   ├── BlockSplashReverseScheduler.java 反向飞溅调度器（932 行，四阶段状态机）
    │   ├── FallingBlockRotationData.java    DataTracker 字段注册表（15 个同步字段）
    │   ├── RegionSelectionCache.java        区域选择坐标缓存
    │   ├── RotatingFallingBlockManager.java 原版实体旋转物理管理器（681 行）
    │   ├── actions/                         五大特效 ActionClip
    │   │   ├── BlockSplashActionClip.java         方块飞溅（814 行，最核心）
    │   │   ├── BlockShockwaveActionClip.java      方块振波
    │   │   ├── BlockSplashReverseActionClip.java  反向飞溅
    │   │   ├── BlockPathActionClip.java           路径运动
    │   │   ├── BlockSplashComboActionClip.java    飞溅组合
    │   │   ├── combo/                       组合系统的支撑类
    │   │   │   ├── BlockSelection.java      5 种选区模式（矩形/圆/三角/多边形/随机）
    │   │   │   ├── IBlockFilterable.java    方块过滤器接口（精确选区传递）
    │   │   │   ├── SubEffect.java           子效果（内嵌完整 ActionClip + 时间 + 过渡曲线）
    │   │   │   └── SubEffectList.java       子效果列表
    │   │   └── physics/                     物理子系统（11 个类）
    │   │       ├── NativeLibraryLoader.java    DLL/SO/Dylib 提取加载器
    │   │       ├── NativePhysicsLibrary.java   JNI 绑定（19 个 native 方法）
    │   │       ├── NativePhysicsWorld.java     Rapier 世界封装（AutoCloseable）
    │   │       ├── PhysicsBlockEntity.java     物理方块实体（791 行，165Hz 预测核心）
    │   │       ├── PhysicsBlockEntityTypes.java 实体类型注册
    │   │       ├── PhysicsEngine.java          纯 Java 物理引擎（Sable 风格回退）
    │   │       ├── PhysicsEntityManager.java   实体注册表（近邻查询）
    │   │       ├── PhysicsRecording.java       单帧物理记录（不可变）
    │   │       ├── PhysicsRecordingManager.java 按 replayId 分组的记录缓存
    │   │       ├── PhysicsState.java           物理状态数据类
    │   │       └── PhysicsWorldRegistry.java   物理世界注册表（生命周期管理）
    │   ├── client/                          客户端专属
    │   │   ├── BlockSplashClient.java       客户端入口
    │   │   ├── ClientRotationStateManager.java  每帧旋转状态
    │   │   ├── PhysicsBlockEntityRenderer.java  物理方块渲染器
    │   │   ├── UIBlockSplashActionClip.java     飞溅编辑面板
    │   │   ├── UIBlockShockwaveActionClip.java  振波编辑面板
    │   │   ├── UIBlockSplashReverseActionClip.java 反向飞溅编辑面板
    │   │   ├── UIBlockPathActionClip.java       路径编辑面板
    │   │   ├── UIPathEditorMenu.java            路径点编辑器（右键世界添加标记点）
    │   │   ├── ui/combo/                        组合编辑器
    │   │   │   ├── UIBlockSplashComboActionClip.java
    │   │   │   ├── UIBlockSplashComboRenderer.java  clip 矩形内子轨道迷你预览
    │   │   │   └── UIComboEditorOverlay.java        组合编辑全屏界面
    │   │   └── mixin/                       9 个客户端 Mixin
    │   ├── items/
    │   │   └── RegionSelectorItem.java      区域选择木棍
    │   └── mixin/                           5 个服务端 Mixin
    └── resources/
        ├── fabric.mod.json                  模组元数据
        ├── bbsblocksplash.mixins.json       服务端 Mixin 配置
        ├── bbsblocksplash.client.mixins.json 客户端 Mixin 配置
        ├── natives/windows/bbs_physics.dll  Rapier3d 原生物理库（Rust 编译）
        └── assets/
            ├── bbs/lang/                    BBS 片段名称翻译
            ├── bbsblocksplash/lang/         物品名称翻译
            ├── bbsblocksplash/strings/      BBS L10n 字符串（UI 面板文案）
            └── bbsblocksplash/models/item/region_selector.json  木棍模型（复用原版贴图）
```

> **源码中的 `class_xxxx` 命名说明**：本插件编译目标为 Minecraft 1.20.1 + Fabric 的
> intermediary（中间）映射。源码里所有 `class_1540`（FallingBlockEntity）、`class_2338`（BlockPos）、
> `method_8652`（setBlockState）等都是 Yarn 映射的中间名，见 [13.2 节](#132-为什么源码里全是-class_xxxx)对照表。

---

## 4. 与 BBS 模组的对接方式

本插件是一个标准的 **BBS Addon**，通过四个对接点挂入 BBS：

### 4.1 入口注册（`BlockSplashAddon`）

`fabric.mod.json` 声明了三个入口点：

```json
"entrypoints": {
    "main":      ["com.example.bbsanimatedbreak.BlockSplashAddon"],
    "client":    ["com.example.bbsanimatedbreak.client.BlockSplashClient"],
    "bbs-addon": ["com.example.bbsanimatedbreak.BlockSplashAddon"]
}
```

`BlockSplashAddon` 同时实现 `ModInitializer` 和 BBS 的 `BBSAddonMod` 接口：

| 时机 | 做什么 |
|---|---|
| `onInitialize()`（Fabric 主入口） | 注册区域选择木棍物品（含 BBS 物品栏 + 原版工具栏双注册）；触发 `PhysicsBlockEntityTypes` 静态注册物理方块实体；注册 `SERVER_STOPPING`（恢复兜底）与 `END_SERVER_TICK`（驱动全部调度器 + 物理步进）事件 |
| `@Subscribe onRegisterSettings(RegisterSettingsEvent)` | 把 5 个 ActionClip 注册进 BBS 的 `getFactoryActionClips()` 工厂，指定 ID、图标（`Icons.BLOCK`）、时间轴颜色（红/橙/绿/蓝/品红） |
| `@Subscribe onRegisterSourcePacks(RegisterSourcePacksEvent)` | 注册 `InternalAssetsSourcePack`，让 BBS 的 `AssetProvider` 能解析 `source="bbsblocksplash"` 的资源链接，加载 UI 字符串翻译 |

### 4.2 Clip 命名与翻译

5 个片段注册 ID 均在 `bbs:` 命名空间下（`Link.bbs("block_splash")` 等），因此 BBS 的时间轴 UI 通过 `bbs.ui.camera.clips.bbs:block_splash` 这样的 L10n 键查找显示名。翻译文件分布在两处：

- `assets/bbs/lang/*.json` —— BBS 原生语言文件（片段名）
- `assets/bbsblocksplash/strings/*.json` —— 本插件自有 SourcePack 的字符串（片段名），由 `BlockSplashClient` 通过 `L10n.registerOne()` 注册

### 4.3 UI 面板注册（客户端）

BBS 提供 `UIClip.register(Clip类, 工厂)` 静态方法。`BlockSplashClient` 中：

```java
UIClip.register(BlockSplashActionClip.class,        UIBlockSplashActionClip::new);
UIClip.register(BlockShockwaveActionClip.class,     UIBlockShockwaveActionClip::new);
UIClip.register(BlockSplashReverseActionClip.class, UIBlockSplashReverseActionClip::new);
UIClip.register(BlockPathActionClip.class,          UIBlockPathActionClip::new);
```

注册后，用户在 BBS 行为编辑器中选中对应片段时，BBS 会自动实例化我们的面板类展示参数控件。"粘贴坐标"按钮读取 `RegionSelectionCache`（木棍选的点）一键填入 x/y/z/x2/y2/z2。

组合片段（`BlockSplashComboActionClip`）的面板通过客户端 Mixin（`UIClipMixin`/`UIClipRenderersMixin`）挂入 BBS 的 clip 渲染工厂，`UIBlockSplashComboRenderer` 在时间轴 clip 矩形右半部分绘制**迷你子轨道预览**（显示所有子效果的时间块）。

### 4.4 借用 BBS 的既有机制

| BBS 机制 | 本插件如何利用 |
|---|---|
| `SuperFakePlayer` | ActionClip 回调提供的假人，用于安全获取 `ServerWorld`（actor 可能为 null） |
| `DamageControl`（WorldMixin 拦截 `setBlockState`） | 回放中本插件改动的方块会被 BBS 记录并在回放结束自动恢复——这是第一层恢复保险 |
| `ValueInt/ValueDouble/ValueBoolean/ValueString/ValuePositions/ValueGroup/Envelope` | 所有 Clip 参数的声明式定义（自动获得序列化 + UI 控件） |
| `Lerps`（cubicHermite/bSpline/cubic/lerp） | `BlockPathCurve` 直接复用 BBS 的插值数学库，保证与 BBS 摄影机路径手感一致 |
| `Envelope`（淡入淡出曲线） | 组合子效果的 `transitionIn/transitionOut` 强度曲线 |
| `Replay.getId()` | 派生确定性 UUID 作为物理记录分组键（`UUID.nameUUIDFromBytes`），保证回放一致性 |

---

## 5. 五大特效详解

所有特效共享同一骨架：**`applyAction()` 只做一次性规划（收集方块 → 算参数 → 记恢复 → 注册任务），实际运动由服务端调度器逐 tick 驱动**。这个"规划/执行分离"设计是刻意的：applyAction 在时间轴 tick 回调中执行，必须立刻返回，不能阻塞；而波浪传播、物理模拟天然是跨 tick 的持续过程。

所有特效共同约束：

- 区域上限 **30,000 方块**（超出直接拒绝执行，防卡死）
- 空气方块与爆破抗性 < 0 的方块（基岩等）自动跳过
- 所有随机数使用固定种子 `Random(42L)`（回放一致性）
- 都实现 `IBlockFilterable` 接口，可被组合片段注入精确选区
- 触发时无条件写入 `BlockSplashRecoveryManager`（退出保险）

### 5.1 方块飞溅 `block_splash`（BlockSplashActionClip，814 行）

**效果**：区域内的方块瞬间变成飞行实体，按指定方向+形状飞溅，像 TNT 炸沙子的加强版。

#### 参数总表

| 参数 | 类型/默认 | 范围 | 说明 |
|---|---|---|---|
| `x/y/z`, `x2/y2/z2` | int | — | 区域两个对角点 |
| `power` | double 1.0 | 0–5 | 飞溅强度（初速度基数） |
| `dirX/dirY/dirZ` | double (0,1,0) | — | 主方向向量（自动归一化，零向量回退为向上） |
| `collision` | bool true | — | 实体间碰撞（防重叠穿透/瞬移） |
| `shape` | string "ray" | — | 飞溅形状：`ray`/`spiral`/`sphere`/`arc` |
| `rotation` | bool false | — | 旋转物理（飞行中翻转） |
| `smoothRotationStop` | bool true | — | 接近地面时旋转减速归零（防角度闪现） |
| `rotationStopDistance` | double 3 | 0.5–10 | 距地面多远开始减速旋转 |
| `rotationResetDuration` | int 40 | 5–200 tick | 角度归零动画时长 |
| `solidify` | bool false | — | true=落地变回实体方块；false=保持动画形态，到期非线性缩小消失 |
| `animationDuration` | double 60 | 0–9999 秒 | 非 solidify 模式的存活时长 |
| `sable`（sableEnabled） | bool **true** | — | Sable 原生物理开关 |
| `gravityX/Y/Z` | double (0,-11,0) | ±50 m/s² | 自定义重力（-11=Sable 调校值，-9.8=现实地球，-24=月球） |
| `linearDamping` | double 0.04 | 0–1 | 线性阻尼（越小飞得越远） |
| `angularDamping` | double 0.3 | 0–2 | 角阻尼（越小转得越久） |
| `impactBoost` | double 1.5 | 0.1–5 | 冲击力倍率（同时放大主方向力度与扩散范围） |
| `maxPhysicsBlocks` | int 300 | 50–2000 | 物理方块上限（超出抽样） |
| `collisionRadius` | int 8 | 3–20 | 静态碰撞体注入半径 |

#### 执行流程（Sable 模式，默认）

1. **抽样** `sampleBlocksForDensity()`：方块数 ≤ 上限直接全用；否则按 **2×2×2 空间分组**（坐标右移 1 位拼成 64-bit key），每组保留 1 个，保证抽样后间距 ≥ 1.5 格避免初始重叠；仍超上限再随机截取。
2. **清场**：区域内所有非空气方块 → 记录到 RecoveryManager → 保存原始 BlockState 到临时 Map → 设为流体状态（含水方块不留源方块）。**全部方块**都清空（包括未被采样的），杜绝"一半动画一半实体方块"的穿帮。
3. **注入静态碰撞体** `injectStaticCollisionBlocks()`：以每个飞溅方块为中心、`collisionRadius` 半径内的非飞溅非空气方块注册为 Rapier 静态刚体（地面/墙壁），上限 2000 个（性能保护）。
4. **创建动态刚体**：每个抽样方块 `createDynamicBlock(中心坐标, mass=1.0, friction=0.8, restitution=0.1)` → 设置自定义阻尼 → 初速度（形状算法，blocks/tick × 20 = m/s）→ 角速度（`min(速度×6, 12)` rad/s，按方块坐标 hash 加随机方向，让翻转自然）。
5. **创建 `PhysicsBlockEntity`**：注入刚体句柄、初速度、客户端物理预测参数（重力/阻尼/角速度——客户端 165Hz 预渲染要用），关联 Recording 系统与 Recovery 系统。
6. 每服务端 tick 由 `PhysicsWorldRegistry.tickAll(1/20)` 以 **2 子步**步进 Rapier，实体读取刚体位置+四元数旋转同步渲染。

#### 四种飞溅形状的速度算法（`calculateVelocityByShape`）

设 `d` = 归一化主方向，`off` = 方块相对区域中心的偏移，`P` = power × impactBoost，`M` = impactBoost：

| 形状 | 公式（矢量形式） | 视觉效果 |
|---|---|---|
| **ray**（射线） | `v = d·P + ô·(P·0.4·M) + rand(±P·0.3)` | 所有方块沿主方向飞 + 从中心向外的扩散力 + 随机扰动，形成射线束 |
| **spiral**（螺旋） | 构造与 d 正交的正交基 (right, fwd)，`v = d·P + right·cos(θ)·(P·0.6·M) + fwd·sin(θ)·(P·0.6·M)`，θ 随方块索引递增 | 方块绕主轴螺旋展开 |
| **sphere**（球型） | `v = ô·P·M + d·P·0.3·M`（正好在中心的方块用球坐标随机方向） | 从中心向四面八方均匀爆开 |
| **arc**（弧形） | `v_水平 = d_xz·P·0.8`，`v_垂直 = d_y·P·0.8 + P·1.2·arcFactor`，arcFactor 随离中心水平距离增大 | 抛物线弹道，边缘方块弧度更大 |

> `impactBoost` 与 `power` 的职责分离：power 管"整体力度"，impactBoost 管"扩散广度"（主方向力度与各形状扩散力同时乘它）。原版模式（sable=false）不应用 impactBoost。

#### 原版模式（sable=false）

回退到 `FallingBlockEntity`（`class_1540.method_40005` 即 `spawnFromBlock`）：

- `field_6037 = true`（setHurtEntities，允许砸伤）
- `collision=true` 时清 `field_5960`（noClip 标志）
- 旋转标记：`RotatingFallingBlockManager.markRotating()`（solidify=false 时强制开启旋转，因为动画形态必须旋转才自然）
- `field_7193 = false`（setDestroyedOnLanding，落地不掉落物品）
- 非 solidify：写入 `NO_SOLIDIFY` DataTracker 标志 + 注册 `BlockSplashAnimationScheduler` 缩小消失任务

### 5.2 方块振波 `block_shockwave`（BlockShockwaveActionClip，464 行 + 调度器 881 行）

**效果**：像地震/冲击波一样，波浪从震源向外传播，方块**原地**跳起、落下、倾斜——不水平飞走。核心是"波浪传播"：每个方块的震动延迟 = `距离 ÷ waveSpeed`（tick），由调度器分批执行，形成肉眼可见的波前。

#### 7 种振波模式

| 模式 | 距离度量 | 行为差异 |
|---|---|---|
| `circle` | 欧几里得距离 | 圆形波前，标准震动 |
| `rectangle` | 切比雪夫距离 | 矩形波前 |
| `diamond` | 曼哈顿距离 | 菱形波前 |
| `cross` | 欧几里得 + 只保留轴线方块 | 沿 X/Z 十字传播 |
| `earthquake` | 欧几里得 | 持续随机震动，幅度不衰减，间隔减半 |
| `ripple` | 欧几里得 | 幅度按正弦波起伏，像水波纹 |
| `chaos` | 欧几里得 | 方向/力度/间隔全随机 |

传播起点：`fromCenter=true` 从区域中心；`false` 时从 `direction`（north/south/east/west）指定的边开始（如 north = 从 minZ 边向南扩散），cross 模式下只保留对应轴向的一列方块。

#### 真实倾斜（冲击坑效果）

`realisticAngle=true` 时，每个方块跳起时按"到震源距离"计算倾斜角：越近越斜（中心约 15°×impactForce，边缘趋近 0°），倾斜方向背离震源。落地后**保持倾斜角度**形成永久冲击坑（像陨石坑地貌）；`restoreAfter=true` 时震动结束恢复原状（角度归零，带 8 tick 的非线性渐变动画而非瞬间跳变）。

> **关键修复记录**：无论 `restoreAfter` 是否开启，触发时都**无条件**记录原始方块状态。早期版本只在 restoreAfter=true 时记录，而该参数默认 false → 回放中途退出时方块无法恢复 → 地形永久破坏。这是"恢复记录与视觉表现解耦"原则的由来。

### 5.3 反向飞溅 `block_splash_reverse`（BlockSplashReverseActionClip + 932 行调度器）

**效果**：时间倒流——开场方块就处于散落状态（无飞溅动画），然后逐个飞回原位，拼回完整建筑（**自动建造效果**）。适合表现"废墟重建""时光倒流"。

两阶段四状态机（调度器内 `Phase` 枚举）：

```
WAITING（实体悬浮在散落点等待） → RECOVERING（关闭重力，直线插值飞向原位）
    → SNAPPING（悬停原位附近，旋转角度线性插值归零，确保 discard 时角度恰为 0）
    → DONE（变回方块；动画方块额外保留 animationKeepDuration tick 后消失，与实体方块重叠避免闪烁空档）
```

- **散落模式**：`randomSplash=true` 时散落在原位附近的随机位置（`scatterRadius` 控制，Y 恒高于原位）；`=false` 时全部集中在 `concentrateX/Y/Z` 一个点（经典"凝聚成建筑"）。
- **恢复顺序** `recoveryOrder`：`near_to_far`（由近及远，波浪式建造）/ `far_to_near` / `random`。每方块间隔 `recoveryDelay` tick，形成建造波浪。
- `disableCollisionDuringRecovery=true`：恢复期间关闭方块间碰撞，防止多个方块互相推开导致错位。
- 参考点：随机模式用区域中心，集中模式用集中坐标（决定 near/far 排序）。

### 5.4 方块路径运动 `block_path`（BlockPathActionClip + 415 行调度器 + BlockPathCurve）

**效果**：区域内所有方块变成实体，沿用户在路径编辑器中定义的 3D 曲线**整体位移**（保持相对位置），支持循环、贪吃蛇、风暴三种队形。

- **路径定义**：`UIPathEditorMenu` 中右键点击世界方块添加标记点（`ValuePositions` 存储），至少 2 个点生效。
- **插值**（`BlockPathCurve`，直接复用 BBS 的 `Lerps` 数学库，4 点法 + 端点 clamp）：

| 类型 | 特性 |
|---|---|
| `catmull_rom`（默认） | Catmull-Rom / cubic Hermite，穿过所有控制点，丝滑 |
| `b_spline` | B 样条，不穿点但更平滑 |
| `linear` | 直线连接 |
| `cubic` | 三次插值，穿点 |

- **运动模型**：`方块当前位置 = 方块起始位置 + (路径当前点 - 路径起点)`——所有方块共享同一位移向量，保持队形。
- **concentration（集中度 0~1）**：1 = 排队间距 1 格，**贪吃蛇**沿曲线前进；0 = 完全离散，**风暴**模式——每方块按黄金角（2.39996 rad，斐波那契分布）获得随机偏移角与高度偏移，且偏移随时间旋转，像方块风暴卷过曲线。
- **scatterRadius（散落半径 0~20）**：实际散落半径 = `(1 - concentration) × scatterRadius`。
- `speed`：每 tick 沿曲线前进的距离（按曲线弧长采样计算总长）。`loop=true` 到终点回到起点循环。
- 物理细节：路径方块设置 `pathMovement=true`（跳过 mixin 的弹跳/摩擦处理只留旋转）、`PATH_MOVEMENT` DataTracker 标志（客户端跳过 tick 保插值）、`setPositionWithPrev` 手动管理 prevPos（EntityAccessor 暴露 private 字段）保证 165Hz 插值连续。

### 5.5 飞溅组合 `block_splash_combo`（BlockSplashComboActionClip + combo 支撑类 + 733 行编辑器）

**效果**：把飞溅/振波/路径/反向飞溅组合成一个片段，各自带时间轴、过渡曲线，按序触发、重叠期强度渐变。

- **选区（`BlockSelection`）**：5 种形状——`rectangle`（2 对角点）、`circle`（圆心+边界点定半径）、`triangle`（3 顶点）、`polygon`（N 顶点 + Y 范围）、`random`（中心+半径+密度种子）。采用**预烘焙方案**：编辑器确认时把选区内非空气 BlockPos 全部烘焙进 `bakedBlocks`，运行时不再重算形状，直接遍历烘焙列表 + `anchor` 锚点偏移——保证"所见即所得"。
- **子效果（`SubEffect`）**：每个子效果内嵌一个**完整的** ActionClip 实例 + `startTick` + `duration` + `transitionIn/transitionOut`（BBS `Envelope` 曲线）+ `blendFactor`（与相邻效果的重叠比例，默认 20%）。
- **运行时触发**（`applyAction`）：计算相对 tick → 找出命中子效果 → 仅在子效果的 `startTick` 精确触发 → 把选区包围盒写入子 clip 坐标 → 通过 `IBlockFilterable` 注入**精确选区过滤器**（圆形/三角形不再受包围盒限制）→ 按 `getStrengthAt()` 强度曲线缩放关键参数（飞溅 power、振波 amplitude/impactForce、反向 scatterRadius；路径不缩放）→ 调用子 clip 的 `applyAction`。
- **UI**：选中组合 clip 时，时间轴矩形右半部分绘制子轨道迷你预览（`UIBlockSplashComboRenderer`）；点"打开编辑器"进入 `UIComboEditorOverlay` 独立界面管理子效果。

---

## 6. 物理系统深度剖析（双引擎设计）

### 6.1 双引擎总览

```
sable=true（默认）                          sable=false
┌────────────────────────────┐            ┌────────────────────────────┐
│ 原生引擎                     │            │ 原版回退                     │
│ Rapier3d (Rust) via JNI     │            │ FallingBlockEntity 原生行为  │
│  + PhysicsBlockEntity       │            │  + RotatingFallingBlock     │
│    (读刚体变换的 MC 实体)      │            │    Manager 注入的旋转物理     │
│  + 纯 Java PhysicsEngine     │            │  （纯 Java PhysicsEngine 的  │
│    作为 JNI 失败时的兜底       │            │    完整实现在此模式下备用）     │
└────────────────────────────┘            └────────────────────────────┘
```

原生路径追求**电影级物理质量**（真实堆叠、滚动、摩擦、CCD 防穿透）；回退路径追求**零依赖兼容性**。两条路径的参数体系对齐（重力 -11 m/s² 等 Sable 调校值），切换时视觉风格一致。

### 6.2 JNI 桥接层

`NativePhysicsLibrary` 用 19 个 native 方法封装 Rapier 能力：

```
世界管理：nCreateWorld(gx,gy,gz) / nStep(ptr,dt) / nDestroyWorld / nClearWorld
静态碰撞：nAddStaticBlock(x,y,z) / nAddStaticBox(AABB)
动态刚体：nCreateDynamicBlock(x,y,z,mass,friction,restitution)→handle / nRemoveBody / nGetBodyCount
状态读取：nGetBodyTransform(pos[3],rot[4]四元数) / nGetBodyVelocity / nGetBodyAngularVelocity
          nIsBodySleeping / nWakeUpBody
状态写入：nSetBodyVelocity / nSetBodyAngularVelocity / nApplyImpulse
          nSetBodyTransform(x,y,z,qx,qy,qz,qw) / nSetBodyDamping(linear,angular)
```

`NativeLibraryLoader` 的加载顺序：先 `System.loadLibrary`（开发环境直连 `target/release`），失败后从 JAR 内 `/natives/{os}/` 提取 `.dll/.so/.dylib` 到临时目录再 `System.load`。当前 JAR 内置 **Windows x64** 的 `bbs_physics.dll`（924 KB）；Rapier 侧为 `rapier3d-f64 0.33.0`，启用 PGS 约束求解器、库仑摩擦锥、多点接触流形、CCD、IslandManager 休眠。

`NativePhysicsWorld` 是 `AutoCloseable` 封装（handle==0 抛异常、finalize 兜底释放）。

### 6.3 物理世界生命周期（PhysicsWorldRegistry）

Rapier 的 `pipeline.step()` 必须**每个世界每 tick 调用一次**（而非每实体一次），所以所有世界集中在注册表中，由 `END_SERVER_TICK` 统一步进（`dt=1/20`，2 子步）。

销毁触发条件（三选一，触发即销毁并**连带清理**）：

1. **空世界**：`getBodyCount()==0`（所有方块已 discard）→ 立即销毁，不等超时。
2. **超时**：存活超过 `MAX_WORLD_LIFE`（1200 tick = 60 秒）。
3. **全局清理**：服务器停止 `clearAll()`。

连带清理的内容与原因（**资源泄漏治理史**）：

| 资源 | 单次回放累积量 | 后果（若不清理） |
|---|---|---|
| `PhysicsRecordingManager` 记录 | 300 方块 × 1200 tick = **36 万条 ≈ 43MB** | GC 风暴，"回放次数越多越卡" |
| Recovery 记录 | 约 12 KB/回放 | 内存缓慢增长 + 退出恢复遍历膨胀 |

> 设计权衡：世界销毁时**只清 Recording，不清 Recovery**——Recovery 是 DamageControl 失效时的存档兜底，运行时清理不安全（世界销毁时 BBS 可能还没恢复完方块）。Recovery 由 SERVER_STOPPING 的 `restoreAll + clearAll` 负责最终清理。时序上还有一条保护：刚体创建在 applyAction（同步 tick），步进在 END_SERVER_TICK，同一 tick 内刚体数必 >0，空世界检测不会误伤刚创建的世界。

### 6.4 PhysicsBlockEntity（791 行，原生引擎的实体载体）

继承 `FallingBlockEntity` 复用其渲染器，但 **tick 完全不跑原版逻辑**——每 tick 从 Rapier 刚体读变换（位置 + 四元数）同步到实体。几个关键工程细节：

- **BlockState 同步陷阱**：`FallingBlockEntity.block` 是 private，且 `initDataTracker()` 里就读 `block.isAir()`。子类公开构造函数无法设置它 → NPE。修复：覆写 `initDataTracker()`，在 super 之前用 `FallingBlockEntityAccessor`（`@Accessor("block")`）把 block 设为 AIR，之后客户端从 spawn 数据包同步真实方块状态。
- **单位约定**：native 坐标是方块**几何中心**（米）；MC 实体坐标是**脚部**（feet = center - 0.5）；`setVelocity` 用 blocks/tick（÷20 换算 m/s）。
- **GC 优化**：每 tick 的 JNI 读取复用实例级 `_tmpPos/_tmpRot/_tmpVel/_tmpAngVel` 数组。早期每 tick new 3 个数组 × 500 方块 × 20 tick = 每秒 3 万个临时数组 → GC 风暴；改后零分配。
- **落地稳定检测**：低速 + 近地连续若干 tick → `settled=true`，停止同步防震荡闪烁。
- **客户端物理预测（165Hz 核心）**：客户端 tick 只有 20Hz 且 lerp 是直线，而物理下落是二次曲线——lerp 在 165Hz 屏幕上会"看起来 20 帧"。方案：客户端每帧用同步来的速度+重力+阻尼**自主积分预测位置与四元数旋转**，位置包到达时只做 10% 轻校正防发散。另有 **BBS 导出 i=0 帧检测**：导出时 BBS 的 RenderTickCounterMixin 每帧累加 tickDelta，i=0 表示 tick 未推进，此时陈旧速度继续积分会发散 → 检测 `tickAdvanced` 标志，未推进则跳过积分走 lerp 兜底。
- **物理记录**：每 tick 把 (tick, blockId, 位置/速度/四元数/角速度/方块状态/休眠) 写入 `PhysicsRecordingManager`（按 replayId 分组，replayId 由 `Replay.getId()` MD5 派生，确定性），为未来的"物理烘焙成关键帧动画"功能积累数据。

### 6.5 纯 Java 物理引擎（PhysicsEngine，489 行）

Sable 风格的简化刚体物理，作为原生库不可用时的兜底（也支撑振波等非 Rapier 场景）。参数全部对齐 Sable 调校：

| 常量 | 值 | 说明 |
|---|---|---|
| GRAVITY | -11 m/s² | Sable 调校值（非现实 -9.8） |
| LINEAR_DAMPING | 0.09/s | Sable DEFAULT_UNIVERSAL_DRAG |
| ANGULAR_DAMPING | 0.5/s | 空中角阻尼 |
| ROLLING_FRICTION | 0.3/s | 接地时角速度衰减（比空中慢 → 能滚动） |
| restitution / friction / mass | 0 / 1.0 / 1.0 | Sable 默认 |
| BLOCK_INERTIA_FACTOR | 1/6 | 单位立方体惯性 I = m/6 |
| HALF_EXTENT | 0.48 | AABB 半边长（略小于 0.5 防浮点穿透） |
| MAX_VELOCITY | 30 m/s | 速度上限防穿透 |
| SLEEP_THRESHOLD / SLEEP_DELAY | 0.05 m/s / 40 tick | 休眠阈值（连续 2 秒低速+接地） |

算法要点：

- **积分**：半隐式（辛）欧拉；线性阻尼用 Rapier 风格 `v *= exp(-damping·dt)`。
- **4 子步**：每 tick 分 4 个子步，每个子步都处理实体间碰撞（修复"只在 tick 末尾处理导致重叠"的 bug）。
- **接地状态机（v1.9.3 关键修复）**：grounded 时跳过重力与 Y 向下碰撞，打破"推回→重力→碰撞→推回"抖动循环；每步检查脚下 0.02m 内是否还有支撑，无则解除接地。
- **Y 轴碰撞优先**：AABB 重叠时选最小穿透轴，但 Y 轴优先判定，落地精确推到方块顶 `by+1+HALF_EXTENT`（修复"遁地"bug）。
- **碰撞不杀角速度**：落地不强制衰减旋转，由滚动摩擦缓慢衰减——方块落地后能继续翻滚。
- **实体间碰撞**（`resolveEntityCollision`）：AABB vs AABB，最小穿透轴按质量倒数比例推开双方，冲量法 `j = -(1+e)·relVel / (invMassA+invMassB)` 反弹，切向摩擦 50% 衰减；用 **UUID 比较约定**（只处理 UUID 较小的一方）避免同一对碰撞被双重处理。
- **休眠**：连续 40 tick 低速+接地 → 清零速度、精确贴合地面，停止计算。

### 6.6 RotatingFallingBlockManager（681 行，原版实体的旋转物理）

服务端为每个标记旋转的 `FallingBlockEntity` 维护一套轻量物理状态（三轴角速度/角度、弹跳恢复系数 0.4、地面摩擦 0.8、角阻尼 0.99/tick、质量、弹跳计数、休眠计数），由 `FallingBlockEntityPhysicsMixin` 在实体 tick 末尾驱动：

- 弹跳：落地反弹保留 40% 能量，逐次衰减
- 摩擦：地面时水平速度每 tick ×0.8
- 力矩效应：碰撞时按冲量调整角速度（模拟力臂）
- 挤压变形：落地冲击设置 `SQUASH_AMOUNT/SQUASH_DIRECTION`，客户端按体积守恒做 scale 变形（压扁量分配给另两轴），每帧自然衰减
- 归位旋转平滑过渡（smoothRotationStop）：接近地面（`rotationStopDistance`）时角速度递减，最后 `rotationResetDuration` tick 内把角度插值归零再变方块——消除"落地瞬间角度闪现"
- 休眠：低速连续若干 tick 后 stopped，省性能
- 旋转数据通过 DataTracker 同步（服务端权威），客户端另有每帧积分补偿（见第 9 章）

---

## 7. 服务端调度器家族

四个调度器 + 物理注册表，全部由 `ServerTickEvents.END_SERVER_TICK` 每 tick 驱动（`BlockSplashAddon.onInitialize` 中统一注册），全部使用 `CopyOnWriteArrayList` 存任务（遍历时增删安全）：

| 调度器 | 职责 | 核心机制 |
|---|---|---|
| `BlockShockwaveScheduler` | 振波的分批延迟震动 | 每方块一个 ShakeTask（含 delay/amplitude/frequency/decay/模式/倾斜参数）；地震/涟漪/混沌模式的随机状态机；落地冻结保留倾斜 |
| `BlockSplashReverseScheduler` | 反向飞溅四阶段状态机 | WAITING→RECOVERING（直线插值飞行）→SNAPPING（角度归零）→DONE；动画方块重叠保留防闪烁 |
| `BlockPathScheduler` | 路径运动 | 每方块 queueIndex × concentration 决定排队间距；黄金角散点；`setPositionWithPrev` 手动管理 prevPos |
| `BlockSplashAnimationScheduler` | 非 solidify 方块的缩小消失 | 前 N-20 tick 正常物理，最后 20 tick 写 `SHRINK_PROGRESS`（ease-in quadratic：progress²，先慢后快），到 1.0 后 discard；含 +100 tick 强制超时保护 |
| `PhysicsWorldRegistry.tickAll` | Rapier 世界统一步进 | 见 6.3 |

所有调度器都有 `clearAll()`（SERVER_STOPPING 时调用：销毁实体/恢复方块/清任务）与 `getActiveTaskCount()`（调试）。

---

## 8. Mixin 注入体系（14 个 Mixin）

### 8.1 服务端/公共（bbsblocksplash.mixins.json，5 个）

| Mixin | 目标 | 注入点 | 作用 |
|---|---|---|---|
| `BbsEntityMixin` | `Entity`（class_1297） | `isOnGround()` HEAD 可取消 | **非实体化模式的核心**：NO_SOLIDIFY=true 时强制返回 false，骗过原版"落地变方块"判定。放在 Entity 层是因为 `isOnGround()` 定义在 Entity，Loom remap 只查目标类直接方法表，放在 FallingBlockEntity mixin 上会映射失败 |
| `FallingBlockEntityDataMixin` | `FallingBlockEntity` | `initDataTracker()` RETURN | 注册 15 个自定义 TrackedData 字段（见 8.3） |
| `FallingBlockEntityPhysicsMixin` | `FallingBlockEntity` | `tick()` HEAD + RETURN（644 行） | HEAD：NO_SOLIDIFY 时重置 `timeFalling=0`（防原版 600 tick 超时 dropItem+discard）；RETURN：对标记实体注入 RotatingFallingBlockManager 完整物理（弹跳/摩擦/旋转/实体碰撞/休眠），并记录落地方块位置供退出恢复 |
| `EntityAccessor` | `Entity` | @Accessor 接口 | 暴露 private 的 `onGround` 与 `prevX/prevY/prevZ`（手动管理渲染插值锚点） |
| `FallingBlockEntityAccessor` | `FallingBlockEntity` | @Accessor 接口 | 暴露 private 的 `block` 字段（PhysicsBlockEntity 构造必需；方法带 `bbs$` 前缀防与其他 Mixin 冲突——AbstractMethodError 教训） |

### 8.2 客户端（bbsblocksplash.client.mixins.json，9 个）

| Mixin | 目标 | 作用 |
|---|---|---|
| `FallingBlockEntityRendererMixin` | FallingBlockEntityRenderer（class_901） | render HEAD：应用每帧旋转积分、缩小 scale、落地挤压 scale（体积守恒）、绕几何中心 (0,0.5,0) 的 YXZ 欧拉旋转；RETURN：pop 矩阵栈 |
| `ClientFallingBlockEntityTickMixin` | `FallingBlockEntity` | PATH_MOVEMENT=true 时取消整个客户端 tick，防止 `prevPos = pos` 破坏插值（165Hz 下只有 20 帧有效位置的根因） |
| `UIClipMixin` / `UIClipRenderersMixin` | BBS UIClip 工厂 | 把组合片段的自定义 UI/渲染器挂入 BBS 编辑器 |
| `UIReplayListMixin` + 3 个 Accessor | BBS 回放列表 UI | 扩展 BBS 回放列表面板（配合 `IFolderView` 接口） |
| `UIListAccessor` | BBS 列表组件 | 供上述 Mixin 访问内部字段 |

> **Mixin 工程教训**（源码注释中记录的真实踩坑）：① Mixin 类不允许定义非 private static 字段 → TrackedData 全部挪到普通类 `FallingBlockRotationData`；② Mixin 包下的内部类会被 Mixin 处理器特殊处理 → 客户端状态类挪到 `ClientRotationStateManager`；③ 命令标签（command tag）不会自动同步到客户端 → 标志位全部改用 DataTracker。

### 8.3 DataTracker 字段注册表（FallingBlockRotationData，15 个字段）

| 字段 | 类型 | 用途 |
|---|---|---|
| `IS_ROTATING` | Boolean | 旋转物理开关标志 |
| `ROTATION_X/Y/Z` | Float ×3 | 累计旋转角度（度） |
| `ANGULAR_VEL_X/Y/Z` | Float ×3 | 角速度（度/tick，客户端每帧积分用） |
| `SQUASH_AMOUNT` / `SQUASH_DIRECTION` | Float/Int | 落地挤压强度与方向（客户端本地衰减，不需持续同步） |
| `PATH_MOVEMENT` | Boolean | 路径运动标志（客户端跳过 tick） |
| `NO_SOLIDIFY` | Boolean | 非实体化标志（服务端拦 isOnGround，客户端渲染缩小） |
| `SHRINK_PROGRESS` | Float | 缩小消失进度 0→1（ease-in quadratic） |
| `PHYSICS_ROT_X/Y/Z/W` | Float ×4 | PhysicsBlockEntity 的旋转**四元数**（Rapier 直出） |

> **ID 空间陷阱**：所有字段用 `FallingBlockEntity.class` 注册（而非 PhysicsBlockEntity.class）——DataTracker 的字段 ID 按注册类分配，子类继承父类字段但共享同一 ID 空间，用子类注册会 Duplicate id value 崩溃。

---

## 9. 网络同步与 165Hz 高刷新率渲染

这是本插件工程含量最高的部分。问题链与解法：

```
问题：MC 逻辑 20Hz，玩家屏幕 144/165Hz
  ├─ 旋转卡顿：DataTracker 每 tick（50ms）同步角度 → 同一 tick 内每帧角度相同
  │    解法①：服务端同步"角速度"而非只有角度
  │    解法②：客户端 ClientRotationStateManager 每帧自积分
  │            旋转增量 = 角速度 × (当前tickDelta - 上一帧tickDelta)
  │            每 tick 用服务端角度校准一次防累积误差
  │
  ├─ 物理下落不自然：lerp 是直线，物理是二次曲线（重力加速）
  │    解法：PhysicsBlockEntity 客户端自主物理预测
  │            每帧用同步来的 v/g/阻尼积分预测位置+四元数
  │            位置包到达只做 10% 轻校正防发散
  │            （附 BBS 导出 i=0 帧检测：tick 未推进时跳过积分）
  │
  └─ 路径运动位置只有 20 帧：客户端 tick 里 prevX=getX() 把插值锚点抹平
       解法①：PATH_MOVEMENT 标志 → 客户端 Mixin 取消整个 tick()
       解法②：服务端 setPositionWithPrev 手动管理 prevPos
```

另外一组经验：`prevRenderRotation/renderRotation` 独立维护四元数插值（`Quaternionf.slerp` 语义），`lastTickDelta` 存在**每个实体实例**上而非渲染器单例（多方块场景每方块独立计算帧增量，避免慢动作 bug）。

---

## 10. 存档安全设计（三层恢复保险）

**威胁模型**：动画把方块变成空气/实体后，游戏在任意时刻可能退出（崩溃、强关、断电）。任何未恢复的改动都是**永久地形破坏**。

```
第 1 层：BBS DamageControl（宿主提供）
    回放正常结束 → 自动恢复所有方块变更、移除生成的实体
    覆盖：正常播完 ✓   中途退出 ✗
第 2 层：各特效自己的恢复逻辑
    振波 restoreAfter / 反向飞溅 SNAPPING→DONE / 飞溅 Recovery 记录
    覆盖：动画自身流程 ✓   异常中断部分覆盖 ✗
第 3 层：BlockSplashRecoveryManager（本插件兜底，本层解决"播到一半强退"）
    触发时：recordBlock(位置→原始 BlockState) + recordEntity(实体 UUID)
            + recordLandedBlock(实体落地后放置的方块位置)
    SERVER_STOPPING：restoreAll(server) 按严格顺序恢复：
      ① 先 discard 所有飞溅实体（防止恢复后继续落地变方块）
      ② 清除落地后变成的方块（设空气，flags=0x12 不触发更新防连锁）
      ③ 恢复原始方块状态（同 flags）
    最后 clearAll() 清空记录
```

配套的时序约束（SERVER_STOPPING 回调内的调用顺序，见 `BlockSplashAddon` 注释）：

```
三大调度器 clearAll()（清未完成任务）
→ PhysicsWorldRegistry.clearAll()（销毁 Rapier 世界释放 native 内存 + 清 Recording；
   注意它刻意不碰 Recovery 记录）
→ BlockSplashRecoveryManager.restoreAll()（用 Recovery 记录兜底恢复方块）
→ BlockSplashRecoveryManager.clearAll()
```

内存开销评估（源码注释）：3 万方块 × 约 40 字节 ≈ 1.2MB，退出恢复约 300ms——完全可接受。`BlockPos` 一律 `method_10062()`（toImmutable）防可变 BlockPos 被复用后记录失真。

---

## 11. 性能设计与资源泄漏治理

| 机制 | 上限/策略 | 目的 |
|---|---|---|
| 区域方块数 | 30,000 上限，超出拒绝执行 | 防卡死 |
| 物理方块抽样 | 2×2×2 分组抽样至 maxPhysicsBlocks（默认 300；i3-10105 建议 300，高端机可 500–1000） | 刚体数量 × 每 tick 2 子步的 CPU 开销 |
| 静态碰撞体 | 2000 个上限 | Rapier 世界内存 |
| 空 world 即销毁 | getBodyCount()==0 → 立即销毁 | 不等 60s 超时，白跑 step 纯浪费 |
| Recording 清理 | 世界销毁连带清（43MB/回放） | "越播越卡"根因 |
| JNI 零分配 | 实例级复用临时数组 | 3 万临时数组/秒 → 0 |
| 实体 GC | 每实体 settled 检测停同步；客户端旋转状态 >50 个时清理 5 秒未访问项 | 减少无谓同步/内存 |
| 线程安全 | 调度器 CopyOnWriteArrayList、Recovery/Registry ConcurrentHashMap | 服务端 tick 与事件回调并发 |
| 碰撞去重 | 实体对只由 UUID 较小方处理；AABB 近邻查询 O(N)（100–300 方块够用） | 避免双重冲量 |

---

## 12. 使用指南

### 12.1 安装

1. 安装 Fabric Loader ≥ 0.15.0 + Minecraft **1.20.1**
2. 放入 Fabric API
3. 放入 BBS 模组（1.20.1 版）
4. 放入 `bbs_Block_Splash`（本插件）
5. 启动游戏。原生物理仅内置 Windows x64 DLL（其他平台请用 sable=false 或自行编译对应平台 native 库）

### 12.2 区域选择木棍

创造物品栏「BBS」分类或原版工具栏中获取 `bbsblocksplash:region_selector`：

- **左键方块**：选第一个点 pos1（不破坏方块，`AttackBlockCallback` 拦截）
- **右键方块**：选第二个点 pos2（不触发方块交互，不会开门/开箱）
- **Shift+右键**：查看当前已选坐标与区域内非空气方块数
- 手持木棍时，已选区域会显示**白色粒子描边**（单点显示 8 角标记，双点显示立方体 12 条边）
- 在各特效编辑面板点「粘贴坐标」一键填入

### 12.3 快速上手：做一个"建筑爆炸"镜头

1. 建好或找一栋建筑，用木棍框选它
2. 在 BBS 行为编辑器给演员回放添加 **block_splash** 片段，粘贴坐标
3. 调参示例——末日崩塌：`power=2.0`、`dirY=1.2`、`shape=sphere`、`sable=开`、`gravityY=-11`、`impactBoost=2.0`、`solidify=关`、`animationDuration=8`
4. 播放回放预览；用 BBS 摄影机环绕镜头拍摄
5. 需要中途建起效果 → 加 **block_splash_reverse** 片段（`randomSplash=关` + 集中坐标在天空 → 方块从天而降拼成建筑）
6. 地面震动 → **block_shockwave**（`mode=earthquake` + `realisticAngle=开`）
7. 方块风暴 → **block_path**（画一条曲线，`concentration=0` + `scatterRadius=8`）

### 12.4 调参速查

| 想要的效果 | 关键参数 |
|---|---|
| 飞得更远 | ↑power、↓linearDamping、↑impactBoost |
| 炮弹直线射击 | shape=ray + 方向向量对准目标 |
| 烟花爆开 | shape=sphere |
| 龙卷风卷起 | shape=spiral + dirY≈1 |
| 月球轻飘 | gravityY=-24、angularDamping↑ |
| 岩浆球慢落 | linearDamping=0.2、gravityY=-4 |
| 落地方块留在地上堆成山 | solidify=true |
| 演完自动消失不留垃圾 | solidify=false + animationDuration |
| 永久陨石坑地貌 | 振波 realisticAngle=true + restoreAfter=false |
| 贪吃蛇方块流 | block_path concentration=1 |
| 方块风暴 | block_path concentration=0 + scatterRadius 大 |

---

## 13. 构建、开发与二次开发

### 13.1 环境与依赖

| 组件 | 版本 |
|---|---|
| Minecraft | 1.20.1（fabric.mod.json 中 `=1.20.1` 精确匹配） |
| Fabric Loader | ≥ 0.15.0（原始构建用 0.16.14） |
| Fabric Loom | 1.15.5（见 releases/MANIFEST.MF） |
| Gradle | 9.2.0 |
| Java | 17+（Mixin compatibilityLevel JAVA_17） |
| BBS | 任意 1.20.1 版（`"bbs": "*"`），建议与 Wemppy4/bbs-fs `1.20.1` 分支同源 |
| Rust（可选） | 仅当要重编 `bbs_physics.dll` 时需要；`rapier3d-f64 0.33.0` |

### 13.2 为什么源码里全是 class_xxxx

本源码包（sources jar）发布时的编译映射命名空间是 **intermediary**（Fabric 中间映射）。源码中所有 `class_数字` / `method_数字` / `field_数字` 都是 intermediary 名。常用对照：

| Intermediary | Yarn 名 | 说明 |
|---|---|---|
| `class_1540` | `FallingBlockEntity` | 下落方块实体 |
| `class_1297` | `Entity` | 实体基类 |
| `class_1299` | `EntityType` | 实体类型 |
| `class_1937` / `class_3218` | `World` / `ServerWorld` | 世界 / 服务端世界 |
| `class_2338` | `BlockPos` | 方块坐标 |
| `class_2680` | `BlockState` | 方块状态 |
| `class_2246` | `Blocks` | 方块注册表（`field_10124`=AIR） |
| `class_1792` | `Item` | 物品 |
| `class_2378` | `Registries` | 注册表（`method_10230`=register） |
| `class_2960` | `Identifier` | 命名空间 ID |
| `class_2940/2945` | `TrackedData`/`DataTracker` | 实体数据同步 |
| `method_8652` | `setBlockState` | 设方块（flags=0x12 不触发邻居更新） |
| `method_8320` | `getBlockState` | 取方块 |
| `method_26215` | `isAir` | 是否空气 |
| `method_5667` | `getUuid` | 实体 UUID |
| `method_31472` | `discard` | 移除实体 |
| `method_27983` | `getRegistryKey` | 世界维度 RegistryKey |
| `method_40005` | `FallingBlockEntity.spawnFromBlock` | 方块→实体转换 |
| `method_5841` | `getDataTracker` | 取 DataTracker |
| `method_12778/12789` | DataTracker `set`/`get` | 数据读写 |
| `method_18800` | `setVelocity` | 设速度（blocks/tick） |
| `field_5960` | `noClip` | 无碰撞标志 |
| `field_6037` | `hurtEntities` | 砸落伤害标志 |
| `field_6012` | `age` | 实体年龄 |
| `field_9236` | `isClient` | 是否客户端 |
| `class_4587/4597` | `MatrixStack`/`VertexConsumerProvider` | 渲染矩阵/顶点 |
| `class_901` | `FallingBlockEntityRenderer` | 下落方块渲染器 |

在 IDE 里导入完整 Gradle 工程（Loom 会自动附加 Yarn 映射）后即可看到可读名；本仓库按原始版本保留 intermediary 名以忠实还原源码包。

### 13.3 重新构建（要点）

本仓库保留了原始源码形态，若要重新打包：

1. 建一个标准 Fabric 1.20.1 Loom 工程（`fabric.mod.json`、两个 mixins.json 已在 `src/main/resources`）
2. `src/main/java` 挂入源码；依赖中加入 BBS（本地 maven 或把 bbs-fs 1.20.1 分支作为 `mavenLocal`/包含依赖构建）
3. native 库已在 `src/main/resources/natives/windows/`，打包时会被带上
4. `fabric.mod.json` 的 `"version": "${version}"` 由 Gradle processResources 替换，发布版本填 2.0.4

### 13.4 二次开发建议

- **加新特效**：继承 `ActionClip` + 实现 `IBlockFilterable`（若要支持组合选区），在 `BlockSplashAddon.onRegisterSettings` 注册，客户端 `BlockSplashClient` 注册 UI 面板；执行遵循"applyAction 只规划、调度器驱动"的模式，并**务必**在规划时写入 `BlockSplashRecoveryManager`
- **跨平台 native**：为 macos/linux 编译同名库放入 `natives/{macos,linux}/`，加载器会自动按 OS 提取
- **物理烘焙**：`PhysicsRecordingManager` 已按 replayId 记录全部物理帧，可直接读取转换为 BBS 关键帧（这是预留的下一步功能）

---

## 14. 已知限制与 FAQ

**Q: 非 Windows 平台能用吗？**
能。把 `sable` 关掉走原版/纯 Java 物理路径；或自行编译对应平台的 `bbs_physics` 库放进 JAR。

**Q: 动画播到一半退出游戏，地图会坏吗？**
不会。三层恢复保险中的第三层（SERVER_STOPPING 兜底恢复）专门为此设计，见第 10 章。

**Q: 组合片段的圆形选区会波及选区外方块吗？**
不会。组合通过 `IBlockFilterable` 把预烘焙的精确方块列表注入子效果，子效果直接用过滤器列表而非包围盒。

**Q: 为什么振波默认 `restoreAfter=false` 还会恢复地形？**
冲击坑是视觉保留效果（实体冻结在倾斜角度），但方块原始状态在触发时就被无条件记录——正常回放结束由 DamageControl 恢复，中途退出由 RecoveryManager 恢复。

**Q: 30000 方块和 300 物理方块是什么关系？**
30000 是"可以被清空/恢复"的区域上限；Sable 模式下真正参与刚体物理的默认只有 300 个（抽样保证空间均匀），其余方块被清空后只作为恢复记录，不生成实体。

**已知限制**：区域为世界坐标绝对值（组合选区支持锚点偏移，普通片段可跟随回放平移 shift）；含水/复杂方块（楼梯等）以完整方块 AABB 参与碰撞（Rapier 侧支持 `addStaticBox` 但当前注入按整方块）；单世界实例的记录用维度 RegistryKey 索引，同维度多回放叠加时恢复以最后一次为准。

---

## 15. 致谢与许可

- **作者**：zhongend（本插件全部原创实现）
- **[BBS 模组 / bbs-fs](https://github.com/Wemppy4/bbs-fs)**（MIT）—— 本插件的前置与 API 来源；Clip/Value/Envelope/UI 体系均出自 BBS
- **[Sable](https://github.com/ryanhcode/sable)** —— 物理参数调校（重力 -11、阻尼体系、休眠机制）与 native 库加载方案的参考
- **[rigid-body](https://github.com/Polari-Stars-MC/rigid-body)**（Polari-Stars-MC）—— Rapier3d JNI 桥接思路参考
- **Rapier3d**（`rapier3d-f64 0.33.0`，Apache-2.0）—— Rust 刚体物理引擎
- 原始源码包：`releases/bbs-Block_Splash-2.0.4-sources.jar`

本项目以 **MIT** 许可证发布（与 BBS 一致）。欢迎 fork、二次创作，但请保留原作者署名。
