#!/usr/bin/env bash
# M59 — a world written with a mod's MinecraftForge build loses, when the player swaps in the same mod's NeoForge build
# on this Forbric, exactly what the same swap loses natively, and nothing else.
#
# WHY THIS EXISTS. Forbric before MinecraftForge's removal (tag tri-eco-26.2-final) ran a mod's MinecraftForge build; this
# tree refuses it, so a player swaps in the mod's NeoForge build and opens the old world. Some of what the Forge build
# wrote is the mod's own format, which the NeoForge build does not read natively either (MinecraftForge 26.2-65.0.1 ->
# NeoForge 26.2.0.93 with the same jars). The owner's ruling (D77, O1 "document only") is that those losses are
# documented, not migrated, and that Forbric-caused losses stay 0. This gate pins that: the Forbric arm's losses must
# equal canary/forge-swap/expected-losses.tsv exactly, each row with the verdict it was measured with against the
# native arm (forge-swap.py classify), and nothing Forbric changes may be a value native keeps.
#
# THE FIXTURE (D54) is a world nobody can write again: run/compat/forge-swap-make-old.sh wrote it with the old kernel and
# the candidates' MinecraftForge builds (brickfurnace, classicpipes, packedup + corelib + configlib, tombstone,
# BiomesOPlenty + TerraBlender + GlitchCore, journeymap, animalgarden-owl + aquariuslibs), including two FILLED packedup
# backpacks (a chest's and the player's; ForgeSwapBagFill), plus the frozen native arm (native/: native-controls.py swap
# --engine native on the same console script, its chest's bag filled the same way). M59_FIXTURE, else run/forge-swap (a
# link to the main checkout's, like neoforge-builds), else $FORBRIC_FORGE_SWAP / ~/Forbric-backups/forge-swap. The gate
# never writes into it; without one it is SKIP-FATAL (exit 3), naming the file it missed.
#
# WHAT IT ASKS (M59_PHASES, default "open client pin"):
#   open    native-controls.py swap --engine forbric: a NeoForge server of this tree opens a clone of the fixture's
#           old-save with the old config/ and the NeoForge builds, runs the drill (forge-swap.py checks: every mod's
#           blocks, items, a place / use / craft per gate representative, and packedup.bag.kept), saves, and reopens.
#           Checks: both sessions Done and saved, no lost port, the drill all PASS, exactly one "Missing data pack" line
#           (the MinecraftForge carrier's) on the open and none on the reopen, every forbric/data/* pack of the old
#           world still enabled, every probe chunk's LastUpdate up (the re-save proof), every mod world file the old
#           world has KEPT (packedup's backpack inventories, tombstone's saved players) and each backpack holding the
#           same stacks.
#   client  the NEW singleplayer client of this tree opens another clone (the player's rows: a dedicated server never
#           loads the player file) and saves and quits by itself. Checks: clean disconnect, no crash, its world files
#           KEPT and its bags the same.
#   pin     forge-swap.py pin against the frozen native arm: no Forbric loss outside expected-losses.tsv (unpinned), no
#           row of it that is not lost as pinned (stale), no value Forbric changes that native keeps (changed). Without
#           the client phase the TSV's player rows are not asked for (--no-player).
#
# REVERSE CONTROLS (M59_CONTROL; each breaks one thing after the open, before the checks that read the new world):
#   drop-slot   the vanilla chest's slot 1 (64 cobblestone, a key the swap keeps) is removed from the new world -> pin
#               unpinned names it.
#   chest-slot  the same slot's count 64 -> 63 -> pin changed names it (FORBRIC CHANGED: native keeps it).
#   drop-row    expected-losses.tsv's first non-player row is deleted (a copy; a player row would be set aside by a
#               server-only run) -> pin unpinned names that row's key.
#   drop-pack   forbric/data/packedup is taken out of the new level.dat's enabled packs -> the packs check.
#   drop-region the probe's overworld region file r.0.0.mca is deleted from the new world -> the re-save proof (and every loss
#               that follows from it).
#
# Evidence: $BUILD/verification/m59-forge-swap/ (M59_RESULTS): the swap's result.json, logs, drill, packs, LastUpdate
# readings, world files, bags, the client's log and export, the pin's JSON (classify table included), the MANIFEST.
#
# It starts a client (phase client): run it under .stage/client.sh, then .stage/heavy.sh.
# Knobs: M59_FIXTURE, M59_RUNDIR (default run/forge-swap-m59; refused when it resolves into a shared fixture, D46),
# M59_RESULTS, M59_PHASES, M59_CONTROL, M59_EXPECTED (the TSV).
# GATE-PARALLEL: rundirs=forge-swap-m59 mem=4000
set -uo pipefail
GATE_PORT="${GATE_PORT:-25659}"
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

# The fixture: M59_FIXTURE, else this tree's run/forge-swap link (D54), else its home outside every checkout (lib.sh's
# FORBRIC_FORGE_SWAP, default ~/Forbric-backups/forge-swap), which a tree cut before the link existed still finds.
if [ -n "${M59_FIXTURE:-}" ]; then FIX="$M59_FIXTURE"
elif [ -e "$KERNEL/run/forge-swap" ]; then FIX="$KERNEL/run/forge-swap"
else FIX="${FORBRIC_FORGE_SWAP:-${HOME:-/nonexistent}/Forbric-backups/forge-swap}"; fi
ROOT="${M59_RUNDIR:-$KERNEL/run/forge-swap-m59}"
refuse_shared_rundir "$ROOT"
RESULTS="${M59_RESULTS:-$BUILD/verification/m59-forge-swap}"
TSV="${M59_EXPECTED:-$KERNEL/canary/forge-swap/expected-losses.tsv}"
PHASES=" ${M59_PHASES:-open client pin} "
CONTROL="${M59_CONTROL:-}"
FS="python3 $KERNEL/run/compat/forge-swap.py"
WS="python3 $KERNEL/run/compat/world-snapshot.py"
NC="python3 $KERNEL/run/compat/native-controls.py"
BOX="-2,-2,3,3"
WORLD=ForgeSwap
want() { case "$PHASES" in *" $1 "*) return 0;; esac; return 1; }
# same_bags <what> <bags-file> -- forge-swap.py bags on a new world says exactly what it said on the fixture's old-save
# (every inventory file, slot, item, count and component name); both listings stay in the evidence.
same_bags() {
  local a b
  a="$(grep -a '^\[forge-swap\] bag ' "$RESULTS/old-bags.txt" | shasum | cut -c1-12)"
  b="$(grep -a '^\[forge-swap\] bag ' "$2" 2>/dev/null | shasum | cut -c1-12)"
  if [ "$a" = "$b" ]; then printf '[kernel] PASS %s (%s bag file(s), listing %s)\n' "$1" "$(grep -ac '^\[forge-swap\] bag inventory' "$2")" "$b"
  else printf '[kernel] FAIL %s (listing %s, want %s: see %s)\n' "$1" "$b" "$a" "$2"; FAIL=1; fi
}
case "$CONTROL" in ""|drop-slot|chest-slot|drop-row|drop-pack|drop-region) ;;
  *) echo "[kernel] SKIP-FATAL: unknown M59_CONTROL=$CONTROL" >&2; exit 3;; esac

step "the frozen fixture"
for f in MANIFEST old-save/level.dat old-config old-snapshot.json native/neo-open.snapshot.json native/forge-reopen.snapshot.json; do
  [ -e "$FIX/$f" ] || { echo "[kernel] SKIP-FATAL: no forge-swap fixture at $FIX (missing $f; D54: run/forge-swap or ~/Forbric-backups/forge-swap, made by run/compat/forge-swap-make-old.sh plus native/)" >&2; exit 3; }
done
FIX="$(cd "$FIX" && pwd -P)"
[ -f "$TSV" ] || { echo "[kernel] SKIP-FATAL: no expected-losses.tsv at $TSV" >&2; exit 3; }
rm -rf "$RESULTS" "$ROOT"; mkdir -p "$RESULTS" "$ROOT"
ROOT="$(cd "$ROOT" && pwd -P)"
cp "$FIX/MANIFEST" "$RESULTS/fixture-MANIFEST"
assert_eq "the fixture's S0 is the one its MANIFEST names" \
  "$(sed -n 's/^old-snapshot.json //p' "$FIX/MANIFEST")" "$(shasum -a 1 "$FIX/old-snapshot.json" | cut -d' ' -f1)"
check "the fixture's MANIFEST records a filled packedup bag (packedup-bag-stacks)" '^packedup-bag-stacks inventory[0-9]+\.nbt [0-9]' "$FIX/MANIFEST" 1
$FS bags "$FIX/old-save" > "$RESULTS/old-bags.txt" 2>&1
check "packedup's saved inventories in the fixture hold stacks" '^\[forge-swap\] bag inventory[0-9]+\.nbt: [1-9]' "$RESULTS/old-bags.txt" 1
[ "$FAIL" = 0 ] || { echo "[kernel] FAIL the fixture is not the one this gate pins (see above)"; exit 1; }

OLD_WORLD="$ROOT/old-save"
cp -c -R "$FIX/old-save" "$OLD_WORLD"
OUT="$ROOT/forbric"
TSV_USED="$TSV"
if [ "$CONTROL" = drop-row ]; then
  TSV_USED="$ROOT/expected-losses.control.tsv"
  # The first row a server-only run asks for too (not a player row, which --no-player sets aside).
  awk -F'\t' 'BEGIN{dropped=0} /^#/ || $1=="key" || dropped || $1 ~ /^(players|advancements|stats|rule player) / {print; next}
    {dropped=1; print "[control] drop-row: " $1 > "/dev/stderr"; next}' "$TSV" > "$TSV_USED" 2> "$RESULTS/control.txt"
  cat "$RESULTS/control.txt"
fi

if want open; then
  step "open the old world with the NeoForge builds on this tree (server), drill, reopen"
  kernel_jar
  $FS checks > "$ROOT/checks.txt"
  $NC swap --engine forbric --old-world "$OLD_WORLD" --old-config "$FIX/old-config" --neo-mods "$FIX"/mods-neo/*.jar \
    --checks "$ROOT/checks.txt" --chunks "$BOX" --out "$OUT" > "$RESULTS/swap.json" 2>&1
  cat "$RESULTS/swap.json"
  cp "$OUT/result.json" "$OUT/inputs.json" "$RESULTS/" 2>/dev/null
  for phase in neo-open neo-reopen; do
    cp "$OUT/$phase.log" "$RESULTS/" 2>/dev/null
    check_absent "$phase: the server kept its port" 'FAILED TO BIND TO PORT' "$OUT/$phase.log"
    check "$phase: the server reached Done" '\]: Done \(' "$OUT/$phase.log" 1
    check "$phase: the save-all flush was answered" 'Saved the game' "$OUT/$phase.log" 1
  done
  if ! grep -aq '"error": null' "$RESULTS/swap.json"; then
    echo "[kernel] FAIL the swap did not finish (swap.json); nothing past it is compared"; exit 1
  fi
  $FS drill "$OUT/neo-open.log" > "$RESULTS/drill.txt"
  cat "$RESULTS/drill.txt"
  check "the drill on the NeoForge open: every check answered as expected" '^\[forge-swap\] drill: [1-9][0-9]* PASS, 0 FAIL' "$RESULTS/drill.txt" 1
  check "packedup.bag.kept: the chest's backpack still names its inventory" '^\[forge-swap\] PASS packedup\.bag\.kept' "$RESULTS/drill.txt" 1
  check_exactly "the open misses exactly one data pack" 'Missing data pack' "$OUT/neo-open.log" 1
  check "...the MinecraftForge carrier's (forbric/carrier/1-forge-runtime-interop)" 'Missing data pack forbric/carrier/1-forge-runtime-interop' "$OUT/neo-open.log" 1
  check_absent "the reopen misses no data pack" 'Missing data pack' "$OUT/neo-reopen.log"

  NEW_WORLD="$OUT/neo-instance/world"
  case "$CONTROL" in
    drop-slot)  $FS control drop-slot "$NEW_WORLD" 0 -60 0 1 | tee -a "$RESULTS/control.txt" ;;
    chest-slot) $WS mutate "$NEW_WORLD" --chest-count "minecraft:overworld 0 -60 0" 1 63 2>&1 | tee -a "$RESULTS/control.txt" ;;
    drop-pack)  $FS control drop-pack "$NEW_WORLD" forbric/data/packedup | tee -a "$RESULTS/control.txt" ;;
    drop-region) rm -f "$NEW_WORLD/dimensions/minecraft/overworld/region/r.0.0.mca"; echo "[control] drop-region: deleted dimensions/minecraft/overworld/region/r.0.0.mca" | tee -a "$RESULTS/control.txt" ;;
  esac
  if [ -n "$CONTROL" ] && [ "$CONTROL" != drop-row ]; then
    # The pin reads the export: after a control it is taken again from the world the control changed.
    $WS export "$NEW_WORLD" --out "$OUT/neo-open.snapshot.json" --chunks "$BOX" --label "forbric neo-open + control $CONTROL" > /dev/null
  fi

  $FS packs "$OLD_WORLD" "$NEW_WORLD" > "$RESULTS/packs.txt" 2>&1; cat "$RESULTS/packs.txt"
  check "every forbric/data/* pack the old world enabled is still enabled" '^\[forge-swap\] packs: [1-9][0-9]* forbric/data pack\(s\) in the old world, 0 no longer enabled' "$RESULTS/packs.txt" 1

  $FS lastupdate "$OLD_WORLD" --chunks "$BOX" > "$RESULTS/lastupdate-old.txt"
  $FS lastupdate "$NEW_WORLD" --chunks "$BOX" > "$RESULTS/lastupdate-new.txt"
  python3 - "$RESULTS/lastupdate-old.txt" "$RESULTS/lastupdate-new.txt" > "$RESULTS/lastupdate.txt" <<'PY'
import re, sys
read = lambda p: {m[1]: int(m[2]) for m in re.finditer(r"LastUpdate (-?\d+ -?\d+) (-?\d+)", open(p).read())}
old, new = read(sys.argv[1]), read(sys.argv[2])
probe = ["0 0", "0 1", "1 0", "1 1"]
up = [c for c in probe if c in old and c in new and new[c] > old[c]]
print("[forge-swap] resave: %d of %d probe chunks saved later than the old world (%s)" % (len(up), len(probe), ", ".join(
    "%s %s->%s" % (c, old.get(c, "-"), new.get(c, "-")) for c in probe)))
PY
  cat "$RESULTS/lastupdate.txt"
  check "re-save proof: every probe chunk's LastUpdate rose" '^\[forge-swap\] resave: 4 of 4 ' "$RESULTS/lastupdate.txt" 1

  $FS files "$OLD_WORLD" "$NEW_WORLD" > "$RESULTS/files.txt" 2>&1; cat "$RESULTS/files.txt"
  check "the mods' own world files are there (packedup backpacks)" '^\[forge-swap\] world file KEPT packedup/backpacks/' "$RESULTS/files.txt" 1
  check_absent "no mod world file is LOST or CHANGED by the open" '^\[forge-swap\] world file (LOST|CHANGED)' "$RESULTS/files.txt"
  $FS bags "$NEW_WORLD" > "$RESULTS/new-bags.txt" 2>&1
  same_bags "every backpack holds the same stacks after the open" "$RESULTS/new-bags.txt"
fi

CLIENT_SNAP=""
if want client; then
  step "the NEW singleplayer client opens the old world (the player's rows)"
  C="$ROOT/client"; mkdir -p "$C/saves" "$C/mods" "$C/quickPlay"
  cp "$FIX"/mods-neo/*.jar "$C/mods/"
  cp -c -R "$FIX/old-config" "$C/config"
  printf 'onboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\nskipMultiplayerWarning:true\njoinedFirstServer:true\nsoundCategory_master:0.0\nrenderDistance:4\nsimulationDistance:5\nguiScale:2\nnarrator:0\n' > "$C/options.txt"
  cp -c -R "$FIX/old-save" "$C/saves/$WORLD"
  $FS client --log "$RESULTS/client.log" --rundir "$C" --env "RUNDIR=$C" --env "FORBRIC_COMPAT_POLICY=strict" \
    --env "FORBRIC_JVM=-Dforbric.compatibilityPolicy=strict -Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=400" \
    --timeout 900 -- "$KERNEL/run/launch-kernel-client.sh" --quickPlayPath "$C/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" \
    > "$RESULTS/client.result.json" 2>&1
  cat "$RESULTS/client.result.json"
  check "the client saved and quit by itself (clean disconnect)" '"cleanDisconnect": true' "$RESULTS/client.result.json" 1
  check_absent "the client did not crash" '"crashed": true' "$RESULTS/client.result.json"
  CLIENT_SNAP="$RESULTS/client.snapshot.json"
  $WS export "$C/saves/$WORLD" --out "$CLIENT_SNAP" --chunks "$BOX" --label "NEW client" > /dev/null
  $FS files "$FIX/old-save" "$C/saves/$WORLD" > "$RESULTS/client-files.txt" 2>&1
  check_absent "no mod world file is LOST or CHANGED by the client" '^\[forge-swap\] world file (LOST|CHANGED)' "$RESULTS/client-files.txt"
  $FS bags "$C/saves/$WORLD" > "$RESULTS/client-bags.txt" 2>&1
  same_bags "every backpack holds the same stacks after the client" "$RESULTS/client-bags.txt"
fi

if want pin; then
  step "pin: the Forbric arm's losses against expected-losses.tsv and the frozen native arm"
  if [ ! -f "$OUT/neo-open.snapshot.json" ]; then
    echo "[kernel] FAIL pin needs phase open's result ($OUT)"; FAIL=1
  else
    PIN_ARGS=(--forbric "$OUT" --native "$FIX/native" --tsv "$TSV_USED" --out "$RESULTS/pin.json")
    if [ -n "$CLIENT_SNAP" ]; then PIN_ARGS+=(--client "$CLIENT_SNAP"); else PIN_ARGS+=(--no-player); fi
    $FS pin "${PIN_ARGS[@]}" > "$RESULTS/pin.txt" 2>&1
    cat "$RESULTS/pin.txt"
    check "the pin read a classify table (its summary line)" '^\[forge-swap\] pin: [0-9]+ loss' "$RESULTS/pin.txt" 1
    check_absent "every Forbric loss is an expected-losses.tsv row (unpinned)" '^\[forge-swap\] pin unpinned ' "$RESULTS/pin.txt"
    check_absent "every expected-losses.tsv row is still lost, with its verdict (stale)" '^\[forge-swap\] pin stale ' "$RESULTS/pin.txt"
    check_absent "no value Forbric changes that native keeps (changed)" '^\[forge-swap\] pin changed ' "$RESULTS/pin.txt"
  fi
fi

step "result"
if [ "$FAIL" = 0 ]; then echo "[kernel] M59 forge-swap: GREEN"; else echo "[kernel] M59 forge-swap: RED"; fi
exit "$FAIL"
