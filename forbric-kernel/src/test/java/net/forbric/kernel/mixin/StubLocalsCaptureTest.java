/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * The locals capture on a carrier stub, on the staged merged atlas listing, written other ways than Continuity's: by the
 * bare name {@code list} (which binds vanilla's overload, the stub, there as natively), and beside an unrelated injector on
 * the same stub — which is not this pass's to move and must come out byte-for-byte (it used to be pointed at the body with
 * a handler that no longer fit it). A capture of a local that is not first after the body's arguments, a capture on a
 * method that is no stub, and two captures on one stub stay as compiled.
 */
@ResourceLock("system-properties")
class StubLocalsCaptureTest {
	private static final String LIST = "net/minecraft/client/renderer/texture/atlas/SpriteSourceList";
	private static final String RESOURCES = "Lnet/minecraft/server/packs/resources/ResourceManager;";
	private static final String RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String BODY = "list(" + RESOURCES + "Ljava/util/Set;)Ljava/util/List;";

	private static ClassNode continuity() throws Exception {
		return MixinCallbackSelectorSpellingTest.unrelatedNames(CarpetMixinAdapterTest.from(Fixture.THIRD_PARTY,
				Path.of("build/compat-inputs/player-loading/mods/continuity-3.0.1+26.2.jar"), "me/pepperbell/continuity/client/mixin/SpriteSourceListMixin"));
	}

	private static ClassNode target() throws Exception {
		return StagedFabricMixinFixture.game(LIST, false);
	}

	private static MethodNode capture(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null && MixinFit.value(MixinFit.injectorOf(m), "locals") != null)
				.findFirst().orElseThrow();
	}

	private static int adapt(ClassNode mixin, ClassNode target) {
		return MixinSpriteLoaderCallbackAdapter.adapt(mixin, name -> name.equals(LIST) ? target : null);
	}

	@Test void aBareNameBindsTheStubAndTheCaptureFollowsTheBody() throws Exception {
		ClassNode mixin = continuity(), target = target();
		MethodNode handler = capture(mixin);
		MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", "list");
		String name = handler.name;
		assertEquals(1, adapt(mixin, target));
		MethodNode wrapper = MixinPlayerWorldCallbackAdapter.named(mixin, name);
		assertEquals(List.of(BODY), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(wrapper), "method")));
		assertEquals("(" + RESOURCES + "Ljava/util/Set;" + RETURNABLE + "Ljava/util/Map;)V", wrapper.desc);
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, adapt(mixin, target), "idempotence");
	}

	@Test void anUnrelatedInjectorOnTheSameStubIsNotTouched() throws Exception {
		ClassNode mixin = continuity(), target = target();
		MethodNode head = new MethodNode(Opcodes.ACC_PRIVATE, "other$atHead", "(" + RESOURCES + RETURNABLE + ")V", null, null);
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "HEAD"));
		inject.values = new ArrayList<>(List.of("method", List.of("list(" + RESOURCES + ")Ljava/util/List;"), "at", List.of(at)));
		head.visibleAnnotations = new ArrayList<>(List.of(inject));
		head.instructions.add(new InsnNode(Opcodes.RETURN));
		head.maxLocals = 3;
		mixin.methods.add(head);
		byte[] before = bytes(head);
		assertEquals(1, adapt(mixin, target), "the capture alone");
		assertArrayEquals(before, bytes(head), "the HEAD injector is MixinStubRebind's, as compiled");
	}

	@Test void capturesTheBodyCannotServeStayAsCompiled() throws Exception {
		ClassNode target = target();
		ClassNode output = continuity();
		MethodNode handler = capture(output);
		handler.desc = handler.desc.replace("Ljava/util/Map;)V", "Lnet/minecraft/client/renderer/texture/atlas/SpriteSource$Output;)V");
		assertUntouched(output, target, "the first local after the body's arguments is the map, not the output");

		ClassNode body = continuity();
		MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(capture(body)), "method", List.of(BODY));
		assertUntouched(body, target, "bound to the body itself: no stub, nothing to follow");

		ClassNode twice = continuity();
		MethodNode original = capture(twice);
		MethodNode copy = new MethodNode(original.access, original.name + "Again", original.desc, original.signature, null);
		original.accept(copy);
		twice.methods.add(copy);
		assertUntouched(twice, target, "two captures on one stub");
	}

	/** The row's columns decide per ecosystem: MinecraftForge ran list(ResourceManager) as the body, NeoForge keeps the stub. */
	@Test void aModWhosePlatformKeepsTheSameStubGetsWhatItGetsNatively() throws Exception {
		ClassNode target = target();
		// Names of their own, so the families noted for them reach no other test's mixins.
		ClassNode neo = named(continuity(), "org/example/stubcapture/NeoForgeCapture"), forge = named(continuity(), "org/example/stubcapture/ForgeCapture");
		MixinStubRebind.noteEcosystem(neo.name, net.forbric.api.Ecosystem.NEOFORGE);
		assertUntouched(neo, target, "a NeoForge mod: NeoForge's own list(ResourceManager) is this stub");
		MixinStubRebind.noteEcosystem(forge.name, net.forbric.api.Ecosystem.FORGE);
		assertEquals(1, adapt(forge, target), "a MinecraftForge mod: its platform ran that signature as the body");
	}

	private static ClassNode named(ClassNode mixin, String name) {
		ClassNode renamed = new ClassNode();
		mixin.accept(new org.objectweb.asm.commons.ClassRemapper(renamed, new org.objectweb.asm.commons.SimpleRemapper(Opcodes.ASM9, mixin.name, name)));
		return renamed;
	}

	private static void assertUntouched(ClassNode mixin, ClassNode target, String why) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(mixin, target), why);
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), why);
	}

	private static byte[] bytes(MethodNode method) {
		ClassNode holder = new ClassNode();
		holder.version = Opcodes.V21;
		holder.name = "Holder";
		holder.superName = "java/lang/Object";
		MethodNode copy = new MethodNode(method.access, method.name, method.desc, method.signature, null);
		method.accept(copy);
		holder.methods.add(copy);
		ClassWriter writer = new ClassWriter(0);
		holder.accept(writer);
		return writer.toByteArray();
	}
}
