#!/usr/bin/env bash
# RED controls: the NeoForge canary's own, in the M21_NEO_CONTRACTS block (neoScanData, seededScanData, modDataPacks,
#               modFilePerJar). The MinecraftForge recipes that stood here (forgeConditions, forgeConditionFunnel,
#               forgeConditionContext, forgeSpawnPlacements, forgeCreativeTabs, forgeScanData, forgeReloadListeners,
#               forgeClientInit) are retired switches (D29) and went with the checks they turned red (Stage 4 C5).
# M21 gate — the mod-loading SETUP lifecycle reaches NeoForge mods, and what a listener defers actually runs.
#
# WHY THIS EXISTS (written when it covered both Forge families). The kernel posted FMLCommonSetupEvent,
# FMLDedicatedServerSetupEvent/FMLClientSetupEvent, the two
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
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). Until Stage 3 this gate staged one canary per Forge
# family. Forbric now refuses a MinecraftForge-only jar and a dedicated server stops on the refusal, so the
# MinecraftForge canary (forbriclive) is no longer staged and every check that read its lines went then.
#
# STAGE 4 OF DROPPING MinecraftForge (what changed and why). The game base is NeoForge's own patched game and the
# MinecraftForge carrier is off the stage. What Stage 3 kept "until Stage 4" went with it, each because nothing prints
# its line any more (D34): the kernel's index of the carrier's annotations, the bridging of its server reload listeners,
# the registration bridges' inventory, the "could not post traditional-Forge" absence, and the whole MinecraftForge
# resource-condition funnel (KernelForgeConditions: the transform's markers, the leniency lines, the rejection count, the
# recipe-condition context). The world-datapack probes stay, re-derived (D49): a forge:condition / forge:conditional is
# MinecraftForge's dialect, which native NeoForge 26.2.0.93 does not judge, so each probe now expects native NeoForge's
# own answer -- measured on native NeoForge 26.2.0.93's dedicated server with this exact pack and these probes
# (native-controls' NeoForge image; 13 of 13 answers as below, 2026-11-09, stage4-c5-g-a.md). The 2026-11-02
# measurement's log was lost (D67). The access-transformer line now says it is the NeoForge carrier's.
# GATE-PARALLEL: rundirs=server-forgesetup mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m21-forgesetup.log"
RUNDIR="$KERNEL/run/server-forgesetup"
refuse_shared_rundir "$RUNDIR"
NEO="$NEOLIVE"
mkdir -p "$BUILD"

step "stage the NeoForge canary"
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
miss=0
for jar in "$NEO"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] MISSING: $jar"; miss=1; fi
done
[ "$miss" -eq 0 ] || { echo "[kernel] FAIL a canary jar is missing — run forbric-loader/run/build-testmods.sh"; exit 1; }
seed_server_properties "$RUNDIR"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

# The conditions canary: a world datapack written in MinecraftForge's condition dialect (and one NeoForge-dialect pair),
# each element with a console command below that asks the live registry whether it is there. Written here rather than
# shipped in a mod jar so the gate needs nothing rebuilt; a world pack is loaded by vanilla's own repository, from no
# ecosystem. Native NeoForge 26.2.0.93 judges only neoforge:conditions, so (measured there, see the header):
#   kept / dropped        forge:true / forge:false, one predicate (reloadable registry) and one configured feature
#                         (datapack registry) each: both kept, the forge: key is an unknown field
#   variant               a forge:conditional array: not a predicate at all, so the file does not parse and is skipped
#   dual_neo              neoforge:conditions [neoforge:always] beside forge:condition forge:false: kept (NeoForge's own)
#   dual_forge            neoforge:conditions naming a type NeoForge does not have, beside forge:condition forge:false:
#                         native NeoForge fails to parse the file ("Unknown registry key ... neoforge:condition_codecs")
#                         and skips it
#   neo_owned             forge:condition naming NeoForge's own neoforge:always: kept
#   unowned               a forge:condition type nobody registers: kept (data file and datapack-registry element alike);
#                         its forge:conditional twin is skipped like variant
#   recipes               shapeless recipes guarded by forge:not(forge:tag_empty ...) over a full and an empty tag: both
#                         present. No command lists recipes without a player, so a crafter crafts each one into the
#                         chest below it and the probe asks the chest -- the recipe manager's own answer.
PACK="$RUNDIR/world/datapacks/forbricm21"
mkdir -p "$PACK/data/forbricm21/predicate" "$PACK/data/forbricm21/worldgen/configured_feature" "$PACK/data/forbricm21/recipe" \
  "$PACK/data/forbricm21/tags/item"
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
printf '{"forge:condition":{"type":"neoforge:always"},%s}\n' "$TRUE_PREDICATE" > "$PACK/data/forbricm21/predicate/neo_owned.json"
printf '{"forge:condition":{"type":"forbricm21:registered_by_nobody"},%s}\n' "$TRUE_PREDICATE" > "$PACK/data/forbricm21/predicate/unowned.json"
printf '{"forge:conditional":[{"forge:condition":{"type":"forbricm21:registered_by_nobody"},"condition":"minecraft:inverted","term":{%s}},{%s}]}\n' \
  "$TRUE_PREDICATE" "$TRUE_PREDICATE" > "$PACK/data/forbricm21/predicate/variant_unowned.json"
printf '{"forge:condition":{"type":"forge:true"},"type":"minecraft:no_op","config":{}}\n'  > "$PACK/data/forbricm21/worldgen/configured_feature/kept.json"
printf '{"forge:condition":{"type":"forge:false"},"type":"minecraft:no_op","config":{}}\n' > "$PACK/data/forbricm21/worldgen/configured_feature/dropped.json"
printf '{"forge:condition":{"type":"forbricm21:registered_by_nobody"},"type":"minecraft:no_op","config":{}}\n' \
  > "$PACK/data/forbricm21/worldgen/configured_feature/unowned.json"
printf '{"values":["minecraft:stone"]}\n' > "$PACK/data/forbricm21/tags/item/full.json"
printf '{"values":[]}\n'                  > "$PACK/data/forbricm21/tags/item/empty.json"
# A dead bush and a fern alone craft nothing in vanilla, so whatever lands in a chest came from these two recipes.
printf '{"forge:condition":{"type":"forge:not","value":{"type":"forge:tag_empty","tag":"forbricm21:full"}},"type":"minecraft:crafting_shapeless","ingredients":["minecraft:dead_bush"],"result":{"id":"minecraft:diamond"}}\n' \
  > "$PACK/data/forbricm21/recipe/full_tag.json"
printf '{"forge:condition":{"type":"forge:not","value":{"type":"forge:tag_empty","tag":"forbricm21:empty"}},"type":"minecraft:crafting_shapeless","ingredients":["minecraft:fern"],"result":{"id":"minecraft:emerald"}}\n' \
  > "$PACK/data/forbricm21/recipe/empty_tag.json"
# Each probe is announced first, so what the log says after "[Server] M21PROBE <name>" is that command's own answer.
M21_PROBES=(
  "predicate kept|execute if predicate forbricm21:kept"
  "predicate dropped|execute if predicate forbricm21:dropped"
  "predicate variant|execute if predicate forbricm21:variant"
  "predicate dual_neo|execute if predicate forbricm21:dual_neo"
  "predicate dual_forge|execute if predicate forbricm21:dual_forge"
  "predicate neo_owned|execute if predicate forbricm21:neo_owned"
  "predicate unowned|execute if predicate forbricm21:unowned"
  "predicate variant_unowned|execute if predicate forbricm21:variant_unowned"
  "feature kept|place feature forbricm21:kept 0 100 0"
  "feature dropped|place feature forbricm21:dropped 0 100 0"
  "feature unowned|place feature forbricm21:unowned 0 100 0"
  "recipe full_tag|execute if items block 4 99 4 container.* minecraft:diamond"
  "recipe empty_tag|execute if items block 8 99 8 container.* minecraft:emerald"
)
# craft_once <x> <z> <ingredient> — a crafter at y=100 facing down into a chest, the ingredient in its first slot, then
# powered from above: it crafts once, through the recipe manager, into the chest the recipe probe asks.
craft_once() {
  printf 'setblock %s 99 %s minecraft:chest\nsetblock %s 100 %s minecraft:crafter[orientation=down_north]\n' "$1" "$2" "$1" "$2"
  printf 'item replace block %s 100 %s container.0 with %s\nsetblock %s 101 %s minecraft:redstone_block\n' "$1" "$2" "$3" "$1" "$2"
}
probe_commands() {
  local probe
  # The feature probes place at 0,100,0 and the crafters stand in the same chunk: force it loaded first, so a feature
  # that is there is PLACED rather than refused for an unloaded position, and the crafters tick.
  echo "forceload add 0 0"
  sleep 3
  craft_once 4 4 minecraft:dead_bush
  craft_once 8 8 minecraft:fern
  sleep 2
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

step "boot it"
: > "$LOG"
( server_ready "$LOG" 30 && { probe_commands; sleep 3; echo stop; } ) | FORBRIC_JVM="${FORBRIC_JVM:-} ${M21_EXTRA_JVM:-}" RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 130

step "NeoForge receives every server-side setup phase, and what it deferred ran (must PASS)"
for p in "common setup" "dedicated server setup" "IMC enqueue" "IMC process" "load complete"; do
  check "Neo: $p delivered"     "\[ForbricNeoLive/SETUP\] $p DELIVERED to a NeoForge mod" "$LOG"
  check "Neo: $p deferred ran"  "\[ForbricNeoLive/SETUP\] $p DEFERRED work ran"           "$LOG"
done

step "each phase drains its OWN queue, in order (must PASS)"
# A single drain at the very end would satisfy every check above while giving a mod its deferred work AFTER the
# phases that are supposed to see the result of it. Assert the interleaving: common setup's deferred work has to
# land before load complete is even delivered.
# Read off the NeoForge canary since Stage 3 (it was the MinecraftForge canary's lines).
DEFERRED_LINE=$(grep -nE '\[ForbricNeoLive/SETUP\] common setup DEFERRED work ran' "$LOG" | head -1 | cut -d: -f1)
COMPLETE_LINE=$(grep -nE '\[ForbricNeoLive/SETUP\] load complete DELIVERED' "$LOG" | head -1 | cut -d: -f1)
if [ -n "$DEFERRED_LINE" ] && [ -n "$COMPLETE_LINE" ] && [ "$DEFERRED_LINE" -lt "$COMPLETE_LINE" ]; then
  printf '[kernel] PASS common setup drained before load complete was posted (line %s < %s)\n' "$DEFERRED_LINE" "$COMPLETE_LINE"
else
  printf '[kernel] FAIL common setup must drain before load complete (deferred=%s complete=%s)\n' "${DEFERRED_LINE:-none}" "${COMPLETE_LINE:-none}"; FAIL=1
fi

step "the kernel says what it did, and says it about the family it did it to (must PASS)"
# [1-9], not just the sentence: it would still print with a zero in it, and zero mods posted-to is the regression.
check "the kernel posted common setup to the NeoForge mods" "posted FML common setup to [1-9][0-9]* NeoForge mod\(s\)" "$LOG"
check "and the earliest phase, construct, reaches them" "posted FML construct to [1-9][0-9]* NeoForge mod\(s\)" "$LOG"

# B5: NeoForge's carrier ships an access transformer that widens the GAME for every NeoForge mod, and only mod jars'
# files were ever fed in, so members NeoForge mods rely on (MenuScreens.register for every GUI mod) stayed private. The
# carrier's file is applied too, and its directives must be among those counted.
check "the NeoForge carrier's access transformer is applied" \
  "Forbric/AT\] applying [0-9]+ NeoForge access-transformer directive\(s\) from [0-9]+ jar\(s\), [1-9][0-9]* of them from the NeoForge carrier" "$LOG"

step "nothing quietly broken by the extra posts (must be ABSENT)"
check_absent "no NoClassDefFound"        "NoClassDefFoundError"                          "$LOG"
check_absent "no deferred queue failure" "its deferred work did not run"                  "$LOG"
# The NeoForge side of the absence that went with the MinecraftForge carrier ("could not post traditional-Forge"): a
# post that throws after the canary heard it passes every DELIVERED check above, and KernelLifecycle says so only here.
check_absent "no phase failed to post"  "Forbric/Lifecycle\] could not post (FML|NeoForge)" "$LOG"
check "server still reached Done"        "Done \("                                        "$LOG"

step "the old H1/H2 link errors stay gone (must be ABSENT)"
# Their canary checks went with the MinecraftForge canary (header); a NoSuchMethodError on either is still a defect.
check_absent "no NoSuchMethodError on CompoundTag.builder" 'NoSuchMethodError.*CompoundTag\.builder' "$LOG"
check_absent "no NoSuchMethodError on getConditionContext" 'NoSuchMethodError.*getConditionContext' "$LOG"

step "conditions in MinecraftForge's dialect are data NeoForge does not judge, as on native NeoForge (D49)"
# Every probe's expected answer is native NeoForge 26.2.0.93's, measured with this pack (header). A Forbric answer that
# differs is a Forbric-only behaviour: dual_forge's is one today. KernelNeoConditions.lenient, which
# ForbricMergedBaseCompatTransformer.letForeignResourceConditionsThrough puts in front of ICondition.CODEC, reads a
# condition type NeoForge has not registered as true for EVERY pack, so Forbric keeps a world datapack's file that
# native NeoForge skips ("Couldn't parse data file ... Unknown registry key ... neoforge:condition_codecs"). The guiding
# rule keeps that leniency only where the mod's own ecosystem tolerates the case (a Fabric mod's data: gate-m8's
# terralith:config), not for a world datapack. The check stays red as an open defect in stage4-c5-g-a.md (owner: the
# kernel lane the orchestrator names), never hidden by matching Forbric's answer here.
check_absent "registries loaded"                             "Failed to load registries due to errors" "$LOG"
check "the canary pack was loaded" "data pack file/forbricm21" "$LOG"
check_probe "a true forge:condition keeps a predicate"           "predicate kept"     'Test passed'
check_probe "a false forge:condition is not judged: the predicate stays" "predicate dropped" 'Test passed'
check_probe "a forge:conditional file does not parse and is skipped" "predicate variant" "Can't find element 'forbricm21:variant'"
check_probe "a file NeoForge judges keeps NeoForge's verdict"    "predicate dual_neo" 'Test passed'
check_probe "a file whose neoforge:conditions name an unknown type is skipped, as native NeoForge does" \
  "predicate dual_forge" "Can't find element 'forbricm21:dual_forge'"
check_probe "a true forge:condition keeps a datapack-registry element" "feature kept" 'Placed "forbricm21:kept"'
check_probe "a false forge:condition does not remove a datapack-registry element" "feature dropped" 'Placed "forbricm21:dropped"'
check_probe "a forge:condition naming NeoForge's own type keeps a data file" "predicate neo_owned" 'Test passed'
check_probe "a forge:condition type nobody registers keeps a data file"     "predicate unowned" 'Test passed'
check_probe "and its forge:conditional twin is skipped like any forge:conditional" "predicate variant_unowned" "Can't find element 'forbricm21:variant_unowned'"
check_probe "and keeps a datapack-registry element"          "feature unowned" 'Placed "forbricm21:unowned"'
check_probe "a recipe guarded by forge:not(forge:tag_empty) over a full tag is there" "recipe full_tag" 'Test passed'
check_probe "and so is one over an empty tag: the guard is not judged" "recipe empty_tag" 'Test passed'

# M21_NEO_CONTRACTS_BEGIN — the contracts the kernel gives a NeoForge mod, read out of NeoForge's own API by forbricneolive.
#
# The scan index, a server data loader, a mod finding its own container, a mixin config plugin, a per-world SERVER
# config: until Stage 3 the MinecraftForge canary proved each for its family too; these are the NeoForge twins that stay.
#
# RED controls (measured on the genuine base 2026-11-09, stage4-c5-g-a.md, unless marked; dual_forge, the open defect
# above, is red in each of them as in the default boot and is not counted):
#   M21_EXTRA_JVM='-Dforbric.neoScanData=off'    the NeoForge index is the empty one it once was: both readers' "found
#                                                its own" lines, both shape lines and both member lines are gone,
#                                                ModList's count line says 0 annotations and lists the jar 0 times --
#                                                9 checks red (11 before the per-jar flip, when a second
#                                                boot repeated two of them) -- and the kernel names the switch
#                                                ("NeoForge scan index DISABLED (-Dforbric.neoScanData=off)")
#   M21_EXTRA_JVM='-Dforbric.modFilePerJar=off'  one IModFile per MOD again, the shape before the Stage 4 flip: the
#                                                canary's jar holds two @Mod classes, NeoForge's own distinct() keeps
#                                                both entries, and the index lists the jar twice -- exactly the two
#                                                per-jar checks go red ("lists the canary's jar once", 2 for 1; "found in
#                                                one entry", exactly 1 got 2), and the kernel says so once ("every
#                                                NeoForge mod keeps an IModFile and file info of its own ...")
#                                                (measured 2026-11-09, stage4-c5-g-a.md)
#   M21_EXTRA_JVM='-Dforbric.seededScanData=off' the seeded LoadingModList's ModFile has no index again, so its reader
#                                                throws FML's "Scanning of this mod file has not started yet." and the
#                                                LoadingModList lines are gone; ModList's reader is untouched: 4 checks red,
#                                                the three LoadingModList lines and "neither reader failed" (measured
#                                                2026-11-09 after the flip; 5 before it, the second boot's absence too)
#   M21_EXTRA_JVM='-Dforbric.modDataPacks=off'   the kernel serves no NeoForge mod's own data/ (ModList.modFiles is
#                                                empty, so NeoForge's own pack finder finds none; KernelDataPacks is the
#                                                only server of it): the canary's listener still runs, over 0 files --
#                                                exactly "NeoForge: the registered data loader ran" goes red
# So of the three NeoForge RELOAD checks only the data loader has a red control. "DELIVERED" and "context=live" are
# POSITIVE-ONLY: no kernel switch turns them red, because the event is posted by NeoForge's own EventHooks.onResourceReload
# on NeoForge's own game bus, where the canary's constructor registers its listener, and its condition context is
# NeoForge's own. The SELF, PLUGIN and SERVER-config checks have no kernel switch either.
step "the NeoForge canary finds its OWN members through both readers of the scan index (must PASS)"
for reader in ModList LoadingModList; do
  check "NeoForge ($reader): the index carries the mod's own annotation" \
    "ForbricNeoLive/SCAN\] $reader found its own @NeoLiveScanned on FIELD PROBE of forbric.live.neoforge.ForbricNeoLiveMod" "$LOG"
  # NeoForge's shape, not MinecraftForge's and not a bare String: a String would also print SECOND.
  check "NeoForge ($reader): an enum member has NeoForge's own EnumHolder shape" \
    "ForbricNeoLive/SCAN\] $reader enum member kind is net\.neoforged\.fml\.loading\.modscan\.ModAnnotation[$]EnumHolder with value SECOND" "$LOG"
  check "NeoForge ($reader): a plain member is still a plain value" \
    "ForbricNeoLive/SCAN\] $reader string member note is probe" "$LOG"
done
check "NeoForge: ModList's index holds annotations at all" \
  "ForbricNeoLive/SCAN\] ModList.getAllScanData\(\): [1-9][0-9]* file\(s\), [1-9][0-9]* annotation\(s\)" "$LOG"
# One jar holding two mods (forbricneolive and its client-only forbricneoclientonly) is ONE file to NeoForge:
# getAllScanData() streams each mod's owning file through distinct(), so the jar is listed once. The count line above
# cannot tell 1 from 2 (it said "2 file(s)" with this one jar staged while the kernel built a file per mod); these can,
# and so can the reader line counted exactly. Until Stage 4 the kernel built a file per mod and these two were asserted
# in a second boot with -Dforbric.modFilePerJar=on; Stage 4 made the jar's one file the default (stage4-c5-g-a.md, the
# A/B), so the default boot asserts it and the switch is the red control above.
check "NeoForge: the index lists the canary's jar once, as one IModFile" \
  "ForbricNeoLive/SCAN\] ModList.getAllScanData\(\) lists forbricneolive's jar 1 time\(s\)" "$LOG"
check_exactly "NeoForge (ModList): its own annotation is found in one entry, not one per mod" \
  "ForbricNeoLive/SCAN\] ModList found its own @NeoLiveScanned" "$LOG" 1
check_absent "NeoForge: neither reader failed" "ForbricNeoLive/SCAN\] (could not read|LoadingModList has no file)" "$LOG"

step "a NeoForge mod's server data loader is registered and runs (must PASS)"
# NeoForge's AddServerReloadListenersEvent, posted by NeoForge's own EventHooks.onResourceReload, which the game base's
# ReloadableServerResources calls itself (the kernel's routing of that call through MinecraftForge's hook went with the
# carrier). Only the file count has a red control (-Dforbric.modDataPacks=off); the delivery and the context are
# positive-only (see the header).
check "NeoForge: the event was delivered"          '\[ForbricNeoLive/RELOAD\] AddServerReloadListenersEvent DELIVERED' "$LOG"
check "NeoForge: the registered data loader ran"   '\[ForbricNeoLive/RELOAD\] reload listener ran over [1-9][0-9]* file' "$LOG"
check "NeoForge: the event hands out a live condition context" '\[ForbricNeoLive/RELOAD\] context=live' "$LOG"

step "a NeoForge mod finds its OWN container during its constructor (must PASS)"
check "NeoForge: own container resolvable from its own ctor" \
  "\[ForbricNeoLive/SELF\] own container during ctor: present=true fmlContainer=true eventBus=true" "$LOG"
check "NeoForge: and it is the active namespace during that ctor" \
  "\[ForbricNeoLive/SELF\].*activeNamespace=forbricneolive" "$LOG"

step "a NeoForge mod's mixin config plugin is constructed, and asks the side and gets it (must PASS)"
check "NeoForge: the plugin saw the dedicated server" "ForbricNeoLive/PLUGIN\] prepareConfigs saw dist=DEDICATED_SERVER" "$LOG"
check_absent "NeoForge: and never failed to ask" "ForbricNeoLive/PLUGIN\] prepareConfigs saw dist=(null|FAILED)" "$LOG"

step "a NeoForge mod's per-world SERVER config loads exactly once (must PASS)"
check_exactly "NeoForge: its SERVER config loads once" "ForbricNeoLive/CFG\] LOADING forbricneolive-server\.toml" "$LOG" 1
check "NeoForge: and its value was read from the loaded spec" \
  "ForbricNeoLive/CFG\] LOADING forbricneolive-server\.toml: greeting=default loaded=true" "$LOG"
# M21_NEO_CONTRACTS_END

step "M21 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M21 SETUP GATE GREEN — NeoForge mods receive every setup phase, conditions are judged as native NeoForge judges them, and the scan index, reload and config contracts hold"
else
  echo "[kernel] ❌ M21 GATE RED — run $LOG"
fi
exit "$FAIL"
