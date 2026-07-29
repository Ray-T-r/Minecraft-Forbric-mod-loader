#!/usr/bin/env bash
# Shared helpers for forbric-kernel gate/oracle scripts. Source this: `. "$(dirname "$0")/lib.sh"`.
set -uo pipefail

KERNEL="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OLD="$(cd "$KERNEL/../forbric-loader" && pwd)"
RUN_OLD="$OLD/run"
BUILD="$KERNEL/build"
FAIL=0

step() { printf '\n[kernel] ==== %s ====\n' "$1"; }

# check <what> <grep-pattern> <file> [required-count]
check() {
  local what="$1" pat="$2" file="$3" want="${4:-1}" got
  got=$(grep -cE "$pat" "$file" 2>/dev/null || true)
  if [ "${got:-0}" -ge "$want" ]; then printf '[kernel] PASS %s (%s)\n' "$what" "$got"
  else printf '[kernel] FAIL %s (want>=%s got %s)\n' "$what" "$want" "${got:-0}"; FAIL=1; fi
}

# check_absent <what> <grep-pattern> <file> — fails if the pattern appears at all.
check_absent() {
  local what="$1" pat="$2" file="$3" got
  got=$(grep -cE "$pat" "$file" 2>/dev/null || true)
  if [ "${got:-0}" -eq 0 ]; then printf '[kernel] PASS %s (absent)\n' "$what"
  else printf '[kernel] FAIL %s (present x%s)\n' "$what" "$got"; FAIL=1; fi
}

# assert_eq <what> <expected> <actual>
assert_eq() {
  if [ "$2" = "$3" ]; then printf '[kernel] PASS %s (%s)\n' "$1" "$3"
  else printf '[kernel] FAIL %s (want %s got %s)\n' "$1" "$2" "$3"; FAIL=1; fi
}

# Rebuild the kernel jar, FAILING LOUDLY. A swallowed build error leaves a stale jar in build/libs and every
# gate downstream then silently reports on code that is not the code in the tree.
kernel_jar() {
  if ! "$KERNEL/gradlew" --offline -q -p "$KERNEL" jar >"$BUILD/kernel-jar.log" 2>&1; then
    echo "[kernel] FATAL: kernel jar build failed — refusing to run against a stale jar" >&2
    grep -vE 'WARNING: |native-access|Restricted method|--enable-native' "$BUILD/kernel-jar.log" >&2
    exit 3
  fi
}

# Build (offline) the boot-side classpath once and cache it. Sets $KERNEL_CP.
kernel_classpath() {
  local jar="$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar"
  if [ ! -f "$jar" ]; then
    kernel_jar
  fi
  local cp
  cp=$("$KERNEL/gradlew" --offline -q -p "$KERNEL" printBootClasspath 2>/dev/null \
        | grep -vE 'WARNING|native|Restricted|enable' | tail -1)
  KERNEL_CP="$jar:$cp"
}

# kernel_scan <mods-dir> <out-json>
kernel_scan() {
  [ -n "${KERNEL_CP:-}" ] || kernel_classpath
  java -cp "$KERNEL_CP" net.forbric.kernel.boot.Main --scan --mods "$1" --report "$2" \
       2>&1 | grep -vE 'WARNING|native|Restricted|enable' || true
}

filter_noise() { grep -vE 'WARNING: |native-access|Restricted method|--enable-native'; }
