package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** NeoForge's ItemStack.useOn and CommonHooks.onPlaceItemIntoWorld, on the real classes. */
@ResourceLock("system-properties")
class ItemUseOnInjectorTest {
	private static final Path BASE = TestFixtures.stagedBase();
	private static final Path VANILLA = TestFixtures.vanillaJar();
	private static final String STACK = "net/minecraft/world/item/ItemStack";
	private static final Path NEO_RUNTIME = TestFixtures.neoCarrier();
	private static final String NEO_HOOKS = "net/neoforged/neoforge/common/CommonHooks";

	@AfterEach void reset() { System.clearProperty(ItemUseOnInjector.PROPERTY); }

	/**
	 * NeoForge's useOn, the base's: its own post stays the only one, its client lambda goes through the relay, and the
	 * relay is the one ItemStack method that calls Item.useOn.
	 */
	@Test void neoForgesUseOnKeepsItsOwnPostAndOnlyTheRelayCallsItemUseOn() throws Exception {
		byte[] original = NativeCoremodParityTest.read(BASE, STACK);
		MethodNode before = method(node(original), "useOn", ItemUseOnInjector.USE_ON_DESC);
		assertEquals(1, news(before, ItemUseOnInjector.EVENT), "premise: NeoForge's body posts UseItemOnBlockEvent itself");
		assertFalse(calls(before, ItemUseOnInjector.ITEM, "useOn"), "premise: and makes no Item.useOn call itself");

		byte[] out = new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, original, null);
		ClassNode stack = node(out);
		MethodNode useOn = method(stack, "useOn", ItemUseOnInjector.USE_ON_DESC);
		assertEquals(1, news(useOn, ItemUseOnInjector.EVENT), "no second post went in");
		assertEquals(real(before).size(), real(useOn).size(), "useOn itself is NeoForge's, instruction for instruction");

		MethodNode relay = method(stack, ItemUseOnInjector.RELAY, ItemUseOnInjector.RELAY_DESC);
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

	/**
	 * A body shaped like NeoForge's that posts no UseItemOnBlockEvent — the shape MinecraftForge's body had on the merged
	 * base — is declined: no post is put in (the event is NeoForge's to post, and only NeoForge's body is reviewed), and
	 * the class gains only the bridge that calls Item.useOn itself.
	 */
	@Test void aBodyThatPostsNoUseItemOnBlockEventGetsNoPostAndNoRelay() throws Exception {
		byte[] unposted = withoutTheEvent(NativeCoremodParityTest.read(BASE, STACK));
		MethodNode before = method(node(unposted), "useOn", ItemUseOnInjector.USE_ON_DESC);
		assertEquals(0, news(before, ItemUseOnInjector.EVENT), "premise: this useOn posts no UseItemOnBlockEvent");
		byte[] out = new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, unposted, null);
		assertFalse(ItemUseOnInjector.routed(out));
		MethodNode useOn = method(node(out), "useOn", ItemUseOnInjector.USE_ON_DESC);
		assertEquals(0, news(useOn, ItemUseOnInjector.EVENT), "no post went in");
		assertEquals(real(before).size(), real(useOn).size(), "and useOn is left as it was");
		assertOnlyTheDirectBridge(unposted, out);
	}

	/** {@code before} with every UseItemOnBlockEvent reference in useOn pointed at another class: the post is not it. */
	private static byte[] withoutTheEvent(byte[] before) {
		ClassNode stack = node(before);
		MethodNode useOn = method(stack, "useOn", ItemUseOnInjector.USE_ON_DESC);
		String other = "net/forbric/test/NotUseItemOnBlockEvent";
		for (AbstractInsnNode insn : useOn.instructions) {
			if (insn instanceof TypeInsnNode type && type.desc.equals(ItemUseOnInjector.EVENT)) type.desc = other;
			if (insn instanceof MethodInsnNode call && call.owner.equals(ItemUseOnInjector.EVENT)) call.owner = other;
		}
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		stack.accept(writer);
		return writer.toByteArray();
	}

	@Test void neoForgesServerCallGoesThroughTheStackTheItemWasReadFrom() throws Exception {
		for (Object[] hooks : new Object[][] { { NEO_RUNTIME, NEO_HOOKS, ItemUseOnInjector.NEO_HOOKS } }) {
			byte[] original = NativeCoremodParityTest.read((Path) hooks[0], (String) hooks[1]);
			byte[] out = new ItemUseOnInjector().transform((String) hooks[2], original, null);
			MethodNode place = method(node(out), "onPlaceItemIntoWorld", ItemUseOnInjector.USE_ON_DESC);
			assertFalse(calls(place, ItemUseOnInjector.ITEM, "useOn"), (String) hooks[1]);
			assertTrue(calls(place, STACK, ItemUseOnInjector.BRIDGE), (String) hooks[1]);
			new Analyzer<>(new BasicVerifier()).analyze((String) hooks[1], place);
			assertSame(out, new ItemUseOnInjector().transform((String) hooks[2], out, null), "a second pass changes nothing");
		}
	}

	/**
	 * The genuine NeoForge base, as Stage 4 runs it: NeoForge's useOn already posts ITEM_AFTER_BLOCK, so it is routed but
	 * not posted a second time, and both halves land together — CommonHooks is pointed at a bridge ItemStack really has.
	 * Measured before this: the ItemStack half stood down on NeoForge's body while CommonHooks was still pointed at its
	 * bridge, a NoSuchMethodError on every item used on a block that no anchor saw.
	 */
	@Test void onTheGenuineBaseNeoForgesBodyIsRoutedOncePostedAndBothHalvesLink() throws Exception {
		List<Path> genuine = ChainReferences.genuineJars();
		Map<String, byte[]> before = new HashMap<>(), after = new HashMap<>();
		for (String[] target : new String[][] { { STACK, ItemUseOnInjector.STACK }, { NEO_HOOKS, ItemUseOnInjector.NEO_HOOKS } }) {
			byte[] original = ChainReferences.read(genuine, target[0]);
			before.put(target[0], original);
			after.put(target[0], new ItemUseOnInjector().transform(target[1], original, null));
		}
		MethodNode posted = method(node(before.get(STACK)), "useOn", ItemUseOnInjector.USE_ON_DESC);
		assertTrue(calls(posted, ItemUseOnInjector.EVENT, "<init>"), "premise: NeoForge's body posts UseItemOnBlockEvent itself");
		assertFalse(ItemUseOnInjector.routed(before.get(STACK)), "premise: and Fabric's wrap has no Item.useOn call in it to find");

		ClassNode stack = node(after.get(STACK));
		assertTrue(ItemUseOnInjector.routed(after.get(STACK)), "the client lambda reaches the item through the relay");
		MethodNode useOn = method(stack, "useOn", ItemUseOnInjector.USE_ON_DESC);
		assertEquals(1, Arrays.stream(useOn.instructions.toArray()).filter(i -> i instanceof TypeInsnNode t
				&& t.getOpcode() == Opcodes.NEW && t.desc.equals(ItemUseOnInjector.EVENT)).count(), "NeoForge's own post, and only it");
		assertEquals(real(posted).size(), real(useOn).size(), "useOn itself is NeoForge's, instruction for instruction");
		for (MethodNode m : stack.methods) new Analyzer<>(new BasicVerifier()).analyze(STACK, m);
		assertTrue(calls(method(node(after.get(NEO_HOOKS)), "onPlaceItemIntoWorld", ItemUseOnInjector.USE_ON_DESC), STACK,
				ItemUseOnInjector.BRIDGE), "the server's call goes through the bridge");
		ChainReferences.assertNoneDangling(before, after, genuine);

		// The control: the hooks' edit against the ItemStack the game would have WITHOUT the stack half is dangling, which
		// is the half-apply this used to be — so the check above is one that can fail.
		List<String> alone = ChainReferences.dangling(Map.of(NEO_HOOKS, before.get(NEO_HOOKS)), Map.of(NEO_HOOKS, after.get(NEO_HOOKS)),
				genuine);
		assertEquals(1, alone.size(), alone.toString());
		assertTrue(alone.getFirst().contains("MEMBER_ABSENT M " + STACK + " " + ItemUseOnInjector.BRIDGE), alone.toString());
	}

	@Test void vanillasBodyKeepsItsCallAndOnlyGainsTheBridge() throws Exception {
		byte[] vanilla = NativeCoremodParityTest.read(VANILLA, STACK);
		byte[] out = new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, vanilla, null);
		assertFalse(ItemUseOnInjector.routed(out), "vanilla's useOn makes the call itself, so nothing is routed or moved");
		assertEquals(real(method(node(vanilla), "useOn", ItemUseOnInjector.USE_ON_DESC)).size(),
				real(method(node(out), "useOn", ItemUseOnInjector.USE_ON_DESC)).size(), "and useOn is left as it was");
		assertOnlyTheDirectBridge(vanilla, out);
	}

	/**
	 * NeoForge's body with a lambda that is not exactly {@code getItem().useOn(context)}: the edit stands down before it
	 * changes anything, and the class gains only the bridge, which calls {@code Item.useOn} itself.
	 * No relay, so MixinRelocatedCall has nothing to move fabric-api's wrap onto (MixinRelocatedCallTest runs that half).
	 * Measured before: the relay and a bridge through it went in ahead of the decision, so the server's call reached a
	 * relay the client's never did.
	 */
	@Test void aDeclinedBodyGainsOnlyTheBridgeAndNoRelay() throws Exception {
		byte[] declined = declinedLambda(NativeCoremodParityTest.read(BASE, STACK));
		byte[] out = new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, declined, null);
		assertFalse(ItemUseOnInjector.routed(out));
		MethodNode useOn = method(node(out), "useOn", ItemUseOnInjector.USE_ON_DESC);
		assertEquals(news(method(node(declined), "useOn", ItemUseOnInjector.USE_ON_DESC), ItemUseOnInjector.EVENT), news(useOn, ItemUseOnInjector.EVENT),
				"no post went in beside NeoForge's own: the edit stood down first");
		assertEquals(real(method(node(declined), "useOn", ItemUseOnInjector.USE_ON_DESC)).size(), real(useOn).size());
		assertOnlyTheDirectBridge(declined, out);
	}

	/**
	 * {@code before} with its client lambda's {@code getItem().useOn(context)} given one more instruction ahead of the
	 * call — a {@code dup; pop} on the item — so the reviewed shape no longer matches and nothing else changes.
	 */
	private static byte[] declinedLambda(byte[] before) {
		ClassNode stack = node(before);
		MethodNode lambda = method(stack, "lambda$useOn$0", "(L" + ItemUseOnInjector.CONTEXT + ";L" + ItemUseOnInjector.CONTEXT
				+ ";)L" + ItemUseOnInjector.RESULT + ";");
		AbstractInsnNode getItem = real(lambda).get(1);
		assertTrue(getItem instanceof MethodInsnNode call && call.name.equals("getItem"), "premise: the reviewed lambda");
		lambda.instructions.insert(getItem, new InsnNode(Opcodes.POP));
		lambda.instructions.insert(getItem, new InsnNode(Opcodes.DUP));
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		stack.accept(writer);
		return writer.toByteArray();
	}

	/** {@code out} is {@code before} plus the static bridge alone, and that bridge makes the Item.useOn call itself. */
	private static void assertOnlyTheDirectBridge(byte[] before, byte[] out) throws Exception {
		ClassNode stack = node(out);
		assertEquals(node(before).methods.size() + 1, stack.methods.size(), "exactly one method went in");
		assertTrue(stack.methods.stream().noneMatch(m -> m.name.equals(ItemUseOnInjector.RELAY)),
				"no relay: nothing for MixinRelocatedCall to move an injector onto");
		MethodNode bridge = method(stack, ItemUseOnInjector.BRIDGE, ItemUseOnInjector.RELAY_DESC);
		assertNotEquals(0, bridge.access & Opcodes.ACC_STATIC);
		assertTrue(calls(bridge, ItemUseOnInjector.ITEM, "useOn"), "the bridge calls Item.useOn itself");
		new Analyzer<>(new BasicVerifier()).analyze(STACK, bridge);
		assertSame(out, new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, out, null), "a second pass changes nothing");
	}

	@Test void theRoutedClaimIsReportedForTheEndStateAndOnlyForIt() throws Exception {
		byte[] base = NativeCoremodParityTest.read(BASE, STACK), vanilla = NativeCoremodParityTest.read(VANILLA, STACK);
		List<String> hits = new java.util.ArrayList<>();
		ItemUseOnInjector repair = new ItemUseOnInjector();
		byte[] once = repair.transform(ItemUseOnInjector.STACK, base, null, hits::add);
		assertEquals(List.of(ItemUseOnInjector.ROUTED_CLAIM), hits, "NeoForge's body routed: the claim is hit");
		repair.transform(ItemUseOnInjector.STACK, once, null, hits::add);
		assertEquals(2, hits.size(), "an already-routed class still reports the end state");
		repair.transform(ItemUseOnInjector.STACK, vanilla, null, hits::add);
		assertEquals(2, hits.size(), "the bridge alone is not the repair");
	}

	@Test void theSwitchLeavesBothAlone() throws Exception {
		System.setProperty(ItemUseOnInjector.PROPERTY, "off");
		byte[] stack = NativeCoremodParityTest.read(BASE, STACK), neo = NativeCoremodParityTest.read(NEO_RUNTIME, NEO_HOOKS);
		assertSame(stack, new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, stack, null));
		assertSame(neo, new ItemUseOnInjector().transform(ItemUseOnInjector.NEO_HOOKS, neo, null));
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		return Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name));
	}

	private static long news(MethodNode method, String type) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW
				&& t.desc.equals(type)).count();
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
