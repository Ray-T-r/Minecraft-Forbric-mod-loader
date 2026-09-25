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
- [x] P2: dependency/member/Mixin-constrained arbitration with explicit override and unsatisfiable findings,
      including the members a cross-mod Mixin needs its target to declare and what Forge-family
      @EventBusSubscriber listeners call (see "Mixin member contracts in arbitration" and "Remaining gaps closed").
- [x] P3: real Fabric/Neo item/fluid transaction coordination, including nesting and re-entry.
- [x] P3: audited Forge snapshot adapters and legacy simulate/execute views.
- [x] P3: server block-entity lookup integration, owner-ecosystem precedence, direction, invalidation (incl.
      chunk unload) and cycle guards.
- [x] P3: exact fluid units and lossless metadata; unsupported providers remain unavailable for writes.
- [x] Acceptance: native controls, mixed pack, real actions, multiplayer, save/reload and world re-entry, on the
      merged candidate: 39 gates GREEN in two evidence-bound release runs, plus the non-gate controls (see
      "Final acceptance").
- [x] Acceptance: at least two hours of sustained operation on the exact candidate artifacts: RELEASE_PASS on the
      mixed pack without JourneyMap. With JourneyMap 6.0.1 the full pack deadlocks inside that mod after 80–100
      minutes of the soak's teleport loop, so M34 on the full pack is RED; the same deadlock (same two frames) was
      reproduced on native NeoForge 26.2.0.88 with JourneyMap alone ("Remaining gaps closed").

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
  "[Indigo] Registering Indigo renderer!" and the slot held IndigoRenderer. (Merged into main with this branch.)

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
  build of a loaded mod, not audited class by class. (The Mixin and event-subscriber parts are closed below.)
- Event/lifecycle chains are verified end to end for the repaired paths (portal, spawner, item use, entity
  callbacks, enchantment, transfer); there is no general chain validator for every event. (Closed below for every
  bus-to-bus forward by M41; call-site composites keep their own gates.)
- The full-game effective hook census (defined-class evidence joined to the platform census) has tooling but no
  archived full-game export yet. (Exported below.)
- Every launch hashes each mod jar for the candidate plan; large packs pay that time at boot.
- Fabric mixins aimed at vanilla calls NeoForge's patches replaced still fail where the merged base keeps
  NeoForge's body: on the popular pack architectury's two BaseSpawner redirects and apoli-legacy's ServerPlayer
  inject are required losses, so its dedicated server stops under STRICT (see "Remaining gaps closed"). (Closed in
  "Popular pack under STRICT".)
- The spawner is not composed by the merger; it stays a runtime repair with a structural stand-down.
- The renderer-slot regression that 8a9df2c introduced on main is fixed there too since the merge (ed1211d).

### Final acceptance on the merged candidate

- Release attempt 1 (`evidence.py run --release`, all ten roles, `gates-all.sh --release -j 2 --mem-budget 6000`,
  evidence `forbric-kernel/build/verification/final-release/`): 39 of 40 gates GREEN, inputs unchanged. M34 was
  RED at 96 minutes (session 14 of the 97-jar pack): the new watchdog recorded FAIL with a thread dump after the
  client thread had not returned for 301 seconds. The dump shows a deadlock inside JourneyMap 6.0.1's own
  MapRenderer: sortRegions (a background worker) replaces the `regions` field with a new synchronized map and,
  holding the new map's lock, copies the old one (needs the old lock); loadInMemoryRegions (render thread) holds
  the old map's lock and re-reads the field, then waits for the new map's lock. Both paths are the mod's
  bytecode (javap of journeymap/client/render/map/MapRenderer); no Forbric frame is between the two locks. It is
  a timing race in the mod, not a Forbric defect, and it is recorded rather than waived.
- Non-gate controls on the same candidate, all passing: native Fabric 0.19.5 / Forge 26.2-65.0.1 / NeoForge
  26.2.0.88 against Forbric with byte-identical canaries (three MATCHED_PASS), Unlit Campfire retention
  reproduced on both arms, the late-prompt UI control (explicit Continue, close refuses and saves), and the Corpse
  render control. Log: `build/claude/controls.log` in the original checkout.
- Release attempt 2 (same command, evidence `final-release-2/`): again 39 of 40 GREEN with inputs unchanged, and
  M34 RED at 79 minutes on the identical JourneyMap deadlock (same two frames). The soak teleports every ~30
  seconds across six probes in three dimensions, so the minimap re-centres far more often than in normal play;
  the race is reproducible here within 80–100 minutes. The default M34 fixture keeps JourneyMap, so M34 on the
  full 97-jar pack stays RED until JourneyMap fixes it.
- Two-hour release soak on the same candidate with only JourneyMap removed (96 jars; evidence
  `final-soak-no-journeymap/`, run 162f7bcf): RELEASE_PASS, releaseAccepted=true, exit 0, inputs unchanged.
  7,264 active seconds and 145,773 real ticks over 19 same-JVM sessions, every probe visited 38 times and seen
  unloading/reloading in all three dimensions, final STRICT report with zero required losses. Retention: all 19
  stopped servers were held before the post-measurement cut; cutting only the reviewed Unlit Campfire root freed
  15 (980 cache entries removed); the 4 left are UNREACHABLE once mod-owned edges are cut (three through EMF's
  Mixin-added HumanoidArmorLayer.humanoidRenderState and TRansition's EntityRenderState.transitionEntity, one
  through Xaero's ServerConfigManager.server).

## Energy interop (claude/energy-interop, 2026-09-24)

- Bridged: energy stored in placed block entities, in all six directed pairs of Team Reborn Energy 5.0.0
  (`EnergyStorage.SIDED`, the Fabric energy API; Fabric API has none), NeoForge 26.2.0.88 (`Capabilities.Energy.BLOCK`,
  `EnergyHandler`) and MinecraftForge 26.2-65.0.1 (`ForgeCapabilities.ENERGY`, `IEnergyStorage`). Item energy
  (batteries in inventories, `EnergyStorage.ITEM`, `Capabilities.Energy.ITEM`) is not bridged. The same seams,
  endpoints, owner-first precedence, face/null passing, recursion guard and invalidation (replacement, capability
  invalidation, chunk unload, Forge LazyOptional) as items and fluids; no new transformer.
- Units: 1 FE = 1 E. Forge and NeoForge are int, Reborn is long: a long request is clamped to Integer.MAX_VALUE before
  anything moves, the int side reports what it moved, and the rest stays in the source. Int reads of a long amount
  saturate. Provider answers outside [0, request] are rejected before the nested scope commits.
- Transactions: Reborn <-> NeoForge through PairedTransactions (real nested scopes of both engines, finals after both
  roots). A Forge consumer gets simulate = aborted operation, execute = commit, inside any open scope. A Forge store is
  written transactionally only if it is Forge's `EnergyStorage` whose final definition carries the
  ForgeTransferShapeAudit certificate (whole-class fingerprint `311f4f17...`), or a subclass declaring none of the six
  IEnergyStorage methods; a per-thread journal snapshots the `energy` field at every depth and dirties the block entity
  once per root commit. Any other Forge store gets no write view and one FORGE_HANDLER_NOT_ROLLBACK_SAFE finding per
  class; no player choice enables it. A non-transactional write to a store while a transaction holding it is open is
  undone if that transaction aborts, as for items and fluids.
- Out-of-bounds Forge stores: Forge's `EnergyStorage.deserializeNBT` sets `energy` unclamped, so a save made before a
  config lowered the capacity loads above capacity (or below zero), and Forge's own receiveEnergy/extractEnergy then
  answers a negative amount. The view puts the field back and moves nothing, instead of throwing into the consumer's
  tick (NeoForge's `EnergyHandlerUtil.move` turned the throw into a crash report, on every restart). The energy above
  capacity is kept and extraction works normally. A lying transactional provider (NeoForge/Reborn) is still rejected
  and rolled back.
- Forge's own `EmptyEnergyStorage` (that exact class) is the owner's answer "no energy here": an empty view (0/0,
  cannot insert or extract, handed back to Forge as `EmptyEnergyStorage.INSTANCE`), no finding, and precedence stops
  there. A subclass of it is audited and refused like any other store.
- A Forge consumer whose live endpoint is invalidated or removed during receive/extract gets 0: its scope rolls the
  provider back and ENDPOINT_INVALIDATED is reported, as for Reborn and NeoForge consumers.
- Reborn is optional. Only RebornEnergyBridge/RebornEnergyAdapters name it; the boot seam detects it as a resource and
  requires and installs that half only when it is present (a missing half is a non-necessary finding). Without Reborn,
  Forge <-> NeoForge energy works and no Reborn class is loaded. The Reborn half links Reborn's API before exposing
  anything, so a failed install leaves Forge/NeoForge energy unchanged. Energy follows `-Dforbric.transferBridge` and,
  like the whole transfer component, is active only when Fabric's transfer API and NeoForge's transfer API are present.
- Build: the game side compiles against `energy-5.0.0.jar` (`-Pforbric.rebornEnergy`, default
  `forbric-kernel/run/energy-api/energy-5.0.0.jar` beside the staged tree, else in this checkout's own
  `forbric-kernel/run/energy-api/`). `verifyRebornEnergy` runs before every game-side compile and transfer-test run and
  fails naming the file when it is absent or its SHA-256 is not `889afc438d3e4add5cfdac76517da7987a2c495e4731690a56f2c5dee775db59`
  (compileRuntimeJava's up-to-date check sees only the API, so it never caught a different same-API file). It is not
  bundled (the runtime jar check refuses `team/reborn/` entries).
- Tests: kernel `test` 2,018 (was 2,009) and `transferTest` 61 (was 42), zero failures, errors or skips; the 19 energy
  engine tests use the real Fabric/NeoForge engines, Reborn's SimpleEnergyStorage, NeoForge's SimpleEnergyHandler and
  Forge's certified EnergyStorage, including a Reborn-free loader that records any Reborn class request. Mutation
  proofs, one targeted test at a time: `forbric-kernel/build/verification/energy-mutations/review/RESULTS.txt` (12
  transfer, 7 unit, 3 whole-gate). The first commit's claim that every new test was red under a mutation was not
  shown for five of them; `energy-mutations/RESULTS.txt` records the correction.
- Game: `gate-m40-energy.sh` GREEN (prepare, reload, noreborn, red bridge-off), now also covering the cached
  NeoForge/Forge views of a replaced Reborn cell, a Fabric addon's explicit Reborn provider on a NeoForge block, and a
  Forge battery loaded at 1,500/1,000 E before and after a restart and without Reborn. Evidence
  `forbric-kernel/build/verification/m40-energy/`, driver `forbric-kernel/build/m40-driver.log`; M33 (energy-silent
  item/fluid pack) and M39 (61/61 engine tests, 13 storage scenarios and the native diagnostic proof) GREEN on the same
  code, drivers `forbric-kernel/build/m33-driver.log` and `forbric-kernel/build/m39-driver.log`.
- Limits: the Fabric side is Team Reborn Energy only; other Fabric energy APIs are not bridged. Directional abilities
  (canReceive/supportsInsertion) are passed on where the store has them; NeoForge's EnergyHandler has none, so its
  stores answer NeoForge's own rule (capacity > 0). No mixed real-mod energy pack was run; the gates use fixture mods.
  The Reborn jar is not fetched by any script (it goes in forbric-kernel/run/energy-api/ or -Pforbric.rebornEnergy).
- The item and fluid Forge facades (`ForgeLegacyFacades`) now treat an endpoint invalidated mid-operation like the
  energy facade does (nothing moved, ENDPOINT_INVALIDATED, rollback required); M39 proves it on a real carrier
  (15/15) and is RED without the change.

### Energy interop regression sweep and evidence location

- On the energy branch, `gates-all.sh -j 2 --mem-budget 6000 --skip gate-m34-soak.sh` (candidate staged root): all
  40 other gates GREEN, including the new M40 energy gate. The soak was not rerun for this change.
- The generated evidence of the 2026-09-23/24 runs (verification reports, gate logs, soak evidence without heap
  dumps, native/retention/UI/Corpse controls) is archived outside git under `build/claude/evidence-archive/` of the
  original checkout, keeping the `forbric-kernel/build/...` layout the sections above cite. The candidate staged
  root used for acceptance is `build/claude/staged-root/`. A later release soak in another checkout must re-run
  `run/compat/retention-control.py` so `native-retention.json`'s evidence exists there.

## Mixin member contracts in arbitration (2026-09-24)

- Choosing between builds of a mod now also checks what another mod's Mixin needs the target class itself to
  declare: @Shadow fields/methods (shadow prefix and aliases, static modifier), @Overwrite, @Accessor and @Invoker
  (Mixin's get/is/set and call/invoke inflection, factory invokers as `<init>`), and the `method` selectors of Mixin
  and MixinExtras injectors. Native Mixin 0.8.7 (`MixinPreProcessorStandard`) rejects the whole Mixin when one of the
  first four is missing, so they are required whenever the target itself is (required config, no plugin, no
  conditional annotation); an injector is required only when its `require`, or the config's
  `injectors.defaultRequire`, asks for a match. A member another declared Mixin adds is unproved, not missing.
  Wildcard, pattern and dynamic selectors and targets the mod ships itself are not modelled.
- What a Mixin's own code calls or reads in its dependencies is recorded as a non-required contract: reported as a
  suspicion when the chosen build lacks it, never a reason to switch builds, because that code runs only when its
  target does.
- Tests: `CandidateContractScannerTest` 34 (was 22), including the whole-instance selection path; 9 of the new
  tests fail with the member rules removed. Kernel `test` 2,030, zero failures, errors or skips.
- Real packs (local probe, not committed): client-merged-pack (97 jars), client-neo-pack (63), client-popular (37)
  and client-kernel (38), client and server side: 380 member rules and 1,092 Mixin-body rules, every one met by an
  installed build, no new finding. Scan time on the 97-jar pack about 0.1–0.2 s more (623→748 ms warm).
- `gates-all.sh -j 2 --mem-budget 6000 --skip gate-m34-soak.sh` (candidate staged root) on this change: 39 of 40
  other gates GREEN; M31 RED on "every dungeon spawns vanilla's mob" (2 chunks). Not caused by this change (M31 has
  zero mods, so arbitration never runs) but by the assertion: the two chunks are edge chunks where a mineshaft
  corridor's cave spider spawner stood at -168,32,-52 in vanilla (three vanilla runs) and at -168,32,-46 in Forbric
  (two runs; a third, on the reverted code, matched vanilla). Vanilla's `MineShaftCorridor` keeps a mutable
  `hasPlacedSpider` and places the spawner from whichever chunk first draws a spot inside itself, so the position
  follows the worker pool; the mob does not. `world-parity.py` now compares spawner mobs only where both worlds
  have a spawner at the same position (what caught the nextInt(400) draw, where all six dungeons disagreed at the
  same spots) and reports differing positions as evidence; `test_world_parity.py` (4 tests) runs in gate-m0.

## Remaining gaps closed (2026-09-24)

- Event-subscriber member contracts: arbitration now also follows the static @SubscribeEvent methods of a
  Forge-family mod's @EventBusSubscriber classes. A listener for an FML lifecycle event of this side (common,
  client or server setup, load complete, IMC) runs on every launch and gets entrypoint rules; any other may never
  fire, so what it calls is a soft contract and what the scan could not follow in it is not reported. On the
  popular pack this adds 68 (client) and 21 (server) member rules, all met. Commit c66b5ab.
- The 97-mod pack's load-report.txt carried 850 "entrypoint member closure remains unproved" notes: soft rules
  that name no member and steer nothing. They stay in the arbitration count and are no longer player findings;
  hard ones still are (same commit).
- Full-game hook census: exported from an M9 client session (97 jars, world entered and left, GREEN) with
  -Dforbric.definedClassEvidence, 25,566 defined classes. Conflict rows 1,004 (710 without a modelled direct hook);
  conflict-row hooks VIA_DEFINED_HELPER 8, VIA_KERNEL_BRIDGE 32, OBSERVED_WITHOUT_HOOK 297, UNOBSERVED 6. The
  export found a tool gap: forwards written as method references (ForgeEventFactory::onPreClientTick) were
  counted as residual loss; EffectiveHookEvidence now reads lambda implementation handles (3d167fc, 4 rows moved).
  Evidence and input hashes: `forbric-kernel/build/verification/full-game-hook-census/SUMMARY.md`.
- General event-chain validator: `-Dforbric.eventChainAudit` wraps NeoForge's dispatch loop and MinecraftForge's
  post/fire and checks every post the kernel makes on one bus inside the other's dispatch: one forward per
  NeoForge listener (a bridge installed twice, or a one-to-one forward that sometimes posts twice, is a
  violation; a listener that always fans out is not), the inner cancel carried back, no failure inside. Posts by
  mod or game code inside a dispatch are incidental and not judged. Gate M41: the 97-mod client forwards 27
  required bridges exactly once with 0 violations; a MinecraftForge veto on a NeoForge entity join is carried back
  and keeps the entity out; with -Dforbric.unifiedEvents=off there is no forward and no veto. Commit 4a55248.
  Not covered: call-site composites (portal, spawner, loot, fuel, tooltips) and result fields other than cancel.
- JourneyMap on native NeoForge: official NeoForge 26.2.0.88 client (installer SHA-1 3b11639b…, FancyModLoader
  11.0.16), JourneyMap 6.0.1 and a driver mod teleporting every 5 s across the soak's six probes: the JVM reported
  a Java-level deadlock after ~36 minutes (teleport 426) between MapRenderer.sortRegions:235 and
  loadInMemoryRegions:199 on the two SynchronizedSortedMap instances, the same frames as Forbric's M34 dumps with
  the threads' roles swapped. The full-pack M34 RED is JourneyMap's own bug. Evidence:
  `forbric-kernel/build/journeymap-native/evidence/RESULT.md`.

- Windows acceptance (the player's Windows 11 machine, push-and-run, 2026-09-24). The machine's only Forbric
  version holds the player's world, so the run used an isolated copy (`C:\ForbricAccept\mc`: vanilla files copied
  read-only from .minecraft, the current installer run ON Windows with the candidate artifacts, removed
  afterwards; the player's version was not touched). A profile generated on the Mac is not valid there: the
  installer writes the host's path separator and native libraries.
  - 97-mod pack: server world created, client joined and drew frames (DREW), region readable, STRICT compatibility
    report with 0 required losses on both sides. The one failing log check, "FML construct posted (Forge)",
    does not apply: every Forge-labelled jar of this pack is a universal jar that loads as NeoForge (0
    traditional-Forge mod buses, the same on the Mac).
  - The first run found a first-launch crash the long-lived Mac pack could never show: LambDynamicLights asks its
    Fabric presence identity (through yumi) for its default config, and presence identities had no jar. Fixed in
    3cc490b (presence identities read their files from the jar that loaded the mod; the seeded NeoForge
    LoadingModList's ModFile contents open the jar on first read); reproduced and verified fixed on the Mac with
    the config removed.
  - Popular pack (37 jars): the dedicated server stops under STRICT on Windows and the Mac alike. One of its
    required losses was false (fabric-resource-conditions' superseded skipData injector, resolved only in one
    class-definition order; fixed in d6806a3). Three remain and are real: architectury's @Redirects on
    Mob.checkSpawnRules/checkSpawnObstruction in BaseSpawner (NeoForge replaced both calls with
    EventHooks.checkSpawnPositionSpawner) and apoli-legacy's preventAvianSleep in ServerPlayer. They need the
    vanilla call shape restored inside NeoForge's version and are left open. (Closed in "Popular pack under STRICT".)
  Evidence: `forbric-kernel/build/compat/win-accept-97c/`, `win-accept-popular/`, `win-accept-97b/` (the crash).
- Regression sweep on all of the above (HEAD d6806a3, candidate staged root): `gates-all.sh -j 2 --mem-budget 6000
  --skip gate-m34-soak.sh`, all 41 other gates GREEN, including the new M41 and M31 with its spawner comparison.
  Kernel test 2049, zero failures or skips.

## Popular pack under STRICT (2026-09-24)

The popular pack's (37 jars) dedicated server stopped under STRICT on three required Mixin losses. Fixing them
exposed four more walls behind them; all seven are closed and the server now starts, runs and stops cleanly with
0 confirmed required losses (Mac, `run/server-popular` mods, candidate staged root).

- apoli-legacy `preventAvianSleep` (ServerPlayer.startSleepInBed → NeoForge's lambda): the renamed-body retarget
  (MixinRetarget R3) saw two candidates because the name also resolved Player.startSleepInBed, which ServerPlayer
  overrides. Mixin injects into the target's own method, so an own match now wins, and a lambda of the other
  static-ness is never a candidate. The same rule now also moves fabric-api's sleep redirect, so M37's negative
  control switches both repairs off (-Dforbric.mixinRetarget=off). Commits 696ea11, 88dff5a.
- architectury `MixinBaseSpawner` and `MixinNaturalSpawner` (@Redirect on Mob.checkSpawnRules /
  checkSpawnObstruction): NeoForge replaced that vanilla pair with one EventHooks call in BaseSpawner.serverTick,
  NaturalSpawner (natural and chunk-generation spawns) and SpawnUtil — the only three classes where vanilla's calls
  are missing from the merged base. SpawnPositionCallsInjector inlines the hook: the same PositionCheck event
  (KernelSpawnPosition), its non-DEFAULT answer as is, and on DEFAULT the two vanilla calls in the caller. The
  unit test checks every vanilla method that makes the calls has them back, and that architectury's mixin fits
  the rewritten classes and not the merged ones. Commit 543d551.
- Corail Tombstone stopped every datapack load: the merged LootPool has both families' fields, the builder uses
  NeoForge's constructor (MinecraftForge's `forge_condition` left null) and the codec is MinecraftForge's, so
  encoding a mod-built pool threw. Each constructor now also fills the other family's fields. Commit c261a5c.
- architectury `onBreak` (vanilla-order locals capture) and apoli-legacy `modifyEffectiveTool` (@ModifyVariable
  ordinal 1) in ServerPlayerGameMode.destroyBlock: NeoForge's body keeps its BreakBlockEvent in a local and
  replaces hasCorrectToolForDrops with canHarvestBlock, stored before the removal. FabricBlockBreakMixinAdapter
  wraps onBreak to capture the merged frame and moves the modifier to ordinal 0, only when the merged body proves
  the values (read from level.getBlockState/getBlockEntity of the position; stored straight from canHarvestBlock;
  vanilla's call gone). Vanilla's own body is left alone; both guards are mutation-checked. Apoli's
  `actionOnBlockBreak` reads the two booleans by @Local ordinal and gets them swapped on the merged body; it only
  ANDs them, so it is left alone. Commit 459a5c0.
- NeoForge's own `neoforge.mixins.json` (accessors on BlockEntityType.validBlocks and
  MappedRegistry.registrationInfos) was never registered: runtime jars do not go through mod discovery. NeoForge
  itself then threw ClassCastException in BlockEntityTypeAddBlocksEvent for every mod using it (tofucraft), and
  its biome/structure modifier re-sync would have too. The runtime jars' declared configs now join the
  Forge-family list. Commit 6b957ba.
- Not Forbric: tofucraft's loot tables name `tofucraft:soymilk_cocoa` and similar, but the mod registers
  `soymilk_cocoa_bottle`; those two tables fail to parse on any loader.
- The popular-pack server after all of the above: Done, 120 s of ticking, clean stop, 0 confirmed required losses;
  the ClassCastException lines went from 4 to 0. Kernel test 2066, zero failures or skips.
- Regression sweeps (candidate staged root, `gates-all.sh -j 2 --mem-budget 6000 --skip gate-m34-soak.sh`): after
  the first four fixes 40/41 GREEN, M37 RED only because its negative control still expected the sleep case to
  fail with the entity anchors off (fixed in 88dff5a, M37 then GREEN alone); after 6b957ba all 41 GREEN.
- Adversarial review of the six commits (6 reviewers, 2 skeptics each, read-only): 14 findings, 3 survived, all
  stale descriptions (M37's README, the mixin service's note on runtime configs), corrected in 62feca7. The census
  behind the R3 finding: across 2,361 (popular) and 2,901 (97-pack) mixins, only fabric-entity-events'
  ServerPlayerMixin (3 handlers) and apoli's preventAvianSleep get a new plan, all into
  `lambda$startSleepInBed$0`, the lambda the native method actually calls.

### Open gaps the review found (older than this round, not yet fixed)

- NeoForge's coremods never run on the merged base (`net.neoforged.neoforge-coremods`, ClassProcessorProvider)
  (closed 2026-09-25, see "NeoForge's coremods on the merged base"):
  - FlowerPotBlock: all three constructors store null in `potted` and NeoForge rewrites every read to
    `getPotted()`; without it `isEmpty()` is never true and `useWithoutItem`/`getCloneItemStack` build an ItemStack
    from null. The merged `useItemOn` is MinecraftForge's body, reading a `fullPots` map nothing fills and casting
    its default (a Holder) to Supplier. Found in bytecode, not yet reproduced in a running game.
  - Biome `climateSettings`/`specialEffects` and Structure `settings` are read directly, so NeoForge biome and
    structure modifiers that change them have no effect.
  - The `finalize_spawn_targets` redirects (Mob.finalizeSpawn → EventHooks.finalizeMobSpawn in 26 classes) are
    missing, so FinalizeSpawn is posted only from spawners; DeadEventAudit still reports it as repaired.
- fabric-api's PlayerBlockBreakEvents.AFTER (`onBlockBroken`, anchored on Block.destroy) finds nothing in the merged
  destroyBlock, where NeoForge moved that call into its own removeBlock; reported as SUSPECTED, no pack mod
  affected. (Closed 2026-09-25, see "Fabric's AFTER break event and MinecraftForge loot pool conditions".)
- A MinecraftForge `LootPool.Builder.when(ICondition)` is dropped by the merged builder, and a pool-level
  `forge:condition` is decoded but never evaluated; no pack mod uses either. (Closed 2026-09-25, same section.)

## NeoForge's coremods on the merged base (2026-09-25)

NeoForge ships its coremods in a separate jar (`net.neoforged.neoforge-coremods`, a `ClassProcessorProvider`) and
MinecraftForge ships the matching `FieldToMethodTransformer`/`MethodRedirector`; the kernel loaded neither, and the
merged bodies are written for them. A probe mod on a bare server measured the result before any change: every flower
pot threw an NPE on pick-block, planting, taking a plant out and clicking with another item (empty pots too);
NeoForge's pot table had 0 entries; `LiquidBlock.getFluid()` threw for vanilla water and lava; `EntityType.spawn` and
`/summon` posted 0 NeoForge and 0 MinecraftForge finalization events; NeoForge biome/structure modifiers ran but
nothing read their result. (Buckets were fine: the static reading that predicted an ICCE there was wrong.)

- NativeCoremodParity, after Mixin (where NeoForge runs its processors; installed in KernelMixinBootstrap, also when
  Mixin does not start): NeoForge's field-to-getter rewrites for `FlowerPotBlock.potted`, `Biome.climateSettings`/
  `specialEffects` and `Structure.settings` (owner+name+desc, getter-descriptor methods and constructors excluded, no
  private check since the ACCESS phase may widen them), and the finalize redirect in NeoForge's 26 classes (pinned by
  a test against NeoForge's and MinecraftForge's own target lists) to KernelFinalizeSpawn: NeoForge's event, then
  MinecraftForge's with what NeoForge left, one `finalizeSpawn` with what MinecraftForge left; either cancel skips
  it; a NeoForge veto survives a MinecraftForge listener clearing the shared flag. TrialSpawner's NeoForge spawner
  hook gets a two-family twin that posts MinecraftForge's event only where vanilla initializes.
- Before Mixin: FlowerPotRepairInjector (vanilla constructor stores its plant and fills `POTTED_BY_CONTENT`; the
  MinecraftForge lookup in `useItemOn` asks KernelFlowerPots — explicit `addPlant` entries, then NeoForge's table;
  `addPlant` records again) and KernelFlowerPots.rebuildTable at both registration-window closes (NeoForge's bake
  callback never runs on the merged block registry; 80 pots on the popular pack); LiquidBlockFluidInjector (field
  first, then supplier; a merge repair, not a coremod); BiomeInfoRebaseInjector (NeoForge's pass starts from the
  biome's current climate/effects, so a fabric-biome-api weather change survives the view) and BiomeLateWriteInjector
  (a climate/effects replaced after the pass wins, as on Fabric — lithostitched-fabric's replace_* do that).
- Switches: `-Dforbric.coremodParity=off` (everything above), and per part `forbric.flowerPotRepair`,
  `biomeModifiedView`, `structureModifiedView`, `finalizeSpawnRedirect`, `liquidBlockFluid`; `forbric.biomeRebase`
  is a diagnostic control for M42 only.
- Gate M42 (canary/coremod-parity, 22 cases): vanilla/NeoForge/addPlant pots and pick-block, NeoForge's getFullPot,
  the liquid getter, a counting probe mob through EntityType.spawn and /summon (one finalization with MinecraftForge's
  data), both cancels, the veto, a NeoForge biome modifier on the server and through NETWORK_CODEC, a NeoForge
  structure modifier, a Fabric weather change and a post-pass climate replacement. Positive 22/22 under STRICT; with
  the master switch off every repaired case fails and only the two raw Fabric climates hold; with the rebase off only
  those two fail. KernelFinalizeSpawnTest runs the helper against NeoForge's real spawner hook (7 cases; two
  mutations caught). Kernel test 2085.
- Adversarial review (4 reviewers, 2 skeptics each): 16 findings, 6 survived — no exact-once proof and the gate
  calling the trial spawner covered (fixed: fixture test + counting mob), a chicken-jockey flake in M42 (fixed),
  post-pass biome writers now dropped (fixed: BiomeLateWriteInjector), and the master switch not covering the liquid
  repair (fixed). Commits cdd7374, 750edc8, aaf840d, ac276c6.
- Known limits: a biome value mutated in place after the pass is not seen (the view holds a copy); MinecraftForge's
  own FieldToMethod targets `MobEffectInstance.effect` and `BucketItem.content` are not ported (no pack effect
  measured; buckets work); guest mixins aimed at `ForgeEventFactory.onFinalizeSpawn` (a pre-Mixin anchor that only
  exists natively on MinecraftForge) find nothing; the trial spawner and natural/structure spawns are proven by tests,
  not in a running world; no rendered client was run for this round.
- Regression sweep (`gates-all.sh -j 2 --mem-budget 6000 --skip gate-m34-soak.sh`, candidate staged root, HEAD
  ac276c6): 31 GREEN in the sweep; the other 11 went red while another project's batch jobs held the machine at a
  load average near 30 on 10 cores (servers timed out mid-boot, no error in their logs) and are GREEN rerun one at a
  time (M3 on a second run once the load fell). An earlier sweep's reds were a stopped sweep's orphaned M24 server
  still holding port 25710.

## Fabric's AFTER break event and MinecraftForge loot pool conditions (2026-09-25)

The two gaps left open by the coremod round's review. A probe mod on a bare server measured both before any change:
PlayerBlockBreakEvents.AFTER never fired (survival, a chest, creative; BEFORE and CANCELED were fine), a
`forge:condition: forge:false` pool still gave its item, and a pool built with `when(FalseCondition)` lost its
condition.

- AFTER (ae7356e): fabric-api's `onBlockBroken` anchors on vanilla's `Block.destroy` in `destroyBlock`; NeoForge's
  body moved that call into its own `removeBlock(pos, state, canHarvest, tool)`, which calls it exactly when it
  removed the block and returns that answer. FabricBlockBreakMixinAdapter wraps fabric-api's own handler in a
  MixinExtras `@ModifyExpressionValue` on both `removeBlock` calls (creative and survival path, `@Local` block entity
  and adjusted state by name), which runs the handler only when the result is true — so AFTER fires exactly once per
  removed block, with the vanilla arguments, and fabric-api's handler stays the only thing that fires it. It applies
  only when the merged body proves this (no `Block.destroy` of its own, the two calls with the named locals in scope,
  the one `Block.destroy` inside, a handler that never reads its callback). Two orderings are the merged body's, not
  the wrapper's: AFTER runs after `Block.destroy` (multiblocks such as apexcore/fantasyfurniture have cleared their
  partner blocks by then), and the tool and harvest decision are fixed before the removal.
- Loot conditions (7bff8a0): ForgeLootPoolConditionsInjector makes `LootPool.Builder.build()` also store
  `Optional.ofNullable(forge_condition)` (as MinecraftForge's builder; only the encoder reads it, as natively), and
  gives NeoForge's `CommonHooks.lootPoolsCodec` MinecraftForge's `LootPool.CONDITIONAL_CODEC` as its element codec,
  inside NeoForge's own conditional wrapper — a false pool becomes an empty pool in place, as natively; neoforge and
  fabric conditions are judged first and unchanged. Like the kernel's other condition keys, `forge:condition` is
  judged for every mod's data. `-Dforbric.forgePoolConditions=off` is read at launch.
- Gate M43 (canary/break-and-loot, 15 cases, the unmodified fabric-events-interaction-v0 modules): breaking in
  survival, a chest, creative, inside an AFTER listener, breaking air (no AFTER), a Fabric veto, a NeoForge cancel;
  loot pools with forge:condition false/true, neoforge:conditions never, both true, and a code-built pool kept,
  encoded and read back empty by MinecraftForge's own codec. Positive 15/15 under STRICT; with both repairs off
  exactly the 8 repaired cases fail and the controls hold. (NeoForge 26.2 names its constant conditions
  `neoforge:always`/`neoforge:never`; `neoforge:true`/`false` are not NeoForge's.)
- Adversarial review (3 reviewers, 2 skeptics each): 11 findings, 4 survived, all low — no failed-removal case (M43
  now breaks air; the unit test pins the wrapper's branch order), stale javadocs, an orphaned doc comment, and a
  unit assertion on a fresh parse instead of the adapted mixin; fixed in f77e5b6.
- Gate infrastructure (52571e7): `kill_tree` killed only the recorded pid and its direct children. M24 records a
  subshell with the java server two or three levels down, so a timed-out M24 left its server re-parented to launchd
  holding port 25710; two sweeps in a row went red on "Address already in use" in every later gate that wanted it.
  It now stops the root and kills the whole tree depth first.
- Still open: fabric-api's `ItemEvents.USE_ON` (the merged `ItemStack.useOn` is MinecraftForge's body, which reaches
  `Item.useOn` only through `ForgeHooks.onPlaceItemIntoWorld` or a lambda) and `PlayerPickItemEvents.BLOCK` (pick-block
  calls NeoForge's four-argument `getCloneItemStack`, the Fabric wrap names vanilla's three-argument one) never fire;
  no mod in either pack uses them.
- Regression sweep (`gates-all.sh -j 2 --mem-budget 6000 --skip gate-m34-soak.sh`, candidate staged root, HEAD
  f77e5b6): all 43 other gates GREEN in one run, including M42 and M43; no orphaned server afterwards. Kernel test 2091.
