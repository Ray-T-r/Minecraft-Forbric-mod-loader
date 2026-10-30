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
#   1. $VANILLA_REFERENCE_JAR, when set and present — a gate or a developer naming one explicitly.
#   2. <stage>/vanilla-reference/vanilla-reference-26.2.jar, when it is newer than the base (what prepare staged).
#   3. build/vanilla-reference/<base-key>/vanilla-reference-26.2.jar in this checkout, built by the installer's own
#      VanillaReference.java run as a single-file program: the same writer, so the same bytes. It records the SHA-1 of
#      both inputs and is reused while they match.
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

if [ -n "${VANILLA_REFERENCE_JAR:-}" ] && [ -f "$VANILLA_REFERENCE_JAR" ]; then echo "$VANILLA_REFERENCE_JAR"; exit 0; fi

STAGED="$(cd "$(dirname "$BASE")/.." && pwd)/vanilla-reference/$NAME"
if [ -f "$STAGED" ] && [ "$STAGED" -nt "$BASE" ]; then echo "$STAGED"; exit 0; fi

[ -f "$VANILLA" ] || { echo "[vanilla-reference] vanilla jar not found: $VANILLA — set MC_DIR" >&2; exit 3; }
KEY="$(printf '%s' "$(cd "$(dirname "$BASE")" && pwd)/$(basename "$BASE")" | shasum -a 1 | cut -c1-12)"
OUT="$KERNEL/build/vanilla-reference/$KEY/$NAME"
if [ -f "$OUT" ] && [ "$OUT" -nt "$BASE" ] && [ "$OUT" -nt "$VANILLA" ]; then echo "$OUT"; exit 0; fi

SOURCE="$KERNEL/../forbric-kernel-installer/src/main/java/net/forbric/installer/kernel/VanillaReference.java"
[ -f "$SOURCE" ] || { echo "[vanilla-reference] the installer's writer is missing: $SOURCE" >&2; exit 4; }
mkdir -p "$(dirname "$OUT")"
# A single-file source program: the installer's own writer with nothing else on its classpath (it needs only the JDK).
java "$SOURCE" "$VANILLA" "$BASE" "$OUT" || { echo "[vanilla-reference] building $OUT failed" >&2; exit 5; }
