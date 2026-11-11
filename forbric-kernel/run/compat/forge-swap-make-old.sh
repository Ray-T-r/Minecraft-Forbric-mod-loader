#!/usr/bin/env bash
# forge-swap-make-old.sh <out-dir> [--no-client]
#
# Write the OLD side of the Forge->NeoForge build swap (Stage 4 C5c): a world the kernel as released before
# MinecraftForge's removal (tag tri-eco-26.2-final, run/old-kernel.sh) wrote with the MinecraftForge builds of the
# candidate mods, the way a player's world was written. Nobody can write such a world any more once the old side is
# gone, so what this script makes is kept as a fixture (D54: ~/Forbric-backups/forge-swap/), and the swap itself
# (native-controls.py swap --engine forbric, later gate-m59) only reads it.
#
# Steps, each its own session with its own log under <out-dir>/logs/:
#   1. old create   a dedicated old server, a flat world from native-controls' seed, the Forge builds in mods/; the
#                   console script (forge-swap.py console) places every candidate's blocks, machines, chests and mobs
#                   in chunks 0,0..1,1, then the drill (forge-swap.py checks) proves on the old side what the NeoForge
#                   open will be asked; save-all, stop. The packedup bag in the BAG chest is FILLED here by the
#                   ForgeSwapBagFill agent (forge-swap.py THE BAGS): packedup's own server-side "open the bag, move
#                   stacks in", since no console line or datapack reaches it and a server has no player. The run fails
#                   unless the agent says `[forge-swap] bag filled: <BAG>` and the drill's packedup.bag.kept passes.
#   2. old player   (skipped by --no-client) a singleplayer client of the old kernel on that world. A datapack
#                   (forge-swap.py datapack) gives the player a kit once (inventory, armour, offhand, ender chest, xp,
#                   tombstone's knowledge/alignment and effect, an obsidian backpack in hotbar 0) after a death that
#                   leaves a tombstone grave; the same agent in the client's JVM fills that backpack from the
#                   PLAYER_BAG_SOURCE chest (`[forge-swap] bag filled: player slot 0`). No key is posted: the earlier
#                   key-press attempt at this step never filled a bag (9488359f). The client saves and quits by itself
#                   (KernelClientSmoke's clean disconnect).
#   3. old A/A      the datapack is removed and the world reopened once on the old server and saved: the export of
#                   THAT world is S0, so a loss that happened before the swap (an old bridge that never saved a
#                   mod's file, a capability that does not round-trip) shows in old-aa.diff and not as a swap loss.
#
# Every backpack inventory packedup saved is read back from the A/A's world (forge-swap.py bags) and written to
# MANIFEST as `packedup-bag <files>` and one `packedup-bag-stacks <file> <slot=item*count ...>` line each; a run where
# no bag holds a stack fails.
#
# Output (the frozen fixture's layout): old-save/ (the world after the A/A), old-config/ (the old instance's config/,
# with forge-swap.py's CONFIG_MARKS written into it after the create), old-snapshot.json (S0, world-snapshot.py
# export --chunks -2,-2,3,3), mods-forge/ and mods-neo/ (both builds of every candidate), MANIFEST (sha1s, the old
# kernel's commit and jar, the merged base, what ran), logs/, and old-create.snapshot.json / old-player.snapshot.json /
# old-aa.diff.json for the old side's own A/A.
#
# Jars: FORGE_SWAP_FORGE_DIR / FORGE_SWAP_NEO_DIR (default run/client-popular/forge-refused and run/client-popular/mods,
# the Stage 3 layout of the orchestrator's worktree). Heavy: run under .stage/heavy.sh, and client.sh first unless
# --no-client. Port: GATE_PORT. Exit 3 when the old side cannot be had here (old-kernel.sh says why).
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../lib.sh"
. "$HERE/../old-kernel.sh"

OUT="${1:-}"; [ -n "$OUT" ] || { echo "usage: forge-swap-make-old.sh <out-dir> [--no-client]" >&2; exit 2; }
CLIENT=1; [ "${2:-}" = "--no-client" ] && CLIENT=0
FS="python3 $HERE/forge-swap.py"
WS="python3 $HERE/world-snapshot.py"
BOX=-2,-2,3,3   # forge-swap.py BOX: the probe's chunks and the spawn area (the player's grave)
SEED=8035262   # native-controls.SEED: the native arm creates its world from the same seed
WORLD=ForgeSwap
FORGE_DIR="${FORGE_SWAP_FORGE_DIR:-$KERNEL/run/client-popular/forge-refused}"
NEO_DIR="${FORGE_SWAP_NEO_DIR:-$KERNEL/run/client-popular/mods}"
# The candidates (map-swap §5; D54): Forge build | NeoForge build of the same version.
CANDIDATES='brickfurnace-forge-26.2-5.2.1.0.jar|brickfurnace-neoforge-26.2-5.2.1.0.jar
classicpipes-forge-26.2-1.1.7.jar|classicpipes-neoforge-26.2-1.1.7.jar
packedup-1.1.1-forge-mc26.2.jar|packedup-1.1.1-neoforge-mc26.2.jar
supermartijn642corelib-1.1.24a-forge-mc26.2.jar|supermartijn642corelib-1.1.24a-neoforge-mc26.2.jar
supermartijn642configlib-1.1.8-forge-mc26.2.jar|supermartijn642configlib-1.1.8-neoforge-mc26.2.jar
tombstone-forge-26.2-9.9.3.jar|tombstone-neoforge-26.2-9.9.3.jar
BiomesOPlenty-forge-26.2-26.2.0.0.28.jar|BiomesOPlenty-neoforge-26.2-26.2.0.0.28.jar
TerraBlender-forge-26.2-26.2.0.0.2.jar|TerraBlender-neoforge-26.2-26.2.0.0.2.jar
GlitchCore-forge-26.2-26.2.0.0.0.jar|GlitchCore-neoforge-26.2-26.2.0.0.0.jar
journeymap-forge-26.2-6.0.8.jar|journeymap-neoforge-26.2-6.0.8.jar
animalgarden-owl-1.2.8-forge-26.2-65.0.0.jar|animalgarden-owl-1.2.8-neoforge-26.2.0.6.jar
aquariuslibs-1.2.0-forge-26.2-65.0.0.jar|aquariuslibs-1.2.0-neoforge-26.2.0.2.jar'

refuse_shared_rundir "$OUT"
mkdir -p "$OUT" || exit 2
OUT="$(cd "$OUT" && pwd -P)"
[ -z "$(ls -A "$OUT" 2>/dev/null)" ] || { echo "[kernel] FAIL $OUT is not empty: forge-swap-make-old.sh writes a fixture from nothing" >&2; exit 2; }
mkdir -p "$OUT/logs" "$OUT/mods-forge" "$OUT/mods-neo" "$OUT/work"
LOG="$OUT/logs/make-old.txt"
say() { echo "$*" | tee -a "$LOG"; }

old_kernel_init > "$OUT/logs/old-kernel.txt" 2>&1 || { cat "$OUT/logs/old-kernel.txt" >&2; exit 3; }
fsv() { python3 -c 'import importlib.util,sys;s=importlib.util.spec_from_file_location("f",sys.argv[1]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);print(eval(sys.argv[2],vars(m)))' "$HERE/forge-swap.py" "$1"; }
AGENT="$($FS bag-agent "$OUT/work/agent")" || { echo "[kernel] FAIL could not build the bag agent (ForgeSwapBagFill.java)" >&2; exit 1; }
BAG="$(fsv BAG)"
BAG_AGENT="-javaagent:$AGENT=$(fsv 'bag_agent_args(BAG, BAG_SOURCE)')"
PLAYER_BAG_AGENT="-javaagent:$AGENT=$(fsv 'bag_agent_args("player", PLAYER_BAG_SOURCE)')"
cat "$OUT/logs/old-kernel.txt" | tee -a "$LOG"

while IFS='|' read -r f n; do
  [ -f "$FORGE_DIR/$f" ] || { say "[kernel] FAIL missing Forge build $FORGE_DIR/$f"; exit 3; }
  [ -f "$NEO_DIR/$n" ] || { say "[kernel] FAIL missing NeoForge build $NEO_DIR/$n"; exit 3; }
  cp "$FORGE_DIR/$f" "$OUT/mods-forge/"; cp "$NEO_DIR/$n" "$OUT/mods-neo/"
done <<< "$CANDIDATES"

# The old launcher, with nothing of the new side's environment (old_launch's rule) and the old stage.
OLD_ENV=(--env "FORBRIC_OLD=$OLD_KERNEL_STAGE" --env "MERGED=" --env "MC_DIR=" --env "GRADLE_OPTS=-Dorg.gradle.daemon=false")
OLD_SERVER="$OLD_KERNEL_TREE/run/launch-kernel-server.sh"
OLD_CLIENT="$OLD_KERNEL_TREE/run/launch-kernel-client.sh"

server_dir() {  # server_dir <dir> — a server rundir with the Forge builds
  mkdir -p "$1/mods"; cp "$OUT"/mods-forge/*.jar "$1/mods/"
  echo "eula=true" > "$1/eula.txt"
  printf 'server-ip=127.0.0.1\nserver-port=%s\nonline-mode=false\nlevel-name=world\nlevel-seed=%s\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nmax-tick-time=-1\nview-distance=4\nsimulation-distance=4\npause-when-empty-seconds=0\nspawn-protection=0\nsync-chunk-writes=true\n' \
    "${GATE_PORT:-25565}" "$SEED" > "$1/server.properties"
}

# 1. old create
S1="$OUT/work/old-create"; server_dir "$S1"
$FS console > "$OUT/work/console.txt"; $FS checks > "$OUT/work/checks.txt"
say "[forge-swap] old create: $S1"
if ! $FS session --log "$OUT/logs/old-create.log" --cwd "$S1" --console "$OUT/work/console.txt" --console "$OUT/work/checks.txt" \
     "${OLD_ENV[@]}" --env "RUNDIR=$S1" --env "FORBRIC_JVM=$BAG_AGENT" -- "$OLD_SERVER" | tee -a "$LOG"; then
  say "[kernel] FAIL the old server did not create and save the world (logs/old-create.log)"; exit 1
fi
$FS drill "$OUT/logs/old-create.log" > "$OUT/logs/old-create.drill.txt"; tail -n 1 "$OUT/logs/old-create.drill.txt" | tee -a "$LOG"
grep -a "\[forge-swap\] bag" "$OUT/logs/old-create.log" | tee -a "$LOG"
grep -aq "\[forge-swap\] bag filled: $BAG " "$OUT/logs/old-create.log" || { say "[kernel] FAIL the bag agent did not fill the bag at $BAG (logs/old-create.log)"; exit 1; }
grep -aq "\[forge-swap\] PASS packedup.bag.kept" "$OUT/logs/old-create.drill.txt" || { say "[kernel] FAIL packedup.bag.kept on the old create (logs/old-create.drill.txt)"; exit 1; }
$WS export "$S1/world" --out "$OUT/old-create.snapshot.json" --chunks "$BOX" --label "old create" | tee -a "$LOG"
# One non-default value per candidate config (forge-swap.py CONFIG_MARKS): whether the NeoForge build reads the old
# file is otherwise invisible, since a reset to the default equals the default the Forge build wrote.
$FS mark-configs "$S1" | tee -a "$LOG" || { say "[kernel] FAIL could not mark the old configs"; exit 1; }
cp -c -R "$S1/world" "$OUT/work/old-world"
cp -c -R "$S1/config" "$OUT/work/old-config"

# 2. old player
if [ $CLIENT = 1 ]; then
  C="$OUT/work/old-client"; mkdir -p "$C/saves" "$C/mods"; cp "$OUT"/mods-forge/*.jar "$C/mods/"
  cp -c -R "$OUT/work/old-config" "$C/config"
  printf 'onboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\nskipMultiplayerWarning:true\njoinedFirstServer:true\nsoundCategory_master:0.0\nrenderDistance:4\nsimulationDistance:5\nguiScale:2\nnarrator:0\n' > "$C/options.txt"
  cp -c -R "$OUT/work/old-world" "$C/saves/$WORLD"
  $FS datapack "$C/saves/$WORLD" >> "$LOG"
  mkdir -p "$C/quickPlay"
  say "[forge-swap] old player: $C"
  $FS client --log "$OUT/logs/old-player.log" --rundir "$C" "${OLD_ENV[@]}" --env "RUNDIR=$C" \
    --env "FORBRIC_JVM=-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=${FORGE_SWAP_CLIENT_TICKS:-900} $PLAYER_BAG_AGENT" \
    --timeout 900 \
    -- "$OLD_CLIENT" --quickPlayPath "$C/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" | tee "$OUT/logs/old-player.result.json" | tee -a "$LOG"
  grep -aq '"cleanDisconnect": true' "$OUT/logs/old-player.result.json" || { say "[kernel] FAIL the old client did not save and quit cleanly (logs/old-player.log)"; exit 1; }
  grep -aq 'forge-swap\] kit given' "$OUT/logs/old-player.log" || { say "[kernel] FAIL the player kit never ran (logs/old-player.log)"; exit 1; }
  grep -a "\[forge-swap\] bag" "$OUT/logs/old-player.log" | tee -a "$LOG"
  grep -aq '\[forge-swap\] bag filled: player slot 0 ' "$OUT/logs/old-player.log" || { say "[kernel] FAIL the bag agent did not fill the player's backpack (logs/old-player.log)"; exit 1; }
  rm -rf "$OUT/work/old-world"; cp -c -R "$C/saves/$WORLD" "$OUT/work/old-world"
  rm -rf "$OUT/work/old-world/datapacks/forge-swap"
fi
$WS export "$OUT/work/old-world" --out "$OUT/old-player.snapshot.json" --chunks "$BOX" --label "old before A/A" | tee -a "$LOG"

# 3. old A/A
A="$OUT/work/old-aa"; server_dir "$A"; cp -c -R "$OUT/work/old-world" "$A/world"; cp -c -R "$OUT/work/old-config" "$A/config"
say "[forge-swap] old A/A: $A"
if ! $FS session --log "$OUT/logs/old-aa.log" --cwd "$A" "${OLD_ENV[@]}" --env "RUNDIR=$A" -- "$OLD_SERVER" | tee -a "$LOG"; then
  say "[kernel] FAIL the old server did not reopen and save the world (logs/old-aa.log)"; exit 1
fi
cp -c -R "$A/world" "$OUT/old-save"
cp -c -R "$A/config" "$OUT/old-config"
$FS configs "$A" | tee -a "$LOG" || say "[forge-swap] WARN the old A/A did not keep every config mark (see above)"
# What the bags hold, read from packedup's own saved files after the A/A (the Forge build loaded and re-saved them).
$FS bags "$OUT/old-save" > "$OUT/logs/old-bags.txt"; BAGS_RC=$?
tee -a "$LOG" < "$OUT/logs/old-bags.txt"
BAGS="$(ls "$OUT/old-save/packedup/backpacks/" 2>/dev/null | tr '\n' ' ' | sed 's/ *$//')"
[ $BAGS_RC = 0 ] || { say "[kernel] FAIL no packedup backpack inventory holds a stack in the old save (logs/old-bags.txt)"; exit 1; }
$WS export "$OUT/old-save" --out "$OUT/old-snapshot.json" --chunks "$BOX" --label "S0: old A/A" | tee -a "$LOG"
$WS diff "$OUT/old-player.snapshot.json" "$OUT/old-snapshot.json" --rules reopen --list 40 --json "$OUT/old-aa.diff.json" \
  > "$OUT/logs/old-aa.diff.txt" 2>&1
say "[forge-swap] old A/A $(tail -n 1 "$OUT/logs/old-aa.diff.txt")"

{
  echo "# forge-swap fixture, written by run/compat/forge-swap-make-old.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "old-kernel-ref tri-eco-26.2-final $OLD_KERNEL_COMMIT"
  echo "old-kernel-jar-sha1 $(shasum -a 1 "$OLD_KERNEL_JAR" | cut -d' ' -f1)"
  echo "old-merged-base-sha1 $(shasum -a 1 "$OLD_KERNEL_STAGE/run/merged-base/patched-mc-merged-26.2.jar" | cut -d' ' -f1)"
  echo "seed $SEED"; echo "box $BOX"; echo "client $CLIENT"
  # The backpack inventories packedup saved, and what each holds (forge-swap.py bags on old-save).
  echo "packedup-bag ${BAGS:-none}"
  sed -n 's/^\[forge-swap\] bag \(inventory[0-9]*\.nbt\): [0-9]* stack(s) /packedup-bag-stacks \1 /p' "$OUT/logs/old-bags.txt"
  echo "tree $(git -C "$KERNEL" rev-parse HEAD)"
  echo "drill $(tail -n 1 "$OUT/logs/old-create.drill.txt")"
  for d in mods-forge mods-neo; do for j in "$OUT/$d"/*.jar; do echo "$d $(shasum -a 1 "$j" | cut -d' ' -f1) $(basename "$j")"; done; done
  echo "old-snapshot.json $(shasum -a 1 "$OUT/old-snapshot.json" | cut -d' ' -f1)"
} > "$OUT/MANIFEST"
rm -rf "$OUT/work"
say "[forge-swap] old side written: $OUT (MANIFEST)"
