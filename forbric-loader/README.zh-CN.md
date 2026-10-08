# Forbric 加载器
[English](README.md) | 简体中文

一个**净室实现的统一 Minecraft mod 加载器**，能在同一个 Minecraft 26.2 实例里加载 **Fabric** mod（`fabric.mod.json`）和两种 **Forge 系** mod：传统 MinecraftForge（`META-INF/mods.toml`）与 NeoForge（`META-INF/neoforge.mods.toml`）。

- 许可与来源：[CREDITS.md](CREDITS.md)、[MAPPINGS.md](MAPPINGS.md)、[NOTICE](NOTICE)
- 启动工具集 —— 启动脚本、打过补丁的游戏基底构建脚本、探测 mod：[run/](run/)

## 一段话说清思路

Forbric 是唯一的进程启动器。它复用 Apache-2.0 许可的 **fabric-loader** 作为底座（Knot 类加载器、mod 发现 + SAT 求解器、元数据解析器、Mixin 服务、game-provider 框架），在此之上加了一个**净室实现的 Forge 侧**，外加两个起统一作用的部件：一条**统一转换流水线**（`TransformChain`），托管在单个转换型类加载器里，且 **Mixin 固定排在最后**；一条**映射主干**（`ForbricMappings`），按 Fabric intermediary 名和 Mojang 官方名（“Mojmap”）共有的混淆名列把两者连接起来，这样无论运行中的游戏用的是哪个命名空间，Forge mod 的字节码都能挪进去。Forge 侧依据公开规范编写，没有复制任何 LGPL 的 FML 代码，因此整个项目保持 Apache-2.0 许可，并且**从不打包任何 MCP 映射数据**。

## 构建

底座是一个**同级目录下的 checkout，不是内置的**。`build.gradle` 把 `../fabric-loader/src/main/java`、它的 `legacyJava` 源码根目录和 `../fabric-loader/minecraft/src/main/java` 加进本模块的 `main` source set，让 Gradle 把上游代码和 Forbric 自己的代码一起编译。本仓库里没有这部分源码，这是有意为之：底座保持为独立的 git checkout，停在锁定的上游标签上，这样 `git -C fabric-loader diff <tag>` 就能准确显示 Forbric 改了上游代码的哪些地方，Forbric 依赖的八处改动也能以补丁形式（`patches/fabric-loader/`）保持可审查，而不是消失在一棵复制过来的代码树里。

在仓库根目录执行：

```bash
./bootstrap.sh                          # fetch ../fabric-loader at the pinned tag, apply patches/
cd forbric-loader && ./gradlew build    # compile + run the unit tests
```

`bootstrap.sh` 第一次运行时需要 `git` 和网络（它要克隆 `https://github.com/FabricMC/fabric-loader`），之后只做复查，所以重复运行是安全的。想从镜像克隆，设置 `FABRIC_LOADER_REMOTE` 即可。用哪个上游发布版只在一处声明：[gradle.properties](gradle.properties) 里的 `fabric_loader_ref`（目前是 **0.19.3**）。`bootstrap.sh` 克隆的就是这个标签，而 `bootstrap.sh` 最后会运行的 `run/verify-substrate-patches.sh` 也拿当前实际的底座和同一个标签做 diff，所以补丁不会悄无声息地失效。`./bootstrap.sh --check` 只做校验，不改动任何东西。

`./gradlew build` 会在 `build/libs/` 里生成两个 jar：

- `forbric-loader-<version>.jar` —— 加载器核心。由父加载器加载，放在 JVM 的 `-cp` 上。
- `forbricruntime-<version>.jar` —— 由 Knot 加载的另一半（见下文的架构说明），MixinExtras 以 JiJ 方式内嵌在它的 `META-INF/jars/` 下。

需要 JDK 17 或更新版本；产物锁定为 Java 17 字节码。

## 代码树里有什么

| 部分 | 状态 |
|---|---|
| **统一转换流水线** —— `TransformPhase`、`ClassTransformer`、`TransformContext`、`TransformChain`，从底座经由 `FabricTransformer` → `ForbricTransformBridge` 这条只有一行代码的接缝（补丁 `0002`）进入 | 已实现 + 有单元测试 |
| **统一 mod 发现** —— `ForbricModDiscoverer` 把 `fabric.mod.json`、`META-INF/mods.toml` 和 `META-INF/neoforge.mods.toml` 读成同一个 `DiscoveredMod` 模型（`ModEcosystem` = FABRIC / FORGE / NEOFORGE）；`ForgeVersionRangeTranslator` 把 Maven 版本范围转换成 Fabric 版本谓词 | 已实现 + 有单元测试 |
| **净室实现的 `mods.toml` 解析器** —— `ModsTomlParser` + 模型（`ForgeModsToml`、`ForgeModEntry`、`ForgeDependency`、`ForgeMetadataMapper`） | 已实现 + 有单元测试 |
| **访问转换器（AT）** —— 净室实现的 `.cfg` 解析器 → `ACCESS` 阶段里的 `AccessTransformer`，包括 `mods.toml` 内声明的 AT 块 | 已实现 + 有单元测试 |
| **映射主干** —— `ForbricMappings` 按共有的混淆名列把 Fabric intermediary 和 Mojang 官方映射连接起来（不含 MCP 数据）；`ForgeModRemapper` 用它驱动 tiny-remapper；`ForbricCache` 以内容哈希为键缓存结果 | 已实现 + 有单元测试 |
| **mod 准备** —— `ModAnnotationScanner`（用 ASM 发现 `@Mod`，由父加载器加载，在 Knot 之前运行）、`JarJarTranslator`，以及 `ForbricForgeLoader` / `ForbricBootstrap`：它们把准备好的 Forge 系 jar 包装成 Fabric mod，通过 `fabric.addMods` 交给底座 | 已实现，部分有单元测试 |
| **Forge 系运行时驱动** —— `ForbricMinecraftForgeRuntime` 和 `ForbricNeoForgeRuntime` 在 Knot 下启动**真正的** MinecraftForge / NeoForge 运行时，但不走 FML 自己的 ModLauncher 启动流程（那会建出一个模块层和第二个转换型类加载器）。全程只用反射，所以加载器在编译期不依赖 Forge。它们就是 `forbricruntime` 声明的 `preLaunch` 入口点 | 已实现 |
| **跨生态桥与游戏 mixin** —— `impl/forge/bridge` + `impl/forge/mixin`：包仓库与已知包（known-packs）身份、注册表同步边界、客户端双生命周期与关闭、客户端模型数据、Fabric 频道注册 | 已实现，部分有单元测试 |
| **入口类** —— `ForbricClient` / `ForbricServer`，代替 Knot 自己的入口，用作启动的 `mainClass` | 已实现 |

`./gradlew test` 会运行 **65 个单元测试**。

## 架构说明（关键）

Forbric 中接触游戏的那一半，作为一个**独立的、由 Knot 加载的模块**（`forbricruntime`）分发，与由父加载器加载的加载器核心分开。这是必须的：解析游戏类型的类必须由 Knot 的转换型类加载器加载，打过补丁的游戏类和由 Knot 加载的 Forge 系运行时都在那里；加载器自己代码源（code source）里的类由父加载器加载，看不到它们。`build.gradle` 从同一次编译中拆出这两部分：`jar` 排除 `impl/forge/{minecraftforge,neoforge,mixin,runtime}` 和 mixin 配置，`runtimeJar` 则恰好打包这些内容，外加 `src/runtime-meta/fabric.mod.json`，后者声明了两个 `preLaunch` 驱动和这些 mixin 配置。运行时如果某个 Forge 系不在，对应的驱动什么也不做，所以一次构建就能覆盖两者。`ModAnnotationScanner` 有意留在核心 jar 里：它在准备阶段、Knot 还不存在时，用 ASM 扫描 `@Mod` 类。

## 目标版本

- 本代码树针对 **Minecraft 26.2** 构建和启动：底座的 26.2 专用服务器入口点补丁（`0001`）、`run/` 里的启动脚本，以及打过补丁的 / 合并后的游戏基底构建脚本（`run/build-patched-forge.sh`、`run/build-merged-base.sh`）都以它为目标。26.2 原生就是 Mojmap（原版 jar 已经去混淆），所以 Forbric 在那里把规范命名空间当作恒等映射运行（`-Dforbric.runtimeNamespace=named`）：没有 intermediary，也不做重映射。
- **Minecraft 1.21.11** 是早先的验证目标，`run/README.md` 和映射主干描述的也是它。在混淆过的版本上，规范的运行时命名空间是 **intermediary**，`ForgeModRemapper` 会把 Forge mod 的 Mojmap 字节码重映射到这个命名空间。

## 范围说明

- MinecraftForge 和 NeoForge 运行时，以及 Forbric 加载的打过补丁或合并后的 Minecraft 基底，都**在运行时提供，从不提交到这里**。`run/` 下的脚本从上游 Maven 获取它们并在本地组装；仓库忽略 `*.jar`。分发的只有 Forbric 自己的净室字节码。
- Forbric 不走 FML 自己的启动路径。驱动通过反射预置 FML 环境，剩下的交给游戏自己的客户端 mod 加载流程，因为 ModLauncher 的模块层和转换型类加载器会和 Knot 打架。

旧版启动路径会拒绝带有未验证状态父类要求的合并基底（`META-INF/forbric/required-ancestor-compositions.tsv`）。这些产物应使用内核加载器，由注册的状态协议对最终类定义进行验证。
