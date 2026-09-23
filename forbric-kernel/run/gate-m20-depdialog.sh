#!/usr/bin/env bash
# M20 gate — an unmet hard dependency reaches the PLAYER, not just the log.
#
# WHY THIS EXISTS. The kernel has always detected this and always written one WARN about it. On a real pack that
# WARN said "biomesoplenty requires terrablender >=26.2.0.0.1 — not installed", and forty seconds later the game
# died on "Failed to load registries due to errors" with 455 unknown block ids — an error naming neither mod. One
# line in ten thousand is not a report; the player never saw it and could not have connected the two.
#
# So the same finding is now put in front of them before the window opens. This gate asserts BOTH halves of that,
# because each is a different way to get it wrong:
#   * on a CLIENT it must reach them
#   * on a dedicated SERVER it must NOT — a server blocked on a dialog nobody can see is strictly worse than the
#     log line it replaces, and every gate in this repo runs unattended
#
# The canary is a jar with nothing in it but a manifest declaring a dependency on a mod that does not exist. No
# code, nothing to compile: the thing under test is the audit and the dialog, and a canary with classes in it
# would only add ways for the gate to fail for an unrelated reason.
#
# THE SECOND CANARY covers the opposite mistake: a requirement the dialog must NOT report. The same library is
# spelled differently by each ecosystem -- Fabric "cloth-config", NeoForge "cloth_config" -- so under Forbric a
# NeoForge mod's requirement reads as NOT INSTALLED with the Fabric build sitting in the same mods folder. That is
# the one thing a dialog whose job is to be believed must never do. RED control:
#   M20_EXTRA_JVM='-Dforbric.crossEcosystemIds=off' -> "forbriccrosseco ... requires forbric_dep_canary >=1.0.0 —
#   not installed" is back and the cross-spelling INFO line is gone. Checks that name "1 finding(s)" in the
#   continue step go red with them, and say so: the false accusation is a second notice row. That IS the switch
#   working -- it puts back a finding that is not real. (Re-count the red checks when this gate is next run; the
#   steps changed when the notice and the confirmation became one window.)
#
# HOW A DIALOG IS TESTED WITH NOBODY TO CLICK IT: -Dforbric.dependencyDialog=dryRun runs the real path — writes
# the real report, forks the real child JVM, reads the real exit code — with AWT disabled inside the child, so it
# finds it cannot draw. Everything but the pixels.
#
# ONE WINDOW, AND THE ANSWER IS WHAT HAPPENS. The candidate arbitration records an unmet hard dependency as a
# confirmed required loss, and a required loss needs an explicit continue. That question is asked IN this window,
# fail-closed, not in a second one: the notice is folded into it. So what the unanswerable dry run must mean
# depends on the policy, and every step below sets its policy explicitly and reads the machine report instead of
# assuming:
#   * ask   — required losses present: ONE fork, which cannot approve, and the launch stops with the typed exit 78
#             and no crash report. The old gate stayed green here on "launching anyway" while the kernel stopped
#             the launch nine lines later.
#   * continue — nothing needs an answer: the fail-open notice is forked once and says "launching anyway", and the
#             boot must really get past BOTH decisions — "Sound engine started" comes after the client-setup
#             decision inside Minecraft.<init>.
# With no required loss in the report, ask degrades to the continue case, and the gate asserts that instead.
# GATE-PARALLEL: rundirs=server-depdialog,client-depdialog mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SLOG="$BUILD/gate-m20-server.log"
CLOG="$BUILD/gate-m20-client.log"
OFFLOG="$BUILD/gate-m20-off.log"
CONTLOG="$BUILD/gate-m20-continue.log"
SRV="$KERNEL/run/server-depdialog"
CLI="$KERNEL/run/client-depdialog"
WORK="$BUILD/depdialog-canary"
mkdir -p "$BUILD"

step "build the canary: a manifest declaring a dependency on a mod that does not exist"
kernel_jar
rm -rf "$WORK"; mkdir -p "$WORK"
cat > "$WORK/fabric.mod.json" <<'JSON'
{
  "schemaVersion": 1,
  "id": "forbricdepcanary",
  "version": "1.0.0",
  "name": "Forbric Dependency Canary",
  "description": "Declares a hard dependency on a mod that is not installed, so the audit has something true to report.",
  "license": "Apache-2.0",
  "environment": "*",
  "depends": {
    "forbricnosuchmod": ">=1.0.0"
  }
}
JSON
(cd "$WORK" && jar --create --file "$BUILD/forbricdepcanary.jar" .) || { echo "[kernel] FAIL canary jar"; exit 1; }
echo "[kernel] built forbricdepcanary.jar (depends on forbricnosuchmod >=1.0.0, which nothing provides)"

# Second canary: requires the first one under the OTHER ecosystem's spelling of the same id. Nothing named
# "forbric_dep_canary" is installed; "forbricdepcanary" is, and they are one mod.
CROSS="$BUILD/crosseco-canary"
rm -rf "$CROSS"; mkdir -p "$CROSS"
cat > "$CROSS/fabric.mod.json" <<'JSON'
{
  "schemaVersion": 1,
  "id": "forbriccrosseco",
  "version": "1.0.0",
  "name": "Forbric Cross-Ecosystem Spelling Canary",
  "description": "Requires the dependency canary under the underscored spelling the Forge families would use.",
  "license": "Apache-2.0",
  "environment": "*",
  "depends": {
    "forbric_dep_canary": ">=1.0.0"
  }
}
JSON
(cd "$CROSS" && jar --create --file "$BUILD/forbriccrosseco.jar" .) || { echo "[kernel] FAIL cross canary jar"; exit 1; }
echo "[kernel] built forbriccrosseco.jar (requires forbric_dep_canary, which is installed as forbricdepcanary)"

step "stage it on a server and a client"
reap_stale_server "$SRV"
rm -rf "$SRV" "$CLI"
mkdir -p "$SRV/mods" "$CLI/mods"
cp "$BUILD/forbricdepcanary.jar" "$SRV/mods/"
cp "$BUILD/forbricdepcanary.jar" "$CLI/mods/"
cp "$BUILD/forbriccrosseco.jar" "$CLI/mods/"
seed_server_properties "$SRV"
cp "$KERNEL/run/client-merged-pack/options.txt" "$CLI/options.txt" 2>/dev/null \
  || printf 'version:4903\nonboardAccessibility:false\n' > "$CLI/options.txt"

# M20_HELPERS_BEGIN — M20OutcomeContractTest runs these and the ask-outcome block below against fixture logs.
# forks <game-log> — how many dialog children this boot started. Counted in the game's own log, which has each
# line once (the launcher's console carries a second copy).
# grep -c already prints 0 for no match; an unreadable log counts as none.
forks() { local n; n=$(grep -acE "Forbric/Deps\] -Dforbric.dependencyDialog=dryRun — forked the dialog" "$1" 2>/dev/null); echo "${n:-0}"; }

# report_field <json> <python-expr over report> — prints the value, or "missing" when there is no fresh report.
report_field() {
  python3 - "$1" "$2" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as stream:
        report = json.load(stream)
except (OSError, ValueError):
    print("missing"); raise SystemExit
print(eval(sys.argv[2], {"report": report}))
PY
}

# The arbitration must not accuse a requirement the dependency audit resolved across ecosystems: the audit names
# forbricdepcanary as the provider of forbric_dep_canary, so no live finding may say that requirement is unmet.
# (The report's "mods" inventory lists forbriccrosseco as installed, so this reads findings, not the whole file.)
no_false_accusation() {
  assert_eq "$1: the report exists and says which policy decided" "$2" "$(report_field "$3" 'report["policy"]')"
  assert_eq "$1: no finding accuses forbriccrosseco of a requirement the audit resolved" "0" "$(report_field "$3" \
    'len([f for f in report["findings"] if f["id"].lower().endswith(":forbric_dep_canary") and f["confidence"] != "RESOLVED"])')"
}
# M20_HELPERS_END

step "the dedicated server must report it, open nothing, and stop exactly as its strict report says"
: > "$SLOG"; rm -f "$SLOG.exit" "$SRV/.forbric-kernel/compatibility-report.json"
# -Dforbric.debug so the side check's own line prints. Without it the only thing observable on a server is the
# ABSENCE of a fork -- and that absence is also produced by launch-kernel-server.sh's -Djava.awt.headless=true,
# so the assertion would stay green with the side check deleted. Asserting the line names WHICH guard fired.
(
  ( sleep 25; echo stop ) | FORBRIC_COMPAT_POLICY=strict FORBRIC_JVM="-Dforbric.debug=true" \
    RUNDIR="$SRV" "$KERNEL/run/launch-kernel-server.sh"
  code=$?
  printf '%s\n' "$code" > "$SLOG.exit"
  exit "$code"
) > "$SLOG" 2>&1 &
SPID=$!
record_server_pid "$SRV" "$SPID"
await_server "$SPID" "$SLOG" 130
cp "$SRV/.forbric-kernel/compatibility-report.json" "$SLOG.json" 2>/dev/null || : > "$SLOG.json"

check "the server still reports the unmet requirement" \
  "Forbric/Deps\] forbricdepcanary .* requires forbricnosuchmod >=1.0.0 — not installed" "$SLOG"
# The guard that matters. launch-kernel-server.sh passes -Djava.awt.headless=true, so BOTH the side check and the
# headless check would stop it; asserting the absence of the fork covers whichever one fired.
check "the SIDE check is what stopped it, by name" \
  "Forbric/Deps\] not the client — the 1 finding\(s\) stay in the log" "$SLOG"
check_absent "and opened no dialog" "Forbric/Deps\] (launching anyway|dryRun|the player chose)" "$SLOG"
assert_eq "the server report is strict" "STRICT" "$(report_field "$SLOG.json" 'report["policy"]')"
SREQ=$(report_field "$SLOG.json" 'report["confirmedRequired"]')
if [ "$SREQ" != "missing" ] && [ "$SREQ" -gt 0 ] 2>/dev/null; then
  assert_eq "strict refused $SREQ required loss(es) with the typed policy stop" "78" "$(cat "$SLOG.exit" 2>/dev/null)"
  check "and said so" "launch stopped by compatibility policy" "$SLOG"
  check_absent "it never came up" "Done \(" "$SLOG"
else
  assert_eq "nothing required: the strict server ran and stopped normally" "0" "$(cat "$SLOG.exit" 2>/dev/null)"
  check "and it came up" "Done \(" "$SLOG"
fi
check_absent "no crash report for a policy decision" "Preparing crash report|Game crashed" "$SLOG"

# run_client <log> <policy> <dialog-mode> — boots the client and waits for it to leave on its own, or to get past
# every loading decision; records its exit code ("killed" when the gate had to stop a client that kept running)
# and keeps the game log and the machine report beside the log.
run_client() {
  local log="$1" policy="$2" mode="$3" pid i code
  : > "$log"
  rm -f "$log.exit" "$CLI/logs/latest.log" "$CLI/.forbric-kernel/compatibility-report.json"
  FORBRIC_COMPAT_POLICY="$policy" FORBRIC_DEP_DIALOG="$mode" FORBRIC_JVM="${M20_EXTRA_JVM:-}" RUNDIR="$CLI" \
    "$KERNEL/run/launch-kernel-client.sh" > "$log" 2>&1 &
  pid=$!
  echo "[kernel] client pid=$pid policy=$policy dialog=$mode (killed by pid only — another client may be running)"
  for i in $(seq 1 240); do
    kill -0 "$pid" 2>/dev/null || { echo "[kernel] client left on its own after ~${i}s"; break; }
    grep -qaE 'Sound engine started|Game crashed|Mod Loading has failed' "$CLI/logs/latest.log" 2>/dev/null \
      && { echo "[kernel] client got past loading after ~${i}s"; break; }
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill_tree "$pid"; wait "$pid" 2>/dev/null; code=killed
  else
    wait "$pid"; code=$?
  fi
  echo "$code" > "$log.exit"
  cp "$CLI/logs/latest.log" "$log.game" 2>/dev/null || : > "$log.game"
  cp "$CLI/.forbric-kernel/compatibility-report.json" "$log.json" 2>/dev/null || : > "$log.json"
  cat "$log.game" >> "$log"
}

step "ask: the client reaches the player in ONE window, and what it cannot answer it does not approve"
run_client "$CLOG" ask dryRun
check "the client reported the same unmet requirement" \
  "Forbric/Deps\] forbricdepcanary .* requires forbricnosuchmod >=1.0.0 — not installed" "$CLOG"
check "a library spelled the other ecosystem's way is resolved, not accused" \
  "Forbric/Deps\] forbriccrosseco .* requires forbric_dep_canary, which is installed as .*forbricdepcanary.* the same library" "$CLOG"
check_absent "and it is nowhere in the not-installed findings" \
  "forbriccrosseco .* requires forbric_dep_canary >=1.0.0 — not installed" "$CLOG"
no_false_accusation "ask" ASK "$CLOG.json"
# Written by DependencyDialog, which is the only layer that knows the child was forked and what it answered --
# the audit that found the requirement cannot establish either.
# M20_ASK_OUTCOME_BEGIN
assert_eq "exactly one dialog was forked: the notice is not a second window" "1" "$(forks "$CLOG.game")"
check_absent "nothing quit the game on the player's behalf" "Forbric/Deps\] the player chose to quit" "$CLOG"
CREQ=$(report_field "$CLOG.json" 'report["confirmedRequired"]')
if [ "$CREQ" != "missing" ] && [ "$CREQ" -gt 0 ] 2>/dev/null; then
  check "the one window was the confirmation, and with no display it could not approve" \
    "Forbric/Deps\] -Dforbric.dependencyDialog=dryRun — forked the dialog for [0-9]+ finding\(s\) with no display; it answered 1 \(not approved\)" "$CLOG.game"
  check_absent "it did not also say it was launching anyway" "Forbric/Deps\] launching anyway" "$CLOG"
  assert_eq "the unapproved launch stopped with the typed policy stop" "78" "$(cat "$CLOG.exit" 2>/dev/null)"
  check "and said so" "launch stopped by compatibility policy" "$CLOG"
  check_absent "never reached the game" "Sound engine started" "$CLOG"
else
  check "nothing required: the one window was the fail-open notice" \
    "Forbric/Deps\] launching anyway with 1 finding\(s\)" "$CLOG"
  check "and the boot continued past both decisions" "Sound engine started" "$CLOG"
  check_absent "nothing stopped it" "Forbric/Compatibility\] launch stopped" "$CLOG"
fi
check_absent "no crash report for a dialog answer" "Preparing crash report|Game crashed|Initializing game" "$CLOG"
# M20_ASK_OUTCOME_END

step "continue: nothing needs an answer, so the notice is the one window and the game really loads"
run_client "$CONTLOG" continue dryRun
no_false_accusation "continue" CONTINUE "$CONTLOG.json"
assert_eq "exactly one dialog was forked" "1" "$(forks "$CONTLOG.game")"
check "it was the fail-open notice, and an unanswerable one launches anyway" \
  "Forbric/Deps\] -Dforbric.dependencyDialog=dryRun — forked the dialog for 1 finding\(s\) with no display; it answered 0 \(launch anyway\)" "$CONTLOG.game"
check "and the boot continued, which is what an unanswerable NOTICE must always mean" \
  "Forbric/Deps\] launching anyway with 1 finding\(s\)" "$CONTLOG"
check_absent "no decision stopped it" "Forbric/Compatibility\] launch stopped" "$CONTLOG"
check "it got past the client-setup decision inside Minecraft.<init>" "Sound engine started" "$CONTLOG"
check_absent "no crash" "Preparing crash report|Game crashed" "$CONTLOG"

step "negative control: with the dialog off, the finding is still reported and nothing is forked"
run_client "$OFFLOG" ask off
check "the switch really turned it off" \
  "Forbric/Deps\] -Dforbric.dependencyDialog=off — [0-9]+ finding\(s\) reported in the log only" "$OFFLOG"
check "and the WARN a gate has always been able to grep is unchanged" \
  "Forbric/Deps\] forbricdepcanary .* requires forbricnosuchmod >=1.0.0 — not installed. It is being loaded anyway" "$OFFLOG"
check_absent "no child was forked" "forked the dialog" "$OFFLOG"
OREQ=$(report_field "$OFFLOG.json" 'report["confirmedRequired"]')
if [ "$OREQ" != "missing" ] && [ "$OREQ" -gt 0 ] 2>/dev/null; then
  # No window means no explicit continue, so a required loss is not approved: fail closed, never fail open.
  check "with no window a required loss is not approved" "continuation was not approved" "$OFFLOG"
  assert_eq "and the launch stopped with the typed policy stop" "78" "$(cat "$OFFLOG.exit" 2>/dev/null)"
fi

step "M20 result"
if [ "${FAIL:-0}" -eq 0 ]; then
  echo "[kernel] ✅ M20 GATE GREEN — an unmet hard dependency reaches the player in one window on a client, nothing on a server, and each answer is what happened"
else
  echo "[kernel] ❌ M20 GATE RED — see $SLOG / $CLOG / $CONTLOG / $OFFLOG"
  exit 1
fi
