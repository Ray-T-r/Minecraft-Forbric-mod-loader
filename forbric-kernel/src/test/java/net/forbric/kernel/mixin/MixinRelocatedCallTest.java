package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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

	@Test void nothingMovesWithoutTheRelay() throws Exception {
		ClassNode mixin = fabric(), merged = merged(false);
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> merged), "the transformer did not run: no proof");
		assertEquals(List.of("useOn"), selectors(mixin, "handleUseOnEvent"));
		ClassNode vanilla = game(Path.of(System.getProperty("user.home"), "Library/Application Support/minecraft/versions/26.2/26.2.jar"));
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> vanilla), "vanilla's useOn makes the call itself");
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
		Path jar = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
		Assumptions.assumeTrue(Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			byte[] bytes = zip.getInputStream(zip.getEntry("net/minecraft/world/item/ItemStack.class")).readAllBytes();
			if (relayed) bytes = new ItemUseOnInjector().transform("net.minecraft.world.item.ItemStack", bytes, null);
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			return node;
		}
	}

	private static ClassNode game(Path jar) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry("net/minecraft/world/item/ItemStack.class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}
}
