/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.transform.ChainReferences;

/**
 * {@link LambdaShapeEvidence} on NeoForge's genuine base: the native reference, read from the vanilla-reference sidecar
 * through the sources KernelBoot configures, is what still shims a handler written for vanilla's lambda.
 *
 * <p>Until Stage 4 this class also replayed every guest mixin of the local packs with a lambda selector or a
 * {@code LevelRenderer} target against the merged base, with the duplicate-lambda pruner on and off, and held the
 * decisions to a table recorded at b37e6ae0 (lambda-decisions-b37e6ae0.tsv). The merged base and the pruner are gone; the
 * genuine base carries no lambda name twice (NeoForgeBaseStructureCensusStagedTest.noClassHasTwoLambdasOfOneName).
 */
// The staged jars and the test classpath carry no fastutil, which the frame recomputer's hierarchy walk reaches from some
// targets; it abstains there, after everything a lambda decision reads.
@ExpectCaught("cannot prove a frame repair")
@ResourceLock("native-game-references")
@ResourceLock("system-properties")
class LambdaShapeEvidenceStagedTest {
	private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
	private static final String SKY = "lambda$addSkyPass$0";
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String VANILLA_SKY_ARGS = "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;";

	@AfterEach void reset() {
		System.clearProperty(LambdaShapeEvidence.PROPERTY);
		bind(null);
		NativeGameReferences.configure(null);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The genuine base: the reference is what still shims
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * Apoli's phasing-blindness sky-skip on NeoForge's own base. There is one {@code lambda$addSkyPass$0}, NeoForge's;
	 * vanilla's shape comes from the FABRIC reference, which the launch reads
	 * from the vanilla-reference sidecar the installer's own writer builds for this base, through the sources
	 * KernelBoot configures. With it the handler is wrapped onto the live lambda, MixinFit sees where it lands, and a
	 * selector that cannot bind is told as the carrier reshape it is; without the sidecar the base answers for NEOFORGE
	 * alone, and the mixin would lose its injector to "Invalid descriptor".
	 */
	@Test void onTheGenuineBaseAHandlerWrittenForVanillasSkyLambdaIsStillShimmed() throws Exception {
		List<Path> genuine = ChainReferences.genuineJars();
		Path sidecar = TestFixtures.vanillaReference(genuine.get(0));
		byte[] levelRenderer = ChainReferences.read(genuine, LEVEL_RENDERER);
		ClassNode target = MixinFit.parse(levelRenderer);
		assertEquals(1, target.methods.stream().filter(m -> m.name.equals(SKY)).count(), "premise: one lambda of the name, NeoForge's");
		MethodNode live = target.methods.stream().filter(m -> m.name.equals(SKY)).findFirst().orElseThrow();
		assertNotEquals("(" + VANILLA_SKY_ARGS + ")V", live.desc, "premise: and not vanilla's shape");
		Function<String, ClassNode> targets = name -> name.equals(LEVEL_RENDERER) ? MixinFit.parse(levelRenderer) : null;

		NativeReferenceSources sources = NativeReferenceSources.open(genuine.get(0), sidecar);
		assertTrue(sources.describe().contains("FABRIC from the vanilla-reference sidecar") && !sources.describe().replace("NEOFORGE", "").contains("FORGE"),
				"premise: KernelBoot's sources for this launch: " + sources.describe());
		NativeGameReferences.configure(sources);
		try (ForbricClassLoader loader = new ForbricClassLoader(genuine.stream().map(LambdaShapeEvidenceStagedTest::url).toArray(URL[]::new),
				getClass().getClassLoader())) {
			bind(loader);
			assertEquals(List.of(new LambdaShapeEvidence.Shape("(" + VANILLA_SKY_ARGS + ")V")),
					LambdaShapeEvidence.vanished(target, SKY), "vanilla's shape, known from the reference alone");

			for (ClassNode mixin : skySkips()) {
				MethodNode handler = mixin.methods.stream().filter(m -> injected(m) != null).findFirst().orElseThrow();
				String name = handler.name;
				assertNotNull(MixinHandlerShim.destination(handler, target), mixin.name + ": MixinFit sees the injector land");
				assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(bytes(mixin), path -> path.equals(LEVEL_RENDERER + ".class")
						? levelRenderer : null).verdict(), mixin.name);
				MixinOverloadPin.clearReasons();
				assertTrue(MixinOverloadPin.explain(mixin, handler, SKY, List.of(LEVEL_RENDERER), targets), mixin.name + " is diagnosed");
				assertTrue(String.valueOf(MixinOverloadPin.reasonFor(mixin.name)).contains("whose captures a carrier patch changed from vanilla's"),
						"as the carrier reshape it is: " + MixinOverloadPin.reasonFor(mixin.name));
				assertEquals(1, MixinHandlerShim.adapt(mixin, targets), mixin.name + " is wrapped");
				MethodNode outer = mixin.methods.stream().filter(m -> m.name.equals(name) && injected(m) != null).findFirst().orElseThrow();
				assertEquals(live.desc.replace(")V", CI + ")V"), outer.desc, "the wrapper takes the live lambda's arguments");
			}

			System.setProperty(LambdaShapeEvidence.PROPERTY, "off");
			for (ClassNode mixin : skySkips()) {
				assertEquals(0, MixinHandlerShim.adapt(mixin, targets), "without the reference there is no evidence on this base");
			}
		}

		// The control: the same launch without the sidecar. The base answers for NEOFORGE, and nothing for FABRIC.
		System.clearProperty(LambdaShapeEvidence.PROPERTY);
		NativeGameReferences.configure(NativeReferenceSources.open(genuine.get(0), null));
		try (ForbricClassLoader loader = new ForbricClassLoader(genuine.stream().map(LambdaShapeEvidenceStagedTest::url).toArray(URL[]::new),
				getClass().getClassLoader())) {
			bind(loader);
			assertEquals(List.of(), LambdaShapeEvidence.vanished(target, SKY), "no sidecar, no vanilla shape");
			for (ClassNode mixin : skySkips()) assertEquals(0, MixinHandlerShim.adapt(mixin, targets), mixin.name);
		}
	}

	/** Apoli's real WorldRendererMixin where the local packs have Origins-Legacy, and always a fixture of its shape. */
	private static List<ClassNode> skySkips() throws IOException {
		List<ClassNode> mixins = new ArrayList<>(List.of(skySkipFixture()));
		Path origins = Path.of("run/client-popular/mods/Origins-Legacy-1.12.18+26.2.jar");
		if (Files.isRegularFile(origins)) {
			for (Guest guest : guests(Files.readAllBytes(origins), origins.getFileName().toString(), null)) {
				if (guest.mixin().name.equals("io/github/apace100/apoli/mixin/WorldRendererMixin")) mixins.add(onlySkyHandlers(guest.mixin()));
			}
			assertEquals(2, mixins.size(), "Origins-Legacy carries Apoli's WorldRendererMixin");
		}
		return mixins;
	}

	/** The mixin with only its handlers of {@link #SKY}, so the count of wraps is the sky-skip's alone. */
	private static ClassNode onlySkyHandlers(ClassNode mixin) {
		mixin.methods.removeIf(m -> injected(m) != null && !List.of(SKY).equals(MixinFit.stringList(MixinFit.value(injected(m), "method"))));
		return mixin;
	}

	/** {@code @Inject(method = "lambda$addSkyPass$0") static void skip(GpuBufferSlice, SkyRenderState, CallbackInfo)}. */
	private static ClassNode skySkipFixture() {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = "fixture/SkySkipMixin";
		mixin.superName = "java/lang/Object";
		AnnotationNode at = new AnnotationNode(MIXIN_DESC);
		at.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(LEVEL_RENDERER)))));
		mixin.visibleAnnotations = new ArrayList<>(List.of(at));
		MethodNode handler = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "skip",
				"(" + VANILLA_SKY_ARGS + CI + ")V", null, null);
		AnnotationNode inject = new AnnotationNode(INJECT);
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(SKY)), "at",
				new ArrayList<>(List.of(atHead()))));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		mixin.methods = new ArrayList<>(List.of(handler));
		return mixin;
	}

	private static AnnotationNode atHead() {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "HEAD"));
		return at;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The decisions
	// ---------------------------------------------------------------------------------------------------------------

	/** One guest mixin: its class, its class file as the jar holds it, the jar it came from, the platform it was built for. */
	record Guest(ClassNode mixin, byte[] raw, String jar, Ecosystem ecosystem) {
		List<String> targets() {
			return MixinOverloadPin.targetsOf(mixin);
		}

		@Override public String toString() {
			return mixin.name + " (" + jar + ")";
		}
	}

	private static AnnotationNode injected(MethodNode method) {
		AnnotationNode injector = MixinFit.injectorOf(method);
		return injector == null || MixinFit.value(injector, "method") == null ? null : injector;
	}

	private static byte[] bytes(ClassNode mixin) {
		ClassWriter writer = new ClassWriter(0);
		mixin.accept(writer);
		return writer.toByteArray();
	}

	private static void bind(ForbricClassLoader loader) {
		NativeGameReferences.bind(loader);
	}

	private static URL url(Path jar) {
		try {
			return jar.toUri().toURL();
		} catch (java.net.MalformedURLException impossible) {
			throw new AssertionError(impossible);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The guests
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * The guest mixins of one jar and of every jar nested in it whose injectors select a lambda by name, or that target
	 * {@code LevelRenderer}.
	 */
	private static List<Guest> guests(byte[] jar, String name, Ecosystem inherited) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry e; (e = zip.getNextEntry()) != null;) if (!e.isDirectory()) entries.put(e.getName(), zip.readAllBytes());
		}
		// A mods.toml-only jar is refused (MinecraftForge is no ecosystem): it inherits, as a jar with no manifest does.
		Ecosystem ecosystem = entries.containsKey("fabric.mod.json") ? Ecosystem.FABRIC
				: entries.containsKey("META-INF/neoforge.mods.toml") ? Ecosystem.NEOFORGE : inherited;
		List<Guest> out = new ArrayList<>();
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			if (entry.getKey().endsWith(".jar")) {
				out.addAll(guests(entry.getValue(), name + "!" + entry.getKey(), ecosystem));
				continue;
			}
			if (!entry.getKey().endsWith(".class")) continue;
			ClassNode node = new ClassNode();
			try {
				new ClassReader(entry.getValue()).accept(node, 0);
			} catch (RuntimeException unreadable) {
				continue;    // not a class file ASM reads; no mixin of interest
			}
			if (!isMixin(node)) continue;
			boolean lambda = node.methods.stream().anyMatch(m -> injected(m) != null
					&& MixinFit.stringList(MixinFit.value(injected(m), "method")).stream().anyMatch(s -> s.trim().startsWith("lambda$")));
			if (lambda || MixinOverloadPin.targetsOf(node).contains(LEVEL_RENDERER)) out.add(new Guest(node, entry.getValue(), name, ecosystem));
		}
		return out;
	}

	private static boolean isMixin(ClassNode node) {
		for (List<AnnotationNode> list : Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
			if (list != null && list.stream().anyMatch(a -> a.desc.equals(MIXIN_DESC))) return true;
		}
		return false;
	}
}
