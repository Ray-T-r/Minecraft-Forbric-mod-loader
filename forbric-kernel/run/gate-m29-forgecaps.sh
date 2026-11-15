#!/usr/bin/env bash
# M29 — a MinecraftForge-only mod is refused by name, and a dedicated server stops on the refusal.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). This gate proved MinecraftForge's capability system
# (AttachCapabilitiesEvent, getCapability, LazyOptional invalidation, "ForgeCaps" persistence) through the MinecraftForge
# canary forbriclive. Forbric refuses a jar whose only usable loader half is MinecraftForge's, so that canary never
# constructs and none of those probes can run; they went in Stage 3, and the gate became the server side of the refusal.
#
# STAGE 4 OF DROPPING MinecraftForge (what changed and why). The game base is NeoForge's own patched game: its vanilla
# classes carry no MinecraftForge capability edits, and the kernel's capability composition went with the carrier in C2
# (no "[Forbric/Capabilities]" line has a producer any more). So the control boot's eight capability lines and their two
# absences went (D34: an absence of a deleted line proves nothing), and so did boot 4, whose switch
# -Dforbric.forgeCapabilities is retired (D29). Boot 3 stays as the control that the stop in boots 1 and 2 is the
# refusal's and nothing else's. The file keeps its name until Stage 6 (D4).
#
# WHAT IT PROVES NOW. The same canary, plus a real multi-manifest MinecraftForge build, put in front of the user-visible
# switch, on the server side, where the answer is a stop (a client asks in the game instead; that path is pinned by
# MinecraftForgeRefusalPromptTest and gate-m20's ask machinery):
#   forbriclive.jar                              a MinecraftForge mods.toml and nothing else: the plain case
#   animalgarden-owl-1.2.8-forge-26.2-65.0.0.jar fabric.mod.json + neoforge.mods.toml + mods.toml, but only
#                                                net.minecraftforge code behind any of them: its Fabric and NeoForge
#                                                halves are fake, so it is refused, not loaded through one of them
#   forbricneolive.jar                           a real NeoForge mod beside them, which must not be swept up
#
#   1. strict (en): each refused jar is named on the "refusing" line and the arbiter holds it; the machine report has a
#      CONFIRMED, required "unsupported:minecraftforge" finding per jar and a FAILED MinecraftForge row (the Lost row) for
#      each; the load report names each mod, says MinecraftForge is no longer supported and which file to replace; the
#      launch stops with the typed policy stop (exit 78) and never reaches Done; nothing of either jar ran.
#   Boot 1 pins both language switches after the caller's FORBRIC_JVM: the load report follows the JVM's locale, the
#   finding's detail follows -Dforbric.dialogLanguage first, and a sweep may set either (test_gate_locale.py).
#   2. control: the NeoForge canary alone, strict — reaches Done with zero confirmed required findings and a clean save,
#      so the stop in 1 is the refusal's and nothing else's.
#
# A zh boot of the same jars stood between 1 and 2 until 2026-11-16 (lane OPT, D88b/D89). Its twelve refusal, report
# and stop checks were boot 1's on the same jars and policy, only the locale differed; its Chinese wording is asserted
# by the unit suite on the path the boot takes: MinecraftForgeRefusalTest (the finding's detail by the switch and by
# the system language, the load report's three refusal lines and no English one) and KernelLoadReportTest (the writer
# asks the system which language).
#
# RED, measured 2026-10-30, 2026-11-10 (stage4-c5-g-a.md) and, with boot 1 and the control only, 2026-11-16
# (stage4-c5-opt.md). M29_POLICY=continue turns exactly boot 1's four stop checks red — "the report says strict decided"
# (CONTINUE), "strict stopped the launch with the typed policy stop" (exit 0), "and said so", "it never came up" (Done
# reached) — while its refusal, machine-report and load-report checks stay green: a refused jar is refused and reported
# whether or not anything stops the launch (18/4). M29_REFUSED='' (only the NeoForge canary staged in boot 1) turns 11
# checks red: the three refusal checks, the machine report, three of the four stop checks ("strict stopped the launch",
# "and said so", "it never came up") and the four load-report checks (why, the file, the owl build, what to do). Five
# checks of boot 1 stay green, because nothing is refused and the policy is still strict: "the report says strict
# decided", "nothing of the refused jars ran", "the Forge-family mixin configs registered are NeoForge's alone", "and
# not the refused canary's under another name" and "no crash report". The control boot's six stay green: 11/11.
# GATE-PARALLEL: rundirs=server-forgecaps mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="$KERNEL/run/server-forgecaps"
refuse_shared_rundir "$RUNDIR"
LOG="$BUILD/gate-m29-refusal.log"
CONTROL_LOG="$BUILD/gate-m29-control.log"
# The live canary's manifest and @Mod, compiled against stand-ins for MinecraftForge (run/build-forge-refusal-fixtures.sh):
# the refusal reads only those, and the stage needs no MinecraftForge carrier to build it.
FORGE_CANARY="$KERNEL/run/canary/forge-refused/forbriclive.jar"
# The real multi-manifest MinecraftForge build. It left the popular pack for its NeoForge build in Stage 3 and is kept
# beside it as the refusal's real-jar fixture (local, like the pack: not in git).
OWL="${M29_OWL:-$KERNEL/run/client-popular/forge-refused/animalgarden-owl-1.2.8-forge-26.2-65.0.0.jar}"
REFUSED=("$FORGE_CANARY" "$OWL")
if [ -n "${M29_REFUSED+set}" ]; then read -r -a REFUSED <<< "$M29_REFUSED"; fi
mkdir -p "$BUILD"
# Its exit code, not only the jar: a failed build leaves the previous jar in place.
"$KERNEL/run/build-forge-refusal-fixtures.sh" > "$BUILD/gate-m29-canary.log" 2>&1 \
  || { echo "[kernel] FATAL: the refusal fixtures did not build (see $BUILD/gate-m29-canary.log)" >&2; exit 3; }
for jar in "$FORGE_CANARY" "$OWL" "$NEOLIVE"; do
  [ -f "$jar" ] || { echo "[kernel] SKIP-FATAL: missing $jar (build-forge-refusal-fixtures.sh builds forbriclive, see $BUILD/gate-m29-canary.log; build-testmods.sh the NeoForge canary; the owl jar is a local fixture)" >&2; exit 3; }
done
kernel_jar

# boot <log> <policy> <extra jvm> <jar>... — stages exactly these jars, boots, records the exit code in <log>.exit and
# keeps the two reports beside the log. A launch that reaches Done is stopped a few seconds later.
boot() {
  local log="$1" policy="$2" extra="$3" pid
  shift 3
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" "$RUNDIR/logs" "$RUNDIR/config"
  mkdir -p "$RUNDIR/mods"
  [ "$#" -eq 0 ] || cp "$@" "$RUNDIR/mods/"
  seed_server_properties "$RUNDIR"
  printf 'online-mode=false\nlevel-type=minecraft\\:flat\nmax-tick-time=-1\nview-distance=2\n' >> "$RUNDIR/server.properties"
  echo "eula=true" > "$RUNDIR/eula.txt"
  : > "$log"; rm -f "$log.exit"
  (
    ( stop_when_ready "$log" 8 ) | FORBRIC_COMPAT_POLICY="$policy" FORBRIC_JVM="${FORBRIC_JVM:-} ${M29_EXTRA_JVM:-} $extra" \
      RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh"
    code=$?
    printf '%s\n' "$code" > "$log.exit"
    exit "$code"
  ) > "$log" 2>&1 &
  pid=$!
  record_server_pid "$RUNDIR" "$pid"
  await_server "$pid" "$log" 240 30
  rm -f "$RUNDIR/.forbric-gate.pid"
  cp "$RUNDIR/.forbric-kernel/compatibility-report.json" "$log.json" 2>/dev/null || : > "$log.json"
  cp "$RUNDIR/.forbric-kernel/load-report.txt" "$log.report" 2>/dev/null || : > "$log.report"
}

# refusal_report <json> <lang> — the machine report: one CONFIRMED required unsupported:minecraftforge finding and one
# FAILED MinecraftForge row per refused mod; the NeoForge canary neither. <lang> picks the detail's wording (en).
refusal_report() {
  python3 - "$1" "$2" <<'PY'
import json, sys
path, lang = sys.argv[1], sys.argv[2]
try:
    report = json.load(open(path, encoding='utf-8'))
except (OSError, ValueError) as error:
    raise SystemExit(f'no machine report: {error}')
expected = {'forbriclive': ('forbriclive.jar', 'Forbric Live Canary'),
            'animalgarden_owl': ('animalgarden-owl-1.2.8-forge-26.2-65.0.0.jar', 'Animal Garden - Owl')}
words = {'en': 'is a MinecraftForge mod. Forbric no longer supports MinecraftForge, so it is not loaded. '
               'Get the NeoForge or Fabric build of {name} instead.',
         }[lang]
problems = []
for mod, (jar, name) in expected.items():
    found = [f for f in report['findings'] if f['id'] == 'unsupported:minecraftforge' and f['modId'] == mod]
    if len(found) != 1:
        problems.append(f'{mod}: {len(found)} unsupported:minecraftforge finding(s)'); continue
    f = found[0]
    if f['confidence'] != 'CONFIRMED' or f['required'] is not True:
        problems.append(f'{mod}: finding is {f["confidence"]} required={f["required"]}')
    if words.format(name=name) not in f['detail'] or jar not in f['detail']:
        problems.append(f'{mod}: detail is not the {lang} wording naming {jar}: {f["detail"]!r}')
    for evidence in (f'jar={jar}', 'unsupported=minecraftforge', 'installed=mods', 'loads=nothing', 'family=FORGE'):
        if evidence not in f['evidence']:
            problems.append(f'{mod}: evidence lacks {evidence}')
    rows = [m for m in report['mods'] if m['modId'] == mod]
    if not rows or rows[0]['ecosystem'] != 'FORGE' or rows[0]['status'] != 'FAILED' or rows[0]['jar'] != jar:
        problems.append(f'{mod}: no FAILED MinecraftForge row for {jar}: {rows}')
if report['confirmedRequired'] < 2:
    problems.append(f'confirmedRequired {report["confirmedRequired"]} < 2')
neo = [f for f in report['findings'] if f['modId'] == 'forbricneolive' and f['required']]
if neo:
    problems.append(f'the NeoForge canary was swept up: {neo}')
if problems:
    raise SystemExit('; '.join(problems))
print(f'[kernel]   {report["confirmedRequired"]} confirmed required finding(s); both refusals CONFIRMED, required, '
      f'in {lang}, each with a FAILED MinecraftForge row')
PY
}

# refused_and_stopped <log> <lang> — the refusal, report and stop assertions of a refusal boot.
refused_and_stopped() {
  local log="$1" lang="$2"
  check "$lang: forbriclive is refused by name" \
    "\[Forbric/Unsupported\] refusing forbriclive\.jar \(forbriclive\): it is a MinecraftForge mod with no usable Fabric or NeoForge half" "$log"
  check "$lang: the multi-manifest owl build is refused, its fake halves named" \
    "\[Forbric/Unsupported\] refusing animalgarden-owl-1\.2\.8-forge-26\.2-65\.0\.0\.jar \(animalgarden_owl\): .*fabric\.mod\.json: not usable.*META-INF/neoforge\.mods\.toml: not usable" "$log"
  check "$lang: the arbiter holds both: no loader loads any of either" \
    "\[Forbric/MultiLoader\] (forbriclive|animalgarden-owl-1\.2\.8-forge-26\.2-65\.0\.0)\.jar is refused" "$log" 2
  if refusal_report "$log.json" "$lang"; then echo "[kernel] PASS $lang: the machine report has both refusals as CONFIRMED required findings and FAILED rows"
  else echo "[kernel] FAIL $lang: the machine report (see $log.json)"; FAIL=1; fi
  assert_eq "$lang: the report says strict decided" "STRICT" \
    "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["policy"])' "$log.json" 2>/dev/null || echo missing)"
  assert_eq "$lang: strict stopped the launch with the typed policy stop" "78" "$(cat "$log.exit" 2>/dev/null)"
  check "$lang: and said so" "\[Forbric/Compatibility\] launch stopped: required mod initialization or features are unavailable; continuation was not approved \(policy STRICT\)" "$log"
  check_absent "$lang: it never came up" "Done \(" "$log"
  # The kernel names the owl's classes when it explains the refusal (the Unsupported and MultiLoader lines): a line it
  # wrote about itself is not the mod running. Its "constructed @Mod" line would be, and it is not excluded.
  check_absent "$lang: nothing of the refused jars ran" "constructed @Mod (forbriclive|animalgarden_owl)|\[ForbricLive|aquariusplayz" \
    "$log" '\[Forbric/(Unsupported|MultiLoader)\]'
  # forbriclive's fixture keeps the live canary's mixin config and its plugin, which prints [ForbricLive/PLUGIN] (above)
  # the moment Mixin is handed the config. The kernel's own count of what it registered says the same from the other
  # side: the line is there, and no config of it is MinecraftForge's or the refused canary's.
  check_forge_family_configs_are_neoforge "$lang: the Forge-family mixin configs registered are NeoForge's alone" "$log"
  check_absent "$lang: and not the refused canary's under another name" \
    '\[Forbric/Mixin\] mixin configs: .*forbriclive\.mixins\.json' "$log"
  check_absent "$lang: no crash report for a policy decision" "Preparing crash report|Game crashed" "$log"
}

step "1. strict, English: two MinecraftForge-only jars beside a NeoForge mod"
boot "$LOG" "${M29_POLICY:-strict}" "-Duser.language=en -Duser.country=US -Dforbric.dialogLanguage=en" ${REFUSED[@]+"${REFUSED[@]}"} "$NEOLIVE"
refused_and_stopped "$LOG" en
check "en: the load report says why, per mod" "not loaded: Forbric no longer supports MinecraftForge" "$LOG.report" 2
check "en: and names the file to replace" \
  "Get the NeoForge or Fabric build of Forbric Live Canary and put it in the mods folder in place of forbriclive\.jar\." "$LOG.report"
check "en: for the owl build too" \
  "Get the NeoForge or Fabric build of Animal Garden - Owl and put it in the mods folder in place of animalgarden-owl-1\.2\.8-forge-26\.2-65\.0\.0\.jar\." "$LOG.report"
check "en: and what to do in general" "Forbric now loads Fabric and NeoForge mods only\." "$LOG.report"

step "2. control: the NeoForge canary alone, strict"
boot "$CONTROL_LOG" strict "" "$NEOLIVE"
check "control: the server reached Done" 'Done \(' "$CONTROL_LOG"
assert_eq "control: it stopped normally" "0" "$(cat "$CONTROL_LOG.exit" 2>/dev/null)"
assert_eq "control: zero confirmed required findings" "0" "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["confirmedRequired"])' "$CONTROL_LOG.json" 2>/dev/null || echo missing)"
check_absent "control: nothing refused" "\[Forbric/Unsupported\] refusing" "$CONTROL_LOG"
check "control: the NeoForge canary was constructed" "constructed @Mod forbricneolive" "$CONTROL_LOG"
check "control: the server saved cleanly" 'All dimensions are saved' "$CONTROL_LOG"

step "M29 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M29 MINECRAFTFORGE-REFUSAL GATE GREEN — MinecraftForge-only jars are refused by name and a strict server stops on them"
else
  echo "[kernel] M29 MINECRAFTFORGE-REFUSAL GATE RED — see $LOG and $CONTROL_LOG"
fi
exit "$FAIL"
