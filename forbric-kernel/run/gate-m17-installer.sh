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
# GATE-PARALLEL: rundirs=installed mem=3000
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
check "it staged Forbric's own jars"   "staged [1-9][0-9]* Forbric and kernel-dependency jar" "$LOG"
check "it staged the game artifacts"   "staged 3 game artifact"                          "$LOG"
check_absent "it never claimed to ship Minecraft" "bundled (merged|game) base"            "$LOG"
[ -f "$DEST/versions/26.2-forbric/26.2-forbric.json" ] && echo "[kernel] PASS the profile exists" \
  || { echo "[kernel] FAIL the profile exists"; FAIL=1; }

step "a launcher reads the profile as MODDED (must PASS)"
# How launchers actually decide: serialise the whole version JSON and run substring matches over the text. PCL2
# does exactly that, in a first-match chain — Fabric, then MinecraftForge (only when NeoForge is absent), then
# NeoForge. A Forbric profile carried none of those strings, so it was read as vanilla, which is not a label: an
# unmodded version gets the SHARED .minecraft/mods folder instead of this version's own, so every mod downloaded
# through the launcher landed where the instance does not look.
PROFILE="$DEST/versions/26.2-forbric/26.2-forbric.json"
if grep -q 'net\.fabricmc:fabric-loader' "$PROFILE"; then
  echo "[kernel] PASS the profile names a loader coordinate a launcher matches on"
else
  echo "[kernel] FAIL the profile names no loader — a launcher will call this vanilla"; FAIL=1
fi
# Exactly ONE answer. A second coordinate does not say "all three" to a first-match chain; it makes the answer
# depend on which branch a given launcher tests first, so two launchers disagree about one file.
for other in 'net\.neoforge' 'minecraftforge'; do
  if grep -q "$other" "$PROFILE"; then
    echo "[kernel] FAIL the profile also names $other — the detection chain now has two answers"; FAIL=1
  else
    echo "[kernel] PASS the profile does not also name $other"
  fi
done
# And the declaration must not have become classpath: a real fabric-loader on -cp would fight the kernel.
if python3 -c "import json,sys; j=json.load(open(sys.argv[1])); sys.exit(0 if not any('fabric-loader' in str(l.get('name','')) for l in j['libraries']) else 1)" "$PROFILE"; then
  echo "[kernel] PASS it is metadata, not a library the launcher would put on the classpath"
else
  echo "[kernel] FAIL a fabric-loader jar reached libraries[] — that would be loaded, not just read"; FAIL=1
fi

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
# This gate is the one place that launches the way a real launcher does -- it does NOT go through
# run/launch-kernel-client.sh, so it does not inherit that script's opt-out. And this pack HAS unmet hard
# dependencies (taxfreelevels -> cloth_config, and the two yumi_commons mods), which is exactly the input that
# opens the dialog. Unattended, that is a hang rather than a failure. A real player would see the dialog here,
# and should; a gate has nobody to click it.
command.append("-Dforbric.dependencyDialog=off")
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

# ---------------------------------------------------------------------------------------------------------
# The cache. Everything above installs into an EMPTY directory, which is why the reuse path went untested for
# so long: the bug it hides only exists on a machine that has built before. Moving the NeoForge pin from
# 26.2.0.38-beta to 26.2.0.88 and re-running on the user's own machine printed "neoforge=26.2.0.88" in the pin
# line and then "[neoforge-runtime] up-to-date", and installed the OLD carrier. Nothing failed. None of the
# artifacts carries its version in its filename, and "the file exists" was the whole freshness test.
#
# These two phases run LAST on purpose: the second one rebuilds an artifact, and nothing downstream should be
# resolved from a half-refreshed cache.
step "a second install into the same directory reuses what is already built"
java -jar "$JAR" --dir "$DEST" --mc 26.2 > "$BUILD/gate-m17-install-2.log" 2>&1
REUSED=$(grep -cE "^\[(forge-runtime|patched|neoforge-runtime|neoform|merge|interop)\] up-to-date" "$BUILD/gate-m17-install-2.log")
assert_eq "every artifact came from the cache" "6" "$REUSED"

step "and a pin that no longer matches is NOT served from it"
# Only forge-runtime's stamp is disturbed, because the stamp is the whole pin set: in real use all six move
# together, and tampering with one is the cheapest honest probe of the DECISION. forge-runtime is also the
# quickest of the six to rebuild.
FR_STAMP="$DEST/.forbric-build/out/forge-runtime.jar.pins"
if [ ! -f "$FR_STAMP" ]; then
  echo "[kernel] FAIL no stamp beside forge-runtime.jar — nothing records what built it"; FAIL=$((FAIL+1))
else
  echo "mc=26.2 forge=PRETEND-OTHER neoforge=PRETEND-OTHER nfrt=0 result=none" > "$FR_STAMP"
  java -jar "$JAR" --dir "$DEST" --mc 26.2 > "$BUILD/gate-m17-install-3.log" 2>&1
  check_absent "the stale artifact is rebuilt, not reused" "^\[forge-runtime\] up-to-date" \
    "$BUILD/gate-m17-install-3.log"
  check "and it says so"       "^\[forge-runtime\] (fetching|merging|wrote)" "$BUILD/gate-m17-install-3.log"
  check "the others still hit" "^\[neoform\] up-to-date"                     "$BUILD/gate-m17-install-3.log"
fi

step "the merge tool itself is not served from the previous install"
# The layer below the artifact cache, and the reason the first fixed installer still produced a broken merged
# base on a machine that had installed before: the bundled tools jar was unpacked into .forbric-build/tools
# only when one was not already there. The artifact stamps invalidated correctly and the rebuild then ran the
# PREVIOUS installer's merge tool.
TOOLS="$DEST/.forbric-build/tools/forbric-merge-tools.jar"
WANT="$BUILD/gate-m17-tools-bundled.jar"
unzip -p "$JAR" forbric/tools/forbric-merge-tools.jar > "$WANT" 2>/dev/null
if [ ! -s "$WANT" ]; then
  echo "[kernel] FAIL the installer carries no bundled merge tools"; FAIL=$((FAIL+1))
else
  # The tool is unpacked by the merge step, so the merge has to actually RUN — disturb its stamp too, or the
  # install is served entirely from cache, never asks for the tool, and the tampered file survives while
  # nothing is wrong. (That is how this assertion failed the first time it was written.)
  printf 'not a jar at all' > "$TOOLS"
  echo "mc=26.2 forge=PRETEND-OTHER neoforge=PRETEND-OTHER nfrt=0 result=none tools=none" \
    > "$DEST/.forbric-build/out/patched-mc-merged-26.2.jar.pins"
  java -jar "$JAR" --dir "$DEST" --mc 26.2 > "$BUILD/gate-m17-install-4.log" 2>&1
  check_absent "the merge re-runs, so the tool is actually asked for" "^\[merge\] up-to-date" \
    "$BUILD/gate-m17-install-4.log"
  assert_eq "the stale tool is replaced by the bundled one" \
    "$(shasum -a 1 "$WANT" | cut -d' ' -f1)" "$(shasum -a 1 "$TOOLS" | cut -d' ' -f1)"
fi

step "M17 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M17 GATE GREEN — the installer's own version profile, resolved the way a launcher resolves it, boots the tri-ecosystem game"
else
  echo "[kernel] ❌ M17 GATE RED — install $LOG / client $CLOG / command $CMD_FILE"
fi
exit "$FAIL"
