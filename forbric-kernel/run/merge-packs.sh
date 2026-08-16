#!/usr/bin/env bash
# Merge two modpack folders into ONE Forbric instance.
#
# Forbric runs Fabric and Forge-family mods in a single game, so two packs can simply be poured into one rundir.
# What makes it non-obvious is what happens to the overlap: a mod installed by BOTH packs (Sodium, Iris, Jade,
# lithostitched … — 10 of them across the two packs this was written against) exists as two different jars claiming
# one mod id. The kernel arbitrates that at boot, keeps one copy, gives the other side back the mod's identity, and
# writes forbric-mods.txt + .forbric-kernel/merge-report.txt explaining what it chose. This script only has to
# assemble the folder; it deliberately does NOT try to resolve overlap itself.
#
# Usage: merge-packs.sh <pack-A-dir> <pack-B-dir> <target-rundir>
#   where each pack dir is an instance folder (the one containing mods/).
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

A="${1:?usage: merge-packs.sh <pack-A-dir> <pack-B-dir> <target-rundir>}"
B="${2:?usage: merge-packs.sh <pack-A-dir> <pack-B-dir> <target-rundir>}"
OUT="${3:?usage: merge-packs.sh <pack-A-dir> <pack-B-dir> <target-rundir>}"

for d in "$A" "$B"; do
  [ -d "$d/mods" ] || { echo "[kernel] FATAL: $d has no mods/ — is it an instance folder?" >&2; exit 2; }
done
[ "$(cd "$A" && pwd)" = "$(cd "$B" && pwd)" ] && { echo "[kernel] FATAL: both packs are the same folder" >&2; exit 2; }

mkdir -p "$OUT/mods" "$OUT/config" "$OUT/shaderpacks"

# ditto, not cp -R: pack folders routinely carry CJK filenames, and this project has already been bitten by
# codepage mangling once (see fetch-curseforge-pack.sh).
copy() { ditto "$1" "$2" 2>/dev/null || cp -R "$1" "$2"; }

step "mods/"
mods=0; clashes=0
for src in "$A" "$B"; do
  for jar in "$src/mods"/*.jar; do
    [ -f "$jar" ] || continue
    name=$(basename "$jar")
    if [ -e "$OUT/mods/$name" ]; then
      # The SAME FILE in both packs. Not the interesting case — that is two DIFFERENT jars of one mod, which the
      # kernel handles at boot. This is just the same download twice.
      echo "[kernel] same file in both packs, kept once: $name"
      clashes=$((clashes + 1)); continue
    fi
    copy "$jar" "$OUT/mods/$name"; mods=$((mods + 1))
  done
done
echo "[kernel] $mods jar(s)$([ "$clashes" -gt 0 ] && echo ", $clashes duplicate filename(s) collapsed")"

step "config/ + shaderpacks/ (first pack wins; nothing is overwritten)"
for src in "$A" "$B"; do
  for sub in config shaderpacks; do
    [ -d "$src/$sub" ] || continue
    for entry in "$src/$sub"/*; do
      [ -e "$entry" ] || continue
      name=$(basename "$entry")
      [ -e "$OUT/$sub/$name" ] && continue
      copy "$entry" "$OUT/$sub/$name"
    done
  done
done
echo "[kernel] config: $(ls -1 "$OUT/config" 2>/dev/null | wc -l | tr -d ' ') entr(ies), shaderpacks: $(ls -1 "$OUT/shaderpacks" 2>/dev/null | wc -l | tr -d ' ')"

step "options.txt"
# Without one, Minecraft shows the accessibility onboarding screen on first launch, which sits in front of
# everything — including --quickPlaySingleplayer, which then appears to do nothing.
if [ ! -f "$OUT/options.txt" ]; then
  for src in "$A" "$B"; do
    if [ -f "$src/options.txt" ]; then cp "$src/options.txt" "$OUT/options.txt"; echo "[kernel] took options.txt from $(basename "$src")"; break; fi
  done
fi
[ -f "$OUT/options.txt" ] || echo "[kernel] NOTE neither pack had options.txt — the first launch will show the accessibility screen"

step "done"
echo "[kernel] instance: $OUT"
echo "[kernel] launch it with:"
echo
echo "    RUNDIR=$OUT $KERNEL/run/launch-kernel-client.sh"
echo
echo "[kernel] mods installed by BOTH packs are arbitrated at boot — after the first launch, read"
echo "[kernel]   $OUT/.forbric-kernel/merge-report.txt   (what was chosen, and why)"
echo "[kernel]   $OUT/forbric-mods.txt                   (edit one line to choose the other copy)"
