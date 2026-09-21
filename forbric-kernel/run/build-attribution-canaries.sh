#!/usr/bin/env bash
# Builds the three single-defect canaries gate-m30 stages: a Fabric mod with two mixins that go wrong (one unfit,
# one failing at apply on a class first loaded at world creation), a NeoForge mod with a subscriber whose <clinit>
# throws and one waiting on a dead event, and a NeoForge mod compiled against a class this instance does not
# carry, touched from a deferred setup task. Each is ONE defect, so the gate can assert ONE attribution each.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SRC="$KERNEL/canary/attribution"
OUT="$KERNEL/run/canary"
canary_scratch attribution
MERGED="$OLD/run/merged-base/patched-mc-merged-26.2.jar"
NEO_RT="$OLD/run/neoforge-runtime/neoforge-runtime.jar"
NEO_MC="$OLD/run/neoforge-patched/patched-mc-neoforge-26.2.jar"
FORGE_RT="$OLD/run/merged-base/forge-runtime-interop.jar"; [ -f "$FORGE_RT" ] || FORGE_RT="$OLD/run/forge-runtime/forge-runtime.jar"
MC_DIR="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
VLIBS="$(find "$MC_DIR/libraries" -name '*.jar' 2>/dev/null | paste -sd: -)"

step "prerequisites"
for f in "$MERGED" "$NEO_RT" "$NEO_MC"; do
  [ -f "$f" ] || { echo "[kernel] FAIL missing: $f"; exit 1; }
done
MIXIN="$(find "$MC_DIR/libraries/net/fabricmc/sponge-mixin" -name '*.jar' 2>/dev/null | sort | tail -1)"
[ -n "$MIXIN" ] || MIXIN="$(find "$HOME/.gradle/caches/modules-2/files-2.1/net.fabricmc/sponge-mixin" -name '*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)"
[ -n "$MIXIN" ] || { echo "[kernel] FAIL sponge-mixin jar not found"; exit 1; }
mkdir -p "$WORK" "$OUT"

build_fabric() { # <mod id>
  local id="$1" classes="$WORK/$1"
  mkdir -p "$classes"
  javac -nowarn -proc:none --release 21 -cp "$MERGED:$MIXIN:$NEO_RT:$VLIBS" -d "$classes" \
        $(find "$SRC/$id/src" -name '*.java') 2>&1 | grep -v '^Note:' || true
  [ -n "$(find "$classes" -name '*.class')" ] || { echo "[kernel] FAIL $id did not compile"; exit 1; }
  cp "$SRC/$id/fabric.mod.json" "$SRC/$id/$id.mixins.json" "$classes/"
  (cd "$classes" && jar --create --file "$WORK/$id.jar" .) || exit 1
  publish_canary "$WORK/$id.jar" "$OUT/$id.jar" || exit 1
  echo "[kernel] built $OUT/$id.jar"
}

build_neo() { # <mod id> [stub dir]
  local id="$1" classes="$WORK/$1" stubs="${2:-}" stubclasses="$WORK/$1-stubs"
  mkdir -p "$classes/META-INF"
  local cp="$NEO_RT:$NEO_MC:$VLIBS"
  if [ -n "$stubs" ]; then
    mkdir -p "$stubclasses"
    javac -nowarn -proc:none --release 21 -d "$stubclasses" $(find "$stubs" -name '*.java') || exit 1
    cp="$cp:$stubclasses"
  fi
  javac -nowarn -proc:none --release 21 -cp "$cp" -d "$classes" \
        $(find "$SRC/$id/src" -name '*.java') 2>&1 | grep -v '^Note:' || true
  [ -n "$(find "$classes" -name '*.class')" ] || { echo "[kernel] FAIL $id did not compile"; exit 1; }
  cp "$SRC/$id/META-INF/neoforge.mods.toml" "$classes/META-INF/"
  (cd "$classes" && jar --create --file "$WORK/$id.jar" .) || exit 1
  # The stub must NOT be in the jar: the defect is that the class is not here. Checked on the STAGED jar, so a
  # jar that fails its own check is never published for a gate to pick up.
  if unzip -l "$WORK/$id.jar" | grep -q ForbricVanishedEvent; then echo "[kernel] FAIL the stub leaked into $id.jar"; exit 1; fi
  publish_canary "$WORK/$id.jar" "$OUT/$id.jar" || exit 1
  echo "[kernel] built $OUT/$id.jar"
}

build_forge() { # <mod id>
  local id="$1" classes="$WORK/$1"
  mkdir -p "$classes/META-INF"
  javac -nowarn -proc:none --release 21 -cp "$FORGE_RT:$MERGED:$VLIBS" -d "$classes" \
        $(find "$SRC/$id/src" -name '*.java') 2>&1 | grep -v '^Note:' || true
  [ -n "$(find "$classes" -name '*.class')" ] || { echo "[kernel] FAIL $id did not compile"; exit 1; }
  cp "$SRC/$id/META-INF/mods.toml" "$classes/META-INF/"
  (cd "$classes" && jar --create --file "$WORK/$id.jar" .) || exit 1
  publish_canary "$WORK/$id.jar" "$OUT/$id.jar" || exit 1
  echo "[kernel] built $OUT/$id.jar"
}

step "compile the canaries"
build_fabric forbricmixincanary
build_neo forbricsubscribercanary
build_neo forbricabicanary "$SRC/forbricabicanary/stub"
build_forge forbricforgecanary

step "result"
echo "[kernel] ✅ built forbricmixincanary.jar, forbricsubscribercanary.jar, forbricabicanary.jar, forbricforgecanary.jar in $OUT"
