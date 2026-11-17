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
# and a kernel handed the arguments of an install made by Forbric 0.3.x or earlier refuses them (control N, below).
#
# The tools-jar and offline phases were written around the merge, which no install runs since C1, and both now assert what
# an install still does (D70): every install runs the link check from the checker jar the installer carries, unpacked over
# whatever an earlier install left in .forbric-build/tools, and a reinstall over a full cache goes to the network for
# nothing. D44 had C6 delete both; D70 found both premises live, and nothing else covers either (no unit test drives
# LinkCheckTool's unpack, none denies an install the network), so they stay (D34, D89). Since C6 (LINK) the checker is
# the kernel's own, forbric-link-check.jar: the installer must carry exactly that one and no forbric-merge-tools.jar, and an
# older install's merge-tools copy must be cleaned away.
#
# Phase U (D6, D32, D44; C6): the new installer run over the install a player of the last public release has. The public
# v0.3.1-beta2 installer jar (the owner allowed that one download, 2026-11-17) is fetched once into run/.gate-downloads/m17,
# SHA-1 checked, and run once into an empty directory there; that directory -- beta2's own profile, libraries and
# .forbric-build, built from what beta2 itself downloads -- is cloned for every run. A second profile still names beta2's
# kernel; it must survive while everything else old goes -- beta2's merged base in libraries among them, as on a player's
# machine -- each removal named in the install's log. A JSON beside a profile that is not one (the game's, under a
# launcher's version isolation) and does not parse must not stop the clean-up. The reverse control runs the same installer
# with -Dforbric.installerCleanup=off first: every removal check must be red. If the old install cannot be fetched or laid
# out (a fresh worktree without the network), phase U alone is red and every other phase still runs; copying
# run/.gate-downloads/m17 from a worktree that has it saves the fetch (the SHA-1 and the marker are checked either way).
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
# M17_UPGRADE_DEST: phase U's directory (default run/installed-upgrade, wiped too).
# GATE-PARALLEL: rundirs=installed,installed-upgrade mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

INSTALLER="$KERNEL/../forbric-kernel-installer"
DEST="${M17_DEST:-$KERNEL/run/installed}"
refuse_shared_rundir "$DEST"
DEST_UP="${M17_UPGRADE_DEST:-$KERNEL/run/installed-upgrade}"
refuse_shared_rundir "$DEST_UP"
# Phase U's old install: what the public v0.3.1-beta2 installer writes, laid out once under run/.gate-downloads.
BETA2_URL=https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/download/v0.3.1-beta2/forbric-kernel-installer-0.3.1-beta2.jar
BETA2_SHA1=ae2ff4faa691665d4768218b97820d7ef9e11ce9
BETA2_JAR="$GATE_DOWNLOADS/m17/forbric-kernel-installer-0.3.1-beta2.jar"
BETA2_INSTALL="$GATE_DOWNLOADS/m17/v0.3.1-beta2-install"
LOG="$BUILD/gate-m17-install.log"
CLOG="$BUILD/gate-m17-client.log"
MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
WORLD="${M17_WORLD:-ForbricTest}"
SRC_RUNDIR="${M17_SRC_RUNDIR:-$KERNEL/run/client-merged-pack}"
mkdir -p "$BUILD"

[ -d "$MC/assets" ] || { echo "[kernel] SKIP-FATAL: no Minecraft assets at $MC/assets" >&2; exit 3; }
[ -d "$SRC_RUNDIR/saves/$WORLD" ] || { echo "[kernel] SKIP-FATAL: no world at $SRC_RUNDIR/saves/$WORLD" >&2; exit 3; }
# The released installer: fetched once (owner answer 2), and refused unless it is the one published. Its install, once:
# an empty directory with the base version, as a player's .minecraft had it, kept only when beta2 says it installed (the
# marker names the jar that wrote it). A failure here is phase U's alone (BETA2_FAIL, judged there), not the gate's.
BETA2_FAIL=""
lay_out_beta2() {
  if [ "$(shasum -a 1 "$BETA2_JAR" 2>/dev/null | cut -d' ' -f1)" != "$BETA2_SHA1" ]; then
    mkdir -p "$(dirname "$BETA2_JAR")"
    curl -fsSL -o "$BETA2_JAR.part" "$BETA2_URL" \
      || { rm -f "$BETA2_JAR.part"; BETA2_FAIL="could not fetch the v0.3.1-beta2 installer from $BETA2_URL"; return 1; }
    local got
    got="$(shasum -a 1 "$BETA2_JAR.part" | cut -d' ' -f1)"
    [ "$got" = "$BETA2_SHA1" ] \
      || { rm -f "$BETA2_JAR.part"; BETA2_FAIL="$BETA2_URL has sha1 $got, not the published $BETA2_SHA1"; return 1; }
    mv "$BETA2_JAR.part" "$BETA2_JAR"
  fi
  if [ "$(cat "$BETA2_INSTALL.done" 2>/dev/null)" != "$BETA2_SHA1" ]; then
    echo "[kernel] laying out phase U's old install with the released v0.3.1-beta2 installer (once; it downloads and builds)"
    rm -rf "$BETA2_INSTALL" "$BETA2_INSTALL.done"
    mkdir -p "$BETA2_INSTALL/versions/26.2"
    cp "$MC/versions/26.2/26.2.json" "$MC/versions/26.2/26.2.jar" "$BETA2_INSTALL/versions/26.2/" 2>/dev/null || true
    if java -jar "$BETA2_JAR" --dir "$BETA2_INSTALL" --mc 26.2 > "$BUILD/gate-m17-beta2-install.log" 2>&1 \
        && grep -aq '^Installed\.' "$BUILD/gate-m17-beta2-install.log"; then
      echo "$BETA2_SHA1" > "$BETA2_INSTALL.done"
    else
      BETA2_FAIL="the v0.3.1-beta2 installer did not install (log $BUILD/gate-m17-beta2-install.log)"; return 1
    fi
  fi
}
if lay_out_beta2; then
  echo "[kernel] phase U's old install: $BETA2_INSTALL, written by v0.3.1-beta2 (sha1 $BETA2_SHA1)"
else
  echo "[kernel] phase U's old install is missing: $BETA2_FAIL; phase U will be red, the rest of the gate runs"
fi

kernel_jar

step "build the installer jar"
# Offline and without a daemon, like every other Gradle run of a gate. kernel_jar (above) built the kernel jar the
# installer bundles; the link checker it carries is built here by the module that owns it -- the kernel's linkCheckJar
# since C6 (LINK), the loader's mergeToolsJar before -- and the installer's own nested builds of the two are left out
# because they call gradlew online, and its bundle tasks fail loudly on a jar that is not there.
if grep -q "register('buildLinkCheck'" "$INSTALLER/build.gradle"; then
  CHECKER_DIR="$KERNEL"; CHECKER_TASK=linkCheckJar; CHECKER_NESTED=buildLinkCheck
else
  echo "[kernel] FATAL: the installer's build file has no buildLinkCheck task; re-point this step" >&2; exit 3
fi
if ! { (cd "$CHECKER_DIR" && ./gradlew --offline --no-daemon -q "$CHECKER_TASK") \
       && (cd "$INSTALLER" && ./gradlew --offline --no-daemon -q jar -x buildForbricJars -x "$CHECKER_NESTED"); } \
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
# Every install ends with the clean-up of what an older Forbric left (OldInstallCleanup, D6); an empty directory holds none
# of it, so it ran and removed nothing. Phase U, at the end, is the install where it has something to do.
check_exactly "the clean-up ran, and found nothing old in an empty directory" "^\[cleanup\] removed 0 file\(s\) an older Forbric left behind \(0\.0 MB\)$" "$LOG" 1
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
# copy_vanilla_libraries <install-dir> -- prints how many it copied. Phase U gives its directory the same.
copy_vanilla_libraries() {
  python3 - "$1" "$MC" <<'LIBS'
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
}
COPIED=$(copy_vanilla_libraries "$DEST")
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
# resolve_profile <install-dir> <command-file> -- the profile there, resolved into one java command. Phase U resolves its own.
resolve_profile() {
  python3 - "$1" "$MC" "$WORLD" "$2" <<'PY'
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
}
resolve_profile "$DEST" "$CMD_FILE"
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

# launch_resolved <install-dir> <command-file> <client-log> -- run that command into its world and out again.
launch_resolved() {
  local dest="$1" cmd="$2" clog="$3" pid game i
  echo "[kernel] launching the resolved command (mainClass $(grep -c . "$cmd") argv entries)"
  # The resolved command is a java line of its own (no launcher reads FORBRIC_JVM there): the caller's FORBRIC_JVM -- a
  # sweep's locale, say -- reaches the game through JAVA_TOOL_OPTIONS, ahead of the smoke properties.
  ( cd "$dest" && tr '\n' '\0' < "$cmd" | xargs -0 env \
      JAVA_TOOL_OPTIONS="${FORBRIC_JVM:-} -Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=140" \
      ) > "$clog" 2>&1 &
  pid=$!
  echo "[kernel] client pid=$pid (killed by pid only — another client may be running)"
  game="$dest/logs/latest.log"
  for i in $(seq 1 300); do
    kill -0 "$pid" 2>/dev/null || { echo "[kernel] client exited on its own after ~${i}s"; break; }
    grep -qE 'ClientSmoke\] clean disconnect observed|Game crashed|Mod Loading has failed' "$game" 2>/dev/null \
      && { echo "[kernel] outcome reached after ~${i}s"; break; }
    sleep 1
  done
  for i in $(seq 1 25); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  stop_tree "$pid"   # the whole tree: lib.sh says why children-only orphans a JVM
  wait "$pid" 2>/dev/null
  cat "$game" >> "$clog" 2>/dev/null || true
}
if [ "$PY_RC" -eq 0 ]; then
  launch_resolved "$DEST" "$CMD_FILE" "$CLOG"
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

step "control N: this kernel, handed an install made by Forbric 0.3.x or earlier, refuses it (must PASS)"
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
    "this game was installed by Forbric 0\.3\.x or earlier \(or a 0\.4\.0-dev build that still had MinecraftForge\), still carries MinecraftForge \(game jar patched-mc-merged-26\.2\.jar embeds META-INF/forbric/native-reference/FORGE/index\.tsv; runtime jar forge-runtime-26\.2\.jar is a complete MinecraftForge runtime\); run the new installer again" \
    "$BUILD/gate-m17-old-refused.log"
  check_absent "and the game never booted" "Forbric/Boot\] game base" "$BUILD/gate-m17-old-refused.log"
  assert_eq "-Dforbric.launchInputCheck=off does not let it through: status 2 again" "2" "$OLDOFF_RC"
  check "and says why the switch does not apply" \
    "launchInputCheck=off does not apply to an install made by Forbric 0\.3\.x or earlier" "$BUILD/gate-m17-old-refused-off.log"
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
# The clean-up never takes this install's own files: its outputs and stamps, the download cache, its libraries.
check_exactly "the clean-up removed none of this install's own files" "^\[cleanup\] removed 0 file\(s\) an older Forbric left behind \(0\.0 MB\)$" "$BUILD/gate-m17-install-2.log" 1

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
# The layer below the artifact cache. The installer carries one checker jar under forbric/tools/ for its link check
# (LinkCheckTool) and runs the link check on every install, a reinstall served wholly from the cache included
# (ArtifactBuilder.build). LinkCheckTool overwrites its copy in .forbric-build/tools each time; it used to keep a copy that
# was already there, and a rebuild then ran the PREVIOUS installer's tool -- a stale checker passes jars the current one
# would refuse. So: spoil that copy, reinstall over the full cache, and the install must still pass the link check (it
# cannot, from a file that is not a jar) and leave the bundled jar's bytes there. RED, measured (C5, 2026-11-09): an
# installer built with the unpack keeping an existing file, run on a copy of this install with the same spoiled file, exits 1
# ("Could not find or load main class net.forbric.tools.MergedLinkChecker").
# (On the merged base this phase forced a merge to make the install ask for the tool; since C1 the link check asks.)
# Since C6 the checker is the kernel's own (forbric-link-check.jar), and an older install's forbric-merge-tools.jar is one
# more file nothing reads: it is planted here and must be cleaned away, by name in the log (OldInstallCleanup).
CHECKERS="$(unzip -Z1 "$JAR" 2>/dev/null | grep -aE '^forbric/tools/[^/]+\.jar$')"
WANT="$BUILD/gate-m17-tools-bundled.jar"
rm -f "$WANT"
if [ "$CHECKERS" != forbric/tools/forbric-link-check.jar ]; then
  echo "[kernel] FAIL the installer carries exactly one checker jar, forbric/tools/forbric-link-check.jar (it carries: $(printf '%s ' $CHECKERS | sed 's/ $//'))"
  FAIL=$((FAIL+1))
  CHECKER=""
else
  CHECKER="$(basename "$CHECKERS")"
  unzip -p "$JAR" "$CHECKERS" > "$WANT" 2>/dev/null
fi
TOOLS="$DEST/.forbric-build/tools/$CHECKER"
OLD_MERGE_TOOLS="$DEST/.forbric-build/tools/forbric-merge-tools.jar"
if [ -z "$CHECKER" ] || [ ! -s "$WANT" ]; then
  echo "[kernel] FAIL the installer carries no bundled checker jar"; FAIL=$((FAIL+1))
elif [ ! -s "$TOOLS" ]; then
  echo "[kernel] FAIL the earlier installs left no $CHECKER in .forbric-build/tools"; FAIL=$((FAIL+1))
else
  printf 'not a jar at all' > "$TOOLS"
  printf 'an older installer left this' > "$OLD_MERGE_TOOLS"
  java -jar "$JAR" --dir "$DEST" --mc 26.2 > "$BUILD/gate-m17-install-4.log" 2>&1
  assert_eq "the reinstall over a spoiled $CHECKER exited cleanly" "0" "$?"
  REUSED=$(grep -acE "^\[(neoforge-runtime|neoform)\] up-to-date" "$BUILD/gate-m17-install-4.log")
  assert_eq "it was served from the cache (both built artifacts up to date)" "2" "$REUSED"
  check_exactly "and still ran the link check, in both orders" \
    "^\[link-check\] loaded [0-9]+ classes .*; dangling references: 0$" "$BUILD/gate-m17-install-4.log" 2
  assert_eq "the spoiled tool is replaced by the bundled one" \
    "$(shasum -a 1 "$WANT" | cut -d' ' -f1)" "$(shasum -a 1 "$TOOLS" | cut -d' ' -f1)"
  [ ! -e "$OLD_MERGE_TOOLS" ] && echo "[kernel] PASS an older installer's merge-tools jar is cleaned away" \
    || { echo "[kernel] FAIL an older installer's merge-tools jar survived an installer that carries none"; FAIL=$((FAIL+1)); }
  check_exactly "and the install's log names it, and nothing else" \
    "^\[cleanup\] removed .*/\.forbric-build/tools/forbric-merge-tools\.jar " "$BUILD/gate-m17-install-4.log" 1
  check_exactly "one file removed in all" "^\[cleanup\] removed 1 file\(s\) an older Forbric left behind" \
    "$BUILD/gate-m17-install-4.log" 1
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
check_exactly "and the clean-up removed nothing" "^\[cleanup\] removed 0 file\(s\) an older Forbric left behind \(0\.0 MB\)$" "$BUILD/gate-m17-install-5.log" 1

# ---------------------------------------------------------------------------------------------------------
# Phase U: the new installer over the install the last public release left (D6, D32, D44). A player's directory, not an
# empty one: v0.3.1-beta2's profile, libraries and .forbric-build as that installer wrote them (cloned from the one laid out
# above), plus a second profile the player still has. Two installs: first with -Dforbric.installerCleanup=off, the reverse
# control (every removal check must be red), then the real one over the same directory, after which only what nothing
# names is gone, every removal is in the log, dl/ and the player's files are untouched, and the game boots from the result.
if [ -n "$BETA2_FAIL" ]; then
step "phase U: lay out the install v0.3.1-beta2 left"
echo "[kernel] FAIL phase U has no old install to upgrade: $BETA2_FAIL"; FAIL=1
else  # phase U, to the line "fi  # phase U" (not indented: its here-documents end at column 0)
step "phase U: lay out the install v0.3.1-beta2 left"
rm -rf "$DEST_UP"
cp -cR "$BETA2_INSTALL" "$DEST_UP" 2>/dev/null || { rm -rf "$DEST_UP"; cp -R "$BETA2_INSTALL" "$DEST_UP"; }
UB="$DEST_UP/.forbric-build"
# The keep-old control: a second profile naming beta2's kernel (critic 24: an older kernel another profile names
# survives). Only the kernel: beta2's merged base in libraries is named by nothing once the new profile replaces beta2's,
# and must go, as it does on a player's machine.
mkdir -p "$DEST_UP/versions/keep-old"
cat > "$DEST_UP/versions/keep-old/keep-old.json" <<'KEEP'
{"id": "keep-old", "inheritsFrom": "26.2", "type": "release", "mainClass": "net.forbric.kernel.boot.KernelClientLaunch",
 "libraries": [{"name": "net.forbric:forbric-kernel:0.3.1-beta2"}]}
KEEP
# Under a launcher's version isolation versions/<id> is the game directory, and the game writes JSON there: one that does
# not parse is skipped, named, and does not stop the clean-up (only versions/<id>/<id>.json is a profile).
: > "$DEST_UP/versions/keep-old/usercache.json"
grep -aq '"--gameJar"' "$DEST_UP/versions/26.2-forbric/26.2-forbric.json" \
  && grep -aq 'patched-mc-merged/26.2/' "$DEST_UP/versions/26.2-forbric/26.2-forbric.json" \
  && echo "[kernel] PASS the old profile is beta2's: it opens the merged base" \
  || { echo "[kernel] FAIL the old profile is not the one v0.3.1-beta2 writes"; FAIL=1; }
# dl/ before: every file an older installer cached there must come out of the upgrade unchanged (critic 19).
dl_files() { (cd "$UB/dl" && find . -type f | LC_ALL=C sort | xargs shasum -a 1); }
dl_files > "$BUILD/gate-m17-upgrade-dl-before.txt"
# What this installer downloads that beta2's caches lack (NeoForge 26.2.0.93's userdev, universal and libraries, NFRT's
# artifacts for it) is exactly what the first install above fetched in this run: copied in from there, never over a file
# beta2 left, so phase U does not depend on the network a second time (two m17 runs on 2026-11-17 lost a cold download to a
# TLS failure from Mojang's and NeoForge's servers).
for cache in dl nfrt; do
  [ -d "$DEST/.forbric-build/$cache" ] || continue
  (cd "$DEST/.forbric-build/$cache" && find . -type f) | while IFS= read -r f; do
    [ -e "$UB/$cache/$f" ] && continue
    mkdir -p "$UB/$cache/$(dirname "$f")"
    cp -c "$DEST/.forbric-build/$cache/$f" "$UB/$cache/$f" 2>/dev/null || cp "$DEST/.forbric-build/$cache/$f" "$UB/$cache/$f"
  done
done

# The player's files, as in the first install, and their digests before anything runs.
cp -cR "$SRC_RUNDIR/saves" "$DEST_UP/saves" 2>/dev/null || cp -R "$SRC_RUNDIR/saves" "$DEST_UP/saves"
cp -R "$SRC_RUNDIR/config" "$DEST_UP/config" 2>/dev/null || true
cp -R "$SRC_RUNDIR/defaultconfigs" "$DEST_UP/defaultconfigs" 2>/dev/null || true
cp "$SRC_RUNDIR/options.txt" "$DEST_UP/options.txt" 2>/dev/null || true
mkdir -p "$DEST_UP/mods" "$DEST_UP/quickPlay"
cp "$SRC_RUNDIR/mods"/*.jar "$DEST_UP/mods/" 2>/dev/null || true
user_data() { (cd "$DEST_UP" && find mods saves config defaultconfigs options.txt -type f 2>/dev/null | LC_ALL=C sort | xargs shasum -a 1); }
user_data > "$BUILD/gate-m17-upgrade-userdata-before.txt"

# What must go (relative to $DEST_UP) and what must stay: everything of beta2's that no profile names, and every
# MinecraftForge or merged product in .forbric-build -- not dl/ (critic 19: the download cache is never touched).
UP_GONE=(
  libraries/net/forbric/patched-mc-merged/26.2/patched-mc-merged-26.2.jar
  libraries/net/forbric/forge-runtime/26.2/forge-runtime-26.2.jar
  libraries/net/forbric/neoforge-runtime/26.2/neoforge-runtime-26.2.jar
  libraries/net/forbric/forbric-loader/0.3.1-beta2/forbric-loader-0.3.1-beta2.jar
  libraries/net/forbric/forbricruntime/0.3.1-beta2/forbricruntime-0.3.1-beta2.jar
  .forbric-build/out/patched-mc-merged-26.2.jar .forbric-build/out/patched-mc-merged-26.2.jar.pins
  .forbric-build/out/merge-conflicts.txt
  .forbric-build/out/forge-runtime.jar .forbric-build/out/forge-runtime.jar.pins
  .forbric-build/out/forge-runtime-interop.jar .forbric-build/out/forge-runtime-interop.jar.pins
  .forbric-build/out/patched-mc-forge-26.2.jar .forbric-build/out/patched-mc-forge-26.2.jar.pins
  .forbric-build/server-main.jar .forbric-build/clean.jar .forbric-build/patched-subset.jar .forbric-build/patched-full.jar
  .forbric-build/patched-at.jar .forbric-build/joined.lzma .forbric-build/ats/accesstransformer.cfg
)
UP_KEPT_OLD=(
  libraries/net/forbric/forbric-kernel/0.3.1-beta2/forbric-kernel-0.3.1-beta2.jar
)
# This install's own: its four libraries and .forbric-build/out's outputs with their stamps.
UP_CURRENT=(
  libraries/net/forbric/patched-mc-neoforge/26.2-26.2.0.93/patched-mc-neoforge-26.2-26.2.0.93.jar
  libraries/net/forbric/neoforge-runtime/26.2.0.93/neoforge-runtime-26.2.0.93.jar
  libraries/net/forbric/vanilla-reference/26.2-26.2.0.93/vanilla-reference-26.2-26.2.0.93.jar
  "libraries/net/forbric/forbric-kernel/$VERSION/forbric-kernel-$VERSION.jar"
  .forbric-build/out/patched-mc-neoforge-26.2.jar .forbric-build/out/patched-mc-neoforge-26.2.jar.pins
  .forbric-build/out/neoforge-runtime.jar .forbric-build/out/neoforge-runtime.jar.pins
  .forbric-build/out/vanilla-reference-26.2.jar
)
# beta2 left its merge tools in tools/; this installer carries the kernel's checker instead, and the clean-up removes them.
[ -n "$CHECKER" ] && UP_CURRENT+=(".forbric-build/tools/$CHECKER")
UP_GONE+=(.forbric-build/tools/forbric-merge-tools.jar)
UP_PRESENT_BEFORE=0
for rel in "${UP_GONE[@]}" "${UP_KEPT_OLD[@]}"; do [ -e "$DEST_UP/$rel" ] && UP_PRESENT_BEFORE=$((UP_PRESENT_BEFORE+1)); done
assert_eq "the old layout holds every file phase U judges" "$(( ${#UP_GONE[@]} + ${#UP_KEPT_OLD[@]} ))" "$UP_PRESENT_BEFORE"

step "phase U, reverse control: the same installer with its clean-up off leaves every old file (must PASS)"
java -Dforbric.installerCleanup=off -jar "$JAR" --dir "$DEST_UP" --mc 26.2 > "$BUILD/gate-m17-upgrade-off.log" 2>&1
assert_eq "the install over the old one exited cleanly" "0" "$?"
check_exactly "its clean-up said it was off" "^\[cleanup\] off \(-Dforbric\.installerCleanup=off\)" "$BUILD/gate-m17-upgrade-off.log" 1
# The old stamps name MinecraftForge and the merge tools: nothing of the old build is served as current.
check_absent "nothing of the old build was taken as up to date" "^\[(neoforge-runtime|neoform)\] up-to-date" \
  "$BUILD/gate-m17-upgrade-off.log"
UP_RED=0
for rel in "${UP_GONE[@]}"; do [ -e "$DEST_UP/$rel" ] && UP_RED=$((UP_RED+1)); done
assert_eq "every one of phase U's ${#UP_GONE[@]} removal checks is red with the clean-up off" "${#UP_GONE[@]}" "$UP_RED"

step "phase U: the profile the new installer wrote over the old one (must PASS)"
UP_PROFILE="$DEST_UP/versions/26.2-forbric/26.2-forbric.json"
UP_PROFILE_CHECKS=$(python3 - "$UP_PROFILE" <<'UPPROF'
import json, sys
text = open(sys.argv[1]).read()
game = json.loads(text)["arguments"]["game"]
def values(flag):
    return [game[i + 1] for i, token in enumerate(game[:-1]) if token == flag]
def say(ok, what):
    print("[kernel] %s %s" % ("PASS" if ok else "FAIL", what))
for flag, tail in (("--gameJar", "/patched-mc-neoforge-26.2-26.2.0.93.jar"),
                   ("--runtimeJar", "/neoforge-runtime-26.2.0.93.jar"),
                   ("--nativeReference", "/vanilla-reference-26.2-26.2.0.93.jar")):
    got = values(flag)
    say(len(got) == 1 and got[0].endswith(tail), "one %s, and it is %s (%s)" % (flag, tail.lstrip("/"), got))
for old in ("patched-mc-merged", "/forge-runtime", ":forge-runtime", "forbric-loader", "forbricruntime"):
    say(old not in text, "the profile no longer names %s" % old)
UPPROF
)
echo "$UP_PROFILE_CHECKS"
echo "$UP_PROFILE_CHECKS" | grep -q '^\[kernel\] PASS' || { echo "[kernel] FAIL the rewritten profile could not be read"; FAIL=1; }
echo "$UP_PROFILE_CHECKS" | grep -q '^\[kernel\] FAIL' && FAIL=1
step "phase U: the real install over the same directory cleans up (must PASS)"
java -jar "$JAR" --dir "$DEST_UP" --mc 26.2 > "$BUILD/gate-m17-upgrade.log" 2>&1
assert_eq "the install exited cleanly" "0" "$?"
for rel in "${UP_GONE[@]}"; do
  if [ -e "$DEST_UP/$rel" ]; then
    echo "[kernel] FAIL still there, though nothing names it: $rel"; FAIL=1
  elif ! grep -aqF "[cleanup] removed $DEST_UP/$rel " "$BUILD/gate-m17-upgrade.log"; then
    echo "[kernel] FAIL gone, but the install's log does not name it: $rel"; FAIL=1
  else
    echo "[kernel] PASS removed, and named in the log: $rel"
  fi
done
check_exactly "the clean-up removed exactly those ${#UP_GONE[@]} files" \
  "^\[cleanup\] removed ${#UP_GONE[@]} file\(s\) an older Forbric left behind \([0-9.]+ MB\)$" "$BUILD/gate-m17-upgrade.log" 1
check_absent "and none failed" "^\[cleanup\] could not" "$BUILD/gate-m17-upgrade.log"
check_absent "and it did not stop part-way" "^\[cleanup\] the clean-up stopped" "$BUILD/gate-m17-upgrade.log"
check_exactly "the game's unparsable JSON beside a profile was skipped, and named" \
  "^\[cleanup\] skipped $DEST_UP/versions/keep-old/usercache\.json " "$BUILD/gate-m17-upgrade.log" 1
for rel in "${UP_KEPT_OLD[@]}"; do
  [ -f "$DEST_UP/$rel" ] && echo "[kernel] PASS kept, the keep-old profile still names it: $rel" \
    || { echo "[kernel] FAIL removed, though the keep-old profile names it: $rel"; FAIL=1; }
done
for rel in "${UP_CURRENT[@]}"; do
  [ -f "$DEST_UP/$rel" ] && echo "[kernel] PASS this install's own file is there: $rel" \
    || { echo "[kernel] FAIL this install's own file is missing: $rel"; FAIL=1; }
done
# Only .forbric-build's own level, out/ and tools/: NFRT's caches and work tree hold names of their own (an "ats" among
# them) that are not an older installer's.
LEFT=$(cd "$UB" && { ls -1; ls -1 out tools 2>/dev/null; } | grep -aE '^(patched-mc-merged|forge-runtime|patched-mc-forge|merge-conflicts\.txt$|server-main\.jar$|clean\.jar$|patched-(subset|full|at)\.jar$|joined\.lzma$|ats$)' | tr '\n' ' ')
assert_eq ".forbric-build holds no MinecraftForge or merged product" "" "$LEFT"
dl_files > "$BUILD/gate-m17-upgrade-dl-after.txt"
DL_CHANGED=$(LC_ALL=C comm -23 "$BUILD/gate-m17-upgrade-dl-before.txt" "$BUILD/gate-m17-upgrade-dl-after.txt" | wc -l | tr -d ' ')
assert_eq "dl/ is untouched: every file an older installer cached there is still there, unchanged ($(wc -l < "$BUILD/gate-m17-upgrade-dl-before.txt" | tr -d ' ') files)" "0" "$DL_CHANGED"
# And nothing else is left under net/forbric: this install's four, and the one the keep-old profile names.
FORBRIC_LEFT=$(cd "$DEST_UP/libraries" && find net/forbric -type f | LC_ALL=C sort | tr '\n' ' ')
FORBRIC_WANT=$(for rel in "${UP_KEPT_OLD[@]}" "${UP_CURRENT[@]}"; do case "$rel" in (libraries/*) echo "${rel#libraries/}";; esac; done | LC_ALL=C sort | tr '\n' ' ')
assert_eq "libraries/net/forbric holds exactly this install's files and the ones another profile names" "$FORBRIC_WANT" "$FORBRIC_LEFT"
user_data > "$BUILD/gate-m17-upgrade-userdata-after.txt"
if cmp -s "$BUILD/gate-m17-upgrade-userdata-before.txt" "$BUILD/gate-m17-upgrade-userdata-after.txt"; then
  echo "[kernel] PASS the player's mods, saves, config and options are byte-identical ($(wc -l < "$BUILD/gate-m17-upgrade-userdata-after.txt" | tr -d ' ') files)"
else
  echo "[kernel] FAIL the player's files changed across the upgrade"; FAIL=1
fi

step "phase U: the upgraded directory boots into the world (must PASS)"
UP_COPIED=$(copy_vanilla_libraries "$DEST_UP")
echo "[kernel] copied $UP_COPIED vanilla librar(ies) into the upgraded install"
UP_CMD="$BUILD/gate-m17-upgrade-command.txt"
UP_CLOG="$BUILD/gate-m17-upgrade-client.log"
resolve_profile "$DEST_UP" "$UP_CMD"
UP_RC=$?
assert_eq "the upgraded profile resolved like a launcher would" "0" "$UP_RC"
check "and it runs this kernel, not beta2's" "/forbric-kernel-$VERSION\.jar" "$UP_CMD"
[ "$UP_RC" -eq 0 ] && launch_resolved "$DEST_UP" "$UP_CMD" "$UP_CLOG"
check "the kernel booted from the upgraded profile" "Forbric/Boot\] sovereign kernel — client" "$UP_CLOG"
check "it opened the game base"                    "Forbric/Boot\] game base"                 "$UP_CLOG"
check "it entered the world"                        "ClientSmoke\] client-ready after"         "$UP_CLOG"
check "it left cleanly"                             "ClientSmoke\] clean disconnect observed"  "$UP_CLOG"
# A refusal of the launch inputs is told by the kernel's install tag, not by its wording (which names versions, and is
# due to change): control N's refused launch above must match the same pattern, so it cannot go blind.
UP_REFUSED='\[Forbric/Install\] (launch stopped|the Forbric install is broken)'
[ "$PY_RC" -eq 0 ] && check "the refusal pattern matches control N's refused launch" "$UP_REFUSED" "$BUILD/gate-m17-old-refused.log"
check_absent "it was not refused as an old install" "$UP_REFUSED"                              "$UP_CLOG"
check_absent "no client crash"                      "Preparing crash report"                   "$UP_CLOG"
check_absent "nothing the installer staged went missing" \
  "(NoClassDefFoundError|ClassNotFoundException).*(net/forbric|net\\.forbric|net/minecraft|org/apache/logging|com/mojang|org/objectweb/asm|org/spongepowered)" "$UP_CLOG"
fi  # phase U

step "M17 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M17 GATE GREEN — the installer's own version profile, resolved the way a launcher resolves it, boots the Fabric + NeoForge game"
else
  echo "[kernel] ❌ M17 GATE RED — install $LOG / client $CLOG / command $CMD_FILE"
fi
exit "$FAIL"
