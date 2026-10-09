/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The released mods that hook {@code ImmutableMap.builder()} in {@code GuiRenderer.<init>} — Create Fly's
 * {@code @WrapOperation} and SuperMartijn642's core lib's {@code @Inject} before it with a {@code LocalRef} to the renderer
 * list — against the real merged GuiRenderer. As NeoForge left it, the constructor makes no builder and both bind nothing.
 * Once the kernel builds the map in vanilla's shape, each binds as written: its selector names the one constructor, the
 * constructor makes the call exactly once, and the core lib's list capture names one local there. No callback adapter
 * touches either mixin.
 */
class GuiRendererBuilderAnchorTest {
	private static final String GUI = "net/minecraft/client/gui/render/GuiRenderer";
	private static final String BUILDER = "Lcom/google/common/collect/ImmutableMap;builder()Lcom/google/common/collect/ImmutableMap$Builder;";

	private static List<ClassNode> releasedMixins() throws Exception {
		return List.of(CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/GuiRendererMixin"),
				CarpetMixinAdapterTest.from(Fixture.THIRD_PARTY, Path.of("build/compat-inputs/sweep90/mods/supermartijn642corelib-1.1.24b-fabric-mc26.2.jar"),
						"com/supermartijn642/core/mixin/GuiRendererMixin"));
	}

	private static ClassNode kernelShaped() {
		ClassNode merged = CarpetMixinAdapterTest.target(GUI);
		return MixinFit.parse(new ForbricMergedBaseCompatTransformer().transform(GUI.replace('/', '.'), CarpetMixinAdapterTest.bytes(merged), null));
	}

	@Test void everyReleasedBuilderHookBindsTheConstructorTheKernelShaped() throws Exception {
		ClassNode gui = kernelShaped();
		for (ClassNode mixin : releasedMixins()) {
			List<MethodNode> hooks = builderHooks(mixin);
			assertFalse(hooks.isEmpty(), mixin.name);
			for (MethodNode hook : hooks) {
				List<MethodNode> bound = MixinTargetSelectors.bound(hook, gui);
				assertNotNull(bound, mixin.name);
				assertEquals(1, bound.size(), mixin.name);
				assertEquals("<init>", bound.getFirst().name);
				assertEquals(1, calls(bound.getFirst()).size(), mixin.name + ": the constructor makes the builder once, where the point names it");
				// A @Local capture there names one local: the core lib's LocalRef<List> is the constructor's renderer list.
				for (int p = 0; p < Type.getArgumentTypes(hook.desc).length; p++) {
					AnnotationNode local = MixinStubRebind.sugar(hook, p, MixinRetarget.LOCAL_SUGAR);
					if (local == null) continue;
					int point = bound.getFirst().instructions.indexOf(calls(bound.getFirst()).getFirst());
					assertEquals(3, MixinLocalOriginProof.slot(local, Type.getType("Ljava/util/List;"), bound.getFirst(), point), mixin.name);
				}
			}
			byte[] before = CarpetMixinAdapterTest.bytes(mixin);
			assertEquals(0, MixinCarrierCallbackAdapters.adapt(mixin, name -> name.equals(GUI) ? kernelShaped() : CarpetMixinAdapterTest.target(name),
					(family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)), mixin.name + ": no adapter is written for any mod");
			assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), mixin.name);
		}
	}

	@Test void onTheConstructorNeoForgeLeftNoHookHasAPoint() throws Exception {
		ClassNode gui = CarpetMixinAdapterTest.target(GUI);
		for (ClassNode mixin : releasedMixins()) for (MethodNode hook : builderHooks(mixin)) {
			List<MethodNode> bound = MixinTargetSelectors.bound(hook, gui);
			assertNotNull(bound, mixin.name);
			for (MethodNode constructor : bound) assertEquals(0, calls(constructor).size(), mixin.name);
		}
	}

	private static List<MethodNode> builderHooks(ClassNode mixin) {
		List<MethodNode> hooks = new ArrayList<>();
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector != null && MixinFit.atNodes(injector).stream().anyMatch(at -> BUILDER.equals(MixinFit.value(at, "target")))) hooks.add(method);
		}
		return hooks;
	}

	private static List<MethodInsnNode> calls(MethodNode method) {
		List<MethodInsnNode> found = new ArrayList<>();
		for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && ("L" + call.owner + ";" + call.name + call.desc).equals(BUILDER)) found.add(call);
		return found;
	}
}
