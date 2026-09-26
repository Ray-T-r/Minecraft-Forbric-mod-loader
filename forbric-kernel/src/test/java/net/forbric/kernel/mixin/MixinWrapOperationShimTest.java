package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** fabric-api's pick-block wrap, as shipped, against the merged and vanilla packet listener. */
@ResourceLock("system-properties")
class MixinWrapOperationShimTest {
	private static final String MIXIN = "net/fabricmc/fabric/mixin/event/interaction/ServerGamePacketListenerImplMixin";
	private static final String LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	private static final String NEO_CLONE = "Lnet/minecraft/world/level/block/state/BlockState;getCloneItemStack(Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/LevelReader;ZLnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/item/ItemStack;";

	@AfterEach void reset() {
		System.clearProperty(MixinWrapOperationShim.PROPERTY);
	}

	@Test void pickBlockWrapBindsToNeoForgesCallAndHandsTheHandlerItsOwnOrder() throws Exception {
		ClassNode mixin = fabric(), target = game(merged());
		assertEquals(1, MixinWrapOperationShim.adapt(mixin, name -> target), "only the block wrap; the entity wrap binds as written");

		MethodNode outer = method(mixin, "onPickItemFromBlock");
		assertEquals("(" + STATE + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/LevelReader;ZLnet/minecraft/world/entity/player/Player;"
				+ "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;"
				+ "Lnet/minecraft/network/protocol/game/ServerboundPickItemFromBlockPacket;)Lnet/minecraft/world/item/ItemStack;", outer.desc);
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(outer)).getFirst();
		assertEquals(NEO_CLONE, MixinFit.value(at, "target"));
		assertNotNull(outer.invisibleParameterAnnotations[6], "the packet's @Local(argsOnly) moved with it");
		assertTrue(outer.invisibleParameterAnnotations[0] == null || outer.invisibleParameterAnnotations[0].isEmpty());

		MethodNode inner = method(mixin, "onPickItemFromBlock" + MixinHandlerShim.INNER_SUFFIX);
		assertNull(MixinFit.injectorOf(inner), "fabric-api's own handler is only called");
		List<Integer> loads = Arrays.stream(outer.instructions.toArray()).filter(VarInsnNode.class::isInstance).map(i -> ((VarInsnNode) i).var).toList();
		assertEquals(List.of(0, 1, 3, 2, 4, 6, 5, 7), loads,
				"this, state, level (slot 3), pos (slot 2), includeData, the operation, the player for the extras, the packet");
		assertTrue(Arrays.stream(outer.instructions.toArray()).anyMatch(i -> i instanceof LdcInsnNode ldc && "1,0,2".equals(ldc.cst)),
				"level→1, pos→0, includeData→2 in NeoForge's call");
		assertTrue(Arrays.stream(outer.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c
				&& c.owner.equals(MixinWrapOperationShim.RUNTIME) && c.name.equals("reordered")));
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, outer);
		assertEquals(0, MixinWrapOperationShim.adapt(mixin, name -> target), "a second pass changes nothing");
	}

	@Test void vanillasBodyBindsAsWritten() throws Exception {
		ClassNode mixin = fabric(), target = game(Path.of(System.getProperty("user.home"),
				"Library/Application Support/minecraft/versions/26.2/26.2.jar"));
		assertEquals(0, MixinWrapOperationShim.adapt(mixin, name -> target));
	}

	@Test void theSwitchWrapsNothing() throws Exception {
		System.setProperty(MixinWrapOperationShim.PROPERTY, "off");
		ClassNode mixin = fabric(), target = game(merged());
		assertEquals(0, MixinWrapOperationShim.adapt(mixin, name -> target));
	}

	@Test void fabricNetworkingsCodecWrapsStayUnboundBecauseTheKernelServesThoseCodecs() throws Exception {
		for (String[] pair : new String[][] {
				{ "net/fabricmc/fabric/mixin/networking/ServerboundCustomPayloadPacketMixin", "net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket" },
				{ "net/fabricmc/fabric/mixin/networking/ServerConfigurationPacketListenerImplMixin", "net/minecraft/server/network/ServerConfigurationPacketListenerImpl" } }) {
			ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-networking-api-v1", pair[0]), target = game(merged(), pair[1]);
			assertEquals(0, MixinWrapOperationShim.adapt(mixin, name -> target), pair[0] + " has the shape but no reviewed row");
		}
	}

	/**
	 * MixinFit asks the shim: the reviewed pick-block wrap's reordered call reads as resolved (the shim will bind it),
	 * and as missing with the shim switched off.
	 */
	@Test void theVerdictFollowsTheReviewedWrap() throws Exception {
		byte[] mixin = StagedFabricMixinFixture.bytes(fabric());
		byte[] listener = StagedFabricMixinFixture.bytes(game(merged()));
		java.util.function.Function<String, byte[]> resolver = name -> name.equals(LISTENER + ".class") ? listener : null;
		MixinFit.Result fit = MixinFit.evaluate(mixin, resolver);
		assertTrue(fit.unresolved().stream().noneMatch(u -> u.contains("getCloneItemStack")), fit.reason());
		System.setProperty(MixinWrapOperationShim.PROPERTY, "off");
		MixinFit.Result off = MixinFit.evaluate(mixin, resolver);
		assertTrue(off.unresolved().stream().anyMatch(u -> u.contains("getCloneItemStack in handlePickItemFromBlock")), off.reason());
	}

	/**
	 * fabric-networking's configuration-channel wrap on the decorator call NeoForge widened: no adapter binds it (a
	 * wrap there would put Fabric's lookup in front of the kernel's), and the verdict now says so instead of reading
	 * FIT because a widened call exists. PARTIAL keeps the mixin by default; only -Dforbric.mixinFit=strict drops it.
	 */
	@Test void fabricNetworkingsDecoratorWrapIsReportedAsTheMissItIs() throws Exception {
		String name = "net/fabricmc/fabric/mixin/networking/ServerConfigurationPacketListenerImplMixin";
		String listener = "net/minecraft/server/network/ServerConfigurationPacketListenerImpl";
		byte[] mixin = StagedFabricMixinFixture.bytes(StagedFabricMixinFixture.mixin("fabric-networking-api-v1", name));
		byte[] target = StagedFabricMixinFixture.bytes(game(merged(), listener));
		MixinFit.Result fit = MixinFit.evaluate(mixin, n -> n.equals(listener + ".class") ? target : null);
		assertEquals(MixinFit.Verdict.PARTIAL, fit.verdict(), fit.reason());
		assertTrue(fit.unresolved().contains("@At(INVOKE) RegistryFriendlyByteBuf.decorator in handleConfigurationFinished"),
				fit.unresolved().toString());
		assertFalse(fit.shouldSuppress(), "PARTIAL is kept unless strict");
	}

	@Test void aRepeatedTypeIsAGuessAndIsRefused() {
		Type pos = Type.getObjectType("net/minecraft/core/BlockPos"), level = Type.getObjectType("net/minecraft/world/level/LevelReader");
		assertArrayEquals(new int[] { 1, 0, 2 }, MixinWrapOperationShim.embedding(new Type[] { level, pos, Type.BOOLEAN_TYPE },
				new Type[] { pos, level, Type.BOOLEAN_TYPE, Type.getObjectType("net/minecraft/world/entity/player/Player") }));
		assertNull(MixinWrapOperationShim.embedding(new Type[] { pos }, new Type[] { pos, pos }), "two candidates for one argument");
		assertNull(MixinWrapOperationShim.embedding(new Type[] { pos, pos }, new Type[] { pos, pos, level }));
		assertNull(MixinWrapOperationShim.embedding(new Type[] { level }, new Type[] { pos }), "an argument the call no longer has");
	}

	private static Path merged() {
		return Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
	}

	private static ClassNode fabric() throws Exception {
		return StagedFabricMixinFixture.mixin("fabric-events-interaction-v0", MIXIN);
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	private static ClassNode game(Path jar) throws Exception {
		return game(jar, LISTENER);
	}

	private static ClassNode game(Path jar, String name) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}
}
