#!/usr/bin/env bash
# RED controls: M21_EXTRA_JVM='-Dforbric.forgeSpawnPlacements=off' / '-Dforbric.forgeCreativeTabs=off' /
#               '-Dforbric.forgeConditions=off' (I8: the dual-dialect element fails MinecraftForge's strict codec) /
#               '-Dforbric.forgeScanData=off' — verified RED: exactly 5 checks fail and nothing else ("the index
#               carries the mod's own annotation", "an enum member has FML's own EnumData shape, not a String",
#               "a plain member is still a plain value", "and the index is not one file's worth by accident",
#               "the kernel says how much it indexed").
# Client counterpart: M26_EXTRA_JVM='-Dforbric.forgeClientInit=off'. Existing setup controls remain ordinary RED.
# M21 gate — the mod-loading SETUP lifecycle reaches BOTH Forge families, and what a listener defers actually runs.
#
# WHY THIS EXISTS. The kernel posted FMLCommonSetupEvent, FMLDedicatedServerSetupEvent/FMLClientSetupEvent, the two
# IMC phases and FMLLoadCompleteEvent to NeoForge mods ONLY. Every traditional-MinecraftForge mod that does its real
# work from setup therefore did nothing at all — and did it silently: the mod constructed, ModList.isLoaded said
# true, its blocks and items registered, and no error appeared anywhere. Biomes O' Plenty is the case that found it.
# Its biome/worldgen wiring hangs off
#
#     FMLCommonSetupEvent.getBus(ctx.getModBusGroup()).addListener(this::commonSetup)
#       -> commonSetup -> event.enqueueWork(...) -> BiomesOPlenty.init() -> ModBiomes.setupTerraBlender()
#         -> terrablender.api.Regions.register(...)
#
# so on a real player's install BOP registered 498 blocks and 503 items and ZERO biomes, and the world generated
# vanilla. Nothing in the log said so.
#
# TWO LINES PER PHASE, PER FAMILY, ON PURPOSE. "DELIVERED" is printed by the listener; "DEFERRED work ran" is
# printed by what the listener handed to enqueueWork. They fail independently — posting the event without draining
# ModLoadingStage's DeferredWorkQueue prints the first and never the second, which is exactly the half-fix that
# reads as correct in a log that only asserts delivery. BOP's registration is in the deferred half.
#
# AND BOTH FAMILIES, FROM ONE BOOT. Asserting only the traditional-Forge lines cannot tell "both families now get
# the phase" apart from "delivery moved from one family to the other" — so the NeoForge canary subscribes to the
# same six phases through its own bus shape and the gate asserts both sets.
# GATE-PARALLEL: rundirs=server-forgesetup mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m21-forgesetup.log"
RUNDIR="$KERNEL/run/server-forgesetup"
FORGE="$RUN_OLD/forge-runtime/forbriclive.jar"
NEO="$RUN_OLD/neoforge-runtime/forbricneolive.jar"
mkdir -p "$BUILD"

step "stage one canary per Forge family"
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
miss=0
for jar in "$FORGE" "$NEO"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] MISSING: $jar"; miss=1; fi
done
[ "$miss" -eq 0 ] || { echo "[kernel] FAIL a canary jar is missing — run forbric-loader/run/build-testmods.sh"; exit 1; }
seed_server_properties "$RUNDIR"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

# The conditions canary: a world datapack whose elements MinecraftForge's evaluator has to judge, each with a
# console command below that asks the live registry whether it is there. Written here rather than shipped in a mod
# jar so the gate needs nothing rebuilt; a world pack is loaded by vanilla's own repository, from no ecosystem.
#   kept / dropped        forge:true / forge:false, one predicate (reloadable registry) and one configured feature
#                         (datapack registry) each -- the two loader bodies the funnel is asked from
#   variant               a forge:conditional array, the other half of ForgeHooks.readConditional: the first variant's
#                         condition is false and it would answer "Test failed"; the second holds and answers "passed"
#   dual_neo              neoforge:conditions [neoforge:always] beside forge:condition forge:false: NeoForge judges its
#                         own conditions, so its verdict (keep) stands and MinecraftForge is not asked
#   dual_forge            neoforge:conditions naming a type NeoForge does not have, beside forge:condition forge:false:
#                         NeoForge cannot judge it, so MinecraftForge decides (drop)
PACK="$RUNDIR/world/datapacks/forbricm21"
mkdir -p "$PACK/data/forbricm21/predicate" "$PACK/data/forbricm21/worldgen/configured_feature"
printf '%s\n' '{"pack":{"description":"Forbric gate-m21 condition canary","min_format":107,"max_format":107}}' > "$PACK/pack.mcmeta"
TRUE_PREDICATE='"condition":"minecraft:all_of","terms":[]'
printf '{"forge:condition":{"type":"forge:true"},%s}\n'  "$TRUE_PREDICATE" > "$PACK/data/forbricm21/predicate/kept.json"
printf '{"forge:condition":{"type":"forge:false"},%s}\n' "$TRUE_PREDICATE" > "$PACK/data/forbricm21/predicate/dropped.json"
printf '{"forge:conditional":[{"forge:condition":{"type":"forge:false"},"condition":"minecraft:inverted","term":{%s}},{"forge:condition":{"type":"forge:true"},%s}]}\n' \
  "$TRUE_PREDICATE" "$TRUE_PREDICATE" > "$PACK/data/forbricm21/predicate/variant.json"
printf '{"neoforge:conditions":[{"type":"neoforge:always"}],"forge:condition":{"type":"forge:false"},%s}\n' \
  "$TRUE_PREDICATE" > "$PACK/data/forbricm21/predicate/dual_neo.json"
printf '{"neoforge:conditions":[{"type":"forbricm21:no_such_condition"}],"forge:condition":{"type":"forge:false"},%s}\n' \
  "$TRUE_PREDICATE" > "$PACK/data/forbricm21/predicate/dual_forge.json"
printf '{"forge:condition":{"type":"forge:true"},"type":"minecraft:no_op","config":{}}\n'  > "$PACK/data/forbricm21/worldgen/configured_feature/kept.json"
printf '{"forge:condition":{"type":"forge:false"},"type":"minecraft:no_op","config":{}}\n' > "$PACK/data/forbricm21/worldgen/configured_feature/dropped.json"
# Each probe is announced first, so what the log says after "[Server] M21PROBE <name>" is that command's own answer.
M21_PROBES=(
  "predicate kept|execute if predicate forbricm21:kept"
  "predicate dropped|execute if predicate forbricm21:dropped"
  "predicate variant|execute if predicate forbricm21:variant"
  "predicate dual_neo|execute if predicate forbricm21:dual_neo"
  "predicate dual_forge|execute if predicate forbricm21:dual_forge"
  "feature kept|place feature forbricm21:kept 0 100 0"
  "feature dropped|place feature forbricm21:dropped 0 100 0"
)
probe_commands() {
  local probe
  # The feature probes place at 0,100,0: force that chunk loaded first, so a feature that is there is PLACED rather
  # than refused for an unloaded position.
  echo "forceload add 0 0"
  sleep 3
  for probe in "${M21_PROBES[@]}"; do printf 'say M21PROBE %s\n%s\n' "${probe%%|*}" "${probe#*|}"; done
}
# probe_answer <name> — what the log says between that probe's announcement and the next one (or the stop).
probe_answer() {
  awk -v marker="[Server] M21PROBE $1" '
    inside && (index($0, "[Server] M21PROBE ") || index($0, "Stopping server")) { exit }
    inside { print }
    { n = length($0); m = length(marker); if (n >= m && substr($0, n - m + 1) == marker) inside = 1 }' "$LOG"
}
# check_probe <what> <name> <regex> — PASS when that probe's answer matches.
check_probe() {
  local answer
  answer=$(probe_answer "$2")
  if [ -n "$answer" ] && printf '%s\n' "$answer" | grep -qE "$3"; then
    printf '[kernel] PASS %s (%s)\n' "$1" "$(printf '%s\n' "$answer" | grep -E "$3" | head -1 | sed 's/^.*INFO\]: //')"
  else printf '[kernel] FAIL %s (answered: %s)\n' "$1" "$(printf '%s' "${answer:-nothing}" | tr '\n' ' ' | cut -c1-300)"; FAIL=1; fi
}

step "boot both Forge families in one instance"
: > "$LOG"
( sleep 30; probe_commands; sleep 3; echo stop ) | FORBRIC_JVM="${FORBRIC_JVM:-} ${M21_EXTRA_JVM:-}" RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 130

step "traditional MinecraftForge receives every server-side setup phase (must PASS — this is the gap)"
for p in "common setup" "dedicated server setup" "IMC enqueue" "IMC process" "load complete"; do
  check "Forge: $p delivered"     "\[ForbricLive/SETUP\] $p DELIVERED to a traditional-Forge mod" "$LOG"
  check "Forge: $p deferred ran"  "\[ForbricLive/SETUP\] $p DEFERRED work ran"                    "$LOG"
done

step "NeoForge still receives every one of them (must PASS — the control for 'delivery just moved')"
for p in "common setup" "dedicated server setup" "IMC enqueue" "IMC process" "load complete"; do
  check "Neo: $p delivered"     "\[ForbricNeoLive/SETUP\] $p DELIVERED to a NeoForge mod" "$LOG"
  check "Neo: $p deferred ran"  "\[ForbricNeoLive/SETUP\] $p DEFERRED work ran"           "$LOG"
done

step "a MinecraftForge mod finds its OWN container during its constructor (must PASS)"
# The libraryferret/awesomedungeonocean shape: a constructor reaches a class initializer that does
# ModList.getModContainerById(self).orElseThrow() and takes the bus off it. The kernel used to publish the
# MinecraftForge containers AFTER the construction loop, so this answered "Mod with ID ... not found" — and a
# class initializer is a one-shot, so that mod's registries never existed and the save would not open.
check "own container resolvable from its own ctor" \
  "\[ForbricLive/SELF\] own container during ctor: present=true fmlContainer=true busGroup=true" "$LOG"
check "and it is the active namespace during that ctor" \
  "\[ForbricLive/SELF\].*activeNamespace=forbriclive" "$LOG"

step "each phase drains its OWN queue, in order (must PASS)"
# A single drain at the very end would satisfy every check above while giving a mod its deferred work AFTER the
# phases that are supposed to see the result of it. Assert the interleaving: common setup's deferred work has to
# land before load complete is even delivered.
DEFERRED_LINE=$(grep -nE '\[ForbricLive/SETUP\] common setup DEFERRED work ran' "$LOG" | head -1 | cut -d: -f1)
COMPLETE_LINE=$(grep -nE '\[ForbricLive/SETUP\] load complete DELIVERED' "$LOG" | head -1 | cut -d: -f1)
if [ -n "$DEFERRED_LINE" ] && [ -n "$COMPLETE_LINE" ] && [ "$DEFERRED_LINE" -lt "$COMPLETE_LINE" ]; then
  printf '[kernel] PASS common setup drained before load complete was posted (line %s < %s)\n' "$DEFERRED_LINE" "$COMPLETE_LINE"
else
  printf '[kernel] FAIL common setup must drain before load complete (deferred=%s complete=%s)\n' "${DEFERRED_LINE:-none}" "${COMPLETE_LINE:-none}"; FAIL=1
fi

step "the kernel says what it did, and says it about the family it did it to (must PASS)"
# NOT `check` alone: the sentence would still print with a zero in it, and zero mods posted-to is the regression.
FIRED=$(grep -oE 'posted FML common setup to [0-9]+ traditional-Forge mod\(s\)' "$LOG" | grep -oE '[0-9]+' | head -1)
if [ -n "${FIRED:-}" ] && [ "$FIRED" -ge 1 ]; then
  printf '[kernel] PASS kernel posted common setup to %s traditional-Forge mod(s)\n' "$FIRED"
else
  printf '[kernel] FAIL kernel posted common setup to no traditional-Forge mod (got %s)\n' "${FIRED:-none}"; FAIL=1
fi
check "and to the NeoForge mods too" "posted FML common setup to [1-9][0-9]* NeoForge mod\(s\)" "$LOG"
# The EARLIEST mod-bus phase, and until 2026-09-17 no MinecraftForge mod ever received it: the kernel named
# NeoForge's event class inline, so there was no second half for anyone to notice was missing. Asserted with the
# same [1-9] shape, because "posted to 0" is exactly the regression.
check "and the earliest phase of all reaches them" \
  "posted FML construct to [1-9][0-9]* traditional-Forge mod\(s\)" "$LOG"
check "and reaches the NeoForge mods" "posted FML construct to [1-9][0-9]* NeoForge mod\(s\)" "$LOG"

step "the merge-lost GAME events reach a real MinecraftForge mod too (must PASS)"
# Setup phases are the mod-bus half. The GAME bus is the other half, and on the merged base almost all of it went
# to NeoForge: Commands carries 7 references to net.neoforged and 0 to net.minecraftforge, PlayerList 13 to 0.
# The cost is not a missing callback, it is a missing FEATURE with no log line -- a Forge mod's commands DO NOT
# EXIST, and the player typing one is told "Unknown command" while the mod loaded cleanly. The canary registers a
# real node rather than logging, so the node count proves it reached the LIVE dispatcher and not a copy.
check "a MinecraftForge mod's command reaches the live dispatcher" \
  "ForbricLive\] RegisterCommandsEvent RECEIVED - /forbriclive registered into the live dispatcher \([1-9][0-9]*" "$LOG"
# handleServerStarting is also the only caller of PermissionAPI.initializePermissionAPI, so its absence made
# every permission question any Forge mod asked NPE inside Forge's own API.
check_exactly "and ServerStartingEvent, which also initialises PermissionAPI, once" \
  "ForbricLive\] ServerStartingEvent RECEIVED" "$LOG" 1
# Once each, not at least once. A merged initServer and runServer that keep both families' edits call MinecraftForge's
# lifecycle hooks natively, and the kernel's forwards delivered each event a second time (and loaded every SERVER
# config twice, with two file watchers) while ">= 1" stayed green. RED control: delete the server-lifecycle rows from
# DeadEventAudit.BRIDGED and every line below counts 2.
check_exactly "ServerStartedEvent reaches it once" "ForbricLive\] ServerStartedEvent RECEIVED" "$LOG" 1
check_exactly "its per-world SERVER config loads once" "ForbricLive/CFG\] LOADING forbriclive-server\.toml" "$LOG" 1

# B9: a seeded MinecraftForge ModFile used to carry a null SecureJar, so getFilePath/findResource — which
# ShoulderSurfing-Forge and collective call on EVERY mod file from their own listeners — NPE'd inside Forge's own
# accessor. Assert every walked file answered both, not merely that the walk did not throw: a walk over zero
# files would also "not throw".
# A11: MinecraftForge lets a mod add a constant to certain vanilla enums by calling a factory on them, and in the
# shipped game that factory's whole body throws "Enum not extended" — their loader rewrites it while the class is
# defined, and the kernel replaces that loader. Mods call it from a static initialiser, which runs once and is
# erroneous forever after, so the mod dies rather than merely losing a constant. The count is asserted too: a
# factory that returned an EXISTING constant would also "not throw".
check "a MinecraftForge mod can add a constant to a vanilla enum" \
  "ForbricLive\] MobCategory.create gave us FORBRIC_CANARY, values went [0-9]+ -> [0-9]+" "$LOG"
check_absent "and the factory is no longer a stub that throws" \
  "ForbricLive\] MobCategory.create FAILED" "$LOG"

check "every seeded ModFile answers getFilePath and findResource" \
  "ForbricLive\] walked [1-9][0-9]* mod file\(s\), [1-9][0-9]* answered getFilePath and findResource" "$LOG"
check_absent "no failure walking ModList.getModFiles()" \
  "ForbricLive\] walking ModList.getModFiles\(\) FAILED" "$LOG"

step "a MinecraftForge mod finds its OWN members through ModList.getAllScanData() (must PASS)"
# The index a traditional-Forge mod's own extension system reads. It was an EMPTY ModFileScanData for the kernel's
# whole life — present so Forge's own injectCapabilities would not NPE on a null, holding nothing. Nothing said so:
# SuperMartijn642's Core Lib injects every @RegistryEntryAcceptor static field from it, found none, and Packed Up's
# menu type stayed null until the CLIENT died in Minecraft.<init> with "Container screen registered with null menu
# type!" — text naming neither the index nor the kernel.
check "the index carries the mod's own annotation" \
  "ForbricLive/SCAN\] found its own @LiveScanned on FIELD PROBE of forbric.live.ForbricLiveMod" "$LOG"
# And the SHAPE, separately: the first non-empty index handed Core Lib a bare String where it casts straight to
# EnumData, so the fix that filled the index turned one silent failure into a ClassCastException inside the mod.
# FML wraps an enum member in a game-side type, and the two ecosystems do not agree on which.
check "an enum member has FML's own EnumData shape, not a String" \
  'ForbricLive/SCAN\] enum member kind is net\.minecraftforge\.forgespi\.language\.ModFileScanData[$]EnumData with value SECOND' "$LOG"
check "a plain member is still a plain value" \
  "ForbricLive/SCAN\] string member note is probe" "$LOG"
check "and the index is not one file's worth by accident" \
  "ForbricLive/SCAN\] ModList.getAllScanData\(\): [1-9][0-9]* file\(s\), [1-9][0-9]* annotation\(s\)" "$LOG"
check "the kernel says how much it indexed" \
  "Forbric/Seed\] indexed [1-9][0-9]* annotation\(s\) across [1-9][0-9]* MinecraftForge jar\(s\)" "$LOG"

# B5: each Forge family ships an access transformer that widens the GAME for every mod of that family, and only
# mod jars' files were ever fed in. Where the merge kept one family's method body it kept that body's access too,
# so the other family's widening was gone — MenuScreens.register, which every MinecraftForge GUI mod calls during
# client setup, came out private. The carriers' files are now applied too.
check "the runtime carriers' access transformers are applied" \
  "Forbric/AT\] applying [0-9]+ Forge-family access-transformer directive\(s\) from [0-9]+ jar\(s\), [1-9][0-9]* of them from the runtime carriers" "$LOG"

step "nothing quietly broken by the extra posts (must be ABSENT)"
check_absent "no NoClassDefFound"        "NoClassDefFoundError"                          "$LOG"
check_absent "no phase failed to post"   "could not post traditional-Forge"              "$LOG"
check_absent "no deferred queue failure" "its deferred work did not run"                  "$LOG"
check "server still reached Done"        "Done \("                                        "$LOG"

step "a MinecraftForge mod can reach the carrier's NBT builder (H1: CompoundTag.builder())"
# RED before the H1 repair (recorded 2026-09-20): the canary printed
#   [ForbricLive/NBT] BlockPos.toCompoundTag() FAILED: java.lang.NoSuchMethodError: 'net.minecraftforge.common.util.INBTBuilder$Builder net.minecraft.nbt.CompoundTag.builder()'
check "BlockPos.toCompoundTag() links and builds" '\[ForbricLive/NBT\] BlockPos\.toCompoundTag\(\) = \{.*x:1.*y:2.*z:3.*\}' "$LOG"
check_absent "no NoSuchMethodError on CompoundTag.builder" 'NoSuchMethodError.*CompoundTag\.builder' "$LOG"

step "MinecraftForge's AddReloadListenerEvent is posted from the merged server reload (H2/H3)"
# RED controls: M21_EXTRA_JVM='-Dforbric.forgeReloadListeners=off' (no DELIVERED / count line, listener never ran);
#               M21_EXTRA_JVM='-Dforbric.forgeConditionContext=off' (context=EMPTY instead of live).
check "the Forge event was delivered"              '\[ForbricLive/RELOAD\] AddReloadListenerEvent DELIVERED' "$LOG"
check "the Forge-registered data loader ran"       '\[ForbricLive/RELOAD\] reload listener ran over [1-9][0-9]* file' "$LOG"
check "the kernel counted the bridged listeners"   'bridged [1-9][0-9]* MinecraftForge server reload listener' "$LOG"
check "the event hands out a live condition context" '\[ForbricLive/RELOAD\] context=live' "$LOG"
check_absent "no NoSuchMethodError on getConditionContext" 'NoSuchMethodError.*getConditionContext' "$LOG"

step "a recipe with a MinecraftForge ingredient type parses (H4: forge:intersection)"
# RED control: M21_EXTRA_JVM='-Dforbric.forgeIngredients=off' (present = false, no count line; the merged base then logs
#   "Couldn't parse data file 'forbriclive:forge_intersection': DataResult.Error['List is too short: 0, ...']").
check "the forge:intersection recipe is present"    '\[ForbricLive/RECIPE\] forbriclive:forge_intersection present = true' "$LOG"
check "the kernel counted the Forge ingredient type" 'MinecraftForge ingredient type forge:intersection decoded' "$LOG"
check_absent "no parse failure for the canary recipe" "Couldn't parse data file 'forbriclive:forge_intersection'" "$LOG"

step "MinecraftForge's resource conditions decide real data again, and only where MinecraftForge would (I8)"
# MinecraftForge judges forge:condition in its own ResourceManagerRegistryLoadTask.load (ConditionCodec.wrap) and
# SimpleJsonResourceReloadListener.scanDirectory (ForgeHooks.readConditional, which also reads a forge:conditional
# array). The merged bodies are NeoForge's and MinecraftForge's are uncalled orphans, so the kernel asks MinecraftForge
# where every conditional decode funnels (ConditionalOps -> KernelForgeConditions.alsoAskForge). The canary pack staged
# above asks the live registries whether each element survived; a "kept" check that cannot see a rejection proves
# nothing, so every element here is one whose absence or presence a command reports.
# The NeoForge canary also ships forbricneolive:dual_dialect, a biome_modifier whose forge:condition is of type
# neoforge:true: MinecraftForge does not know that type, so its leniency keeps the element instead of failing the whole
# registry load. No command can list biome modifiers, so its survival is held by the exact rejection count: the three
# false elements of the canary pack, and nothing else.
# RED with M21_EXTRA_JVM=-Dforbric.forgeConditionFunnel=off: nothing asks MinecraftForge -- the transform line, the
# funnel line and the leniency line are gone, the dropped predicate, the dropped feature and dual_forge are present, the
# variant does not parse ("Can't find element"), and nothing is rejected (count 0). dual_neo and the kept elements stay.
# RED with -Dforbric.forgeConditions=off: strict CODEC.parse error -> 'Failed to load registries due to errors' -> no Done.
# RED with -Dforbric.forgeConditionContext=off: the recipe context line is gone (and "context=live" above).
# KernelForgeConditionFunnelTest greps the funnel's real output with the funnel pattern.
check "the transform says MinecraftForge is asked at the funnel" "MergedBaseCompat\] forge:condition has an evaluator again" "$LOG"
check "MinecraftForge judges forge:condition at the conditional-decode funnel" "Forbric/Conditions\] MinecraftForge's forge:condition is judged again" "$LOG"
check "the MinecraftForge leniency fired on a real datapack element" "resource condition 'neoforge:true' is not in MinecraftForge's condition registry" "$LOG"
check_absent "registries loaded"                             "Failed to load registries due to errors" "$LOG"
check "the canary pack was loaded" "data pack file/forbricm21" "$LOG"
check_probe "a true forge:condition keeps a predicate"        "predicate kept"     'Test passed'
check_probe "a false forge:condition removes a predicate"     "predicate dropped"  "Can't find element 'forbricm21:dropped'"
check_probe "the first forge:conditional variant that holds is the file" "predicate variant" 'Test passed'
check_probe "a file NeoForge judges keeps NeoForge's verdict" "predicate dual_neo" 'Test passed'
check_probe "MinecraftForge decides a file NeoForge cannot judge" "predicate dual_forge" "Can't find element 'forbricm21:dual_forge'"
check_probe "a true forge:condition keeps a datapack-registry element" "feature kept" 'Placed "forbricm21:kept"'
check_probe "a false forge:condition removes a datapack-registry element" "feature dropped" 'There is no feature with type "forbricm21:dropped"'
check_exactly "exactly the canary pack's three false elements were rejected -- not the dual-dialect biome modifier" \
  "\[Forbric/Conditions\] forge:condition(al)? said no to a data file" "$LOG" 3
check "MinecraftForge's recipe conditions read this reload's tags" "MinecraftForge's recipe conditions read the tags this reload loads again" "$LOG"

# M21_REGISTRATION_ASSERTIONS_BEGIN — the Phase 1 A registration hooks; green since A8 landed the bridge inventory.
check "common registration canary subscribed" 'ForbricLive/REGISTRATION\] subscribed to Forge spawn and creative registration events' "$LOG"
check "common registration observations completed" 'ForbricLive/REGISTRATION\] common registration observations completed' "$LOG"
step "Forge registration callbacks changed the game-owned tables"
check "Forge spawn placement listener received" 'ForbricLive/REGISTRATION\] SpawnPlacementRegisterEvent RECEIVED' "$LOG"
check "zombie heightmap changed in SpawnPlacements" 'ForbricLive/REGISTRATION\] zombie heightmap=WORLD_SURFACE([[:space:]]|$)' "$LOG"
check "Forge creative listener received for building blocks" 'ForbricLive/REGISTRATION\] BuildCreativeModeTabContentsEvent RECEIVED: minecraft:building_blocks' "$LOG"
check "creative injection reached parent and search after server start" 'ForbricLive/REGISTRATION\] injection VISIBLE: true search=true phase=server started' "$LOG"
check "registration bridges report installed" 'all 2 REGISTRATION bridge\(s\) installed' "$LOG"
step "M21 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M21 FORGE-SETUP GATE GREEN — both families receive setup and Forge registration changes reach the game"
else
  echo "[kernel] ❌ M21 GATE RED — run $LOG"
fi
exit "$FAIL"
# M21_REGISTRATION_ASSERTIONS_END
