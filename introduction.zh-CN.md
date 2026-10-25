# Forbric —— 架构与内部实现

[English](introduction.md) | 简体中文

写给 mod 开发者和加载器开发者。本文求精确，不求浅显：按 Forbric 实际执行的顺序讲清它做了什么，并直接写出真实的类型名和文件名。本文描述的是 **`main` 分支**，而不是某个发布版；想了解发布版包含什么、玩家如何安装，请读 [README](README.zh-CN.md)。

> **本文描述的是哪份代码。** 仓库里有两代实现。`forbric-kernel/`，即*自主内核*，`main` 分支交付的、`forbric-kernel-installer/` 安装的都是它，它也是本文的主题。`forbric-loader/` 是第一代（*焊接方案*：以真正的 fabric-loader/Knot 为宿主，同时驱动 FML 和 FancyModLoader）。它在安装好的实例里已不再运行，但内核构建和测试所依据的工具与暂存产物仍由它构建，见 [§14](#14-forbric-loader-还有什么用)。

术语：

| 术语 | 在本文中的含义 |
| --- | --- |
| **生态（ecosystem）** | Fabric、传统 MinecraftForge、NeoForge，对应 `net.forbric.api.Ecosystem.FABRIC` / `FORGE` / `NEOFORGE` |
| **Forge 系（Forge family）** | MinecraftForge 与 NeoForge 的合称。两套运行时、两种清单（`META-INF/mods.toml`、`META-INF/neoforge.mods.toml`）、两条事件总线 |
| **合并基底（merged base）** | `patched-mc-merged-26.2.jar`：带有两个 Forge 系补丁的 Minecraft 26.2，按字节合并成一个 jar；两个 Forge 系都改过的方法，凡是能证明的，都把两边的编辑保留在同一个方法体里（§2.4） |
| **载体（carrier）** | 某一个 Forge 系的运行时 jar（`neoforge-runtime.jar`、`forge-runtime-interop.jar`），作为被动的 ABI 提供者加载：它的类存在，但它的加载器生命周期从不运行 |
| **引导侧 / 游戏侧（boot side / game side）** | 前者指由系统类加载器加载的代码，后者指由 `ForbricClassLoader` 定义的代码 |
| **第三方（guest）** | 凡是属于第三方 mod 的东西（第三方 mixin、第三方 jar） |
| **原生（native）** | mod 在自己的加载器上得到的东西：Fabric mod 是 Fabric Loader 下的原版 26.2，MinecraftForge/NeoForge mod 是各自打过补丁的 26.2。每一项修复都以它为准来评判（§16.1） |

---

## 1. 要解决的问题

三个加载器都默认整个进程归自己管。把它们的 mod 放在一起跑，会以五种相互独立的方式出问题；内核的每个部分都在应对其中一种。

1. **启动权。** Fabric 启动 Knot；MinecraftForge 和 NeoForge 启动 ModLauncher/BootstrapLauncher，外加一个 JPMS 模块层。两个转换型类加载器，意味着每个游戏类都有两份定义。
2. **一个类，三套补丁。** 两个 Forge 系都会给*同一批* `net.minecraft` 类打补丁，插入的钩子各不相同，而一个 JVM 里每个类只能有一个版本。总得有某种机制在做得到的地方把两边的编辑放进同一个方法体，做不到的地方逐方法地决定保留谁的，然后还得应付落败方那边的 mod 原本的预期。
3. **生命周期与注册窗口。** 每个生态都有自己的阶段、自己的总线、自己那段允许写注册表的窗口，对冻结何时发生也各有各的看法。
4. **可见性。** 每个加载器都维护自己的 mod 列表。一个 mod 问自己的加载器“装了 Sodium 吗？”或“我在哪个平台上？”，得到的答案在单加载器实例里成立，放到这里就错了。
5. **为另一个游戏写的第三方字节码。** Fabric mixin 是对着原版字节码写的；MinecraftForge mod 是对着 MinecraftForge 打过补丁的游戏写的。到了合并基底上，锚点挪了位置，方法被拆开，字段改了类型，lambda 重新编号，父类也换了。

在 26.2 上，命名空间*不在*上面这几条之列：游戏发布时用的就是 Mojmap 名称，26.2 的 Forge 系 mod 按 Mojmap 编译，Fabric 的 mod 也一样（`KernelMappingResolver` 的 javadoc 记录了一次对 fabric-api 和 Jade 的常量池扫描，没有发现任何 intermediary 符号）。内核采用恒等映射：`TransformContext(…, "named")`，`KernelMappingResolver` 对每次查询都原样返回输入。

mod API 同样不在其列。Forbric 不重新实现 Fabric API、MinecraftForge API 或 NeoForge API：mod 调用的是装进来的那个真正的 Fabric API mod，以及载体里真正的 MinecraftForge/NeoForge 类。内核掌管的是原本由加载器掌管的部分：类加载、发现、生命周期、注册窗口，以及 Fabric Loader 自己的 API（§5.1）；此外还有合并带来的、不得不做的修复。其中有两类修复是复现原有行为，而不是调用它：一是 NeoForge 的 coremod 改写，由内核自己执行（§5.2）；二是钩子在合并中落败的事件，由内核重新发出（§8）。

## 2. 方案总览

一个 JVM，一个转换型类加载器，一个生命周期，一次注册表冻结。任何真正的加载器生命周期都不会启动：没有 Knot，没有 ModLauncher，没有 FancyModLoader 的发现流程，也没有模块层。

```
system class loader  (BOOT side)
 ├─ forbric-kernel.jar            net.forbric.kernel.{boot,classloading,discovery,fabric,transform,mixin,
 │                                 access,metadata,mapping,interop,ui,util,soak}, net.forbric.api,
 │                                 vendored net.fabricmc.api / net.fabricmc.loader surface
 └─ its dependencies               ASM, sponge-mixin, SAT4J, NightConfig, tiny-remapper, class-tweaker, mapping-io
     │  new ForbricClassLoader(owned, parent)
     ▼
ForbricClassLoader  (GAME side — the only loader that defines game/ecosystem classes)
 owned jars, in this order (KernelOwnedClasspath.compose):
   1. patched-mc-merged-26.2.jar           --gameJar
   2. forge-runtime-interop.jar,            --runtimeJar   (the two carriers)
      neoforge-runtime.jar
   3. Minecraft's own libraries             --libraryPath  (owned: mods mixin into DataFixerUpper & co.)
   4. kernel-bundled: mixinextras-fabric.jar, forbric-kernel-runtime.jar   (extracted to .forbric-kernel/lib/)
   5. guest mod jars: Forge-family, then Fabric, nested jars included; newest version first within one mod id
```

### 2.1 引导侧与游戏侧

`forbric-kernel.jar` 由父加载器加载，不引用任何游戏类型。凡是引用 `net.minecraft.*`、`net.minecraftforge.*`、`net.neoforged.*` 或 `net.fabricmc.fabric.*` 的代码，都放在 `src/runtime/java` 里（`net.forbric.kernel.runtime` 包及其 `soak`/`transfer` 子包，共 157 个文件）：以 `compileOnly` 方式针对暂存的游戏产物编译，打包成 `forbric-kernel-runtime.jar`，内嵌在引导 jar 的 `META-INF/jars/` 下，启动时由 `KernelBundledJars` 解出，再作为托管 jar 加载。

引导侧通过*字符串*访问游戏侧（`Class.forName`、`getMethod`、ASM owner 名）。`KernelRuntimeClasses` 登记了所有这样的字符串；启动时（在 Fabric `preLaunch` 入口点之后，§3.2 第 20 步），`KernelRuntimeClasses.verify(loader)` 会经由已经组装好的流水线把整条接缝加载一遍。这样一来，如果引导 jar 构建时缺了游戏侧那一半，或者游戏侧某个方法改了名，就会在游戏开始之前失败，而不是等到某个 mod 构造到一半才失败。游戏侧代码全部是编译而来：以前唯一在运行时生成的类（`KernelHudLayer`，给 Fabric HUD 桥用，§8）已随那座桥一起退役。

### 2.2 `ForbricClassLoader` 与委派表

`classloading.ForbricClassLoader` 是一个扁平的、不涉及 JPMS 的 `URLClassLoader`。`loadClass` 按以下顺序查询 `DelegationPolicy`：

- **ALWAYS_PARENT**：JDK、ASM（`org.objectweb.asm.`）、Mixin（`org.spongepowered.asm.`）、log4j/slf4j、NightConfig（某个载体自带一份未做 shade 的旧版本，否则它会按子优先规则胜出）、`net.fabricmc.api.`、`net.fabricmc.loader.api.`、`FabricLoaderInternals` 里列出的 Fabric Loader 内部类（按精确名称匹配）、`net.forbric.api.`，以及内核的引导包（`boot`、`classloading`、`transform`、`mixin`、`access`、`mapping`、`metadata`、`discovery`、`fabric`、`util`、`interop`）。整个 JVM 恰好只有一份。
- **ALWAYS_GAME**：平台自己的包：`net.minecraft.`、`com.mojang.blaze3d.`、`net.minecraftforge.`、`net.neoforged.`、`net.forbric.kernel.runtime.`、`com.llamalad7.mixinextras.`（由内核捆绑）以及 Mixin 的合成包。要么在这里定义，要么根本不定义。任何 mod 的包都不在这里，fabric-api 的也不在：它的类照样由下面的子优先规则在这里定义，因为这个加载器托管着它的 jar；而 `MixinFit` 正是靠这张表判断一个目标属于平台还是属于别的 mod，所以注入 fabric-api 的 mixin 和注入其他任何 mod 的 mixin 一样评判。
- **其余情况走子优先**：哪个托管 jar 里有这个类就在这里定义，否则交给父加载器。

`tryDefineGameClass` 读取字节，先跑 Mixin 前的转换链（`setTransformer`），再跑 Mixin 阶段（`setMixinTransformer`），最后带着一个真实的 `ProtectionDomain` 调用 `defineClass`（以该 jar 作为代码来源，JourneyMap、spark 等 mod 就是靠它找到自己的 jar）。这个加载器的其他职责：

- **Mixin 前的字节。** `getPreMixinClassBytes` 给 Mixin 提供走完转换链、尚未织入的字节；如果给的是织入后的字节，织入器就会把自己的输出再织入一遍。
- **生成的类。** `putGeneratedClass` 存放由转换器合成的类（class-tweaker 枚举扩展）；如果读到 `null` 且没有对应的生成条目，Mixin 就知道该由自己来合成。
- **备援 jar。** `setRescueJars` 设定的是在跨 jar 仲裁中被取代的那些 jar；*只有*在没有任何托管 jar 含有该类时才会查它们，因此它们无法遮蔽胜出方（§4.3）。
- **jar 所属生态。** `setJarFamilies` 记录每个 mod jar 被仲裁归入了哪个生态；`familyOfClass` / `familyOfResource` 为环境剥离器和加载器探测改写器提供依据。
- **可重入的定义。** 在 Mixin 第一次 `select()` 期间构造的第三方配置插件，可能会去加载当前正在定义的类；`define` 会取回已经完成的那份定义，而不是因重复定义抛出 `LinkageError` 而失败。
- **包清单。** 定义包时会带上所属 jar 的清单属性，因为真正的 FML 会读取 `Package.getImplementationVersion()`。

### 2.3 三个游戏产物

它们都不在仓库里，也不在任何 Forbric 下载包里：每一个都嵌有 Mojang、MinecraftForge 或 NeoForge 的代码。它们在哪台机器上运行，就在哪台机器上构建：给玩家用的由安装器构建（§13），给开发者用的由 `forbric-loader/run/` 下的脚本构建（§14）。

| 产物 | 说明 |
| --- | --- |
| `patched-mc-merged-26.2.jar` | 原版 26.2 + 打过 MinecraftForge 补丁的 26.2 + 打过 NeoForge 补丁的 26.2，由 `net.forbric.tools.MergedBaseBuilder` 按字节合并。以 NeoForge 的类为基础，再把 Forge 的类拼接进来；两个 Forge 系都改过的方法，凡是编辑拼接能证明的，两边的编辑都保留在同一个方法体里（§2.4）。构建时写在 jar 旁边的报告（`merge-conflicts.txt`）对现行工具构建的基底记录为：类数 `forge=184 neo=10150 MERGED=634`，`edit splice: methods=240 runs carried=350 already-present=85 conflicting=256 refused=201`，冲突 `CONFLICTS: methods=921 fields=8 STRUCTURAL(superclass/field)=19`。这个 jar 还带着每个平台自己的类，只供读取：`META-INF/forbric/native-reference/<FABRIC\|FORGE\|NEOFORGE>/` 里是该平台游戏全部类的 SHA-256 索引，以及合并后与之不同的那些类的原始字节，从不定义（`NativeGameReferences`；即 §7.4 所说的原生方法体） |
| `neoforge-runtime.jar` | NeoForge 的 `-universal` jar 加上它的 userdev 配置声明的库，合并成一个 jar（`NeoForgeRuntimeBuilder`） |
| `forge-runtime-interop.jar` | MinecraftForge 的 `-universal` jar 加上它的运行时库（`ForgeRuntimeBuilder`），再由 `net.forbric.tools.RuntimeInteropPatcher` 打补丁：合并时替 NeoForge 加宽了一些接口，而 Forge 自己编译好的实现已经满足不了它们。以坐标 `net.forbric:forge-runtime` 暂存 |

载体是*被动*的：它们的类会被定义，它们的事件总线也会被使用，但它们所属加载器的发现、排序和生命周期从不运行。内核只提供它们的代码会查询的身份信息（`PassiveSeeder`，§3.2）。

### 2.4 编辑拼接 —— 两边的编辑放进同一个方法体

两个 Forge 系都打过补丁的方法，以前合并只保留其中一边的方法体，另一边的钩子记为丢失。`EditSplicer`（在 `forbric-loader/src/tools` 里）改为把两边的编辑都保留下来，依据的是合并以前看不到的一个事实：

- **出处。** 两个打过补丁的游戏都是从打过补丁的源码重新编译的，都保留了 `LineNumberTable`，所以每条指令都带着它所来自的*补丁后*源码行号。每个 Forge 系的 userdev 产物里正好带着生成这些源码的补丁（MinecraftForge：620 个 `.java.patch`，普通 unified diff；NeoForge：881 个，DiffPatch 的 `+_` 写法）。`PatchLineProvenance` 沿着补丁把补丁后的行号还原成“原版第 N 行”或者“由这个编辑块新增”。于是每个方法体都能精确地分成两部分：该生态没动过的原版代码，以及它的补丁新增的指令段（run）。依据是补丁作者自己的记录，而不是从两次编译的差异里猜。
- **放置。** 两个方法体里的原版部分是同一批语句的两次编译，可以逐条指令对齐。另一边新增的每一段因此在基底方法体里有一个确定的位置：紧挨在它后面那条原版指令在基底里的对应指令之前。两边都在同一个插入点加了代码时，MinecraftForge 的那段排在前面。
- **什么可以搬过来。** 一段代码只有满足下面这些条件才会被搬进来：在两边都从语句边界开始、到语句边界结束（操作数栈为空）；从它跳出去的每个跳转都落在它的锚点或某条已对齐的原版指令上，没有任何跳转跳进它中间，覆盖它的异常区域在它自己的方法体里和插入的位置上相同；它读的每个局部变量在结果里的值都和在它自己的方法体里一样（它自己的局部变量拿一个新槽位）；结果里每条能到达它的基底指令，在它自己的方法体里都有能到达它的对应指令；而且结果里确实有代码能到达它。纯插入的代码按语句逐条搬运，所以基底在同一位置已经插入过的语句只运行一次。
- **什么算冲突。** 替换了原版行的一段，只有在基底没动过这些行、而且基底自己的代码不依赖这些行时，才替换基底里的那份。基底也改了同样的行时，两边编辑相同就只保留一份，不同就是冲突：基底的编辑保留，报告写明丢了什么、留了什么（`both-changed`）。两个补丁都新增了同名成员、而这个方法里两边的编辑都在操作它，或者两边都删掉了同一段原版代码、各自在原处放了自己的代码，说明两个生态把同一个功能各实现了一遍，两份都运行就会运行两次：同样算冲突（`both-reimplemented`）。属于一体的几段要么一起搬，要么都不搬——同一个 `finally` 块的各份副本、同一个对象上的 begin/end 配对、一条语句和读取它计算结果的语句、一次删除和依赖这次删除的那条被挪走的语句；搭档搬不过来的那段会被扣下，方法去掉它重新规划。
- **诚实性检查。** 每个拼接出来的方法都要通过校验（在合并的声明图上跑 `SimpleVerifier`，重算栈帧）；校验不过的整个拒绝，保留拼接之前的仲裁结果。然后 `MergedBaseBuilder` 检查只有看整个类才能发现的问题，并反复重新合并这个类直到不再变化：搬过来的代码如果直接或经由它调用的方法读取一个私有字段，而能运行的代码里没有谁写它，就扣下（否则就是合并后游戏里一个等着发生的 `NullPointerException`）；类里每个指向自身的方法句柄必须和它所指方法的种类一致，基底一侧的代码必须捕获基底自己的 lambda——某个类的拼接一旦引入这类问题，它的所有拼接都被拒绝。两边编译成同一个原版 lambda、在同一个原版位置捕获的 lambda，留在逐方法合并给它的位置；被重新对齐挤掉、自己的编辑因此不再运行的 lambda 方法体，以它自己的行格式记为该生态丢失的钩子，而不是从统计里消失。任何类名、方法名或 mod 名都不参与决定。
- **报告。** `merge-conflicts.txt` 的 `=== edit splice` 一节逐个方法写 `SPLICED` 或 `NOT SPLICED (原因)`，然后逐段写：`+` 已搬入，`=` 只保留一份或冲突（带丢失和保留的调用），`x` 拒绝，并写明原因。
- **丢失棘轮（loss ratchet）。** `LostHookAttribution.platformCensus` 统计每一次“打过补丁的游戏在某个调用方里调用了平台方法、合并基底在同一个调用方里却不再调用”的出现次数。编辑拼接把这个数字在 MinecraftForge 一侧从 794 降到 409，NeoForge 一侧从 16 降到 15；随后诚实性检查把搬错的部分退了回去（以前会触发、但答案被丢掉的钩子，现在如实记为丢失），数字停在 508 和 15。`MergedBaseHookLossRatchetTest` 把合并限制在这两个上限之内（合并工具的改动只能降低它们，不能抬高），并检查每一条钩子丢失记录指向的方法三个游戏都有、丢失在基底里确实存在，而且没有哪个调用方没被观察到。它只读带着构建记录的基底：`build-merged-base.sh` 写在 jar 旁边的 `patched-mc-merged-26.2.jar.provenance.json`，用哈希点名 jar 和报告；CI 用准备好的输入构建这样一个基底并运行它（§16）。
- **调进某一边委托孪生方法的调用**（`DelegateTwinRoutes`，构建器的 PASS 6）。一个 Forge 系有时给原版方法 `C(args)` 加一个“模式”：真正的方法体放进它自己的 `C(args, extra)`，`C(args)` 只带着常量转调过去，而某个调用方传另一个常量，因为同一个生态在它自己版本的“调用方的调用方”里做了被关掉的那部分。另一边的 `C` 在合并中胜出时，这个调用方——另一边没改它，所以整个取自第一边——会跳过保留下来的 `C` 和其中所有钩子，把被关掉的那部分交给一个合并时已经丢掉的补偿。补偿确实不在时，这个调用会被改回 `C(args)`：合并后的调用方里没有一个保留了第一边对它的全部编辑，而且编辑拼接带进来的那部分编辑里，也没有哪个调用做的是模式关掉的事（每个带进来的调用沿它方法体里的 void 调用往下看两层，在第一边及其运行时里读；再和孪生方法在被改动的模式参数受测试时做的调用比较，这个参数被交给哪个声明，就跟到哪个声明里去；被关掉的部分读不出来、而又带进来了一部分编辑时，这个调用保持不动，留待判断）。在 26.2 上恰好改了一处：`Block.playerDestroy` 调的是 MinecraftForge 的 `dropResources(…, false)`，而合并后的 `ServerPlayerGameMode.destroyBlock` 是 NeoForge 的（它把经验交给 `handleBlockDrops`，MinecraftForge 的则是自己弹出），所以玩家挖方块不掉经验，也从不发布 `BlockDropsEvent`。合并后的 `destroyBlock` 带着 MinecraftForge 的 `onBlockStartBreak` 和 `onPlayerDestroyItem`，没带它的 `popExperience`，而被关掉的部分是 `ForgeHooks.dropXpForBlock` 及其中的 `popExperience`，所以这个调用现在走到 NeoForge 的 `dropResources`。其余 21 个候选保持不动；每个决定及其原因写在报告的 `=== delegate-twin routes` 一节。

保留两边的编辑有代价，由内核在运行时承担：另一个生态的编辑可能打开那个生态加载器里一个以前从未运行过的机制。合并后的 `Bootstrap` 现在也会运行 MinecraftForge 自己的原版快照，它会锁住 MinecraftForge 的注册表包装器（注册窗口也会打开这把锁，§3.4）；合并后的 `Main.main` 和 `Minecraft.<init>` 保留了 MinecraftForge 自己的加载器调用（被重定向或清空，§3.3）；一个现在同时调用两边钩子的方法会原生地发布 MinecraftForge 事件，所以每个转发这个事件的桥都必须在那里退让（§8.1）。

提交在 `forbric-loader/run/merged-base/merge-conflicts.txt` 的那份，是 `forbric-loader/run` 里那份合并基底旁边写出的报告，那份基底由现在的工具（包括编辑拼接）构建，所以用这个目录做成的暂存根，基底和它自己的报告是配对的。`LostHookAttributionTest` 把其中每一条 hook 丢失记录按写出的样子读回来，不钉死它列了哪些方法、列了多少条。

## 3. 启动顺序

### 3.1 入口

`boot.KernelClientLaunch.main` 和 `boot.KernelServerLaunch.main` 都只有一行：

```java
int code = CompatibilityLaunchBoundary.run(() -> KernelBoot.launch(KernelBoot.Side.CLIENT, args));
if (code != 0) System.exit(code);
```

兼容性拒绝只会在 `CompatibilityLaunchBoundary` 这一处变成进程退出（退出码 `78`，§12.4），安装损坏而被拒绝的启动也只在这里退出（退出码 `2`，§3.2 第 0 步）。其他任何离开引导过程的异常都会先在这里写进 `latest.log`（消息和堆栈），然后原样重新抛出，因为启动器展示的是这个文件，而不是 stderr。`KernelBoot.launch` 自己处理 `--gameJar`、`--runtimeJar`（可重复，一个值里也可以用路径分隔符连接多个 jar —— PCL2 这类启动器遇到重复的参数只保留最后一个）和 `--libraryPath`；其余参数，以及 `--` 之后的全部内容，都转给游戏的 `Main.main`。专用服务器不接受 `--gameDir`，所以 `KernelBoot` 在服务端会把它去掉。游戏版本从基底 jar 的 `version.json` 读取（读不到时回退为 `26.2`）。

### 3.2 `KernelBoot.launch` 的执行顺序

0. **启动输入** —— `LaunchInputCheck.require(gameJars, runtimeJars)`，按内容判断，并且在从这些 jar 里读取任何东西之前进行：基底的 `Block` 必须实现两个 Forge 系各自的扩展接口（即合并基底）；每个 `--runtimeJar` 必须完整携带一个 Forge 系（加载器 SPI、`ModContainer`、`FMLLoader`、`FMLEnvironment` 和它自己的 `mods.toml`；只有 `mods.toml` 的 jar 会被报告为该 Forge 系的一个 mod）；两个 Forge 系都必须由这次启动拥有的某个 jar 携带。不通过时，每个问题和修复办法（重新运行安装器，*Built artifacts* 留空）都写进日志，并以退出码 `2` 停止；`-Dforbric.launchInputCheck=off` 只发出警告。Issue #13：空的"运行时" jar 曾经在后面每一步都得到一个合法的空结果，最后在 `KernelRuntimeClasses.verify` 里死在 stderr 上，`latest.log` 里只有五行 INFO。这项检查只看条目名，不看每一个类；它留给后续步骤处理的情况列在该类的 javadoc 里。
1. **跨 jar 仲裁预扫描** —— `DuplicateModArbiter.arbitrate(mods/, envType)` 清点所有根候选和内嵌候选，在任一生态的发现开始之前先定下唯一的选择（§4.3）。
2. **载体版本** —— `EcosystemVersions.record(runtimeJars)`，这样一旦某个 mod 的 `versionRange` 载体满足不了，发现它的当下就能报出来。
3. **Forge 系发现** —— 所有带 Forge 系清单的 `mods/*.jar`，再加上仲裁方案选中的 JarJar 子 jar（`META-INF/jarjar/`），后者取自该方案在 `.forbric-kernel/candidates/` 下按内容寻址解压出的副本（旧的解压器写入 `.forbric-kernel/jarjar/`，只在仲裁关闭时运行）。
4. **在场信息** —— `ModPresence.publishForgeFamily(…)` 必须在构建 Fabric 侧之前调用，因为 Fabric 侧会把它读回去。
5. **Fabric 发现** —— `KernelFabricEcosystem.scan`，然后对所有内嵌 jar 的并集再做一轮仲裁（`DuplicateModArbiter.arbitrateNested`）；落败方从两份列表中都移除。
6. **Mixin 配置声明** —— Forge 系配置，来源包括 mod jar、内嵌 mod jar *以及载体*（NeoForge 自己的 `neoforge.mixins.json`）。
7. **构建 Fabric 生态** —— `KernelFabricEcosystem.build` 创建 `FabricLoader` 视图（§5.1）。
8. **托管 classpath** —— `KernelOwnedClasspath.compose`（顺序见 §2）。
9. **静态审计** —— 在加载任何东西之前扫一遍所有第三方 jar：`PortingLayerAudit`（自带 `net.neoforged.*`/`net.minecraftforge.*` 的 Fabric jar）、`FabricApiModuleLossAudit`、`FieldDriftAudit`、`MergedBaseUncalledMethods.scanGuests`、`AbiLinkAudit`（jar 里引用了、却在任何载体、基底或已安装的 jar 中都不存在的 Forge 系类）。
10. **类加载器** —— `new ForbricClassLoader(owned, bootLoader)`、备援 jar、jar 家族、`LoaderProbePolicy.bindGuestLoader`、`KernelFabricLauncher.install`（mod 的 `addToClassPath` 最终落到这里）。
11. **转换链** —— 131 个 `chain.register(…)` 调用点（§6）。
12. `loader.setTransformer((name, bytes) -> chain.applyBeforeMixin(name, bytes, ctx))`；上下文类加载器换成游戏类加载器；绑定 `KernelLifecycle`、`KernelHudBridge`、`LootTableEventDispatch`；`KernelLoadReport` 和 `CrashAttribution` 拿到运行目录（以及各自的关闭钩子）。
13. **加载器身份，先于 Mixin** —— `PassiveSeeder.seedNeoForgePaths`、`seedNeoForgeLoader`、`seedForgeFmlLoader`、`publishForgeLoadingList`。这一步必须早于第一个经过 Mixin 转换器的类，因为 Mixin 会在那一刻构造所有第三方 `IMixinConfigPlugin`，而这些配置插件会在 `<clinit>` 里读取 `FMLPaths`/`FMLLoader`。MinecraftForge 的 `FMLEnvironment` 在一次性的 `<clinit>` 里把 `dist` 缓存进 `static final` 字段；谁先碰到它，它的值就永远由谁决定。随后是 `KernelFabricEcosystem.constructLanguageAdapters()`：Fabric mod 声明的每个语言适配器都在这里构造，和 Fabric Loader 的 `freeze()` 一样早于 `FabricMixinBootstrap.init`（§5.1）。
14. **Mixin** —— 先放 Fabric 配置，再追加 Forge 系配置（§7.1）；注册之前先调用 `MixinConfigOwners.publish`；`KernelMixinBootstrap.init`。
15. `PassiveSeeder.reportDependencies()` —— 放在 Mixin 之后，因为它要报告的内容有一半（写给另一个 mod、却没有挂上的 mixin）是在 Mixin 解析配置时才记录下来的。
16. `PassiveSeeder.seedAll` —— NeoForge 的 `FMLLoader`、`ModList`、路径；MinecraftForge 身份（幂等）。
17. 审计报告（其中有 `MixinOverlapLint`，§7.6）、`KernelLoadReport.writeEvidence()`，然后是 `CompatibilityDecision.requireContinuation(isClient)` —— 进入游戏之前的决策点（§12.4）。
18. `NeoGraphicsBootstrappers.run` —— NeoForge 的 `GraphicsBootstrapper` 服务（按 SPI 自己的规则：`mods/` 顶层、被加载为 NeoForge 的 jar 在 `META-INF/services/…earlywindow.GraphicsBootstrapper` 里列出的提供者），在真正的 FML 运行它们的位置运行：最先，在游戏创建窗口之前。Sodium 的 NeoForge 构建把它的启动前检查、显卡探测和驱动 workaround 都放在这里。提供者抛异常只连累它所在的 jar（记 ERROR，该 jar 的每个 mod 标为 FAILED），不终止启动；`-Dforbric.graphicsBootstrappers=off`。NeoForge 的 `FMLConfig` 回答 `earlyWindowControl=false`，和关掉早期窗口的真正 NeoForge 一样：Forbric 没有早期窗口。
19. `KernelFabricEcosystem.runPreLaunch()` —— Fabric `preLaunch` 入口点，在 Mixin 之后、任何游戏类之前运行。
20. `KernelRuntimeClasses.verify(loader)` —— 让内核自己的游戏侧过一遍已经搭好的流水线。放在 `preLaunch` 之后，是因为证明一个类能链接会解析它公开方法里提到的每个类型，因此会定义大约 40 个 Minecraft 类；一个按 Fabric 允许的方式在 `preLaunch` 里注册 mixin 配置的 mod，会发现自己的目标类已经被定义了（Essential 遇到的是 `MixinTargetAlreadyLoadedException`）。
21. 加载入口类（`net.minecraft.server.dedicated.DedicatedServer` / `…client.gui.screens.TitleScreen` 是普查标志点；实际调用的是游戏的 `Main`）。如果 `LifecycleHookInjector` 没找到它的触发点，**内核拒绝启动**（`missedRequiredExcision()`）。
22. `Main.main(gameArgs)` —— 原版启动流程，其中真正的加载器触发点已被重定向。

### 3.3 被重定向的触发点

两个入口点在合并中都由 NeoForge 胜出。`transform.LifecycleHookInjector` 在每个入口点里重定向一条 `invokestatic`（改 owner 和 name，描述符不变）：

| 侧 | 合并基底中真正的调用 | 现在调用 |
| --- | --- | --- |
| 服务端 | `net.neoforged.neoforge.server.loading.ServerModLoader.load(Z)V`，位于 `net.minecraft.server.Main.main`，在 `Bootstrap.bootStrap()` 之后；编辑拼接后的基底还在它前面保留了 MinecraftForge 的 `if (!initSettings) ServerModLoader.load()` | `KernelLifecycle.onServerModLoading(boolean)` / `onServerModLoadingNoArg()`：两种形式都被重定向，内核的窗口每个进程只打开一次（`KernelLifecycle.openWindow`），在先到的那个打开，随后只跟一次 Fabric 的 `Hooks.startServer(null, null)` 标记（`-Dforbric.fabricHooks=off` 会省掉它） |
| 客户端 | `net.neoforged.neoforge.client.loading.ClientModLoader.begin()V`，位于 `net.minecraft.client.main.Main.main`，在 `Bootstrap.validate()` 之后、`new Minecraft` 之前 | `KernelLifecycle.onClientModLoading()` |

客户端稍后对两个 Forge 系各自 `ClientModLoader` 的调用（`finish`、`completeModLoading`）由 `MethodBodyNeuter` 替换成存根；`setupModResourcePacks` 则改为重定向到 `KernelLifecycle.onClientResourcePacks`（§9.2）。编辑拼接后的基底保留下来的 MinecraftForge 自己的加载器调用也这样处理，各自在被保留的调用本会运行它的地方：`Minecraft.<init>` 里 MinecraftForge 的 `ClientModLoader.begin(Minecraft, PackRepository, ReloadableResourceManager)`（它的方法体*就是* MinecraftForge 的生命周期）被清空；它的生物群系修改器流程（保留在 `initServer` 里）和 mod 包查找器（保留在 `configurePackRepository` 里）在两侧都被清空，因为这些工作已经搭在 NeoForge 的流程和内核自己的包上（§9）；它的 `ImmediateWindowHandler` 在游戏开始前拿到 FML 自己的无早期窗口提供者，因为被保留的 `Window.<init>` 检查会解引用它。

在客户端，同一个注入器还让 `Main.logEarlyException` 先调用 `KernelLifecycle.onEarlyStartupFailure`。这是原版给 `Main.main` 开头三步（检测版本、构造参数解析器、解析参数）准备的处理器：它只打印到 stderr，随后 `main` 直接退出（249、252、251）而不抛出异常，所以没有这个钩子时，结束游戏的那个错误永远到不了 `latest.log`。

### 3.4 原生注册窗口 —— `KernelLifecycle.driveNativeRegistration`

两侧走的是同一套步骤（源码注释里给它们编了号）：

- **0** 预置 MinecraftForge 的 `LoadingModList`；接上 Forge 的 `LogicalSidedProvider` 执行器；在客户端加载每个载体的内置翻译（`CarrierLanguages`）。
- **1** 注册 NeoForge 的基线注册表（`PassiveSeeder.seedNeoForgeRegistries`）；应用 NeoForge 自己的注册表修改（同步标志、回调）。
- **2** `registerNeoForgeContent` —— 注册窗口本身：
  1. 在内核创建的总线和容器上构造 `NeoForgeMod`（`KernelModContainerFactory`）；
  2. 构造每一个 Forge 系 `@Mod`（`KernelModLoader.constructMods`，§5.2/5.3）；
  3. 注册第三方 `@EventBusSubscriber` 类（`KernelEventSubscribers.registerAll`）；
  4. 向两个 Forge 系发布 `FMLConstructModEvent`；注入 MinecraftForge 的 capability；
  5. NeoForge `GameData.vanillaSnapshot`，然后**解冻**（`openRegistrationWindow`：注册调用和注册表之间的每一道闸门，不管是谁关上的——原版的 `frozen` 标志，以及 MinecraftForge 的包装器锁和冻结的 `ForgeRegistry`；编辑拼接后的 `Bootstrap` 也会运行 MinecraftForge 自己的原版快照，因而会关上后两道；第 9 步把它打开时发现关着的再关回去）；发布 `NewRegistryEvent`；
  6. 按 NeoForge 的注册顺序，在每条总线上为每个注册表触发 `RegisterEvent`；然后是 MinecraftForge 基线（`KernelForgeBaseline.register`）；
  7. 打开 `minecraft:root`，运行 Fabric `main` 入口点（在客户端，只有 `-Dforbric.fabricMainInConstructor=off` 时才在这里运行；见 §3.5）；
  8. 属性事件、生成位置规则、`BlockEntityTypeAddBlocksEvent`、mod 添加的游戏规则分类、NeoForge 的提示框追加器（`DefaultAttributes` 在 MinecraftForge 的属性事件遍历实体类型注册表之前先初始化：一个在这个类的初始化里注册实体类型的 mod 曾让遍历抛出 `ConcurrentModificationException`，事件永远没跑完，没有任何实体拿到 MinecraftForge 的属性）；
  9. `closeRegistrationWindow`（在 `finally` 里）：补上还没关联的方块物品（正常情况下没有；§9.4），**冻结**，重建 NeoForge 的方块状态→id 映射，重新排序 NeoForge 的创造模式物品栏标签页。
- **2a–2c3** 校验 REGISTRATION 桥；在 `ModList` 中发布 NeoForge 基线；加载 STARTUP/COMMON 配置（客户端上还有 CLIENT）；接上两个载体自己的 `@EventBusSubscriber` 类；安装客户端重载监听器桥。
- **3a** 声明数据包注册表（`DataPackRegistryEvent.NewRegistry`，Fabric 动态注册表双向镜像）—— 在客户端会推迟到 Fabric 入口点之后（`DatapackRegistryDeclaration`）。
- **3a2** 启动游戏总线（`startGameBuses`）—— 必须在初始化阶段之前，因为在尚未启动的总线上调用 `IEventBus.post` 会静默返回。
- **3b**（服务端）向两个 Forge 系的第三方 mod 发布初始化生命周期（`fireModSetupLifecycle`）：通用初始化、专用服务器初始化、NeoForge 的 `RegistrationEvents.init()`（逐步执行，见 `RegistrationEventSteps` —— 它会发布 `RegisterCapabilitiesEvent` 和 `RegisterDataMapTypesEvent`）、IMC 入队/处理、加载完成；各阶段之间，延迟任务在每个 Forge 系各自使用的线程上运行（`NeoDeferredWork`；失败由 `DeferredWorkFailures` 读回）。随后写出 `load-report.txt`，并再次询问 `CompatibilityDecision.requireContinuation` —— 这是加载结束时的决策点。在客户端，这些阶段（包括通用初始化）全部推迟到 `onNeoClientSetup`（§3.5），因为此时 `Minecraft.getInstance()` 还是 null。
- **3b2** 打开晚注册的配置（在构造或初始化期间注册的）。
- **3c**（服务端）关闭 NeoForge 的负载注册阶段（`setupNeoForgeNetwork`）。这一步必须放在初始化之后：mod 会在 `FMLCommonSetupEvent` 里注册负载。

注册窗口只有一个，冻结也只有一次。焊接方案撞上的那堵“Tags not bound”墙（两个生态轮流重新冻结）在这里不可能出现。

### 3.5 `Minecraft.<init>` 里的客户端专用钩子

Fabric 和 NeoForge 在构造函数里需要的状态正好相反，所以内核设了两个锚点：

- `ClientEntrypointHookInjector` → `KernelLifecycle.onClientEntrypoints()`，在 `Options` 存在之前：重新打开注册表（以及 MinecraftForge 的注册表闸门），构造那些因为过早去拿 `Minecraft` 而被暂缓的 MinecraftForge mod，先运行 Fabric `main` 再运行 `client` 入口点（即 Fabric `Hooks.startClient` 的顺序），重新关闭并重新冻结，打开晚注册的 CLIENT 配置，然后声明数据包注册表。这次冻结前后就是 Fabric 的注册表冻结点：有 Fabric mixin 推迟冻结时（按推迟冻结的那个重定向 `Bootstrap` 里 `bootStrap()` 调用的形状识别，不管是谁声明的——fabric-registry-sync 就是这样做的），Fabric mod 挂在 `BuiltInRegistries.freeze()` 头尾、或 `bootStrap()` 里调用 `freeze()` 前后的 `@Inject` 在这里运行（`FabricFreezeHookMixinAdapter`），也就是原生 Fabric 冻结的地方——入口点之后，`Minecraft.getInstance()` 已经有值。LiquidBounce 用这样的注入建它的创造模式标签页，留在 `Bootstrap` 里时拿不到客户端，直接崩溃。
- `NeoClientSetupHookInjector` 挂在合并基底调用 `ClientModLoader.finish()` 的位置 → `KernelLifecycle.onNeoClientSetup()`，在 `options` 赋值之后：校验 CLIENT_INIT 桥；预加载客户端资源管理器（MinecraftForge 在第一次资源重载内部运行 mod 加载，它的 mod 都指望在客户端初始化时就能读到自己的资源；`-Dforbric.clientResourcePreload=off`）；然后是 `fireClientSetupLifecycle`：通用初始化、客户端初始化、`RegistrationEvents.init()`、IMC、加载完成 —— 和真正的 NeoForge 一样，此时注册表处于冻结状态 —— 接着写出 `load-report.txt` 并做加载结束时的决策（`requireClientContinuation`），再处理晚注册的配置，最后关闭负载注册阶段。

## 4. 发现与仲裁

### 4.1 读取清单

- `discovery.ForbricModDiscoverer` 按描述文件给 jar 分类 —— `fabric.mod.json`、`META-INF/mods.toml`、`META-INF/neoforge.mods.toml` —— 并报告一个 jar 携带的*每一份*清单。最终加载哪一份由策略决定（§4.2）。
- `metadata.forge.ModsTomlParser`（净室实现；TOML 词法分析用 NightConfig），配合 `ForgeModsToml`、`ForgeModEntry`、`ForgeDependency`；`ForgeVersionRangeTranslator` 把 Maven 版本范围转成 Fabric 风格的谓词，所以 `net.forbric.api.UnifiedDependency` 只有一种写法（由 `VersionPredicate` 求值）。`UnifiedDependency` 还保留了 Fabric 里没有对应说法的两个维度：顺序（`BEFORE`/`AFTER`）和适用的侧。
- `fabric.FabricModMetadataParser` 是完整的 `fabric.mod.json` v1 读取器（入口点，包括适配器形式；`jars`；按侧区分的 `mixins`；`accessWidener`；`custom`）。`fabric.FabricModDiscovery` 会顺着 Fabric JiJ 继续发现（解压缓存在 `.forbric-kernel/jij/`）；`environment` 排除了当前侧的 mod 会被跳过，和 Fabric 上一样。
- 内嵌的 Fabric mod 按 fabric-loader 0.19.5 的 `ModSolver` 的规则决定加不加载（`fabric.NestedFabricRequirements`，由 `NestedCandidateInventory` 执行；没有选择计划时由 `FabricModDiscovery` 执行）：对 `minecraft` 或 `java` 的 `depends` 不包含当前版本，或者 `breaks` 包含当前版本，它就不加载；硬依赖只能由这些被排除的 mod 满足、或者只被它们内嵌的内嵌 mod 也一起不加载。实例是 ViaFabric：它在 `viafabric-mc26-2` 旁边还内嵌了 `viafabric-mc26-1`（`minecraft >=26.1 <=26.1.2`），原生只加载 `viafabric-mc26-2`。（Fabric 的"每个 id 只加载一个 mod，provides 也算"在仲裁之后决定，见下一条。）只判定 Fabric mod 在自己的 `jars` 里声明的 jar。NeoForge/MinecraftForge mod 内嵌的 jar 不判定，哪怕里面只有一个 `fabric.mod.json`，因为 Fabric Loader 从不打开没有 `fabric.mod.json` 的 jar。父 jar 的 `META-INF/jarjar/metadata.json` 声明过的子 jar 归 FML 管，FML 把它当成父 mod 的库加载；只是放在 `META-INF/jars/` 或 `META-INF/jarjar/` 里、在这个文件里没有条目的 jar，没有任何原生加载器会加载，内核保留它是因为内核对 Forge 系 mod 的遍历一直都会走这两个目录。两条路都能走到的 jar 保留，不管遍历先走了哪条。有一处和原生不同：内核要加载的某个 mod 硬依赖某个 id，而会加载的 mod 里没有一个满足这条要求时，被排除的副本中满足要求的那些会被留下，连同把它一路内嵌到某个已加载父 mod 的那些 jar，并打一行 WARN：`[Forbric/JiJ] nested <id> <version> in <parent> loaded although Fabric Loader would leave it out (<原因>): <依赖方> requires <id> <范围>, and nothing else installed meets that`。如果已安装的副本没有一个满足要求，而且会加载的 mod 里根本没有这个 id，就留下所有被排除的副本（规则加入之前内核本来就加载它们），WARN 的结尾换成 `<依赖方> requires <id> <范围>; no installed build meets that, and no other <id> would load`。依赖方在 `mods/` 里时原生会拒绝启动；内核在缺依赖时也照样加载这种 mod，所以把提供者排除掉只会让它少一个依赖。最终仍被排除的每个 mod 都打一行 `[Forbric/JiJ] nested <id> <version> in <parent> left out: <原因>`，它内嵌的 mod 也一样：被排除的 jar 仍会被打开，这样已加载的 mod 需要其中某个 jar 时才看得见。`mods/` 里的 jar 从不在这里判定；`fabricloader`/`mixinextras` 的范围、读不懂的范围、以及没有任何已安装 mod 能满足的依赖都不会让任何东西被排除。没有选择计划时（`-Dforbric.crossJarArbitration=off`），`FabricModDiscovery` 只走 `mods/` 和 Fabric 的 `jars`，所以 `KernelBoot.scanFabricMods` 把它读不到的 jar 的硬依赖交给它：Forge 系 mod 的，以及它们 JarJar 里那些 jar 的 Fabric 清单的（这些 jar 在这条路上不是 Fabric 容器，但在 classpath 上）。`-Dforbric.nestedRequirements=off` 恢复为全部加载；`off:<id>,<id>` 只放过这几个 id（内核不读 Fabric 的 `config/fabric_loader_dependencies.json`）。
- 每个 id 只加载一个 Fabric mod，`provides` 也算（`fabric.FabricUniqueIds`，作用在选择结果上：`DuplicateModArbiter` 在 `ReachableCandidateSelector` 之后调用，没有选择计划时由 `FabricModDiscovery` 调用）。Fabric Loader 把每个候选按自己的 id 和它 provides 的每个 id 建索引（`ModPrioSorter.sort`），每个 id 最多加载一个（`UNIQUE_ID`）。`mods/` 里的 jar 只有在它是自己某个 id 的唯一持有者时才强制加载（`ROOT_FORCELOAD_SINGLE`）；其余候选的权重都是 `-2^(n - 优先级)`，所以选择结果是 `ModPrioSorter` 顺序上的字典序最优（`mods/` 里的 jar 在前，然后按 id，再按版本从高到低；和前面某个 mod 共用 id 且版本更高的会被挪到它前面）：按这个顺序，每个 mod 在前面的 mod 都定下之后能加载就加载；内嵌 mod 需要一个会加载的父 mod，每个 mod 的每条硬依赖都要有会加载的提供者；硬依赖没有任何已安装 mod 能满足的直接关掉（`HARD_DEP_NO_CANDIDATE`）。内核用 SAT 求解器求同一个最优解。实例是 owo-sentinel：需要 owo-lib 的 mod 会内嵌它，让它在缺 owo-lib 时提示玩家去装，它 provides `owo` 和 `owo-lib`；装了 owo-lib 时原生从不加载它，内核以前两个都加载。两个共用 id 的内嵌 mod 通常是 Fabric 排在前面的那个胜出，但如果有一个排在它们两个前面的 mod 需要另一个，就留下另一个：这个 mod 是另一个内嵌的，或者依赖另一个。这一步只在仲裁选中的 jar 之间决定，绝不提前：被仲裁丢掉的构建（另一个生态更优先的副本、别处更新的副本、只被一个落选的库内嵌的副本）不会把它的对手挤掉，对这个共用 id 的依赖也不会左右仲裁留下某个 mod 的哪个构建。之后每个在争夺中落选的 mod 都被排除，同 id 的其他构建如果也会以同样方式输给 `mods/` 里的 jar 就一起排除（所有 owo-sentinel 构建一次排完）；只有当被排除的东西（落选者，或跟着它走的）还有别的构建可以顶上时，仲裁才再跑一轮：owo-sentinel 和 owo-lib 同装时只跑一轮，日志是 `one mod per id: decided in <n> arbitration round(s)`。只是跟着落选者走的 mod 每轮重新判定；需要落选者的 mod 在后面的轮次里也照样不加载。同一个 mod 的多个构建永远不是对手，只共用一个别名时也一样（每个 `mixinextras-fabric` 副本都 provides `com_github_llamalad7_mixinextras`）。只有 Fabric Loader 自己的索引参与：`mods/` 里的 Fabric jar，以及 Fabric Loader 自己能走到、没有被 `off:<id>` 豁免的内嵌 Fabric mod；它们的硬依赖由索引里的 mod 满足，和在 Fabric 上一样左右选择。其余的（Forge 系 mod、被 JarJar 声明或 Forge 系遍历带进来的 Fabric mod、被豁免的 mod、没有 `fabric.mod.json` 的库）只要有一个父 mod 加载就加载，跟着父 mod 走，从不左右胜负，也不为 Fabric 的选择满足任何依赖；它们自己的需求 Fabric Loader 看不到，只在 Fabric 的选择定下之后，靠把本该落选的 mod 留下来满足。落选者打 `left out: it answers to '<id>', as <胜者> <版本> [in mods/] does, and Fabric Loader loads one mod per id, provides included`；跟着它走的打 `left out: only mods left out bundle it`；依赖只有落选者能满足的 mod 打 `left out: it requires <id> <范围>, and every installed build of that is left out`。内核比原生宽松的地方：`mods/` 里的 jar 全部加载（对手拿走了它的所有 id、而它没有任何一个 id 被自己独占时，原生会丢掉它，例如 Sodium provides `indium` 时旁边的 Indium；两个被强制加载的 jar 冲突时原生拒绝启动）；硬依赖没有任何会加载的 mod 能满足的内嵌 mod，在求原生的选择时当作已关掉，之后再连同跟着它的 mod 一起放回来，只要不挤掉原生会加载的任何 mod，所以它让出所有共用的 id，没人争时照样加载；`mods/` 里的 Fabric jar 或 Fabric 索引之外的 mod 需要某个本该落选的 mod（按它的 id 或它 provides 的 id，或者它内嵌的 mod），而会加载的 mod 里没有别的能满足时，这个落选者被留下，连同把它一路内嵌到某个会加载的父 mod 的那些 mod、以及它带来的（它内嵌的 mod、需要它的 mod），胜者也照样加载，打 `loaded although Fabric Loader would leave it out (<原因>): <mod> requires <id> <范围>, and nothing else that loads meets that` WARN（因为它内嵌的东西被需要而留下时是 `it bundles <mod> (...)`）（需要它的是 `mods/` 里的 jar 时，如果胜出的 `mods/` jar 没有被自己的某个 id 强制加载，原生会丢掉它，否则拒绝启动；来自 Fabric 索引之外的需求原生根本看不到）。Fabric 判定的内嵌 Fabric mod 不会让任何 mod 被留下：和原生一样，它自己不加载。被留下的是仲裁为这个 id 选中的那个构建；另一个不需要被留下的已安装构建不会被尝试；胜者是内嵌 mod 而不在 `mods/` 里时，每轮只排除落选者 id 的一个构建。不参与权衡的：mod 之间的 `recommends` 和 `breaks`，以及在 `provides` 里列出自己 id 或把某个别名写两次的 mod（原生永远不加载它）。没有选择计划时没有仲裁：同一个 id 的多个副本一次权衡一个，最先发现的先权衡（也就是 `KernelFabricLoader.register` 会保留的那个）；一个副本在争夺中落选，就连同只有它内嵌的 mod 一起不加载，再权衡下一个副本。`forbric.nestedRequirements` 的开关同样适用。在本地语料（70 个 `mods/` 目录）里只有两种情况：owo-sentinel 和 owo-lib 同装；voxy 的 `dummyprovider`（只在服务端，provides `sodium`，而 Sodium 只在客户端，两者永远不会同时出现）。
- `discovery.ModAnnotationScanner` 按字节码描述符查找 `@Mod` 类；`ModFileScanner` 构建完整的 `ModFileScanData`（NeoForge 和 MinecraftForge 的形态不同：`EnumHolder` 与 `EnumData`），因为 JEI、Jade、Sophisticated Core 和 Sodium 都通过 `ModList.getAllScanData()` 找自己的插件。
- `net.forbric.api.DiscoveredMod` 是唯一的 mod 模型。

`--scan` 模式（`boot.Main --scan --mods <dir> --report out.json`）只运行发现，并写出确定性的 JSON；`run/diff-oracle.sh` 用一个独立的 Python 读取器解析同样的清单，与它交叉核对。

### 4.2 一个 jar，多份清单 —— `MultiLoaderArbiter`

通用 jar 为每个加载器各带一份清单和一个胶水类。如果不仲裁，三个生态都会初始化它。`MultiLoaderArbiter.ownerOf(jar)` 按偏好挑出一个 —— 默认是 **NeoForge、MinecraftForge、Fabric**（`-Dforbric.multiLoaderPreference=…`）。Forge 系排在前面，是因为它们的基线在合并基底上始终存在；NeoForge 排在 MinecraftForge 之前，是因为合并中大部分由 NeoForge 胜出。如果一个 jar 带着某个加载器残留的清单、却没有对应的实现，就不会交给那个加载器。jar 仍然留在 classpath 上；这里只决定它的身份。

### 4.3 两个 jar，同一个 mod id —— `DuplicateModArbiter`

把一个 Fabric 整合包和一个 NeoForge 整合包合在一起，同一个 id 下就会出现两个*文件*。落败方必须**移出** classpath（否则按“第一个 URL 胜出”的规则，它可能遮蔽胜出方，还会带进自己的 mixin 配置）。

- **清点** —— `NestedCandidateInventory` 遍历每一个根 jar 和内嵌 jar（深度 ≤ 8，有防 zip 炸弹的字节上限，不限归档数量），构建出一张由候选和父→子边组成的图；内嵌 jar 解压到 `.forbric-kernel/candidates/` 下，以 SHA-256 为键。
- **选择** —— `ReachableCandidateSelector` 为整个实例构建一个布尔模型（内嵌候选只能经由被选中的父 jar 存在），并用 SAT4J 求解；`JointCandidateSelector` 提供子句：硬依赖/软依赖、`breaks`/`incompatible` 互斥，以及来自 `CandidateContractScanner` 的符号契约（只采用强到足以约束选择的证据 —— 例如某个 mixin 必需的成员）。搜索按工作量设限，从不按时钟设限（`CONFLICT_BUDGET`、`VARIABLE_LIMIT`、`-Dforbric.arbitrationMaxNodes`，默认 100 000），所以同一个文件夹在任何机器上都会选出同样的 jar。
- **偏好** —— 顶层重复：`-Dforbric.dupeIdPreference`，未设置时回退到 `multiLoaderPreference`。内嵌重复：`-Dforbric.nestedDupePreference`，默认 NeoForge、Fabric、MinecraftForge —— 之所以这样排，是因为多加载器库针对各加载器的构建会把该加载器缺少的阶段换成存根，而这个顺序能让调用空方法的调用方最少（javadoc 里记录了定下这个顺序的两个案例）。
- **覆盖设置** —— `-Dforbric.modOwner=sodium=fabric,…` 或 `<rundir>/forbric-mods.txt`（每行一条 `<mod id> = <loader>`；实例第一次出现重复时，内核会写出一份带注释的模板）。命令行优先于文件。
- **关掉的 jar** —— `<rundir>/forbric-disabled.txt` 列出 `mods/` 里的 jar 文件名（注释和写错的行与 `forbric-mods.txt` 的处理方式相同）。`DisabledMods` 让这些 jar 不进入扫描，所以它们不会成为声明；它们进入 `Decision.suppressedJars`，但绝不进入 `rescueJars`；`-Dforbric.crossJarArbitration=off` 时仍会缓存一份只含这些 jar 的决定。`load-report.txt` 会列出它们。
- **残留处理** —— 落败的生态会得到一个仅在场的别名，让 `isLoaded(id)` 仍能作答（`Decision.aliases`）；对于已加载的 mod，它在另一个生态的构建可以作为最后手段借出缺失的类（`rescueJars`）；`ArbitratedAwayClasses` 统计落败构建里有、胜出方却缺少的内容；`ArbitratedAwayDispatchers` 补上落败构建带走的那一种行为：只有落败的 Fabric 构建会分发的自定义入口 key（`EntrypointDispatchScan` 从它的字节码读出 key、入口类型、调用的方法和到达分发点的生命周期阶段），在胜出方自己不提到这个 key 时由内核代为分发。从 `main`、`client`、`server` 到达的 key 在该阶段的入口之间分发，位置就是落败构建自己那个入口本来所在的位置：按 Fabric Loader 的顺序，即库的 id 排在哪里（`ArbitratedAwayDispatchers.place`）。落败构建持有某个已声明的 key、而它有一处查询的 key 或类型不是常量时，不去猜，记一条 SUSPECTED finding。`ArbitratedAwayEntrypoints` 处理反方向：落败的 Fabric 构建以别的 mod 会分发的 key 提供给*其他* mod 的入口点（最常见的是 `modmenu`），挂到这个库的在场别名上，用库自己的 id 声明，和原生一样。只限 Fabric Loader 自己不驱动的 key（绝不是 `main`、`client`、`server`、`preLaunch`：胜出方已经用自己的方式初始化了这个库），不是胜出方已经声明的 key，不是库自己的协议，也不是落败构建所依赖的某个框架的 key；而且只限胜出方能满足的入口点——它的类能解析，它在胜出方所带的类上用到的每个成员都以同样的描述符存在，它读取的“只有落败构建才有”的类的字段不能只由永远不运行的代码写入。它能运行什么，从入口点自己的方法出发逐个方法地读，绝不经过落败构建的生命周期入口方法（那些只有 Fabric Loader 会调用）（`-Dforbric.arbitratedAwayEntrypoints=off`）。`MergeReport` 写出 `.forbric-kernel/merge-report.txt`，逐条解释每项决定。
- `-Dforbric.crossJarArbitration=off` 会完全关闭这一机制。

### 4.4 回答“我在哪个加载器上？”和“X 装了吗？”

- `LoaderProbePolicy` + `transform.LoaderProbeRewriter`（COREMOD 阶段）改写单加载器第三方类里的 `Class.forName` 调用点，让平台探测（`FMLLoader`、`FabricLoader`）按该 jar 被仲裁到的生态作答（`-Dforbric.loaderProbes=off`）。
- `net.forbric.api.ModPresence` 给出跨生态的答案。`transform.ForeignModPresenceInjector` 把它以逻辑或并入两个 Forge 系的 `ModList.isLoaded`；Fabric 侧把每个 Forge 系 mod 注册成仅在场的容器（只有身份 —— 没有入口点、mixin 或资源），让 `FabricLoader.isModLoaded` 能作答；Forge 系的列表也预置了 Fabric mod（`-Dforbric.crossEcosystemPresence=off` 恢复单加载器的答案）。`net.forbric.api.ModIds` 负责映射各生态拼写不同的 id（`cloth-config` / `cloth_config`）。
- 一个 mod 自己的 id 优先于别的 mod 用 `provides` 声明的同名别名，不管谁先到：`KernelFabricLoader.register`、`ModConstructionOrder` 和 `ModPresence` 的索引都先收齐所有真实 id，再收别名，和 `DuplicateModArbiter` 从不让别名占用 id 的规则一致。owo-sentinel 嵌在需要 owo-lib 的 mod 里，它 provides `owo`，而且不依赖任何已安装的 mod，所以先注册；结果 owo-lib 本身被当成"重复 id"丢掉，连同它的别名 `owo-impl`。现在装了 owo-lib 时 sentinel 本身也不加载，和 Fabric Loader 的解析器一样（每个 id 只加载一个 mod，provides 也算，见上文）；两者都保留的情况下（`mods/` 里的两个 jar，或被留下的内嵌 mod），仍由真实 id 优先的顺序决定。
- `ModConstructionOrder` 按拓扑顺序构造 Forge 系 mod —— 一个 mod 排在它依赖的、以及它声明 `AFTER` 的所有 mod 之后；并列时按字母序；遇到环会点名报出，不会悄悄拆开（`-Dforbric.modOrder=name` 恢复按文件名排序）。Fabric mod 按 Fabric Loader 自己的顺序初始化，即按 mod id 排序（`FabricLoadOrder`；`-Dforbric.fabricOrder=off` 让它们回到拓扑顺序）。

## 5. 由内核驱动的三个生态

### 5.1 Fabric —— 不运行任何 Fabric Loader 代码

- `fabric.KernelFabricLoader` *就是* `FabricLoader` 单例：它是内核状态的一个视图，入口点索引在 mod 代码运行之前就已冻结。`KernelModContainer` 通过 zip `FileSystem` 暴露每个 jar；`KernelLanguageAdapters` 支持 `languageAdapters`（fabric-language-kotlin）；`KernelObjectShare`、`KernelVersion`、`KernelMappingResolver`（恒等映射）补齐了整套 API。
- 语言适配器在 Mixin 之前全部构造，不管有没有入口点用到它（§3.2 第 13 步），因为 Fabric Loader 的 `freeze()` 就是这么做的：Configured Defaults 在适配器构造函数里复制整合包的默认配置，而且没有任何入口点用到这个适配器。构造时抛异常的适配器会让它所属的 mod 失败，并且不会再构造第二次。`-Dforbric.eagerLanguageAdapters=off` 改回第一次用到时才构造。
- 入口点的值按 Fabric 的 `DefaultLanguageAdapter` 解析（`KernelLanguageAdapters.resolveDefault`，没有适配器的入口点和 `LanguageAdapter.getDefault()` 共用这一份实现）：一个类、一个静态字段、同名的唯一方法（静态方法，或者绑定到一个新实例上的实例方法），以及 `::<init>`（构造函数）。Fabric 拒绝的写法（同名方法有多个、成员是私有的），内核保持宽松：打开访问权限，或者取第一个静态方法。
- 面向 mod 的 API 以原名内置：`src/main/java/net/fabricmc/api`（7 个文件）和 `src/main/java/net/fabricmc/loader`（40 个文件：`loader.api.*`，包括 `EntrypointException`，并在 Fabric Loader 抛它的地方抛出；加上 mod 实际会链接到的那一小部分内部类——`FabricLoaderImpl`、`ModContainerImpl`、`EntrypointStorage`、`Hooks`、`FabricLauncher`/`FabricLauncherBase` 及其旧版视图 `net.fabricmc.loader.launch.common`、`DefaultLanguageAdapter`、`StringUtil`、旧版的 `net.fabricmc.loader.FabricLoader`，以及 mod 在运行时把自己解出的 jar 注册成 Fabric mod 所用的元数据接口：`ModMetadataParser`、`LoaderModMetadata`、`EntrypointMetadata`、`ModCandidateImpl`）。这样的 mod 在启动之后才添加的 mixin 配置，同样按 §7.2 的第三方规则处理，归声明它的那个 jar 所属的 mod。`FabricLoaderInternals` 按确切名称把这些内部类固定交给父加载器；`-Dforbric.fabricImpl=off` 则不提供它们。报告的 API 级别是 `KernelFabricEcosystem.FABRIC_LOADER_API_LEVEL = "0.19.3"`。`FabricLoaderApiSurfaceTest` 拿 Fabric Loader 0.19.3 自己的 jar（本机启动器里的那份）对照 `net.fabricmc.api` 和 `net.fabricmc.loader.api`：每个公开类、它的父类型，以及每个 public 或 protected 成员的描述符。它接受的差异列在测试里（区间运算那一组，本机 mod 里没有任何一个调用）；出现新的差异就红。和 Fabric 一样，`getEntrypoints` 先把所有入口都构建一遍，结果为 null 的不放进列表，再抛出一个点名第一个失败 mod 的 `EntrypointException`；容器的 `getEntrypoint()` 为它自己的入口抛一个，但构建本身冒出来的 Error（比如类的静态初始化失败）原样抛出。和 Fabric 不同的是，参与的只是能成为所要类型的入口：类型对不上的入口会被跳过，而不是让整个 key 失败。`getGameInstance()` 在客户端返回 `Minecraft` 实例（由 `Hooks.startClient` 在入口点之前交过去），在服务端从专用服务器构造时起返回它，和 Fabric Loader 对服务端入口的补丁一致。
- 内置 mod 是 `minecraft`、`java` 和 `fabricloader`。Fabric Loader 会把它自己 jar 里嵌套的 jar 当作 `fabricloader` 的嵌套 mod 读进来（0.19.5 嵌的是 MixinExtras），内核同样把自己捆绑的、带 `fabric.mod.json` 的 game-side jar 注册成 `fabricloader` 的嵌套 mod（`KernelFabricEcosystem.registerLoaderNested`）：目前就是 `mixinextras`，版本是内核的 0.5.4，Fabric Loader 0.19.5 里是 0.5.5。同 id 或同 alias 的 guest 副本不注册，和 Fabric Loader 一样；`-Dforbric.kernelBundledFirst=off` 时改由 guest 副本回答。`util.PlatformIds` 是唯一一张“依赖永远视为满足”的 id 表：这三个、内核捆绑的 `mixinextras`，以及载体的 `forge`、`neoforge`、`fml`——绝不包括只有某个已安装 mod 才提供的 id（旧版 fabric-api 提供过的 `fabric`，和其他 mod 的 id 一样对待）。
- fabric-api 和其他 mod 一样是一个 mod。内核只通过它的公开 API 接触它——`PayloadTypeRegistry` 的静态访问器（`FabricPayloadRegistries`，按类型读取：协议、方向和 `TypeAndCodec` 映射）、`PacketContext.get()`、`Renderer.get()`/`Renderer.register`、`RegistryEntryAddedCallback.event`、`ResourceCondition.CONDITION_CODEC`、内核自己实现的 `FuelValueEvents.Context`（`KernelFuelValueContext`）——或者看它的代码做了什么（交易引擎、某个注册方法背后的映射：`PublicApiStorage`），从不按它实现里的类名、字段名或 mixin 名。
- 入口点：`preLaunch` 在 Mixin 之后（§3.2 第 19 步）；`main` 在注册窗口内（服务端）或在 `Minecraft.<init>` 里（客户端，默认）；`client` 在 `Minecraft.<init>` 里；`server` 在专用服务器上。每个 mod 的入口点都隔离调用——抛出异常的 mod 会记录下来并跳过——调用时该 mod 对应的 NeoForge `ModContainer` 处于激活状态（`KernelForeignShimContext`），因为 Fabric mod 可能持有某个多加载器库的 NeoForge 构建。
- Fabric 的访问加宽器 / class tweaker 在 `ACCESS` 阶段运行（`access.ClassTweakerTransformer`）；`@Environment` 剥离在 `ENV_STRIP` 阶段（`EnvironmentStripTransformer`，只作用于仲裁归 Fabric 的 jar 里的类）。

### 5.2 NeoForge

- **身份** —— `PassiveSeeder` 创建当前的 `FMLLoader`、`FMLPaths`，以及根据本次启动选中的 jar 构建的 `LoadingModList` 和 `ModList`——不做发现，没有模块层，不排序。
- **构造** —— `KernelModLoader`：一个由 `BusBuilder` 构建的 `IEventBus`，加上一个内核的 `ModContainer`（`net.forbric.kernel.runtime.KernelModContainer`）；构造函数参数按类型填充（`IEventBus`、`Dist`、`ModContainer`）。
- **总线** —— 内核自己发布的 mod 总线事件都走 `KernelLifecycle.postModBusEvent`；初始化阶段走 `runtime.KernelNeoSetup`；延迟任务在 NeoForge 原本运行它的那个线程上运行（`NeoDeferredWork`）。
- **枚举扩展** —— `NeoEnumExtensions` 把每个 jar 的 `META-INF/enumextensions.json` 交给 NeoForge 自己的 `RuntimeEnumExtender`；`NeoEnumExtensionInjector` 在 COREMOD 阶段接近末尾的位置注册（这个位置很关键：它会在那一刻定义 FML 类），而且只在有 mod 声明了扩展时才注册。
- **Coremod** —— NeoForge 的 coremod jar 从不加载；`transform.NativeCoremodParity` 代为执行它的改写（花盆 `potted`、生物群系气候/效果、结构设置、`finalizeSpawn`），时机在 Mixin *之后*，和 NeoForge 运行它们的时机一致。MinecraftForge 自己的“字段改走 getter”转换器也在同一个位置运行（§5.3）。

### 5.3 MinecraftForge

- **身份** —— `PassiveSeeder.seedForgeFmlLoader`（Mixin 前，§3.2）；`ForgeLoadingListHolderInjector` 让 `LoadingModListImpl$1LazyInit` 读取内核发布的列表（`net.forbric.api.ForgeLoadingList`），而不是一个只有真正的加载器才会填充的字段；`ForgeLauncherInfoInjector` 代为应答 `FMLLoader` 里三个依赖 ModLauncher 的方法；`ForgeBindingsLookupInjector` 在没有 FML 模块层的情况下解析 `Bindings`；`ForgeSecureJarStandIn` 在没有 ModLauncher 的情况下给预置的 `ModFile` 提供 `SecureJar`。
- **构造** —— `KernelForgeModContext` 生成每个 Forge mod 构造函数都要接收的三件套：EventBus 7 的 `BusGroup` + `FMLModContainer` + `FMLJavaModLoadingContext`。在早期窗口里就去碰 `Minecraft` 的 mod 会推迟到 `onClientEntrypoints` 再构造，MinecraftForge 原本就在那里构造自己的 mod。
- **加载状态** —— `KernelLifecycle.setForgeLoadingState` 翻转 `loadingStateValid`；`publishForgeGatherStates` 把 `VALIDATE … LOAD_REGISTRIES` 记为已完成，这样 `ModLoader.hasCompletedState` 给出的才是实情（`-Dforbric.forgeLoadingStates=off`）。
- **Capability** —— 合并把 `Entity`/`BlockEntity`/`Level` 放到了 NeoForge 的附件继承体系之下，所以 `transform.ForgeCapabilityCompositionTransformer` 把 MinecraftForge 的 `CapabilityProvider` 组合进这些根类型，`ForgeCapabilityTokenInjector` 驱动 Forge 的 `CapabilityTokenSubclass` 插件（`-Dforbric.forgeCapabilities=off`）。这个开关只关掉分发：合并基底在 `required-ancestor-compositions.tsv` 里列了这三个根类型，没有组合的证明加载器就拒绝定义它们，所以关掉时仍然会组合，只是换成一个惰性的 provider——不发 `AttachCapabilitiesEvent`，任何查询都返回空。关掉时也不注册 Forge 方块 transfer 回退，transfer 桥会收到这个决定，不再去找它。`CapabilityUseAudit` 列出使用 MinecraftForge capability 的 jar；如果分发被关掉，或者组合没有落到每一个根类型上，就把这些 jar 标为 DEGRADED。
- **枚举扩展** —— `ForgeEnumExtensionInjector` 驱动 MinecraftForge 自己的处理器（无条件注册；除非 Forge 的 mod 列表里超过两个 mod，否则它什么都不处理）。
- **字段 getter** —— MinecraftForge 自己的 `FieldToMethodTransformer`（在 `forge-transformers` 里，是一个 ModLauncher 转换服务，内核和 NeoForge 的 coremod jar 一样从不加载它）把对 `LiquidBlock.fluid` 和 `BucketItem.content` 的读取改成调用 `getFluid()`。合并基底保留了 MinecraftForge 的 `(Supplier, …)` 构造器，它们往字段里存 null、把流体放在一个 supplier 里，因为流体注册得比引用它的方块或物品晚；没有这个改写，每个 MinecraftForge 液体方块一放下就抛异常（`onPlace` 读这个字段），每个 MinecraftForge 桶都倒不出东西。`NativeCoremodParity` 把 MinecraftForge 的规则和 NeoForge 的放在一起，按系区分：MinecraftForge 只让 getter 自己保留对字段的直接读取（它的“同描述符”检查永远不成立），NeoForge 则是每个和 getter 形状相同的方法。有两个条件是这个基底自己的：只有 getter 对每个构造器造出的实例都能给出答案时才改写（`answersEveryConstructor`），这样原版的水和岩浆不会因为 supplier 没设置而丢掉流体；而且合并后的字段是 public 的（NeoForge 这样声明），所以别的类（NeoForge 自己的流体代码、NeoForge mod）对它的读取也改写，判断依据是字段所属类在基底里的原始字节码（`readGameClassesFrom`，由 `KernelMixinBootstrap` 在织入任何类之前把游戏类交给它，有没有 mixin 配置都一样）。它列表里的花盆和生物群系规则 NeoForge 也有，本来就在运行；列表里剩下的那个字段 `MobEffectInstance.effect` 不动：那个 getter 只是返回这个字段，原生 NeoForge 也不改写它。`-Dforbric.forgeFieldGetters=off` 关闭；`ForgeFieldGettersExecutionTest` 实际运行改写后的类。
- **流体类型** —— MinecraftForge mod 的流体只重写 MinecraftForge 的 `getFluidType()`。合并后的实体代码是 NeoForge 的，问的是 NeoForge 的类型，而这种流体从不提供，所以 `KernelFluidTypes` 以前按流体的标签回答：没有标签的就是 NeoForge 的空类型，也就是空气，于是眼睛在一种 `canDrown(true)` 的流体里的身体照样能呼吸。现在，对类里有自己 MinecraftForge 类型的流体（它解析到的 `getFluidType()` 声明在 `Fluid` 之下），`KernelForgeFluidTypeViews` 先回答：MinecraftForge 的空、水、岩浆类型就是 NeoForge 自己的那几个（NeoForge 的实体代码按身份认它们），其他类型各得到一个 NeoForge `FluidType` 视图，NeoForge 的实体和世界代码问类型的每个问题（溺水、游泳、推动、灭火、湿润、船、寻路类型、声音、`move`）都转去问那个 MinecraftForge 类型，普通数值（亮度、密度、温度、粘度、稀有度、名字）照抄。这个视图不向 NeoForge 注册。MinecraftForge 的流体类型注册完成之前，这种流体按标签回答，不缓存。`-Dforbric.forgeFluidOwnType=off` 回到按标签回答。
- **配置** —— `KernelForgeConfigLoad` 逐个打开真正的 MinecraftForge 配置，保留 Forge 的读取器、事件、保存和文件监视器；退出时由 `interop.ClientShutdown` 停掉这些监视器（`ExitHookInjector`：客户端上是 `Minecraft.close`，服务端上是 `DedicatedServer.onServerExit`）。监视器 1.5 秒内没确认停止不算失败；退出守护会继续扫，到期时还活着的非守护线程会被点名。

### 5.4 事件之外的跨生态服务

- **网络** —— 在 Fabric API 和 NeoForge 共用同一个原版频道 id 的地方，`interop.PayloadInterop` 按运行时的负载类选择自定义负载编解码器；`CommonNetworkInteropInjector` 仲裁两边都要占用的 `c:version` / `c:register` 频道（`-Dforbric.commonNetworkInterop=off`）；`RegistrySyncParityInjector` + `KernelForgeWrapperSync` 借助 Forge 自己的 `GameData.injectSnapshot`，把 NeoForge 的注册表同步（装了 fabric-api 时还有 fabric-api 的）应用到 MinecraftForge 包装的注册表上；`KernelRegistryRevert` 在断开连接时恢复连接之前的 id；`NetworkChannelCensus` 比对已注册的频道和已声明的频道。合并后的 `ServerGamePacketListenerImpl.handleCustomPayload` 是 MinecraftForge 的重写：它问一下 `ForgeHooks.onCustomPayload`，把答案丢掉，永远走不到 NeoForge，所以每个 NeoForge mod 在游玩阶段发给服务端的包都没人收（Carry On 的"搬运键按下"包就是其中之一，所以它什么都搬不起来）。`CommonNetworkInteropInjector` 把丢掉的答案改成分支：MinecraftForge 没收、而 NeoForge 注册过的负载交给 `NetworkRegistry.handleModdedPayload`。直接调它而不是调 `super`，因为 fabric-api 注入在 super 里的处理器只服务配置阶段的监听器，对游玩阶段的会抛 `Unknown addon`（`-Dforbric.playPayloadFallThrough=off` 关闭）。`KernelClientSmoke` 的 `-Dforbric.clientSmokeCarry=<tick>` 演练用游戏自己的键盘和鼠标输入把整条链跑一遍。编辑拼接后的通用监听器保留了 MinecraftForge 的 `ForgeHooks.onCustomPayload` 调用的地方，只把 `ForgePayload` 交给它（`PayloadInterop.offerToForge`），和 MinecraftForge 上一样——它的编解码器会把自己拥有的每个频道都解码成这一种负载；内核自己的分发前置代码只在这个被保留的调用位于方法开头、并按它的答案返回时才退让。Fabric 的负载接收方按它实现的公开 `PacketSender` 找出来，交给它的共享负载按 Fabric 自己的编解码器读出（`WirePayloads`）。每条频道声明只交给 fabric-api 的 addon 一次，只列出本阶段已经声明过的频道的 `minecraft:register` 不再重复发送（`-Dforbric.channelDeclarationsOnce=off`）；镜像频道的版本标明它的来源和阶段，而不是方向，所以客户端和服务端对它的看法一致。
- **物品/流体/能量传输** —— `KernelTransferInterop` + `runtime/transfer/` 桥接 Fabric 的传输 API、NeoForge 的 `ResourceHandler` 和 MinecraftForge capability，装了 Team Reborn Energy 时也桥接它（`-Dforbric.transferBridge=off`、`-Dforbric.hopperFabricStorage=off`）。只在相关 API 存在时启用，是否存在通过查找资源来判断。传输检出项（桥不会以事务方式驱动的提供者）归给哪个 mod，看的是替这个提供者应答的那个类在谁的 jar 里：提供者自己的类，动态代理则是它的 invocation handler。这个类以 `Class` 的形式传过来（`TransferIssues.Issue.source`），因为 lambda 按名字找不到，而代理类根本不是从 jar 里定义的。jar 由 `util.CodeRoots` 读出（代理、没有保护域的类、JDK 类返回 null；隐藏类返回定义它的那个类的 jar）；不来自任何 mod jar 的类归给 `forbric`，这是一个答案，不算失败。

## 6. 转换流水线

`transform.TransformPhase` 规定了顺序：`RAW_PATCH`、`DEOBF_REMAP`、`ENV_STRIP`、`ACCESS`、`COREMOD`、`FABRIC_BUILTIN`、`MIXIN`。`TransformChain` 运行链上的阶段（`RAW_PATCH` … `FABRIC_BUILTIN`）；同一阶段内，先按 `predepends` 做拓扑排序，再按 `sortIndex`，最后按注册顺序。`MIXIN` 是终结阶段，不能注册进链里。在 26.2 上，`RAW_PATCH` 和 `DEOBF_REMAP` 里什么都没注册。

`KernelBoot` 注册了什么（131 个调用点，部分有条件）：

| 阶段 | 注册内容 |
| --- | --- |
| `ENV_STRIP` | `EnvironmentStripTransformer` |
| `ACCESS` | `ClassTweakerTransformer`（Fabric），以及由每个 mod jar **和两个载体**的 `META-INF/accesstransformer*.cfg` 构建的 `access.AccessTransformer`——载体的 AT 之所以要紧，是因为合并在哪里保留了另一个 Forge 系的方法体，就在哪里连带保留了那个方法体的访问修饰符（`MenuScreens.register` 合并后成了 private） |
| `COREMOD` | 126 项注册：加载器探测改写器、Mixin 织入器插槽（`FmlContextLoaderRewriter`、`ModuleClassLoaderInitInjector`）、`GuestMixinPluginGuard`、生命周期重定向、`ForbricMergedBaseCompatTransformer`、capability 组合，以及 `transform/` 里 103 个 `*Injector` 类中的大部分，每个类要么修复一处合并弄坏的具名接缝，要么接上一座桥（§8） |
| `FABRIC_BUILTIN` | `RestoredAccessTransformer`（给 COREMOD 恢复出来的成员重新应用访问修饰符）、`MergedBaseFrameRecomputer`（第三方类的栈映射帧引用了合并删掉的父类时，重新计算这些帧） |

Mixin 后阶段（`KernelMixinBootstrap`）是固定的组合：

```
Mixin (via MixinWeaverSlot) → NativeCoremodParity → PostMixinFixups → UncalledValueRelay
                           → InterfaceDefaultConflictRepair → ForgeTransferShapeAudit.certify
```

按类别列出值得一提的修复（每个类是因为哪个案例写出来的，请看它的 javadoc）：

- **合并不变量** —— `ForbricMergedBaseCompatTransformer`（lambda 引导句柄与 static 属性的冲突、MinecraftForge 的 `getFluidType()` 桥、按键映射的 `MAP` 初始化器、把原版的 `KeyMapping.MAP` 作为“按键 → 映射”的视图加回去（`KernelKeyMappingMap`；两个生态都把它改了类型，而 LiquidBounce 在打开界面时的每次按键都会读它）、在 NeoForge 重新编译时把参数为 byte 的调用绑到了它扩展接口的 `writeByte(byte)`（这个方法只是转发到原版的 `writeByte(int)`）的地方，改回调用原版的 `FriendlyByteBuf.writeByte(int)`（10 个网络 `write` 方法里共 14 处；ViaFabricPlus 的能力标志重定向锚定的是原版的调用；`-Dforbric.vanillaWriteByte=off` 关闭），以及把基底里写死的、指向 `net/forbric/loader/impl/…` 的调用重定向到 `net.forbric.kernel.interop`）、`DuplicateLambdaPruneInjector`（只按名字匹配的 mixin 选择器会绑上去的孤立 lambda）、`WidenedFieldTwinInjector`（改过类型的字段的原版描述符孪生字段）、`MethodBodyNeuter`。
- **包与数据** —— `DataPackHookInjector`、`ClientPackHookInjector`、`PackMetadataFailSoftInjector`、`PackOverlayMutabilityInjector`、`NullPackGuardInjector`、`PackScreenHiddenFilterInjector`、`RegistryDirectoryOwnerInjector`、`RegistryAliasParityInjector`（§9）。
- **UI** —— `ModsButtonRedirector`（两个 Forge 系的暂停菜单 lambda 都打开 `KernelModListScreen`；合并在某个菜单里给每个 Forge 系各留了一个 mods 按钮时，只保留第一个）、`HudElementBridgeInjector`、`ForgeHudPipelineInjector`（合并后的 `Hud` 同时保留了 MinecraftForge 的覆盖层树时，HUD 仍只走一条流水线，即 NeoForge 的图层管理器：那棵树自己的构建和它那段会提前返回的绘制被去掉）、`CreativePagerBridgeInjector`（页面状态始终是载体的；没有安装任何 Forge 系 mod 时，第三方翻页器画它自己的翻页控件，和原生 Fabric 一样，并通过 Fabric 接口翻载体的页）、`CreativeTabOrderInjector`（Fabric mod 的标签页在 NeoForge 唯一的标签页顺序里按 Fabric 翻页器的规则分页，其他标签页留在 NeoForge 放的位置；`EcosystemTabPlacement`）、`EarlyKeyMappingRegistrationInjector`、`CreativeSearchTreesInjector`。最后这个修的是生产者和消费者被合并劈到两边：合并后的 `SessionSearchTrees` 保留了 MinecraftForge 的 `getSearchTree(Key)`，它从一个没人写的私有 map 里取 future 再 join，取不到时的默认 future 永远不会完成；而创造模式物品栏界面是 NeoForge 的，只读 `CreativeModeTabSearchRegistry`。所以一个像 MinecraftForge 自己的创造界面那样通过它读搜索树的 MinecraftForge mod 会永远卡住。更早的合并还给原版的 `updateCreativeTooltips(Provider, List)` / `updateCreativeTags(List)` 保留了 MinecraftForge 的方法体，一个自己重建标签页、再用原版方法刷新搜索的 mod（TCDCommons，每次进世界都这么做）会让整局的创造模式搜索都搜不到东西；现在的基底这两个是 NeoForge 的委托方法体。仍在读 MinecraftForge 存储的方法体改为调用 `KernelCreativeSearch`，走 NeoForge 的带 key 方法（生产者对每个带搜索栏的标签页都刷新）；在现在的基底上只改 `getSearchTree`。`-Dforbric.creativeSearchTrees=off` 关闭。gate-m55 用一个 MinecraftForge canary 去调 `getSearchTree`。`-Dforbric.clientSmokeCreativeSearch=<tick>[,query…]` 会在真实的创造模式界面里打字搜索并把结果网格写进日志。
- **把原生形状还回去** —— 让合并后的方法体恢复平台自己方法体里的某种形状，使照着它写的第三方注入器像原生一样落点和运行（§7.4）：`VanillaEarlyReturns`（载体把一个守卫并进汇合点、或把方法体拆开的地方，恢复原版编译出的提前 `return`；拆开后的最后一块拿回它所属方法的那些 return）、`LocalRanges`（拼接把对某个局部变量的读取放到它的作用域前面时，让它的局部变量表范围重新覆盖它自己的用处）、`TailLocals`（拆开的方法的尾部重新持有原版尾部持有的基本类型局部变量）、`LambdaInvocationThunkInjector`（把本类的虚方法引用物化成一次确切的调用，供第三方作为目标）、`ScopedClearRelayInjector`（载体把原生的 `field.clear()` 换成删除算出来的一部分 key 时，把它放回去；`ScopedClearView`），以及 §8.1 的投递记账（`NativeDualEventInjector`），它从不挪动任何一条游戏指令。
- **客户端渲染与装备** —— `ForgeModelDataFlowTransformer` + `KernelForgeModelData`（MinecraftForge 的方块模型数据能在合并后的客户端里流动，尽管客户端问的是 NeoForge 的模型问题；每当方块被曲面细分时，都会进入它的细分入口的边）、`GlideFreshEquipmentInjector`（载体缓存的滑翔属性还没跟上装备时，`canGlide` 按装备回答，和原生方法一样）。
- **配置** —— `StaleWatchEventInjector`（配置文件监视的处理器只对监视注册之后发生的修改运行，所以配置追踪器在已被监视的目录里新建配置时不再触发重载事件；`StaleWatchGuard`）。
- **空气** —— `VanillaAirRefillInjector` + `KernelAirRefill`（所有者选了原版的规则）。合并后的 `baseTick` 是 NeoForge 的，它把空气值交给 `CommonHooks.onLivingBreathe`。眼睛在一种不是空气的流体里、而身体在里面不会溺水时——水下呼吸或潮涌能量、溺尸、无敌的玩家、岩浆、mod 的可呼吸流体——这个钩子交给呼吸事件的回气量是 0，所以空气值原地不动。原版除了它的“水里”分支，到处都每 tick 回 4 点直到上限；在“水里”分支里，仅靠鹦鹉螺之息不溺水的身体保持空气值不动。这个分支指的是眼睛在一种会让这个身体溺水的流体里（fabric-api 正是这样让 Fabric 的溺水流体回答原版的“是不是在水里”），内核去问眼睛所在的各个流体类型，每个类型对这个身体回答 `FluidType.canDrownIn`——水的类型说会，岩浆的类型说不会，mod 的类型自己回答——从不按水的标签来问；所以岩浆和身体能在里面呼吸的流体照样回气，和原版一样。MinecraftForge 自己的钩子回气方式和原版一样，但对这种身体，在任何不是空气的流体里都保持空气值不动（`canRefillAir = isAir || 效果允许回气`）。钩子里存这个 0 的那一处（按形状找：钩子的签名、它唯一一次构造的 `LivingBreatheEvent(entity, canBreathe, consume, refill)`、紧跟在“能呼吸”局部变量存入 true 之后、往回气量局部变量里存的那个 0）改成原版会给的回气量。原版还只在空气值低于上限时才改它，而钩子对能呼吸的身体做的更新 `setAirSupply(min(air + refill, max))` 每 tick 都把高于上限的空气值拉回上限（原生 NeoForge 也是这样，MinecraftForge 在它回气的地方也是）；这里的 `Math.min` 改成 `KernelAirRefill.refilledAir`，它拿到事件开始时的回气量和结束时的回气量：除非有监听器设置了回气量，否则空气值在上限或以上时原地不动（监听器设成的恰好是事件开始时的那个值时无法分辨，按原版更新）。其他都不变：钩子照样只发一次 `LivingBreatheEvent`，NeoForge 的监听器照样设置数量、决定身体能不能呼吸，和在 NeoForge 上一样。MinecraftForge 自己的 `LivingBreatheEvent` 是在这唯一一次发布之中，由内核挂在 `LOWEST` 的 NeoForge 监听器发出的（§8），所以 MinecraftForge 监听器改了什么，在 `refilledAir` 比较回气量之前就已经写到 NeoForge 事件上：它带回的回气量算作监听器设置的，什么都没改的 MinecraftForge 监听器什么都不写。MinecraftForge mod 的流体通过它自己的类型回答 `canDrownIn`（§5.3）。跨宿主谓词岛的受保护判定（像水一样会溺水的 Fabric 流体，§7.4.1）也从同一个回气量开始发事件（`KernelAirRefill.waterRefill`），`VanillaAirRefillInjectorExecutionTest` 在装饰过的钩子里实际执行了岛的前缀。回气量问“会不会溺水”时问的是身体的流体交互，而不是 `isEyeInFluid`：载体自己问原版的那个调用，原生判定岛会当成调用被挪走了，放弃挂在那里的注入器（Create 的潜水装备、fabric-api 的溺水流体）；`NativeDecisionIslandStagedTest` 把两边都钉住了。`-Dforbric.vanillaAirRefill=off`（原名 `forbric.foreignFluidBreathing`，以前只给 Fabric mod 的不溺水流体回气）保留 NeoForge 的规则。
- **插桩** —— `ClientSmokeTickInjector`（除非 `-Dforbric.clientSmoke=true`，否则不起作用）、`EventChainAuditInjector`（除非 `-Dforbric.eventChainAudit=<report>`，否则不起作用）、`ServerTickSamplerInjector`、`CompatibilityPromptTickInjector`、`ServerCompatibilityTickInjector`。
- **加固** —— `ChunkExecutorGuardInjector`（服务器停止后，拒收提交给其区块执行器的任务；`-Dforbric.chunkExecutorGuard=off`）。

承诺会落到某个类上的转换器要声明这个类（`AnchorSet`）；`AnchorLedger` 会报告经过转换器却没有被改动的类（记为一次 **Miss**，立即以 ERROR 级别记日志），并在普查标志点报告从未加载过的类。`RepairDriftCensus`（`run/compat/repair-drift.sh`）把这些声明拿到一个*候选*游戏构建上重放——升级载体时要问的正是这个问题。

## 7. 合并基底上的 Mixin

### 7.1 内核就是 Mixin 服务

`mixin.ForbricMixinService` 通过 `META-INF/services/org.spongepowered.asm.service.IMixinService` 注册（连同 `ForbricMixinServiceBootstrap` 和 `ForbricGlobalPropertyService`）；Mixin 库用的是 Fabric 的分支（`net.fabricmc:sponge-mixin`，版本见 `gradle.properties` 里的 `mixin_version`），MixinExtras 则是内核自带的 `mixinextras-fabric`（放在游戏侧，因为它生成的 `LocalRef` 类必须和游戏共用同一个加载器）。`KernelMixinBootstrap.init` 绑定服务、注册每个配置、安装 `KernelMixinErrorHandler`、把织入器装成流水线的最后一级，然后把环境推进到 `INIT` 和 `DEFAULT`。配置的准备（以及插件的构造）发生在 `init` 之后第一个经过转换器的类上，而不是 `init` 本身。

推进阶段之前，每个配置都按 Fabric Loader 的 `MixinConfigDecorator` 的方式打上标记：所属 mod 的 id（`KEY_MOD_ID`，让 Mixin 自己的消息能说出是哪个 mod），以及对 Fabric 拥有的配置，Fabric Loader 会给这个 mod 的 Mixin 兼容级别（`KEY_COMPATIBILITY`，`FabricMixinCompat`）。级别取自该 mod 对 `fabricloader` 的 `depends`/`breaks` 所接受的最低 Fabric Loader 版本，按 Fabric 自己的版本顺序比较（移植自 `SemanticVersionImpl.compareTo`，不用内核做依赖判断的比较器，那个对预发布版本的排序不同），再查 Fabric Loader 0.19.3 的 `FabricMixinVersions` 表（这是内核对外报告的版本，这张表的最高级别也正好是内置 Mixin 的最新级别）。它决定初始化器和注入器的应用顺序、局部变量怎么判别、平移后的注入点落在哪里。之前没有这个标记，所有 Fabric mod 都按最新级别运行。在本地语料（997 个不同的 `fabric.mod.json`）上，这份移植和 Fabric Loader 0.19.3 本身对每个 mod 的结果都一致；带 mixin 的 561 个 mod 里有 323 个得到比之前更旧的级别。

不设下限。Fabric 在两种情况下给 0.9.2：mod 能接受的最低 `fabricloader` 版本低于 0.12.0，或者往下不设限（没有 `fabricloader` 依赖、写 `*`、或只有上界）。那 561 个里有 58 个属于这类，大多数是没有声明 `fabricloader` 依赖或没有下界，少数下界低于 0.12.0，例如 WorldEdit 的 `>=0.4.0`。按 mod id 对语料的 jar 清单重新数了一遍（计数口径不同）：带 mixin 的有 620 个，其中 112 个是 0.9.2；这 112 个里，55 个没有依赖，52 个没有下界，5 个下界低于 0.12.0。0.9.2 用的是 Mixin 旧的局部变量查找（`Locals.getLocalsAt092`）。这套查找和 0.10.0 的有一处差别，会碰上内核搬过位置的捕获。两套查找在 label 或行号处读局部变量之前，都会先往前跳过伪指令：0.10.0 的遇到帧就停；0.9.2 的最多走三个节点，走到帧上就把帧读进去。`MixinGuiItemCaptureAdapter` 会把 TAIL 捕获搬到 `INVOKE <结束方法体的那个调用>, AFTER`。如果那个调用直接落进尾部所在的汇合点，AFTER 就是汇合点的 label，0.9.2 的查找会走到汇合点的帧上。没存过这个局部变量的那条路径也会到达汇合点，所以这个帧里没有它：Mixin 判 "LVT ... has incompatible changes"，跳过这个捕获。功能就没了：跳过并打 WARN；如果是 `CAPTURE_FAILEXCEPTION`，则织成一个每次执行到这里都抛 `InjectionError` 的方法。适配器会把自己搬过的捕获从 `CAPTURE_FAILEXCEPTION` 改成 `CAPTURE_FAILSOFT`（和 `MixinLocalsCapture` 对 `CAPTURE_FAILHARD` 的处理一样，受同一个 `forbric.localsFailSoft` 开关控制），所以搬过的捕获即使仍然失败，也只会被跳过，不会抛错。`MixinCaptureLanding` 是一个 Mixin 扩展，在 Mixin 即将把 mixin 应用到某个类时（`preApply`）处理这件事。它只管 Mixin 正在应用到这个类、且级别低于 0.10.0 的 mixin。这包括 mixin 经 `MixinMergedTwin` 追加的合并孪生类，但不包括被配置插件拒掉的 mixin。它从实际应用的 mixin 上读出被搬过的注入点，按 Mixin 的匹配方式找到这个点绑定的调用；如果调用后面的节点是伪指令，就在调用后面紧接着插一个 `NOP`。`NOP` 仍在这个局部变量的作用域内，又不是伪指令，所以查找不用往前走，读到的就是方法体留下的局部变量。任何注入点类型本身都不会选中 `NOP`。同一个调用上的其他 AFTER 现在落在 `NOP` 上而不是 label 上，在代码里是同一个位置。`Shift.BY` 会把每个节点都算进去，插 `NOP` 会让跨过这个调用的 BY 落点挪一位，所以只要有任何一个正在应用的 mixin 在这个方法里用了 BY，这个方法的方法体就保持原样，那里的捕获在 0.9.2 下仍会被跳过，并打 Mixin 的 WARN（0.10.0 下限还在时，这类 mod 会被抬到 0.10.0，捕获是成立的）。真实织入测试套在 0.9.2、0.10.0 和 0.17.0 下全部通过。GUI 物品那个 fixture 在 0.9.2、0.10.0 和 0.17.1 下都能捕获，只有 0.9.2 的织入里有 `NOP`。孪生类 fixture 在 0.9.2 下两份拷贝都能捕获；旁边放一个 `by = 2` 的注入器时，它仍在汇合点 label 之后、每条路径都执行。去掉 BY 检查实测过：插了 `NOP` 它会被挪到 label 之前。`-Dforbric.captureLanding=off` 时，0.9.2 的织入又会跳过这个捕获。在此之前，内核把这类 mod 抬到 0.10.0（`RELOCATION_FLOOR`）。去掉这个下限后，纯 Fabric 服务端 A/B（130 个 mod 各自单跑，再把 159 个 jar 放进一个整包跑 1200 tick）的结果仍和原生 Fabric 一致：两边都通过的 126 个，两边都失败的还是那 4 个，没有只在 Forbric 上失败的。走到 Mixin 的 127 次会话里包括 12 次带有曾被下限抬高的配置，每一次记下的 Mixin 警告和没挂上的注入器都和有下限时完全相同。客户端门禁（gate-m9，其中 13 个配置现在是 0.9.2）的结果和之前一样。本地语料里，得到 0.9.2 的 Fabric mod 没有一个带 TAIL 局部变量捕获，所以这个落点针对的是这种形状，而不是目前见过的某个 mod。在纯 Fabric 服务端整包（126 个 mod、159 个 jar）上，按 Fabric 自己的级别织入、而不是全按最新级别，只改变了 6 个类里字段初始化的顺序（比较的是导出的类，并用同级别的第二次运行作噪声对照），整包运行结果相同。Forge 系的配置保持原样：NeoForge 的 FML 会按声明的 `behaviorVersion` 给自己的配置打标记，低于 0.17.1 直接拒绝加载，所以它能给出的任何级别在内置 Mixin 下都和最新级别表现一样。`-Dforbric.mixinCompat=latest` 让所有 Fabric 配置保持最新级别。

顺序：Fabric 配置按 Fabric Loader 的顺序，Forge 系配置追加在后面。Mixin 按优先级排序，注册顺序只用来决定同优先级的先后，所以验证最少的那一组会成为最外层，包在已知可靠的栈外面。`MixinWeaverSlot` 让第三方 mod 能按 NeoForge 允许的方式替换织入器（LibJF 包装了 `FMLMixinClassProcessor.transformer`）。

### 7.2 Mixin 读取之前，第三方配置会被怎样处理

`ForbricMixinService` 会改写每个配置的 JSON：

1. **整份配置的拦截** —— `MixinConfigPolicy.isDisabled`：`MergedBaseMixinCompat` 里的内置列表（`-Dforbric.mergedBaseCompat=off` 去掉这份列表；`-Dforbric.enableMixinConfigs` 把某个配置强制加回来），再加上 `-Dforbric.disableMixinConfigs`（逗号分隔，支持末尾的 `*` 通配）。
2. **放宽** —— 名字不以 `forbric` 开头的配置都是第三方配置，一律放宽：`injectors.defaultRequire → 0`、`overwrites.requireAnnotations → false`、`required → false`。单个注入器上的 `require`/`expect` 仍然优先。`-Dforbric.relaxGuestMixins=off` 恢复严格行为；放宽关闭时，`-Dforbric.relaxMixinOverwrites` 会放宽指定的配置；`-Dforbric.mixinDiagnostics` 让注入要求保持严格（这样每一处不匹配都会报出来），同时保留 `required=false`。`required=false` 只在 Mixin 按单个 mixin 屏蔽失败的地方起作用，也就是它自己的应用循环里。MixinExtras 的 late 注入器（`@WrapOperation`、`@WrapWithCondition`、`@ModifyExpressionValue`）在那之后才注入和校验，位置是一个目标类级别的扩展（`LateInjectionApplicatorExtension.postApply`）。上游在这里遇到 `InvalidMixinException` 时会把整个类原样交回：这个类上所有 mod 的 mixin 都没了。会在这里抛出的有两种，玩家的游戏里只会遇到其中一种：late 注入器注入时自己的检查（比如 `@WrapOperation` 的 handler 和它绑到的调用对不上），任何设置下都会发生。另一种是 `mixin.debug.countInjections`（或包含它的 `mixin.debug`）的 `expect` 校验，任何一个注入点都没落上的注入器都会失败；这是调试开关，内核的测试 harness 会打开它，启动器不会。`LateInjectionIsolation` 排在这个扩展正前方，取走这个类的队列，按同样的顺序执行，每一步都套上 Mixin 自己的规则：可选 mixin 的失败放进这个类的 suppressed 列表，像其他失败的可选 mixin 一样上报，队列继续往下走。required 配置的失败、没有指明 mixin 的失败、`Error`（比如 `InjectionError`）和其他异常，抛出的方式和上游一样，但走到它们的方式不总是一样：上游在这个类的第一个失败处就放弃了，队列剩下的部分根本不会执行；这里放过一个失败之后，队列还会继续。所以排在后面、上游根本走不到的 late 注入，如果它的失败上游同样不会容忍——required 配置的失败，或者某个保持严格的 `require`/`allow` 抛出的 `InjectionError`（对被放宽的第三方 mixin，要 `-Dforbric.mixinDiagnostics` 或 `-Dforbric.requireFailSoft=off` 才会保持严格；内核不放宽的配置保留它自己的）——现在会让游戏停下；而上游已经放弃了这个类，只会打一条警告，让这个类原样不织入。放过一个失败之后，如果又有东西抛出队列，Mixin 只上报抛出的那一个，所以之前放过的那些会写进日志，并作为 suppressed 异常挂在抛出的异常上。一条注入也可能只做了一半（不带描述符的 target 同时匹配两个重载：第一个调用被包上了，handler 校验在第二个调用处失败）。这里不做回滚：Mixin 对它放过的可选 mixin 也不回滚任何东西，而且一条注入替换掉的指令，是排在它上面的所有其他注入共用的。包装类注入器也不需要回滚：`@WrapOperation` 和 `@WrapWithCondition` 都是先校验一个调用、再动它，`@WrapOperation` 改写调用时需要的桥接方法也在同一步加进类里。这一点是读 MixinExtras 的代码得出的，不是强制保证的，所以在放过一个失败之前，会先用 ASM 的 `BasicVerifier` 分析这个类的每个方法（检查每个汇合点的栈高度和每个值的基本类型，不是完整的校验）；如果有方法已经分析不通过，就把这个类原样交回，抛出它的第一个失败，结果和上游完全一样。这个分析只在 late 注入失败之后才跑；在一整套客户端整合包的 JVM 定义的 22,250 个类上（其中有 759 个是 Mixin 织入过的）跑了一遍，没有一个分析不通过。这些是 JVM 最终定义的字节，Mixin 的 `ClassWriter` 已经重算过 frame 和 max，不完全等于 `postApply` 那一刻手里的类。`@ModifyExpressionValue` 有一条先插指令、后校验 handler 的路径（在它认为还没被复制过的 `new` 后面插一个 `DUP`）；在 javac 编出来的 `new` 上它什么都不留下，在 javac 编出来的代码上也没有已知的 MixinExtras 0.5.4 注入器会在校验之前改代码。weave 测试覆盖了做到一半的 wrap（保留；被改写的那个调用上的两条注入都按各自的要求生效；类能通过校验）、handler 校验失败的 `@ModifyExpressionValue` 打在条件分支里的 `new` 上（保留，方法里只有 javac 自己的那一个 `DUP`），以及被替身弄得前后不一致的类（原样交回）。隔离读的是 MixinExtras 的私有队列，只接受 MixinExtras 0.5.4 声明的形状（`Map<ITargetClassContext, Map<String, List<Runnable[]>>>`）：安装时检查字段的泛型类型，取走每个条目之前也会检查；形状不对就退下，打一条警告，恢复上游行为。如果某个 guest 自带一份更新的、重定位过的 MixinExtras，并在它的配置插件的 `onLoad` 里启动，它会在隔离安装之后接管：内核那份 applicator 被注销，新的 applicator 有自己的队列，late 注入又会按上游的方式失败。隔离会在 Mixin 的活动扩展列表变化时发现这一点，并打一次警告。实测（带 lambdynamiclights、开 `countInjections`）：spruceui 的 NeoForge `GuiMixin` 包的是 `ClientHooks.extractScreen`，合并后的 `Gui.extractRenderState` 已经不调用它了，于是 `Gui` 丢了 fabric-screen-api 的 `GuiExtensions`，`Minecraft.<init>` 在强转处崩溃；balm 的湿度 wrap 也以同样的方式让 `CropBlock` 丢了所有 mixin，因为它在 `CropBlock.getGrowthSpeed` 里包的那次湿度读取，合并基底的两个 `getGrowthSpeed` 重载里都没有了（`randomTick` 调的是 NeoForge 的 `BlockState` 那个）。有了隔离，丢的只有这两条 wrap。这两处丢掉的调用是合并基底的问题，仍然没修：不管有没有隔离、开不开开关，spruceui 的 `BEFORE_EXTRACT` / `AFTER_EXTRACT` 屏幕事件和 balm 的湿度钩子都不会触发。不开 `countInjections`（也就是玩家的环境）时，这几组 mod 从来没有走到隔离这一步。`-Dforbric.lateInjectionIsolation=off` 恢复上游行为。
3. **逐个丢弃 mixin** —— 取 `KernelGuestMixinAdapter.unfitMixins`（见下文）、手工列表 `MergedBaseMixinCompat.SUPPRESSED_MIXINS` 和 `-Dforbric.suppressMixins=config:Mixin,…` 三者的并集，再减去 `-Dforbric.keepMixins`。丢弃一个 mixin，也会连带丢弃所有依赖它添加的接口的 mixin。

配置按 Mixin 读它的方式读取（`MixinConfigJson`）：Gson 的宽松解析，所以带注释的配置（Essential 带着它的预处理器写的 `//#if` 行）能得到普通配置能得到的全部适配，而不是一个都没有。mod 声明的配置不管文件叫什么名字都算数。被放宽的子配置所指的 `parent` 配置也一并放宽，因为子配置会继承它没写的注入器选项。外壳 jar 声明的配置，如果它内嵌的 jar 自己就带着，会被静默丢掉。启动之后才注册的配置（在 `preLaunch` 里调用 `Mixins.addConfiguration`，或者运行时加入的 jar）和启动时注册的一样放宽，归声明它的 jar 所属的 mod；配置插件添加的 mixin 在 Mixin 第一次读到它时评判（`-Dforbric.lateMixinJudgment=off` 关闭），这样下面的适配器会像对列出的 mixin 一样替它规划。

### 7.3 `MixinFit` —— 看能否解析，不看出处

`KernelGuestMixinAdapter` 让 `MixinFit.evaluate` 解析第三方 mixin 点名的每个锚点——每个 `@Shadow`、每个注入器的目标方法、每个 `@At(target=…)`——解析对象是合并后目标类经过**转换链之后**的字节：

| 判定 | 含义 | 默认动作 |
| --- | --- | --- |
| `FIT` | 所有锚点都能解析 | 应用 |
| `PARTIAL` | 部分能解析 | **应用**（只有在 `-Dforbric.mixinFit=strict` 下才丢弃）；总会报告 |
| `UNFIT` | 一个都解析不了 | 丢弃 |
| `HAZARD` | 能顺利应用，但 @Shadow 了一个被合并弄成孤立的字段 | 丢弃 |

细化规则：纯 accessor/invoker mixin 永远保留；由另一个 mod 的 mixin 满足的锚点（`ForeignMixinTargets`、`MixinAddedMembers`）算作已解析；`@Group` 注入器按组整体判定；如果一个注入器只绑定在合并后的游戏里没有任何地方调用的合并基底方法上，它就**不算**已解析（活性检查，`MergedBaseUncalledMethods`，`-Dforbric.mixinFit.liveness=off`），除非某个已安装的 mod 调用了这个方法。“没人调用”是在合并 jar、两个载体和内核上求的不动点，规则和写出 `uncalled-methods.txt` 的普查是同一套：来自本身没人调用的方法、或来自内核屏蔽掉的代码的调用不算调用；私有方法只认点名它自己那个类的调用或句柄，静态方法只认点名它的类、或点名一个没有重新声明它的子类的静态调用，重写方法对自身的 `super` 调用不算调用；一行要成立，还需要 mod 自己的游戏从一个在那里会运行的位置调用这个方法。启动时对已安装 mod 的扫描（`scanGuests`）按同样的规则读它们的调用，`@Invoker` 只算作对它目标类里同名同描述符的那个方法的调用，每一行还会在实际字节上再核对一次。内核把织进去的钩子中继到载体自己位置去运行的方法（`UncalledValueRelay`，§7.4），对它的普查行所列的生态算作在运行。最终只绑在这种方法上的注入器，会被最终类检查报告为从不运行（`CONFIRMED`，非必需：标记该 mod 的条目，不阻止启动）。Physics Mod 挂在一个没人调用的私有 `removeBlock` 重载上的坍塌钩子（现已由 `MixinTwinRebind` 挪到真正运行的重载上，§7.4），以及 Create 挂在没人调用的静态方法 `RedStoneWireBlock.shouldConnectTo` 上的钩子（现已由 `UncalledDecisionEntries` 挪到载体的判断上，§7.4），以前都被当成在运行。只按名字写的 `@Inject` 选择器，如果它绑到的方法（Mixin 取目标类里第一个同名方法）不是 handler 写给的那个（该方法的全部参数再加上与返回类型对应的 callback，或者只有 callback），Mixin 会报 "Invalid descriptor" 拒绝，这种也**不算**已解析（`-Dforbric.mixinFit.handlerFit=off`）：合并基底可能在原版方法的位置上放了载体的重载，不管 mod 属于哪个生态。`MixinOverloadPin` 会钉住的选择器（见 7.4）按它落到的那个重载来判，所以钉住和这条规则不会同时作用在一个注入器上；`@Surrogate` 只按 Mixin 查找它的方式算数——handler 的名字、与绑到方法的 callback 描述符完全一致、注解是可见的。如果这个名字恰好只绑到一个方法、handler 也不抓局部变量，并且它的某个 `@At` 在那个方法里一定能找到注入点（`HEAD`；方法里有返回时的 `RETURN`/`TAIL`；成员确实在方法里、并且数量超过 `ordinal` 的 `INVOKE`、`INVOKE_ASSIGN`、`FIELD`、`NEW`；带 slice 的一律不算），这个缺失就是一次*拒绝*（`MixinFit.Rejection`）：Mixin 在找到的每个注入点上检查 handler，在第一个点上直接抛异常，不看 `require`，异常让这个 mixin 对该类的应用整体失败——排在它后面的注入器全部跟着丢，配置仍是 required 的话整个游戏也停。如果不能确定有注入点，Mixin 可能一个点也找不到，什么也不注入、也不抛异常，所以这种绑定仍按普通缺失处理，日志那一行会写明原因（`-Dforbric.mixinFit.rejectionPoint=off` 恢复旧做法，把这种绑定都当成拒绝）。所以带着拒绝的 mixin——不管是 `PARTIAL`、因为在另一个 mod 的类上有缺失而保留的，还是 `UNFIT` 但因为另一个 mod 的 mixin 也改这个类而保留的——都不会原样保留：如果 mixin 里没有别的代码调用它、它不在 `@Group` 里、也没有哪个目标按原写法绑上它，就在 Mixin 读到 mixin 之前把这一个注入器剪掉（`GuestInjectorPruner`，见 7.4，对 Mixin 实际收到的节点、带着目标类的代码再按同一条规则问一次），mixin 的其余部分照常应用，剪掉的注入器记一条 `CONFIRMED` 发现，作者自己给它定的次数（`require`，没有就看 `defaultRequire`）至少为一时算 required；剪不了就像 `UNFIT` 一样整个丢掉。被内核修复整个顶替的 mixin（`SupersededMixins`）总是整个丢掉，那一行不向玩家发问，看到修复生效后自动消解。按名字保留的 mixin（`MergedBaseMixinCompat.KEPT_MIXINS`、`-Dforbric.keepMixins`）不经判定，原样交给 Mixin。`-Dforbric.guestInjectorPruner.refused=off` 让这种 mixin 照旧交给 Mixin，`PARTIAL` 汇总里也会单独计数。`PARTIAL` 和 `UNFIT` 的 mixin 都会先交给 `MixinRetarget` 问一次，改写后缺的锚点更少、是适配器会保留的 mixin、而且没有多出拒绝，就采用这份计划——`UNFIT` 的改写不能留下任何拒绝（`MixinRetarget.adopt`）。内核按名字压掉的 mixin 根本不判定，既没有判定日志，也不计入 `PARTIAL` 数。`PARTIAL` 的判定会在 mixin 交给 Mixin 时、所有适配器之后再读一遍（`KernelGuestMixinAdapter.judgeAsHanded`）：适配器挪到会运行的方法体上的锚点，从报告里撤回并写明落在哪里，因此变成合适的 mixin 直接消解。`MixinFitReport` 离线执行同样的判定：`MixinFitReport <merged-base.jar> <mods-dir> [--verbose]`。

只有 mod 自己的平台本来有这个成员，缺失才算合并造成的：Fabric mod 的平台是原版 26.2，MinecraftForge 或 NeoForge mod 的平台是各自打过补丁的 26.2（取声明这个配置的 mod 的生态，`MixinConfigOwners.ecosystemOf`；没有唯一一个 mod 认领的配置一律不问）。这两个打过补丁的游戏声明了原版没有的方法——`KeyMapping.getKeyModifier()`、`AxeItem.canPerformAction`、NeoForge 的 `EnderDragon.getParts()`——其中一些被合并丢掉或改了类型，所以对 MinecraftForge 或 NeoForge mod 来说，“原版也没有”什么也证明不了。如果一个注入器的 `method` 选择器全是普通名字，点名的方法这个平台也全都没有，并且没有任何东西要求它必须注入成功——没有 ≥ 1 的 `require`、配置原本的 `injectors.defaultRequire` 为 0、不在 `@Group` 里、没开 `mixin.debug.countInjections`——那么原生 Mixin 会一声不吭地跳过这个注入器，mixin 的其余部分照常应用（sponge-mixin 0.17.x 和上游 Mixin 0.8.7 都一样：`TargetSelectors.validate` 只在要求次数大于 0 时才抛错；配置的 `required` 只决定这种错误是否致命）。“普通名字”指一个方法名，可以带描述符，可以带目标类自己作为所有者；`@` 开头的动态选择器（MixinSquared 的 `@MixinSquared:Handler` 会解析到另一个 mixin 的处理方法）、`+` 或 `{n,}` 量词（其最小次数不管 `require` 是多少都会抛错）、带点号的所有者、正则和格式不对的描述符一律不回答。`NativeAbsentTargets` 把这样的注入器既不算已解析也不算缺失，只打一行 info 日志，于是 mixin 按其余锚点判定；其余都不缺时整个交给 Mixin —— 不产生发现，不触发策略停止。Not Enough Crashes 对 `BlockEntity.populateCrashReport` 的 `@Inject`（26.2 里叫 `fillCrashReportCategory`）就是这个情况。“平台也没有”由合并基底的原始字节加上随包分发的差异表 `native-only-methods.txt` 回答：每个平台各有一份，列出它的游戏在原版包里声明、而合并基底没有声明的方法（原版 565 个、MinecraftForge 424 个、NeoForge 14 个），以及只有合并基底才有、位于原版包里的类（分别 18、10、6 个）。原始类里仍然声明着的方法、`net/minecraft/`/`com/mojang/` 之外的类、别的 mod 的类，一律不会被判成“平台也没有”。这些行只对推导它们的那个基底成立，所以表里还记录了那个基底的成员摘要（原版包里每个类及其方法的名字和描述符，不含方法体）；由摘要不同的 jar 提供的类不回答，并打一条警告说明。`com/mojang/` 下 Minecraft 自己的库（brigadier、DataFixerUpper、authlib 等八个）按它们自己的字节回答：内核把它们放在合并基底旁边加载，每个平台加载的都是同样的 jar（原版 26.2 的版本 JSON 列出它们，MinecraftForge 65.0.1 和 NeoForge 26.2.0.88 的启动器配置继承这份清单，且不额外加任何 `com.mojang` 库），所以表里也记录了这些 jar 各自的成员摘要（`library` 行），恰好由其中之一提供的类按合并基底的类那样回答；别的版本的库 jar 不回答。这些行对每个平台只描述一个游戏——原版 26.2、MinecraftForge 65.0.1 打过补丁的 26.2、NeoForge 26.2.0.88 打过补丁的 26.2——表里的 `platform` 行记着这些版本。如果一个 mod 必需的 `minecraft` 范围，或对应平台的 `forge`/`neoforge` 范围，不包含这个版本，它就不是为这个游戏做的（它自己的加载器在这里会拒绝它，而它面向的更新的游戏可能就有那个方法），所以不回答，并打一行 info 说明是哪条要求；范围读不懂的、内核没有发布其清单（`ModPresence`）的 mod 也不回答。这些情况下缺失都照旧算合并造成的。`-Dforbric.mixinFit.nativeAbsent=off` 让这种注入器重新算作缺失。

以前的规则是看出处，而那是错的：合并基底*本身就是* NeoForge 打过补丁的 Minecraft，再拼进了 Forge，所以“Forge 系的类”说的是这个 jar 的大部分。

### 7.4 适配器 —— 把注入器挪到代码的新位置

当合并把第三方注入器要找的东西挪了位置，内核会把注入器跟着挪过去，而不是丢弃它。每个适配器的适用面都很窄，靠表格或证明驱动：

`MixinRetarget` 和 `MixinStubRebind`（委派存根 → 实际承载方法体的重载；先算出一个参数再转调的存根——`Player.doSweepAttack` 的判定框、`EntityFluidInteraction.update` 的谓词、`Entity.restituteMovementAfterCollisions` 的方块位置——只在承载方法体的重载的其他调用方全都是原版里调用存根签名的方法时才挪，所以 NeoForge 注册表快照直接调用的 `MappedRegistry.register(int, …)` 不会挂上 fabric-registry-sync 的新增条目回调）、`MixinOverloadPin`（只按名字写的 `@Inject`，Mixin 会绑到排在前面的另一生态的重载上时，钉到 handler 唯一对得上的那个重载——只在第一个重载接不住 handler 时才动；钉不了就说明原因）、`MixinMergedTwin`（以 `$forbricneo` 改名的匿名孪生类）、`MixinAnonymousRetarget` + `MergedBaseAnonymousDrift`（重新编号的 `Outer$N`）、`MixinAtWidenedCall` 和 `MixinWrapOperationShim`（载体加宽过或重排过的调用；`@Redirect` 只跟着复核过的 `REDIRECTABLE` 行里的加宽静态调用走，经一个丢掉追加参数的包装——creativecore 的 `RegistryFriendlyByteBuf.decorator`，此后它建的缓冲区带的是 `ConnectionType.OTHER`，NeoForge 那些看连接类型的 codec 在这些缓冲区上走原版线格式）、`MixinRelocatedCall`、`MixinSubtypeOwnerRetarget`（同一个调用换了 owner：子类型，或合并加宽过的字段的合并类型——`RangedBowAttackGoal.mob` 让 `Monster.lookAt` 变成了 `Mob.lookAt`；注入点拿到经由该字段那次调用的 ordinal，handler 外面加一道守卫，只在字段装的是原版类型时运行）、`MixinShearsRelay`、`MixinHandlerShim`、`MixinAtShape`（不同 Mixin 分支之间 `at=[…]` 与 `at=…` 的差异）、`MixinLocalsCapture`（`CAPTURE_FAILHARD → CAPTURE_FAILSOFT`）、`InsertedLambdaArgumentShim`、`MergedBaseCalleeSwaps`（其中的 `REPLACED` 行：载体在唯一调用点整个替换掉的原版私有方法，由 `MixinRetarget` 的 R7 跟随——`StructureTemplate.placeEntities` → NeoForge 的 `addEntitiesToWorld`，挪过去的 HEAD handler 从 settings 上读回原版的参数；这类选择器由上面那条 handler 匹配规则发现，这些行只负责挪）、`MergedBaseAbsorbedCalls`、`CarrierHelpers`（表 `carrier-helpers.txt`）、`CarrierRenames`（表 `carrier-renames.txt`：载体把原版方法体整个或分段搬进了它新加的、描述符相同的方法，每一行是随方法体一起搬过去的一个调用或字段访问，以及它在 mod 自己那套游戏的这个方法（参考方法）里出现了几次——Fabric mod 的参考方法是原版的，Forge 或 NeoForge mod 的是对应载体打过补丁的那个；次数不同时按生态分成几行。`MixinRetarget` 的 R3 只在 mod 自己那套游戏里方法体还在原处、注入器的每个锚点都是这些搬过去的调用之一而且绑定的次数不超过参考方法里的次数（写了 `ordinal` 的锚点要求次数完全一致）、并且合并后的类仍然调用这个改名方法时，才把选择器挪过去——唯一的例外是表里标成 `UNCALLED` 的那一行，挪过去只是为了让注入器绑上。这一行是 NeoForge 的 `ItemStack.addDetailsToTooltipComponents`：原版的 tooltip 方法体，NeoForge 把它留成私有方法、从来不调用（NeoForge 用自己的 appender 画 tooltip）；普查会排除所有不是私有的、或者同一 nest 里有任何代码调用它的改名方法。挪到这里的注入器永远不会运行，而且会如实报告：MixinFit 判它“永远不运行”，最终类检查把它记为一个永远不运行的注入器（CONFIRMED，但不是 required），只会在这个 mod 的那一行做标记，不会阻止启动。malilib 的最后一个 tooltip 钩子（`onGetTooltipComponentsLast`，required）会落到这里；trinkets 作为 Fabric mod 加载时（它是通用 jar，默认按 NeoForge 加载），它的属性行钩子也会落到这里；如果不挪，malilib 的钩子哪里都绑不上，成为一条 CONFIRMED 的 required 损失，严格策略下所有带 malilib（Litematica、MiniHUD、Tweakeroo）的客户端都会被拦下，默认策略下会弹出询问。让这类钩子在 NeoForge 真正生成 tooltip 行的地方运行，目前还没有做。改名方法包含参考方法的全部调用和字段访问、并且每个出现的次数都相同的行是 `RENAME`，否则是 `PIECE`（NeoForge 的 `addDetailsToTooltipTail`、`startSleepInBed` 的 lambda、四个 HUD 层）；挪进片段的只能是只依赖那个调用本身的 handler：不能共享变量、不能捕获局部变量、不能用 slice，要拿方法参数的 handler 只在方法把自己的参数原样交给这个片段、并且之前没有改写过这些参数时才挪。能取消方法的 handler（可取消的 `@Inject`、带 `@Cancellable` 的回调）只在合并后的方法会把片段返回的 `Either` left 原样返回时才挪进片段——表里标成 `LEFT` 的行，并且运行时再按实际字节码核对一遍：NeoForge 的 `startSleepInBed` 把 lambda 的结果交给 `EventHooks.canPlayerStartSleeping`，结果带问题就直接返回——而且这个 handler 所有可能的取消值都必须是 `Either.left(..)`，分析时会跟进它自己 mixin 里的 lambda 和私有辅助方法。apoli-legacy 的鸟类禁睡和 Fabric API 的睡觉朝向否决（`MODIFY_SLEEPING_DIRECTION`）都是这样在 lambda 里取消的，所以 NeoForge 的 `CanPlayerSleepEvent` 会像看到原版的问题一样看到它们的问题，`startSleepInBed` 也会把它返回；apoli 取消时用的是 `Either.left(null)`，NeoForge 的这个钩子读不了它，会在 `startSleepInBed` 里面直接抛异常。走原版 `BedBlock` 这条路径时两边都会失败（apoli 自己的游戏里是 `BedBlock` 对这个 null 调 `message()` 时抛异常），但在 Forbric 上，会判断 null 的其他调用方（别的 mod 的睡袋、自定义床）也会失败；这和加入这张表之前一样。在完整的 `RENAME` 上，共享变量的 handler 只在它所在 mixin 里同一方法上共享同一个变量的 handler 全部一起挪时才挪。只看字节码的时候，R3 曾把注入器挪进形状相同但毫不相干的方法（text_styles 的颜色钩子挪到了阴影颜色上，ViaFabricPlus 的物品使用和快捷栏按键钩子挪进了别的原版方法，goldenpotions 的标签页图标挪进了另一个标签页的 lambda），也挪进过那个没人调用的 tooltip 改名方法体，而且都被判为完全匹配。前四个现在 R3 不再挪：ViaFabricPlus 5.0.2 的那两个是 NeoForge 在原处换掉的调用上的重定向，改由 `ReplacedCallRedirects` 挪（见下）。`-Dforbric.mixinRetarget.renameCensus=off` 恢复只看字节码的做法，`-Dforbric.mixinRetarget.renameCensus.leftExit=off` 让所有能取消的 handler 都不进片段，`-Dforbric.mixinRetarget.renameCensus.uncalled=off` 让所有注入器都不进 `UNCALLED` 的方法体），`FabricClientMixinAnchors`（对载体加宽过的调用的空重定向，按源头契约找出来），以及 `MixinOperationSeamTransport`：Fabric mod 挂在某个调用前后的回调，如果 mod 自己那套游戏在宿主方法里直接做这个调用，而合并后的宿主改经一个继承来的网关去做（`Gui.extractRenderState` 的屏幕绘制，合并后的 `Gui` 经 `ForgeLayerInstance.drawScreen` 去画），回调方法体原样保留，在网关里面那一次调用前后运行。回调用 MixinExtras `@Local` 取的值，只有在 mod 自己那套游戏里能证明时才一起带过去：要么是宿主方法自己没被改写过的参数，要么正是这次调用的操作数所读的那个槽位，并且载体把这个操作数从网关原样传到调用；回调拿到的就是合并后的宿主交给网关的那个值。LiquidBounce 的整个浏览器菜单就画在这里，画布用 `@Local` 取；`-Dforbric.operationSeams.locals=off` 让这类回调留在原处。还有 `ReplacedCallRedirects`：载体在原处把一个原版调用换成了自己的调用，而 mod 对原版调用的 `@Redirect` 只是在转发原版调用外面加一个条件时，这个重定向挪到载体的调用上、改为转发载体的调用。只沿着表里的行挪，每行写明两者在哪里互相对应、哪些操作数是同一个值以及理由（ViaFabricPlus 的快捷栏按键：`KeyMapping.matches` → `isActiveAndMatches`；物品持续使用：`ItemStack.isSameItem` → `CommonHooks.canContinueUsing`，handler 自己的逻辑仍按原版的参数顺序看两个物品堆；铲子压路：`FLATTENABLES.get` → 方块状态的 `SHOVEL_FLATTEN` 修改）；只对自己那套游戏里做的是原版调用的生态生效；`-Dforbric.replacedCallRedirects=off` 关闭。还有 `MixinTwinRebind`：合并基底保留了某个原版方法、但合并后的游戏里没有任何代码调用它，而载体在它的位置加了一个重载时，按原版签名写的注入器挪到这个重载上。只沿着 `carrier-twins.txt` 的行挪（`CarrierTwinCensusTest` 推导：原版方法对这个 mod 的生态来说没人调用、也不是载体存根；那个重载包含原版的每一个参数，按局部变量名和类型一一对应；该生态自己那套游戏里调用原版方法的每个方法，在合并基底里都改为调用这个重载）。handler 捕获的原版参数在重载里位置不同时会被包一层：外层接重载的参数，再按原版的参数交给原 handler。只在原版方法仍然没人调用（没有已安装的 mod 引用它）时挪；只挪捕获全部原版参数或不捕获参数的 `@Inject`，以及能确定自身参数约定的 `@At` 类注入器（`@ModifyVariable` 不挪）——或者用旧方式捕获局部变量（`locals = CAPTURE_*`）、注入点是两个方法都恰好调用一次的某个 `INVOKE` 的 `@Inject`，前提是捕获的每个局部变量在重载里那个位置都有同名同类型的对应项（挪过去的 handler 以 MixinExtras `@Local` 读取它们；Physics Mod 的坍塌钩子捕获的是 MinecraftForge 的 `removeBlock(pos, canHarvest)` 在 `Block.destroy` 之前的局部变量，现在在 NeoForge 的 `removeBlock(pos, state, canHarvest, tool)` 里运行，也就是合并后的 `destroyBlock` 调用的那个）；每个 `INVOKE`/`FIELD` 注入点在两个方法体里出现的次数必须相同。外层 handler 占据被挪 handler 在 mixin 声明顺序里的位置，所以同一注入点上的 handler 按原生顺序运行。NeoForge 给 `ModelBlockRenderer.shouldRenderFace` 加了方块自己的位置参数，并且排在原版那个前面，所以 LiquidBounce 的 X-Ray 面剔除钩子（按名字选择、接原版的四个参数）以前会绑到 NeoForge 的重载上、在那里被拒绝，连带整个方块渲染 mixin 一起失效；`-Dforbric.mixinTwinRebind=off` 关闭。还有 `ThinnedCallOrdinals`：载体把一个原版调用换掉了几处、保留了其余几处，使这个调用出现的次数比原版少时，按原版方法体数出来的 `@At(INVOKE)` ordinal 会改成合并后方法体里对应同一个调用的那一处。只沿着复核过的行改：每行写明原版的每一处对应保留下来的哪一处（或者已经没有），以及每个保留下来的调用后面紧跟的是哪个调用（在两个 jar 上核对过，运行时还会对实际的方法再核对一次）。ViaFabricPlus 的 1.12.2 放置钩子位于 `MultiPlayerGameMode.performUseItemOn` 第三个 `ItemStack.isEmpty()` 之前；NeoForge 和 MinecraftForge 把原版对两只手物品的 `isEmpty` 判断换成了 `doesSneakBypassUse`，所以合并后的方法体只剩下第三个。只对按原版次数编译的生态生效；`-Dforbric.thinnedCallOrdinals=off` 关闭。`GuestInjectorPruner`（COREMOD）在内核接管了某些注入器功能的地方，从第三方 mixin 类里剪掉这些单独的注入器；另外在字节码提供器的适配器全部跑完之后，剪掉判定认定 Mixin 会直接拒绝的注入器（见 7.3）——每个都要在 Mixin 即将拿到的节点上按同一条规则再确认一次，已经被某个适配器挪到合适位置的注入器会留下。有几个适配器会读取 `src/main/resources/net/forbric/kernel/mixin/` 下随包分发的表（`carrier-helpers.txt`、`carrier-renames.txt`、`carrier-stubs.txt`、`carrier-twins.txt`、`lambda-permutations.txt`、`uncalled-methods.txt`、`native-only-methods.txt`）；`CarrierHelperCensusTest`、`CarrierRenameCensusTest`、`CarrierTwinCensusTest`、`UncalledMethodCensusTest` 和 `NativeOnlyMethodsCensusTest` 根据暂存的 jar（`native-only-methods.txt` 读的是合并基底、两个打过补丁的游戏和原版自己的 jar）重新推导 `carrier-helpers.txt`、`carrier-renames.txt`、`carrier-twins.txt`、`uncalled-methods.txt` 和 `native-only-methods.txt`，并把它们锁定。把 handler 方法体改名挪到一边、原名换成包装的那几处（`MixinRetarget` 的守卫和 R7、`MixinAtWidenedCall` 的 redirect、`MixinSubtypeOwnerRetarget` 的守卫），改名时会带上 mixin 类的标记，免得同一目标上两个 mixin 的同名 handler 被合并成一个方法体。`MixinFitLivenessCensusStagedTest` 另有一份普查：Fabric mixin 点名的锚点里，在原版 26.2 上能解析、按内核的判定在合并基底上解析不了的那些。只有 fabric-api 的行有断言（多出一行构建就失败）；`FORBRIC_ANCHOR_PACKS` 指定的其他语料只出报告（`build/reports/vanilla-anchor-census.txt`）——第三方 mod 的行不会让任何东西失败，得有人去读报告。

#### 7.4.1 原生等价 —— 用通用机制取代特判

把注入器挪到合并基底放代码的地方，还不等于 mod 原生得到的东西。注入器必须落在它在 mod 自己的游戏里落的那条指令上，只在它在那里运行的那些执行里运行，看到它在那里看到的值。下面这些机制做的就是这件事。它们取代了按功能面划分的 Fabric 适配器（`FabricBlockBreakMixinAdapter`、`FabricEntityMixinAnchors`、`FabricEnchantmentMixinAdapter`、`FabricMiningMixinAdapter`、`FabricSoundMixinAdapter`、`FabricServerLanguageMixinAdapter`、`FabricSectionCompilerMixinAdapter`、`FabricFluidFlowMixinAdapter`）、流体交互和呼吸适配器、声音契约改写和全清否决改写，以及所有认某个 mod 的 mixin、handler 或 `@Local` 名字、某个 mod 的字节码模板、或按 SHA-256 认某个库的方法体的适配器。它们都不由名字触发：每一个都比较两个方法体——Mixin 将要拿到的合并后方法，和*原生方法体*，即该方法在平台自己那份字节里的样子，从合并基底的 `native-reference` 目录读取（§2.3），平台是 mod 自己所属生态的那个（`MixinConfigOwners`）。

- **读两个方法体。** `CodeAlignment` 把每条原生指令映射到合并后方法体里“同一个程序的同一步”的那条指令（被载体删掉或改写的映射为空）；`ReturnAlignment` 说明合并后的哪个 return 对应原生的哪个 return；`HandlerFlow` 按数据流读 handler 计算了什么，所以一份契约对 handler 不管怎么编译都成立，对多做了任何事的 handler 都不成立（按指令条数匹配的模板已经去掉）；`OperandStack`；`DeliveryBookkeeping` 把游戏自己的代码交给每个决定注入点落在哪里的适配器，不让内核的投递记账（§8.1）挡在中间；`MergedSourceRenames` 按 Mixin 给 lambda 和辅助方法起的名字比较被搬运的方法体。
- **注入点落在哪里。** `NativeOccurrences`/`NativeOccurrencePoint` 在原生方法体上数调用、字段、存储或表达式注入点，只选合并后方法体里对应原生那几次的出现，不管载体的代码添加、删掉或重排了多少次（`AddedCallOccurrences` 用同样的方式给没写序号的注入点编号）；`NativeSliceOccurrences` 在载体把某个调用挪出 slice 时，按它的操作数和条件把切片注入点选中的调用重新找出来；`MixinReturnOrdinals` 按“是第几种出口”而不是按位置数 `RETURN` 的 ordinal；`MixinShiftedPoints` 让 `shift = BY` 落在它原生会落的那条指令上；`JoinedReturnExits` / `MixinKeyActionAdapter` 处理载体把几个出口并成最终 return 前一次钩子调用的情况（也能跨过 `VanillaEarlyReturns` 在尾部前面放的 return 块找到汇合点；原版在 `handleGlobalKeyPress` 回答 true 后就返回的全局键——F2、F11——被载体送进自己的钩子时，最终 return 上的钩子不会在它上面运行）。载体换掉了操作数的某个原生出现，按它的值被哪个对齐的消费者使用，和合并后的对应出现配对（Mod Menu 的常量钩子像原生一样包住三个按钮，不再把 NeoForge 的 mods 按钮当成第四个）。
- **代码去了哪里。** `MixinStubRebind`（现在也处理 slice、连同同伴一起挪的共享状态重定向、连同它读取的 HEAD 回调一起挪的 HEAD 参数修改器，以及用槽位原生持有的值来固定槽位的 `@ModifyVariable`；按 mod 被织入时的 Mixin 级别判断）、`MixinBuilderStubRebind`（原版签名被保留成一个构建器存根）、`MixinTwinRebind`（见上文）、`MixinRenamedBodyEdges`（挂在被载体改了名的方法体边缘的注入器，也会挂到只转发给它的每个方法上）、`MixinSplitBodyEdges` + `SplitBodyRuns`（被载体拆成几块的方法，它的开头和结尾分别是第一块的开头和最后一块的结尾，开头处的取消会跳过后面几块）、`MixinForgeModelDataEdge`、`NativeCallInlining`（mod 的注入器点名的调用被载体挪进了一个辅助方法时，把这个辅助方法内联回去）、`MixinFieldInitialiserPlacement`（mixin 的字段初始化放回 `super()` 之后）、`MixinExpressionThunks`（内核把某个方法引用改道经过一个 thunk 后，MixinExtras 表达式仍能找到它）。被挪走的 handler 保留它在 mixin 声明顺序里的位置。
- **载体换掉的判断。** `CarrierDecisions`（原版某个判断上的注入器，挪到载体在原处做的判断上，答案的极性双向映射）、`CarrierGuardedPoints`（被载体放到它自己某个分支后面的 `@Inject` 挪回分支前面）、`CarrierDefaultChains`（对一个原版调用的重定向，而载体现在通过继承来的实现来做这个调用）、`CarrierHelperResults`（原版某个调用上的 `@Inject`，而载体现在在一个辅助方法里做这个调用：在辅助方法的答案被证明能说明“调用是否发生”时，按这个答案运行；在被载体整体改名的方法体里，改名方法体规则优先）、`CarrierReturnedCalls`（对某个调用的包装，而这个调用的答案就是方法的答案）、`CarrierMethodReferences`（原生 lambda 里的重定向，而载体把这个 lambda 换成了一个方法引用；方法引用折进来的值按链自己的规则读，不挪时会说明原因——装了 Origins Legacy 时 fabric-item-api 的附魔检查）、`UncalledDecisionEntries`（合并后的游戏已不再调用的静态判断方法 `HEAD` 上的注入器，在原生游戏问这个方法的每个地方包住载体自己的判断，交给它原生的参数，它的取消就是那里的答案——Create 在 `RedStoneWireBlock.shouldConnectTo` 上的红石连接，NeoForge 在那里改问邻居的 `canRedstoneConnectTo`；两个 mixin 挂在同一个这种判断上的钩子会变成两层包装，所以运行顺序和原生相反，第一个取消时第二个也照样运行）、`ReplacedDecisionValues`（对原版某个判断的 `@ModifyExpressionValue`）、`NativeEntryRelay`（再次调用原生的判断方法，让挂在它入口上的注入器运行）、`NativeDecisionIsland` + `NativeDecisionRelay`（把原生方法的那些判断放回替换了它们的载体钩子前面，并把答案交给载体的代码）、`CallWitness`（被挪走的调用之后重放的 handler，只在这个调用确实发生过时才运行）。
- **值与局部变量。** `NativeValueStores`（载体先往同一个局部变量里存了别的值时，`STORE` 修改器落在原生值的那次存储上）、`NativeConsumerStores`（为局部变量唯一一次原生存储写的修改器，挪到紧接着那次存储的原生读取上）、`NativeDeadValueConsumers` + `NativeValueRelay`（合并后的方法把某个值算出来却没人用时，把第三方改过的值交给载体自己的消费者）、`NativeLocalPins`（按类型取的 `@Local` 指向 mod 自己的加载器会交给它的那个局部变量）、`NativeLocalMaterialization`、`MixinCapturedLocals`（`locals = CAPTURE_*` 的捕获变成读原生局部变量的 `@Local`）、`MixinCameraRollAdapter` 的参数列表（参数列表钩子从它的 slice 原生选中的调用，挪到合并后起同样作用的加宽调用上），以及 `MixinDelegationEquivalence`（只用于报告：证明一个找不到调用可挂的参数修改器并没有丢失什么）。
- **没人调用、而载体在别处算它的值的方法。** `UncalledValueRelay` 在 Mixin 之后运行，不改 mixin：普查列为没人调用的原版方法里织进了第三方钩子，而这个方法的值由载体在自己的某个位置算出来时，把织好的方法体复制成同一个类里的中继方法，替换其中三个调用——原始抽样什么也不返回（载体已经抽过了），加工返回载体的值，效果只记下数量而不真正执行——再由载体的那个位置运行它。每个钩子都留在 Mixin 放的位置，按原来的顺序运行。目前只有一份合约：`Block.tryDropExperience`，它的值就是 NeoForge `BlockDropsEvent` 的经验（§8）。中继只为“自己的游戏会调用这个方法”的生态的钩子制作（NeoForge 的钩子挂在那里，原生上也是死的，不会因此开始运行）；只在抽样的值直接交给加工时制作；钩子拿到方法的 provider 参数时，交给它的是原生调用方传的那个值（方块的字段、静态字段，或对常量的静态调用，从调用方的原生方法体读出来）。其他情况保持织好的样子，打一条 WARN，最终类检查照样报告。
- **规则把方法体放到旁边时用的名字。** 规则把 mod 的 handler 方法体留作普通方法、外面再包一层时，名字由 handler 名、规则和 mixin 共同决定（`MixinHandlerShim.asideName`；所有规则跑完后，`markAsides` 给每个 `$forbricOriginal` 方法体加上它所属 mixin 的标记）。Mixin 按名字把普通方法合并进目标类：以前两个 mixin 的 handler 同名时会共用一个方法体，第二层包装运行的是第一个 mixin 的方法体（"Method overwrite conflict"；一个可取消、一个不可取消时，游戏方法直接抛异常）。

每个机制都有一个 `-Dforbric.<name>=off` 开关（§17），也都有一个织入测试：让一个名字毫不相干的合成 mod 经过真正的 Mixin 和 MixinExtras，开关打开和关闭各跑一次（`src/test/java/net/forbric/kernel/mixin/weave/`，共 164 个文件）。只有同一个 mod 还通过了 §16.1 的改名实验和原生对比，一个机制才算做完。

### 7.5 归因

`MixinConfigOwners` 在注册前把每个配置映射到它所属的 mod，这样 Mixin 自己报出的失败就会点名那个 mod（`-Dforbric.mixinModIdDecoration` 还会把 mod id 写进生成的 handler 名）。`KernelMixinErrorHandler` 把准备/应用阶段的失败记到该 mod 的那一行上，但不改变 Mixin 的决定。`FinalMixinApplications` 在所有阶段结束后观察每个已定义的类——某个 handler 零引用，就证明它没有挂上。当某个具名的内核修复完成了那个 mixin 做的*全部*事情时，`SupersededMixins` 不让这次失败记到该 mod 的那一行上；在 Mixin 读配置之前就被拿掉、而接手它的修复有见证类的 mixin，在见证类定义之前只是一条 `SUSPECTED` 备注——在见证类里看到修复就 `RESOLVED`，没看到或已无法证明（`LootSourceContracts.disproved`）就 `CONFIRMED`——所以 fabric-loot-api-v3 不再在进第一个世界之前显示“没加载完”；当该 mod 自己的配置插件本来就会拒绝这个 mixin 时，`PluginDeclinedMixins` 也这样处理；`ForeignMixinBreaks` 记录那些专门写来挂到另一个 mod 上、结果没挂上的 mixin。`MixinCompatibility` 让一个 mixin 从预检到应用始终带着同一个身份。

### 7.6 跨 mod 的重叠 —— `MixinOverlapLint`

`MixinFit` 拿一个 mixin 对照基底来判；两个各自都合身的 mod 放在一起仍可能相撞。`MixinOverlapLint` 列出每个 handler 的占用（目标方法、`@At` 调用、ordinal），把不同 mod 的占用两两配对 —— 打包在一个 jar 里的模块算作装进来的那个 jar，同一 mod id 的两个 jar 算作同一个 mod：

| 规则 | 组合 | 类别 |
| --- | --- | --- |
| R1 | 同一方法上的两个 `@Overwrite` —— 只留下一个方法体：优先级高的那个，优先级相同时留先应用的那个 | 冲突 |
| R2 | 同一调用上的两个 `@Redirect`，ordinal 相同或未指定 —— Mixin 只保留一个 | 冲突 |
| R3 | 一个 `@Overwrite`，加上另一个 mod 在该方法里的任意注入器 | 冲突 |
| R4 | 同一调用上的 `@Redirect` 和另一个 mod 的 `@WrapOperation`/`@ModifyExpressionValue` | 提示 |

通配符/正则选择器，或目标类读不到时的裸方法名，不产生占用；slice 不读。启动时（§3.2 第 17 步）它按 `ForbricMixinService` 实际交给 Mixin 的配置来读（内核删掉的 mixin 已不在里面），每个 mod 每条冲突记一条 `SUSPECTED` 发现，id 为 `mixin-overlap:<owner>.<name><desc>[@<at>]`，detail 里点名另一个 mod；日志里记各规则计数和耗时毫秒数（`-Dforbric.mixinOverlapLint=off` 关掉）。崩溃调用栈经过一个记录了冲突的方法时，`CrashAttribution` 会同时点名这两个 mod。离线：`MixinOverlapLint <merged-base.jar> <mods-dir> [--json out]`（递归查找 jar）。

## 8. 事件桥

两个 Forge 系的钩子争夺同一个调用点、合并只留下一方的地方，落败方的钩子成了死代码，于是这个系的监听器挂在一条没人发布事件的总线上。MinecraftForge mod 要的必须正是 `net.minecraftforge.…Event` 的实例，所以重新发出事件本来就无法避免。编辑拼接保留了两边钩子的地方（§2.4），游戏自己会发布两个事件，桥就不能再发一遍（§8.1）。

- **桥清单** —— `net.forbric.api.GameEventBridge` 列出 106 个桥，每个都标明对应的事件、所属的安装轮次，以及从玩家角度说的**代价**。安装轮次有：`GAME_BUS`（客户端与服务端）、`CLIENT_GAME_BUS`、`CLIENT_MOD_BUS`、`CLIENT_INIT`、`REGISTRATION`、`CLIENT_HUD`、`ON_DEMAND`。
- **校验** —— `net.forbric.api.EventBridges.verify(pass)` 把实际装上的桥和声明的桥对照，缺了哪个就连同它的代价一起点名；桥装不上时会少掉一项功能，却不抛任何异常，所以这是它唯一能被看见的途径。
- **实现** —— `boot.GameEventMultiplexer` 安装总线之间的桥；游戏侧的另一半是 `runtime.KernelGame*Events`（tick、服务端生命周期、玩家、level、世界、方块、实体、伤害、追踪，以及客户端的 tick/渲染/输入/网络/资源/界面鼠标事件），外加 `KernelGameResultBridges`，负责 MinecraftForge 一侧有返回值的那两个事件。可取消的事件会把取消结果传回去。不属于总线到总线的桥由转换器落地（`ForgeDamageSeamsInjector`、`ForgeCreativeTabsInjector`、`ForgeSpawnPlacementsInjector`、`ForgeClientConsumersInjector`、`ForgeBlockTintInjector`、`ForgeOverlayNeuterInjector`/`KernelForgeOverlayLayers`……）。
- **其他方向** —— 合并后的方法体不再发布的 NeoForge 事件（`ItemTooltipEvent` 经由 `KernelItemTooltips`，`ScreenEvent.Opening/Closing` 经由 `NeoScreenEventsInjector`，转化事件的 `Post` 经由 `NeoConversionPostInjector`）；在 Fabric 的 mixin 套不上的地方，从 NeoForge 自己的调用点触发 Fabric API 事件（`LootTableEventBridgeInjector` + `LootTableEventDispatch` 负责 `LootTableEvents`，`FabricFuelValuesInjector`，以及提示框桥）。合并后的服务端在 `populateFuelValues` 里建燃料表；燃料桥把那个 builder 交给恢复出来的 `vanillaBurnTimes` 门面，门面直接跳到它自己的 `build()` 前面，所以 mod 包在这个 build 上的钩子（Create 的烈焰蛋糕、纸板）把燃料加进去一次，客户端的 data map 表也带着它们。`KernelHudBridge` 里通过 fabric-api 的实现类重画 `HudElementRegistry` 根节点的 Fabric 那一半已经退役：fabric-rendering-v1 自己的 `HudMixin` 的包装现在能挂上了，落在内核给方法引用生成的方法体里（`LambdaInvocationThunkInjector`），而这座桥在每次测量中都是“21 个根节点桥接了 0 个”。合并后的游戏播放声音的每个地方，MinecraftForge 的 `PlayLevelSoundEvent` 都从 NeoForge 的同名事件转发过去，答案再带回来（`KernelGameSoundEvents`）。
- **挖方块的经验** —— NeoForge 在 `BlockDropsEvent` 里算经验，由 `handleBlockDrops` 弹出一次（玩家挖方块能走到这里，靠的是合并的委托孪生一遍，§2.4）；MinecraftForge 在它的 `BreakEvent` 里算，在它自己的 `destroyBlock` 里弹出，而那不是合并后的那个；Fabric mod 挂的是原版的 `Block.tryDropExperience`，合并后的游戏里没人调用它。`KernelGameBlockEvents` 在 `HIGHEST` 把 MinecraftForge 的决定（监听器调用了 `setExpToDrop`，或者方块的经验只由 MinecraftForge 的 `getExpDrop` 回答）交给同一次挖掘的 `BlockDropsEvent`；在 `LOWEST`，对原生 `spawnAfterBreak` 会问 `tryDropExperience` 的方块（普查列出的调用方，经过只在父类基础上追加代码的子类也算），运行它的中继（`UncalledValueRelay`，§7.4），交给它事件当时的值，事件掉落中继本来会弹出的数量。于是 Fabric mod 替换或取消矿石经验、PuzzlesLib 的经验事件，都只在 NeoForge 弹出的地方决定一次经验。不相等的地方：只读 `getExpToDrop` 的 MinecraftForge 监听器看到的是 MinecraftForge 自己那次抽样，不是 NeoForge 实际掉落的那次（设置它的监听器结果正确）；在内核之后注册的 `LOWEST` NeoForge 监听器看到中继后的值，之前注册的看到中继前的值。
- **呼吸** —— 合并后的 `baseTick` 只把空气值交给 NeoForge 的钩子；MinecraftForge 的（`ForgeHooks.onLivingBreathe`）没有调用方，所以 MinecraftForge 的 `LivingBreatheEvent` 监听器从不运行。`KernelGameWorldEvents.installBreathe` 在 `LOWEST` 从 NeoForge 的事件发出 MinecraftForge 的事件，只在有 MinecraftForge 监听器时才做（这是每个活体实体、每个 tick 都要走的路）。MinecraftForge 的事件比 NeoForge 的多一个标志 `canRefillAir`：NeoForge 的答案有回气时它是 true；没有回气时它按 MinecraftForge 自己的规则（眼睛在空气或气泡柱里，或者身上的效果不是只有鹦鹉螺之息），给出的数量是原版会回的量，除非这条规则说可以回气，那时就保留 NeoForge 的“不回”。写回去的只有 MinecraftForge 监听器改过的东西——身体能不能呼吸、失去的空气、以及能呼吸的身体回的空气（不能回气时就是 0）——所以没有任何 MinecraftForge 监听器碰过的事件，完全保持 NeoForge 监听器留下的样子（`ForgeBreath.of` 和 `carryBack`，由 `KernelBreatheBridgeTest` 实际驱动）。有了原版的回气（§6），NeoForge 的答案在原版回气的每个地方都已经有回气，所以 MinecraftForge 监听器在那里读到的 `canRefillAir` 是 true，只有鹦鹉螺之息、在岩浆或可呼吸流体里时也是，而原生 MinecraftForge 在那里读到 false、保持空气值不动。在内核之后注册的 `LOWEST` NeoForge 监听器能看到 MinecraftForge 监听器的改动，之前注册的看不到。这个事件有一行 `BRIDGED` 和一行 `ForeignType`（`LIVING_BREATHE_EVENT`）；桥唯一的开关是全局的 `-Dforbric.unifiedEvents=off`。
- **溺水** —— 同一个钩子发的是 NeoForge 的 `LivingDrownEvent`；MinecraftForge 的（在 `ForgeHooks.onLivingBreathe` 里直接构造，另外 `ForgeEventFactory.onLivingDrown` 也会构造，但原生也没有人调用它）没有任何发布者，所以停止溺水、或者改溺水伤害的 MinecraftForge mod 什么都做不了。`KernelGameWorldEvents.installDrown` 在 `LOWEST` 从 NeoForge 的事件发出它，只在 NeoForge 的答案是“身体在溺水”、并且有 MinecraftForge 监听器时才做。这正是原生 MinecraftForge 发它的地方：它的钩子在空气值降到 -20 时才发，而 NeoForge 的事件正是从这一点开始是“在溺水”的（NeoForge 从空气值 0 起就发，那时还不算溺水，这些不转给 MinecraftForge），并且交出去的永远是一个在溺水的身体；这里的伤害和气泡数用 NeoForge 的（除非有 NeoForge 监听器改过，否则就是原版的 2 和 8）。它像 MinecraftForge 的钩子那样用 `BUS.post` 发：EventBus 7 把取消放在 `post` 的返回值里，而不是事件上（`onLivingDrown` 用的是 fire，会把取消丢掉）。取消只作为 NeoForge 的取消带回去，别的都不写——原生上被取消的溺水事件别的字段一律不读；没有取消时，身体是否溺水和伤害，只在 MinecraftForge 监听器改过时才写回（`ForgeDrown.of` 和 `carryBack`，由 `KernelDrownBridgeTest` 在真实的总线上驱动）。气泡数会交给 MinecraftForge 的监听器，但从不写回：MinecraftForge 的钩子从不读它（`ForgeHooks.onLivingBreathe` bc 198–282 里没有 `getBubbleCount`），而 NeoForge 的钩子每个气泡要从身体的随机数源抽 6 次 `nextDouble`（bc 246–368），然后才向世界要粒子，服务端的世界会忽略这个粒子。写回的气泡数会改变这个身体的随机数序列——同一 tick 受伤音效的音高、之后的 AI——数值很大时还会加重服务端线程的负担，而原生上改它什么都不会发生。NeoForge 监听器取消了事件，就不再问 MinecraftForge，和这里每一个可取消的桥一样。和呼吸一样有 `BRIDGED` 行和 `ForeignType` 行（`LIVING_DROWN_EVENT`）；开关同样只有 `-Dforbric.unifiedEvents=off`。
- **审计** —— `HookCallSiteCensus`（合并基底还在调用两边钩子类里的哪些钩子，因而哪些事件从不发出），`DeadEventAudit` + `ForgeBusSubscriptions`（谁在监听死事件，包括注解扫描看不到的订阅），`DeadHookWorklist`（前两者交叉比对的结果，按会察觉到的已安装 jar 数量排序），`EventChainAudit`（每一次跨总线发布，都用同一套规则检查；gate-m41）。`HookCallSiteCensus.all` 一次判断所有钩子类：游戏里没有调用点的钩子，只要有载体方法调用它，仍算可达；但本身就是钩子的调用方（被统计的类里 public static、非合成的方法）只有在它自己可达时才算，一直推到不动点。以前载体里任何调用方都算，于是 MinecraftForge 的呼吸事件躲在没有人调用的 `ForgeHooks.onLivingBreathe` 后面。只有父类型能追到本家族事件基类的类型才算事件。内核自己编译出来的类是第四个来源：它们调用的死钩子、构造的死事件，由内核送达。`DeadEventAudit` 的各行是数据，`HookCallSiteCensusStagedTest` 拿它和这个统计对照，覆盖 `ForgeEventFactory`、`ForgeEventFactoryClient`、`ForgeHooks`、`ForgeHooksClient`、`EventHooks`、`CommonHooks` 和 `ClientHooks`：`DEAD` 行不能写基底还在发、或者内核在送的事件；每一个被测出从不发出的事件，都必须有 `DEAD`、`BRIDGED`、`REPAIRED` 或 `STAND_DOWN` 行。2026-10-24 之前只检查前一个方向，所以已经被测出是死的 MinecraftForge `LivingDrownEvent` 哪张表都没写。两个方向都卡住的只有 `DEAD`：被测出是死的事件放进 `BRIDGED`、`REPAIRED` 或 `STAND_DOWN`，不管有没有东西送达它都能通过，而这三张表审计都不点名任何 mod，所以放错表的事件又会变得无声无息（把 `PlayerXpEvent$XpChange` 从 `DEAD` 依次挪到这三张表里，测试套都是绿的）。目前这三张表里被测出是死的行，统计都算作内核送达，只有 `GatherLoginConfigurationTasksEvent` 例外，它的钩子是内核用反射调用的（`PayloadInterop`）；没有测试要求这一点。另外，合并丢掉了原生发布点的事件，只要统计仍算它会发——经由一个本身不是钩子的载体调用方，或者别处保留下来的调用点——就不需要任何一行；这样的 MinecraftForge 事件有五个，列在 §19。
  - *看实际运行的，不看暂存的。* 统计读每个游戏类时，先让它经过合并基底修复（`MergedBaseLoadTimeView` 重放 `ForbricMergedBaseCompatTransformer`；这个转换器写回时不计算栈帧，所以不需要运行中的游戏）。修复也可能拿掉钩子调用：`routePlaceItemHookToNeoForge` 把 `ItemStack.useOn` 里的 `ForgeHooks.onPlaceItemIntoWorld` 改去了 NeoForge 的 `CommonHooks`，于是 MinecraftForge 的 `BlockEvent$EntityMultiPlaceEvent`（它唯一的发布者只能从这次调用到达）运行时从不发出，而暂存 jar 里这个调用还在；只看暂存字节码时它像是会发，也就写不了任何一行。现在它有一行 `DEAD`。另有三个修复把游戏里的钩子调用挪进了内核（两边的 `initClientHooks` 经 `KernelForgeClientInit`，NeoForge 的服务端重载经 `KernelForgeReload`，MinecraftForge 的燃烧时间经 `KernelFuelValues`），所以它们发的八个事件是 `REPAIRED` 行。其他转换器不重放；碰到钩子调用的那些，要么包一层，要么转进内核，而内核的类本来就读。调用指 `invoke` 指令或方法句柄（像 `ForgeEventFactory::onPreClientTick` 这样的方法引用，同样能到达它的钩子）。反射调用仍然看不见；MinecraftForge 的配置任务手工写成一行 `REPAIRED`。`PARTIAL` 的路径数仍然按暂存字节码数：它们数的钩子里，修复只挪动了 `ForgeEventFactory.getItemBurnTime`，挪进了内核，而内核照样调用它。
  - *看合并丢了什么，不看原本就没有的。* 每个家族的钩子也拿它自己的补丁游戏（合并读的 `forge-patched` 和 `neoforge-patched` 两个 jar）统计一遍。在那里也是死的事件不写行，任何表都不能写它：`DEAD` 行会因为一个它自己的加载器也有的缺失，把 mod 标成 DEGRADED；桥则会发出原生加载器从不发的东西。MinecraftForge 26.2-65.0.1 自己从不发 `PotionBrewEvent$Pre`、`EntityTeleportEvent$ChorusFruit`、`ChunkDataEvent$Load` 和 `CustomizeGuiOverlayEvent$DebugText`——它们唯一的发布者，在它的补丁客户端和服务端、安装器产出的 jar、运行时里都没有调用方——这四个曾经当了一轮 `DEAD` 行。
  - *哪一侧。* `DEAD` 或 `PARTIAL` 行在专用服务器上判断，前提是 MinecraftForge 在那里会发它：`DeadEventAudit.CLIENT_ONLY` 列出这样的行——它在原生上的每个发布点（补丁游戏里调用能到达发布者的方法）都带着家族的客户端/服务端合并给“只有客户端 jar 才有的东西”加的 `@OnlyIn(Dist.CLIENT)`（加在方法或它的类上）；staged 测试会重新算这个集合。它取代了按包名判断的规则：那条规则让每个专用服务器都把监听 `PlayerInteractEvent$LeftClickEmpty` 的 mod 标成 DEGRADED，而这个事件虽然在通用包里，原生只在 `Minecraft` 里发。
  - *自己会发事件的 mod。* 钩子是公开 API，mod 可以自己调：Tombstone 换上自己的幻翼生成器，后者调用 `ForgeEventFactory.onPlayerSpawnPhantom`，所以在装了它的包里，合并后的游戏虽然从不发 `PlayerSpawnPhantomsEvent`，它还是会发。在为一个 `DEAD` 事件点名某个 mod 之前，`DeadEventAudit.report` 先问一遍已加载的 jar（`GuestEventPosters`，嵌套 jar 也算）：某个类调用或引用了构造这个事件的钩子——直接的，或者经过另一个钩子，从游戏类路径上的钩子类读出来——或者自己构造这个事件，就算它发了这个事件；这时审计只用一行说明，不指责任何人。只读常量池，除非常量池里出现了钩子类或这个事件；离线一次问全部 51 行 `DEAD`，在本机四个整合包上耗时 0.3–0.7 秒；启动时只在有 mod 监听 `DEAD` 事件时才跑。mod 只在它自己的路径上发这个事件，不一定是游戏的路径，所以这一步只会让审计少说，不会多说。在专用服务器上实测过：一个用订阅类监听 `PlayerSpawnPhantomsEvent` 的 mod 会被点名；旁边再装一个只调用 `onPlayerSpawnPhantom` 的 mod，它就不再被点名，那一行写明发布者是哪个 mod。启动时的这条路径只有这一次实测：`GuestEventPostersTest` 用交给它的钩子类驱动扫描，`KernelEventSubscribers` 里的调用、以及它怎么读钩子类，都没有测试覆盖（把其中任何一个断开，测试套照样全绿）。

### 8.1 每次原生分发只投递一次 —— 退让（stand-down）

桥监听 NeoForge 事件，再发布对应的 MinecraftForge 事件。一个现在在同一条路径上同时调用两边钩子的合并方法，会原生地发布 MinecraftForge 事件，*同时*桥又转发一次。在第一个编辑拼接基底上用事件链审计测量，每个 MinecraftForge mod 收到的服务端、玩家、客户端和渲染 tick，每个 level 的加载、保存和卸载，登录和登出，系统聊天，鼠标按键，雾和方块覆盖层，都是两次。单元测试和“至少一次”式的闸门检查从来没看到过它；下面的记账是按每次分发算的。

- **给原生投递划定作用域。** `transform.NativeDualEventInjector` 在每个游戏方法里找 MinecraftForge 的投递（静态调用一个方法体会构造并发布 MinecraftForge 事件的 MinecraftForge 钩子，或者内联的发布）和 NeoForge 的投递，只找有桥会重发的那些 MinecraftForge 事件。它用一个作用域（`net.forbric.api.NativeEventDelivery`）把 NeoForge 调用包起来，作用域里写明这条路径上已经原生投递的 MinecraftForge 事件：MinecraftForge 投递支配 NeoForge 投递、或后支配它的正常完成时，属于*已证明*；它可能在同一次调用里更早运行时，属于*已记录*（MinecraftForge 钩子实际构造了什么就为这次调用记下什么，所以只在某个条件下才发布的钩子，恰好覆盖它真的投递了的那几次）；它在后面、而调用方可能绕过它时，属于*忠实*。对最后这种，`NativeDeliveryPaths` 把合并后的方法体和 MinecraftForge 自己打过补丁的同一方法体（从原生参考里读）对齐，给每条决定跳过这次调用的边分类：MinecraftForge 自己的方法体也在这里跳过的（两边共有的守卫），免掉这次事件；只有合并后的游戏才有的（NeoForge 补丁加的出口），欠着这次事件，桥推迟的那次转发就在那里投递（`NativeEventDelivery.bypassed`）。调用方记下的和欠下的都属于一次调用（`NativeEventDelivery.Invocation`，存在调用方自己的一个局部变量里），所以在自己的分发过程中被重入的调用方只结算它自己那份。连锁投递也算在内：每个生态自己的监听器在它的分发过程中发布的事件（例如 MinecraftForge 自己的登录监听器发布的客户端命令注册），算作对应事件的桥已经投递过。事件按身份匹配，从不按类匹配，也不认调用方、事件或 mod 的名字。
- **桥这一侧。** `GameEventMultiplexer.guarded` 让桥拿着自己收到的事件去问 `NativeEventDelivery.covered`，并恰好对这个事件退让。`DeadEventAudit.STAND_DOWN` 逐个桥列出它的转发会构造的 MinecraftForge 事件（44 行），和死事件判定用的 `BRIDGED` 行并列；两张表里都没有的桥永远不可能退让。NeoForge 的 mod 总线分发器也算一次发布，所以两个 mod 总线上的转发（`TextureStitchEvent.Post`、`ModelEvent.BakingCompleted`）也会退让。在 NeoForge 调用旁边补一个 MinecraftForge 调用的修复，只对同一个对象、同一条路径上的 MinecraftForge 调用退让（`NativeCallPairing`），而不是只要它出现就退让。
- **不挡 mixin 的路。** 每个作用域或记录都在它所对应的那条钩子调用或发布之前打开、之后立即关闭（异常离开时也关闭），所以第三方挂在这个调用上的 `@At("INVOKE")` 仍然找得到它；每个决定第三方注入点落在哪里的适配器读到的方法都不带这些记账（`DeliveryBookkeeping`，§7.4.1）。
- **记账。** `EventChainAudit` 记下发布每个事件的游戏方法和指令，为对游戏自己的投递退让的桥记功，把游戏也投递过的转发记为投递了两次；gate-m41 要求每个必需的桥逐次发布，并按次数和 MinecraftForge 总线核对，还按结束方式核算每一次退让。无法判断的方法体（读不到 MinecraftForge 自己的方法体、NeoForge 的发布在循环里重复、不同的分支）保留它的转发。

## 9. 数据包、资源与数据

### 9.1 服务端数据

真正的加载器会遍历 `ModList` 里的 mod 文件，把每个 mod jar 变成一个包。内核往 `ModList` 发布 mod 时 `modFiles` 是空的，所以这次遍历什么也找不到。由 `DataPackHookInjector` → `KernelLifecycle.onServerDataPacks` → `KernelDataPacks` / `runtime.KernelDataPackSource` 提供每个 Forge 系 jar 的 `data/`，两个载体的也一并提供（`c:` 约定标签、`neoforge:` 伤害类型和数据映射只存在于载体里）。元数据通过 NeoForge 的 `ResourcePackLoader.readWithOptionalMeta` 读取，保留根包的覆盖层。两个载体带了同一个文件时，标签会叠加，按“后者胜出”处理的文件则由 NeoForge 胜出。Fabric mod 的数据和在 Fabric 上一样，由 fabric-api 自己的资源加载器提供。`KernelPackFinders` 让 MinecraftForge mod 能添加自己的包查找器（`-Dforbric.modDataPacks=off` 会关闭 Forge 系的包）。每个被提供的 jar 都声明它自己的平台会声明的已知包（NeoForge：对每个 mod 文件都是 `KnownPack("neoforge", "mod/<ids>", <versions>)`，载体也一样；MinecraftForge：不声明），所以 NeoForge 载体里那个数据驱动的伤害类型不再让每个世界都变成实验性的、要求玩家先备份。

### 9.2 客户端资源

NeoForge 的 `mod_resources` 来源在合并基底上是孤立的。`ClientPackHookInjector` 把 `ClientModLoader.setupModResourcePacks(PackRepository)` 的方法体重定向到 `KernelLifecycle.onClientResourcePacks`，由 `KernelClientPacks` 在第一次资源重载之前为每个生态 jar 添加一个包（兼容性强制设为 `COMPATIBLE`，两个真正的加载器也是这么做的）。`PackScreenHiddenFilterInjector` 让这些包不出现在资源包界面里。内核自己的资源（Mods 按钮图标）放在 `forbric-kernel-runtime.jar` 里。

### 9.3 同时写给三个加载器的包元数据

多加载器的 `pack.mcmeta` 为每个加载器各带一节，而在 Forbric 上，三个解析器都会读它。`KernelPackMetadata` + `PackMetadataFailSoftInjector` 防止某个解析不了的外来小节导致整个包被丢弃；`PackOverlayMutabilityInjector`（修复）和 `NullPackGuardInjector`（兜底）处理 NeoForge 合并覆盖层时去修改 fabric-api 已冻结列表的问题（两者的说明都在 `KernelPackRepair` 里）。

### 9.4 条件、注册表、数据映射、世界生成

- **资源条件** —— 有三个求值器，因为合并后的 `RegistryLoadTask` 同时带着两个系的补丁：`runtime.KernelFabricConditions`（`fabric:load_conditions`，用 fabric-api 公开的 `ResourceCondition.CONDITION_CODEC` 和 `test()` 判断；fabric-api 自己的那两个 mixin 套不上）、`KernelNeoConditions`、`KernelForgeConditions`——每个都防止一种方言让另一种方言的文件失败。`forge:condition` 只在 MinecraftForge 自己的两个读取点里判断（数据文件扫描，经由 `ForgeHooks.readConditional`；数据包注册表元素的解码器，经由 `ConditionCodec.wrap`），也就是 NeoForge 的 `ConditionalOps` 漏斗再问一次的地方，所用的依据和真正的 MinecraftForge 一样：配方按这次重载的标签判断，战利品池的条件就地判断，`forge:conditional` 的各个变体。没人注册的条件类型在数据文件里为 false，和 MinecraftForge 一样；另一个生态注册的类型交给那个生态决定；在注册表元素上，MinecraftForge 的答案会让世界打不开，所以照常加载，日志写明这个类型（`-Dforbric.forgeConditionFunnel=off`）。
- **注册表目录** —— `RegistryDirectoryOwnerInjector` / `KernelRegistryDirectories`：`registryDirPath` 的返回值与所属生态的做法一致。**别名** —— `RegistryAliasParityInjector` / `KernelRegistryAliases`。**反射** —— `RegistryWrapperAccessInjector` 在 MinecraftForge 的 `NamespacedWrapper` 和 `NamespacedDefaultedWrapper`（合并后的游戏里 block、item 等 27 个内建注册表，外加 MinecraftForge 自己的 3 个，就是它们）加载时把它们改成 public，与它们所替代的 `MappedRegistry` 一致，这样 mod 在 `registry.getClass()` 上查到的方法才能被调用（`-Dforbric.publicRegistryWrappers=off`）。`Class.getMethod` 还会解析它查找到的每一层类所声明的全部 public 方法的类型（从运行时类往上，查到第一个声明了匹配方法的类为止；`getMethods` 会查遍所有层），所以 `RegistrySyncParityInjector` 只在游戏类加载器里有这些类型时，才给 wrapper 加上 fabric-api 的 `remap(Object2IntMap, RemapMode)`（由 `RegistrySyncParityInjector.forGameLoader` 判断）。没有 fabric-api 时，这个方法引用的类不存在，`getMethods()` 以及所有查到 `NamespacedWrapper` 这一层的查找（`getOptional`、`keySet` 等）都会抛 `NoClassDefFoundError`。
- **方块 → 物品** —— `Block.asItem()` 读的是 `Item.BY_BLOCK`。在 MinecraftForge 上，这个映射就是活动（`RegistryManager.ACTIVE`）物品注册表的 `block_to_item` 映射，物品添加回调在物品加入时通过每个 `BlockItem` 的 `registerBlocks` 填它——内建物品在 `Bootstrap` 里就填好了——所以 MinecraftForge 的 `Items.registerItem` 自己不建立关联。合并后的 `Items` 取的是 MinecraftForge 的，但合并后的 `Item` 是 NeoForge 的，它的 `BY_BLOCK` 来自 NeoForge 的 `GameData.getBlockItemMap()`，于是那个回调填的是一个没人读的映射，每个方块都回答 AIR，直到注册窗口关闭——那已经在 Fabric 的 `main` 入口点之后；而 `asItem()` 会缓存第一次的结果，在那之前被问过的方块会一直是 AIR。`BlockItemLinkInjector` 让 `GameData$ItemCallbacks.onCreate` 把 NeoForge 的这个映射交给活动注册表（`KernelBlockItemLinks`；其他阶段仍各用自己的映射），这样每次添加都关联到 `asItem()` 读的地方：内建物品，经 wrapper 的 Fabric mod 的 `Registry.register` 和 NeoForge 的 `RegisterEvent`，以及直接写进 `ForgeRegistry` 的 MinecraftForge mod 的 `RegisterEvent` 和客户端上被暂缓的 MinecraftForge mod。覆盖注册会先解除被替换物品的关联，`RegistryEntryAddedCallback` 的监听器也已经能看到关联。MinecraftForge 在重新同步活动注册表时（关闭世界、读取存档或服务器的 id）会清空这个映射再把每个条目加回来；现在 `onClear` 不清空这个共享映射（Fabric 从来不清它），反正加回来时每个方块物品都会重新关联。别的代码手动放进去的条目，只有对应的方块没有任何已注册的 `BlockItem` 认领时才能保留下来；如果是把一个已注册方块重新映射到别的物品，重新同步时会被加回来的条目覆盖，和 MinecraftForge 上一样。关闭窗口时的那次扫描保留作兜底，正常情况下一个也不会补（`-Dforbric.blockItemLinks=off`）。
- **数据包注册表** —— `KernelLifecycle.registerDataPackRegistries` 发布 `DataPackRegistryEvent.NewRegistry`，声明 MinecraftForge 的生物群系/结构修改器注册表，并双向镜像 Fabric 的动态注册表。
- **数据映射** —— 会加载 NeoForge 数据映射（`KernelNeoDataMapWatch`、`KernelNeoWorldgen`）。所有经由 holder 的数据映射查询最后都落在 `Holder.Reference.getData`，它会调 `key()`，而还没注册的值没有 key，于是抛 `Trying to access unbound value`；原版的氧化、打蜡、去皮表对任何方块都能回答。`UnboundHolderDataInjector` 让未绑定的 holder 回答“没有数据”（没有 key 的值不可能出现在任何数据映射里），NeoForge 的钩子因此回落到原版的表——Fabric mod 在初始化里调用它们时期望的就是这样（`-Dforbric.unboundHolderData=off` 可关闭）。这一点和原生 NeoForge 不同：原生在任何时候都会在这里抛错。值所属的注册表还开着时，这个回答是静默的；一旦该注册表的任一 `frozen` 标志被置上（它的 `freeze()` 已经跑过，而 `freeze()` 遇到这样的值会抛 "Some intrusive holders were not registered"），`util.KernelUnboundHolderData` 就对每个这样的值 WARN 一次（最多 64 条，之后再打一行汇总），写明值、注册表和发起查询的调用栈——原生 NeoForge 会抛的那个错仍然被报告出来，只是查询照样回答“没有数据”。这条 WARN 说的是“注册表关闭时它还没注册”，不是“它永远注册不进去”：注册表可以被 unfreeze，Forbric 也会为 Fabric 客户端 entrypoint 重新打开注册表。
- **世界生成** —— MinecraftForge 的生物群系/结构修改器搭在 NeoForge 仅有的那一轮修改器处理里运行（`runtime.KernelForgeWorldgen`；`-Dforbric.forgeWorldgen=off` 可以关闭，此时 `ForgeWorldgenShippers` 会点名因此失去这部分功能的 mod）。`NativeCoremodParity`、`BiomeInfoRebaseInjector`、`BiomeLateWriteInjector` 让修改后的视图能被读到。gate-m31 要求零 mod 时的世界生成在相同种子下，生物群系和结构起点与原版一致。

## 10. 统一 API —— `net.forbric.api`

固定交给父加载器（每个 JVM 只有一份），是内核和全部三个兼容层共用的语言，免得两两互相翻译：

| 类型 | 作用 |
| --- | --- |
| `Ecosystem`、`Side` | 唯一一套生态与端（side）词汇 |
| `ForeignType` | 74 行，每行把一个 Forge 系概念映射到它在 MinecraftForge 和 NeoForge 中的类名——只有名字；各系之间的差异仍以数据表示 |
| `DiscoveredMod`、`UnifiedDependency`、`VersionPredicate`、`ModIds` | mod 与依赖模型 |
| `ModPresence`、`ModCatalog` | “X 是否在运行”，以及玩家看到的列表，状态为 `OK` / `DEGRADED` / `FAILED` |
| `GameEventBridge`、`EventBridges` | 桥清单及其校验 |
| `NativeEventDelivery` | 当前路径上游戏自己正在投递哪些 MinecraftForge 事件，好让桥退让（§8.1） |
| `ForgeLoadingList` | MinecraftForge 的 `LoadingModList` 据以构建的数据 |
| `CompatibilityFinding`、`CompatibilityFindings` | 每次启动的证据账本（§12） |

它不是给 mod 用的稳定 API。

## 11. Mods 界面

`runtime.KernelModListScreen` 取代两个系各自的 mod 列表界面（`ModsButtonRedirector`），读取 `ModCatalog`；`ModCatalog` 由 `KernelModCatalog` 根据发现结果填充，并再读一遍每个 jar，取出描述、作者和 logo。每个 `ModCatalog.Entry` 都带有 `status` 和 `statusDetail`；如果它是以内嵌 jar 的形式带进来的，还会写明是哪个 mod 自带了它。
`KernelModConfigScreens` 负责打开 mod 自己的配置界面。

Fabric mod 的配置界面只在一个地方声明：一个实现 Mod Menu 的 `com.terraformersmc.modmenu.api.ModMenuApi` 的 `"modmenu"` 入口点。这个接口属于 Mod Menu 这个 mod，所以没装 Mod Menu 时，入口点类连链接都过不了。找到已安装的 Mod Menu 时照旧问它。否则由 `boot.ModMenuApiStandIn` 把这五个 API 类型及其默认值返回的 `util.NullScreenFactory` 的替身交给 `ForbricClassLoader.putGeneratedClass`（与 Mod Menu 20.0.3 的公开形状完全一致，从 `src/modmenuApi/java` 编译，以 `.class.bin` 资源的形式放在游戏侧 jar 里）。加载器只在所有自有 jar 都找不到这个类之后才定义这些字节，所以真正的 Mod Menu 仍然优先。只在客户端、只有 API 这一个包外加那一个类：`isModLoaded("modmenu")` 仍然是 false，Mod Menu 的其余内部类也仍然不存在。随后 `fabric.ModMenuConfigFactories` 按 Mod Menu 初始化时的方式读取这些入口点：先读每个 mod 自己的工厂：如果它是接口默认值所返回的那个类的实例就跳过（即 Mod Menu 的 `instanceof NullScreenFactory`，所以重写后又退回默认值的 mod 不会出现一个按了没反应的 Config 按钮，问"有没有"时也不需要构建界面）；再用 `putIfAbsent` 合并每个入口点的 `getProvidedConfigScreenFactories()`，和 Mod Menu 一样每次查询都重新合并。坏掉的入口点会被跳过。`-Dforbric.modMenuStandIn=off` 恢复旧行为：Fabric mod 只有装了 Mod Menu 才有 Config 按钮。

列表的行由 `ui.ModListRows` 决定（启动侧、纯逻辑，`KernelModConfigScreens.rows()` 喂给它）：目录里每个装进来的 jar 各占一行，再加上所有只能经由一行才能打开的配置界面。排行这一步对同一个 id 的两条已安装条目会留两行，但目录根本不会交给它两条：`ModCatalog.publish` 先按 id 合并重复（先到的赢），而 `KernelModCatalog` 是在所有加载成功的 mod 之后才追加读不了元数据的 jar（按它失败信息里的 id 进目录）。所以一个读不了的 jar 只要和一个正常加载的 jar 同 id，它就没有自己的行，它的发现反而显示在那个正常 jar 的行上（`CompatibilityFindings.project` 按 id 匹配）——这是一条还开着的缺陷：目录的身份是 mod id，不是 jar。被别的 jar 带在里面的 mod，当且仅当它自己有配置界面（`!installed() && has()`）时才有一行，位置和 Mod Menu 一致：挂在它自己在 `custom.modmenu.parent` 里声明的父级下面（可以是一个 id；也可以是一个对象，若这个 id 在实例上不是任何 mod，就用对象里的名字做一个 `GROUP` 标题行），沿父级一直找到最顶层；没有声明就放在顶层——不挂在携带它的 jar 下面，Mod Menu 不按 jar-in-jar 分组，被两个 mod 同时带着的库也没有唯一的携带者。自己没有配置的被携带 mod，只在它被某个有配置的被携带 mod 声明为父级时才有一行，详情栏也按这个原因写。装进来的 jar 不管声明了什么都留在原位。平台行：游戏本身（`minecraft`；配置是 Fabric 协议对这个 id 的回答——装了 Mod Menu 时就是 Mod Menu 自己的工厂——否则是原版 `OptionsScreen`），以及某个生态的注册表里有、目录里没有、并且在那个生态里有配置界面的容器（NeoForge 上就是 NeoForge 自己的设置）。这种加载器自己的行，只有当目录（装进来的和被携带的 mod，各按赢得它的生态算）里有这个加载器所服务的 mod 时才列出（`ModListRows.serves`）：NeoForge 自己的行（Config 打开 NeoForge 的设置界面）在实例里有 NeoForge mod、MinecraftForge mod（两者跑在同一个打过 NeoForge 补丁的基底上），或者被仲裁给其中之一的通用 jar 时出现；纯 Fabric 整包——包括被 Fabric 赢走的通用 jar——没有这一行。NeoForge 本身在每个实例里照样运行，也照样读它自己的设置文件。这一行的名字取自容器自己的显示名；内核的 NeoForge 基线容器（构造 `NeoForgeMod` 用的那个）现在按 NeoForge 自己的 `neoforge.mods.toml` 为它自己声明的 `[[mods]]` 条目来描述，从运行时 jar 里读（`boot.CarrierOwnMod`）：显示名 `NeoForge`、NeoForge 的版本和描述；以前给的是 id（`neoforge`）和 `0.0`。所以凡是读 `ModList.getModContainerById("neoforge").getModInfo()` 的，拿到的都和真正 NeoForge 一样。没有任何运行时 jar 声明它时，名字退回到生态自己的名字（`NeoForge`）。内核构建的 NeoForge `IModInfo` 现在对每个 mod 都从它自己的 `[[mods]]` 条目回答 `getDescription()`，不再一律是 `""`（没声明时仍是 `""`；真正 NeoForge 这时给的是 "MISSING DESCRIPTION"）。列表的 `setSelected` 会通知界面，所以不管什么移动了选中项，Config 按钮都跟着变；测过的是方向键和鼠标，Tab 没测。`ModMenuProtocolExtension` 对“为实例上不存在的 id 代供的工厂”记一次日志。另一个方向是给 Mod Menu 自己的列表用的：客户端上 `fabricloader` 声明内核自己的 `"modmenu"` 入口点（`runtime.modmenu.KernelModMenuPlugin`，单独的源码集，对着替身 API 编译，`KernelRuntimeClasses.Origin.PACKED`），它代供的工厂把“由别的生态加载、Fabric 只当作存在身份持有”的每个 id 映射到那个生态自己的配置界面（最多每 2 秒重建一次）。它对所有读 `"modmenu"` 入口点的东西都提供——Mod Menu、它的分叉、内核自己的替身读取器（在那里无害：内核只对 FABRIC 条目问 Fabric 协议）——所以 Mod Menu 的列表里，被 NeoForge 赢走的通用 jar、以及每个有配置的 NeoForge / MinecraftForge mod 都有 Config 按钮；`-Dforbric.modMenuForeignScreens=off` 时什么都不提供。Fabric 构建输掉的存在身份，按那份输掉的 `fabric.mod.json` 展示（名字、描述、作者、联系方式、图标：`KernelModMetadata.presenceShownAs`），以前在 Mod Menu 上是一行没有名字、没有图标的 id：通用 jar 的 Fabric 半边和嵌套的输家来自 discovery；顶层 Fabric jar 输给别的生态的 jar 时，直接从那个 jar 里读（discovery 从不读被压制的 jar）；声明的图标如果运行中的 jar 里没有，就从声明它的那个 jar 里读（`KernelModContainer.presence(metadata, loadedFrom, shownFrom)`）。

gate-m56 实际测到的（四个阶段都是必需的；缺 Mod Menu 或 fabric-api 时 SKIP-FATAL、exit 3；用 `M56_PHASES` 只跑一部分时也是 exit 3，绝不是 0）：不装 Mod Menu 时，金丝雀经 Config 按钮打开——两个 Fabric mod 自己的入口点、一个由别的 mod 代供的工厂、两个被携带的 mod（一个在顶层，一个挂在它声明的标题下，位置读自打开的界面里列表控件实际持有的行，并给界面截图）、通用 jar 的 NeoForge 界面、游戏的 `OptionsScreen`，以及 NeoForge 自己的行（因为这一组里有 NeoForge mod 才列出，名字是 `NeoForge`，打开 NeoForge 的 `ConfigurationScreen`），9 行里恰好 8 行打开；只放 Fabric 金丝雀（没有 NeoForge 或 MinecraftForge mod）时，列表控件持有的行里没有 NeoForge 行，游戏行照样能打开；无配置金丝雀的按钮隐藏；所有选择和按下都经过 `KeyboardHandler.keyPress` / `MouseHandler.onButton`（从答案相反的行按方向键过来再按回去，其中两次是从没有配置的 mod 到有配置的 mod；单击；每个有配置的行都双击一次）；以及 Mod Menu 的 API 类定义时带的代码来源（没有代码来源，游戏加载器读的 jar 里也都没有它，内核的替身字节在；`-Dforbric.modMenuStandIn=off` 这个对照下则解析不到）。装了 Mod Menu 20.0.3 时，问的是 Mod Menu 的 API：`ModMenu.hasConfigScreen`（它列表里的 Config 按钮读的就是这个）、`ModMenu.MODS` 条目的名字和描述（通用金丝雀的 `neoforge.mods.toml` 里名字不同，读成 NeoForge 的名字就会红）、`ModMenu.getConfigScreen` 建出 NeoForge 的界面；图标是探针自己按 Mod Menu 的查找方式在 Fabric 容器里找到的，不是 Mod Menu 给的答案；游戏行和一个 Fabric 金丝雀的 Config 按钮各恰好经过 Mod Menu 自己的工厂一次、通用金丝雀的则没有；`-Dforbric.modMenuForeignScreens=off` 作为对照。没有任何闸门测到的：Mod Menu 自己的列表界面和真去按它的 Config 按钮、除这个金丝雀以外的 NeoForge / MinecraftForge mod 在 Mod Menu 里的样子、“代供工厂无对应 id”的日志、2 秒刷新、Tab、只有 MinecraftForge mod 或只有被携带的 Forge 系 mod 时的 NeoForge 行（只有 `ModListRowsTest`；通用 jar 经仲裁器两种结果见 `LoaderRowCatalogueTest`）、顶层 Fabric jar 输给别的生态的 jar（只有 `FabricAliasDisplayTest`，用的是仲裁器真实给出的决定）。

## 12. 兼容性报告与策略

### 12.1 证据

`net.forbric.api.CompatibilityFinding(id, modId, feature, source, confidence, required, detail, evidence)`；`Confidence` 取值为 `SUSPECTED`、`CONFIRMED` 或 `RESOLVED`。只有 `CONFIRMED && required` 能阻止启动；`RESOLVED` 只作为证据保留，从不给 mod 打标记。`CompatibilityFindings` 是每次启动的账本，引导代码、游戏界面和发布检查共用它。写入方包括：依赖审计（`DependencyAudit`，跨生态——没有哪个解析器会处理合在一起的整个集合）、仲裁、Mixin 预检/应用、缺失的桥、延迟任务失败、§3.2 的静态审计、`KernelTransferInterop`，以及服务端/客户端的后期检出项。

### 12.2 文件 —— 都在 `<gameDir>/.forbric-kernel/` 下

| 文件 | 何时写入 |
| --- | --- |
| `load-report.txt` | 在进入游戏前的边界处作为证据写一次，初始化生命周期结束后再写一次，`ServerStartedEvent` 时（世界已就绪；内置服务器也会发布这个事件）以及后期检出项到来时还会再写（`-Dforbric.loadReportRewrite=off` 只保留第一次写入）；如果加载始终没有完成，由关闭钩子写入。使用系统语言 |
| `compatibility-report.json` | 与它放在一起，机器可读的检出项 |
| `merge-report.txt` | 两个 jar 声明同一个 mod id 时（§4.3） |
| `crash-analysis.txt` | 生成崩溃报告之后：调用栈指向哪些 mod，调用栈经过的方法上有 mixin 重叠时同时点名双方（`CrashAttribution`，§7.6；`-Dforbric.crashAnalysis=off`）。Forge 的 `Suspected Mods:` 那一行依赖一个模块层，而内核不构建这个模块层 |
| `crash-suspects.json` | 与它放在一起：`{schema:1, report, clash, suspects:[{modId,name,jar,reason,depth}]}`。下一次客户端启动时，在仲裁之前，`CrashSuspectOffer` 提出不加载这些 jar 启动（冲突时保留第一个被点名的一方），选“不加载启动”就把它们追加进 `<gameDir>/forbric-disabled.txt`；无论怎么回答，都把文件改名为 `crash-suspects.offered.json`。服务器、无显示环境和 `-Dforbric.dependencyDialog=off` 只在日志里写出这些行 |

同一位置还有几个工作目录：`lib/`（解压出来的自带 jar）、`jij/`、`jarjar/`、`candidates/`。

### 12.3 依赖对话框

`ui.DependencyDialog` 向玩家显示未满足的硬依赖和跨 mod 的 mixin 失效。这个窗口是一个**独立的 JVM**（`DependencyDialogMain`，启动时 classpath 里只有内核 jar 这一项），因为在 macOS 上游戏带着 `-XstartOnFirstThread` 运行，AWT 无法和 GLFW 共用第一个线程。父子进程之间只共享 `DependencyReport` 里的制表符分隔文件格式；文案在 `DialogLang` 里（跟随系统语言，`-Dforbric.dialogLanguage=<code>` 可强制指定一种）。`-Dforbric.dependencyDialog=on`（默认）| `off` | `dryRun`（派生真正的子进程，但禁用 AWT——闸门断言的就是它）。带 `-Djava.awt.headless=true` 启动的子进程画不出窗口，给出的正是它被启动来给的答案，不报失败；没被要求却处于 headless 的子进程（比如不在窗口会话里启动的 macOS 客户端）仍然报失败。子进程 10 分钟后超时。确认窗口的退出码：`0` 继续，`4` 退出或关掉窗口，其他任何值（`3` 画不出窗口、启动器自己的 `1`）表示窗口没能让人回答。同一个子进程还有第三种窗口 `--isolation`，即 §12.2 的崩溃嫌疑提示：退出码 `2` 表示不加载它们启动；除了那两个明确的按钮，其他任何情况都按加载全部 mod 启动处理。

### 12.4 策略 —— `-Dforbric.compatibilityPolicy`

`ui.CompatibilityDecision.policy()`：

| 值 | 已确认的必要功能缺失…… |
| --- | --- |
| `ask`（默认） | 在对话框里询问玩家一次（依赖提示也并入其中）；只有明确选择“继续启动”才算批准，选“退出”或关掉窗口算拒绝。客户端上对话框弹不出来时——`java.home/bin/java` 不能执行（FCL 这类安卓启动器）、子进程起不来或画不出窗口、10 分钟没人回答——问题改到游戏里问：不批准任何东西，由 `KernelCompatibilityPrompts` 在标题界面用对话框同样的按钮询问，选“退出”就停止游戏。启动参数要求直接进世界（`--quickPlaySingleplayer`/`Multiplayer`/`Realms`：世界会在游戏能问之前就加载）或设了 `-Dforbric.dependencyDialog=off` 时，照旧不批准。专用服务器没人可问 → 不批准 |
| `continue` | 接受并记录在案；提示仍可能显示 |
| `strict` | 阻止启动；不显示任何窗口 |
| 其他任何值 | 按 `strict` 处理（失败时按拒绝处理） |

这个决定会在进入游戏前的边界处询问一次（§3.2 第 17 步），加载结束时再问一次（服务端在 §3.4 第 3b 步，客户端在 `fireClientSetupLifecycle`），专用服务器还会在 `ServerStartedEvent` 时再问一次（世界加载期间出现的必要功能失败会让服务器按正常流程停机）。拒绝时抛出 `CompatibilityDecision.LaunchStopped`；`CompatibilityLaunchBoundary` 把它转成退出码 **78** 并打印报告路径；在 `Minecraft.<init>` 内部则通过 `SilentInitException` 退出，因此不会作为崩溃上报。启动之后才出现的检出项从不派生 Swing 进程，也不会退出 JVM：在服务端，它们在一个 tick 完成后的边界处处理（`LateServerCompatibility`）；在客户端，则在渲染线程的某个 tick 上由一个原生 Minecraft 界面处理（`KernelCompatibilityPrompts`、`KernelCompatibilityScreen`）。安装好的版本配置不传任何 JVM 参数，所以玩家得到的是 `ask`；`run/launch-kernel-{client,server}.sh` 默认用 `strict`（客户端脚本还默认 `-Dforbric.dependencyDialog=off`）。

### 12.5 内核吞下继续运行的失败 —— `util.Caught`

内核是容错设计：形状对不上的修复会放弃，另一侧缺失的桥会停用，一个 mod 的监听器抛异常不会连累其他 mod 的监听器。这些都是 `catch`，而 `src/main` 和 `src/runtime` 里的每个 `catch` 要么重新抛出，要么上报给 `Caught`，并且上报必须是 catch 体自己的一条语句（不能放在 `if` 下面）。`EveryCatchIsVisibleTest` 扫描源码，遇到任何其他写法就失败，没有白名单。

- `Caught.warn/error(t, format, args)` —— 内核吞下并继续运行的失败。以对应级别记录，带 `[Forbric/Caught]` 前缀和 catch 所在位置（`Class.method:line`）：某个位置第一次出现时打完整堆栈，之后每次一行。同一位置的同一条消息打印 20 次（`-Dforbric.caught.printedPerSite`）后只计数；该位置没说过的新消息总会打印（每个位置最多 100 种），所以一个转发函数仍然会点名每一个失败的事件。反射的 `InvocationTargetException` 会先剥掉再打印。
- `Caught.expected(t)` —— 这个异常本身就是答案：可选类的存在性探测、识别器的“不是这个形状”、mod 写的文本不是合法值、作为值交给负责上报的代码的异常。只计数，只在 `-Dforbric.debug` 下记录。如果一个“expected”异常的 cause 链里有只有 bug 才会抛的异常（`NullPointerException`、`ClassCastException`、ASM 分析帧之外的越界……），且是 Forbric 自己的代码抛的，就改按失败上报：ASM 的 `Analyzer` 会把解释器抛出的任何异常包进识别器本来就预期的那个 `AnalyzerException` 里。`Caught.Invariant` 同理：注册表发现同一个 key 下已经有一份不同的值时，一律抛它（`RegistryConflictsAreInvariantsTest` 保证这一点）。修复是在自己那个“形状不符”的 catch 里注册的，普通的 `IllegalStateException` 在那里会被悄悄当成放弃。

靠抛异常来放弃的识别器，要给“放弃”一个自己的异常类型（比如一个私有的 `Unproven` 类），先把它按 `expected` 接住，其余异常再走 `warn`：识别器故意抛的 `IllegalArgumentException` 和 bug 抛的，在同一个 catch 里分不出来。

加载结束时和 JVM 退出时各记录一次汇总（`[Forbric/Caught] summary …`，每个失败位置一行，带次数）。用 `FORBRIC_CAUGHT_FILE`（或 `-Dforbric.caught.file`）指定一个文件后，每个失败位置第一次出现时也会追加写进去，退出时再写入次数。

单测和闸门都会读它。`CaughtErrorsExtension`（通过 META-INF/services 对每个单测生效）会让测试期间内核吞下过失败的测试失败，除非测试用 `@ExpectCaught("<失败消息里的文字>")` 说明这是它故意造成的；标在方法上时，列出的每个失败还必须真的发生。`run/lib.sh` 给每个闸门分配自己的 `build/caught/<gate>.<pid>.tsv`，导出给闸门启动的每个 JVM，退出时打印 `[kernel] CAUGHT …`，列出每个位置、消息和次数；`FORBRIC_CAUGHT_STRICT=1` 会让其中任何一条把闸门变红。闸门里再启动的闸门，通过外层闸门汇报：m33、m35、m37、m38、m39、m40 启动服务端的方式是在 `evidence.py` 下把自己再跑一遍，这个内层运行会连同 `FORBRIC_CAUGHT_GATE`（属主的名字和 pid）一起继承这个文件，往里写，自己什么都不打印；属主已经不在的遗留变量、或者没有属主却被导出的文件，都不算继承（`LibCaughtContractTest`）。以前每个内层运行都另开一个文件，把自己的 CAUGHT 行打进外层闸门留作证据的服务端日志里，外层闸门则打印 `CAUGHT none`——而 gate-m39 的服务端每次运行都会撞上一个 NPE（main f7f4ff92 上也一样；见 §5 的传输归属）。gate-m34 改为等待 `soak-run.py` 结束而不是 exec 成它，所以它的 EXIT trap 照样会跑；soak 客户端（一个 JVM：客户端加上它打开的每个集成服务端）把被吞下的失败写进本次运行的 `evidence/caught.tsv`，`soak-run.py` 再把它追加进闸门的文件，并记进 `acceptance.json`。

## 13. 安装器 —— `forbric-kernel-installer/`

纯 JDK 实现，没有依赖，字节码 release 17，版本 `0.3.1-beta2`。

```
java -jar forbric-kernel-installer.jar                     # window (InstallerGui)
java -jar forbric-kernel-installer.jar --dir DIR [options] # headless install
    --mc 26.2  --artifacts DIR  --jdk PATH  --remote  --release TAG  --mirror PREFIX  --offline
java -jar forbric-kernel-installer.jar --doctor [--dir DIR] [--jdk PATH]
```

### 13.1 在玩家的机器上构建游戏产物

`ArtifactBuilder.build` 在 `<mcDir>/.forbric-build/` 下运行，从第一个未完成的步骤接着做：

```
forge userdev ─┬→ forge-runtime ───────────────┬→ patched-mc-forge ─┐
               ├───────────────────────────────┘                    │
               └→ (source patches: edit provenance) ────────────────┤
neoforge userdev ─┬→ neoforge-runtime ──────────────────────────────┤
                  ├→ NFRT → patched-mc-neoforge ────────────────────┼→ patched-mc-merged
                  └→ (source patches: edit provenance) ─────────────┤
vanilla 26.2.jar ───────────────────────────────────────────────────┤
versions/26.2/26.2.json's libraries (fetched only if a tool runs) ──┘
forge-runtime ────────────────────────────────→ forge-runtime-interop   (what is staged)
```

- `ForgeRuntimeBuilder`、`PatchedMcBuilder`（Forge 的 `installertools`/`mergetool`/`binarypatcher` 作为子 JVM 运行，Forge 的 `AccessTransformerEngine` 在进程内运行）、`NeoForgeRuntimeBuilder`、`NfrtRunner`（NeoFormRuntime，取的结果是 `gameJarNoRecomp`：只打二进制补丁，不用反编译器，也不用 `javac`）。
- `MergedBaseTool` 从安装器的资源里解出 `forbric-merge-tools.jar`，依次运行 `net.forbric.tools.MergedBaseBuilder`（`-Xmx4g`）、`RuntimeInteropPatcher`，然后对照打包在安装器里的已审核基线运行 `MergedLinkChecker`。**只要链接检查报告的不是 `new 0`，安装就会失败。** 合并会拿到两个 userdev 产物（它们的源码补丁就是 §2.4 说的编辑出处），以及基础版本 JSON 为本平台列出的那些库，按 classpath 顺序，绝不是整个 `libraries` 文件夹：它的字段证明要顺着构造过程追进这些库。只有当合并或互操作补丁发现自己的输出过期、真的要运行时，才会核对这些库并下载缺失的，所以完全从缓存重装的安装在离线时也能完成。
- `--artifacts DIR`（窗口里的 “Built artifacts (leave empty)”）只供开发者使用，用来跳过构建。`GameArtifacts` 只从这一个目录取这三个 jar，并在下载或写入任何东西之前逐个打开检查：合并基底必须是 Minecraft 26.2，且它 `net/minecraft/` 下的类同时引用 `net/minecraftforge/` 和 `net/neoforged/`；每个运行时都必须带着本生态的核心类和它的 mod 加载器（`FMLLoader`、`IModInfo`），并且清单主段的 `Implementation-Version` 必须是锁定的版本（`Pins.NEOFORGE`；MinecraftForge 则是 `Pins.FORGE` 的 FML 部分，即 `65.0.1`）；MinecraftForge 运行时还必须是打过互操作补丁的那个，即其中的 `NamespacedWrapper$3` 声明了 `contents()`。这之后才做链接检查——单靠链接检查，任何不引用自身以外任何东西的 jar 都能通过（issue #13）；提供的一组文件没通过链接检查时，报告为这些文件彼此对不上，并给出同样的出路。
- `Pins`：`MINECRAFT = "26.2"`（唯一支持的版本）、`FORGE = "26.2-65.0.1"`、`NEOFORGE = "26.2.0.88"`、`NFRT = "2.0.18"`、`NFRT_RESULT = "gameJarNoRecomp"`，每一项的理由都写在源码里。`BuildStamp` 让每个缓存产物都以整组锁定版本为键，所以锁定版本一升级，就不可能沿用缓存里的旧产物。
- `JdkLocator` 要求构建工具用 Java ≥ 21（NeoFormRuntime 的 class 文件版本是 65）；它依次尝试当前运行的 JVM、启动器的运行时、系统里的 Java，从不下载 JDK。游戏本身需要 Java 25。

### 13.2 版本配置

`Installer` 写出 `versions/26.2-forbric/26.2-forbric.json`：

- `inheritsFrom: "26.2"`，`mainClass: net.forbric.kernel.boot.KernelClientLaunch`，没有 JVM 参数；
- 游戏参数 `--gameJar <merged>`、`--runtimeJar <forge-runtime><sep><neoforge-runtime>`（两者合在一个参数里）、`--libraryPath <every vanilla library for this platform>`；
- `libraries`：自带的 Forbric jar 和内核的第三方依赖（取自内核自己的 `printBootClasspath`），再加上三个游戏产物，分别暂存为 `net.forbric:patched-mc-merged`、`net.forbric:forge-runtime`、`net.forbric:neoforge-runtime`；
- 一个 `forbric` 块，只是元数据，声明了 `net.fabricmc:fabric-loader:0.19.3`。有些启动器靠搜索 JSON 文本来识别加载器，这样它们就会把这个实例当作装了 mod 的实例（并给它单独的 mods 文件夹）。

三个生态的 mod 都放进 `<mcDir>/mods`（启动器开了版本隔离的话，则放进 `versions/26.2-forbric/mods`）。

### 13.3 Forbric 自己的 jar 从哪里来

默认构建会自带这些 jar（`bundleForbric` 写出带 SHA-1 的 `forbric-kernel-libraries.json`）。`-Pslim` 构建一个都不带，安装时再通过 `RemoteSource` 获取：Forbric 的 jar 取自 GitHub 发布版，其他库优先从 Maven 获取。`-PreleasePin` 把 `forbric-release.properties`（tag、仓库、`manifestSha256`）编译进 jar。清单的摘要就是信任锚，所以发布要分两步（先 `releaseAssets`，再做锁定后的 slim 构建）。只有 `run/compat/evidence.py release-check` 确认内核 jar 和 merge-tools jar 与已验收的候选版本逐字节一致，`releaseAssets` 才肯写出发布资产。

### 13.4 `--doctor`

`Doctor.examine` 不写任何东西、不建任何东西、不下载任何东西，只报告：平台、找到的 JVM、基础版本是否已安装、锁定版本、哪些产物已经存在或将会构建、预计占用的磁盘空间，以及一行结论。

## 14. `forbric-loader/` 还有什么用

- **合并工具。** `forbric-loader/src/tools/java/net/forbric/tools/` 下的 `MergedBaseBuilder`、`EditSplicer`、`PatchLineProvenance`、`MergedLinkChecker`、`RuntimeInteropPatcher`，以及 `AdditiveMethodMerger`、`MergeabilityCensus`、`LostHookAttribution`、`EffectiveHookEvidence`，由 `:mergeToolsJar` 构建成 `forbric-merge-tools-0.1.0.jar`，安装器自带并运行这个 jar。已审核的链接基线是 `forbric-loader/src/test/resources/merge/link-check-baseline.txt`；合并丢失棘轮是 `MergedBaseHookLossRatchetTest`（§2.4）。
- **开发者流水线。** `run/build-patched-forge.sh`、`assemble-minecraftforge-runtime.sh`、`assemble-neoforge-runtime.sh`、`build-merged-base.sh`、`check-merged-links.sh` 在 `forbric-loader/run/{merged-base,forge-runtime,neoforge-runtime}/` 下生成暂存产物；内核的游戏侧对着它们编译，每个闸门也都在它们上面运行。`FORBRIC_OLD`（或 `-Pforbric.stagedRoot`）可以让另一个工作树指向这些产物。`build-merged-base.sh` 在 jar 旁边写出合并报告（`merge-conflicts.txt`）和一份构建记录（`patched-mc-merged-26.2.jar.provenance.json`：工具源码哈希、输入哈希、jar 和报告的哈希）。提交进仓库的 `run/merged-base/merge-conflicts.txt` 是现在的工具构建的那份基底（就是 `run/merged-base/` 里它旁边那份）的报告，同时是测试夹具（§2.4）。
- **测试输入。** `run/livemod-src*`/`testmod-src` 里的金丝雀 mod 源码（`build-testmods.sh`），以及它各个运行目录里的 mod 集合，供 gate-m0 的发现对照基准读取。
- **安装器载荷。** 安装器的打包清单里仍有 `net.forbric:forbric-loader` 和 `net.forbric:forbricruntime`，所以安装好的版本配置会把它们列为库。内核代码没有提到任何 `net.forbric.loader` 类，唯一的引用是 §6 里的重定向。

`forbric-loader/` 自己的引导路径（Knot 宿主，加上 `bootstrap.sh` 打上的八个 fabric-loader 底座补丁）内核并不使用。焊接方案的设计记录在 `forbric-loader/README.md` 和 `forbric-loader/run/README.md` 里。`forbric-installer/` 是焊接方案的安装器，不是发布出去的那一个。

## 15. 仓库结构

```
Forbric/
├── README.md                      player-facing, describes the latest release
├── introduction.md                this document, describes main
├── LICENSE, NOTICE
├── bootstrap.sh                   clones ./fabric-loader for forbric-loader (not needed by the kernel)
├── MOD_TEST_FAILURES.md           per-mod compatibility results (Chinese)
├── .github/workflows/build.yml    CI: job `build` (bootstrap + forbric-loader) and job `kernel`
│
├── forbric-kernel/                THE KERNEL — own Gradle build and wrapper
│   ├── build.gradle               boot jar (release 21), runtimeJar, transferTest, staged-artifact wiring
│   ├── gradle.properties          ASM, sponge-mixin, MixinExtras, SAT4J, NightConfig … versions
│   ├── src/main/java/net/forbric/api/          the unified API (25 files)
│   ├── src/main/java/net/forbric/kernel/       boot (107), transform (152), mixin (166), fabric (15),
│   │                                           metadata (12), access (7), classloading (10), discovery (5),
│   │                                           interop (23), ui (6), util (13), mapping (3), soak (2)
│   ├── src/main/java/net/fabricmc/             vendored Fabric API surface (47 files)
│   ├── src/main/resources/                     Mixin service registrations; mixin census tables
│   ├── src/runtime/                            GAME side, net.forbric.kernel.runtime (+ soak/, transfer/)
│   ├── src/test/, src/transferTest/            unit tests; transfer-engine tests
│   ├── canary/                                 canary mods the gates build and load
│   └── run/                                    gate-m*.sh, launch-kernel-{client,server}.sh, lib.sh,
│                                               diff-oracle.sh, mixin-inventory.sh, merge-packs.sh,
│                                               build-*-canary.sh, compat/ (sweep and evidence tooling,
│                                               rename-mod.py: the rename experiment, §16.1)
│
├── forbric-kernel-installer/      THE INSTALLER — src/main/java/net/forbric/installer/kernel/, packaging/
│
├── forbric-loader/                first generation; merge tools (edit splice) + artifact pipeline (§14)
├── forbric-installer/             the first generation's installer
└── fabric-loader/                 gitignored upstream checkout for forbric-loader
```

## 16. 构建与测试

目前的开发入口是 `python3 tools/dev.py client`（Windows 上用 `py tools/dev.py client`）。它借用安装器的产物流水线，在 `forbric-kernel/.dev/` 下准备一套隔离的游戏输入，解析库/资源和锁定的编译 API，然后构建并启动当前的内核。需要 JDK 25+ 和 Python 3.9+。Gradle 提供 `prepareDev`、`runClient`、`runServer` 和 `devDoctor`；准备和启动必须分成两次 Gradle 调用，因为游戏侧的接线在任务运行之前就已配置完毕。命令和配置见[开发指南](forbric-kernel/run/README.md)。

`check` 还会运行开发/证据工具的自测，以及打包链接闸门的合成控制。`integrationTest` 要求暂存好的游戏和传输测试套件齐备，并且不允许任何测试被跳过；普通的 `test` 仍允许本地测试夹具缺失，并打印已执行/已跳过的数量。完整的集成测试套件除了基础游戏，还需要它指名的那些 mod 测试夹具；游戏准备好了，并不等于宣称每个兼容性包或真实实例闸门都已经跑过。测试期间内核吞下失败继续运行的单测会失败，除非标了 `@ExpectCaught`；每个闸门结束时都会打印一行 `[kernel] CAUGHT`（§12.5）。

```sh
cd forbric-kernel
./gradlew --offline jar        # boot jar; nests forbric-kernel-runtime.jar only when the staged artifacts exist
./gradlew --offline test       # unit suite (depends on compileRuntimeJava)
./gradlew --offline check      # + transferTest (real Fabric/NeoForge transaction engines)
./run/gate-m0.sh               # build + suite from the JUnit XML + scan + link check + discovery oracle
java -cp <boot-cp> net.forbric.kernel.boot.Main --scan --mods <dir> --report out.json
```

- **暂存产物。** 游戏侧编译时依赖 `forbric-loader/run/merged-base/patched-mc-merged-26.2.jar`、`…/forge-runtime/forge-runtime.jar`、`…/neoforge-runtime/neoforge-runtime.jar`，外加取自本地 Minecraft 安装的 brigadier、datafixerupper 和 gson，传输模块要用的一个 fabric-api jar（`-Pforbric.fabricApi`），以及按 SHA-256 锁定的 Team Reborn Energy 5.0.0（`run/energy-api/energy-5.0.0.jar` 或 `-Pforbric.rebornEnergy`）。没有暂存的 jar 时，`compileRuntimeJava` 会被跳过，`jar` 产出一个没有游戏侧的引导 jar——CI 构建的就是这种 jar。启动时负责发现这种 jar 的是 `KernelRuntimeClasses.verify`。
- **单元测试。** 按源码数：`src/test` 的 1 045 个 `*Test.java` 文件里有 5 543 个 `@Test` 方法（其中 23 个写成 `@org.junit.jupiter.api.Test`）、57 个 `@TestFactory` 方法和 6 个 `@ParameterizedTest` 方法；`src/transferTest` 的 7 个文件里有 75 个 `@Test` 方法（只数缩进之后位于行首的注解，用 `git grep` 扫已跟踪的文件）。在一个暂存根上完整跑一遍（`cleanTest test`，不用 daemon）：这个根整份克隆自开发者的 `forbric-loader/run`（合并基底、运行时和测试夹具），里面放的是这棵树跟踪的报告，再加上那台机器上还在的第三方 mod 整合包；JUnit XML 里是 6 654 个测试，其中 74 个跳过：72 个因为那台机器已经没有对应的第三方整合包，2 个是 opt-in 夹具。跑出和跳过多少个测试取决于用哪个根、有哪些夹具（有些测试按夹具逐个生成用例；从较旧的副本克隆出来的根会多跳过 5 个）。很多测试会读取暂存的 jar；gate-m0 只要遇到*任何*一个被跳过的测试就失败，因为在那里跳过意味着测试没有看过真正的基底。织入测试（`mixin/weave/`）让名字毫不相干的合成 mod 经过真正的 Mixin 和 MixinExtras，每个都以关掉对应机制作为对照。测试在 JDK 21 上也必须通过（CI 的 unstaged 作业用的就是 JDK 21）。
- **闸门。** 共 59 个脚本（`forbric-kernel/run/gate-m*.sh`），每个都对真实实例的真实日志和文件做断言，大多带有指名的负控制（一个 `-D…=off`，或移除某项输入，必须恰好让指名的那几项检查变红）。`run/compat/gates-all.sh` 按 glob 发现它们；`gates-parallel.py` 依据每个闸门里的 `# GATE-PARALLEL: rundirs=… mem=…` 行让它们重叠运行（59 个里有 56 个带这一行；没有的闸门单独运行），并给每个槽位分配独立的端口段。闸门的时间上限是按一台还有空闲核心的机器定的挂钟秒数，大多从启动脚本开始算起，包括它跑的 Gradle 构建；gate-m22 的客户端窗口则改为从 JVM 自己启动算起。机器一忙，这些上限量到的就是机器本身。把同一组闸门拆成两个 `-j 5` 批次并排跑，一个跑某棵树，一个跑它分出来的那个提交，两边承受的是同一份负载（10 核；每轮负载均值的中位数 28–59，峰值 40–111）：三轮里 m2b、m3、m4 每一轮都在两边一起变红——m2b 和 m3 的服务端在第一行日志之后 136–140 秒和 77–83 秒还没到 `Done`（有一次 m2b 在 138 秒到了，但已经来不及收到 stop），而上限是从启动算起的 150 秒和 90 秒；m4 自己的 stop 投喂器在启动后 150 秒就不再等待，把 `stop` 写进一个还没到 `Done` 的启动过程，控制台把它丢掉了（"An unexpected error occurred while trying to execute that command"；`run/lib.sh` 里的 `server_ready` 正是为这种情况写的，而 m4 没有用它）。在负载均值 44–163 下完整跑一遍 `-j 5`，m8、m17、m18、m40 也因为同一类超时变红。负载下并行跑出来的红闸门，在读过它日志里的启动耗时之前，对内核什么也说明不了。时钟还会按日期出问题：游戏的 log4j 配置在午夜轮转 `logs/latest.log`，有 11 个闸门读这个文件；m9 有一次变红，就是因为它的会话跨过了午夜，它要找的那行资源重载日志被轮转进了归档文件。

| 闸门 | 断言内容 |
| --- | --- |
| m0 | 构建、整个测试套件、`--scan`、合并基底链接检查、发现对照基准、工具自测、传输测试套件 |
| m1, m3 | 零 mod、不跑真正的生命周期时，合并基底能到达 `Done`；两个 Forge 系的基线 + 一个真实的 `@Mod` |
| m2, m2b | 一个真实的 Fabric mod，然后是完整的 fabric-api，全程没有 Fabric Loader |
| m4, m4-canary, m7-neo | 三个生态的真实 mod 同在一个服务端；纯 NeoForge jar |
| m8, m10 | 多加载器的 `pack.mcmeta` 分段不会删掉或弄坏一个包 |
| m9, m27 | 客户端带着 97 个 jar 的整合包进入世界并干净退出；新渲染出的一帧非黑画面 |
| m11 | 专用服务器上一组由配置驱动的 mod |
| m12–m16 | 客户端↔服务端走真实 socket；Paper 反作弊；纯 Fabric 服务端；MinecraftForge 的频道与握手 |
| m17 | 安装器写出的版本配置，按启动器的方式启动 |
| m18, m19, m20 | 跨生态在场；内嵌库只初始化一次；玩家能得知未满足的依赖 |
| m21, m26, m28, m29 | MinecraftForge 初始化、客户端注册事件、配置 + 实时文件监视器、capability |
| m22, m23 | 内核 jar 被替换后仍能正常退出（客户端 JVM 从自己启动起有 `M22_READY_SECONDS`（600 秒）进入世界；启动脚本在它之前跑的 Gradle 构建单独计时、单独设上限；负对照：客户端 JVM 一启动就被暂停，以及整合包里唯一的世界读不出来）；鞘翅飞行 |
| m24, m24b, m30 | 一个失败的 mod、一个元数据读不出来的 mod、一个部分失败的 mod，在每个呈现面上都有归因 |
| m24c | 写进 `forbric-disabled.txt` 的 jar 谁都不加载，并在加载报告里点名；服务器对崩溃嫌疑提示只写日志 |
| m25, m31, m32 | 两条生物群系修改器流水线；零 mod 时世界生成与原版一致；移除一个 mod 后存档仍能打开 |
| m33, m39, m40, m52 | 跨生态的物品/流体/能量传输；漏斗向 Fabric 存储输送 |
| m34 | ≥ 7200 s 有玩家在线的模拟 soak 测试，带留存检查 |
| m35–m38, m41–m51, m53 | 逐个功能面的行为：mixin 结果、实体回调、附魔、事件链、coremod 一致性、方块破坏与战利品、交互、日常操作、存根重新绑定、伤害/服务端/世界事件、加载谓词、提示框、加宽的 `NEW` 锚点 |
| m54 | NeoForge mod 在游玩阶段发给服务端的包能送到：装着 fabric-api 的 Carry On 用真实的键盘和鼠标输入搬起并放下箱子和猪；同样的运行关掉修复后必须什么都搬不起来（第三方 jar：`M54_CARRYON`、`M54_FABRIC_API`） |
| m55 | 一个 Fabric mod 用原版方法刷新搜索树之后，创造模式物品栏的搜索仍能搜到物品；以界面自己的物品网格为准，并带一个关掉修复的反向对照 |
| m56 | 不装 Mod Menu 时，Fabric mod 的配置界面能从内核 Mods 界面的 Config 按钮打开（自己的入口点、别的 mod 代供的工厂、两个被 jar 带在里面的 mod——其中一个挂在它声明的标题下，位置读自界面自己的列表控件），通用 jar 的 NeoForge 界面、游戏的选项界面和 NeoForge 自己的设置（这一行名为 `NeoForge`，因为这一组里有 NeoForge mod 才列出）也能打开，全部经由游戏自己的键盘和鼠标处理器驱动（方向键、单击、双击）；`-Dforbric.modMenuStandIn=off` 必须让 Fabric 金丝雀的按钮消失；只放 Fabric 金丝雀时 NeoForge 行必须不在。装了 Mod Menu 时（必需：`M56_MODMENU`/`M56_FABRIC_API`，默认 `run/modmenu/`，缺了就 SKIP-FATAL），Mod Menu 的 API 对通用金丝雀答出 `fabric.mod.json` 的名字和描述、并建出 NeoForge 的界面（没有打开它的列表界面），游戏行由 Mod Menu 自己的工厂回答一次；`-Dforbric.modMenuForeignScreens=off` 是这一阶段的对照。金丝雀从 `canary/modmenu-config` 构建 |

- **兼容性批量测试。** `run/compat/PROTOCOL.md` 是一套流程：在一台 Windows 机器上通过安装好的版本配置运行随机/热门的 Modrinth mod 组合（`push-and-run.sh`、`win/*.py`、`pick_mods.py`、`evidence.py`），另有静态工具（`abi-audit.py`、`field-drift.py`、`fapi-usage.py`、`hook-worklist.sh`、`repair-drift.sh`、`control-diff.sh`——同一批 Fabric mod 分别跑在原生 Fabric 和 Forbric 上做对比）。
- **CI**（`.github/workflows/build.yml`）：`build` 作业先自举，再构建 `forbric-loader/`。`kernel` 作业（JDK 21，没有游戏文件）在 `forbric-kernel/` 中运行 `./gradlew build -Pforbric.skipBaseline=ci-unstaged`：编译启动侧，运行不需要游戏文件的单元测试。没有游戏文件时约三分之一的测试会跳过，跳过的集合必须与 `src/test/skip-baseline/ci-unstaged.tsv` 逐行一致（`skipRatchet`、`tools/junit_report.py`）：新开始跳过的测试会让作业失败，不再跳过的行必须删掉。运行页面会显示测试数 / 实际执行 / 跳过数和最常见的跳过原因，JUnit 报告作为 `kernel-test-results` 上传；基线可以用该产物里的 `skips-actual-ci-unstaged.tsv` 或 `-Pforbric.writeSkipBaseline` 重新生成。`kernel-prepared` 作业（JDK 25）先在 runner 上用 `tools/dev.py prepare --no-assets` 构建游戏文件（Minecraft 从 Mojang 下载，Forge 和 NeoForge 从它们自己的 maven 下载，合并基底和载体在 runner 上构建，单元测试要读的两个 canary mod 和合并报告也一并准备好；只缓存上游下载的文件，派生出的东西一律不上传），再用 `-Pforbric.requireFixtures=staged,game-side,mc-libraries,java-25` 运行同一套测试外加 `transferTest`：除第三方 mod 整合包以外的各类测试夹具在这里都齐全，所以任何其他类别的跳过、或者没有标注类别的跳过，都会在跳过的那个测试上让作业失败。仍然跳过的 781 个测试（按测试名计，和基线的记法一样）同样由 `ci-prepared.tsv` 卡住：779 个需要不在本仓库里的第三方 mod 整合包，2 个是需要主动开启的夹具。同一个作业还用准备好的输入通过 `build-merged-base.sh` 构建一个合并基底，在上面运行合并丢失棘轮（§2.4），这是它唯一运行的地方。`development-tools` 作业在 Windows、Linux 和 macOS 上运行 `tools/dev.py tool-test` 和打包后的链接闸门。之后 kernel-prepared 还在同一批文件上运行四个真实专用服门禁：m1、m36、m46、m53，它们用的 mod 都是从本仓库源码构建的 canary。其余门禁（客户端、第三方整合包、长时间 soak）需要开发者的 Mac：`tools/nightly/` 每晚由 launchd 在那台 Mac 上运行它们（02:30 启动，soak 只在周日跑），把当晚的摘要提交到 `ci-results` 分支，并在被测提交上设置提交状态 `nightly/dev-mac`。

### 16.1 以原生游戏为准评判，以及改名实验

测试和闸门证明的是内核做了作者想让它做的事。一个 mod 的行为是否和在它自己的加载器上一样，是另一项测量；每一项针对 mod 行为的修复都要经过三重证明：

1. **合成样本。** 名字毫不相干，经过真正的 Mixin 和 MixinExtras 织入，机制打开和关闭各跑一次（即上面的织入测试）。
2. **改名实验。** `run/compat/rename-mod.py rename` 写出一个 jar，用别的名字做原 mod 做的事：mod 根包下的每个类都挪到另一个包名和类名下（引用、描述符、签名、注解、Mixin 目标、MixinExtras 定义、refmap 和类名字符串都跟着改），它的 mixin 配置、refmap 和访问加宽器换成别的文件名，它的 mod id 在元数据、清单、服务文件和 jar-in-jar 元数据里出现的地方都换成别的 id（只用字母：Forge 的 Mixin 在给合并成员命名时会去掉 mod id 里的数字）。资源命名空间不变，mod 代码里拿来比较名字的字符串也不变；针对这个 mod 编译的探针或附属 mod 跟着一起改（`--dependent`）；按名字和摘要钉住的内嵌 jar 会重新钉住。`verify` 用 `javap` 把改名后的 jar 读回来，检查它是纯粹的改名；`compare` 把改名 jar 的一次运行和原 jar 的一次运行并排——`-Dmixin.debug.export=true` 普查、`Mixing … into …` 行、内核的 `[Forbric/…]` 行和兼容性报告、探针文件——把新名字换回旧名字，列出每一处差异。剩下的差异只是原 jar 跑第二遍也会出现的噪声（时序、出生点、列表顺序），或者由新 id 的形状结构性地带来的（只有带 `-` 的 id 才有的下划线别名行），修复才算通过。按字面 id 查找自己的 mod，改名时保留 id。`ModRename.java` 是其中的 ASM 部分。
3. **原生对比。** 同样的 mod 在原生 Fabric Loader 0.19.5、原生 NeoForge 26.2.0.88 和原生 MinecraftForge 26.2-65.0.1 上运行，逐个注入器比对：每个注入器落在哪条指令上（导出普查，以及落点前后的指令）、它所在的方法在游戏里是否运行（调用者，以及内核的 uncalled 表）、游戏内探针报告了什么（每帧的次数、数值、截图），以及警告。同一条指令、在同样的执行里运行、效果相同，才算等价；其余的要么是点名的缺口，要么附有“没有任何结构性触发条件能弥补”的证明。这套逐 mod 的工具放在开发者的机器上，不在仓库里；`rename-mod.py census`/`compare` 和 `run/compat/control-diff.sh` 是仓库里的部分。

最近一轮的结果见 §19。

## 17. 系统属性

在 JVM 上用 `-D` 设置。安装好的版本配置一个也不设。`src/main` 和 `src/runtime` 中大约有 440 个不同的 `forbric.*` 属性名；几乎每项修复都有一个 `-Dforbric.<name>=off` 开关，关掉时会记一条 WARN，说明失去了什么。这些开关是给二分排查和闸门的负控制用的。开发者常用的有：

**策略与报告**

| 属性 | 作用 |
| --- | --- |
| `forbric.compatibilityPolicy` | `ask`（默认）、`continue`、`strict`；其他任何值都按 `strict` 处理 |
| `forbric.dependencyDialog` | `on`（默认）、`off`、`dryRun` |
| `forbric.dialogLanguage` | 强制指定对话框的语言（如 `ja`） |
| `forbric.loadReportRewrite` | `off`：以第一次写入的 `load-report.txt` 为准 |
| `forbric.crashAnalysis` | `off`：不生成 `crash-analysis.txt` |
| `forbric.debug` | 启用 `ForbricLog.debug` 的日志行（以及每个位置第一条 `Caught.expected` 日志） |
| `forbric.caught.printedPerSite` | 同一位置的同一个被吞下的失败打印多少次后只计数（默认 20，§12.5） |
| `forbric.caught.file` | 把每个被吞下的失败位置及其次数也追加写进这个文件；`FORBRIC_CAUGHT_FILE` 作用相同，闸门设的就是它 |

**仲裁与顺序**

| 属性 | 作用 |
| --- | --- |
| `forbric.multiLoaderPreference` | 单个 jar 内的生态优先顺序，默认 `neoforge,minecraftforge,fabric` |
| `forbric.dupeIdPreference`, `forbric.nestedDupePreference` | 顶层 / 内嵌重复项的跨 jar 优先顺序 |
| `forbric.modOwner` | `id=loader,…` 形式的锁定；也可用 `<rundir>/forbric-mods.txt` |
| `forbric.crossJarArbitration` | `off`：同一个 id 的两个 jar 都会加载 |
| `forbric.nestedRequirements` | `off`：`minecraft`/`java` 范围不包含本游戏的内嵌 Fabric mod，或和 Fabric Loader 更优先的 mod 共用一个 id（provides 也算）的内嵌 Fabric mod，照样加载；`off:<id>,…`：只豁免这几个 id，它们照样加载，也不再把对手挤掉 |
| `forbric.arbitrationMaxNodes` | 选择器的工作量上限（默认 100 000，封顶 1 000 000） |
| `forbric.modOrder` | `name`：按文件名决定构造顺序 |
| `forbric.fabricOrder` | `off`：Fabric mod 按拓扑顺序，而不是按 mod id 顺序 |
| `forbric.fabricMainInConstructor` | `off`：客户端的 Fabric `main` 入口点在 `Minecraft` 之前的窗口里运行 |
| `forbric.eagerLanguageAdapters` | `off`：Fabric 语言适配器在入口点第一次用到时才构造，而不是在 Mixin 之前 |
| `forbric.loaderProbes`, `forbric.crossEcosystemPresence` | `off`：平台探测 / 在场查询按单一加载器的方式作答 |

**Mixin**

| 属性 | 作用 |
| --- | --- |
| `forbric.relaxGuestMixins` | `off`：不放宽第三方配置 |
| `forbric.mixinCompat` | `latest`：所有 Fabric 配置按 Mixin 的最新级别运行，而不是 Fabric Loader 给其 mod 的级别（§7.1） |
| `forbric.captureLanding` | `off`：内核搬到"落进尾部汇合点的调用"之后的 TAIL 捕获，在 0.10.0 以下不再得到落脚的 `NOP`，0.9.2 下 Mixin 会跳过它（§7.1） |
| `forbric.lateInjectionIsolation` | `off`：可选 mixin 里的 MixinExtras late 注入器失败时，按上游行为把整个目标类原样交回（§7.2）；开着时，如果 guest 在安装之后启动了更新的 MixinExtras，也会走同样的路径，日志里会说一次 |
| `forbric.relaxMixinOverwrites` | 要放宽的配置，csv（支持 `*` 通配） |
| `forbric.mixinDiagnostics` | 保持注入要求严格，让每一处不适配都暴露出来 |
| `forbric.mixinFit` | `strict`：连 `PARTIAL` 的 mixin 也丢弃 |
| `forbric.mixinFit.liveness` | `off`：位于无人调用的方法上的注入器也算已解析 |
| `forbric.mixinFit.nativeAbsent` | `off`：mod 自己的平台也没有的注入目标重新算作缺失的锚点 |
| `forbric.mixinFit.nativeAbsent.base` | `<摘要>`：让 `native-only-methods.txt` 改为信任这个合并基底成员摘要，而不是表里记录的那个（测试里的夹具游戏用） |
| `forbric.mixinOverlapLint` | `off`：启动时不报告跨 mod 的 mixin 重叠（§7.6） |
| `forbric.guestMixinAdapter` | `off`：不做推导出来的丢弃，只用手写清单 |
| `forbric.mergedBaseCompat` | `off`：去掉内置的不兼容清单 |
| `forbric.disableMixinConfigs`, `forbric.enableMixinConfigs` | 要禁用 / 强制启用的配置，csv |
| `forbric.suppressMixins`, `forbric.keepMixins` | 要丢弃 / 保留的 `config:Mixin`，csv |
| `forbric.lateMixinJudgment` | `off`：配置插件添加的 mixin 在 Mixin 第一次读到时不评判 |

**原生等价机制**（§7.4.1、§6；每个设为 `=off` 关掉一个，并记下失去了什么）：`forbric.nativeOccurrences`、`forbric.addedCallOccurrences`、`forbric.nativeSliceOccurrences`、`forbric.returnOrdinals`、`forbric.shiftedPoints`、`forbric.vanillaEarlyReturns`、`forbric.mixinStubRebind`、`forbric.builderStubRebind`、`forbric.mixinTwinRebind`、`forbric.renamedBodyEdges`、`forbric.splitBodyEdges`、`forbric.forgeModelDataEdge`、`forbric.nativeCallInlining`、`forbric.mixinFieldInitialisers`、`forbric.expressionThunks`、`forbric.carrierDecisions`、`forbric.carrierGuardedPoints`、`forbric.carrierDefaultChains`、`forbric.carrierHelperResults`、`forbric.carrierReturnedCalls`、`forbric.carrierMethodReferences`、`forbric.replacedDecisionValues`、`forbric.nativeEntryRelay`、`forbric.nativeDecisionIsland`、`forbric.placementCallWitness`、`forbric.nativeValueStores`、`forbric.nativeConsumerStores`、`forbric.nativeDeadValueConsumers`、`forbric.nativeLocalPins`、`forbric.nativeLocalMaterialization`、`forbric.capturedLocals`、`forbric.localRanges`、`forbric.tailLocals`、`forbric.scopedClearRelay`、`forbric.uncalledDecisionEntries`、`forbric.uncalledValueRelay`、`forbric.mixinAsideMarks`。

**诊断与测试驱动**

| 属性 | 作用 |
| --- | --- |
| `forbric.clientSmoke`（+ `clientSmokeWorld`、`clientSmokeReadyTicks`、`clientSmokeDisconnectTicks` 等） | 无人值守的客户端运行：进入世界、停留、离开、退出 |
| `forbric.eventChainAudit` | 跨总线审计的报告文件 |
| `forbric.definedClassEvidence` | 存放每个已定义类的内容寻址记录的目录 |
| `forbric.traceClassDefine` | 二进制类名的 csv；每个类第一次被定义时记下调用栈 |
| `forbric.tickSampler` | `off`：不对服务端 tick 耗时采样 |

**部分修复开关**——`forbric.arbitratedAwayEntrypoints`、`forbric.graphicsBootstrappers`、`forbric.channelDeclarationsOnce`、`forbric.forgeConditionFunnel`、`forbric.forgeModelData`、`forbric.creativeTabEcosystemOrder`、`forbric.glideFreshEquipment`、`forbric.staleWatchGuard`、`forbric.commonNetworkInterop`、`forbric.playPayloadFallThrough`、`forbric.chunkExecutorGuard`、`forbric.forgeCapabilities`、`forbric.forgeWorldgen`、`forbric.transferBridge`、`forbric.hopperFabricStorage`、`forbric.clientResourcePreload`、`forbric.earlyConfigs`、`forbric.fabricHooks`、`forbric.fabricImpl`、`forbric.kernelBundledFirst`、`forbric.modDataPacks`、`forbric.modMenuStandIn`、`forbric.forgeBreakExperience`、`forbric.forgeFieldGetters`、`forbric.forgeFluidOwnType`（两者见 §5.3）、`forbric.supersededMixins`。`forbric.kernel.registryRedirect=true` 会启用一个实验性的注册表包装器重定向。

## 18. 不变量

违反其中任何一条，故障通常都会在离原因很远的地方冒出来。

1. **引导代码不直接引用任何游戏类型。** 它通过字符串访问游戏侧，所有这类字符串都在 `KernelRuntimeClasses` 里。跨边界共享的一律是 `ALWAYS_PARENT`；游戏侧的一律是 `ALWAYS_GAME`。每个 JVM 中每个类只有一份。
2. **`MIXIN` 是末端阶段。** 没有任何东西通过 `TransformChain` 注册到它里面；Mixin 拿到的是 Mixin 前的字节；Mixin 后各阶段的顺序是固定的。
3. **加载器身份在第一个类到达 Mixin 转换器之前就已存在。** 晚于这一刻再预置，第三方插件的 `<clinit>` 就会永久决定 MinecraftForge 的 `dist`。
4. **一个注册窗口，一次冻结。** 内容注册发生在 `unfreeze` 和 `closeRegistrationWindow` 之间；客户端会为自己的入口点重新打开一次，然后重新冻结。
5. **要么生命周期触发点完成重定向，要么内核不启动。**
6. **游戏总线在各初始化阶段之前启动；负载阶段在这些阶段之后关闭。**
7. **仲裁只决定一次。** 预扫描得出的方案由两次发现共同使用；后续各轮只做核验，绝不重新选择。选择的上限按工作量算，不按时间算。
8. **修复一旦停用，就会明说** —— `AnchorSet`/`AnchorLedger`、`EventBridges.verify`、每个开关一条 WARN。
9. **归因从不改变结果。** 错误处理器和报告只做记录；Mixin 的决定和 mod 的失败都保持原样。`CompatibilityDecision` 从不退出 JVM；只有启动边界才会退出。
10. **任何带有 Mojang、MinecraftForge 或 NeoForge 字节的东西都不提交、不分发。** 游戏侧以 `compileOnly` 方式链接暂存的 jar；这些 jar 由安装器在玩家的机器上构建。
11. **没有静默的 catch。** 每个 catch 要么重新抛出，要么上报给 `Caught`（§12.5）；让内核吞下失败继续运行的单测会失败，除非它用 `@ExpectCaught` 说明。
12. **修复不认名字。** 触发修复的是形状——字节码、描述符、继承与接口关系、契约、事件是否投递——绝不是 mod id、mod 的类名或包名、jar 名、mixin 配置名、mixin 类名、handler 名、`@Local` 或 `@Share` 名、mod 的实现类（fabric-api 也是 mod：可以调用它的公开 API，不能点名它的实现）、只针对某个 mod 的字节码模板或方法体哈希。具体 mod 是样本和测试用例，从不是条件（§16.1）。
13. **每次原生分发只投递一次。** 监听器收到某个事件的次数，和它自己的加载器会给它的次数相同：游戏在某条路径上自己投递了这个事件，桥就在那里退让；只有在存在 mod 自己的游戏里没有的出口时，桥才欠着这次投递（§8.1）。“至少一次”不算通过。
14. **合并只会丢得更少。** 合并丢失棘轮的上限只降不升（§2.4）。

## 19. 现状与已知边界

- **仅支持 Minecraft 26.2**，以 Mojmap 为恒等命名空间。没有重映射步骤：针对其他命名空间编译的 jar 不做转换（`kernel/mapping/` 是从焊接方案沿用过来的，不在启动路径上）。
- **合并基底是 NeoForge 的游戏，再拼进 MinecraftForge；能证明的地方两边的编辑都保留。** 240 个方法带着两边的编辑；921 个两边都改过的方法仍只保留一边的方法体（编辑拼接证明不了能搬，或者发现了真正的冲突）；合并在 MinecraftForge 一侧丢失 508 次、NeoForge 一侧丢失 15 次平台调用，由棘轮卡在这里（§2.4）。落败一方的 mod 因此丢掉的东西由转换器、第三方 mixin 机制和桥修复，没修复的由 `DeadEventAudit`、`HookCallSiteCensus`、`FieldDriftAudit`、`AbiLinkAudit`、`CapabilityUseAudit` 报告。结构性冲突（`Entity` 有两个真正的父类）在字节码层面无解；MinecraftForge 的 capability 由转换器重新组合进来。
- **`PARTIAL` 的 mixin 默认应用** —— 宁可保留只应用了一半的结果（并让它可见），也不丢掉还能工作的钩子。
- **一个类，一份副本。** 同一个 mod 的两个生态构建相互竞争时，只有一个胜出；落败的生态看到的是在场别名，而不是该 mod 自己的平台胶水代码。
- **靠实测，不靠承诺。** `MOD_TEST_FAILURES.md` 记录了针对 2026-09-26 的 `main`（`11ca1ffa`，编辑拼接之前）的逐 mod 测试（每个 jar 只带上它必需的依赖单独运行，进入世界、截图、退出），用的是三组全新随机抽取的 Modrinth mod：平均 89.0% 加载时没有失败行（91.8% 进入了世界；79.1% 在加载报告里没有任何一项被标为 DEGRADED），而同一批 jar 在发布版 v0.2.0 上是 80.5%。这轮扫测还没有在编辑拼接后的基底上重跑。
- **原生对比，最近一轮。** 按 §16.1 的方法（指令级落点、活性、游戏内探针、改名实验）把 27 个 mod 和它们的原生加载器对比：Carpet、Create Fly、C2ME、Lithium（Fabric 和 NeoForge 两个构建）、Physics Mod、Sodium（NeoForge）、Continuity、Iris、Corpse、Shoulder Surfing 加 CustomSkinLoader、fusion、Do a Barrel Roll、Controlify、Just Enough Characters、SpectreLib、Mod Menu、Forge Config API Port、PuzzlesLib、Item Glint Relight、Jade、Essential、fabric-api、Balm、Architectury、Origins Legacy、Better Mount HUD。22 个等价；Lithium（Fabric）和 Forge Config API Port 不等价的原因都已证明无法弥补，Mod Menu、fabric-api 和 Balm 不等价的原因见下。每一次改名实验的结果都和原 jar 相同。最近一轮重测了下表十行（基底用当前工具构建；内核为 `91dbfa5d`，再加上本轮的经验中继、委托孪生规则和旁置方法名）：

  | mod | 结论 | 剩余 |
  | --- | --- | --- |
  | 挖方块经验，不装 mod | 等价 | 只读 `getExpToDrop` 的 MinecraftForge 监听器看到的是另一次抽样 |
  | Carpet | 等价 | — |
  | Create Fly（配 fabric-api） | 等价 | 燃料、红石连接和全局键现在与原生一致；只影响报告：`FuelValuesMixin` 仍标 `SUSPECTED`，渲染线程上的第二次预检会再打印一遍 `EntityMixin` 旧的 partial 行 |
  | PuzzlesLib（Fabric 和 NeoForge 构建） | 等价 | 经验事件拿到的是原版的值，只决定一次（两次挖掘 50 和 100，与原生相同） |
  | Mod Menu | 不等价 | 更新检查提示 `fabricloader@0.19.3` 有新版；载体的品牌行 |
  | fabric-api 0.161 | 不等价 | 数据生成的 `TagsProviderMixin`；`AFTER` 有两个静态调用点（每次挖掘只触发一次）；两项已证明 |
  | Balm | 不等价 | `SCREEN_OPEN` 的读取点；作物湿度（已证明） |
  | Origins Legacy（配 fabric-api） | 等价 | — |
  | Architectury | 等价 | —（本轮在游戏里实测了全局键） |
  | Physics Mod | 等价 | — |

  之后又在专用服务器上重测了流体里的空气值，对照原生 Fabric（带 fabric-api 和一种 Fabric 测试流体，再加 Create Fly）、NeoForge 26.2.0.88 和 MinecraftForge 26.2-65.0.1，Forbric 分别不带 fabric-api、带 fabric-api、带 Create Fly 运行。对每一种眼睛所在之处能呼吸的身体——水下呼吸、潮涌能量、溺尸、海龟头盔、无敌的玩家、岩浆、mod 的可呼吸流体、Create 的潜水装备——Forbric 的空气值现在和原生 Fabric 逐 tick 相同，每 tick 回 4 点直到 300。只有鹦鹉螺之息时，在水里和不带水标签的 mod 溺水流体里保持不动——Fabric 测试流体和原生 Fabric 一样，会溺水的 NeoForge 流体类型和原生 NeoForge 一样，关掉跨宿主谓词岛、只由钩子自己判定时也一样——在岩浆和可呼吸流体里回气，和原生 Fabric 一样（原生 NeoForge 和 MinecraftForge 在任何流体里都保持不动）。高于上限的空气值（400/300）在水里带水下呼吸、在开阔空气里、只有鹦鹉螺之息时都保持在 400，和原生 Fabric 一样；原生 NeoForge 三种都拉回 300，MinecraftForge 拉回前两种。空气值不高于上限时（游戏内的监听器场景都从 200 开始），设置数量或决定能否呼吸的 NeoForge 监听器得到的曲线和在 NeoForge 上完全相同，它读到的默认回气量是原版的 4，而 NeoForge 给的是 0。高于上限时，事件开始时的回气量是 0，除非监听器把回气量设成别的值，否则空气值原地不动（§6），而 NeoForge 会把它拉回上限：只让溺水身体能呼吸的监听器让 400/300 留在 400，把回气量设成 0 或任何负数（事件会把它钳成 0）的监听器也一样；NeoForge 上两者都是 300（在 NeoForge 真实的钩子上执行得出，装饰过的和关掉开关的各跑一遍，没有在游戏里实测）。加上 MinecraftForge 的字段 getter、它的流体自己的类型和它的呼吸事件（§5.3、§8）之后，又用一种会溺水、一种不会溺水的 MinecraftForge 测试流体重测了 MinecraftForge 服务器：29 个场景里 25 个和原生 MinecraftForge 逐 tick 相同。其中有 MinecraftForge `LivingBreatheEvent` 监听器的五个场景——把回气量设成 2、设成 0、给溺尸设回气量，让水下呼吸的身体溺水，让溺水的身体能呼吸（加桥之前这五个监听器一个都不运行）；还有 MinecraftForge 流体里六个场景中的五个，它们以前测不了，因为液体方块一放下就抛异常：在会溺水的那种里溺水的身体、带水下呼吸的、里面的溺尸、只有鹦鹉螺之息的，以及在可呼吸那种里的身体。另外四个是上面说的原版规则：只有鹦鹉螺之息时在岩浆和可呼吸的 MinecraftForge 流体里回气，MinecraftForge 保持不动；高于上限的空气值在水里带水下呼吸、在开阔空气里原地不动，MinecraftForge 拉回 300；前两种情况下 MinecraftForge 监听器读到的 `canRefillAir` 是 true，原生 MinecraftForge 读到的是 false。加 `-Dforbric.forgeFluidOwnType=off` 时，身体又能在会溺水的 MinecraftForge 流体里呼吸（3 个场景变化）。NeoForge 带 fabric-api、Fabric 带 Create 的两组运行，和这三项改动之前的运行逐个场景完全相同。加上溺水桥（§8）之后，又加了八个溺水场景——水里一头没有空气的猪，每 tick 记录空气值、生命值和它的监听器看到的每一个 MinecraftForge 溺水事件：只读、取消、设成不在溺水、把伤害设成 3 和设成 0、把气泡数设成 0 和设成 50，以及带水下呼吸（没有事件）。八个场景的空气值、生命值、事件的个数和发生的 tick，全部和原生 MinecraftForge 相同：每次溺水一个事件，在空气值 -20 时交出 `(true, 2.0, 8)`；取消或者设成不在溺水时，空气值继续往 -20 以下掉、没有伤害，和原生一样；改气泡数什么都不改变，和原生一样。加桥之前监听器一个事件也看不到，有监听器的七个场景都和没有监听器时一样溺水。探针量的是空气值、生命值和事件，不量身体的随机数抽取，而写回气泡数改变的正是这个（桥的第一版会写回），所以这条规则改由 `KernelDrownBridgeTest` 在真实的总线上钉住。29 个呼吸场景和不带桥的运行逐个场景相同，和原生 MinecraftForge 相比只有上面说的四个原版规则的差别。在这一轮之前，探针在强制加载水池所在区块的同一个 tick 就开始场景；有三个场景——每个新区块里的第一个，其中包括启动后的第一个——把生物放进了还不运行实体 tick 的区块（生物的头几次采样里，眼睛所在的方块是水，眼睛却不在水里，因为它还没 tick 过），于是它们的空气值开始变化的时间，要晚上区块系统所花的那几个 tick，原生和 Forbric 都一样。按那种方式跑出的运行里：猪沉进会溺水的 MinecraftForge 流体，八次原生运行中有三次早一个 tick 开始，二十次 Forbric 运行中只有一次（多装了两个 mod 的那次）；水里的猪，带桥的十四次 Forbric 运行中有六次晚一个 tick 开始，不带桥的六次一次也没有，八次原生运行也一次没有（有一次原生运行晚了六个 tick）；开阔空气里的猪，带桥的十四次中有两次早一个 tick 开始回气，其余都没有。现在探针先等水池所在的区块开始运行实体 tick，再开始场景——这三个要等 2 到 5 个 tick，其余场景不用等——这样跑出的九次运行（三次原生、三次带桥、三次不带桥），每组内部在每个场景上都完全一致，包括这三个；带桥的运行和原生 MinecraftForge 相比只差那四个原版规则场景，和不带桥的运行相比只差有监听器的七个溺水场景。等待的 tick 数本身仍然会变（开阔空气里的猪，在不带桥的运行里等 2 个 tick，在另外两组里等 3 个）：区块系统的快慢随构建、随运行而不同，而探针现在不再把它量进去。

**仍然不与原生等价的地方。** 已证明任何结构性触发条件都无法弥补的（证明都保留着）：

- Forge Config API Port 自己的 `config/forgeconfigapiport.toml` 从不写出：读写它的只有这个移植自带的几份载体类副本（`ConfigTracker`、`ConfigurationScreen$TranslationChecker`），它们和 NeoForge 载体里的类同名，永远无法被定义。它的三个选项改为从 `fml.toml` 里的同名项读取。
- 装了 fabric-api 时，高级提示框会多出 `[1 + 6]` 这类行：两个生态往同一个共享的提示框里追加内容。
- Balm 的作物湿度钩子：载体把湿度判断换成了虚方法 `isFertile` 调用。
- Lithium 的 Fabric 构建：三个漏斗注入器挂不上。NeoForge 把邻居通知挪进了 `markAndNotifyBlock`，查找容器分三步（`ContainerOrHandler`），无法拆成原版的两步（Lithium 自己的 NeoForge 构建也不应用它们）。失效的只是漏斗容器缓存这项优化。
- 频道声明：每次进服，配置阶段客户端触发 5 次 `REGISTER`、服务端 4 次（原生各 1 次），游玩阶段 3 次（原生 2 次），另外多 3 次 `UNREGISTER`。

已定位、尚未修复的（没有不可能性证明）：

- Mod Menu：`fabricloader` 容器报的版本是 0.19.3——内核实现的 Fabric Loader API 级别，`FabricLoaderApiSurfaceTest` 把它钉在这里——而原生报的是实际运行的加载器版本（0.19.5），所以 Mod Menu 的更新检查会提示一个原生不会提示的更新；挂在加载器下的 `mixinextras` 是内核自带的 0.5.4（原生 0.5.5），容器类型是 `builtin`（原生 `fabric`）。报一个内核没有实现的版本是不实的，所以要等 API 本身到 0.19.5。标题界面还多一行 Forbric 自己的品牌行 `forbric-v<版本> (N mods)`，N 是 `mods/` 里的 jar 数（Mod Menu 的数还算上了打包在这些 jar 里的 mod），它让 Mod Menu 那一行上移一格；所有者决定保留。
- Balm 的 `SCREEN_OPEN` 钩子（挂在每次读取 `Gui.screen` 上的修改器）原生挂在 4 处读取上，这里只有 3 处：载体把这个字段存进了局部变量，少读了一次。
- fabric-api：`PlayerBlockBreakEvents.AFTER` 在 `Block.destroy` 之后有两个静态调用点（原生一个），但在测过的每种模式下每次挖掘都恰好触发一次；附魔台的主物品判断走 NeoForge 的 `isPrimaryItemFor`，只证明了它折叠掉的那个析取项等价（游戏内附魔探针与原生相同）；数据生成的 `TagsProviderMixin` 挂不上任何地方（只影响 Fabric 数据生成）。
- 空气的随机数序列：有氧气加成（水下呼吸附魔，Respiration）的身体在服务端每活着一 tick 就抽一次自己的随机数，原版只在溺水时才抽。合并后的 `baseTick` 一开始就把消耗量 `air - decreaseAirSupply(air)` 交给 NeoForge 的呼吸钩子，而 `OXYGEN_BONUS` 属性大于 0 时 `decreaseAirSupply` 会调 `random.nextDouble()`；原版只在溺水分支里调它（原生 NeoForge 和 MinecraftForge 抽法和 Forbric 一样）。空气值和原版相同——消耗量只在身体不能呼吸时才用——但这个实体之后的随机数（移动、声音、粒子）不同。可以用一个取舍来弥补，还没有做，也没有实测：在 `baseTick` 里包住这一次调用，先记下实体随机数的状态、抽值、再把状态恢复，只在钩子真正用到消耗量的地方（事件结束时身体不能呼吸，也就是原版抽值的地方）才像这次调用那样推进随机数。监听器读到的消耗量和 NeoForge 上一样；事件期间没有监听器从实体随机数里抽值时，序列和原版相同。代价：在事件期间从实体随机数里抽值的 NeoForge 监听器，抽到的是消耗量用过的那个值，而在 NeoForge 上是下一个；记下和恢复状态依赖随机数源的具体实现（实体用的是原版的 `LegacyRandomSource`，`nextDouble` 只推进它私有的 `seed`）；从别的随机数源抽值的 `decreaseAirSupply` 重写照样每 tick 都抽。
- 有四十七个 MinecraftForge 26.2 会发的 MinecraftForge 事件，这里从不发出，也还没有桥。统计必须交代每一个被测出从不发出的事件之后（§8），才把它们找出来；`DeadEventAudit` 会点名等着它们的 mod，并标成 DEGRADED，合并后的游戏在它们的位置上发的是 NeoForge 的事件（或者什么都不发）。通用的：`PlayerXpEvent.XpChange` 和 `.LevelChange`、`ItemTossEvent`、`ItemExpireEvent`、`EntityItemPickupEvent`、`PlayerEvent.ItemPickupEvent`、`AnimalTameEvent`、`LivingChangeTargetEvent`、`LivingDestroyBlockEvent`、`EntityMobGriefingEvent`、`LivingUseTotemEvent`、`LivingGetProjectileEvent`、`LivingEntityUseItemEvent.Start`、`LivingSwapItemsEvent.Hands`、`LivingPackSizeEvent`、`MobSpawnEvent.PositionCheck` 和 `.SpawnPlacementCheck`、`LevelEvent.PotentialSpawns`、`EntityTeleportEvent.EnderPearl` 和 `.SpreadPlayersCommand`、`PlayerSleepInBedEvent`、`SleepingLocationCheckEvent`、`SleepingTimeCheckEvent`、`SleepFinishedTimeEvent`、`PlayerSpawnPhantomsEvent`、`PlayerEvent.NameFormat` 和 `.TabListNameFormat`、`PlayerInteractEvent.LeftClickEmpty`（原生只在客户端发）、`AlterGroundEvent`、`NoteBlockEvent.Play` 和 `.Change`，以及 `BlockEvent.EntityMultiPlaceEvent`（床或门；被 `routePlaceItemHookToNeoForge` 弄丢，见 §8）。客户端的：`RenderNameTagEvent`、`RenderAvatarEvent.Pre` 和 `.Post`、`RenderArmEvent`、`RenderTooltipEvent.Pre`、`ScreenEvent.CharacterTyped.Pre` 和 `.Post`、`ScreenEvent.MouseButtonPressed.Post`、`.MouseButtonReleased.Post`、`.MouseDragged.Post`、`.MouseScrolled.Pre`、`ScreenEvent.RenderInventoryMobEffects`、`InputEvent.MouseButton.Post`、`PlaySoundEvent`、`GatherAtlasMetadataSectionsEvent`。每一行的说明写了相对原生 MinecraftForge 玩家会失去什么。其中没有 NeoForge 的事件。在某个 mod 自己会发其中一个事件的包里（Tombstone 会发 `PlayerSpawnPhantomsEvent`），那个事件不报告（§8）。另有四个这里也从不发出，但不列入，因为 MinecraftForge 26.2 自己也从不发：`PotionBrewEvent.Pre`、`EntityTeleportEvent.ChorusFruit`、`ChunkDataEvent.Load`、`CustomizeGuiOverlayEvent.DebugText`。
- 另有五个 MinecraftForge 事件在合并中丢了原生发布点，但哪张表都没写，所以等着它们的 mod 不会被标成 DEGRADED，也没有任何日志。统计只要还有任何一条路径能到达发布者，就算这个事件会发，而本身不是钩子的载体调用方直接算可达；`PARTIAL` 行只针对 `ForgeEventFactory` 和 `ForgeEventFactoryClient` 测量。`PlayerContainerEvent.Open`：原生 `ServerPlayer` 通过 `ForgeEventFactory.onPlayerOpenContainer` 发它；合并后的 `ServerPlayer` 在三处构造 NeoForge 的 `Open`，却仍调用 MinecraftForge 的关闭钩子，所以 MinecraftForge 监听器会收到原版容器关闭，却收不到它打开（这个钩子剩下的唯一调用方是 `IForgeServerPlayer.openMenu`，只有 MinecraftForge mod 自己开的菜单才走这里）。`PlayerEvent.HarvestCheck`：原生 `Player` 和 `BlockBehaviour` 经 `doPlayerHarvestCheck` 和 `ForgeHooks.isCorrectToolForDrops` 到达它；合并后的这两个类不调用这类钩子，合并后的 `Block.canHarvestBlock` 取代了 MinecraftForge 的 `IForgeBlock.canHarvestBlock` 默认方法，调用的是 NeoForge 的。`BlockEvent.CropGrowEvent.Pre`：`ForgeHooks.onCropsGrowPre` 原生在游戏里有十处调用点，合并后只剩两处（仙人掌和甘蔗；合并后的 `CropBlock` 问的是 NeoForge 的 `CommonHooks.canCropGrow`），`.Post` 保留了十处里的九处（`StemBlock` 两个都不调）。`BlockEvent.FluidPlaceBlockEvent`：合并后的 `LavaFluid` 在它的三处只发 NeoForge 的；MinecraftForge 自己的 `FluidInteractionRegistry` 仍在一个 lambda 里调用这个钩子。从字节码读出（MinecraftForge 的补丁游戏对照合并基底，套用了 `useOn` 的改路），没有在游戏里实测。
- MinecraftForge 下物品用在方块上被用完时（放下一叠里最后一个方块、工具在 `useOn` 里坏掉）：原生是 `ForgeHooks.onPlaceItemIntoWorld` 在物品用完后发 `PlayerDestroyItemEvent`；同一个 `routePlaceItemHookToNeoForge` 让这个钩子没有人调用，那里发的是 NeoForge 钩子里 NeoForge 的事件，没有桥转发。这个事件在别的路径上照常发，所以不是 `DEAD`；`PARTIAL` 数的是游戏对 `ForgeEventFactory` 的调用点，看不到在载体钩子内部丢掉的路径。从字节码读出，没有实测。
- MinecraftForge 下放置占多格的方块：放置桥会转发它看到的每一个 NeoForge `EntityPlaceEvent`，而 NeoForge 的总线把 `EntityMultiPlaceEvent`（它的子类）也交给它，所以 MinecraftForge 的 `EntityPlaceEvent` 监听器会把一张床或一扇门当成“放了它的第一格”收到一次。原生 MinecraftForge 在那里只发 `EntityMultiPlaceEvent`，发在它自己的总线上（EventBus 7 不把子类事件交给父类的监听器，除非它是 `InheritableEvent`），所以这样的监听器根本收不到。两种总线的分发方式都在它们真实的类上验证过（发一个子类事件，在各自总线上挂一个父类监听器）；放置本身没有在游戏里实测。
- 客户端上的溺水：合并后的钩子（NeoForge 的）在身体溺水时不发实体事件 67，所以客户端不显示原版那一团溺水气泡；原生 MinecraftForge 和原版会发，原生 NeoForge 不发。从字节码读出，没有实测。
- 经验：只读 `getExpToDrop` 的 MinecraftForge `BreakEvent` 监听器看到的是 MinecraftForge 自己那次抽样，不是 NeoForge 实际掉落的那次（设置它的监听器结果正确）。`tryDropExperience` 的中继（§7.4）会拒绝读取或改动原始抽样、或者替换了 `popExperience` 调用的钩子，并打一条 WARN；而且只在方块的原生调用方传给 provider 的值读得出来时（字段、静态字段、对常量的静态调用）才为它运行；在内核之前注册的 `LOWEST` `BlockDropsEvent` 监听器看到的是中继前的值。从字节码读出、没有实测：NeoForge 的 `handleBlockDrops` 调用的是 `spawnAfterBreak(…, false)`，所以一个 Fabric mod 自己的方块，如果它重写的 `spawnAfterBreak` 在 `dropXp` 为 true 时弹出经验，这里一点也不会弹出。
- 两个 mod 挂在同一个已没人调用的静态判断方法上的 `HEAD` 钩子（`UncalledDecisionEntries`）会变成载体判断外面的两层包装：运行顺序和原生相反，第一个取消时第二个照样运行（由 `UncalledDecisionTwoMixinsWeaveTest` 钉住；测过的 mod 里没有哪个有两个这样的钩子）。
- 给开发者：委托孪生一遍（§2.4）之前构建的合并基底，挖方块仍然没有经验，`UncalledMethodCensusTest` 和 gate-m43 在它上面会红；请用当前工具重建 staged 基底（CI 和安装器总是这样做）。
- 只影响报告、行为等价的噪声：Sodium 的 `WindowMixin` “partial 3/4”，Sodium 与 fabric-rendering 的 `MixinOverlap` R3 警告，Continuity 过时的 “partial (kept)”，Shoulder Surfing 加 CustomSkinLoader 的争用调用点警告，Create 的 `FuelValuesMixin` `SUSPECTED` 和渲染线程上对 `EntityMixin` 的第二次预检。挂在加载器下的 `mixinextras.init.mixins.json`（没有 mixin，只有 MixinExtras 的初始化）现在在每个包里都会应用，和 Fabric Loader 一样，只装 MinecraftForge mod 的包也不例外，而原生 MinecraftForge 没有这个配置。
- 没有测到的：Controlify 的手柄功能（没有手柄）；Sodium 的 NVIDIA/AMD/Intel workaround 分支（只有 Apple 硬件；两边的代码路径相同）；SpectreLib 下 Fabric 消费者的服务端配置的加载时点（`ServerAboutToStart`，而不是 `SERVER_STARTING`，两者都在世界加载之前；按字节码论证，没有这样的消费者可以实际运行）。
- 有意的差异：真正的 NeoForge 26.2.0.88 渲染 Corpse 的尸体时会崩溃（`NoSuchFieldError NeoForgeMod.NAMETAG_DISTANCE`）；`ZeroNameTagMigrationInjector` 先证明这个字段只用来设成 0，再改读 `Attributes.NAME_TAG_DISTANCE`，所以尸体能正常渲染、不显示名牌。
- **版本。** `forbric-kernel/build.gradle` 写的是 `0.1.0-SNAPSHOT`；安装器是 `0.3.1-beta2`。`net.forbric.api` 是内部 API，随时可能变动，不另行通知。

## 20. 延伸阅读

- [`forbric-kernel/README.md`](forbric-kernel/README.zh-CN.md) —— 内核自己的概述和闸门说明
- [`forbric-kernel/run/compat/PROTOCOL.md`](forbric-kernel/run/compat/PROTOCOL.md) —— 兼容性批量测试的流程
- [`forbric-loader/README.md`](forbric-loader/README.zh-CN.md)、[`forbric-loader/run/README.md`](forbric-loader/run/README.md) —— 第一代，以及产物流水线
- [`forbric-loader/CREDITS.md`](forbric-loader/CREDITS.md)、[`forbric-loader/MAPPINGS.md`](forbric-loader/MAPPINGS.md) —— 净室边界与在映射上的立场
- 类的 javadoc。`net.forbric.kernel` 下几乎每个类的开头都写着它是为了哪个故障而存在的。

Forbric 与 Mojang、FabricMC、MinecraftForge、NeoForged 均无关联。
