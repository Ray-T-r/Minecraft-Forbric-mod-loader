# Stage 4 C3, lane "runtime-injectors": how each runtime injector and boot-path fallback was checked

Branch `s4-c3`, commits a9be1761..30a330cc on e99f3b66, then a355f50a (a test the suite asked for) and this file. The lane took the C3 repairs that had runtime helpers or sat on the
boot path: the client part tracking, the creative search trees, the furnace tick calls, the merged-record Optional defaults,
the flower pot repair's MinecraftForge half and table refill, the data-map fallback, the common-network injector's
play-phase fall-through, the client resource preload and the once-per-process window guard (D16). Each deleting commit's
message carries its three checks (symptom with merged red, whole-jar search, callers). This file keeps the commands, the
throwaway probes and the scanner source, which lived only in a session scratchpad.

Names: `$GS` genuine stage (`run/neoforge-patched/patched-mc-neoforge-26.2.jar` + `run/neoforge-runtime/neoforge-runtime.jar`,
G below, FABRIC sidecar `run/vanilla-reference/vanilla-reference-26.2.jar`), `$MS` merged stage (`run/merged-base/patched-mc-merged-26.2.jar`
+ `run/forge-runtime/forge-runtime.jar` + `run/neoforge-runtime/neoforge-runtime.jar`, M below), `$WF` the `s4-c3` worktree
(clean at e99f3b66 while it served as the "before" tree), `$WT` a detached snapshot worktree of the lane (its commits were
replayed onto `s4-c3` unchanged; the trees are identical), `$RO` a read-only worktree at cf0498eb (throwaway edits reverted,
left clean). Heavy commands went through `.stage/heavy.sh`; `MC_DIR` was never set; ports 46160-46178; every client fixture
was a real APFS clone (`cp -c -R "$(cd <link> && pwd -P)" <dest>`, checked to be a directory) or a scratch rundir.

## Results

| candidate | verdict | check 1: symptom on G; merged red | check 2: whole jar (RiScan, G + carrier) | check 3: callers | commit |
|---|---|---|---|---|---|
| ClientPartTrackingInjector + ClientPartTrackingFlow + KernelMultipartViews | DELETE (+ new staged test, new smoke probe) | G: tofucraft's `shudofuspider` tracked on the client, 7/7 parts in the level's part list, dragon 8/8, clean disconnect; `NeoForgeBaseClientPartTrackingStagedTest` 4/4. M red: impossible, the merged body is NeoForge's too (the unrepaired merged body tracks NeoForge parts in the cf0498eb test) | client `getParts` callers: `ClientLevel$EntityCallbacks.onTrackingStart/End`, NeoForge descriptor only; M also has MinecraftForge-typed calls | tofucraft + bagus_lib + fabric-api client; dragon client/server; m9 pack | a9be1761 |
| CreativeSearchTreesInjector + KernelCreativeSearch (+ gate-m55 edit) | DELETE | G: gate-m55 phases 0-2 GREEN (94/60/12, refresh found, withheld empty). M red: impossible (evidence row f, only a MinecraftForge caller could hang) | `Map.getOrDefault` -> `CompletableFuture.join`: G 0, M 1 (`SessionSearchTrees.getSearchTree`) | m55 Fabric canary; m9 pack | 845ecd11 |
| FurnaceTickCallsInjector (+ gate-m45 edit) | DELETE | G: gate-m45 positive 22/22 incl. furnace.smelt. M red: shown, ICCE `Expected static method canBurn` (evidence row d) | opcode vs declared static-ness: G 0, M 3 (`AbstractFurnaceBlockEntity.serverTick`) | vanilla furnaces and NeoForge burn times through m45 | 27db5912 |
| MergedRecordOptionalDefaults | DELETE | G: three server-list pings answered, server ticks 26 s past Done, clean stop. M red: not reproducible without LPLM (no fixture); static defect on M | record ctor leaving an Optional unset: G 0 of 2020, M 1 (`ServerStatus.forgeData`) | any status ping; LPLM not available | 30afbe37 |
| FlowerPotRepairInjector MinecraftForge half (lookup + addPlant) + KernelFlowerPots decorators | DELETE (constructor half KEEP) | G: m42 21/21, all ten pot cases; the halves already stood down on G ("lookup ... NO, addPlant recorded NO"). M red: not run (subject only on M) | no `fullPots` on G (javap); table readers `getFullPot`/`getFullPots` | m42 pots; FlowerPotRepairInjectorTest (constructor) | 22e6b07f |
| KernelFlowerPots.rebuildTable (R6) | DELETE | G: table already 40 entries (server) / 38 (client) before the refill; refill stopped: m42 21/21. M red: shown, refill stopped -> table 0, pot.vanilla.plant / pot.neoforge.plant / pot.neoforge.api fail | readers only `getFullPot`/`getFullPots`; writer NeoForge's `BlockCallbacks.onBake` | m42 pots; m9 client | 4add18ac |
| data-map fallback (KernelNeoWorldgen) (+ gate-m7-neo edit) | DELETE (redirect + modifiers KEEP) | R11: client with the fallback off printed "already applied data maps for 6 registries". G tip: m7-neo data-map line PASS, m9 client "reload #1 ... 6 registries", m31 GREEN (dungeons vanilla's). M red: impossible (evidence row h) | DataMapLoader registered and applied by NeoForge's own handler | furnace fuels (m45 fuel.vanilla), dungeons | 280e84b9 |
| CNI serverGamePlayFallThrough (+ PayloadInterop.neoForgeWillHandle) | DELETE branch; FabricCommonPayloadMixinAdapter + its switch KEEP | G: m54 picks up chest and pig; JFR: 4 play `handleModdedPayload` calls via the super, each followed by Carry On's `handle`; control (adapter off) disconnects "Unknown addon". M red: shown (evidence row g) | play override's one call is its super; M: only `ForgeHooks.onCustomPayload` | Carry On + fabric-api; m9 pack | 4dd8629c |
| preloadClientResources + KernelClientResources (+ m9/m26 check removals) | DELETE (D16) | G before/after, m9 pack and neo pack: join, clean leave, same missing textures (7) and 0 missing translations; the only new lines are EnhancedVisuals' 20 "Could not find any resources" and CreativeCore's default style, native NeoForge behaviour (client setup before the first reload). M red: n/a (a MinecraftForge mod's symptom) | `ClientModLoader.finish` at insn 1337 < `createReload` 1368; no reload listener loads mods; M also calls MinecraftForge's `ClientModLoader.begin` | m9 pack, neo pack | b7dd2f64 |
| KernelLifecycle.openWindow (D16) | DELETE | G: one window, no second-trigger line, before and after. M red: shown, without the guard the merged server runs the window twice and never reaches Done | one trigger per entry on G; M server Main has both ecosystems' | every boot of the lane | 30a330cc |
| PackScreenHiddenFilterInjector (deleted in C2) | symptom recorded | G: "the resource-pack screen lists 5 pack row(s), 1 of them the kernel's ecosystem asset packs (repository holds 187 selected); rows: [high_contrast, programmer_art, mod_resources, forbric/mod_resources, vanilla]" before and after the lane | — | m9 pack | — |

Switches (D29): `forbric.clientPartTracking`, `forbric.creativeSearchTrees`, `forbric.furnaceTickCalls`,
`forbric.mergedRecordDefaults`, `forbric.neoDataMapFallback`, `forbric.clientResourcePreload` are in
`ForbricSwitches.RETIRED` (all six already in `released-switches.txt`). `forbric.playPayloadFallThrough` is NOT retired:
FabricCommonPayloadMixinAdapter reads it. `forbric.flowerPotRepair` / `forbric.coremodParity` stay (constructor edit,
NativeCoremodParity). openWindow had no switch.

## Runs

| label | tree | command | port | result |
|---|---|---|---|---|
| pre-m42 | $WT at e99f3b66 + throwaway table switch | `c3ri-m42.sh` (positive, table-off, withhold) | 46160 | GREEN; table 40 entries before the refill |
| pre-m9-fallbackoff | same | gate-m9, `M9_EXTRA_JVM=-Dforbric.neoDataMapFallback=off` | 46161 | 146 PASS / 25 FAIL; R11 line N=6; pack screen 1 kernel row |
| tip-m9 | $WT at the lane tip | gate-m9 | 46162 | 148 PASS / 22 FAIL |
| tip-m55 | lane tip | gate-m55, `M55_RUNDIR` scratch | 46164 | GREEN, 23 PASS |
| tip-m45 | lane tip | gate-m45 | 46165 | positive 22/22; FAILs only the two stale PartEntity-bridge lines (C5) |
| tip-m42 | lane tip | `c3ri-m42.sh` (positive, withhold) | 46166 | GREEN |
| tip-m7neo / pre-m7neo | lane tip / $WF at e99f3b66 | gate-m7-neo | 46167 / 46174 | one FAIL both: "MinecraftForge baseline intact (10)" (C5) |
| tip-m54 | lane tip | `c3ri-m54.sh`, JFR method-trace | 46168 | repaired GREEN; control disconnects "Unknown addon", gate prints `<no verdict>` (C5) |
| tip-tofu | lane tip | client.sh, tofucraft + bagus_lib + fabric-api, `-Dforbric.clientSmokeMultipart=80` | — | 7/7 and 8/8 parts tracked |
| pre-neo / tip-neo | $WF e99f3b66 / lane tip | client.sh, C1's neo template (`c1/boot/neo-tpl`) | — | both join and leave, exit 0 |
| tip-ping | lane tip | srvping.sh, fabric-api + Carry On | 46171 | 3/3 pings |
| ro-window-stock / ro-window-noguard | $RO / $RO with `openWindow` returning true | srvping.sh, zero mods | 46172 / 46173 | stock Done + 3/3; no guard: window x2, no Done (killed after 5 min, jstack kept) |
| ro-m42-tableoff | $RO + throwaway table switch | `c3ri-m42.sh` (22 cases, positive, table-off record only) | 46178 (46176 clashed with m31's second server) | positive 22/22; table-off 0 entries, 3 pot cases fail |
| tip-m31 | lane tip | gate-m31 | 46175 | GREEN, 13 PASS |

Throwaway probes (never committed): `run/c3ri-m42.sh` = gate-m42 with the case count 21 (G) and phases 1 + 4, optionally a
`table-off` phase with `-Dforbric.flowerPotTable=off`; on $RO with 22 cases and table-off judged `True` (record only).
`run/c3ri-m54.sh` = gate-m54 whose rundir is `${M54_RUNDIR}` (the stock gate `rm -rf`s the shared fixture symlink). The
table switch: in `KernelFlowerPots.rebuildTable`, log `GameData.getFlowerPotBlockTable().size()` before `clear()` and return
-1 under `-Dforbric.flowerPotTable=off`. The window probe: `KernelLifecycle.openWindow`'s `compareAndSet` replaced by `true`.
JFR: `-XX:StartFlightRecording:method-trace=tschipp.carryon.networking.serverbound.ServerboundCarryKeyPressedPacket::handle;net.neoforged.neoforge.network.registration.NetworkRegistry::handleModdedPayload,filename=<dir>/tip-m54-%p.jfr`,
read with `jfr print --events jdk.MethodTrace --stack-depth 12`. Status ping: a Python client sending handshake (next
state 1), status request and ping to `127.0.0.1:<port>`, three rounds 6 s apart.

## Unit suite

`heavy.sh ./gradlew <.stage4 props> -Pforbric.requireFixtures=all compileJava compileRuntimeJava compileTestJava cleanTest
test --continue` in `$WF`. At 30a330cc: 5721 tests, 92 failed. Against the mixin-side lane's tip (`9a0e3794`, 5774 / 102):
11 rows gone, exactly the (c) rows of this lane's repairs (ClientPartTrackingInjectorTest x2,
CommonNetworkInteropInjectorTest x2 — one deleted, everyHookSplicedIntoTheRealListenersResolves now passing —,
CoremodRepairInjectorsTest's flower-pot case, CreativeSearchTreesInjectorTest x2, FurnaceTickCallsInjectorTest x2,
MergedRecordOptionalDefaultsTest x2), and 1 new row, InjectorExecutionCensusTest.everyInjectorIsExecutedOrListedWithAReason:
its reader floor (70) was crossed by the three deleted injectors (68 left), and behind it FlowerPotRepairInjector had lost
its execution test with the MinecraftForge half. a355f50a lowers the floor to 60 and gives the constructor edit its own
execution test (census 68/68). At a355f50a: 5723 tests, 91 failed; against 9a0e3794 0 new and the same
11 rows gone. Every one of the 91 is a row of C1's failures-genuine.txt (the litematica one under the name the mixin-side
lane gave it). Per-commit compile (`compileJava compileRuntimeJava compileTestJava`): all ten deleting commits rc=0;
a355f50a compiled and ran its tests (InjectorExecutionCensusTest 4/4, FlowerPotRepairInjector{,Execution}Test 2/2 + 2/2).

## RiScan (the lane's whole-jar scanner)

`javac -cp asm-9.10.1.jar:asm-tree-9.10.1.jar RiScan.java; java -cp .:<asm jars> RiScan genuine $GS/run/neoforge-patched/patched-mc-neoforge-26.2.jar $GS/run/neoforge-runtime/neoforge-runtime.jar`
(and `RiScan merged` over the merged base and both carriers). Outputs, abridged: G — 0 classes naming net/minecraftforge;
client getParts callers onTrackingStart/End (NeoForge descriptor); getOrDefault->join 0; ICCE 0; records leaving an Optional
0 of 2020; pot-table readers getFullPot/getFullPots; DataMapLoader NEW in NeoForgeEventHandler.onResourceReload, apply in
tagsUpdated; ServerGamePacketListenerImpl.handleCustomPayload -> super only; Minecraft.<init> finish 1337 < createReload 1368;
one loader trigger per entry. M — 1940 classes naming net/minecraftforge; MinecraftForge-typed getParts x2; getSearchTree
1; ICCE 3 (furnace); ServerStatus.forgeData unset; play override -> ForgeHooks.onCustomPayload; MinecraftForge's
ClientModLoader.begin in Minecraft.<init> and ServerModLoader.load() beside NeoForge's in server Main.

```java
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Whole-jar search (C3 check 2) for the runtime-injectors lane, each repair by its own defect criterion, over the jars
 * given (first one wins for a duplicated class name, as on a class path).
 * Usage: java RiScan <label> <jar>...   Prints one section per repair.
 */
public class RiScan {
	static final Map<String, ClassNode> classes = new TreeMap<>();

	public static void main(String[] args) throws Exception {
		String label = args[0];
		for (int i = 1; i < args.length; i++) read(args[i]);
		System.out.println("# RiScan " + label + ": " + classes.size() + " classes from " + String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
		forgeRefs(); partTracking(); creativeSearch(); icce(); recordOptionals(); flowerPotTable(); dataMaps(); customPayload();
		clientResources(); modLoadingTriggers();
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

	static String m(ClassNode c, MethodNode mn) { return c.name + "." + mn.name + mn.desc; }

	static void forgeRefs() {
		int n = 0;
		for (ClassNode c : classes.values()) {
			if (c.name.startsWith("net/minecraftforge/") || refs(c, "net/minecraftforge/")) n++;
		}
		System.out.println("## classes naming net/minecraftforge/ (as name or reference): " + n);
	}

	static boolean refs(ClassNode c, String prefix) {
		if (c.superName != null && c.superName.startsWith(prefix)) return true;
		for (String i : c.interfaces) if (i.startsWith(prefix)) return true;
		for (FieldNode f : c.fields) if (f.desc.contains("L" + prefix)) return true;
		for (MethodNode mn : c.methods) {
			if (mn.desc.contains("L" + prefix)) return true;
			for (AbstractInsnNode i : mn.instructions) {
				if (i instanceof MethodInsnNode x && (x.owner.contains(prefix) || x.desc.contains(prefix))) return true;
				if (i instanceof FieldInsnNode x && (x.owner.contains(prefix) || x.desc.contains(prefix))) return true;
				if (i instanceof TypeInsnNode x && x.desc.contains(prefix)) return true;
			}
		}
		return false;
	}

	/** ClientPartTracking: every game method that reads an entity's getParts(), by descriptor, and what it does with it. */
	static void partTracking() {
		System.out.println("## part tracking: every call of Entity.getParts (any descriptor) / isMultipartEntity");
		Map<String, Integer> byDesc = new TreeMap<>();
		for (ClassNode c : classes.values()) for (MethodNode mn : c.methods) for (AbstractInsnNode i : mn.instructions) {
			if (i instanceof MethodInsnNode x && x.name.equals("getParts") && x.desc.startsWith("()[")) {
				byDesc.merge(x.desc, 1, Integer::sum);
				if (c.name.startsWith("net/minecraft/client/multiplayer/") || c.name.startsWith("net/minecraft/client/renderer/"))
					System.out.println("  client caller " + m(c, mn) + " -> " + x.owner + ".getParts" + x.desc);
			}
		}
		System.out.println("  getParts call descriptors: " + byDesc);
	}

	/** CreativeSearchTrees: a reader that joins a future taken from a map with a default (getSearchTree's shape). */
	static void creativeSearch() {
		System.out.println("## creative search: Map.getOrDefault followed by CompletableFuture.join in one method; SessionSearchTrees members");
		int n = 0;
		for (ClassNode c : classes.values()) for (MethodNode mn : c.methods) {
			AbstractInsnNode[] code = mn.instructions.toArray();
			for (int k = 0; k < code.length; k++) {
				if (!(code[k] instanceof MethodInsnNode x) || !x.name.equals("getOrDefault")) continue;
				for (int j = k + 1; j < Math.min(code.length, k + 6); j++) {
					if (code[j] instanceof MethodInsnNode y && y.owner.equals("java/util/concurrent/CompletableFuture") && y.name.equals("join")) {
						System.out.println("  " + m(c, mn)); n++;
					}
				}
			}
		}
		System.out.println("  count " + n);
		ClassNode s = classes.get("net/minecraft/client/multiplayer/SessionSearchTrees");
		if (s != null) {
			List<String> names = new ArrayList<>();
			for (MethodNode mn : s.methods) names.add(mn.name + mn.desc.substring(0, mn.desc.indexOf(')') + 1));
			System.out.println("  SessionSearchTrees methods: " + names);
			System.out.println("  declares getSearchTree: " + names.stream().anyMatch(x -> x.startsWith("getSearchTree")));
		}
	}

	/** FurnaceTickCalls: a call whose opcode contradicts the static-ness the owner itself declares (ICCE). */
	static void icce() {
		System.out.println("## ICCE: call opcode vs the owner's declared static-ness");
		int n = 0;
		for (ClassNode c : classes.values()) for (MethodNode mn : c.methods) for (AbstractInsnNode i : mn.instructions) {
			if (!(i instanceof MethodInsnNode x)) continue;
			ClassNode owner = classes.get(x.owner);
			if (owner == null) continue;
			for (MethodNode target : owner.methods) {
				if (!target.name.equals(x.name) || !target.desc.equals(x.desc)) continue;
				boolean st = (target.access & Opcodes.ACC_STATIC) != 0;
				boolean callStatic = x.getOpcode() == Opcodes.INVOKESTATIC;
				if (st != callStatic) { System.out.println("  " + m(c, mn) + " -> " + x.owner + "." + x.name + x.desc + " op=" + x.getOpcode()); n++; }
			}
		}
		System.out.println("  count " + n);
	}

	/** MergedRecordOptionalDefaults: a record constructor (not delegating to this(...)) that leaves an Optional component null. */
	static void recordOptionals() {
		System.out.println("## records: a constructor that leaves an Optional component unset or stores null into it");
		int records = 0, n = 0;
		for (ClassNode c : classes.values()) {
			if (!"java/lang/Record".equals(c.superName)) continue;
			records++;
			List<String> optionals = new ArrayList<>();
			for (FieldNode f : c.fields) if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("Ljava/util/Optional;")) optionals.add(f.name);
			if (optionals.isEmpty()) continue;
			for (MethodNode mn : c.methods) {
				if (!mn.name.equals("<init>")) continue;
				boolean delegates = false; Set<String> put = new HashSet<>(); Set<String> nulls = new HashSet<>();
				for (AbstractInsnNode i : mn.instructions) {
					if (i instanceof MethodInsnNode x && x.getOpcode() == Opcodes.INVOKESPECIAL && x.name.equals("<init>") && x.owner.equals(c.name)) delegates = true;
					if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(c.name)) {
						put.add(f.name);
						AbstractInsnNode prev = f.getPrevious();
						while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
						if (prev != null && prev.getOpcode() == Opcodes.ACONST_NULL) nulls.add(f.name);
					}
				}
				if (delegates) continue;
				for (String o : optionals) if (!put.contains(o) || nulls.contains(o)) { System.out.println("  " + m(c, mn) + " leaves " + o); n++; }
			}
		}
		System.out.println("  records " + records + ", count " + n);
		ClassNode status = classes.get("net/minecraft/network/protocol/status/ServerStatus");
		if (status != null) {
			List<String> comps = new ArrayList<>();
			if (status.recordComponents != null) for (RecordComponentNode r : status.recordComponents) comps.add(r.name);
			List<String> ctors = new ArrayList<>();
			for (MethodNode mn : status.methods) if (mn.name.equals("<init>")) ctors.add(mn.desc);
			System.out.println("  ServerStatus components " + comps + ", constructors " + ctors);
		}
	}

	/** rebuildTable: who reads and who writes NeoForge's flower pot table. */
	static void flowerPotTable() {
		System.out.println("## flower pot table: callers of GameData.getFlowerPotBlockTable, and puts/clears in the same method");
		for (ClassNode c : classes.values()) for (MethodNode mn : c.methods) {
			boolean reads = false, puts = false, clears = false;
			for (AbstractInsnNode i : mn.instructions) {
				if (i instanceof MethodInsnNode x) {
					if (x.name.equals("getFlowerPotBlockTable")) reads = true;
					if (x.owner.equals("com/google/common/collect/Table") && x.name.equals("put")) puts = true;
					if (x.owner.equals("com/google/common/collect/Table") && x.name.equals("clear")) clears = true;
				}
			}
			if (reads) System.out.println("  " + m(c, mn) + (puts ? " PUT" : "") + (clears ? " CLEAR" : ""));
		}
	}

	/** data-map fallback: which game classes construct or register NeoForge's DataMapLoader. */
	static void dataMaps() {
		System.out.println("## data maps: methods that NEW net/neoforged/neoforge/registries/DataMapLoader or call into it");
		for (ClassNode c : classes.values()) for (MethodNode mn : c.methods) {
			Set<String> what = new TreeSet<>();
			for (AbstractInsnNode i : mn.instructions) {
				if (i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals("net/neoforged/neoforge/registries/DataMapLoader")) what.add("NEW");
				if (i instanceof MethodInsnNode x && x.owner.equals("net/neoforged/neoforge/registries/DataMapLoader") && !c.name.equals(x.owner)) what.add(x.name);
			}
			if (!what.isEmpty()) System.out.println("  " + m(c, mn) + " " + what);
		}
	}

	/** CNI serverGamePlayFallThrough: every handleCustomPayload body and where it hands a payload. */
	static void customPayload() {
		System.out.println("## custom payload: every handleCustomPayload(ServerboundCustomPayloadPacket) and the calls it makes");
		for (ClassNode c : classes.values()) for (MethodNode mn : c.methods) {
			if (!mn.name.equals("handleCustomPayload") || !mn.desc.contains("ServerboundCustomPayloadPacket")) continue;
			Set<String> calls = new LinkedHashSet<>();
			for (AbstractInsnNode i : mn.instructions) if (i instanceof MethodInsnNode x)
				calls.add((x.getOpcode() == Opcodes.INVOKESPECIAL ? "special " : "") + x.owner.substring(x.owner.lastIndexOf('/') + 1) + "." + x.name);
			System.out.println("  " + m(c, mn) + " -> " + calls);
		}
		System.out.println("  callers of NetworkRegistry.handleModdedPayload:");
		for (ClassNode c : classes.values()) for (MethodNode mn : c.methods) for (AbstractInsnNode i : mn.instructions)
			if (i instanceof MethodInsnNode x && x.name.equals("handleModdedPayload") && x.owner.endsWith("NetworkRegistry")) System.out.println("    " + m(c, mn) + " " + x.desc);
	}

	/** preloadClientResources: where Minecraft.<init> runs NeoForge's client mod loading against the first reload. */
	static void clientResources() {
		System.out.println("## client resources: Minecraft.<init> offsets of ClientModLoader calls and createReload; reload listeners that load mods");
		ClassNode mc = classes.get("net/minecraft/client/Minecraft");
		if (mc != null) for (MethodNode mn : mc.methods) {
			if (!mn.name.equals("<init>")) continue;
			int k = 0;
			for (AbstractInsnNode i : mn.instructions) {
				if (i.getOpcode() >= 0) k++;
				if (i instanceof MethodInsnNode x && (x.owner.endsWith("ClientModLoader") || x.name.equals("createReload")))
					System.out.println("  insn#" + k + " " + x.owner + "." + x.name + x.desc);
			}
		}
		int n = 0;
		for (ClassNode c : classes.values()) {
			boolean listener = c.interfaces.contains("net/minecraft/server/packs/resources/PreparableReloadListener")
					|| c.interfaces.contains("net/minecraft/server/packs/resources/ResourceManagerReloadListener");
			if (!listener) continue;
			for (MethodNode mn : c.methods) for (AbstractInsnNode i : mn.instructions)
				if (i instanceof MethodInsnNode x && (x.owner.startsWith("net/neoforged/fml/ModLoader") || x.owner.endsWith("ClientModLoader"))) {
					System.out.println("  reload listener loading mods: " + m(c, mn) + " -> " + x.owner + "." + x.name); n++;
				}
		}
		System.out.println("  reload listeners that load mods: " + n);
	}

	/** openWindow: how many calls each mod-loading trigger has in the game's entry points. */
	static void modLoadingTriggers() {
		System.out.println("## mod-loading triggers: calls of ServerModLoader.load / ClientModLoader.begin");
		for (ClassNode c : classes.values()) for (MethodNode mn : c.methods) for (AbstractInsnNode i : mn.instructions)
			if (i instanceof MethodInsnNode x && ((x.owner.endsWith("ServerModLoader") && x.name.equals("load")) || (x.owner.endsWith("ClientModLoader") && x.name.equals("begin"))))
				System.out.println("  " + m(c, mn) + " -> " + x.owner + "." + x.name + x.desc);
	}
}
```
