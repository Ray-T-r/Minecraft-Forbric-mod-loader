#!/usr/bin/env bash
# M29 — a MinecraftForge-only mod is refused by name, and a dedicated server stops on the refusal.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). This gate proved MinecraftForge's capability system on
# the merged base (AttachCapabilitiesEvent, getCapability on block entities / entities / levels / chunks / item stacks,
# LazyOptional invalidation, "ForgeCaps" persistence) through the MinecraftForge canary forbriclive. Forbric now refuses
# a jar whose only usable loader half is MinecraftForge's, so that canary never constructs and none of those probes can
# run: the capability system is reachable only by a MinecraftForge mod, and no MinecraftForge mod loads. The canary's
# probes, the attach/injectCapabilities/token/audit lines and the lifecycle counts went with it. What did NOT go is the
# kernel's rewrite of vanilla classes: the capability composition into Entity, Level and BlockEntity, the ServerLevel and
# LevelChunk initialisers, and the LivingEntity/furnace/bookshelf initialisers the merge dropped while keeping every
# reader (the first mob death once crashed in invalidateCaps). Those run on every boot of the merged base with no
# MinecraftForge mod at all, so the control boot (3) still asserts them, and boot 4 keeps the dispatch-off proof that the
# roots get their inert composition and the loader never refuses to define one. The capability code stays in the kernel
# and the merged base — Stage 3 deletes nothing — and goes in Stage 4.
# The file keeps its name so the scheduler, its port block and every reference to it stay put; Stage 4 renames it.
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
#   2. strict (zh): the same refusal under FORBRIC_JVM="-Duser.language=zh -Duser.country=CN" — the load report and the
#      finding's detail are in Chinese (DialogLang), the evidence stays machine words.
#   3. control: the NeoForge canary alone, strict — reaches Done with zero confirmed required findings, so the stop in 1
#      and 2 is the refusal's and nothing else's; and the capability rewrites of vanilla classes still land, with no
#      capability error signature and a clean save.
#   4. control with -Dforbric.forgeCapabilities=off: the switch says it is off, the roots still get the inert
#      composition their definition requires, and no class is refused for a missing ancestor composition.
#
# RED, measured 2026-10-30. M29_POLICY=continue (boot 1 only) turns exactly boot 1's four stop checks red — "the report
# says strict decided" (CONTINUE), "strict stopped the launch with the typed policy stop" (exit 0), "and said so", "it
# never came up" (Done reached) — while its refusal, machine-report and load-report checks stay green: a refused jar is
# refused and reported whether or not anything stops the launch. M29_REFUSED='' (only the NeoForge canary staged in
# boots 1 and 2) turns every refusal, report and stop check of both boots red, and the control stays green.
# GATE-PARALLEL: rundirs=server-forgecaps mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="$KERNEL/run/server-forgecaps"
LOG="$BUILD/gate-m29-refusal.log"
ZH_LOG="$BUILD/gate-m29-refusal-zh.log"
CONTROL_LOG="$BUILD/gate-m29-control.log"
OFF_LOG="$BUILD/gate-m29-control-off.log"
FORGE_CANARY="$RUN_OLD/forge-runtime/forbriclive.jar"
# The real multi-manifest MinecraftForge build. It left the popular pack for its NeoForge build in Stage 3 and is kept
# beside it as the refusal's real-jar fixture (local, like the pack: not in git).
OWL="${M29_OWL:-$KERNEL/run/client-popular/forge-refused/animalgarden-owl-1.2.8-forge-26.2-65.0.0.jar}"
REFUSED=("$FORGE_CANARY" "$OWL")
if [ -n "${M29_REFUSED+set}" ]; then read -r -a REFUSED <<< "$M29_REFUSED"; fi
mkdir -p "$BUILD"
for jar in "$FORGE_CANARY" "$OWL" "$NEOLIVE"; do
  [ -f "$jar" ] || { echo "[kernel] SKIP-FATAL: missing $jar (build-testmods.sh builds the canaries; the owl jar is a local fixture)" >&2; exit 3; }
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
    ( stop_when_ready "$log" 8 ) | FORBRIC_COMPAT_POLICY="$policy" FORBRIC_JVM="${M29_EXTRA_JVM:-} $extra" \
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
# FAILED MinecraftForge row per refused mod; the NeoForge canary neither. <lang> picks the detail's wording.
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
         'zh': '是 MinecraftForge mod。Forbric 已不再支持 MinecraftForge，所以它不会被加载。请改用 {name} 的 NeoForge 或 Fabric 版本。'}[lang]
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

# refused_and_stopped <log> <lang> — every assertion boots 1 and 2 share.
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
  check_absent "$lang: no crash report for a policy decision" "Preparing crash report|Game crashed" "$log"
}

step "1. strict, English: two MinecraftForge-only jars beside a NeoForge mod"
boot "$LOG" "${M29_POLICY:-strict}" "-Duser.language=en -Duser.country=US" ${REFUSED[@]+"${REFUSED[@]}"} "$NEOLIVE"
refused_and_stopped "$LOG" en
check "en: the load report says why, per mod" "not loaded: Forbric no longer supports MinecraftForge" "$LOG.report" 2
check "en: and names the file to replace" \
  "Get the NeoForge or Fabric build of Forbric Live Canary and put it in the mods folder in place of forbriclive\.jar\." "$LOG.report"
check "en: for the owl build too" \
  "Get the NeoForge or Fabric build of Animal Garden - Owl and put it in the mods folder in place of animalgarden-owl-1\.2\.8-forge-26\.2-65\.0\.0\.jar\." "$LOG.report"
check "en: and what to do in general" "Forbric now loads Fabric and NeoForge mods only\." "$LOG.report"

step "2. strict, Chinese: the same refusal in the player's language"
boot "$ZH_LOG" strict "-Duser.language=zh -Duser.country=CN" ${REFUSED[@]+"${REFUSED[@]}"} "$NEOLIVE"
refused_and_stopped "$ZH_LOG" zh
check "zh: the load report says why, per mod" "没有加载：Forbric 已不再支持 MinecraftForge" "$ZH_LOG.report" 2
check "zh: and names the file to replace" \
  "请下载 Forbric Live Canary 的 NeoForge 或 Fabric 版本，放进 mods 文件夹替换 forbriclive\.jar。" "$ZH_LOG.report"
check "zh: and what to do in general" "Forbric 现在只加载 Fabric 和 NeoForge mod。" "$ZH_LOG.report"
check_absent "zh: no English refusal wording in the load report" "Forbric no longer supports MinecraftForge" "$ZH_LOG.report"

step "3. control: the NeoForge canary alone, strict"
boot "$CONTROL_LOG" strict "" "$NEOLIVE"
check "control: the server reached Done" 'Done \(' "$CONTROL_LOG"
assert_eq "control: it stopped normally" "0" "$(cat "$CONTROL_LOG.exit" 2>/dev/null)"
assert_eq "control: zero confirmed required findings" "0" "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["confirmedRequired"])' "$CONTROL_LOG.json" 2>/dev/null || echo missing)"
check_absent "control: nothing refused" "\[Forbric/Unsupported\] refusing" "$CONTROL_LOG"
check "control: the NeoForge canary was constructed" "constructed @Mod forbricneolive" "$CONTROL_LOG"
# The merged base's vanilla classes carry MinecraftForge's capability edits whether or not a Forge mod is installed;
# these are the kernel's rewrites of them, which run on every boot until Stage 4 swaps the base. A merge that keeps both
# sides' edits has the initialiser sites natively and the repair stands by; either line means the site has the code.
check "control: the roots were composed" 'Capabilities\] composed MinecraftForge capabilities into net\.minecraft\.world\.entity\.Entity' "$CONTROL_LOG"
check "control: block entities were composed" 'Capabilities\] composed MinecraftForge capabilities into net\.minecraft\.world\.level\.block\.entity\.BlockEntity' "$CONTROL_LOG"
check "control: levels were composed" 'Capabilities\] composed MinecraftForge capabilities into net\.minecraft\.world\.level\.Level' "$CONTROL_LOG"
check "control: ServerLevel gathers" 'Capabilities\] ServerLevel\.<init> (gathers MinecraftForge level capabilities again|already gathers MinecraftForge level capabilities in the merged base.s own bytes)' "$CONTROL_LOG"
check "control: LevelChunk writes its provider" 'Capabilities\] LevelChunk\.<init> (writes Forge.s own capability provider again|already writes Forge.s own capability provider in the merged base.s own bytes)' "$CONTROL_LOG"
check "control: the lost LivingEntity initializer was replayed" 'Capabilities\] net\.minecraft\.world\.entity\.LivingEntity\.<init> (assigns handlers again|already assigns handlers in the merged base.s own bytes)' "$CONTROL_LOG"
check "control: the lost furnace initializer was replayed" 'Capabilities\] net\.minecraft\.world\.level\.block\.entity\.AbstractFurnaceBlockEntity\.<init> (assigns handlers again|already assigns handlers in the merged base.s own bytes)' "$CONTROL_LOG"
check "control: the lost bookshelf initializer was replayed" 'Capabilities\] net\.minecraft\.world\.level\.block\.entity\.ChiseledBookShelfBlockEntity\.<init> (assigns itemHandler again|already assigns itemHandler in the merged base.s own bytes)' "$CONTROL_LOG"
check_absent "control: no lost initializer left unrepaired" 'Capabilities\] .*(not repaired|initializer not replayed)' "$CONTROL_LOG"
# Thrown exceptions print as java.lang.X: the kernel's own stub line mentions "AbstractMethodError" in prose.
check_absent "control: no capability error signature" 'java\.lang\.(ExceptionInInitializerError|AbstractMethodError)|NoSuchMethodError.*Caps|NullPointerException.*capProvider|This will be implemented by a transformer|which the merged game does not carry' "$CONTROL_LOG"
check "control: the server saved cleanly" 'All dimensions are saved' "$CONTROL_LOG"

step "4. control with MinecraftForge capability dispatch off"
# The switch turns off DISPATCH only: the merged base requires Entity/BlockEntity/Level composed
# (required-ancestor-compositions.tsv), so the roots still get the (inert) composition and nothing is refused.
boot "$OFF_LOG" strict "-Dforbric.forgeCapabilities=off" "$NEOLIVE"
check "off: the kernel says it is off" 'forgeCapabilities=off — MinecraftForge capability dispatch is off' "$OFF_LOG"
check "off: the roots still get the inert composition their definition requires" 'Capabilities\] gave net\.minecraft\.world\.entity\.Entity the composed MinecraftForge provider state .* with dispatch off' "$OFF_LOG"
check_absent "off: no root refused for a missing ancestor composition" 'Unresolved stateful ancestor composition' "$OFF_LOG"
check "off: the server still reached Done" 'Done \(' "$OFF_LOG"
check_absent "off: and the composition line of boot 3 is gone, so the switch reached it" \
  'Capabilities\] composed MinecraftForge capabilities into net\.minecraft\.world\.entity\.Entity' "$OFF_LOG"

step "M29 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M29 MINECRAFTFORGE-REFUSAL GATE GREEN — MinecraftForge-only jars are refused by name, in the player's language, and a strict server stops on them"
else
  echo "[kernel] M29 MINECRAFTFORGE-REFUSAL GATE RED — see $LOG, $ZH_LOG, $CONTROL_LOG and $OFF_LOG"
fi
exit "$FAIL"
