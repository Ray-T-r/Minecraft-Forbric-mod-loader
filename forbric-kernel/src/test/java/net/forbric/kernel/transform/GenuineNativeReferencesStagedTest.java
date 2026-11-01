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
import net.fabricmc.api.EnvType;
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
 * HIT with an index and DECLINED without one). VanillaReferenceStagedTest proves the sidecar itself against this base.
 */
@ResourceLock("native-game-references")
class GenuineNativeReferencesStagedTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String FUEL = "net/minecraft/world/level/block/entity/FuelValues";
	private static final String FLUID_RENDERER = "net/minecraft/client/renderer/block/FluidRenderer";
	private static final String FLUID_INTERACTION = "net/minecraft/world/entity/EntityFluidInteraction";
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
		Path genuine = TestFixtures.stagedBase();
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
		assertFalse(line.replace("NEOFORGE", "").contains("FORGE"), "MinecraftForge is no ecosystem, so the line names none: " + line);
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
					write(NativeGameReferences.reference(Ecosystem.NEOFORGE, owner)),
					"NEOFORGE " + owner + " is the base's own compile");
			assertTrue(NativeGameReferences.has(Ecosystem.FABRIC, owner) && NativeGameReferences.has(Ecosystem.NEOFORGE, owner));
			assertEquals(1, NativeGameReferences.peers(Ecosystem.FABRIC, owner).size(), owner + ": NeoForge's, the one other ecosystem");
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

		// LocalRanges' one merged-base site was FluidRenderer.tesselate as the MinecraftForge fluid-model splice left it; that
		// splice went with MinecraftForge, and NeoForge's own tesselate gives LocalRanges nothing to widen. Its site on
		// NeoForge's base is the kernel's own (localRangesWidensTheOneLocalTheTagTrackerRewriteLeavesInEntityFluidInteraction).
		assertSame(compatFluid(genuine), localRanges(genuine),
				"LocalRanges leaves the genuine FluidRenderer.tesselate as NeoForge ships it");
	}

	/**
	 * Why LocalRanges stays on NeoForge's base (Stage 4 C3): no class of the base or carrier gives it anything, but the
	 * kernel's own chain does. NativeTagTrackerSourceInjector's rewrite of {@code EntityFluidInteraction} leaves one local
	 * whose table entry starts after its reads, and LocalRanges, at the end of the COREMOD phase, moves exactly that one
	 * range back to its store. With {@code -Dforbric.nativeTagTrackerSource=off} the same chain leaves it nothing. The
	 * transformers are KernelBoot's COREMOD fluid segment, in its order, over the staged base; a dedicated server logs the
	 * same count under {@code -Dforbric.debug} ("EntityFluidInteraction: 1 local range(s) now start at the local's
	 * declaring store").
	 */
	@Test
	void localRangesWidensTheOneLocalTheTagTrackerRewriteLeavesInEntityFluidInteraction() throws Exception {
		Path genuine = genuine();
		launch(genuine, TestFixtures.vanillaReference(genuine));
		String binary = FLUID_INTERACTION.replace('/', '.');
		byte[] raw = entry(genuine, FLUID_INTERACTION + ".class");
		byte[] beforeTracker = apply(beforeTagTracker(), binary, raw);
		byte[] tracked = InjectorExecution.transform(tagTracker(genuine), binary, beforeTracker, EnvType.SERVER);
		assertNotSame(beforeTracker, tracked, "premise: the tag-tracker source rewrites NeoForge's EntityFluidInteraction");
		byte[] chained = apply(afterTagTracker(), binary, tracked);
		byte[] widened = InjectorExecution.transform(new LocalRanges(), binary, chained, EnvType.SERVER);
		assertEquals(List.of(FLUID_INTERACTION + " 1"), movedRanges(chained, widened),
				"LocalRanges moves exactly the one range the tag-tracker rewrite leaves starting after its reads");

		System.setProperty(net.forbric.kernel.mixin.NativeTagTrackerSourceInjector.PROPERTY, "off");
		try {
			byte[] off = apply(afterTagTracker(), binary,
					InjectorExecution.transform(tagTracker(genuine), binary, beforeTracker, EnvType.SERVER));
			assertSame(off, InjectorExecution.transform(new LocalRanges(), binary, off, EnvType.SERVER),
					"with the tag-tracker rewrite off, LocalRanges has nothing to widen in EntityFluidInteraction");
		} finally {
			System.clearProperty(net.forbric.kernel.mixin.NativeTagTrackerSourceInjector.PROPERTY);
		}
	}

	/** KernelBoot's COREMOD transformers from ForeignFluidTypeInjector up to the tag-tracker source, in its order. */
	private static List<ClassTransformer> beforeTagTracker() {
		return List.of(new ForeignFluidTypeInjector(), new FabricFluidBehaviorInjector(), new VanillaAirRefillInjector(),
				new UntrackedFluidEyeQueryInjector(), new PredicateGetterResultRecorder());
	}

	/** KernelBoot's COREMOD transformers after the tag-tracker source in the same segment, in its order. */
	private static List<ClassTransformer> afterTagTracker() {
		return List.of(new net.forbric.kernel.mixin.NativeTagSourceFacadeInjector(), new OwnFluidTypeClausesInjector(),
				new EmptyFluidTypeTrackerInjector(), new net.forbric.kernel.mixin.CrossHostPredicateIslandInjector(),
				new AxeStripCallbacksInjector());
	}

	/**
	 * The tag-tracker source as the launch builds it: FABRIC references from the sidecar, and every other class it reads
	 * as the chain before it hands that class on — the base first, then the staged game's runtime jars and libraries
	 * (the tracker's maps and vectors), then the JDK.
	 */
	private static ClassTransformer tagTracker(Path genuine) throws Exception {
		List<Path> jars = new java.util.ArrayList<>(List.of(genuine));
		for (URL url : net.forbric.kernel.runtime.StagedGameClassLoader.urls()) jars.add(Path.of(url.toURI()));
		return new net.forbric.kernel.mixin.NativeTagTrackerSourceInjector(
				owner -> NativeGameReferences.reference(Ecosystem.FABRIC, owner), owner -> {
					byte[] bytes = gameClass(jars, owner + ".class");
					if (bytes == null) return null;
					ClassNode node = new ClassNode();
					new ClassReader(apply(beforeTagTracker(), owner.replace('/', '.'), bytes)).accept(node, ClassReader.EXPAND_FRAMES);
					return node;
				});
	}

	private static byte[] gameClass(List<Path> jars, String name) {
		try {
			for (Path jar : jars) {
				if (java.nio.file.Files.isDirectory(jar)) {
					Path file = jar.resolve(name);
					if (java.nio.file.Files.isRegularFile(file)) return java.nio.file.Files.readAllBytes(file);
				} else if (java.nio.file.Files.isRegularFile(jar)) {
					byte[] bytes = entry(jar, name);
					if (bytes != null) return bytes;
				}
			}
			try (InputStream in = GenuineNativeReferencesStagedTest.class.getResourceAsStream("/" + name)) {
				return in == null ? null : in.readAllBytes();
			}
		} catch (IOException unreadable) {
			throw new AssertionError(unreadable);
		}
	}

	private static byte[] apply(List<ClassTransformer> transformers, String binary, byte[] bytes) {
		for (ClassTransformer transformer : transformers) bytes = InjectorExecution.transform(transformer, binary, bytes, EnvType.SERVER);
		return bytes;
	}

	/** Per class, how many local-variable entries start after another number of instructions in {@code after} than in {@code before}. */
	private static List<String> movedRanges(byte[] before, byte[] after) {
		ClassNode was = node(before), now = node(after);
		int moved = 0;
		for (int i = 0; i < was.methods.size(); i++) {
			MethodNode a = was.methods.get(i), b = now.methods.get(i);
			assertEquals(a.name + a.desc, b.name + b.desc, "LocalRanges keeps the methods in order");
			if (a.localVariables == null) continue;
			assertEquals(a.localVariables.size(), b.localVariables.size(), a.name + a.desc + ": LocalRanges adds no entry");
			for (int v = 0; v < a.localVariables.size(); v++) {
				if (codeBefore(a.localVariables.get(v).start) != codeBefore(b.localVariables.get(v).start)) moved++;
			}
		}
		return moved == 0 ? List.of() : List.of(was.name + " " + moved);
	}

	/** How many real instructions precede {@code label}: a position that a label LocalRanges inserts does not shift. */
	private static int codeBefore(AbstractInsnNode label) {
		int n = 0;
		for (AbstractInsnNode at = label.getPrevious(); at != null; at = at.getPrevious()) if (at.getOpcode() >= 0) n++;
		return n;
	}

	private byte[] compatFluidBytes;

	/** FluidRenderer after the merged-base compat edit, which the chain runs before LocalRanges. */
	private byte[] compatFluid(Path genuine) throws IOException {
		if (compatFluidBytes == null) {
			byte[] fluid = entry(genuine, FLUID_RENDERER + ".class");
			compatFluidBytes = new ForbricMergedBaseCompatTransformer()
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
