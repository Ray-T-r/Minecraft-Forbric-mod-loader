# Compatibility verification

The current compatibility plan has four implementation phases after this tooling phase.
Keep the same popular and random sets for each before/after comparison. A successful boot
alone does not prove that a mod's features ran.

## Inputs and transport

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
`field-drift.py` compares vanilla and merged field descriptors and reports affected
guest jars. `fapi-usage.py` scans class references, including `META-INF/jars`, to prove
that candidates actually call a named set of symbols — by default the Fabric loot/model
surfaces, and with `--preset` / `--symbols` any other set, which is how "who is actually
waiting on this Forge event" gets answered from bytecode instead of from `javap` notes in
a javadoc. `--list-presets` prints the named sets. `hook-worklist.sh` goes the other way round: it
censuses which of a carrier's event hooks the merged base still calls, then joins the dead
ones to the jars in a mods directory that name them, so the list is ordered by how many
mods notice rather than by whatever order the byte-merge produced. `repair-drift.sh`
replays the compat transformer's claim ledger against a CANDIDATE game build and names
the repairs that would stop applying, which is the question a carrier bump asks and that
nothing answered before a player did. `control-diff.sh` answers the question that has
been settled by argument until now — is this symptom Forbric's — by booting the same
Fabric mods on a native Fabric server and on Forbric and saying which log it appears in:
FORBRIC-ONLY, BOTH, NATIVE-ONLY or NEITHER, and INCONCLUSIVE when an arm did not start.
Run each with `--help` for its argument list. API usage must be proved from the downloaded candidate
jar before treating the selection as final; metadata resolution alone cannot prove it.

## Run and collect

1. Build the four staged artifacts and canaries. Do not run a gate while a Windows
   sweep is using those artifacts; gates rebuild the kernel jar in place.
2. Use `push-and-run.sh --label <label> --mods <mods-dir> --manifest <manifest.json>`.
   Preview with `--dry-run --version-json <installed-profile.json>`. The dry run names
   all four artifact uploads, sanitized mod names, PID stop, cleanup, and evidence.
3. Stop only PIDs read from `.forbric-sweep.pid` / `.forbric-gate.pid`, including the
   server subdirectory's gate file. Never kill all Java/Python/game processes by name.
4. Clean the explicit test-state children: `config`, `mods`, `saves`, `logs`,
   `.forbric-kernel`, `.mixin.out`, `.fabric`, `crash-reports`, `screenshots`,
   `server-gen`, `quickPlay`, `resourcepacks`, `defaultconfigs`, `.cache`,
   `.physics_mod_cache`, and the three console logs. Preserve natives, `options.txt`,
   backup ZIPs, launcher metadata and PCL files. `mods-all` is refreshed only from the
   new pack and serves as the source for a later subset test.
5. `version-json-sync.py` updates SHA-1/size for exactly four supplied `group:artifact`
   pairs. It keeps library order and all other metadata. A missing pair is an error.
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
   JSON rather than a developer classpath. `win/common.py` owns shared arguments,
   PID recording, launch resolution, frame inspection, and F2 fallback.
7. Long jobs run with `Start-Process` and a saved PID/status handle. Poll that same
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
   and it prints, per facet, how many common chunks differ. Only `differ biomes` and
   `differ structures` may be asserted on. Vanilla does not reproduce itself at the block
   level, so `differ blocks`, `differ heightmaps` and `differ block_entities` are evidence
   and nothing more. A comparison over two absent or half-generated worlds reports zero
   differences, so the `chunks:` and `full:` counts must be checked before any zero counts.
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
says so in the progress log; a new gate is slow rather than silently unsound.

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
`M12_PORT`…`M16_PORT` and `M28_PORT` are exported to the gate from that block. A `GATE_PORT`
already in the environment becomes the base instead. This is not tidiness: eighteen gates
write `GATE_PORT` into `server.properties`, and the loser of a port race prints `FAILED TO
BIND TO PORT` and then still prints `Stopping server` — so the clean-shutdown assertion
passes and the gate reads green over a server that never started. The old default, 25599,
is also `gate-m12`'s own `M12_PORT` default, which is exactly that collision.

The script's output is exactly the RESULT lines, byte for byte the same as `summary.txt`.
The running commentary — what started when, on which slot and port, what each gate cost, and
what the run would have taken sequentially — goes to `build/gates/progress.log`; `--progress`
also mirrors it to stderr.

`gate-m25-worldgen.sh` still deliberately exposes the missing MinecraftForge biome-modifier
mechanism (its Forge half is expected red until workstream D). `gate-m26-forgeclient.sh` was
born expected red and turned green with Phase 1 A; it stages a data-free copy of its canary so adding a worldgen datapack cannot block
quick-play behind a new-pack confirmation; the source jar remains intact and m25 tests its data.
It also invokes `win/prepare-world.py` on the zero-mod test save to acknowledge the
carrier experimental-generation prompt before quick-play.
Header `EXPECTED: RED until ...`, exit code 2, and an
`EXPECTED-RED` observation together distinguish a known failure from boot failures
or broken control assertions (exit 1). Remove the expected-red contract when its
implementation lands, as m26's was. `gate-m27-frame.sh` requires the 97-jar pack and a PNG newer
than the current launch. Gate headers document `M25_NO_DATA`, `M26_EXTRA_JVM`,
`M27_SHOT_TICKS`, and `M27_FRAME` negative controls.

`gate-m28-forgeconfig.sh` opens a fresh dedicated-server fixture, checks both the
canary and Forge's own COMMON files, then changes the canary value from 11 to 73
while the server is running. Only a new Reloading event within 40 seconds and a
matching file readback pass. `M28_EXTRA_JVM=-Dforbric.earlyConfigs=off` is its negative
control. `gate-m16-forge-handshake.sh` also checks that the client loads its CLIENT
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
committed sources, a mods directory (empty is valid for zero-mod tests), and all of: `vanilla`,
`forge-patched`, `neo-patched`, `merged`, `forge-runtime`, `neo-runtime`, `forge-interop`, `kernel`,
`kernel-runtime`, `merge-tools`. Missing inputs fail. Keep output under ignored `build/` or outside the repo.

A release capture also fails unless:
- the versions read out of the artifacts are the pins (Minecraft `26.2` from each game jar's `version.json`,
  MinecraftForge `26.2-65.0.1` and NeoForge `26.2.0.88` from the carriers' manifests);
- the merged base and both runtimes the kernel build compiles against and its bytecode tests read
  (`$FORBRIC_OLD/run/...`, else `forbric-loader/run/...`) are byte-identical to the attested ones;
- `<merged>.provenance.json`, written by `build-merged-base.sh`, names the attested inputs and outputs, an
  enforced link check, and merge-tool sources identical to the attested commit.
A release `run` refuses `--skip`, requires `gates-all.sh --release`, and records any `RESULT ... SKIP` or
`EXPECTED_RED` line as a failed command. `${file.jarVersion}` mod versions are resolved from the archive's own
`Implementation-Version` and kept beside the raw declaration.

`evidence.py release-check --manifest <run.json> ... --publish kernel=<jar> ...` passes only when every manifest
is a passed release run, all bind the same source and the same hash per role, and each published file is the
accepted one. The installer's `releaseAssets` requires `-PreleaseEvidence=<run.json>[,...]` and runs it on the
kernel and merge-tools jars it is about to publish.

`native-controls.py prepare` installs the fixed native Fabric, Forge and NeoForge servers into
`build/native-controls`; `build` compiles one public-API canary per ecosystem. `run --engine native` and
`run --engine forbric` execute the same jar hashes, seed and world actions, each in a fresh owned instance.
`compare <native-result.json> <forbric-result.json>` rejects mismatched inputs before comparing behavior.
`NATIVE_CONTROL_CACHE` selects the read-only reference checkout; its default is the parent of `FORBRIC_OLD`,
or this checkout when that variable is absent. All generated files remain under this kernel's `build/`.

`retention-control.py` requires the prepared native NeoForge image and the fixed Unlit Campfire jar in
the copied mixed pack. It compiles an independent canary, saves a real campfire and compares the untouched
mod's static cache after normal shutdown on native NeoForge and Forbric. Both arms and their exact mod
hashes must agree. This attributes one observed native retention issue; it does not clear another retained
root by itself. Evidence stays under `build/retention-control/`, and `native-retention.json` names the root
and exact jar hash it proved; a release M34 run re-reads that evidence before launching.

`ui-control.py` requires the built kernel, `FORBRIC_OLD`, `MERGED`, `FORGE_RT` and `NEO_RT`. It creates a
nonce-owned copy of the full mixed pack/world under `build/compat-ui/`, publishes late necessary findings,
clicks the actual native Continue button, then closes a second prompt. It requires preserved failure
evidence, initial refusal focus, normal save/return to title and two fresh game screenshots. Its deliberately
incompatible control scenario is separate from a strict compatibility acceptance run.

`gate-m39-transfer-core.sh` first runs the transfer engine suite (`transferTest`) as a required step: every
`@Test` declared under `src/transferTest` must appear in its XML report, none failed, errored or skipped, and a
missing game side fails instead of skipping. It then packages the existing real-carrier transfer scenarios as a
game canary. It
requires all fourteen Forge snapshot/alias/metadata/facade cases (including a dying endpoint) and a real full watchdog thread dump, including
the final-defined native-helper equivalence finding. Inputs are hash-bound and its server remains strict.

`gate-m40-energy.sh` checks block-entity energy between Team Reborn Energy (the Fabric energy API; Fabric API
has none), NeoForge's `Capabilities.Energy.BLOCK` and MinecraftForge's `ForgeCapabilities.ENERGY`, through those
public lookups only. It needs `energy-5.0.0.jar` (team_reborn_energy, MIT): `M40_REBORN_ENERGY`, default
`forbric-kernel/run/energy-api/energy-5.0.0.jar` beside the staged tree; the gate fails if it is missing and copies
it into its own world's mods. The canaries are built with `TRANSFER_CANARY_ENERGY=1` into `build/energy-canary/`,
never into `run/canary/`. Four hash-bound phases in the nonce-owned `run/server-energy-m40`: `prepare` (all three
ecosystems: 12 routes, 60,000 E conserved, faces, native precedence, a refused custom Forge store reported once, store
limits, nested rollback, replacement including the cached NeoForge/Forge views of a Reborn cell, a Fabric addon's
explicit Reborn provider on a NeoForge block reached by NeoForge and Forge consumers, a Forge battery loaded at
1,500/1,000 E that moves nothing on bridged insertion and keeps its energy, one dirty mark per root commit, long/int
clamping), `reload` (amounts after a real save; the overfull battery refuses again after the restart, then drains
into its bounds), `noreborn` (the same pack without Reborn: 4 Forge <-> NeoForge routes, the overfull battery for
NeoForge, and the JVM's class-load log must show no Reborn class) and `negative` (bridge off, must fail at a foreign
lookup). 1 FE = 1 E. Item energy is not bridged. Evidence: `build/verification/m40-energy/`.

The kernel game side compiles the energy bridge against the same jar (`-Pforbric.rebornEnergy=<jar>`, same default,
else this checkout's own `forbric-kernel/run/energy-api/energy-5.0.0.jar`). Nothing fetches it and `*.jar` is not
committed: take it from Team Reborn Energy's release (https://github.com/TechReborn/Energy, the project page in the
jar's own `fabric.mod.json`). `verifyRebornEnergy` runs before every game-side compile and transfer-test run and fails
naming the file when it is missing or when its SHA-256 is not
`889afc438d3e4add5cfdac76517da7987a2c495e4731690a56f2c5dee775db59`; the runtime jar is checked to contain the energy
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

Use `--control --seconds 30 --sessions 2 --dwell-ticks 20 --settle-seconds 10` only to test the controller;
CONTROL_PASS is never release acceptance. `python3 run/compat/test_soak.py` verifies rejection of stale or
incomplete telemetry, fake activity totals, missing reentry/unload observations and short release claims.

`corpse-repro.py` compiles two read-only Mixin probes and opens a nonce-owned copy of the mixed pack. It
requires Corpse's actual dummy constructor to complete with vanilla name-tag distance zero and its actual
render submission to run, then requires screenshots and normal save/exit. The unmodified mod jar remains
hash-bound. A crash marker terminates only this child process group within five seconds. Run the offline
CorpseNameTagAdapterTest before this client test; an off-adapter graphics run is unnecessary to reproduce
the known missing-field error because the actual original constructor is executed in the JVM test.
