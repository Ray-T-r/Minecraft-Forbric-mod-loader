# Forbric

[English](README.md) | 简体中文

**一个 Minecraft 实例，同时运行 Fabric mod、Forge mod 和 NeoForge mod。**

版本 0.3.0 · Minecraft 26.2

## 它能做什么

Minecraft 的 mod 分三种，通常你只能选其中一种。每个 mod 都是为 **Fabric**、**Forge** 或 **NeoForge** 中的某一个做的，而且只能在它对应的那一个上运行。把 Fabric mod 放进 Forge 游戏里，什么也不会发生。所以大多数人会同时维护好几套互相独立的环境，而不管启动哪一套，大部分 mod 都躺在另外几套里。

Forbric 是第四种东西，装了它就不用再装那三个。你把**所有** mod 放进**同一个**文件夹——Fabric、Forge、NeoForge 混在一起，不用分类，Forbric 会打开每个文件，判断它是哪一种，然后加载它。所有 mod 都在同一个世界里同时运行。

它还会把你装的所有东西列在同一张列表里。暂停菜单和标题界面上会多出一个 Forbric 的 Mods 按钮；在这张列表里，不管一个 mod 属于三种中的哪一种，你都能打开它自己的设置界面（Fabric mod 需要同时装了 Mod Menu 才行）。

**你也许听说过 Kilt 或 Sinytra Connector。** 它们是装在普通加载器上的 mod，在一方内部重新实现另一方的功能——相当于屋里请了个翻译。Forbric 则是加载器本身。Fabric Loader，以及 Forge 和 NeoForge 里面自带的加载器，都不会启动；它们的活由 Forbric 来干——找到 mod、启动 mod、按顺序运行 mod——并尽量按每个 mod 自己的加载器那样去做。你的 mod 调用的是真正的 Fabric API，以及真正的 Forge 和 NeoForge 代码；这一部分不是重新实现的。所以这不是三个加载器并排运行，而是一个新的加载器，把三种 mod 和它们依赖的真实代码放进同一个游戏。

不过 Forge 和 NeoForge 都会修改 Minecraft，而且常常改的是同一个地方，而一个游戏里每个地方只能保留一个版本，所以 Forbric 大多保留 NeoForge 的版本。然后由 Forbric 自己的衔接代码让其他 mod 继续工作：把游戏事件转交给 Forge mod，把 Fabric mod 的修改挪到代码现在所在的位置，并让来自不同加载器的 mod 互相传递物品、流体和能量。这些衔接代码同样是一种翻译，而且还没有完成，这也是一部分 mod 仍然失败的原因之一。

Connector 已经很成熟，Forbric 还不是，所以如果 Connector 已经能运行你想要的 mod，就用 Connector。

## 安装方法

### 准备工作

- **一个从 `.minecraft/versions` 文件夹启动版本的启动器。** Forbric 已在 Windows 上用 **PCL2** 测试过。HMCL 读取的是同样的文件，应该也能用，但还没有测试过。官方 Minecraft 启动器也还没有测试过（见第 6 步）。Prism Launcher 和 MultiMC 使用各自独立的实例，看不到 Forbric。
- **Java。** 如果你已经能玩 Minecraft，你就已经有了。就算你从没自己装过 Java，安装器也能找到你的启动器下载的那一份。
- **网络连接**，以及安装过程中约 730 MB 的可用磁盘空间（完成后约保留 190 MB）。

你**不需要**先装 Minecraft 26.2。如果没有，安装器会自动下载。你也**不需要** Fabric、Forge 或 NeoForge，也不用去找其他任何文件：Forbric 需要的一切都由安装器下载并构建。不过你的 mod 照常还需要各自的前置 mod，比如大多数 Fabric mod 都需要 Fabric API。

### 安装

1. 打开[最新发布版](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/latest)。

2. 把**两个**文件下载到**同一个文件夹**：

   | 你的系统 | 下载 |
   | --- | --- |
   | Windows | `forbric-kernel-installer-0.3.0.jar` **和** `Forbric-Installer.bat` |
   | macOS | `forbric-kernel-installer-0.3.0.jar` **和** `Forbric-Installer.command` |
   | Linux | `forbric-kernel-installer-0.3.0.jar`（用 `java -jar` 运行） |

3. **双击 `.bat`（Windows）或 `.command`（macOS）。** 它会查找 Java，包括启动器放在默认 Minecraft 文件夹里的那一份，然后用它启动安装器。在某些 Windows 电脑上，直接双击 jar 只会闪一下黑窗口，这是因为 Windows 曾被设置成用一种行不通的方式打开 `.jar` 文件；用脚本就能避开这个问题。如果双击 jar 确实打开了安装器窗口，那也没问题：是同一个安装器。

   在 macOS 上第一次运行时，你可能需要右键点击这个文件，选择**打开**，然后确认。这是 macOS 对下载的文件比较谨慎，并不是出错了。

4. **会弹出一个窗口。** 唯一需要关心的是 **Game directory**（游戏目录）这一栏——也就是你的启动器使用的 `.minecraft` 文件夹。它一开始就填好了你的系统上通常的位置：

   - Windows：`C:\Users\<your name>\AppData\Roaming\.minecraft`
   - macOS：`~/Library/Application Support/minecraft`
   - Linux：`~/.minecraft`

   如果你的启动器把 `.minecraft` 放在别处（PCL2 和 HMCL 可以把它放在启动器程序所在的文件夹里），就点 Game directory 旁边的 **Browse…**，选择那个文件夹。

   其他设置都不要动。尤其是 **Built artifacts** 要留空——它只给从源码构建了 Forbric 游戏文件的开发者使用。

5. **点击 Install，然后等待。** 第一次安装需要几分钟。它在下载 Minecraft、Forge 和 NeoForge 各自的文件，并在你的电脑上把它们组装起来，因为按照法律，这些文件不能做成现成的包直接分发。安装期间请保持联网。之后再安装会复用磁盘上已有的文件，很快就能完成。

6. **打开你的启动器。** 列表里会出现一个名为 **`26.2-forbric`** 的新版本。像启动其他版本一样启动它即可。PCL2 会把它显示成 Fabric 版本，这是正常的（见下一节）。

   安装器不会把它加进官方 Minecraft 启动器的安装列表。在官方启动器里，你大概得自己新建一个安装，并选择 `26.2-forbric`。

> 想在点击 Install 之前先检查一下你的电脑？在 jar 所在的文件夹里运行这条命令。它只做检查，不写入任何东西：
>
> ```bash
> java -jar forbric-kernel-installer-0.3.0.jar --doctor
> ```

### mod 放在哪里

**Fabric、Forge 和 NeoForge 的 mod 都放进同一个 `mods` 文件夹。** 具体是哪个文件夹取决于你的启动器，而不是 Forbric：

- 如果你的启动器让每个版本各自独立（通常叫“版本隔离”；PCL2 和 HMCL 都能这样设置）：`.minecraft/versions/26.2-forbric/mods/`
- 否则就是你所选 Game directory 里共用的 `.minecraft/mods/`。所有没有独立文件夹的版本都用这个文件夹，所以 Forbric 也会尝试加载里面已有的 mod。

安装器完成时会把这两个位置都列出来，这需要根据你自己的启动器设置来自行判断。

你的启动器可能会把 `26.2-forbric` 称为 Fabric 版本。这是有意为之：因为我在测试的时候发现有些启动器在使用的时候（比如PCL2）原版的 Minecraft 不能使用启动器自带的自动安装 mod 功能，所以让它识别为 mod 版本。

有一点要注意：很多 mod 同时有 Fabric 版、Forge 版和 NeoForge 版。每个 mod 只放**一个**版本进文件夹。如果你放了不止一个，Forbric 仍然只会运行其中一个。第一次遇到这种情况时，它会把自己的选择写进 `mods` 文件夹旁边的 `forbric-mods.txt`，你可以在那里改选另一个版本。

多个 mod 共同需要的前置（库）mod 也是一样：通常一个版本就够了，因为 Forge 或 NeoForge 的 mod 一般可以使用其前置 mod 的 Fabric 版，反过来也一样。为两个加载器各装一份前置，并不会让每个 mod 各用各的：Forbric 仍然只运行其中一份。如果一个 mod 直接挂接在另一个 mod 上（比如 Iris 之于 Sodium），两者要用同一个加载器的版本。关于 Sodium，另见下文的*一个已知的崩溃*。

### 装好了吗？

打开暂停菜单。那里有一个画着**三个叠在一起的方块**的按钮，提示框上写着 *Mods (Forbric)*。点开是一张列表，列出你装的所有 mod，每一行都标明了它是哪一种。选中一个 mod 后点 **Config**，或者双击那一行，就能打开这个 mod 自己的设置。

Fabric mod 把自己的设置界面交给 Mod Menu 管理，所以只有同时装了 Mod Menu，Fabric mod 在这张列表里才会有 **Config** 按钮。装了 Mod Menu 后，标题界面和暂停菜单上都会有**两个** Mods 按钮。请用画着三个方块的那个：它能打开三种 mod 的设置，而 Mod Menu 自己的按钮只能打开 Fabric mod 的设置。

### 出了问题怎么办

| 你看到的情况 | 怎么做 |
| --- | --- |
| **弹出窗口说某个 mod 缺少它需要的前置** | 窗口里会写出是哪个 mod、需要装什么。装上它，或者点 **继续启动**。 |
| **弹出窗口说必要的 mod 功能不可用** | mod 的某个部分没能启动。你可以继续玩，也可以退出游戏并移除那个 mod。 |
| **游戏崩溃** | 打开 `mods` 文件夹旁边的 `.forbric-kernel` 文件夹。里面的 `crash-analysis.txt` 会列出嫌疑最大的 mod；完整的崩溃报告在 `crash-reports/` 里。移除这些 mod 后再试一次。**0.3.0 还没有这个功能：**下次启动时会弹窗问你要不要不加载这些 mod 启动。选了的话，它们的文件名会写进 `mods` 文件夹旁边的 `forbric-disabled.txt`；想重新启用哪个 mod，就把它那一行删掉。用的是 NeoForge 版的 Sodium？请看下文的*一个已知的崩溃*。 |
| **mod 装上了，但没有任何效果** | 打开 Forbric mod 列表——没加载完的 mod 会在那里被标出来。同样的列表也在 `load-report.txt` 里，位于 `mods` 文件夹旁边的 `.forbric-kernel` 文件夹中。常见原因是这个 mod 是为别的 Minecraft 版本做的，或者你装了它的两个版本。 |
| **专用服务器启动不了**，日志说是兼容策略让它停下的 | 服务器没有界面可以询问你，所以会直接停下。移除它点名的 mod，或者在服务器的启动命令里加上 `-Dforbric.compatibilityPolicy=continue`，强行继续运行。 |
| **Continuity 加载了，但玻璃方块之间仍然有边框** | 在 **选项 → 资源包** 中启用 **Default Connected Textures**（Continuity 自带）。它内置的资源包是可选的，光装上 mod 并不会自动启用。在 0.3.0 上，即使这样做了，Fabric 版的 Continuity 仍可能留下边框。这是 Forbric 的 bug。0.3.1 beta 已经修复（Releases 页面上的预发布版），但还没有进入正式版。另一个选择是使用为你的 Minecraft 版本制作的 NeoForge 版 Continuity。 |
| **安装好像卡住了** | 通常是你和 Mojang 服务器之间有代理或 VPN。运行*安装*一节末尾的 `--doctor` 检查，然后关掉代理或 VPN 再试一次。 |

遇到其他问题？可以在[这里](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/issues/new?template=bug_report.yml)反馈。

### 更新与卸载

**更新**：用相同的设置运行新的安装器。你的 mods 文件夹和世界都不会被动到。从 0.2.0 升级后的第一次安装会重新构建 Forbric 的游戏文件，所以又要花上几分钟。

**卸载**：删除 `.minecraft/versions/26.2-forbric/`。如果你的启动器让每个版本各自独立，这个文件夹里还存着这个版本的 mod、世界和设置，所以请先把想保留的东西复制出来。如果还想收回磁盘空间，再删除 `.minecraft/.forbric-build/` 和 `.minecraft/libraries/net/forbric/`。

## 0.3.0 更新内容

**能用的 mod 更多了。** 我们从 Modrinth 随机挑了三批 mod，每批约 100 个（既有热门的，也有随机的，三种都有），然后每次只装其中一个 mod 来启动游戏。**0.2.0 上有 80.5% 无错误加载，0.3.0 上是 89.0%**（判定标准：日志里没有 mod 加载失败）。在 0.3.0 上，有 91.8% 能进入世界，有 79.1% 同时做到了没有任何部分被报告为无法工作。这个测试只检查 mod 能不能加载、世界能不能打开；不会逐个试 mod 的功能，也不测多个 mod 放在一起。

新增：

- **不同加载器的 mod 之间可以互相传递物品、流体和能量**——比如 Fabric 的管道或漏斗可以给 NeoForge 或 Forge 的机器供料。
- **当 mod 的某个部分无法工作时，开始游戏前会弹出窗口**，让你选择继续还是退出，而不是事后才发现。
- **Forbric mod 列表会标出没加载完的 mod**，并说明原因。
- **崩溃之后会生成 `crash-analysis.txt`**，列出最可能导致崩溃的 mod。
- **警告窗口支持 10 种语言**，包括中文，并会建议你该装什么。
- Forbric 现在使用正式版的 NeoForge 而不是 beta 版，所以需要较新 NeoForge 的 NeoForge mod 也能加载了，标题界面上也不再显示“beta”。

修复：

- 以下情况下的崩溃：装有 Fabric API 时用熔炉冶炼、合成或酿造，与末影龙战斗，使用花盆，或放置来自 Fabric 或 Forge mod 的流体。
- 某些 mod 组合曾让游戏在启动时停在黑屏。
- 之前地牢不会生成，同一个种子生成的地形也与原版 Minecraft 不一致。
- 物品提示框里之前缺少附魔、物品描述（lore）、属性和耐久度。
- 许多 Forge mod 加载了却不起作用——现在它们的命令、按键绑定、屏幕上的显示内容、配置文件、生物和对世界的改动都能正常工作了。
- Xaero's Minimap 和 World Map（Forge 版）曾在启动时崩溃。
- 在 0.2.0 上崩溃或失败、现在能正常工作的 mod 包括 Farmer's Delight Refabricated、Better End、Better Nether、Entity Culling、More Culling、Friends & Foes、Repurposed Structures、Traveler's Backpack、Shoulder Surfing 和 YetAnotherConfigLib。

不如 0.2.0 的地方：在同一测试中，**Alex's Mobs Continued**、**Drippy Loading Screen**、**FancyMenu** 和 **Easy Magic**（NeoForge 版）在 0.2.0 上能用，在 0.3.0 上不能用了。

面向服主的改动：如果某个 mod 缺少它需要的部分，专用服务器现在会在启动时停下，因为没有界面可以询问你（见上文的*出了问题怎么办*）。

## 我们的承诺

>
> 仓库内的大部分代码为 Ai agent 生成，请理性看待。
>

**不会动你现有的 Minecraft。** Forbric 与其他所有东西并存安装。你的 Fabric、Forge 和 NeoForge 环境、你的世界、你的其他 mod 文件夹，都和原来一模一样。

**卸载就是删掉一个文件夹。** 它不会在你的系统里到处留下东西，你不玩游戏时也不会有任何东西在运行。

**没有任何隐藏。** 所有源代码都在这里，许可证是 Apache-2.0。本仓库不包含任何 Minecraft、Forge 或 NeoForge 的代码——这些都是在你安装时从它们各自的服务器上获取，并在你的机器上组装的。

我们**不**承诺的是：

**我们无法保证任何一个具体的 mod 能用。** 在我们自己的测试里，大约每十个 mod 中仍有一个单独运行就会失败；而各自单独能用的 mod，放在一起时也仍可能冲突。

**mod 的某个部分可能不崩溃也不工作。** 当 mod 的某一部分接不上游戏时，Forbric 会让这个 mod 的其余部分继续运行，而不是直接停下，并且通常会告诉你——在开始游戏前的窗口里，以及 Forbric 的 Mods 列表里。如果某一部分接上了、但行为不对，Forbric 和我们的测试都发现不了。

**一个已知的崩溃：** 除非同时装了 Fabric API，否则 NeoForge 版的 Sodium 会在启动时崩溃，需要它的 mod（例如 NeoForge 版的 Iris 和 Sodium Extra）也一样。这是 Forbric 的 bug，并不是你安装的方式有错。在修复之前，请把 Fabric API 也放进你的 `mods` 文件夹，或者改用 Fabric 版的 Sodium，以及挂接在它上面的那些 mod 的 Fabric 版。

**这是一个处于 0.3.0 版本的研究项目。** 没有技术支持，没有路线图，一切都还会变。

Forbric 与 Mojang、FabricMC、MinecraftForge 或 NeoForged 均无关联。

---

### 写给 mod 开发者

**你的 mod 不需要做任何修改。** 它调用的是真正的 Fabric API、MinecraftForge 或 NeoForge 类，所以不存在需要你专门针对它编写代码的兼容层。如果其他 mod 需要通过修改来兼容 Forbric 那也不是我想要的，Forbric 重新实现的是加载器：类加载、mod 发现、加载顺序、生命周期、Mixin 服务，以及 Fabric Loader 的 API（Forbric 自带 Fabric Loader 的公开 API 类型——这些类型保留 FabricMC 的版权——并实现了它们；Fabric Loader 本身从不运行）。游戏本身也不一样：安装器会用两个 Forge 系的补丁构建出一个合并后的游戏 jar。这对你的 mod 意味着：

- **游戏是一个合并后的 jar。** 凡是 MinecraftForge 和 NeoForge 都打了补丁的同一个方法（大约一千个），只保留了一个版本：除其中五个保留的是 MinecraftForge 的版本外，其余都保留 NeoForge 的。因此丢失了调用的事件——几乎都是 MinecraftForge 的，另有少数 NeoForge 的，比如物品提示框和界面打开——只有在 Forbric 重新发出时才会到达你的监听器，没有桥的则永远不会触发（[introduction.md §8](introduction.zh-CN.md#8-事件桥)）。调用在合并中保留下来的事件照常触发。NeoForge 自带的 coremod 不会被加载；它们所做的改写由 Forbric 自己完成。
- **mixin 会应用在这份合并后的代码上。** Forbric 会放宽 mod 的 mixin 配置（`required: false`、`defaultRequire: 0`），因此目标缺失的注入器会什么也不做，而不是报错失败，除非它自己设置了 `require`。目标挪了位置的注入器，Forbric 会把它跟着挪过去；如果一个 mixin 的目标全都不存在，就丢弃整个 mixin（[§7](introduction.zh-CN.md#7-合并基底上的-mixin)）。
- **启动按 Forbric 的顺序进行**，与各加载器原生的顺序接近，但并不相同（[§3](introduction.zh-CN.md#3-启动顺序)）。
- **不支持启动扩展：** MinecraftForge 的 ModLauncher 服务（`ITransformationService`、`ILaunchPluginService`）和 `coremods.json`、NeoForge 的 `ClassProcessorProvider`，以及自定义的 mod 定位器或依赖定位器。目前它们会被直接跳过，不会有任何警告。

更多细节：

- [introduction.md](introduction.zh-CN.md)——面向开发者介绍 Forbric 的内部工作方式：启动顺序、三种 mod 如何一起加载、安装器构建了什么，以及它是怎样测试的。
- [forbric-kernel/README.md](forbric-kernel/README.zh-CN.md)——内核的简要概述；安装器安装的就是内核。

要从源码构建内核，你需要 `git` 和 JDK 21 或更新版本。内核有自己的 Gradle 构建；引导侧的编译不需要 Fabric 底座：

```bash
git clone https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader.git
cd Minecraft-Forbric-mod-loader
cd forbric-kernel && ./gradlew build
```

**全新克隆上构建通过，并不代表游戏能启动。** 本地没有暂存的游戏 jar 时，运行时源码集和传输测试会被跳过，需要这些 jar 的测试也可能被跳过。CI 验证的是引导侧构建和独立的加载器构建；它不会启动 Minecraft。

要在开发用的游戏里运行当前源码，请安装 **JDK 25+ 和 Python 3.9+**，然后在仓库根目录运行（Windows 上把 `python3` 换成 `py`）：

```bash
python3 tools/dev.py client                 # automatically prepare dependencies, build and launch
python3 tools/dev.py server --accept-eula   # a separate local server instance
```

下载的文件、组装好的 jar 和实例都放在被忽略的 `forbric-kernel/.dev/` 目录下。也可以使用 Gradle 入口：先运行 `prepareDev`，再在另一次调用中运行 `runClient` 或 `runServer`。配置和测试覆盖范围见[内核开发指南](forbric-kernel/run/README.md)。`check` 包含工具自测；`integrationTest` 不接受被跳过的断言，并且需要完整的测试夹具。构建第一代 `forbric-loader/` 本身时，请运行 `./bootstrap.sh`；独立的合并工具和当前的内核开发流程都不需要它。

### 许可证

Apache-2.0——见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。净室边界的说明见 [forbric-loader/CREDITS.md](forbric-loader/CREDITS.md) 和 [forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md)。
