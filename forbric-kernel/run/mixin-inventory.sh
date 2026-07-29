#!/usr/bin/env bash
# Iteratively discovers every guest mixin that cannot apply to the merged base, producing an EXPLICIT list.
#
# Why iteratively: with strict injection requirements (-Dforbric.mixinDiagnostics) Mixin reports the FIRST
# misfitting injection and dies — an InjectionError escapes as a fatal MixinTransformerError, so `required: false`
# (which soft-skips apply-time errors) cannot catch it. So: boot, read the failure, add it to the suppression
# list, boot again. Each round reveals exactly one more.
#
# The output list is the honest alternative to relaxing injection requirements globally: relaxing makes a
# non-matching injection SILENT, which is how fabric-registry-sync's half-applied ScopedValue re-bind hid until it
# crashed on a worker thread at runtime.
#
# Usage: ./mixin-inventory.sh [max-rounds]   (default 12)
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

MAX="${1:-12}"
RUNDIR="$KERNEL/run/server-fabric-real"
LOG="$BUILD/mixin-inventory.log"
SUPPRESS=""

# Mixins known to be COUPLED to another: suppressing one alone leaves the other half running against a state it
# no longer owns. Discovered empirically: BootstrapMixin.delayRegistryFreeze delays the registry freeze and
# MainMixin.afterModInit performs it afterwards, so dropping only Bootstrap yields "Registry is already frozen".
# (Both are redundant on the kernel, which owns the single freeze itself.)
coupled_with() {
  case "$1" in
    fabric-registry-sync-v0.mixins.json:BootstrapMixin) echo "fabric-registry-sync-v0.mixins.json:MainMixin" ;;
    fabric-registry-sync-v0.mixins.json:MainMixin)      echo "fabric-registry-sync-v0.mixins.json:BootstrapMixin" ;;
    *) echo "" ;;
  esac
}

mkdir -p "$BUILD"
kernel_jar
echo "[kernel] inventory: booting with STRICT injection requirements, up to $MAX rounds"

for round in $(seq 1 "$MAX"); do
  pkill -9 -f KernelServerLaunch 2>/dev/null; sleep 1
  rm -rf "$RUNDIR/world" "$RUNDIR/.forbric-kernel" 2>/dev/null
  : > "$LOG"

  # mergedBaseCompat=off: discover from scratch, ignoring the built-in MergedBaseMixinCompat lists — otherwise the
  # tool would only ever rediscover what is already suppressed.
  JVM="-Dforbric.relaxMixinOverwrites=fabric-* -Dforbric.mixinDiagnostics=true -Dforbric.mergedBaseCompat=off"
  [ -n "$SUPPRESS" ] && JVM="$JVM -Dforbric.suppressMixins=$SUPPRESS"

  ( sleep 40; echo stop ) | RUNDIR="$RUNDIR" FORBRIC_JVM="$JVM" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
  BOOTPID=$!
  for i in $(seq 1 140); do
    pgrep -f KernelServerLaunch >/dev/null 2>&1 || break
    grep -qE 'Stopping server|Failed to start the minecraft server' "$LOG" 2>/dev/null && break
    sleep 1
  done
  pkill -9 -f KernelServerLaunch 2>/dev/null; wait "$BOOTPID" 2>/dev/null

  if grep -qE 'Done \(' "$LOG"; then
    step "round $round: SERVER REACHED Done"
    echo "[kernel] ✅ inventory complete after $round round(s)"
    echo ""
    echo "[kernel] mixins that cannot apply to the merged base (suppress these):"
    echo "$SUPPRESS" | tr ',' '\n' | sed 's/^/  /'
    echo ""
    echo "[kernel] -Dforbric.suppressMixins=$SUPPRESS"
    exit 0
  fi

  # The first strictly-failing mixin this round.
  FAILED=$(grep -oE '[a-z0-9-]+\.mixins\.json:[A-Za-z0-9_.$]+ from mod' "$LOG" | sed 's/ from mod//' | head -1)

  if [ -z "$FAILED" ]; then
    step "round $round: no mixin failure, but no Done either"
    echo "[kernel] the remaining wall is NOT a mixin apply/inject failure. Last exception:"
    grep -oE '^[a-z]+(\.[a-zA-Z0-9_$]+)+(Exception|Error).*' "$LOG" | head -2
    grep 'Caused by' "$LOG" | head -2
    echo ""
    echo "[kernel] suppressed so far: ${SUPPRESS:-<none>}"
    exit 1
  fi

  REASON=$(grep -oE 'Critical injection failure[^[]*|Invalid descriptor[^[]*|No candidates were found[^[]*' "$LOG" \
           | head -1 | cut -c1-110)
  echo "[kernel] round $round: $FAILED"
  echo "[kernel]          $REASON"

  case ",$SUPPRESS," in
    *",$FAILED,"*)
      echo "[kernel] FAIL $FAILED already suppressed yet still reported — suppression is not taking effect"
      exit 1 ;;
  esac

  SUPPRESS="${SUPPRESS:+$SUPPRESS,}$FAILED"

  PARTNER=$(coupled_with "$FAILED")
  if [ -n "$PARTNER" ]; then
    case ",$SUPPRESS," in
      *",$PARTNER,"*) ;;
      *) echo "[kernel]          + coupled partner $PARTNER"; SUPPRESS="$SUPPRESS,$PARTNER" ;;
    esac
  fi
done

echo "[kernel] ❌ still failing after $MAX rounds; suppressed: $SUPPRESS"
exit 1
