package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The pre-Mixin halves of the coremod repair, on the real merged and NeoForge runtime classes. */
@ResourceLock("system-properties")
class CoremodRepairInjectorsTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path VANILLA = Path.of(System.getProperty("user.home"), "Library/Application Support/minecraft/versions/26.2/26.2.jar");

	@AfterEach void reset() {
		for (String key : List.of(NativeCoremodParity.PROPERTY, NativeCoremodParity.FLOWER_POT, NativeCoremodParity.BIOME,
				LiquidBlockFluidInjector.PROPERTY)) System.clearProperty(key);
	}

	@Test void theFlowerPotStoresItsPlantLooksUpEveryFamilyAndRecordsAddPlant() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, FlowerPotRepairInjector.OWNER);
		byte[] out = new FlowerPotRepairInjector().transform(FlowerPotRepairInjector.TARGET, original, null);
		assertNotSame(original, out);
		ClassNode node = node(out);
		MethodNode ctor = method(node, "<init>", FlowerPotRepairInjector.BLOCK_CTOR);
		FieldInsnNode store = find(ctor, FieldInsnNode.class, f -> f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("potted"));
		assertTrue(store.getPrevious() instanceof VarInsnNode load && load.var == 1, "potted = the plant, as vanilla");
		assertNotNull(find(ctor, FieldInsnNode.class, f -> f.name.equals("POTTED_BY_CONTENT")), "and in POTTED_BY_CONTENT, as vanilla");
		MethodNode use = method(node, "useItemOn", FlowerPotRepairInjector.USE_ITEM_ON);
		assertNull(find(use, MethodInsnNode.class, c -> c.name.equals("getDelegateOrThrow") || c.name.equals("getOrDefault")));
		assertNull(find(use, TypeInsnNode.class, t -> t.desc.equals("java/util/function/Supplier")));
		assertNotNull(find(use, MethodInsnNode.class, c -> c.owner.equals(FlowerPotRepairInjector.RUNTIME) && c.name.equals("fullPotFor")));
		MethodNode add = method(node, "addPlant", FlowerPotRepairInjector.ADD_PLANT);
		assertNotNull(find(add, MethodInsnNode.class, c -> c.name.equals("put")));
		for (MethodNode m : List.of(ctor, use, add)) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		assertEquals(0, FlowerPotRepairInjector.storePlant(node) + FlowerPotRepairInjector.lookUpAllFamilies(node) + FlowerPotRepairInjector.recordAddedPlant(node),
				"a second pass changes nothing");
	}

	@Test void vanillasFlowerPotAndTheSwitchAreLeftAlone() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(VANILLA), "vanilla 26.2 absent");
		byte[] vanilla = NativeCoremodParityTest.read(VANILLA, FlowerPotRepairInjector.OWNER);
		assertSame(vanilla, new FlowerPotRepairInjector().transform(FlowerPotRepairInjector.TARGET, vanilla, null));
		System.setProperty(NativeCoremodParity.FLOWER_POT, "off");
		byte[] merged = NativeCoremodParityTest.read(MERGED, FlowerPotRepairInjector.OWNER);
		assertSame(merged, new FlowerPotRepairInjector().transform(FlowerPotRepairInjector.TARGET, merged, null));
	}

	@Test void theLiquidBlocksGetterReadsTheFieldBeforeTheSupplier() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, LiquidBlockFluidInjector.OWNER);
		byte[] out = new LiquidBlockFluidInjector().transform(LiquidBlockFluidInjector.TARGET, original, null);
		assertNotSame(original, out);
		MethodNode getter = method(node(out), "getFluid", "()L" + LiquidBlockFluidInjector.FLUID + ";");
		List<AbstractInsnNode> real = java.util.Arrays.stream(getter.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
		assertTrue(real.get(1) instanceof FieldInsnNode f && f.name.equals("fluid"), "the field first");
		assertTrue(real.get(3) instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFNONNULL);
		assertNotNull(find(getter, FieldInsnNode.class, f -> f.name.equals("supplier")), "then the supplier");
		new Analyzer<>(new BasicVerifier()).analyze(LiquidBlockFluidInjector.OWNER, getter);
		assertSame(out, new LiquidBlockFluidInjector().transform(LiquidBlockFluidInjector.TARGET, out, null), "a second pass changes nothing");
		System.setProperty(LiquidBlockFluidInjector.PROPERTY, "off");
		assertSame(original, new LiquidBlockFluidInjector().transform(LiquidBlockFluidInjector.TARGET, original, null));
	}

	@Test void neoForgesBiomePassStartsFromTheBiomesCurrentInfo() throws Exception {
		String owner = BiomeInfoRebaseInjector.OWNER;
		byte[] original = NativeCoremodParityTest.read(NEO, owner);
		byte[] out = new BiomeInfoRebaseInjector().transform(BiomeInfoRebaseInjector.TARGET, original, null);
		assertNotSame(original, out);
		MethodNode apply = method(node(out), "applyBiomeModifiers", BiomeInfoRebaseInjector.APPLY_DESC);
		MethodInsnNode originalInfo = find(apply, MethodInsnNode.class, c -> c.name.equals("getOriginalBiomeInfo"));
		AbstractInsnNode next = originalInfo.getNext();
		assertTrue(next instanceof VarInsnNode load && load.var == 1, "the holder");
		assertTrue(next.getNext() instanceof MethodInsnNode call && call.owner.equals(BiomeInfoRebaseInjector.RUNTIME) && call.name.equals("startFrom"));
		new Analyzer<>(new BasicVerifier()).analyze(owner, apply);
		assertSame(out, new BiomeInfoRebaseInjector().transform(BiomeInfoRebaseInjector.TARGET, out, null), "a second pass changes nothing");
		System.setProperty(NativeCoremodParity.BIOME, "off");
		assertSame(original, new BiomeInfoRebaseInjector().transform(BiomeInfoRebaseInjector.TARGET, original, null),
				"the same switch as the read rewrite, so the two never apply apart");
	}

	@Test void aBiomeReplacedAfterNeoForgesPassWinsOverTheView() throws Exception {
		String biome = BiomeLateWriteInjector.OWNER;
		byte[] original = NativeCoremodParityTest.read(MERGED, biome);
		byte[] out = new BiomeLateWriteInjector().transform(BiomeLateWriteInjector.TARGET, original, null);
		assertNotSame(original, out);
		ClassNode node = node(out);
		for (var guarded : BiomeLateWriteInjector.GUARDED) {
			assertTrue(node.fields.stream().anyMatch(f -> f.name.equals(guarded.atPass()) && f.desc.equals(guarded.desc())));
			MethodNode getter = method(node, guarded.getter(), "()" + guarded.desc());
			List<AbstractInsnNode> real = java.util.Arrays.stream(getter.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
			assertTrue(real.get(1) instanceof FieldInsnNode f && f.name.equals(guarded.atPass()), "the pass-time record is asked first");
			assertNotNull(find(getter, MethodInsnNode.class, c -> c.name.equals("modifiableBiomeInfo")), "then NeoForge's own answer");
			new Analyzer<>(new BasicVerifier()).analyze(biome, getter);
		}
		MethodNode mark = method(node, BiomeLateWriteInjector.MARK, BiomeLateWriteInjector.MARK_DESC);
		assertNull(find(mark, FieldInsnNode.class, f -> f.getOpcode() == Opcodes.GETFIELD), "the record takes its values as arguments");
		new Analyzer<>(new BasicVerifier()).analyze(biome, mark);
		assertSame(out, new BiomeLateWriteInjector().transform(BiomeLateWriteInjector.TARGET, out, null), "a second pass changes nothing");
		// The read rewrite after Mixin still takes the class: the getters are NeoForge's, only guarded.
		ClassNode parity = node(NativeCoremodParity.apply(biome, out));
		long views = parity.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
				.filter(i -> i instanceof MethodInsnNode c && c.owner.equals(biome) && c.name.startsWith("getModified")).count();
		long before = node.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
				.filter(i -> i instanceof MethodInsnNode c && c.owner.equals(biome) && c.name.startsWith("getModified")).count();
		assertEquals(16, views - before);
		System.setProperty(NativeCoremodParity.BIOME, "off");
		assertSame(original, new BiomeLateWriteInjector().transform(BiomeLateWriteInjector.TARGET, original, null));
	}

	@Test void theMasterSwitchCoversTheLiquidRepairToo() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, LiquidBlockFluidInjector.OWNER);
		System.setProperty(NativeCoremodParity.PROPERTY, "off");
		assertSame(original, new LiquidBlockFluidInjector().transform(LiquidBlockFluidInjector.TARGET, original, null));
	}

	private static <T extends AbstractInsnNode> T find(MethodNode m, Class<T> type, java.util.function.Predicate<T> test) {
		for (AbstractInsnNode insn : m.instructions) if (type.isInstance(insn) && test.test(type.cast(insn))) return type.cast(insn);
		return null;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
	}
}
