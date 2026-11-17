# Compatibility verification

The current compatibility plan has four implementation phases after this tooling phase.
Keep the same popular and random sets for each before/after comparison. A successful boot
alone does not prove that a mod's features ran.

## Inputs and transport

`../patch-dynamic-torches.py <original.jar> --output <fixed.jar>` repairs Dynamic Torches 5.4's legacy
`type` entity-predicate key to `entity_type` for Minecraft 26.2. It writes a separate jar and preserves every
other entry; keep the original outside the active mods directory when installing the repaired copy. Run
`python3 -m unittest discover -s run/compat -p test_dynamic_torches_patch.py` for archive preservation,
idempotence and refusal checks. World validation must also prove `dt:tag` loads and a torch item receives
the `dt.lit` tag; a JSON rewrite alone is not functional acceptance.

Use Python 3 (standard library only), Bash, and the staged game/carrier jars. Configure
`WINSH` and `WINFILE` as the executable commands for the existing Windows shell/file
transports. Shell aliases are not inherited by scripts. `lib-compat.sh` parses these
commands without `eval`, limits each call to 240 seconds, and checks a PowerShell success
sentinel. Do not put credentials in reports or committed files.

Set `FORBRIC_MC` to the Windows Minecraft root and `FORBRIC_VERSION` to its installed
profile id. `FORBRIC_INSTANCE` optionally selects a dedicated test instance; otherwise
the profile directory is used. `FORBRIC_WORLD` selects the generated save. Preserve the
launcher-created native directory and `options.txt`. `FORBRIC_PYTHON` selects the remote
Python executable and must name the real interpreter, never a launcher shim: a shim
re-executes a different process, so the job publishes a pid this run never started and
cannot vouch for, which is reported by name instead of retried. A Python Manager shim
names its interpreter in `<command>.__target__`. All five Windows entry points accept `--print-config` on Mac
without launching, reading the Windows disk, or importing Windows-only APIs.

## Select the packs

`pick_mods.py <mods-dir> --slugs a=forge,b=fabric --resolve-only` resolves named builds
before downloading. The requested loader is mandatory: a NeoForge build is not a
MinecraftForge substitute. Missing versions print `UNAVAILABLE`. Keep that result in
the report and choose a same-purpose replacement before accepting the popular set.
Use `MODRINTH_API` to select the API endpoint (also the local HTTP fixture seam).

For the next random set, set `SEED=20260919`, `WANT_FABRIC=26`, `WANT_NEO=26`,
`WANT_FORGE=18`, and `EXCLUDE_MANIFEST` to the previous set's manifest. Preserve the
resolved manifest, including dependencies and actual loaders, with the run evidence.

`abi-audit.py` checks class references against explicitly supplied carrier/game jars;
`field-drift.py` compares the field descriptors of vanilla and a game base (it was written for the merged base of
Forbric 0.4 and earlier; it reads any two jars, so NeoForge's patched game is the base to give it now) and reports
affected guest jars. `fapi-usage.py` scans class references, including `META-INF/jars`, to prove
that candidates actually call a named set of symbols — by default the Fabric loot/model
surfaces, and with `--preset` / `--symbols` any other set, which is how "who is actually
waiting on this Forge event" gets answered from bytecode instead of from `javap` notes in
a javadoc. `--list-presets` prints the named sets. (`hook-worklist.sh`, which joined the carrier's event hooks the
merged base no longer called to the jars that named them, went with the NeoForge-to-MinecraftForge event forward in
Stage 4 of dropping MinecraftForge: on NeoForge's own base every NeoForge hook is NeoForge's.) `repair-drift.sh`
replays the compat transformer's claim ledger against a CANDIDATE game build and names
the repairs that would stop applying, which is the question a carrier bump asks and that
nothing answered before a player did. It runs the vanilla twins first, as a launch does, so a
candidate run needs the vanilla-reference sidecar last in its jar list (with no arguments it uses
the staged one); without it the census says so and reports the repairs that stand on a copied
vanilla body as declined, and a twin that stands down on a stale sidecar prints as HEDGED, which
withholds the all-clear. `control-diff.sh` answers the question that has
been settled by argument until now — is this symptom Forbric's — by booting the same
Fabric mods on a native Fabric server and on Forbric and saying which log it appears in:
FORBRIC-ONLY, BOTH, NATIVE-ONLY or NEITHER, and INCONCLUSIVE when an arm did not start.
Run each with `--help` for its argument list. API usage must be proved from the downloaded candidate
jar before treating the selection as final; metadata resolution alone cannot prove it.

## MinecraftForge refusal sweep (Stage 3 of dropping MinecraftForge)

`stage3-sweep.py --classpath <kernel classpath> --out DIR [--root DIR ...]` asks two kernels' own arbitration how every
local mod jar loads: the `tri-eco-26.2-final` tag's (`before`) and this tree's (`after`, which refuses a MinecraftForge
build with no usable Fabric or NeoForge half). It writes one TSV row per distinct SHA-1 with each declared loader half's
verdict and reason, and prints every transition, every refused jar and every jar that now loads as Fabric with a
mandatory dependency its pack does not supply. Without `--root` it sweeps the main checkout's `forbric-kernel/run`,
`forbric-kernel/build` and `forbric-loader/run` (`FORBRIC_SWEEP_HOME` overrides where that checkout is).
`stage3-refused-check.py <stage3-sweep.tsv> <out.tsv> [--offline]` reads each refused jar independently of the kernel
(manifests, `@Mod` classes, entrypoints, mixin configs, package references, nested jars) and, online, asks Modrinth
whether the mod has a NeoForge or Fabric build for 26.2 — the evidence for the hand check that no refusal is false.
`stage3-logcheck.py <stage3-sweep.tsv> [--since ...] [--exclude SUBSTRING ...]` cross-checks the `before` column against
what real boots logged for multi-manifest jars and exits 1 when a current log disagrees. The committed results and the
exact commands are in `reports/2026-10-30-forge-removal-baseline/stage3-sweep.md`.

## Rename experiment

A fix must not recognise a mod by name, so the same mod under other names must be treated exactly like the original.
`rename-mod.py rename <mod.jar> --out-dir DIR [--dependent <probe.jar> ...]` writes that mod: every class under the
mod's root packages moves to another package and class name (descriptors, signatures, annotations, Mixin targets,
MixinExtras definitions, refmap entries and class-name strings follow), its mixin configs, refmaps, access widener and
jar get other file names, and its mod ids (letters only) replace the old ones in `fabric.mod.json`, `mods.toml`,
`neoforge.mods.toml`, the manifest, service files, jar-in-jar metadata, loader annotations and loader mod lookups.
Minecraft, the loaders, Mixin, other mods, shaded libraries and resource namespaces keep their names; a name segment
the mod's own code tests strings against (Iris' `contains("VKOnly")`) keeps its text. A probe compiled against the mod
goes in `--dependent` so its references follow. `rename-mod.py verify` reads every class back with the old names put
in again and must report 0 problems. `rename-mod.py compare --map DIR/<new>.jar.rename.json <original run> <renamed
run> [--probe GLOB] [--image GLOB]` compares two runs of the same scenario: the Mixin export census
(`-Dmixin.debug.export=true`), the `Mixing ... into ...` lines (`-Dmixin.debug.verbose=true`), warnings naming the
mod, the kernel's `[Forbric/...]` lines, the compatibility report and probe files, with the renamed run's names put
back. Run it on the genuine loader first (it must say SAME, or the rename itself changed the mod), then on Forbric:
any difference there is a name-keyed defect. Run the original twice to see what differs between any two runs.
`python3 -m unittest discover -s run/compat -p test_rename_mod.py` runs a synthetic mod through rename, verify, the
JVM and compare.

## Carpet rule and event verification

`carpet-gate.py --carpet <fabric-carpet-26.2+v260616.jar> --staged-root <stage/run>`
compiles the Carpet probe and runs 34 behavior checks in isolated dedicated-server worlds, three times. The baseline
runs with `forbric.playerWorldCallbacks=off` and must fail exactly the 13 checks that need the adapter (fill shape
updates, renewable blackstone and deepslate on a neighbour change, both Scarpet events and their native-Fabric order;
the direct `Level.setBlock` lamp check and the two fluid rules on placement pass without it, because the generic
retarget carries that redirect and NeoForge's `LiquidBlock.onPlace` calls vanilla's `shouldSpreadLiquid`). Vanilla's
own lava/water reactions, on placement and on a neighbour change, must pass in every run (NeoForge's own registry and
vanilla's placement path give them; the neighbour-change twins are the neighbour wrap's negative controls), and the
summary lists Carpet and base-fluid failures separately. A third run with only
`-Dforbric.fluidReactionNeighbourChange=off` must fail exactly the two neighbour-change rule checks. The fixed run uses
strict compatibility policy and must pass every behavior check with no confirmed Carpet losses, and none of the five
adapted mixins may be left suspected or "applies only partially" (the baseline must still show them, so the check can
fail). Every run must save and shut down normally. Reports, logs, test worlds and input hashes stay under the printed
output directory; `--output` selects a new directory explicitly; `--port N` puts the three servers on N to N+2 (default:
any free port). See [the Carpet probe instructions](../../canary/carpet/README.md) for prerequisites and coverage.
`gate-m62-carpet.sh` runs it in the gate list (the jar from `build/compat-inputs/carpet`, `M62_CARPET` overrides;
servers on `GATE_PORT` to `GATE_PORT+2`) and asserts 21/34, 34/34 and 32/34.

## Fluid HEAD handlers on a neighbour change

`fluid-head-gate.py --fabric-api <fabric-api-0.161.0+26.2.jar> [--create <create-fly.jar>] [--staged-root <stage/run>]
[--output DIR]` compiles the fluid HEAD-handler probe and runs its cases in two isolated dedicated servers (ports from
`GATE_PORT`, else any free one). fabric-block-api's `FluidFlowEvents` veto (block-api 3.1.0, fabric-api 0.161.0 or later)
and Create Fly's lava + honey reaction are cancellable HEAD injectors on vanilla's `LiquidBlock.shouldSpreadLiquid`,
which NeoForge's `onPlace` asks but its `neighborChanged` does not (it asks NeoForge's fluid interaction registry), so
`MixinFluidReactionAdapter` runs them from a wrap of that registry question. The run with
`-Dforbric.fluidReactionNeighbourChange.head=off` must fail exactly the neighbour-change cases (`neighbor.veto`,
`neighbor.veto.reaction`, and `create.neighbor.reaction` with Create) and announce no wrap; the placement and un-vetoed
cases pass in both runs. The fixed run uses strict compatibility policy, must pass every case and must announce the
wrap of each loaded mixin. `--off <switch>` replaces the negative run's switch and then only reports which cases fail.
Every run must save and shut down normally; reports stay under the output directory. See [the probe's
instructions](../../canary/fluidhead/README.md). `gate-m61-fluid-head.sh` runs it in the gate list (fabric-api 0.161.0
and Create Fly from `build/purefabric-2026-10-03` and `build/compat-inputs/create-fly`, `M61_FABRIC_API` /
`M61_CREATE` override; servers on `GATE_PORT` and `GATE_PORT+1`) and asserts both runs' lines.

## Fabric item and shears adaptations

`regress-gate.py --fabric-api <fabric-api-0.155.2+26.2.jar> --bclib <bclib-26.201.2.jar> --mod <worldweaver> --mod
<wunderlib> [--staged-root DIR] [--port N] [--output DIR]` compiles the regress probe (a Fabric mod with a NeoForge half)
and runs 15 cases in four dedicated servers: fabric-item-api's custom damage handler, BCLib's tag shears on sheep and
snow golems beside a Fabric and a NeoForge item with an entity entry of their own, and fabric-item-api's stack-aware
furnace remainder. The fixed run must pass every case with fabric-item-api's `ItemStackMixin` and
`AbstractFurnaceBlockEntityMixin` and BCLib's `SheepMixin` / `SnowGolemMixin` unsuspected; `-Dforbric.itemDamageHandler=off`
must fail exactly the two `damage.*` handler cases and `-Dforbric.shearsRelayMobs=off` exactly the three tag-shears cases,
and `-Dforbric.supersededMixins=off` no case, each showing its mixin suspected again (the furnace mixin's rows are
resolved only by SupersededMixins' native-furnace-remainder entry), so each check is known to be able to fail. See [the probe's instructions](../../canary/regress/README.md).
`gate-m60-regress.sh` runs it in the gate list (fabric-api from `.dev/api`, else `run/client-merged-pack/mods`, as
build.gradle takes it; BCLib and WorldWeaver from `build/compat-inputs/sweep90`, WunderLib from
`build/purefabric-2026-10-03`; `M60_*` override; servers on `GATE_PORT` to `GATE_PORT+3`) and asserts each phase's
exact line.

## Vanilla fluid parity

`fluid-parity-gate.py [--output DIR]` runs one datapack, unchanged, on a vanilla 26.2 dedicated server
(`launch-vanilla-server.sh`, the player's own jar) and on the kernel with zero mods (`launch-kernel-server.sh`): lava set
beside water (source and flowing), water set beside lava, lava set on soul soil beside blue ice, lava flowing into a
cell under water, water flowing to lava, lava flowing down into water, and a cobblestone and a basalt generator emptied
every tick for 600 ticks. The scores the console prints and the blocks in the saved region files must be identical on
both sides, and vanilla itself must show every reaction (so a scenario that measured nothing cannot pass). Needs Java 25
on `PATH`, like gate M31. Server ports come from `GATE_PORT` (each server takes the next), else a pid-derived default.
It is a manual tool, not a gate.

`fluid-parity-gate.py --mods [--native-controls DIR]` is the same comparison for a NeoForge mod's fluid rules, against
native NeoForge 26.2.0.93 itself, with a NeoForge canary mod (`canary/fluid-interactions/neo`, compiled against native
NeoForge's installed jars only), and the kernel with the same jar. The native image is the one `native-controls.py
prepare` installs (default `build/native-controls`); each run copies it and never writes into it. On NeoForge a mod's
rule runs when a block next to the liquid changes and never when the liquid is placed. Every case cell must match native
NeoForge's server, immediately and 100 ticks later, and the canary must report the same firings there.
Stage 4 of dropping MinecraftForge took out `--unfixed` (`-Dforbric.fluidInteractions=off`: FluidInteractionsInjector
was deleted as native parity and the switch is retired, so the run could not go red) and the native MinecraftForge
column (Forbric refuses a MinecraftForge-only mod, so no MinecraftForge rule runs on Forbric to be compared).

## Breathing, drowning and liquids (gate-m57)

`breath-drown.py` is gate-m57's measuring tool. Its canaries (`canary/forge-breath-drown`, one MinecraftForge and one
NeoForge mod; `run/build-breath-drown-canary.sh` builds the NeoForge one from the stage, and
`run/build-forge-refusal-fixtures.sh` the MinecraftForge one against stand-ins, entry for entry the jar the real
carrier gave) play 53 scenarios on a dedicated server and write one JSON
result: air and health every tick, what each family's breathe, drown and damage listeners saw, a liquid's bucket round
trip, the body's random-source steps per tick, and the entity events the server sent for the body (the probe's player
gets an entity tracker of its own for that, as a player in a level has one). Since Stage 3 of dropping MinecraftForge,
Forbric refuses the MinecraftForge canary, so on Forbric the NeoForge one plays alone; both still run on their native
loaders for the reference.

- `breath-drown.py reference [--runs 3] [--port N]` rebuilds the canaries and runs native MinecraftForge 26.2-65.0.1
  and NeoForge 26.2.0.93 (the images `native-controls.py prepare` installs; `M57_NATIVE` points elsewhere) `--runs`
  times each, in fresh instances under `build/m57-reference/`. Every run of one loader must agree with its first on
  every stored field — what is judged, and the random draws — or it stops and names the differences; nothing is
  filtered. It then writes `canary/forge-breath-drown/native-reference.json` with the canary sources' digest. Run it
  after any change to the canaries; the gate refuses a reference measured with other sources (`check-sources`). The
  native images are cloned with `cp -c` on macOS and copied elsewhere, and the NeoForge and MinecraftForge servers
  start from their installers' `unix_args.txt` (`win_args.txt` on Windows); the canary build reads the game's
  libraries from `MC_DIR` (default: the macOS launcher directory), as every canary builder does.
- `breath-drown.py judge --result R.json --phase positive|refused|vanilla-refill-off` is the gate's verdict: every row
  against native NeoForge (air and health, or a liquid row's result, and what the NeoForge canary's listeners saw; a
  row's `f-*` tags do nothing there, as they do on Forbric with the MinecraftForge canary refused), no row with a view
  of a MinecraftForge listener, the rows in the MinecraftForge canary's liquids skipped because the block or item is not
  registered (as on native NeoForge), plus one documented deliberate difference (`vanilla-refill`: a body that breathes
  with its eye in water refills as vanilla does, so NeoForge's breathe listener is handed `refill=4` where native NeoForge
  hands it `refill=0`, which the reference must still show; the water-breathing pig, which keeps the refill, must have
  the air native MinecraftForge's hook, which refills as vanilla does, gives the same row, and native NeoForge's air must
  differ). Exit 1 unless the failed rows are exactly the ones the phase predicts (none for `positive` and `refused`; the
  two vanilla-refill rows for `vanilla-refill-off`, each failing because NeoForge's breathe listener was handed
  NeoForge's own refill).
- `breath-drown.py run --engine native-forge|native-neo|native-fabric|forbric --mods JAR... --driver forge|neo|fabric
  --out DIR --port N [--jvm=-D...] [--tree WT --staged OLD]` is one server, for a developer probe (a Fabric build of the
  same canary with fabric-api's damage events stays out of the repository); `compare A.json B.json` lists every
  difference, the unjudged fields included. `properties` prints the server.properties lines every arm uses. The
  Forbric engine runs `run/launch-kernel-server.sh`, so it needs bash.

`test_breath_drown.py` holds the judge's row table to the canary's plan (and the liquid rows to the rows the plan puts
in MinecraftForge's liquids), the family names it reads to the names the canaries report under, the gate to every row
and phase, the committed reference to the canary sources (line endings ignored), to
every row it is compared on and to the documented difference, and each phase's verdict to "exact both ways, for the
phase's cause"; it needs no game files and runs in `tools/dev.py tool-test`.

## Fabric menu codec verification

`menu-codec-gate.py --farmers-delight <FarmersDelight-26.2-3.6.26+refabricated.jar>`
compiles the menu probe and opens Farmer's Delight's cooking pot through a real `ServerboundUseItemOnPacket` in an
isolated dedicated-server world with Fabric API 0.155.2+26.2. The run uses strict compatibility policy and must record
the codec, open the menu and send fabric-menu-api's `open_screen` payload, with no confirmed Farmer's Delight or
fabric-menu-api losses, and must save and shut down normally. It is a manual tool, positive only: its baseline run
(`forbric.wrapperEntryEvents=off`, the merged base's MinecraftForge-wrapper half of the registries' entry events) went
in Stage 4 of dropping MinecraftForge, since that switch is retired and the baseline passed every check on NeoForge's
base. See [the menu probe instructions](../../canary/menu-codec/README.md).

## Run and collect

1. Build the four staged artifacts and canaries: the kernel jar, the base (NeoForge's patched game), its one carrier
   (the NeoForge runtime), and the vanilla-reference sidecar measured against that base (`tools/dev.py prepare` stages
   them; push-and-run builds a sidecar with `run/vanilla-reference-jar.sh` when the staged one records another base).
   The stage is `FORBRIC_OLD` when it is set, else `forbric-kernel/.dev/staged` (what `tools/dev.py prepare` writes,
   and what `build.gradle` reads without `FORBRIC_OLD`); never `forbric-loader/run`, whose carrier is NeoForge
   26.2.0.88 and unstamped. The base and the carrier go up only while their build stamps (`<jar>.pins`) name this
   tree's installer pins; otherwise push-and-run refuses before building or touching anything (prepare the stage,
   point `FORBRIC_OLD` at a prepared one, or name the files with `--artifact`). Do not run a gate while a Windows sweep is using those artifacts; gates rebuild
   the kernel jar in place.
2. Use `push-and-run.sh --label <label> --mods <mods-dir> --manifest <manifest.json>`.
   Preview with `--dry-run --version-json <installed-profile.json>`. The dry run names
   all four artifact uploads, sanitized mod names, PID stop, cleanup, and evidence. push-and-run rewrites only the
   profile's game arguments: `--gameJar`, `--runtimeJar` and `--nativeReference` become the paths of the base, carrier
   and sidecar libraries the profile names, one value per flag, with `--libraryPath` and every other argument kept
   (`PROFILE_REWRITES`, or `PROFILE_REWRITES none` when they already say that). It changes no library. A profile the
   installer of this tree did not write -- one an older installer (Forbric 0.4 or earlier) wrote, naming the merged
   base, a MinecraftForge carrier or the old loader's jars, or one naming the three game artifacts at other versions
   than this tree stages, or lacking one -- is refused (exit 2) before anything is stopped or staged, with the advice
   to run the new installer again with the same game directory; the kernel would refuse that game anyway (D9).
3. Stop only PIDs read from `.forbric-sweep.pid` / `.forbric-gate.pid`, including the
   server subdirectory's gate file. Never kill all Java/Python/game processes by name.
   Those PIDs include the client and bisect drivers, so a stop also skips their own restore of the player's
   `options.txt`. The stop does that restore itself: before its kill it notes whether the process that wrote
   `options.txt.forbric-sweep` is alive, and after the kill it puts the file back from that record (step 6).
4. Clean the explicit test-state children: `config`, `mods`, `saves`, `logs`,
   `.forbric-kernel`, `.mixin.out`, `.fabric`, `crash-reports`, `screenshots`,
   `server-gen`, `quickPlay`, `resourcepacks`, `defaultconfigs`, `.cache`,
   `.physics_mod_cache`, `replay_recordings`, and the three console logs. A recording a killed client left
   unfinished makes ReplayMod hold the title screen on its recovery prompt, and quick-play waits behind it
   (72 s in one Mac run). Preserve natives, `options.txt`,
   backup ZIPs, launcher metadata and PCL files. `mods-all` is refreshed only from the
   new pack and serves as the source for a later subset test.
   The kernel jar is built from the working tree (`./gradlew --offline jar`) before anything is staged, so the
   commit report.md names is the code that ran; `--no-build` stages `build/libs` as it is.
   Through a relayed tunnel that throttles or stalls (a UU Remote port forward stalls for minutes after tens of
   megabytes), pass `--remote-mods`: only the manifest crosses the transport and `win/fetch-mods.py` downloads each
   jar on the Windows side from its own URL, verifying size and SHA-1 against the manifest; an upload whose remote
   SHA-256 already matches is skipped either way. `COMPAT_CALL_TIMEOUT` (default 240) raises the per-call ceiling
   for such a tunnel; whether a stalled call may be repeated is the `WINSH`/`WINFILE` command's own decision.
5. `version-json-sync.py` updates SHA-1/size for every supplied `group:artifact` pair — the five
   push-and-run supplies, `net.forbric:vanilla-reference` among them. It keeps library order and all other
   metadata. A missing pair is an error.
   Upload the artifacts and refreshed profile, then the driver tools and mod archive.
   Windows-illegal jar characters are replaced with `_`; collisions fail before upload.
6. `win/run-server-test.py` drives `win/forbric-server.py` through world generation,
   ticks, save, and clean stop, then copies the save for the client. Both job drivers
   distinguish a process that is still working from one that has stopped: `--boot-timeout`
   (900s) and `--run-timeout` (1200s) are ceilings for the former, `--boot-stall` (120s,
   `BOOT_STALL`) and `--stall` (300s, `CLIENT_STALL`) bound the silence of the latter,
   measured from the last line it printed. The server's stall applies only before `Done` —
   after that the tick soak is quiet by design. Sixteen recorded sweeps put the largest
   silence of a boot that reached `Done` at 8 seconds, against 900 spent waiting on ones
   that never would; two such runs cost 820s and 1615s.
   The soak is `--tick-seconds` (60s, `TICK_SECONDS`), and every second of it now ticks: the
   properties written for the run set `pause-when-empty-seconds=0`, without which vanilla
   pauses a player-less server 60 seconds after `Done` and returns from `tickServer` before
   `tickCount++` and before `fireServerTickPre`. The old 90s soak was 60s of simulation and
   30s of a paused JVM. Keep that property whatever `--tick-seconds` becomes — a boot reaching
   `Done` is still not the claim this sweep makes, and a soak that is not ticking makes no
   claim at all. `win/prepare-world.py`
   sets only the copied test save's `Data/confirmedExperimentalSettings` byte to 1,
   acknowledging the carrier's experimental-world prompt without changing lifecycle,
   datapacks or terrain. The source server save is untouched; `--check` is read only. `win/run-client-test.py`
   drives `win/forbric-launch.py` into it, requests Minecraft's own screenshot at tick
   100, and requires a clean disconnect. Both launchers resolve the installed version
   JSON rather than a developer classpath. Vanilla runs quick-play only after its chain of first-run screens, so
   before the client starts `common.FIRST_RUN_SEEN` marks a mod's own first-run screen as already dismissed (today
   wover-ui's BetterX welcome) — the state of a player who has clicked through it once. The client also plays one
   language whoever runs it: `common.sweep_language` sets options.txt's `lang:` to `en_us` for the run.
   `push-and-run --client-lang` (default `en_us`, also for an empty `FORBRIC_LANG`) passes another code to the
   driver's `--lang`; `player` leaves the player's language as it is. report.md records the language the client
   played. The language decides which assets every mod loads, and the Windows profile's zh_cn is what killed
   sweep90-win-r7c: Axiom 6.1.3's bundled Dear ImGui keeps a pointer into font arrays the JVM may move, and its CJK
   fonts are big enough to trigger that GC. Native Fabric with only Axiom and fabric-api asserts the same way once a
   GC lands between the add and the build (forced, or under `-XX:+UseSerialGC -Xmn16m`); under default G1 that
   minimal native pack did not crash in the runs recorded. en_us narrows the race, it does not close it.
   `options.txt` is the player's own file, and Minecraft rewrites all of it while the client loads (it saves
   `startedCleanly:false` at startup and `true` only once loading finishes; a false one makes the player's next start
   reset its fullscreen mode). What the client and bisect drivers promise about it, in every `--lang`:
   - Before anything is written, the driver keeps `options.txt.forbric-sweep`: the player's file as it was (or that
     there was none), the `lang:` it wrote and the one it replaced, and its own pid and process start time.
   - The run ends, normally or with an exception: the player's bytes go back exactly, over the client's rewrite too,
     or the file the client wrote is removed when the player had none.
   - The stop (step 3) kills a running or loading client: the stop saw the record's writer alive before its kill,
     so after the kill it puts back the same exact bytes, or removes the file, as the driver would have.
   - The writer died any other way, a reboot above all: the next stop or client or bisect run finds a record whose
     writer is gone (pid and start time, so a pid Windows has handed on does not count). The player may have played
     since, so only the `lang:` line goes back (under `player` nothing does), and only while every `lang:` line
     still names the sweep's language; a line the sweep added is taken out. Everything else stays as the file has
     it, Minecraft's rewrite from the killed run included (`startedCleanly:false`, and whatever else it saved), and
     so does a file the client created where the player had none.
   - A second client or bisect run on the same instance refuses to start while the first one's writer is alive,
     naming its pid and the record, and touches nothing of options.txt.
   - A record that cannot be read is acted on by nobody: the driver, or the stop after its kill, fails naming the
     file to delete once `options.txt` has been checked by hand.
   - Not covered: anything else writing `options.txt` during a run (the player starting the same instance) is
     overwritten by the restore. Two copies of the stop at once (`winsh` re-sending one after a relay stall) can race
     each other, and the player then gets the language back but may keep the killed client's rewrite. A restore that
     itself fails (the file held open by something else) leaves the record, which is then undone like a reboot's.
   The stop's side is PowerShell; `common.note_sweep_writer` / `common.restore_after_stop` are its Python twin, and
   the tests run that twin. `win/common.py` owns shared arguments, PID recording, launch resolution, frame
   inspection, and F2 fallback.
7. Long jobs run with `Start-Process` (no stream redirection: the job writes its own
   logs, so it inherits nothing of the remote shell and the start returns at once)
   and a saved PID/status handle. Poll that same
   handle; an observation timeout is not a terminal job and never authorizes starting
   another copy. Re-inspect the handle after a connection interruption. Each remote
   command remains below 240 seconds even when the game takes tens of minutes.
8. Collect logs, fresh Minecraft PNGs, saved regions and `load-report.txt` into the
   run directory. `assert.sh` checks the common client observations; `ASSERT_EXTRA`
   may name a pack-specific Bash assertion file using `ck`/`abs`. Missing/empty logs fail.
   `frame-verdict.py` reports `DREW`, `BLACK`, or `UNSUPPORTED`; only `DREW` is success.
   Desktop/GDI captures cannot replace a Minecraft screenshot. `region-probe.py`
   reports chunks containing each needle, plus explicit unreadable lz4/custom/corrupt
   counts. `--dungeons` reports spawner/mossy-cobblestone co-occurrence, not an exact
   count of dungeon structures. Require readable chunks and at least one matching
   chunk; record every unreadable chunk.
   `world-parity.py` is not part of the sweep: gate-m31 runs it over two saved
   overworlds — one written by a pure-vanilla server, one by the kernel with zero mods —
   and it prints, per facet, how many common chunks differ. Only `differ biomes`,
   `differ structures` and `differ spawner_mobs` (the mob of spawners standing at the same
   position in both worlds) may be asserted on. Vanilla does not reproduce itself at the block
   level, so `differ blocks`, `differ heightmaps` and `differ block_entities` are evidence
   and nothing more, and so is `differ spawner_positions`: a mineshaft corridor's spawner goes
   to whichever chunk generated first. A comparison over two absent or half-generated worlds
   reports zero differences, so the `chunks:` and `full:` counts must be checked before any
   zero counts. `test_world_parity.py` checks which spawner differences count; gate-m0 runs it.
   `world-snapshot.py` is not part of the sweep either: gate-m58 (world upgrade) and gate-m59 (Forge to NeoForge
   build swap) export a save after a clean stop to a typed JSON snapshot (`export`, schema
   `forbric-world-snapshot/1`, frozen: level.dat, block states, biomes, block entities, chunk extras such as
   attachments, entities, players, stats, SavedData), compare two snapshots under named rules (`diff --rules
   upgrade|reopen`; every allowance is printed with its count, including the resave noise measured old -> old,
   `--ignore-namespace` for a mod that is meant to be gone, `--json` for the same result as
   `forbric-world-snapshot-diff/1`), change a copy of a save for a reverse control (`mutate`), and check that every
   registry name a snapshot refers to is in the registry lists a canary wrote (`census`). A rule is noise only once
   a run of the old line against itself has shown it. It reads saves through `world-parity.py`'s typed reader, the
   tree's one NBT parser; its docstring is the operating manual, and `test_world_snapshot.py` checks it, including
   against bytes the game wrote (`testdata/world-snapshot/`).
   `forge-swap-make-old.sh <out-dir>` writes the old side of the Forge to NeoForge build swap with
   `run/old-kernel.sh` (a world the pre-removal kernel wrote with the candidates' MinecraftForge builds, a
   singleplayer player pass, the old side's own A/A, and the fixture's MANIFEST); `forge-swap.py` holds the probe's
   console and drill lines, the player kit, the loader-key census and the per-key loss classification
   (`classify --forbric DIR --native DIR`); `native-controls.py swap` runs the native oracle and the Forbric open.
   A packedup backpack's contents live in its own files, which only a player opening the bag creates, so
   `ForgeSwapBagFill.java` (built by `forge-swap.py bag-agent`, a `-javaagent` on the old create, the native create
   via `swap --create-jvm=...`, and the old singleplayer client) runs packedup's own server-side open-and-insert, and
   `forge-swap.py bags` reads the saved inventories back. `run/gate-m59-forge-swap.sh` opens the frozen fixture on
   this tree and pins its losses with `forge-swap.py pin` against `canary/forge-swap/expected-losses.tsv` and the
   fixture's frozen native arm (D77: exactly the native-identical rows). Their docstrings are the operating manual;
   `test_forge_swap.py` and `test_native_controls.py` check them.
9. `push-and-run.py` implements the shell entry point's orchestration and writes
   `report.md` using `report-template.md`, with commit, manifest, phase results, log
   assertions, frame verdict, region evidence and named load-report failures. Retain
   failed baselines as evidence. Compare each field, including new DEGRADED reasons,
   with the corresponding baseline; a boot reaching Done is insufficient.

## Investigate a failure

Use `push-and-run.sh --label <label> --bisect <subset.txt>` to run one named subset
against the staged `mods-all` and world. `win/bisect.py` uses the same fresh-frame
classifier, never a quiet log as proof of a rendered client. Halve the suspect subset
and repeat to isolate the failure, keeping each subset and verdict. Preserve required
dependencies. Missing/unsupported screenshots are unproven, not a green subset.

`--quarantine <jar>` moves one explicitly named jar out of `mods`; record its actual
failure and the subset evidence. Do not hide quarantined jars when comparing totals.
Replacements must be labelled with their actual project and loader in the manifest.

## Gates and cadence

`gates-all.sh` discovers every `run/gate-m*.sh` and reports in numerical order, including
network and GUI gates. `--list` is the actual glob. Use repeated `--skip <script.sh>`
only when intentional; every skip prints a RESULT line.
Logs and one-line results go to `build/gates/`, with a `summary.txt`.

It runs several gates at once: `-j auto` (the default) sizes the pool from RAM and cores,
`-j 1` is the old strictly-sequential run, `--mem-budget MB` caps what the running set may
claim. `gates-parallel.py` does the scheduling and is where the reasoning lives. Each gate
declares itself in one line near its top:

    # GATE-PARALLEL: rundirs=server-kernel,canary mem=1500

`rundirs` names what the gate owns while it runs — two gates naming the same one are never
co-scheduled — and `mem` is what it costs. A gate WITHOUT that line runs alone, and the run
says so in the progress log; a new gate is slow rather than silently unsound. A gate whose
verdict is a timing adds the bare word `alone` (gate-m12's late-tick ceiling, and every gate
that calls `check_kept_up`): a neighbour's CPU would read as the server falling behind, so it
runs with no other gate, and `GatesAllTest` fails on such a gate that does not say `alone`.
Any other word on the line, or a line with none, stops the sweep before a gate runs; a note goes after ` # `.

A gate that wants a shared fixture to itself declares `clone=<dir>:<ENV_VAR>` instead, and the
scheduler points that variable at a private copy under `run/.gate-clones/`. Four gates want
`run/client-merged-pack`, and serialising them left the last two minutes of a sweep with one
gate in it; `cp -Rc` clones that 434 MB install in 0.17s and shares its blocks until written,
so the four copies cost no disk and no wait. On a filesystem without clones this falls back to
a reflink copy and then to a real one.

`-j auto` sizes the pool at one slot per two cores. **Cores, not memory, is the bound**: the
sweep peaks at 5.5 GB of game JVMs however wide it runs, but at `-j 7` on ten cores the gates
are starved enough that time-based assertions fail — `gate-m19` went red on `await_server`'s
"still alive 20s after announcing its stop", which is a real check for a leaked non-daemon
thread and is not to be relaxed to suit a scheduler. `-j 4` and `-j 5` are green.

Ports are per concurrent SLOT, not per gate: slot *i* gets `25700 + 10i`, and `GATE_PORT`,
`M12_PORT`…`M16_PORT`, `M28_PORT` and `M32_PORT` are exported to the gate from that block. A
`GATE_PORT` already in the environment becomes the base instead. This is not tidiness: every
gate that starts a server writes a port into `server.properties`, and the loser of a port race
prints `FAILED TO BIND TO PORT`, writes a crash report, and then still prints `Stopping server`
and `All dimensions are saved`. Measured on `gate-m1` with its port held by another process: both
shutdown checks passed, and the gate went red on "server reached Done" without a word about a
port, which reads as the kernel failing to boot. The old default, 25599, is also `gate-m12`'s
own `M12_PORT` default, which is exactly that collision.

So a lost port is now named. `lib.sh`'s `port_was_free` fails with the port when a server log
says it lost it. `await_server` runs it on every server it waits for; `gate-m12`…`m16`, which
give up on a server that never reaches Done before they ever wait for it, run it on that exit;
and the gates that start their server through `evidence.py` call it, or its Python equivalent,
on that server's log — `gate-m39` before `set -e` ends it on the failed run.

The knob list is hand-kept, and a gate reading a name it does not contain gets nothing: it keeps
its own literal, and the slot where that literal meets an exported one is the race above.
`M32_PORT` was missing, and `gate-m32-savedrop.sh`'s fallback was slot 10's `M16_PORT`. Slot 10
exists only from `-j 11` up — at least 22 cores and about 30 GB under `-j auto` — and then
`gate-m16` and `gate-m32` could take one port, and whichever lost it went red. `gate-m36`,
`gate-m37` and `gate-m38` wrote their port into `server.properties` as a literal, so a second
sweep started with its own `GATE_PORT` still met them there; they read `GATE_PORT` now.
`python3 run/compat/test_gate_ports.py` holds every gate to these rules: a `*_PORT` it reads from
the environment is one the scheduler exports; no gate writes a literal port; a gate that starts a
server calls the lost-port check; and a "never reached Done" exit calls it before it leaves. It
reads text, so it holds the call and that one shared exit, not every path through a gate.

`FORBRIC_JVM` is how a whole sweep is pointed at something every game JVM must carry -- a locale
(`FORBRIC_JVM="-Duser.language=zh -Duser.country=CN"`; no routine run uses one, see below), a
debugging property, a switch a red control turns off. A gate that sets it for a launch keeps the
caller's value first: `FORBRIC_JVM="${FORBRIC_JVM:-} -D..."` in the shell, `os.environ.get('FORBRIC_JVM',
'') + ' -D...'` in Python, `JAVA_TOOL_OPTIONS="${FORBRIC_JVM:-} ..."` for a JVM started from a resolved
command line (gate-m17). First, so what the gate sets on purpose still wins (the JVM keeps the last `-D`
of a name). Until 2026-11 most gates wrote their own value over it and a sweep "in Chinese" reached the
game JVMs of 16 of 60 gates. `python3 run/compat/test_gate_locale.py` (also run by gate-m0 step 6) holds
every gate, its Python heredocs and the `run/compat/*.py` drivers it names to that (the caller's value
first, not only present), and lists each launch that pins a language on purpose with the reason:
gate-m26's refusal-prompt boot and gate-m29's refusal boot, both English. A pin pins
both language switches the kernel reads -- `-Duser.language` (the load report) and
`-Dforbric.dialogLanguage` (DialogLang: the dependency dialog and a refused mod's finding, read before
the locale) -- the second by a tag DialogLang has a table for: `zh` names two tables and is ignored, so
a sweep that wants the dialog in Chinese adds `-Dforbric.dialogLanguage=zh_cn`.

Three things keep repeated sweeps cheap (2026-11-15; a development round had swept every gate five or six times,
several of them on one tree), and a fourth keeps a sweep from being run twice: nothing is run a second time in Chinese.

- **Longest first.** With more than one slot, `gates-parallel.py` starts gates by their last measured duration,
  longest first: `run/compat/gate-durations.tsv` is the checked-in prior, and each run's own timings
  (`<cache dir>/durations.json`) win over it. `-j 1` keeps numerical order; `summary.txt` is always numerical.
- **The verdict cache** (`run/compat/gate_cache.py`). A gate whose inputs are identical to a run that went GREEN
  is reported `GREEN (exit=0; cached: same inputs went GREEN at <time>)`, and its log in the results directory is
  that run's log. The key is every non-prose file git sees (each gate keys on its own script and the gate scripts
  it names, not the others'; a gate that says `# GATE-CACHE: tests` also on the test sources, every gate script and
  the prose outside `reports/`, which its tests read), every jar under `run/` outside the gates' own declared rundirs
  and outside `run/canary/` (the canary builders' output; their sources, and the ones they compile from the staged
  root, are keyed instead) and under the staged and game directories (by entry name, CRC-32 and size, so a re-zipped
  jar still hits), the inherited `FORBRIC_*`/`MC_*`/`M<n>_*`/`JAVA_*`/`LANG` environment and the launchers'
  unprefixed knobs (`BASE`, `NEO_RT`, `NATIVE_REF`, `VANILLA_JAR`, `VANILLA_JVM`, with the jars they name) minus
  ports and the scheduler's own knobs, and `java -version` of the `java` the launchers start.
  `python3 run/compat/test_gate_cache.py` fails on a gate that runs test sources without `# GATE-CACHE: tests`, on a
  fixture jar a gate stages from build/ outside the hashed roots, on a canary a gate stages without building it, on
  an environment variable a run/ script reads that the key does not, and on every way the key could miss a change it
  must see; gate-m0 runs it, with test_control_arm.py. It lives in git's common directory and is shared by
  every worktree; `FORBRIC_GATE_CACHE=<dir>` moves it, `=0` or `--no-cache` turns it off, and `--release` never
  reads it. It cannot see a hand-edited fixture world: run `--no-cache` after changing one.
- **Control arms are kept, not re-booted.** A run inside a gate whose inputs hold nothing of this tree -- gate-m31's
  pure-vanilla server -- is kept by lib.sh's `control_arm_dir` / `control_arm_store` under a key file listing every
  input (beside the verdicts, so every worktree shares it), stored only after it passed its own checks, and booted
  again under `FORBRIC_GATE_FRESH=1` (`--release`, `--no-cache`) or `FORBRIC_GATE_CACHE=0`.
  `python3 run/compat/test_control_arm.py` holds the key, the fresh switches and the whole-or-nothing store.
- **No Chinese run** (D88). Only the load report, merge report, crash analysis, the disabled-mods file, DialogLang's
  windows, findings and in-game compatibility screen, and the installer's two sentences follow the language (lane ZH,
  2026-11-15: a full zh sweep differed from en in no verdict), and the unit suite asserts each of them in zh_CN and
  English with the system locale deciding.

`gates-all.sh` also builds the jar and resolves the boot classpath once and exports them as `FORBRIC_PREBUILT_JAR`,
`FORBRIC_PREBUILT_DEPS` and `FORBRIC_PREBUILT_STAMP`; the launchers and `lib.sh` skip their own two Gradle runs
while the jar still has that size and mtime, so every gate of a sweep boots the same jar.

A gate's rundir must not be a fixture other worktrees share. A worktree links `client-merged-pack`,
`client-kernel`, `client-neo-pack`, `carry-on`, `modmenu` and `neoforge-builds` to the main
checkout's, and a gate that wiped or wrote one of them through a fixed or default rundir changed a
pack every other tree reads. `lib.sh`'s `refuse_shared_rundir "$RUNDIR"` fails (exit 3, a
`[kernel] FAIL refusing rundir` line) when the rundir is, lies inside, or resolves into one of those,
`client-popular` or `forge-swap`, or is spelled inside this tree's `run/` but leaves it through a
symlink. `forge-swap` is also refused at its own home outside every checkout (`$FORBRIC_FORGE_SWAP`,
default `~/Forbric-backups/forge-swap`), since a tree cut before it existed has no link to it; clone the fixture by its real path (`cp -c -R`) instead. `python3
run/compat/test_lib_guards.py` runs the real `lib.sh` in a throwaway tree laid out like a worktree
beside a main checkout and goes red when the guard is a no-op or matches names by prefix.

The script's output is exactly the RESULT lines, byte for byte the same as `summary.txt`.
The running commentary — what started when, on which slot and port, what each gate cost, and
what the run would have taken sequentially — goes to `build/gates/progress.log`; `--progress`
also mirrors it to stderr.

`gate-m25-worldgen.sh` checks that NeoForge's biome and structure modifiers leave the NeoForge canary's blocks in
saved overworld chunks (`M25_NO_DATA=1` is its red demonstration); its MinecraftForge half went in Stage 3 of
dropping MinecraftForge, when Forbric began refusing a MinecraftForge-only jar. `gate-m26-forgeclient.sh` checks that
the client event buses receive the game's real registration events and that a refused MinecraftForge mod is put to
the player; it stages a data-free copy of the NeoForge canary so adding a worldgen datapack cannot block
quick-play behind a new-pack confirmation; the source jar remains intact and m25 tests its data.
It also invokes `win/prepare-world.py` on the zero-mod test save to acknowledge the
carrier experimental-generation prompt before quick-play.
A gate that is red by declaration carries the header `EXPECTED: RED until ...`, exits 2 and prints an
`EXPECTED-RED` observation, which together distinguish a known failure from boot failures
or broken control assertions (exit 1); no gate carries that contract today. Remove it when its
implementation lands, as m26's was. `gate-m27-frame.sh` requires the 97-jar pack and a PNG newer
than the current launch. Gate headers document `M25_NO_DATA`, `M26_EXTRA_JVM`,
`M27_SHOT_TICKS`, and `M27_FRAME` negative controls.

`gate-m28-forgeconfig.sh` opens a fresh dedicated-server fixture, checks the NeoForge
canary's COMMON file, then changes the canary value from 11 to 73
while the server is running (its MinecraftForge canary went in Stage 3 of dropping MinecraftForge, MinecraftForge's
own `forge-common.toml` with the carrier in Stage 4). Only a new Reloading event within 40 seconds and a
matching file readback pass. `M28_EXTRA_JVM=-Dforbric.earlyConfigs=off` is its negative
control. `gate-m16-forge-handshake.sh` also checks that the NeoForge canary's client loads its CLIENT
config exactly once while the dedicated server creates no CLIENT file.

After each step, run `./gradlew --offline cleanTest test`, read the JUnit XML, and
deliberately break new behavior once to verify the test fails. After a workstream,
run its gate and negative controls plus every gate. Phase 0 ends with all gates and
Windows `popular-baseline` / `random-baseline`. Each later phase reruns the popular
set; Phase 2 and the final phase rerun the random set too. If an unrelated observation
regresses, isolate it with the frame-based subset test before the next phase.
# Bind validation to its inputs

Use `evidence.py run` for candidate acceptance commands. It hashes source contents (including uncommitted
and newly added source files), the supplied artifacts, and every top-level/nested mod archive before the
command; afterwards it verifies that none changed. The command log and JSON verdict are saved beside the
manifest. An exit-zero command whose inputs changed is a failure. This is provenance, not a substitute for
the command's own behavior assertions.

`python3 run/compat/test_evidence.py` checks the evidence recorder, including missing required inputs,
changed source/mod/archive bytes, and a nominally successful command that changes its own inputs.

```bash
python3 run/compat/evidence.py run --source .. \
  --artifact kernel=build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar \
  --mods run/client-merged-pack/mods --output build/evidence/client.json \
  -- bash forbric-kernel/run/gate-m9-client.sh
```

Run from `forbric-kernel/`; command paths are resolved from the recorded repository source root. Supply every
actual game/runtime/tool jar as a named `--artifact` in real acceptance runs. `--release` requires clean
committed sources, a mods directory (empty is valid for zero-mod tests), and all six release roles: `vanilla`,
`neo-patched` (NeoForge's patched game, the base), `neo-runtime` (its carrier), `kernel`, `kernel-runtime` and
`sidecar` (the vanilla-reference jar measured against the base). Missing inputs fail. Since Stage 4 of dropping
MinecraftForge there is no merged base, MinecraftForge carrier, interop jar or merge-tools jar to attest. Keep output
under ignored `build/` or outside the repo.

A release capture also fails unless:
- the versions read out of the artifacts are the pins (Minecraft `26.2` from each game jar's `version.json`,
  NeoForge `26.2.0.93` from the carrier's manifest);
- the base, the carrier and the sidecar that the kernel build compiles against, its bytecode tests read and the
  gates' launchers hand the kernel are byte-identical to the attested ones. The staged root resolves as
  `build.gradle`'s does: `$FORBRIC_OLD/run`, else `forbric-kernel/.dev/staged/run` when it holds the base, else
  `forbric-loader/run` (the old loader's stage, which `tools/dev.py prepare` never writes a sidecar into; a release
  that falls back to it fails here);
- the sidecar's `META-INF/forbric/vanilla-reference.properties` records the attested `neo-patched` and `vanilla`
  jars (their SHA-1s) as what it was measured against.
A release `run` refuses `--skip`, requires `gates-all.sh --release`, and records any `RESULT ... SKIP` or
`EXPECTED_RED` line as a failed command. `${file.jarVersion}` mod versions are resolved from the archive's own
`Implementation-Version` and kept beside the raw declaration.

`evidence.py release-check --manifest <run.json> ... --publish kernel=<jar> ...` passes only when every manifest
is a passed release run, all bind the same source and the same hash per role, and each published file is the
accepted one; a `--publish` role that is not a release role (`merge-tools`, `merged`, ...) is refused by name. The
installer's `releaseAssets` requires `-PreleaseEvidence=<run.json>[,...]` and runs it on the kernel jar it is about to
publish.

`native-controls.py prepare` installs the fixed native Fabric and NeoForge servers into `build/native-controls`
(`--family forge` also installs native MinecraftForge 26.2-65.0.1, which only the C5c swap oracle uses: Forbric loads
no MinecraftForge mod, so there is no MinecraftForge behaviour control). `build [--family fabric|neo]` compiles the
public-API canary of each family asked for, against that family's image only. `run --engine native` and
`run --engine forbric` (`--family fabric|neo`) execute the same jar hashes, seed and world actions, each in a fresh
owned instance, one server at a time on the caller's `GATE_PORT` (an OS ephemeral port without it).
`compare <native-result.json> <forbric-result.json>` rejects mismatched inputs before comparing behavior.
`NATIVE_CONTROL_CACHE` selects the read-only reference checkout; its default is the parent of `FORBRIC_OLD`,
or this checkout when that variable is absent. The Forbric arm boots `FORBRIC_OLD` when it is set, else the prepared
`forbric-kernel/.dev/staged`, and refuses with neither (never the cache's `forbric-loader`, whose carrier is NeoForge
26.2.0.88 and unstamped); `retention-control.py` does the same. All generated files remain under this kernel's `build/`.

`native-controls.py run-set --engine native|forbric --family fabric --mods JAR... [--ticks 200] [--timeout 900]
[--xmx 3G] [--level-type minecraft:normal] [--policy strict] [--keep]` boots one fresh server under `build/native-controls/instances/`
with exactly those jars (a normal world from the fixed seed, view and simulation distance 3, pause when empty off),
waits for `Done (` as `control-diff.sh` does, asks `time query gametime` until the game has advanced `--ticks`, then
saves and stops. The native arm is the image `prepare --family fabric` installed; the Forbric arm builds this
checkout's kernel jar first and runs `launch-kernel-server.sh` under the strict policy (a dedicated server has no
window, so the product default refuses the same way; `--policy continue` shows what a refused launch would have
done, as a diagnostic and never as the comparison). `results/<run>/result.json` records `outcome`, the
`modSetSha256` over the sorted jar SHA-256s (equal on both arms means they ran the same bytes), the kernel jar's
SHA-256 (Forbric) or the launcher's (native), the measured game time, `signature` (`mac/ddmin_core.signature` of a
failure), crash-report count, uncaught exceptions of other threads, and whether a non-daemon thread kept the JVM
alive after the server had stopped (`lingeredAfterStop`, killed and not a failure). The console is classified by
`server_outcome(log, ticks, exit_code)` alone: no `Done (` is `FAILED_TO_START` when the process ended on its own or
printed a start failure (`Failed to start the minecraft server`, an uncaught `main` exception, a Forbric policy
stop) and `STALL` when it had to be killed; after `Done (`, a crash report, the run loop's `Encountered an unexpected
exception`, an exception escaping the server or main thread, a failed stop or a JVM fatal error is `CRASH`, and so is
a JVM that went away by itself before the stop; it is `DONE` only when the ticks were reached and the stop was
acknowledged. A mod that logs a caught exception and keeps ticking is not a crash. `test_native_controls.py` pins
these on synthetic consoles. The instance is deleted after a `DONE` without `--keep`; the console, crash reports and
the kernel's reports stay in `results/<run>/`.

`fabric-ab.py --data <pick dir> --out <dir> pack|per-mod|confirm|ddmin|summary` runs a pure-Fabric pick (for example
`PICK_LOADER=fabric PICK_COUNT=130 PICK_SIDE=server mac/pick.py`, then `mac/closure.py`) through `run-set` on both arms.
It refuses a jar that is not the manifest's bytes or a closure that names a jar outside the manifest. `pack` runs
every jar together (`--subjects native-pass` only the subjects that ran alone on native Fabric, `matched-pass` those
that ran alone on both, each with its dependencies); `per-mod --jobs N` runs each subject with its `closure.json`
dependencies; `confirm` reruns, one at a time, each per-mod pair whose arms disagree; `ddmin [--subjects ...]`
minimises a pack's Forbric failure with `mac/ddmin_core.py` (oracle: the Forbric arm; FAIL: the pack session's
signature; seeds: that session's own evidence, where a named library or nested mod stands for the at most three
candidates that pull it in, since only candidates can be taken out) and then runs the minimal set on native Fabric, so the result says
`FORBRIC_ONLY` or `BOTH_FAIL` from a run rather than an argument. Every session is a line of `<out>/runs.jsonl`
keyed by engine, mod-set SHA-256, ticks and the engine's identity (the kernel jar's SHA-256 or the launcher's), so a
repeated or interrupted command runs only what it has not seen, and a session of another kernel is never reused.
A pair is `MATCHED_PASS`, `FORBRIC_ONLY`, `NATIVE_ONLY`, `BOTH_FAIL`, or `INPUT_MISMATCH` when its arms ran different
bytes. `summary [--report DIR]` writes `summary.json` with jar names, digests, outcomes and signatures and no local
path. `test_fabric_ab.py` runs all of it against a fake server: verdicts, the cache, kernel refusal, the minimiser
with a dependency, pack selection and the summary. The 2026-10-03 run is `reports/2026-10-03-pure-fabric-server/`.

`retention-control.py` requires the prepared native NeoForge image and the fixed Unlit Campfire jar in
the copied mixed pack. It compiles an independent canary, saves a real campfire and compares the untouched
mod's static cache after normal shutdown on native NeoForge and Forbric. Both arms and their exact mod
hashes must agree. This attributes one observed native retention issue; it does not clear another retained
root by itself. Evidence stays under `build/retention-control/`, and `native-retention.json` names the root
and exact jar hash it proved; a release M34 run re-reads that evidence before launching.

`ui-control.py` requires the built kernel, `FORBRIC_OLD` and `NEO_RT` (`BASE` optional, as for the launchers). It
creates a nonce-owned copy of the full mixed pack/world under `build/compat-ui/`, publishes late necessary findings,
clicks the actual native Continue button, then closes a second prompt. It requires preserved failure
evidence, initial refusal focus, normal save/return to title and two fresh game screenshots. Its deliberately
incompatible control scenario is separate from a strict compatibility acceptance run.

`gate-m39-transfer-core.sh` first runs the transfer engine suite (`transferTest`) as a required step: every
`@Test` declared under `src/transferTest` must appear in its XML report, none failed, errored or skipped, and a
missing game side fails instead of skipping. It then packs the watchdog canary (`src/transferTest`'s `WatchdogCanary`
with its `watchdog-canary.fabric.mod.json`) beside fabric-api and runs it on a strict kernel server: a real full watchdog
thread dump, and fabric-api's `ServerWatchdogMixin#printEntireThreadDump` reported RESOLVED (the final-defined
native-helper equivalence finding). Inputs are hash-bound. The fourteen MinecraftForge storage scenarios it ran on the
merged base went with the MinecraftForge carrier in Stage 4 of dropping MinecraftForge; their Fabric/NeoForge half is
the transfer engine suite and gate-m33/m40's world runs.

`gate-m40-energy.sh` checks block-entity energy between Team Reborn Energy (the Fabric energy API; Fabric API
has none) and NeoForge's `Capabilities.Energy.BLOCK`, through those public lookups only. It needs `energy-5.0.0.jar` (team_reborn_energy, MIT): `M40_REBORN_ENERGY`, default
`forbric-kernel/run/energy-api/energy-5.0.0.jar` beside the staged tree; the gate fails if it is missing and copies
it into its own world's mods. The canaries are built with `TRANSFER_CANARY_ENERGY=1` into `build/energy-canary/`,
never into `run/canary/`. Four hash-bound phases in the nonce-owned `run/server-energy-m40`: `prepare` (Fabric and
NeoForge: 4 routes, 40,000 E conserved, faces, native precedence, store limits, nested rollback across both
transaction engines, replacement including the cached NeoForge views of a Reborn cell, a Fabric addon's explicit
Reborn provider on a NeoForge block reached by the NeoForge consumer, one dirty mark per root commit, long/int
clamping), `reload` (amounts after a real save, the routes again), `noreborn` (the same pack without Reborn: the
NeoForge cell and consumer alone, no route, the probe's SKIP for the checks that need a second family, and the JVM's
class-load log must show the probe and the bridge's fallback loaded and no Reborn class) and `negative` (bridge off,
must fail at a foreign lookup). The gate clears `build/verification/m40-energy/` when it starts, so no check reads an
earlier run's file. 1 FE = 1 E. Item energy is not bridged. Evidence: `build/verification/m40-energy/`. MinecraftForge's
`ForgeCapabilities.ENERGY` (a Forge consumer, a Forge cell and battery) went with the MinecraftForge carrier in Stage 4
of dropping MinecraftForge.

The kernel game side compiles the energy bridge against the same jar (`-Pforbric.rebornEnergy=<jar>`, same default,
else this checkout's own `forbric-kernel/run/energy-api/energy-5.0.0.jar`). Nothing fetches it and `*.jar` is not
committed: take it from Team Reborn Energy's release (https://github.com/TechReborn/Energy, the project page in the
jar's own `fabric.mod.json`). `verifyRebornEnergy` runs before every game-side compile and transfer-test run and fails
naming any missing or incompatible public binary member. The contract is
`src/main/resources/net/forbric/kernel/interop/protocol/energy.api-contract.txt`; a release with the same ABI is
accepted regardless of its filename, version or archive hash. The runtime jar is checked to contain the energy
classes and to bundle no `team/reborn/` entry. M33 also requires that its item/fluid-only pack logs no energy
bridge activity.

`gate-m34-soak.sh` builds once, then `soak-run.py` freezes the exact boot/runtime/game jars, dependencies and
mod pack into a nonce-owned copy of the test world. Default acceptance requires at least 7,200 seconds of
occupied, advancing simulation, three normal same-JVM world sessions, all three dimensions and six chunks
observed unloading and reloading. Paused time cannot satisfy the requirement. Sources and snapshots must
remain unchanged; release runs require committed sources and strict compatibility policy. The original
world is never opened by the client. Retained retired servers produce REVIEW_REQUIRED, not a pass or an
unsupported claim of a leak. The one exception is differential: after measurement ends, the controller
removes only the entries of `native-retention.json` roots (present in this run with the registered jar hash)
that belong to its own stopped servers, then collects again. If every retired server is then gone, nothing
else held it and the acceptance records `nativeRetentionAttributed`; if any server survives, it is still a
review. Heap, thread and chunk samples and thread dumps remain in the run's evidence. A watchdog records a
FAIL result with a thread dump and halts the owned JVM when the client thread stays inside a native world
open or save-and-disconnect loop longer than the timeout, and a controller that cannot start stops the game.
Activity is independently verified even when retention requires review; releaseAccepted remains false and
the command remains nonzero. Release runs also require a fresh final strict compatibility report with zero
confirmed necessary losses and no unclassified failed initialization.

The client gets the base's vanilla-reference sidecar (`--nativeReference`, from `run/vanilla-reference-jar.sh` or
`--native-reference`), frozen with the other inputs; when none can be had the soak stops instead of launching, since
NeoForge's patched game embeds no vanilla index and a client without the sidecar has no FABRIC references at all.

Use `--control --seconds 30 --sessions 2 --dwell-ticks 20 --settle-seconds 10` only to test the controller;
CONTROL_PASS is never release acceptance. `python3 run/compat/test_soak.py` verifies rejection of stale or
incomplete telemetry, fake activity totals, missing reentry/unload observations and short release claims.

`corpse-repro.py` compiles two read-only Mixin probes and opens a nonce-owned copy of the mixed pack. It
requires Corpse's actual dummy constructor to complete with vanilla name-tag distance zero and its actual
render submission to run, then requires screenshots and normal save/exit. The unmodified mod jar remains
hash-bound. A crash marker terminates only this child process group within five seconds. Run the offline
CorpseNameTagAdapterTest before this client test; an off-adapter graphics run is unnecessary to reproduce
the known missing-field error because the actual original constructor is executed in the JVM test.

## macOS random sweep

`mac/pick.py <data-dir> <seed> <exclude-manifest> ...` selects up to 38 previously untested popular projects from the top 200
and fills the remaining places with random projects for the selected game version, then downloads and verifies required dependencies.
`PICK_COUNT` changes the 100 subjects (38 in 100 stay popular), `PICK_LOADER=fabric|neoforge|forge` takes every subject's
build for that loader and skips a project without one instead of substituting another ecosystem's build, and
`PICK_SIDE=server` keeps only projects whose Modrinth server side is required or optional and, for Fabric, whose own
`fabric.mod.json` does not declare `"environment": "client"`. Without them the selection is the earlier sweeps' for the
same seed. `mac/test_pick.py` checks the loader choice, the settings and a whole selection against a fake registry.
`mac/api.py` supplies registry requests; `mac/archive.py` reads declared nested dependencies recursively.
Set `PERMOD_DATA=<data-dir>` and run `mac/closure.py` to produce the per-subject transitive dependency sets.
`mac/per-mod.py` runs each subject separately; dependency libraries are not counted as subjects. Configure
`PERMOD_MC` to an isolated installed Minecraft root and `FORBRIC_VERSION` to its kernel profile (a single
kernel profile is detected automatically), `FORBRIC_JAVA` to Java 25+, and `PERMOD_OUT` to a new evidence
folder. `mac/mac-run.py` adapts the installed-profile client drivers to macOS and captures thread dumps
for owned Java processes that time out. `SWEEP_WORLD_TICKS` extends the standard world session for pack
validation. Evidence remains under the selected data directory. The current world/options fixtures come
from the preserved local `build/sweep80-mac` baseline; a missing fixture is a setup error, not a mod failure.

Run `python3 -m unittest discover -s run/compat/mac -p test_archive.py` to verify recursive dependency handling (`mac/test_archive.py`).

After all subjects finish, run `mac/mixed.py` with the same `PERMOD_MC`, `PERMOD_DATA`, `PERMOD_OUT`,
`FORBRIC_VERSION` and `FORBRIC_JAVA`. It checks kernel/input fingerprints, combines every strictly passing
subject and its dependencies, tests 6,000 world ticks, then reloads the saved world. Its full pack manifest,
reports and screenshots go to `mixed/`. `PERMOD_MIXED_OUT` and `PERMOD_MIXED_INSTANCE` select fresh
evidence/instance names for a later candidate. `--subjects all` combines every subject in `manifest.json` instead,
and needs no individual sweep at all: in place of the per-mod fingerprints it checks each jar's bytes against the
digest the manifest records (`sha256` when a row has one, else Modrinth's `sha1` and `size`). Either way
`result.json` names the mode and the SHA-256 of every jar, which must still match after the last session.
Each session's evidence also keeps `forbric-mods.txt`, and `.forbric-kernel/crash-analysis.txt` and
`merge-report.txt` when that session wrote them (a crash-analysis file is never counted as a crash report).
Importing `mixed.py` starts nothing and reads no environment: `run(label, ticks, subjects, out, jvm, stall,
timeout, grace)` is one session of the prepared instance with the subjects, extra JVM flags and the driver's
`CLIENT_STALL`/`RUN_TIMEOUT`/`GRACE` all explicit, which is how the minimiser below drives it.
`mac/test_mixed.py` runs it against a fake driver. Save verification uses `mac/world_save.py` for both save layouts: fresh level data, existing region data,
and a fresh player or region write. `mac/test_world_save.py` rejects copied or incomplete saves. The disposable mixed instance disables pause on lost focus. A partial or failed first load leaves reload explicitly NOT_RUN.
The runner clears only directories bearing its `.forbric-sweep-instance` marker. Use a new evidence folder
for a new candidate; unfinished or differently fingerprinted individual results cannot feed a mixed test.

`mac/dependency_selection.py` keeps already required API providers ahead of unrelated sampled hosts;
`mac/test_archive.py` also verifies this selection rule.

What a run says is decided in one place, `mac/sweep_verdict.py` (standard library only; it reads no file and no
environment): `classify_run` (PASS, CRASH, STALL, STALL_IN_WORLD, NO_WORLD, NOT_DRAWN, FAIL), `mod_status` (a jar's
worst row, its bundled rows included; ABSENT when the kernel never listed it), `subject_strict` (per-mod's strict
pass) and `pack_strict` (mixed's). per-mod.py, mixed.py and the minimiser below all read runs through it.
`mac/test_sweep_verdict.py` pins each outcome and compares every predicate, over input grids, with the code the two
scripts carried before it moved here.

## Minimise a failing pack

`mac/ddmin_core.py` is the game-free half of replacing hand bisection (halving a failing mixed pack, or
`--bisect` above) with delta debugging. It uses only the standard library and reads no environment or files.
An oracle maps a jar list to FAIL (the full pack's failure signature), PASS, or UNRESOLVED (it failed some other
way); only FAIL ever shrinks the set. `ddmin` is Zeller and Hildebrandt's ddmin2: subsets, then complements,
then double the granularity. `one_minimal` removes single jars until none can go. `closed` adds every
`closure.json` dependency to each run; dependencies are never minimised. `signature` names a failure by the
crash report's top exception, with timestamps, paths, Mixin handler prefixes, hex and digits removed and a
`Sources: a and b` list sorted; exit 78 is `POLICY_STOP:` plus the sorted keys of the confirmed required
findings. `seeds` turns a run's own evidence (the clash `Sources`, `crash-analysis.txt` suspects, jars in the
exception chain's frames, report rows that are not OK) into jars, mapping mod ids only through the report's
`mods[]` rows. `minimise` runs the closed seed set first and starts ddmin there when it FAILs; runs are
remembered by the closed configuration, so two subsets that launch the same jars run once. Nothing in it starts
a client. `mac/test_ddmin.py` checks it with fake oracles and the fixtures in `mac/testdata/`; `python3 tools/dev.py
tool-test` runs it with every other `mac/test_*.py`.

`mac/ddmin.py` is the half that runs the game: every configuration is one `mixed.run` session in a fresh
disposable instance, with the strict compatibility policy and short limits.

    PERMOD_DATA=<data-dir> PERMOD_MC=<isolated root> FORBRIC_VERSION=<profile> FORBRIC_JAVA=<java 25> \
      python3 run/compat/mac/ddmin.py --manifest <failing mixed run>/manifest.json [--out DIR] \
      [--ticks 200] [--stall 120] [--timeout 420] [--grace 20] [--jvm=-D...] [--budget 80] \
      [--no-seeds] [--iterate] [--narrow]

- **Pack.** The jars are the manifest's rows, each checked against the manifest's digest like `mixed.py --subjects
  all`; dependencies come from `PERMOD_DATA/closure.json`, which must hold every subject and no jar outside the pack.
  The candidates ddmin may take out are the subjects (`popular`, `random`) plus any jar no subject needs; every
  other jar only ever arrives through `closed`. The instance is `PERMOD_DATA/ddmin-inst` (`PERMOD_DDMIN_INSTANCE`),
  prepared like per-mod's, with pause on lost focus off.
- **Reference.** The whole pack is run first, with the same flags and limits as every later session, and that
  session defines the failure: its signature (`ddmin_core.signature`; when no exception names it, the session's
  outcome is added, e.g. `STALL EXIT:None` or `PASS EXIT:0 bad:mod=DEGRADED`, so a stall and a degraded mod stay
  different failures) and its arbitration. A reference that passes strictly is `PASSED` (nothing to minimise). A
  failure that needs more than 200 world ticks to appear needs `--ticks`.
- **Verdicts.** A session is PASS when `sweep_verdict.pack_strict` passes, FAIL when its signature is the
  reference's, UNRESOLVED otherwise — and UNRESOLVED whatever it printed when its arbitration differs from the
  reference's: a mod id loaded from another jar, ecosystem or version (`compatibility-report.json` rows), an id the
  reference did not load, or a different `forbric-mods.txt` choice. Duplicate builds make that happen: a subset
  holding only `sodium-fabric` runs Fabric Sodium where the pack ran NeoForge Sodium, which is a different program.
  `ddmin-result.json` records the reference's choices (`arbitration`: id, loader and copy); `merge-report.txt` is
  kept as evidence but not compared, since it is written in the system language. When subsets hold both copies but
  arbitration would choose the other one, `--jvm=-Dforbric.modOwner=<id>=<loader>` pins the reference's choice for
  every session; a subset holding only the other copy stays UNRESOLVED.
- **Seeds.** The reference's own evidence (`ddmin_core.seeds`) is tried first; `--no-seeds` starts from the whole
  pack.
- **Cache.** `<out>/ddmin/cache.jsonl` (out defaults to `PERMOD_DATA`) keeps every finished session keyed by the
  installed kernel's SHA-256, the sorted SHA-256 of its jars, its JVM flags and the world ticks (which reach the game
  as a JVM flag), so an interrupted or repeated minimisation launches only what it has not seen and the verdicts are
  recomputed against each round's reference. A cache written by another kernel is refused; so is a kernel that
  changes between or during sessions, and a jar whose bytes change before the result is written. Evidence for each
  session is `<out>/ddmin/runs/<nnn>-<key>/` (mixed's evidence, crash-analysis.txt included).
- **Budget.** `--budget` caps the game launches of one invocation (cache hits are free). Running out gives
  `BUDGET` with the smallest failing configuration seen so far.
- **`--iterate`.** After a round is `MINIMISED`, its minimal jars leave the candidates and the rest is run as the
  next round's reference: its failure, whatever it is, is minimised next, against that round's own arbitration.
  Rounds stop when a reference passes, the candidates run out, or a round does not finish. A minimal jar that other
  candidates need comes back with them, so the next round may name one of its dependents.
- **`--narrow`.** The closed minimal set is narrowed further, by ddmin over what stays enabled: first the mixin
  configs (every other one goes into `-Dforbric.disableMixinConfigs`), then the mixin classes of those configs as
  `config:Entry` (the rest into `-Dforbric.suppressMixins`). Configs are read as each loader declares them
  (`fabric.mod.json` `mixins`, `[[mixins]]` in either `mods.toml`, a manifest's `MixinConfigs`), in nested jars too;
  a server-only config or a config's `server` list is left out. With every config off first: a failure that survives
  that needs none of them, and the result says so. `--narrow` owns those two properties; passing either in `--jvm`
  is refused.

`<out>/ddmin/ddmin-result.json` has the overall `status` (`MINIMISED`, `PASSED`, `BUDGET` or `NOT_REPRODUCED`;
exit 0 for the first two), the kernel, manifest and closure SHA-256, the settings, launches and cache hits, and per
round the reference, the seeds, every session with its verdict and signature (and winner differences), and
`minimal` (the candidates) with `closed` (what to install to see the failure).

Known answer, for the owner to run on the Mac (it needs the installed profile; nothing in CI or the tool tests
launches a game): the 2026-10-01 sweep100 mixed pack (`reports/2026-10-01-sweep100/mixed-manifest.json`, 88
subjects, 109 jars, with that sweep's data directory, whose `closure.json` is the one committed beside it) must
reduce to `minimal` = {`chloride-NEOFORGE-mc26.2-v1.8.1.jar`, `cwb-4.1.0+26.2.jar`} and `closed` = those plus
`sodium-neoforge-0.9.2+mc26.2.jar`, the pair Sodium refuses with `Multiple overrides for option
'sodium:general.fullscreen_mode'! Sources: chloride and cwb`:

    PERMOD_DATA=build/sweep100-mac-network PERMOD_MC=<isolated root> FORBRIC_VERSION=<profile> \
      FORBRIC_JAVA=<java 25> python3 run/compat/mac/ddmin.py \
      --manifest run/compat/reports/2026-10-01-sweep100/mixed-manifest.json --out build/sweep100-ddmin

Against the fake game below that takes four launches with seeds (the pack, the seed pair with Sodium, each of the
pair alone with its closure) and 30 with `--no-seeds`. On the game the count also depends on which jars the
reference's evidence seeds (a subject whose row is not OK is seeded too). Both mods are in every configuration that
FAILs, since only a session with both loaded can carry a signature that names both. It has not been run on the game
yet.
`mac/test_ddmin_driver.py` runs exactly this pack and closure, plus the cache, kernel refusal, arbitration, budget,
`--iterate`, `--narrow` and command-line wiring, against a fake game behind `mixed.run` (no game files).
