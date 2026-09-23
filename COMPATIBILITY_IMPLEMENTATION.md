# Compatibility implementation ledger

Branch: `codex/compatibility-contracts`, based on `b890449`.

This is the implementation/evidence ledger for the approved P0–P3 plan, not release documentation.
Claude memory is read-only. Each implementation batch is tested before being committed as
`Jerry <rt.ge.jerry@gmail.com>`. A passing unit test is not a claim of game-level compatibility.

## Required work

- [x] P0: reviewed, tracked link baseline shared by dev, integration gates and installer; missing inputs fail.
- [ ] P0: evidence binds source revision/content, tool/input/output hashes, versions and mod manifest.
- [ ] P0: symmetric, owner-qualified hook attribution; distinguish raw loss, repaired, residual and unobserved.
- [ ] P1: conservative three-way composition with counterexamples and explicit rejection reasons.
- [ ] P1: portal return-value/cancellation composition; no duplicate legacy bridge.
- [ ] P1: spawner input/data fidelity and exactly one finalization; reject unsafe fall-hook composition.
- [ ] P2: structured suspected/confirmed/resolved findings and final Mixin outcome reconciliation.
- [ ] P2: confirmed necessary failures require an explicit client decision; headless/release strict.
- [ ] P2: safely present late failures; targeted access replay after descriptor repair.
- [ ] P2: dependency/member/Mixin-constrained arbitration with explicit override and unsatisfiable findings.
- [ ] P3: real Fabric/Neo item/fluid transaction coordination, including nesting and re-entry.
- [ ] P3: audited Forge snapshot adapters and legacy simulate/execute views.
- [ ] P3: server block-entity lookup integration, native precedence, direction, invalidation and cycle guards.
- [ ] P3: exact fluid units and lossless metadata; unsupported providers remain unavailable for writes.
- [ ] Acceptance: native controls, mixed pack, real actions, multiplayer, save/reload and world re-entry.
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
