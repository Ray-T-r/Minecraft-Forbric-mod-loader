package net.forbric.kernel.transform;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** NeoForge's coremod rewrites, done by the kernel, on the real merged classes. */
@ResourceLock("system-properties")
class NativeCoremodParityTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path FORGE = STAGED.resolve("merged-base/forge-runtime-interop.jar");
	private static final Path NEO_RUNTIME = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final Path VANILLA = TestFixtures.vanillaJar();
	/** tools/dev.py prepare downloads NeoForge's universal jar here; its coremods jar is nested inside. */
	private static final Path NEO_DOWNLOADS = TestFixtures.minecraftDir().resolve(".forbric-build/dl");
	/** A native NeoForge instance's extracted nested jars, on a machine that ran one. */
	private static final Path NEO_COREMODS = Path.of("build/journeymap-native/instance/.cache/jij");
	private static final String POT = "net/minecraft/world/level/block/FlowerPotBlock";
	private static final String BIOME = "net/minecraft/world/level/biome/Biome";
	private static final String STRUCTURE = "net/minecraft/world/level/levelgen/structure/Structure";
	private static final String LIQUID = "net/minecraft/world/level/block/LiquidBlock";
	private static final String BUCKET = "net/minecraft/world/item/BucketItem";

	@AfterEach void reset() {
		for (String key : List.of(NativeCoremodParity.PROPERTY, NativeCoremodParity.FLOWER_POT, NativeCoremodParity.BIOME,
				NativeCoremodParity.STRUCTURE, NativeCoremodParity.FINALIZE, NativeCoremodParity.FORGE_GETTERS)) System.clearProperty(key);
		NativeCoremodParity.readGameClassesFrom(null);
	}

	@Test void everyFlowerPotReadGoesThroughTheGetterExceptTheGetter() throws Exception {
		ClassNode after = rewritten(POT);
		assertEquals(0, reads(after, POT, "potted", m -> !m.desc.equals("()Lnet/minecraft/world/level/block/Block;") && !m.name.equals("<init>")));
		assertEquals(5, calls(after, POT, "getPotted"), "useWithoutItem, getCloneItemStack, isEmpty, randomTick and the codec");
		verify(after);
		byte[] once = NativeCoremodParity.apply(POT, read(MERGED, POT));
		assertSame(once, NativeCoremodParity.apply(POT, once), "a second pass changes nothing");
	}

	@Test void biomeAndStructureReadsGoThroughNeoForgesModifiedView() throws Exception {
		ClassNode biome = rewritten(BIOME);
		assertEquals(0, reads(biome, BIOME, "climateSettings", m -> !m.desc.equals("()Lnet/minecraft/world/level/biome/Biome$ClimateSettings;") && !m.name.equals("<init>")));
		assertEquals(0, reads(biome, BIOME, "specialEffects", m -> !m.desc.equals("()Lnet/minecraft/world/level/biome/BiomeSpecialEffects;") && !m.name.equals("<init>")));
		assertEquals(10, calls(biome, BIOME, "getModifiedClimateSettings") - calls(node(read(MERGED, BIOME)), BIOME, "getModifiedClimateSettings"));
		assertEquals(6, calls(biome, BIOME, "getModifiedSpecialEffects") - calls(node(read(MERGED, BIOME)), BIOME, "getModifiedSpecialEffects"));
		assertEquals(1, reads(biome, BIOME, "specialEffects", m -> m.name.equals("getSpecialEffects")), "getSpecialEffects stays raw, as NeoForge leaves it");
		verify(biome);
		ClassNode structure = rewritten(STRUCTURE);
		assertEquals(4, calls(structure, STRUCTURE, "getModifiedStructureSettings") - calls(node(read(MERGED, STRUCTURE)), STRUCTURE, "getModifiedStructureSettings"));
		verify(structure);
	}

	/**
	 * MinecraftForge's own rules on the merged base: its {@code (Supplier, …)} constructors store null in the field, so
	 * every read but the getter's goes through the getter — the liquid's onPlace, spread and pickup, the bucket's use and
	 * emptying, and vanilla's {@code getContent()}, which MinecraftForge routes too.
	 */
	@Test void minecraftForgesLiquidAndBucketReadsGoThroughTheGetter() throws Exception {
		ClassNode liquid = rewritten(LIQUID);
		assertEquals(0, reads(liquid, LIQUID, "fluid", m -> !m.name.equals("<init>")), "no raw read of the fluid is left");
		assertEquals(9, calls(liquid, LIQUID, "getFluid") - calls(node(read(MERGED, LIQUID)), LIQUID, "getFluid"),
				"onPlace, shouldSpreadLiquid, neighborChanged, updateShape, isPathfindable, skipRendering, pickupBlock, "
						+ "getPickupSound and the codec");
		verify(liquid);
		ClassNode bucket = rewritten(BUCKET);
		assertEquals(0, reads(bucket, BUCKET, "content", m -> !m.name.equals("<init>")), "no raw read of the content is left");
		assertEquals(18, calls(bucket, BUCKET, "getFluid") - calls(node(read(MERGED, BUCKET)), BUCKET, "getFluid"));
		verify(bucket);
		byte[] once = NativeCoremodParity.apply(LIQUID, read(MERGED, LIQUID));
		assertSame(once, NativeCoremodParity.apply(LIQUID, once), "a second pass changes nothing");
	}

	/** NeoForge's fluid code reads the bucket's public field from outside; on this base those reads go through the getter. */
	@Test void neoForgesOutsideReadsOfTheBucketGoThroughTheGetter() throws Exception {
		NativeCoremodParity.readGameClassesFrom(path -> {
			try (ZipFile zip = new ZipFile(MERGED.toFile())) {
				ZipEntry entry = zip.getEntry(path);
				return entry == null ? null : zip.getInputStream(entry).readAllBytes();
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		int routed = 0;
		for (String reader : List.of("net/neoforged/neoforge/transfer/fluid/FluidUtil",
				"net/neoforged/neoforge/transfer/fluid/BucketResourceHandler",
				"net/neoforged/neoforge/fluids/capability/wrappers/FluidBucketWrapper",
				"net/neoforged/neoforge/fluids/capability/wrappers/BucketPickupHandlerWrapper")) {
			byte[] original = read(NEO_RUNTIME, reader);
			int raw = reads(node(original), BUCKET, "content", m -> true);
			assertTrue(raw >= 1, "premise: " + reader + " reads BucketItem.content");
			ClassNode out = node(NativeCoremodParity.apply(reader, original));
			assertEquals(0, reads(out, BUCKET, "content", m -> true), reader);
			assertEquals(raw, calls(out, BUCKET, "getFluid") - calls(node(original), BUCKET, "getFluid"), reader);
			verify(out);
			routed += raw;
		}
		assertEquals(7, routed, "every outside read of the bucket's field in NeoForge's runtime");
		NativeCoremodParity.readGameClassesFrom(null);
		byte[] unread = read(NEO_RUNTIME, "net/neoforged/neoforge/transfer/fluid/FluidUtil");
		assertSame(unread, NativeCoremodParity.apply("net/neoforged/neoforge/transfer/fluid/FluidUtil", unread),
				"without the base to read the bucket from, its outside reads stay raw");
	}

	@Test void everyFinalizeSpawnInNeoForgesListIsRedirectedAndNothingElse() throws Exception {
		int before = 0, after = 0;
		for (String target : NativeCoremodParity.FINALIZE_TARGETS) {
			ClassNode original = node(read(MERGED, target));
			int raw = virtualFinalize(original), supers = specialFinalize(original);
			assertTrue(raw >= 1, target + " has a finalizeSpawn call on the merged base");
			ClassNode rewritten = node(NativeCoremodParity.apply(target, read(MERGED, target)));
			assertEquals(0, virtualFinalize(rewritten), target);
			assertEquals(raw, calls(rewritten, NativeCoremodParity.RUNTIME, "finalizeMobSpawn"), target);
			assertEquals(supers, specialFinalize(rewritten), "super.finalizeSpawn in an override is not an event site: " + target);
			verify(rewritten);
			before += raw;
			after += calls(rewritten, NativeCoremodParity.RUNTIME, "finalizeMobSpawn");
		}
		assertEquals(before, after);
		// The census: every such call in the merged game is in a listed class.
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			Set<String> outside = new TreeSet<>();
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().endsWith(".class") || !entry.getName().startsWith("net/minecraft/")) continue;
				String name = entry.getName().substring(0, entry.getName().length() - 6);
				if (NativeCoremodParity.FINALIZE_TARGETS.contains(name)) continue;
				if (virtualFinalize(node(zip.getInputStream(entry).readAllBytes())) > 0) outside.add(name);
			}
			assertEquals(Set.of(), outside, "finalizeSpawn calls NeoForge's coremod would not see");
		}
	}

	@Test void theTrialSpawnersNeoForgeHookIsRoutedThroughBothFamilies() throws Exception {
		ClassNode rewritten = rewritten(NativeCoremodParity.TRIAL_SPAWNER);
		assertEquals(0, calls(rewritten, NativeCoremodParity.NEO_HOOKS, NativeCoremodParity.NEO_SPAWNER_HOOK));
		assertEquals(1, calls(rewritten, NativeCoremodParity.RUNTIME, "finalizeTrialSpawner"));
		verify(rewritten);
	}

	/** How {@link #trialSpawnerAsking} puts MinecraftForge's spawner hook beside NeoForge's. */
	private enum Ask { SAME_MOB, NULL_MOB, OTHER_BRANCH }

	/**
	 * The staged trial spawner with MinecraftForge's own {@code onFinalizeSpawnSpawner} inserted in front of the statement
	 * that calls NeoForge's hook: handed the mob NeoForge's call finalizes, handed null, or only on a branch.
	 */
	private static ClassNode trialSpawnerAsking(Ask ask) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(read(MERGED, NativeCoremodParity.TRIAL_SPAWNER)).accept(node, 0);
		for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(NativeCoremodParity.NEO_HOOKS)
					|| !call.name.equals(NativeCoremodParity.NEO_SPAWNER_HOOK)) continue;
			var frames = new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter()).analyze(node.name, method);
			var frame = frames[method.instructions.indexOf(call)];
			int args = org.objectweb.asm.Type.getArgumentTypes(call.desc).length;
			AbstractInsnNode mob = frame.getStack(frame.getStackSize() - args).insns.iterator().next();
			AbstractInsnNode level = frame.getStack(frame.getStackSize() - args + 1).insns.iterator().next();
			assertTrue(mob instanceof VarInsnNode && level instanceof VarInsnNode, "premise: NeoForge's call takes the mob and level from locals");
			InsnList forge = new InsnList();
			LabelNode skip = new LabelNode();
			if (ask == Ask.OTHER_BRANCH) { forge.add(new InsnNode(Opcodes.ICONST_0)); forge.add(new JumpInsnNode(Opcodes.IFEQ, skip)); }
			forge.add(ask == Ask.NULL_MOB ? new InsnNode(Opcodes.ACONST_NULL) : mob.clone(Map.of()));
			forge.add(level.clone(Map.of()));
			for (int i = 0; i < 4; i++) forge.add(new InsnNode(Opcodes.ACONST_NULL));
			forge.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SpawnerFinalizeInjector.FORGE, SpawnerFinalizeInjector.FORGE_HOOK,
					SpawnerFinalizeInjector.FORGE_DESC, false));
			forge.add(new InsnNode(Opcodes.POP));
			if (ask == Ask.OTHER_BRANCH) forge.add(skip);
			method.instructions.insertBefore(mob, forge);
		}
		return node;
	}

	/** A trial spawner method that already calls MinecraftForge's own spawner hook for the same mob keeps NeoForge's, not posting Forge's twice. */
	@Test void aTrialSpawnerThatAlreadyPostsMinecraftForgesFinalizationIsLeftNative() throws Exception {
		ClassNode node = trialSpawnerAsking(Ask.SAME_MOB);
		assertEquals(0, NativeCoremodParity.routeTrialSpawner(node));
		assertEquals(1, calls(node, NativeCoremodParity.NEO_HOOKS, NativeCoremodParity.NEO_SPAWNER_HOOK));
	}

	/** Near misses: MinecraftForge's hook handed another mob, or only on a branch, finalizes nothing NeoForge's does: routed. */
	@Test void aTrialSpawnerWhoseMinecraftForgeCallFinalizesSomethingElseIsStillRouted() throws Exception {
		for (Ask ask : List.of(Ask.NULL_MOB, Ask.OTHER_BRANCH)) {
			ClassNode node = trialSpawnerAsking(ask);
			assertEquals(1, NativeCoremodParity.routeTrialSpawner(node), ask.name());
			assertEquals(0, calls(node, NativeCoremodParity.NEO_HOOKS, NativeCoremodParity.NEO_SPAWNER_HOOK), ask.name());
		}
	}

	@Test void theTargetListIsNeoForgesAndMinecraftForgesLessTheTrialSpawner() throws Exception {
		TestFixtures.requireFiles(Fixture.STAGED, "staged MinecraftForge carrier", FORGE);
		try (ZipFile zip = new ZipFile(FORGE.toFile())) {
			Set<String> forge = new TreeSet<>();
			String json = new String(zip.getInputStream(zip.getEntry("coremods/finalize_spawn_targets.json")).readAllBytes());
			var match = java.util.regex.Pattern.compile("\"class\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
			while (match.find()) forge.add(match.group(1));
			Set<String> expected = new TreeSet<>(NativeCoremodParity.FINALIZE_TARGETS);
			expected.add(NativeCoremodParity.TRIAL_SPAWNER);
			assertEquals(expected, forge);
		}
		String json = neoForgeFinalizeTargets();
		Set<String> listed = new TreeSet<>();
		var match = java.util.regex.Pattern.compile("\"([a-zA-Z0-9_.$]+)\"").matcher(json);
		while (match.find()) listed.add(match.group(1).replace('.', '/'));
		assertEquals(new TreeSet<>(NativeCoremodParity.FINALIZE_TARGETS), listed);
	}

	/**
	 * NeoForge's own finalize_spawn_targets.json: from the coremods jar nested in the universal jar tools/dev.py
	 * downloaded, else from a native instance's extracted copy. Both are NeoForge's official build, not a mod.
	 */
	private static String neoForgeFinalizeTargets() throws Exception {
		String targets = "net/neoforged/neoforge/coremods/finalize_spawn_targets.json";
		List<Path> universal = Files.isDirectory(NEO_DOWNLOADS)
				? Files.list(NEO_DOWNLOADS).filter(p -> p.getFileName().toString().matches("neoforge-.+-universal\\.jar")).sorted().toList()
				: List.of();
		if (!universal.isEmpty()) {
			try (ZipFile outer = new ZipFile(universal.get(universal.size() - 1).toFile())) {
				ZipEntry nested = Collections.list(outer.entries()).stream()
						.filter(e -> e.getName().matches("META-INF/jarjar/net\\.neoforged\\.neoforge-coremods-.+\\.jar"))
						.findFirst().orElseThrow(() -> new AssertionError(universal + " has no nested coremods jar"));
				try (var in = new java.util.zip.ZipInputStream(outer.getInputStream(nested))) {
					for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
						if (entry.getName().equals(targets)) return new String(in.readAllBytes());
					}
				}
				throw new AssertionError(nested.getName() + " has no " + targets);
			}
		}
		Optional<Path> neo = Files.isDirectory(NEO_COREMODS) ? Files.walk(NEO_COREMODS).filter(p -> p.getFileName().toString().startsWith("net.neoforged.neoforge-coremods-")).findFirst() : Optional.empty();
		TestFixtures.require(Fixture.MC_LIBRARIES, neo.isPresent(), "NeoForge universal jar absent from " + NEO_DOWNLOADS);
		try (ZipFile zip = new ZipFile(neo.get().toFile())) {
			return new String(zip.getInputStream(zip.getEntry(targets)).readAllBytes());
		}
	}

	@Test void vanillasShapeAndTheSwitchesLeaveClassesAlone() throws Exception {
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(VANILLA), "vanilla 26.2 absent");
		byte[] vanillaPot = read(VANILLA, POT);
		assertSame(vanillaPot, NativeCoremodParity.apply(POT, vanillaPot), "vanilla's getter reads the field; nothing to route");
		for (String owner : List.of(LIQUID, BUCKET)) {
			byte[] vanilla = read(VANILLA, owner);
			assertSame(vanilla, NativeCoremodParity.apply(owner, vanilla), "vanilla has no MinecraftForge getter to route to: " + owner);
		}
		System.setProperty(NativeCoremodParity.FORGE_GETTERS, "off");
		for (String owner : List.of(LIQUID, BUCKET)) {
			byte[] merged = read(MERGED, owner);
			assertSame(merged, NativeCoremodParity.apply(owner, merged), NativeCoremodParity.FORGE_GETTERS + ": " + owner);
		}
		System.clearProperty(NativeCoremodParity.FORGE_GETTERS);
		for (String property : List.of(NativeCoremodParity.PROPERTY, NativeCoremodParity.FLOWER_POT)) {
			System.setProperty(property, "off");
			byte[] merged = read(MERGED, POT);
			assertSame(merged, NativeCoremodParity.apply(POT, merged), property);
			System.clearProperty(property);
		}
		System.setProperty(NativeCoremodParity.FINALIZE, "off");
		byte[] zombie = read(MERGED, "net/minecraft/world/entity/monster/zombie/Zombie");
		assertSame(zombie, NativeCoremodParity.apply("net.minecraft.world.entity.monster.zombie.Zombie", zombie));
		byte[] other = read(MERGED, "net/minecraft/world/level/block/Blocks");
		assertSame(other, NativeCoremodParity.apply("net.minecraft.world.level.block.Blocks", other));
	}

	private static ClassNode rewritten(String internalName) throws Exception {
		byte[] original = read(MERGED, internalName);
		byte[] out = NativeCoremodParity.apply(internalName.replace('/', '.'), original);
		assertNotSame(original, out, internalName + " is rewritten");
		return node(out);
	}

	private static int reads(ClassNode node, String owner, String field, java.util.function.Predicate<MethodNode> in) {
		int n = 0;
		for (MethodNode m : node.methods) {
			if (!in.test(m)) continue;
			for (AbstractInsnNode insn : m.instructions) if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && f.owner.equals(owner) && f.name.equals(field)) n++;
		}
		return n;
	}

	private static int calls(ClassNode node, String owner, String name) {
		int n = 0;
		for (MethodNode m : node.methods) for (AbstractInsnNode insn : m.instructions) if (insn instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)) n++;
		return n;
	}

	private static int virtualFinalize(ClassNode node) { return finalizeCalls(node, Opcodes.INVOKEVIRTUAL); }
	private static int specialFinalize(ClassNode node) { return finalizeCalls(node, Opcodes.INVOKESPECIAL); }
	private static int finalizeCalls(ClassNode node, int opcode) {
		int n = 0;
		for (MethodNode m : node.methods) for (AbstractInsnNode insn : m.instructions)
			if (insn.getOpcode() == opcode && insn instanceof MethodInsnNode c && c.name.equals("finalizeSpawn") && c.desc.equals(NativeCoremodParity.FINALIZE_DESC)) n++;
		return n;
	}

	private static void verify(ClassNode node) throws Exception {
		for (MethodNode m : node.methods) if (m.instructions.size() > 0) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
	}

	/** One class of {@code jar}, which some thirty transform tests read through here; a jar present without it fails. */
	static byte[] read(Path jar, String internalName) {
		return TestFixtures.requireEntry(fixtureOf(jar), jar, internalName + ".class");
	}

	/**
	 * Which fixture {@code jar} is, from where it lives: the callers pass staged carriers, the local vanilla jar and
	 * pinned mod jars through the one helper, and each is missing on a different kind of machine.
	 */
	static Fixture fixtureOf(Path jar) {
		Path at = jar.toAbsolutePath().normalize();
		if (at.startsWith(TestFixtures.stagedRoot().toAbsolutePath().normalize())) return Fixture.STAGED;
		if (at.startsWith(TestFixtures.minecraftDir().toAbsolutePath().normalize())) return Fixture.MC_LIBRARIES;
		return Fixture.THIRD_PARTY;
	}
}
