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

/** MinecraftForge's ItemStack.useOn and onPlaceItemIntoWorld, on the real classes. */
@ResourceLock("system-properties")
class ItemUseOnInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path FORGE_RUNTIME = STAGED.resolve("merged-base/forge-runtime-interop.jar");
	private static final Path NEO_PATCHED = STAGED.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final Path VANILLA = Path.of(System.getProperty("user.home"), "Library/Application Support/minecraft/versions/26.2/26.2.jar");
	private static final String STACK = "net/minecraft/world/item/ItemStack";
	private static final String HOOKS = "net/minecraftforge/common/ForgeHooks";
	private static final Path NEO_RUNTIME = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String NEO_HOOKS = "net/neoforged/neoforge/common/CommonHooks";

	@AfterEach void reset() { System.clearProperty(ItemUseOnInjector.PROPERTY); }

	@Test void useOnPostsNeoForgesAfterBlockPhaseFirstAndItsLambdaCallsTheRelay() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, STACK);
		MethodNode before = method(node(original), "useOn", ItemUseOnInjector.USE_ON_DESC);
		assertFalse(calls(before, ItemUseOnInjector.EVENT, "<init>"), "premise: MinecraftForge's body posts no UseItemOnBlockEvent");
		assertFalse(calls(before, ItemUseOnInjector.ITEM, "useOn"), "premise: and makes no Item.useOn call itself");

		byte[] out = new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, original, null);
		ClassNode stack = node(out);
		MethodNode useOn = method(stack, "useOn", ItemUseOnInjector.USE_ON_DESC);
		List<AbstractInsnNode> real = real(useOn);
		assertTrue(real.get(0) instanceof FieldInsnNode bus && bus.name.equals("EVENT_BUS"), "the post is the first thing useOn does");
		assertTrue(real.get(4) instanceof FieldInsnNode phase && phase.name.equals("ITEM_AFTER_BLOCK"), "in NeoForge's ITEM_AFTER_BLOCK phase");
		assertTrue(calls(useOn, ItemUseOnInjector.EVENT, "getCancellationResult"), "a cancelled event's result is returned");
		assertTrue(calls(useOn, "net/minecraftforge/common/ForgeHooks", "onPlaceItemIntoWorld"), "MinecraftForge's body follows unchanged");
		new Analyzer<>(new BasicVerifier()).analyze(STACK, useOn);

		MethodNode relay = method(stack, ItemUseOnInjector.RELAY, ItemUseOnInjector.RELAY_DESC);
		assertNotNull(relay);
		assertEquals(0, relay.access & Opcodes.ACC_STATIC);
		assertTrue(calls(relay, ItemUseOnInjector.ITEM, "useOn"));
		MethodNode lambda = method(stack, "lambda$useOn$0", "(L" + ItemUseOnInjector.CONTEXT + ";L" + ItemUseOnInjector.CONTEXT + ";)L" + ItemUseOnInjector.RESULT + ";");
		assertFalse(calls(lambda, ItemUseOnInjector.ITEM, "useOn"), "the client lambda no longer calls Item.useOn directly");
		assertTrue(calls(lambda, STACK, ItemUseOnInjector.RELAY), "it calls the relay on this stack");
		new Analyzer<>(new BasicVerifier()).analyze(STACK, lambda);
		MethodNode bridge = method(stack, ItemUseOnInjector.BRIDGE, ItemUseOnInjector.RELAY_DESC);
		assertNotEquals(0, bridge.access & Opcodes.ACC_STATIC);
		assertTrue(calls(bridge, ItemUseOnInjector.CONTEXT, "getItemInHand") && calls(bridge, STACK, ItemUseOnInjector.RELAY));
		new Analyzer<>(new BasicVerifier()).analyze(STACK, bridge);

		long itemUseOnCalls = stack.methods.stream().filter(m -> calls(m, ItemUseOnInjector.ITEM, "useOn")).count();
		assertEquals(1, itemUseOnCalls, "the relay is the only ItemStack method that calls Item.useOn");
		assertSame(out, new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, out, null), "a second pass changes nothing");
	}

	@Test void bothFamiliesServerCallsGoThroughTheStackTheItemWasReadFrom() throws Exception {
		for (Object[] hooks : new Object[][] { { FORGE_RUNTIME, HOOKS, ItemUseOnInjector.FORGE_HOOKS }, { NEO_RUNTIME, NEO_HOOKS, ItemUseOnInjector.NEO_HOOKS } }) {
			byte[] original = NativeCoremodParityTest.read((Path) hooks[0], (String) hooks[1]);
			byte[] out = new ItemUseOnInjector().transform((String) hooks[2], original, null);
			MethodNode place = method(node(out), "onPlaceItemIntoWorld", ItemUseOnInjector.USE_ON_DESC);
			assertFalse(calls(place, ItemUseOnInjector.ITEM, "useOn"), (String) hooks[1]);
			assertTrue(calls(place, STACK, ItemUseOnInjector.BRIDGE), (String) hooks[1]);
			new Analyzer<>(new BasicVerifier()).analyze((String) hooks[1], place);
			assertSame(out, new ItemUseOnInjector().transform((String) hooks[2], out, null), "a second pass changes nothing");
		}
	}

	@Test void neoForgesAndVanillasOwnBodiesAreLeftAlone() throws Exception {
		byte[] neo = NativeCoremodParityTest.read(NEO_PATCHED, STACK);
		assertSame(neo, new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, neo, null), "NeoForge's body already posts its event");
		byte[] vanilla = NativeCoremodParityTest.read(VANILLA, STACK);
		assertSame(vanilla, new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, vanilla, null), "vanilla's body makes the call itself");
	}

	@Test void theSwitchLeavesBothAlone() throws Exception {
		System.setProperty(ItemUseOnInjector.PROPERTY, "off");
		byte[] stack = NativeCoremodParityTest.read(MERGED, STACK), hooks = NativeCoremodParityTest.read(FORGE_RUNTIME, HOOKS),
				neo = NativeCoremodParityTest.read(NEO_RUNTIME, NEO_HOOKS);
		assertSame(stack, new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, stack, null));
		assertSame(hooks, new ItemUseOnInjector().transform(ItemUseOnInjector.FORGE_HOOKS, hooks, null));
		assertSame(neo, new ItemUseOnInjector().transform(ItemUseOnInjector.NEO_HOOKS, neo, null));
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		return Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name));
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow(() -> new AssertionError(name + desc));
	}
}
