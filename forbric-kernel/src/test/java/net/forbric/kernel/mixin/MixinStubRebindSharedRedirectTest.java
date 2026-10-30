package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * fabric-renderer-api's real chunk takeover on the staged merged {@code SectionCompiler}, moved by the generic stub rebind
 * that replaced the adapter keyed on its mixin, share and handler names.
 *
 * <p>The pair — a setup {@code @Inject} capturing the stub's arguments and {@code startedLayers}, and a tessellation
 * {@code @Redirect}, sharing a renderer and an emitter — binds NeoForge's forwarding stub. Vanilla's compile (the merged
 * base's pinned FABRIC native reference) tessellates once and so does NeoForge's overload, so the redirect replaces only
 * the calls it replaced natively and both move. The same pair under names sharing nothing with fabric-api's moves alike;
 * a body that tessellated a second time (a carrier pipeline of its own) keeps both where Mixin bound them.
 */
@ResourceLock("native-game-references")
class MixinStubRebindSharedRedirectTest {
	private static final String MIXIN = "net/fabricmc/fabric/mixin/client/renderer/block/render/SectionCompilerMixin";
	private static final String TARGET = "net/minecraft/client/renderer/chunk/SectionCompiler";
	private static final String ARGS = "Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;"
			+ "Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;";
	private static final String LIVE = "compile(" + ARGS + "Ljava/util/List;)L" + TARGET + "$Results;";

	private ForbricClassLoader loader;

	@BeforeEach void bindTheMergedBasesNativeReferences() throws Exception {
		Path merged = TestFixtures.stagedBase();
		TestFixtures.requireFiles(Fixture.STAGED, "the staged merged base and its native-reference index", merged);
		MixinStubRebind.forget();
		loader = new ForbricClassLoader(new java.net.URL[] {merged.toUri().toURL()}, getClass().getClassLoader());
		NativeGameReferences.bind(loader);
		assertNotNull(NativeGameReferences.reference(Ecosystem.FABRIC, TARGET), "premise: the merged base pins vanilla's SectionCompiler");
	}

	@AfterEach void reset() throws Exception {
		NativeGameReferences.bind(null);
		if (loader != null) loader.close();
		MixinStubRebind.forget();
	}

	private static ClassNode mixin() throws Exception {
		return StagedFabricMixinFixture.mixin("fabric-renderer-api-v1", MIXIN);
	}

	private static ClassNode target() throws Exception {
		ClassNode node = new ClassNode();
		try (var zip = new java.util.zip.ZipFile(TestFixtures.stagedBase().toFile())) {
			new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry(TARGET + ".class"))).accept(node, 0);
		}
		return node;
	}

	@Test void bothHalvesMoveWithTheirCapturedLayerMapAndSharedRendererIntact() throws Exception {
		ClassNode mixin = mixin(), target = target();
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		byte[] before = StagedFabricMixinFixture.bytes(target);
		assertEquals(2, MixinStubRebind.adapt(mixin, n -> target));
		assertMoved(mixin, "beforeLoopCompile", "tesselateBlockProxy", "startedLayers", "altBlockRenderer", "altQuadOutput");
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(target), "NeoForge's extra geometry and native compile body stay intact");
	}

	/** The rule reads no name of the mod's: the same pair relocated and renamed throughout moves exactly alike. */
	@Test void thePairUnderUnrelatedNamesMovesAlike() throws Exception {
		ClassNode mixin = renamed(mixin());
		ClassNode target = target();
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(2, MixinStubRebind.adapt(mixin, n -> target));
		assertMoved(mixin, "qzxPrepare", "qzxPaint", "startedLayers", "qzxPainter", "qzxSink");
	}

	/** A body that makes the redirected call more often than the mod's own platform did is a takeover of calls it never met. */
	@Test void aBodyThatTessellatesASecondTimeKeepsThePairOnTheStub() throws Exception {
		ClassNode mixin = mixin(), target = target();
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode live = target.methods.stream().filter(m -> LIVE.equals(m.name + m.desc)).findFirst().orElseThrow();
		MethodInsnNode call = null;
		for (AbstractInsnNode insn : live.instructions) if (insn instanceof MethodInsnNode m && m.name.equals("tesselateBlock")) call = m;
		assertNotNull(call);
		live.instructions.insert(call, new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, MixinStubRebind.adapt(mixin, n -> target));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin), "neither half moved: they share state");
	}

	/** Without the mod's platform reference there is nothing to count the calls against, and nothing moves. */
	@Test void withoutANativeReferenceThePairStays() throws Exception {
		NativeGameReferences.bind(null);
		ClassNode mixin = mixin(), target = target();
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(0, MixinStubRebind.adapt(mixin, n -> target));
	}

	private static void assertMoved(ClassNode mixin, String setupName, String drawName, String local, String renderer, String output)
			throws Exception {
		MethodNode setup = mixin.methods.stream().filter(m -> m.name.equals(setupName) && MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
		MethodNode draw = StagedFabricMixinFixture.method(mixin, drawName);
		for (MethodNode handler : List.of(setup, draw))
			assertEquals(List.of(LIVE), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")), handler.name);
		int params = org.objectweb.asm.Type.getArgumentTypes(setup.desc).length;
		assertEquals(9, params, "the outer takes the live overload's arguments, its list included: " + setup.desc);
		assertEquals(List.of(local), MixinFit.stringList(MixinFit.value(setup.invisibleParameterAnnotations[6].getFirst(), "name")));
		assertEquals(renderer, MixinFit.value(setup.invisibleParameterAnnotations[7].getFirst(), "value"));
		assertEquals(output, MixinFit.value(setup.invisibleParameterAnnotations[8].getFirst(), "value"));
		assertEquals(renderer, MixinFit.value(draw.invisibleParameterAnnotations[10].getFirst(), "value"));
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, setup);
	}

	/** {@code mixin} relocated to another package and class, its handlers and share keys renamed, its selectors spelled in full. */
	private static ClassNode renamed(ClassNode mixin) {
		ClassNode out = new ClassNode();
		mixin.accept(new org.objectweb.asm.commons.ClassRemapper(out, new org.objectweb.asm.commons.Remapper() {
			@Override public String map(String internalName) {
				return internalName.equals(MIXIN) ? "org/qzx/render/hooks/QzxChunkHooks" : internalName;
			}

			@Override public String mapMethodName(String owner, String name, String descriptor) {
				return switch (name) {
					case "beforeLoopCompile" -> "qzxPrepare";
					case "tesselateBlockProxy" -> "qzxPaint";
					default -> name;
				};
			}
		}));
		for (MethodNode m : out.methods) {
			AnnotationNode injector = MixinFit.injectorOf(m);
			if (injector != null) for (int i = 0; i + 1 < injector.values.size(); i += 2)
				if ("method".equals(injector.values.get(i))) injector.values.set(i + 1, List.of("compile(" + ARGS + ")L" + TARGET + "$Results;"));
			if (m.invisibleParameterAnnotations != null) for (List<AnnotationNode> list : m.invisibleParameterAnnotations) if (list != null)
				for (AnnotationNode a : list) if (a.desc.endsWith("/Share;") && a.values != null)
					a.values.set(1, "altBlockRenderer".equals(a.values.get(1)) ? "qzxPainter" : "qzxSink");
		}
		return out;
	}
}
