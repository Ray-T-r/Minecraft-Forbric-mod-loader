# Stage 4 C3, lane "events-hooks": how each deletion was checked

Branch `s4-c3`. The lane deleted six repairs that existed only because the base merged MinecraftForge's and
NeoForge's patches (commits b019efc3..6a1fcd6d), and a review round fixed four findings (3be25731..7af6a450). Each
deleting commit's message carries its three checks (symptom with merged red, whole-jar search, callers) with log
excerpts. This file keeps the commands and the scanner sources, which lived only in a session scratchpad.

Names used below: `$GS` genuine stage (NeoForge's patched base `run/neoforge-patched/patched-mc-neoforge-26.2.jar`,
carrier `run/neoforge-runtime/neoforge-runtime.jar`), `$MS` merged stage (`run/merged-base/patched-mc-merged-26.2.jar`,
`run/forge-runtime/forge-runtime.jar`, `run/neoforge-runtime/neoforge-runtime.jar`), `$WF` the `s4-c3` worktree, `$RO` a
read-only worktree at cf0498eb (merged base, all old repairs; throwaway edits there were reverted), `$C3` the lane's
scratch directory (logs; not kept). Heavy commands went through `.stage/heavy.sh` (two shared slots). `MC_DIR` was
never set.

## Results

| candidate | verdict | check 1: symptom on genuine; merged red | check 2: whole jar (genuine base + carrier) | check 3: callers | commit |
|---|---|---|---|---|---|
| NeoScreenEventsInjector + KernelScreenEvents | DELETE | gate-m9 Controlling PASS; merged red with `-Dforbric.neoScreenEvents=off` (only that line) | `Gui.setScreen` NEWs `ScreenEvent$Opening`/`$Closing`; 0 `net/minecraftforge` classes | m9 pack, PASS/FAIL names equal to the C1 tip | b019efc3 |
| NeoConversionPostInjector + KernelConversions | DELETE (merged red impossible: the repair rewrote uncaptured lambdas there) | gate-m42 drown.neo PASS; withhold control (phase 4, dbfd7ddc): exactly drown.neo fails | 10 `EventHooks.onLivingConvert` sites, all live, no other owner | m42 Post listener | 3044c801 |
| CMT #36 tooltip + KernelItemTooltips | DELETE (+ check 87ad9f38) | m9 smoke line heard 1/drawn 1; withheld 0/0 is the only diff; merged: claim stood down, 1/1 | one ItemTooltipEvent builder (`EventHooks.onItemTooltip`), one game caller | m9 pack, gate-m51 | 2b63bf22 |
| CMT #56 fuel + KernelFuelValues | DELETE (+ check 12d83bb2) | m45 fuel.neoEvent/neoEventFurnace/fabricInNeoEvent PASS; withhold exactly those 3 fail; merged without the claim: only the claim's own Forge-overload subject goes red | `FuelValues` has no platform call; burn time from `IItemStackExtension`/`EventHooks` | balm (m9), m45 Fabric fuel | 3f131ccd |
| CMT #30 routePlaceItemHookToNeoForge | DELETE | m44 positive 12/12, no ClassCastException; merged red without the claim: CCE at first placement | `useOn` calls `CommonHooks.onPlaceItemIntoWorld`; every BlockSnapshot cast is NeoForge's | every placement (m44, m9) | 87014cb1 |
| CMT #37 askNeoForgeWhatAnItemsAttributesAre | DELETE (merged red impossible: satisfied there too) | m23 elytra attribute reached the player; m9 tooltip attributes | both `forEachModifier` overloads call `getAttributeModifiers()` on both bases | m23, m9 pack | 6a1fcd6d |
| ItemUseOnInjector, post half (review B F1) | EDIT (relay half kept: Fabric-on-NeoForge) | m44 GREEN on genuine (positive 12/12, off exactly REPAIRED); merged red with only the post insertion withheld: exactly use.direct, use.neoCancel, use.place.neoAfter fail | UseOnScan: one ITEM_AFTER_BLOCK NEW (`ItemStack.useOn`); merged has none | fabric-events-interaction-v0 + m44 probe | 7af6a450 |

The plan's check 1 asks for a merged red. It could not be shown for NeoConversionPost, #36, #37 and #56 (the repair was
dead, stood down, satisfied, or covered only a MinecraftForge overload on the staged merged base); each commit says so
and the owner was asked to accept it.

Unit suite at 6a1fcd6d: 5826 tests, 118 failed, 0 new against the C1 tip's 124 (6 rows gone with their tests). After the
review round (7af6a450): 5827 tests, 116 failed, 0 new; the two ItemUseOnInjectorTest rows (merged-body premise) are gone.

## Commands

### Gate runner (`$C3/events-hooks/gate.sh`)

```bash
#!/bin/bash
# gate.sh <ro|wf> <label> <port> <gate-script> [VAR=val ...] — run one gate (or probe copy) on the merged (ro) or genuine (wf) stage.
set -u
D=$C3/events-hooks
tree=$1; label=$2; port=$3; gate=$4; shift 4
if [ "$tree" = ro ]; then
  cd $RO/forbric-kernel
  export FORBRIC_OLD=$MS
  export FORBRIC_NEOLIVE=$RO/forbric-kernel/run/canary/forbricneolive.jar
else
  cd $WF/forbric-kernel
  export FORBRIC_OLD=$GS
  export FORBRIC_NEOLIVE=$GS/run/neoforge-runtime/forbricneolive.jar
fi
export GATE_PORT=$port
unset MC_DIR MERGED FORGE_RT
for kv in "$@"; do export "$kv"; done
start=$(date +%s)
$MS/heavy.sh bash run/$gate > $D/gates/$label.out 2>&1
rc=$?
echo "$label $tree $gate rc=$rc secs=$(( $(date +%s) - start )) env: $*" >> $D/gates/summary.txt
exit $rc
```

`$C3/events-hooks-fix/gate.sh` is the same with its own output directory, a HEAD/dirty line per run, and
`FORBRIC_NEOLIVE` unset on the merged tree.

### Chains

Genuine stage, lane tip (`wf-chain.sh`; `$MAIN/forbric-kernel/run/client-merged-pack` cloned with `cp -c -R` per
client run):

```bash
#!/bin/bash
set -u
D=$C3/events-hooks
W=$WF/forbric-kernel
FIX=$MAIN/forbric-kernel/run/client-merged-pack
save() { rm -rf $D/gates/$1.d; cp -R $W/build/verification/$2 $D/gates/$1.d 2>/dev/null; }
echo "HEAD $(git -C $W rev-parse HEAD)" >> $D/gates/summary.txt
$D/gate.sh wf wf-m45 46105 gate-m45-everyday-actions.sh; save wf-m45 m45-everyday-actions
$D/gate.sh wf wf-m42 46106 c3eh-m42.sh; save wf-m42 m42-coremod-parity
$D/gate.sh wf wf-m44 46107 gate-m44-interaction-events.sh; save wf-m44 m44-interaction-events
$D/gate.sh wf wf-m51 46108 gate-m51-tooltips.sh; save wf-m51 m51-tooltips
for run in default withhold; do
  rm -rf $D/rundirs/wf-m9-$run; cp -c -R $FIX $D/rundirs/wf-m9-$run
  J=""; [ $run = withhold ] && J=-Dforbric.clientSmokeTooltipEvent=off
  $D/gate.sh wf wf-m9-$run 4611$([ $run = default ] && echo 0 || echo 1) gate-m9-client.sh M9_RUNDIR=$D/rundirs/wf-m9-$run M9_EXTRA_JVM=$J
  cp $W/build/gate-m9-client-boot.log $D/gates/wf-m9-$run.boot.log
done
rm -rf $D/rundirs/wf-m23; cp -c -R $FIX $D/rundirs/wf-m23
$D/gate.sh wf wf-m23 46112 gate-m23-elytra.sh M23_RUNDIR=$D/rundirs/wf-m23
cp $W/build/gate-m23-elytra.log $D/gates/wf-m23.boot.log
echo WFCHAIN-DONE >> $D/gates/summary.txt
```

Merged stage (`ro-chain1.sh`, then `ro-chain2.sh` with CMT #56's dispatch line commented out in `$RO`):

```bash
#!/bin/bash
set -u
D=$C3/events-hooks
R=$RO/forbric-kernel
FIX=$MAIN/forbric-kernel/run/client-merged-pack
save45() { rm -rf $D/gates/$1.d; cp -R $R/build/verification/m45-everyday-actions $D/gates/$1.d; }
$D/gate.sh ro ro-m45-positive 46100 c3eh-m45.sh C3EH_PHASE=positive; save45 ro-m45-positive
$D/gate.sh ro ro-m45-withhold 46101 c3eh-m45.sh C3EH_PHASE=withhold C3EH_JVM=-Dforbric.everydayWithholdFuelEvent=true; save45 ro-m45-withhold
for run in bridgeoff withhold; do
  rm -rf $D/rundirs/m9-$run; cp -c -R $FIX $D/rundirs/m9-$run
  case $run in bridgeoff) J=-Dforbric.itemTooltipBridge=off;; withhold) J=-Dforbric.clientSmokeTooltipEvent=off;; esac
  $D/gate.sh ro ro-m9-$run 4610$([ $run = bridgeoff ] && echo 2 || echo 3) gate-m9-client.sh M9_RUNDIR=$D/rundirs/m9-$run M9_EXTRA_JVM=$J
  cp $R/build/gate-m9-client-boot.log $D/gates/ro-m9-$run.boot.log
done
echo CHAIN1-DONE >> $D/gates/summary.txt
# ro-chain2.sh
$D/gate.sh ro ro-m45-noFuelClaim 46104 c3eh-m45.sh C3EH_PHASE=positive
rm -rf $D/gates/ro-m45-noFuelClaim.d; cp -R $R/build/verification/m45-everyday-actions $D/gates/ro-m45-noFuelClaim.d
echo CHAIN2-DONE >> $D/gates/summary.txt
```

Review round (`events-hooks-fix/wf-chain.sh`, then `ro-m44-noPost` on `$RO` with
`useOn.instructions.insert(neoForgePost());` commented out, then `wf-m44-final` after the gate-m44 edit):

```bash
#!/bin/bash
set -u
D=$C3/events-hooks-fix
W=$WF/forbric-kernel
save() { rm -rf $D/gates/$1.d; cp -R $W/build/verification/$2 $D/gates/$1.d 2>/dev/null; }
$D/gate.sh wf wf-m42 46101 c3fx-m42.sh; save wf-m42 m42-coremod-parity
$D/gate.sh wf wf-m44 46102 gate-m44-interaction-events.sh; save wf-m44 m44-interaction-events
$D/gate.sh wf wf-m45 46103 gate-m45-everyday-actions.sh; save wf-m45 m45-everyday-actions
echo WFCHAIN-DONE >> $D/gates/summary.txt
$D/gate.sh ro ro-m44-noPost 46104 gate-m44-interaction-events.sh
$D/gate.sh wf wf-m44-final 46105 gate-m44-interaction-events.sh
```

Throwaway probe copies (created in `run/`, run, deleted; never committed):
- `c3eh-m42.sh` / `c3fx-m42.sh`: gate-m42 with the case count 21 (the stock 22 is C5's) and only phase 1
  (`c3fx` also phase 4).
- `c3eh-m45.sh`: gate-m45 reduced to one phase picked by `C3EH_PHASE`, with extra JVM flags from `C3EH_JVM`, for the
  merged tree, which predates the withhold phase.

Per-commit compile (`compileJava compileRuntimeJava compileTestJava`), in a temporary detached worktree:

```bash
#!/bin/bash
set -u
D=$C3/events-hooks
T=$D/wt-compile
W=$WF
P=$(cat $GS/props.txt)
git -C $W worktree add -q --detach $T 73a4314c || exit 1
for c in $(git -C $W rev-list --reverse 73a4314c..HEAD); do
  git -C $T checkout -q --detach $c
  (cd $T/forbric-kernel && ./gradlew $P -q compileJava compileRuntimeJava compileTestJava > $D/percommit-$c.log 2>&1); echo "$c rc=$?"
done
git -C $W worktree remove --force $T
```

Full suite: `heavy.sh ./gradlew $(cat $GS/props.txt) -Pforbric.requireFixtures=all cleanTest test --continue`.

### Whole-jar scanners

ASM 9.10.1 from the Gradle cache (`$ASM` = `asm-9.10.1.jar:asm-tree-9.10.1.jar`):

```bash
javac -d cls -cp $ASM EhScan.java UseOnScan.java
java -cp cls:$ASM EhScan genuine $GS/run/neoforge-patched/patched-mc-neoforge-26.2.jar $GS/run/neoforge-runtime/neoforge-runtime.jar
java -cp cls:$ASM EhScan merged $MS/run/merged-base/patched-mc-merged-26.2.jar $MS/run/forge-runtime/forge-runtime.jar $MS/run/neoforge-runtime/neoforge-runtime.jar
java -cp cls:$ASM UseOnScan genuine <same two jars>      # and: UseOnScan merged <same three jars>
```

#### EhScan output, genuine

```text
# EhScan genuine: 13394 classes from $GS/run/neoforge-patched/patched-mc-neoforge-26.2.jar $GS/run/neoforge-runtime/neoforge-runtime.jar
## screen: methods that write a Screen-typed field 'screen' and whether each posts NeoForge's ScreenEvent Opening/Closing
  net/minecraft/client/MouseHandler$LastClick#<init>(JLnet/minecraft/client/gui/screens/Screen;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/Gui#setScreen(Lnet/minecraft/client/gui/screens/Screen;)V neoOpening=true neoClosing=true callsMinecraftForge=false
  net/minecraft/client/gui/Gui#pushScreenLayer(Lnet/minecraft/client/gui/screens/Screen;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/Gui#popScreenLayerInternal()V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/components/CommandSuggestions#<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/components/EditBox;Lnet/minecraft/client/gui/Font;ZZIIZI)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/components/OptionsList$Entry#<init>(Ljava/util/List;Lnet/minecraft/client/gui/screens/Screen;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/components/OptionsList$HeaderEntry#<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;I)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/layouts/HeaderAndFooterLayout#<init>(Lnet/minecraft/client/gui/screens/Screen;II)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/screens/worldselection/WorldSelectionList#<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;IILjava/lang/String;Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList;Ljava/util/function/Consumer;Ljava/util/function/Consumer;Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList$EntryType;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/screens/worldselection/WorldSelectionList$Builder#<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/Screen;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/screens/worldselection/WorldSelectionList$WorldListEntry#<init>(Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList;Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList;Lnet/minecraft/world/level/storage/LevelSummary;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  => 11 site(s), 10 without both NeoForge screen events
## conversion: calls of any onLivingConvert(LivingEntity,LivingEntity), owner and whether the caller is live (a lambda some handle names)
  net/minecraft/world/entity/animal/cow/MushroomCow#lambda$shear$1(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/animal/cow/Cow;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/animal/frog/Tadpole#lambda$ageUp$1(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/frog/Frog;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/animal/pig/Pig#lambda$thunderHit$1(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/zombie/ZombifiedPiglin;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/hoglin/Hoglin#lambda$finishConversion$0(Lnet/minecraft/world/entity/monster/Zoglin;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/piglin/AbstractPiglin#lambda$finishConversion$0(Lnet/minecraft/world/entity/monster/zombie/ZombifiedPiglin;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/skeleton/Skeleton#lambda$doFreezeConversion$1(Lnet/minecraft/world/entity/monster/skeleton/Stray;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/zombie/Zombie#lambda$convertVillagerToZombieVillager$0(Lnet/minecraft/world/entity/npc/villager/Villager;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/zombie/ZombieVillager;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/zombie/Zombie#lambda$convertToZombieType$0(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/zombie/Zombie;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/zombie/ZombieVillager#lambda$finishConversion$0(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/npc/villager/Villager#lambda$thunderHit$1(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/Witch;)V -> net/neoforged/neoforge/event/EventHooks live=true
  => NeoForge calls in live methods 10, NeoForge calls in dead lambdas 0, other owners 0
## tooltip: getTooltipLines bodies and their tooltip-event calls; every builder of NeoForge's ItemTooltipEvent and its callers
  net/minecraft/client/multiplayer/SessionSearchTrees#getTooltipLines(Ljava/util/stream/Stream;Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/stream/Stream; tooltip-event calls=[]
  net/minecraft/network/chat/HoverEvent$EntityTooltipInfo#getTooltipLines()Ljava/util/List; tooltip-event calls=[]
  net/minecraft/world/item/ItemStack#getTooltipLines(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List; tooltip-event calls=[net/neoforged/neoforge/event/EventHooks.onItemTooltip]
  builders of ItemTooltipEvent: [net/neoforged/neoforge/event/EventHooks#onItemTooltip(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;Ljava/util/List;Lnet/minecraft/world/item/TooltipFlag;Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;)Lnet/neoforged/neoforge/event/entity/player/ItemTooltipEvent;]
  callers of net/neoforged/neoforge/event/EventHooks#onItemTooltip(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;Ljava/util/List;Lnet/minecraft/world/item/TooltipFlag;Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;)Lnet/neoforged/neoforge/event/entity/player/ItemTooltipEvent; (1): [net/minecraft/world/item/ItemStack#getTooltipLines(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;]
## fuel: getItemBurnTime calls by owner; FuelValues methods with a platform call; callers of ItemStack.getBurnTime
  burn hook net/neoforged/neoforge/event/EventHooks <- net/neoforged/neoforge/common/extensions/IItemStackExtension#getBurnTime(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/level/block/entity/FuelValues;)I
  FuelValues methods: [<init>(Lit/unimi/dsi/fastutil/objects/Object2IntSortedMap;)V, isFuel(Lnet/minecraft/world/item/ItemStack;)Z, fuelItems()Ljava/util/SequencedSet;, burnDuration(Lnet/minecraft/world/item/ItemStack;)I, vanillaBurnTimes(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;)Lnet/minecraft/world/level/block/entity/FuelValues;, vanillaBurnTimes(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;I)Lnet/minecraft/world/level/block/entity/FuelValues;, vanillaBurnTimes(Lnet/minecraft/world/level/block/entity/FuelValues$Builder;I)Lnet/minecraft/world/level/block/entity/FuelValues;]
  callers of net/minecraft/world/item/ItemStack#getBurnTime(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/level/block/entity/FuelValues;)I (3): [net/minecraft/world/inventory/AbstractFurnaceMenu#isFuel(Lnet/minecraft/world/item/ItemStack;)Z, net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity#getBurnDuration(Lnet/minecraft/world/level/block/entity/FuelValues;Lnet/minecraft/world/item/ItemStack;)I, net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity#canPlaceItem(ILnet/minecraft/world/item/ItemStack;)Z]
  callers of net/minecraft/world/level/block/entity/FuelValues#burnDuration(Lnet/minecraft/world/item/ItemStack;)I (1): [net/neoforged/neoforge/common/extensions/IItemExtension#getBurnTime(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/level/block/entity/FuelValues;)I]
## placeItem: onPlaceItemIntoWorld calls by owner; BlockSnapshot types each class names; the capturedBlockSnapshots field type
  place hook net/neoforged/neoforge/common/CommonHooks <- net/minecraft/world/item/ItemStack#useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
  Level.capturedBlockSnapshots Ljava/util/ArrayList; Ljava/util/ArrayList<Lnet/neoforged/neoforge/common/util/BlockSnapshot;>;
  CHECKCASTs to a BlockSnapshot type: {net/neoforged/neoforge/common/util/BlockSnapshot=6}
## attributes: forEachModifier bodies; every raw DataComponents.ATTRIBUTE_MODIFIERS reader
  net/minecraft/world/item/ItemStack#forEachModifier(Lnet/minecraft/world/entity/EquipmentSlotGroup;Lorg/apache/commons/lang3/function/TriConsumer;)V asksGetAttributeModifiers=true readsRawComponent=false
  net/minecraft/world/item/ItemStack#forEachModifier(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V asksGetAttributeModifiers=true readsRawComponent=false
  net/minecraft/world/item/enchantment/EnchantmentHelper#forEachModifier(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlotGroup;Ljava/util/function/BiConsumer;)V asksGetAttributeModifiers=false readsRawComponent=false
  net/minecraft/world/item/enchantment/EnchantmentHelper#forEachModifier(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V asksGetAttributeModifiers=false readsRawComponent=false
  raw reader net/minecraft/core/component/DataComponents#<clinit>()V
  raw reader net/minecraft/core/component/predicates/AttributeModifiersPredicate#componentType()Lnet/minecraft/core/component/DataComponentType;
  raw reader net/minecraft/world/entity/Mob#getApproximateAttributeWith(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Holder;Lnet/minecraft/world/entity/EquipmentSlot;)D
  raw reader net/minecraft/world/item/Item$Properties#attributes(Lnet/minecraft/world/item/component/ItemAttributeModifiers;)Lnet/minecraft/world/item/Item$Properties;
  raw reader net/minecraft/world/item/ItemStack#addAttributeTooltips(Ljava/util/function/Consumer;Lnet/minecraft/world/item/component/TooltipDisplay;Lnet/minecraft/world/entity/player/Player;)V
  raw reader net/minecraft/world/level/storage/loot/functions/SetAttributesFunction#run(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/storage/loot/LootContext;)Lnet/minecraft/world/item/ItemStack;
  raw reader net/minecraft/world/waypoints/Waypoint#addHideAttribute(Lnet/minecraft/world/item/Item$Properties;)Lnet/minecraft/world/item/Item$Properties;
  raw reader net/neoforged/neoforge/common/extensions/IItemStackExtension#getAttributeModifiers()Lnet/minecraft/world/item/component/ItemAttributeModifiers;
  raw reader net/neoforged/neoforge/common/tooltip/VanillaDataComponentTooltips#collectVanillaAppenders()Ljava/util/SequencedMap;
  raw reader net/neoforged/neoforge/common/util/AttributeUtil#addAttributeTooltips(Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Consumer;Lnet/minecraft/world/item/component/TooltipDisplay;Lnet/neoforged/neoforge/common/util/AttributeTooltipContext;)V
  raw reader net/neoforged/neoforge/event/AddAttributeTooltipsEvent#shouldShow()Z
## classes naming net/minecraftforge/ anywhere: 0
```

#### EhScan output, merged

```text
# EhScan merged: 15372 classes from $MS/run/merged-base/patched-mc-merged-26.2.jar $MS/run/forge-runtime/forge-runtime.jar $MS/run/neoforge-runtime/neoforge-runtime.jar
## screen: methods that write a Screen-typed field 'screen' and whether each posts NeoForge's ScreenEvent Opening/Closing
  net/minecraft/client/MouseHandler$LastClick#<init>(JLnet/minecraft/client/gui/screens/Screen;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/Gui#setScreen(Lnet/minecraft/client/gui/screens/Screen;)V neoOpening=false neoClosing=false callsMinecraftForge=true
  net/minecraft/client/gui/Gui#setScreenInternal(Lnet/minecraft/client/gui/screens/Screen;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/Gui#pushScreenLayer(Lnet/minecraft/client/gui/screens/Screen;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/Gui#popScreenLayerInternal()V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/components/CommandSuggestions#<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/components/EditBox;Lnet/minecraft/client/gui/Font;ZZIIZI)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/components/OptionsList$Entry#<init>(Ljava/util/List;Lnet/minecraft/client/gui/screens/Screen;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/components/OptionsList$HeaderEntry#<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;I)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/layouts/HeaderAndFooterLayout#<init>(Lnet/minecraft/client/gui/screens/Screen;II)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/screens/worldselection/WorldSelectionList#<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;IILjava/lang/String;Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList;Ljava/util/function/Consumer;Ljava/util/function/Consumer;Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList$EntryType;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/screens/worldselection/WorldSelectionList$Builder#<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/Screen;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  net/minecraft/client/gui/screens/worldselection/WorldSelectionList$WorldListEntry#<init>(Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList;Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList;Lnet/minecraft/world/level/storage/LevelSummary;)V neoOpening=false neoClosing=false callsMinecraftForge=false
  => 12 site(s), 12 without both NeoForge screen events
## conversion: calls of any onLivingConvert(LivingEntity,LivingEntity), owner and whether the caller is live (a lambda some handle names)
  net/minecraft/world/entity/animal/cow/MushroomCow#lambda$shear$1(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/animal/cow/Cow;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/animal/cow/MushroomCow#lambda$shear$1(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/animal/cow/Cow;)V -> net/minecraftforge/event/ForgeEventFactory live=true
  net/minecraft/world/entity/animal/frog/Tadpole#lambda$ageUp$1(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/frog/Frog;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/animal/pig/Pig#lambda$thunderHit$1(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/zombie/ZombifiedPiglin;)V -> net/minecraftforge/event/ForgeEventFactory live=true
  net/minecraft/world/entity/animal/pig/Pig#lambda$thunderHit$1(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/zombie/ZombifiedPiglin;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/hoglin/Hoglin#lambda$finishConversion$0(Lnet/minecraft/world/entity/monster/Zoglin;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/piglin/AbstractPiglin#lambda$finishConversion$0(Lnet/minecraft/world/entity/monster/zombie/ZombifiedPiglin;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/skeleton/Skeleton#lambda$doFreezeConversion$1(Lnet/minecraft/world/entity/monster/skeleton/Stray;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/zombie/Zombie#lambda$convertVillagerToZombieVillager$0(Lnet/minecraft/world/entity/npc/villager/Villager;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/zombie/ZombieVillager;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/zombie/Zombie#lambda$convertToZombieType$0(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/zombie/Zombie;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/monster/zombie/Zombie#lambda$convertVillagerToZombieVillager$1(Lnet/minecraft/world/entity/npc/villager/Villager;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/zombie/ZombieVillager;)V -> net/minecraftforge/event/ForgeEventFactory live=false
  net/minecraft/world/entity/monster/zombie/Zombie#lambda$convertToZombieType$1(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/zombie/Zombie;)V -> net/minecraftforge/event/ForgeEventFactory live=false
  net/minecraft/world/entity/monster/zombie/ZombieVillager#lambda$finishConversion$0(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;)V -> net/minecraftforge/event/ForgeEventFactory live=true
  net/minecraft/world/entity/monster/zombie/ZombieVillager#lambda$finishConversion$0(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;)V -> net/neoforged/neoforge/event/EventHooks live=true
  net/minecraft/world/entity/npc/villager/Villager#lambda$thunderHit$1(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/Witch;)V -> net/neoforged/neoforge/event/EventHooks live=true
  => NeoForge calls in live methods 10, NeoForge calls in dead lambdas 0, other owners 5
## tooltip: getTooltipLines bodies and their tooltip-event calls; every builder of NeoForge's ItemTooltipEvent and its callers
  net/minecraft/client/multiplayer/SessionSearchTrees#getTooltipLines(Ljava/util/stream/Stream;Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/stream/Stream; tooltip-event calls=[]
  net/minecraft/network/chat/HoverEvent$EntityTooltipInfo#getTooltipLines()Ljava/util/List; tooltip-event calls=[]
  net/minecraft/world/item/ItemStack#getTooltipLines(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List; tooltip-event calls=[net/minecraftforge/event/ForgeEventFactory.onItemTooltip, net/neoforged/neoforge/event/EventHooks.onItemTooltip]
  builders of ItemTooltipEvent: [net/neoforged/neoforge/event/EventHooks#onItemTooltip(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;Ljava/util/List;Lnet/minecraft/world/item/TooltipFlag;Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;)Lnet/neoforged/neoforge/event/entity/player/ItemTooltipEvent;]
  callers of net/neoforged/neoforge/event/EventHooks#onItemTooltip(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;Ljava/util/List;Lnet/minecraft/world/item/TooltipFlag;Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;)Lnet/neoforged/neoforge/event/entity/player/ItemTooltipEvent; (1): [net/minecraft/world/item/ItemStack#getTooltipLines(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;]
## fuel: getItemBurnTime calls by owner; FuelValues methods with a platform call; callers of ItemStack.getBurnTime
  burn hook net/minecraftforge/event/ForgeEventFactory <- net/minecraft/world/level/block/entity/FuelValues#burnDuration(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;)I
  burn hook net/neoforged/neoforge/event/EventHooks <- net/neoforged/neoforge/common/extensions/IItemStackExtension#getBurnTime(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/level/block/entity/FuelValues;)I
  FuelValues platform call in burnDuration(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;)I
  FuelValues methods: [<init>(Lit/unimi/dsi/fastutil/objects/Object2IntSortedMap;)V, isFuel(Lnet/minecraft/world/item/ItemStack;)Z, fuelItems()Ljava/util/SequencedSet;, burnDuration(Lnet/minecraft/world/item/ItemStack;)I, vanillaBurnTimes(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;)Lnet/minecraft/world/level/block/entity/FuelValues;, vanillaBurnTimes(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;I)Lnet/minecraft/world/level/block/entity/FuelValues;, vanillaBurnTimes(Lnet/minecraft/world/level/block/entity/FuelValues$Builder;I)Lnet/minecraft/world/level/block/entity/FuelValues;, isFuel(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;)Z, burnDuration(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;)I]
  callers of net/minecraft/world/item/ItemStack#getBurnTime(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/level/block/entity/FuelValues;)I (3): [net/minecraft/world/inventory/AbstractFurnaceMenu#isFuel(Lnet/minecraft/world/item/ItemStack;)Z, net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity#getBurnDuration(Lnet/minecraft/world/level/block/entity/FuelValues;Lnet/minecraft/world/item/ItemStack;)I, net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity#canPlaceItem(ILnet/minecraft/world/item/ItemStack;)Z]
  callers of net/minecraft/world/level/block/entity/FuelValues#burnDuration(Lnet/minecraft/world/item/ItemStack;)I (1): [net/neoforged/neoforge/common/extensions/IItemExtension#getBurnTime(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/level/block/entity/FuelValues;)I]
## placeItem: onPlaceItemIntoWorld calls by owner; BlockSnapshot types each class names; the capturedBlockSnapshots field type
  place hook net/minecraftforge/common/ForgeHooks <- net/minecraft/world/item/ItemStack#useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
  Level.capturedBlockSnapshots Ljava/util/ArrayList; Ljava/util/ArrayList<Lnet/neoforged/neoforge/common/util/BlockSnapshot;>;
  CHECKCASTs to a BlockSnapshot type: {net/minecraftforge/common/util/BlockSnapshot=6, net/neoforged/neoforge/common/util/BlockSnapshot=6}
## attributes: forEachModifier bodies; every raw DataComponents.ATTRIBUTE_MODIFIERS reader
  net/minecraft/world/item/ItemStack#forEachModifier(Lnet/minecraft/world/entity/EquipmentSlotGroup;Lorg/apache/commons/lang3/function/TriConsumer;)V asksGetAttributeModifiers=true readsRawComponent=false
  net/minecraft/world/item/ItemStack#forEachModifier(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V asksGetAttributeModifiers=true readsRawComponent=false
  net/minecraft/world/item/enchantment/EnchantmentHelper#forEachModifier(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlotGroup;Ljava/util/function/BiConsumer;)V asksGetAttributeModifiers=false readsRawComponent=false
  net/minecraft/world/item/enchantment/EnchantmentHelper#forEachModifier(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V asksGetAttributeModifiers=false readsRawComponent=false
  raw reader net/minecraft/core/component/DataComponents#<clinit>()V
  raw reader net/minecraft/core/component/predicates/AttributeModifiersPredicate#componentType()Lnet/minecraft/core/component/DataComponentType;
  raw reader net/minecraft/world/entity/Mob#getApproximateAttributeWith(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Holder;Lnet/minecraft/world/entity/EquipmentSlot;)D
  raw reader net/minecraft/world/item/Item$Properties#attributes(Lnet/minecraft/world/item/component/ItemAttributeModifiers;)Lnet/minecraft/world/item/Item$Properties;
  raw reader net/minecraft/world/item/ItemStack#addAttributeTooltips(Ljava/util/function/Consumer;Lnet/minecraft/world/item/component/TooltipDisplay;Lnet/minecraft/world/entity/player/Player;)V
  raw reader net/minecraft/world/level/storage/loot/functions/SetAttributesFunction#run(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/storage/loot/LootContext;)Lnet/minecraft/world/item/ItemStack;
  raw reader net/minecraft/world/waypoints/Waypoint#addHideAttribute(Lnet/minecraft/world/item/Item$Properties;)Lnet/minecraft/world/item/Item$Properties;
  raw reader net/neoforged/neoforge/common/extensions/IItemStackExtension#getAttributeModifiers()Lnet/minecraft/world/item/component/ItemAttributeModifiers;
  raw reader net/neoforged/neoforge/common/tooltip/VanillaDataComponentTooltips#collectVanillaAppenders()Ljava/util/SequencedMap;
  raw reader net/neoforged/neoforge/common/util/AttributeUtil#addAttributeTooltips(Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Consumer;Lnet/minecraft/world/item/component/TooltipDisplay;Lnet/neoforged/neoforge/common/util/AttributeTooltipContext;)V
  raw reader net/neoforged/neoforge/event/AddAttributeTooltipsEvent#shouldShow()Z
## classes naming net/minecraftforge/ anywhere: 1971
```

#### UseOnScan output, genuine and merged

```text
# UseOnScan genuine: 13394 classes
## every NEW UseItemOnBlockEvent, with the phase it is built for
  net/minecraft/world/item/ItemStack#useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult; -> ITEM_AFTER_BLOCK
  net/minecraft/world/item/ItemStack#onItemUseFirst(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult; -> ITEM_BEFORE_BLOCK
  net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase#useItemOn(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult; -> BLOCK
## use-on paths (Item.useOn or onPlaceItemIntoWorld callers, lambdas left out) with no NEW UseItemOnBlockEvent
  net/minecraft/world/item/CompassItem#useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
  net/minecraft/world/item/MapItem#useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
  net/minecraft/world/item/ShearsItem#useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
  net/neoforged/neoforge/common/CommonHooks#onPlaceItemIntoWorld(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
## net/minecraftforge references: 0 classes

# UseOnScan merged: 15372 classes
## every NEW UseItemOnBlockEvent, with the phase it is built for
  net/minecraft/world/item/ItemStack#onItemUseFirst(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult; -> ITEM_BEFORE_BLOCK
  net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase#useItemOn(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult; -> BLOCK
## use-on paths (Item.useOn or onPlaceItemIntoWorld callers, lambdas left out) with no NEW UseItemOnBlockEvent
  net/minecraft/world/item/CompassItem#useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
  net/minecraft/world/item/ItemStack#useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
  net/minecraft/world/item/MapItem#useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
  net/minecraft/world/item/ShearsItem#useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
  net/minecraftforge/common/ForgeHooks#onPlaceItemIntoWorld(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
  net/neoforged/neoforge/common/CommonHooks#onPlaceItemIntoWorld(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;
## net/minecraftforge references: 1852 classes
```

#### EhScan.java

```java
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Whole-jar search (C3 check 2) for the six events-hooks repairs, each by its own defect criterion, over the jars given.
 * Usage: java EhScan <label> <jar>...   Prints one section per repair.
 */
public class EhScan {
	static final Map<String, ClassNode> classes = new TreeMap<>();

	public static void main(String[] args) throws Exception {
		String label = args[0];
		for (int i = 1; i < args.length; i++) read(args[i]);
		System.out.println("# EhScan " + label + ": " + classes.size() + " classes from " + String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
		screen(); conversion(); tooltip(); fuel(); placeItem(); attributes(); forgeRefs();
	}

	static void read(String jar) throws IOException {
		try (ZipFile z = new ZipFile(jar)) {
			for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
				ZipEntry entry = e.nextElement();
				if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
				ClassNode node = new ClassNode();
				try (InputStream in = z.getInputStream(entry)) { new ClassReader(in).accept(node, 0); }
				classes.putIfAbsent(node.name, node);
			}
		}
	}

	interface InsnTest { boolean test(AbstractInsnNode insn); }
	static boolean has(MethodNode m, InsnTest t) {
		if (m.instructions == null) return false;
		for (AbstractInsnNode i : m.instructions) if (t.test(i)) return true;
		return false;
	}
	static boolean calls(AbstractInsnNode i, String owner, String name) {
		return i instanceof MethodInsnNode c && (owner == null || c.owner.equals(owner)) && c.name.equals(name);
	}
	static boolean news(AbstractInsnNode i, String type) { return i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals(type); }
	static String id(ClassNode c, MethodNode m) { return c.name + "#" + m.name + m.desc; }
	/** Lambda/method names referenced by an invokedynamic handle or a method handle constant anywhere in the class. */
	static Set<String> handleTargets(ClassNode c) {
		Set<String> out = new HashSet<>();
		for (MethodNode m : c.methods) if (m.instructions != null) for (AbstractInsnNode i : m.instructions) {
			if (i instanceof InvokeDynamicInsnNode indy) for (Object a : indy.bsmArgs) if (a instanceof Handle h && h.getOwner().equals(c.name)) out.add(h.getName() + h.getDesc());
			if (i instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h && h.getOwner().equals(c.name)) out.add(h.getName() + h.getDesc());
		}
		return out;
	}
	static boolean live(ClassNode c, MethodNode m) {
		if (!m.name.startsWith("lambda$")) return true;
		return handleTargets(c).contains(m.name + m.desc);
	}

	// 1. NeoScreenEventsInjector: a method that installs a new screen (writes a Screen-typed field named "screen") must
	//    post NeoForge's ScreenEvent.Opening and .Closing (NEW, or a NeoForge hook that builds them).
	static void screen() {
		System.out.println("## screen: methods that write a Screen-typed field 'screen' and whether each posts NeoForge's ScreenEvent Opening/Closing");
		String opening = "net/neoforged/neoforge/client/event/ScreenEvent$Opening", closing = "net/neoforged/neoforge/client/event/ScreenEvent$Closing";
		int sites = 0, missing = 0;
		for (ClassNode c : classes.values()) for (MethodNode m : c.methods) {
			if (!has(m, i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("screen")
					&& f.desc.equals("Lnet/minecraft/client/gui/screens/Screen;"))) continue;
			if (!c.name.startsWith("net/minecraft/")) continue;
			sites++;
			boolean o = has(m, i -> news(i, opening) || i instanceof MethodInsnNode k && k.owner.startsWith("net/neoforged/") && k.desc.contains(opening));
			boolean cl = has(m, i -> news(i, closing) || i instanceof MethodInsnNode k && k.owner.startsWith("net/neoforged/") && k.desc.contains(closing));
			boolean forge = has(m, i -> i instanceof MethodInsnNode k && k.owner.startsWith("net/minecraftforge/"));
			if (!(o && cl)) missing++;
			System.out.println("  " + id(c, m) + " neoOpening=" + o + " neoClosing=" + cl + " callsMinecraftForge=" + forge);
		}
		System.out.println("  => " + sites + " site(s), " + missing + " without both NeoForge screen events");
	}

	// 2. NeoConversionPostInjector: every conversion-Post hook call (any owner's onLivingConvert) sits in a live method,
	//    and asks NeoForge's EventHooks.
	static void conversion() {
		System.out.println("## conversion: calls of any onLivingConvert(LivingEntity,LivingEntity), owner and whether the caller is live (a lambda some handle names)");
		int neoLive = 0, neoDead = 0, other = 0;
		for (ClassNode c : classes.values()) for (MethodNode m : c.methods) {
			if (m.instructions == null) continue;
			for (AbstractInsnNode i : m.instructions) {
				if (!(i instanceof MethodInsnNode k) || !k.name.equals("onLivingConvert")) continue;
				if (k.owner.equals(c.name)) continue; // the hook's own body
				boolean neo = k.owner.equals("net/neoforged/neoforge/event/EventHooks");
				boolean l = live(c, m);
				if (neo && l) neoLive++; else if (neo) neoDead++; else other++;
				System.out.println("  " + id(c, m) + " -> " + k.owner + " live=" + l);
			}
		}
		System.out.println("  => NeoForge calls in live methods " + neoLive + ", NeoForge calls in dead lambdas " + neoDead + ", other owners " + other);
	}

	// 3. Tooltip claim: every getTooltipLines that posts a tooltip event posts NeoForge's ItemTooltipEvent; and who builds it.
	static void tooltip() {
		System.out.println("## tooltip: getTooltipLines bodies and their tooltip-event calls; every builder of NeoForge's ItemTooltipEvent and its callers");
		String ev = "net/neoforged/neoforge/event/entity/player/ItemTooltipEvent";
		for (ClassNode c : classes.values()) for (MethodNode m : c.methods) {
			if (!m.name.equals("getTooltipLines") || !c.name.startsWith("net/minecraft/") || m.instructions == null) continue;
			List<String> calls = new ArrayList<>();
			for (AbstractInsnNode i : m.instructions) if (i instanceof MethodInsnNode k && k.name.equals("onItemTooltip")) calls.add(k.owner + "." + k.name);
			System.out.println("  " + id(c, m) + " tooltip-event calls=" + calls);
		}
		Set<String> builders = new TreeSet<>();
		for (ClassNode c : classes.values()) for (MethodNode m : c.methods) if (has(m, i -> news(i, ev)) && !c.name.equals(ev)) builders.add(c.name + "#" + m.name + m.desc);
		System.out.println("  builders of ItemTooltipEvent: " + builders);
		for (String b : builders) callers(b);
	}

	// 4. Fuel claim: every burn-time hook call site, by owner; every question a furnace asks; and whether each FuelValues
	//    method that asks a hook asks NeoForge's.
	static void fuel() {
		System.out.println("## fuel: getItemBurnTime calls by owner; FuelValues methods with a platform call; callers of ItemStack.getBurnTime");
		for (ClassNode c : classes.values()) for (MethodNode m : c.methods) {
			if (m.instructions == null) continue;
			for (AbstractInsnNode i : m.instructions) if (i instanceof MethodInsnNode k && k.name.equals("getItemBurnTime") && !k.owner.equals(c.name))
				System.out.println("  burn hook " + k.owner + " <- " + id(c, m));
		}
		ClassNode fv = classes.get("net/minecraft/world/level/block/entity/FuelValues");
		if (fv != null) for (MethodNode m : fv.methods) {
			boolean platform = has(m, i -> i instanceof MethodInsnNode k && (k.owner.startsWith("net/neoforged/") || k.owner.startsWith("net/minecraftforge/")));
			if (platform) System.out.println("  FuelValues platform call in " + m.name + m.desc);
		}
		System.out.println("  FuelValues methods: " + (fv == null ? "absent" : fv.methods.stream().map(m -> m.name + m.desc).toList()));
		callers("net/minecraft/world/item/ItemStack#getBurnTime(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/level/block/entity/FuelValues;)I");
		callers("net/minecraft/world/level/block/entity/FuelValues#burnDuration(Lnet/minecraft/world/item/ItemStack;)I");
	}

	// 5. Place-item claim: every onPlaceItemIntoWorld call by owner, and the element type every reader of
	//    Level.capturedBlockSnapshots casts to (producer and consumer must agree).
	static void placeItem() {
		System.out.println("## placeItem: onPlaceItemIntoWorld calls by owner; BlockSnapshot types each class names; the capturedBlockSnapshots field type");
		for (ClassNode c : classes.values()) for (MethodNode m : c.methods) {
			if (m.instructions == null) continue;
			for (AbstractInsnNode i : m.instructions) if (i instanceof MethodInsnNode k && k.name.equals("onPlaceItemIntoWorld") && !k.owner.equals(c.name))
				System.out.println("  place hook " + k.owner + " <- " + id(c, m));
		}
		ClassNode level = classes.get("net/minecraft/world/level/Level");
		if (level != null) for (FieldNode f : level.fields) if (f.name.equals("capturedBlockSnapshots")) System.out.println("  Level.capturedBlockSnapshots " + f.desc + " " + f.signature);
		Map<String, Integer> snapshotTypes = new TreeMap<>();
		for (ClassNode c : classes.values()) for (MethodNode m : c.methods) if (m.instructions != null) for (AbstractInsnNode i : m.instructions)
			if (i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.CHECKCAST && t.desc.endsWith("/BlockSnapshot")) snapshotTypes.merge(t.desc, 1, Integer::sum);
		System.out.println("  CHECKCASTs to a BlockSnapshot type: " + snapshotTypes);
	}

	// 6. Attributes claim: every reader of the raw ATTRIBUTE_MODIFIERS component, and whether each forEachModifier asks
	//    ItemStack.getAttributeModifiers() (NeoForge's computed answer, which posts ItemAttributeModifierEvent).
	static void attributes() {
		System.out.println("## attributes: forEachModifier bodies; every raw DataComponents.ATTRIBUTE_MODIFIERS reader");
		for (ClassNode c : classes.values()) for (MethodNode m : c.methods) {
			if (!m.name.equals("forEachModifier") || m.instructions == null) continue;
			boolean asks = has(m, i -> calls(i, null, "getAttributeModifiers"));
			boolean raw = has(m, i -> i instanceof FieldInsnNode f && f.owner.equals("net/minecraft/core/component/DataComponents") && f.name.equals("ATTRIBUTE_MODIFIERS"));
			System.out.println("  " + id(c, m) + " asksGetAttributeModifiers=" + asks + " readsRawComponent=" + raw);
		}
		for (ClassNode c : classes.values()) for (MethodNode m : c.methods)
			if (has(m, i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals("net/minecraft/core/component/DataComponents") && f.name.equals("ATTRIBUTE_MODIFIERS")))
				System.out.println("  raw reader " + id(c, m));
	}

	static void forgeRefs() {
		int n = 0;
		for (ClassNode c : classes.values()) {
			StringWriter sw = new StringWriter();
			ClassWriter w = new ClassWriter(0); c.accept(w);
			if (new String(w.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("net/minecraftforge/")) n++;
		}
		System.out.println("## classes naming net/minecraftforge/ anywhere: " + n);
	}

	static void callers(String target) {
		String owner = target.substring(0, target.indexOf('#')), rest = target.substring(target.indexOf('#') + 1);
		String name = rest.substring(0, rest.indexOf('(')), desc = rest.substring(rest.indexOf('('));
		List<String> out = new ArrayList<>();
		for (ClassNode c : classes.values()) for (MethodNode m : c.methods) if (m.instructions != null) for (AbstractInsnNode i : m.instructions)
			if (i instanceof MethodInsnNode k && k.name.equals(name) && k.desc.equals(desc) && (k.owner.equals(owner) || owner.endsWith("ItemStack") && k.owner.endsWith("ItemStack")))
				out.add(id(c, m) + (live(c, m) ? "" : " [dead lambda]"));
		System.out.println("  callers of " + target + " (" + out.size() + "): " + out);
	}
}
```

#### UseOnScan.java

```java
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Whole-jar search (C3 check 2) for ItemUseOnInjector's merged-only half ("put NeoForge's ITEM_AFTER_BLOCK post back into
 * a useOn body that lacks it"), by its own defect criterion: a game method that runs an item's use-on path (calls
 * Item.useOn, or any family's onPlaceItemIntoWorld, or an ItemStack.onItemUse) without a NEW of NeoForge's
 * UseItemOnBlockEvent, versus where each phase IS posted. Usage: java UseOnScan <label> <jar>...
 */
public class UseOnScan {
	static final String EVENT = "net/neoforged/neoforge/event/entity/player/UseItemOnBlockEvent";
	static final Map<String, ClassNode> classes = new TreeMap<>();

	public static void main(String[] args) throws Exception {
		for (int i = 1; i < args.length; i++) read(args[i]);
		System.out.println("# UseOnScan " + args[0] + ": " + classes.size() + " classes");
		System.out.println("## every NEW UseItemOnBlockEvent, with the phase it is built for");
		List<String> unposted = new ArrayList<>();
		for (ClassNode c : classes.values()) {
			for (MethodNode m : c.methods) {
				boolean posts = false, usePath = false;
				for (AbstractInsnNode insn : m.instructions) {
					if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals(EVENT)) {
						posts = true;
						String phase = "?";
						for (AbstractInsnNode n = insn.getNext(); n != null; n = n.getNext()) {
							if (n instanceof FieldInsnNode f && f.owner.equals(EVENT + "$UsePhase")) { phase = f.name; break; }
							if (n instanceof MethodInsnNode mi && mi.owner.equals(EVENT) && mi.name.equals("<init>")) break;
						}
						System.out.println("  " + c.name + "#" + m.name + m.desc + " -> " + phase);
					}
					if (insn instanceof MethodInsnNode call) {
						if (call.owner.equals("net/minecraft/world/item/Item") && call.name.equals("useOn")) usePath = true;
						if (call.name.equals("onPlaceItemIntoWorld")) usePath = true;
					}
				}
				if (usePath && !posts && !m.name.startsWith("lambda$")) unposted.add(c.name + "#" + m.name + m.desc);
			}
		}
		System.out.println("## use-on paths (Item.useOn or onPlaceItemIntoWorld callers, lambdas left out) with no NEW UseItemOnBlockEvent");
		for (String u : unposted) System.out.println("  " + u);
		System.out.println("## net/minecraftforge references: " + classes.values().stream().filter(c -> refsForge(c)).count() + " classes");
	}

	static boolean refsForge(ClassNode c) {
		if (c.name.startsWith("net/minecraftforge/")) return true;
		for (MethodNode m : c.methods) for (AbstractInsnNode i : m.instructions)
			if (i instanceof MethodInsnNode mi && mi.owner.startsWith("net/minecraftforge/")) return true;
		return false;
	}

	static void read(String jar) throws IOException {
		try (ZipFile z = new ZipFile(jar)) {
			for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
				ZipEntry entry = e.nextElement();
				if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
				ClassNode node = new ClassNode();
				try (InputStream in = z.getInputStream(entry)) { new ClassReader(in).accept(node, 0); }
				classes.putIfAbsent(node.name, node);
			}
		}
	}
}
```
