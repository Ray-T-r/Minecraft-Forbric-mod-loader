package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.Ecosystem;

/** Fabric mods' injectors on the merged base's carrier stubs, as shipped, against the real merged classes. */
@ResourceLock("system-properties")
class MixinStubRebindTest {
	private static final Path MERGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
	private static final Path POPULAR = Path.of("run/client-popular/mods");
	private static final Path MERGED_PACK = Path.of("run/client-merged-pack/mods");

	@AfterEach void reset() {
		System.clearProperty(MixinStubRebind.PROPERTY);
		MixinStubRebind.forget();
	}

	@Test void architecturysBreakSpeedMovesToTheBodyAndStillReceivesTheState() throws Exception {
		ClassNode mixin = fromJar(POPULAR.resolve("architectury-fabric-21.1.10.jar"), "dev/architectury/mixin/fabric/MixinPlayer");
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> player));
		MethodNode outer = mixin.methods.stream().filter(m -> m.name.equals("breakSpeed")).findFirst().orElseThrow();
		assertEquals(List.of("getDestroySpeed(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)F"),
				MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(outer), "method")));
		assertTrue(outer.desc.startsWith("(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
				+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"), outer.desc);
		assertNull(MixinFit.injectorOf(mixin.methods.stream().filter(m -> m.name.equals("breakSpeed" + MixinHandlerShim.INNER_SUFFIX)).findFirst().orElseThrow()));
		List<Integer> loads = Arrays.stream(outer.instructions.toArray()).filter(VarInsnNode.class::isInstance).map(i -> ((VarInsnNode) i).var).toList();
		assertEquals(List.of(0, 1, 3), loads, "this, the state (the stub passes its only argument first), the callback");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, outer);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "a second pass changes nothing");
	}

	@Test void fabricApisElytraCheckMovesWhereItsFieldReadIs() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-entity-events-v1", "net/fabricmc/fabric/mixin/entity/event/elytra/LivingEntityMixin");
		ClassNode living = merged("net/minecraft/world/entity/LivingEntity");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> living));
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("injectElytraCheck")).findFirst().orElseThrow();
		assertEquals(List.of("canGlide(Z)Z"), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")));
	}

	@Test void aPairVanillaAlreadyHasStaysWhereTheModPutIt() throws Exception {
		Path xaero;
		try (var files = Files.list(MERGED_PACK)) {
			xaero = files.filter(p -> p.getFileName().toString().contains("xaerominimap-fabric")).findFirst().orElse(null);
		}
		Assumptions.assumeTrue(xaero != null, "Xaero's Minimap (Fabric) fixture absent");
		ClassNode mixin = fromJar(xaero, "xaero/common/mixin/MixinFabricMinecraftClient");
		ClassNode minecraft = merged("net/minecraft/client/Minecraft");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> minecraft),
				"vanilla's own disconnect(Screen, boolean) forwards to its three-argument overload: the mod chose it");
	}

	@Test void aNeoForgeModsMixinIsLeftAsNeoForgeWouldHaveIt() throws Exception {
		ClassNode mixin = fromJar(POPULAR.resolve("architectury-fabric-21.1.10.jar"), "dev/architectury/mixin/fabric/MixinPlayer");
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.NEOFORGE);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "compiled against the stub-first shape: native behaviour");
		MixinStubRebind.forget();
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "no known owner: no move");
	}

	@Test void theSwitchMovesNothing() throws Exception {
		System.setProperty(MixinStubRebind.PROPERTY, "off");
		ClassNode mixin = fromJar(POPULAR.resolve("architectury-fabric-21.1.10.jar"), "dev/architectury/mixin/fabric/MixinPlayer");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player));
	}

	private static ClassNode merged(String name) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(MERGED), "actual game required");
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	private static ClassNode fromJar(Path jar, String name) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(jar), jar + " absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name + ".class");
			assertNotNull(entry, name);
			return MixinFit.parse(zip.getInputStream(entry).readAllBytes());
		}
	}
}
