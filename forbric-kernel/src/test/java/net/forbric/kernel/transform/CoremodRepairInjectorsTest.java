package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
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
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path NEO = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final Path VANILLA = TestFixtures.vanillaJar();

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
		assertEquals(net.forbric.kernel.mixin.MixinInstructionFingerprint.hash(method(node(original),"useItemOn",FlowerPotRepairInjector.USE_ITEM_ON)),net.forbric.kernel.mixin.MixinInstructionFingerprint.hash(use),"the public native query and all interaction effects stay intact");
        MethodNode lookup=method(node,"getFullPot","(L"+FlowerPotRepairInjector.BLOCK+";)L"+FlowerPotRepairInjector.BLOCK+";");
        assertNotNull(find(lookup,MethodInsnNode.class,c->c.name.equals("getFlowerPotBlockTable")),"the original public SDK lookup remains");
        assertNotNull(find(lookup,MethodInsnNode.class,c->c.owner.equals(FlowerPotRepairInjector.RUNTIME)&&c.name.equals("fullPotOrNative")),"explicit SDK additions decorate its actual result");
        new Analyzer<>(new BasicVerifier()).analyze(node.name,lookup);
		MethodNode add = method(node, "addPlant", FlowerPotRepairInjector.ADD_PLANT);
		assertNotNull(find(add, MethodInsnNode.class, c -> c.name.equals("put")));
		for (MethodNode m : List.of(ctor, use, add)) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		assertEquals(0, FlowerPotRepairInjector.storePlant(node) + FlowerPotRepairInjector.lookUpAllFamilies(node) + FlowerPotRepairInjector.recordAddedPlant(node),
				"a second pass changes nothing");
	}

	@Test void vanillasFlowerPotAndTheSwitchAreLeftAlone() throws Exception {
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(VANILLA), "vanilla 26.2 absent");
		byte[] vanilla = NativeCoremodParityTest.read(VANILLA, FlowerPotRepairInjector.OWNER);
		assertSame(vanilla, new FlowerPotRepairInjector().transform(FlowerPotRepairInjector.TARGET, vanilla, null));
		System.setProperty(NativeCoremodParity.FLOWER_POT, "off");
		byte[] merged = NativeCoremodParityTest.read(MERGED, FlowerPotRepairInjector.OWNER);
		assertSame(merged, new FlowerPotRepairInjector().transform(FlowerPotRepairInjector.TARGET, merged, null));
	}

	/**
	 * Read from the staged base, exact both ways: where one of its constructors leaves the supplier unfilled the getter
	 * is repaired to read the field first; where every constructor fills it (a both-edits merge) the repair stands by.
	 * Either way the claim is met, and the stand-by is only taken where this test's own reading agrees.
	 */
	@Test void theLiquidBlocksGetterReadsTheFieldBeforeTheSupplier() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, LiquidBlockFluidInjector.OWNER);
		List<String> hits = new java.util.ArrayList<>();
		byte[] out = new LiquidBlockFluidInjector().transform(LiquidBlockFluidInjector.TARGET, original, null, hits::add);
		assertEquals(List.of(LiquidBlockFluidInjector.CLAIM), hits, "the getter answers every liquid once through");
		boolean everyConstructorFills = true;
		for (MethodNode init : node(original).methods) {
			if (!init.name.equals("<init>")) continue;
			boolean delegates = find(init, MethodInsnNode.class, c -> c.getOpcode() == Opcodes.INVOKESPECIAL
					&& c.owner.equals(LiquidBlockFluidInjector.OWNER) && c.name.equals("<init>")) != null;
			FieldInsnNode put = find(init, FieldInsnNode.class, f -> f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("supplier"));
			AbstractInsnNode value = put == null ? null : put.getPrevious();
			while (value != null && value.getOpcode() < 0) value = value.getPrevious();
			if (!delegates && (put == null || value.getOpcode() == Opcodes.ACONST_NULL)) everyConstructorFills = false;
		}
		if (everyConstructorFills) {
			assertSame(original, out, "every constructor of the staged LiquidBlock fills the supplier: nothing to repair");
			return;
		}
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

	/**
	 * The coremod master switch leaves the liquid repair on; only its own switch turns it off. It is a merge repair, not
	 * one of NeoForge's coremods, and on a base that needs it the game cannot start without it: Blocks.<clinit> asks a
	 * liquid block for its fluid, so -Dforbric.coremodParity=off used to take the whole boot down with it, and
	 * gate-m42's off phase -- which exists to show every coremod repair failing -- had no server to show it on.
	 */
	@Test void theMasterSwitchLeavesTheLiquidRepairOnAndOnlyItsOwnSwitchTurnsItOff() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, LiquidBlockFluidInjector.OWNER);
		System.setProperty(NativeCoremodParity.PROPERTY, "off");
		// Judged by the claim, not by changed bytes: on a base whose constructors all fill the supplier the repair still
		// runs and stands by (theLiquidBlocksGetterReadsTheFieldBeforeTheSupplier holds which one this base is).
		List<String> hits = new java.util.ArrayList<>();
		new LiquidBlockFluidInjector().transform(LiquidBlockFluidInjector.TARGET, original, null, hits::add);
		assertEquals(List.of(LiquidBlockFluidInjector.CLAIM), hits, "the master switch does not reach the liquid repair");
		System.setProperty(LiquidBlockFluidInjector.PROPERTY, "off");
		hits.clear();
		assertSame(original, new LiquidBlockFluidInjector().transform(LiquidBlockFluidInjector.TARGET, original, null, hits::add));
		assertEquals(List.of(), hits, "its own switch turns it off");
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
