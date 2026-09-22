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

- [x] **双发 —— 查下来是我自己的假阳性,已撤销**。`ReplaceDisk#apply` 只调 MinecraftForge 的
      `onBlockPlace`,**一个 NeoForge 钩子都不调**;而桥是在**另一个生态的事件**上触发的,
      所以这条路径上桥根本不响,订阅者只被叫一次。
      我原来的判据("这个被桥接的事件基底还在直接发")问错了问题。可判定的问法是:
      **有没有哪个方法同时调进两家的事件钩子入口**——整个合并基底 **0 个**。
      规则挪进 `HookCallSiteCensus.methodsCallingBothFamilies`,合成测试钉住它会红
      (真基底上无论怎么变异都是 0,所以只在真 jar 上断言等于没断言)。
- [x] **部分死亡 —— 修了**。量出来是 **8 个钩子**,全在服务端,不是一个类别:
      `canLivingConvert 10→2`、`onLivingConvert 7→2`、`onPlayerDestroyItem 4→1`、`onNeighborNotify 4→1`、
      `onLivingFall 3→2`、`blockGrowFeature 3→1`、`onBlockPlace 2→1`、`onLivingEffectCanApply 2→1`
      (判据:同一个钩子在 **MinecraftForge 自己的补丁游戏**里和在合并基底里各有几个调用点)。
      `DeadEventAudit` 多了第三张表 `PARTIAL`,8 行,每行写清"几条路径里还剩几条";
      `audit()` 的优先级是 桥 → 从不发 → 部分发。表由 staged 测试**重新算一遍并要求相等**,删一行就红。
      为什么值得单列:一个从不触发的监听器会被报上来查,一个"给马和羊驼生效、别的都不生效"的监听器
      看起来是间歇性的 —— 最难报、也最容易被赖到 mod 头上。

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

- [x] **B1 —— 做了,而且是按证据挑的一条**。新工具 `LostHookAttribution` 回答了报告本身回答不了的问题:
      **每条冲突到底丢的是哪个钩子**(读两边的方法体做差集),以及**有没有 mod 在等它**(常量池点名事件类)。
      995 条里:**773 条根本没丢钩子**(合并拿走的是别的东西)、8 条是**两边的钩子都有人等**的真交易、
      9 条净赚。9 条里 6 条已经有桥在送、2 条是网络 interop 自己管的那条缝,**residual 只剩 1 条**。
      于是 `FORCE_FORGE_METHODS` 加了这一条:`PlayerChunkSender#sendChunk` ——
      赚到 `ChunkWatchEvent`(测试整合包里有 mod 订阅),让出 NeoForge 的 `fireChunkSent`(没人点名)。
      **重建合并基底到临时目录并逐项验过**:冲突 1000→999、`forge hook lost` 995→994、
      link check 24 known / **0 new**、`RepairDriftCensus` **44/44 全部落地**、gate-m12 在新基底上 **GREEN**。
      (产物不入库 —— 改的是源,下一次重建就会产出它。)
- [ ] **B1 余下** 那 8 条真交易需要"两边都留"才有意义,而合并工具目前没有"把两侧插入都拼进去"的能力;
      `MergeabilityCensus` 说 66.6% 的冲突结构上允许这么做。
      **注意**:8 条里有 5 条是 tick/level-tick/player-tick —— 这些桥本来就在送,所以那几条是**假交易**。
- [x] **工单第一条做掉了:`PlayerEvent$StartTracking` + `StopTracking` 两条新桥** —— 本机 m12 实测
      **真的在送**(服务端日志里两条 "bridged the first ..." 都出现了),GAME_BUS 桥 28→30,gate GREEN。
      成对做是因为只送 Start 不送 Stop 会让 mod 按观察者累积状态而永远不拆 —— 那是漏,比原来的沉默更糟。
      顺带量了另一件事:7 条里只有 4 条**可桥**(对面那条还活着);`AddPackFindersEvent` 和
      `MobSpawnEvent$FinalizeSpawn` 在 NeoForge 侧压根没有对应事件,`EntityMultiPlaceEvent` 的 Forge 侧也死了 ——
      这三条桥无从听起,只能靠 repair 或改仲裁。工单 7→6。
- [x] **工单再做掉两条:`LivingEntityUseItemEvent$Finish` + `BlockEvent$PortalSpawnEvent`** ——
      这两条是**带返回值**的,不是观察者:hook 分别返回"物品变成什么"和"портal 建不建"。
      转发了却把返回值丢掉,比不桥更糟 —— mod 的监听器跑了、改了值、游戏用的还是原来的值,
      既不是沉默也不是在工作。所以 Finish 把返回的 stack 写回 `setResultStack`;
      PortalSpawn 把"拒绝"变成 `setCanceled`。
      **PortalSpawn 只有部分保真度并且明说**:Neo 的事件能取消但没有 shape 的 setter,
      所以 Forge mod 返回一个**不同的** shape 时会打一行(一次),而不是被悄悄丢掉。
      m12 实测:32 条 GAME_BUS 桥全部装上,gate GREEN。但**没有任何 gate 会吃东西或点传送门**,
      所以"装上了"验到了、"真的送到"没验到 —— 于是加了字节码断言钉住最容易错的那半:
      hook 被调用,且它的返回值被一个写回的调用带走。把 `setResultStack` 去掉,测试立刻红。
      工单 6 → 4。
- [x] **工单第四条:`FurnaceFuelBurnTimeEvent`(←balm)** —— 方向相反的那条,用 repair 不是桥:
      合并后的 `FuelValues.burnDuration` 只调 MinecraftForge 的 `getItemBurnTime`,NeoForge 的事件没人发。
      NeoForge 那边是**静态调用不是总线事件**,监听器无从下手,所以把调用点重定向到内核,**两边依次问**
      (Forge 先拿游戏算出的值,Neo 拿 Forge 返回的值)—— 两个生态的 mod 都能改同一个燃烧时间,
      这本来就是把它们放一起跑的意义。Neo 的 hook 还要 `FuelValues` 本身,所以重定向前先压 `this`,
      并且**只在实例方法里做**(静态方法的 slot 0 是第一个参数,压下去等于把 ItemStack 当 FuelValues 传)。
      实测:repair 在真服务端落地、m12 GREEN、`RepairDriftCensus` **45/45**。
      顺带被 `KernelRuntimeClassesTest` 抓了一次:boot 侧新点名了一个 runtime 类却没登记。
- [x] **工单第五条:`MobSpawnEvent$FinalizeSpawn`(←collective)** —— 两边都改了 `BaseSpawner.serverTick`,
      NeoForge 的体赢了,于是 MinecraftForge 的 hook 一个调用点都没有。
      **没有**去把 Forge 的指令段塞回一个用着 NeoForge 局部变量编号的方法体(那是这棵树没有的三方合并),
      而是把**幸存的那个调用**重定向:内核方法用 NeoForge 的**完全相同的签名**,所以改的只是 owner,栈不动。
      拒绝能带回去(Forge 的 hook 返回 null 表示被取消 → `setSpawnCancelled(true)`);
      **改写 spawn data 带不回去**,会打一行说明而不是悄悄丢掉。
      Forge 的 hook 还要一个 NeoForge 签名里没有的 `ValueInput`,传 null;真要紧就抛,抛了被接住 ——
      下限锁死在"不比不问更糟"。实测 repair 落地、m12 GREEN、`RepairDriftCensus` **46/46**。
- [x] **工单第六条:`AddPackFindersEvent`(←collective)** —— MinecraftForge 的 `addPackFindersServer`
      在它自己的补丁游戏里有一个调用点、在合并基底里零个;NeoForge 的 `populatePackRepository` 活着。
      NeoForge 这边**没有对应事件可听**,所以还是重定向(同签名,栈不动),然后把仓库自己的
      `addPackFinder` 当 sink 交给 Forge 的 hook。只在 `SERVER_DATA` 上转发 —— 26.2 的 MinecraftForge
      **只有服务端那一半**,没有 `addPackFindersClient`。
      **第一次实跑直接把服务端打死了**:这是个 scanned repair(没有固定锚点),于是它把
      `KernelPackFinders` **自己那句调用**也重定向了 —— 指向自己,第一个 pack repository 就把栈用完了
      (`StackOverflowError`)。有固定锚点的 repair 不会犯这个错,scanned 的必须自己说明不许碰谁。
      已排除并加测试钉住(去掉排除立刻红)。修完 m12 GREEN,而且
      `MinecraftForge mods can add data-pack finders again` 在真服务端里真的打出来了。
- [x] **工单第七条:`BlockEvent$EntityMultiPlaceEvent`(←journeymap)—— 不是 Forbric 的问题**。
      `EventHooks.onMultiBlockPlace` 这个钩子存在,但**在 NeoForge 自己的补丁游戏里也是零调用点**
      (合并基底里同样是零)。也就是说 journeymap 的这个监听器在**原生 NeoForge 26.2 上一样不会触发**。
      判据是字节码,不是论证。 —— `hook-worklist.sh` 修掉名字匹配方向之后从 10 降到 7:
      `AddPackFindersEvent`←collective、`LivingEntityUseItemEvent$Finish`←nutritiousmilk、
      `MobSpawnEvent$FinalizeSpawn`←collective、`PlayerEvent$StartTracking`←collective、
      `BlockEvent$PortalSpawnEvent`←collective、`FurnaceFuelBurnTimeEvent`←balm(**NeoForge 的事件**)、
      `BlockEvent$EntityMultiPlaceEvent`←journeymap(**NeoForge 的事件**)。
      后两条值得注意:NeoForge 通常赢合并,它的事件死掉说明那两处是 Forge 侧赢了。
- [x] **B2** 可合并性 —— `MergeabilityCensus` + `run/mergeability-census.sh`,**实测有答案了**:
      995 条被丢弃的 Forge 钩子里判了 961 条,**640 条(66.6%)是 ADDITIVE** —— 两边都只往原版体里
      **插入**,没有谁重写原版做的事,所以两边的钩子原则上都能留。321 条是 OVERLAPPING,真的没有
      "同时是两者"的方法体。
      先报自己的可信度再报答案:三个 jar 来自三条不同的反编译流水线,所以在**没冲突**的方法上先标定——
      原版指令序列在 Forge 体里 98.0%、NeoForge 体里 93.2% 仍然是子序列。标定率是答案的天花板,
      而且偏差方向是**低估** additive,所以 66.6% 是下界。
      结论对 B1 很重要:三分之二的丢弃是**选择**,不是必然。
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
- [x] **C-api-surface(判定 + 补洞)** 计划里说的"抽成一张表"**不做** —— 两张表之间的接缝早就被
      `KernelRuntimeClassesTest`(11 个测试)钉死了:类、方法重命名、compiled/generated、
      永远走 game 侧,全都有断言。那是便利问题,不是正确性缺口。
      真正的洞是**覆盖不闭合**:原来的检查只覆盖 `GAME_BUS` 那一趟,其余靠逐个点名。
      新增 `noBridgeIsCoveredByNeitherCheck` 把 44 条封成一个集合(装了 / 或者是 late 落地的)。
      顺带一课:我先用 grep 数"哪个 bridge 没被任何测试提到",答案是 `GUI_OVERLAY_LAYERS`;
      按字节码真算一遍,答案是 `CLIENT_RELOAD_LISTENERS` —— **散文的量具又错了一次**。
      而它其实也是装了的,只是从多路复用器的另一个方法里装的,所以判据得是"这个类里任何地方"。
- [ ] **C-merged-base**(= B1 的另一面,要重建合并基底)
- [x] **C-arbitration(其一)** `ModPresence.isLoaded` 的 `-`/`_` 归一化 —— NeoForge 的 mod id 不许带 `-`,
      另外两家许,所以同一个 mod 跨生态就是两个拼写;而这个"专门用来跨生态回答"的注册表在用字符串比较,
      恰好跨不过两家唯一真正不同的那条边界。代价不对称:假 no 会让 mod 走"没装"分支而它其实装了。
- [x] **C-arbitration(其二·可见性)** 合成 mod info 上**没建模的 accessor 现在会被记下来**。
      返回值改不了 —— 类型是接口定的,"不知道"和"没有"只能是同一个空值。能改的是:内核不再是
      唯一一个不知道自己被问过的人。原案就是 Indigo 问跨生态的 Sodium 有没有渲染器,得到空,
      于是走了"这里没有渲染器"的分支,而 Sodium 已经把管线换掉了。每个不同的 accessor 记一次,
      在审计那一行里报出来。
- [ ] **C-lifecycle** / **C-arbitration(其三)** `getModContainerById`、落败版独有类保留

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
- [x] **D1(余下,已定性)** m14 本机跑了,**GREEN**,但客户端日志里**没有**频道普查行 —— 量出来的事实:
      注册那半边两端都记(客户端也注册 payload 类型,m14 客户端日志里有 2 行),
      但**申报那半边和报告点挂在连接自己的申报路径上,而那条路径只有在服务端是 Forbric 时才是 Forbric 的**。
      所以 m13(Paper)和 m14(原生 Fabric)这两个 gate 这里没有可断言的东西 ——
      说清楚这件事,比加一条"在缺席上也会通过"的断言好。
- [x] **D2** 性能 —— `KernelServerTicks` + `ServerTickSamplerInjector`,默认开(`-Dforbric.tickSampler=off` 关),
      每 600 tick 一行。**这是这个项目第一个 tick 时间数字**:实测 `1800 tick(s): mean 50.00ms, max 109.95ms,
      at twice the budget or worse 1 (0.1%)`,m12 已断言。
      两个坑自己踩了并修掉:① 用 `0` 当"还没有上一次"的哨兵 —— `nanoTime` 真的可能是 0,单测抓到;
      ② 第一版把阈值设成 budget 本身,于是报"48% 的 tick 超时" —— 保持 20 TPS 的服务器**本来就**稳在 50ms
      (多余时间用来 sleep),那测的是抖动不是延迟。阈值改成两倍 budget(没有 sleep 可还了)。
      JFR 不用改代码:`launch-kernel-{server,client}.sh` 本来就透传 `FORBRIC_JVM`。
- [x] **D3** 语言提供者 —— `modLoader` 第一次有了消费者(`LanguageProviders`,接在发现阶段),
      并且 Kotlin `object` 那个形状真的能构造了(没有公开构造器时取 `INSTANCE`,正是 kotlinforforge 自己的做法)。
      原来的失败信息是"no public constructor",一句关于一个没坏的 mod 的真话,而真正的原因就写在它自己的 manifest 里。
      判据窄:public + static + final + 类型是自己,否则一个叫 INSTANCE 的无关静态字段会被当成 mod 实例。
- [x] **D4(存档可携带性)** 新 gate `run/gate-m32-savedrop.sh`,**本机 GREEN**:三个 mod 写出一个世界、
      里面真的放了一块被摘掉那个 mod 的方块,然后**把那个 mod 拿走再开同一个世界**。
      `make-test-world.sh` 自己写着"反方向才是会烂的那个",而没有任何 gate 测过反方向。
      它先红了两次,而且是对的两次:方块没放进去、region 文件没找到 —— 分母不成立就不许绿。
      三个坑:26.2 的 overworld region 在 `world/dimensions/minecraft/overworld/`;没人在线时
      spawn 区块不常驻,要先 `forceload`;猜的方块 id 会让"世界里其实什么都没有"照样通过。
- [ ] **D4(长时程)** 长测 gate(内存泄漏 / 区块卸载 / 维度切换 / 几小时后的状态漂移)

## M-E 批评者补的

- [~] **E1** 跨生态内容级互操作 —— **先量了,没有造桥**。两个新 preset(`fabric-transfer-lookup` /
      `forge-capabilities`)在真实 97 jar 整合包上给出:**1 个 jar 用 Fabric 的 transfer/lookup**
      (Jade 的 Fabric 版 `JadeFabricUtils`),**10 个 jar 用 Forge/Neo capability**(sophisticatedcore 一族)。
      也就是说这条缝今天的暴露面很窄,而且它点名了能演示它的那一对:
      **Jade-Fabric 读 sophisticatedcore 的箱子**。造桥本身没做 —— 那是功能开发,而且我没法在本机验证
      "Fabric 的管道真的抽到了 Forge 机器里的东西"。
- [x] **E2** 归因对照 —— `run/compat/control-diff.sh`,**本机跑通**:同一组 Fabric mod 在
      **原生 Fabric 服务端**和 **Forbric** 上各起一次,然后说这个症状出现在哪一边。
      四个判决:`FORBRIC-ONLY`(实测:`Forbric/Mixin]` 原生 0 / Forbric 53)、
      `BOTH`(实测:`Preparing level` 两边各 1)、`NATIVE-ONLY`、`NEITHER`(实测),
      外加 `INCONCLUSIVE` —— 有一臂没起来的时候,"症状不在"是关于一台不存在的服务器的陈述。
      自己踩的坑:`grep -c || echo 0` 在没有匹配时会输出**两行**(grep 自己印 0 再退出 1),
      于是计数变成 `"0\n0"`,第一次跑把 Forbric 每次启动都会打的那个模式判成了 `NEITHER`。
      改成 `lib.sh` 的 `|| true` + `${VAR:-0}`。
- [x] **E3** 库 mod 爆炸半径 —— load-report 现在会写"还有 N 个 mod 说它们需要这个:…"。
      只陈述事实(它们声明了**必需**依赖),不判定它们也坏了 —— 判定就是这条分支已经删过一次的假指控。
      id 比较跨生态拼写,否则最可能两个生态都发布的那批库恰好报不出依赖者。
- [x] **E4** 载体版本漂移 —— `RepairDriftCensus` + `run/compat/repair-drift.sh`:把 claim 账本对着一个
      **候选**构建重放,点名哪些 repair 会不再适用。在载体升级之前跑,而不是等玩家报上来。
      实测当前 staged 与 Sep-10 备份两个基底都是 44/44 落地;负控制(候选里有一个类已经自带初始化)
      恰好点名那一条 claim。
