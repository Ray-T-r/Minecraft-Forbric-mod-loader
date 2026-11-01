# Stage 4 C3, lane "cmt-structural": how each structural compat repair was checked

Branch `s4-c3`, commits 830f3a46..f49d8068 on d801a69e. The lane took the structural and one-off claims of
`ForbricMergedBaseCompatTransformer` (CMT) and neighbours. Each deleting commit's message carries its three checks
(symptom with merged red, whole-jar search, callers). This file keeps the commands and the scanner sources, which lived
only in a session scratchpad.

Names: `$GS` genuine stage (`run/neoforge-patched/patched-mc-neoforge-26.2.jar` + `run/neoforge-runtime/neoforge-runtime.jar`,
G below), `$MS` merged stage (`run/merged-base/patched-mc-merged-26.2.jar` + `run/forge-runtime/forge-runtime.jar` +
`run/neoforge-runtime/neoforge-runtime.jar`, M below), `$WF` the `s4-c3` worktree, `$RO` a read-only worktree at cf0498eb
(throwaway edits reverted), `$C3` the lane's scratch directory (not kept). Heavy commands went through
`.stage/heavy.sh`; `MC_DIR` was never set; ports 46120-46123.

## Results

| candidate | verdict | check 1: symptom on G; merged red | check 2: whole jar (G + carrier, + fabric-api 0.155.2 nested jars) | check 3: callers | commit |
|---|---|---|---|---|---|
| #1 repairLambdaBootstrapHandles (+7 helpers) | DELETE (merged red impossible: inert on M too) | m9 joins/spawns, server Done | criterion C1: G 0, M 0; claim replay 0 hits | every join | 830f3a46 |
| #55 returnFromANestedBootstrapBeforeItsTail (+realAfter) | DELETE (check written) | JFR `method-trace` of `Bootstrap::bootStrap`: G server 1 entry (Main.main), G client 1 (client Main); M server 2, the second nested at `ForgeRegistries.init` <- `BucketItem.<init>` | CB callers of `bootStrap()V`: G only mains/datagen/gametest/junit; M adds `ForgeRegistries.init` | cristellib-fabric 3.1.13 BootstrapMixin (TAIL) on a G server: ran once, Done, exit 0 | 660106a5 |
| #39 giveTheUnwrittenLoggerAValue (+writesStatic, LOGGER_DESC) | DELETE (check rewritten: `MergedBaseUnwrittenStaticsTest`) | census of unassigned static finals: G+C empty; same test on M: 7 entries incl. `ResourceManagerRegistryLoadTask.LOGGER` | C39: G only four fabric-api mixin `@Shadow` (non-final) LOGGERs, no game class; native Mixin binds them to the target's own (evidence §3b) | fabric-registry-sync, -model-loading, -resource-loader in m9 + server | caeb1463 |
| #54 wrapTheStreamsVanillaWraps | KEEP, reworded | n/a (not merge damage: NeoForge's patch replaces the call; native NeoForge does not wrap, vanilla/Fabric do) | evidence §3a | owner may decide | 39d6ef58 |
| tolerateEmptyCreativeTabStacks (+KernelLifecycle.onCreativeTabEntrySkipped) | DELETE (D25: no native ecosystem tolerates it; no merged red by design) | with the repair on: 0 skips in the m9 (97) and neo (63) packs; removed: m9 joins, m55 grid counts equal baseline, 0 count-1 throws | CT: the throw only in vanilla's accept and NeoForge's lambda | m9 pack, m55 canary | 88a16ff7 |
| #6 routeKeyMappingClickToPopulatedLookup | DELETE (check written: `MergedBaseKeyMappingMapTest.theBaseHasOneKeyLookupAndEveryAccessToItIsNeoForges`) | green on G; red on M (two MAP fields); claims tests re-pinned on the streams claim | C6: G one static MAP, every access NeoForge-typed; DupFields: G 0 same-name pairs, M 17 | every client key (not pressed at run time) | fa025d43 |
| #53 letTheAtlasLowerItsMipLevelLikeVanilla + AtlasMipBoundsRepair + MipLevelLimits | DELETE | m9 + `c3mipprobe.jar` (8x8 sprite, mipmapLevels 4): vanilla lowering logged, both absences PASS; M with the probe and the switch off: black screen (evidence §2 b′) | G `SpriteLoader.stitch` = vanilla's instruction for instruction; NeoForgeClientConfig has no mip key | sprite smaller than the mip level (probe stands in for Logistics) | 817a6b80 |
| #28/#47/#48 + NativeMergeShapeRepair | DELETE | inert on G by construction (no MinecraftForge original: NativeGameReferences FORGE null); proof run CN: 0 removed / 0 inlined; M: C48 finds the one lost switch map (`AbstractFurnaceBlockEntity.getCapability`); standalone claim replay hit 0 on M too (no merged red by replay) | C48: G 0 / M 1; CN: G 0 | none on G | 00c9e26b |
| adoptInterop… / stillNamesTheOldLoader / LEGACY_INTEROP_* | DELETE | m9 joins with payloads, no NoClassDefFoundError; CustomPayloadInteropSeam lands on G; M: CL finds `CustomPacketPayload$1$forbricneo` naming `net/forbric/loader/impl/` | CL: G 0 | every custom payload (m9) | 3a6c549a |
| LootPoolFieldsInjector | DELETE | G server with tombstone-neoforge 9.9.3 loads datapacks, Done, exit 0; M: `LootPool.name` declared twice + `forge_condition` | DupFields: G 0 / M 17; G LootPool: one ctor, one name | Tombstone | f8c03154 |
| particle claim, getProvider/providersByName half | EDIT (providers-map half kept, R2) | G has neither member; M has both | no class of G or C names `providersByName` | fabric particles via the kept half | 7255ca27 |
| letDungeonsGenerateWithoutTheDataMap | KEEP (D20), claim text + javadoc | — | — | gate-m31 | b0facd67 |
| CraftingRemainderConflictTest (pins InterfaceDefaultConflictRepair on Item) | test DELETED (subject gone), repair KEEP | G Item: IItemExtension's default is `(ItemInstance)`, FabricItem's `(ItemStack)`: no conflict | repair lands on G for ItemStack, TooltipFlag, DataComponentMap$Builder | — | f49d8068 |
| OwnFluidTypeClausesInjector | KEEP (check 2 fails: defect moved) | declines on G (no MinecraftForge clause) | G `Fluid.entityInside` asks only NeoForge's type, so a Fabric fluid in `minecraft:water` gets the kernel's water stand-in and puts fire out, which vanilla (only `WaterFluid` extinguishes) never does: the Fabric half of its documented defect lives on in NeoForge's single clause | needs a NeoForge-only form (owner) | — |

Gate runs at the tip (7eabdaef, same tree as f49d8068): gate-m9-client 145 PASS / 25 FAIL, the events lane's names minus
the deleted mip line; gate-m55: only the declining creative-search repair's two rewrite lines fail.
Unit suite at the tip: 5795 tests, 105 failed, 0 new against the events lane's 116; 11 rows gone (the deleted or
rewritten tests above, and the three re-pinned claims tests now green).

## Commands

Whole-jar scan (G, then M with the cf0498eb classes):

```bash
java -cp "$C3/tool-base:<kernel main classes at d801a69e>:<boot libs>" net.forbric.kernel.transform.CmtStructuralScan \
  genuine $GS/run/neoforge-patched/patched-mc-neoforge-26.2.jar $GS/run/neoforge-runtime/neoforge-runtime.jar -- fabric-api-0.155.2+26.2.jar
java -cp "$C3/tool-ro:$RO/forbric-kernel/build/classes/java/main:<boot libs>" net.forbric.kernel.transform.CmtStructuralScan \
  merged $MS/run/merged-base/patched-mc-merged-26.2.jar $MS/run/forge-runtime/forge-runtime.jar $MS/run/neoforge-runtime/neoforge-runtime.jar -- fabric-api-0.155.2+26.2.jar
java -cp "$C3/tool-base:<boot libs>" DupFields <jars...>          # same-name field pairs
java -cp "$C3/tool-base:<boot libs>" UnwrittenStatics <jars...>   # static finals never assigned
```

Bootstrap entries (server; the client used `M9_EXTRA_JVM` with the same flag):

```bash
FORBRIC_JVM="-XX:StartFlightRecording:method-trace=net.minecraft.server.Bootstrap::bootStrap,filename=$C3/boot/wf-server.jfr" \
  .stage/heavy.sh $C3/boot/server.sh wf wf-server 46120 fabric-api-0.155.2+26.2.jar cristellib-fabric-26.2-3.1.13.jar tombstone-neoforge-26.2-9.9.3.jar
jfr print --events jdk.MethodTrace $C3/boot/wf-server.jfr
```

`server.sh <wf|ro> <name> <port> [jars]` copies the jars into a scratch rundir, writes `server.properties`
(127.0.0.1, the port, offline, seed 4242), runs `run/launch-kernel-server.sh` with `FORBRIC_OLD` set to `.stage4`
(wf) or `.stage` (ro) through a FIFO, sends `save-all flush` and `stop` after `Done`, and records the exit code.

gate-m9 with the mip probe: `cp -c -R client-merged-pack $C3/rundirs/wf-m9-probe; cp c3mipprobe.jar …/mods/`, then the
events lane's gate runner with `M9_RUNDIR` and port 46122. gate-m55: `M55_RUNDIR` clone, port 46123.

Merged red-checks of the two new staged tests: both test files copied into `$RO` (throwaway), `heavy.sh ./gradlew
<.stage props> -Pforbric.requireFixtures=all test --tests …`, both failed as quoted in the commits, files restored with
`git checkout --`.

## Scanner sources

### CmtStructuralScan.java

```java
package net.forbric.kernel.transform;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * C3 lane cmt-structural whole-jar scan. args: <label> <game jar>... [-- <guest jar>...]
 * Game jars (base + carrier) are the resolver; guest jars (fabric-api, nested jars read recursively) are scanned too.
 * For every class: replays ForbricMergedBaseCompatTransformer with a claim reporter (claims that hit, class changed),
 * and applies each lane criterion independently of the repair code:
 *  C1  own-class lambda indy handle whose tag contradicts the target's static-ness, or whose target is missing (#1)
 *  C39 static field of type org/slf4j/Logger never PUTSTATIC in its own class (flags: final? @Shadow?) (#39)
 *  C6  KeyMapping: static MAP fields and every GETSTATIC MAP descriptor (#6)
 *  C48 GETSTATIC of an undeclared $SwitchMap$ field on its own class (#48)
 *  CL  class mentions net/forbric/loader/impl/ (old-loader interop owner) (adoption)
 *  CB  callers of Bootstrap.bootStrap()V (#55)
 *  CN  NativeMergeShapeRepair.production over the class: methods dropDefaultStubs/dropSuperclassStubs would remove,
 *      lost switch maps it would inline (#28/#47/#48)
 *  CT  ldc "Stack size must be exactly 1" / "The stack count must be 1" sites (tolerateEmptyCreativeTabStacks)
 */
public final class CmtStructuralScan {
	record Entry(String origin, String name, byte[] bytes) {}

	public static void main(String[] args) throws Exception {
		String label = args[0];
		List<Path> game = new ArrayList<>(), guests = new ArrayList<>();
		boolean g = false;
		for (int i = 1; i < args.length; i++) { if (args[i].equals("--")) { g = true; continue; } (g ? guests : game).add(Path.of(args[i])); }
		Map<String, byte[]> resources = new HashMap<>();
		List<Entry> all = new ArrayList<>();
		for (Path p : game) read(p.getFileName().toString(), Files.readAllBytes(p), all, resources, true);
		for (Path p : guests) read(p.getFileName().toString(), Files.readAllBytes(p), all, resources, false);
		Function<String, byte[]> resolver = resources::get;
		// As KernelBoot binds it: the native-reference reader over the game resources (embedded index where the base has one).
		java.lang.reflect.Field active = net.forbric.kernel.mixin.NativeGameReferences.class.getDeclaredField("active");
		active.setAccessible(true);
		active.set(null, new net.forbric.kernel.mixin.NativeGameReferences(resolver));
		for (net.forbric.api.Ecosystem eco : net.forbric.api.Ecosystem.values())
			System.out.println("# native-reference index " + eco + ": " + (resolver.apply("META-INF/forbric/native-reference/" + eco + "/index.tsv") != null));
		ForbricMergedBaseCompatTransformer cmt = new ForbricMergedBaseCompatTransformer(resolver);
		Map<String, List<String>> claimHits = new TreeMap<>();
		int changed = 0, failures = 0;
		List<String> out = new ArrayList<>();
		for (Entry e : all) {
			List<String> hits = new ArrayList<>();
			byte[] after;
			try { after = cmt.transform(e.name.replace('/', '.'), e.bytes, null, hits::add); }
			catch (Throwable t) { failures++; out.add("TRANSFORM-FAILED " + e.origin + " " + e.name + " " + t); after = e.bytes; }
			if (after != e.bytes && !Arrays.equals(after, e.bytes)) { changed++; out.add("CHANGED " + e.origin + " " + e.name + " " + hits); }
			for (String h : hits) claimHits.computeIfAbsent(h.substring(h.indexOf('#') + 1), k -> new ArrayList<>()).add(e.origin + "!" + e.name);
			ClassNode node = new ClassNode();
			new ClassReader(e.bytes).accept(node, 0);
			criteria(e, node, resolver, out);
		}
		System.out.println("# " + label + ": classes " + all.size() + " (game jars " + game + ", guests " + guests.size() + "); CMT changed " + changed + "; failures " + failures);
		System.out.println("## CMT claims that hit (claim: count; classes)");
		for (String r : ForbricMergedBaseCompatTransformer.REPAIRS) {
			List<String> h = claimHits.getOrDefault(r, List.of());
			System.out.println("CLAIM " + r + ": " + h.size() + (h.isEmpty() ? "" : " " + h));
		}
		System.out.println("## criteria rows");
		out.forEach(System.out::println);
	}

	static void read(String origin, byte[] jar, List<Entry> all, Map<String, byte[]> resources, boolean resolve) throws IOException {
		try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry z; (z = zin.getNextEntry()) != null;) {
				String n = z.getName();
				byte[] b = zin.readAllBytes();
				if (n.endsWith(".jar")) { read(origin + "!" + n.substring(n.lastIndexOf('/') + 1), b, all, resources, resolve); continue; }
				if (resolve && !n.endsWith("/")) resources.putIfAbsent(n, b);
				if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
				String name = n.substring(0, n.length() - 6);
				if (resolve) resources.putIfAbsent(n, b);
				all.add(new Entry(origin, name, b));
				ALL.putIfAbsent(name, b);
			}
		}
	}

	static void criteria(Entry e, ClassNode node, Function<String, byte[]> resolver, List<String> out) {
		Map<String, MethodNode> methods = new HashMap<>();
		for (MethodNode m : node.methods) methods.put(m.name + m.desc, m);
		Set<String> putStatics = new HashSet<>();
		for (MethodNode m : node.methods) for (AbstractInsnNode i : m.instructions) {
			if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && f.owner.equals(node.name)) putStatics.add(f.name);
			if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.name.startsWith("$SwitchMap$") && !declares(resolver, f.owner, f.name))
				out.add("C48 " + e.origin + " " + node.name + "#" + m.name + m.desc + " reads undeclared " + f.owner + "." + f.name);
			if (i instanceof MethodInsnNode c && c.owner.equals("net/minecraft/server/Bootstrap") && c.name.equals("bootStrap") && c.desc.equals("()V"))
				out.add("CB " + e.origin + " " + node.name + "#" + m.name + m.desc);
			if (i instanceof LdcInsnNode l && ("Stack size must be exactly 1".equals(l.cst) || "The stack count must be 1".equals(l.cst)))
				out.add("CT " + e.origin + " " + node.name + "#" + m.name + m.desc + " ldc \"" + l.cst + "\"");
			if (i instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) for (Object a : indy.bsmArgs) {
				if (!(a instanceof Handle h) || !h.getOwner().equals(node.name)) continue;
				MethodNode t = methods.get(h.getName() + h.getDesc());
				if (t == null) { if (h.getName().startsWith("lambda$")) out.add("C1 " + e.origin + " " + node.name + " handle to missing " + h.getName() + h.getDesc()); continue; }
				boolean isStatic = (t.access & Opcodes.ACC_STATIC) != 0;
				boolean tagStatic = h.getTag() == Opcodes.H_INVOKESTATIC;
				boolean tagMember = h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKESPECIAL || h.getTag() == Opcodes.H_INVOKEINTERFACE;
				if ((tagStatic && !isStatic) || (tagMember && isStatic)) out.add("C1 " + e.origin + " " + node.name + " handle tag " + h.getTag() + " vs static=" + isStatic + " " + h.getName() + h.getDesc());
			}
		}
		for (FieldNode f : node.fields) {
			if ((f.access & Opcodes.ACC_STATIC) == 0 || !"Lorg/slf4j/Logger;".equals(f.desc) || putStatics.contains(f.name)) continue;
			boolean shadow = has(f.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;") || has(f.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;");
			out.add("C39 " + e.origin + " " + node.name + "." + f.name + " final=" + ((f.access & Opcodes.ACC_FINAL) != 0) + " @Shadow=" + shadow);
		}
		if (node.name.equals("net/minecraft/client/KeyMapping")) {
			for (FieldNode f : node.fields) if (f.name.equals("MAP")) out.add("C6 " + e.origin + " field MAP " + f.desc + " static=" + ((f.access & Opcodes.ACC_STATIC) != 0));
			for (MethodNode m : node.methods) for (AbstractInsnNode i : m.instructions)
				if (i instanceof FieldInsnNode f && f.name.equals("MAP") && f.owner.equals(node.name)) out.add("C6 " + e.origin + " " + (f.getOpcode() == Opcodes.GETSTATIC ? "GETSTATIC" : "PUTSTATIC") + " MAP " + f.desc + " in " + m.name + m.desc);
		}
		if (new String(e.bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("net/forbric/loader/impl/")) out.add("CL " + e.origin + " " + node.name + " names net/forbric/loader/impl/");
		try {
			NativeMergeShapeRepair repair = NativeMergeShapeRepair.production(node, resolver);
			List<MethodNode> defaults = repair.defaultStubs(node);
			ClassNode copy = new ClassNode(); new ClassReader(e.bytes).accept(copy, 0);
			int before = copy.methods.size();
			boolean sup = NativeMergeShapeRepair.production(copy, resolver).dropSuperclassStubs(copy);
			ClassNode copy2 = new ClassNode(); new ClassReader(e.bytes).accept(copy2, 0);
			boolean sw = NativeMergeShapeRepair.production(copy2, resolver).inlineLostSwitchMaps(copy2);
			if (!defaults.isEmpty() || sup || sw) out.add("CN " + e.origin + " " + node.name + " defaultStubs=" + defaults.size() + " superclassStubsRemoved=" + (before - copy.methods.size()) + " switchMapsInlined=" + sw);
		} catch (Throwable t) { out.add("CN-FAILED " + e.origin + " " + node.name + " " + t); }
	}

	static final Map<String, byte[]> ALL = new HashMap<>();
	static boolean declares(Function<String, byte[]> resolver, String owner, String field) {
		byte[] b = resolver.apply(owner + ".class");
		if (b == null) b = ALL.get(owner);
		if (b == null) return false;
		ClassNode n = new ClassNode(); new ClassReader(b).accept(n, ClassReader.SKIP_CODE);
		return n.fields.stream().anyMatch(x -> x.name.equals(field));
	}

	static boolean has(List<AnnotationNode> as, String desc) {
		if (as == null) return false;
		for (AnnotationNode a : as) if (a.desc.equals(desc)) return true;
		return false;
	}
}
```

### DupFields.java

```java
import java.util.*; import java.util.zip.*; import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
/** Classes declaring two fields of one name (legal in bytecode only): the merge's two-family field pairs. */
public class DupFields { public static void main(String[] a) throws Exception { int classes = 0; for (String j : a) try (ZipFile z = new ZipFile(j)) { for (var en = z.entries(); en.hasMoreElements();) { ZipEntry e = en.nextElement(); if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue; classes++; ClassNode n = new ClassNode(); new ClassReader(z.getInputStream(e).readAllBytes()).accept(n, ClassReader.SKIP_CODE); Map<String, List<String>> by = new TreeMap<>(); for (FieldNode f : n.fields) by.computeIfAbsent(f.name, k -> new ArrayList<>()).add(f.desc); by.forEach((k, v) -> { if (v.size() > 1) System.out.println(j.substring(j.lastIndexOf('/')+1) + " " + n.name + "." + k + " " + v); }); } } System.out.println("# classes scanned: " + classes); } }
```

### UnwrittenStatics.java

```java
import java.util.*; import java.util.zip.*; import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
public class UnwrittenStatics { public static void main(String[] a) throws Exception { for (String j : a) try (ZipFile z = new ZipFile(j)) { for (var en = z.entries(); en.hasMoreElements();) { ZipEntry e = en.nextElement(); if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue; ClassNode n = new ClassNode(); new ClassReader(z.getInputStream(e).readAllBytes()).accept(n, 0); Set<String> w = new HashSet<>(); for (MethodNode m : n.methods) for (AbstractInsnNode i : m.instructions) if (i instanceof FieldInsnNode f && f.getOpcode()==Opcodes.PUTSTATIC && f.owner.equals(n.name)) w.add(f.name); for (FieldNode f : n.fields) { if ((f.access & Opcodes.ACC_STATIC)==0 || (f.access & Opcodes.ACC_FINAL)==0 || f.value != null) continue; if (!f.desc.startsWith("L") && !f.desc.startsWith("[")) continue; if (w.contains(f.name)) continue; System.out.println(j.substring(j.lastIndexOf('/')+1) + " " + n.name + "." + f.name + " " + f.desc); } } } } }
```

### RefProbe.java

```java
package net.forbric.kernel.transform;
import java.nio.file.*; import java.util.*; import java.util.zip.*; import java.io.*;
import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
public class RefProbe {
  public static void main(String[] a) throws Exception {
    Map<String, byte[]> res = new HashMap<>();
    for (int i = 1; i < a.length; i++) try (ZipFile z = new ZipFile(a[i])) { for (var en = z.entries(); en.hasMoreElements();) { ZipEntry e = en.nextElement(); if (!e.isDirectory()) res.putIfAbsent(e.getName(), z.getInputStream(e).readAllBytes()); } }
    var f = net.forbric.kernel.mixin.NativeGameReferences.class.getDeclaredField("active"); f.setAccessible(true);
    f.set(null, new net.forbric.kernel.mixin.NativeGameReferences(res::get));
    String owner = a[0];
    for (var eco : net.forbric.api.Ecosystem.values()) { ClassNode n = net.forbric.kernel.mixin.NativeGameReferences.reference(eco, owner); System.out.println(eco + " -> " + (n == null ? null : n.name + " methods=" + n.methods.size())); }
    ClassNode node = new ClassNode(); new ClassReader(res.get(owner + ".class")).accept(node, 0);
    NativeMergeShapeRepair r = NativeMergeShapeRepair.production(node, res::get);
    System.out.println("inlineLostSwitchMaps=" + r.inlineLostSwitchMaps(node));
  }
}
```
