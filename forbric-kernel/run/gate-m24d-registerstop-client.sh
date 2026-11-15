#!/usr/bin/env bash
# M24d — on a CLIENT, a mod that throws while the game loads stops the boot as its own loader does (owner ruling D82 for
# NeoForge's registration, D87 for the rest).
#
# WHY THIS EXISTS. gate-m24 proves the stops on a dedicated server. The client stops on three different paths:
#   * NeoForge's construction and registry phases run in the kernel's ClientModLoader.begin redirect, which lets NeoForge's
#     ModLoadingException out to the client's Main: crash-*-client.txt ("Bootstrap"), then NeoForge's FatalErrorReporting
#     (an early-window screen natively when there is one; a native message box otherwise), then exit — phases listener,
#     try (D82) and construct (D87). Nothing of a mod runs after the roll-back, Minecraft is never constructed.
#   * every later NeoForge phase runs at the base's ClientModLoader.finish call inside Minecraft.<init>, whose own handler
#     natively writes the crash report (category "Finish mod loading") and shows the same fatal-error report — phase
#     common (D87; the kernel's KernelNeoClientStop does what that handler does).
#   * a Fabric main/client entrypoint stage that throws leaves Minecraft.<init> with Fabric Loader's "Could not execute
#     entrypoint stage" error for Main's handler ("Initializing game" crash report, exit 255) — phase fabric (D87, with the
#     Fabric broken canary).
# Each stop phase waits for the crash report, then for the client to leave or to sit in NeoForge's fatal-error report,
# which waits for a click nobody gives: the gate then stops the client (TERM, so its shutdown hooks run and Forbric's crash
# analysis is written) and kills what is left of its tree (on macOS the message box is an osascript child). Forbric's
# crash analysis must name the failing mod in every phase — for try, from NeoForge's own record of the issue's cause, as
# the client's report carries only its message (K3c open item 6, fixed by lane STOP). The control boots the same three jars
# with =off and must get as far as "Sound engine started", froze the registries and ran the Fabric main.
#
# RED control (D34): the kernel at 2c231c9c (before K3c) contained the registration throw, and the kernel at 421c53cc
# (before STOP) contained construct, common and fabric and named nobody for try.
# GATE-PARALLEL: rundirs=client-registerstop mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

CLI="${M24D_RUNDIR:-$KERNEL/run/client-registerstop}"
refuse_shared_rundir "$CLI"
REGSTOP="$KERNEL/run/canary/forbricregisterstop.jar"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
NEO="$NEOLIVE"
STOP_LISTENER="$BUILD/gate-m24d-listener.log"
STOP_TRY="$BUILD/gate-m24d-try.log"
STOP_CONSTRUCT="$BUILD/gate-m24d-construct.log"
STOP_COMMON="$BUILD/gate-m24d-common.log"
STOP_FABRIC="$BUILD/gate-m24d-fabric.log"
BROKEN="$KERNEL/run/canary/forbricbrokencanary.jar"
CONTROL="$BUILD/gate-m24d-control.log"
mkdir -p "$BUILD"

step "build the register-stop canary and the Fabric canary"
"$KERNEL/run/build-fabric-canary.sh" >"$BUILD/gate-m24d-canary.log" 2>&1
"$KERNEL/run/build-register-stop-canary.sh" >>"$BUILD/gate-m24d-canary.log" 2>&1
"$KERNEL/run/build-broken-canary.sh" >>"$BUILD/gate-m24d-canary.log" 2>&1
for jar in "$REGSTOP" "$FABRIC" "$NEO" "$BROKEN"; do
  [ -f "$jar" ] || { echo "[kernel] FAIL missing canary: $jar (see $BUILD/gate-m24d-canary.log)"; exit 1; }
done

stage() { # stage [fabric] — the register-stop canary and the two live canaries; "fabric" swaps in the Fabric broken canary
  reap_stale_server "$CLI"
  rm -rf "$CLI"
  mkdir -p "$CLI/mods"
  cp "$FABRIC" "$NEO" "$CLI/mods/"
  if [ "${1:-}" = fabric ]; then cp "$BROKEN" "$CLI/mods/"; else cp "$REGSTOP" "$CLI/mods/"; fi
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

client_stopped() { # client_stopped <log> <mode> <expected first suspect> [allowed exit codes, default "1 held"]
  local log="$1" mode="$2" who="$3" allowed="${4:-1 held}" code
  code=$(cat "$log.exit" 2>/dev/null)
  if [ -n "$code" ] && [[ " $allowed " == *" $code "* ]]; then
    echo "[kernel] PASS $mode: the client stopped as its loader stops it (exit: $code; allowed: $allowed)"
  else
    echo "[kernel] FAIL $mode: the client did not stop as its loader stops it (exit: ${code:-none}; allowed: $allowed)"; FAIL=1
  fi
  if [ -s "$log.crash-client.txt" ]; then
    echo "[kernel] PASS $mode: the client's crash report was written"
  else
    echo "[kernel] FAIL $mode: no crash-reports/crash-*-client.txt"; FAIL=1
  fi
  if python3 - "$log.suspects.json" "$who" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    report = json.load(stream)
ids = [s["modId"] for s in report["suspects"]]
assert ids[:1] == [sys.argv[2]], ids
PY
  then
    echo "[kernel] PASS $mode: Forbric's crash analysis names the mod that failed ($who)"
  else
    echo "[kernel] FAIL $mode: Forbric's crash analysis does not name $who first ($log.suspects.json)"; FAIL=1
  fi
  if [ -d "$CLI/saves" ]; then
    echo "[kernel] FAIL $mode: a saves directory was created after the stop"; FAIL=1
  else
    echo "[kernel] PASS $mode: no saves directory was created"
  fi
}

client_neo_stop() { # client_neo_stop <log> <mode> <NeoForge init task>
  local log="$1" mode="$2" task="$3"
  client_stopped "$log" "$mode" forbricregisterstop
  check "$mode: NeoForge's ModLoader ended its task"             "Failed to wait for future $task, 1 errors found" "$log"
  check "$mode: the kernel stops the boot as native does"        "the boot stops here, as native NeoForge stops it" "$log"
  check "$mode: the crash report carries NeoForge's mod-loading error" "net\.neoforged\.fml\.ModLoadingException: Loading errors encountered" "$log.crash-client.txt"
}

client_stop() { # client_stop <log> <mode> — a stop in NeoForge's registry phase (begin path)
  local log="$1" mode="$2"
  client_neo_stop "$log" "$mode" "Registry initialization"
  check "$mode: NeoForge rolled every registry back to vanilla"  "Rolling back to VANILLA state"   "$log"
  check_absent "$mode: Minecraft was never started (no sound engine)"  "Sound engine started"                "$log"
  check_absent "$mode: no Fabric main ran after the roll-back"          "\[ForbricFabricLive\] onInitialize" "$log"
  check_absent "$mode: the registries were not frozen after the roll-back" "froze the registries NeoForge-first" "$log"
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

step "a NeoForge mod whose constructor throws stops the client in construction, as native NeoForge does (D87; must PASS)"
stage
run_client "$STOP_CONSTRUCT" construct
check "construct: the constructor really ran and threw" "\[ForbricRegisterStop\] its constructor is about to fail on purpose" "$STOP_CONSTRUCT"
client_neo_stop "$STOP_CONSTRUCT" construct "Mod Construction"
check "construct: NeoForge's error names the mod"      "\(forbricregisterstop\) has failed to load correctly" "$STOP_CONSTRUCT.crash-client.txt"
check_absent "construct: no registry event was posted" "\[ForbricRegisterStop\] reached RegisterEvent" "$STOP_CONSTRUCT"
check_absent "construct: Minecraft was never started"   "Sound engine started"                          "$STOP_CONSTRUCT"

step "a NeoForge mod whose common setup throws stops the client at ClientModLoader.finish, as native NeoForge does (D87; must PASS)"
stage
run_client "$STOP_COMMON" common
check "common: the listener really ran and threw" "\[ForbricRegisterStop\] its FMLCommonSetupEvent listener is about to fail on purpose" "$STOP_COMMON"
client_neo_stop "$STOP_COMMON" common "Common setup"
check "common: the crash report is ClientModLoader.finish's" "-- Finish mod loading --" "$STOP_COMMON.crash-client.txt"
check "common: NeoForge's error names the mod and the event" \
  "\(forbricregisterstop\) encountered an error while dispatching the net\.neoforged\.fml\.event\.lifecycle\.FMLCommonSetupEvent event" "$STOP_COMMON.crash-client.txt"
check_absent "common: not Main's Initializing-game handler" "Description: Initializing game" "$STOP_COMMON.crash-client.txt"

step "a Fabric main entrypoint that throws takes the client down, as native Fabric Loader does (D87; must PASS)"
stage fabric
run_client "$STOP_FABRIC" off
check "fabric: the broken mod reached its entrypoint" "ForbricBrokenCanary\] reached onInitialize" "$STOP_FABRIC"
client_stopped "$STOP_FABRIC" fabric forbricbrokencanary "255"
check "fabric: Fabric Loader's stage error names the mod" \
  "Could not execute entrypoint stage 'main' due to errors, provided by 'forbricbrokencanary'" "$STOP_FABRIC.crash-client.txt"
check "fabric: the game's Main reported it"          "Description: Initializing game"            "$STOP_FABRIC.crash-client.txt"
check "fabric: the kernel stops as native Fabric does" "the boot stops here, as native Fabric Loader stops it" "$STOP_FABRIC"
check_absent "fabric: no NeoForge mod-loading error around it" "Failed to wait for future"         "$STOP_FABRIC"
check_absent "fabric: Minecraft never finished starting"        "Sound engine started"              "$STOP_FABRIC"

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
  echo "[kernel] ✅ M24D CLIENT STOP GATE GREEN — the client stops as native NeoForge / Fabric Loader does, and the control boots"
else
  echo "[kernel] ❌ M24D GATE RED — $BUILD/gate-m24d-{listener,try,construct,common,fabric,control}.log"
fi
exit "$FAIL"
