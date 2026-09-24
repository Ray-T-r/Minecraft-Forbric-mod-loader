package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path FORGE = STAGED.resolve("merged-base/forge-runtime-interop.jar");
	private static final Path VANILLA = Path.of(System.getProperty("user.home"), "Library/Application Support/minecraft/versions/26.2/26.2.jar");
	private static final Path NEO_COREMODS = Path.of("build/journeymap-native/instance/.cache/jij");
	private static final String POT = "net/minecraft/world/level/block/FlowerPotBlock";
	private static final String BIOME = "net/minecraft/world/level/biome/Biome";
	private static final String STRUCTURE = "net/minecraft/world/level/levelgen/structure/Structure";

	@AfterEach void reset() {
		for (String key : List.of(NativeCoremodParity.PROPERTY, NativeCoremodParity.FLOWER_POT, NativeCoremodParity.BIOME,
				NativeCoremodParity.STRUCTURE, NativeCoremodParity.FINALIZE)) System.clearProperty(key);
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

	@Test void theTargetListIsNeoForgesAndMinecraftForgesLessTheTrialSpawner() throws Exception {
		try (ZipFile zip = new ZipFile(FORGE.toFile())) {
			Set<String> forge = new TreeSet<>();
			String json = new String(zip.getInputStream(zip.getEntry("coremods/finalize_spawn_targets.json")).readAllBytes());
			var match = java.util.regex.Pattern.compile("\"class\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
			while (match.find()) forge.add(match.group(1));
			Set<String> expected = new TreeSet<>(NativeCoremodParity.FINALIZE_TARGETS);
			expected.add(NativeCoremodParity.TRIAL_SPAWNER);
			assertEquals(expected, forge);
		}
		Optional<Path> neo = Files.isDirectory(NEO_COREMODS) ? Files.walk(NEO_COREMODS).filter(p -> p.getFileName().toString().startsWith("net.neoforged.neoforge-coremods-")).findFirst() : Optional.empty();
		Assumptions.assumeTrue(neo.isPresent(), "native NeoForge coremods jar absent");
		try (ZipFile zip = new ZipFile(neo.get().toFile())) {
			Set<String> listed = new TreeSet<>();
			String json = new String(zip.getInputStream(zip.getEntry("net/neoforged/neoforge/coremods/finalize_spawn_targets.json")).readAllBytes());
			var match = java.util.regex.Pattern.compile("\"([a-zA-Z0-9_.$]+)\"").matcher(json);
			while (match.find()) listed.add(match.group(1).replace('.', '/'));
			assertEquals(new TreeSet<>(NativeCoremodParity.FINALIZE_TARGETS), listed);
		}
	}

	@Test void vanillasShapeAndTheSwitchesLeaveClassesAlone() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(VANILLA), "vanilla 26.2 absent");
		byte[] vanillaPot = read(VANILLA, POT);
		assertSame(vanillaPot, NativeCoremodParity.apply(POT, vanillaPot), "vanilla's getter reads the field; nothing to route");
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

	static byte[] read(Path jar, String internalName) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(jar), jar + " absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internalName + ".class");
			assertNotNull(entry, internalName + " in " + jar);
			return zip.getInputStream(entry).readAllBytes();
		}
	}
}
