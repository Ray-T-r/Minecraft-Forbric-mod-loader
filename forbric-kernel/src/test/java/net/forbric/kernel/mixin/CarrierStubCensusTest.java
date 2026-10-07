package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Re-derives {@code carrier-stubs.txt} — every merged-base method that is a pure delegating stub to a same-name overload
 * the CARRIER added (vanilla has the stub's signature and not the overload's), and one that works an argument out first
 * where the carrier only pointed vanilla's own calls of that signature at the overload — and asserts the shipped table
 * equals it.
 * MixinStubRebind moves an injector only along a row here: where vanilla has both overloads itself, a mod that chose the
 * short one meant it. Each row also says, per Forge family, what that carrier's OWN patched class has at the stub's
 * signature ({@link MixinStubRebind.Shape}) — whether a mod of that family was compiled against code there or against the
 * same stub.
 */
class CarrierStubCensusTest {
	private static final Path MERGED = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path MC = TestFixtures.minecraftDir();
	/**
	 * The two jars the merge merged. For MinecraftForge that is {@link TestFixtures#forgeMergeInput}, never whatever sits
	 * in forge-patched/: forbric-loader's is an older build, and its LivingEntity differs from the one the merge took.
	 */
	private static final Path FORGE = TestFixtures.forgeMergeInput();
	private static final Path NEO = TestFixtures.stagedRoot().resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final Path VANILLA = MC.resolve("versions/26.2/26.2.jar");
	private static final Path INTEROP = TestFixtures.stagedRoot().resolve("merged-base/forge-runtime-interop.jar");
	private static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path KERNEL_MAIN = Path.of("build/classes/java/main");
	private static final Path KERNEL_RUNTIME = Path.of("build/classes/java/runtime");

	@Test void theShippedTableIsExactlyWhatTheArtifactsSay() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "merged base and vanilla jar required");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(VANILLA), "merged base and vanilla jar required");
		// The rows themselves need only the merged base and vanilla; each row's forge=/neo= columns need both carriers.
		boolean carriers = Files.isRegularFile(FORGE) && Files.isRegularFile(NEO);
		Map<String, ClassNode> vanilla = read(VANILLA, true);
		// Who calls what, by name and descriptor whatever the owner: in vanilla, and in the merged game the kernel runs.
		Map<String, Set<String>> vanillaCalls = callers(List.of(VANILLA));
		Map<String, Set<String>> mergedCalls = null;   // read on the first stub that works an argument out
		TreeSet<String> rows = new TreeSet<>();
		try (ZipFile zip = new ZipFile(MERGED.toFile()); ZipFile forge = carriers ? new ZipFile(FORGE.toFile()) : null;
				ZipFile neo = carriers ? new ZipFile(NEO.toFile()) : null) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().endsWith(".class") || !entry.getName().startsWith("net/minecraft/")) continue;
				ClassNode merged = new ClassNode();
				new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(merged, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				ClassNode original = vanilla.get(merged.name);
				if (original == null) continue;
				for (MethodNode stub : merged.methods) {
					// A compiler's generic bridge forwards to its typed overload too, and is no carrier's doing.
					if ((stub.access & (org.objectweb.asm.Opcodes.ACC_BRIDGE | org.objectweb.asm.Opcodes.ACC_SYNTHETIC)) != 0) continue;
					MixinStubRebind.Delegation delegation = MixinStubRebind.delegation(merged, stub);
					if (delegation == null) continue;
					// A stub that works an argument out heads a row only where the carrier pointed vanilla's own calls at
					// the body and nothing else: every caller of the body in the merged game is a method that calls the
					// stub's signature in vanilla. There a mod's injector runs exactly where it runs on vanilla; left on
					// the stub, nothing reaches it (see MixinStubRebind.Delegation).
					if (delegation.computed()) {
						if (mergedCalls == null) {
							TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(KERNEL_RUNTIME), "the merged game's callers include the kernel's");
							mergedCalls = callers(List.of(MERGED, INTEROP, NEO_RUNTIME, KERNEL_MAIN, KERNEL_RUNTIME));
						}
						String self = merged.name + "#" + stub.name + stub.desc;
						Set<String> fromVanilla = vanillaCalls.getOrDefault(stub.name + stub.desc, Set.of());
						Set<String> fromMerged = new java.util.HashSet<>(mergedCalls.getOrDefault(
								delegation.delegate().name + delegation.delegate().desc, Set.of()));
						fromMerged.remove(self);
						if (fromMerged.isEmpty() || !fromVanilla.containsAll(fromMerged)) continue;
					}
					String delegate = delegation.delegate().desc;
					if (!declares(original, stub.name, stub.desc) || declares(original, delegation.delegate().name, delegate)) continue;
					String row = merged.name + "#" + stub.name + stub.desc + " -> " + delegate;
					rows.add(!carriers ? row : row
							+ " forge=" + MixinStubRebind.Shape.of(entry(forge, merged.name), stub.name, stub.desc, delegate).token
							+ " neo=" + MixinStubRebind.Shape.of(entry(neo, merged.name), stub.name, stub.desc, delegate).token);
				}
			}
		}
		List<String> shipped = new ArrayList<>();
		try (InputStream in = MixinStubRebind.class.getResourceAsStream(MixinStubRebind.TABLE)) {
			assertNotNull(in, MixinStubRebind.TABLE + " is missing");
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.isBlank() && !line.startsWith("#")) shipped.add(line.trim());
			}
		}
		if (!carriers) {
			assertEquals(rows, new TreeSet<>(shipped.stream().map(line -> line.replaceAll(" (forge|neo)=\\S+", "")).toList()),
					"carrier-stubs.txt's rows must equal what the staged merged base and vanilla say");
			// Both are merge inputs, so staged: no vanilla install has either.
			TestFixtures.require(Fixture.STAGED, carriers, "both carriers' patched game jars required for the forge=/neo= columns");
		}
		if (System.getenv("FORBRIC_WRITE_CARRIER_STUBS") != null) {
			Path out = Path.of("src/main/resources" + MixinStubRebind.TABLE);
			Files.writeString(out, "# Generated by CarrierStubCensusTest (FORBRIC_WRITE_CARRIER_STUBS=1): merged-base delegating stubs to a\n"
					+ "# same-name overload the carrier added. MixinStubRebind moves an injector only along these rows: a Fabric mod's\n"
					+ "# on every row; a MinecraftForge (forge=) or NeoForge (neo=) mod's only where its own carrier's patched class\n"
					+ "# ran that selector on code - body (vanilla's signature is the body there, first of its name), descriptor-body\n"
					+ "# (a body, declared after another overload: a descriptor selector only), overload-body (only the widened\n"
					+ "# overload is there: a name-only selector only). stub (the carrier keeps the same stub) and absent never move.\n"
					+ String.join("\n", rows) + "\n");
		}
		assertEquals(rows, new TreeSet<>(shipped), "carrier-stubs.txt must equal what the staged merged base, vanilla and "
				+ "both carriers say; regenerate with FORBRIC_WRITE_CARRIER_STUBS=1 after a base rebuild");
		// What fusion (MinecraftForge) was built against: MinecraftForge's ModelManager has both as bodies, NeoForge added
		// the overloads. The mirror: NeoForge kept PackDetector's signature as the body where MinecraftForge forwards.
		assertRow(rows, "net/minecraft/client/resources/model/ModelManager#loadModels(", "forge=body neo=stub");
		assertRow(rows, "net/minecraft/client/resources/model/ModelManager#discoverModelDependencies(", "forge=body neo=stub");
		assertRow(rows, "net/minecraft/server/packs/repository/PackDetector#detectPackResources(", "forge=stub neo=body");
		assertRow(rows, "net/minecraft/client/renderer/block/dispatch/multipart/MultiPartModel#collectParts(", "forge=stub neo=absent");
		assertRow(rows, "net/minecraft/world/level/ServerExplosion#hurtEntities(", "forge=overload-body neo=stub");
		assertRow(rows, "net/minecraft/world/item/AxeItem#evaluateNewBlockState(", "forge=stub neo=overload-body");
		assertRow(rows, "net/minecraft/client/multiplayer/ClientLevel#addBreakingBlockEffect(", "forge=descriptor-body neo=stub");
		assertRow(rows, "net/minecraft/world/entity/player/Player#getDestroySpeed(", "forge=stub neo=stub");
		// Stubs that work an argument out, which nothing in the merged game calls: LiquidBounce's sweep-attack sound, and
		// ViaFabricPlus' old fluid physics and collision bounce, hang on the vanilla signature.
		assertRow(rows, "net/minecraft/world/entity/player/Player#doSweepAttack(", "forge=body neo=stub");
		assertRow(rows, "net/minecraft/world/entity/EntityFluidInteraction#update(", "forge=body neo=stub");
		assertRow(rows, "net/minecraft/world/entity/Entity#restituteMovementAfterCollisions(", "forge=body neo=stub");
		// Not one whose body NeoForge's own code calls besides: its registry snapshot calls MappedRegistry.register(int, …)
		// directly, and fabric-registry-sync's add-entry callback stays on register(ResourceKey, …), where it fires on Fabric.
		assertTrue(rows.stream().noneMatch(r -> r.startsWith("net/minecraft/core/MappedRegistry#register(")), rows.toString());
	}

	private static void assertRow(TreeSet<String> rows, String head, String columns) {
		List<String> matching = rows.stream().filter(r -> r.startsWith(head)).toList();
		assertEquals(1, matching.size(), head + ": " + matching);
		assertTrue(matching.getFirst().endsWith(" " + columns), matching.getFirst());
	}

	private static ClassNode entry(ZipFile jar, String name) throws Exception {
		ZipEntry entry = jar.getEntry(name + ".class");
		if (entry == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return node;
	}

	/**
	 * Every method of {@code code} (jars or class directories) that calls a method or takes a handle to it, by the
	 * callee's {@code name + descriptor} — whatever the owner, so a call through any type counts.
	 */
	private static Map<String, Set<String>> callers(List<Path> code) throws Exception {
		Map<String, Set<String>> out = new HashMap<>();
		for (Path source : code) {
			List<byte[]> classes = new ArrayList<>();
			if (Files.isDirectory(source)) {
				try (java.util.stream.Stream<Path> files = Files.walk(source)) {
					for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) classes.add(Files.readAllBytes(f));
				}
			} else {
				try (ZipFile zip = new ZipFile(source.toFile())) {
					for (ZipEntry entry : Collections.list(zip.entries())) {
						if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/")) {
							classes.add(zip.getInputStream(entry).readAllBytes());
						}
					}
				}
			}
			for (byte[] bytes : classes) {
				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				for (MethodNode method : node.methods) {
					if (method.instructions == null) continue;
					String caller = node.name + "#" + method.name + method.desc;
					for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
						List<org.objectweb.asm.Handle> handles = new ArrayList<>();
						if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) {
							out.computeIfAbsent(call.name + call.desc, k -> new java.util.HashSet<>()).add(caller);
						} else if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
							for (Object argument : indy.bsmArgs) if (argument instanceof org.objectweb.asm.Handle h) handles.add(h);
						} else if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof org.objectweb.asm.Handle h) {
							handles.add(h);
						}
						for (org.objectweb.asm.Handle h : handles) {
							out.computeIfAbsent(h.getName() + h.getDesc(), k -> new java.util.HashSet<>()).add(caller);
						}
					}
				}
			}
		}
		return out;
	}

	private static boolean declares(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true;
		return false;
	}

	private static Map<String, ClassNode> read(Path jar, boolean skipCode) throws Exception {
		Map<String, ClassNode> out = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().endsWith(".class") || !entry.getName().startsWith("net/minecraft/")) continue;
				ClassNode node = new ClassNode();
				new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, skipCode ? ClassReader.SKIP_CODE : 0);
				out.put(node.name, node);
			}
		}
		return out;
	}
}
