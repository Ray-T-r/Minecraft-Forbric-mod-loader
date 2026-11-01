# Stage 4 C3, second round ("gaps"): the four repairs the C3 critic found without a verdict

After the first C3 round (tip a3791619) a completeness critic read every C3 lane, map row and commit and found four
items with no C3 verdict, none of them on the C3 work list. The orchestrator gave each to a lane of its own, each lane
was reviewed twice (A and B) and followed by a review-fix round, and each wrote a record file beside this one. This file
is the round's summary; the per-repair evidence, commands and scanner sources are in the four lane files.

| critic item | lane | record file | commits | verdict |
|---|---|---|---|---|
| `MixinRelocatedCall` + `MixinPlacementTransactionAdapter` (live on G; the log line blamed MinecraftForge's `useOn`) | c3b-useon-relocation | `stage4-c3-useon-relocation.md` | 00eef69c, 8114f55a; fixes 180b68f3, 72a3ff7b | both KEEP: NeoForge's own `ItemStack.useOn` makes its `Item.useOn` call outside its body too (server: `CommonHooks.onPlaceItemIntoWorld` @115; client: `lambda$useOn$0`), so both are Fabric-on-NeoForge adaptations. Wording and log line rewritten; the gate-visible prefix "<handler> now wraps Item.useOn in forbric$useOnItem" kept |
| `PostMixinFixups` (alias parity, merged-constructor repairs; javadoc blamed the byte merge) | c3b-postmixin-fixups | `stage4-c3-postmixin-fixups.md` | 45ec501e, 6fc5a910, 2fba129f, 4bf8a6d8, bf60c1e4; fixes 27f9f4a2, 95c287df | DELETE the alias rewrite's `keySet()` half (merged red: 22 warnings with it off, 0 with it; G 0 either way) and the `GuiRenderer.pictureInPictureRenderers` seed (dead on both bases; merged red impossible, D37). KEEP NeoForge's alias-first routing (`.deferredRegister` sub-switch renamed into `forbric.aliasPresenceParity`; G: 67 warnings with it off, 0 with it), `MixinFieldInitialiserPlacement` (off: G server "Failed to load datapacks", client NPE in `Pack.fabric$parentsEnabled`), the delegated-constructor replay (EDIT: skips a delegation the mixin's own platform makes into a private wide constructor; new switch `forbric.postMixinFixups.nativeDelegation`), `NativeDeadValueConsumers` (wording) |
| CNI `guardOtherConnectionInitialisation` (HEDGE reasoning from "the merged listener") | c3b-cni-hedge | `stage4-c3-cni-hedge.md` | 0eb8de2a, 7b07d377; fixes ccf9851b, 3664016c, b6c54b6c, ab1bd684 | DELETE (a NeoForge 26.2.0.38-beta carrier fix, dead since .88 on both bases; NeoForge guards re-initialisation itself, now pinned by a strict carrier test, red on four mutated carriers; merged red impossible, D37). `shareMinecraftRegisterWithSuper` KEEP (idle on both bases, adapts Fabric to NeoForge; staged pin). The anchor census now names each hedge that found nothing; G clients report 0 hedges after the deletion (86 of 108 declared) |
| `MixinFluidReactionAdapter.afterUnhandled` / `flowingReaction` + `REGISTRIES`' FORGE entry | c3b-fluid-reaction | `stage4-c3-fluid-reaction.md` | 6c3e96f6, 3c88e7e1, 8f8ea638; fixes 63ea77dc, 8462a705, 98cdbd22, be89707e, b0016a15, 4201ec0c | DELETE both merged rewrites (G 0 / M 1 sites by their own criteria; merged red: Carpet's four fluid cases fail with them off at cf0498eb), the FORGE registry entry, `NativeEntryRelay` (G 0 plans / M 1) and the orphan `weave/createfluid` fixture. KEEP `neighbourChangeOnly`, now only for a mixin known to be Fabric's (red/green test), and a new HEAD arm (MOVE of NativeEntryRelay's job): a Fabric HEAD handler on `shouldSpreadLiquid` (fabric-block-api's flow veto, Create Fly's lava reaction) runs on a neighbour change too, as natively. New gate `run/compat/fluid-head-gate.py`: HEAD-off 3/6 failing exactly the three neighbour cases, fixed 6/6 STRICT |
| record files for regress-server / regress-client and the README Stage 4 section | record | `stage4-c3-regress-server.md`, `stage4-c3-regress-client.md`, README | the commit that adds this file | — |
| unit pin that the four native NeoForge call sites C2 restored stay one NeoForge-owned `INVOKESTATIC` each after the kernel's chain (`Minecraft.<init>` → `ClientHooks.initClientHooks` and `onRegisterParticleProviders`, `ReloadableServerResources` → `EventHooks.onResourceReload`, `BaseFireBlock.onPlace` → `EventHooks.onTrySpawnPortal`) | — | — | — | not taken by any lane; still open → C5 (no kernel code rewrites them today: only comments and RETIRED messages name them) |

## Measurements across the round (G = `.stage4`)

- Suite (`-Pforbric.requireFixtures=all cleanTest test --continue`), always **65 failed** with names identical to the
  a3791619 baseline: 8114f55a 5749; 72a3ff7b 5749; 4bf8a6d8 5754; 95c287df 5757; 7b07d377 5757; ab1bd684 5758; 3c88e7e1
  5759; **4201ec0c 5760**. No (c) row disappeared in this round (none of the four repairs had one); every deleted test
  had passed on G.
- Gates and probes after the round's changes: gate-m44 GREEN (port 46252, after 180b68f3); gate-m14 at 3664016c PASS/FAIL
  lines identical (digits stripped) to the a3791619 verify run, with `PASS NeoForge loaded its default server configs once
  (absent)`; gate-m9 at 95c287df 149/21, the same sets (with `-Dforbric.aliasPresenceParity=off` 134 "but the latter
  doesn't exist" lines, 67 per JVM, against 0); carpet-gate 21/34, 34/34, 32/34 at 3c88e7e1 and after 8462a705;
  fluid-head-gate 6/6 at 8462a705 and after 98cdbd22; a G client joined a pure vanilla 26.2 server and a G kernel server
  under a JFR method trace (`configureOtherConnection` 1 / 0, `loadDefaultServerConfigs` 1, no "Overwriting non-null
  config"); the 97-jar dedicated server reached Done before and after the postmixin edits.
- Not re-run after 7b07d377: gate-m9, gate-m22 and the client-neo-pack boot (the "0 hedges" claim rests on the two lane
  joins and gate-m14). C5's sweep re-runs them.

## Switches changed in the round

- Retired: `forbric.nativeEntryRelay` (98cdbd22; in `ForbricSwitches.RETIRED` and `released-switches.txt`).
- Renamed: `forbric.aliasPresenceParity.deferredRegister` → `forbric.aliasPresenceParity` (`ForbricSwitches.RENAMED`).
- New: `forbric.fluidReactionNeighbourChange.head` (8462a705), `forbric.postMixinFixups.nativeDelegation` (27f9f4a2).

## Owner items the round left

- 7b07d377 deleted a NeoForge carrier repair, not a merge repair, on the ground that it is dead on both bases and meets no
  keep criterion; it needs a decision ("a carrier repair dead on the shipped carrier, and on the merged base, may go
  under C3 with the three checks and a strict carrier pin") or a revert (three files, clean).
- The HEAD arm (8462a705) is a new Fabric-on-NeoForge adaptation like D36's list and needs a D-number and a release-notes
  line. Residual, pinned by `FluidPairMixinAdaptersWeaveTest.onANeighbourUpdateTheTwoHooksRunInTheReverseOfTheirNativeOrder`:
  two HEAD wraps chain with the later-applied one outside, so where fabric-block-api's veto and Create Fly's reaction act
  on one cell they run in reverse native order on a neighbour change.
- better-nether's `bn_shouldSpreadLiquid` (INVOKE `Level.setBlockAndUpdate` with captured locals) runs on placement only on
  G; it was not served on the merged base either. Accept as a vanilla-parity loss or ask for an adaptation.
- The replay's native-delegation skip changes one measured behaviour: fabric-permission-api's `storeOriginalSource` no
  longer runs in `CommandSourceStack`'s private wide constructor (vanilla delegates the same way, so native Fabric never ran
  it there; final values are the same). Decide whether it joins D36.
- A Fabric `@Inject(method = "useOn", at = @At("TAIL"))` on `ItemStack` most likely never runs server-side on G (the
  server path returns at @49 from `CommonHooks.onPlaceItemIntoWorld`; the same on the merged base, not caused by C3).
  Subjects: purefabric `old_red_mushroom_dye` and `story_mode_forest_temple`. Needs a probe.
- `MixinPlacementTransactionAdapter` and the delegated-constructor replay have no off switch of their own (their check 1
  used throwaway builds).
