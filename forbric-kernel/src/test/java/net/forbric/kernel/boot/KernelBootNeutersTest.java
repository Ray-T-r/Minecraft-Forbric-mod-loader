/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.transform.AnchorSet;
import net.forbric.kernel.transform.MethodBodyNeuter;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The neuter KernelBoot registers, as {@link KernelBoot#neuters} builds it for each side. MinecraftForge's
 * {@code FluidInteractionRegistry.canInteract} must not be on it with the fluid repair on: a MinecraftForge mod's fluid
 * rules run through that method, and a neuter there silenced them while every other test and gate stayed green.
 */
class KernelBootNeutersTest {
	private static final String REGISTRY = ForeignType.FLUID_INTERACTION_REGISTRY.binary(Ecosystem.FORGE);
	private static final String CAN_INTERACT = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z";
	private static final Path FORGE_CARRIER = TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar");
	private static final Path NEO_CARRIER = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);

	@Test void withTheRepairOnNoSideNeutersMinecraftForgesFluidRegistry() {
		for (KernelBoot.Side side : KernelBoot.Side.values()) {
			assertFalse(owners(KernelBoot.neuters(side, true)).contains(REGISTRY), side + ": " + owners(KernelBoot.neuters(side, true)));
		}
	}

	@Test void withTheRepairOffEverySideNeutersItAgain() {
		for (KernelBoot.Side side : KernelBoot.Side.values()) {
			assertTrue(owners(KernelBoot.neuters(side, false)).contains(REGISTRY), side.toString());
		}
	}

	@Test void onTheRealRegistryTheRepairOnLeavesCanInteractWholeAndOffEmptiesIt() throws Exception {
		byte[] registry = read(FORGE_CARRIER, REGISTRY.replace('.', '/'));
		for (KernelBoot.Side side : KernelBoot.Side.values()) {
			assertSame(registry, KernelBoot.neuters(side, true).transform(REGISTRY, registry, null), side + ": the repair on leaves it alone");
			MethodNode emptied = canInteract(KernelBoot.neuters(side, false).transform(REGISTRY, registry, null));
			assertEquals(List.of(Opcodes.ICONST_0, Opcodes.IRETURN), real(emptied), side + ": off, it answers false as before the repair");
			assertTrue(real(canInteract(registry)).size() > 2, "premise: the carrier's canInteract walks its interactions");
		}
	}

	/**
	 * MinecraftForge's own client begin is the MinecraftForge lifecycle, called from Minecraft.<init>. A merged
	 * constructor that keeps it ran gatherAndInitializeMods under the kernel and died on FMLLoader's background scan;
	 * on the client its body must be empty, while NeoForge's no-arg begin (the redirected Main.main trigger) and the
	 * server side are left alone.
	 */
	@Test void onTheClientMinecraftForgesOwnBeginIsEmptiedAndNothingElseOfItsName() throws Exception {
		String forge = ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.FORGE);
		byte[] loader = read(FORGE_CARRIER, forge.replace('.', '/'));
		assertTrue(real(method(loader, "begin", KernelBoot.FORGE_CLIENT_BEGIN_DESC)).size() > 2,
				"premise: the carrier's begin runs MinecraftForge's lifecycle");
		MethodNode emptied = method(KernelBoot.neuters(KernelBoot.Side.CLIENT, true).transform(forge, loader, null),
				"begin", KernelBoot.FORGE_CLIENT_BEGIN_DESC);
		assertEquals(List.of(Opcodes.RETURN), real(emptied));
		assertSame(loader, KernelBoot.neuters(KernelBoot.Side.SERVER, true).transform(forge, loader, null),
				"a dedicated server never runs the client loader, and its neuter has nothing for it");

		String neo = ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE);
		byte[] neoLoader = read(NEO_CARRIER, neo.replace('.', '/'));
		MethodNode neoBegin = method(KernelBoot.neuters(KernelBoot.Side.CLIENT, true).transform(neo, neoLoader, null),
				"begin", "()V");
		assertEquals(real(method(neoLoader, "begin", "()V")), real(neoBegin),
				"NeoForge's begin is the Main.main trigger the lifecycle injector redirects; its body stays whole");
	}

	/**
	 * MinecraftForge's own modifier pass is emptied on both sides, because its modifiers run inside NeoForge's: a merged
	 * initServer that keeps MinecraftForge's about-to-start reached it, and on an integrated server whose world loaded
	 * Fabric's registry list its lookupOrThrow of forge:biome_modifier killed the server. The rest of about-to-start —
	 * the recorded server, the SERVER configs, the event — stays whole.
	 */
	@Test void onEverySideMinecraftForgesOwnModifierPassIsEmptiedAndItsAboutToStartIsNot() throws Exception {
		String hooks = ForeignType.SERVER_LIFECYCLE_HOOKS.binary(Ecosystem.FORGE);
		byte[] original = read(FORGE_CARRIER, hooks.replace('.', '/'));
		String aboutToStart = "(Lnet/minecraft/server/MinecraftServer;)Z";
		assertTrue(real(method(original, KernelBoot.FORGE_RUN_MODIFIERS, KernelBoot.FORGE_RUN_MODIFIERS_DESC)).size() > 2,
				"premise: the carrier's runModifiers walks the modifier registries");
		for (KernelBoot.Side side : KernelBoot.Side.values()) {
			byte[] served = KernelBoot.neuters(side, true).transform(hooks, original, null);
			assertEquals(List.of(Opcodes.RETURN),
					real(method(served, KernelBoot.FORGE_RUN_MODIFIERS, KernelBoot.FORGE_RUN_MODIFIERS_DESC)), side.toString());
			assertEquals(real(method(original, "handleServerAboutToStart", aboutToStart)),
					real(method(served, "handleServerAboutToStart", aboutToStart)), side + ": about-to-start stays whole");
		}
	}

	/**
	 * MinecraftForge's own mod-pack finder is emptied on both sides: the kernel serves those packs, and a merged
	 * configurePackRepository that kept MinecraftForge's call offered every mod's data a second time as mod:&lt;id&gt;.
	 * The pack-name list the rest of the merged game may still ask for is left whole.
	 */
	@Test void onEverySideMinecraftForgesOwnModPackFinderIsEmptied() throws Exception {
		String loader = KernelBoot.FORGE_RESOURCE_PACK_LOADER;
		byte[] original = read(FORGE_CARRIER, loader.replace('.', '/'));
		assertTrue(real(method(original, "loadResourcePacks", KernelBoot.FORGE_LOAD_RESOURCE_PACKS_DESC)).size() > 1,
				"premise: the carrier's loadResourcePacks adds a pack finder");
		for (KernelBoot.Side side : KernelBoot.Side.values()) {
			byte[] served = KernelBoot.neuters(side, true).transform(loader, original, null);
			assertEquals(List.of(Opcodes.RETURN),
					real(method(served, "loadResourcePacks", KernelBoot.FORGE_LOAD_RESOURCE_PACKS_DESC)), side.toString());
			assertEquals(real(method(original, "getPackNames", "()Ljava/util/List;")),
					real(method(served, "getPackNames", "()Ljava/util/List;")), side + ": getPackNames stays whole");
		}
	}

	private static MethodNode method(byte[] bytes, String name, String desc) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
				.orElseThrow(() -> new AssertionError(node.name + " has no " + name + desc));
	}

	private static List<String> owners(MethodBodyNeuter neuter) {
		AnchorSet anchors = neuter.anchors();
		return anchors.anchors() == null ? List.of() : anchors.anchors().stream().map(AnchorSet.Anchor::binaryName).toList();
	}

	private static MethodNode canInteract(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals("canInteract") && m.desc.equals(CAN_INTERACT)).findFirst().orElseThrow();
	}

	private static List<Integer> real(MethodNode method) {
		return java.util.Arrays.stream(method.instructions.toArray()).map(AbstractInsnNode::getOpcode).filter(op -> op >= 0).toList();
	}

	private static byte[] read(Path jar, String internalName) throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, jar, jar + " absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internalName + ".class");
			assertNotNull(entry, internalName + " in " + jar);
			return zip.getInputStream(entry).readAllBytes();
		}
	}
}
