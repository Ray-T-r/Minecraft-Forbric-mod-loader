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
#
# Stage 3 of dropping MinecraftForge: the installer's which-mods sentence and the profile's ecosystems list now name
# Fabric and NeoForge only (the kernel refuses a MinecraftForge-only mod); both are asserted below. Stage 4: the
# installed game is NeoForge's own patched game with one runtime, NeoForge's; the profile is checked for exactly that,
# and a kernel handed the arguments of an install made by Forbric 0.4 or earlier refuses them (control N, below).
#
# The last two phases were written around the merge, which no install runs since C1, and both now assert what an install
# still does (D70): every install runs the link check from the bundled tools jar, unpacked over whatever an earlier install
# left in .forbric-build/tools, and a reinstall over a full cache goes to the network for nothing.
#
# Rundir: M17_DEST (default run/installed, which the gate wipes). M17_SEED_CACHE=<an earlier install's directory> copies
# that install's download caches (.forbric-build/dl and tools) and NeoForm's patched game (.forbric-build/out/
# patched-mc-neoforge-*.jar and its stamp) into the fresh directory first: the first install then builds the NeoForge
# runtime and the vanilla reference from local inputs and takes NeoForm's game from the cache, so it goes to the network
# for nothing, so far as the installer's own fetches go. Measured with the network denied (C5, 2026-11-09): a directory
# seeded this way from this gate's install, installed behind a proxy on a port nothing listens on, exits 0 having built
# the NeoForge runtime and the vanilla reference and passed both link checks, while an empty directory behind the same
# proxy fails at once ("could not reach maven.neoforged.net"). Unset, the first install builds everything and downloads
# its inputs, as a player's does.
# GATE-PARALLEL: rundirs=installed mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

INSTALLER="$KERNEL/../forbric-kernel-installer"
DEST="${M17_DEST:-$KERNEL/run/installed}"
refuse_shared_rundir "$DEST"
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
# Offline and without a daemon, like every other Gradle run of a gate. kernel_jar (above) built the kernel jar the
# installer bundles and the loader builds the merge-tools jar (the link checker's) here; the installer's own nested
# builds of the two (buildForbricJars, buildMergeTools) are left out because they call gradlew online, and its bundle
# tasks fail loudly on a jar that is not there.
if ! { (cd "$KERNEL/../forbric-loader" && ./gradlew --offline --no-daemon -q mergeToolsJar) \
       && (cd "$INSTALLER" && ./gradlew --offline --no-daemon -q jar -x buildForbricJars -x buildMergeTools); } \
     > "$BUILD/gate-m17-installer-build.log" 2>&1; then
  echo "[kernel] FATAL: installer build failed" >&2
  grep -vE 'WARNING|^$' "$BUILD/gate-m17-installer-build.log" | tail -20 >&2
  exit 3
fi
# The jar of the version this tree builds, by name: build/libs keeps every earlier version's jar, and the first one a
# glob listed was 0.3.1-beta2's, which this gate kept testing after the version moved on.
VERSION="$(sed -n "s/^version = '\(.*\)'$/\1/p" "$INSTALLER/build.gradle")"
JAR="$INSTALLER/build/libs/forbric-kernel-installer-$VERSION.jar"
[ -n "$VERSION" ] && [ -f "$JAR" ] || { echo "[kernel] FATAL: no installer jar for version '$VERSION' in $INSTALLER/build/libs" >&2; exit 2; }
echo "[kernel] installer: $JAR ($(du -h "$JAR" | cut -f1))"

step "install into an empty directory"
rm -rf "$DEST"
mkdir -p "$DEST"
if [ -n "${M17_SEED_CACHE:-}" ]; then
  mkdir -p "$DEST/.forbric-build"
  for cache in dl tools; do
    [ -d "$M17_SEED_CACHE/.forbric-build/$cache" ] || { echo "[kernel] SKIP-FATAL: M17_SEED_CACHE has no .forbric-build/$cache" >&2; exit 3; }
    cp -cR "$M17_SEED_CACHE/.forbric-build/$cache" "$DEST/.forbric-build/" 2>/dev/null \
      || cp -R "$M17_SEED_CACHE/.forbric-build/$cache" "$DEST/.forbric-build/"
  done
  mkdir -p "$DEST/.forbric-build/out"
  SEEDED_BASE=$(ls "$M17_SEED_CACHE"/.forbric-build/out/patched-mc-neoforge-*.jar 2>/dev/null | head -1)
  [ -n "$SEEDED_BASE" ] && [ -f "$SEEDED_BASE.pins" ] || { echo "[kernel] SKIP-FATAL: M17_SEED_CACHE has no stamped NeoForm game in .forbric-build/out" >&2; exit 3; }
  cp "$SEEDED_BASE" "$SEEDED_BASE.pins" "$DEST/.forbric-build/out/"
  echo "[kernel] seeded dl, tools and NeoForm's $(basename "$SEEDED_BASE") from $M17_SEED_CACHE; the NeoForge runtime and the vanilla reference are built here"
fi
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
# Three: NeoForge's patched game, the NeoForge runtime and the vanilla reference the kernel reads beside the base
# (VanillaReference). (Four on the merged base: its own base and two runtimes.)
check "it staged the game artifacts"   "staged 3 game artifact"                          "$LOG"
# Stage 3 of dropping MinecraftForge: the install's last line names the two ecosystems the kernel loads, and says it no
# longer loads MinecraftForge mods. The old line ("Fabric, MinecraftForge and NeoForge mods all go in") sent a player
# straight into the kernel's refusal of a MinecraftForge-only jar. Its absence was asserted here too until 2026-11-16, and
# so was the absence of "bundled (merged|game) base": nothing in the installer, the kernel or forbric-loader prints
# either any more (git grep: one javadoc mention), so neither absence could fail (D34, lane OPT); the positive line
# below and InstallerWindowInputTest's English mods-folder line hold the wording.
check "it says Fabric and NeoForge mods go in the mods folder" "Fabric and NeoForge mods go in .*Forbric no longer loads MinecraftForge mods" "$LOG"
check "it built the vanilla reference" "^\[vanilla-reference\] wrote vanilla-reference-26.2.jar: [1-9][0-9]* vanilla classes" "$LOG"
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
# The ecosystems the profile names in prose (not as coordinates): Fabric and NeoForge since Stage 3.
if python3 -c "import json,sys; j=json.load(open(sys.argv[1])); sys.exit(0 if j['forbric']['ecosystems'] == ['fabric', 'neoforge'] else 1)" "$PROFILE"; then
  echo "[kernel] PASS the profile names exactly the two ecosystems Forbric loads (fabric, neoforge)"
else
  echo "[kernel] FAIL the profile's ecosystems are not [fabric, neoforge]"; FAIL=1
fi
# And the declaration must not have become classpath: a real fabric-loader on -cp would fight the kernel.
if python3 -c "import json,sys; j=json.load(open(sys.argv[1])); sys.exit(0 if not any('fabric-loader' in str(l.get('name','')) for l in j['libraries']) else 1)" "$PROFILE"; then
  echo "[kernel] PASS it is metadata, not a library the launcher would put on the classpath"
else
  echo "[kernel] FAIL a fabric-loader jar reached libraries[] — that would be loaded, not just read"; FAIL=1
fi

step "give the directory the vanilla libraries a launcher would have downloaded"
# The base version's own libraries are the launcher's job. The installer fetches none of them since C1 (only the merge
# read them, D13), so this copies every one the base version lists for this OS from the real Minecraft install.
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

# The profile has to survive that dedup intact. It did not always: on the merged base two --runtimeJar flags collapsed
# into one, MinecraftForge's runtime never reached the kernel, and the game died on the first net.minecraftforge class.
# One runtime now, NeoForge's, and no MinecraftForge runtime at all: the kernel would refuse it as an old install.
check "NeoForge's runtime survived the dedup"       "/neoforge-runtime-[0-9.]+\.jar"  "$CMD_FILE"
check_exactly "and it is the only runtime"          "^--runtimeJar$"                 "$CMD_FILE" 1
check_absent "no MinecraftForge runtime is on the command line" "/forge-runtime-[0-9.-]+\.jar" "$CMD_FILE"
check "the game base survived the dedup"            "/patched-mc-neoforge-[0-9.-]+\.jar" "$CMD_FILE"
# The vanilla reference rides in its own flag. Were it a second --gameJar, the dedup above would keep only the last
# value and hand the kernel a resources-only jar as the game, which is exactly what PCL2 would do.
check "the vanilla reference survived the dedup"    "/vanilla-reference-[0-9.-]+\.jar" "$CMD_FILE"
check_exactly "one game jar, and it is the base"    "^--gameJar$"                    "$CMD_FILE" 1
check_exactly "the reference has its own flag"      "^--nativeReference$"            "$CMD_FILE" 1

if [ "$PY_RC" -eq 0 ]; then
  echo "[kernel] launching the resolved command (mainClass $(grep -c . "$CMD_FILE") argv entries)"
  # The resolved command is a java line of its own (no launcher reads FORBRIC_JVM there): the caller's FORBRIC_JVM -- a
  # sweep's locale, say -- reaches the game through JAVA_TOOL_OPTIONS, ahead of the smoke properties.
  ( cd "$DEST" && tr '\n' '\0' < "$CMD_FILE" | xargs -0 env \
      JAVA_TOOL_OPTIONS="${FORBRIC_JVM:-} -Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=140" \
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
  stop_tree "$CLIENT_PID"   # the whole tree: lib.sh says why children-only orphans a JVM
  wait "$CLIENT_PID" 2>/dev/null
  cat "$CGAME" >> "$CLOG" 2>/dev/null || true
fi

step "the installed version runs the Fabric + NeoForge game (must PASS)"
check "the kernel booted from the profile" "Forbric/Boot\] sovereign kernel — client" "$CLOG"
check "it opened the game base"            "Forbric/Boot\] game base"                 "$CLOG"
check "FABRIC references come from the vanilla-reference sidecar" "$NATIVE_REFS_FROM_SIDECAR"  "$CLOG"
check "mods from both ecosystems"          "Forbric/Boot\] sovereign kernel .*Forge-family mod\(s\) in mods/, [0-9]+ nested jar\(s\), [1-9]" "$CLOG"
check "the smoke controller armed"         "ClientSmoke\] armed on Minecraft.tick"    "$CLOG"
check "it entered the world"               "ClientSmoke\] client-ready after"         "$CLOG"
check "it left cleanly"                    "ClientSmoke\] clean disconnect observed"  "$CLOG"
check_absent "no client crash"             "Preparing crash report"                   "$CLOG"
# A mod asking for an optional class of a mod that is not installed is this pack's own noise (gate-m9 carries the
# same). What may not go missing is anything the INSTALL is responsible for putting on the classpath.
check_absent "nothing the installer staged went missing" \
  "(NoClassDefFoundError|ClassNotFoundException).*(net/forbric|net\\.forbric|net/minecraft|org/apache/logging|com/mojang|org/objectweb/asm|org/spongepowered)" "$CLOG"

step "control N: this kernel, handed an install made by Forbric 0.4 or earlier, refuses it (must PASS)"
# The reverse of everything above: the same resolved command, with the inputs an old install's profile passed -- a merged
# base (it embedded MinecraftForge's native-reference index) and a second, MinecraftForge runtime. A kernel that carries
# no MinecraftForge would otherwise fail far from here on the first MinecraftForge class that base names, so
# LaunchInputCheck refuses it before anything is read, with exit 2 and one problem (OLD_INSTALL, English only until a
# localized refusal exists). -Dforbric.launchInputCheck=off must not downgrade that refusal (D9). Both inputs are
# stand-ins built here holding exactly the entries the check reads (an index entry; the five MinecraftForge carrier
# entries), so nothing old is downloaded or staged.
OLD_DIR="$BUILD/gate-m17-old-install"
rm -rf "$OLD_DIR"; mkdir -p "$OLD_DIR"
python3 - "$OLD_DIR" <<'PY_OLD' || exit 3
import sys, zipfile
d = sys.argv[1]
with zipfile.ZipFile(d + '/patched-mc-merged-26.2.jar', 'w') as base:
    base.writestr('META-INF/forbric/native-reference/FORGE/index.tsv', 'net/minecraft/world/level/block/Block\n')
with zipfile.ZipFile(d + '/forge-runtime-26.2.jar', 'w') as carrier:
    for entry in ('net/minecraftforge/forgespi/language/IModInfo.class', 'net/minecraftforge/fml/ModContainer.class',
                  'net/minecraftforge/fml/loading/FMLLoader.class', 'net/minecraftforge/fml/loading/FMLEnvironment.class',
                  'META-INF/mods.toml'):
        carrier.writestr(entry, b'')
PY_OLD
# old_launch <label> [jvm-arg] -- the resolved command with the old base and the extra runtime; prints the exit status.
old_launch() {
  local label="$1" extra="${2:-}" line prev="" rc
  local argv=()
  while IFS= read -r line; do
    if [ "$prev" = "--gameJar" ]; then line="$OLD_DIR/patched-mc-merged-26.2.jar"; fi
    argv+=("$line")
    if [ "$prev" = "--runtimeJar" ]; then argv+=("--runtimeJar" "$OLD_DIR/forge-runtime-26.2.jar"); fi
    if [ "${#argv[@]}" -eq 1 ] && [ -n "$extra" ]; then argv+=("$extra"); fi
    prev="$line"
  done < "$CMD_FILE"
  ( cd "$DEST" && JAVA_TOOL_OPTIONS="${FORBRIC_JVM:-}" "${argv[@]}" ) > "$BUILD/gate-m17-old-$label.log" 2>&1 &
  local pid=$!
  for i in $(seq 1 120); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  if kill -0 "$pid" 2>/dev/null; then stop_tree "$pid" >&2; fi
  wait "$pid" 2>/dev/null; rc=$?
  echo "$rc"
}
if [ "$PY_RC" -eq 0 ]; then
  OLD_RC=$(old_launch refused)
  OLDOFF_RC=$(old_launch refused-off "-Dforbric.launchInputCheck=off")
  assert_eq "the old install's arguments end the launch with status 2" "2" "$OLD_RC"
  check "with the one old-install problem, naming the merged base's index" \
    "this game was installed by Forbric 0\.4 or earlier, still carries MinecraftForge \(game jar patched-mc-merged-26\.2\.jar embeds META-INF/forbric/native-reference/FORGE/index\.tsv; runtime jar forge-runtime-26\.2\.jar is a complete MinecraftForge runtime\); run the new installer again" \
    "$BUILD/gate-m17-old-refused.log"
  check_absent "and the game never booted" "Forbric/Boot\] game base" "$BUILD/gate-m17-old-refused.log"
  assert_eq "-Dforbric.launchInputCheck=off does not let it through: status 2 again" "2" "$OLDOFF_RC"
  check "and says why the switch does not apply" \
    "launchInputCheck=off does not apply to an install made by Forbric 0\.4 or earlier" "$BUILD/gate-m17-old-refused-off.log"
fi

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
# Two built artifacts since C1: the NeoForge runtime and NeoForm's patched game (six on the merged base).
REUSED=$(grep -cE "^\[(neoforge-runtime|neoform)\] up-to-date" "$BUILD/gate-m17-install-2.log")
assert_eq "every artifact came from the cache" "2" "$REUSED"
# Reused by what it records -- the SHA-1 of the vanilla jar and of the base -- not by the pins: rebuilt only when either
# input changed, and neither did.
check "the vanilla reference came from the cache too" "^\[vanilla-reference\] up-to-date" "$BUILD/gate-m17-install-2.log"

step "and a pin that no longer matches is NOT served from it"
# Only the NeoForge runtime's stamp is disturbed, because the stamp is the whole pin set: in real use both move
# together, and tampering with one is the cheapest honest probe of the DECISION. The runtime is also the quicker of the
# two to rebuild (its inputs are already in .forbric-build/dl).
NR_STAMP="$DEST/.forbric-build/out/neoforge-runtime.jar.pins"
if [ ! -f "$NR_STAMP" ]; then
  echo "[kernel] FAIL no stamp beside neoforge-runtime.jar — nothing records what built it"; FAIL=$((FAIL+1))
else
  echo "mc=26.2 neoforge=PRETEND-OTHER nfrt=0 result=none" > "$NR_STAMP"
  java -jar "$JAR" --dir "$DEST" --mc 26.2 > "$BUILD/gate-m17-install-3.log" 2>&1
  check_absent "the stale artifact is rebuilt, not reused" "^\[neoforge-runtime\] up-to-date" \
    "$BUILD/gate-m17-install-3.log"
  check "and it says so"       "^\[neoforge-runtime\] (fetching|merging|wrote)" "$BUILD/gate-m17-install-3.log"
  check "the others still hit" "^\[neoform\] up-to-date"                        "$BUILD/gate-m17-install-3.log"
fi

step "the link checker's tools jar is not served from the previous install (must PASS)"
# The layer below the artifact cache. The installer carries forbric/tools/forbric-merge-tools.jar for its link checker
# (LinkCheckTool) and runs the link check on every install, a reinstall served wholly from the cache included
# (ArtifactBuilder.build). LinkCheckTool.unpackTools overwrites .forbric-build/tools/forbric-merge-tools.jar each time; it
# used to keep a copy that was already there, and a rebuild then ran the PREVIOUS installer's tool -- a stale checker
# passes jars the current one would refuse. So: spoil that copy, reinstall over the full cache, and the install must still
# pass the link check (it cannot, from a file that is not a jar) and leave the bundled jar's bytes there. RED, measured
# (C5, 2026-11-09): an installer built with unpackTools keeping an existing file, run on a copy of this install with the
# same spoiled file, exits 1 ("Could not find or load main class net.forbric.tools.MergedLinkChecker").
# (On the merged base this phase forced a merge to make the install ask for the tool; since C1 the link check asks.)
TOOLS="$DEST/.forbric-build/tools/forbric-merge-tools.jar"
WANT="$BUILD/gate-m17-tools-bundled.jar"
unzip -p "$JAR" forbric/tools/forbric-merge-tools.jar > "$WANT" 2>/dev/null
if [ ! -s "$WANT" ]; then
  echo "[kernel] FAIL the installer carries no bundled tools jar"; FAIL=$((FAIL+1))
elif [ ! -s "$TOOLS" ]; then
  echo "[kernel] FAIL the earlier installs left no tools jar in .forbric-build/tools"; FAIL=$((FAIL+1))
else
  printf 'not a jar at all' > "$TOOLS"
  java -jar "$JAR" --dir "$DEST" --mc 26.2 > "$BUILD/gate-m17-install-4.log" 2>&1
  assert_eq "the reinstall over a spoiled tools jar exited cleanly" "0" "$?"
  REUSED=$(grep -acE "^\[(neoforge-runtime|neoform)\] up-to-date" "$BUILD/gate-m17-install-4.log")
  assert_eq "it was served from the cache (both built artifacts up to date)" "2" "$REUSED"
  check_exactly "and still ran the link check, in both orders" \
    "^\[link-check\] loaded [0-9]+ classes .*; dangling references: 0$" "$BUILD/gate-m17-install-4.log" 2
  assert_eq "the spoiled tool is replaced by the bundled one" \
    "$(shasum -a 1 "$WANT" | cut -d' ' -f1)" "$(shasum -a 1 "$TOOLS" | cut -d' ' -f1)"
fi

step "an offline reinstall over that cache needs no network (must PASS)"
# A reinstall whose every artifact is cached must not need the network: the NeoForge runtime and NeoForm's game are served
# by their stamps before anything is fetched (NfrtRunner hashes or downloads the server jar only once NFRT is about to
# run), the vanilla reference by the SHA-1s it records, and the link checker from the installer's own jar. "Offline" is a
# proxy on a port nothing listens on, for http and https alike, so any fetch the install attempted would fail it.
# The port is one the system just handed out and took back, so nothing listens there; it is not a knob, because no
# server binds it (gates-parallel.py hands out the ports servers bind).
# (On the merged base this phase also removed one library the base version lists first, because the merge read the
# base's libraries and they were fetched ahead of its cache check. The installer has fetched no Minecraft library since
# C1 -- MojangDownloader.downloadLibraries went with the merge, D13 -- so that step had nothing left to catch, D34; any
# other fetch is caught by the dead proxy below, and the launcher-side library copy is the step above.)
DEAD_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
echo "[kernel] proxy 127.0.0.1:$DEAD_PORT (nothing listening)"
java -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort="$DEAD_PORT" -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort="$DEAD_PORT" \
  -jar "$JAR" --dir "$DEST" --mc 26.2 > "$BUILD/gate-m17-install-5.log" 2>&1
assert_eq "the offline reinstall exited cleanly" "0" "$?"
REUSED=$(grep -acE "^\[(neoforge-runtime|neoform)\] up-to-date" "$BUILD/gate-m17-install-5.log")
assert_eq "every built artifact came from the cache" "2" "$REUSED"
check "and it measured no new vanilla reference" "^\[vanilla-reference\] up-to-date" "$BUILD/gate-m17-install-5.log"
check_exactly "the link check ran offline, in both orders" \
  "^\[link-check\] loaded [0-9]+ classes .*; dangling references: 0$" "$BUILD/gate-m17-install-5.log" 2

step "M17 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M17 GATE GREEN — the installer's own version profile, resolved the way a launcher resolves it, boots the Fabric + NeoForge game"
else
  echo "[kernel] ❌ M17 GATE RED — install $LOG / client $CLOG / command $CMD_FILE"
fi
exit "$FAIL"
