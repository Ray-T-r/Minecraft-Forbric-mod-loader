package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;


/** Real carrier bytecode: the two Forge builder repairs, the two splices into NeoForge's pass, and the switch. */
class ForgeWorldModifierInjectorTest {
	private static final Path RUN = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	private static final Path FORGE = RUN.resolve("forge-runtime/forge-runtime.jar");
	private static final Path NEO = RUN.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path MERGED = RUN.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String SPAWN_BUILDER = "net/minecraftforge/common/world/MobSpawnSettingsBuilder";
	private static final String REMOVE_SPAWNS = "net/minecraftforge/common/world/ForgeBiomeModifiers$RemoveSpawnsBiomeModifier";
	private static final String HOOKS = "net/neoforged/neoforge/server/ServerLifecycleHooks";
	private static final String BUILDER = "net/minecraft/util/random/WeightedList$Builder";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelForgeWorldgen";

	@AfterEach
	void clearSwitch() {
		System.clearProperty(ForgeWorldModifierInjector.PROPERTY);
	}

	@Test
	void thePremiseTheMergedBuilderHasCollectionAddAllAndNoIterableOverload() throws Exception {
		ClassNode builder = parse(bytesOf(MERGED, BUILDER));
		List<String> addAll = new ArrayList<>();
		for (MethodNode m : builder.methods) if ("addAll".equals(m.name)) addAll.add(m.desc);
		assertTrue(addAll.contains("(Ljava/util/Collection;)L" + BUILDER + ";"), addAll.toString());
		assertFalse(addAll.contains("(Ljava/lang/Iterable;)L" + BUILDER + ";"),
				"the merged builder grew Forge's Iterable overload — the descriptor repair is redundant, re-derive");
	}

	@Test
	void spawnBuilderCallsCollectionAddAllAfterTheRepair() throws Exception {
		ClassNode before = parse(bytesOf(FORGE, SPAWN_BUILDER));
		assertEquals(1, calls(before, BUILDER, "addAll", "(Ljava/lang/Iterable;)L" + BUILDER + ";").size(), "premise");
		ClassNode after = parse(transform(SPAWN_BUILDER, FORGE));
		assertEquals(0, calls(after, BUILDER, "addAll", "(Ljava/lang/Iterable;)L" + BUILDER + ";").size());
		assertEquals(1, calls(after, BUILDER, "addAll", "(Ljava/util/Collection;)L" + BUILDER + ";").size());
	}

	@Test
	void removeSpawnsRoutesRemoveIfThroughTheKernelsCopyOfTheLostDefault() throws Exception {
		ClassNode after = parse(transform(REMOVE_SPAWNS, FORGE));
		List<MethodInsnNode> kernel = calls(after, KERNEL, "removeIfValue", null);
		assertEquals(1, kernel.size());
		assertEquals(Opcodes.INVOKESTATIC, kernel.getFirst().getOpcode());
		assertEquals("(L" + BUILDER + ";Ljava/util/function/Predicate;)L" + BUILDER + ";", kernel.getFirst().desc);
		assertTrue(calls(after, BUILDER, "removeIf", null).isEmpty(), "no removeIf on WeightedList$Builder may remain");
	}

	@Test
	void runModifiersGetsExactlyTwoSplicesRightAfterEachListIsMaterialised() throws Exception {
		ClassNode after = parse(transform(HOOKS, NEO));
		MethodNode run = method(after, "runModifiers");
		List<String> seen = new ArrayList<>();
		for (AbstractInsnNode insn : run.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !KERNEL.equals(call.owner)) continue;
			assertEquals("(Ljava/util/List;)Ljava/util/List;", call.desc);
			assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
			AbstractInsnNode previous = realPrevious(call), next = realNext(call);
			assertTrue(previous instanceof MethodInsnNode m && "toList".equals(m.name), "spliced right after Stream.toList");
			assertTrue(next instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE, "and right before the store");
			int slot = ((VarInsnNode) next).var;
			seen.add(call.name + "->" + slot);
		}
		assertEquals(List.of("withMinecraftForgeBiomeModifiers->2", "withMinecraftForgeStructureModifiers->3"), seen);
	}

	@Test
	void aSyntheticSingleListIsLeftAloneWhole() throws Exception {
		byte[] one = syntheticRunModifiers(1);
		assertSame(one, new ForgeWorldModifierInjector().transform(HOOKS.replace('/', '.'), one, null),
				"one recognisable list is not two — nothing may be edited");
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		for (String[] target : new String[][] { { SPAWN_BUILDER, "F" }, { REMOVE_SPAWNS, "F" }, { HOOKS, "N" } }) {
			Path jar = "F".equals(target[1]) ? FORGE : NEO;
			byte[] once = transform(target[0], jar);
			assertSame(once, new ForgeWorldModifierInjector().transform(target[0].replace('/', '.'), once, null), target[0]);
		}
	}

	@Test
	void theSwitchStandsAllThreeTargetsDownAndDeclaresScannedAnchors() throws Exception {
		System.setProperty(ForgeWorldModifierInjector.PROPERTY, "off");
		for (String[] target : new String[][] { { SPAWN_BUILDER, "F" }, { REMOVE_SPAWNS, "F" }, { HOOKS, "N" } }) {
			byte[] bytes = bytesOf("F".equals(target[1]) ? FORGE : NEO, target[0]);
			assertSame(bytes, new ForgeWorldModifierInjector().transform(target[0].replace('/', '.'), bytes, null), target[0]);
		}
		assertTrue(new ForgeWorldModifierInjector().anchors().anchors().isEmpty(), "off is a request, not a missed anchor");
		System.clearProperty(ForgeWorldModifierInjector.PROPERTY);
		assertEquals(3, new ForgeWorldModifierInjector().anchors().anchors().size());
	}

	@Test
	void afterBothTransformersTheGuardAndTheSplicesCoexist() throws Exception {
		byte[] compat = new ForbricMergedBaseCompatTransformer().transform(HOOKS.replace('/', '.'), bytesOf(NEO, HOOKS), null);
		byte[] both = new ForgeWorldModifierInjector().transform(HOOKS.replace('/', '.'), compat, null);
		ClassNode node = parse(both);
		assertEquals(1, calls(node, "net/forbric/kernel/runtime/KernelNeoWorldgen", "beforeServerStart", null).size(),
				"the compat transformer's guard on handleServerAboutToStart must survive");
		assertEquals(2, calls(node, KERNEL, null, "(Ljava/util/List;)Ljava/util/List;").size());
	}

	private static byte[] syntheticRunModifiers(int lists) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "runModifiers", "(Lnet/minecraft/server/MinecraftServer;)V", null, null);
		mv.visitCode();
		for (int i = 0; i < lists; i++) {
			mv.visitFieldInsn(Opcodes.GETSTATIC, "net/neoforged/neoforge/registries/NeoForgeRegistries$Keys", i == 0 ? "BIOME_MODIFIERS" : "STRUCTURE_MODIFIERS", "Lnet/minecraft/resources/ResourceKey;");
			mv.visitInsn(Opcodes.POP);
			mv.visitInsn(Opcodes.ACONST_NULL);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "toList", "()Ljava/util/List;", true);
			mv.visitVarInsn(Opcodes.ASTORE, 2 + i);
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] transform(String internal, Path jar) throws Exception {
		return new ForgeWorldModifierInjector().transform(internal.replace('/', '.'), bytesOf(jar, internal), null);
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) if (name.equals(m.name)) return m;
		throw new AssertionError("no " + name);
	}

	private static List<MethodInsnNode> calls(ClassNode node, String owner, String name, String desc) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && owner.equals(call.owner)
						&& (name == null || name.equals(call.name)) && (desc == null || desc.equals(call.desc))) out.add(call);
			}
		}
		return out;
	}

	private static AbstractInsnNode realPrevious(AbstractInsnNode insn) {
		AbstractInsnNode p = insn.getPrevious();
		while (p != null && p.getOpcode() < 0) p = p.getPrevious();
		return p;
	}

	private static AbstractInsnNode realNext(AbstractInsnNode insn) {
		AbstractInsnNode n = insn.getNext();
		while (n != null && n.getOpcode() < 0) n = n.getNext();
		return n;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(Path jar, String internal) throws Exception {
		assumeTrue(Files.isRegularFile(jar), "staged artifact absent: " + jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal + " not in " + jar.getFileName());
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
