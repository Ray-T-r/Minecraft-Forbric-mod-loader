#!/usr/bin/env bash
# Print the path to a RESOURCES-ONLY jar carrying the game's root metadata (version.json), generating it from the
# merged base when missing or stale. Both launchers put it on the PARENT -cp.
#
# WHY THIS EXISTS. Mods reach for the game's version.json through ClassLoader.getSystemClassLoader(), because on
# the loaders they were built against the game jar is on the application classpath. Under Forbric it is not: the
# merged base is handed to ForbricClassLoader via --gameJar, so a getSystemClassLoader().getResourceAsStream(
# "version.json") returns null and the mod silently falls back to a default.
#
# Measured cost of that: CustomSkinLoader's bootstrap picks its bytecode patch variant by PROTOCOL VERSION
# ("skin-manager.v1" claims [,763], "skin-manager.v2" claims [765,800],…). With version.json unreadable its
# protocol reads 0, which lands inside v1's open lower bound, so it applied a pre-1.20.2 pattern to 26.2, matched
# nothing, and threw `matched protocol 0 but did not modify any bytecode` — taking SkinManager and
# PlayerTabOverlay with it, i.e. skin loading. 26.2's real protocol is 776, which selects v2. Its documented
# fallback (net/minecraft/realms/RealmsSharedConstants) cannot help either: that class no longer exists.
#
# NO CLASS FILES, EVER. Putting the merged base itself on the parent -cp would let the application loader win
# parent-first for game classes and quietly strip every mixin from them. This jar is asserted to be class-free
# below so that can never happen by accident.
#
# Usage: game-metadata-jar.sh <merged-base.jar>   → prints the jar path on stdout (nothing else goes to stdout)
set -uo pipefail

MERGED="${1:?usage: game-metadata-jar.sh <merged-base.jar>}"
[ -f "$MERGED" ] || { echo "[game-metadata] merged base not found: $MERGED" >&2; exit 2; }

OUT="${GAME_METADATA_JAR:-$(dirname "$MERGED")/forbric-game-metadata.jar}"
RESOURCES="version.json"

if [ -f "$OUT" ] && [ "$OUT" -nt "$MERGED" ]; then echo "$OUT"; exit 0; fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for r in $RESOURCES; do
  if ! unzip -p "$MERGED" "$r" > "$WORK/$r" 2>/dev/null || [ ! -s "$WORK/$r" ]; then
    echo "[game-metadata] $(basename "$MERGED") has no $r — skipping the metadata jar" >&2
    exit 3
  fi
done

(cd "$WORK" && jar --create --file "$OUT.tmp" $RESOURCES) || { echo "[game-metadata] jar failed" >&2; exit 4; }

# The whole safety argument for this jar is that it carries no classes. Check it rather than trust it.
if unzip -l "$OUT.tmp" | grep -q '\.class$'; then
  echo "[game-metadata] REFUSING: generated jar contains class files, which would let the application loader" >&2
  echo "[game-metadata] win parent-first for game classes and silently drop their mixins" >&2
  rm -f "$OUT.tmp"; exit 5
fi

mv "$OUT.tmp" "$OUT"
echo "[game-metadata] $(basename "$OUT") <- $RESOURCES (protocol_version $(python3 -c "import json,sys;print(json.load(open('$WORK/version.json')).get('protocol_version'))" 2>/dev/null || echo '?'))" >&2
echo "$OUT"
