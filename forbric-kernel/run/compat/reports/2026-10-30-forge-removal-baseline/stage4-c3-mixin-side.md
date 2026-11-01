# Stage 4 C3, lane "mixin-side": how each mixin-side repair was checked

Branch `s4-c3`, commits dd5b5f23..d253a14d on 32f77fa2, review fixes 1328bc00..9a0e3794. The lane took the repairs on the
Mixin side of the merged base: VirtualGetters, AncestorComposition + RequiredAncestorCompositions, MixinBuilderStubRebind,
MixinMergedTwin, DuplicateLambdaPruneInjector (with LambdaShapeEvidence's pruner record), and LocalRanges. Each deleting
commit's message carries its three checks (symptom with merged red, whole-jar search, callers). This file keeps the
commands and the scanner sources, which lived only in a session scratchpad.

Names: `$GS` genuine stage (`run/neoforge-patched/patched-mc-neoforge-26.2.jar` + `run/neoforge-runtime/neoforge-runtime.jar`,
G below, with the FABRIC sidecar `run/vanilla-reference/vanilla-reference-26.2.jar`), `$MS` merged stage
(`run/merged-base/patched-mc-merged-26.2.jar` + `run/forge-runtime/forge-runtime.jar` + `run/neoforge-runtime/neoforge-runtime.jar`,
M below), `$WF` the `s4-c3` worktree, `$RO` a read-only worktree at cf0498eb (throwaway edits reverted, left clean),
`$C3` the lane's scratch directory (not kept). Heavy commands went through `.stage/heavy.sh`; `MC_DIR` was never set;
ports 46140-46159.

## Results

| candidate | verdict | check 1: symptom on G; merged red | check 2: whole jar (G + carrier; views raw / after the vanilla twins / after twins + compat repairs) | check 3: callers | commit |
|---|---|---|---|---|---|
| VirtualGetters | DELETE | G: a plain `getConditionContext()` / `getFluidType()` call compiles (KernelNeoWorldgenTest asserts INVOKEVIRTUAL); M: the same plain calls fail javac with "reference to getConditionContext/getFluidType is ambiguous" | VG: G raw 0; the twin view adds one pair, `ParticleResources.getProviders()` (Int2ObjectMap / Map), made by the twin at run time, no kernel caller; M raw 16 | the data-map fallback, its only user; gate-m9 and a dedicated server on G | dd5b5f23 |
| AncestorComposition + RequiredAncestorCompositions | DELETE | M: the manifest has 3 rows (Forge `CapabilityProvider$Entities/$Levels/$BlockEntities` removed under NeoForge's `AttachmentHolder`); with no proof registered `verify` throws LinkageError for all three (AncProbe below). G: no requirement parsed; `Entity`, `Level`, `BlockEntity` extend `AttachmentHolder` natively | `META-INF/forbric/` entries: G 0, carrier 0, M 1. Vanilla ancestors a base class no longer reaches: G 2 = M 2 (`FunctionCommand$1/$5`, NeoForge's renumbered anonymous classes, not a lost state protocol) | no registrant since C2; every class the genuine boots define goes through `define` | 065d292d |
| MixinBuilderStubRebind | DELETE | new `CreateNormalsWrapBindingTest` green on G; on M "expected <1> but was <0>" | STUB: G raw 3, all vanilla's own (`CrashReport.getDetails`, `StringTag.quoteAndEscape/escapeWithoutQuotes`), 0 new in any class the twins or compat repairs changed; M raw 4 (+`UnbakedCuboidGeometry.bake`). Standing test `NeoForgeBaseStructureCensusStagedTest.noVanillaBodyMovedBehindABuilderStub` | Create Fly client on G joins and disconnects cleanly | 03a94761 |
| MixinMergedTwin (owner unpinning moved into MixinAnonymousRetarget first, R12) | DELETE | M gate-m9 + Bad Packets neo 0.12.2: twins on 187 PASS / 0 FAIL, off 140 / 47 with `ClassCastException` (`CustomPacketPayload$1$forbricneo` cannot be cast to `FabricCustomPayloadStreamCodec`). G gate-m9 + Bad Packets: joined, FAIL set = baseline | TWIN (`$forbric` in a class or member name): G 0 in every view; M raw 5 | Bad Packets + fabric-networking on the live codec (javap of the exported `CustomPacketPayload$1`: both injections invoked from encode/decode/findCodec); R12 mutation red 2/3 | c5558162 |
| DuplicateLambdaPruneInjector + LambdaShapeEvidence pruner record | DELETE / EDIT | M gate-m9: pruner on 187 / 0; off 186 / 1, the litematica main-pass injectors attached only in the dead `lambda$addMainPass$0`. Tectonic's StubException did not come back in this save on M. G: no such row | DUPL (two `lambda$` of one name in one class): G 0 in every view; M raw 132, 24 after the compat repairs. Standing test `noClassHasTwoLambdasOfOneName`; whole chain measured by a `-Dmixin.debug.export=true` boot (1,227 woven classes, none, review A) | iris, sodium, sodium-extra, litematica, lithostitched, tectonic, fabric-api OK in G gate-m9; R14: no skipData finding | 4175d4bc |
| LocalRanges | KEEP (javadoc; standing test added in review) | the merged sites were MinecraftForge splices plus the compat FluidRenderer edit, gone on G | LR: G 0 in every view, but the kernel's own chain makes one site: NativeTagTrackerSourceInjector's rewrite of `EntityFluidInteraction` leaves a local starting after its reads (dedicated server under `-Dforbric.debug`: "1 local range(s)"; with `-Dforbric.nativeTagTrackerSource=off` the line is gone). Standing test `GenuineNativeReferencesStagedTest.localRangesWidensTheOneLocalTheTagTrackerRewriteLeavesInEntityFluidInteraction` (36dff316) | KernelBoot registration; whether a mod's `@Local` needs that local was not measured (owner) | d253a14d, 36dff316 |

Switches (D29): `forbric.builderStubRebind`, `forbric.mixinMergedTwins`, `forbric.pruneDuplicateLambdas` are in
`ForbricSwitches.RETIRED` and `released-switches.txt`. VirtualGetters and AncestorComposition never had a switch.

Per-commit compile (`compileJava compileRuntimeJava compileTestJava`, temporary worktree): all six lane commits rc=0.
Unit suite at d253a14d on `.stage4`: 5773 tests, 102 failed; against 32f77fa2 (5801 / 103) 0 new, 1 gone
(`MixinMergedTwinTest.theMergedBaseReallyCarriesTheTwinThisIsAbout`, the deleted repair's subject-gone row).

## Gate runs

gate-m9 on G: `post-m9` (lane tip, real APFS clone of the m9 pack plus Bad Packets, `-Dforbric.debug=true`, port 46149)
162 PASS / 25 FAIL. The run before the lane (`shared-fixture-base-m9`) had `M9_RUNDIR` pointing at the shared fixture
through a symlink, not a clone (see below); its 25 FAIL lines are identical to `post-m9`'s, and to those of the
cmt-structural review run `m9-fixed` (a real clone, 09:24, 162 PASS), which serves as the clone-made baseline.
Review A re-ran gate-m9 at d253a14d on a fresh clone with `-Dmixin.debug.export=true` (port 46140): the same 25 FAIL lines.

Merged runs (`$RO`, `.stage`): `ro-m9-twins` (defaults), `ro-m9-notwins` (`-Dforbric.mixinMergedTwins=off`),
`ro-m9-noprune` (`-Dforbric.pruneDuplicateLambdas=off`), each on a real clone.

The symlink trap: `run/client-merged-pack` in the worktrees links to `forbric-kernel/run/client-merged-pack` of the main
checkout, and `cp -c -R <link> <dest>` copies the link. The lane's first runs (`shared-fixture-*`) therefore wrote into
the shared fixture; two overlapped and were discarded (`INVALID-overlap-*`); every run cited in a commit except
`shared-fixture-base-m9` used a real clone (clone `$(cd <link> && pwd -P)` and check the result is a directory).

## Review fixes (mixin-side-review-A.md / -B.md, commits 1328bc00..9a0e3794)

- A F1 / B F8: gate-m29's `check_absent 'Unresolved stateful ancestor composition'` could not go red after 065d292d
  (RequiredAncestorCompositions was its only producer); deleted, with the boot-4 comment's tsv sentence and the README
  sentence that still required composition proofs (1328bc00). The rest of boot 4 (MinecraftForge capabilities) is C5's.
- A F2: MixinOverloadPinWeaveTest's handler prints the String it was handed; the default run must print
  "[sky hook ran sky] drew sky+moon", and a handler printing `moon` makes it red (mutation); javadocs say it binds by
  default (0b778d8d).
- B F3 / A F4: LocalRanges' one genuine site held by a staged test over KernelBoot's COREMOD fluid segment (one moved
  range; none with the tag-tracker switch off; red with `-Dforbric.localRanges=off`), and KernelBoot's comment and
  off-switch warning say where the site comes from (36dff316).
- A F3 / B F4: NeoForgeBaseStructureCensusStagedTest's javadoc says its view is the twins and compat repairs only; the
  whole chain is measured by a `.mixin.out` export scan (5f0e801b). A standing gate phase for that is a C5 item.
- B F5: stale "pruner" / "byte merge" prose and test names (9a0e3794); the litematica (a) row is renamed
  `actualLitematicaOpaqueAndTranslucentHandlersFollowTheLiveLambda`.
- A F5: 03a94761's "0 on the twin and compat views" means no new stub in any class the twins or compat repairs changed;
  the three vanilla stubs are still there, unchanged (MsScan skips a view whose bytes did not change). No code change.
- Unit suite at 9a0e3794 on `.stage4`: 5774 tests (one new staged test), 102 failed; against d253a14d 0 new, the only
  name change being the litematica rename above. Each review-fix commit compiles (`compileJava compileRuntimeJava
  compileTestJava`, temporary worktree).
- B F1: this file. B F2 (PostMixinFixups alias parity, MixinRelocatedCall / MixinPlacementTransactionAdapter) is not in
  the C3 work list (`c3-repairs.md`); handed to the orchestrator as unowned. The orchestrator later gave the two
  use-on adapters to lane c3b-useon-relocation (both KEEP): see `stage4-c3-useon-relocation.md`. B F6 / A F6 / B F7 / B F8: handoff notes.

## Commands

Whole-jar scan, one pass per criterion and view (G with the lane-tip main classes, M with the cf0498eb classes; the
deleted repairs' own predicates are called by reflection, so the classpath must hold a build that still has them):

```bash
java -Xmx3g -cp "$C3/cls:<kernel main classes>:<kernel resources>:<boot libs>" MsScan G \
  $GS/run/vanilla-reference/vanilla-reference-26.2.jar $GS/run/neoforge-patched/patched-mc-neoforge-26.2.jar \
  $GS/run/neoforge-runtime/neoforge-runtime.jar
java -Xmx3g -cp "$C3/cls:$RO/forbric-kernel/build/classes/java/main:$RO/forbric-kernel/build/resources/main:<boot libs>" MsScan M \
  $GS/run/vanilla-reference/vanilla-reference-26.2.jar $MS/run/merged-base/patched-mc-merged-26.2.jar \
  $MS/run/forge-runtime/forge-runtime.jar $MS/run/neoforge-runtime/neoforge-runtime.jar
```

Summary lines (G / M): classes 13,394 / 15,421; ANC tsv 0 / 1; ANC lost 2 / 2; VG raw 0 / 16, twin 1 / 0, compat 1 / 5;
TWIN raw 0 / 5, twin 0 / 0, compat 0 / 0; DUPL raw 0 / 132, twin 0 / 0, compat 0 / 24; STUB raw 3 / 4, twin 0 / 0,
compat 0 / 0; LR raw 0 / 13, twin 0 / 0, compat 0 / 5; ERR 0. A view counts only classes whose bytes it changed.

Ancestor probe (cf0498eb `RequiredAncestorCompositions` by reflection): `java -cp "$C3/cls:$RO/forbric-kernel/build/classes/java/main:<boot libs>" AncProbe <base jar> <carrier jars...>`.

gate-m9 on G, one clone per run (port from the lane's range; `M9_EXTRA_JVM` as listed above):

```bash
FIX=$(cd $WF/forbric-kernel/run/client-merged-pack && pwd -P)
cp -c -R "$FIX" $C3/rundirs/<label>; [ -d $C3/rundirs/<label>/mods ] && [ ! -L $C3/rundirs/<label> ] || exit 9
cp badpackets-neo-0.12.2.jar $C3/rundirs/<label>/mods/
cd $WF/forbric-kernel && FORBRIC_OLD=$GS FORBRIC_NEOLIVE=$GS/run/neoforge-runtime/forbricneolive.jar GATE_PORT=<port> \
  M9_RUNDIR=$C3/rundirs/<label> M9_EXTRA_JVM="<flags>" .stage/heavy.sh bash run/gate-m9-client.sh
```

The same from `$RO` with `FORBRIC_OLD=$MS` for the merged runs. Dedicated servers on G went through
`run/launch-kernel-server.sh` with a scratch rundir and a FIFO for `save-all flush` / `stop` (`boot/server.sh`, as in the
cmt-structural record): `srv-default` (fabric-api 0.155.2, `-Dforbric.debug=true`) and `srv-nttoff` (the same plus
`-Dforbric.nativeTagTrackerSource=off`).

Merged red-checks of the new unit tests: the test file copied into `$RO` (throwaway), `heavy.sh ./gradlew <.stage props>
-Pforbric.requireFixtures=all test --tests …`, failed as quoted in the commits, file restored with `git checkout --`.

## Scanner sources

### MsScan.java

```java
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Mixin-side lane (Stage 4 C3) whole-jar search, one pass per defect criterion of the six repairs:
 *   VG    two non-bridge, non-synthetic methods of one class with one name and one parameter list, different returns
 *         (what VirtualGetters exists for: Java source cannot pick one)
 *   TWIN  a class or member whose name carries a "$forbric" suffix (the merge's renamed twin, MixinMergedTwin)
 *   DUPL  two lambda$ methods with one name in one class (DuplicateLambdaPruneInjector)
 *   STUB  a method MixinBuilderStubRebind.builderDelegate accepts as a builder stub over a same-name overload
 *   LR    a method LocalRanges.widen would change (a local whose debug range starts after reads of its slot)
 *   ANC   META-INF/forbric/required-ancestor-compositions.tsv, and every vanilla superclass/interface a base class lost
 *         (vanilla from the FABRIC native-reference index of the vanilla jar given)
 * Views: RAW (jar bytes), TWIN (after VanillaMemberTwinInjector), CMT (after twins + ForbricMergedBaseCompatTransformer),
 * KernelBoot's COREMOD order for those two.
 * args: <label> <vanilla-index jar> <base jar> <carrier jar>...
 */
public final class MsScan {
	public static void main(String[] args) throws Exception {
		String label = args[0];
		ZipFile vanillaRef = new ZipFile(args[1]);
		List<ZipFile> jars = new ArrayList<>();
		for (int i = 2; i < args.length; i++) jars.add(new ZipFile(args[i]));
		Function<String, ClassNode> vanilla = owner -> {
			ZipEntry e = vanillaRef.getEntry("META-INF/forbric/native-reference/FABRIC/" + owner + ".class.bin");
			if (e == null) return null;
			try (InputStream in = vanillaRef.getInputStream(e)) {
				ClassNode cn = new ClassNode();
				new ClassReader(in.readAllBytes()).accept(cn, ClassReader.EXPAND_FRAMES);
				return cn;
			} catch (Exception x) { throw new RuntimeException(x); }
		};
		Function<String, byte[]> resource = path -> {
			for (ZipFile z : jars) {
				ZipEntry e = z.getEntry(path);
				if (e == null) continue;
				try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); } catch (Exception x) { throw new RuntimeException(x); }
			}
			return null;
		};
		Method delegate = Class.forName("net.forbric.kernel.mixin.MixinBuilderStubRebind").getDeclaredMethod("builderDelegate", ClassNode.class, MethodNode.class);
		delegate.setAccessible(true);
		Method widen = Class.forName("net.forbric.kernel.transform.LocalRanges").getDeclaredMethod("widen", String.class, MethodNode.class);
		widen.setAccessible(true);
		Object twins = Class.forName("net.forbric.kernel.transform.VanillaMemberTwinInjector").getConstructor(Function.class).newInstance(vanilla);
		Object cmt = Class.forName("net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer").getConstructor(Function.class).newInstance(resource);
		Method twinT = twins.getClass().getMethod("transform", String.class, byte[].class, Class.forName("net.forbric.kernel.transform.TransformContext"));
		Method cmtT = cmt.getClass().getMethod("transform", String.class, byte[].class, Class.forName("net.forbric.kernel.transform.TransformContext"));

		Map<String, List<String>> out = new TreeMap<>();
		Map<String, Set<String>> ancestors = new HashMap<>();
		int classes = 0;
		for (ZipFile z : jars) {
			if (z.getEntry("META-INF/forbric/required-ancestor-compositions.tsv") != null)
				add(out, "ANC tsv", z.getName() + " carries META-INF/forbric/required-ancestor-compositions.tsv");
			var en = z.entries();
			while (en.hasMoreElements()) {
				ZipEntry e = en.nextElement();
				if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
				byte[] bytes;
				try (InputStream in = z.getInputStream(e)) { bytes = in.readAllBytes(); }
				ClassNode raw = node(bytes, ClassReader.SKIP_CODE);
				classes++;
				Set<String> sup = new LinkedHashSet<>();
				if (raw.superName != null) sup.add(raw.superName);
				sup.addAll(raw.interfaces);
				ancestors.putIfAbsent(raw.name, sup);
				String name = raw.name;
				if (name.contains("$forbric")) add(out, "TWIN RAW", "class " + name);
				for (var f : raw.fields) if (f.name.contains("$forbric")) add(out, "TWIN RAW", "field " + name + "." + f.name);
				for (var m : raw.methods) if (m.name.contains("$forbric")) add(out, "TWIN RAW", "method " + name + "." + m.name + m.desc);
				vg(name, raw, out);
				byte[] t, c;
				try {
					t = (byte[]) twinT.invoke(twins, name.replace('/', '.'), bytes, null);
					c = (byte[]) cmtT.invoke(cmt, name.replace('/', '.'), t, null);
					if (c == null) c = t;
				} catch (Throwable x) { add(out, "ERR", name + " " + x); t = bytes; c = bytes; }
				for (var view : List.of(Map.entry("RAW", bytes), Map.entry("TWIN", t), Map.entry("CMT", c))) {
					if (!view.getKey().equals("RAW") && view.getValue() == bytes) continue;
					dupl(view.getKey(), name, view.getValue(), out);
					ClassNode full = node(view.getValue(), 0);
					for (MethodNode m : full.methods) {
						try { if (delegate.invoke(null, full, m) != null) add(out, "STUB " + view.getKey(), name + "." + m.name + m.desc); }
						catch (Throwable x) { add(out, "ERR", "stub " + name + "." + m.name + " " + x.getCause()); }
					}
					ClassNode copy = node(view.getValue(), 0);
					for (MethodNode m : copy.methods) {
						try {
							int n = (int) widen.invoke(null, copy.name, m);
							if (n > 0) add(out, "LR " + view.getKey(), name + "." + m.name + m.desc + " widened=" + n);
						} catch (Throwable x) { add(out, "ERR", "lr " + name + "." + m.name + " " + x.getCause()); }
					}
					if (!view.getKey().equals("RAW")) {
						ClassNode skip = node(view.getValue(), ClassReader.SKIP_CODE);
						for (var f : skip.fields) if (f.name.contains("$forbric")) add(out, "TWIN " + view.getKey(), "field " + name + "." + f.name);
						for (var m : skip.methods) if (m.name.contains("$forbric")) add(out, "TWIN " + view.getKey(), "method " + name + "." + m.name + m.desc);
						vgView(view.getKey(), name, skip, out);
					}
				}
			}
		}
		// ANC: every vanilla ancestor a base class no longer reaches (transitively, within the jars given)
		var en = vanillaRef.entries();
		int compared = 0;
		while (en.hasMoreElements()) {
			ZipEntry e = en.nextElement();
			String p = e.getName(), prefix = "META-INF/forbric/native-reference/FABRIC/";
			if (!p.startsWith(prefix) || !p.endsWith(".class.bin")) continue;
			String owner = p.substring(prefix.length(), p.length() - ".class.bin".length());
			if (!ancestors.containsKey(owner)) continue;
			compared++;
			ClassNode v = vanilla.apply(owner);
			Set<String> want = new LinkedHashSet<>();
			if (v.superName != null) want.add(v.superName);
			want.addAll(v.interfaces);
			Set<String> reach = closure(owner, ancestors);
			for (String w : want) if (!reach.contains(w)) add(out, "ANC lost", owner + " lost vanilla ancestor " + w);
		}
		System.out.println(label + ": classes " + classes + "; vanilla classes compared for ancestors " + compared);
		for (String key : List.of("ANC tsv", "ANC lost", "VG RAW", "VG TWIN", "VG CMT", "TWIN RAW", "TWIN TWIN", "TWIN CMT",
				"DUPL RAW", "DUPL TWIN", "DUPL CMT", "STUB RAW", "STUB TWIN", "STUB CMT", "LR RAW", "LR TWIN", "LR CMT", "ERR")) {
			List<String> rows = out.getOrDefault(key, List.of());
			System.out.println(label + " " + key + ": " + rows.size());
			for (String r : rows) System.out.println("  " + label + " " + key + " " + r);
		}
	}

	static Set<String> closure(String owner, Map<String, Set<String>> ancestors) {
		Set<String> seen = new HashSet<>();
		Deque<String> todo = new ArrayDeque<>(ancestors.getOrDefault(owner, Set.of()));
		while (!todo.isEmpty()) {
			String s = todo.pop();
			if (!seen.add(s)) continue;
			todo.addAll(ancestors.getOrDefault(s, Set.of()));
		}
		return seen;
	}

	static void vg(String name, ClassNode node, Map<String, List<String>> out) { vgView("RAW", name, node, out); }

	static void vgView(String view, String name, ClassNode node, Map<String, List<String>> out) {
		Map<String, Set<String>> byArgs = new TreeMap<>();
		for (MethodNode m : node.methods) {
			if ((m.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0 || m.name.startsWith("<")) continue;
			String key = m.name + m.desc.substring(0, m.desc.indexOf(')') + 1);
			byArgs.computeIfAbsent(key, k -> new TreeSet<>()).add(Type.getReturnType(m.desc).getDescriptor());
		}
		byArgs.forEach((k, r) -> { if (r.size() > 1) add(out, "VG " + view, name + "." + k + " returns " + r); });
	}

	static void dupl(String view, String cls, byte[] bytes, Map<String, List<String>> out) {
		Map<String, List<String>> byName = new TreeMap<>();
		for (MethodNode m : node(bytes, ClassReader.SKIP_CODE).methods)
			if (m.name.startsWith("lambda$")) byName.computeIfAbsent(m.name, k -> new ArrayList<>()).add(m.desc);
		byName.forEach((n, d) -> { if (d.size() > 1) add(out, "DUPL " + view, cls + "." + n + " " + d); });
	}

	static void add(Map<String, List<String>> out, String key, String row) { out.computeIfAbsent(key, k -> new ArrayList<>()).add(row); }

	static ClassNode node(byte[] bytes, int flags) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, flags);
		return cn;
	}
}
```

### AncProbe.java

```java
import java.io.InputStream;
import java.lang.reflect.*;
import java.util.function.Function;
import java.util.zip.*;
/** args: <jar>... — RequiredAncestorCompositions (cf0498eb build) over the jars: requirements parsed, then verify() of each required owner with no proof registered. */
public final class AncProbe {
	public static void main(String[] a) throws Exception {
		Function<String, byte[]> res = p -> { for (String j : a) try (ZipFile z = new ZipFile(j)) { ZipEntry e = z.getEntry(p); if (e != null) try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); } } catch (Exception x) { throw new RuntimeException(x); } return null; };
		Class<?> c = Class.forName("net.forbric.kernel.classloading.RequiredAncestorCompositions");
		Constructor<?> k = c.getDeclaredConstructor(); k.setAccessible(true); Object r = k.newInstance();
		Method parse = c.getDeclaredMethod("parse", byte[].class); parse.setAccessible(true);
		Method verify = c.getDeclaredMethod("verify", String.class, byte[].class, Function.class); verify.setAccessible(true);
		java.util.Map<?, ?> req = (java.util.Map<?, ?>) parse.invoke(null, (Object) res.apply("META-INF/forbric/required-ancestor-compositions.tsv"));
		System.out.println("requirements: " + req);
		for (Object owner : req.keySet()) {
			try { verify.invoke(r, owner.toString().replace('/', '.'), res.apply(owner + ".class"), res); System.out.println("verify " + owner + ": passes with no proof"); }
			catch (InvocationTargetException x) { System.out.println("verify " + owner + ": " + x.getCause()); }
		}
	}
}
```
