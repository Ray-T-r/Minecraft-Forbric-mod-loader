#!/usr/bin/env bash
# The kernel as it was released before MinecraftForge was dropped, built and launched beside the current one.
#
# WHY THIS EXISTS. A player's world was written by a Forbric from before MinecraftForge's removal (v0.3.1-beta2 and
# earlier, and the 0.4.0-dev builds of that line): the merged base (vanilla + MinecraftForge + NeoForge byte-merged) with
# both carriers. The current tree runs NeoForge's own patched game and one carrier. Whether that world still opens, with
# everything in it, can only be asked of a world the OLD kernel really wrote; a save this tree writes and reads back says
# nothing about the upgrade. gate-m58 (world upgrade) and gate-m59 (Forge->NeoForge build swap) both start by writing one
# with this kernel.
#
# The old side is ONE ref: the tag tri-eco-26.2-final (187143a1, the last commit with the three-ecosystem base). It is
# taken out of git with `git archive` into a cache keyed by commit, never as a second worktree, and nothing in the
# branch's own tree is written: the old tree keeps its own build/ and its own launchers.
#
# TWO WAYS IN.
#   Sourced, after lib.sh:   . "$KERNEL/run/old-kernel.sh"; old_kernel_init || exit $?
#     then old_launch server|client [game args] (RUNDIR=... in the environment), or old_boot_flat <rundir>.
#   Run:   run/old-kernel.sh build            build (or reuse) the old tree; prints the two "[kernel] old kernel" lines
#          run/old-kernel.sh boot-flat <dir>  build, then boot a fresh flat world in <dir> on the old server and stop it
#          run/old-kernel.sh server|client [game args]   the old launcher, RUNDIR from the environment
#   A game JVM is a heavy command: wrap boot-flat/server/client in .stage/heavy.sh (and client.sh for a client).
#
# Knobs (all optional):
#   FORBRIC_OLD_KERNEL_CACHE   where archived trees are built (default $KERNEL/build/old-kernel); one dir per commit
#   FORBRIC_OLD_KERNEL_ONLINE  =1 lets the build resolve the tag's Gradle dependencies online (the only download this
#                              script can cause, and only Gradle dependency resolution, D68). Needed once on a machine
#                              whose Gradle cache lacks them; every later build and every old launch is --offline.
#   M58_OLD_STAGE              a staged tree (the parent of run/merged-base/) the old kernel boots; else
#   FORBRIC_MERGED_STAGE       the same, by its older name; else the first of <worktree>/.stage and
#                              $KERNEL/../forbric-loader that has the merged base; else the backup tarball
#   FORBRIC_OLD_STAGE_TARBALL  staged-artifacts.tar.gz of the tag (default ~/Forbric-backups/2026-10-30-tri-eco-26.2-final/)
#
# A BUILT TREE IS CHECKED, NOT TRUSTED. `.built` holds the boot classpath the build printed, and a tree counts as built
# only while its kernel jar and every entry of that classpath exist. The first version wrote an empty marker: when the
# Gradle cache was wiped (2026-11-08) the marker still said "built", this script skipped the build, and the old launcher
# then failed its own `gradlew --offline jar` on the first boot, far from the cause. Now a stale tree is rebuilt here,
# and a rebuild that cannot resolve offline stops here, naming FORBRIC_OLD_KERNEL_ONLINE=1.
#
# Exit 3 (SKIP-FATAL) when something the old side needs is not on this machine; the message names what to do.

# Run as a command, lib.sh comes first: KERNEL (and so the cache default below) is set there.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  . "$(cd "$(dirname "$0")" && pwd)/lib.sh"
fi

OLD_KERNEL_REF="tri-eco-26.2-final"
OLD_KERNEL_BACKUP="${FORBRIC_OLD_KERNEL_BACKUP:-$HOME/Forbric-backups/2026-10-30-tri-eco-26.2-final}"
OLD_KERNEL_TARBALL="${FORBRIC_OLD_STAGE_TARBALL:-$OLD_KERNEL_BACKUP/staged-artifacts.tar.gz}"
# The tarball's sha1, from the backup's own SHA1SUMS when it was made (2026-10-30). A tarball that does not hash to this
# is not the tag's stage, and an old kernel booted on another base would answer a different question.
OLD_KERNEL_TARBALL_SHA1="5befce983c1692efe4f9d8126012eb76889b79c7"
# The merged base inside that tarball (measured 2026-11-09; the stage worktree's .stage copy hashes the same). A stage
# found any other way must carry this exact jar: the pins line alone would also match a merged base rebuilt later.
OLD_KERNEL_MERGED_SHA1="ca98f14791645a512013ed97a7482d42b473a4a4"
OLD_KERNEL_CACHE="${FORBRIC_OLD_KERNEL_CACHE:-$KERNEL/build/old-kernel}"

_old_fatal() { echo "[kernel] SKIP-FATAL: $*" >&2; return 3; }

# _old_built_ok <root> — the tree's jar exists and every entry of the classpath recorded in .built still exists.
_old_built_ok() {
  local root="$1" cp entry
  [ -f "$root/forbric-kernel/build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar" ] || return 1
  [ -s "$root/.built" ] || return 1
  cp="$(head -n 1 "$root/.built")"
  [ -n "$cp" ] || return 1
  local IFS=:
  for entry in $cp; do
    [ -e "$entry" ] || { echo "[kernel] old kernel: $root/.built names $entry, which is gone; rebuilding" >&2; return 1; }
  done
  return 0
}

# archived_kernel <commit> — print the path of a forbric-kernel tree at <commit>, archived out of git and built once.
#
# What the archive needs, measured: forbric-kernel-installer (the old build.gradle compiles its sources in) and tools;
# and forbric-kernel/.dev/api (fabric-api 0.155.2 + Team Reborn Energy 5.0.0), which is untracked. Without it the old
# launcher's own `gradlew jar` fails :verifyRebornEnergy and refuses to launch ("kernel jar build failed"), so it is
# linked to this tree's .dev/api — the same API jars this tree compiles against.
#
# Built with the daemon off: the old launcher calls gradlew again on every launch (an up-to-date check), and a daemon
# left behind by an archived tree is one more JVM nobody owns.
archived_kernel() {
  local commit="$1" root tree log out cp online="--offline"
  root="$OLD_KERNEL_CACHE/$commit"
  tree="$root/forbric-kernel"
  if [ ! -f "$root/.archived" ]; then
    rm -rf "$root"; mkdir -p "$root"
    # From the repository's top: run in forbric-kernel/, `git archive` reads the paths relative to it and refuses the
    # two outside it.
    if ! git -C "$(git -C "$KERNEL" rev-parse --show-toplevel)" archive "$commit" forbric-kernel forbric-kernel-installer tools \
        | tar -x -C "$root"; then
      rm -rf "$root"
      _old_fatal "could not archive $commit out of git"; return 3
    fi
    : > "$root/.archived"
  fi
  if [ ! -e "$tree/.dev/api" ]; then
    [ -d "$KERNEL/.dev/api" ] || { _old_fatal "no $KERNEL/.dev/api (fabric-api + energy API jars) to lend the archived tree"; return 3; }
    [ -L "$tree/.dev/api" ] && rm -f "$tree/.dev/api"   # dangling: the tree it pointed into is gone
    mkdir -p "$tree/.dev"
    ln -s "$(cd "$KERNEL/.dev/api" && pwd -P)" "$tree/.dev/api"
  fi
  if ! _old_built_ok "$root"; then
    rm -f "$root/.built"
    log="$root/build.log"; out="$root/classpath.out"
    [ "${FORBRIC_OLD_KERNEL_ONLINE:-}" = 1 ] && online=""
    echo "[kernel] old kernel: building $commit (${online:-online dependency resolution}; log $log)" >&2
    if ! env GRADLE_OPTS="-Dorg.gradle.daemon=false" FORBRIC_OLD="${OLD_KERNEL_STAGE:-$OLD}" \
        "$tree/gradlew" $online --no-daemon -q -p "$tree" jar printBootClasspath > "$out" 2> "$log"; then
      grep -vE 'WARNING: |native-access|Restricted method|--enable-native' "$log" >&2
      if grep -aq 'No cached version' "$log"; then
        _old_fatal "the kernel at $commit needs Gradle dependencies this machine's cache does not hold; resolve them once with FORBRIC_OLD_KERNEL_ONLINE=1 (log $log)"
      else
        _old_fatal "the kernel at $commit did not build (log $log)"
      fi
      return 3
    fi
    cp="$(grep -E '\.jar' "$out" | tail -n 1)"
    [ -n "$cp" ] || { _old_fatal "the kernel at $commit built but printed no boot classpath ($out)"; return 3; }
    printf '%s\n' "$cp" > "$root/.built"
    _old_built_ok "$root" || { rm -f "$root/.built"; _old_fatal "the kernel at $commit printed a boot classpath naming files that do not exist ($out)"; return 3; }
  fi
  echo "$tree"
}

# _old_stage — set OLD_KERNEL_STAGE to a staged tree carrying the merged base the tag boots (see the knobs above).
_old_stage() {
  local c here wt got
  here="$(cd "$KERNEL/.." && pwd)"
  OLD_KERNEL_STAGE=""
  for c in "${M58_OLD_STAGE:-}" "${FORBRIC_MERGED_STAGE:-}" "$here/.stage" "$here/forbric-loader"; do
    [ -n "$c" ] || continue
    if [ -f "$c/run/merged-base/patched-mc-merged-26.2.jar" ]; then OLD_KERNEL_STAGE="$(cd "$c" && pwd)"; break; fi
  done
  if [ -z "$OLD_KERNEL_STAGE" ]; then
    # After Stage 5 no tree on this machine carries a merged stage: the tag's own staged set, from the backup.
    [ -f "$OLD_KERNEL_TARBALL" ] || { _old_fatal "no merged stage (set M58_OLD_STAGE to a tree with run/merged-base/) and no backup tarball at $OLD_KERNEL_TARBALL"; return 3; }
    wt="$OLD_KERNEL_CACHE/stage-$OLD_KERNEL_TARBALL_SHA1"
    if [ ! -f "$wt/.extracted" ]; then
      got="$(shasum -a 1 "$OLD_KERNEL_TARBALL" | cut -d' ' -f1)"
      [ "$got" = "$OLD_KERNEL_TARBALL_SHA1" ] || { _old_fatal "$OLD_KERNEL_TARBALL hashes to $got, not the tag's $OLD_KERNEL_TARBALL_SHA1"; return 3; }
      rm -rf "$wt"; mkdir -p "$wt/run"
      tar -xzf "$OLD_KERNEL_TARBALL" -C "$wt/run" || { rm -rf "$wt"; _old_fatal "could not extract $OLD_KERNEL_TARBALL"; return 3; }
      : > "$wt/.extracted"
    fi
    OLD_KERNEL_STAGE="$wt"
  fi
  # The tag's base was built with NeoForge 26.2.0.93; a stage that says otherwise is some other merged base.
  if ! grep -q 'neoforge=26.2.0.93' "$OLD_KERNEL_STAGE/run/merged-base/patched-mc-merged-26.2.jar.pins" 2>/dev/null; then
    _old_fatal "$OLD_KERNEL_STAGE/run/merged-base is not the tag's merged base (pins do not read neoforge=26.2.0.93)"; return 3
  fi
  got="$(shasum -a 1 "$OLD_KERNEL_STAGE/run/merged-base/patched-mc-merged-26.2.jar" | cut -d' ' -f1)"
  [ "$got" = "$OLD_KERNEL_MERGED_SHA1" ] || { _old_fatal "$OLD_KERNEL_STAGE/run/merged-base/patched-mc-merged-26.2.jar hashes to $got, not the tag's merged base $OLD_KERNEL_MERGED_SHA1"; return 3; }
}

# old_kernel_init — resolve the tag, its stage and its built tree. Sets OLD_KERNEL_COMMIT, OLD_KERNEL_STAGE,
# OLD_KERNEL_TREE, OLD_KERNEL_JAR. Returns 3 with a SKIP-FATAL line when the old side cannot be had here.
old_kernel_init() {
  OLD_KERNEL_COMMIT="$(git -C "$KERNEL" rev-parse -q --verify "$OLD_KERNEL_REF^{commit}" 2>/dev/null)" || OLD_KERNEL_COMMIT=""
  if [ -z "$OLD_KERNEL_COMMIT" ]; then
    _old_fatal "tag $OLD_KERNEL_REF is not in this clone; fetch it from the backup bundle by hand: git fetch $OLD_KERNEL_BACKUP/forbric-tri-eco-26.2-final.bundle 'refs/tags/*:refs/tags/*'"
    return 3
  fi
  _old_stage || return 3
  OLD_KERNEL_TREE="$(archived_kernel "$OLD_KERNEL_COMMIT")" || return 3
  OLD_KERNEL_JAR="$OLD_KERNEL_TREE/build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar"
  echo "[kernel] old kernel: $OLD_KERNEL_REF = $OLD_KERNEL_COMMIT, tree $OLD_KERNEL_TREE, stage $OLD_KERNEL_STAGE/run"
  echo "[kernel] old kernel jar sha1 $(shasum -a 1 "$OLD_KERNEL_JAR" | cut -d' ' -f1), merged base sha1 $(shasum -a 1 "$OLD_KERNEL_STAGE/run/merged-base/patched-mc-merged-26.2.jar" | cut -d' ' -f1)"
}

# old_launch <server|client> [args...] — the archived tree's own launcher, with the old stage, and nothing of the new
# side's environment: the current launchers refuse MERGED/FORGE_RT and these read them, so neither may leak across.
# RUNDIR, FORBRIC_JVM, FORBRIC_COMPAT_POLICY and NEO_RT/FORGE_RT (installed-style file names) pass through.
old_launch() {
  local kind="$1"; shift
  env -u MERGED -u MC_DIR GRADLE_OPTS="-Dorg.gradle.daemon=false" FORBRIC_OLD="$OLD_KERNEL_STAGE" \
    "$OLD_KERNEL_TREE/run/launch-kernel-$kind.sh" "$@"
}

# old_boot_flat <rundir> — a fresh flat world on the OLD server: boot to Done, stop, and check the save is on disk.
# Prints [kernel] PASS/FAIL lines; returns 0 only when every check passed. The world is <rundir>/world. This is U1's
# smoke, and the first step an old-side gate can reuse before it adds its own mods. <rundir> is wiped first.
old_boot_flat() {
  local rundir="$1" log pid bad=0
  refuse_shared_rundir "$rundir"
  rm -rf "$rundir"; mkdir -p "$rundir"
  rundir="$(cd "$rundir" && pwd)"
  log="$rundir/old-boot.log"
  echo "eula=true" > "$rundir/eula.txt"
  printf 'level-seed=forbrickernel\nserver-port=%s\nonline-mode=false\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\n' \
    "$GATE_PORT" > "$rundir/server.properties"
  : > "$log"
  # The old boot includes the old launcher's own `gradlew --offline jar` up-to-date check: wait longer than a gate does.
  STOP_READY_CAP="${STOP_READY_CAP:-420}" stop_when_ready "$log" 5 | RUNDIR="$rundir" old_launch server > "$log" 2>&1 &
  pid=$!
  record_server_pid "$rundir" "$pid"
  FAIL=0
  await_server "$pid" "$log" "${OLD_BOOT_LIMIT:-600}"
  [ "$FAIL" = 0 ] || bad=1
  local what pattern
  while IFS='|' read -r what pattern; do
    if grep -aqE "$pattern" "$log"; then echo "[kernel] PASS $what"; else echo "[kernel] FAIL $what (no /$pattern/ in $log)"; bad=1; fi
  done <<'CHECKS'
the old launcher booted the tag's merged base|merged base = .*/merged-base/patched-mc-merged-26\.2\.jar
the old launcher owned both carriers|owned carriers: forge-runtime-interop\.jar, neoforge-runtime\.jar
the old server reached Done|Done \(
the old server saved every dimension and stopped|All dimensions are saved
CHECKS
  # 26.2 keeps the generator in data/minecraft/world_gen_settings.dat, not level.dat. Read through world-snapshot.py, the
  # exporter gate-m58/m59 diff old worlds with, so a world this smoke passes is one that exporter can read.
  if [ -f "$rundir/world/level.dat" ] \
      && python3 "$KERNEL/run/compat/world-snapshot.py" export "$rundir/world" --out "$rundir/old-flat.snapshot.json" \
           --label "old-kernel boot-flat" > "$rundir/old-flat.export.txt" 2>&1 \
      && python3 - "$rundir/old-flat.snapshot.json" <<'PY'
import json, sys
snap = json.load(open(sys.argv[1], encoding="utf-8"))
settings = snap["saved_data"].get("data/minecraft/world_gen_settings.dat")
sys.exit(0 if settings is not None and "minecraft:flat" in json.dumps(settings) and snap["blocks"] else 1)
PY
  then
    echo "[kernel] PASS the old server wrote a flat world (world-snapshot.py export: $(tail -n 1 "$rundir/old-flat.export.txt" | sed 's/^\[snapshot\] exported [^:]*: //'); world_gen_settings names minecraft:flat)"
  else
    echo "[kernel] FAIL the old server wrote a flat world (no level.dat, world-snapshot.py could not export it, or data/minecraft/world_gen_settings.dat does not name minecraft:flat; $rundir/old-flat.export.txt)"; bad=1
  fi
  return $bad
}

# Run as a command (not sourced): one of the subcommands in the header.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  cmd="${1:-}"; [ $# -gt 0 ] && shift
  case "$cmd" in
    build) old_kernel_init ;;
    boot-flat)
      [ $# -eq 1 ] || { echo "usage: old-kernel.sh boot-flat <rundir>" >&2; exit 2; }
      old_kernel_init || exit $?
      old_boot_flat "$1" ;;
    server|client) old_kernel_init || exit $?; old_launch "$cmd" "$@" ;;
    *) echo "usage: old-kernel.sh build | boot-flat <rundir> | server|client [game args]" >&2; exit 2 ;;
  esac
  exit $?
fi
