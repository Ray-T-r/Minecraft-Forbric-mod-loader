# Carpet behavior gate

This probe tests the released `fabric-carpet-26.2+v260616.jar` against the staged Minecraft 26.2 game (NeoForge's patched base) and its NeoForge carrier. It uses disposable dedicated-server worlds, Carpet's real fake-player class, real Scarpet event functions, and NeoForge event listeners.

From the repository root, with the shared game artifacts already staged:

```sh
python3 forbric-kernel/run/compat/carpet-gate.py \
  --carpet /path/to/fabric-carpet-26.2+v260616.jar \
  --staged-root /path/to/forbric-loader/run
```

The gate compiles this probe, runs the same 34 behavior checks with `forbric.playerWorldCallbacks=off`, then runs them with the repair enabled under **strict** compatibility policy. It requires the negative control to fail exactly the 13 behaviors only the adapter repairs (the direct `Level.setBlock` lamp check passes without it: the generic retarget carries that redirect; so do the two fluid rules on placement, because NeoForge's `LiquidBlock.onPlace` calls vanilla's `shouldSpreadLiquid`), a third run with only `-Dforbric.fluidReactionNeighbourChange=off` to fail exactly the two neighbour-change fluid checks (vanilla's own lava/water reactions, on placement and on a neighbour change, must pass in all three runs; `summary.json` lists Carpet and base-fluid failures separately), the fixed run to pass every check, both servers to save and stop normally, and the fixed compatibility report to contain no confirmed Carpet losses. The fixed run must also leave none of the five adapted mixins suspected, confirmed or "applies only partially" in the report and console; the negative control must still show report rows for `Level_fillUpdatesMixin` and `ServerPlayerGameMode_scarpetEventsMixin`, which proves the check can fail. Logs, test worlds, reports and input hashes are kept under the printed output directory. `--output` can select a new directory explicitly.

Coverage:

- `fillUpdates` on/off: redstone lamp notifications and support-dependent block shape updates. A direct `Level.setBlock` with neighbor updates while `impendingFillSkipUpdates` is set covers the `updateNeighborsMaybe` redirect on its own; `/fill` places with flags 2 and never reaches it.
- `renewableBlackstone` and `renewableDeepslate` on/off, both placement and neighbor updates. Source lava remains obsidian, reactions above zero remain cobblestone, basalt retains precedence, and lava without water does not become deepslate, each on placement and again on a neighbour change (the `.neighbor` twins), where basalt also keeps precedence over deepslate when blue ice is reached before water and flowing water beside water stays water: the negative controls of the neighbour-change wrap.
- Real `player_swaps_hands` and `player_breaks_block` Scarpet callbacks, including cancellation. Canceled breaks preserve blocks and tool durability; successful events fire once and receive the block state. Creative and survival modes are both exercised.
- The callbacks run where native Fabric runs them. Hand swap runs before anything reads a hand, so a script that empties the main hand without cancelling keeps that change. Block break runs after `playerWillDestroy` and before durability and removal, so a cancelled break keeps what `playerWillDestroy` already did: in creative the bed's head is gone and the foot follows, and unstable TNT is primed while the block stays. These expectations were taken from native Fabric 0.19.5 with the same Carpet jar.
- NeoForge's hand-swap veto still prevents the swap. Scarpet's callback now runs before NeoForge's event, because the event reads the hands, so the callback sees the attempt (one event). NeoForge's block-break veto runs before the callback and prevents both the break and the callback.

The fake player is instantiated locally without a profile/skin lookup. This tests the affected event paths; it does not certify every Carpet rule, Scarpet API, or third-party extension.

The bytecode regression suite is `CarpetMixinAdapterTest`. Its Carpet fixture goes at `forbric-kernel/build/compat-inputs/carpet/mods/fabric-carpet-26.2+v260616.jar`. The suite also reads the staged carrier/game JARs and the local vanilla Minecraft 26.2 JAR. Missing fixtures are reported as skipped tests rather than replaced by mock injector bodies.
