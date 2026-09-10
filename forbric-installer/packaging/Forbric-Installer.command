#!/bin/sh
# Double-click launcher for macOS. Finder runs this from an arbitrary CWD, so resolve our own directory and
# launch the installer jar sitting next to it. Requires a Java runtime on PATH (PCL2/HMCL users already have one).
DIR="$(cd "$(dirname "$0")" && pwd)"
if ! command -v java >/dev/null 2>&1; then
  echo "Java was not found on PATH. Install Java (a Minecraft launcher's JRE works) and try again." >&2
  read -r _ 2>/dev/null
  exit 1
fi
exec java -jar "$DIR/forbric-installer.jar" "$@"
