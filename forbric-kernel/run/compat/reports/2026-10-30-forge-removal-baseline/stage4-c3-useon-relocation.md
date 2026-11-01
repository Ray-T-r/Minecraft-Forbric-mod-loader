# Stage 4 C3, lane "c3b-useon-relocation": how the two use-on relocation adapters were checked

Branch `s4-c3`, commits 00eef69c and 8114f55a on a3791619, review fixes 180b68f3 and the commit that adds this file. The
lane took the two Mixin adapters that move a Fabric mod's injector off vanilla's `Item.useOn` call in `ItemStack.useOn`:
`MixinRelocatedCall` (a `@WrapOperation` on that call, fabric-api's `ItemEvents.USE_ON`) and
`MixinPlacementTransactionAdapter` (an `@Inject` at that call or at a later point, Create Fly's placement hooks). Both
were written for the merged base; neither was in the C3 work list (`c3-repairs.md`), so the orchestrator assigned them
after the critic pass. Both are KEEP: NeoForge's own `useOn` also makes the call outside its body, so both are
Fabric-on-NeoForge adaptations under the guiding rule. Nothing was deleted, so no switch was retired and no
registration, census or claim row changed. This file keeps the probe, the scanners and the measured outcomes, which
otherwise lived only in a session scratchpad.

Names: `$GS` genuine stage (`run/neoforge-patched/patched-mc-neoforge-26.2.jar` + `run/neoforge-runtime/neoforge-runtime.jar`
+ `run/neoforge-runtime/forbricneolive.jar`, G below, with the FABRIC sidecar
`run/vanilla-reference/vanilla-reference-26.2.jar`), `$WF` the `s4-c3` worktree's `forbric-kernel`, `$LANE` the lane's
scratch directory (not kept). Heavy commands went through `.stage/heavy.sh`; `MC_DIR` was never set; ports 46250-46269.

## Results

| candidate | verdict | check 1: symptom on G with the repair off | check 2: whole jar (G + carrier) | check 3: callers on G | commit |
|---|---|---|---|---|---|
| MixinRelocatedCall | KEEP (javadoc, log line, ForbricMixinService comment) | probe phase `relocOff` (`-Dforbric.mixinRelocatedCall=off` only): exactly `use.place.fabric`, `use.place.neoAfter`, `use.fabricOverrides`, `use.direct` fail (Fabric's USE_ON never fires; events `[neo:ITEM_BEFORE_BLOCK, neo:BLOCK, neo:ITEM_AFTER_BLOCK]`), "injection warning: mod=fabric-events-interaction-v0 ... handler=handleUseOnEvent", report SUSPECTED. `positive`: 12/12 and "handleUseOnEvent now wraps Item.useOn in forbric$useOnItem" | G `ItemStack.useOn`: posts `UseItemOnBlockEvent` ITEM_AFTER_BLOCK, then server `invokestatic CommonHooks.onPlaceItemIntoWorld` @46 / `areturn` @49, client `onItemUse(ctx, lambda$useOn$0)` @59 / `areturn` @62; no `Item.useOn` call. Vanilla makes it @57. Callers of `Item.useOn(UseOnContext)`: G `ItemStack.lambda$useOn$0`, `CommonHooks.onPlaceItemIntoWorld` @115, super calls in `CompassItem`/`MapItem`/`ShearsItem.useOn`; vanilla `ItemStack.useOn` @57 and the same three super calls. No method-handle reference; `forbricneolive.jar` none. The defect sits where the relocation points: no MOVE | `@Mixin` classes naming `Item.useOn` with `ItemStack`: the m9 pack (97 jars) has only fabric-events-interaction-v0's `ItemStackMixin`; across every fixture root, that mixin in each fabric-api copy (0.149, 0.152.1, 0.155.2, 0.161, worldedit's and PrideTags' nested copies) plus Create Fly. gate-m44 GREEN at 8114f55a; m9 lines unchanged with the adapter off (m9 asserts no use-on) | 00eef69c |
| MixinPlacementTransactionAdapter | KEEP (javadoc/comments; placementsingle weave fixture's `ForgeHooks` stand-in became `CommonHooks`) | probe phases with Create Fly and `-Dmixin.debug.export=true`. `create`: 12/12, 0 Create injection warnings, exported `useOn` has 10 references / 6 invocations of `create$...` handlers (`create$cacheState`, `create$useOnBlock$forbricReach`, `create$useOnBlock`). `createOff` (`-Dforbric.carrierCallbackAdapters=off`, the group of six): 2 references (declarations) / 0 invocations, warnings for `cacheState`, `useOnBlock` and `handleUpdateTag`. Isolated (review fix, throwaway build with only `MixinCarrierCallbackAdapters.placement` returning 0, reverted): 2 / 0, warnings for exactly `cacheState` and `useOnBlock`, both SUSPECTED, Create's `ItemStackMixin` "2/4 anchors resolve, missing: @At(INVOKE) Item.useOn in ItemStack.useOn, @At(INVOKE) InteractionResult$Success.wasItemInteraction in ItemStack.useOn" | as above: `CommonHooks.onPlaceItemIntoWorld` makes the one server-side `Item.useOn` call (@115); the adapter finds it by reachability from `useOn` (`MixinNativeReplay.reaches`, depth 3), not by owner name | the only `@Inject` subject at that call in all fixtures is Create Fly's `com.zurrtum.create.mixin.ItemStackMixin` (not in the m9 pack). Create phase 12/12; unit `CreateInjectionAdaptersTest`, `Placement*WeaveTest`, `MixinPlacementTransactionGeneralTest` pass on G | 8114f55a |

The at-tip re-run by review A (8114f55a, port 46250) reproduced all four phases with the new log text. Unit tests on G:
the lane's targeted set 377/0 at 8114f55a, 383/0 at 180b68f3 (with `TransformerAnchorCensusTest`). Full suite at 8114f55a:
5749 tests, 65 failed, names identical to the a3791619 baseline.

## Not covered by these adapters (measured by bytecode, not at run time)

A Fabric `@Inject(method = "useOn", at = @At("TAIL"))` on `ItemStack` binds on G, but TAIL is only the client path's
`areturn` @62; the server path returns @49 from `CommonHooks.onPlaceItemIntoWorld`. In vanilla TAIL is the return @103
after the placement on both sides. So such a handler most likely never runs on a server. `MixinNativeTail` covers only
NeoForge-ecosystem mods, and `MixinPlacementTransactionAdapter` moves only INVOKE / FIELD / NEW points. Subjects in the
fixtures (a broader scan by review B: any `@Mixin` with `useOn` in its constant pool and `ItemStack`): the purefabric
mods `old_red_mushroom_dye-1.0.0-fabric-26.2` and `story_mode_forest_temple-1.0.0-fabric-26.2`
(`net/mcreator/*/mixin/ItemStackMixin`, `@Inject(method="useOn(...)", at=@At("TAIL"))`). The merged base had the same
early return, so this is not caused by C3. Handed to the owner as a measured-later item.

## Commands

Probe (gate-m44's server and probe with the extra phases; run as
`FORBRIC_OLD=$GS FORBRIC_NEOLIVE=$GS/run/neoforge-runtime/forbricneolive.jar GATE_PORT=46250 .stage/heavy.sh bash c3b-m44.sh $WF $LANE [phases]`;
the isolated placement phase was `create` run with the throwaway build, `GATE_PORT=46251`):

```bash
#!/usr/bin/env bash
# c3b lane probe (not a tracked gate): gate-m44's server and probe, with
#   positive   — as gate-m44 phase 1 (STRICT)
#   relocOff   — only -Dforbric.mixinRelocatedCall=off (check 1 for MixinRelocatedCall)
#   create     — + Create Fly (the MixinPlacementTransactionAdapter subject), Mixin export on
#   createOff  — + Create Fly, -Dforbric.carrierCallbackAdapters=off (the adapter's group switch), Mixin export on
# Usage: c3b-m44.sh <kernel dir> <lane dir> [phases...]
set -uo pipefail
. "$1/run/lib.sh"
LANE="$2"; shift 2
PHASES="${*:-positive relocOff create createOff}"
SERVER_DIR="$LANE/server-m44"
RESULTS="$LANE/gates/m44-results"
CREATE="$KERNEL/build/compat-inputs/create-fly/create-fly.jar"
FAIL=0
mkdir -p "$RESULTS"
kernel_jar
bash "$KERNEL/run/build-interaction-events-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }
run_server() {
  local phase="$1" policy="$2" extra="$3" withcreate="$4" pid
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR"; mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricinteraction.jar" "$KERNEL"/run/canary/m44-modules/*.jar "$SERVER_DIR/mods/"
  [ "$withcreate" = yes ] && cp "$CREATE" "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.interactionProbe=$RESULTS/$phase.json -Dforbric.interactionPhase=$phase $extra" \
    "$KERNEL/run/launch-kernel-server.sh" < /dev/null > "$RESULTS/$phase.log" 2>&1 &
  pid=$!; record_server_pid "$SERVER_DIR" "$pid"
  await_server "$pid" "$RESULTS/$phase.log" 300 30
  rm -f "$SERVER_DIR/.forbric-gate.pid"
  cp "$SERVER_DIR/.forbric-kernel/compatibility-report.json" "$RESULTS/$phase-compatibility.json" 2>/dev/null || true
  if [ -d "$SERVER_DIR/.mixin.out" ]; then rm -rf "$RESULTS/$phase.mixin.out"; cp -R "$SERVER_DIR/.mixin.out" "$RESULTS/$phase.mixin.out"; fi
}
judge() {
  local phase="$1" rule="$2" what="$3"
  check "$phase: the server started and stopped" 'Done \(' "$RESULTS/$phase.log"
  if python3 - "$RESULTS/$phase.json" "$phase" "$rule" <<'PY'
import json, sys
report, phase, rule = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
assert report['phase'] == phase, report['phase']
cases = {c['name']: c for c in report['cases']}
assert len(cases) == 12, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}
USEON="{'use.place.fabric', 'use.place.neoAfter', 'use.fabricOverrides', 'use.direct'}"
EXPORT="-Dmixin.debug.export=true"
for phase in $PHASES; do
  case "$phase" in
    positive) step "positive"; run_server positive strict "" no; judge positive "not failed" "all 12 cases pass"
      check "positive: fabric-api's use-on wrap moved to the relay" 'handleUseOnEvent now wraps Item.useOn in forbric\$useOnItem' "$RESULTS/positive.log" ;;
    relocOff) step "relocOff"; run_server relocOff continue "-Dforbric.mixinRelocatedCall=off" no
      judge relocOff "failed == $USEON" "exactly Fabric's USE_ON cases fail with only MixinRelocatedCall off"
      check_absent "relocOff: nothing moved the wrap" 'now wraps Item.useOn in forbric' "$RESULTS/relocOff.log" ;;
    create) step "create"; run_server create continue "$EXPORT" yes; judge create "not failed" "all 12 cases pass beside Create Fly" ;;
    createOff) step "createOff"; run_server createOff continue "$EXPORT -Dforbric.carrierCallbackAdapters=off" yes
      judge createOff "not failed" "all 12 cases pass beside Create Fly, adapters off" ;;
  esac
done
exit "$FAIL"
```

Mixin scan for check 3 (`python3 scan.py <roots...>`; roots: the m9 pack `run/client-merged-pack/mods`, and `build/compat-inputs`,
`build/purefabric-2026-10-03`, `build/sweep80-mac`, `build/sweep90-mac` and `run/client-popular`):

```python
import sys, zipfile, io, os
# Finds classes (incl. nested jars) whose constant pool names Item.useOn as a call target string together with ItemStack:
# the defect criterion is "a mixin that points at ItemStack.useOn's Item.useOn call".
NEEDLES=[b'Lnet/minecraft/world/item/Item;useOn', b'net/minecraft/world/item/Item.useOn', b'Item;useOn(']
def scan(name, data, out):
    try: z=zipfile.ZipFile(io.BytesIO(data))
    except Exception: return
    for n in z.namelist():
        if n.endswith('.jar'): scan(name+'!/'+n, z.read(n), out)
        elif n.endswith('.class'):
            b=z.read(n)
            if any(x in b for x in NEEDLES) and (b'ItemStack' in b): out.append(name+'!/'+n)
out=[]
for root in sys.argv[1:]:
    for dp,_,fs in os.walk(root, followlinks=True):
        for f in fs:
            if f.endswith('.jar'):
                p=os.path.join(dp,f); scan(p, open(p,'rb').read(), out)
print('\n'.join(sorted(set(out))))
```

Callers of `Item.useOn(UseOnContext)` for check 2 (`python3 callers.py <patched jar> <neoforge-runtime.jar> <forbricneolive.jar>`;
it reads `net/**.class`, so it finds nothing in the vanilla reference jar):

```python
import sys, zipfile, subprocess, os, tempfile
# Lists every class in the given jars whose constant pool names Item.useOn(UseOnContext) as a Methodref target string, then
# javap's those classes to print the calling method.
needle=b'useOn'
for jar in sys.argv[1:]:
    z=zipfile.ZipFile(jar)
    hits=[n for n in z.namelist() if n.endswith('.class') and n.startswith('net/') and b'(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;' in z.read(n) and needle in z.read(n)]
    for n in hits:
        out=subprocess.run(['javap','-c','-p','-cp',jar,n[:-6].replace('/','.')],capture_output=True,text=True).stdout
        m=None
        for line in out.splitlines():
            if line.startswith('  ') and not line.startswith('   ') and '(' in line: m=line.strip()
            if 'Method net/minecraft/world/item/Item.useOn:(Lnet/minecraft/world/item/context/UseOnContext;)' in line:
                print(os.path.basename(jar), n[:-6], '::', m)
```

The vanilla side (review fix): the reference jar stores classes as `META-INF/forbric/native-reference/FABRIC/**.class.bin`.

```python
import sys, zipfile, subprocess, os, tempfile
# Like c3b-useon-relocation/src/callers.py, but for the vanilla reference jar, whose classes are stored as
# META-INF/forbric/native-reference/<ECO>/<name>.class.bin: extracts each class whose constant pool has Item.useOn's
# descriptor and the name useOn, javaps it, and prints every method that calls Item.useOn(UseOnContext).
DESC=b'(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;'
CALL='Method net/minecraft/world/item/Item.useOn:(Lnet/minecraft/world/item/context/UseOnContext;)'
jar=sys.argv[1]; z=zipfile.ZipFile(jar); tmp=tempfile.mkdtemp()
classes=[n for n in z.namelist() if n.endswith('.class.bin') or n.endswith('.class')]
print('# scanned', len(classes), 'class entries in', os.path.basename(jar))
for n in classes:
    b=z.read(n)
    if DESC not in b or b'useOn' not in b: continue
    p=os.path.join(tmp,'C.class'); open(p,'wb').write(b)
    out=subprocess.run(['javap','-c','-p',p],capture_output=True,text=True).stdout
    m=None
    for line in out.splitlines():
        if line.startswith('  ') and not line.startswith('   ') and '(' in line: m=line.strip()
        if CALL in line: print(os.path.basename(jar), n, '::', m, '@', line.strip().split(':')[0])
```

Output on G (`callers.py`) and on the vanilla reference (`callers-vanilla.py`):

```
patched-mc-neoforge-26.2.jar net/minecraft/world/item/CompassItem :: public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext);
patched-mc-neoforge-26.2.jar net/minecraft/world/item/ItemStack :: private net.minecraft.world.InteractionResult lambda$useOn$0(net.minecraft.world.item.context.UseOnContext, net.minecraft.world.item.context.UseOnContext);
patched-mc-neoforge-26.2.jar net/minecraft/world/item/MapItem :: public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext);
patched-mc-neoforge-26.2.jar net/minecraft/world/item/ShearsItem :: public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext);
neoforge-runtime.jar net/neoforged/neoforge/common/CommonHooks :: public static net.minecraft.world.InteractionResult onPlaceItemIntoWorld(net.minecraft.world.item.context.UseOnContext);
vanilla-reference-26.2.jar net/minecraft/world/item/CompassItem.class.bin :: public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext); @ 172
vanilla-reference-26.2.jar net/minecraft/world/item/ItemStack.class.bin :: public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext); @ 57
vanilla-reference-26.2.jar net/minecraft/world/item/MapItem.class.bin :: public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext); @ 73
vanilla-reference-26.2.jar net/minecraft/world/item/ShearsItem.class.bin :: public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext); @ 162
```

Exported-class counts: `javap -c -p <phase>.mixin.out/class/net/minecraft/world/item/ItemStack.class`, then
`grep -c 'create\$'` (references) and `grep invoke | grep -c 'create\$'` (invocations); warnings:
`grep 'injection warning' <phase>.log`.

## Review fixes

- Review A A1 / B F1: this file; `stage4-c3-mixin-side.md` now points here for B F2.
- A A2, B F2, B F5 (import), B F7 (`ItemUseOnInjector`): merged-base wording in `ItemUseOnInjector`'s off-switch anchor
  reason, the relocatedcall and placementwitness weave fixtures, `PlacementCallWitnessWeaveTest`'s javadoc and
  `MixinCarrierCallbackAdapters`' class javadoc; unused import in `MixinPlacementTransactionGeneralTest` (180b68f3).
  00eef69c's message said the relocatedcall fixture comment was updated; only its `ItemStack.java` was, and 180b68f3
  updates the mixin's comment.
- B F4: the isolated placement-only check above.
- B F3: the TAIL section above.
- A A3 / B F6: the empty vanilla callers file came from `callers.py` not reading `.class.bin`; regenerated with
  `callers-vanilla.py`.
- Handed on: stale skip-baseline rows for `MixinPlacementTransactionGeneralTest` (`ci-prepared-noforge.tsv`,
  `ci-unstaged.tsv`) to C5; `COMPATIBILITY_IMPLEMENTATION.md`'s merged `useOn` paragraph to C6; the remaining
  "left as merged" / "merged" log wording in other injectors to C4.
