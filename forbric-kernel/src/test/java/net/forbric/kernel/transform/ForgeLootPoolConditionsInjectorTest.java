package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** MinecraftForge's pool conditions, carried by the builder and judged by the pool decoder, on the real classes. */
@ResourceLock("system-properties")
class ForgeLootPoolConditionsInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path FORGE = STAGED.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final Path NEO = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String BUILDER = "net/minecraft/world/level/storage/loot/LootPool$Builder";
	private static final String HOOKS = "net/neoforged/neoforge/common/CommonHooks";

	@AfterEach void reset() { System.clearProperty(ForgeLootPoolConditionsInjector.PROPERTY); }

	@Test void theBuilderKeepsItsConditionInThePool() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, BUILDER);
		assertEquals(0, puts(build(original)), "premise: NeoForge's builder drops it");
		byte[] out = injector().transform(ForgeLootPoolConditionsInjector.BUILDER, original, null);
		MethodNode build = build(out);
		assertEquals(1, puts(build));
		List<AbstractInsnNode> real = Arrays.stream(build.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
		assertEquals(Opcodes.ARETURN, real.getLast().getOpcode());
		assertTrue(real.get(real.size() - 2) instanceof FieldInsnNode put && put.name.equals("forge_condition"), "stored right before the return");
		assertTrue(real.get(real.size() - 3) instanceof MethodInsnNode call && call.name.equals("ofNullable"));
		new Analyzer<>(new BasicVerifier()).analyze(BUILDER, build);
		assertSame(out, injector().transform(ForgeLootPoolConditionsInjector.BUILDER, out, null), "a second pass changes nothing");
	}

	@Test void thePoolDecoderJudgesForgeConditionInsideNeoForgesWrapper() throws Exception {
		byte[] original = NativeCoremodParityTest.read(NEO, HOOKS);
		byte[] out = injector().transform(ForgeLootPoolConditionsInjector.HOOKS, original, null);
		MethodNode pools = method(node(out), "lootPoolsCodec", ForgeLootPoolConditionsInjector.POOLS_CODEC_DESC);
		List<AbstractInsnNode> real = Arrays.stream(pools.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
		assertTrue(real.get(0) instanceof FieldInsnNode codec && codec.name.equals("CODEC"));
		assertTrue(real.get(1) instanceof MethodInsnNode helper && helper.owner.equals(ForgeLootPoolConditionsInjector.RUNTIME) && helper.name.equals("poolElementCodec"));
		assertTrue(real.get(2) instanceof MethodInsnNode wrap && wrap.name.equals("createConditionalCodec"), "inside NeoForge's own wrapper");
		assertEquals(1, Arrays.stream(real.toArray()).filter(i -> i instanceof MethodInsnNode c && c.owner.equals(ForgeLootPoolConditionsInjector.RUNTIME)).count(),
				"the encoder side is left as it is");
		new Analyzer<>(new BasicVerifier()).analyze(HOOKS, pools);
		assertSame(out, injector().transform(ForgeLootPoolConditionsInjector.HOOKS, out, null), "a second pass changes nothing");
	}

	@Test void nativeMinecraftForgeIsWhatThisRestores() throws Exception {
		ClassNode table = node(NativeCoremodParityTest.read(FORGE, "net/minecraft/world/level/storage/loot/LootTable"));
		assertTrue(table.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
				.anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("CONDITIONAL_CODEC")), "MinecraftForge's LootTable reads pools with CONDITIONAL_CODEC");
		MethodNode forgeBuild = build(NativeCoremodParityTest.read(FORGE, BUILDER));
		assertTrue(Arrays.stream(forgeBuild.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("ofNullable")),
				"and its builder hands the pool Optional.ofNullable(forge_condition)");
		ClassNode merged = node(NativeCoremodParityTest.read(MERGED, "net/minecraft/world/level/storage/loot/LootTable"));
		assertFalse(merged.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
				.anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("CONDITIONAL_CODEC")), "premise: the merged LootTable does not");
	}

	@Test void theSwitchLeavesBothAlone() throws Exception {
		System.setProperty(ForgeLootPoolConditionsInjector.PROPERTY, "off");
		byte[] builder = NativeCoremodParityTest.read(MERGED, BUILDER), hooks = NativeCoremodParityTest.read(NEO, HOOKS);
		assertSame(builder, injector().transform(ForgeLootPoolConditionsInjector.BUILDER, builder, null));
		assertSame(hooks, injector().transform(ForgeLootPoolConditionsInjector.HOOKS, hooks, null));
	}

	private static ForgeLootPoolConditionsInjector injector() { return new ForgeLootPoolConditionsInjector(); }

	private static int puts(MethodNode build) {
		return (int) Arrays.stream(build.instructions.toArray()).filter(i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
				&& f.owner.equals(ForgeLootPoolConditionsInjector.POOL) && f.name.equals("forge_condition")).count();
	}

	private static MethodNode build(byte[] bytes) {
		return method(node(bytes), "build", "()L" + ForgeLootPoolConditionsInjector.POOL + ";");
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
	}
}
