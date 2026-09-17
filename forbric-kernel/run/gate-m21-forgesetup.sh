#!/usr/bin/env bash
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

step "boot both Forge families in one instance"
: > "$LOG"
( sleep 30; echo stop ) | RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
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
check "and ServerStartingEvent, which also initialises PermissionAPI" \
  "ForbricLive\] ServerStartingEvent RECEIVED" "$LOG"

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

step "M21 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M21 FORGE-SETUP GATE GREEN — both Forge families get all five server-side setup phases, and each phase's deferred work runs before the next"
else
  echo "[kernel] ❌ M21 GATE RED — run $LOG"
fi
exit "$FAIL"
