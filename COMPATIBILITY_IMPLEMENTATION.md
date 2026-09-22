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
