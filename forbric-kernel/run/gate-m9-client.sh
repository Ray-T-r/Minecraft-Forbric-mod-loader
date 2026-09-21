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

# SEED A MODIFIER BINDING, every run. MinecraftForge writes a modified key as
# `key_key.jei.toggleOverlay:key.keyboard.o:CONTROL_OR_COMMAND` and reads it back through vanilla's
# InputConstants.getKey, which throws on the suffix; Options.load wraps the whole file, so the player loses every
# setting AND the client then SAVES the defaults over the file. That last part is why this has to be re-seeded:
# this fixture carried three of JEI's and lost them exactly that way, taking the evidence with them.
# M9_KEY_MODIFIER_SEED_BEGIN — the contract test runs this exact step against a fixture options.txt.
python3 - "$RUNDIR/options.txt" <<'PY_SEED' || exit 3
from pathlib import Path
import sys
options = Path(sys.argv[1])
lines = options.read_text(encoding='utf-8').splitlines()
for i, line in enumerate(lines):
    if not line.startswith('key_key.') or ':' not in line:
        continue
    name, _, value = line.partition(':')
    if value.endswith(':CONTROL_OR_COMMAND'):
        break
    lines[i] = f'{name}:{value}:CONTROL_OR_COMMAND'
    options.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f'[kernel] seeded a modifier binding: {lines[i]}')
    break
else:
    raise SystemExit('no key_key.* binding in options.txt to give a modifier to')
PY_SEED
# M9_KEY_MODIFIER_SEED_END

kernel_jar
mkdir -p "$RUNDIR/quickPlay"
rm -f "$RUNDIR/logs/latest.log"
: > "$LOG"

step "launch the client into $WORLD via quick-play ($(ls -1 "$RUNDIR/mods"/*.jar 2>/dev/null | wc -l | tr -d ' ') mods, no compatibility flags)"
# M9_EXTRA_JVM is how the gate's teeth are demonstrated: switch a fix off and this must go RED. Also verified:
#   -Dforbric.mipmapLowering=off   -> 1 red ("an atlas may lower its mip level again")
#   -Dforbric.keyModifierSuffix=off -> 2 red ("the key-modifier suffix is dropped before the name is parsed",
#                                             "options.txt loads with modded modifier bindings in it")
#   -Dforbric.carrierLanguages=off -> 2 red ("NeoForge's own screens have their text", "and MinecraftForge's do too")
#   -Dforbric.blockStateCaches=off -> 1 red ("every block state's cache is computed"). Off, a block a mod
#                                      registered carries an uninitialised cache all run; vanilla computes
#                                      lazily and tolerates it, Lithium throws "Could not initialize block state
#                                      flags" the first time one is put in a chunk (verified on Windows: Biomes
#                                      O' Plenty's fir leaves, during feature placement).
#   -Dforbric.splitterPacketContext=off -> 2 red ("NeoForge's splitter encodes in Fabric's packet context",
#                                      and the anchor census noticing a repair that was handed its target and
#                                      declined — which is the switch working, said twice).
#                                      Off, a Fabric codec reading PacketContext.get() from inside NeoForge's
#                                      splitter sees null; with Polymer in the pack that is update_recipes
#                                      failing to encode and the client disconnected at world join.
#   -Dforbric.itemTooltipBridge=off -> 1 red ("a NeoForge mod can add a line to an item's tooltip"). Off, the
#                                      merged getTooltipLines posts only MinecraftForge's event and every
#                                      NeoForge mod's tooltip line goes into a list nobody built.
#   -Dforbric.fabricMainInConstructor=off -> 2 red ("Fabric main entrypoints run where Fabric runs them",
#                                                   "and not in the pre-Minecraft window"). Off, a Fabric mod that
#                                                   caches Minecraft.getInstance() from onInitialize caches null:
#                                                   ClickCrystals then killed the client inside Minecraft.<init>.
# Verified with
# -Dforbric.pruneDuplicateLambdas=off, which brings back StubException and the failed world load.
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeModsScreen=80 -Dforbric.clientSmokeDisconnectTicks=140 ${M9_EXTRA_JVM:-}" \
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

# The anchor census on the side that has the most repairs to lose. It must FIRE -- a census that never ran looks
# exactly like a clean one -- and nothing may have been handed its target class and declined it. Every miss here
# is a feature gone with no other symptom, which is how four of them arrived together with a carrier upgrade.
check        "the anchor census ran"        "Forbric/Anchor\] [0-9]+ of [1-9][0-9]* declared repair" "$LOG"
check_absent "every declared repair landed" "Forbric/Anchor\] [0-9]+ of [0-9]+ declared repair\(s\) landed, and" "$LOG"
check_absent "no repair was handed its target and declined" "Forbric/Anchor\] .* made no edit" "$LOG"
# J12: every AT line and access-widener entry is judged against the class it was applied to. Measured on this
# pack: 23 matched nothing — 16 AT lines naming members this Minecraft does not have at all (journeymap's
# SRG-named fields and 1.x members), 6 AT methods whose name is there under another descriptor (an overload this
# Minecraft lacks or a merge re-typing — not judged: bagus_lib's Model.animate, YACL's and Jade's constructors,
# sophisticatedcore's recipe builders), all of which a native loader ignores the same and which mark nobody — and
# ONE judged re-typed by the merge: fabric-biome-api's widener for ChunkGenerator.featuresPerStep names vanilla's
# Supplier descriptor, the ACCESS phase runs before the COREMOD repair that restores it, so the tweaker saw the
# merged descriptor and the field was not widened. That one is pinned; a change in either direction is worth knowing.
check        "the access census ran"               "Forbric/Access\] [0-9]+ directive\(s\) matched nothing across [1-9][0-9]* transformed class" "$LOG"
check        "exactly one directive is re-typed by an ecosystem" "Forbric/Access\] [0-9]+ directive\(s\) matched nothing.*: 1 re-typed by an ecosystem" "$LOG"
check        "and it is fabric-biome-api's featuresPerStep" "Forbric/Access\] AW directive from fabric-biome-api.*re-typed.*featuresPerStep" "$LOG"
check "the window title was read"      "ClientSmoke\] window title: Minecraft"     "$LOG"
check_absent "…and it names no single loader" "ClientSmoke\] window title: .*(NeoForge|Forge|Fabric)" "$LOG"
check "left the world cleanly"        "ClientSmoke\] clean disconnect observed"    "$LOG"
check "server side really ran"        "joined the game"                            "$LOG"
check "datapacks fully loaded"        "Loaded [1-9][0-9]* advancements"                 "$LOG"

step "the merge did not leave one ecosystem's opt-out binding the other two (client-fatal both times)"
# VANILLA lowers an atlas's mip level to fit its smallest sprite. MinecraftForge patches SpriteLoader to gate that
# on ForgeConfig.CLIENT.allowMipmapLowering(), default FALSE; the byte merge kept that half. The Logistics mod
# (NeoForge) has an 8x8 sprite in its own atlas, so the GPU refused the upload, the FIRST resource reload died,
# Minecraft dropped every pack, reloaded into the same failure -- and the client rendered a BLACK SCREEN for the
# rest of the run with no crash report and no further log line. That is the worst report shape there is.
check "an atlas may lower its mip level again" \
  'Forbric/MergedBaseCompat\] SpriteLoader lowers an atlas' "$LOG"
check_absent "no resource reload was abandoned" 'Caught error loading resourcepacks' "$LOG"
check_absent "no atlas was refused by the GPU" 'mipLevels must be at most' "$LOG"
# MinecraftForge writes a modified binding as key.keyboard.o:CONTROL_OR_COMMAND and then hands that whole string
# to InputConstants.getKey before splitting the modifier off, so vanilla's Integer.parseInt throws. Options.load
# wraps the WHOLE file in one try/catch: one modded binding costs the player every setting. This gate's own
# fixture has had three of them (JEI's) and lost its options on every run, silently, for as long as it existed.
check "the key-modifier suffix is dropped before the name is parsed" \
  'Forbric/MergedBaseCompat\] InputConstants.getKey now drops' "$LOG"
check_absent "options.txt loads with modded modifier bindings in it" 'Failed to load options' "$LOG"

step "the full FML mod lifecycle ran, not just the phases the kernel used to know about"
# Each of these was missing outright until the kernel started mirroring CommonModLoader.load's task order.
check "construct phase posted"        "posted FML construct to [1-9][0-9]* NeoForge mod"      "$LOG"
# A1: mods were constructed in jar-file-name order, which is not an order. A mod whose jar sorts before a library
# it requires ran first and called that library before it had initialised — the error then comes out of the
# library, blamed on the library. Two real dependency pairs from this pack, each with the library's name sorting
# AFTER its user, so alphabetical order gets both of them wrong.
check "construction is in dependency order" \
  "Forbric/Order\] construction order is dependency order" "$LOG"
# The Fabric half of the same fix: registration order is the order entry points are handed back in, so it is the
# order onInitialize runs in.
check "Fabric mods initialise in dependency order" \
  "Forbric/Order\] [1-9][0-9]* Fabric mod\(s\) initialise in dependency order" "$LOG"
for PAIR in "balm:cookingforblockheads" "creativecore:ambientsounds"; do
  LIB="${PAIR%%:*}"; USER_MOD="${PAIR##*:}"
  LIB_AT=$(grep -nE "constructed @Mod $LIB " "$LOG" | head -1 | cut -d: -f1)
  USER_AT=$(grep -nE "constructed @Mod $USER_MOD " "$LOG" | head -1 | cut -d: -f1)
  if [ -n "$LIB_AT" ] && [ -n "$USER_AT" ] && [ "$LIB_AT" -lt "$USER_AT" ]; then
    printf '[kernel] PASS %s is constructed before %s (line %s < %s)\n' "$LIB" "$USER_MOD" "$LIB_AT" "$USER_AT"
  else
    printf '[kernel] FAIL %s is constructed before %s (lib=%s user=%s)\n' "$LIB" "$USER_MOD" "${LIB_AT:-none}" "${USER_AT:-none}"; FAIL=1
  fi
done
check "client setup posted"           "posted FML client setup to [1-9][0-9]* NeoForge mod"   "$LOG"
# B3: common setup used to be posted from the kernel's pre-Minecraft window, on the main thread, with
# Minecraft.getInstance() still null — and common setup is exactly where a mod does its dist-guarded client
# initialisation (caching that singleton into a static, or handing work to its executor). Genuine NeoForge posts
# it from inside Minecraft's own constructor. The THREAD is the evidence: before the fix this line said [main].
check "common setup posted inside Minecraft's constructor" \
  "\[Render thread/INFO\]: \[Forbric/Lifecycle\] posted FML common setup to [1-9][0-9]* NeoForge mod" "$LOG"
check_absent "and not from the window before it exists" \
  "\[main/INFO\]: \[Forbric/Lifecycle\] posted FML common setup" "$LOG"
COMMON_AT=$(grep -nE "posted FML common setup to" "$LOG" | head -1 | cut -d: -f1)
CLIENT_AT=$(grep -nE "posted FML client setup to" "$LOG" | head -1 | cut -d: -f1)
if [ -n "$COMMON_AT" ] && [ -n "$CLIENT_AT" ] && [ "$COMMON_AT" -lt "$CLIENT_AT" ]; then
  printf '[kernel] PASS common setup precedes the sided phase (line %s < %s)\n' "$COMMON_AT" "$CLIENT_AT"
else
  printf '[kernel] FAIL common setup precedes the sided phase (common=%s client=%s)\n' "${COMMON_AT:-none}" "${CLIENT_AT:-none}"; FAIL=1
fi
check "registration events ran"       "ran NeoForge.s registration events"               "$LOG"
check "IMC enqueued and processed"    "posted FML IMC (enqueue|process) to [1-9][0-9]* NeoForge mod" "$LOG" 2
check "load complete posted"          "posted FML load complete to [1-9][0-9]* NeoForge mod"  "$LOG"
check_absent "no mod failed a phase"  "failed during (construct|IMC enqueue|IMC process)" "$LOG"

# The client half of the Neo->Forge bridge inventory. This one bridge carries every MinecraftForge mod's client
# reload listeners -- GeckoLib's whole model and animation cache hangs off it -- and it used to be reported only
# by an unasserted "installed the ... bridge" line.
check "the client-side bridge pass is complete" "all 1 CLIENT_MOD_BUS bridge\(s\) installed" "$LOG"
# By "complete", not by count -- see gate-m4 for why. The client ticks are their own pass because they name
# NeoForge's client event package, which a dedicated server must never resolve.
check "the game-bus bridge pass is complete too" "EventMux\] all [0-9][0-9]* GAME_BUS bridge\(s\) installed"      "$LOG"
check "the client game-bus bridges went on too" "EventMux\] all [0-9][0-9]* CLIENT_GAME_BUS bridge\(s\) installed" "$LOG"
# The two transformer-landed passes (Phase 1 A): Forge's client registration hooks inside Minecraft.<init> and the
# block-colour table, and the creative-tab / spawn-placement hooks. Verified by count so a repair that stood down on
# an unexpected base is named, not silently absent.
check "the client initialization bridges landed" "EventMux\] all 3 CLIENT_INIT bridge\(s\) installed"  "$LOG"
check "the registration bridges landed"          "EventMux\] all 2 REGISTRATION bridge\(s\) installed" "$LOG"
check_absent "no bridge reported missing"       "bridge\(s\) MISSING"                        "$LOG"
# F2: fabric-model-loading-api-v1's ModelManagerMixin is TRIMMED to the eight injectors that fit the merged
# ModelManager instead of pinned whole, so Fabric ModelLoadingPlugins dispatch. RED with
# M9_EXTRA_JVM=-Dforbric.guestInjectorPruner=off (the pin returns and the 'pruned' line is absent). The
# check_absent is the missingno regression guard: half-applied, all 4666 block models died on this parse error
# and the world rendered as the checkerboard with no other symptom.
check "ModelManagerMixin trimmed, not pinned" "GuestInjectorPruner\] pruned 2 injector\(s\) from .*ModelManagerMixin" "$LOG"
check_absent "block models still parse"       "JSON data was null or empty"                "$LOG"
# G1: an injector bound by explicit descriptor to a merge-added DELEGATING STUB (NeoForge moved the body of
# SimpleContainer.setItem(int,ItemStack) into a 3-arg overload) is rebound to the delegate, so fabric-transfer's
# setChanged suppression applies again instead of reading PARTIAL. RED with M9_EXTRA_JVM=-Dforbric.mixinRetarget=off
# (the two 'retargeted' lines are absent and the 'applies only partially' lines return).
check "fabric-transfer's SimpleContainer suppression rebound" "Forbric/Mixin\] retargeted guest mixin fabric-transfer-api-v1 .*SimpleContainerMixin .*setItem\(ILnet/minecraft/world/item/ItemStack;\)V → setItem\(ILnet/minecraft/world/item/ItemStack;Z\)V.*PARTIAL→FIT" "$LOG"
check "…and its BaseContainerBlockEntity twin"      "Forbric/Mixin\] retargeted guest mixin fabric-transfer-api-v1 .*BaseContainerBlockEntityMixin .*PARTIAL→FIT" "$LOG"
check_absent "SimpleContainerMixin no longer half-applied" "SimpleContainerMixin applies only partially" "$LOG"
check_absent "BaseContainerBlockEntityMixin no longer half-applied" "BaseContainerBlockEntityMixin applies only partially" "$LOG"
# G3: NeoForge's 12-arg Snippet constructor made MixinExtras reject fabric-rendering-v1's 11-arg wrap whole
# ('has an invalid signature'); buildSnippet now constructs through the vanilla-shaped constructor with the
# stencil test carried by a kernel scope. RED with M9_EXTRA_JVM=-Dforbric.snippetFunnel=off (the 'routed' line
# is absent and the invalid-signature apply failure returns).
check "the snippet call site was funnelled"   "SnippetFunnel\] routed 1 RenderPipeline\\\$Builder.buildSnippet" "$LOG"
check_absent "fabric-rendering-v1's snippet wrap matches the constructor" "RenderPipelineBuilderMixin.*has an invalid signature|Found unexpected argument type com.llamalad7.mixinextras.injector.wrapoperation.Operation" "$LOG"
check_absent "RenderPipelineBuilderMixin is not half-applied either" "RenderPipelineBuilderMixin applies only partially" "$LOG"
# G4: NeoForge swapped BlockState.isAir() for its overridable isEmpty() in LevelChunkSection; fabric-block-api's
# redirect handler IS NeoForge's default isEmpty predicate, so the @At follows the swap (a KNOWN row, census-pinned).
# RED with M9_EXTRA_JVM=-Dforbric.mixinRetarget=off (shared with G1).
check "fabric-block-api's isAir redirect rebound to isEmpty" "Forbric/Mixin\] retargeted guest mixin fabric-block-api-v1 .*LevelChunkSectionMixin .*isAir → isEmpty.*PARTIAL→FIT" "$LOG"
check "…and the block-counter twin"                   "Forbric/Mixin\] retargeted guest mixin fabric-block-api-v1 .*ChunkSectionBlockStateCounterMixin .*isAir → isEmpty.*PARTIAL→FIT" "$LOG"
check_absent "LevelChunkSectionMixin no longer half-applied" "LevelChunkSectionMixin applies only partially" "$LOG"
# G5: the merge re-typed AttributeSupplier$Builder.builder (ImmutableMap.Builder → Map) and widened the two ranged
# goals' `mob` (Monster → Mob); a vanilla-descriptor twin now sits beside each, so fabric-object-builder's
# @Accessor binds instead of InvalidAccessorException on every boot. The goals are only loaded when a ranged mob
# spawns, so only the builder is asserted. RED with M9_EXTRA_JVM=-Dforbric.widenedFieldTwins=off.
check "the attribute builder got its vanilla-typed twin" "WidenedFields\] net.minecraft.world.entity.ai.attributes.AttributeSupplier\\\$Builder: vanilla-descriptor twin" "$LOG"
check_absent "fabric-object-builder's attribute accessor binds" "InvalidAccessorException.*builder:Lcom/google/common/collect/ImmutableMap\\\$Builder;" "$LOG"
check_absent "…and the kernel reports no unbound accessor for it" "guest accessor mixin .*AttributeSupplierBuilderAccessor cannot bind" "$LOG"
# G6: ItemStack.addDetailsToTooltip is scrapeable again (vanilla's component order copied to its head from the
# merge's own renamed body). RED with M9_EXTRA_JVM=-Dforbric.tooltipOrderScrape=off.
check "vanilla's tooltip component order restored" "TooltipOrder\] restored a scrapeable vanilla component order of [2-9][0-9] type" "$LOG"
# G7: no mixin in this pack lands on a renumbered vanilla anonymous class (chat_heads' ChatComponent$1 is
# capture-only and must NOT be named). No RED demonstration is possible here — no staged mixin targets a
# relocated name; the unit test carries the mechanism. This pins today's state.
check_absent "no pack mixin lands on a renumbered anonymous class" "targets .* a renumbered anonymous class" "$LOG"
# G8: every installed jar is scanned for reads of a vanilla field the merge re-typed (KeyMapping.MAP as a Map,
# WeightedList$Builder.result as an ImmutableList.Builder). The count line always prints; the pack's readers are
# NeoForge builds compiled against the lookup descriptor, so the finding is 0. RED (line absent) with
# M9_EXTRA_JVM=-Dforbric.fieldDriftAudit=off.
check "field-drift audit ran over the whole pack" "Forbric/FieldDrift\] scanned [0-9][0-9]+ jar\(s\): [0-9]+ reference" "$LOG"
check_absent "no pack jar reads a re-typed vanilla field" "Forbric/FieldDrift\] .* reads .* \(cost" "$LOG"
# H5 (the FluidRenderer.tesselate funnel for MinecraftForge fluid models) is asserted in gate-m26, not here: this
# pack carries sodium, which replaces vanilla's chunk and fluid meshing, so the vanilla funnel is never reached.

step "a Forge-family mod's own content and data actually arrived (must PASS)"
# Three fixes that only this pack exercises, each demonstrable: -Dforbric.modDataPacks=off,
# -Dforbric.registryAliasParity=off, -Dforbric.neoRegistrationOrder=off each turn this gate RED.
check "datapacks served"              "Forbric/DataPacks\] served [1-9][0-9]* datapack"             "$LOG"
# The carriers are where the c: convention-tag skeleton lives — 513 tag files that exist in NO other jar, and that
# every cross-mod recipe is written against. Assert the NUMBER: the line keeps printing when the count goes to zero.
CARRIERS=$(grep -aoE 'served [0-9]+ datapack\(s\).*— [0-9]+ loader carrier' "$LOG" | grep -oE '[0-9]+ loader' | grep -oE '[0-9]+' | head -1)
assert_eq "loader carriers served" 2 "${CARRIERS:-none}"
# The ORDER, not the file names. This asserted the basenames of one machine's staged artifacts, so pointing the
# gate at another machine's — a user's own install, where the same jars are named forge-runtime-26.2.jar — failed
# it for a reason that has nothing to do with where the carriers sit. What it is about is 1- before 2-.
check "carriers sit below the mods"   "forbric/carrier/1-forge-runtime[^,]*, forbric/carrier/2-neoforge-runtime" "$LOG"
check "registry alias parity restored" "Forbric/Aliases\] gave .* alias-resolving lookup"      "$LOG"
check "NeoForge registration order"    "fired RegisterEvent in NeoForge.s registration order"  "$LOG"
# A mod whose items name their own data components: with RegisterEvent in field order the item registry is filled
# 57 registries too early, DeferredHolder.value() throws, and the mod loses every item it had not reached yet.
check_absent "no unbound data component" "Trying to access unbound value"                      "$LOG"
# I5: the registries freeze NeoForge-first on both windows (server + client entrypoint), so NeoForge's freezeData
# finishes instead of aborting at the first registry MinecraftForge had already frozen. RED with
# M9_EXTRA_JVM=-Dforbric.freezeNeoForgeFirst=off (the THREW line returns twice).
check_absent "NeoForge's freezeData finished on both windows" "GameData.freezeData\(\) THREW" "$LOG"
check "registries frozen NeoForge-first, twice"  "froze the registries NeoForge-first: [1-9][0-9]* registr(ies|y), [0-9]+ tag key" "$LOG" 2
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
# two are expected, and a third must turn this gate red. Both are mods (or a carrier) shipping data for a
# contract that moved, and a genuine NeoForge 26.2 instance rejects each of them the same way:
#   *:global_loot_modifiers  — the legacy Forge list file (replace/entries). NeoForge's LootModifierManager runs
#     IGlobalLootModifier.DIRECT_CODEC over every file in loot_modifiers/ and has no list-file concept; its own
#     GlobalLootModifierProvider stopped writing one. The MODIFIERS are fine — usefulfood:glow_squid and
#     earthmobsmod:desert_in_ruby are not named here, and this loader names everything that fails.
#   (earthmobsmod:entities/tropical_slime used to be the third. The mod left the pack when the carrier moved to
#     NeoForge 26.2.0.88: its EntityFluidInteraction mixin calls isInFluid(TagKey) with its own earthmobsmod:mud
#     tag, and from .88 that path goes through getFluidTypeByTag, which knows water and lava and throws on
#     anything else — verified against the stock NeoForge-patched jar, so it is not a Forbric failure.)
# I7: both managers' directory scans now run over a view that hides the legacy index, so NOTHING fails to parse
# — a genuinely broken loot modifier is distinguishable again. RED with M9_EXTRA_JVM=-Dforbric.lootModifierIndex=off
# (both ERROR lines return and the set is the two indexes again).
UNPARSEABLE=$(grep -aoE "Couldn.t parse data file '[^']*'" "$LOG" | sed -E "s/.*'(.*)'/\1/" | sort -u | paste -sd, -)
assert_eq "no data file fails to parse" "" "$UNPARSEABLE"
check "loot-modifier scan ran and hid the two indexes" "loot-modifier directory scan: [1-9][0-9]* file\(s\) kept, 2 legacy index file\(s\) hidden \[(forge|neoforge):loot_modifiers/global_loot_modifiers.json, (forge|neoforge):loot_modifiers/global_loot_modifiers.json\]" "$LOG"
# J8: every installed jar's Forge-family class references resolve against the carriers, the merged base and the
# pack itself. 0 on this pack is the false-positive pin (CustomSkinLoader's fml/loading refs are out of scope by
# rule); a mod compiled against another NeoForge/MinecraftForge would be named here and DEGRADED on its row.
check "abi audit ran and found no dangling Forge-family reference" "AbiAudit\] scanned [1-9][0-9]* jar\(s\) in [0-9]+ ms: 0 with dangling" "$LOG"
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

step "the unified Mods screen opens and draws (must PASS)"
# The only thing here no unit test can reach: a Screen's init and its draw run when a player clicks the button,
# so a mistake in either is a crash mid-frame on someone else's machine. The smoke opens it the way the pause
# menu does, holds it, and reads back the frame count the screen itself kept -- "no exception reached the caller"
# would still be true of a screen the crash handler had replaced.
# THE assertion, and the one that was missing: a player presses the pause menu's mods button. Everything else
# here reaches the screen by NAME, which proves the screen and proves nothing about the button — and the button
# is what a player has. The pause menu on a modded instance carries more than one "Mods" button (Mod Menu inserts
# its own next to the Forge family's), so the label is asserted too: a redirect nobody can tell took effect reads
# as "nothing happened", which is exactly how it was reported.
# BOTH screens, because they do not share a button. The title screen's is not built in TitleScreen at all -- it
# is neoforge.client.gui.widget.ModsButton, a widget whose own create() builds it and whose own lambda opens the
# old list -- so the first version of this redirect reported a site re-pointed in TitleScreen (a dead one the
# byte merge left) while the button a player can see went on opening NeoForge's list. Asserting only the pause
# menu measured the wrong half and called the feature done.
# The icon too. A button wearing NeoForge's logo while opening every ecosystem's mods is a picture that is wrong
# about what the button does, and a GUI sprite that resolves to nothing renders as a magenta square rather than
# failing — so the absence of an error proves nothing on its own. Assert the rewrite AND that the pack carrying
# the texture reached the client repository.
check "the widget's icon is the kernel's own" \
  "ModsButton's mods button now opens the unified list and says so \\([0-9]+ construction site\\(s\\) re-pointed, [1-9][0-9]* label" "$LOG"
check "the kernel's own assets reached the pack repository" "forbric/forbric-kernel-runtime" "$LOG"
check_absent "and its sprite resolved"  "Missing sprite: forbric" "$LOG"
check "the title screen's mods button is labelled as ours" \
  "title-screen button #[0-9]+: net\.neoforged\..*ModsButton \"Mods \(Forbric\)\"" "$LOG"
check "pressing it opens the unified list" \
  "the title screen's mods button opened: net\.forbric\.kernel\.runtime\.KernelModListScreen" "$LOG"
check_absent "and pressing it did not fail" "could not press the title screen's mods button" "$LOG"
# Asserted on the LABEL, not on the widget class. NeoForge 26.2.0.88 moved the pause menu's mods button out of
# PauseScreen into its own neoforge.client.gui.widget.ModsButton, so a pattern that also pinned
# net.minecraft...SpriteIconButton went red while the button said exactly what it was supposed to say. The claim
# here is what a player reads off the button; which class draws it is upstream's business.
check "the Forge-family mods button is labelled as ours" \
  "pause-menu button: [^ ]+ \"Mods \(Forbric\)\"" "$LOG"
check "pressing it opens the unified list" \
  "the mods button opened: net\.forbric\.kernel\.runtime\.KernelModListScreen"   "$LOG"
check_absent "and pressing it did not fail" "could not press the pause menu's mods button" "$LOG"
# The double-click shortcut, exercised through a row's own mouseClicked with doubled=true -- the same call the
# widget makes on the second click. Calling the resolver by name proves the resolver and says nothing about
# whether the flag is wired to it, which is the exact shape of the mods-button bug.
# NOT "opened: <something>": the line prints either way, and when the shortcut is not wired what it names is the
# mod list itself — which the obvious pattern matches. Mutation-testing this assertion is what caught that. What
# has teeth is that the screen in front of the player is no longer the list.
DBL=$(grep -oE "double-clicking [a-z0-9_]+ in the unified list opened: [A-Za-z0-9_.$]+" "$LOG" | head -1)
case "${DBL:-}" in
  "") echo "[kernel] FAIL double-clicking a row never reported a screen"; FAIL=1 ;;
  *KernelModListScreen) echo "[kernel] FAIL double-clicking a row left the list up — the shortcut is not wired"; FAIL=1 ;;
  *) echo "[kernel] PASS ${DBL#double-clicking }" ;;
esac
check_absent "and the double-click did not fail" "could not double-click a row" "$LOG"
check "the screen opened"  "ClientSmoke\] opened the unified Mods screen" "$LOG"
FRAMES=$(grep -oE 'unified Mods screen drew [0-9]+ frame' "$LOG" | grep -oE '[0-9]+' | head -1)
ROWS=$(grep -oE 'frame\(s\) listing [0-9]+ mod' "$LOG" | grep -oE '[0-9]+' | head -1)
[ "${FRAMES:-0}" -ge 1 ] && echo "[kernel] PASS it actually rendered ($FRAMES frames)" \
  || { echo "[kernel] FAIL the Mods screen drew no frames (got ${FRAMES:-none}) — constructed is not rendered"; FAIL=1; }
[ "${ROWS:-0}" -ge 1 ] && echo "[kernel] PASS and it listed mods ($ROWS rows)" \
  || { echo "[kernel] FAIL the Mods screen listed nothing (got ${ROWS:-none})"; FAIL=1; }
check_absent "the screen did not throw" "the unified Mods screen could not be opened" "$LOG"

step "a mod's assets are applied but are not resource packs the player has to see (must PASS)"
# Pack.isHidden gates LISTING, never application: getAvailableIds/getSelectedIds filter on it, openAllSelected
# and getSelectedPacks do not, and rebuildSelected re-inserts every required pack regardless. The byte merge kept
# NeoForge's Pack (so the flag exists) and vanilla's TransferableSelectionList (so nothing read it), which put
# every ecosystem's asset pack in the player's list as a row they did not add and cannot remove.
#
# Counted from the SCREEN's own rows and not from the repository: the repository's id accessors already filter
# hidden packs and would report success whether or not the screen does.
# A8 (overlays half): the kernel SYNTHESISED each pack's metadata with an empty overlay list, so a mod declaring
# overlays — the mechanism for shipping one set of assets per game version — had them dropped without a word.
# The packs are now read through the loader's own reader, which fills them in. The count is the evidence: under
# the old code it was zero however many mods declared them.
check "mod packs carry the overlays they declare" \
  "ClientPacks\] served [0-9]+ ecosystem asset pack\(s\).*, [1-9][0-9]* of them declaring overlays" "$LOG"
check_absent "and no pack fell back to synthesised metadata" \
  "could not read a pack's own metadata" "$LOG"

check "the filter is back on the screen" \
  "PackScreen\] restored the hidden-pack filter" "$LOG"
# EXACTLY ONE row, and it is the parent. Seventy-odd rows would be the old "every mod is a row the player did
# not add" problem; zero would mean the player has no way to put their own pack above a mod's textures, which is
# what required+fixed+TOP on every mod pack used to guarantee.
#
# The count itself was measuring nothing until now: it read each row's NARRATION, which is the pack's title, and
# matched it against "forbric/". That only worked while the kernel titled each pack after its own id, so the
# moment a pack got a real title the count answered "none" whatever the screen held. It reads the row's pack id
# now.
PACKROWS=$(grep -oE 'resource-pack screen lists [0-9]+ pack row\(s\), [0-9]+ of them' "$LOG" | grep -oE '[0-9]+' | tail -1)
if [ -n "$PACKROWS" ] && [ "$PACKROWS" -eq 1 ]; then
  echo "[kernel] PASS the kernel's assets are one movable row, not one per mod"
else
  echo "[kernel] FAIL the resource-pack screen lists ${PACKROWS:-?} of the kernel's packs, expected exactly 1"; FAIL=1
fi
check "and that row is the parent pack" "rows: \[.*forbric/mod_resources" "$LOG"
# …and they are still APPLIED. A screen with nothing in it would pass the check above and cost every mod its
# textures, which is the failure this assertion exists to tell apart from success.
check "and they are still selected in the repository" \
  "repository holds [1-9][0-9]* selected" "$LOG"
check_absent "the screen opened at all" "could not open the resource-pack screen" "$LOG"

step "a config registered too late for the early pass is still opened (must PASS)"
# The early config pass runs once, before mod content registration. A mod registering a config from a Fabric
# client entrypoint is past it, and nothing else opens a non-STARTUP config -- the carrier eagerly opens STARTUP
# only. The mod then reads a config that was registered and never loaded, and what it gets is not a default but
# "Cannot get config value before config is loaded", thrown wherever it first asked. ShoulderSurfing asks from a
# mixin in Minecraft.<init>, so the whole client dies.
# This used to assert that the LATE pass opened 18 configs. It did -- and then loadEarlyConfigs, which runs after
# it used to, opened all 18 again through ConfigTracker.loadConfigs (a sweep of the whole type that does not skip
# a config with a loaded one). So the assertion pinned the double open: 36 "Opening a config that was already
# loaded" warnings per boot on the main thread, ModConfigEvent.Loading delivered twice to every one of those mods,
# each file re-read and a second watcher installed. The early pass now runs first and covers them, so the late
# pass correctly finds nothing left -- which is why the count assertion had to go rather than be retargeted.
#
# What is asserted instead is the OUTCOME: the early pass ran over both types, the config that used to crash the
# client is loaded, and no config is opened twice during boot. The Server-thread double open at world join (16 per
# run, the per-world SERVER configs) is a SEPARATE defect and is deliberately not covered here yet.
check "the early pass loads both boot-time config types" \
  "Forbric/Lifecycle\] loaded NeoForge configs \(COMMON\+CLIENT\)" "$LOG"
check_absent "and no config is opened twice during boot" \
  "\[main/WARN\]: Opening a config that was already loaded" "$LOG"
check_absent "and nothing read a config before it was loaded" \
  "Cannot get config value before config is loaded" "$LOG"
# The late pass opens only what has no loaded config yet. Re-opening one the early pass already did warns and
# installs a SECOND file watcher, so every later edit of that file fires the reload twice.
check_absent "and nothing was opened twice" "Attempted to load config .* more than once|Overwriting non-null config" "$LOG"

step "a Fabric mod shipping its own copy of a Forge-family class is named (must PASS)"
# ForgeConfigAPIPort ships net.neoforged.fml.config.* so Fabric mods can use NeoForge's config API. Under Forbric
# that package is ALWAYS_GAME, so the carrier's copy wins and the port's own compiled call sites meet an API they
# were not built against -- registerConfig takes a ModContainer here and a mod-id String there. Unreported, that
# surfaces as a NoSuchMethodError in whichever mod registered a config, several steps later.
check "the audit names the port and the class" \
  "Forbric/PortAudit\] ForgeConfigAPIPort.*ConfigTracker.* does NOT match the carrier" "$LOG"
check "and it says which member differs" \
  "Forbric/PortAudit\].*registerConfig.*Lnet/neoforged/fml/ModContainer;" "$LOG"
check_absent "nothing actually failed on that API" "NoSuchMethodError.*ConfigTracker" "$LOG"

step "a Fabric mod's tooltip providers reach the body that runs (must PASS)"
# NeoForge's patch moved vanilla's ItemStack.addDetailsToTooltip body into a private
# addDetailsToTooltipComponents with the IDENTICAL descriptor and made the original a dispatcher over its own
# ItemTooltipHandler. fabric-item-api's ItemStackMixin has five injections into that method — two @ModifyArgs,
# two @Injects and a @ModifyExpressionValue sharing one LocalIntRef — and every one of their anchors ended up in
# the renamed method, so a Fabric mod registering a tooltip provider had its entry recorded and never applied.
#
# The identical descriptor is what makes the rewrite safe: the handlers' parameters, their CallbackInfo and every
# @Local they capture stay exactly as valid. RED with M9_EXTRA_JVM=-Dforbric.mixinRetarget=off: these two go red,
# and so do this gate's other retarget checks — that switch turns off every rule, not just this one.
check "the mixin is retargeted onto the renamed body" \
  "Forbric/Mixin\] retargeted guest mixin fabric-item-api-v1.*addDetailsToTooltip.*addDetailsToTooltipComponents" "$LOG"
check "and almost every anchor resolves afterwards" \
  "Forbric/Mixin\] guest mixin fabric-item-api-v1.*still applies only partially.*1[0-9]/1[0-9] anchors resolve" "$LOG"
check_absent "the stale 'recorded but not applied' claim is gone" \
  "recorded but not applied" "$LOG"

step "an access directive the kernel already satisfies does not mark its mod (must PASS)"
# fabric-biome-api's widener asks for ChunkGenerator.featuresPerStep as vanilla's Supplier. MinecraftForge
# re-typed that field to its own ClearableLazy so refreshFeaturesPerStep() has something to invalidate, and the
# merge kept only that declaration — so the widener matches nothing and the mod was marked.
#
# It loses nothing: the COREMOD repair gives the field vanilla's descriptor back AND makes it public non-final,
# which is the widener's whole job. The ACCESS phase simply runs first. Both halves are asserted because either
# alone passes with the judgement broken — the line must SAY what the field is now, and no row may be marked.
check "the directive is reported as already satisfied" \
  "Forbric/Access\] AW directive from fabric-biome-api.*ClearableLazy; here.*but the kernel gives that field" "$LOG"
check_absent "and its mod is not marked for it" \
  "Forbric/Access\] AW directive from fabric-biome-api.*the mod is marked" "$LOG"

step "no row says the same thing twice (must PASS)"
# A reason is often SEVERAL clauses already joined with "; " — one repair naming two things it could not do —
# and the dedup compared the whole incoming text to each existing clause, so the same multi-clause reason
# arriving twice was printed twice. fabric-item-api's tooltip row read that way on the Mods screen and in the
# load report. Checked over the whole report rather than that one row: a repeated reason is a reporting defect
# wherever it appears, and pinning the row would go stale the moment the pack changes.
# M9_LOAD_REPORT_DEDUP_BEGIN — the contract test runs this exact step against a fixture report.
python3 - "$RUNDIR/.forbric-kernel/load-report.txt" <<'PY_DEDUP'
from pathlib import Path
import sys
report = Path(sys.argv[1])
if not report.is_file():
    print(f'[kernel] FAIL no load report at {report}')
    raise SystemExit(1)
bad = []
for line in report.read_text(encoding='utf-8').splitlines():
    clauses = [c.strip() for c in line.split('; ') if c.strip()]
    if len(clauses) != len(set(clauses)):
        bad.append(line.strip()[:120])
if bad:
    for line in bad:
        print(f'[kernel] FAIL a load-report row repeats a reason: {line}')
    raise SystemExit(1)
print('[kernel] PASS no load-report row repeats a reason')
PY_DEDUP
[ $? -eq 0 ] || FAIL=1
# M9_LOAD_REPORT_DEDUP_END

step "a mixin the kernel took over does not report a loss that did not happen (must PASS)"
# fabric-resource-conditions' SimpleJsonResourceReloadListenerMixin cannot apply here: NeoForge's patch of
# scanDirectory made the value Optional and reordered the lambda's captures, so the descriptor Mixin expects is
# not the one the mod was built against. BOTH of that mixin's members are the fabric:load_conditions evaluator,
# and the kernel does that job one level down on ConditionalOps' own funnel — covering every consumer instead of
# this one call site.
#
# So the mod lost nothing, and marking it reports a loss that did not happen. A report that cries wolf is worse
# than no report: the next real one is read the same way. RED with M9_EXTRA_JVM=-Dforbric.supersededMixins=off,
# which turns it back into an ordinary marked failure — that is how the claim gets checked against the game.
check "the failure is reported as superseded, not as a loss" \
  "Forbric/Mixin\].*SimpleJsonResourceReloadListenerMixin failed to apply.*so its mod is not marked" "$LOG"
check_absent "and its mod is not marked" \
  "Forbric/Mixin\].*SimpleJsonResourceReloadListenerMixin.*is marked" "$LOG"
check "and the conditions are still judged by someone" \
  "Forbric/Conditions\] Fabric's own resource-condition evaluator is live" "$LOG"

step "each carrier's own screens have their own text (must PASS)"
# The carriers keep a second translation table beside Minecraft's, because the text on it -- the loading screen,
# the mod list, the branding line under the logo -- has to render before a resource pack exists. FMLTranslations
# and ForgeI18n read only that table and NEVER the resource manager, and a missing key there renders as the key
# itself. Each carrier fills it in exactly one place, and both are inside the client mod loader the kernel
# replaces, so both tables stayed empty: a Forbric client showed "fml.menu.branding" under the logo and
# "fml.button.continue.launch" on the button that leaves the loading screen.
#
# The probe key is asserted, not just the count: a table that loaded the WRONG file is still a broken screen, and
# that failure used to look identical to a healthy one in the log.
check "NeoForge's own screens have their text" \
  "Forbric/Lang\] neoforge carrier: [0-9]+ built-in translation\(s\) loaded; 'fml.menu.branding' resolves" "$LOG"
check "and MinecraftForge's do too" \
  "Forbric/Lang\] forge carrier: [0-9]+ built-in translation\(s\) loaded; 'fml.menu.mods' resolves" "$LOG"
check_absent "and no carrier loaded a table without its own keys in it" \
  "Forbric/Lang\].*still does not resolve" "$LOG"
# The other half of the same story, and the half that is NOT the carriers' private table: what the resource
# manager can see. A pack served with no namespace of its own keeps its textures (fetched by path) and loses
# everything found by listing — its language file among it. RED with M9_EXTRA_JVM=-Dforbric.clientResourcePreload=off.
check "and both carriers' assets are visible to the resource manager" \
  "Forbric/ClientResources\] [0-9]+ namespace\(s\) visible; the carriers' own: \[forge, neoforge\]" "$LOG"

step "the world is on disk before the process ends (must PASS)"
# A real player Alt+F4'd and lost a minute of play: IntegratedServer.stopServer runs teardownPublishedState
# FIRST and unguarded, and MinecraftServer.stopServer -- which writes players and worlds -- second, so one throw
# on the way out ended the process with level.dat at the last autosave. The repair is a two-instruction exception
# range; what is asserted here is the OUTCOME, because a handler that exists and a save that runs are different
# claims and only the second is the one that matters.
# Fabric's own Hooks.startClient runs main and then client from inside Minecraft.<init>, after instance = this.
# The kernel ran main in its pre-Minecraft registration window, where getInstance() is null -- so the thread name
# is the assertion: "main" is the pre-Minecraft window, "Render thread" is the constructor.
# A @Group is a mod's own statement that some of its alternatives are MEANT to miss — they are the shapes other
# game versions have. Anything the kernel does to injection points has to leave those alone, and the cost of not
# doing so is the whole mixin class: Iris' LevelRenderer group took every shader hook in it down with one.
check_absent "no callback group is broken by a point the kernel moved" \
  "Callback group @Group.*failed injection check" "$LOG"

# Asserted on the POST, not on the transformer's line: the seam being in the bytecode is what the census proves,
# and what a player gets is the event actually firing while a tooltip is built.
# Asserted on the transformer, not the runtime line: the splitter only announces itself once a Fabric packet
# context exists to bind, and this pack has no mod that needs one — what must hold here is that the seam is in.
check "every block state's cache is computed" \
  "\[Forbric/Lifecycle\] initialised [1-9][0-9]* block state cache\(s\)" "$LOG"

check "NeoForge's splitter encodes in Fabric's packet context" \
  "\[Forbric/Net\] .*GenericPacketSplitter.encode now runs inside the connection's Fabric packet context" "$LOG"

check "a NeoForge mod can add a line to an item's tooltip" \
  "\[Forbric/Tooltips\] NeoForge's ItemTooltipEvent is posted beside MinecraftForge's" "$LOG"

check "Fabric main entrypoints run where Fabric runs them" \
  "\[Render thread/INFO\]: \[Forbric/Fabric\] invoked [1-9][0-9]* Fabric main entrypoint\(s\) in the Minecraft.<init> window" "$LOG"
check_absent "and not in the pre-Minecraft window" \
  "\[main/INFO\]: \[Forbric/Fabric\] invoked [0-9]+ Fabric main entrypoint" "$LOG"

check "the save ran on the way out"        "Saving worlds"                                    "$LOG"
check "and it finished"                    "ThreadedAnvilChunkStorage: All dimensions are saved" "$LOG"
check_absent "nothing aborted the stop"    "Exception stopping the server"                    "$LOG"

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
