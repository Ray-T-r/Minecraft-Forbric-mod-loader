/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Builds a single MERGED patched-Minecraft base carrying BOTH the traditional-MinecraftForge and the NeoForge
 * injections, so Forbric can run Forge + NeoForge + Fabric mods in ONE instance (the tri-in-one goal). The two
 * ecosystems patch the SAME {@code net.minecraft} classes with different hooks, and one game jar can hold only
 * one version of each class — so the merge happens here, at the bytecode (ASM) level.
 *
 * <p>"Modified by ecosystem X" is decided by a HOOK-REFERENCE signal — does the member carry an instruction/
 * interface/superclass referencing {@code net.minecraftforge}/{@code net.neoforged} — rather than a byte-level
 * diff against vanilla. This matters because the two patched jars come from DIFFERENT decompile+recompile
 * pipelines (MCPConfig/ForgeFlower vs NeoForm/Vineflower): a normalized bytecode diff false-positives on nearly
 * every method (different local-variable slots, lambda desugaring, instruction selection) even when neither
 * side touched it. A pipeline cannot invent an ecosystem package reference that wasn't a genuine injected hook,
 * so the reference itself is the reliable "was this actually patched" signal.
 *
 * <p>Strategy, per {@code net.minecraft}/{@code com.mojang} class:
 * <ul>
 *   <li><b>only one side hooks it</b> &rarr; take that side's class wholesale.</li>
 *   <li><b>both hook it</b> &rarr; base = the NeoForge-patched class; splice in every Forge-ADDED member
 *       (method/field absent on the Neo side) and every method where ONLY Forge injected a hook; MERGE the
 *       {@code implements} list (both ecosystems' extension interfaces); a method where BOTH sides inject a hook
 *       keeps the Neo body (the Forge hook there is LOST — recorded in the conflict report for hand
 *       reconciliation — this is the real tri-in-one conflict set).</li>
 *   <li><b>neither hooks it</b> &rarr; take vanilla (or whichever patched side happens to carry the class).</li>
 * </ul>
 * Forge's baked-in {@code net.minecraftforge} classes are copied wholesale (Neo does not touch that namespace);
 * {@code net.neoforged} comes from the separate neoforge runtime at launch.
 *
 * <p>Usage: {@code MergedBaseBuilder <vanilla.jar> <forge-patched.jar> <neo-patched.jar> <out.jar> [report.txt]}
 */
public final class MergedBaseBuilder {
	public static void main(String[] args) throws IOException {
		if (args.length < 4) {
			System.err.println("usage: MergedBaseBuilder <vanilla> <forge-patched> <neo-patched> <out.jar> [report.txt]");
			System.exit(2);
		}
		Path vanillaJar = Path.of(args[0]);
		Path forgeJar = Path.of(args[1]);
		Path neoJar = Path.of(args[2]);
		Path outJar = Path.of(args[3]);
		Path report = args.length > 4 ? Path.of(args[4]) : null;
		// The two RUNTIME jars (net.minecraftforge.* / net.neoforged.* "extension" interfaces such as
		// IForgeItemStack / IItemStackExtension live HERE, not in the patched game jars) — needed only to detect
		// diamond-default-method conflicts when both ecosystems' interfaces get spliced onto the same class.
		Path forgeRuntimeJar = args.length > 5 ? Path.of(args[5]) : null;
		Path neoRuntimeJar = args.length > 6 ? Path.of(args[6]) : null;

		new MergedBaseBuilder().run(vanillaJar, forgeJar, neoJar, outJar, report, forgeRuntimeJar, neoRuntimeJar);
	}

	private final List<String> conflicts = new ArrayList<>();
	private final List<String> structuralConflicts = new ArrayList<>();
	private int classesTakenVanilla, classesTakenForge, classesTakenNeo, classesMerged, classesForgeOnly;
	private int splicedMethods, splicedFields, mergedInterfaces, conflictMethods, conflictFields;
	private int superclassRebased, diamondDefaultsResolved, duplicateFieldsResolved, fieldInitPreserved;
	private int collisionMethodsDropped, classAccessWidened, bridgedConstructors, exclusiveFieldsInitialized;
	private int divergentAnonymousMaterialized, divergentAnonymousCallSitesRewritten;
	private int customPayloadInteropPatched;
	/** The three input class sets, kept as fields so per-class merge helpers can inspect sibling classes. */
	private Map<String, byte[]> vanillaClasses = Map.of(), forgeClasses = Map.of(), neoClasses = Map.of();
	/** interface internal name -> the name+desc keys of its DEFAULT (non-abstract) methods. */
	private final Map<String, Set<String>> interfaceDefaults = new LinkedHashMap<>();
	/** interface internal name -> its OWN direct {@code extends} list — for walking the transitive closure. */
	private final Map<String, List<String>> interfaceExtends = new LinkedHashMap<>();
	/**
	 * Anonymous/synthetic nested class internal name -> the ecosystem package it must be taken from, overriding
	 * the default "stay on Neo" rule for anonymous classes. Populated while merging an OUTER class: if a
	 * per-method decision there takes the OTHER side's body (e.g. Forge's {@code codec()} replaces Neo's,
	 * because only Forge hooked it) and that body {@code NEW}s an anonymous sibling in the SAME top-level
	 * family, the anonymous class must follow that SAME side — not the outer class's overall default — or the
	 * constructor call is to the wrong (differently-shaped) pipeline's version (caught empirically:
	 * {@code CustomPacketPayload.codec()} took Forge's body, which {@code new}s a 2-field
	 * {@code CustomPacketPayload$1}, while the anonymous class itself defaulted to Neo's 4-field version).
	 */
	private final Map<String, String> anonymousPackageOverride = new LinkedHashMap<>();

	private static final String FORGE_PKG = "net/minecraftforge/";
	private static final String NEO_PKG = "net/neoforged/";
	private static final String CUSTOM_PAYLOAD_NEO_CODEC =
			"net/minecraft/network/protocol/common/custom/CustomPacketPayload$1$forbricneo";
	private static final String CUSTOM_PAYLOAD_INTEROP =
			"net/forbric/loader/impl/compat/ForbricCustomPayloadInterop";

	/**
	 * Structural (superclass) conflicts where empirically the Neo-base default is WRONG — a sibling class that
	 * gets taken wholesale from Forge (for unrelated reasons) has a method SIGNATURE (not body instruction —
	 * our hook scan only inspects instructions) typed to Forge's version of this class, so Neo's version at
	 * runtime is not assignable and throws {@code VerifyError: Bad return type} (caught empirically for
	 * {@code EnderDragon.getParts(): PartEntity[]} vs a Neo-based {@code EnderDragonPart}). Narrow, hand-verified
	 * exceptions to the general "keep Neo" fallback — not a generic cross-reference solver.
	 */
	private static final Set<String> PREFER_FORGE_STRUCTURAL = Set.of(
			"net/minecraft/world/entity/boss/enderdragon/EnderDragonPart");

	/**
	 * Classes taken from NeoForge WHOLESALE — no Forge method may be spliced in, however clearly it is "hooked".
	 *
	 * <p>These carry the custom-payload CODEC PLUMBING, which must come from exactly ONE pipeline: the one whose
	 * payload registry is live at runtime. On the merged base that is NeoForge's — both
	 * {@code ClientCommonPacketListenerImpl.handleCustomPayload} and {@code ServerCommonPacketListenerImpl
	 * .handleCustomPayload} already resolve to Neo's (Forge's hook lost those sites; see merge-conflicts.txt), and
	 * {@code ServerboundCustomPayloadPacket} already calls Neo's 4-arg
	 * {@code CustomPacketPayload.codec(fallback, list, protocol, flow)}.
	 *
	 * <p>Without this pin, Forge's hooked {@code STREAM_CODEC} initializer wins in
	 * {@code ClientboundCustomPayloadPacket} (it references {@code net.minecraftforge.network.ForgePayload}, so the
	 * hook-reference signal splices it over Neo's), leaving the CLIENTBOUND encoder resolving codecs through Forge's
	 * registry while every payload object comes from Neo's — {@code ClassCastException:
	 * MinecraftUnregisterPayload cannot be cast to ForgePayload}, thrown on the first channel-registration packet,
	 * i.e. the moment a world is joined. A coupled pair must not be split across pipelines.
	 */
	private static final Set<String> PREFER_NEO_WHOLESALE = Set.of(
			"net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket",
			"net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket");

	/**
	 * For each {@link #PREFER_FORGE_STRUCTURAL} class, the ecosystem supertype ITS OWN rebased ancestor uses —
	 * elsewhere in the tree, an UNRELATED class may have a method whose signature was independently retyped by
	 * each ecosystem's compiler to reference that same ancestor type (e.g. {@code EnderDragon.getParts()}
	 * returns {@code PartEntity[]}, narrowed per-ecosystem because the array's element type, EnderDragonPart,
	 * extends a different PartEntity per side). Used to keep such "same-name, retyped-per-ecosystem" method
	 * pairs consistent with whichever ancestor we actually chose, instead of splicing in a redundant duplicate
	 * typed to the ancestor we did NOT choose (which can never pass verification).
	 */
	private static final Map<String, String> STRUCTURAL_ANCESTOR_TYPE = Map.of(
			"net/minecraft/world/entity/boss/enderdragon/EnderDragonPart", "net/minecraftforge/entity/PartEntity");

	/**
	 * For specific (class, method-key) pairs, ALWAYS take Forge's body, bypassing the normal per-method hook
	 * vote (which would otherwise keep the base/Neo body since this is a "both hooked" conflict). Used when
	 * MULTIPLE methods on a class must stay consistent with a field-type decision that isn't the class's
	 * overall default: {@code ChunkGenerator} lazily-memoizes {@code featuresPerStep} — Forge wraps it in
	 * {@code ClearableLazy}, Neo in its own {@code Lazy} — and while {@code applyBiomeDecoration} naturally
	 * takes Forge's body already (only Forge hooks THAT specific method), {@code <init>} and
	 * {@code refreshFeaturesPerStep} do NOT (both sides hook them, so the normal vote keeps Neo's) — leaving
	 * the field NEO-initialized while the majority of its readers expect FORGE's type
	 * ({@code AbstractMethodError}/NPE, caught empirically). Verified safe here because Forge's and Neo's
	 * {@code lambda$new$1}/{@code lambda$new$2} helper methods (which the grafted {@code <init>} body
	 * references) are IDENTICALLY NAMED AND SIGNATURED on both sides (the underlying computation is unchanged
	 * source, only the memoization wrapper differs) — no synthetic-lambda numbering collision risk here, unlike
	 * the general anonymous-class case.
	 */
	private static final Map<String, Set<String>> FORCE_FORGE_METHODS = Map.of(
			"net/minecraft/world/level/chunk/ChunkGenerator", Set.of(
					"<init>(Lnet/minecraft/world/level/biome/BiomeSource;Ljava/util/function/Function;)V",
					"refreshFeaturesPerStep()V"));

	/**
	 * Concrete empty-collection impls for interface-typed exclusive-added fields the byte-merge left null.
	 * The losing side's {@code <init>} (the field's only writer) was dropped, so an EMPTY collection is the
	 * correct "absent" value (same rationale as {@link #injectMissingExclusiveFieldInits}'s no-arg default).
	 * Keyed/valued by INTERNAL name (no reflection): the fastutil types are on the game's runtime classpath
	 * but NOT on this tool's classpath, which is exactly why {@link #hasPublicNoArgConstructor} can't see them.
	 * A concrete impl is verifier-assignable to its interface-typed field, so no CHECKCAST is needed.
	 */
	private static final Map<String, String> INTERFACE_DEFAULT_IMPLS = Map.of(
			"java/util/Map", "java/util/HashMap",
			"java/util/List", "java/util/ArrayList",
			"java/util/Set", "java/util/HashSet",
			"it/unimi/dsi/fastutil/ints/Int2ObjectMap", "it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap",
			"it/unimi/dsi/fastutil/objects/Object2IntMap", "it/unimi/dsi/fastutil/objects/Object2IntOpenHashMap");

	void run(Path vanillaJar, Path forgeJar, Path neoJar, Path outJar, Path report,
			Path forgeRuntimeJar, Path neoRuntimeJar) throws IOException {
		System.out.println("[merge] reading jars …");
		Map<String, byte[]> vanilla = this.vanillaClasses = readClasses(vanillaJar);
		Map<String, byte[]> forge = this.forgeClasses = readClasses(forgeJar);
		Map<String, byte[]> neo = this.neoClasses = readClasses(neoJar);
		System.out.printf("[merge]   vanilla=%d  forge-patched=%d  neo-patched=%d classes%n",
				vanilla.size(), forge.size(), neo.size());

		// Index every interface's DEFAULT methods across all four class sources — needed to detect "diamond
		// default" conflicts when splicing both ecosystems' extension interfaces (IForgeItemStack +
		// IItemStackExtension, etc.) onto the same class: Java requires an explicit override when two
		// interfaces on one class both provide a default for the same method, else IncompatibleClassChangeError
		// at runtime (caught empirically on VanillaPackResources.isHidden). The extension interfaces themselves
		// live in the RUNTIME jars, not the patched game jars.
		indexInterfaceDefaults(vanilla);
		indexInterfaceDefaults(forge);
		indexInterfaceDefaults(neo);
		if (forgeRuntimeJar != null) indexInterfaceDefaults(readClasses(forgeRuntimeJar));
		if (neoRuntimeJar != null) indexInterfaceDefaults(readClasses(neoRuntimeJar));

		// Non-class resources of the merged jar: take the union, Neo first (its patched game is the base),
		// then Forge's, then vanilla's — first writer wins so a class's own jar's resources are preferred.
		Map<String, byte[]> outEntries = new LinkedHashMap<>();

		// Every class present on either patched side (plus any vanilla-only leftover).
		Set<String> allClasses = new LinkedHashSet<>();
		allClasses.addAll(neo.keySet());
		allClasses.addAll(forge.keySet());
		allClasses.addAll(vanilla.keySet());

		// PASS 1: parse every patched-side class once (cached below) and aggregate hook presence PER TOP-LEVEL
		// CLASS FAMILY (outer + every $-nested/anonymous class). Anonymous/synthetic nested classes (numeric
		// $N suffixes) are decompiler-pipeline-specific — their exact shape/numbering differs between Forge's
		// and Neo's independent decompile+recompile, so they can NEVER be decided independently of their
		// enclosing top-level class (doing so pairs an outer class from one pipeline with a nested helper from
		// the other, producing a NoSuchMethodError/VerifyError at class-init — this was caught empirically).
		Map<String, boolean[]> groupHooks = new LinkedHashMap<>(); // topLevel -> [forgeHooked, neoHooked]
		for (String cls : allClasses) {
			byte[] vb = vanilla.get(cls);
			byte[] fb = forge.get(cls);
			byte[] nb = neo.get(cls);
			ClassNode vn = vb != null ? parse(vb) : null;
			boolean[] gh = groupHooks.computeIfAbsent(topLevel(cls), k -> new boolean[2]);
			if (fb != null && classHasHook(parse(fb), vn, FORGE_PKG)) gh[0] = true;
			if (nb != null && classHasHook(parse(nb), vn, NEO_PKG)) gh[1] = true;
		}

		// PASS 2a: named (non-anonymous) classes first — mergeBoth may record, in anonymousPackageOverride,
		// that a SIBLING anonymous class must follow a specific method's chosen side (see field javadoc).
		for (String cls : allClasses) {
			if (isAnonymousOrSynthetic(cls)) continue;
			boolean[] gh = groupHooks.get(topLevel(cls));
			byte[] merged = mergeClass(cls, vanilla.get(cls), forge.get(cls), neo.get(cls), gh[0], gh[1]);
			if (merged != null) outEntries.put(cls, merged);
		}
		// PASS 2b: anonymous/synthetic nested classes — now honoring any override collected during PASS 2a.
		for (String cls : allClasses) {
			if (!isAnonymousOrSynthetic(cls)) continue;
			boolean[] gh = groupHooks.get(topLevel(cls));
			byte[] merged = mergeClass(cls, vanilla.get(cls), forge.get(cls), neo.get(cls), gh[0], gh[1]);
			if (merged != null) outEntries.put(cls, merged);
		}

		// PASS 3: TRANSITIVE diamond-default resolution across every concrete class in the output — catches
		// conflicts inherited through an interface hierarchy rather than declared directly (e.g. the vanilla
		// PackResources interface itself gets BOTH IForgePackResources and IPackResourcesExtension spliced onto
		// it during PASS 2, so every class implementing PackResources inherits the ambiguity even if THAT class
		// was never itself "both hooked" — caught empirically on VanillaPackResources.isHidden). PASS 2's
		// direct-interface check inside mergeBoth already resolved the common direct case (e.g. ItemStack); this
		// is strictly additive and skips anything already explicitly overridden.
		Map<String, ClassNode> allNodes = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : outEntries.entrySet()) allNodes.put(e.getKey(), parse(e.getValue()));
		int transitiveFixed = 0;
		for (Map.Entry<String, ClassNode> e : allNodes.entrySet()) {
			ClassNode cn = e.getValue();
			if ((cn.access & Opcodes.ACC_INTERFACE) != 0) continue; // only concrete classes need an override
			Set<String> allIfaces = collectTransitiveInterfaces(cn, allNodes);
			if (allIfaces.isEmpty()) continue;
			if (resolveDiamondDefaultsFor(cn, allIfaces)) {
				ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				cn.accept(cw);
				outEntries.put(e.getKey(), cw.toByteArray());
				transitiveFixed++;
			}
		}
		if (transitiveFixed > 0) {
			System.out.println("[merge] transitive diamond-default pass: fixed " + transitiveFixed + " additional class(es)");
		}

		// PASS 4: pipeline-divergent anonymous siblings. PASS 2b can only put ONE version of `Outer$N` in the
		// merged jar, but the merged OUTER may retain a method from EACH ecosystem that `NEW`s that slot with a
		// DIFFERENTLY-SHAPED constructor (they survive side by side when their own descriptors differ, so the
		// same-key collision guard never sees them). The loser's call site then links against a ctor that no
		// longer exists -> NoSuchMethodError, thrown far away at first use. Give the loser its OWN class instead:
		// materialize its side's version under a fresh name and repoint just that method's references.
		// (Caught empirically: CustomPacketPayload$1 — Forge's codec(fallback,list) NEWs a 2-capture $1 while
		// Neo's codec(fallback,list,protocol,flow) NEWs a 4-capture $1; the merged $1 was Forge's, so every
		// custom-payload channel died on world join.)
		// Runs BEFORE the constructor-bridging pass: materializing the REAL class is always the better repair, and
		// leaving it to PASS 5 would let a bogus delegate be synthesized instead, silently masking the divergence.
		materializeDivergentAnonymousSiblings(allNodes, outEntries, forge, neo);
		if (divergentAnonymousMaterialized > 0) {
			System.out.println("[merge] divergent-anonymous pass: materialized " + divergentAnonymousMaterialized
					+ " pipeline-divergent anonymous sibling(s), rewrote " + divergentAnonymousCallSitesRewritten
					+ " call site(s)");
		}

		// PASS 4b: the materialized Neo custom-payload codec is structurally correct for the merged base, but
		// its lookup is still id-only. Fabric API can legitimately register a different payload class for the same
		// vanilla id (minecraft:register/unregister), so encode must choose by runtime payload type.
		patchCustomPayloadInterop(allNodes, outEntries);
		if (customPayloadInteropPatched > 0) {
			System.out.println("[merge] custom-payload interop pass: patched " + customPayloadInteropPatched
					+ " merged codec lookup method(s)");
		}

		// PASS 5: bridge missing-but-referenced constructors. When a class is taken WHOLESALE from one ecosystem
		// because that side gave it an extra field + a longer constructor (e.g. Forge appends a trailing ModelBakery
		// to client ModelManager$ReloadState), but the merged ENCLOSING class runs the OTHER side's method that
		// constructs the vanilla/shorter arity, the short ctor is gone -> NoSuchMethodError at that call site (caught
		// empirically on ModelManager$ReloadState during client model bake). Where a surviving invokespecial targets
		// a `<init>(short)` the owner lacks but the owner has exactly one `<init>(short + extra trailing params)`,
		// synthesize the short ctor as a delegate `this(short-args, <defaults>)`. The longer ctor is a strict superset
		// (it sets the shared fields identically and the extra ones to the passed defaults — null/zero, i.e. exactly
		// the "absent" semantics the shorter-arity caller intends), so this is behavior-preserving for that caller.
		Set<String> bridgedOwners = bridgeMissingReferencedConstructors(allNodes);
		for (String owner : bridgedOwners) {
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			allNodes.get(owner).accept(cw);
			outEntries.put(owner, cw.toByteArray());
		}
		if (bridgedConstructors > 0) {
			System.out.println("[merge] bridge-constructor pass: synthesized " + bridgedConstructors
					+ " delegating constructor(s) across " + bridgedOwners.size() + " class(es)");
		}

		System.out.println("[merge] writing " + outJar);
		Files.createDirectories(outJar.toAbsolutePath().getParent());
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outJar))) {
			// carry over the Neo-patched jar's non-class resources (data packs, version.json, etc.), then any
			// Forge-only resources the Neo jar lacks.
			copyResources(neoJar, zos, outEntries.keySet());
			copyResourcesMissing(forgeJar, zos, outEntries.keySet(), collectResourceNames(neoJar));
			for (Map.Entry<String, byte[]> e : outEntries.entrySet()) {
				zos.putNextEntry(new ZipEntry(e.getKey() + ".class"));
				zos.write(e.getValue());
				zos.closeEntry();
			}
		}

		summarize(System.out);
		if (report != null) {
			try (PrintStream ps = new PrintStream(Files.newOutputStream(report))) {
				summarize(ps);
				ps.println();
				ps.println("=== STRUCTURAL conflicts (superclass / field-init) — not auto-resolvable at bytecode level: ");
				ps.println("===   * (superclass: forge=… vs neo=…): both inject a DIFFERENT real superclass (no multiple ");
				ps.println("===     inheritance in Java); Neo's superclass kept, Forge's superclass extension LOST. ");
				ps.println("===   * #field (cross-method / no surviving writer): a lone-side added field whose only ");
				ps.println("===     initializer was lost to a method-splice — reconcile the writer method for this class. ===");
				for (String c : structuralConflicts) ps.println(c);
				ps.println();
				ps.println("=== both-modified methods where one side's hook is LOST (see suffix for which) ===");
				for (String c : conflicts) ps.println(c);
			}
			System.out.println("[merge] conflict report -> " + report);
		}
	}

	/**
	 * Decide + build the merged bytes for one internal class name; null to omit. {@code groupForgeHook}/
	 * {@code groupNeoHook} are the AGGREGATED (whole top-level-class-family) hook signal from pass 1.
	 */
	private byte[] mergeClass(String name, byte[] van, byte[] frg, byte[] neu, boolean groupForgeHook, boolean groupNeoHook) {
		// net.minecraftforge.* : Forge baked these into its patched jar; Neo has no such namespace. Take wholesale.
		if (name.startsWith("net/minecraftforge/")) {
			classesForgeOnly++;
			return frg;
		}
		// Present on only one patched side (rare — both recompile all of net.minecraft): take whoever has it.
		if (neu == null && frg != null) { classesTakenForge++; return frg; }
		if (frg == null && neu != null) { classesTakenNeo++; return neu; }
		if (frg == null && neu == null) { classesTakenVanilla++; return van; }

		// Highest priority: an OUTER method (decided in PASS 2a, before this anonymous sibling is reached in
		// PASS 2b) NEWs this exact anonymous class and took a SPECIFIC side's body — this class must match,
		// overriding every other rule below (including the family's overall pipeline default).
		String override = anonymousPackageOverride.get(name);
		if (FORGE_PKG.equals(override) && frg != null) { classesTakenForge++; return frg; }
		if (NEO_PKG.equals(override) && neu != null) { classesTakenNeo++; return neu; }

		// Coupled networking plumbing: never let a Forge method splice in here (see PREFER_NEO_WHOLESALE javadoc).
		if (PREFER_NEO_WHOLESALE.contains(name)) { classesTakenNeo++; return neu; }

		if (groupForgeHook && !groupNeoHook) { classesTakenForge++; return frg; } // whole family is Forge-only
		if (groupNeoHook && !groupForgeHook) { classesTakenNeo++; return neu; }   // whole family is Neo-only
		if (!groupForgeHook && !groupNeoHook) { classesTakenNeo++; return neu; }  // neither hooks anywhere in
		// the family — stay on ONE canonical pipeline (Neo, our merge base) so any anonymous/synthetic siblings
		// elsewhere in this class family that DO get merged still pair with a class from the SAME compile.

		// Both ecosystems patch SOMETHING in this top-level family. Anonymous/synthetic nested classes (numeric
		// $N suffix) cannot be safely spliced or independently re-sourced — their exact shape/numbering is
		// decompiler-pipeline-specific, so mixing an outer class from one pipeline with a nested helper from the
		// other produces a NoSuchMethodError/VerifyError at class-init. Keep them on the Neo pipeline (the outer
		// merged class's base) for consistency.
		if (isAnonymousOrSynthetic(name)) { classesTakenNeo++; return neu; }

		// Outer or a NAMED inner class (stable identifier — safe to splice at the method level): fine-grained
		// per-class hook check decides splice vs wholesale vs pipeline-consistent fallback.
		ClassNode vanN = van != null ? parse(van) : null;
		boolean forgeMod = classHasHook(parse(frg), vanN, FORGE_PKG);
		boolean neoMod = classHasHook(parse(neu), vanN, NEO_PKG);
		if (forgeMod && neoMod) {
			classesMerged++;
			return mergeBoth(name, van, frg, neu);
		}
		if (forgeMod) { classesTakenForge++; return frg; }
		if (neoMod) { classesTakenNeo++; return neu; }
		// this specific class isn't directly hooked even though a sibling in its family is — stay on Neo.
		classesTakenNeo++;
		return neu;
	}

	/** {@code Outer$Inner$3} style: a purely-numeric final segment marks an anonymous/local/synthetic class. */
	private static boolean isAnonymousOrSynthetic(String internalName) {
		int dollar = internalName.lastIndexOf('$');
		if (dollar < 0) return false;
		String last = internalName.substring(dollar + 1);
		return !last.isEmpty() && last.chars().allMatch(Character::isDigit);
	}

	/** The top-level class name (substring before the first {@code $}), grouping a class with all its nesting. */
	private static String topLevel(String internalName) {
		int dollar = internalName.indexOf('$');
		return dollar < 0 ? internalName : internalName.substring(0, dollar);
	}

	/**
	 * Whether the class carries a hook from ecosystem {@code pkg}: a member instruction/interface/superclass
	 * referencing that package, OR — since Access Transformers widen visibility WITHOUT adding any such
	 * reference — a class/field/method whose ACCESS FLAGS differ from vanilla's. (Caught empirically: a
	 * merge that ignored AT-only widening picked Neo's still-package-private nested class to pair with Forge's
	 * wholesale-copied {@code NamespacedWrapper}, which needs Forge's AT-widened version — IllegalAccessError.)
	 */
	private static boolean classHasHook(ClassNode cn, ClassNode vanillaCn, String pkg) {
		if (vanillaCn != null && cn.access != vanillaCn.access) return true;
		for (String itf : cn.interfaces) if (itf.startsWith(pkg)) return true;
		if (cn.superName != null && cn.superName.startsWith(pkg)) return true;

		Map<String, FieldNode> vanFields = vanillaCn != null ? fieldMap(vanillaCn) : Map.of();
		for (FieldNode f : cn.fields) {
			if (f.desc != null && f.desc.contains("L" + pkg)) return true;
			FieldNode vf = vanFields.get(f.name + " " + f.desc);
			if (vf != null && vf.access != f.access) return true; // AT-widened field
			// A genuinely NEW, hand-written field (present here, absent from vanilla) is itself a patch signal —
			// e.g. a new API surface — even if its own descriptor doesn't reference pkg. Compiler-synthetic
			// fields are excluded: full-tree decompile+recompile can introduce/vary these as pure ARTIFACTS on
			// classes NEITHER ecosystem actually touched (this was the earlier "4895 false conflicts" trap).
			if (vanillaCn != null && vf == null && (f.access & Opcodes.ACC_SYNTHETIC) == 0) return true;
		}

		Map<String, MethodNode> vanMethods = vanillaCn != null ? methodMap(vanillaCn) : Map.of();
		for (MethodNode m : cn.methods) {
			MethodNode vm = vanMethods.get(m.name + m.desc);
			if (methodPatched(m, vm, pkg)) return true;
			// Same reasoning as fields: a new, hand-written (non-synthetic, non-bridge) method not present in
			// vanilla — e.g. Forge's ItemTags.create(String,String) convenience overload — is a real API
			// addition even when its own body stays within vanilla types.
			boolean synthetic = (m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
			if (vanillaCn != null && vm == null && !synthetic) return true;
		}
		return false;
	}

	/** {@link #methodHasHook} PLUS an AT-style access-flag widening vs {@code vanillaM} (may be null). */
	private static boolean methodPatched(MethodNode m, MethodNode vanillaM, String pkg) {
		if (vanillaM != null && vanillaM.access != m.access) return true;
		return methodHasHook(m, pkg);
	}

	/** Whether a method body references {@code pkg} (calls/fields/types/ldc/handles into that ecosystem). */
	private static boolean methodHasHook(MethodNode m, String pkg) {
		if (m.instructions == null) return false;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			switch (insn.getType()) {
			case AbstractInsnNode.METHOD_INSN:
				if (((org.objectweb.asm.tree.MethodInsnNode) insn).owner.startsWith(pkg)) return true;
				break;
			case AbstractInsnNode.FIELD_INSN:
				if (((org.objectweb.asm.tree.FieldInsnNode) insn).owner.startsWith(pkg)) return true;
				break;
			case AbstractInsnNode.TYPE_INSN:
				if (((org.objectweb.asm.tree.TypeInsnNode) insn).desc.startsWith(pkg)) return true;
				break;
			case AbstractInsnNode.INVOKE_DYNAMIC_INSN: {
				org.objectweb.asm.tree.InvokeDynamicInsnNode d = (org.objectweb.asm.tree.InvokeDynamicInsnNode) insn;
				if (d.bsm != null && d.bsm.getOwner().startsWith(pkg)) return true;
				for (Object a : d.bsmArgs) {
					if (a instanceof org.objectweb.asm.Handle && ((org.objectweb.asm.Handle) a).getOwner().startsWith(pkg)) return true;
					if (a instanceof org.objectweb.asm.Type && ((org.objectweb.asm.Type) a).getInternalName().startsWith(pkg)) return true;
				}
				break;
			}
			default:
				break;
			}
		}
		return false;
	}

	/** Records every DEFAULT (non-abstract, non-static) method AND the {@code extends} list of every INTERFACE. */
	private void indexInterfaceDefaults(Map<String, byte[]> classes) {
		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			ClassNode cn = parse(e.getValue());
			if ((cn.access & Opcodes.ACC_INTERFACE) == 0) continue;
			Set<String> defaults = interfaceDefaults.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>());
			for (MethodNode m : cn.methods) {
				boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
				boolean isAbstract = (m.access & Opcodes.ACC_ABSTRACT) != 0;
				// PRIVATE interface methods (e.g. a common `self()` cast helper) are NOT inherited by
				// implementing classes at all — they aren't part of the diamond problem, and synthesizing an
				// invokespecial to one from OUTSIDE the declaring interface is invalid bytecode regardless.
				boolean isPrivate = (m.access & Opcodes.ACC_PRIVATE) != 0;
				if (!isStatic && !isAbstract && !isPrivate && m.instructions != null) {
					defaults.add(m.name + m.desc);
				}
			}
			interfaceExtends.put(e.getKey(), cn.interfaces);
		}
	}

	/**
	 * The FULL transitive interface closure reachable from {@code cn}: its own interfaces, its superclass
	 * chain's interfaces, and every interface's own {@code extends} chain. A default-method conflict is not
	 * only possible between a class's DIRECT interfaces — the vanilla {@code PackResources} interface itself
	 * extends BOTH {@code IForgePackResources} and {@code IPackResourcesExtension} once its own interface list
	 * is merged, so every class implementing it (e.g. {@code VanillaPackResources}) inherits the ambiguity
	 * transitively even though THAT class was never itself "both hooked" (caught empirically).
	 */
	private Set<String> collectTransitiveInterfaces(ClassNode cn, Map<String, ClassNode> allNodes) {
		Set<String> seen = new LinkedHashSet<>();
		Deque<String> queue = new ArrayDeque<>();

		ClassNode cursor = cn;
		while (cursor != null) {
			queue.addAll(cursor.interfaces);
			if (cursor.superName == null || "java/lang/Object".equals(cursor.superName)) break;
			cursor = allNodes.get(cursor.superName);
		}
		while (!queue.isEmpty()) {
			String itf = queue.poll();
			if (!seen.add(itf)) continue;
			List<String> supers = interfaceExtends.get(itf);
			if (supers != null) queue.addAll(supers);
		}
		return seen;
	}

	/**
	 * Resolves "diamond default" conflicts on a merged class: when two of its interfaces (one from each
	 * ecosystem, e.g. {@code IForgeItemStack} + {@code IItemStackExtension}) both declare a DEFAULT method with
	 * the same name+desc and the class itself has no explicit override, the JVM throws
	 * {@code IncompatibleClassChangeError: Conflicting default methods} the first time it's invoked (caught
	 * empirically on {@code VanillaPackResources.isHidden}). Synthesizes a delegating override
	 * ({@code return InterfaceA.super.method(args);}) to the FIRST declaring interface for every such conflict
	 * that isn't already resolved by a real method the merge already spliced in.
	 */
	private void resolveDiamondDefaults(ClassNode cn) {
		resolveDiamondDefaultsFor(cn, new LinkedHashSet<>(cn.interfaces));
	}

	/** As {@link #resolveDiamondDefaults(ClassNode)}, but against an explicit (e.g. transitive) interface set. */
	private boolean resolveDiamondDefaultsFor(ClassNode cn, Set<String> ifaces) {
		Map<String, List<String>> keyToIfaces = new LinkedHashMap<>();
		for (String itf : ifaces) {
			Set<String> defaults = interfaceDefaults.get(itf);
			if (defaults == null) continue;
			for (String key : defaults) keyToIfaces.computeIfAbsent(key, k -> new ArrayList<>()).add(itf);
		}

		Set<String> ownMethods = new LinkedHashSet<>();
		for (MethodNode m : cn.methods) ownMethods.add(m.name + m.desc);

		boolean changed = false;
		for (Map.Entry<String, List<String>> e : keyToIfaces.entrySet()) {
			String key = e.getKey();
			List<String> owners = e.getValue();
			if (owners.size() < 2 || ownMethods.contains(key)) continue;

			int split = key.indexOf('(');
			String name = key.substring(0, split);
			String desc = key.substring(split);
			String owner = owners.get(0);

			// invokespecial for a default-method super-call is legal ONLY against a DIRECT superinterface of
			// the class (JVM spec) — if `owner` is only reachable TRANSITIVELY (e.g. VanillaPackResources
			// implements PackResources, which itself now extends IForgePackResources, but NOT directly), the
			// call is "Bad invokespecial instruction: interface method to invoke is not in a direct
			// superinterface" (caught empirically). Adding the interface directly is harmless (Java allows a
			// class to redundantly re-declare an already-inherited interface) and makes the call legal.
			if (!cn.interfaces.contains(owner)) cn.interfaces.add(owner);

			MethodNode stub = synthesizeDelegatingOverride(owner, name, desc);
			cn.methods.add(stub);
			ownMethods.add(key);
			diamondDefaultsResolved++;
			changed = true;
		}
		return changed;
	}

	/** {@code public <returnType> name(args) { return InterfaceOwner.super.name(args); }} */
	private static MethodNode synthesizeDelegatingOverride(String interfaceOwner, String name, String desc) {
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
		org.objectweb.asm.Type methodType = org.objectweb.asm.Type.getMethodType(desc);
		org.objectweb.asm.Type[] argTypes = methodType.getArgumentTypes();

		m.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
		int local = 1;
		for (org.objectweb.asm.Type argType : argTypes) {
			m.instructions.add(new org.objectweb.asm.tree.VarInsnNode(argType.getOpcode(Opcodes.ILOAD), local));
			local += argType.getSize();
		}
		m.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(
				Opcodes.INVOKESPECIAL, interfaceOwner, name, desc, true));
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(methodType.getReturnType().getOpcode(Opcodes.IRETURN)));
		m.maxStack = Math.max(local, 2);
		m.maxLocals = local;
		return m;
	}

	/**
	 * Merges a class both ecosystems patch. The structural BASE (superclass + its own members) is normally
	 * Neo's class, with Forge's additions spliced on — EXCEPT when the two sides' superclasses genuinely
	 * diverge (see below), where the base flips to whichever side owns the real ancestor.
	 *
	 * <p><b>Superclass reconciliation</b> — Java has no multiple inheritance, so three cases:
	 * <ul>
	 *   <li><b>same superclass</b> (the common case) — no conflict, proceed with Neo as base as usual.</li>
	 *   <li><b>only one side injects a real (non-vanilla) superclass</b> — e.g. NeoForge's {@code ItemStack}
	 *       stays {@code Object} while Forge's extends {@code CapabilityProvider$ItemStacks}: that side is NOT
	 *       a competing mechanism here, it simply didn't touch the ancestor. Use IT as the structural base
	 *       (superclass + its own splice-target) and splice the OTHER side's added interfaces/methods onto it —
	 *       this is the SAME splice logic as the Neo-base case, just with which side is "base" flipped. Verified
	 *       empirically for {@code ItemStack}: NeoForge's {@code IItemStackExtension}/
	 *       {@code MutableDataComponentHolder} are pure default-method interfaces requiring NO extra state —
	 *       ItemStack can genuinely carry both ecosystems' item extensions this way.</li>
	 *   <li><b>both sides inject DIFFERENT real superclasses</b> (e.g. {@code Entity}/{@code Level}/
	 *       {@code BlockEntity}: Forge's {@code CapabilityProvider} vs NeoForge's {@code AttachmentHolder}) —
	 *       a genuine multiple-inheritance conflict with NO bytecode-level resolution (each ecosystem's own
	 *       {@code <init>} correctly {@code invokespecial}s ITS superclass's constructor; swapping just the
	 *       {@code superName} field without swapping the constructor body throws {@code VerifyError: Bad <init>
	 *       method call} — caught empirically). Cannot auto-resolve: falls back to the Neo base (Forge's
	 *       superclass-based extension is LOST for this class) and is recorded as a STRUCTURAL conflict,
	 *       distinct from the method-level conflict list, for explicit hand-reconciliation.</li>
	 * </ul>
	 */
	private byte[] mergeBoth(String name, byte[] van, byte[] frg, byte[] neu) {
		ClassNode vanN = van != null ? parse(van) : null;
		ClassNode frgN = parse(frg);
		ClassNode neuN = parse(neu);

		String vanSuper = vanN != null ? vanN.superName : "java/lang/Object";
		boolean forgeRealSuper = frgN.superName != null && !frgN.superName.equals(vanSuper);
		boolean neoRealSuper = neuN.superName != null && !neuN.superName.equals(vanSuper);
		boolean superclassesDiffer = !java.util.Objects.equals(frgN.superName, neuN.superName);

		ClassNode baseN, otherN;
		String basePkg, otherPkg;
		boolean baseIsForge;
		if (superclassesDiffer && forgeRealSuper && neoRealSuper) {
			structuralConflicts.add(name + " (superclass: forge=" + frgN.superName + " vs neo=" + neuN.superName + ")");
			if (PREFER_FORGE_STRUCTURAL.contains(name)) {
				baseN = frgN; otherN = neuN; basePkg = FORGE_PKG; otherPkg = NEO_PKG; baseIsForge = true;
			} else {
				baseN = neuN; otherN = frgN; basePkg = NEO_PKG; otherPkg = FORGE_PKG; baseIsForge = false;
			}
		} else if (superclassesDiffer && forgeRealSuper) {
			// Only Forge injects a real ancestor here — not a competing mechanism, adopt it as the base.
			baseN = frgN; otherN = neuN; basePkg = FORGE_PKG; otherPkg = NEO_PKG; baseIsForge = true;
			superclassRebased++;
		} else {
			baseN = neuN; otherN = frgN; basePkg = NEO_PKG; otherPkg = FORGE_PKG; baseIsForge = false;
		}

		Map<String, MethodNode> vanMethods = vanN != null ? methodMap(vanN) : Map.of();
		Map<String, FieldNode> vanFields = vanN != null ? fieldMap(vanN) : Map.of();
		Map<String, MethodNode> baseMethods = methodMap(baseN);
		Map<String, FieldNode> baseFields = fieldMap(baseN);
		Map<String, FieldNode> otherFields = fieldMap(otherN);

		// Reference INSTANCE fields the BASE side added exclusively (present in base, absent from BOTH vanilla and
		// the other side). These are the fields at risk when a later per-method decision replaces a base method
		// with the other side's body: only the base side knows to initialize them, so taking the other's version
		// of the writer (typically <init>) leaves the field null at runtime (caught empirically: ChunkStatus's
		// Neo-added `chunkSaveHeightmaps` — a vanilla-typed EnumSet, so Neo's <init> that PUTFIELDs it doesn't
		// trip the net.neoforged hook signal, while Forge's <init> references net.minecraftforge and wins the
		// otherHook-vs-baseHook vote → the field is never written → SerializableChunkData.copyOf NPE). Used both
		// to PRESERVE the base writer (see the method loop) and to AUDIT for any that still slipped through.
		Set<String> baseExclusiveAddedFields = new LinkedHashSet<>();
		for (FieldNode f : baseN.fields) {
			if ((f.access & Opcodes.ACC_STATIC) != 0) continue;
			if (!(f.desc.startsWith("L") || f.desc.startsWith("["))) continue; // reference/array — the NPE risk
			String k = f.name + " " + f.desc;
			if (vanFields.containsKey(k) || otherFields.containsKey(k)) continue;
			baseExclusiveAddedFields.add(k);
		}
		// SYMMETRIC set: reference instance fields the OTHER side added exclusively. When the base method (esp.
		// <init>) wins — e.g. because Forge rebased the SUPERCLASS so the whole class adopts Forge as base (Gui ->
		// ForgeLayerInstance) — the other side's <init>, the ONLY writer of these fields, is dropped, leaving them
		// null (caught empirically: NeoForge's Gui.screenLayers Stack -> ClientHooks.extractScreen NPE at the first
		// screen render). The method loop can't preserve these (it can only keep ONE <init> body), so they're
		// initialized post-hoc by injectMissingExclusiveFieldInits below.
		Set<String> otherExclusiveAddedFields = new LinkedHashSet<>();
		for (FieldNode f : otherN.fields) {
			if ((f.access & Opcodes.ACC_STATIC) != 0) continue;
			if (!(f.desc.startsWith("L") || f.desc.startsWith("["))) continue;
			String k = f.name + " " + f.desc;
			if (vanFields.containsKey(k) || baseFields.containsKey(k)) continue;
			otherExclusiveAddedFields.add(k);
		}

		// 0) Reconcile CLASS-level access flags to the MOST PERMISSIVE of both ecosystems: an Access Transformer
		// that un-finals or widens a class on one side must carry into the merged class, or the OTHER side's
		// subclasses fail to link. Forge un-finals vanilla classes like Ingredient so mods can subclass them (Neo
		// keeps them final); the merge takes Neo's base, so without this the merged Ingredient stays final and
		// Forge's AbstractIngredient throws IncompatibleClassChangeError at link (caught empirically). Dropping
		// ACC_FINAL / adding ACC_PUBLIC only ever RELAXES constraints — safe for the side that didn't widen.
		int reconciledAccess = baseN.access;
		if ((frgN.access & Opcodes.ACC_FINAL) == 0 || (neuN.access & Opcodes.ACC_FINAL) == 0) {
			reconciledAccess &= ~Opcodes.ACC_FINAL;
		}
		if ((frgN.access & Opcodes.ACC_PUBLIC) != 0 || (neuN.access & Opcodes.ACC_PUBLIC) != 0) {
			reconciledAccess |= Opcodes.ACC_PUBLIC;
		}
		if (reconciledAccess != baseN.access) {
			baseN.access = reconciledAccess;
			classAccessWidened++;
		}

		// 1) Merge interfaces (both ecosystems' extension interfaces) onto the base.
		for (String itf : otherN.interfaces) {
			if (!baseN.interfaces.contains(itf)) {
				baseN.interfaces.add(itf);
				mergedInterfaces++;
			}
		}

		// 2) Fields: add every "other"-side field the base lacks (by name+desc); for a field BOTH have, prefer
		// whichever side's ACCESS differs from vanilla (an Access Transformer widening) — if both differ (rare),
		// keep the base's and record a conflict.
		for (FieldNode of : otherN.fields) {
			String key = of.name + " " + of.desc;
			FieldNode bf = baseFields.get(key);
			if (bf == null) {
				baseN.fields.add(of);
				baseFields.put(key, of);
				splicedFields++;
				continue;
			}
			FieldNode vf = vanFields.get(key);
			boolean otherWidened = vf != null && vf.access != of.access;
			boolean baseWidened = vf != null && vf.access != bf.access;
			if (otherWidened && !baseWidened) {
				int idx = baseN.fields.indexOf(bf);
				baseN.fields.set(idx, of);
				baseFields.put(key, of);
				splicedFields++;
			} else if (otherWidened && baseWidened && of.access != bf.access) {
				conflictFields++;
			}
		}

		// 3) Methods: for every "other"-side method, decide against the base — using the HOOK-REFERENCE signal
		//    (net.minecraftforge / net.neoforged references, or an AT-style access-flag widening vs vanilla),
		//    not a recompile-noisy body diff.
		for (MethodNode om : otherN.methods) {
			String key = om.name + om.desc;
			MethodNode bm = baseMethods.get(key);
			MethodNode vm = vanMethods.get(key);
			if (splicedMethodNeedsDivergentAnonymous(name, om)) {
				// This other-side method NEWs an anonymous sibling whose slot number means a DIFFERENT logical
				// class in each pipeline (see the helper). The merged base keeps ONE version of that slot; this
				// method needs the other — it cannot be spliced. Drop it (keep base's own method if any), record.
				collisionMethodsDropped++;
				conflicts.add(name + "#" + key + " (dropped " + (baseIsForge ? "neo" : "forge")
						+ " method — needs a pipeline-divergent anonymous sibling the merged slot can't provide)");
				continue;
			}
			if (bm == null) {
				// A same-NAME, different-DESC method already in the base is NOT a genuinely new method — it's
				// the SAME conceptual method that each ecosystem's compiler retyped to ITS OWN extension type
				// (e.g. getParts(): PartEntity[], narrowed per-ecosystem because the element type's ancestor
				// was structurally rebased — see PREFER_FORGE_STRUCTURAL). Keeping BOTH as "overloads" leaves
				// two methods whose bodies each expect a DIFFERENT, mutually exclusive ancestor for the same
				// runtime value — only one can ever pass verification (caught empirically on
				// EnderDragon.getParts()). Reconcile to whichever ancestor we ACTUALLY chose.
				MethodNode staleSameName = baseN.methods.stream()
						.filter(x -> x.name.equals(om.name) && !x.desc.equals(om.desc))
						.findFirst().orElse(null);
				boolean handled = false;
				if (staleSameName != null) {
					String preferredType = preferredAncestorType(om.desc, staleSameName.desc);
					if (preferredType != null) {
						handled = true;
						boolean omMatches = om.desc.contains(preferredType);
						boolean staleMatches = staleSameName.desc.contains(preferredType);
						if (omMatches && !staleMatches) {
							int idx = baseN.methods.indexOf(staleSameName);
							baseN.methods.set(idx, om);
							baseMethods.remove(staleSameName.name + staleSameName.desc);
							baseMethods.put(key, om);
							splicedMethods++;
						}
						// else: the base's existing version already matches (or neither does) — keep it, drop `om`.
					} else if (mentionsPkg(om.desc, otherPkg) && mentionsPkg(staleSameName.desc, basePkg)) {
						handled = true; // no known ancestor preference either way — keep the base's own typing.
					}
				}
				if (!handled) {
					baseN.methods.add(om);
					baseMethods.put(key, om);
					splicedMethods++;
					recordAnonymousOverrides(name, om, otherPkg);
				}
				continue;
			}
			Set<String> forceForge = FORCE_FORGE_METHODS.get(name);
			if (forceForge != null && forceForge.contains(key) && FORGE_PKG.equals(otherPkg)) {
				// Hand-verified override (see FORCE_FORGE_METHODS): this method must agree with a field-type
				// decision that isn't this class's overall default — take Forge's body unconditionally.
				replaceMethod(baseN, key, om, baseMethods);
				splicedMethods++;
				recordAnonymousOverrides(name, om, otherPkg);
				continue;
			}

			boolean otherHook = methodPatched(om, vm, otherPkg);
			boolean baseHook = methodPatched(bm, vm, basePkg);
			if (otherHook && !baseHook && writesBaseExclusiveFieldOtherDrops(name, bm, om, baseExclusiveAddedFields)) {
				// The other side hooked this method (usually <init>) and base didn't — normally we'd take the
				// other's body. But base's body is the ONLY one that initializes a base-exclusive added field
				// (the other side doesn't know the field exists), so replacing would leave it null. Keep base's
				// body: the field stays initialized; the other side's hook here is the (documented) casualty —
				// consistent with the general "the field-owner's writer wins" principle. (ChunkStatus.<init>.)
				fieldInitPreserved++;
				conflicts.add(name + "#" + om.name + om.desc + " (kept " + (baseIsForge ? "forge" : "neo")
						+ " body to preserve base-added field init; " + (baseIsForge ? "neo" : "forge") + " hook lost)");
			} else if (otherHook && !baseHook) {
				// The other side injected a hook here, base left it vanilla — take its body (keeps the hook).
				replaceMethod(baseN, key, om, baseMethods);
				splicedMethods++;
				recordAnonymousOverrides(name, om, otherPkg);
			} else if (otherHook && baseHook) {
				// Both injected hooks into the same method — keep the base's body; the other side's hook here
				// is LOST (recorded for hand-reconciliation). This is the real tri-in-one conflict set.
				conflictMethods++;
				conflicts.add(name + "#" + om.name + om.desc + (baseIsForge ? " (neo hook lost)" : " (forge hook lost)"));
			}
			// else (neither hooked): keep the base's body — semantically vanilla on both sides, nothing lost.
		}

		resolveDuplicateFieldNames(baseN);
		resolveDiamondDefaults(baseN);
		Set<String> allExclusiveAdded = new LinkedHashSet<>(baseExclusiveAddedFields);
		allExclusiveAdded.addAll(otherExclusiveAddedFields);
		injectMissingExclusiveFieldInits(baseN, allExclusiveAdded);
		auditBaseAddedFieldWriters(baseN, baseExclusiveAddedFields);

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		baseN.accept(cw);
		return cw.toByteArray();
	}

	/**
	 * For every exclusive-added reference field of {@code cn} that NO method of {@code cn} writes (its only writer,
	 * the losing side's {@code <init>}, was dropped by the method-merge — typically after a superclass-forced base
	 * flip), inject a default {@code this.field = new <Impl>()} at the end of each non-delegating constructor,
	 * where {@code <Impl>} is the field's own type when it is a concrete class with a public no-arg constructor
	 * (e.g. NeoForge's {@code Gui.screenLayers} {@code Stack}), OR a known empty-collection impl from
	 * {@link #INTERFACE_DEFAULT_IMPLS} when the field is interface-typed (e.g. {@code ClientLevel.partEntities}
	 * {@code Int2ObjectMap} → {@code Int2ObjectOpenHashMap}). An empty instance is the correct "absent" value for
	 * the collection/holder fields this covers; a field whose type is neither default-constructible nor mapped is
	 * left for the {@code auditBaseAddedFieldWriters} report rather than guessed.
	 */
	private void injectMissingExclusiveFieldInits(ClassNode cn, Set<String> exclusiveAdded) {
		if (exclusiveAdded.isEmpty()) return;
		Set<String> writtenAnywhere = new LinkedHashSet<>();
		for (MethodNode m : cn.methods) writtenAnywhere.addAll(writtenFieldKeys(m, cn.name));

		for (String key : exclusiveAdded) {
			if (writtenAnywhere.contains(key)) continue; // already initialized by some surviving method
			int sp = key.indexOf(' ');
			String fName = key.substring(0, sp);
			String fDesc = key.substring(sp + 1);
			if (!fDesc.startsWith("L") || !fDesc.endsWith(";")) continue; // only object types get a no-arg default
			String type = fDesc.substring(1, fDesc.length() - 1);
			// The field's own type if it's concrete + no-arg constructible; else a known empty-collection impl
			// for interface-typed fields (fastutil/JDK collections) whose only writer was dropped by the merge.
			String implType = hasPublicNoArgConstructor(type) ? type : INTERFACE_DEFAULT_IMPLS.get(type);
			if (implType == null) {
				structuralConflicts.add(cn.name + "#" + fName + " (exclusive-added field with no surviving writer and no"
						+ " default-constructible type " + type + " — left null)");
				continue;
			}
			boolean injectedAny = false;
			for (MethodNode m : cn.methods) {
				if (!m.name.equals("<init>") || m.instructions == null) continue;
				if (delegatesToSisterConstructor(m, cn.name)) continue; // the delegated-to ctor gets the init instead
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					int op = insn.getOpcode();
					if (op < Opcodes.IRETURN || op > Opcodes.RETURN) continue; // insert before every return
					m.instructions.insertBefore(insn, emitDefaultInit(cn.name, fName, fDesc, implType));
					injectedAny = true;
				}
			}
			if (injectedAny) exclusiveFieldsInitialized++;
		}
	}

	/** {@code this.<fName> = new <implType>()} — implType is assignable to the (possibly interface-typed) fDesc. */
	private static org.objectweb.asm.tree.InsnList emitDefaultInit(String owner, String fName, String fDesc,
			String implType) {
		org.objectweb.asm.tree.InsnList init = new org.objectweb.asm.tree.InsnList();
		init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
		init.add(new TypeInsnNode(Opcodes.NEW, implType));
		init.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
		init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, implType, "<init>", "()V", false));
		init.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, fName, fDesc));
		return init;
	}

	/** Whether {@code m} chains to another constructor of the SAME class ({@code this(...)}) rather than super. */
	private static boolean delegatesToSisterConstructor(MethodNode m, String owner) {
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL
					&& mi.name.equals("<init>") && mi.owner.equals(owner)) {
				return true;
			}
		}
		return false;
	}

	/** Whether {@code internalType} is a concrete class (on the tool classpath, i.e. JDK/library) with a public no-arg ctor. */
	private static boolean hasPublicNoArgConstructor(String internalType) {
		try {
			Class<?> c = Class.forName(internalType.replace('/', '.'), false, MergedBaseBuilder.class.getClassLoader());
			if (c.isInterface() || java.lang.reflect.Modifier.isAbstract(c.getModifiers())) return false;
			c.getConstructor(); // public no-arg
			return true;
		} catch (Throwable notDefaultConstructible) {
			return false;
		}
	}

	/** All (name+desc) keys of instance fields of {@code owner} that {@code m} writes via PUTFIELD/PUTSTATIC. */
	private static Set<String> writtenFieldKeys(MethodNode m, String owner) {
		Set<String> out = new LinkedHashSet<>();
		if (m == null || m.instructions == null) return out;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getType() != AbstractInsnNode.FIELD_INSN) continue;
			org.objectweb.asm.tree.FieldInsnNode fi = (org.objectweb.asm.tree.FieldInsnNode) insn;
			if (fi.getOpcode() != Opcodes.PUTFIELD && fi.getOpcode() != Opcodes.PUTSTATIC) continue;
			if (!fi.owner.equals(owner)) continue;
			out.add(fi.name + " " + fi.desc);
		}
		return out;
	}

	/**
	 * True when the base method writes at least one {@code baseExclusiveAdded} field that the other method does
	 * NOT write — i.e. replacing the base body with the other's would strand that field null. Drives the
	 * "keep base body to preserve added-field init" resolution in the method loop.
	 */
	private static boolean writesBaseExclusiveFieldOtherDrops(String owner, MethodNode baseM, MethodNode otherM,
			Set<String> baseExclusiveAdded) {
		if (baseExclusiveAdded.isEmpty()) return false;
		Set<String> baseWrites = writtenFieldKeys(baseM, owner);
		baseWrites.retainAll(baseExclusiveAdded);
		if (baseWrites.isEmpty()) return false;
		baseWrites.removeAll(writtenFieldKeys(otherM, owner));
		return !baseWrites.isEmpty();
	}

	/**
	 * Safety net: after all method decisions, any base-exclusive added reference field STILL present on the merged
	 * class but with no surviving PUTFIELD writer anywhere in it will be null at runtime. Records each such field
	 * (with its declaring method-less location) so the merge report names the next class to reconcile — this is
	 * the general detector for the ChunkStatus/jigsaw class of defect (a lone-side field whose initializer was
	 * lost to a method-splice), independent of whether {@link #writesBaseExclusiveFieldOtherDrops} caught it.
	 */
	private void auditBaseAddedFieldWriters(ClassNode cn, Set<String> baseExclusiveAdded) {
		if (baseExclusiveAdded.isEmpty()) return;
		Set<String> presentFields = new LinkedHashSet<>();
		for (FieldNode f : cn.fields) presentFields.add(f.name + " " + f.desc);
		Set<String> written = new LinkedHashSet<>();
		for (MethodNode m : cn.methods) written.addAll(writtenFieldKeys(m, cn.name));
		for (String k : baseExclusiveAdded) {
			if (!presentFields.contains(k)) continue; // removed by duplicate-field resolution — not a live field
			if (written.contains(k)) continue;
			String note = cn.name + "#" + k + " (base-added reference field has NO surviving writer — null at runtime)";
			if (!structuralConflicts.contains(note)) structuralConflicts.add(note);
		}
	}

	/**
	 * Resolves same-NAME, different-DESC field pairs left over from field splicing: when the base lacks a
	 * field matching the other side's EXACT key (name+desc), step 2 treats it as genuinely new and adds it — but
	 * if the base ALREADY has a field of the SAME NAME with a DIFFERENT type, this is the SAME conceptual field
	 * retyped per-ecosystem (e.g. {@code ChunkGenerator.featuresPerStep}: Neo types it
	 * {@code Supplier<List<StepFeatureData>>}, Forge types it {@code ClearableLazy<List<StepFeatureData>>}) —
	 * not two independent fields. Keeping BOTH declarations compiles fine but only ONE ever gets a value from
	 * {@code <init>} (whichever side's constructor survived the method-splice above); the OTHER stays at its
	 * zero-initialized default (null for references), so whichever method reads IT throws an NPE the first time
	 * it's used (caught empirically: {@code ChunkGenerator.applyBiomeDecoration} on the un-written
	 * {@code ClearableLazy}-typed duplicate). Fix: keep only the field a surviving method actually accesses
	 * (post-splice) — {@code PUTFIELD}/{@code GETFIELD} for instance fields, {@code PUTSTATIC}/{@code GETSTATIC}
	 * for static ones (e.g. client {@code KeyMapping.MAP}, written by the winning {@code <clinit>}); drop the
	 * other same-named duplicate entirely.
	 */
	private void resolveDuplicateFieldNames(ClassNode cn) {
		Map<String, List<FieldNode>> byName = new LinkedHashMap<>();
		for (FieldNode f : cn.fields) byName.computeIfAbsent(f.name, k -> new ArrayList<>()).add(f);

		for (Map.Entry<String, List<FieldNode>> e : byName.entrySet()) {
			List<FieldNode> dups = e.getValue();
			if (dups.size() < 2) continue;

			Set<String> writtenDescs = new LinkedHashSet<>();
			Set<String> readDescs = new LinkedHashSet<>();
			for (MethodNode m : cn.methods) {
				if (m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getType() != AbstractInsnNode.FIELD_INSN) continue;
					org.objectweb.asm.tree.FieldInsnNode fi = (org.objectweb.asm.tree.FieldInsnNode) insn;
					if (!fi.name.equals(e.getKey())) continue;
					// Consider BOTH instance (PUT/GETFIELD) and STATIC (PUT/GETSTATIC) access. Duplicate same-name
					// fields can be static (e.g. client KeyMapping.MAP — Forge types it its own KeyMappingLookup,
					// Neo types it its own; the merged <clinit> PUTSTATICs whichever side's methods won). Scanning
					// only the instance opcodes left both static variants looking dead -> BOTH removed -> the winning
					// <clinit>'s PUTSTATIC then hits NoSuchFieldError at class init (caught empirically on the client
					// merged base). A static field IS written by <clinit>, which is itself one of cn.methods.
					int op = fi.getOpcode();
					if (op == Opcodes.PUTFIELD || op == Opcodes.PUTSTATIC) writtenDescs.add(fi.desc);
					else if (op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC) readDescs.add(fi.desc);
				}
			}

			// Only remove a duplicate that is NEITHER written NOR read anywhere in the class (fully dead) —
			// a variant that IS read by some surviving method (e.g. a winning "otherHook" method) but never
			// written by <init> is a genuine CROSS-METHOD dependency conflict (the winning method needs state
			// only the LOSING side's <init> would have set up — caught empirically: ChunkGenerator's Forge-
			// sourced applyBiomeDecoration() reads the ClearableLazy variant, but Neo's surviving <init> only
			// writes the Supplier variant). Removing the read-but-unwritten variant would trade an NPE for a
			// (no better) NoSuchFieldError; recording it and leaving BOTH declarations in place is the safe,
			// non-regressing choice — it stays a documented conflict, not a guess.
			List<FieldNode> dead = new ArrayList<>();
			for (FieldNode f : dups) {
				if (!writtenDescs.contains(f.desc) && !readDescs.contains(f.desc)) dead.add(f);
			}
			boolean crossMethodConflict = dups.stream().anyMatch(f -> readDescs.contains(f.desc) && !writtenDescs.contains(f.desc));
			if (crossMethodConflict) {
				String key = cn.name + "#" + e.getKey() + " (read by a winning method but never initialized —"
						+ " cross-method field dependency)";
				if (!structuralConflicts.contains(key)) structuralConflicts.add(key);
			}
			if (!dead.isEmpty()) {
				cn.fields.removeAll(dead);
				duplicateFieldsResolved++;
			}
		}
	}

	/**
	 * PASS 4 body: repair <em>pipeline-divergent anonymous siblings</em>.
	 *
	 * <p>Forge's and Neo's decompile+recompile pipelines each emit their own {@code Outer$N} for the anonymous
	 * classes of {@code Outer}. The merged jar can hold only ONE class at that name, but the merged {@code Outer}
	 * may legitimately retain a method from EACH ecosystem that constructs it — when the two methods' own
	 * descriptors differ (e.g. Forge's {@code codec(fallback,list)} vs Neo's
	 * {@code codec(fallback,list,protocol,flow)}) they are not a same-key collision, so both survive PASS 2 and the
	 * {@code splicedMethodNeedsDivergentAnonymous} guard never fires. Whichever side lost the {@code $N} slot then
	 * holds an {@code invokespecial} to a constructor shape that no longer exists — a {@code NoSuchMethodError}
	 * detonating far from the merge, at first use of that code path.
	 *
	 * <p>Repair: for each such dangling {@code invokespecial Outer$N.<init>(desc)}, find the ecosystem whose
	 * {@code Outer$N} DOES declare {@code desc}, materialize that side's class under a fresh name
	 * ({@code Outer$N$forbric<eco>}), and repoint the references to {@code Outer$N} <em>within the offending method
	 * only</em> — the method is self-consistent, so the rewrite is total for it and invisible to every other method.
	 * Both ecosystems then get the shape they compiled against.
	 *
	 * <p>Materialized classes and rewritten owners are written straight back into {@code outEntries}.
	 */
	private void materializeDivergentAnonymousSiblings(Map<String, ClassNode> allNodes,
			Map<String, byte[]> outEntries, Map<String, byte[]> forge, Map<String, byte[]> neo) {
		// Constructor descriptors each merged class actually declares.
		Map<String, Set<String>> mergedCtors = new LinkedHashMap<>();
		for (ClassNode cn : allNodes.values()) {
			Set<String> ds = new LinkedHashSet<>();
			for (MethodNode m : cn.methods) if (m.name.equals("<init>")) ds.add(m.desc);
			mergedCtors.put(cn.name, ds);
		}

		Map<String, String> materialized = new LinkedHashMap<>(); // "owner\0desc" -> new class internal name
		Set<String> touchedOwners = new LinkedHashSet<>();

		for (ClassNode cn : new ArrayList<>(allNodes.values())) {
			for (MethodNode mn : cn.methods) {
				if (mn.instructions == null) continue;

				// Collect the anonymous-sibling ctors this method calls that the merged slot cannot provide.
				Map<String, String> remapForThisMethod = new LinkedHashMap<>(); // old internal -> new internal
				for (AbstractInsnNode insn : mn.instructions.toArray()) {
					if (!(insn instanceof MethodInsnNode min)) continue;
					if (min.getOpcode() != Opcodes.INVOKESPECIAL || !min.name.equals("<init>")) continue;
					if (!isAnonymousOrSynthetic(min.owner)) continue;
					Set<String> have = mergedCtors.get(min.owner);
					if (have == null || have.contains(min.desc)) continue; // slot satisfies this call site

					String key = min.owner + "\0" + min.desc;
					String replacement = materialized.get(key);
					if (replacement == null) {
						replacement = materializeSibling(min.owner, min.desc, forge, neo, allNodes, outEntries,
								touchedOwners);
						if (replacement == null) continue; // no side declares it — leave for PASS 5 / the link check
						materialized.put(key, replacement);
					}
					remapForThisMethod.put(min.owner, replacement);
				}
				if (remapForThisMethod.isEmpty()) continue;

				remapReferencesWithinMethod(mn, remapForThisMethod);
				divergentAnonymousCallSitesRewritten++;
				touchedOwners.add(cn.name);
				conflicts.add(cn.name + "#" + mn.name + mn.desc
						+ " (repointed to a materialized pipeline-divergent anonymous sibling: " + remapForThisMethod + ")");
			}
		}

		for (String owner : touchedOwners) {
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			allNodes.get(owner).accept(cw);
			outEntries.put(owner, cw.toByteArray());
		}
	}

	/**
	 * Copy whichever ecosystem's {@code anonName} declares {@code ctorDesc} to a fresh internal name, registering it
	 * in {@code allNodes}/{@code outEntries}. Returns the new name, or null when neither side declares that ctor.
	 */
	private String materializeSibling(String anonName, String ctorDesc, Map<String, byte[]> forge,
			Map<String, byte[]> neo, Map<String, ClassNode> allNodes, Map<String, byte[]> outEntries,
			Set<String> touchedOwners) {
		String eco = null;
		byte[] src = null;
		if (declaresCtor(forge.get(anonName), ctorDesc)) {
			eco = "forge";
			src = forge.get(anonName);
		} else if (declaresCtor(neo.get(anonName), ctorDesc)) {
			eco = "neo";
			src = neo.get(anonName);
		}
		if (src == null) return null;

		String newName = anonName + "$forbric" + eco;
		if (allNodes.containsKey(newName)) return newName;

		// Full remap of the class onto its new name: own name, self-references in descriptors/signatures/frames,
		// InnerClasses/EnclosingMethod attributes. The enclosing OUTER class name is untouched, so the sibling stays
		// attached to the same (merged) outer.
		ClassReader cr = new ClassReader(src);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cr.accept(new ClassRemapper(cw, new SimpleRemapper(anonName, newName)), 0);
		byte[] bytes = cw.toByteArray();

		ClassNode newNode = parse(bytes);
		allNodes.put(newName, newNode);
		outEntries.put(newName, bytes);

		// NESTMATES (JEP 181): the copy keeps the original's NestHost, but the host's NestMembers still lists only
		// the ORIGINAL sibling. A nest host must name every member, or the JVM denies the member access to the nest's
		// private fields/methods — IllegalAccessError ("… is not a nest member of …: current type is not listed as a
		// nest member"), thrown at first private access rather than at load. Enroll the copy in the host's nest.
		// (Caught empirically: the materialized CustomPacketPayload$1 copy reads the private CustomPacketPayload$Type.id.)
		String host = newNode.nestHostClass != null ? newNode.nestHostClass : topLevel(anonName);
		ClassNode hostNode = allNodes.get(host);
		if (hostNode != null) {
			if (hostNode.nestMembers == null) hostNode.nestMembers = new ArrayList<>();
			if (!hostNode.nestMembers.contains(newName)) {
				hostNode.nestMembers.add(newName);
				touchedOwners.add(host); // re-serialize the host with the widened nest
			}
		} else {
			System.out.println("[merge]   WARNING: nest host " + host + " of " + newName
					+ " is not in the merged output — the copy may hit IllegalAccessError on private nest access");
		}

		divergentAnonymousMaterialized++;
		System.out.println("[merge]   materialized " + newName + " (" + eco + "'s " + anonName
				+ ", ctor " + ctorDesc + ") — the merged slot holds the other pipeline's shape; enrolled in nest " + host);
		return newName;
	}

	/** Whether the class bytes (may be null) declare {@code <init>desc}. */
	private static boolean declaresCtor(byte[] classBytes, String desc) {
		if (classBytes == null) return false;
		for (MethodNode m : parse(classBytes).methods) {
			if (m.name.equals("<init>") && m.desc.equals(desc)) return true;
		}
		return false;
	}

	/**
	 * Rewrite every reference to the keys of {@code remap} inside ONE method body — instruction owners/types, LDC
	 * class constants, and stack-map frame entries (the frames must agree with the new type or verification fails).
	 */
	private static void remapReferencesWithinMethod(MethodNode mn, Map<String, String> remap) {
		for (AbstractInsnNode insn : mn.instructions.toArray()) {
			if (insn instanceof TypeInsnNode t) {
				String r = remap.get(t.desc);
				if (r != null) t.desc = r;
			} else if (insn instanceof MethodInsnNode m) {
				String r = remap.get(m.owner);
				if (r != null) m.owner = r;
			} else if (insn instanceof FieldInsnNode f) {
				String r = remap.get(f.owner);
				if (r != null) f.owner = r;
			} else if (insn instanceof LdcInsnNode l && l.cst instanceof Type ty
					&& ty.getSort() == Type.OBJECT) {
				String r = remap.get(ty.getInternalName());
				if (r != null) l.cst = Type.getObjectType(r);
			} else if (insn instanceof FrameNode fr) {
				remapFrameList(fr.local, remap);
				remapFrameList(fr.stack, remap);
			}
		}
	}

	private static void remapFrameList(List<Object> entries, Map<String, String> remap) {
		if (entries == null) return;
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i) instanceof String s) {
				String r = remap.get(s);
				if (r != null) entries.set(i, r);
			}
		}
	}

	/**
	 * Replace the Neo materialized custom-payload codec lookup with a loader-neutral lookup. The helper returns an
	 * {@code Object} because the merge tool cannot compile against the live game interface; merged bytecode casts it
	 * back to {@code StreamCodec} in the game class loader.
	 */
	private void patchCustomPayloadInterop(Map<String, ClassNode> allNodes, Map<String, byte[]> outEntries) {
		ClassNode cn = allNodes.get(CUSTOM_PAYLOAD_NEO_CODEC);
		if (cn == null) return;

		String idToTypeDesc = fieldDesc(cn, "val$idToType", "Ljava/util/Map;");
		String protocolDesc = fieldDesc(cn, "val$protocol", "Lnet/minecraft/network/ConnectionProtocol;");
		String packetFlowDesc = fieldDesc(cn, "val$packetFlow", "Lnet/minecraft/network/protocol/PacketFlow;");
		String fallbackDesc = fieldDesc(cn, "val$fallback",
				"Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;");

		boolean changed = false;
		for (MethodNode m : cn.methods) {
			if (!m.name.equals("findCodec") || Type.getArgumentTypes(m.desc).length != 1) continue;
			org.objectweb.asm.tree.InsnList insn = new org.objectweb.asm.tree.InsnList();
			insn.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
			insn.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "val$idToType", idToTypeDesc));
			insn.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
			insn.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
			insn.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "val$protocol", protocolDesc));
			insn.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
			insn.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "val$packetFlow", packetFlowDesc));
			insn.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
			insn.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "val$fallback", fallbackDesc));
			insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CUSTOM_PAYLOAD_INTEROP, "findCodec",
					"(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
					false));
			insn.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/network/codec/StreamCodec"));
			insn.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));

			m.instructions = insn;
			m.tryCatchBlocks.clear();
			if (m.localVariables != null) m.localVariables.clear();
			customPayloadInteropPatched++;
			changed = true;
		}

		if (changed) {
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(cw);
			outEntries.put(cn.name, cw.toByteArray());
		}
	}

	private static String fieldDesc(ClassNode cn, String name, String fallback) {
		for (FieldNode f : cn.fields) {
			if (name.equals(f.name)) return f.desc;
		}
		return fallback;
	}

	/**
	 * PASS 5 body: find every {@code invokespecial owner.<init>(short)} whose {@code owner} (a merged class) lacks
	 * that ctor but declares exactly one {@code <init>(short + extra trailing params)}, and synthesize the short
	 * ctor as a delegate {@code this(short-args, <defaults>)}. Returns the set of owners that were modified.
	 *
	 * <p>Runs after the divergent-anonymous pass, which is the correct repair whenever the missing ctor exists in
	 * the other pipeline's copy of the class; only genuinely absent shapes reach this fallback.
	 */
	private Set<String> bridgeMissingReferencedConstructors(Map<String, ClassNode> allNodes) {
		// ctor descriptors each class declares, for quick "does owner have <init>(desc)?" checks.
		Map<String, Set<String>> ctorDescs = new LinkedHashMap<>();
		for (ClassNode cn : allNodes.values()) {
			Set<String> ds = new LinkedHashSet<>();
			for (MethodNode m : cn.methods) if (m.name.equals("<init>")) ds.add(m.desc);
			ctorDescs.put(cn.name, ds);
		}

		Set<String> modified = new LinkedHashSet<>();
		Set<String> attempted = new LinkedHashSet<>(); // owner+desc pairs already handled, to synthesize once
		for (ClassNode cn : allNodes.values()) {
			for (MethodNode m : cn.methods) {
				if (m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (!(insn instanceof MethodInsnNode mi)) continue;
					if (mi.getOpcode() != Opcodes.INVOKESPECIAL || !mi.name.equals("<init>")) continue;
					Set<String> owned = ctorDescs.get(mi.owner);
					if (owned == null || owned.contains(mi.desc)) continue; // owner not merged, or ctor already present
					String pairKey = mi.owner + mi.desc;
					if (!attempted.add(pairKey)) continue;

					String longer = uniqueSupersetConstructor(owned, mi.desc);
					if (longer == null) continue; // no single trailing-superset ctor to delegate to — leave for the report
					ClassNode owner = allNodes.get(mi.owner);
					owner.methods.add(synthesizeBridgeConstructor(owner.name, mi.desc, longer));
					owned.add(mi.desc);
					bridgedConstructors++;
					modified.add(mi.owner);
				}
			}
		}
		return modified;
	}

	/**
	 * If exactly one of {@code candidates} is {@code shortDesc}'s parameter list plus one-or-more EXTRA trailing
	 * parameters (a strict prefix superset), return it; else null (zero or ambiguous — not safe to auto-bridge).
	 */
	private static String uniqueSupersetConstructor(Set<String> candidates, String shortDesc) {
		org.objectweb.asm.Type[] shortArgs = org.objectweb.asm.Type.getArgumentTypes(shortDesc);
		String found = null;
		for (String cand : candidates) {
			org.objectweb.asm.Type[] a = org.objectweb.asm.Type.getArgumentTypes(cand);
			if (a.length <= shortArgs.length) continue;
			boolean prefix = true;
			for (int i = 0; i < shortArgs.length; i++) {
				if (!a[i].equals(shortArgs[i])) { prefix = false; break; }
			}
			if (!prefix) continue;
			if (found != null) return null; // ambiguous: more than one superset
			found = cand;
		}
		return found;
	}

	/** Build {@code <init>(shortDesc)} whose body is {@code this(short-args…, <default>…); return}. */
	private static MethodNode synthesizeBridgeConstructor(String ownerName, String shortDesc, String longDesc) {
		org.objectweb.asm.Type[] shortArgs = org.objectweb.asm.Type.getArgumentTypes(shortDesc);
		org.objectweb.asm.Type[] longArgs = org.objectweb.asm.Type.getArgumentTypes(longDesc);
		MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE, "<init>", shortDesc, null, null);
		org.objectweb.asm.tree.InsnList in = mn.instructions;
		in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0)); // this
		int local = 1;
		for (org.objectweb.asm.Type t : shortArgs) {
			in.add(new org.objectweb.asm.tree.VarInsnNode(t.getOpcode(Opcodes.ILOAD), local));
			local += t.getSize();
		}
		for (int i = shortArgs.length; i < longArgs.length; i++) {
			in.add(defaultValueInsn(longArgs[i]));
		}
		in.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ownerName, "<init>", longDesc, false));
		in.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
		return mn;
	}

	/** The zero/null literal instruction for a parameter type — the "absent" value for a bridged trailing arg. */
	private static AbstractInsnNode defaultValueInsn(org.objectweb.asm.Type t) {
		switch (t.getSort()) {
			case org.objectweb.asm.Type.OBJECT:
			case org.objectweb.asm.Type.ARRAY:
				return new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL);
			case org.objectweb.asm.Type.LONG:
				return new org.objectweb.asm.tree.InsnNode(Opcodes.LCONST_0);
			case org.objectweb.asm.Type.FLOAT:
				return new org.objectweb.asm.tree.InsnNode(Opcodes.FCONST_0);
			case org.objectweb.asm.Type.DOUBLE:
				return new org.objectweb.asm.tree.InsnNode(Opcodes.DCONST_0);
			default: // boolean/byte/char/short/int
				return new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0);
		}
	}

	/**
	 * Scans a method body that was just spliced in from the OTHER ecosystem for {@code NEW} references to
	 * anonymous/synthetic siblings in the SAME top-level family ({@code family$<digits>}), and records that
	 * each such anonymous class must be taken from {@code pkg} — overriding the default "stay on Neo" rule for
	 * anonymous classes (see {@link #anonymousPackageOverride}).
	 */
	private void recordAnonymousOverrides(String family, MethodNode m, String pkg) {
		if (m.instructions == null) return;
		String prefix = family + "$";
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getType() != AbstractInsnNode.TYPE_INSN) continue;
			org.objectweb.asm.tree.TypeInsnNode t = (org.objectweb.asm.tree.TypeInsnNode) insn;
			if (t.getOpcode() != Opcodes.NEW) continue;
			if (!t.desc.startsWith(prefix) || !isAnonymousOrSynthetic(t.desc)) continue;

			String existing = anonymousPackageOverride.get(t.desc);
			if (existing != null && !existing.equals(pkg)) {
				// A genuine dual-need conflict: TWO different other-side-sourced methods in this family need
				// the SAME numbered anonymous class from DIFFERENT ecosystems (caught empirically —
				// CustomPacketPayload$1 is needed as Forge's 2-field shape by ClientboundCustomPayloadPacket
				// AND as Neo's 4-field shape by ServerboundCustomPayloadPacket). The numeric slot represents a
				// DIFFERENT logical anonymous helper per pipeline here — unresolvable by numbering-based
				// pairing. Revert to no override (the family's default pipeline) and record it; whichever
				// caller needed the OTHER shape loses that specific call path.
				anonymousPackageOverride.remove(t.desc);
				String key = t.desc + " (needed as both " + existing + " and " + pkg + ")";
				if (!structuralConflicts.contains(key)) structuralConflicts.add(key);
				continue;
			}
			anonymousPackageOverride.put(t.desc, pkg);
		}
	}

	/**
	 * True when a method about to be spliced in from the OTHER pipeline {@code NEW}s an anonymous sibling whose
	 * FORGE and NEO versions are structurally INCOMPATIBLE — i.e. the two decompilers assigned the SAME numeric
	 * slot ({@code Outer$1}) to DIFFERENT logical classes (different interfaces/superclass). The merged base keeps
	 * ONE version of that slot (the base/Neo one, which the base's own surviving methods — {@code <clinit>},
	 * lambdas — reference), so a spliced method that needs the OTHER version cannot run against it. Splicing it
	 * would either strand a mismatched instance (caught empirically: Forge's {@code CompoundTag.builder()} NEWs
	 * {@code CompoundTag$1}=INBTBuilder, but the slot holds Neo's {@code CompoundTag$1}=TagType, so
	 * {@code CompoundTag.TYPE} ends up an INBTBuilder and {@code TagTypes.<clinit>} throws ArrayStoreException) or
	 * fail verification. Such a method must be DROPPED, not spliced — its capability is the documented casualty.
	 * (This is distinct from {@link #recordAnonymousOverrides}'s dual-need case: there TWO spliced methods clash;
	 * here a spliced method clashes with the base's own, un-spliced usage of the slot.)
	 */
	private boolean splicedMethodNeedsDivergentAnonymous(String family, MethodNode m) {
		if (m.instructions == null) return false;
		String prefix = family + "$";
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getType() != AbstractInsnNode.TYPE_INSN) continue;
			org.objectweb.asm.tree.TypeInsnNode t = (org.objectweb.asm.tree.TypeInsnNode) insn;
			if (t.getOpcode() != Opcodes.NEW) continue;
			if (!t.desc.startsWith(prefix) || !isAnonymousOrSynthetic(t.desc)) continue;
			byte[] fb = forgeClasses.get(t.desc), nb = neoClasses.get(t.desc);
			if (fb == null || nb == null) continue; // only one pipeline has this slot — no collision
			ClassNode fc = parse(fb), nc = parse(nb);
			boolean sameIfaces = new java.util.HashSet<>(fc.interfaces).equals(new java.util.HashSet<>(nc.interfaces));
			boolean sameSuper = java.util.Objects.equals(fc.superName, nc.superName);
			if (!sameIfaces || !sameSuper) return true; // same slot, different logical class per pipeline
		}
		return false;
	}

	private static void replaceMethod(ClassNode cn, String key, MethodNode repl, Map<String, MethodNode> index) {
		for (int i = 0; i < cn.methods.size(); i++) {
			MethodNode m = cn.methods.get(i);
			if ((m.name + m.desc).equals(key)) {
				cn.methods.set(i, repl);
				index.put(key, repl);
				return;
			}
		}
	}

	private static boolean mentionsPkg(String desc, String pkg) {
		return desc != null && desc.contains("L" + pkg);
	}

	/** The {@link #STRUCTURAL_ANCESTOR_TYPE} value referenced by either descriptor, or null if neither is known. */
	private static String preferredAncestorType(String descA, String descB) {
		for (String type : STRUCTURAL_ANCESTOR_TYPE.values()) {
			if (descA.contains("L" + type) || descB.contains("L" + type)) return type;
		}
		return null;
	}

	// --- jar io --------------------------------------------------------------------------------------

	private static Map<String, byte[]> readClasses(Path jar) throws IOException {
		Map<String, byte[]> out = new LinkedHashMap<>();
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			var it = zf.entries();
			while (it.hasMoreElements()) {
				ZipEntry e = it.nextElement();
				if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
				String internal = e.getName().substring(0, e.getName().length() - ".class".length());
				out.put(internal, zf.getInputStream(e).readAllBytes());
			}
		}
		return out;
	}

	private static Set<String> collectResourceNames(Path jar) throws IOException {
		Set<String> names = new LinkedHashSet<>();
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			var it = zf.entries();
			while (it.hasMoreElements()) {
				ZipEntry e = it.nextElement();
				if (!e.isDirectory() && !e.getName().endsWith(".class")) names.add(e.getName());
			}
		}
		return names;
	}

	private static void copyResources(Path jar, ZipOutputStream zos, Set<String> classNames) throws IOException {
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			var it = zf.entries();
			while (it.hasMoreElements()) {
				ZipEntry e = it.nextElement();
				if (e.isDirectory() || e.getName().endsWith(".class")) continue;
				if (e.getName().equals("META-INF/MANIFEST.MF")) continue; // fresh jar, no stale signing
				zos.putNextEntry(new ZipEntry(e.getName()));
				zf.getInputStream(e).transferTo(zos);
				zos.closeEntry();
			}
		}
	}

	private static void copyResourcesMissing(Path jar, ZipOutputStream zos, Set<String> classNames,
			Set<String> already) throws IOException {
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			var it = zf.entries();
			while (it.hasMoreElements()) {
				ZipEntry e = it.nextElement();
				if (e.isDirectory() || e.getName().endsWith(".class")) continue;
				if (e.getName().equals("META-INF/MANIFEST.MF") || already.contains(e.getName())) continue;
				zos.putNextEntry(new ZipEntry(e.getName()));
				zf.getInputStream(e).transferTo(zos);
				zos.closeEntry();
			}
		}
	}

	// --- asm helpers ---------------------------------------------------------------------------------

	private static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	private static Map<String, MethodNode> methodMap(ClassNode cn) {
		Map<String, MethodNode> m = new LinkedHashMap<>();
		if (cn != null) for (MethodNode mn : cn.methods) m.put(mn.name + mn.desc, mn);
		return m;
	}

	private static Map<String, FieldNode> fieldMap(ClassNode cn) {
		Map<String, FieldNode> m = new LinkedHashMap<>();
		if (cn != null) for (FieldNode fn : cn.fields) m.put(fn.name + " " + fn.desc, fn);
		return m;
	}

	private void summarize(PrintStream ps) {
		ps.println("[merge] classes: vanilla=" + classesTakenVanilla + " forge-only-ns=" + classesForgeOnly
				+ " forge=" + classesTakenForge + " neo=" + classesTakenNeo + " MERGED=" + classesMerged
				+ " (of which superclass-rebased-to-forge=" + superclassRebased + ")");
		ps.println("[merge] spliced into merged classes: methods=" + splicedMethods + " fields=" + splicedFields
				+ " interfaces=" + mergedInterfaces + " diamond-defaults-resolved=" + diamondDefaultsResolved
				+ " duplicate-fields-resolved=" + duplicateFieldsResolved + " field-init-preserved=" + fieldInitPreserved
				+ " bridged-constructors=" + bridgedConstructors + " exclusive-fields-initialized=" + exclusiveFieldsInitialized
				+ " divergent-anonymous-materialized=" + divergentAnonymousMaterialized
				+ " (call sites repointed=" + divergentAnonymousCallSitesRewritten + ")"
				+ " custom-payload-interop=" + customPayloadInteropPatched);
		ps.println("[merge] CONFLICTS: methods=" + conflictMethods + " fields=" + conflictFields
				+ " STRUCTURAL(superclass/field)=" + structuralConflicts.size()
				+ " collision-methods-dropped=" + collisionMethodsDropped
				+ " class-access-widened=" + classAccessWidened);
	}
}
