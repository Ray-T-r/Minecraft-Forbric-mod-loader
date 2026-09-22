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
