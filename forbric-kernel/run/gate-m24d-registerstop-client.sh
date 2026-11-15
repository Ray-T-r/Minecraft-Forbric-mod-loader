#!/usr/bin/env bash
# M24d — on a CLIENT, a NeoForge registration failure stops the boot as native NeoForge 26.2.0.93 does (owner ruling D82).
#
# WHY THIS EXISTS. gate-m24 proves the stop on a dedicated server. The client stops on a different path: the kernel's
# ClientModLoader.begin redirect lets NeoForge's ModLoadingException out to the client's Main, which writes the
# crash-*-client.txt report and hands the error to NeoForge's FatalErrorReporting (an early-window screen natively when
# there is one; a native message box otherwise) and exits. Nothing of a mod runs after the roll-back, the registries are
# not frozen, Minecraft is never constructed, no world is created. That path rested on one manual run; a catch added
# later in the client's mod-loading hook would turn it back into "the boot carries on with vanilla registries" with no
# test to notice.
#
# The register-stop canary (run/build-register-stop-canary.sh, the same jar gate-m24 stages) fails both ways D82 names:
# -Dforbric.registerstop=listener (its RegisterEvent listener throws) and =try (its mixin throws inside NeoForge's
# per-registry try). Each stop phase waits for the crash report, then for the client to leave or to sit in NeoForge's
# fatal-error report, which waits for a click nobody gives: the gate then stops the client (TERM, so its shutdown hooks run
# and Forbric's crash analysis is written) and kills what is left of its tree (on macOS the message box is an osascript
# child). The control boots the same three jars with =off and must get as far as "Sound engine started", froze the
# registries and ran the Fabric main — which is what the stop phases assert did NOT happen.
#
# RED control (D34): the kernel at 2c231c9c (before K3c) contained the throw, so the stop phases boot on to the sound engine
# and every stop check is red there.
# GATE-PARALLEL: rundirs=client-registerstop mem=3000
# GATE-TAGS: localized   (reads the load report, a crash report or a dialog: output that follows the language; gates-all --only-tag localized is the zh sweep)
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

CLI="${M24D_RUNDIR:-$KERNEL/run/client-registerstop}"
refuse_shared_rundir "$CLI"
REGSTOP="$KERNEL/run/canary/forbricregisterstop.jar"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
NEO="$NEOLIVE"
STOP_LISTENER="$BUILD/gate-m24d-listener.log"
STOP_TRY="$BUILD/gate-m24d-try.log"
CONTROL="$BUILD/gate-m24d-control.log"
mkdir -p "$BUILD"

step "build the register-stop canary and the Fabric canary"
"$KERNEL/run/build-fabric-canary.sh" >"$BUILD/gate-m24d-canary.log" 2>&1
"$KERNEL/run/build-register-stop-canary.sh" >>"$BUILD/gate-m24d-canary.log" 2>&1
for jar in "$REGSTOP" "$FABRIC" "$NEO"; do
  [ -f "$jar" ] || { echo "[kernel] FAIL missing canary: $jar (see $BUILD/gate-m24d-canary.log)"; exit 1; }
done

stage() {
  reap_stale_server "$CLI"
  rm -rf "$CLI"
  mkdir -p "$CLI/mods"
  cp "$REGSTOP" "$FABRIC" "$NEO" "$CLI/mods/"
  printf 'version:4903\nonboardAccessibility:false\n' > "$CLI/options.txt"
  echo "[kernel] staged: $(ls -1 "$CLI/mods" | tr '\n' ' ')"
}

# run_client <log> <mode> — boots the client with -Dforbric.registerstop=<mode> under explicit continue (the policy does
# not decide a registration stop). Waits up to 240 s for a crash report or for the sound engine. After a crash report it
# gives the client 10 s to leave on its own. A client still running then is stopped by the gate: "held" in $log.exit when
# it wrote its crash report and never reached the sound engine (it is sitting in NeoForge's fatal-error report), "killed"
# otherwise (it went on).
run_client() {
  local log="$1" mode="$2" pid i code report="" crashed="" sound=""
  : > "$log"
  # Every artifact of an earlier run goes first: a stale crash report or analysis beside the log would pass for this one.
  rm -f "$log.exit" "$log.game" "$log.crash-client.txt" "$log.suspects.json"
  FORBRIC_COMPAT_POLICY=continue FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.registerstop=$mode" RUNDIR="$CLI" \
    "$KERNEL/run/launch-kernel-client.sh" > "$log" 2>&1 < /dev/null &
  pid=$!
  record_server_pid "$CLI" "$pid"
  echo "[kernel] client pid=$pid registerstop=$mode"
  for i in $(seq 1 240); do
    kill -0 "$pid" 2>/dev/null || { echo "[kernel] client left on its own after ~${i}s"; break; }
    if [ -z "$crashed" ] && ls "$CLI"/crash-reports/crash-*-client.txt >/dev/null 2>&1; then
      crashed=$i; echo "[kernel] crash report written after ~${i}s"
    fi
    [ -n "$crashed" ] && [ $((i - crashed)) -ge 10 ] && break
    if grep -qa "Sound engine started" "$CLI/logs/latest.log" 2>/dev/null; then
      sound=$i; echo "[kernel] client reached the sound engine after ~${i}s"; sleep 3; break
    fi
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    # Informational: what the client is waiting in (NeoForge's message box is a child process on macOS).
    echo "[kernel] client still running; its children: $(pgrep -P "$pid" 2>/dev/null | xargs -I{} ps -o command= -p {} 2>/dev/null | cut -c1-160 | tr '\n' ' ')"
    stop_tree "$pid" 15
    wait "$pid" 2>/dev/null
    if [ -n "$crashed" ] && [ -z "$sound" ] && ! grep -qa "Sound engine started" "$CLI/logs/latest.log" 2>/dev/null; then
      code=held
    else
      code=killed
    fi
  else
    wait "$pid"; code=$?
  fi
  rm -f "$(_pidfile "$CLI")"
  echo "$code" > "$log.exit"
  cp "$CLI/logs/latest.log" "$log.game" 2>/dev/null || : > "$log.game"
  report=$(ls "$CLI"/crash-reports/crash-*-client.txt 2>/dev/null | head -1)
  [ -n "$report" ] && cp "$report" "$log.crash-client.txt"
  cp "$CLI/.forbric-kernel/crash-suspects.json" "$log.suspects.json" 2>/dev/null || : > "$log.suspects.json"
  cat "$log.game" >> "$log"
}

client_stop() { # client_stop <log> <mode>
  local log="$1" mode="$2" code
  code=$(cat "$log.exit" 2>/dev/null)
  case "$code" in
    1)    echo "[kernel] PASS $mode: the client exited with status 1, as NeoForge's fatal-error report does" ;;
    held) echo "[kernel] PASS $mode: the client sat in NeoForge's fatal-error report after its crash report until the gate stopped it" ;;
    *)    echo "[kernel] FAIL $mode: the client neither exited with status 1 nor sat in NeoForge's fatal-error report (exit: ${code:-none})"; FAIL=1 ;;
  esac
  check "$mode: NeoForge rolled every registry back to vanilla"  "Rolling back to VANILLA state"   "$log"
  check "$mode: NeoForge's ModLoader ended its registry phase"   "Failed to wait for future Registry initialization, 1 errors found" "$log"
  check "$mode: the kernel stops the boot as native does"        "the boot stops here, as native NeoForge stops it" "$log"
  if [ -s "$log.crash-client.txt" ]; then
    echo "[kernel] PASS $mode: the client's crash report was written"
    check "$mode: the crash report carries NeoForge's mod-loading error" "net\.neoforged\.fml\.ModLoadingException: Loading errors encountered" "$log.crash-client.txt"
  else
    echo "[kernel] FAIL $mode: no crash-reports/crash-*-client.txt"; FAIL=1
  fi
  # Forbric's crash analysis reads the client's report. A listener's issue names its mod ("Name (id) encountered an error
  # while dispatching ..."), so the analysis must name it. A throw inside the try is NeoForge's "uncaught parallel
  # processing error": the client's report carries only its message, not its trace (the issue's cause is not the
  # exception's cause), so nothing in it names a mod by anything but free text — there the analysis must at least not
  # blame the two healthy mods.
  if python3 - "$log.suspects.json" "$mode" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    report = json.load(stream)
ids = [s["modId"] for s in report["suspects"]]
if sys.argv[2] == "listener":
    assert ids[:1] == ["forbricregisterstop"], ids
else:
    assert not {"forbricneolive", "forbricfabriclive"} & set(ids), ids
PY
  then
    echo "[kernel] PASS $mode: Forbric's crash analysis $([ "$mode" = listener ] && echo "names the mod that failed" || echo "blames neither healthy mod")"
  else
    echo "[kernel] FAIL $mode: Forbric's crash analysis is wrong about the mods ($log.suspects.json)"; FAIL=1
  fi
  check_absent "$mode: Minecraft was never started (no sound engine)"  "Sound engine started"                "$log"
  check_absent "$mode: no Fabric main ran after the roll-back"          "\[ForbricFabricLive\] onInitialize" "$log"
  check_absent "$mode: the registries were not frozen after the roll-back" "froze the registries NeoForge-first" "$log"
  if [ -d "$CLI/saves" ]; then
    echo "[kernel] FAIL $mode: a saves directory was created after the stop"; FAIL=1
  else
    echo "[kernel] PASS $mode: no saves directory was created"
  fi
}

step "a NeoForge mod whose RegisterEvent listener throws stops the client as native NeoForge does (D82; must PASS)"
stage
run_client "$STOP_LISTENER" listener
check "listener: the listener really ran and threw" "\[ForbricRegisterStop\] reached RegisterEvent for minecraft:item, mode=listener" "$STOP_LISTENER"
check "listener: NeoForge's error names the mod and the event" \
  "\(forbricregisterstop\) encountered an error while dispatching the net\.neoforged\.neoforge\.registries\.RegisterEvent event" "$STOP_LISTENER"
client_stop "$STOP_LISTENER" listener

step "a throw inside NeoForge's per-registry try stops the client as native NeoForge does (D82; must PASS)"
stage
run_client "$STOP_TRY" try
check "try: the mixin really ran and threw" "\[ForbricRegisterStop\] mixin after minecraft:item's dispatch, about to fail on purpose" "$STOP_TRY"
check "try: NeoForge's error names the uncaught error" "An uncaught parallel processing error has occurred" "$STOP_TRY"
client_stop "$STOP_TRY" try

step "control: the same three jars with =off boot the client on (what the stops above must not do)"
stage
run_client "$CONTROL" off
check "control: the register-stop canary ran and threw nothing" \
  "\[ForbricRegisterStop\] reached RegisterEvent for minecraft:item, mode=off" "$CONTROL"
check "control: the Fabric main ran"            "\[ForbricFabricLive\] onInitialize"     "$CONTROL"
check "control: the registries were frozen"     "froze the registries NeoForge-first"    "$CONTROL"
check "control: Minecraft started (sound engine)" "Sound engine started"                 "$CONTROL"
check_absent "control: no roll-back"            "Rolling back to VANILLA state"          "$CONTROL"
if ls "$CLI"/crash-reports/crash-*.txt >/dev/null 2>&1; then
  echo "[kernel] FAIL control: a crash report was written"; FAIL=1
else
  echo "[kernel] PASS control: no crash report"
fi

step "M24d result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M24D CLIENT REGISTRATION-STOP GATE GREEN — the client stops as native NeoForge does, and the control boots"
else
  echo "[kernel] ❌ M24D GATE RED — listener $STOP_LISTENER / try $STOP_TRY / control $CONTROL"
fi
exit "$FAIL"
