package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.transform.ItemUseOnInjector;

/** fabric-api's ItemEvents.USE_ON wrap, as shipped, against the merged ItemStack with and without the kernel's relay. */
@ResourceLock("system-properties")
class MixinRelocatedCallTest {
	private static final String STACK_MIXIN = "net/fabricmc/fabric/mixin/event/interaction/ItemStackMixin";
	private static final String RELAY = ItemUseOnInjector.RELAY + ItemUseOnInjector.RELAY_DESC;

	@AfterEach void reset() {
		System.clearProperty(MixinRelocatedCall.PROPERTY);
	}

	@Test void fabricsUseOnWrapSelectsTheRelayAndItsUseWrapStays() throws Exception {
		ClassNode mixin = fabric(), stack = relayed();
		assertEquals(1, MixinRelocatedCall.adapt(mixin, name -> stack));
		assertEquals(List.of(RELAY), selectors(mixin, "handleUseOnEvent"));
		assertEquals(List.of("use"), selectors(mixin, "handleUseEvent"), "the use wrap binds in use and is not touched");
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> stack), "a second pass changes nothing");
	}

	/**
	 * The genuine NeoForge base: its useOn posts ITEM_AFTER_BLOCK itself and makes the call in the same two places
	 * MinecraftForge's does, so fabric-api's wrap had nothing to bind until ItemUseOnInjector routed NeoForge's body too.
	 */
	@Test void onTheGenuineBaseFabricsUseOnWrapSelectsTheRelay() throws Exception {
		byte[] raw = net.forbric.kernel.transform.ChainReferences.read(net.forbric.kernel.transform.ChainReferences.genuineJars(),
				"net/minecraft/world/item/ItemStack");
		ClassNode genuine = node(raw), mixin = fabric();
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> genuine), "premise: NeoForge's body alone has no relay to move to");
		assertEquals(List.of("useOn"), selectors(mixin, "handleUseOnEvent"));
		ClassNode stack = node(new ItemUseOnInjector().transform("net.minecraft.world.item.ItemStack", raw, null));
		assertEquals(1, MixinRelocatedCall.adapt(mixin, name -> stack));
		assertEquals(List.of(RELAY), selectors(mixin, "handleUseOnEvent"));
		assertEquals(List.of("use"), selectors(mixin, "handleUseEvent"), "the use wrap binds in use and is not touched");
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	@Test void nothingMovesWithoutTheRelay() throws Exception {
		ClassNode mixin = fabric(), merged = merged(false);
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> merged), "the transformer did not run: no proof");
		assertEquals(List.of("useOn"), selectors(mixin, "handleUseOnEvent"));
		ClassNode vanilla = game(TestFixtures.vanillaJar());
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> vanilla), "vanilla's useOn makes the call itself");
	}

	/**
	 * A merged useOn ItemUseOnInjector declines — its client lambda does one thing more than {@code getItem().useOn(context)}
	 * — keeps fabric-api's wrap where it was compiled: the class gains only a bridge that calls Item.useOn itself and no
	 * relay. useOn still makes no Item.useOn call, which is the half of the proof a declined body always meets, so the
	 * relay is the whole of what stops the move. Measured before: the relay went in ahead of the decline, and the wrap
	 * moved onto a method only the server's call reached.
	 */
	@Test void aDeclinedBodyGivesTheWrapNoRelayToMoveTo() throws Exception {
		ClassNode declined = merged(true, true), routed = relayed();
		assertTrue(declined.methods.stream().anyMatch(m -> m.name.equals("forbric$useOnItemFor")), "premise: the bridge went in");
		ClassNode mixin = fabric();
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> declined));
		assertEquals(List.of("useOn"), selectors(mixin, "handleUseOnEvent"), "the wrap stays on useOn");
		assertEquals(1, MixinRelocatedCall.adapt(mixin, name -> routed), "control: the same wrap moves onto a routed body");
	}

	@Test void theSwitchLeavesItAlone() throws Exception {
		System.setProperty(MixinRelocatedCall.PROPERTY, "off");
		ClassNode mixin = fabric(), stack = relayed();
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> stack));
	}

	@Test void onlyHandlersThatDescribeTheCallMove() {
		MixinRelocatedCall.Relocation relocation = MixinRelocatedCall.RELOCATIONS.getFirst();
		String item = "Lnet/minecraft/world/item/Item;", context = "Lnet/minecraft/world/item/context/UseOnContext;",
				result = "Lnet/minecraft/world/InteractionResult;", op = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
		String wrap = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
		assertTrue(MixinRelocatedCall.callShaped(wrap, "(" + item + context + op + ")" + result, relocation));
		assertFalse(MixinRelocatedCall.callShaped(wrap, "(" + item + context + op + context + ")" + result, relocation),
				"a trailing capture of useOn's own argument has nothing to bind to in the relay");
		assertTrue(MixinRelocatedCall.callShaped("Lorg/spongepowered/asm/mixin/injection/Redirect;", "(" + item + context + ")" + result, relocation));
		assertTrue(MixinRelocatedCall.callShaped("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", "(" + result + ")" + result, relocation));
		assertFalse(MixinRelocatedCall.callShaped("Lorg/spongepowered/asm/mixin/injection/Inject;",
				"(" + context + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", relocation),
				"an @Inject takes useOn's parameters and callback");
	}

	@Test void anInjectAtTheCallStaysPut() throws Exception {
		ClassNode mixin = fabric(), stack = relayed();
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("handleUseOnEvent")).findFirst().orElseThrow();
		AnnotationNode wrap = MixinFit.injectorOf(handler);
		wrap.desc = "Lorg/spongepowered/asm/mixin/injection/Inject;";
		handler.desc = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType("Lnet/minecraft/world/item/context/UseOnContext;"),
				Type.getType("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"));
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> stack));
	}

	private static List<String> selectors(ClassNode mixin, String handler) {
		MethodNode method = mixin.methods.stream().filter(m -> m.name.equals(handler)).findFirst().orElseThrow();
		return new ArrayList<>(MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(method), "method")));
	}

	private static ClassNode fabric() throws Exception {
		return StagedFabricMixinFixture.mixin("fabric-events-interaction-v0", STACK_MIXIN);
	}

	private static ClassNode relayed() throws Exception {
		return merged(true);
	}

	private static ClassNode merged(boolean relayed) throws Exception {
		return merged(relayed, false);
	}

	/** {@code declined}: the client lambda gets a {@code dup; pop} after {@code getItem()}, which is not the reviewed shape. */
	private static ClassNode merged(boolean relayed, boolean declined) throws Exception {
		Path jar = TestFixtures.stagedBase();
		TestFixtures.requireFile(Fixture.STAGED, jar, "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			byte[] bytes = zip.getInputStream(zip.getEntry("net/minecraft/world/item/ItemStack.class")).readAllBytes();
			if (declined) bytes = offShape(bytes);
			if (relayed) bytes = new ItemUseOnInjector().transform("net.minecraft.world.item.ItemStack", bytes, null);
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			return node;
		}
	}

	private static byte[] offShape(byte[] bytes) {
		ClassNode stack = node(bytes);
		MethodNode lambda = stack.methods.stream().filter(m -> m.name.equals("lambda$useOn$0")).findFirst().orElseThrow();
		org.objectweb.asm.tree.AbstractInsnNode getItem = java.util.Arrays.stream(lambda.instructions.toArray())
				.filter(i -> i instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("getItem")).findFirst().orElseThrow();
		lambda.instructions.insert(getItem, new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.POP));
		lambda.instructions.insert(getItem, new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		stack.accept(writer);
		return writer.toByteArray();
	}

	private static ClassNode game(Path jar) throws Exception {
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry("net/minecraft/world/item/ItemStack.class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}
}
