#!/usr/bin/env bash
# Print the path to the vanilla-reference sidecar for a game base — the jar both launchers hand the kernel as
# --nativeReference — building it when the staged tree has none that is current.
#
# WHAT IT IS. A RESOURCES-ONLY jar that says how vanilla Minecraft compiled each class, measured against the base the
# game runs on (META-INF/forbric/native-reference/FABRIC/: an index of every vanilla class and its hash, plus the vanilla
# bytes of every class the base changed). The kernel's mixin adapters compare a Fabric mixin's vanilla target against
# it. The merged base used to embed the same tree; a base that does not (the genuine NeoForge-patched jar) has no
# FABRIC references without this, and every adapter that needs them stands down without a word. The installer builds
# it for a player's profile; tools/dev.py prepare stages it at <stage>/vanilla-reference/. This covers a staged tree
# that predates it, or one this checkout must not write into.
#
# WHERE IT COMES FROM, in order:
#   1. $VANILLA_REFERENCE_JAR, when set and present — a gate or a developer naming one explicitly. Used as named while
#      it records this base. One that records another base is REFUSED (exit 6, the reason on stderr, nothing on
#      stdout): on the genuine NeoForge base no embedded index stands behind the sidecar, so a sidecar measured against
#      another base silently costs every changed class without a vanilla copy its FABRIC reference. The launchers and
#      gate-m34's soak stop on it (a launch without the flag would cost every FABRIC reference). For a deliberate experiment,
#      FORBRIC_VANILLA_REFERENCE_ANY_BASE=1 uses it anyway, with the mismatch said on stderr.
#   2. <stage>/vanilla-reference/vanilla-reference-26.2.jar, when it records THIS base (what prepare staged).
#   3. build/vanilla-reference/<base-key>/vanilla-reference-26.2.jar in this checkout, built by the installer's own
#      VanillaReference.java run as a single-file program: the same writer, so the same bytes. Reused while it records
#      this base, else rebuilt.
#
# CURRENT means provenance, never file age: every sidecar records the SHA-1 of the base and the vanilla jar it was
# measured from (META-INF/forbric/vanilla-reference.properties), and it is current only while the base being launched
# (and, when it is here, the vanilla jar) hash to those. A base rebuilt, restaged or swapped for another one (the genuine
# jar staged beside the merged one) can be older than a sidecar that was never measured against it.
#
# Usage: vanilla-reference-jar.sh <base.jar> [<vanilla.jar>]   → prints the jar path on stdout (nothing else goes there)
# The vanilla jar defaults to $MC_DIR's versions/26.2/26.2.jar. Exits non-zero, printing nothing, when it cannot provide
# one; the launchers then start without the flag, which the kernel reports in its "native references:" line.
set -uo pipefail

BASE="${1:?usage: vanilla-reference-jar.sh <base.jar> [<vanilla.jar>]}"
MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
VANILLA="${2:-$MC/versions/26.2/26.2.jar}"
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/.." && pwd)"
NAME="vanilla-reference-26.2.jar"

[ -f "$BASE" ] || { echo "[vanilla-reference] base not found: $BASE" >&2; exit 2; }

sha1_of() { shasum -a 1 "$1" | cut -d' ' -f1; }
# What a sidecar records for one input (base-sha1, vanilla-sha1), or nothing for a file that is not one.
recorded() { unzip -p "$1" META-INF/forbric/vanilla-reference.properties 2>/dev/null | tr -d '\r' | sed -n "s/^$2=//p" | head -n 1; }
BASE_SHA1="$(sha1_of "$BASE")"
VANILLA_SHA1=""
[ -f "$VANILLA" ] && VANILLA_SHA1="$(sha1_of "$VANILLA")"
# current <jar>: it records the base being launched, and the vanilla jar when that is here to compare.
current() {
  [ -f "$1" ] && [ "$(recorded "$1" base-sha1)" = "$BASE_SHA1" ] \
    && { [ -z "$VANILLA_SHA1" ] || [ "$(recorded "$1" vanilla-sha1)" = "$VANILLA_SHA1" ]; }
}

if [ -n "${VANILLA_REFERENCE_JAR:-}" ] && [ -f "$VANILLA_REFERENCE_JAR" ]; then
  if ! current "$VANILLA_REFERENCE_JAR"; then
    if [ "${FORBRIC_VANILLA_REFERENCE_ANY_BASE:-}" != 1 ]; then
      echo "[vanilla-reference] REFUSED: \$VANILLA_REFERENCE_JAR=$VANILLA_REFERENCE_JAR records another base (or vanilla" \
        "jar) than $BASE; a base without an embedded index has nothing to answer what it cannot verify. Unset it to get" \
        "this base's sidecar, or set FORBRIC_VANILLA_REFERENCE_ANY_BASE=1 to use it anyway" >&2
      exit 6
    fi
    echo "[vanilla-reference] $VANILLA_REFERENCE_JAR records another base than $BASE; using it as named because" \
      "FORBRIC_VANILLA_REFERENCE_ANY_BASE=1 (on a base that embeds its own index the kernel answers what it cannot" \
      "verify from that index; on any other base those classes have no FABRIC reference)" >&2
  fi
  echo "$VANILLA_REFERENCE_JAR"; exit 0
fi

STAGED="$(cd "$(dirname "$BASE")/.." && pwd)/vanilla-reference/$NAME"
if current "$STAGED"; then echo "$STAGED"; exit 0; fi
[ -f "$STAGED" ] && echo "[vanilla-reference] $STAGED records another base than $BASE; not using it" >&2

[ -f "$VANILLA" ] || { echo "[vanilla-reference] vanilla jar not found: $VANILLA — set MC_DIR" >&2; exit 3; }
KEY="$(printf '%s' "$(cd "$(dirname "$BASE")" && pwd)/$(basename "$BASE")" | shasum -a 1 | cut -c1-12)"
OUT="$KERNEL/build/vanilla-reference/$KEY/$NAME"
if current "$OUT"; then echo "$OUT"; exit 0; fi

SOURCE="$KERNEL/../forbric-kernel-installer/src/main/java/net/forbric/installer/kernel/VanillaReference.java"
[ -f "$SOURCE" ] || { echo "[vanilla-reference] the installer's writer is missing: $SOURCE" >&2; exit 4; }
mkdir -p "$(dirname "$OUT")"
# A single-file source program: the installer's own writer with nothing else on its classpath (it needs only the JDK).
java "$SOURCE" "$VANILLA" "$BASE" "$OUT" || { echo "[vanilla-reference] building $OUT failed" >&2; exit 5; }
