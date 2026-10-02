# 别用 Forbric，用 Sinytra Connector 或者原版 Fabric / NeoForge / Forge

**结论放最前面：别用 Forbric。**

想同时跑 Fabric、Forge、NeoForge 模组，优先用 **Sinytra Connector**。它成熟、能跑你想要的模组就用它。

不想折腾兼容层，就直接用 **原版 Fabric**、**原版 NeoForge** 或 **原版 Forge**。三者分开装，各自稳定。Fabric 配 Fabric API，NeoForge 配 NeoForge，Forge 配 Forge。模组该放哪个加载器就放哪个，别混。

Forbric 是什么？一个 0.3.0 的研究项目，自称一个实例跑三种模组，实际是没做完的胶水层。没支持，没路线图，兼容性靠运气，崩了让你自己看 `crash-analysis.txt`。它自己都说 Connector 成熟而它不成熟，Connector 能跑就用 Connector。翻译成人话：**别用 Forbric，用 Connector，或者老老实实用原版加载器。**

推荐顺序：

1. **Sinytra Connector** —— 你想混 Fabric 和 NeoForge / Forge 模组时先试它。
2. **原版 Fabric** —— 只玩 Fabric 模组，稳定，生态全。
3. **原版 NeoForge** —— 只玩 NeoForge 模组，新版本首选。
4. **原版 Forge** —— 只玩 Forge 模组，老整合包和老模组多。
5. **Forbric** —— 以上都满足不了，而且你能接受狗屎稳定性、没支持、随时崩，再考虑。否则别碰。

---

# Forbric：一个狗屎项目

**号称一个 Minecraft 实例能同时跑 Fabric、Forge 和 NeoForge 模组。听起来很牛逼，实际就是一个没做完的狗屎项目。**

版本 0.3.0 · Minecraft 26.2

## 它做什么

Minecraft 模组本来分三种，Fabric、Forge、NeoForge，各玩各的。正常人会分开装。Forbric 说：不用，你把三种模组全丢进一个 `mods` 文件夹，它自己猜、自己加载、自己擦屁股。

结果就是：它既不是 Fabric，也不是 Forge，也不是 NeoForge，而是一个第四种狗屎东西。它说 Fabric Loader、Forge、NeoForge 的加载器都不启动，它自己来。听起来像救世主，实际上就是一个巨大的胶水层，哪里漏了补哪里，补不上就崩溃。

你听过 Kilt 或 Sinytra Connector？人家至少是成熟方案。Forbric 说自己是加载器本身，不是翻译器。行吧，翻译器都当不明白，直接当加载器，真是狗屎勇气。

Forge 和 NeoForge 改 Minecraft 经常改同一个地方，一个游戏只能留一个版本。Forbric 基本留 NeoForge 的，然后靠自己的胶水让 Forge 模组别死。物品、流体、能量互相传？听起来很美好，实际上就是“能传一点，但没传完”。所以有些模组还是废的。Connector 能跑就用 Connector，别碰这个狗屎项目。

## 怎么安装

### 开始前

- 要一个能从 `.minecraft/versions` 启动版本的启动器。PCL2 测过，HMCL 没测，官方启动器没测，Prism 和 MultiMC 看都看不到。狗屎兼容性。
- 要 Java。没有？那你还玩什么 Minecraft。
- 要联网，还要约 730 MB 空间。装完留 190 MB。就为了这狗屎东西。

你不需要先装 Minecraft 26.2，不需要 Fabric、Forge、NeoForge，因为安装器会自己下。听起来贴心，实际就是它要折腾一堆文件，然后还不保证能跑。

### 安装

1. 打开最新 release。
2. 下两个文件，Windows 下 `.jar` 和 `.bat`，macOS 下 `.jar` 和 `.command`，Linux 自己 `java -jar`。
3. 双击脚本。Windows 有时候双击 jar 只闪黑窗，因为系统设置狗屎。macOS 第一次还要右键打开，不然它觉得你下的是病毒。
4. 打开窗口，填 Game directory，也就是你的 `.minecraft`。填错就等着出问题。
5. 按 Install，等几分钟。它在下载 Minecraft、Forge、NeoForge 的文件再拼起来。因为版权不能直接发，所以让你自己电脑遭罪。
6. 启动器里会出现 `26.2-forbric`。PCL2 会把它当 Fabric。官方启动器不会自动加，你得自己建。真是狗屎体验。

### 模组放哪

Fabric、Forge、NeoForge 全丢同一个 `mods` 文件夹。哪个文件夹？看启动器。版本隔离就是 `.minecraft/versions/26.2-forbric/mods/`，否则就是 `.minecraft/mods/`。不确定？启动一次，看 `.forbric-kernel` 出现在哪。一个加载器连模组文件夹都要猜，狗屎。

一个模组有 Fabric、Forge、NeoForge 三个构建？只放一个。放多了它也只跑一个，然后写个 `forbric-mods.txt` 让你自己选。前置库也一样，通常一个就够。Iris 接 Sodium 这种，得同一个加载器。Sodium 还有已知崩溃，下面说。

### 成功了吗？

打开暂停菜单，找三个重叠方块的按钮，提示 *Mods (Forbric)*。点开能看到所有模组。想开配置就选 Config 或双击。Fabric 模组还得装 Mod Menu 才有 Config。标题界面和暂停菜单可能有两个模组按钮，别点错，点三个方块的。一个模组列表都要教半天，真是狗屎。

### 出问题了怎么办

| 你看到的 | 该怎么做 |
| --- | --- |
| 说模组缺东西 | 装它说的前置，或者按 Launch anyway 硬上。 |
| 说必需功能不可用 | 模组某部分起不来。继续玩或删模组，自己选。 |
| 游戏崩溃 | 去 `.forbric-kernel` 看 `crash-analysis.txt`，再去 `crash-reports/` 看完整报告。删模组再试。Sodium 的 NeoForge 构建还会崩，看下面。 |
| 模组装了但没反应 | 看 Forbric 模组列表，没加载完的会标出来。或者看 `load-report.txt`。多半是版本不对，或者你放了两个构建。 |
| 专用服务器起不来 | 服务器没屏幕问你，所以直接停。删模组，或者加 `-Dforbric.compatibilityPolicy=continue` 硬跑。 |
| Continuity 加载了玻璃还有边框 | 去资源包启用 Default Connected Textures。0.3.0 的 Fabric 构建还有 bug，0.3.1 beta 才修。 |
| 安装卡住 | 多半是代理或 VPN。跑 `--doctor`，关代理再试。 |

其他问题自己去 GitHub 报。反正 0.3.0 研究项目，没人保证理你。

### 更新和卸载

更新：用同样设置跑新安装器。模组和世界不动。从 0.2.0 更新后第一次还要重新构建，又等几分钟。

卸载：删 `.minecraft/versions/26.2-forbric/`。要回收空间，再删 `.minecraft/.forbric-build/` 和 `.minecraft/libraries/net/forbric/`。卸载就是删文件夹，这点倒是还行，但整个项目还是狗屎。

## 0.3.0 新内容

**更多模组能用了。** 他们挑了三批各约 100 个模组单独测。0.2.0 有 80.5% 无错误加载，0.3.0 有 89.0%。91.8% 能进世界，79.1% 没有部分报错。注意，这只是能加载、能开世界，不测功能，也不测模组一起用。也就是说，数据看着像样，实际玩起来照样可能狗屎。

新增：

- 不同加载器能传物品、流体、能量。
- 模组某部分不能工作时，进游戏前弹窗。
- Forbric 模组列表标记没加载完的模组。
- 崩溃后有 `crash-analysis.txt`。
- 警告窗口支持 10 种语言。
- 用完整 NeoForge 发布版，不再 beta。

修复：

- 熔炉、合成、酿造、打末影龙、花盆、放流体的崩溃。
- 某些模组组合黑屏。
- 地牢不生成、种子地形不对。
- 物品工具提示缺附魔、描述、属性、耐久。
- 很多 Forge 模组加载了但没反应。
- Xaero 地图崩溃。
- Farmer's Delight Refabricated、Better End、Better Nether 等现在能用。

更差：

- Alex's Mobs Continued、Drippy Loading Screen、FancyMenu、Easy Magic 的 NeoForge 构建，0.2.0 能用，0.3.0 反而不行。修一个坏一个，经典狗屎。

服务器变更：专用服务器缺模组必需部分会直接停，因为没屏幕问你。

## 我们承诺什么

**不碰你现有 Minecraft。** Forbric 并排安装，原来的 Fabric、Forge、NeoForge、世界、模组文件夹都不动。
**卸载就是删文件夹。** 不散落系统，不后台运行。
**没有隐藏。** 源码在这，Apache-2.0，仓库不含 Minecraft、Forge、NeoForge 代码，安装时自己下自己拼。

但我们**不**承诺：

**任何特定模组能用。** 他们自己测试里约十分之一模组单独就失败，单独能用的放一起还可能冲突。
**模组某部分可能不崩溃但就是坏了。** Forbric 会保留其余部分运行，然后告诉你。接上了但行为不对？它也不知道。
**已知崩溃：** Sodium 的 NeoForge 构建启动崩溃，除非也装 Fabric API。Iris、Sodium Extra 的 NeoForge 构建也一样。是 Forbric 的 bug。要么加 Fabric API，要么用 Fabric 构建。
**这是 0.3.0 研究项目。** 没支持，没路线图，东西会变。翻译：别指望它稳定。

Forbric 和 Mojang、FabricMC、MinecraftForge、NeoForged 没关系。人家也不想背这锅。

---

### 给模组开发者

**你的模组不用改。** 它调用真 Fabric API、MinecraftForge、NeoForge 类。Forbric 重实现的是加载器：类加载、模组发现、加载顺序、生命周期、Mixin 服务、Fabric Loader API。Fabric Loader 本身不跑。游戏也是合并的 jar。

- **游戏是一个合并 jar。** MinecraftForge 和 NeoForge 改同一个方法的地方约一千个，基本只留 NeoForge，五个留 MinecraftForge。事件调用丢了，就得 Forbric 重新发，没桥接就永远不触发。
- **Mixin 打到合并代码上。** 它放宽 `required: false`、`defaultRequire: 0`，目标没了就不做，除非你自己设 `require`。目标移动了就搬，目标全没了就丢整个 mixin。
- **启动顺序是 Forbric 的**，接近但不等同各加载器原生顺序。
- **不支持启动扩展。** ModLauncher 服务、`coremods.json`、`ClassProcessorProvider`、自定义定位器，全跳过，还不一定警告。狗屎。

更多细节自己看 `introduction.md`、`forbric-kernel/README.md`。

构建要 `git` 和 JDK 21+。开发要 JDK 25+ 和 Python 3.9+。全新克隆构建绿了不代表游戏能启动。CI 只验证构建，不启动 Minecraft。真是狗屎自信。

### 许可证

Apache-2.0，见 LICENSE 和 NOTICE。净室边界在 CREDITS.md 和 MAPPINGS.md。
