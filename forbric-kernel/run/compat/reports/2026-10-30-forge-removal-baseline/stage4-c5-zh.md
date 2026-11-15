# Stage 4 C5 — lane ZH (wave 2c): a sweep run in Chinese reaches every gate's game JVMs

Branch `s4-c5-zh` from the stage tip `2c231c9c`. Evidence: `~/Forbric-backups/stage4-notes/c5/zh/w2c/`.

## The defect

VERIFY run 1 (block 3, `c5/verify/block3/`) ran `gates-all.sh -j 4` with `FORBRIC_JVM="-Duser.language=zh -Duser.country=CN"`
exported and a JVM sampler. Only 16 of the 60 gates it ran had a game JVM with zh on its command line; 43 never did, and m29
only ran its own en/zh boots. The chain is: gates-parallel.py copies the caller's environment into every gate, and
launch-kernel-server.sh / launch-kernel-client.sh / old-kernel.sh put `${FORBRIC_JVM}` on the java line. It broke in the gates:

- 38 gate scripts set `FORBRIC_JVM="-D..."` (or `"${Mxx_EXTRA_JVM:-}"`, `"$jvm"`, `"${*:-}"`) over the caller's value;
- the Python heredocs of gate-m36, m37 and m38 and four drivers gates run (`regress-gate.py` m60, `fluid-head-gate.py` m61,
  `carpet-gate.py` m62, `breath-drown.py`) built the launcher's environment with their own `FORBRIC_JVM`;
- gate-m59 handed `forge-swap.py client` an `--env "FORBRIC_JVM=..."` of its own (its NeoForge boots, through
  `native-controls.py`, already appended);
- gate-m17 starts the installed profile's resolved java line, which reads no `FORBRIC_JVM`, with `JAVA_TOOL_OPTIONS` of its own
  (and a `FORBRIC_JVM=""` beside it that did nothing).

So "gates-all in zh" tested English for 43 gates and said nothing about it.

## The fix (`53e5d1d9`)

Every one of those keeps the caller's value, first: `FORBRIC_JVM="${FORBRIC_JVM:-} -D..."`, in Python
`os.environ.get('FORBRIC_JVM', '') + ' -D...'`, and for gate-m17 `JAVA_TOOL_OPTIONS="${FORBRIC_JVM:-} ..."` (the no-op
`FORBRIC_JVM=""` goes). First, so a property a gate sets on purpose still wins (the JVM keeps the last `-D` of a name).
With an empty caller value nothing changes, so an English run is the run it was. `GateForgeConfigContractTest` pins
gate-m28's launch line and follows it.

Gates that pin the language on purpose (they keep their pin, after the caller's value, so the pin wins):

| gate | pin | why |
|---|---|---|
| gate-m26-forgeclient | en on the refusal-prompt boot | it asserts the English prompt text; its other two boots take the caller's locale |
| gate-m29-forgecaps | en on boot 1, zh on boot 2 | boot 1 asserts the English load report, boot 2 the Chinese one; its control boot takes the caller's locale |

JVMs that do not read `FORBRIC_JVM` and are left alone: gate-m13's Paper server, gate-m14's native Fabric server, gate-m31's
vanilla arm (`VANILLA_JVM`), the installer and NFRT/link-check tools gate-m17 runs, the `VanillaReference.java` source launch
of gate-m58's old-kernel build. They are the reference arm or tools, not Forbric game JVMs. The kernel's own dependency-dialog
child JVM (`DependencyDialogMain`, gate-m20) inherits the OS locale, not `-Duser.language` (DependencyDialog.fork forwards only
`-Dforbric.dialogLanguage`, by design: a player's OS locale reaches the child). A `-Duser.language`-only sweep therefore shows
that dialog in the host's language; adding `-Dforbric.dialogLanguage=zh` to the sweep's `FORBRIC_JVM` reaches it. Not changed.

## The check (`5a512c40`)

`run/compat/test_gate_locale.py`, run by gate-m0 step 6 beside the evidence/soak tools' tests and documented in PROTOCOL.md.
It reads the gates through gates-parallel.py's `Gate` class (like `test_gate_ports.py`) and fails when

1. a gate sets `FORBRIC_JVM` or `JAVA_TOOL_OPTIONS` in the shell without `${FORBRIC_JVM` / `$FORBRIC_JVM` in the value
   (continuation lines joined; an `--env "FORBRIC_JVM=..."` string included), or unsets it, `env -u FORBRIC_JVM`, `env -i`;
2. a Python heredoc in a gate, or a `run/compat/*.py` driver a gate names (closed over the drivers those name; tests
   excluded), sets `FORBRIC_JVM` (keyword, dict key, subscript, an `"FORBRIC_JVM=..."` string) from an expression that does not
   read the caller's (`x.get('FORBRIC_JVM')`, `getenv`, `x['FORBRIC_JVM']`), or pops or deletes it (read with `ast`);
3. a gate pins `-Duser.language` without a row in its `PINS` table (the two above, with their reasons).

It also checks the walk is not vacuous (>50 gates, the five named drivers found, >60 `FORBRIC_JVM` settings seen), and five
control tests show each rule firing on a gate that breaks it and quiet on one that does not. Limit (stated in its docstring):
text cannot follow an environment built from scratch or a value read into a variable first; the rules hold the assignments,
which is where all 43 losses were.

- **Red on the old tree**: the test copied into an export of `2c231c9c` fails exactly one test,
  `test_every_gate_hands_the_caller_its_jvm_options`, with 58 findings in 43 gates and 4 drivers
  (`c5/zh/w2c/red-control/test_gate_locale-at-2c231c9c.txt`, `violations-at-2c231c9c.txt`). They cover all 43 gates VERIFY's
  sampler saw run without zh (40 in the gate, m60–m62 through their drivers) and three it counted as zh because another JVM
  of theirs had it: m29's control boot, m31's `M31_UNFIXED` arm, m59's client.
- **Green on the tip**: 8/8 (`red-control/test_gate_locale-at-tip.txt`); all run/compat tests `unittest discover` 245 OK
  (237 at 2c231c9c + 8).

## Measurement

Full suite at `5a512c40` (pinned harness, `heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all
jar cleanTest test --continue`): **5873 tests / 0 skipped / 5 failed = failures-w2b.txt name for name, 0 new, 0 gone**
(`c5/zh/w2c/suite/`). No new JUnit test, so no TSV row (D47).

gates-all once in zh at `5a512c40` (`c5/zh/w2c/gates-all-zh.sh`, VERIFY block 3's script with: worktree s4c5-zh,
`GATE_PORT=46660` and `-j 2` so every slot stays inside this lane's block 46660–46679, `--skip gate-m34-soak.sh` (D84), and a
sampler that records for each game JVM the gate that started it (parent chain, while it lives) and the language it actually
gets: the LAST `-Duser.language` on its command line, or `JAVA_TOOL_OPTIONS` from its environment). Wall 55.8 min.
**60 run: 59 GREEN, gate-m0 RED only for D58** (unit tests: 3 of 5873 skipped, the LiquidBounce fixtures; its C6 SKIP), m34
SKIP. Tool tests in m0 step 6: 64 ran OK (the 8 new ones included).

Coverage: **every gate's Forbric game JVMs ran in zh**, except the deliberate pins (m26's prompt boot en, m29's boot 1 en).
Counts are `^[kernel] PASS/FAIL/SKIP` lines; "en" is VERIFY block 2 (`gates-all -j 4`, en, at 2c231c9c; the gate changes here
are no-ops for an empty caller value). `none` = a non-Forbric JVM (Paper, native Fabric server, the dependency-dialog child,
installer/NFRT tools, a source launch).

| gate | zh P/F/S | en P/F/S | game JVMs by the language they got |
|---|---|---|---|
| gate-m0 | 13/1/1 | 13/1/1 | zh=1 |
| gate-m1 | 19/0/0 | 19/0/0 | zh=1 |
| gate-m2 | 29/0/0 | 29/0/0 | zh=1 |
| gate-m2b | 38/0/0 | 38/0/0 | zh=1 |
| gate-m3 | 13/0/0 | 12/1/0 | zh=1 |
| gate-m4 | 33/0/0 | 33/0/0 | zh=1 |
| gate-m4-canary | 22/0/0 | 22/0/0 | zh=1 |
| gate-m7-neo | 28/0/0 | 28/0/0 | zh=1 |
| gate-m8-packmeta | 20/0/0 | 20/0/0 | zh=1 |
| gate-m9-client | 163/0/0 | 163/0/0 | zh=2 |
| gate-m10-overlay | 24/0/0 | 24/0/0 | zh=1 |
| gate-m11-serverconfig | 14/0/0 | 14/0/0 | zh=1 |
| gate-m12-multiplayer | 44/0/0 | 44/0/0 | zh=2 |
| gate-m13-anticheat | 30/0/0 | 30/0/0 | none=1, zh=1 |
| gate-m14-fabric-server | 34/0/0 | 34/0/0 | none=1, zh=1 |
| gate-m15-forge-network | 22/0/0 | 22/0/0 | zh=2 |
| gate-m16-forge-handshake | 39/0/0 | 39/0/0 | zh=2 |
| gate-m17-installer | 49/0/0 | 49/0/0 | none=5, zh(JAVA_TOOL_OPTIONS)=1 |
| gate-m18-presence | 20/0/0 | 20/0/0 | zh=2 |
| gate-m19-nesteddupe | 40/0/0 | 40/0/0 | zh=7 |
| gate-m20-depdialog | 41/0/0 | 41/0/0 | none=1, zh=6 |
| gate-m21-forgesetup | 54/0/0 | 54/0/0 | zh=1 |
| gate-m22-exitswap | 5/0/0 | 5/0/0 | zh=1 |
| gate-m23-elytra | 7/0/0 | 7/0/0 | zh=1 |
| gate-m24-brokenmod | 30/0/0 | 30/0/0 | zh=3 |
| gate-m24b-badmetadata | 23/0/0 | 23/0/0 | zh=2 |
| gate-m24c-disabled | 25/0/0 | 25/0/0 | zh=2 |
| gate-m25-worldgen | 16/0/0 | 16/0/0 | zh=1 |
| gate-m26-forgeclient | 49/0/0 | 49/0/0 | en=1, zh=2 |
| gate-m27-frame | 7/0/0 | 7/0/0 | zh=1 |
| gate-m28-forgeconfig | 15/0/0 | 15/0/0 | zh=1 |
| gate-m29-forgecaps | 38/0/0 | 38/0/0 | en=1, zh=2 |
| gate-m30-attribution | 28/0/0 | 28/0/0 | zh=2 |
| gate-m31-vanilla-parity | 13/0/0 | 13/0/0 | zh=1 |
| gate-m32-savedrop | 10/0/0 | 10/0/0 | zh=2 |
| gate-m33-transfer | 22/0/0 | 22/0/0 | zh=3 |
| gate-m35-behavior | 3/0/0 | 3/0/0 | zh=3 |
| gate-m36-mixin-outcome | 6/0/0 | 6/0/0 | zh=6 |
| gate-m37-entity-callbacks | 3/0/0 | 3/0/0 | zh=3 |
| gate-m38-enchantment-contracts | 2/0/0 | 2/0/0 | zh=2 |
| gate-m39-transfer-core | 6/0/0 | 6/0/0 | zh=1 |
| gate-m40-energy | 36/0/0 | 36/0/0 | zh=4 |
| gate-m42-coremod-parity | 9/0/0 | 9/0/0 | zh=4 |
| gate-m43-break-and-loot | 5/0/0 | 5/0/0 | zh=2 |
| gate-m44-interaction-events | 9/0/0 | 9/0/0 | zh=2 |
| gate-m45-everyday-actions | 17/0/0 | 17/0/0 | zh=4 |
| gate-m46-stub-rebind | 10/0/0 | 10/0/0 | zh=2 |
| gate-m50-load-predicates | 5/0/0 | 5/0/0 | zh=2 |
| gate-m51-tooltips | 16/0/0 | 16/0/0 | zh=3 |
| gate-m52-hopper-fabric-storage | 29/0/0 | 29/0/0 | zh=5 |
| gate-m53-widened-new | 8/0/0 | 8/0/0 | zh=2 |
| gate-m54-carry | 11/0/0 | 11/0/0 | zh=2 |
| gate-m55-creative-search | 23/0/0 | 23/0/0 | zh=3 |
| gate-m56-config-screens | 143/0/0 | 143/0/0 | zh=5 |
| gate-m57-forge-breath-drown | 12/0/0 | 12/0/0 | zh=2 |
| gate-m58-world-upgrade | 208/0/0 | 208/0/0 | none=1, zh=25 |
| gate-m59-forge-swap | 30/0/0 | 30/0/0 | zh=3 |
| gate-m60-regress | 10/0/0 | 10/0/0 | zh=4 |
| gate-m61-fluid-head | 6/0/0 | 6/0/0 | zh=2 |
| gate-m62-carpet | 6/0/0 | 6/0/0 | zh=3 |

Differences from en (`c5/zh/w2c/gates-zh/compare-lines.txt`, result lines with digits and hashes normalised):

- **gate-m3**: en 12/1/0 (RED), zh 13/0/0. The en FAIL is "mod's ServerTickEvent listener FIRED (game loop)", VERIFY's lib.sh
  `stop_when_ready` race (stop with no settle when Done lands just before the timer; lane HARN's). Not the locale: it is the
  same assertion green here, at `-j 2`.
- Every other difference is the worktree path (`s4c5-verify` vs `s4c5-zh`) or a run token (m0, m13, m24, m24c, m30, m32,
  m39, m56, m58, m60–m62).

So no gate's result depends on the report language: no gate check was changed for it. That the language really reached the
product: the load reports the sweep left behind are in Chinese in 20 run dirs, including gates zh never reached before (m9's
clones, m17's installed game, m19, m36, m37, m38, m43, m51, m52, m58's new kernel, m60, m62); m26's en-pinned
client-forge-refusal report has none (`gates-zh/load-reports-han-lines.tsv`, samples in `gates-zh/load-reports/`); gate-m17's
installed game and both old-install refusals print `Picked up JAVA_TOOL_OPTIONS: -Duser.language=zh -Duser.country=CN`.

## Open

- The dependency-dialog child JVM follows the OS locale, not `-Duser.language` (above). Whether a zh sweep should also set
  `-Dforbric.dialogLanguage=zh` is VERIFY's call (owner: orchestrator); no kernel change proposed.
- Port blocks are 20 ports, i.e. two gates-parallel slots: `-j 4` from GATE_PORT=46660 runs into 46680+, and lane HARN's
  46640 block at `-j 4` runs into this one (owner: orchestrator / HARN).
