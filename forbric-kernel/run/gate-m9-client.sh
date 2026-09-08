#!/usr/bin/env bash
# M9 gate — the CLIENT half. A tri-ecosystem instance must reach a rendered world and leave it cleanly.
#
# Every client fix in this kernel was verified by launching the game and reading the log by hand, so none of them
# was protected against the next change. This gate is that protection: it drives a real client into a real world
# with -Dforbric.clientSmoke, then asserts the absence of each failure that has actually cost a world load here.
# Those check_absent lines are the point of the gate — they are a list of bugs, each one paid for.
#
# WHY IT KILLS BY PID. A developer (or a second agent session) may have their own Minecraft client open, and a
# name-matched kill would take it down with no warning and no way to tell whose it was. This gate kills the
# process tree it started and nothing else. The server gates now do the same, via await_server in lib.sh.
#
# The window between disconnect and exit is deliberate. Vanilla's own watchdog logs "Client shutdown from
# post-main" ~15s after main returns if a non-daemon thread is still alive, which is how a leaked mod thread
# announces itself — so the gate waits for the process to end on its own rather than killing it at the disconnect.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="${M9_RUNDIR:-$KERNEL/run/client-merged-pack}"
WORLD="${M9_WORLD:-ForbricTest}"
LOG="$BUILD/gate-m9-client-boot.log"
mkdir -p "$BUILD"

if [ ! -d "$RUNDIR/saves/$WORLD" ]; then
  echo "[kernel] SKIP-FATAL: no world at $RUNDIR/saves/$WORLD — this gate needs a pre-generated save" >&2
  exit 3
fi
if [ ! -f "$RUNDIR/options.txt" ]; then
  # Without it the accessibility onboarding screen sits in front of --quickPlaySingleplayer and nothing ever loads.
  echo "[kernel] SKIP-FATAL: no $RUNDIR/options.txt — quick-play would be blocked by the onboarding screen" >&2
  exit 3
fi

kernel_jar
mkdir -p "$RUNDIR/quickPlay"
rm -f "$RUNDIR/logs/latest.log"
: > "$LOG"

step "launch the client into $WORLD via quick-play ($(ls -1 "$RUNDIR/mods"/*.jar 2>/dev/null | wc -l | tr -d ' ') mods, no compatibility flags)"
# M9_EXTRA_JVM is how the gate's teeth are demonstrated: switch a fix off and this must go RED. Verified with
# -Dforbric.pruneDuplicateLambdas=off, which brings back StubException and the failed world load.
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=140 ${M9_EXTRA_JVM:-}" \
RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$LOG" 2>&1 &
CLIENT_PID=$!
echo "[kernel] client pid=$CLIENT_PID (this gate never kills by name — another client may be running)"

# The game log is the one with the mod chatter in it; the launcher log carries the JVM's own output.
GAMELOG="$RUNDIR/logs/latest.log"
for i in $(seq 1 400); do
  kill -0 "$CLIENT_PID" 2>/dev/null || { echo "[kernel] client exited on its own after ~${i}s"; break; }
  grep -qE 'ClientSmoke\] clean disconnect observed|Game crashed|Mod Loading has failed|Failed to load level data|Network Protocol Error' \
       "$GAMELOG" 2>/dev/null && { echo "[kernel] outcome reached after ~${i}s"; break; }
  sleep 1
done

step "let vanilla's post-main watchdog speak before killing anything"
for i in $(seq 1 25); do
  kill -0 "$CLIENT_PID" 2>/dev/null || break
  sleep 1
done

# ONLY this gate's own process tree.
for pid in $(pgrep -P "$CLIENT_PID" 2>/dev/null) "$CLIENT_PID"; do kill "$pid" 2>/dev/null; done
sleep 2
for pid in $(pgrep -P "$CLIENT_PID" 2>/dev/null) "$CLIENT_PID"; do kill -9 "$pid" 2>/dev/null; done

# The launcher log already carries the console appender, so this mostly duplicates it — deliberately, because
# the file appender is the only place some detail lands. Duplication cannot change a verdict: check is >=1
# and check_absent is ==0, so the counts printed below may read double and mean nothing by it.
cat "$GAMELOG" >> "$LOG" 2>/dev/null || true

step "the client entered a world and left it cleanly (must PASS)"
check "smoke controller armed"        "ClientSmoke\] armed on Minecraft.tick"      "$LOG"
check "joined a world"                "ClientSmoke\] joined world via quick-play"  "$LOG"
check "survived real simulation"      "ClientSmoke\] client-ready after"           "$LOG"
check "the window title was read"      "ClientSmoke\] window title: Minecraft"     "$LOG"
check_absent "…and it names no single loader" "ClientSmoke\] window title: .*(NeoForge|Forge|Fabric)" "$LOG"
check "left the world cleanly"        "ClientSmoke\] clean disconnect observed"    "$LOG"
check "server side really ran"        "joined the game"                            "$LOG"
check "datapacks fully loaded"        "Loaded [0-9]+ advancements"                 "$LOG"

step "the full FML mod lifecycle ran, not just the phases the kernel used to know about"
# Each of these was missing outright until the kernel started mirroring CommonModLoader.load's task order.
check "construct phase posted"        "posted FML construct to [0-9]+ NeoForge mod"      "$LOG"
check "client setup posted"           "posted FML client setup to [0-9]+ NeoForge mod"   "$LOG"
check "registration events ran"       "ran NeoForge.s registration events"               "$LOG"
check "IMC enqueued and processed"    "posted FML IMC (enqueue|process) to [0-9]+ NeoForge mod" "$LOG" 2
check "load complete posted"          "posted FML load complete to [0-9]+ NeoForge mod"  "$LOG"
check_absent "no mod failed a phase"  "failed during (construct|IMC enqueue|IMC process)" "$LOG"

step "a Forge-family mod's own content and data actually arrived (must PASS)"
# Three fixes that only this pack exercises, each demonstrable: -Dforbric.modDataPacks=off,
# -Dforbric.registryAliasParity=off, -Dforbric.neoRegistrationOrder=off each turn this gate RED.
check "datapacks served"              "Forbric/DataPacks\] served [0-9]+ datapack"             "$LOG"
# The carriers are where the c: convention-tag skeleton lives — 513 tag files that exist in NO other jar, and that
# every cross-mod recipe is written against. Assert the NUMBER: the line keeps printing when the count goes to zero.
CARRIERS=$(grep -aoE 'served [0-9]+ datapack\(s\).*— [0-9]+ loader carrier' "$LOG" | grep -oE '[0-9]+ loader' | grep -oE '[0-9]+' | head -1)
assert_eq "loader carriers served" 2 "${CARRIERS:-none}"
check "carriers sit below the mods"   "forbric/carrier/1-forge-runtime-interop, forbric/carrier/2-neoforge-runtime" "$LOG"
check "registry alias parity restored" "Forbric/Aliases\] gave .* alias-resolving lookup"      "$LOG"
check "NeoForge registration order"    "fired RegisterEvent in NeoForge.s registration order"  "$LOG"
# A mod whose items name their own data components: with RegisterEvent in field order the item registry is filled
# 57 registries too early, DeferredHolder.value() throws, and the mod loses every item it had not reached yet.
check_absent "no unbound data component" "Trying to access unbound value"                      "$LOG"
check_absent "no RegisterEvent listener failed" "RegisterEvent listener failed"                "$LOG"
check_absent "no tag lost to a dangling id"     "Couldn.t load tag"                            "$LOG"

step "one NightConfig, and it is the working one (must PASS)"
# The MinecraftForge carrier bundles NightConfig 3.7.4 at the UNSHADED package name, where
# StampedConfig.valueMap() is a stub that throws. Child-first handed the game that copy and shadowed the working
# 3.8.x on the parent classpath, so every config read that descends a dotted path into a nested table died.
# zfastnoise is the visible victim — it reads config in its mixin PLUGIN's constructor, and Mixin responds to a
# plugin it cannot build by applying that config's mixins with no opinion, which then killed chunk generation.
# The positive assertion is the load-bearing one: the plugin only gets guarded once it has been CONSTRUCTED.
check "a config-reading mixin plugin constructs" "guarded .*FastNoiseMixinPlugin"             "$LOG"
check_absent "no NightConfig version split"      "StampedConfig does not support valueMap"      "$LOG"

step "every failure that has cost a world load here (must be ABSENT)"
# Each of these is a bug that actually happened on this pack; the wording is the log's, not ours to change lightly.
check_absent "JEI found its plugins"        "plugins must not be empty"                        "$LOG"
check_absent "no plugin name unloadable"    "Failed to load: [a-z0-9_]+/"                      "$LOG"
check_absent "no null pack reached the repo" "streamSelfAndChildren.* because .pack. is null"  "$LOG"
check_absent "no pack metadata read failed" "Failed to read pack .* metadata"                  "$LOG"
check_absent "no entity missing attributes" "has no attributes"                                "$LOG"
check_absent "render-layer latch is set"    "Render layers can only be set"                    "$LOG"
check_absent "no skipped-element leak"      "StubException"                                    "$LOG"
check_absent "no duplicate registry key"    "Duplicate key ResourceKey"                        "$LOG"
# NOT a bare "Unknown registry key": a save carries chunk sections referencing content the CURRENT mod set no
# longer has, and vanilla reports those as "Recoverable errors when loading section" — 1214 of them here,
# every one a terralith biome from before that mod was dropped. That is a property of the save. The shape
# that matters is a datapack ELEMENT failing to parse, which is what an unregistered modifier type produced.
check_absent "no datapack element unparseable" "Failed to parse .* from pack"                 "$LOG"
# A SimpleJsonResourceReloadListener names EVERY element it rejects, so assert the SET rather than the absence:
# three are expected, and a fourth must turn this gate red. All three are mods (or a carrier) shipping data for a
# contract that moved, and a genuine NeoForge 26.2 instance rejects each of them the same way:
#   *:global_loot_modifiers  — the legacy Forge list file (replace/entries). NeoForge's LootModifierManager runs
#     IGlobalLootModifier.DIRECT_CODEC over every file in loot_modifiers/ and has no list-file concept; its own
#     GlobalLootModifierProvider stopped writing one. The MODIFIERS are fine — usefulfood:glow_squid and
#     earthmobsmod:desert_in_ruby are not named here, and this loader names everything that fails.
#   earthmobsmod:entities/tropical_slime — MC 26.2 split minecraft:type_specific into type_specific/lightning,
#     /fishing_hook, /player, /cube_mob, /raider. The mod still ships the pre-split shape.
UNPARSEABLE=$(grep -aoE "Couldn.t parse data file '[^']*'" "$LOG" | sed -E "s/.*'(.*)'/\1/" | sort -u | paste -sd, -)
assert_eq "only the known-vestigial data files fail to parse" \
  "earthmobsmod:entities/tropical_slime,forge:global_loot_modifiers,neoforge:global_loot_modifiers" "$UNPARSEABLE"
check_absent "join negotiation succeeded"   "Network Protocol Error"                           "$LOG"
# Same treatment for "was loaded too early": pin the SET, because two are upstream behaviour and a third would be
# ours. Mixin's select() runs selectConfigs -> Extensions.select -> prepareConfigs, so EVERY guest config plugin
# is constructed before ANY config is prepared. A game class that a plugin's static initialiser loads therefore
# misses every mixin — on any Mixin platform, genuine Fabric and NeoForge included. Measured here with
# -Dforbric.traceClassDefine=net.minecraft.world.level.BlockGetter, which named the chain Mixin will not:
#   PluginHandle.<init> -> IrisMixinPlugin.<clinit> -> IrisPlatformHelpers.<clinit> -> ServiceLoader.findFirst()
#   -> defining IrisForgeHelpers -> loadClass(BlockGetter).
# Cost is lithium's raycast optimisation and a duck interface nothing in this pack calls. The kernel could defer
# plugin construction behind a lazy proxy and beat upstream here — deliberately not done: no real loader does
# that, and fidelity to the genuine contract is worth more than two recovered mixins.
TOO_EARLY=$(grep -aoE 'Critical problem: [^ ]+ from mod' "$LOG" | sed -E 's/Critical problem: (.*) from mod/\1/' | sort -u | paste -sd, -)
assert_eq "only the known plugin-clinit casualties load too early" \
  "fabric-block-getter-api-v2.mixins.json:BlockGetterMixin,lithium.mixins.json:world.raycast.BlockGetterMixin" \
  "$TOO_EARLY"
check_absent "no registry load failure"     "Failed to load registries due to errors"          "$LOG"
check_absent "no crash report"              "Preparing crash report"                           "$LOG"
# Raw-ASM bytecode patching, the kind CustomSkinLoader does instead of Mixin, fails SILENTLY at WARN and takes a
# whole feature with it. Two ways it has happened here, both fixed and both invisible without this line: the
# protocol version reading 0 so it picked a pre-1.20.2 patch variant (see run/game-metadata-jar.sh), and Shoulder
# Surfing's @Redirect DELETING the call site the cape patch scans for (see MergedBaseMixinCompat). Any new one is
# a mod losing a feature, so it must be a decision rather than a line nobody reads.
check_absent "no bytecode patch failed"     "did not modify any bytecode"                      "$LOG"

step "the pack is honestly provisioned (must PASS)"
# A genuine NeoForge refuses to launch when a mod's versionRange on neoforge is not satisfied. The kernel parses
# those ranges and used to evaluate none of them, so an under-provisioned mod loaded and failed later somewhere
# that named neither it nor the version: JEI 30.14.0.87 wants [26.2.0.16-beta,), the carrier WAS 26.2.0.7-beta,
# and what that actually looked like was NeoForgeGuiPlugin dying on NoClassDefFoundError for TooltipFlagExtension
# — an interface .7 genuinely does not have, because those methods are inlined on TooltipFlag there instead.
#
# That audit is why the carrier is now 26.2.0.38-beta (see forbric-loader/run/assemble-neoforge-runtime.sh for
# why .38 and not the newest .64): the bump is what closed the only entry this set ever had. So the expected
# value is now "none" — and keeping the assertion, rather than deleting it with the finding, is the point. It
# fails in both directions: a mod whose range outruns the carrier turns it red, and so does silently sliding
# the carrier back. Anything appearing here must be a decision, not a surprise.
check "ecosystem versions reported"   "Forbric/Versions\] this instance provides"                "$LOG"
UNDERPROVISIONED=$(grep -aoE 'Forbric/Versions\] [a-z0-9_]+ requires' "$LOG" \
  | sed -E 's/.*\] ([a-z0-9_]+) requires/\1/' | sort -u | paste -sd, -)
assert_eq "no under-provisioned mod" "none" "${UNDERPROVISIONED:-none}"

step "every mod's own assets are reachable once the reload has run (must PASS)"
# EnhancedVisuals emits 21 `Could not find any resources for 'damaged'!` during startup, and they look exactly
# like the kernel failing to serve a Forge-family mod's assets. They are not: the mod's EVClient probes its
# textures EAGERLY, before the resource reload that selects the ecosystem packs (measured: the warnings land at
# log line 1654-1674, `Reloading ResourceManager:` — which lists forbric/EnhancedVisuals_… — starts at 1706), and
# the reload then loads all 21 silently. Every one of those 20 names ships in the mod's own jar at exactly the
# path it asks for, `assets/enhancedvisuals/visuals/<category>/<name>/<name><n>.png`.
#
# So the assertion is not "no such warning" — that would pin startup noise and go red the day a mod probes early.
# It is "none of them SURVIVES the reload", which is the property that actually matters and the one that breaks
# if ecosystem asset packs ever stop being served or lose a namespace.
RELOAD_LINE=$(grep -an 'Reloading ResourceManager' "$GAMELOG" 2>/dev/null | head -1 | cut -d: -f1)
if [ -n "$RELOAD_LINE" ]; then
  UNRESOLVED=$(grep -an "Could not find any resources for" "$GAMELOG" 2>/dev/null \
    | cut -d: -f1 | awk -v r="$RELOAD_LINE" '$1 > r' | wc -l | tr -d ' ')
  assert_eq "no mod resource still unresolved after the reload" "0" "$UNRESOLVED"
else
  echo "[kernel] FAIL never saw a resource reload"; FAIL=1
fi

step "the lost BlockGetter interface injection still has no consumer (must PASS)"
# fabric-block-getter-api-v2's BlockGetterMixin is one of the two mixins lost to a plugin <clinit> loading its
# target early (pinned in the set above). It is an EMPTY interface-injection mixin: its whole job is to make
# net.minecraft.world.level.BlockGetter implement FabricBlockGetter (getBlockEntityRenderData, hasBiomes,
# getBiomeFabric). Losing it costs nothing while nothing casts to that interface — and today nothing does. The
# only jar in this pack that implements it is Fabric Sodium's LevelSliceMixin, and Fabric Sodium LOSES
# arbitration: the pack runs sodium-neoforge, whose LevelSlice keeps its own blockEntityRenderDataArrays and
# never names FabricBlockGetter at all.
#
# That is a coincidence of this pack, not a property of the kernel, and it would stop holding silently — flip one
# line in forbric-mods.txt to `sodium = fabric`, or add a mod that uses the render-data API, and the cast starts
# throwing with nothing in the log pointing back here. Both triggers are asserted.
BG_CONSUMERS=$(python3 - "$RUNDIR/mods" <<'PYEOF'
import os, sys, zipfile, io
needle = b"net/fabricmc/fabric/api/blockgetter/v2/FabricBlockGetter"
found = set()
def walk(data, top, depth=0):
    try: z = zipfile.ZipFile(io.BytesIO(data))
    except Exception: return
    for n in z.namelist():
        if n.endswith(".class"):
            try:
                if needle in z.read(n): found.add(top)
            except Exception: pass
        elif n.endswith(".jar") and depth < 2:
            try: walk(z.read(n), top, depth + 1)
            except Exception: pass
d = sys.argv[1]
for f in sorted(os.listdir(d)) if os.path.isdir(d) else []:
    if f.endswith(".jar"):
        with open(os.path.join(d, f), "rb") as fh: walk(fh.read(), f)
# fabric-api ships the interface itself; only third parties count as consumers.
print(",".join(sorted(j for j in found if not j.startswith("fabric-api-"))) or "none")
PYEOF
)
assert_eq "only the known-inert consumer of FabricBlockGetter" "[钠] sodium-fabric-0.9.1+mc26.2.jar" "$BG_CONSUMERS"
SODIUM_SIDE=$(grep -aoE '^# sodium = [a-z]+' "$RUNDIR/forbric-mods.txt" 2>/dev/null | awk '{print $4}')
assert_eq "and it is still the side that lost arbitration" "neoforge" "${SODIUM_SIDE:-unknown}"

step "nothing leaked past main"
# Vanilla logs this ~15s after main returns when a non-daemon thread is still alive — a leaked mod thread.
check_absent "no thread leaked past main"   "Client shutdown from post-main"                   "$LOG"

step "M9 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M9 CLIENT GATE GREEN — tri-ecosystem client entered a world and left it cleanly"
else
  echo "[kernel] ❌ M9 CLIENT GATE RED — see $LOG"
fi
exit "$FAIL"
