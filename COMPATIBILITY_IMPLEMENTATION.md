# Compatibility implementation ledger

Branch: `codex/compatibility-contracts`, based on `b890449`.

This is the implementation/evidence ledger for the approved P0–P3 plan, not release documentation.
Claude memory is read-only. Each implementation batch is tested before being committed as
`Jerry <rt.ge.jerry@gmail.com>`. A passing unit test is not a claim of game-level compatibility.

## Required work

Status after the 2026-09-23 continuation (see the last sections). [x] means implemented with unit/JVM tests and,
where the item names game behaviour, a real game run; [~] means implemented with named limitations.

- [x] P0: reviewed, tracked link baseline shared by dev, integration gates and installer; missing inputs fail.
- [x] P0: evidence binds source revision/content, tool/input/output hashes, versions (pins read from the jars),
      the jars the tests actually read, merge provenance and mod manifest; a release sweep refuses SKIP.
- [x] P0: symmetric, owner-qualified hook attribution over every hook-lost row form; raw loss, restored,
      residual and unobserved reported separately.
- [~] P1: conservative three-way composition with counterexamples and explicit rejection reasons. The merger
      composes the portal pilot on the real jars (accepted 1, declined 998 with per-reason counts); fall damage is
      a pinned must-refuse counterexample; the spawner stays a runtime repair (the merger refuses it, reason named).
- [x] P1: portal return-value/cancellation composition; no duplicate legacy bridge (structural stand-down).
- [x] P1: spawner input/data fidelity and exactly one finalization; the repair stands down if a base ever carries
      both native calls; reject unsafe fall-hook composition.
- [x] P2: structured suspected/confirmed/resolved findings and final Mixin outcome reconciliation, including
      MixinExtras injectors, kernel-made removals and superseded mixins resolved only on structural proof.
- [x] P2: confirmed necessary failures require an explicit client decision in the existing dependency window;
      headless/release strict; a client refusal is a typed exit-78 stop, not a vanilla crash.
- [x] P2: safely present late failures (paged, re-asked on rejoin); targeted access replay after descriptor repair.
- [~] P2: dependency/member/Mixin-constrained arbitration with explicit override and unsatisfiable findings.
      Member-level Mixin contracts (@Shadow/@Invoker targets) are not modelled; only target classes are.
- [x] P3: real Fabric/Neo item/fluid transaction coordination, including nesting and re-entry.
- [x] P3: audited Forge snapshot adapters and legacy simulate/execute views.
- [x] P3: server block-entity lookup integration, owner-ecosystem precedence, direction, invalidation (incl.
      chunk unload) and cycle guards.
- [x] P3: exact fluid units and lossless metadata; unsupported providers remain unavailable for writes.
- [ ] Acceptance: native controls, mixed pack, real actions, multiplayer, save/reload and world re-entry, on the
      merged candidate (see "Final acceptance").
- [ ] Acceptance: at least two hours of sustained operation on the exact candidate artifacts.

## Evidence and decisions

The original staged base is a reference input, not an output built from this branch. Its reviewed raw-link
baseline has 24 references: capability composition handles many at runtime, while other entries remain
accepted known defects. This list is not a claim that all 24 are harmless or repaired. New raw defects fail.

The new worktree reads reference artifacts from the original checkout via `FORBRIC_OLD`; generated game
instances, output jars and test results must stay in the isolated worktree. Rebuild candidates into separate
paths. Never replace the original checkout's staged base or modify a player's world to test a candidate.

Pending implementation and acceptance items remain open even when a smaller batch passes its tests.

### P0 link-gate batch

- `forbric-loader/run/test-link-check.sh`: 26 assertions pass, including new-defect, missing-baseline,
  missing-artifact and empty-scan negative controls, and both file/resource baseline paths.
- `forbric-kernel-installer/run/test-link-gate.py`: the real installer child process accepts a linked fixture,
  rejects a deliberately removed field, and rejects a merged jar with no classes. The bundled baseline is
  byte-identical to the tracked source.
- Reference staged jars: 15,372 classes scanned, 24 known raw dangling references, zero new references.
- `mergeToolsJar` builds offline. The installation gate now throws on a failed or absent verdict, before the
  new version profile can be published. The ordinary boot-only Gradle tasks do not require staged jars.

### P0 evidence runner batch

- Added `run/compat/evidence.py` capture/verify/run with source, artifact and recursive mod fingerprints.
- Eleven tests pass, including same-size binary replacement, new/deleted source, newly added mods, nested
  versions, missing release inputs, and an exit-zero acceptance command that changes its inputs.
- The release input set and clean-source rule are enforced. A recorded successful command is not a claim
  that its own test assertions cover the entire compatibility plan; the full candidate sweep remains pending.

### P1 constrained-merger foundation

- Twelve tests pass, including complete operand/branch/bootstrap comparisons and loading/executing the merged
  fixture jar in a real JVM with its original stack maps and exception handlers.
- The first supported grammar is two stack-neutral static hook prefixes before an exactly identical vanilla
  body; uncertain shapes retain the previous winner and receive an explicit refusal reason.
- Trial on the actual three game jars: zero accepted, 999 declined. Output is under
  `/private/tmp/forbric-p1-merge-check/`; no original staged file was replaced. This is infrastructure, not
  evidence that any current mod's lost game behavior has been restored. The P1 behavior pilots remain open.

### P2 finding and decision core

- Added stable suspected/confirmed/resolved findings, machine JSON including unclassified legacy failures,
  and a reversible display projection into ModCatalog. Preserved the mod's original Mixin `required` flag
  before relaxing its config, so an actual application failure retains the correct contract.
- New necessary-function confirmation requires an explicit continue decision; old dependency behavior is
  preserved. Strict decisions cannot be waived by a prior interactive choice. Boot and safe late-UI
  invocation are a separate, still-pending integration batch.
- 133 tests in 11 suites passed with no failures or skips. XML and command log are archived under
  `forbric-kernel/build/verification/compatibility-core/` before other Gradle tests overwrite their outputs.

### P0 symmetric raw-hook attribution

- Hook identities now include owner, name and descriptor; both Forge-lost and Neo-lost report rows are
  traversed. Missing methods are counted as unobserved; unreadable mod inputs fail instead of implying no use.
- Three tests pass, including a full synthetic two-family report and the same-name/different-owner collision.
- Reference run: all 1,000 historical conflict rows examined, 705 without a modelled direct hook, 9 candidate
  trades with only a lost event type observed and 14 with both event types observed. These are raw-input
  observations across six facades, not remaining defects after runtime repair or proof of live subscriptions.
- Log: `forbric-loader/build/verification/hook-attribution/reference.log`.

### P2 prompt integration and P1 portal pilot

- Startup checks now consume confirmed necessary findings. Late client findings use the native confirmation
  screen from a client tick; refusal saves/disconnects to the title, strict mode stops normally. Dedicated
  server world-start failures request a normal halt. Per-launch state resets and atomic JSON reports are wired.
- The portal caller now forwards the complete Neo→Forge Optional result, and the old event bridge skips only
  the wrapper's thread-local dispatch scope. Cancellation, nesting, exception cleanup and cross-thread
  independence are covered. Both current native carrier hooks return the original shape or empty; shape
  replacement is a contract probe for a mod-rewritten hook, not an invented native event setter.
- The integrated batch passed 114 tests in 12 suites, no failures/errors/skips. Evidence is archived under
  `forbric-kernel/build/verification/p2-integration/`. The portal probe executes actual carrier hook bytecode
  with isolated world/bus boundaries; a full-game portal action and actual GUI acceptance are still pending.

### P1 spawner and P2 restored-access batch

- The spawner transformer proves the ValueInput's source through the actual Mob/entity-load data flow, then
  passes it to an eight-argument runtime entry. Both event families see the data before exactly one possible
  finalization. Listener exceptions propagate. An unproved input retains the Neo path with a necessary-loss
  finding instead of pretending a null input is sufficient.
- Native parity was rechecked after review found an incorrect first implementation: spawn veto forbids world
  insertion but does NOT skip finalization; only event cancellation does. The real native caller and bridge
  now agree, including discarding finalizeSpawn's return. A replaced spawn tag is recorded as native parity,
  not a new compatibility failure.
- Access replay targets only previously missed explicit AW/AT members after COREMOD, before Mixin. It does
  not repeat interface/enum injection or apply AT wildcards to unrelated new members. Repeat/parallel byte
  requests do not depend on a diagnostic row still existing. The m9 assertion now requires restoration rather
  than pinning the old unresolved diagnostic. Existing featuresPerStep repair already set public/non-final;
  this batch verifies/reconciles that actual result and covers repairs that restore only the descriptor.
- 42 tests passed with zero failures/errors/skips, including actual merged ChunkGenerator bytes, external JVM
  field writes, repeated transformation, and native spawner differential probes. Evidence:
  `forbric-kernel/build/verification/spawn-access/`. Full-game action gates remain pending.

### P0 actual bundled-artifact identity

- Bundled game-side jars now extract into SHA-256 addressed directories and are reused only when the bytes
  match. A previous instance can keep its old archive open while an updated instance loads its own build;
  an arbitrary readable old archive is no longer accepted after a failed overwrite. Required missing bundles
  fail at extraction. Writes are staged and atomically installed where the filesystem supports it.
- Six tests pass without skips, including an open old archive, exact-byte reuse, a valid-but-wrong cached jar,
  invalid bytes, and the actual badpackets two-level old MixinExtras fixture. Evidence:
  `forbric-kernel/build/verification/bundled-provenance/`.

### Early game observations (not final release acceptance)

- The isolated zero-mod M1 server reached Done, ticked, saved all dimensions and exited normally.
- A fresh merged candidate built from the installed fixed-version inputs has 24 known/zero new raw link
  defects. The installed Neo patched input's SHA-1 matches the reference staged input and its .pins file
  records NeoForge 26.2.0.88, NFRT 2.0.18, gameJarNoRecomp. Output stays in `forbric-kernel/build/candidate/`.
- Transfer core: 23 JVM transaction tests pass; the first real game run passed only 2/11 because final
  ItemStack/CompoundTag shapes were refused. This is unresolved and must not be counted as working Forge
  transaction writes. Final-definition dumps are being compared; no audit bypass was enabled.

### P2 necessary initialization decisions

- Actual withdrawn constructors and failed Fabric main/client/server entrypoints now become stable required
  findings at explicit report/decision boundaries. DEGRADED remains unclassified unless separately proved;
  mixed aggregate reasons are not all promoted. A different lifecycle failure requires a new acknowledgement.
- The report remains pure output. Explicit lifecycle boundaries enforce the decision, and the launcher maps
  only typed policy stops/cause chains to exit 78. Development launchers default to strict; installed profiles
  retain ask. Reports no longer claim that every reported run reached a usable game.
- The integrated 60-test batch passed, together with 23 separate transfer-engine tests (those do not prove
  full transfer gameplay). M24 then passed all three real server cases: explicit continue retained the one
  required failure, strict stopped with 78 before Done and without a game crash report, and a healthy strict
  control reached Done and exited 0. Evidence: `build/verification/initialization-policy/gate.log` and the
  three `build/gate-m24-*.log`/compatibility reports. This is an expected-failure policy test, not a claim that
  the broken canary is compatible.

### P2 joint candidate selection, first batch

- Whole-jar exact-cover selection now checks required versions, unconditional required Mixin targets and
  direct entrypoint member contracts; explicit overrides remain visible when unsatisfiable. Ecosystem order
  ranks feasible combinations, and bounded-search/unknown results are not labelled solved.
- Static/instance fields and calls, class/interface owners, inherited members, and potential Mixin/AT/AW
  changes are distinguished. Pre-transform uncertainty is not a confirmed incompatibility.
- 56 tests passed without skips, including 20 new solver/scanner cases and the actual staged Jade pair.
  Evidence: `forbric-kernel/build/verification/compat-arbitration/abi-junit/` and `abi-tests.log`.
- This batch covers the top-level decision. Parent-reachable nested candidates and JarJar coordinate/range
  selection still need the next discovery batch; the whole-instance arbitration requirement remains open.

### P3 transaction and block-query implementation

- Added game-side Fabric/NeoForge native transaction pairing, including nested rollback, cross-API reentry,
  scope-order checks, and final notifications only after both native scopes close. Forge simulation rolls back;
  execution commits. Exact audited standard Forge handlers use a shared object-graph journal that restores
  backing containers, stack identity, aliases and data. Unknown subclasses/proxies/validators are refused.
- Final post-Mixin class audits cover transfer-critical methods and helpers. Known unrelated extensions are
  accepted only with structural evidence; altered copy/count/validation code and unproved helpers remain
  unavailable for transactional writes. Fluid conversion preserves 81:1 precision and rejects lossy metadata.
- Installed fallback queries preserve native-provider priority, face/null access, loaded-server-block scope,
  invalidation and fresh provider lookup. The boot seam checks optional API/runtime availability without
  loading game types early. Committed Forge writes dirty the current block entity once per root commit.
- 25 boot/transform tests and 23 real-engine transaction tests pass without skips. The exact final game
  definitions then passed all 11 Forge storage scenarios in a real server, which saved and exited 0. This
  resolves the earlier 2/11 shape-audit failure without bypassing the audit. Evidence:
  `forbric-kernel/build/verification/transfer-core/`. Real public world-query routing, persistence and the
  bridge-off negative control are still the next M33 batch; these core results do not substitute for it.

### P0 final-definition hook evidence

- Opt-in `-Dforbric.definedClassEvidence=<directory>` records only successfully defined final class bytes,
  each with SHA-256 in a unique loader-session manifest. Pre-Mixin previews, failed definitions and duplicate
  reentrant attempts do not overwrite this evidence. The setting is off during normal play.
- LostHookAttribution accepts the manifest directory as its eighth argument. It separately counts original
  losses, direct restoration, exact static paths through defined kernel helpers, residual direct-call loss,
  and unobserved callers. Hash mismatch, missing/empty evidence and identity mismatch fail closed. These
  structural categories do not assert execution, cancellation or return-value fidelity; event-bus bridges,
  reflection and unmodelled paths remain explicitly unassessed.
- Twelve class-loading/evidence tests and six report tests pass with no skips. Evidence is archived under
  `forbric-kernel/build/verification/defined-class-evidence/` and
  `forbric-loader/build/verification/effective-hook-evidence/`. Full-game coverage export remains pending.

### P2 whole-instance nested candidate selection

- Discovery now inventories roots and both Fabric/JarJar nested declarations before choosing any winner.
  SAT constraints bind children to selected parents, preserve same-ID wrapper payloads, and satisfy JarJar
  coordinate/version ranges alongside dependency/member/Mixin contracts. Symbol supply is per physical jar.
- Content-addressed extraction prevents same-basename or same-size collisions. Both ecosystem discoveries
  consume the same selection; final SHA-256/materialization checks never silently select a second winner.
  Unknown metadata and bounded searches remain explicitly unproved.
- 65 tests passed with zero failures/errors/skips, including nine new graph/discovery cases, actual Jade
  candidates and existing nested behavior. A test caught SAT4J exposing vector capacity as zero literals;
  assumptions now copy only logical entries. Evidence: `build/verification/compat-arbitration/nested-junit/`.
  Real nested-pack and broad client gates are still pending.

### P3 real world routing and native controls

- M33 passed on the fresh candidate: 12 item and 12 fluid routes across all six directed ecosystem pairs with
  NORTH/null access, SOUTH rejection, native-provider priority, replacement invalidation and stale optional
  refusal. An outer rollback restored the real inventory after a fractional fluid operation; 17 Fabric units
  stayed in their source. Total inventory remained 60 items and 48,617 Fabric fluid units.
- The same world saved and reloaded the exact item components and fluid quantities. A separate clean-chunk
  probe showed abort leaves it clean, root commit dirties it, and both item/fluid writes persist on reload.
  The bridge-off run failed the actual public lookup assertions as expected. All three phases had unchanged
  source/artifact/mod fingerprints. Evidence: `build/verification/m33-transfer/` and `build/m33-driver.log`.
- True native Fabric 0.19.5, Forge 26.2-65.0.1 and NeoForge 26.2.0.88 servers passed the same fixed-seed public
  API scenarios as Forbric using byte-identical canary jars. All three comparisons are MATCHED_PASS, with
  initialization/start/command registration exactly once, at least 20 real ticks, three world actions and
  clean exit. Native controls contain no Forbric dependency. Evidence: `build/native-controls/results/`.
  These scenarios do not replace the broader client, multiplayer and sustained-operation requirements.

### Persistent checkout recovery and full integration gate

- The temporary worktree disappeared after the interrupted session. All committed code was recovered from
  the branch into `build/compatibility-contracts` under the original checkout; original source and worlds were
  not edited. Uncommitted behavior/soak drafts were recovered from this task's own tool records and still
  require validation. Historical generated evidence in the temporary directory is no longer available.
- Rebuilt the candidate from the same fixed inputs: 24 known/zero new link defects. Restaged read-only
  third-party fixtures and reran M0: 1,792 tests ran, zero failures/errors/skips, both three-ecosystem discovery
  controls matched, and the candidate link gate passed. The separate 23 real-engine transaction tests pass.
- M0 now rejects any skipped fixture, uses the requested candidate paths and checks the actual build exit
  status. The oracle rejects missing/empty fixtures; its missing-directory negative control passed. Fixed
  portable native-control cache lookup, documented evidence tests, declared the replay transformer's dynamic
  targets, and updated the boot boundary assertion to the typed continuation check. Evidence:
  `forbric-kernel/build/m0-candidate-driver.log` and `build/verification/recovery-{test,transferTest}/`.

### P1 actual caller behavior, positive and repair-off controls

- M35 passed all 11 actual-world cases: four portal decisions including a mod-rewritten shape result, six
  native spawner paths with real ValueInput identity/data and distinct finalization/insertion cancellation,
  and natural item consumption through real entity ticks with Neo→Forge result/component writeback.
- The same jar failed exactly the expected cases when repairs were disabled: portal replacement only; five
  Forge-dependent spawner cases; item result only. Four runs reached the game and retained unchanged input
  hashes; crashes, missing probes or startup failure cannot satisfy the negative controls.
- The initial probe incorrectly equated the public visible-entity lookup with insertion. Native bytecode
  proves ServerLevel marks added-to-level only after its section manager accepts the entity; new sections
  may not yet be visible during the same tick. The test now asserts that actual insertion flag and records
  public visibility separately. Production spawn behavior was not altered to accommodate the test.
- Evidence: `build/verification/m35-behavior/`, final defined classes in `build/verification/m35-defined/`,
  and the structural raw/effective hook comparison in `build/verification/m35-hook-attribution.log`.

### P2 final attachment reconciliation and safe late dedicated-server handling

- Retains original config/default requirements and final adapter injector declarations. After successful JVM
  definition, exact Mixin rename metadata and merged-handler references distinguish missing necessary
  standard injectors from optional zero matches. Group alternatives, unknown renames, incomplete minimum
  counts and unsupported extension forms remain unproved. An aggregate suspicion resolves only after all
  understood injectors/targets have been observed; a confirmed independent failure is never erased.
- Late dedicated-server findings are now consumed after a completed tick, independently of optional event
  forwarding. Strict/headless refusal requests normal save/stop once; integrated servers retain the client
  screen path. Recording a finding itself never stops the server. Explicit continue preserves the finding.
- 34 focused tests passed with zero failures/errors/skips. Real M36 then passed required-strict,
  required-continue, optional and plugin-declined cases with unchanged hashed inputs. Its actual Mixin
  silently misses one default-required injector: strict mode runs the valid handler and stops normally
  before tick three; optional/declined runs finish normally, and continue leaves the necessary loss visible.
  Evidence: `build/verification/final-mixin-and-late-server/` and `build/verification/m36-outcome/`.

### Sustained-run controller (acceptance not yet performed)

- Recovered and completed the M34 controller and frozen-input launcher. It requires occupied, unpaused
  server/world tick advances, all three dimensions, six fixed chunk unload/reload probes, normal saves and
  new integrated-server objects in the same JVM. Idle time cannot replace measured simulation. Old server
  weak references, heap/thread/chunk counters and thread dumps support retention review.
- Release mode enforces >=7,200 active seconds, >=144,000 real ticks, three sessions, committed unchanged
  sources, unchanged copied binaries and strict policy. A short explicit control can never be release proof.
- 29 focused JVM tests and ten independent Python verifier negative controls pass. Compilation uses
  ServerPlayer.level(), the actual 26.2 API. Evidence: `build/verification/soak-model/` and `build/soak-tests.log`.
  Short real-client control and the full two-hour run are still pending; this batch is not soak acceptance.
- The first renewed M9 mixed-pack run stopped strictly on 13 final missing-injector findings. Those need
  equivalent-implementation/remaining-loss review before a passing broad client acceptance can be claimed.

### P2 preserve fixed-index argument injection after appended parameters

- The full mixed client exposes 16 missing standard handlers after world loading. One is Fabric registry
  sync's actual WorldLoader list replacement: NeoForge appended a fifth argument, while the modifier still
  named the four-argument call. The adapter now handles single-argument ModifyArg only when its index is
  explicit and its parameter/return type match the same original prefix argument. Full-argument handlers,
  inferred indices, groups, changed prefixes/returns and ambiguous call forms remain unchanged.
- 23 tests passed, including the actual Fabric API 0.155.2 WorldLoader and current five-argument game call.
  M36's six real-game cases passed: the adapted argument becomes changed while the added context survives;
  disabling the adapter leaves the original value and a confirmed necessary loss with normal strict halt.
- Evidence: `build/verification/fixed-index-widening/`, `build/verification/m36-outcome/` and
  `build/m36-fixed-index-driver.log`. The remaining mixed-client findings still require work.

### P2 restore actual Fabric entity event contracts

- Rebound effect callbacks to NeoForge's corresponding validation/removal stage and gliding callbacks before
  both its attribute and equipment branches, preserving the movement prerequisites. The nearby-monster
  check follows the actual same-signature lambda invoked by the native sleeping method. Ambiguous shapes,
  shifted constructor phases and callback groups are refused. Native vanilla bodies remain unchanged.
- The bed occupancy redirect accepts only the reviewed original handler instruction body. Its replacement
  honors Fabric's handled result, then uses the current block's native setter. A Fabric non-bed is not
  overwritten, and a NeoForge custom bed without vanilla OCCUPIED retains its own property and setter.
- 22 focused tests passed without skips. M37 then passed eleven strict real-world cases and all eleven
  precisely failed with the adapter disabled. Cases prove duration writeback, effect removal timing, flight
  veto/custom results, ordinary/custom/non-bed state and nearby-monster sleep success. The positive run had
  zero confirmed required findings; both runs retained unchanged source/artifact/mod hashes and saved normally.
  Evidence: `build/verification/entity-callback-adapters/` and `build/verification/m37-entity/`.
- This is the actual unmodified Fabric entity-event module and base module. The full mixed pack still needs
  its remaining item, sound, renderer and equivalent-implementation findings resolved; no broad acceptance
  or two-hour result is claimed by this focused gate.

### P2 prove known replacements instead of reporting them as lost

- Final injection reconciliation now recognizes the condition skip consumer only when the original Fabric
  handler instruction fingerprint, both actual kernel filter calls, and both audited native Optional
  consumers agree. A missing filter, changed consumer or unknown handler remains a confirmed loss. A repair
  name or registration alone cannot resolve it. Original marker handling and native consumers are pinned
  to reviewed upstream executable bodies; shared fingerprints ignore only non-executable metadata.
- Real compiled KernelFabricConditions and real DataResult tests prove that the marker no longer reaches
  the casting consumer, normal Optional data and decode errors are retained, and the unfiltered baseline
  throws. Final-ledger tests prove RESOLVED becomes CONFIRMED again if its structural witness is removed.
- Machine reports include effective policy. M9 requires a fresh STRICT report with zero necessary losses
  and no unclassified failed initialization. Its actual shell section rejects missing/stale/continue and
  contradictory reports. The access assertion now checks real replay instead of an obsolete warning line.
- 47 tests passed, zero failures/errors/skips. Evidence: `build/verification/equivalent-implementation/`,
  `build/verification/equivalence-reviewed-symbols.json` and `build/equivalence-tests.log`.

### P2 preserve Fabric and native enchantment decisions plus server language resources

- Three actual item-event redirects follow native stack-based decisions. The primary-enchantment method
  reference gets a typed same-capture wrapper so the real Fabric injector can attach. Fabric's default item
  implementation delegates to native item decisions; explicit Fabric event opinions and per-item overrides
  keep their original precedence. Reviewed handler bodies, fixed descriptors and unique sites constrain edits.
- The item module's real dependency closure exposed two additional server-language gaps. Fabric language
  merging now occurs before the native map capture without replacing the native mutable/string/component
  maps. Resource opening follows the live parser overload. Minecraft's builtin container has its actual game
  jar roots while remaining excluded from foreign-mod presence aliases. Unknown bodies/groups stand down.
- 14 focused tests pass without skips. M38 passed twelve strict real-world command/loot/candidate cases and
  all twelve failed with item adaptation off, with unchanged per-phase inputs. A Fabric-only language mod,
  vanilla Stick translation and Minecraft version.json path also pass in the real server. Evidence:
  `build/verification/enchantment-and-language/` and `build/verification/m38-entity/`.

### P2 mining, stale block entities and contextual destruction rendering

- The audited Fabric mining handler now preserves native reset/continue decisions and invokes the explicit
  same-item Fabric override only when needed. A JVM execution test checks all four decision branches and
  exact player/old/new object propagation through the adapted upstream handler.
- The stale block-entity removal hook follows the uniquely proved blockEntities receiver, not the unrelated
  pending-NBT map. It moves only when that removal precedes the original createBlockEntity slice boundary.
  The renderer's pure no-op redirect accepts the exact new context arguments; any nontrivial body is refused.
- 23 focused tests passed without skips. The real full mixed client entered, simulated, saved and exited
  after these changes. Its confirmed necessary list fell from 16 to three: sound and two Litematica rendering
  callbacks. This was explicit continuation for diagnosis; M9 correctly remained RED under the strict-report
  rule. Evidence: `build/verification/client-anchor-adapters/` and `build/m9-after-client-adapters-driver.log`.

### P2 compose sound stream overrides without bypassing native priority

- The audited native SoundInstance default now dispatches Fabric's audio-stream callback after the actual
  interface graft. A sound overriding the native method keeps normal virtual-dispatch priority. The original
  upstream redirect follows that native call only after its default dispatch is structurally present.
- Four focused tests pass with zero skips. They execute the adapted upstream default and handler in a JVM,
  verify identical future/library/path/loop values, and prove native overrides bypass the Fabric fallback.
  Missing graft/API, unknown bodies and the off switch remain unchanged. Evidence: `build/verification/sound-contracts/`.
- Full mixed-client acceptance follows the remaining inserted-parameter rendering adapter; this focused
  result does not by itself prove playback or full-pack acceptance.

### P2 retain explicit lambda callbacks across uniquely inserted parameters

- The shim requires actual pruner evidence, a single referenced live lambda, matching staticness/return,
  and a unique ordered parameter embedding. Repeated resource handles retain their positions. It refuses
  groups, locals/sugar, unknown anchors and ambiguous mappings. The original private helper retains its
  name; its synthetic injector forwards the original callback object, preserving cancellation.
- Seventeen focused regression tests pass with zero skips, including the actual two Litematica handlers
  and a JVM execution probe for repeated objects, wide local slots and cancellation.
- M9 passed on the full 97-jar client in STRICT mode: rendered world, simulation, normal save/disconnect,
  normal JVM exit, and a fresh report with zero confirmed required losses. Sound and both rendering
  callbacks are now attached. Four pre-existing unclassified DEGRADED rows remain visible and are not
  promoted to verified functionality by this result. Evidence: `build/verification/inserted-lambda/`.

### Acceptance runner fixes from actual launch attempts

- M9's actual sound/lambda off control restored exactly the three named required losses; the diagnostic
  continue run remained RED. Reports and logs are retained beside the strict-positive artifacts.
- M34's first snapshot attempt correctly failed before launching: a library Path shadowed the captured
  source record. Separate source identity now survives the copy loop; a real launcher snapshot test with
  a replaced external JVM checks the complete frozen manifest/verifier path. Eleven Python tests pass.
- The full M0 worker aborted while constructing Swing components. Those unit tests now use explicit
  headless mode; actual window behavior remains the separately forked GUI/client gate's responsibility.
  50 focused Java tests passed without skips. The interrupted M0 is RED and will be rerun.
  Evidence: `build/verification/soak-launcher/`; no short or two-hour game soak is claimed yet.

### P2 dedicated-server block-entity removal and final P3 world routing

- The actual server lifecycle mixin uses the same displaced stale-block-entity map removal as its client
  counterpart. Both now use the existing receiver/dataflow proof; neither handler body is rewritten.
  Six focused tests passed with zero skips, including the unmodified upstream server handler.
- M33 then passed all public item/fluid routes, side/null restrictions, native precedence, replacement
  invalidation, exact fractional rollback/retry, real save/deserialization and the bridge-off negative.
  Each phase retained unchanged source, artifact and mod hashes. Evidence: `build/verification/m33-transfer/`.
- Before this small server-only extension, full M0 passed 1,864 tests without skips and all discovery/link
  gates; link-check synthetic negatives and the actual installer subprocess tests passed as well.
- M34 short control completed two normal same-JVM sessions, 5,430 simulation ticks and all six chunk probes
  unloading/reloading. It remains REVIEW_REQUIRED because retired servers stayed reachable. A heap dump
  identifies the old-server path through Unlit Campfire's static CAMPFIRES set, a saved campfire and its
  level. Native comparison/review remains required; no full soak pass is claimed.

### Native controls and attribution of the observed retained world

- The three pinned native loaders and Forbric each passed the same own-ecosystem initialization and
  world-action canaries. Comparisons verified identical mod hashes, seed and actions for all three pairs.
  Durable evidence: `build/verification/native-comparison/` and `build/native-controls/results/`.
- A separate campfire probe uses the unmodified Unlit Campfire 26.2-4.1.0.0 jar, saves a real campfire,
  stops normally, then reads the upstream static cache during JVM shutdown. Native NeoForge and Forbric
  both retain one campfire whose level references the stopped server; both use identical mod hashes.
  No cache is modified. Evidence: `build/retention-control/comparison.json` and its per-arm manifests.
- This reproduces the exact shortest root found in the short-run heap. It establishes one native mod
  retention issue, not absence of other roots. M34's REVIEW_REQUIRED result remains visible; neither
  release acceptance nor a two-hour run is claimed by this attribution.

### P2 actual late confirmation UI and network/render acceptance

- An independently declared client canary now publishes necessary findings from a background thread after
  joining a copied real world. The displayed native screen initially focuses refusal. A real screen mouse
  click on Continue preserves the world and failure evidence; closing a second prompt saves normally and
  returns to title without consent or a repeated prompt. Two fresh game screenshots were visually checked.
  The game exited 0 with unchanged source/artifact/mod hashes. Evidence: `build/compat-ui/latest.json`.
- Actual M12/M13/M15/M16 networking and anti-cheat gates passed, as did M27's fresh nonblack game frame and
  M32's save reload after a mod removal. M14 initially failed on its missing canary, then passed after that
  prerequisite was rebuilt. No skipped case was counted as passed. Logs: `build/verification/network-render-sweep/`
  and `build/m14-final-driver.log`. Native compatibility UI assertions remain separate from strict release
  acceptance: the UI canary deliberately retains two required findings.

### M34 independent activity proof keeps retention and release verdict separate

- The verifier now checks every completed server session and independently recomputes occupied ticks,
  duration and six-probe coverage before reporting a retained-server review. REVIEW_REQUIRED still exits
  nonzero and still has releaseAccepted=false; proving activity never turns retention into a clean pass.
- Release runs additionally require a fresh final STRICT compatibility report with a consistent zero
  required count and no unclassified failed initialization. Thirteen Python verifier tests pass, including
  stale/missing/continue reports, incomplete session observations and retained-but-insufficient activity.
- Rechecking the actual short trace independently proves 264.450576581 occupied seconds and 5,430 ticks,
  while preserving its retention review. The two-hour run has not yet completed.

### P2 deferred native watchdog proof and selected nested provenance

- The broader sweep exposed the native full-thread renderer behind the old Fabric append patch. The final
  ledger now records SUSPECTED while that exact native helper is unobserved; only its successfully defined,
  audited executable body plus both actual caller shapes resolves the finding. Altered handlers/callers or a
  defined altered/missing helper remain CONFIRMED. A later helper definition rechecks the deferred caller.
- The same sweep exposed two stale assumptions: the MixinExtras gate expected a pre-content-addressed path,
  and the catalog guessed bundled ownership from that old directory layout. Bundle class/config locations
  now must share one actual digest; nested display ownership uses selected parent edges, with ambiguity
  preserved. A module defining its own API is excluded as a third-party consumer even when installed alone.
- 63 focused tests passed without skips. M2b now passes under strict policy with no catalog failures; its
  unexecuted native renderer remains explicitly suspected. New M39 runs the eleven real Forge snapshot,
  alias, metadata and transaction scenarios plus an actual full diagnostic dump. All twelve pass, and the
  final watchdog finding becomes RESOLVED after the real renderer executes. Inputs remain hash-bound.
- Operational documentation now names the UI/retention controls and M39; generated M19 instances are ignored.
  The initial final M0 was red only for those missing instructions. Its 23 separate native transaction-engine
  tests passed; the merge-tool tests passed. M31 compared 400 full chunks on each side with zero biome or
  structure-start differences. Evidence: `build/verification/watchdog-provenance/` and `build/m31-final-driver.log`.
- The first full-soak attempt and remaining-gate sweep were interrupted to avoid mixing updated inputs with
  running acceptance. Neither interruption is counted as passing; the stable final sweep and soak are next.

### P0 complete inventories and P2 safe resource enumeration for presence aliases

- EMF/ETF's actual manifests contain literal control characters in description strings accepted by the
  game metadata reader. The evidence collector now reads those without rewriting archive bytes, follows
  declared JarJar paths outside conventional folders, and fails with the archive name for invalid or missing
  metadata. Fourteen Python tests pass; the complete 97-jar pack inventories 253 physical archives.
- Machine reports now include every catalog mod's resolved version, ecosystem, jar and parent identity.
  The actual mixed client reports 163 resolved mod entries, with no unresolved version expressions.
- Presence-only containers return NeoForge's native empty JarContents. They contribute no resources and
  no duplicate initialization, while a third-party all-mod resource visitor can finish. A direct JVM probe
  failed on the prior null and passes with the native empty view; real jar resources remain enumerable.
- 43 related Java tests passed without skips. The hash-bound real M9 client passed under STRICT with zero
  necessary losses and zero catalog failures; Crafting Tweaks' configuration callback now completes. A healthy
  run removes the failure-only text report, so the dedup gate accepts absence only with a nonempty all-OK
  machine inventory. Missing/empty evidence and a missing degraded report still fail.
  Evidence: `build/verification/alias-inventory/` and `build/verification/alias-resource-m9-verified.*`.

### Acceptance fixtures enforce the current contracts instead of obsolete implementation details

- The prior full 39-gate sweep (M34 separately excluded) passed 37 gates; only M19/M30 were red. M19's
  old Forge-only artifact coordinate made Fabric an invalid replacement under the new joint constraints.
  Both canary builds now explicitly provide one shared artifact contract. The actual five-run gate proves
  the preferred valid build, one initialization, both parent lifecycles, the opposite manual selection, the
  presence-rewrite negative, and strict refusal of deliberately incompatible artifact coordinates.
- M30's old FluidPlaceBlockEvent premise contradicted the existing audited table: the Forge carrier posts
  that event. Its independent canary now listens for the still-missing CreateFluidSourceEvent. Four distinct
  degraded mods and all reasons are required; same-row Mixin reasons are checked without pinning order.
  The after-world report update and clean/no-canary negative remain. The real gate passes.
- Both previously red gates now pass with their meaningful negative controls intact. Evidence:
  `build/verification/final-fixture-contracts/`. This does not count M34 as passed or remove the native
  Unlit Campfire retention review. The complete final source/artifact set will be frozen for the long run.

### Real rendered death exposed an upstream attribute API removal

- The attempted final soak and a following client both hit Corpse's DummyPlayer constructor after drowning:
  NeoForgeMod.NAMETAG_DISTANCE no longer exists. The crash windows did not exit normally and the owned test
  processes were stopped. Both runs remain failures; no two-hour acceptance is claimed.
- Upstream NeoForge PR 3333 removed that field in 26.2.0.30 in favor of Attributes.NAME_TAG_DISTANCE:
  https://github.com/neoforged/NeoForge/pull/3333 . Nonzero crouching-distance behavior differs, so the repair
  is restricted to Corpse's audited constructor whose only operation is setting distance to zero. It rewrites
  one read, only when the old field is absent and the public static vanilla replacement exists. It adds no
  registry or global alias. Existing legacy fields, changed constructors and nonzero variants are refused.
- Three offline tests execute the actual original/adapted constructor: the original throws NoSuchFieldError;
  the adapted one suppresses the name while preserving world/profile, equipment, model and position.
- A real full-pack client reproduced drowning and exited normally. A second isolated run with read-only
  probes confirmed the actual completed dummy has name-tag distance 0.0 and CorpseRenderer.submit ran.
  It produced two fresh screenshots, saved, exited 0 and retained unchanged source/artifact/mod hashes.
  Evidence: `build/verification/corpse-name-tag/`, `build/corpse-render-control/latest.json`.
- `corpse-repro.py` bounds a detected crash window to five seconds and targets only its owned process group.
  Remaining long-run validation resumes after restoring a live-player precondition in the soak controller.

### P0 complete direct-platform-call denominator

- The attribution tool now scans every declared method on both patched inputs, including callers absent
  from the conflict report and APIs outside the six event facades. Caller/callee identities include owner,
  name and descriptor; occurrence comparison also preserves opcode/interface form. Missing callers are
  unobserved. Raw symbol overlap is not control-flow equivalence or proof of event behavior.
- Fixed inputs contain 1,095 Forge-side and 1,678 Neo-side platform calls: respectively 789 and 18 raw
  missing instructions, with another 16 Forge instructions unobserved because the merged caller is absent.
  Existing runtime-restoration reporting remains separate. Reflection, handles, fields, bridges and helper
  behavior remain explicitly outside this raw census. Mod class references are labelled candidate filters.
- Eleven tests pass without failures or skips; the actual 97-root/253-recursive-archive scan is archived
  under `build/verification/full-platform-call-census/`, with unchanged input/output hashes.

### P1 restored portal calls suppress only the redundant legacy forward

- A future base with both direct native calls is accepted only when removing the precise Forge insertion
  restores the reviewed 26.2 caller fingerprint. The insertion must preserve Neo-to-Forge order, both veto
  guards and the same consumed Optional. Only that Neo dispatch suppresses the legacy event forward.
  Unknown two-call shapes keep their bytes and receive a suspected finding; independent producers still bridge.
- Twenty-eight tests pass without skips, including real runtime descriptor/access linkage, the original
  double-delivery negative, both cancellations, replacement consumption, nesting, exception cleanup and
  malformed restoration shapes. Evidence: `forbric-kernel/build/verification/portal-direct-restoration/`.
  A fresh full-game M35 run on the integrated candidate remains required.

### P2 bounded entrypoint helper member contracts

- Candidate selection follows inspectable same-jar static/private/final helper calls from entrypoints.
  Conditional references stay soft; ambiguous dispatch, missing bodies, recursion and explicit depth/node/
  instruction bounds remain unproved. This does not claim reflection or general virtual-dispatch coverage.
- A review caught a false unknown for ordinary branch/try guards. The new regression first failed, then
  passed after removing only the branch-presence finding. Actual conditional missing references remain
  uncertain; fully satisfied guarded code is solved. M19's valid-selection assertion was not weakened.
- All 74 tests across six arbitration/scanner suites pass without failures or skips. Before/after evidence:
  `forbric-kernel/build/verification/member-reference-closure/guard-after-summary.json` and adjacent archives.

## Continuation by Claude (2026-09-23)

Codex stopped before the acceptance items. The work below re-audited every batch above, fixed what the audit
confirmed, and ran acceptance on one merged candidate.

### Audit of the earlier batches

- Eight areas (P0 evidence, P1 merge, P2 Mixin, P2 arbitration, §4 decisions, P3 transfer, §5 acceptance/soak,
  regressions) were read against PLAN.md, and every reported defect was checked by two or three independent
  refuters. 54 defects were reported and 47 confirmed; 50 plan gaps were reported and 47 not refuted.
- The confirmed defects that hurt an ordinary pack: every boot turned an uninstalled optional or respelled
  dependency into a hard arbitration rule; JarJar edges accepted only the exact artifact, so realistic
  Forge-parent/NeoForge-child and Fabric JiJ layouts became UNSATISFIABLE; same-family nested duplicates were
  chosen by content digest instead of version; any UNSAT dropped every contract; a malformed JarJar range
  aborted the boot; every losing jar went onto the rescue class path; a release/installer build could silently
  omit the transfer package; Fabric's generic Container view could answer (and write) for a Forge/NeoForge block
  entity before its owner; cross-mod Mixin mismatches no longer reached the dependency window or the Mods screen;
  a client refusal at client setup became a vanilla "Initializing game" crash with exit -1.
- Each area was fixed in its own worktree branch (claude/compat-*), reviewed by an independent reviewer whose
  issues were adversarially checked, and the confirmed review issues were fixed before merging.

### P0 evidence (claude/compat-evidence)

- LostHookAttribution and MergeabilityCensus parse every "... hook lost)" form, including the six
  field-init-preserving constructor rows that were silently dropped (LivingEntity#<init> lost
  ForgeHooks#onLivingMakeBrain); an unrecognised form stops the run.
- Evidence manifests read the platform pins from the jars (26.2 / 26.2-65.0.1 / 26.2.0.88), record the jars the
  build and its bytecode tests actually read (FORBRIC_OLD), and a release capture refuses a mismatch; gate-m0
  step 0 refuses a split base. build-merged-base.sh writes merge provenance (inputs, tool sources, outputs, link
  mode); a release refuses a missing or dirty one. `gates-all.sh --release` fails on SKIP or EXPECTED_RED, and
  `evidence.py release-check` binds published jars to accepted manifests.
- The link baseline is an input of the merge-tools/installer builds; the installer link-checks `--artifacts`.
  gate-m0 now also runs transferTest, the Python evidence/soak tests, the link-check self tests and the installer
  link gate. Defined-class evidence is content-addressed (case-insensitive file systems no longer collide).

### P1 merge (claude/compat-merger)

- AdditiveMethodMerger aligns each side with vanilla first and adds a paired-hook grammar. On the real jars it
  now accepts BaseFireBlock#onPlace (the portal pilot): Neo call, then Forge's, same Optional, both vetoes kept.
  accepted=1, declined=998 with per-reason counts. The kernel proves the restored caller and stands its legacy
  forward down; a failing Forge listener keeps NeoForge's result instead of escaping onPlace.
- Fall damage is a pinned must-refuse case on the real staged bytes ("hook stages differ"). The spawner stays a
  runtime repair: the merger refuses it with its reason, and the repair stands down if a base ever carries both
  native finalize calls. The portal mute is scoped to the dispatch in progress, not the whole thread.

### P2 arbitration (claude/compat-arbitration)

- Dependency rules exist only for installed contests; respelled ids match DependencyAudit; JarJar edges accept
  any in-range build that claims the child's ids (top-level copy, other platform artifact, Fabric JiJ); newest
  version wins inside one ecosystem; UNSAT relaxes only the conflicting pins/contracts; the search is bounded by
  work, not wall-clock; proved providers beat unproved ones; breaks/conflicts/incompatible are exclusions;
  findings are filed under the mods involved or "forbric"; only another ecosystem's build of a loaded mod can be
  a rescue jar; nested inventories are no longer truncated at 1,024 archives. gate-m19 has main's real nested
  shape again plus ranged and unsatisfiable strict cases.

### P2 Mixin ledger (claude/compat-mixin-ledger)

- Foreign cross-mod mismatches feed the dependency window's Mixin section and DEGRADED rows again (SUSPECTED,
  non-blocking). Drift suspicions resolve only on their own evidence. A default-required injector is necessary
  as in native Mixin (require/defaultRequire), plugin declines are asked per target, held-back preflight rows are
  discharged by the final verdict. MixinExtras injectors are reconciled; an unaudited MixinExtras miss is
  SUSPECTED, not CONFIRMED. Mixins and injectors the kernel removes by name are in the ledger.
- The superseded-mixin proof exists twice on the branches; the merge keeps the evidence branch's version
  (resolved at ForbricClassLoader's definition point, honouring the replacement's own switch).

### §4 decisions (claude/compat-ui-decisions)

- One startup window: required findings, the folded dependency notice (asked once), and a details pane with the
  suspected findings. A client refusal leaves Minecraft.<init> through SilentInitException and exits 78. The
  "every mod finished loading" line is written only at the real end of loading. Findings with no catalogue row
  appear on the Mods screen and in load-report.txt; reports refresh during singleplayer play. Late prompts page
  four findings at a time and re-ask on rejoin. gate-m20 asserts the policy outcome instead of the dialog line;
  the Windows sweep forces strict and fails on any required loss.

### P3 transfer (claude/compat-transfer)

- The runtime jar can no longer be built without the transfer package. The owning ecosystem's provider answers
  first; Fabric's generic Container view is never a write bridge over a Forge/NeoForge block entity. Forge
  LazyOptional listeners are registered once and held weakly; legacy extraction stops at one stack; an empty
  fluid tag moves as plain fluid; paired-transaction close ordering fixed. M33 now also covers chunk unload and
  replacement without manual invalidation; gate-m39 runs the transfer engine suite and fails if it skips.

### M34 soak (claude/compat-soak)

- A saved test player that joins dead is respawned before measurement; a crash window is closed within five
  seconds; a watchdog records FAIL with a thread dump when the client thread stays inside a native world-open or
  disconnect loop; an unreachable probe fails within the timeout; a controller that cannot start stops the game.
- Retention: a 12-session control retained all 12 stopped servers. After measurement the controller cuts only
  reviewed native roots (native-retention.json: Unlit Campfire's CAMPFIRES, reproduced on native NeoForge with the
  same jar) for its own stopped servers: 9 of 12 were then collected. run/compat/HeapPaths.java reads the heap
  dump and cuts every mod-owned edge (mod classes, nested jars, lambdas, Mixin-added fields); the three left were
  UNREACHABLE (first server: EMF's cached armor render state via TRansition's transitionEntity; last two: Xaero,
  Chunky and Spark "last server" fields). A live server in a mid-session dump stays REACHABLE (negative control).
  A release now accepts residual retention only when every retained server is UNREACHABLE once mod-owned edges
  are cut and fewer than half the sessions' servers remain.

### Fabric renderer slot regression found by the soak

- Every soak on the mixed pack logged "NO renderer registered", and one crashed drawing a block in an item frame
  ("Attempted to retrieve active rendering plug-in before one was registered"). Sodium 0.9.1's NeoForge build
  declares contains_renderer in [modproperties] but ships no FRAPI renderer; since 8a9df2c (on main) that
  declaration made Indigo stand down with nobody to take the slot. The declaration is now forwarded only from a
  build that directly calls Renderer.register / RendererManager.registerRenderer. The next control logged
  "[Indigo] Registering Indigo renderer!" and the slot held IndigoRenderer. main still carries the regression.

### Merge and unit results

- All seven branches merged into codex/compatibility-contracts. Kernel test 2,009, transferTest 42, loader 115,
  Python 48: zero failures, errors or skips (before this continuation: 1,892 / 23 / 88).
- Candidate rebuilt with the merged tools from committed sources: accepted=1 declined=998, 24 known / 0 new
  dangling references, provenance written. Staged under build/claude/staged-root (a mirror of the reference
  staged tree whose merged-base is the candidate), so the unit tests and the gates read the same base.

### Preflight on the merged candidate

- `gates-all.sh -j 2 --mem-budget 6000 --skip gate-m34-soak.sh` with FORBRIC_OLD on the candidate staged root:
  all 39 other gates GREEN in 23 minutes, including M9 (97-jar strict client, zero required losses), M19
  (restored nested shape), M20 (policy outcomes), M33 (owner-first routing, chunk unload, save/reload, red
  bridge-off control) and M35 (11 cases on the merger-composed portal, exact repair-off counterexamples; the log
  shows the proved restored portal call). This preflight is not evidence-bound; the release run follows.

### Known limitations after this continuation

- Arbitration models Mixin target classes, not the members a Mixin shadows or invokes; Mixin bodies and static
  event-subscriber seeds are not scanned for member contracts. Rescue jars are limited to another ecosystem's
  build of a loaded mod, not audited class by class.
- Event/lifecycle chains are verified end to end for the repaired paths (portal, spawner, item use, entity
  callbacks, enchantment, transfer); there is no general chain validator for every event.
- The full-game effective hook census (defined-class evidence joined to the platform census) has tooling but no
  archived full-game export yet.
- Every launch hashes each mod jar for the candidate plan; large packs pay that time at boot.
- The spawner is not composed by the merger; it stays a runtime repair with a structural stand-down.
- main still carries the renderer-slot regression fixed here (8a9df2c forwards Sodium NeoForge's declaration).
