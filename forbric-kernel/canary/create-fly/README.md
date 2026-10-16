# Create Fly 加载回归

实测文件：`create-fly-26.2-rc-2-6.0.9-1.jar`（Fabric，Minecraft 26.2）。
下载地址：<https://cdn.modrinth.com/data/dKvj0eNn/versions/phlsMPgT/create-fly-26.2-rc-2-6.0.9-1.jar>。
SHA-512：`2879187dcb1aa494710b71e4d967ac8f12d38e5f4b800652d38c484c1db3a38a72bf624e8dc0b53a457ccdf62f479c3264e1d5cbb2418e23da42bdce9c738f54`。

在 `forbric-kernel` 目录运行专项测试：

```sh
./gradlew --offline -Pforbric.createFlyJar=/绝对路径/create-fly-26.2-rc-2-6.0.9-1.jar \
  test --tests '*Create*MixinAdapterTest' --tests '*ExitGuardParkedOrphansTest' \
       --tests '*CreateTaskWaitTest' --tests '*FabricFreezeHookMixinAdapterTest' \
       --tests '*CreateInjectionAdaptersTest' --tests '*CreateCallbackScopesTest' \
       --tests '*CreateCarrierHooksTest' --tests '*FinalMixinApplicationsTest' \
       --tests '*StringVersionCompatibilityTest' --tests '*SoundRegistryIdentityInjectorTest' \
       --tests '*ServerReloadListenerNamesInjectorTest' \
  transferTest --tests '*IdentityValueBiMapTest'
```

未指定路径时读取 `build/compat-inputs/create-fly/create-fly.jar`；缺少真实模组文件的专项用例会跳过。
字节码测试还需要已准备好的游戏与 Forge/NeoForge 运行时文件。

2026-10-01 客户端验证：仅装 Create Fly，严格兼容模式，进入独立的原版测试世界；第 100 tick 截图、
第 200 tick 退出世界，进程自行以 0 退出。加载报告中 Create Fly 为 `OK`，已确认的必需功能缺失为 0。
覆盖加载和退出流程，以及声音对象身份、按键回调和 Flywheel 线程池收尾；未覆盖所有机械、列车和 Ponder 功能。

2026-10-04（issue #52）：Create Fly + fabric-api 0.161.0 一起装。修复前专用服在 `Bootstrap` 里崩溃：
`Registry is already frozen (trying to add key ResourceKey[minecraft:root / create:arm_interaction_point_type])`；
原生 Fabric 0.19.5 同样两个 jar 正常到 Done。原因是 fabric-registry-sync 在原生 Fabric 上把原版冻结推到所有 main
入口点之后，Create Fly 据此在装了 fabric-api 时改由 `onInitialize` 创建注册表，而 Forbric 的冻结仍在 `Bootstrap`。
修复后，Fabric 模组挂在 `BuiltInRegistries.freeze()` 上的 HEAD/TAIL 注入改到 Fabric 入口点之后执行
（`FabricFreezeHookMixinAdapter` + `FabricFreezePointInjector`，`-Dforbric.fabricFreezePoint=off` 可退回旧行为）：
专用服到 Done 并正常停服，`create:` 共 1772 项，与不装 fabric-api 时一致；客户端严格模式进入测试世界，
200 tick 后自行退出（退出码 0），Create Fly 与 fabric-api 均为 `OK`，已确认的必需功能缺失为 0。

后续注入验证：主动加载这套配置启用的 145 个混入目标类，在实际 GUI 实例确认 21 个 Create 自定义渲染器。
严格模式下运行 300 tick、截图并正常退出；最终报告没有 `SUSPECTED` 或 `CONFIRMED` 注入问题。
独立检查导出的最终类字节码，也没有零引用的注入处理方法或局部捕获桥。

`probe-src/probe/CreateFlyProbe.java` 是只读客户端覆盖探针。编译成独立 Fabric 测试模组，在 `client` 入口注册
`probe.CreateFlyProbe`；进入世界后会加载已启用目标并写出 `create-injection-probe.txt`。探针不代表每项机械玩法都已验证。
