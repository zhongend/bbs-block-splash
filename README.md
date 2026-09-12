# BBS Block Splash（方块飞溅）

> **Minecraft 1.20.1 · Fabric · BBS 模组扩展插件（Addon）**
>
> 为 [BBS 模组](https://modrinth.com/mod/bbs) 的电影/回放系统添加 **5 种方块级特效 Action Clip**：
> 区域方块飞溅、方块振波、反向飞溅（自动建造）、方块路径运动、飞溅组合。
> 内置 **三档物理引擎**（Rapier3d 原生 / **BBS 物理引擎 Jolt（v2.1.0 新增）** / 原版下落方块），
> 编辑面板一键切换，支持真实弹跳、滚动、旋转、堆叠，并且拥有**完整的存档保护机制**（动画中途退出游戏也不会破坏地图）。

| 项目信息 | 内容 |
|---|---|
| 模组 ID | `bbsblocksplash` |
| 模组名称 | `bbs_Block_Splash` |
| 当前版本 | 2.2.0 |
| 作者 | **zhongend** |
| 许可证 | MIT |
| 前置 | Minecraft **1.20.1**（精确匹配）、Fabric Loader ≥ 0.15.0、Fabric API、**BBS 模组** |
| Java 版本 | ≥ 17 |
| 代码规模 | 约 15,500 行 Java 源码（60 个类） + Rapier3d / Jolt 双物理库（Rust / C++ 原生） |
| 参考项目 | [Wemppy4/bbs-fs](https://github.com/Wemppy4/bbs-fs)（BBS 模组源码，MIT）、[Wemppy4/bbs-physics-engine](https://github.com/Wemppy4/bbs-physics-engine)（Jolt 引擎用法参照）、[Sable](https://github.com/ryanhcode/sable)、[rigid-body](https://github.com/Polari-Stars-MC/rigid-body) |

---

## 目录

- [1. 第一性原理：这个插件为什么存在、如何思考](#1-第一性原理这个插件为什么存在如何思考)
- [2. 整体架构](#2-整体架构)
- [3. 目录结构](#3-目录结构)
- [4. 与 BBS 模组的对接方式](#4-与-bbs-模组的对接方式)
- [5. 五大特效详解](#5-五大特效详解)
- [6. 物理系统深度剖析（三引擎设计）](#6-物理系统深度剖析三引擎设计)
- [7. 服务端调度器家族（含回放时钟）](#7-服务端调度器家族)
- [8. Mixin 注入体系（15 个 Mixin）](#8-mixin-注入体系14-个-mixin)
- [9. 网络同步与高刷新率渲染](#9-网络同步与高刷新率渲染)
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

5. **渲染必须突破 Minecraft 20 TPS 的天花板，但只能靠"插值"而不能靠"预测"。**
   服务器 20 tick/秒，但屏幕可能是 144Hz/165Hz。直觉上会想"客户端用速度积分往前猜"，
   但那是**开环预测**：网络会丢包/乱序/延迟，猜出来的位置没有权威校正源，误差会累积成漂移与抖动。
   正确的做法是把渲染位置定义为「两个已确认权威样本之间的插值函数」——
   纯函数、无累积状态、与帧率无关，因此实时预览与 BBS 导出逐帧一致。
   本插件用**二次拉格朗日插值（最近 3 个权威 tick 样本）**，对自由落体这类恒定加速度运动是精确解，
   旋转用两样本 slerp——详见 [第 9 章](#9-网络同步与高刷新率渲染)。

6. **物理必须由回放时钟驱动，而不是由服务端 tick 驱动。**
   这是"电影工具"与"游戏玩法"的分水岭：拍摄需要**定格、倒拖、可复现**。
   旧实现把物理步进挂在 `ServerTickEvents.END_SERVER_TICK` 上，于是
   暂停回放时方块仍在继续下落、拖动时间轴时物理与画面不对应、导出视频不可复现。
   正确挂载点是 BBS 的 `ActionClip.applyRange()`——它在"片段覆盖的每个 tick"被调用，
   且只在回放时钟真正推进时调用——详见 [第 7 章](#7-服务端调度器家族)。

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
│  │ BlockSplashActionClip        方块飞溅（Rapier/Jolt/原版）  │            │
│  │ BlockShockwaveActionClip     方块振波（7 种模式）           │            │
│  │ BlockSplashReverseActionClip 反向飞溅（自动建造）           │            │
│  │ BlockPathActionClip          路径运动（4 种样条插值）        │            │
│  │ BlockSplashComboActionClip   组合（选区+子效果+过渡）        │            │
│  └──────┬──────────────────────────────────────────┬───────┘            │
│         │ 一次性规划（收集方块/算速度/记恢复）          │ 注册长期任务         │
│  ┌──────▼──────────────────┐            ┌──────────────────▼───────────┐  │
│  │   物理层（三引擎）        │            │  调度器层（每 tick 驱动）       │  │
│  │                         │            │                              │  │
│  │ PhysicsBackendWorld     │            │ BlockShockwaveScheduler      │  │
│  │  ├─ NativePhysicsWorld  │            │ BlockSplashReverseScheduler  │  │
│  │  │  └─ JNI → bbs_physics│            │ BlockPathScheduler           │  │
│  │  │     (Rapier3d, Rust) │            │ BlockSplashAnimationScheduler│  │
│  │  └─ JoltPhysicsWorld    │            │ PhysicsWorldRegistry.driveTo │  │
│  │     └─ jolt-jni (Jolt)  │            └──────────────────┬───────────┘  │
│  │ PhysicsBlockEntity      │                               │              │
│  │  └─ 读刚体变换→MC实体     │                               │              │
│  │ PhysicsEngine(纯Java回退)│                               │              │
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
│  │ PhysicsBlockEntityRenderer 高刷插值渲染（二次插值+slerp）             │   │
│  │ ClientRotationStateManager 每帧旋转积分状态（原版路径用）              │   │
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
        ▼ 之后每个回放 tick（ActionClip.applyRange —— 回放时钟驱动）
   [Sable/Jolt] PhysicsWorldRegistry.driveTo() → 刚体步进 →
               PhysicsBlockEntity 读刚体变换 → 同步到 MC 实体 → 记录到 Recording
               暂停回放 → applyRange 不再被调用 → 物理一起定格
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
├── build.gradle / settings.gradle / gradle.properties ← Gradle 构建（Loom 1.15.5）
├── gradlew / gradlew.bat / gradle/wrapper/            ← Gradle 9.2.0 wrapper
├── buildscript/
│   └── JoltSmokeTest.java             ← Jolt 后端独立冒烟测试（无 MC 依赖，10 项检查）
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
    │   ├── BlockSplashRecoveryManager.java  存档恢复管理器（退出保护核心，位置去重）
    │   ├── BlockSplashReplayHook.java        回放停止钩子（由 ActionPlayerStopMixin 调用）
    │   ├── BlockSplashReverseScheduler.java 反向飞溅调度器（932 行，四阶段状态机）
    │   ├── FallingBlockRotationData.java    DataTracker 字段注册表（15 个同步字段）
    │   ├── RegionSelectionCache.java        区域选择坐标缓存
    │   ├── RotatingFallingBlockManager.java 原版实体旋转物理管理器（681 行）
    │   ├── actions/                         五大特效 ActionClip
    │   │   ├── BlockSplashActionClip.java         方块飞溅（814 行，最核心，三档引擎）
    │   │   ├── BlockShockwaveActionClip.java      方块振波
    │   │   ├── BlockSplashReverseActionClip.java  反向飞溅
    │   │   ├── BlockPathActionClip.java           路径运动
    │   │   ├── BlockSplashComboActionClip.java    飞溅组合
    │   │   ├── combo/                       组合系统的支撑类
    │   │   │   ├── BlockSelection.java      5 种选区模式（矩形/圆/三角/多边形/随机）
    │   │   │   ├── IBlockFilterable.java    方块过滤器接口（精确选区传递）
    │   │   │   ├── SubEffect.java           子效果（内嵌完整 ActionClip + 时间 + 过渡曲线）
    │   │   │   └── SubEffectList.java       子效果列表
    │   │   └── physics/                     物理子系统（14 个类）
    │   │       ├── PhysicsBackendWorld.java   双引擎统一接口（v2.1.0）
    │   │       ├── JoltRuntime.java           Jolt 上下文 + native 提取加载（v2.1.0）
    │   │       ├── JoltPhysicsWorld.java      Jolt 刚体世界（v2.1.0，BBS 物理引擎后端）
    │   │       ├── NativeLibraryLoader.java    DLL/SO/Dylib 提取加载器
    │   │       ├── NativePhysicsLibrary.java   JNI 绑定（19 个 native 方法）
    │   │       ├── NativePhysicsWorld.java     Rapier 世界封装（实现统一接口）
    │   │       ├── PhysicsBlockEntity.java     物理方块实体（791 行，165Hz 预测核心）
    │   │       ├── PhysicsBlockEntityTypes.java 实体类型注册
    │   │       ├── PhysicsEngine.java          纯 Java 物理引擎（Sable 风格回退）
    │   │       ├── PhysicsEntityManager.java   实体注册表（近邻查询）
    │   │       ├── PhysicsRecording.java       单帧物理记录（不可变）
    │   │       ├── PhysicsRecordingManager.java 按 replayId 分组的记录缓存
    │   │       ├── PhysicsState.java           物理状态数据类
    │   │       └── PhysicsWorldRegistry.java   物理世界注册表（回放时钟驱动 + 生命周期 + 确定性回退重放）
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
    │   └── mixin/                           6 个服务端 Mixin（含 ActionPlayerStopMixin 回放停止钩子）
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
| `sable`（sableEnabled） | bool **true** | — | 旧版兼容字段（新存档请用 engine；engine 为空时由它决定 Sable/原版） |
| `engine` | string "" | — | **物理引擎**：空=跟随 sable（旧存档兼容），`sable`=Rapier 原生，`jolt`=BBS 物理引擎（Jolt），`vanilla`=原版下落。由编辑面板「物理引擎」按钮设置 |
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
6. 之后每个**回放 tick** 由 `BlockSplashActionClip.applyRange()` 调用
   `PhysicsWorldRegistry.driveTo(key, localTick)`，以 **2 子步**（Rapier）/ **3 子步**（Jolt）
   步进刚体世界，实体读取刚体位置 + 四元数旋转同步渲染。
   **暂停回放 → 不再步进 → 物理定格**（这是与旧版"END_SERVER_TICK 步进"的本质区别）。

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

## 6. 物理系统深度剖析（三引擎设计）

### 6.1 三引擎总览（v2.1.0 起）

`block_splash` 片段的编辑面板提供 **「物理引擎」三选一切换按钮**：

```
① Sable 物理（Rapier，默认）     ② BBS 物理引擎（Jolt，v2.1.0 新增）    ③ 原版下落
┌────────────────────────────┐   ┌────────────────────────────┐      ┌────────────────────────────┐
│ Rapier3d (Rust) via JNI     │   │ Jolt Physics (C++) via      │      │ FallingBlockEntity 原生行为  │
│  + PhysicsBlockEntity       │   │  jolt-jni（与               │      │  + RotatingFallingBlock     │
│    (读刚体变换的 MC 实体)     │   │  bbs-physics-engine 同源）   │      │    Manager 注入的旋转物理     │
│  + 纯 Java PhysicsEngine     │   │  + PhysicsBlockEntity       │      │                             │
│    作为 JNI 失败时的兜底       │   │  （同一套实体与记录系统）      │      │                             │
└────────────────────────────┘   └────────────────────────────┘      └────────────────────────────┘
```

三档共享完全相同的上层代码路径（采样 → 清场 → 静态碰撞注入 → 形状速度算法 → 恢复系统）——
①② 两种刚体引擎只在世界创建与刚体调用层不同（统一抽象 `PhysicsBackendWorld`），保证切换引擎不改变效果手感；③ 用于极致性能场景。

### 6.1.1 BBS 物理引擎（Jolt 后端，v2.1.0 新增）

[Jolt Physics](https://github.com/jrouwe/JoltPhysics) 是《地平线：西之绝境》所用的工业级 C++ 刚体引擎，
本插件经 stephengold 的 **jolt-jni** 绑定（`com.github.stephengold:jolt-jni-Windows64:1.0.0`，ReleaseSp 单精度）接入，
与 [Wemppy4/bbs-physics-engine](https://github.com/Wemppy4/bbs-physics-engine) 采用**同一个物理引擎**——用法与调校也参照了它的引擎层（引擎初始化、层表、每 tick 3 子步、CCD、质量覆盖），但实现为本插件自有的适配代码（面向世界方块，而非 BBS 表单）。

新增类（`actions/physics/` 包）：

| 类 | 职责 |
|---|---|
| `PhysicsBackendWorld` | 双引擎统一接口（17 个方法：创建/移除刚体、变换读写、阻尼、步进、生命周期） |
| `JoltRuntime` | 进程级 Jolt 上下文：native 提取加载 + `registerDefaultAllocator/newFactory/registerTypes`；**失败是正常结果**（平台不支持、缺库）→ `available()=false`，效果自动回退原版路径并只记一次日志 |
| `JoltPhysicsWorld` | 单个 Jolt 世界（`PhysicsSystem` + 双层表 + 单线程任务系统），`AutoCloseable` |
| （`NativePhysicsWorld`/`PhysicsWorldRegistry`/`PhysicsBlockEntity`） | 改为实现同一接口，原有 Rapier 逻辑零改动 |

与 bbs-physics-engine 一致的关键调校（为什么 Jolt 后端的物理手感"对"）：

| 调校 | 值 | 理由 |
|---|---|---|
| 单位 | 1 方块 = 1 米 | Jolt 在 10cm 以下分辨率不佳；方块作为米正好 |
| 子步进 | 每 tick（50ms）**3 次**求解（≈60Hz） | 50ms 一步对求解器太长，3 子步是"堆叠不下沉、接触不平炸"的最廉价方案；固定值保证回放可复现 |
| CCD | `EMotionQuality.LinearCast` | 电影方块初速高，50ms 内可飞出自身厚度数倍，只在步进端点检测会直接穿地；CCD 用百分之几的步进代价根除 |
| 质量 | `setMass + CalculateInertia` | 按形状自动算惯性，方块翻滚手感像方块而不是质点 |
| 求解 | 单线程 `JobSystemSingleThreaded` | Jolt 多线程结果与线程数相关；回放必须逐帧可复现 |
| 层表 | STATIC(0)↔MOVING(1)、MOVING↔MOVING | 静态间互不检测（都推不动，纯浪费）；宽相动/静分树，静态树不随每步重建 |

工程细节（避开 jolt-jni 的坑）：

- **handle==0 约定**：Jolt body id 从 0 开始可能合法，而插件处处以 `handle==0` 表示"无刚体"——对外统一 `handle = bodyId + 1`，0 永不冲突。
- **空世界检测**：`system.getNumBodies()` 含静态碰撞体（最多 2000+），不能用于 `PhysicsWorldRegistry` 的空世界销毁判定——`JoltPhysicsWorld` 自行维护动态刚体计数。
- **零 GC 变换读取**：`getPositionAndRotation(id, rvec3, quat)` 原地填充世界级复用对象；`RVec3` 分量 getter 返回 `Object`（单精度=Float/双精度=Double），用 `Number` 中转兼容两种 native 构建。
- **阻尼语义**：Rapier 指数衰减 `v *= exp(-rate·dt)` vs Jolt 每步 `v *= 1/(1+rate·dt)`，在 40~60 步/秒下同速率差别 <1%，插件参数（linearDamping/angularDamping）跨引擎语义一致，切换引擎无需重调参。
- **TempAllocator 容量**：单次分配随 `MAX_BODY_PAIRS/MAX_CONTACTS` 线性放大，容量取 bbs-physics-engine 验证过的 4096/4096/2048 + 12MB（实测 65536/20480 会在步进时直接 Out of memory）。
- **jar-in-jar 分发**：`include` ReleaseSp 构件（内嵌 `windows/x86-64/com/github/stephengold/joltjni.dll`），运行时由模组自身 ClassLoader 提取到游戏目录后 `System.load`，原子写入防多实例竞态。

以上行为均由 `buildscript/JoltSmokeTest.java`（独立冒烟测试，无 MC 依赖）验证：
native 加载、重力加速、精确落地（y=0.48）、四元数归一化、阻尼静止、自动休眠、移除后计数归零、**-25 m/s 高速方块 CCD 防穿透**，10 项全部通过。

### 6.1.2 引擎切换按钮与旧存档兼容

- `BlockSplashActionClip` 新增 `engine` 字符串值（`"sable"` / `"jolt"` / `"vanilla"`），UI 上为三选一循环按钮。
- **旧存档兼容**：engine 为空（2.1.0 之前的地图数据没有这个键）时自动跟随原 `sable` 布尔字段——旧地图行为逐字节不变；一旦在面板上点击按钮即固化写入。
- 点击任一选项会同步写 `sableEnabled`（`vanilla`→false，其余→true），保证被旧版本模组读取时行为也一致。
- 选中 Jolt 但 native 不可用时（异构平台/缺库），自动回退原版路径并只记录一次日志，不会崩游戏。

### 6.2 双引擎总览（原两档）

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

### 6.3 JNI 桥接层

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

`NativePhysicsWorld` 是 `AutoCloseable` 封装。**所有 native 调用都经过 `valid` 闸门**：
`close()` 之后 worldPtr 已被释放，此时设置类方法直接忽略、读取类方法写入安全默认值
（位置 0 / 单位四元数 / 速度 0）——把 use-after-free 的后果从"垃圾坐标 + 抽搐 + 崩溃"降级为"静止的默认值"。
`JoltPhysicsWorld` 同样对全部读写方法加了 `valid` 前置判断。

### 6.4 物理世界生命周期（PhysicsWorldRegistry）

物理世界按 **BBS 回放时钟** 推进：由 `BlockSplashActionClip.applyRange()` 每回放 tick
调用一次 `PhysicsWorldRegistry.driveTo(key, localTick)`。这与旧版的
`ServerTickEvents.END_SERVER_TICK → tickAll()` 有本质区别——见 [第 7 章](#7-服务端调度器家族)。

**确定性键（幂等）**：`worldKey = filmId + "@" + replayId + "#" + 片段起始tick`。
BBS 的 `ActionPlayer.goTo()` 在拖动时间轴时会**逐 tick 重放** `applyAction`，
若非幂等，同一位置会被反复生成刚体 → 数百个方块重叠互挤 → 剧烈随机抽搐。
用确定性键 + `has()` 判定后，重复触发直接返回。

**向后拖动（回放倒放）**：物理不可逆，`driveTo` 检测到 `localTick < simulatedTick`
时，按保存的**初始条件**（位置/速度/角速度/质量/摩擦/阻尼）重建全部刚体，
再以固定步长重新模拟到目标 tick。固定步长 + 固定种子 + 单线程求解器保证结果可复现。

销毁触发条件：

1. **空世界**：动态刚体归零（实体全部 discard）→ 立即销毁。
2. **回放停止**：`ActionPlayerStopMixin` 注入 BBS `ActionPlayer#stop()`（所有结束路径的唯一汇聚点），
   销毁该影片的全部物理世界。
3. **绝对超时**：`simulatedTick > MAX_WORLD_LIFE`（兜底，正常由上面两条清理）。
4. **全局清理**：服务器停止 `clearAll()`。

**销毁顺序（native 安全的关键）**：① 断开实体引用 → ② `world.close()` → ③ `remove(discard)` 实体。
顺序反过来会让实体在 native 世界释放后仍持有 `worldPtr` → use-after-free。

> 设计权衡：世界销毁时**只清 Recording，不清 Recovery**——Recovery 是 DamageControl 失效时的存档兜底，
> 运行时清理不安全（世界销毁时 BBS 可能还没恢复完方块）。Recovery 由 SERVER_STOPPING 的
> `restoreAll + clearAll` 负责最终清理。Recovery 记录已改为「位置 → 原始状态」去重，内存按位置数封顶。

连带清理的内容与原因（**资源泄漏治理史**）：

| 资源 | 单次回放累积量 | 后果（若不清理） |
|---|---|---|
| `PhysicsRecordingManager` 记录 | 300 方块 × 1200 tick = **36 万条 ≈ 43MB** | GC 风暴，"回放次数越多越卡" |
| Recovery 记录 | 约 12 KB/回放 | 内存缓慢增长 + 退出恢复遍历膨胀 |

> 设计权衡：世界销毁时**只清 Recording，不清 Recovery**——Recovery 是 DamageControl 失效时的存档兜底，运行时清理不安全（世界销毁时 BBS 可能还没恢复完方块）。Recovery 由 SERVER_STOPPING 的 `restoreAll + clearAll` 负责最终清理。时序上还有一条保护：刚体创建在 applyAction（同步 tick），步进在 END_SERVER_TICK，同一 tick 内刚体数必 >0，空世界检测不会误伤刚创建的世界。

### 6.5 PhysicsBlockEntity（791 行，双刚体引擎共用的实体载体）

继承 `FallingBlockEntity` 复用其渲染器，但 **tick 完全不跑原版逻辑**——每 tick 从 Rapier 刚体读变换（位置 + 四元数）同步到实体。几个关键工程细节：

- **BlockState 同步陷阱**：`FallingBlockEntity.block` 是 private，且 `initDataTracker()` 里就读 `block.isAir()`。子类公开构造函数无法设置它 → NPE。修复：覆写 `initDataTracker()`，在 super 之前用 `FallingBlockEntityAccessor`（`@Accessor("block")`）把 block 设为 AIR，之后客户端从 spawn 数据包同步真实方块状态。
- **单位约定**：native 坐标是方块**几何中心**（米）；MC 实体坐标是**脚部**（feet = center - 0.5）；`setVelocity` 用 blocks/tick（÷20 换算 m/s）。
- **GC 优化**：每 tick 的 JNI 读取复用实例级 `_tmpPos/_tmpRot/_tmpVel/_tmpAngVel` 数组。早期每 tick new 3 个数组 × 500 方块 × 20 tick = 每秒 3 万个临时数组 → GC 风暴；改后零分配。
- **落地稳定检测**：低速（< 0.2 m/s）连续 10 tick → `settled=true`，**停止位置/旋转同步**。
  刚体求解器在静止时仍有亚毫米级微抖，若继续每 tick 同步，客户端会把这点微抖当真实位移插值出来
  → 方块静止时持续"抽搐"。（旧实现算出了 `settled` 但从未读取，防抖机制其实是死代码。）
- **客户端插值（高刷新率核心）**：客户端 tick 只有 20Hz 且线性 lerp 是直线，而物理下落是二次曲线，
  于是 165Hz 屏幕上"看起来只有 20 帧"。解法**不是**开环积分预测（见第 9 章），
  而是把渲染位置定义为**已确认权威样本之间的插值函数**：
  位置用**最近 3 个 tick 样本的二次拉格朗日插值**（对恒定加速度精确）、
  旋转用**最近 2 个 tick 四元数的 slerp**（含符号修正）。纯函数、无累积状态、与帧率无关。
- **物理记录**：每 tick 把 (tick, blockId, 位置/速度/四元数/角速度/方块状态/休眠) 写入 `PhysicsRecordingManager`（按 replayId 分组，replayId 由 `Replay.getId()` MD5 派生，确定性），为未来的"物理烘焙成关键帧动画"功能积累数据。

### 6.6 纯 Java 物理引擎（PhysicsEngine，489 行）

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

### 6.7 RotatingFallingBlockManager（681 行，原版实体的旋转物理）

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
| `PhysicsWorldRegistry` | 刚体物理世界的生命周期 + 回放时钟步进 | **不再由 `END_SERVER_TICK` 步进**：物理由 `BlockSplashActionClip.applyRange()` 按回放 tick 驱动（`driveTo`）；`END_SERVER_TICK` 只调用轻量的 `maintenance()` 做失效/空世界/超时清理 |

### 7.1 为什么物理必须挂在 `applyRange` 而不是 `END_SERVER_TICK`

BBS 的 `ActionClip` 提供两个回调，语义完全不同：

| 回调 | 触发时机 | 本插件用它做什么 |
|---|---|---|
| `applyAction` | 只在**片段起始 tick**（或 `frequency` 命中时） | 一次性规划：收集方块、清场、建刚体、生成实体 |
| `applyRange` | **片段覆盖的每一个 tick** | 按回放 tick 推进物理（`driveTo`） |

BBS 的 `ActionPlayer.tick()` 只在回放时钟推进时被调用（`playing=false` 时提前 return），
`ActionPlayer.goTo(from, to)` 则是**逐 tick 回放**所有 clip。因此把物理挂在 `applyRange` 上，
天然获得三个"回放规则"必需的语义：

```
暂停回放  → applyRange 不被调用 → 物理定格（可以拍定格镜头）
播放回放  → 每个回放 tick 调用一次 → 物理 1:1 跟随
拖动时间轴 → 与拖动方向一致地逐 tick 调用 → 物理随拖动推进 / 触发确定性回退重放
导出视频  → 回放时钟驱动 → 与实时预览逐帧一致（可复现）
```

旧实现挂在 `END_SERVER_TICK` 上，导致暂停时方块仍在继续下落，**对短片拍摄是致命的**。

所有调度器都有 `clearAll()`（SERVER_STOPPING 时调用：销毁实体/恢复方块/清任务）与 `getActiveTaskCount()`（调试）。

---

## 8. Mixin 注入体系（15 个 Mixin）

### 8.1 服务端/公共（bbsblocksplash.mixins.json，6 个）

| Mixin | 目标 | 注入点 | 作用 |
|---|---|---|---|
| `ActionPlayerStopMixin` | BBS `ActionPlayer` | `stop()` HEAD | **回放停止钩子**：BBS 的 DamageControl 只管方块，不管本插件 spawn 的物理实体。在所有回放结束路径（播完/编辑器停止/断线/关服）的唯一汇聚点销毁本插件为该影片建立的物理世界与实体——否则遗留实体持有已释放的 `worldPtr` → 抽搐/崩溃 |
| `BbsEntityMixin` | `Entity`（class_1297） | `isOnGround()` HEAD 可取消 | **非实体化模式的核心**：NO_SOLIDIFY=true 时强制返回 false，骗过原版"落地变方块"判定。放在 Entity 层是因为 `isOnGround()` 定义在 Entity，Loom remap 只查目标类直接方法表，放在 FallingBlockEntity mixin 上会映射失败 |
| `FallingBlockEntityDataMixin` | `FallingBlockEntity` | `initDataTracker()` RETURN | 注册 15 个自定义 TrackedData 字段（见 8.3） |
| `FallingBlockEntityPhysicsMixin` | `FallingBlockEntity` | `tick()` HEAD + RETURN（644 行） | HEAD：NO_SOLIDIFY 时重置 `timeFalling=0`（防原版 600 tick 超时 dropItem+discard）；RETURN：对标记实体注入 RotatingFallingBlockManager 完整物理（弹跳/摩擦/旋转/实体碰撞/休眠），并记录落地方块位置供退出恢复。通过 `isRotating()` 守门，`PhysicsBlockEntity` 不受影响 |
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

## 9. 网络同步与高刷新率渲染

这是本插件工程含量最高的部分。核心结论只有一个：

> **客户端不做物理预测，只做插值。**

### 9.1 为什么"预测"是错的（第一性原理）

直觉方案是：客户端每帧用同步来的速度 + 重力积分往前推，位置包到达时按比例校正。
这个方案在数学上是一个**正反馈回路**：

```
误差 e  →  积分放大  →  校正不足  →  误差更大  →  ...
```

只要网络有丢包 / 合并 / 延迟（这是结构性事实），校正源就不是连续可用的，
于是预测值会持续漂移，再被不连续的校正拉回 —— 方块看起来"不跟随物理运动、原地随意抽搐"。

历史实现还叠了三个具体缺陷，把抖动放大到肉眼可见：

| 缺陷 | 后果 |
|---|---|
| `getRenderX/Y/Z` 在「预测值」和「lerp 值」之间**逐帧切换**（`predictionSkippedThisFrame`） | 同一 tick 内第一帧用预测位置、后续帧用 lerp 位置，两者数值不同 → 20Hz 的来回跳变 |
| 每帧无条件积分重力 + 每 tick 只做 20% 校正 | 静止/贴地方块每帧下沉、每 tick 被拉回 → 周期性抖动 |
| 客户端预测角速度从未到达客户端（`setClientPhysicsParams` 只在服务端实体实例上调用，不是 DataTracker 字段） | 旋转预测是空转，渲染旋转也在两个源之间切换 |

### 9.2 正确解法：把渲染位置定义为权威样本之间的插值

MC 的逻辑是 20 TPS，渲染可以是任意帧率。客户端每 tick 收到一个**权威样本**
（`EntityS2CPacket` / `EntityPositionS2CPacket` 会同时推进 `pos` 与 `lastRender`），
渲染要做的是在样本之间取值。位置用一条曲线把它们连起来：

```
样本位于 t = -1, 0, +1（单位 tick），取值为 a, b, c
τ = tickDelta ∈ [0,1]，渲染 t ∈ [0,1] 之间的位置

p(τ) = a·τ(τ-1)/2 + b·(1-τ²) + c·τ(τ+1)/2      ← 二次拉格朗日插值
```

这个表达式的性质正是我们需要的：

- **对恒定加速度精确**。自由落体是二次曲线，二次插值与真实轨迹完全重合
  （线性 lerp 的误差是 O(T²)，视觉上就是"20 帧感"）。
- **纯函数、无累积状态** → 不漂移、不抖动、发散不可能发生。
- **与帧率无关** → 60Hz 实时预览、165Hz 显示器、BBS 离线导出（任意帧率、任意拖动位置）
  得到逐帧一致的结果，这是"为短片开发"的硬需求。
- **不需要额外同步速度** → 零额外带宽。

旋转用**最近 2 个权威四元数的 slerp**（含 `q`/`-q` 符号修正，否则会走远路导致旋转抽帧）。

### 9.3 渲染矩阵：必须先"抵消"再"偏移"

MC 渲染实体的矩阵原点并不是 `entity.getX()`，而是（`WorldRenderer#method_22977`）：

```java
base = lerp(tickDelta, entity.lastRenderX, entity.getX())
```

同时 `FallingBlockEntityRenderer` 内部还会 `translate(-0.5, 0, -0.5)` 再画 0..1 的方块模型，
即方块中心 = `(baseX, baseY + 0.5, baseZ)`。因此渲染器必须：

```java
matrices.push();
matrices.translate(renderCenter - (baseX, baseY + 0.5, baseZ));  // 抵消 + 偏移，一步到位
matrices.translate(0, 0.5, 0);
matrices.multiply(renderRotation);                               // 绕方块几何中心旋转
matrices.translate(0, -0.5, 0);
super.render(...);
matrices.pop();
```

用**同一个** `base` 做抵消，offset 才不会随 `tickDelta` 摆动（这是"抽搐"最直接的一类来源）。

### 9.4 原版实体路径（非 PhysicsBlockEntity）

原版 `FallingBlockEntity` 路径另有两条既有经验，与本插件其它特效共用：

```
问题：旋转卡顿 —— DataTracker 每 tick（50ms）同步角度 → 同一 tick 内每帧角度相同
  解法①：服务端同步"角速度"而非只有角度
  解法②：客户端 ClientRotationStateManager 每帧自积分 + 每 tick 校准一次防累积误差

问题：路径运动位置只有 20 帧 —— 客户端 tick 里 prevX = getX() 把插值锚点抹平
  解法①：PATH_MOVEMENT 标志 → 客户端 Mixin 取消整个 tick()
  解法②：服务端 setPositionWithPrev 手动管理 prevPos
```

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
| 空 world 即销毁 | getBodyCount()==0 → 立即销毁 | 不白跑 step |
| Recording 清理 | 世界销毁连带清（43MB/回放） | "越播越卡"根因 |
| Recovery 记录去重 | 「位置 → 原始状态」Map（putIfAbsent） | 内存按位置数封顶，不再随回放次数线性增长 |
| JNI 零分配 | 实例级复用临时数组 | 3 万临时数组/秒 → 0 |
| native 安全闸门 | `isValid()` 前置判断 + 读取类方法安全默认值 | 消除 worldPtr 释放后的 use-after-free |
| 静止停同步 | 低速 10 tick → `settled` 停止位置/旋转同步 | 求解器亚毫米微抖不传播到客户端（防静止抽搐） |
| 实体 GC | 客户端旋转状态 >50 个时清理 5 秒未访问项 | 减少无谓内存 |
| 回退重放预算 | 单次 `driveTo` 最多 60 步 | 长时间倒拖不会阻塞服务器主线程 |
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

### 13.2.1 ⚠️ 铁律：Mixin 注解里的成员名也必须写 intermediary 名

**这是本仓库最容易踩、且后果最隐蔽的坑。**

Loom 的 Mixin 注解处理器靠映射文件里的 `named` 命名空间，把
`@Inject(method = "tick")` 翻译成 `method_5773` 并写进 **refmap**。
而 `net.fabricmc:intermediary` 只有 `official` / `intermediary` 两个命名空间，**没有 `named`**
—— 处理器查不到，只能把字符串原样保留，**refmap 也生成不出来**。

| 症状 | 说明 |
|---|---|
| 构建**成功**、jar 正常产出 | 没有任何编译错误，只打印一行 `Cannot remap tick because it does not exist...` 警告，极易被忽略 |
| 产物里 `@Inject(method = "tick")` 原样保留 | 但运行时 `class_1540` 的方法叫 `method_5773` → 按名字 `"tick"` 找不到目标 |
| 因为 `mixins.json` 是 `"required": true` + `defaultRequire: 1` | **直接抛 InjectionError，游戏启动崩溃** |

正确写法（本仓库当前状态）：

```java
@Inject(method = "method_5773", ...)                    // FallingBlockEntity.tick()
@Inject(method = "method_24828", ...)                   // Entity.isOnGround()
@Inject(method = "method_3965", ...)                    // FallingBlockEntityRenderer.render()
@Inject(method = "method_5693", ...)                    // Entity.initDataTracker()
@Accessor("field_7192") int bbs$getTimeFalling();       // FallingBlockEntity.timeFalling
@Accessor("field_7188") BlockState bbs$getBlock();      // FallingBlockEntity.block
@Accessor("field_5952") boolean bbs$isOnGround();       // Entity.onGround
@Accessor("field_6014" / "field_6036" / "field_5969")   // Entity.prevX / prevY / prevZ
```

**BBS 模组（`mchorse.bbs_mod.*`）的类没有被重映射**，所以针对 BBS 的 Mixin 注解
一律写真实名（`@Inject(method = "stop")`、`@Accessor("list")`、`@Inject(method = "render")`
指向 `UIReplaysListPanel`），**不要"顺手统一"成 `method_XXXX`**。

查法（intermediary ↔ yarn 对照）：

```bash
curl -O https://maven.fabricmc.net/net/fabricmc/yarn/1.20.1+build.10/yarn-1.20.1+build.10-v2.jar
unzip yarn-*.jar mappings/mappings.tiny      # tiny v2：intermediary  named
```

构建后自检（确认注解真的被写成了中间名）：

```bash
javap -v -p build/classes/java/main/.../mixin/FallingBlockEntityPhysicsMixin.class \
  | grep -A 3 "injection.Inject(" | grep "method="
# 期望：method=["method_5773"]   而不是 method=["tick"]
```

### 13.3 重新构建（build.gradle 已随仓库提供，编译已验证）

仓库已包含完整 Gradle 构建（`build.gradle` / `settings.gradle` / `gradle.properties` / Gradle 9.2.0 wrapper）与编译验证工具（`buildscript/`）：

```bash
# 0. 准备 BBS 到本地 Maven（一次性，二选一）
#    A. 从源码：克隆 bbs-fs 1.20.1 分支执行 gradlew publishToMavenLocal
#    B. 现成 jar：把 BBS 1.20.1 成品 mod jar 复制为
#       ~/.m2/repository/mchorse/bbs/2.5.2-1.20.1/bbs-2.5.2-1.20.1.jar（附最小 POM）

# 1. 编译本插件（Jolt 后端依赖自动从 Maven Central 拉取并 jar-in-jar 打包）
./gradlew build        # 产物在 build/libs/bbs-Block_Splash-2.1.0.jar
```

说明：
- **跑 Gradle/Loom 1.15.5 需要 JDK 21**（Loom 自身的硬性要求）；但 `javac --release 17`
  保证产物字节码仍是 Java 17——**模组运行时只要 Java 17+**
- 源码以 **intermediary 命名**书写，`build.gradle` 因此直接以 intermediary 作为开发映射
  （Loom 会把 Minecraft 重映射为 intermediary 名称，编译时所见即所得）
- 本仓库源码已通过全量 javac 编译验证（`--release 17`，零错误；classpath = MC 1.20.1
  intermediary + fabric-api 全模块（含访问加宽）+ BBS 2.5.2-1.20.1 + jolt-jni 1.0.0 +
  fabric-loader 等，产出 73 个 class 文件）。`buildscript/AwPatch.java` 可把 fabric-api 的
  传递性访问加宽应用到重映射后的 MC jar——手工复刻 Loom 的行为，供无 Loom 的 javac 流水线使用
- `gradle.properties` 里 `mod_version=2.1.0`；`fabric.mod.json` 的 `"version": "${version}"` 由 processResources 注入
- Rapier 后端的原生库 `src/main/resources/natives/windows/bbs_physics.dll` 随资源打包
- Jolt 后端的原生库由 `include "jolt-jni-Windows64:1.0.0:ReleaseSp"` 以 Fabric jar-in-jar 嵌入产物
- `buildscript/JoltSmokeTest.java` 是不依赖 Minecraft 的物理冒烟测试，可用任意 JDK17 直接运行（验证 native 加载与物理行为）

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
- **[Wemppy4/bbs-physics-engine](https://github.com/Wemppy4/bbs-physics-engine)** —— v2.1.0 新增的 Jolt 物理引擎后端与它同源（Jolt / jolt-jni），引擎层用法（层表、子步进、CCD、质量覆盖、TempAllocator 容量）参照其公开实现，面向世界方块的适配为本插件原创
- **[Jolt Physics](https://github.com/jrouwe/JoltPhysics) / [jolt-jni](https://github.com/stephengold/jolt-jni)** —— C++ 工业级刚体引擎及其 JNI 绑定（Apache-2.0 / MIT）
- **[rigid-body](https://github.com/Polari-Stars-MC/rigid-body)**（Polari-Stars-MC）—— Rapier3d JNI 桥接思路参考
- **Rapier3d**（`rapier3d-f64 0.33.0`，Apache-2.0）—— Rust 刚体物理引擎
- 原始源码包：`releases/bbs-Block_Splash-2.0.4-sources.jar`

本项目以 **MIT** 许可证发布（与 BBS 一致）。欢迎 fork、二次创作，但请保留原作者署名。

---

## 16. 修复记录（"方块不受物理控制、随机抽搐"专项）

本节记录一次以第一性原理为起点的排查与修复，主症状是
**区域内方块不沿刚体轨迹运动，而是原地剧烈抖动 / 抽搐**，并伴随"回放不像回放"的问题。

### 16.1 症状 → 根因对照表

| # | 症状 | 根因 | 修复 |
|---|---|---|---|
| 1 | 方块在两个位置之间以 ~20Hz 来回跳 | 渲染位置在「客户端预测值」与「lerp 值」之间**逐帧切换**（`predictionSkippedThisFrame`）；两者数值不同 | 删除整套开环预测，改为「权威样本之间的插值」单一数据源 |
| 2 | 方块移动滞后 / 追不上刚体 | `tickAdvanced` 只在客户端 tick 置位、每渲染帧消费一次，导致每 tick 实际只积分到一个 **dt ≈ 0** 的步长；预测几乎被冻结，只剩 20%/tick 的滞后校正 | 同上（插值无需速度与 dt） |
| 3 | 静止的方块持续微抖 | 每帧无条件积分重力 + 每 tick 只做 20% 校正 → 低位振荡；`settled` 被算出但从未读取（死代码） | 用二次插值（对恒定加速度精确）；`settled` 真正落地为"停止同步" |
| 4 | 旋转也在抖动 / 顿挫 | `setClientPhysicsParams()` 只在**服务端**实体实例上调用（不是 DataTracker 字段），客户端的预测角速度恒为 0，旋转预测是空转；渲染旋转同样在两个源之间切换 | 旋转改为两样本 slerp（含 `q`/`-q` 符号修正） |
| 5 | 部分方块穿过地面掉进虚空后乱弹 | `injectStaticCollisionBlocks()` 达到 2000 上限时**整体 `return`**（而非中断单个方块的注入），导致排在后面的方块完全没有地面；且"每方块 × 半径 8 立方体"的遍历方式本身就与体积同阶 | 改为只注入"承力几何"（每列地板 + 外壳侧墙），注入量与表面积同阶；达到上限只跳过不中断 |
| 6 | 播放/重播若干次后出现重叠方块互相挤压 | `applyAction` **非幂等**：BBS `ActionPlayer.goTo()` 拖动时间轴时会逐 tick 重放 `applyAction`，每次都新建物理世界 + 在同一位置再生成最多 300 个刚体 | worldKey 由「filmId + replayId + 片段起始 tick」派生，重复触发直接返回 |
| 7 | 回放暂停时方块仍在继续下落 | 物理步进挂在 `ServerTickEvents.END_SERVER_TICK`，与回放时钟无关 | 物理改由 `ActionClip.applyRange()` 驱动（暂停不调用 → 物理定格） |
| 8 | 拖动时间轴 / 导出视频结果对不上 | 同上；且客户端预测依赖逐帧 dt，帧率一变结果就变 | 回放时钟驱动 + 帧率无关的插值函数 |
| 9 | 回放停止后世界中残留大量下落方块，下次拍摄越来越乱 | BBS 的 `DamageControl` **只记录方块变更，不记录本插件 spawn 的实体** | 新增 `ActionPlayerStopMixin`：在 `ActionPlayer#stop()`（所有结束路径的唯一汇聚点）销毁本插件为该影片建立的物理世界与实体 |
| 10 | 物理世界销毁后方块瞬移到垃圾坐标 / 随机抽搐 / 偶发崩溃 | worldPtr 释放后实体仍持有句柄 → 后续 `nGetBodyTransform` / `nRemoveBody` 是 **use-after-free**；且实体 `MAX_LIFE`(1200) 与世界 `MAX_WORLD_LIFE`(1200) 相同，存在竞态 | `PhysicsBlockEntity` 所有 native 调用前检查 `isValid()`，失效即自毁；`NativePhysicsWorld` / `JoltPhysicsWorld` 全部读写方法加 `valid` 闸门（读取类写入安全默认值）；销毁顺序固定为「断开实体 → 关世界 → 移除实体」 |
| 11 | 反向飞溅/振波方块卡墙、卡地 | （既有实现保留，未改动） | — |

### 16.2 回放规则对齐清单

| BBS 规则 | 本插件的应对 |
|---|---|
| `applyAction` 只在片段起点触发，且拖动时会**重复**触发 | 用确定性 worldKey 做幂等，重复触发不产生副作用 |
| `applyRange` 覆盖片段全部 tick，且只在回放时钟推进时调用 | 物理步进唯一入口（`driveTo`） |
| 暂停 = 时钟不推进 | 物理定格 |
| 拖动 = 逐 tick 行走 | 前进逐 tick 步进；后退按初始条件重建刚体后重新模拟（步数预算 60/次） |
| 导出 = 同一时间轴应当复现 | 渲染与物理都与帧率无关、由回放 tick 决定 |
| 停止 = 世界必须复原 | 方块交给 `DamageControl`；本插件实体与刚体交给 `ActionPlayerStopMixin` 钩子 |

### 16.3 行为变化（使用上需要知道）

1. **物理的存活范围现在等于片段的长度**。片段结束后 `applyRange` 不再被调用，物理随之停止——
   这是 BBS 的语义（clip = 效果的窗口）。需要更长的物理演出，请把片段拉长。
2. **`solidify=true`** 的方块现在会一直留在地上直到回放停止（旧版 60 秒后被强制 `discard`）。
3. **`solidify=false`** 时存活上限改由 `animationDuration` 决定（以回放 tick 计，暂停不计寿命）。
4. 向后拖动时间轴会触发一次"重建 + 重新模拟"，大规模场景（300+ 刚体）倒拖时会有轻微卡顿，
   已用 60 步/次的预算限制最坏情况。

### 16.4 详细报告

完整的问题清单、根因分析与验证说明见 `docs/`：

| 文档 | 内容 |
|---|---|
| [`docs/方块飞溅_代码审计与修复报告.md`](docs/方块飞溅_代码审计与修复报告.md) | **全库系统性审计**：审计方法（八条公理）、23 项缺陷清单（P0×2 / P1×13 / P2×6 / P3×2）、根因分析、修复明细、字节码级验证说明、未修复项 |
| [`docs/方块飞溅_物理与回放修复报告.md`](docs/方块飞溅_物理与回放修复报告.md) | 物理与回放专项：抽搐的四条根因链、BBS 回放规则对齐、生命周期设计 |

> 两个最值得记住的坑（详见审计报告 §4）：
> 1. **`@Accessor` 方法名绝不能与目标类已有方法同名** —— 否则 Mixin 认为接口方法已被满足，
>    不会生成访问器实现，调用会静默落到 vanilla 方法上。本仓库统一用 `bbs$` 前缀规避。
> 2. **`static` 物理状态表在单机下是两端共享的** —— 客户端与服务端同 JVM、同 UUID，
>    物理入口必须用 `World.isClient` 守卫，否则两端互相施加速度、互相删除状态。

