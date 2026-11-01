# Stage 4 C3, lane "regress-client": fabric-screen-api's screen draw events beside spruceui and JourneyMap

Branch `s4-c3`. Lane commit 9369f153 on e3259814; review fixes df51265d, 74146c9e, 4a1b2202, a3791619. This lane
deleted nothing. It fixed the one real defect the C1 boots found on the genuine base: gate-m9's "Fabric's screen draw
events reach an open screen" went from `beforeExtract 48, afterExtract 48` on the merged base to `0, 0` on NeoForge's
base with the 97-jar client pack (c1 walls, m9). The fix is Fabric-on-NeoForge adaptation code with two switches,
accepted as an adaptation by DECISIONS D36. This file was written by the Stage 4 record lane from the lane's commit
messages, checks rows and logs, which otherwise lived only in a session scratchpad.

Names: G = genuine stage (`run/neoforge-patched/patched-mc-neoforge-26.2.jar` + `run/neoforge-runtime/neoforge-runtime.jar`,
NeoForge 26.2.0.93). Heavy commands went through `.stage/heavy.sh`; `MC_DIR` was never set; gate-m9 ports 46203-46207.

## The defect: two walls, one behind the other

fabric-screen-api's `GuiMixin.onExtractGui` is a `@WrapOperation` of `Screen.extractRenderStateWithTooltipAndSubtitles` in
`Gui.extractRenderState`. On NeoForge's base that call is made inside `ClientHooks.extractScreen` →
`extractScreenInternal`. Natively each mod runs in its own loader: Fabric wraps the screen draw, spruceui (a NeoForge
mixin nested in LambDynamicLights) wraps `ClientHooks.extractScreen` in `Gui`, and JourneyMap (NeoForge) HEAD-injects
`ClientHooks.extractScreen`.

1. `NativeCallInlining` planned the inline of `ClientHooks.extractScreen` while only the Fabric configs read so far were
   known, then withdrew it when `Gui` was defined because spruceui's mixin anchors on that very call. The withdrawn
   inline also kept the operation seam from being tried, so the wrap was lost:
   `[Forbric/Mixin] …GuiMixin.onExtractGui: left …extractRenderStateWithTooltipAndSubtitles… inside
   net.neoforged.neoforge.client.ClientHooks.extractScreen → …extractScreenInternal — mixin
   dev.lambdaurora.spruceui.mixin.neoforge.GuiMixin anchors an injector on extractScreen(…), which the inline would remove`,
   then `injection warning: mod=fabric-screen-api-v1 … handler=onExtractGui … did not attach`.
   Fix: an inline's conflicts are every mixin of every config registered on this side that Mixin applies
   (`ForeignMixinTargets.registeredMixins`); the inline is declined while planned and `MixinOperationSeamTransport` carries
   the wrap inside `ClientHooks`. Switch `-Dforbric.nativeCallInlining.registeredMixins=off`.
2. The seam then declined at run time, because JourneyMap's HEAD `@Inject` changed the instrumented gateway: "the final
   body of …ClientHooks.extractScreen… is not the body Forbric instrumented (changed by mixin
   journeymap.common.mixin.client.ClientHooksMixin)". Fix: the witness also accepts a final body that only adds another
   mixin's handler call (every emitted instruction still there in order, the instrumented calls made as often, no
   instrumented local or parameter written by the added code, the added same-class calls all merged by another mixin).
   Switch `-Dforbric.operationSeams.additions=off`.

## Results

| item | verdict | before on G | after on G | red controls | commit |
|---|---|---|---|---|---|
| fabric-screen-api `GuiMixin.onExtractGui` with spruceui + JourneyMap | REGRESSION-FIX (D36) | C1 m9 tip: `beforeExtract 0, afterExtract 0`, onExtractGui injection warning | gate-m9 on a fresh clone: PASS, `beforeExtract 43, afterExtract 43`, 149 PASS / 21 FAIL, no onExtractGui warning, no report row for GuiMixin, spruceui or journeymap | `-Dforbric.operationSeams.additions=off`: FAIL 0/0, warning and a CONFIRMED mixin-seam row naming journeymap's ClientHooksMixin back (148/22); `-Dforbric.nativeCallInlining.registeredMixins=off`: FAIL 0/0, "left … inside ClientHooks.extractScreen" + "did not attach" (148/22) | 9369f153 |
| other genuine-only m9 client injection warnings (c1 walls Q8) | NO NEW DEFECT | hookDamage, furnace `getCraftingRemainder`, onExtractGui, plus lithium ×3, malilib, sodium (the last five also on the merged base) | hookDamage gone (b2b3e240), furnace measured not lost (ee1aed96), onExtractGui fixed; left: furnace + lithium ×3 + malilib + sodium | — | — |
| plan risk R13 (NeoForge's screen render events now posted by G; Fabric's wrap must bind; no double posting) | RESOLVED | — | one Fabric before/after per frame (43/43); 0 kernel posters of `ScreenEvent.Render` (`grep -a 'ScreenEvent$Render\|ScreenEvent.Render'` over src/main + src/runtime); the exported helper still posts NeoForge's `ScreenEvent.Render.Pre/Post` around `forbric$operation$…` | — | 9369f153 |

The 21 remaining m9 FAILs are C1's classified premise and vacuous lines (EventMux ×5, carriers ×2, alias parity, loot
index, MinecraftForge hears ×9, client commands, pack filter, MinecraftForge lang); the screen-draw line is the only
check that changed between the fix run and its controls.

## Evidence excerpts

gate-m9 outputs (lane `gates/`, fresh APFS clones, G):

```
m9-v3          [kernel] PASS Fabric's screen draw events reach an open screen (2)
               [Forbric/ClientSmoke] … beforeExtract 43, afterExtract 43   (2 lines)
m9-v3-addoff   [kernel] FAIL Fabric's screen draw events reach an open screen (want>=1 got 0)
               … beforeExtract 0, afterExtract 0
m9-v3-regoff   [kernel] FAIL Fabric's screen draw events reach an open screen (want>=1 got 0)
               … beforeExtract 0, afterExtract 0
               [Render thread/WARN]: [Forbric/Mixin] net.fabricmc.fabric.mixin.screen.GuiMixin.onExtractGui: left
               Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(…)V inside
               net.neoforged.neoforge.client.ClientHooks.extractScreen → …extractScreenInternal — mixin dev.lambdauro…
```

`-Dmixin.debug.export=true` run (`gui-export.javap`, `clienthooks-export.javap`): `Gui.extractRenderState` calls
`wrapOperation$eki000$spruceui$onRenderScreen` and `wrapOperation$bil000$fabric-screen-api-v1$onExtractGui$forbricoperation$…$invoke$scope`
around `ClientHooks.extractScreen`; `ClientHooks.extractScreen` starts with `handler$djb000$journeymap$preLayerScreenEvent`
and forwards the top screen through `forbric$selected$8b8b3cfe…`. All three mods' mixins attach; each runs where its own
loader runs it.

## Review fixes

| finding | verdict | evidence | commit |
|---|---|---|---|
| A-F1 / B-F1 NativeCallInliningRegisteredMixinsStagedTest's 4 cases missing from the 3 CI skip baselines | FIXED | 0 rows before; 12 rows added in sorted position, later 6 per file with the two new cases | df51265d (+ 4a1b2202, a3791619) |
| A-F3 the additions' attribution read the whole final body | FIXED | `additions` now returns `Additions(refused, mixins)` from the added instructions only: every added same-class call/handle must be `@MixinMerged` by a non-source mixin, at least one. Red with the old rule ("expected <no other mixin's handler is in what was added> but was <null>"); JourneyMap's real HEAD callback still accepted | 74146c9e |
| A-F2 the registered-mixin index counted mixins Mixin never applies | FIXED | served JSON (`servedConfig`) less `suppressedMixinsFor`, plus a liveness predicate at every conflict read (`registeredMixinApplies`); red tests; server default/off 81 inline/seam lines identical to before; gate-m9 `m9-fix2` (port 46203) 149/21, PASS/FAIL set and 82 inline/seam lines identical to m9-v3, screen draw PASS 38/38 | 4a1b2202 |
| A-F4 wall 1's loss had no red unit test | FIXED | staged case `decidedOnTheMixinsPlannedSoFarTheSeamIsNotTriedAndTheWithdrawnInlineLosesTheWrap`: with the switch off the inline is planned, the inlined class view keeps `MixinOperationSeamTransport.adapt` at 0, the transform leaves 0 screen draws; without the switch-off line it fails | a3791619 |
| A-F5 / B-F6 the shared client fixture was written by the lane's first four m9 runs | DEFERRED → owner; decided by D39 | see "Fixture incident" | — |
| B-F2 owner acceptance asked only for the additions witness | DONE by D36 (the adaptation as a whole, both switches) | | — |
| B-F3 the 15 unit rows still pinned on the merged gateway were not handed to C5 | FIXED (handoff) | FabricClientMixinAnchorsTest ×2, MixinOperationSeamSpellingTest ×12, OperationCallGraphTest ×1 expect merged `Gui.drawScreen` / `ForgeLayerInstance`; G finds `ClientHooks.extractScreen` | — |
| B-F4 no C6 docs item | FIXED (handoff) | introduction.md:1265-1269 explain the seam through `ForgeLayerInstance.drawScreen`; the switch lists (introduction.md:2425, introduction.zh-CN.md:727) lack the two switches | — |

## Fixture incident (decided by D39)

The lane's first four gate-m9 runs (`m9-fix`, `m9-fix-off`, `m9-v2`, `m9-v2-addoff`) ran on the shared fixture
`/Users/jerry/Documents/Forbric/forbric-kernel/run/client-merged-pack`, not a clone: `cp -c -R <worktree link> <dest>`
copies the symlink. 459 files there are newer than 2026-11-01 15:00 and none newer than 19:22 (journeymap/icon 317,
saves/ForbricTest 35, journeymap/data 18, config/xaero 12, …, options.txt, usercache.json, logs/latest.log). Those four
runs are kept as history only; every result above is from the `m9-v3*` and `m9-fix2` runs on real APFS clones. The fix
and its controls share the same (mutated) base, so the differential holds. D39: `mods/` was verified byte-identical to the
2026-10-30 backup (97 jars, sha1); configs and saves are runtime state the backup never held; not restored; every later
gate clones the fixture by its real path (`pwd -P`), and gate scripts should refuse a rundir that resolves to it (C5).

## Suite

Full suite on G at 9369f153: **5746 tests, 65 failed**, the same names as e3259814 (+8 green tests:
NativeCallInliningRegisteredMixinsStagedTest 4, NativeCallInliningRegisteredMixinsWeaveTest 4). At a3791619: **5749
tests, 65 failed**, the same names (+3 green). This lane deleted no repair, so no (c) row disappeared; the C3 critic
compiled df51265d, 74146c9e, 4a1b2202 and 9369f153 from `git archive` snapshots (all rc=0).

## Open

- C5: re-pin the 15 unit rows to the genuine branch they mean (the inline when no registered mixin anchors on
  `ClientHooks.extractScreen`, or the operation seam with a registered anchor such as spruceui; reference
  `NativeCallInliningRegisteredMixinsStagedTest.GATEWAY`); reword gate-m9:597-598 (on G it is the operation seam that
  carries the wrap).
- C4: log lines of NativeCallInlining / MixinOperationSeamTransport that still say "the merged game".
- C6: document the conflict rule, the additions witness and the two switches.

## Commands

```bash
# m9.sh <label> <port> [extra jvm]: gate-m9 on G on a fresh APFS clone made by real path
FIX=$(cd "$WF/run/client-merged-pack" && pwd -P)
rm -rf "$LANE/rundirs/$1"; cp -c -R "$FIX/" "$LANE/rundirs/$1"; [ -L "$LANE/rundirs/$1" ] && exit 9
FORBRIC_OLD="$GS" FORBRIC_NEOLIVE="$GS/run/neoforge-runtime/forbricneolive.jar" GATE_PORT=$2 \
  M9_RUNDIR="$LANE/rundirs/$1" M9_EXTRA_JVM="${3:-}" .stage/heavy.sh bash run/gate-m9-client.sh
# controls: M9_EXTRA_JVM=-Dforbric.operationSeams.additions=off | -Dforbric.nativeCallInlining.registeredMixins=off
# export:   M9_EXTRA_JVM=-Dmixin.debug.export=true, then javap -c -p the exported Gui and ClientHooks classes
```
