# Forbric 内核

[English](README.md) | 简体中文

一个**自主**的统一 Minecraft mod 加载器，在同一个 Minecraft 26.2 实例上运行 **Fabric + 传统 MinecraftForge + NeoForge** 的 mod。它从零重写了 `forbric-loader` 的“焊接方案”。

做源码开发的话，先看[开发指南](run/README.md)。在仓库根目录运行 `python3 tools/dev.py client`，会准备一个隔离的游戏环境，然后启动当前的内核。Gradle 还提供 `prepareDev`、`runClient`、`runServer`、`toolTest` 和 `integrationTest` 这几个任务。

## 为什么重写

`../forbric-loader` 做到三合一的办法是*把三个真正的独立加载器焊接在一起*：以真正的 fabric-loader/Knot 为宿主（8 个底座补丁），借助反射冒充，并排驱动真正的 FML 和 FancyModLoader，底下是一个按字节合并出来的 3-ABI 游戏基底。这套方案能用，但对 38 堵墙做的一次根因普查表明，**约 53% 的冲突直接由这种架构造成**（生命周期焊接 + 多条流水线的字节合并）。本内核删掉了焊接方案：**一个**转换型类加载器，**一个**生命周期状态机，**一套**注册表/标签模型；三个生态都只是构建在内核自有服务之上的适配器。真正的加载器生命周期从不启动，Forge/NeoForge 的通用 jar 只是被动的 ABI 载体。

重写*消除不了*的两类墙是第三方 mixin/ABI 与合并基底不匹配（C），以及跨生态的注册表/标签/资源/网络语义（D）。它们成了内核的一等工作线：mixin 适配器层；只冻结一次的注册表模型，它让“Tags not bound”在结构上不可能发生。

构建它时依据的里程碑计划不在仓库里。不过计划断言的内容都在，就是 `run/` 下的闸门脚本：每个里程碑一个，各自对真实实例的真实日志做断言。

## 架构（两侧，没有 JPMS 模块层）

- **引导侧**（系统类加载器，`forbric-kernel.jar`）：类加载、发现、依赖解析、转换流水线、Mixin 服务、映射、访问权限、元数据，以及内置的 `net.fabricmc.api.*` 接口面。不含任何游戏类型。
- **游戏侧**（`ForbricClassLoader`，唯一的转换型类加载器，`forbric-kernel-runtime.jar`）：所有引用 `net.minecraft.*` / `net.minecraftforge.*` / `net.neoforged.*` / `net.fabricmc.fabric.*` 的代码，也就是注册表、生命周期、事件、网络、资源这些机制。这一侧针对暂存的 jar 编译，并以 JiJ 方式内嵌 MixinExtras。

旧的“Knot 类加载器分离”法则留了下来，成了内核自己的引导侧↔游戏侧分离。

**游戏侧现状**：已填满。`src/runtime/java`（撰写本文时有 130 个文件，最新数量见 introduction.md §2）针对暂存的 jar 编译，作为 `forbric-kernel-runtime.jar` 分发。里面有注册表和生命周期驱动、两个 Forge 系的初始化阶段、条件求值器、包来源，以及统一的 Mods 界面。本文件的早先版本说它是空的。写那句话时确实如此，后来情况变了，那句话却一直没改。

注入的字节码仍会直接调用一些引导侧的静态成员。还有少数类仍由引导侧的工厂（`KernelModContainerFactory`、`KernelHudBridge`、`KernelGameLookup`）在运行时用 ASM 合成。这些是根本没法编译的情况，每一处都在所在位置写明了原因。

## 统一 API（`net.forbric.api`）

正在按领域逐个扩充。它是内核和三个兼容层共同对齐的词汇与服务，免得各层两两互相迁就。它在 `DelegationPolicy` 里固定交给父加载器，理由和 `net.fabricmc.api.` 一样：每个 JVM 里恰好只有一份。

| 类型 | 取代了什么 |
|---|---|
| `Ecosystem` | 五个不同的生态枚举（两个拼写不同，一个缺了 NeoForge，一个已经没人用），外加一段手写的互相转换代码 |
| `ForeignType` | 每个调用点上成对相邻的 Forge/NeoForge 类名字面量 |
| `DiscoveredMod`, `UnifiedDependency` | 从 `kernel.metadata` 移到这里；唯一的 mod 模型 |
| `ModPresence` | `boot.KernelForeignMods`；“mod X 是否在运行”的唯一答案，注入的字节码现在通过 `net/forbric/api/ModPresence.isLoaded` 调用它 |
| `ModCatalog` | 玩家看到的唯一列表，以及每个 mod 的最终结果：`OK`、`DEGRADED` 或 `FAILED` |

它建立在两条规则上，两条都是吃过亏才学到的：

- **中枢把各 Forge 系之间的分歧当作数据保留，不取平均把它抹掉**。早先一版统一的订阅者注册一下子弄坏了三样东西（见 `KernelEventSubscribers` 自己的 javadoc）。所以 `ForeignType` 只映射*名字*，两个 `ServerModLoader.load` 触发点保留各自不同的描述符和不同的钩子。
- **不是每次归并都意味着漏了拆分。**`LoaderProbePolicy.Family` 故意只有两个取值：一个 NeoForge mod 探测 MinecraftForge 的 `FMLLoader` 时，仍然必须得到肯定的回答。

## 兼容契约

兼容转换按公开 API 契约和已证明的字节码结构匹配，不按第三方 mod 的 ID、包名、私有回调名或版本哈希放行。同一种回调结构换成新的 mod，仍使用同一个适配器；有歧义或无法证明安全的结构保持原样，并交给兼容性诊断。测试包含真实回调改包名、改方法名后的执行，以及必须拒绝的近似结构。

工作线程通过已证明的启动、排空和退出流程，或 `ManagedWorkerResources.register` 注册生命周期，不再反射某个 mod 的懒加载单例。注册表初始化按完整循环记录回调，正常返回才发布；注册窗口关闭后只补新增对象，不重复初始化已有对象。公开 entrypoint 和 API 的名称仍作为协议边界保留。第三方 mod 互相争用同一指令的冲突会被报告，不再由加载器内置具名优先级决定谁丢功能；结果与 Mixin 自己处理这两个 mod 时一致：一个 mod 的注入器拿走了另一个 mod 在配置插件 `postApply` 里用原始 ASM 寻找的调用时，Mixin 先应用所有注入器、后调用 `postApply`，所以注入器保有该调用、补丁什么也找不到，`ContendedCallSites` 会点名两个 mod 和这条调用。

## 共用部分（沿用，未重写）

合并基底的流水线留在 `../forbric-loader` 里：`src/tools/{MergedBaseBuilder,MergedLinkChecker,
RuntimeInteropPatcher}` + `run/{build-merged-base,assemble-*-runtime}.sh` 产出 3-ABI 游戏 jar 和被动运行时 jar。内核使用这些产物（编译时用 compileOnly 提供类型，启动时由运行环境提供）。LGPL 和 Mojang 衍生的产物从不打包进来。

## 里程碑

`run/` 下的闸门脚本针对真实实例的真实日志和产物做断言。这里原来有一张里程碑表，列出了从未存在过的 `gate-m5.sh` 和 `gate-m6.sh`，而且 M2 以及 M4 往后的各项，闸门早已通过很久，表里却还记为未完成。所以现在脚本本身就是清单：

| 闸门 | 证明了什么 |
|---|---|
| `gate-m0` | 构建 + 整个单元测试套件（依据 JUnit XML 断言，不看 gradle 的退出码）+ 发现结果与一个独立解析器的比对 |
| `gate-m1` / `gate-m3` | 合并基底在零 mod 下启动到 Done；两个 Forge 系的基线 + 一个真实的 `@Mod`，以原生方式加载 |
| `gate-m2` / `gate-m2b` | 先是一个真实的 Fabric mod，再是完整的 fabric-api，全程没有任何 Fabric Loader |
| `gate-m4` / `gate-m4-canary` / `gate-m7-neo` | 三个生态的真实第三方 mod 同在一个服务端；纯 NeoForge |
| `gate-m9-client` | 客户端这一半：97 个 jar 的整合包进入世界、统一的 Mods 界面、干净退出 |
| `gate-m12` … `gate-m16` | 走真实 socket 的多人游戏、一款反作弊的判定、纯 Fabric 服务端、两个 Forge 系的网络 |
| `gate-m17` | 安装器，按启动器的方式解析并启动 |
| `gate-m24` | 一个故意失败的 mod：其余 mod 照常加载，失败也能归因 |
| `gate-m24c` | 写进 `forbric-disabled.txt` 的 jar 谁都不加载，并在 `load-report.txt` 里点名；对照组照常加载它 |
| `gate-m25-worldgen` | 两个 Forge 系的生物群系修改器金丝雀 mod 在保存下来的区域文件里留下各自不同的方块 |
| `gate-m26-forgeclient` | Forge 客户端收到按键、渲染器、着色（tint）、提示框、几何加载器和创造模式物品栏的注册事件，并且 CLIENT_INIT/REGISTRATION 的桥清单报告完整 |
| `gate-m27-frame` | 97 个 jar 的客户端产出一张新生成的、不是黑屏的 Minecraft 截图 |
| `gate-m28-forgeconfig` | Forge 的 COMMON 配置只加载一次，随后它原生的文件监视器读到对文件的实时修改；专用服务器从不打开 CLIENT 配置 |
| `gate-m29-forgecaps` | 合并基底上的 MinecraftForge capability：僵尸和熔炉的 item-handler capability，并补跑了丢失的字段初始化器 |
| `gate-m30-attribution` | 只丢了自身**一部分**的 mod（某个 mixin 被排除或在创建世界时失败、某个订阅者无法注册、某个监听器挂在死事件上、某个延迟任务抛出异常、某个 jar 针对另一版 NeoForge 编译），日志、Mods 界面和 `load-report.txt` 都会点名该 mod 并给出原因；`load-report.txt` 会在世界启动后重写一次 |
| `gate-m31-vanilla-parity` | 在零 mod、同一种子下，Forbric 的主世界和旁边同时生成的纯原版 26.2 服务端有相同的生物群系和相同的结构起点 |

`run/compat/gates-all.sh` 用 glob 找出所有闸门，按编号顺序运行，网络/GUI 闸门也包括在内；有意加上的 `--skip <script.sh>` 会打印在结果里。它默认同时跑多个（`-j auto`；`-j 1` 就是旧的严格串行运行），在这台机器上把跑完一整轮的时间从 26 分钟缩短到 7 分钟。`auto` 是每两个核心一个槽位：内存从来不是限制因素（峰值时游戏 JVM 合计 5.5 GB，机器有 16 GB），让闸门陷入饥饿才是。在 `-j 7` 时，一次正常情况下远在 `await_server` 宽限窗口之内就能完成的关闭不再能按时完成，而这个窗口本身就是对非守护线程泄漏的一项真实检查。

让它们并行不是没有代价的，每个闸门都在靠近开头的一行里写明自己需要什么：

```sh
# GATE-PARALLEL: rundirs=server-kernel,canary mem=1500
```

`rundirs` 写明它运行期间独占哪些目录（写了同一个名字的两个闸门从不一起运行），`mem` 是它占用的内存。`clone=<dir>:<VAR>` 则是申请共享夹具的一份私有副本：需要 `run/client-merged-pack` 的四个闸门各拿一份，在 APFS 上克隆那个 434 MB 的安装目录只要 0.17s，而且不占磁盘。之所以要有这一行，而不是直接用 `xargs -P`，是因为有三种冲突。第一，每个启动服务端的闸门都会把端口写进 `server.properties`，端口争抢的落败方会打印 `FAILED TO BIND TO PORT`，写一份崩溃报告，然后照样打印 `Stopping server`，于是干净关闭的断言通过，闸门改在 “Done” 那一条上变红，看起来像内核没能启动，而不是端口被占（`lib.sh` 的 `port_was_free` 现在会直接说出是哪个端口）。第二，`gate-m1`/`m2`/`m3` 共用 `run/server-kernel`，`gate-m9`/`m17`/`m22`/`m23`/`m27` 共用 `run/client-merged-pack`。第三，每个内核 JVM 启动时都不带 `-Xmx`，所以各自继承 JVM ergonomics 给出的“物理内存四分之一”上限。调度器给每个并发槽位分配独立的端口段，从不把写了同一个 rundir 的闸门排在一起，并把正在运行的集合控制在内存预算之内。**没有 `# GATE-PARALLEL:` 行的闸门会单独运行**，运行时也会在 stderr 上说明这一点。新闸门只会变慢，不会悄无声息地坏掉。

可移植的 Windows 基线采集和负控制见[兼容性协议](run/compat/PROTOCOL.md)。

其余闸门（`m8`、`m10`、`m11`、`m18`–`m23`）各自锁定一个曾经发布出去的缺陷。上次统计时，二十五个闸门里有十六个已有一天没跑过，其中一个一直是红的。所以 `gate-m0` 现在拒绝为没有实际执行的测试任务出报告。

旧的 `forbric-loader` 仍保持可运行，用作**差分对照基准**，并且仍负责构建内核所用的共享游戏产物。它无法验证被移除的有状态父类；声明 `required-ancestor-compositions.tsv` 的基底必须使用已注册状态协议证明的内核。

## 通用兼容规则与协议扩展

Mixin 的跳过、接口保留、调用重定位和局部变量选择来自实际源字节码、当前目标和控制流证据。类名、mod id、处理器名或固定 ordinal 不再作为这批兼容规则的许可条件；无法证明的回调不会被搬到另一个事件顺序。原生参考索引中的哈希用于验证证据来源，不用于限制某个 mod 版本。

可选 SDK 通过 `META-INF/services/net.forbric.api.ProtocolExtension` 提供独立协议适配器，每个游戏类加载器拥有自己的注册表。新增协议可以提供缺失 API、注册转换、接收配置生命周期和提供配置界面。合并掉有状态父类时，产物会声明 `required-ancestor-compositions.tsv` 要求；`AncestorComposition` 必须证明最终定义保留了该状态协议，否则加载会明确失败。

共享 API 提供 `VirtualGetters`，按完整 JVM 返回类型选择公开 getter；`VirtualProperties` 根据已定义的公开 getter 证明可写存储。它们可以用于任意声明类，并保留虚调用分派；属性写入前必须先获得实际 getter/字段证据。协议提供者在构造时不能链接游戏类，应通过 `Context.gameLoader()` 解析游戏对象，具体约定见 `ProtocolExtension`。

## 构建与运行

```sh
./gradlew --offline test          # the unit suite; ~a third of it reads the staged game jars and
                                  # SKIPS without them, which is why gate-m0 asserts on the results
./gradlew --offline jar           # boot jar
./run/gate-m0.sh                  # build + the suite (tests>0, no failures, skip ceiling) + the oracle
# unified discovery over a mods/ dir → deterministic JSON (the oracle anchor):
java -cp <boot-cp> net.forbric.kernel.boot.Main --scan --mods <dir> --report out.json
```

构建产出 Java 21 字节码（`options.release = 21`），游戏本身需要 Java 25。依赖来自 maven.fabricmc.net + Maven Central。

## 许可证

Apache-2.0（`LICENSE`）。内置的 Fabric Loader 源码的署名，以及对 FML/FancyModLoader 的净室立场，见 `NOTICE`。
