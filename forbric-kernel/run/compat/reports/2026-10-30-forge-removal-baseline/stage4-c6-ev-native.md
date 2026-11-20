# Stage 4 C6: EnhancedVisuals rain crash (ACCEPT F2), native NeoForge 26.2.0.93 against Forbric f6685b05

The owner answered F2 with "先测原生再定" (measure native first, then decide). This record holds that measurement (lane MEASURE)
and the two read-only checks of it (lens EVIDENCE, lens MECHANISM). No kernel, mod or harness code was changed; nothing was
pushed. Evidence: `/Users/jerry/Forbric-backups/stage4-notes/c6/ev-native/` (called `<ev>` below; 25 MB). `<stage>` is the
stage worktree `.claude/worktrees/suspicious-chaum-b28730`.

## Question

m34's soak client crashed at JVM uptime 4610 s (`stage4-c6-accept-mac.md`, F2) with `IllegalArgumentException: bound must be
positive` at `Random.nextInt` ← `EVManagerClient.addParticlesFadeOut:221` ← `RainHandler.tick:70` (EnhancedVisuals 1.8.29
NeoForge + CreativeCore 2.14.16). ACCEPT's check A-1 corrected the mechanism to:

1. client setup runs before the first resource reload (native order too: `ClientModLoader.finish` insn 3081 before
   `createReload` 3155), so `EVClient.init` loads 0 texture variants;
2. EV's reload listener `EVClient$1.apply` sets `EVRenderer.reloadResources = true`, and `EVRenderer.render` (a
   `RenderGuiEvent.Post` listener through CreativeCore) reloads the variants, but only when `EVClient.shouldRender()` is true,
   which is false for spectators and for creative players unless `doEffectsInCreative=true`;
3. the soak player is creative (`ClientSoakController.preparePlayer`, `src/runtime/.../soak/ClientSoakController.java:189`),
   and `RainHandler.tick` ignores game mode, so rain calls `nextInt(0)`.

Untested before this run: (a) native NeoForge crashes the same way for a creative player; (b) a survival player recovers on
the first HUD frame under both native and Forbric; (c) `RenderGuiEvent.Post` reaches CreativeCore under Forbric.

## Method

### Inputs (`<ev>/install/inputs.sha1`, `<ev>/server/server-jars.sha1`, `<ev>/install/native-client-classpath.sha1`)

| Input | SHA-1 |
|---|---|
| `EnhancedVisuals_NEOFORGE_v1.8.29_mc26.2.jar` (copied from `build/soak-runs/272912f0-417c-4c86-9d2b-7691991012c2/mods/`) | `2f55bcf5f7249272d4b60adf4d1f4b6a5a2ce635` |
| `CreativeCore_NEOFORGE_v2.14.16_mc26.2.jar` (same source) | `6eb7f277a6aec47fa12ac7295f70030a368618a2` |
| `config/enhancedvisuals-client.json` = the soak's (`doEffectsInCreative=false`; rain delay {0,0}, amount {1,1}, lookDownFactor 0.1, lookUpFactor 2) | `e63afdd51edfc831a85d33deda502bb4abd60135` |
| same with only `/general/doEffectsInCreative` = true (the Cx control arms) | `506fea08…` |
| `config/enhancedvisuals.json` (= soak) | `5705d9c4313bab006ecd417f308628067e2e9ba7` |
| `config/creativecore.json`, `config/creativecore-client.json` (= soak) | `b9707864…`, `2ac12a74…` |
| client `options.txt` (the soak's plus `skipMultiplayerWarning:true`, `joinedFirstServer:true`, `soundCategory_master:0.0`) | `6755e160…` |
| NeoForge installer `build/native-controls/downloads/neoforge-26.2.0.93-installer.jar` (= its `.sha1`) | `97205829a020fc9389f504a77f590b54157c8dde` |
| native `minecraft-client-patched-26.2.0.93.jar` (written by the installer) / `neoforge-26.2.0.93-universal.jar` | `7cc4ec8a…` / `6c86caca…` |
| vanilla `versions/26.2/26.2.jar` (APFS clone, matches `26.2.json`) | `2dc72797acbc1b63fc16a11c4ac393605f453754` |
| kernel jar `build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar` (pinned `gradlew $(cat .stage4/props.txt) --no-daemon -q jar` was UP-TO-DATE; built Nov 18 00:46; no later commit touches `src/main` or the build files) | `d6c9bbaeb90e0ed67517a0574d3ac196d0c9d01a` |
| Forbric base `.stage4/run/neoforge-patched/patched-mc-neoforge-26.2.jar` / `neoforge-runtime.jar` | `67bbf24f…` / `0e3ce8d9…` |

Both mods declare `side=BOTH`, so both were also in the server's `mods/`. Byte identity across all 20 runs was re-checked by
lens EVIDENCE (every `game/mods/` holds exactly these two jars; configs as in the table).

### Setup

- **Native client:** the official installer, offline-by-construction:
  `java -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9 -jar <installer> --installClient build/ev-native/launcher`
  → rc 0, "Successfully installed client into launcher"; 55 "exists. Checksum valid", 0 downloads, 5 DNS lookups ("Host:").
  `build/ev-native/launcher` holds APFS clones (`cp -c`) of vanilla `versions/26.2`, `libraries`, `assets`;
  `~/Library/Application Support/minecraft` was not written (0 files modified after 18:30). The client command is built
  from the version JSON by `<ev>/tools/native-client-cmd.py`; each run's exact command is `<ev>/runs/<run>/command.json`.
  Example (N-S-R1, cwd = the run's game dir, `<L>` = `<stage>/forbric-kernel/build/ev-native/launcher`):
  `.stage/client.sh .stage/heavy.sh java -Xms2G -Xmx4G … -XstartOnFirstThread … -cp <108 libs:versions/26.2/26.2.jar> -DlibraryDirectory=<L>/libraries … net.neoforged.fml.startup.Client --username ForbricKernel --version neoforge-26.2.0.93 --gameDir <run>/game --assetsDir <L>/assets --assetIndex 32 … --quickPlayPath <run>/game/quickPlay/log.json --quickPlayMultiplayer 127.0.0.1:46810 --fml.neoForgeVersion 26.2.0.93 --fml.mcVersion 26.2 --fml.neoFormVersion 2`.
- **Forbric client:** `.stage/client.sh .stage/heavy.sh forbric-kernel/run/launch-kernel-client.sh --quickPlayPath <run>/game/quickPlay/log.json --quickPlayMultiplayer 127.0.0.1:46810`
  with `RUNDIR=<run>/game`, `MC_DIR=<L>` (the same clone), `FORBRIC_OLD=<stage>/.stage4`, `FORBRIC_COMPAT_POLICY=strict`,
  `FORBRIC_PREBUILT_JAR/DEPS/STAMP` (DEPS from the pinned `printBootClasspath`; it includes `build/classes/java/main`, as gates do,
  not rebuilt during the run).
- **Server (both engines):** one native NeoForge 26.2.0.93 dedicated server, a clone of `build/native-controls/native/neo`:
  `.stage/heavy.sh bash -c "cd build/ev-native/server && exec java -Xmx2G @libraries/net/neoforged/neoforge/26.2.0.93/unix_args.txt nogui"`;
  127.0.0.1:46810, RCON 46811, `online-mode=false`, `difficulty=peaceful`, flat plains world (player at y = −60),
  `force-gamemode=true`. Weather: `gamerule advance_weather false` (26.2's name; `doWeatherCycle` is rejected), then
  `weather rain 100000d`. Game mode set before each join with `defaultgamemode creative|survival`. At each join the player was
  teleported to `0.5 -60 0.5` rotation `0 0`, so EV's angle factor is d = 1.1 and the trigger is certain on the first in-rain tick.
- **Server-side evidence per in-rain window (RCON, `<ev>/runs/<run>/events.log`):** `data get entity ForbricKernel playerGameType`
  (0 or 1 as intended), Rotation `[0.0f,0.0f]`, `execute as ForbricKernel at @s if predicate {weather_check raining:true}`,
  `location_check can_see_sky:true` at feet and eyes, `if biome ~ ~ ~ minecraft:plains` — all passed.
- **Arms.** N = native, F = Forbric; C = creative, S = survival, Cx = creative with `doEffectsInCreative=true`.
  "join-in-rain": already raining at join. "clear→rain": joined in clear weather, ~33 s with HUD frames drawn, then rain started
  (added by MEASURE because join-in-rain crashes in every mode and cannot separate the modes).
  Non-crashing runs were ended by an RCON kick, then SIGTERM to the client's process group (exit 143).
- Driver: `<ev>/tools/run-arm.py`, `batch1.sh`, `rcon.py`, `keypost.py`.

## Results (`<ev>/results.tsv`)

All 14 crashes have the same stack — the first 14 frames, normalised, hash identically (lens EVIDENCE): `Random.nextInt:551` ←
`EVManagerClient.addParticlesFadeOut:221` ← `:188` ← `VisualHandler:116` ← `RainHandler.tick:70` ← `onTick:79` ←
`clientTick:47` ← `CreativeForgeLoader.lambda$registerClientTick$0:60` ← `ConsumerEventHandler:27` ← `EventBus.post:360/:328` ←
`ClientHooks.fireClientTickPre:877` ← `Minecraft.tick:1817` ← `runTick:1208`; Forbric runs differ only below `Main.main`
(KernelBoot frames). Every crash report says `Client weather: Raining: true` and its `mode=` matches the arm (14/14). Every
`latest.log` has 21 `Could not find any resources` lines and exactly one `Reloading ResourceManager` (20/20).

| Arm | Runs | Outcome | JVM uptime at crash / connected time in rain | Exit |
|---|---|---|---|---|
| N-C join-in-rain | N-C-1, N-C-2 | 2/2 CRASH ~1 s after join | 16.7 s, 31.0 s | 255 |
| N-C clear→rain | N-C-R1, N-C-R2 | 2/2 CRASH; fine 32.8 s in clear, crash 1 s after rain | 49.8 s, 67.6 s | 255 |
| N-S join-in-rain | N-S-1, N-S-2 | 2/2 CRASH ~1 s after join (mode=SURVIVAL) | 19.6 s, 12.4 s | 255 |
| N-S clear→rain | N-S-R1, N-S-R2 | 0/2 crash | 124 s, 125 s in rain | 143 |
| F-C join-in-rain | F-C-1, F-C-2 | 2/2 CRASH | 14.6 s, 20.9 s | 255 |
| F-C clear→rain | F-C-R1, F-C-R2 | 2/2 CRASH 2 s / 1 s after rain | 49.2 s, 48.3 s | 255 |
| F-S join-in-rain | F-S-1, F-S-2 | 2/2 CRASH (mode=SURVIVAL) | 15.1 s, 17.7 s | 255 |
| F-S clear→rain | F-S-R1, F-S-R2 | 0/2 crash | 124 s, 124 s in rain | 143 |
| N-Cx join-in-rain | N-Cx-1 | 1/1 CRASH | 31.7 s | 255 |
| N-Cx clear→rain | N-Cx-R1 | 0/1 crash | 125 s in rain | 143 |
| F-Cx join-in-rain | F-Cx-1 | 1/1 CRASH | 15.4 s | 255 |
| F-Cx clear→rain | F-Cx-R1 | 0/1 crash | 124 s in rain | 143 |

Totals: 20 runs, 14 crashes, 6 survivals; native and Forbric agree in all 12 arms.
"Connected time in rain" is rain command → kick from the server console (lens EVIDENCE); MEASURE's `in_rain_s_before_stop`
column (129.8–130.0 s) runs to SIGTERM, ~5 s after the kick. Rain was sampled by the server at +3 s and ~+120 s per surviving
run; continuity in between rests on `advance_weather=false` and no weather command in the console.

**Run length is decisive.** With the defect present each in-rain tick triggers with p = 1 at xRot ≤ 9° and p ≥ 0.1 otherwise,
so P(no crash in n ticks) ≤ 0.9ⁿ: 66 ticks (3.3 s) give < 1e-3 at any rotation; the survivors had ≥ ~2 460 in-rain ticks at
xRot 0 (deterministic trigger). One run per arm would already decide it.

**Recovery is observed, not only inferred.** MC's own F2 screenshots at ~+120 s of rain show EV's blue drop particles over the
HUD in all six surviving runs (`<ev>/screenshots/`, side by side in `rain-grid-NS-FS-NCx-FCx.png`); drawing a particle reads
`resources[…]`, so the `drop` variants were loaded after setup had loaded 0. Pre-rain screenshots show a clear sky with the HUD.

**Ordering.** Native: the `drop` warning comes 22–28 ms before `Reloading ResourceManager` (10/10). Forbric: the warning's line
precedes the reload's line in 10/10 (same second in 8, one second earlier in F-C-1 and F-S-1; Forbric's log has no ms).

## Bytecode (`<ev>/bytecode.md`, re-derived independently in `<ev>/verify-mechanism.md`)

- Tick path, no game-mode test anywhere: CreativeCore's `ClientTickEvent.Pre` listener → `EVManagerClient.clientTick`
  (`shouldTick = player == null || !paused`) → `onTick` (`VisualHandler.isEnabled = enabled && opacity > 0`) → `RainHandler.tick`
  (fires when `isRainingAt` the player's block or head; delay 0 → acts every in-rain tick) → `addParticlesFadeOut` → line 221
  `random.nextInt(getVariantAmount())`, `getVariantAmount = resources.length`.
- The only `putfield VisualTypeTextureClient.resources` is in `loadResources` (pc 248); its only callers are `EVClient.init`
  (pc 80, client setup) and `EVRenderer.render(Object)` (pc 62). `render` returns at pc 8 unless `EVClient.shouldRender()`;
  it reloads when `reloadResources` is set; `EVRenderer.<clinit>` sets it false and only `EVClient$1.apply` (every reload) sets
  it true. `render` is the method handle `EVClient.init` passes to CreativeCore's `registerClientRenderGui`, which adds a
  `RenderGuiEvent$Post` lambda on `NeoForge.EVENT_BUS`. `shouldRender = player == null || (!spectator && (!creative || doEffectsInCreative))`.
- Native and Forbric `Minecraft.<init>` are the same bytes (`709bf8c8…`): `ClientHooks.initClientHooks` pc 2881 (posts
  `AddClientReloadListenersEvent`), `ClientModLoader.finish` pc 3081 (client setup), `createReload` pc 3155. Forbric's
  `NeoClientSetupHookInjector` calls `KernelLifecycle.onNeoClientSetup` right before that same `finish` call site.
  `ClientHooks`/`ClientModLoader` bytes are identical native vs `neoforge-runtime.jar`.
- `RenderGuiEvent.Post` is posted only by `GuiLayerManager.render`, called only from `Hud.extractRenderState`, which skips it
  while the screen is a `LevelLoadingScreen` (pc 27–33). `Minecraft.tick` fires `ClientTickPre` regardless of screen, and
  `runTick` ticks (pc 258) before `GameRenderer.extract` (pc 440). The server sends position and the rain events
  (`sendLevelInfo`) before any chunk. So a fresh client that joins into rain runs EV's tick before it can draw any HUD frame.
- `GuiLayerManager` is byte-identical native vs Forbric (`bb8e1b97…`); `Hud` and `GameRenderer` are instruction-identical after
  normalising constant-pool and indy indices; kernel `Hud` transforms touch only `updateContextualBarRenderer` and member twins.

## Verdicts

| Inference | Verdict | Basis |
|---|---|---|
| (a) Native NeoForge crashes the same way for a creative player (`doEffectsInCreative=false`) | **YES** | N-C 4/4 crash, same stack and line numbers as Forbric and as the soak; N-C-R is exactly the soak's sequence (fine in clear weather, crash 1 s after rain) |
| (b) A survival player recovers on the first HUD frame, native and Forbric | **Only if a HUD frame after the first reload comes before the first in-rain tick.** Joining into rain crashes survival too, on both engines (N-S 2/2, F-S 2/2); with HUD frames before rain, survival recovers on both (N-S-R 2/2, F-S-R 2/2) | outcomes + bytecode: the `LevelLoadingScreen` gate and tick-before-extract force the join-in-rain crash in any mode (MEASURE had marked this inferred; lens MECHANISM showed the bytecode forces it) |
| (c) `RenderGuiEvent.Post` reaches CreativeCore under Forbric | **YES, by elimination, not instrumented** | F-S-R and F-Cx-R logged one 0-variant pass then survived ~2 460 deterministic triggers; the only other writer is `EVRenderer.render`; F-C-R (crash) vs F-Cx-R (survive) differ only in `doEffectsInCreative`, so the loader under Forbric is the `shouldRender`-gated path; classes on the post path are identical/instruction-identical. No javaagent, no runtime dump of transformed `Hud` |
| `doEffectsInCreative=true` makes creative behave like survival | **YES** on both engines (N-Cx-R1, F-Cx-R1 survive; N-Cx-1, F-Cx-1 crash on join-in-rain) | control arms |
| Forbric differs from native on this path | **NO** | same outcome in 12/12 arms, same stack, 21 warnings in both, same setup-before-reload order |

**F2 is EnhancedVisuals' own behaviour on native NeoForge 26.2.0.93, not a Forbric defect.** The kernel has nothing to fix on
this path.

Forbric-only log lines seen, off this path and not investigated: `require 1 → 0` relaxations for EV `GameRendererMixin.extractGuiEnd`
(shader/blur path), EV `PlayerMixin.actuallyHurt` and several CreativeCore mixins; "EV ExplosionMixin applies only partially, 3/4
anchors (ServerLevel.getEntities in ServerExplosion.hurtEntities)". Both engines log CreativeCore's "Could not load default style"
(NoSuchElementException) in client setup. Time from java start to join: native 9.4–32.5 s, Forbric 13.4–33.3 s.

## What the verifiers found (kept only what survived)

Neither verifier refuted the result; both returned. Corrections applied above:

- **Kept as corrections:** connected rain time is 124–125 s, not 129.8–130.0 s (the larger figure runs to SIGTERM); Forbric's warning
  is one second before the reload in F-C-1 and F-S-1, not the same second; F-C-R1 crashed 2 s after rain, not 1 s; the
  survival join-in-rain crash is forced by bytecode, not merely inferred; `EVRenderer.render` is passed by `EVClient.init`'s indy
  handle and wrapped by CreativeCore's `registerClientRenderGui$0` (wording only).
- **Kept as limits:** the clients' network was not cut off (Mojang session/realms auth calls went out, 401s in `latest.log`; no file
  was downloaded); the installer still did 5 DNS lookups and logged "Found 1 additional library directories" without naming it;
  `RenderGuiEvent.Post` delivery under Forbric is not instrumented; rain at the player was sampled at 2 points per surviving run.
- **Not explained (soak residual):** why the soak crashed at 4610 s and not sooner. Its weather history is not recorded (the crash
  report says `mode=CREATIVE`, `Raining: true`, pos (2048.5, 160.0, 2048.5), integrated server, ~98 mods). The soak save's
  `level.dat` has `GameType 0` (survival) while `players/data/0…0.dat` has `playerGameType 1` at the end; by the mechanism the
  soak can only crash if the client never drew a survival HUD frame after the first reload in 4610 s (e.g. `preparePlayer`
  made the player creative before the first HUD frame). The crash shows that held; how it held was not checked.

## Deviations from the soak

Only EV + CreativeCore, not the soak's ~98 jars; a dedicated server instead of the soak's integrated one (the integrated path's
loading-screen close delay of up to 500 ms only widens the no-HUD window; lens MECHANISM: no outcome changes); game mode via
`defaultgamemode` + `force-gamemode` before join instead of `preparePlayer` after join (verified by `playerGameType`); runs ended by
kick + SIGTERM; in N-S-R1 macOS `screencapture` grabbed the early-display window (MC's F2 screenshots are the evidence).

## m34 options consistent with "不准对 mod 特判" and with this measurement

The owner decides; this lists what the evidence supports. Common to every option: on native NeoForge any client that joins into
rain before its first HUD frame crashes with these two jars, whatever the game mode, so no option that keeps EnhancedVisuals and
rain in m34 removes the hazard entirely; it only moves it to "the first world join of the JVM happens in rain" (once loaded,
`resources` is static and survives later world changes).

| Option | What changes | Evidence for it | What it does not cover |
|---|---|---|---|
| 1. `doEffectsInCreative=true` in the m34 fixture's `config/enhancedvisuals-client.json` | one fixture config value; no code; the soak player stays creative | **measured on both engines**: N-Cx-R1 and F-Cx-R1 survive 124–125 s of rain after HUD frames; same as survival | join-in-rain on the first world join still crashes (N-Cx-1, F-Cx-1). The fixture `run/client-merged-pack` is a symlink to the main checkout's untracked `run/client-merged-pack` (config sha1 `e63afdd5…` today), so the edit is outside git and shared by every worktree |
| 2. A survival soak player | harness: `ClientSoakController.preparePlayer` sets `GameType.CREATIVE` (line 189); keeping invulnerability/flight in survival is harness design, not measured | **measured on both engines**: N-S-R, F-S-R survive | same first-join-in-rain hazard (N-S-1/2, F-S-1/2); unmeasured whether the rest of the soak route behaves the same in survival |
| 3. Make m34's weather deterministic (no rain), or route/fixture without EnhancedVisuals | fixture/route | structurally removes the trigger; not run | loses rain/EV coverage the soak has today; not measured |
| 4. A structural kernel change (e.g. restore a client resource preload before mod setup) | kernel | **not supported by this evidence**: the brief allows it only if native and Forbric differ, and they do not differ in any arm. It would be a leniency beyond native, not a Forbric defect fix (the merged-base builds had such a preload, 8c58428d; C3 removed it, b7dd2f64/D16) | — |

Not acceptable under the rule: anything keyed to EnhancedVisuals/CreativeCore (mod id, class or package names) in the kernel.

Which the evidence supports most directly: options 1 and 2 are the only ones measured on both engines; option 1 is the smaller
change (config only, no harness code) and was measured exactly as it would run (creative player + `doEffectsInCreative=true`).
Neither makes m34's verdict weather-independent on the first world join; only option 3 does, at the cost of coverage.

## Cleanup

Server stopped via RCON `stop`; no `build/ev-native` java process remains; ports 46810–46819 free; no client-queue tickets left;
`git status` clean. Scratch `<stage>/forbric-kernel/build/ev-native` (gitignored, 2.7 GB apparent: launcher 2.4 GB mostly APFS
clones, server 149 MB, runs 164 MB) left in place. `~/Library/Application Support/minecraft` unchanged.
