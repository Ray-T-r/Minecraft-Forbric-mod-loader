/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The twin rebind finds the method a handler's selectors bind as Mixin binds them, and the descriptor they pin, however
 * they are written: an owner prefix, a dotted owner, whitespace, several spellings of one method. The row is the platform's
 * own ({@code ModelBlockRenderer.shouldRenderFace}); the mods are invented, an {@code @Inject} at the face test's head
 * and a return modifier capturing nothing.
 */
class MixinTwinRebindSpellingTest {
	private static final String RENDERER = "net/minecraft/client/renderer/block/ModelBlockRenderer";
	private static final String GETTER = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;";
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	private static final String DIRECTION = "Lnet/minecraft/core/Direction;";
	private static final String POS = "Lnet/minecraft/core/BlockPos;";
	private static final String VANILLA = "(" + GETTER + STATE + DIRECTION + POS + ")Z";
	private static final String OVERLOAD = "(" + GETTER + POS + STATE + DIRECTION + POS + ")Z";
	private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	@AfterEach void reset() { MixinStubRebind.forget(); }

	/** A head callback taking vanilla's four arguments, its selector the name however written: the overload, wrapped. */
	@Test
	void aNameWrittenAnyWayMixinReadsItMovesTheHeadCallback() {
		for (List<String> selectors : List.of(List.of("net.minecraft.client.renderer.block.ModelBlockRenderer.shouldRenderFace"),
				List.of("shouldRenderFace", "L" + RENDERER + ";shouldRenderFace"), List.of(" shouldRenderFace "))) {
			ClassNode mixin = headMixin(selectors);
			MethodNode handler = mixin.methods.getFirst();
			assertNotNull(MixinFit.injectorOf(handler));
			assertEquals(OVERLOAD, MixinTwinRebind.destination(mixin, handler, renderer()).desc, selectors.toString());
			assertEquals(1, MixinTwinRebind.adapt(mixin, name -> renderer()), selectors.toString());
			MethodNode outer = mixin.methods.stream().filter(m -> m.name.equals("peekFace")).findFirst().orElseThrow();
			assertEquals("(" + GETTER + POS + STATE + DIRECTION + POS + CIR + ")V", outer.desc);
			assertEquals(List.of("shouldRenderFace" + OVERLOAD), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(outer), "method")));
		}
	}

	/** Vanilla's descriptor spelled with a dotted owner and whitespace binds vanilla's method: only the selector moves. */
	@Test
	void vanillasDescriptorWrittenAnotherWayMovesTheSelector() {
		for (String selector : List.of("net.minecraft.client.renderer.block.ModelBlockRenderer.shouldRenderFace " + VANILLA,
				"L" + RENDERER + "; shouldRenderFace " + VANILLA)) {
			ClassNode mixin = returnMixin(List.of(selector));
			assertEquals(1, MixinTwinRebind.adapt(mixin, name -> renderer()), selector);
			assertEquals(List.of("shouldRenderFace" + OVERLOAD), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(mixin.methods.getFirst()), "method")));
		}
		// …and the cheap pre-question the verdict asks agrees.
		ClassNode mixin = returnMixin(List.of("net.minecraft.client.renderer.block.ModelBlockRenderer.shouldRenderFace" + VANILLA));
		assertTrue(MixinTwinRebind.mayMove(renderer(), mixin.methods.getFirst()));
	}

	@Test
	void selectorsBindingTheOverloadSeveralMethodsOrNothingStay() {
		List<List<String>> stay = List.of(
				List.of("shouldRenderFace" + OVERLOAD),
				List.of("shouldRenderFace*"),
				List.of("shouldRenderFace", "shouldRenderFace" + VANILLA),
				List.of("Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;shouldRenderFace"),
				List.of("tesselateFlat"));
		for (List<String> selectors : stay) {
			ClassNode mixin = returnMixin(selectors);
			assertEquals(0, MixinTwinRebind.adapt(mixin, name -> renderer()), selectors.toString());
			assertEquals(selectors, MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(mixin.methods.getFirst()), "method")));
		}
		assertFalse(MixinTwinRebind.mayMove(renderer(), returnMixin(List.of("tesselateFlat")).methods.getFirst()));
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** The merged class's shape: NeoForge's overload first and called, vanilla's after it and called by nothing. */
	private static ClassNode renderer() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = RENDERER;
		node.superName = "java/lang/Object";
		node.methods = new ArrayList<>(List.of(face(OVERLOAD), face(VANILLA)));
		MethodNode tesselate = new MethodNode(Opcodes.ACC_PUBLIC, "tesselateFlat", "()V", null, null);
		tesselate.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (int i = 0; i < 5; i++) tesselate.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		tesselate.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDERER, "shouldRenderFace", OVERLOAD, false));
		tesselate.instructions.add(new InsnNode(Opcodes.POP));
		tesselate.instructions.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(tesselate);
		return node;
	}

	private static MethodNode face(String desc) {
		MethodNode face = new MethodNode(Opcodes.ACC_PRIVATE, "shouldRenderFace", desc, null, null);
		face.instructions.add(new InsnNode(Opcodes.ICONST_1));
		face.instructions.add(new InsnNode(Opcodes.IRETURN));
		return face;
	}

	private static ClassNode headMixin(List<String> selectors) {
		return mixin("spelled/culling/FacePeek", "peekFace", "(" + GETTER + STATE + DIRECTION + POS + CIR + ")V",
				"Lorg/spongepowered/asm/mixin/injection/Inject;", selectors, "HEAD");
	}

	private static ClassNode returnMixin(List<String> selectors) {
		return mixin("spelled/culling/FaceVeto", "veto", "(Z)Z", "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", selectors, "RETURN");
	}

	private static ClassNode mixin(String name, String handlerName, String desc, String kind, List<String> selectors, String point) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(RENDERER)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", point));
		AnnotationNode injector = new AnnotationNode(kind);
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(selectors), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, handlerName, desc, null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(injector));
		Type returns = Type.getReturnType(desc);
		if (returns.getSort() == Type.VOID) {
			handler.instructions.add(new InsnNode(Opcodes.RETURN));
		} else {
			handler.instructions.add(new VarInsnNode(returns.getOpcode(Opcodes.ILOAD), 1));
			handler.instructions.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
		}
		mixin.methods = new ArrayList<>(List.of(handler));
		MixinStubRebind.noteEcosystem(name, Ecosystem.FABRIC);
		return mixin;
	}
}
