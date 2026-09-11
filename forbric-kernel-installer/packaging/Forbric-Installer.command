#!/bin/sh
# Double-click launcher for macOS.
#
# Finder runs this from an arbitrary working directory, so resolve our own folder and launch the installer
# jar sitting next to it. The jar is matched by pattern, not by a fixed name, because the released asset
# carries its version (forbric-kernel-installer-0.1.0.jar).
#
# A Minecraft player often has no system-wide JDK -- the only runtime on the machine is the one their
# launcher downloaded under the .minecraft folder -- so look there as well as on PATH.
DIR="$(cd "$(dirname "$0")" && pwd)"

JAR=""
for candidate in "$DIR"/forbric-kernel-installer*.jar; do
	[ -f "$candidate" ] && JAR="$candidate"
done
if [ -z "$JAR" ]; then
	echo "Could not find forbric-kernel-installer*.jar next to this script." >&2
	echo "Keep the two files in the same folder." >&2
	printf 'Press Return to close. '
	read -r _ 2>/dev/null
	exit 1
fi

find_java() {
	if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
		echo "$JAVA_HOME/bin/java"
		return
	fi
	if command -v java >/dev/null 2>&1; then
		# macOS ships a /usr/bin/java stub that only nags about installing a JDK; java_home tells us whether
		# a real runtime is behind it.
		if /usr/libexec/java_home >/dev/null 2>&1; then
			command -v java
			return
		fi
	fi
	# Minecraft launcher runtimes. Mojang has shipped both a flat <runtime>/bin and a nested
	# <runtime>/<os-arch>/<runtime>/bin layout, so search rather than guessing the depth.
	for root in "$HOME/Library/Application Support/minecraft/runtime" "$HOME/.minecraft/runtime" "$DIR/../runtime"; do
		[ -d "$root" ] || continue
		found=$(find "$root" -type f -name java -perm -u+x 2>/dev/null | head -n 1)
		[ -n "$found" ] && { echo "$found"; return; }
	done
}

JAVA="$(find_java)"
if [ -z "$JAVA" ]; then
	echo "No Java runtime found." >&2
	echo >&2
	echo "Looked in: JAVA_HOME, PATH, and the Minecraft launcher's runtime folder." >&2
	echo "If you have a Minecraft launcher installed, start the game once so it downloads a runtime," >&2
	echo "then run this again. Otherwise install Java 17 or newer." >&2
	printf 'Press Return to close. '
	read -r _ 2>/dev/null
	exit 1
fi

echo "Using Java: $JAVA"
echo "Installer : $JAR"
echo

"$JAVA" -jar "$JAR" "$@"
status=$?
if [ "$status" -ne 0 ]; then
	echo
	echo "The installer exited with status $status." >&2
	printf 'Press Return to close. '
	read -r _ 2>/dev/null
fi
exit "$status"
