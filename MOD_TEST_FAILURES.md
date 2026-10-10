# Mod 兼容测试：不成功的 mod（待修）

测试日期：2026-09-26 至 2026-09-27
加载器：Forbric main `11ca1ffa`，用 `forbric-kernel-installer` 装进 Mac 官方目录 `~/Library/Application Support/minecraft`（版本 `26.2-forbric`）
下面的历史测试保留当时结果；后续修复及验证单独记录，不回写原始统计。

## 130 个纯 Fabric mod 复测：只在 Forbric 失败的从 7 个降到 0 个（服务端，2026-10-11）

还是 2026-10-03 那 165 个 jar，原生一侧的结果直接沿用（原生启动器的 SHA-256 一样）。Forbric 一侧换成 main `6d1dd0f2` 加上下面这条修复后的内核（SHA-256 `ce609c8a…`），合并基底用这棵树自己的 `tools/dev.py prepare --no-assets` 现建。证据在 `forbric-kernel/run/compat/reports/2026-10-11-pure-fabric-server/`。**仍然只测了服务端。**

| | 2026-10-03 | 2026-10-11 |
|---|---|---|
| 单独跑（各带依赖，200 tick） | 119 两边都过 · 7 只在 Forbric 失败 · 4 两边都失败 | **126 两边都过 · 0 只在 Forbric 失败** · 4 两边都失败 · 0 只在原生失败 |
| 原生能单独跑的 126 个一起（159 jar，1200 tick） | 原生 Done，Forbric 被策略拒绝 | **两边都跑满** |
| 全部 130 个一起（165 jar） | 两边都起不来 | 两边都起不来，原因还是那 4 个两边都失败的 mod |

- 当时那 7 个都已在 2026-10-05 修好，现在全部两边都过。
- 复测途中发现一个新的回退：在 main `6d1dd0f2` 上，naturalist 只在 Forbric 失败（10-03 时两边都过）。原因是合并工具的一次改动让方块物品不再和方块关联，Fabric 初始化时 `Blocks.MELON.asItem()` 拿到的是空气。已修：让 MinecraftForge 物品注册表自己维护的方块→物品映射直接就是游戏读取的那一张，所有注册路径在注册当下就关联好。不认任何 mod 名字。
- 那 4 个两边都失败的仍是 mod 自己的问题。

## 130 个纯 Fabric mod：原生 Fabric 与 Forbric 同字节对照（服务端，2026-10-03）

用 `PICK_LOADER=fabric PICK_COUNT=130 PICK_SIDE=server`、种子 `20261003` 抽了 49 个热门 + 81 个随机的纯 Fabric 服务端主体，连同依赖共 165 个 jar（sha1 已固定）。同一组 jar 分别跑原生 Fabric Loader 0.19.5 和 Forbric（内核 SHA-256 `80ad82d1…`，strict 策略）专用服，判据是到 `Done` 后实测跑满 tick 数。证据在 `forbric-kernel/run/compat/reports/2026-10-03-pure-fabric-server/`，工具是 `run/compat/fabric-ab.py` 和 `native-controls.py run-set`。**只测了服务端，客户端、联机和渲染没测。**

| | 结果 |
|---|---|
| 单独跑（各带依赖，200 tick） | 119 两边都过 · **7 只在 Forbric 失败** · 4 两边都失败 · 0 只在原生失败 |
| 全部 130 个一起（165 jar） | 两边都起不来（原生自己先死在 beilin-data-portability，那是 1.21.x 的 mod） |
| 原生能单独跑的 126 个一起（159 jar） | 原生 Done，Forbric 被策略拒绝；ddmin 22 次启动缩到 4 个主体，都在上面那 7 个里，**没有新的组合冲突** |
| 两边都能单独跑的 119 个一起（149 jar） | 两边都跑满 1200 tick |

只在 Forbric 失败的 7 个（都是 Forbric 的缺陷；当时未修，2026-10-05 已修，见上面的复测）：

- notenoughcrashes：它的一个 mixin 目标在原版里本来就不存在，原生静默跳过，Forbric 却算成必需损失并拒绝启动（误报）。
- debugify、EnhancedVisuals（经 CreativeCore）、MoogsEndStructures（经 MoogsStructureLib）：合并基底的方法体或描述符和原版不同，mixin 锚点找不到。
- moreladders：合并基底的铜氧化走 NeoForge 数据映射，Fabric 初始化时读到还没绑定的值。
- ViaVersion（经 ViaFabric）：Forbric 多加载了一个 Minecraft 版本不符的嵌套 jar，两个入口都跑。
- meowantixray：方块注册表的运行时类型是一个非 public 的 MinecraftForge 类，mod 反射调用被拒，进服后崩溃。

前 6 个改用 continue 策略都能进服跑满 200 tick，但对应功能缺失。两边都失败的 4 个是 mod 自己的问题（1.21.x 的 mod、注册时空指针、用了 Fabric API 却没声明依赖）。

## 同一批 100 个 mod 用当前内核复测（2026-10-03）

输入与 2026-10-01 完全相同（同一份 manifest、依赖闭包和 jar，SHA-256 已复核），只换了内核：main `ddceddb6` 的内核代码（构建自 `9d6bdc2e`，两者内核源码一致），装进与上次相同布局的**隔离**安装目录，不碰日常使用的版本配置。证据在 `forbric-kernel/run/compat/reports/2026-10-03-sweep100-rerun/`。

| | 2026-10-01 | 2026-10-03 |
|---|---:|---:|
| 单独加载：严格成功 | 88/100 | **90/100** |
| 单独加载：正常进世界并退出 | 95/100 | **96/100** |
| 全部严格成功的 mod 一起装 | 88 个，崩溃 | 90 个，崩溃（同一原因） |
| 只拿掉互相冲突的那一对中的一个后一起装（6000 tick + 存档重载） | 需同时拿掉 cwb 和 EnchantCraft，86 个通过 | **只拿掉 cwb，89 个 mod / 110 个 jar 通过** |

- 单独加载没有任何一项变差。变好的两项：c2me（之前依赖模块降级）、lplm（之前崩溃）。其余 10 个仍不严格成功，原因与上次相同；tuanzis_server_mod 从"没进世界"变成"卡住"，仍算失败。
- 全混装崩溃的原因仍是 Sodium 自己的检查：`Multiple overrides for option 'sodium:general.fullscreen_mode'! Sources: chloride and cwb`——两个 mod 都要改 Sodium 同一个选项。同样三个 jar 放进官方 NeoForge 26.2.0.88 客户端（不经过 Forbric）也崩，第一行异常一字不差；Sodium 只配其中任何一个都能进标题界面（`forbric-kernel/run/compat/reports/2026-10-03-native-sodium-pair/`）。cwb 上游 2026-08-03 已把 chloride 标为不兼容（Kira-NT/cubes-without-borders#139），但还没进 26.2 的发布版。这次 Forbric 的崩溃分析直接点名这两个 mod，并给出写进 `forbric-disabled.txt` 就能不加载 cwb 启动的那一行。
- 自动二分（`run/compat/mac/ddmin.py`）在真实游戏里 **4 次启动**（含 1 次全包参照，种子是报错里点名的两个 mod）就把 111 个 jar 的失败包缩到 {chloride, cwb}（加上它们依赖的 Sodium），不用再按报错手工排查。拿掉这一对后其余 88 个也一起通过。
- EnchantCraft 上次也挡住全混装（原生 NeoForge 同样复现）；当前内核已让它不再把加入的玩家踢出，这次全混装里不再是阻断项。
- "只拿掉 cwb 后通过"仍然**不算**完整混装成功：完整混装的定义是所有严格成功的主体一起，不做任何排除。

## 修复后新抽样：100 个主体 mod（2026-10-01）

本轮已完成公共问题修复、100 个新主体逐个加载，以及全部严格成功主体的完整混装测试。**单独加载：88/100 严格成功，95/100 正常进入世界、绘制画面并退出。完整 88 个混装失败；隔离两项明确冲突后，86 个的保存和重载通过。** 剩余不严格成功的项目仍保留，未改写成成功。

### 样本与判定

- 排除历史测试的 570 个项目，按项目 ID 确认本轮 100 个主体没有重复；种子 `20261001`。下载量前 200 中尚未测过且可用的热门项目只剩 35 个，因此采用 **35 热门 + 65 随机**，加载器构建随机选择。
- 依赖额外计入，共 **128 个 jar**，分母始终为 100 个主体。补齐 Puzzle 未声明却直接使用的 MidnightLib；原始 mod jar 均未改写。初始错误依赖闭包、修正前结果和中断记录另存，不计作额外主体。
- 每个主体只带自己的必要依赖，使用干净测试目录和同一个原版世界，逐个启动官方格式的已安装 Forbric 配置。第 100 tick 截图，第 200 tick 正常断开退出。
- 严格成功要求进入世界、实际绘制、正常退出，主体、内嵌模块及所有加载依赖的报告状态均为 `OK`，无已确认必要损失、入口失败或未解决依赖。使用“继续”策略收集诊断并不会让失败变为严格成功。
- 100 个最新结果均绑定同一个冻结内核 SHA-256：`48425134ce52b4bbd235677a8f72b1457d4756e68d74326cd21693f70197a468`。主体与依赖文件的 SHA-256 均已复核；源码修复集基于 `fd4fa20e`。

| 样本 | 严格成功 | 正常进世界并退出 |
|---|---:|---:|
| 热门主体 | 32/35 | 35/35 |
| 随机主体 | 56/65 | 60/65 |
| **合计** | **88/100（88%）** | **95/100（95%）** |

历史三批的“完全正常”是 79.1%，但那是另一批样本，且只判断主体自身；本轮还要求依赖严格正常。本轮没有修复前的配对基线，因此**不能把两个比例之差称为这次修复带来的确定提升**。严格加载通过也不代表每个 mod 的全部游戏行为都已覆盖。

### 本轮修复与实测证据

| 公共原因 | 修复 |
|---|---|
| KotlinForForge 外壳没有 mod 清单，嵌套运行库被漏掉 | 保留带 JarJar 的匿名物理根，发现并加载真实内嵌库 |
| 外部运行时档案没有进入变换类加载器 | 将运行库 URL 纳入拥有类的加载路径，保留变换及资源解析 |
| 合并后 Forge/NeoForge 类型或生命周期不一致 | 接回 multipart 实体追踪/移除，填入真实游戏及载体版本，补发 ModifyRegistriesEvent |
| 可选 mixin 的 requiredMods 被丢弃 | 按原始 requiredMods 和实际存在的 mod 决定注册 |
| macOS AWT 字体初始化与 GLFW 冲突、初始化回调失联 | 默认字体使用 headless 模式并保留显式选择；接回真实客户端初始化回调 |
| 原方法/局部变量不在实际执行路径 | 修复流体标签查询、物品光泽局部捕获和斧头去皮回调，保留原调用、事件和取消行为 |
| 插件假定 Knot 或原生 FML；共享库仲裁后配置入口消失 | 按实际拥有者选择 Controlify 平台；接入当前 Mixin 装饰链；发布嵌套库真实身份并调用 Spectre 配置入口 |
| 枚举声明路径被硬编码 | 读取每个 mod 声明的 enumExtensions 路径；EUM 在最终 100 个中严格通过 |
| 缺失依赖或选中了无关主体作为依赖 | 收紧依赖闭包，补齐 MidnightLib，保留所有修正前证据 |

专项重测中，15 个历史失败/降级主体已在对应修复阶段严格通过：Fzzy Config、SimpleGUI、Particle Core、Alex’s Mobs、AutoEat、LambDynamicLights、Sodium、Iris、EasyMagic、FancyMenu、DrippyLoadingScreen、Biomes O’ Plenty、Item Glint Relight、WorldWeaver、BCLib。这些专项不混入新抽样的 100 个分母。

最终代码的完整内核回归为 **2736 项，0 跳过、0 失败**；依赖/存档工具的 8 项 Python 检查通过。53 个门禁通过；其中缺夹具或受并发影响的尝试保留，采用完整夹具复核后的通过记录。M0 完整夹具门禁及 M24 故意失败/严格停止/正常对照均通过。两小时 soak 曾在约 2000 秒正常退出，但缺少 controller-result，**未获放行，也没有计为通过**。

### 完整混装与冲突隔离

- **完整包：88 个主体 + 必要依赖，共 109 个 jar。严格启动失败，重载标为 NOT_RUN。** 明确错误为 Chloride 与 CWB 同时覆盖 `sodium:general.fullscreen_mode`。未删除冲突成员后把结果改成成功。
- 隔离 CWB 后暴露了 Spectre 配置入口缺失以及 Controlify/JECharacters 插件问题；对应 Forbric 修复已落实。修复后进世界又遇到 EnchantCraft 配方编码拒绝新实例。
- 用 **官方 NeoForge 26.2.0.88、未修改的 EnchantCraft jar** 做原生对照，同样复现 StreamCodec.unit 拒绝新建 ApplyEnchantRecipe 的异常；原生服务器正常启动和停止。
- **仅用于诊断的最终包：排除 CWB、EnchantCraft，86 个主体、107 个 jar。** 6000 tick 首次加载、正常保存退出，以及 200 tick 保存后重载均严格通过；报告无非 OK mod，必要损失为零。此结果不替代完整包的失败。
- 保存检查兼容 26.2 新目录结构，要求实际的新 level.dat 及玩家/区块写入，不能用复制进来的旧存档充当保存证据；混装测试目录关闭失焦暂停。

### 仍未严格成功的 12 个主体

| 主体 | 本轮结果 | 确认的阻塞 |
|---|---|---|
| ClientCarts | 进世界，主体 OK | 依赖 Extended Drawers 的 useOn 注入没有实际附着 |
| Crash Assistant | 进世界，降级 | 依赖原生 FML 中额外加入的 ExitVMBypass 类 |
| Leaves Be Gone | 进世界，主体 OK | Puzzles Lib 的 tryDropExperience 回调仍落在不执行的方法上 |
| MyConnectionMyChoice | 崩溃 | 内嵌 lifecycle API 使用了不匹配 26.2 的注入参数 |
| C2ME | 进世界，降级 | worldgen-threading 的结构计数字段锚点不匹配 |
| Extended Drawers Polymer Patch | 进世界，主体 OK | 同上游 Extended Drawers 的必要注入缺失 |
| LPLM | 崩溃，报告主体 OK | 服务器状态序列化收到 null Optional；运行结果仍判失败 |
| NoFog | 进世界，降级 | modifyFogEnd 必要注入未附着 |
| OptiCore | 崩溃、降级 | 多个游戏/Sodium 目标不匹配，并出现渲染状态类型转换失败 |
| PlayerCollars | 崩溃、降级 | 静态初始化向已冻结的物品注册表注册 |
| Sound Visualizer | 进世界，主体 OK | 依赖 Architectury 的 rightClickAir 注入未附着 |
| Tuanzi’s Server Mod | 未进入世界，报告 OK | 它自己的白名单拒绝测试账号 CompatPlayer；属于配置阻挡，不能据此认定加载器损坏 |

清单、闭包、每个主体的最新结果、冻结指纹及混装结果见 [本轮机器可读记录](forbric-kernel/run/compat/reports/2026-10-01-sweep100/summary.json) 和 [抽样清单](forbric-kernel/run/compat/reports/2026-10-01-sweep100/manifest.json)。完整控制台、截图、崩溃报告和保存世界保存在本机 `forbric-kernel/build/sweep100-mac-network/`；原生对照在 `build/sweep100-native-recipe/`，回归及未放行 soak 证据在 `build/sweep100-validation/`。


## 后续修复：Carpet（2026-09-30）

针对 `fabric-carpet-26.2+v260616.jar`，已接回 `fillUpdates` 的两处注入、黑石/深板岩再生，以及 Scarpet 换手与挖方块事件。保留原始 Carpet 回调与取消结果；原生换手、挖方块事件的否决仍然有效。

独立服务器行为测试共 22 项：关闭修复时 7 项通过，启用修复后严格兼容模式下 22 项全部通过，且 Carpet 的已确认兼容损失为零。另有 7 项真实字节码专项测试全部通过。覆盖规则开关、放置和邻居更新、原版流体产物、创造/生存模式的事件次数及取消效果；不代表已验证所有 Carpet 规则或扩展模组。

复现方法见 [Carpet 行为测试](forbric-kernel/canary/carpet/README.md)。

### 后续修复：Scarpet 回调的时机（2026-10-02）

上面那次修复里，换手和挖方块两个回调的触发时机与原生 Fabric 不一致。以原生 Fabric 0.19.5 + Carpet 为对照，用同一个探针 mod 和同一个 Scarpet 脚本测试，发现三处不同：

- 换手时，脚本清空主手但不取消。原生的结果是副手为空；我们这边旧物品被写回副手，可能刷物品或丢物品。
- 创造模式下挖床尾，脚本取消。原生的结果是床头床尾都消失；我们这边整张床都还在。
- 生存模式下挖不稳定 TNT，脚本取消。原生会点燃 TNT；我们这边没有点燃。

现在两个回调都改到原生的位置：

- 换手回调在任何读取手中物品的操作之前执行。
- 挖方块回调在 `playerWillDestroy` 之后、扣耐久和移除方块之前执行。

结果：同一组 14 项对照全部和原生一致。修复前是 10/14，关闭修复是 5/14。

有一个可见变化：NeoForge 的换手否决现在发生在 Scarpet 回调之后。换手仍然会被拦下，但脚本会收到这次换手事件。

行为测试从 22 项增加到 27 项：关闭修复时恰好 16 项 Carpet 检查失败，原版流体检查 0 项失败；启用修复后 27 项全部通过。兼容报告和控制台不再把这几个已修好的 mixin 标成"疑似"或"只部分生效"。


## 最新测试：3 批随机 mod，main 对比 release v0.2.0（2026-09-27）

### 方法

- 从 Modrinth 抽了 **3 批互不相同的随机 mod**。每批 30 个热门（下载量前 200 名里随机抽）+ 50 个随机（全部 26.2 mod 里随机抽），加载器随机，再加上依赖，分别是 110 / 97 / 104 个 jar。3 批之间不重复，也不和下面旧测试的那批重复。
- 每批分别用两个版本各测一轮：main `11ca1ffa`（装在官方目录）和 [release v0.2.0](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/tag/v0.2.0)（用它自己的安装器装在单独的目录里）。
- 测法和下面旧测试一样：每个 jar 单独加载，只带它必需的依赖，进同一个原版世界，截图后退出。
- v0.2.0 没有逐 mod 的加载报告，所以两个版本统一用同一个口径判定"加载成功"：进了世界、画面画出来、正常退出，**并且**日志里没有这个 mod 的入口失败、`@Mod` 构造失败或 mixin 应用失败（两个版本打的是同样的日志行）。
- 有少数 mod 缺的依赖在 Modrinth 上按 mod id 找不到，这些 mod 在两个版本里都是缺依赖状态测的。

### 成功率

| | 第 1 批 | 第 2 批 | 第 3 批 | **平均** |
|---|---|---|---|---|
| main 加载成功 | 92/110（83.6%） | 91/97（93.8%） | 93/104（89.4%） | **89.0%** |
| v0.2.0 加载成功 | 87/110（79.1%） | 83/97（85.6%） | 80/104（76.9%） | **80.5%** |
| main 能进世界 | 95/110（86.4%） | 93/97（95.9%） | 97/104（93.3%） | **91.8%** |
| v0.2.0 能进世界 | 89/110（80.9%） | 85/97（87.6%） | 85/104（81.7%） | **83.4%** |

**main 比 v0.2.0 的加载成功率高 8.5 个百分点**（80.5% → 89.0%），能进世界的比例高 8.4 个百分点。

逐个 mod 看：有 30 个在 v0.2.0 上失败、在 main 上成功；有 4 个反过来，在 v0.2.0 上成功、在 main 上失败（见下面"比 v0.2.0 差的"）。

按 main 自己更严格的加载报告算（mod 有任何部分功能未生效都不算），main 的"完全正常"平均是 79.1%（85/110、77/97、84/104）。v0.2.0 没有这个报告，没法比。

### main 上不成功的 mod（35 个）

"批次"是它在哪一批里；"v0.2.0 上"是同一个 jar 在 v0.2.0 上的结果。

| mod | 批次 | 来源 | 加载器 | 版本 | main 上 | v0.2.0 上 |
|---|---|---|---|---|---|---|
| [alexs-mobs-continued](https://modrinth.com/mod/alexs-mobs-continued) | 1 | 随机 | NeoForge | 2.2.2+26.2-neoforge | 崩溃 | 正常 |
| [better-combat](https://modrinth.com/mod/better-combat) | 1 | 热门 | NeoForge | 3.2.2+26.2-neoforge | 崩溃 | 崩溃 |
| [biomes-o-plenty](https://modrinth.com/mod/biomes-o-plenty) | 3 | 热门 | Fabric | 26.2.0.0.28 | 崩溃 | 崩溃 |
| [iris](https://modrinth.com/mod/iris) | 1 | 热门 | NeoForge | 1.11.4+26.2-neoforge | 崩溃 | 崩溃 |
| [itemglintrelight](https://modrinth.com/mod/itemglintrelight) | 3 | 随机 | Fabric | 0.3.0+26.2 | 崩溃 | 崩溃 |
| [lambdynamiclights](https://modrinth.com/mod/lambdynamiclights) | 3 | 热门 | NeoForge | 4.12.4+26.2 | 崩溃 | 崩溃 |
| [mcrpvp](https://modrinth.com/mod/mcrpvp) | 1 | 随机 | Fabric | 1.0.1 | 崩溃 | 崩溃 |
| [particle-core](https://modrinth.com/mod/particle-core) | 3 | 热门 | NeoForge | 0.3.3+26.2+neoforge | 崩溃 | 崩溃 |
| [particledrawing](https://modrinth.com/mod/particledrawing) | 1 | 随机 | NeoForge | 1.0.7-ALPHA | 崩溃 | 崩溃 |
| [player-animation-library](https://modrinth.com/mod/player-animation-library) | 1 | 依赖 | NeoForge | 1.2.6 | 崩溃 | 崩溃 |
| [sodium](https://modrinth.com/mod/sodium) | 1 | 依赖 | NeoForge | mc26.2-0.9.2-neoforge | 崩溃 | 崩溃 |
| [sodium](https://modrinth.com/mod/sodium) | 2 | 依赖 | NeoForge | mc26.2-0.9.2-neoforge | 崩溃 | 崩溃 |
| [sodium-extra](https://modrinth.com/mod/sodium-extra) | 2 | 依赖 | NeoForge | mc26.2-0.9.4+neoforge | 崩溃 | 崩溃 |
| [sodium-extra-information](https://modrinth.com/mod/sodium-extra-information) | 2 | 随机 | NeoForge | 2.9.0 | 崩溃 | 崩溃 |
| [ukulib](https://modrinth.com/mod/ukulib) | 1 | 热门 | Fabric | 2.1.1+26.2-fabric | 崩溃 | 崩溃 |
| [animated-loading-overlay](https://modrinth.com/mod/animated-loading-overlay) | 1 | 随机 | Fabric | 1.0.2 | 卡在加载画面 | 卡在加载画面 |
| [bclib](https://modrinth.com/mod/bclib) | 2 | 热门 | Fabric | 26.201.2 | 卡在加载画面 | 崩溃 |
| [bclib-neoforge](https://modrinth.com/mod/bclib-neoforge) | 3 | 依赖 | NeoForge | 26.2.3 | 卡在加载画面 | 进不了世界 |
| [drippy-loading-screen](https://modrinth.com/mod/drippy-loading-screen) | 1 | 热门 | NeoForge | 3.1.5-26.2-neoforge | 卡在加载画面 | 正常 |
| [fancymenu](https://modrinth.com/mod/fancymenu) | 1 | 依赖 | NeoForge | 3.9.12-26.2-neoforge | 卡在加载画面 | 正常 |
| [bclib](https://modrinth.com/mod/bclib) | 1 | 依赖 | Fabric | 26.201.2 | 进世界后卡住 | 崩溃 |
| [easy-magic](https://modrinth.com/mod/easy-magic) | 3 | 热门 | NeoForge | 26.2.0 | 进不了世界 | 正常 |
| [entitycrosshair](https://modrinth.com/mod/entitycrosshair) | 1 | 随机 | Fabric | 2.3.0-26.2+_fabric | 进不了世界 | 崩溃 |
| [expanded-crossbow-enchantings](https://modrinth.com/mod/expanded-crossbow-enchantings) | 1 | 随机 | Forge | 1.11.1+mod | 进不了世界 | 进不了世界 |
| [mythicquests](https://modrinth.com/mod/mythicquests) | 3 | 随机 | Fabric | 1.1.2-beta | 进不了世界 | 进不了世界 |
| [oneconfig](https://modrinth.com/mod/oneconfig) | 1 | 依赖 | Fabric | v1.2.7 | 进不了世界 | 崩溃 |
| [auto-eat](https://modrinth.com/mod/auto-eat) | 3 | 随机 | NeoForge | 1.6.7 | 能进世界，mod 加载失败 | 能进世界，mod 加载失败 |
| [emotesounds](https://modrinth.com/mod/emotesounds) | 3 | 随机 | Fabric | 1.0.0 | 能进世界，mod 加载失败 | 崩溃 |
| [fzzy-config](https://modrinth.com/mod/fzzy-config) | 2 | 热门 | NeoForge | 0.7.7+26.2+neoforge | 能进世界，mod 加载失败 | 能进世界，mod 加载失败 |
| [fzzy-config](https://modrinth.com/mod/fzzy-config) | 3 | 依赖 | NeoForge | 0.7.7+26.2+neoforge | 能进世界，mod 加载失败 | 能进世界，mod 加载失败 |
| [macebot](https://modrinth.com/mod/macebot) | 2 | 随机 | Fabric | mc26.2-1.3.3-fabric | 能进世界，mod 加载失败 | 能进世界，mod 加载失败 |
| [pigeon-chat](https://modrinth.com/mod/pigeon-chat) | 1 | 随机 | Fabric | 0.3.0+26.2 | 能进世界，mod 加载失败 | 崩溃 |
| [simpleguiapi](https://modrinth.com/mod/simpleguiapi) | 3 | 依赖 | NeoForge | 1.5.9 | 能进世界，mod 加载失败 | 能进世界，mod 加载失败 |
| [the-elementals](https://modrinth.com/mod/the-elementals) | 1 | 随机 | Fabric | 1.4.0+fabric-26.2 | 能进世界，mod 加载失败 | 能进世界，mod 加载失败 |
| [time-weather-changer](https://modrinth.com/mod/time-weather-changer) | 1 | 随机 | Fabric | 1.2.0 | 能进世界，mod 加载失败 | 能进世界，mod 加载失败 |

### 比 v0.2.0 差的（4 个，main 失败而 v0.2.0 正常）

Alex's Mobs Continued（main 崩溃）、Drippy Loading Screen（main 卡在加载画面）、FancyMenu（main 卡在加载画面）、Easy Magic（main 进不了世界）。均为 NeoForge 版。

### 证据位置（本地，不在仓库里）

- 3 批 mod 清单：`forbric-kernel/build/sweep80-mac/v020-rounds/r1..r3/manifest.json`
- main 每轮结果：`forbric-kernel/build/sweep80-mac/per-mod-main-r1..r3/`；v0.2.0：`per-mod-v020-r1..r3/`
- 对比汇总：`forbric-kernel/build/sweep80-mac/compare.json`、`compare.txt`

---

## 发布 v0.3.0 前的闸门（2026-09-28）

全套闸门 53 个里 52 个通过，2 小时的 soak 没跑。**M9 客户端闸门不通过**：179 条检查里挂 1 条，加载报告的"可能的问题"里，fabric-api 的 `HudMixin` 那一条同样的原因写了两遍；单独重跑一次，结果一样。其余检查（进世界、渲染、正常退出等）全部通过。待修。

---

## 旧测试：同一批 mod 测 3 轮（2026-09-26 至 27，只测 main）

### 测试方法

- 从 Modrinth 选了 26.2 的 30 个热门 mod（下载量前 100 名里随机抽）和 50 个随机 mod，加载器随机（Fabric / NeoForge / MinecraftForge），再加上它们的依赖，共 101 个 jar。
- **每个 jar 单独测**，只带它自己必需的依赖。每次都用一个干净的游戏目录，进入同一个零 mod 生成的原版世界，第 100 tick 截图，第 200 tick 退出世界。
- 兼容策略设为"继续"（相当于玩家在提示窗口里点了继续）。
- 同样的测试跑了 3 轮。

"完全正常" = 进了世界、画面画出来了、正常退出，并且这个 mod 在 Forbric 加载报告里是 OK。

### 成功率

| 轮次 | 完全正常 | 能进世界 |
|---|---|---|
| 第 1 轮 | 83/101（82.2%） | 95/101（94.1%） |
| 第 2 轮 | 83/101（82.2%） | 95/101（94.1%） |
| 第 3 轮 | 83/101（82.2%） | 95/101（94.1%） |
| **平均** | **82.2%** | **94.1%** |

三轮结果完全一致，下面 18 个 jar 每轮都不成功。

按分组看（每轮相同）：热门 30 个里 23 个完全正常，随机 50 个里 43 个，依赖库 21 个里 17 个。

### 不成功的 mod

"来源"一列：热门 / 随机 = 抽中的 mod；依赖 = 被抽中的 mod 需要、从 Modrinth 依赖关系带进来的；补装依赖 = mod 自己的元数据要、但 Modrinth 上没标出来、测试时手动补上的。

#### 进不了游戏或世界（6 个）

| mod | 来源 | 加载器 | 版本 | 现象（3 轮一致） |
|---|---|---|---|---|
| [MCA Reborn](https://modrinth.com/mod/minecraft-comes-alive-reborn) | 补装依赖 | NeoForge | 8.1.11+26.2 | 卡在 Mojang 加载画面，不再前进 |
| [Supermarket Life](https://modrinth.com/mod/mca-rebornsupermarket-life)（需要 MCA Reborn） | 随机 | NeoForge | 1.0.1 | 卡在 Mojang 加载画面，不再前进 |
| [Massive Smoke Columns](https://modrinth.com/mod/massive-smoke-columns) | 随机 | NeoForge | 1.5.2 | 启动时崩溃 |
| [Essential](https://modrinth.com/mod/essential) | 热门 | Fabric | 1.5.0.1 | 启动时直接退出 |
| [Roughly Enough Items (REI)](https://modrinth.com/mod/rei) | 热门 | NeoForge | 26.2.820+neoforge | 游戏能启动，打开世界时失败（数据包加载失败） |
| [Visual Workbench](https://modrinth.com/mod/visual-workbench) | 热门 | NeoForge | 26.2.1 | 游戏能启动，打开世界时失败（数据包加载失败） |

#### 能进世界，但 mod 没加载成功（4 个）

| mod | 来源 | 加载器 | 版本 | Forbric 加载报告原文（未核实） |
|---|---|---|---|---|
| [Resourceful Config](https://modrinth.com/mod/resourceful-config) | 热门 | Fabric | 5.0.0 | did not finish loading — its main entrypoint threw |
| [andonium](https://modrinth.com/mod/andonium2) | 随机 | Fabric | 2.2.1+26.2-fabric | did not finish loading — its main entrypoint threw |
| [Knox](https://modrinth.com/mod/knox) | 随机 | Fabric | 1.0.0 | did not finish loading — its client entrypoint threw |
| [minimega](https://modrinth.com/mod/minimega) | 依赖 | Fabric | 7.1.0 | did not finish loading — its client entrypoint threw；它自带的 fantasy 部分功能未生效 |

#### 能进世界，但 mod 部分功能没生效（8 个）

| mod | 来源 | 加载器 | 版本 | Forbric 加载报告原文（未核实） |
|---|---|---|---|---|
| [fabric-api](https://modrinth.com/mod/fabric-api) | 依赖 | Fabric | 0.161.0+26.2 | 4 个模块部分未生效：fabric-block-api-v1、fabric-creative-tab-api-v1、fabric-loot-api-v3、fabric-registry-sync-v0 |
| [Architectury API](https://modrinth.com/mod/architectury-api) | 热门 | Fabric | 21.1.10+fabric | A required injector has no attachment in the actual defined class |
| [Language Reload](https://modrinth.com/mod/language-reload) | 热门 | Fabric | 1.7.7+26.2 | A required injector has no attachment in the actual defined class |
| [Physics Mod](https://modrinth.com/mod/physicsmod) | 热门 | Fabric | 3.2.4 | 3 个 mixin 被跳过：immediatelyfast.MixinSignText、liquid.MixinProgramManager、sodium.MixinVertexTransform |
| [Carpet](https://modrinth.com/mod/carpet) | 补装依赖 | Fabric | 26.2 | A required injector has no attachment；receiveFluidToBlackstone 挂在一个没有调用方的方法上 |
| [Phantom Tweaks](https://modrinth.com/mod/phantom-tweaks) | 随机 | Fabric | 1.1.3+mc26.1 | A required injector has no attachment in the actual defined class |
| [Pack Tools](https://modrinth.com/mod/pack-tools) | 随机 | NeoForge | 1.26.6.2 | GuiMixin 等 mixin 应用失败（InvalidMixinException） |
| [No Too Expensive Anvil](https://modrinth.com/mod/no-too-expensive-anvil) | 随机 | NeoForge | 1.3.1 | RenderLabelsAnvilScreenMixin 被跳过 |

### 其他测试中看到的情况（未单独复测）

- **只在整包里出现**：andonium 和整包一起加载时，服务端生成地形直接崩溃；把 andonium 拿掉后世界能生成。单独测 andonium 时能进世界（但 andonium 自己加载失败，见上表）。
- **缺依赖但照样加载了**：wcopy（chat-copy）需要 Chat Heads，hide-minimega-leaderboards 需要 Legacy4J（没有 26.2 版）。两个都没装依赖，Forbric 照样加载、进了世界、没有报错，所以算作"完全正常"。
- **Modrinth 没标出的依赖**：blockframe 需要 owo-lib、ibcarpet 需要 Carpet、Supermarket Life 需要 MCA Reborn、chunky-friends 需要 Chunky、Peterwolf's Railroads One 需要 Minecart Chain。补上后，除了带着 MCA 的 Supermarket Life，其余都完全正常。

### 证据位置（本地，不在仓库里）

- 每个 jar 每一轮的日志、加载报告、截图、崩溃报告：`forbric-kernel/build/sweep80-mac/per-mod/`、`per-mod-r2/`、`per-mod-r3/`
- 选中的 mod 清单（含版本、SHA-1、下载地址）：`forbric-kernel/build/sweep80-mac/manifest.json`
- 汇总：`forbric-kernel/build/sweep80-mac/summary.json`
