#!/usr/bin/env bash
# Build Forbric's raw-Forge TEST mods (compiled against the runtime-supplied Forge + patched MC; never
# redistributed). Currently: forbriclive (stage-5 gameplay-event + persistence canary, run/livemod-src).
# Output: run/forge-runtime/<name>.jar — stage into a rundir's mods/ to use.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/forge-runtime/work"
RUNTIME="$HERE/forge-runtime/forge-runtime.jar"
PATCHED="${PATCHED:-$HERE/forge-patched/patched-mc-forge-26.2.jar}"
MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
CENTRAL=https://repo1.maven.org/maven2

[ -f "$RUNTIME" ] || { echo "forge-runtime.jar missing - run assemble-minecraftforge-runtime.sh" >&2; exit 2; }
ANNOT="$WORK/dl/annotations-24.1.0.jar"
[ -f "$ANNOT" ] || curl -sS -L -o "$ANNOT" "$CENTRAL/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar"
VLIBS="$(find "$MC/libraries" -name '*.jar' 2>/dev/null | paste -sd: -)"

build_one() { # <src-dir> <out-jar>
  local src="$1" out="$2" classes
  classes="$WORK/testmod-classes-$(basename "$out" .jar)"
  rm -rf "$classes"; mkdir -p "$classes"
  find "$src" -name '*.java' -print0 | xargs -0 javac --release 17 -proc:none \
    -cp "$RUNTIME:$PATCHED:$ANNOT:$VLIBS" -d "$classes"
  mkdir -p "$classes/META-INF"
  cp "$src/META-INF/mods.toml" "$classes/META-INF/mods.toml"
  (cd "$classes" && jar --create --file "$out" .)
  echo "[testmods] wrote $out"
}

build_one "$HERE/livemod-src" "$HERE/forge-runtime/forbriclive.jar"
