# Stage 4 C3, lane "c3b-postmixin-fixups": how PostMixinFixups' repairs were checked

Branch `s4-c3`, commits 45ec501e, 6fc5a910, 2fba129f and 4bf8a6d8 on 72a3ff7b, plus the commit that adds this file.
`PostMixinFixups` runs on every class after Mixin has woven it. Its javadoc blamed the Forge/NeoForge byte merge, and
no C3 lane had given it a verdict (critic item 2). The lane checked each repair in it with the plan's three checks.
Two were merge-only:
- the `keySet()` half of the alias-target rewrite: deleted;
- the `GuiRenderer.pictureInPictureRenderers` seed: deleted.

The rest do work on NeoForge's own base and stay:
- the alias rewrite's NeoForge alias-first routing;
- `MixinFieldInitialiserPlacement`;
- the delegated-constructor replay, now restricted to delegations the mixin's own platform does not have;
- `NativeDeadValueConsumers`, whose wording was the only change.

Names:
- `$GS`: the genuine stage, `run/neoforge-patched/patched-mc-neoforge-26.2.jar` + `run/neoforge-runtime/neoforge-runtime.jar`
  (G below), with the FABRIC sidecar `run/vanilla-reference/vanilla-reference-26.2.jar`.
- `$MS`: the merged stage of the read-only worktree at cf0498eb, `merged-base/patched-mc-merged-26.2.jar` + the two carriers
  (M below).
- `$WF`: the `s4-c3` worktree's `forbric-kernel`.
- `$LANE`: the lane's scratch directory (not kept).

Heavy commands went through `.stage/heavy.sh`. `MC_DIR` was never set. Ports were 46270-46289. Every client fixture was
an APFS clone, made by real path, of `/Users/jerry/Documents/Forbric/forbric-kernel/run/client-merged-pack`, the 97-jar
pack.

## Results

| repair | verdict | check 1: symptom with it off | check 2: whole jar, its own criterion | check 3: callers on G | commit |
|---|---|---|---|---|---|
| alias rewrite, `keySet()` half (MinecraftForge `NamespacedWrapper` never filled `byLocation`) | DELETE | More Nemo's Woodcutter Variants + nemos-woodcutter + fabric-api 0.161.0, server to Done. **M**: on 0 warnings, `-Dforbric.aliasPresenceParity=off` 22, throwaway "routing kept, `byLocation.keySet()` instead of `this.keySet()`" 22 (11 block + 11 item): red proven. **G**: 0 before and after | `MappedRegistry` subclasses overriding `register`/`keySet`/`containsKey`. **G**: only `DefaultedMappedRegistry` (`register`, calls `super.register`); `MappedRegistry.keySet()` = `unmodifiableSet(byLocation.keySet())`. **M**: `NamespacedWrapper` overrides all four | fabric-registry-sync-v0 7.1.0 `MappedRegistryMixin.addAlias`, every shipped copy (unit census). 97-jar server: 0 warnings; m9 0 | 45ec501e |
| alias rewrite, NeoForge alias-first routing (`KernelRegistryAliases.aliasTargetPresent`) | KEEP (EDIT: reads `byLocation`; `.deferredRegister` sub-switch → `ForbricSwitches.RENAMED` → `forbric.aliasPresenceParity`) | **G**, 97-jar server: 0 warnings with it; 67 with `-Dforbric.aliasPresenceParity.deferredRegister=off` (66 waystones, 1 sophisticatedcore), the same 67 under the former name after the edit (with the RENAMED warning) | G: `MappedRegistry extends BaseMappedRegistry` (NeoForge `addAlias`, overridden by the mixin's); `DeferredRegister.addEntries` runs `aliases.forEach(registry::addAlias)` before `RegisterEvent.register` | waystones, sophisticatedcore (NeoForge) under fabric-registry-sync | 45ec501e |
| `seedOrphanedPipRenderers` (+ `SEEDED_FIELDS`, MixinFit's exemption) | DELETE | **G** without it: m9 149/21 with identical sets, no orphan finding, PiP fallback "gave that map its only writer". Its log line appears in no G log, nor in the merged m9 log of 2026-11-01 (seeded 0, writer 4): it was already dead on M, so the merged red check is impossible (D37) | Private fields vanilla assigns in a constructor that the base declares and never assigns. **G**: 0 (GuiRenderer declares no `pictureInPictureRenderers`; `VanillaMemberTwinInjector` adds it pre-mixin and the CMT bridge writes it). **M**: 4 | fabric-rendering-v1 `GuiRendererMixin`, malilib: applied, no HAZARD | 6fc5a910 |
| `replayDelegatedConstructorInjections` | KEEP, EDIT | **G**, throwaway early return: the 97-jar server reaches Done and m9 is 149/21 with identical sets. No gate line covers it. By bytecode, `ModelManager.lambda$reload$4` is the only caller of `ModelBakery`'s constructors and calls NeoForge's 9-argument one, so fabric-model-loading's `onReturnInit` (in the vanilla 7-argument one) would never run | Constructors that only delegate `this(...)` to a wider one whose parameters they prefix, where vanilla lacks the wide one: G 59, M 66 | replayed on G: fabric-object-builder `BlockEntityType.mutableBlocks`, fabric-data-generation `TagsProvider.initPathResolver`, fabric-model-loading `ModelBakery.onReturnInit`, and (before the edit) fabric-permission-api `CommandSourceStack.storeOriginalSource` | 2fba129f |
| `MixinFieldInitialiserPlacement` (`-Dforbric.mixinFieldInitialisers`) | KEEP, wording | **G**, off: the 97-jar server fails `Registry must be non-empty: minecraft:cat_sound_variant` / "Failed to load datapacks"; the client crashes with `NullPointerException ... this.parentsPredicate is null` at `Pack.fabric$parentsEnabled` (from `ModPackResourcesUtil.refreshAutoEnabledPacks`); m9 105/65 | `super()`-chaining constructors that construct their own class or superclass after `super()`. On G only `Pack` differs from vanilla; six vanilla classes have the same shape natively | fabric-resource-loader-v1 `PackMixin.parentsPredicate`, repaired on every G boot | 4bf8a6d8 |
| `NativeDeadValueConsumers` | KEEP, wording (`merged` → carrier's / NeoForge's) | not re-measured. NeoForge's own `TitleScreen` still draws `BrandingControl` lines instead of vanilla's version string | — | Mod Menu's title-screen suffix | 4bf8a6d8 |

### The replay edit

On the merged base and on G the replay also fired for `CommandSourceStack`. Vanilla's own public 9-argument constructor
delegates to its private wide one, so on native Fabric `storeOriginalSource` never runs for the `with*()` copies;
`copyOriginalOwner` fills those. The replay made it run there. That was not a merge wound but a broader-than-native
criterion. The replay now skips a handler whose mixin's native class has the narrow constructor delegating to the same
wide one:
- for a FABRIC mixin, the vanilla reference;
- for a NEOFORGE mixin, the base itself, so it is never moved.

An unknown ecosystem or a missing reference keeps the old behaviour. After the edit, m9 replays only `ModelBakery`,
`TagsProvider` and `BlockEntityType`, and its line sets are unchanged. The mutation red check: removing the guard fails 4
tests, 3 unit tests plus the NeoForge weave case.

### Switches

No switch was retired. `forbric.aliasPresenceParity.deferredRegister` is RENAMED to `forbric.aliasPresenceParity`: once
the `keySet()` half was gone, both did the same thing. `forbric.postMixinFixups` and `forbric.mixinFieldInitialisers` stay.

### Suite

Full suite at 4bf8a6d8 on `$GS`: 5754 tests, 65 failed. The failing names are identical to the 72a3ff7b baseline, which is the a3791619 `failures-c3.txt` set: 0 new, 0 gone. The count rose by 5: 2 tests were deleted and 7 added. None of the deleted tests were open rows in any baseline. Per-commit compile (`compileJava compileRuntimeJava compileTestJava` from `git archive` snapshots) of 45ec501e, 6fc5a910 and 2fba129f exited 0; 4bf8a6d8 compiled in the suite.

## Commands

Server boots: `heavy.sh server.sh G|M <label> <port> <modsdir> [jvm flags]`. gate-m9:
`gate.sh <label> <port> gate-m9-client.sh [M9_EXTRA_JVM=...]`.

```bash
#!/bin/bash
# server.sh <G|M> <label> <port> <modsdir> [jvm flags...] — one dedicated-server boot to "Done", then save+stop.
# G: wf tree on .stage4; M: c3-merged-ro tree on .stage (merged base). Scratch rundir under boot/<label>.
set -u
WHICH=$1; NAME=$2; PORT=$3; MODS=$4; shift 4
D=/private/tmp/claude-501/-Users-jerry-Documents-Forbric--claude-worktrees-suspicious-chaum-b28730/184da63d-abc4-4a47-82ac-70a602e12327/scratchpad/s4/c3/c3b-postmixin-fixups
if [ "$WHICH" = G ]; then W=/Users/jerry/Documents/Forbric/.claude/worktrees/wf_55971350-f54-1/forbric-kernel; OLD=/Users/jerry/Documents/Forbric/.claude/worktrees/suspicious-chaum-b28730/.stage4
else W=/Users/jerry/Documents/Forbric/.claude/worktrees/c3-merged-ro/forbric-kernel; OLD=/Users/jerry/Documents/Forbric/.claude/worktrees/suspicious-chaum-b28730/.stage; fi
OUT=$D/boot/$NAME; RUN="$OUT/run"
rm -rf "$OUT"; mkdir -p "$RUN/mods"
cp -c "$MODS"/*.jar "$RUN/mods/"
printf 'server-ip=127.0.0.1\nserver-port=%s\nonline-mode=false\nenforce-secure-profile=false\nlevel-seed=4242\n' "$PORT" > "$RUN/server.properties"
echo "eula=true" > "$RUN/eula.txt"
FIFO="$OUT/stdin.fifo"; rm -f "$FIFO"; mkfifo "$FIFO"
LOG="$OUT/console.log"
cd "$W"
echo "[boot] $WHICH tree=$W head=$(git rev-parse --short HEAD) dirty=$(git status --short -- src | wc -l | tr -d ' ') jvm=$*" > "$OUT/meta.txt"
( exec 3>"$FIFO"; exec sleep 100000 ) >/dev/null 2>&1 </dev/null & HOLDER=$!
unset MC_DIR
FORBRIC_OLD=$OLD RUNDIR="$RUN" FORBRIC_JVM="$*" run/launch-kernel-server.sh < "$FIFO" > "$LOG" 2>&1 & PID=$!
SENT=0
for i in $(seq 1 900); do
  if ! kill -0 $PID 2>/dev/null; then break; fi
  if [ $SENT = 0 ] && grep -aqE 'Done \([0-9.]+s\)!' "$LOG"; then
    sleep 2; echo "save-all flush" > "$FIFO"; sleep 3; echo stop > "$FIFO"; SENT=1
  fi
  sleep 1
done
if kill -0 $PID 2>/dev/null; then echo "[boot] still running after 900s; killing" >> "$LOG"; echo stop > "$FIFO"; sleep 20; kill $PID 2>/dev/null; fi
wait $PID; RC=$?
kill $HOLDER 2>/dev/null
echo "[boot] exit=$RC done=$SENT" >> "$LOG"
echo "$NAME exit=$RC done=$SENT"
```

```bash
#!/bin/bash
# gate.sh <label> <port> <gate-script> [VAR=val ...] — one gate on the genuine stage in the wf tree; M9_RUNDIR clone made here.
set -u
D=/private/tmp/claude-501/-Users-jerry-Documents-Forbric--claude-worktrees-suspicious-chaum-b28730/184da63d-abc4-4a47-82ac-70a602e12327/scratchpad/s4/c3/c3b-postmixin-fixups
label=$1; port=$2; gate=$3; shift 3
W=/Users/jerry/Documents/Forbric/.claude/worktrees/wf_55971350-f54-1/forbric-kernel
cd $W
SRC=$(cd $W/run/client-merged-pack && pwd -P)
CL=$D/rundirs/$label; rm -rf "$CL"; cp -c -R "$SRC" "$CL"
if [ -L "$CL" ] || [ ! -d "$CL" ]; then echo "clone $CL is not a directory" >&2; exit 9; fi
export M9_RUNDIR=$CL
export FORBRIC_OLD=/Users/jerry/Documents/Forbric/.claude/worktrees/suspicious-chaum-b28730/.stage4
export FORBRIC_NEOLIVE=/Users/jerry/Documents/Forbric/.claude/worktrees/suspicious-chaum-b28730/.stage4/run/neoforge-runtime/forbricneolive.jar
export GATE_PORT=$port
unset MC_DIR MERGED FORGE_RT
for kv in "$@"; do export "$kv"; done
start=$(date +%s)
echo "$label HEAD $(git rev-parse --short HEAD) dirty=$(git status --short -- . | grep -v '^??' | wc -l | tr -d ' ') src=$SRC env: $*" >> $D/gates/summary.txt
/Users/jerry/Documents/Forbric/.claude/worktrees/suspicious-chaum-b28730/.stage/heavy.sh bash run/$gate > $D/gates/$label.out 2>&1
rc=$?
cp build/gate-m9-client-boot.log $D/gates/$label.boot.log 2>/dev/null
echo "$label $gate rc=$rc secs=$(( $(date +%s) - start ))" >> $D/gates/summary.txt
exit $rc
```

Whole-jar scan (ASM 9.10.1; `java -cp asm:asm-tree:asm-analysis Scan.java vanilla-reference-26.2.jar G=<patched>,<neoforge-runtime> M=<merged>,<interop>,<neoforge-runtime>`):

```java
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Whole-jar scan for the defect criteria of PostMixinFixups' repairs.
 * args: <vanilla.jar> <label=jar[,jar...]>...
 * A: subclasses of MappedRegistry that override register/keySet (a store byLocation never sees)
 * B: classes whose super()-chaining constructor constructs its own class / superclass on another object after super()
 * C: a constructor that only delegates this(...) to a wider constructor whose parameters it prefixes
 * D: instance fields the vanilla class assigns in a constructor that this jar declares but never assigns in the class
 */
public class Scan {
	static Map<String, ClassNode> load(String spec) throws IOException {
		Map<String, ClassNode> out = new HashMap<>();
		for (String jar : spec.split(",")) {
			try (ZipFile z = new ZipFile(jar)) {
				for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
					ZipEntry en = e.nextElement();
					String nm = en.getName();
					boolean ref = nm.startsWith("META-INF/forbric/native-reference/FABRIC/") && nm.endsWith(".class.bin");
					if (!ref && (!nm.endsWith(".class") || nm.startsWith("META-INF"))) continue;
					byte[] b = z.getInputStream(en).readAllBytes();
					ClassNode n = new ClassNode();
					try { new ClassReader(b).accept(n, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); } catch (RuntimeException ex) { continue; }
					out.putIfAbsent(n.name, n);
				}
			}
		}
		return out;
	}

	static boolean isSub(Map<String, ClassNode> all, String name, String root) {
		for (String s = name; s != null; ) {
			if (s.equals(root)) return true;
			ClassNode n = all.get(s);
			if (n == null) return false;
			s = n.superName;
		}
		return false;
	}

	record Shape(MethodInsnNode chain, List<MethodInsnNode> nested) {}

	static Shape shape(ClassNode node, MethodNode ctor) {
		Frame<SourceValue>[] frames;
		try { frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, ctor); } catch (Exception ex) { return null; }
		MethodInsnNode chain = null; List<MethodInsnNode> nested = new ArrayList<>();
		for (int i = 0; i < ctor.instructions.size(); i++) {
			if (!(ctor.instructions.get(i) instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
					|| !call.name.equals("<init>") || frames[i] == null) continue;
			if (!call.owner.equals(node.name) && !call.owner.equals(node.superName)) continue;
			Frame<SourceValue> f = frames[i];
			int r = f.getStackSize() - 1 - Type.getArgumentTypes(call.desc).length;
			if (r < 0) return null;
			boolean self = true;
			for (AbstractInsnNode s : f.getStack(r).insns) self &= s instanceof VarInsnNode l && l.getOpcode() == Opcodes.ALOAD && l.var == 0;
			if (self) { if (chain != null) return null; chain = call; } else if (chain != null) nested.add(call);
		}
		return new Shape(chain, nested);
	}

	static boolean selfBuildsAfterSuper(ClassNode n) {
		for (MethodNode m : n.methods) {
			if (!m.name.equals("<init>")) continue;
			Shape s = shape(n, m);
			if (s != null && s.chain != null && s.chain.owner.equals(n.superName) && !s.nested.isEmpty()) return true;
		}
		return false;
	}

	/** narrow ctor desc -> wide ctor desc, for every ctor that is only `this(prefix args, extras...)`. */
	static Map<String, String> delegations(ClassNode n) {
		Map<String, String> out = new TreeMap<>();
		for (MethodNode m : n.methods) {
			if (!m.name.equals("<init>")) continue;
			MethodInsnNode chain = null;
			for (AbstractInsnNode i : m.instructions) if (i instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL && c.name.equals("<init>")) { chain = c; break; }
			if (chain == null || !chain.owner.equals(n.name)) continue;
			Type[] a = Type.getArgumentTypes(m.desc), b = Type.getArgumentTypes(chain.desc);
			if (b.length <= a.length) continue;
			boolean prefix = true;
			for (int k = 0; k < a.length; k++) prefix &= a[k].equals(b[k]);
			if (prefix) out.put(m.desc, chain.desc);
		}
		return out;
	}

	static Set<String> ctorAssigned(ClassNode n) {
		Set<String> s = new HashSet<>();
		for (MethodNode m : n.methods) if (m.name.equals("<init>")) for (AbstractInsnNode i : m.instructions)
			if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(n.name)) s.add(f.name + f.desc);
		return s;
	}

	static Set<String> anyAssigned(ClassNode n) {
		Set<String> s = new HashSet<>();
		for (MethodNode m : n.methods) for (AbstractInsnNode i : m.instructions)
			if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(n.name)) s.add(f.name + f.desc);
		return s;
	}

	public static void main(String[] args) throws Exception {
		Map<String, ClassNode> vanilla = load(args[0]);
		System.out.println("vanilla classes: " + vanilla.size());
		for (int ai = 1; ai < args.length; ai++) {
			String label = args[ai].substring(0, args[ai].indexOf('='));
			Map<String, ClassNode> all = load(args[ai].substring(label.length() + 1));
			System.out.println("\n===== " + label + " (" + all.size() + " classes)");
			List<String> a = new ArrayList<>(), b = new ArrayList<>(), c = new ArrayList<>(), d = new ArrayList<>();
			for (ClassNode n : new TreeMap<>(all).values()) {
				if (isSub(all, n.name, "net/minecraft/core/MappedRegistry") && !n.name.equals("net/minecraft/core/MappedRegistry")) {
					List<String> over = new ArrayList<>();
					for (MethodNode m : n.methods) if ((m.name.equals("register") || m.name.equals("keySet") || m.name.equals("containsKey")) && (m.access & Opcodes.ACC_SYNTHETIC) == 0) over.add(m.name + m.desc);
					a.add(n.name + " extends " + n.superName + " overrides=" + over);
				}
				ClassNode v = vanilla.get(n.name);
				if (selfBuildsAfterSuper(n)) b.add(n.name + (v == null ? " [no vanilla class]" : selfBuildsAfterSuper(v) ? " [vanilla SAME shape]" : " [vanilla: NO self-construction after super()]"));
				Map<String, String> del = delegations(n);
				if (!del.isEmpty() && n.name.startsWith("net/minecraft/")) {
					Map<String, String> vdel = v == null ? Map.of() : delegations(v);
					for (var e : del.entrySet()) {
						boolean vanillaHasWide = v != null && v.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(e.getValue()));
						if (!vanillaHasWide) c.add(n.name + " " + e.getKey() + " -> " + e.getValue() + (v == null ? " [no vanilla]" : " [wide ctor not in vanilla]"));
					}
				}
				if (v != null && n.name.startsWith("net/minecraft/")) {
					Set<String> vset = ctorAssigned(v), mine = anyAssigned(n);
					for (FieldNode f : n.fields) {
						if ((f.access & Opcodes.ACC_STATIC) != 0) continue;
						String k = f.name + f.desc;
						if (vset.contains(k) && !mine.contains(k)) d.add(n.name + "#" + k);
					}
				}
			}
			System.out.println("-- A: MappedRegistry subclasses (" + a.size() + ")"); a.forEach(x -> System.out.println("   " + x));
			System.out.println("-- B: self-construction after super() (" + b.size() + ")"); b.forEach(x -> System.out.println("   " + x));
			System.out.println("-- C: narrow ctor delegating to a wider one vanilla lacks (" + c.size() + ")"); c.forEach(x -> System.out.println("   " + x));
			System.out.println("-- D: field vanilla assigns in a ctor, never assigned here (" + d.size() + ")"); d.forEach(x -> System.out.println("   " + x));
		}
	}
}
```
