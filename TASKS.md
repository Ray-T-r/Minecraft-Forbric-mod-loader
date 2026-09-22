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
- [ ] **A2** `LostHookCensus` —— 把 682 条运行期 `forge hook lost` 分成 DATAGEN / RUNTIME_DEAD / RUNTIME_LIVE
- [ ] **A3** 钩子调用点普查(把一次性的 `javap` 升成常驻测试)+ 每条桥的生产者必须有调用点
- [ ] **A4** 给 `DeadEventAudit` 的手写表配生成器;让审计看见 `addListener`
- [ ] **A5** 元普查:gate-m0 skip 上限、`build.gradle` 的 `inputs.files`、`StagedArtifactCoverageTest` 覆盖新普查

## 可插队(零/低成本)

- [ ] **D2a** `check_absent "Can't keep up"` —— 全仓对这个字符串零命中,而它是 vanilla 自己会打的
- [ ] **E0** `REPAIRS` 实际 49 条,javadoc 写"Forty"、`AnchorSet` 文案写"47" —— 改成从 `REPAIRS.size()` 生成

## M-B 仲裁

- [ ] **B1** 不翻 `MergedBaseBuilder.java:957` 的默认方向;用 A2 的 `RUNTIME_LIVE` 驱动 `FORCE_FORGE_METHODS` 白名单
- [ ] **B2** 可合并性探索(只出报告,不承诺落地)
- [ ] **B3** 把合并基底构建接进内核构建/CI 回路
- [ ] **B4** 相位内顺序的两条结构性约束加断言(不动相位间顺序)

## M-C 五族收口

- [ ] **C-merged-base** / **C-api-surface** / **C-mixin**(`-Dforbric.mixinDiagnostics` 已在代码里,没有 gate 跑过)
- [ ] **C-lifecycle** / **C-arbitration**(`isLoaded` 的 `-`/`_` 归一化、"不知道"≠"没有")

## M-D 四个盲区

- [ ] **D1** network-protocol:`NetworkChannelCensus` 单点产出,五个网络 gate 各一条 check
- [ ] **D2** performance:`ServerTickSampler` + JFR 透传 + m31 式并排对照
- [ ] **D3** 语言提供者:`modLoader` 被解析被暴露但 `src/main` 零消费者(Kotlin-for-Forge / lowcodefml)
- [ ] **D4** 时间轴与存档可携带性:长测 gate + 摘 mod 后开旧世界 / 跨构建搬世界 / 拿回原生 loader

## M-E 批评者补的

- [ ] **E1** 跨生态内容级互操作(Fabric transfer/lookup ↔ Forge capability:**零桥、零测试**)
- [ ] **E2** 常驻对照实例(归因准确率本身是一等产品问题)
- [ ] **E3** 库 mod 爆炸半径(load-report 点名库,不点名被它拖死的十几个)
- [ ] **E4** 载体版本漂移:49 条 repair 对新载体的有效性普查
