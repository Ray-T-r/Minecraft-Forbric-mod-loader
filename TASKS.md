# compat-instruments — tasklist

修复路线图见 `~/.claude/plans/happy-tinkering-toast.md`。顺序是**先建量具,再修缺陷** ——
现在所有「已修/全绿」都建立在一套只能观测"在场"、观测不到"缺席"的测试套件上。

判据形状统一抄 `run/gate-m9-client.sh:136,147` 的双句式:**先断言普查发生过(分母非零),再断言结果**。
每条都要有**负控制**:故意弄坏,它必须变红。

## M-A 量具层

- [x] **A1** `MergedLinkChecker` 加牙 —— `--baseline` 模式,新增即红,`[FIXED]` 提示剪枝;
      `build-merged-base.sh` 去掉 `|| echo`;安装器补上 link check(报告不拦)。
      自测 `forbric-loader/run/test-link-check.sh`:16/16 绿,含负控制。
- [x] **A4b** `fapi-usage.py` 的符号集提成参数(`--preset` / `--symbols` / `--list-presets`),
      definer 排除也变成数据(`!` 行)而不是从 surface 前缀猜。6 个测试全绿,变异检查确认断言有牙。
- [x] **A2** `DeadHookWorklist` + `run/compat/hook-worklist.sh` —— **改了计划里的做法**:原案是解析
      `merge-conflicts.txt` 再做方法体 diff(带三条已知风险);A3 落地后发现更直接——死钩子的清单本来就是
      普查的输出,不需要反推。真实 97 jar 整合包实测:**10 个死事件有 mod 在等且没有桥**(`collective`
      一个库就占 8 个,`nutritiousmilk`/`balm`/`journeymap` 各一)。5 个测试钉 join 规则。
- [x] **A3** `HookCallSiteCensus` —— 从字节码重新推导"哪些钩子还有调用点"。**复现了手工 javap 的数字**:
      `ForgeEventFactoryClient` 46 declared / 8 live(javadoc 写的就是这两个数),`ForgeEventFactory` 160/20,
      NeoForge `EventHooks` 114/106。6 个合成测试钉规则(三次变异全部被抓),3 个 staged 测试钉现实。
- [x] **A4(前半)** 手写表有生成器了,并且**第一次跑就抓到 3 条假指控** —— `NeighborNotifyEvent`
      (`ServerLevel#updateNeighborsAt` 还在调)、`LivingFallEvent`(`AbstractHorse`/`Llama#causeFallDamage`)、
      `EntityPlaceEvent`(`ReplaceDisk#apply`)。手工读的不是马虎,是**只读了一个类**;但这张表的断言是
      "合并后的游戏从不发它",而这三个确实会发,把玩家的 mod 标成 DEGRADED 是假指控。三行已删。
- [x] **A4(后半)** 审计能看见 `addListener` 了 —— 走每个 MinecraftForge mod 自己的 BusGroup(内核本来就留着),
      一个 mod 一次字段读取,不用再扫一遍方法体。注解扫描的输入本来只有
      {类级 `@EventBusSubscriber`} × {`@SubscribeEvent` 方法},而对一个失败模式是"沉默"的审计,
      看不见和没问题长得一模一样。
- [x] **A5** 元普查 —— 抓到一条真的:`build.gradle` 的三条 staged `inputs.files` **写死了相对路径**,
      而认 `FORBRIC_OLD` 的 `stagedRoot` 就在上面三十行。于是在第二个 worktree(`FORBRIC_OLD` 存在的唯一
      理由)上,声明的输入是个不存在的文件 —— **换掉合并基底,`test` 仍然 UP-TO-DATE 并报绿**。
      已实测复现并验证修复:换 jar 前后,修复前 UP-TO-DATE/绿,修复后重跑/红。
      另:新的 staged 测试原本用了共享 helper,会对 `StagedArtifactCoverageTest` 的源码扫描隐身,已改回内联。

## 已定位未修(有断言钉住,新增即红)

- [ ] **双发** `BlockEvent$EntityPlaceEvent` 同时有桥和幸存调用点(`ReplaceDisk#apply`),这条路径上
      MinecraftForge 的订阅者会被叫两次。已 pin 在 `HookCallSiteCensusStagedTest.KNOWN_DOUBLE_POSTED`;
      该删哪一边要开着游戏才能定,静态扫描定不了。
- [ ] **部分死亡** 一个事件在一条路径上活、其它路径上死,现在的表没有这种行数;三条被删的行都是这个形状。

## 可插队(零/低成本)

- [x] **D2a** `check_kept_up` 进 `lib.sh` —— **两条断言**:先证明有玩家在线(空服会暂停,否则"没有超载警告"
      是对一个停了 tick 的 JVM 的陈述),再断言没有 `Can't keep up`,红时打印最差的那条。
      已接进 m12/m15/m16(三者本来就断言了 `logged in with entity id`,分母现成)。4 个契约测试全绿。
- [x] **E0** `anchors()` 的自述改成数 `REPAIRS.size()`,并加断言钉住(原来 javadoc 说 "Forty"、文案说 "47"、列表是 49)

## 量具层补漏(第二轮)

- [x] **97 个测试类无视 `FORBRIC_OLD`** —— `StagedArtifactCoverageTest` 的 javadoc 早就写了这件事,
      但没人量过:121 个读 staged 产物的测试类里只有 24 个认那个变量。于是在第二个工作树里
      **337 个字节码断言静默跳过,而套件照样说 "0 failures"**。
      一处纯机械替换(99 文件 / 112 处,零非机械改动),**337 → 46 跳过,全部通过**。
      剩下的 46 个要的是内核自己的 `run/` 夹具(fabric-api jar、崩溃报告、ShoulderSurfing),
      那些在主 checkout 里本来就有 —— 所以 gate-m0 的 skip 上限不动。

## M-B 仲裁

- [ ] **B1** 不翻 `MergedBaseBuilder.java:957` 的默认方向;用 A2 的 `RUNTIME_LIVE` 驱动 `FORCE_FORGE_METHODS` 白名单
- [ ] **B2** 可合并性探索(只出报告,不承诺落地)
- [x] **B3** link check 进了 `gate-m0`(第 4 步),并且 **baseline 已用真 staged 产物播种:24 条**,
      和 `build-merged-base.sh` 里那句注释的数字对上。原来它只活在"重建合并基底"那条路径上,
      两次重建之间没有任何东西问过"每个测试和每个 gate 读的这个产物还连得上吗"。
      接线时自己踩了两次同一个坑:**源码(工具、baseline)属于本仓库,产物(jar)在 `FORBRIC_OLD`**——
      按 `RUN_OLD` 解析源码会让第二个工作树去别人的 checkout 里编译一份没有 `--baseline` 的旧工具。
- [x] **B4** 相位内的两条结构性约束加了断言(相位间顺序不动):`LoaderProbeRewriter` 必须是第一个、
      `ForgeCapabilityCompositionTransformer` 必须在 `ForbricMergedBaseCompatTransformer` 之前。
      读的是**编译后的字节码**不是源码(`KernelBoot.java` 有 NUL 字节,grep 会静默漏行,这个项目栽过两次)。
      把 probe 挪到 guard 之后,断言立刻变红。

## M-C 五族收口

- [x] **C-mixin(其一)** 部分应用的 mixin 第一次被**数**出来了 —— 一个 **3 个 mod** 的 gate 里,
      客户端 92 条、服务端 31 条 "applies only partially",每次启动都有,gate 报绿。
      `MixinFit` 自己的 javadoc 说"部分应用比两个极端都糟",而九十行没人加总就不是测量。
      m12 现在断言普查跑过 + 数量在天花板内(实测 27 ≤ 30,天花板是"应该往下走"的意思)。
- [x] **C-mixin(其二,判定)** `-Dforbric.mixinDiagnostics` **不能**当成"收集齐所有失配"的模式:
      实测它在这个 3-mod 集上直接把服务端弄死了(`InjectionError: Critical injection failure:
      checkIfUnderSwimmableFluid(Z, LocalRef)` —— MixinExtras 的 `@Local` 糖)。它的 javadoc 本来就写了
      raw `InjectionError` 绕过所有 error handler。所以"一轮一个发现",而这个项目一轮都没跑过。
- [ ] **C-merged-base** / **C-api-surface**
- [x] **C-arbitration(其一)** `ModPresence.isLoaded` 的 `-`/`_` 归一化 —— NeoForge 的 mod id 不许带 `-`,
      另外两家许,所以同一个 mod 跨生态就是两个拼写;而这个"专门用来跨生态回答"的注册表在用字符串比较,
      恰好跨不过两家唯一真正不同的那条边界。代价不对称:假 no 会让 mod 走"没装"分支而它其实装了。
- [ ] **C-lifecycle** / **C-arbitration(其二)** `getModContainerById`、落败版独有类

## M-D 四个盲区

- [x] **D1(前半)** `NetworkChannelCensus` —— 记"注册了 payload 类型"和"向对端申报了频道"两个集合,
      在配置阶段结束时报差集。差集就是把玩家踢出世界的那一类(cardinal 的 `entity_sync`:mod 发得出包,
      对端没同意收,未处理的 payload 是断线不是跳过)。接在**已有的**两处遍历里,没有新增网络路径上的代码;
      整个类任何输入都不抛(一个能弄断连接的普查比没有普查更糟)。5 个测试。
- [x] **gate-m12 本机真跑了两次**(真 socket、真专用服、真客户端):
      第一次 RED —— 但不是我的改动:`check_absent "…Incompatible…"` 匹配到了**内核自己**解释
      `getAppearance` 修复时写的 "died on IncompatibleClassChangeError"。自己的成功信息被自己的失败模式匹配。
      修法不是去改那句解释(下一句解释还会再犯),而是给 `check_absent` 加一个排除参数:
      内核写给自己的一行不是游戏在做那件事。修完第二次 **GREEN**,`check_kept_up` 现场通过。
- [x] **D1(后半,m12)** m12 加了两条频道普查断言(普查跑过 + 差集为 0),**本机实测 GREEN**。
      第一次接线把一条跨行的 `check` 从中间劈开了(`$2: unbound variable`),已修并重跑确认。
- [x] **m15 / m16 本机真跑了** —— 两个都在 main 上就是红的:m15 是同一条 `Incompatible` 自指,
      m16 除此之外还有一条 **"CLIENT Loading fired exactly once (want 1 got 2)"**。
      量了才知道配置其实**只加载了一次**:`latest.log` 里一行,`CLOG` 里同一行两份 —— 因为
      `System.out` 现在接进了 log4j,同一句 print 既进 stdout 重定向又进被追加的 `latest.log`。
      gate 自己的注释("latest.log 的追加副本不可能重复它们")被一个已经落地的改进证伪了。
      改成数 `CGAME`(每次发生只出现一次),而不是数去重后的 `CLOG`——后者会把真的第二次加载吞掉。
      三个 gate 现在都 GREEN。
- [ ] **D1(余下)** m13/m14 也加频道普查两条 —— 没在本机跑过这两个
- [ ] **D2** performance:`ServerTickSampler` + JFR 透传 + m31 式并排对照
- [x] **D3** 语言提供者 —— `modLoader` 第一次有了消费者(`LanguageProviders`,接在发现阶段),
      并且 Kotlin `object` 那个形状真的能构造了(没有公开构造器时取 `INSTANCE`,正是 kotlinforforge 自己的做法)。
      原来的失败信息是"no public constructor",一句关于一个没坏的 mod 的真话,而真正的原因就写在它自己的 manifest 里。
      判据窄:public + static + final + 类型是自己,否则一个叫 INSTANCE 的无关静态字段会被当成 mod 实例。
- [ ] **D4** 时间轴与存档可携带性:长测 gate + 摘 mod 后开旧世界 / 跨构建搬世界 / 拿回原生 loader

## M-E 批评者补的

- [ ] **E1** 跨生态内容级互操作(Fabric transfer/lookup ↔ Forge capability:**零桥、零测试**)
- [ ] **E2** 常驻对照实例(归因准确率本身是一等产品问题)
- [x] **E3** 库 mod 爆炸半径 —— load-report 现在会写"还有 N 个 mod 说它们需要这个:…"。
      只陈述事实(它们声明了**必需**依赖),不判定它们也坏了 —— 判定就是这条分支已经删过一次的假指控。
      id 比较跨生态拼写,否则最可能两个生态都发布的那批库恰好报不出依赖者。
- [x] **E4** 载体版本漂移 —— `RepairDriftCensus` + `run/compat/repair-drift.sh`:把 claim 账本对着一个
      **候选**构建重放,点名哪些 repair 会不再适用。在载体升级之前跑,而不是等玩家报上来。
      实测当前 staged 与 Sep-10 备份两个基底都是 44/44 落地;负控制(候选里有一个类已经自带初始化)
      恰好点名那一条 claim。
