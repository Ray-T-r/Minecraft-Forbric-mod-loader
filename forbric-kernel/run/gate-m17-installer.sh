#!/usr/bin/env bash
# M17 gate — the installer produces a version an ordinary Minecraft launcher can start.
#
# WHY THIS EXISTS. Every other gate calls run/launch-kernel-client.sh, which builds the java command by hand from
# paths in this checkout. That proves the kernel runs; it proves nothing about the thing a user actually installs.
# Between the two sits the whole surface the installer owns: which jars are staged where, what the version profile
# says, and whether a launcher reading Mojang's format can turn that profile back into the same command.
#
# So this gate never calls the launch script. It installs into an empty directory, then RESOLVES the profile the
# way a launcher does — follow inheritsFrom, merge libraries and arguments, expand ${library_directory} and the
# rest, build the classpath — and runs what comes out. A profile that names a jar the installer forgot to stage,
# or an argument the kernel cannot parse, fails here and nowhere else.
#
# The world, the assets and the LWJGL natives come from the real Minecraft install, exactly as a launcher would
# supply them. The install directory itself is fresh every run.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

INSTALLER="$KERNEL/../forbric-kernel-installer"
DEST="${M17_DEST:-$KERNEL/run/installed}"
LOG="$BUILD/gate-m17-install.log"
CLOG="$BUILD/gate-m17-client.log"
MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
WORLD="${M17_WORLD:-ForbricTest}"
SRC_RUNDIR="${M17_SRC_RUNDIR:-$KERNEL/run/client-merged-pack}"
mkdir -p "$BUILD"

[ -d "$MC/assets" ] || { echo "[kernel] SKIP-FATAL: no Minecraft assets at $MC/assets" >&2; exit 3; }
[ -d "$SRC_RUNDIR/saves/$WORLD" ] || { echo "[kernel] SKIP-FATAL: no world at $SRC_RUNDIR/saves/$WORLD" >&2; exit 3; }

kernel_jar

step "build the installer jar"
if ! (cd "$INSTALLER" && ./gradlew -q jar > "$BUILD/gate-m17-installer-build.log" 2>&1); then
  echo "[kernel] FATAL: installer build failed" >&2
  grep -vE 'WARNING|^$' "$BUILD/gate-m17-installer-build.log" | tail -20 >&2
  exit 3
fi
JAR="$(ls "$INSTALLER"/build/libs/forbric-kernel-installer-*.jar | head -1)"
echo "[kernel] installer: $JAR ($(du -h "$JAR" | cut -f1))"

step "install into an empty directory"
rm -rf "$DEST"
mkdir -p "$DEST"
# The base version is copied rather than re-downloaded: this gate is about the installer, not about Mojang's CDN.
mkdir -p "$DEST/versions/26.2"
cp "$MC/versions/26.2/26.2.json" "$MC/versions/26.2/26.2.jar" "$DEST/versions/26.2/" 2>/dev/null || true
java -jar "$JAR" --dir "$DEST" --mc 26.2 > "$LOG" 2>&1
INSTALL_RC=$?
sed 's/^/[kernel]   /' "$LOG" | cut -c1-180

step "the install wrote what a launcher needs (must PASS)"
assert_eq "the installer exited cleanly" "0" "$INSTALL_RC"
check "it wrote a version profile"     "wrote .*versions/26.2-forbric/26.2-forbric.json" "$LOG"
check "it staged Forbric's own jars"   "staged [0-9]+ Forbric and kernel-dependency jar" "$LOG"
check "it staged the game artifacts"   "staged 3 game artifact"                          "$LOG"
check_absent "it never claimed to ship Minecraft" "bundled (merged|game) base"            "$LOG"
[ -f "$DEST/versions/26.2-forbric/26.2-forbric.json" ] && echo "[kernel] PASS the profile exists" \
  || { echo "[kernel] FAIL the profile exists"; FAIL=1; }

step "give the directory the vanilla libraries a launcher would have downloaded"
# The installer stages only what it owns; the base version's own libraries are the launcher's job. Copying them
# from the real install is what makes this a launcher simulation rather than a half-populated directory.
COPIED=$(python3 - "$DEST" "$MC" <<'LIBS'
import json, os, platform, shutil, sys
dest, mc = sys.argv[1:3]
with open(os.path.join(dest, "versions", "26.2", "26.2.json")) as f:
    base = json.load(f)
osname = {"Darwin": "osx", "Windows": "windows"}.get(platform.system(), "linux")

def allowed(entry):
    rules = entry.get("rules")
    if not rules:
        return True
    ok = False
    for rule in rules:
        spec = rule.get("os")
        if spec and spec.get("name") not in (None, osname):
            continue
        ok = rule.get("action") == "allow"
    return ok

copied = 0
for entry in base.get("libraries", []):
    if not allowed(entry):
        continue
    artifact = (entry.get("downloads") or {}).get("artifact") or {}
    path = artifact.get("path")
    if not path:
        group, name, version = entry["name"].split(":")[:3]
        path = "%s/%s/%s/%s-%s.jar" % (group.replace(".", "/"), name, version, name, version)
    src = os.path.join(mc, "libraries", path)
    dst = os.path.join(dest, "libraries", path)
    if os.path.isfile(src) and not os.path.isfile(dst):
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        copied += 1
print(copied)
LIBS
)
echo "[kernel] copied $COPIED vanilla librar(ies) into the install, as a launcher would"

step "resolve that profile the way a launcher does, and launch it"
# Everything the pack needs to load its world: the save, the mods, and the configs those mods were set up with.
# A world that opens only because a config happens to be absent proves nothing about an install.
cp -R "$SRC_RUNDIR/saves" "$DEST/saves"
cp -R "$SRC_RUNDIR/config" "$DEST/config" 2>/dev/null || true
cp -R "$SRC_RUNDIR/defaultconfigs" "$DEST/defaultconfigs" 2>/dev/null || true
cp "$SRC_RUNDIR/options.txt" "$DEST/options.txt" 2>/dev/null || true
mkdir -p "$DEST/mods" "$DEST/quickPlay"
cp "$SRC_RUNDIR/mods"/*.jar "$DEST/mods/" 2>/dev/null || true
echo "[kernel] mods: $(ls -1 "$DEST/mods" 2>/dev/null | wc -l | tr -d ' ')"

CMD_FILE="$BUILD/gate-m17-command.txt"
python3 - "$DEST" "$MC" "$WORLD" "$CMD_FILE" <<'PY'
import json, os, platform, sys

dest, mc, world, out = sys.argv[1:5]

def load(version):
    with open(os.path.join(dest, "versions", version, version + ".json")) as f:
        return json.load(f)

child = load("26.2-forbric")
parent = load(child["inheritsFrom"])

osname = {"Darwin": "osx", "Windows": "windows"}.get(platform.system(), "linux")

def allowed(entry):
    rules = entry.get("rules")
    if not rules:
        return True
    ok = False
    for rule in rules:
        # Feature-gated arguments (quick play, demo, custom resolution) are opt-in: a launcher emits them only
        # when it turned that feature on. This gate turns quick play on by appending the options itself.
        if rule.get("features"):
            continue
        spec = rule.get("os")
        if spec and spec.get("name") not in (None, osname):
            continue
        ok = rule.get("action") == "allow"
    return ok

# libraries: the parent's first, then the child's — the order a launcher builds its classpath in.
classpath = []
for entry in list(parent.get("libraries", [])) + list(child.get("libraries", [])):
    if not allowed(entry):
        continue
    artifact = (entry.get("downloads") or {}).get("artifact") or {}
    path = artifact.get("path")
    if not path:
        group, name, version = entry["name"].split(":")[:3]
        path = "%s/%s/%s/%s-%s.jar" % (group.replace(".", "/"), name, version, name, version)
    jar = os.path.join(dest, "libraries", path)
    if os.path.isfile(jar) and jar not in classpath:
        classpath.append(jar)
missing = [e["name"] for e in child.get("libraries", []) if not os.path.isfile(
    os.path.join(dest, "libraries", ((e.get("downloads") or {}).get("artifact") or {}).get("path", "")))]

# A launcher always puts the base version's jar on the classpath. It is harmless here and worth keeping in the
# simulation: the kernel defines every net.minecraft class itself, from the merged base, so the vanilla copy on
# the parent classpath is never the one that answers.
game_jar = os.path.join(dest, "versions", "26.2", "26.2.jar")
if os.path.isfile(game_jar):
    classpath.append(game_jar)

placeholders = {
    "${library_directory}": os.path.join(dest, "libraries"),
    "${classpath}": os.pathsep.join(classpath),
    "${classpath_separator}": os.pathsep,
    "${natives_directory}": os.path.join(mc, "versions", "26.2", "26.2-natives"),
    "${launcher_name}": "forbric-gate",
    "${launcher_version}": "1",
    "${auth_player_name}": "ForbricKernel",
    "${version_name}": "26.2-forbric",
    "${game_directory}": dest,
    "${assets_root}": os.path.join(mc, "assets"),
    "${assets_index_name}": parent["assetIndex"]["id"],
    "${auth_uuid}": "00000000000000000000000000000000",
    "${auth_access_token}": "0",
    "${clientid}": "0",
    "${auth_xuid}": "0",
    "${user_type}": "legacy",
    "${version_type}": "release",
    "${resolution_width}": "854",
    "${resolution_height}": "480",
}

def expand(value):
    for key, replacement in placeholders.items():
        value = value.replace(key, replacement)
    return value

def flatten(section, key):
    out = []
    for source in (parent, child):
        for item in (source.get("arguments") or {}).get(key, []):
            if isinstance(item, str):
                out.append(expand(item))
            elif allowed(item):
                value = item.get("value")
                for v in ([value] if isinstance(value, str) else value or []):
                    out.append(expand(v))
    return out

def dedupe_pairs(argv):
    """Collapse repeated game-argument flags, keeping the last value — the strictest thing a real launcher does.

    A launcher is free to read the game arguments as a flag-to-value map rather than a list; PCL2 does, and
    reports each collapse. A profile that needs a flag to appear twice loses one of them there and nowhere else,
    so the simulation has to be at least as strict as the strictest launcher."""
    value, order = {}, []
    i = 0
    while i < len(argv):
        token = argv[i]
        pair = token.startswith("--") and i + 1 < len(argv) and not argv[i + 1].startswith("--")
        if token not in value:
            order.append(token)
        value[token] = argv[i + 1] if pair else None
        i += 2 if pair else 1
    out = []
    for token in order:
        out.append(token)
        if value[token] is not None:
            out.append(value[token])
    return out

game_args = flatten(child, "game")
game_args += ["--quickPlayPath", os.path.join(dest, "quickPlay", "log.json"), "--quickPlaySingleplayer", world]
deduped = dedupe_pairs(game_args)

command = ["java"]
if osname == "osx":
    command.append("-XstartOnFirstThread")
command += flatten(child, "jvm")
command += ["-cp", os.pathsep.join(classpath), child["mainClass"]]
command += deduped

with open(out, "w") as f:
    f.write("\n".join(command))
print("[kernel]   classpath: %d jar(s); game args: %d (%d dropped by launcher-style dedup); "
      "missing staged libraries: %s"
      % (len(classpath), len(deduped), len(game_args) - len(deduped), missing or "none"))
PY
PY_RC=$?
assert_eq "the profile resolved like a launcher would" "0" "$PY_RC"

# The profile has to survive that dedup with every ecosystem intact. It did not always: two --runtimeJar flags
# collapsed into one, MinecraftForge's runtime never reached the kernel, and the game died on the first
# net.minecraftforge class — after the kernel had logged a clean boot, so the game's own log said nothing.
check "MinecraftForge's runtime survived the dedup" "/forge-runtime-[0-9.]+\.jar"     "$CMD_FILE"
check "NeoForge's runtime survived the dedup"       "/neoforge-runtime-[0-9.]+\.jar"  "$CMD_FILE"
check "the merged base survived the dedup"          "/patched-mc-merged-[0-9.]+\.jar" "$CMD_FILE"

if [ "$PY_RC" -eq 0 ]; then
  echo "[kernel] launching the resolved command (mainClass $(grep -c . "$CMD_FILE") argv entries)"
  ( cd "$DEST" && FORBRIC_JVM="" tr '\n' '\0' < "$CMD_FILE" | xargs -0 env \
      JAVA_TOOL_OPTIONS="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=140" \
      ) > "$CLOG" 2>&1 &
  CLIENT_PID=$!
  echo "[kernel] client pid=$CLIENT_PID (killed by pid only — another client may be running)"
  CGAME="$DEST/logs/latest.log"
  for i in $(seq 1 300); do
    kill -0 "$CLIENT_PID" 2>/dev/null || { echo "[kernel] client exited on its own after ~${i}s"; break; }
    grep -qE 'ClientSmoke\] clean disconnect observed|Game crashed|Mod Loading has failed' "$CGAME" 2>/dev/null \
      && { echo "[kernel] outcome reached after ~${i}s"; break; }
    sleep 1
  done
  for i in $(seq 1 25); do kill -0 "$CLIENT_PID" 2>/dev/null || break; sleep 1; done
  for pid in $(pgrep -P "$CLIENT_PID" 2>/dev/null) "$CLIENT_PID"; do kill "$pid" 2>/dev/null; done
  sleep 2
  for pid in $(pgrep -P "$CLIENT_PID" 2>/dev/null) "$CLIENT_PID"; do kill -9 "$pid" 2>/dev/null; done
  cat "$CGAME" >> "$CLOG" 2>/dev/null || true
fi

step "the installed version runs the tri-ecosystem game (must PASS)"
check "the kernel booted from the profile" "Forbric/Boot\] sovereign kernel — client" "$CLOG"
check "it opened the merged base"          "Forbric/Boot\] merged base"               "$CLOG"
check "mods from all three ecosystems"     "Forbric/Boot\] sovereign kernel .*Forge-family mod\(s\), [1-9]" "$CLOG"
check "the smoke controller armed"         "ClientSmoke\] armed on Minecraft.tick"    "$CLOG"
check "it entered the world"               "ClientSmoke\] client-ready after"         "$CLOG"
check "it left cleanly"                    "ClientSmoke\] clean disconnect observed"  "$CLOG"
check_absent "no client crash"             "Preparing crash report"                   "$CLOG"
# A mod asking for an optional class of a mod that is not installed is this pack's own noise (gate-m9 carries the
# same). What may not go missing is anything the INSTALL is responsible for putting on the classpath.
check_absent "nothing the installer staged went missing" \
  "(NoClassDefFoundError|ClassNotFoundException).*(net/forbric|net\\.forbric|net/minecraft|org/apache/logging|com/mojang|org/objectweb/asm|org/spongepowered)" "$CLOG"

step "M17 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M17 GATE GREEN — the installer's own version profile, resolved the way a launcher resolves it, boots the tri-ecosystem game"
else
  echo "[kernel] ❌ M17 GATE RED — install $LOG / client $CLOG / command $CMD_FILE"
fi
exit "$FAIL"
