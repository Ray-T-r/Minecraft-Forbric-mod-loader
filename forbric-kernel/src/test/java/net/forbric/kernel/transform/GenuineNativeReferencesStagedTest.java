/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.mixin.NativeReferenceSources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * On the genuine NeoForge-patched base — which embeds no native-reference index — the kernel's references come from the
 * vanilla-reference sidecar (FABRIC) and from the base's own bytes (NEOFORGE), configured exactly as KernelBoot configures
 * them, and the repairs that compare against those references land there. Without the sidecar each of them declines, as
 * the Stage 2.0 census measured (GlideFreshEquipmentInjector, ScopedClearRelayInjector and NativeConstructorFacadeRestorer
 * HIT with an index and DECLINED without one). Nothing here changes what the merged base does: it embeds its own index,
 * which VanillaReferenceStagedTest proves equal to the sidecar.
 */
@ResourceLock("native-game-references")
class GenuineNativeReferencesStagedTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String FUEL = "net/minecraft/world/level/block/entity/FuelValues";
	private static final String FLUID_RENDERER = "net/minecraft/client/renderer/block/FluidRenderer";
	/** Classes the measured adapters and repairs read references of, on both sides. */
	private static final List<String> ADAPTER_CLASSES = List.of(LIVING, FUEL, FLUID_RENDERER,
			"net/minecraft/server/ReloadableServerRegistries", "net/minecraft/server/WorldLoader",
			"net/minecraft/resources/RegistryDataLoader", "net/minecraft/world/item/ItemStack",
			"net/minecraft/world/level/block/state/BlockBehaviour");

	private ForbricClassLoader loader;

	@AfterEach
	void unbind() throws Exception {
		NativeGameReferences.configure(null);
		StagedGameClasses.bind(null);
		if (loader != null) loader.close();
	}

	private static Path genuine() {
		Path genuine = TestFixtures.genuineBase();
		TestFixtures.requireFiles(Fixture.STAGED, "the genuine NeoForge-patched base", genuine);
		return genuine;
	}

	/** Configures and binds the references for a launch on {@code base} with {@code sidecar} (may be null), as KernelBoot does. */
	private void launch(Path base, Path sidecar) throws Exception {
		Path neo = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "the NeoForge carrier", neo);
		NativeGameReferences.configure(NativeReferenceSources.open(base, sidecar));
		loader = new ForbricClassLoader(new URL[] {base.toUri().toURL(), neo.toUri().toURL()},
				getClass().getClassLoader());
		StagedGameClasses.bind(loader);
	}

	@Test
	void theSourcesAreReadOffTheFilesAndSaidInOneLine() {
		Path genuine = genuine();
		String line = NativeReferenceSources.open(genuine, TestFixtures.vanillaReference(genuine)).describe();
		assertTrue(line.contains("native references: FABRIC from the vanilla-reference sidecar"), line);
		assertTrue(line.contains("FORGE none"), "the genuine base carries no MinecraftForge compile: " + line);
		assertTrue(line.contains("NEOFORGE from the base's own bytes"), "its Block declares NeoForge's extension: " + line);
		String bare = NativeReferenceSources.open(genuine, null).describe();
		assertTrue(bare.contains("native references: FABRIC none") && bare.contains("stands down"),
				"without a sidecar the line says what is lost: " + bare);
		assertTrue(NativeReferenceSources.open(genuine, genuine.resolveSibling("no-such-sidecar.jar")).describe()
				.contains("FABRIC none"), "a sidecar that does not exist is no source, and no failure");
	}

	@Test
	void bothHalvesAnswerForTheClassesTheAdaptersRead() throws Exception {
		Path genuine = genuine();
		launch(genuine, TestFixtures.vanillaReference(genuine));
		for (String owner : ADAPTER_CLASSES) {
			assertArrayEquals(write(node(entry(TestFixtures.vanillaJar(), owner + ".class"))),
					write(NativeGameReferences.reference(Ecosystem.FABRIC, owner)), "FABRIC " + owner + " is vanilla's compile");
			assertArrayEquals(write(node(entry(genuine, owner + ".class"))),
					write(NativeGameReferences.reference(Ecosystem.NEOFORGE, owner)), "NEOFORGE " + owner + " is the base's own");
			assertNull(NativeGameReferences.reference(Ecosystem.FORGE, owner), "no FORGE compile exists here");
			assertTrue(NativeGameReferences.has(Ecosystem.FABRIC, owner) && NativeGameReferences.has(Ecosystem.NEOFORGE, owner));
			assertEquals(2, NativeGameReferences.peers(Ecosystem.FORGE, owner).size(), owner);
		}
	}

	@Test
	void withoutTheSidecarTheGenuineBaseHasNoFabricReference() throws Exception {
		Path genuine = genuine();
		launch(genuine, null);
		assertNull(NativeGameReferences.reference(Ecosystem.FABRIC, LIVING), "the regression the sidecar exists for");
		assertNotNull(NativeGameReferences.reference(Ecosystem.NEOFORGE, LIVING), "the base still answers for itself");
		assertNull(new NativeGameReferences(resources(genuine)).get(Ecosystem.FABRIC, LIVING),
				"and a reader of the embedded index alone, which is all there used to be, has nothing at all");
	}

	@Test
	void theRepairsThatCompareAgainstVanillaLandOnTheGenuineBase() throws Exception {
		Path genuine = genuine();
		launch(genuine, TestFixtures.vanillaReference(genuine));
		byte[] living = entry(genuine, LIVING + ".class");

		byte[] glided = new GlideFreshEquipmentInjector().transform(LIVING.replace('/', '.'), living, null);
		assertNotSame(living, glided, "GlideFreshEquipmentInjector lands on the genuine LivingEntity");
		assertNotNull(method(node(glided), "forbric$equipmentAheadOfAttributes", "()Z"));

		byte[] relayed = new ScopedClearRelayInjector().transform(LIVING.replace('/', '.'), living, null);
		assertNotSame(living, relayed, "ScopedClearRelayInjector lands on the genuine LivingEntity");
		assertEquals(0, clears(method(node(living), "removeAllEffects", "()Z")), "NeoForge removes a computed subset");
		assertEquals(1, clears(method(node(relayed), "removeAllEffects", "()Z")), "and the relay puts the one clear back");

		byte[] fuel = entry(genuine, FUEL + ".class");
		byte[] restored = new NativeConstructorFacadeRestorer(resources(genuine)).transform(FUEL.replace('/', '.'), fuel, null);
		assertNotSame(fuel, restored, "NativeConstructorFacadeRestorer restores FuelValues' native constructor facade");

		assertNotSame(compatFluid(genuine), localRanges(genuine),
				"LocalRanges widens FluidRenderer.tesselate's local, the one genuine survivor of the merge's pattern");
	}

	/**
	 * LocalRanges asks only whether a class is the base's (any ecosystem's reference pins it), so the base's own bytes are
	 * enough: it lands with no sidecar, and declined on the genuine base for as long as an embedded index was the only
	 * source (the census' "0 without the index"). It runs after the merged-base compat edit of tesselate, as in the chain.
	 */
	@Test
	void localRangesNeedsOnlyTheBasesOwnBytes() throws Exception {
		Path genuine = genuine();
		launch(genuine, null);
		assertNotSame(compatFluid(genuine), localRanges(genuine), "the base answers for itself");
		NativeGameReferences.configure(null);
		StagedGameClasses.bind(loader);
		assertSame(compatFluid(genuine), localRanges(genuine), "with the embedded index as the only source, nothing");
	}

	private byte[] compatFluidBytes;

	/** FluidRenderer after the merged-base compat edit, which the chain runs before LocalRanges. */
	private byte[] compatFluid(Path genuine) throws IOException {
		if (compatFluidBytes == null) {
			byte[] fluid = entry(genuine, FLUID_RENDERER + ".class");
			compatFluidBytes = new ForbricMergedBaseCompatTransformer(resources(genuine))
					.transform(FLUID_RENDERER.replace('/', '.'), fluid, null);
		}
		return compatFluidBytes;
	}

	private byte[] localRanges(Path genuine) throws IOException {
		return new LocalRanges().transform(FLUID_RENDERER.replace('/', '.'), compatFluid(genuine), null);
	}

	@Test
	void andEachDeclinesWithoutTheSidecar() throws Exception {
		Path genuine = genuine();
		launch(genuine, null);
		byte[] living = entry(genuine, LIVING + ".class");
		assertSame(living, new GlideFreshEquipmentInjector().transform(LIVING.replace('/', '.'), living, null));
		assertSame(living, new ScopedClearRelayInjector().transform(LIVING.replace('/', '.'), living, null));
		byte[] fuel = entry(genuine, FUEL + ".class");
		assertSame(fuel, new NativeConstructorFacadeRestorer(resources(genuine)).transform(FUEL.replace('/', '.'), fuel, null));
	}

	private static Function<String, byte[]> resources(Path jar) {
		return path -> {
			try {
				return entry(jar, path);
			} catch (IOException unreadable) {
				throw new AssertionError(unreadable);
			}
		};
	}

	private static byte[] entry(Path jar, String name) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name);
			if (entry == null) return null;
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	private static ClassNode node(byte[] bytes) {
		assertNotNull(bytes);
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		return node;
	}

	private static byte[] write(ClassNode node) {
		assertNotNull(node);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
	}

	private static int clears(MethodNode method) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions)
			if (instruction instanceof MethodInsnNode call && call.name.equals("clear") && call.desc.equals("()V")
					&& call.owner.equals("java/util/Map")) n++;
		return n;
	}
}
