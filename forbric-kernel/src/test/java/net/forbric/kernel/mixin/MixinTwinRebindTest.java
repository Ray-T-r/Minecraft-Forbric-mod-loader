/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * MixinTwinRebind on {@code ModelBlockRenderer.shouldRenderFace}, the row LiquidBounce's X-Ray needs: vanilla's
 * {@code (level, state, direction, neighborPos)}, which the merged class keeps after NeoForge's
 * {@code (level, pos, state, direction, neighborPos)} and nothing calls.
 */
class MixinTwinRebindTest {
	private static final Path MERGED = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path LIQUIDBOUNCE = Path.of("../build/lb-vfp/jars/liquidbounce-0.40.1+26.2-20260925.jar");
	private static final String RENDERER = "net/minecraft/client/renderer/block/ModelBlockRenderer";
	private static final String GETTER = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;";
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	private static final String DIRECTION = "Lnet/minecraft/core/Direction;";
	private static final String POS = "Lnet/minecraft/core/BlockPos;";
	private static final String VANILLA = "(" + GETTER + STATE + DIRECTION + POS + ")Z";
	private static final String OVERLOAD = "(" + GETTER + POS + STATE + DIRECTION + POS + ")Z";
	private static final String NEIGHBOR_CALL = "Lnet/minecraft/world/level/block/Block;shouldRenderFace";
	private static final String MODIFY_RETURN = "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
	private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
	private static final String NOT_NULL = "Lorg/jetbrains/annotations/NotNull;";

	@AfterEach void reset() {
		System.clearProperty(MixinTwinRebind.PROPERTY);
		MixinStubRebind.forget();
	}

	@Test void aRowParsesAndAMalformedOneIsRefused() {
		MixinTwinRebind.Row row = MixinTwinRebind.Row.parse(RENDERER + "#shouldRenderFace" + VANILLA + " -> " + OVERLOAD
				+ " | 0,2,3,4 | FABRIC,FORGE");
		assertNotNull(row);
		assertEquals(RENDERER, row.owner());
		assertEquals("shouldRenderFace", row.name());
		assertArrayEquals(new int[] {0, 2, 3, 4}, row.positions());
		assertEquals(java.util.Set.of(Ecosystem.FABRIC, Ecosystem.FORGE), row.ecosystems());
		// A position whose type is not the vanilla parameter's, one too few, an unknown ecosystem.
		assertNull(MixinTwinRebind.Row.parse(RENDERER + "#shouldRenderFace" + VANILLA + " -> " + OVERLOAD + " | 0,1,3,4 | FABRIC"));
		assertNull(MixinTwinRebind.Row.parse(RENDERER + "#shouldRenderFace" + VANILLA + " -> " + OVERLOAD + " | 0,2,3 | FABRIC"));
		assertNull(MixinTwinRebind.Row.parse(RENDERER + "#shouldRenderFace" + VANILLA + " -> " + OVERLOAD + " | 0,2,3,4 | QUILT"));
	}

	/**
	 * LiquidBounce's shape: a return modifier selected by name, taking vanilla's four arguments after the result. Mixin
	 * binds the name to NeoForge's overload, declared first, and rejects it there; moved, an outer handler takes the
	 * overload's arguments and hands the original the four it was written for.
	 */
	@Test void aReturnModifierTakingVanillasArgumentsMovesAndIsWrapped() {
		ClassNode renderer = renderer(1, 1);
		ClassNode mixin = mixin("com/example/xray/RendererMixin", "drawSide", "(Z" + GETTER + STATE + DIRECTION + POS + ")Z",
				injector(MODIFY_RETURN, "shouldRenderFace", at("RETURN")));
		MethodNode handler = mixin.methods.getFirst();
		assertSame(overload(renderer), MixinTwinRebind.destination(mixin, handler, renderer));
		assertEquals(1, MixinTwinRebind.adapt(mixin, name -> renderer));

		MethodNode outer = method(mixin, "drawSide");
		assertEquals("(Z" + GETTER + POS + STATE + DIRECTION + POS + ")Z", outer.desc);
		assertEquals(List.of("shouldRenderFace" + OVERLOAD), selectors(outer));
		MethodNode inner = method(mixin, "drawSide" + MixinHandlerShim.INNER_SUFFIX);
		assertEquals("(Z" + GETTER + STATE + DIRECTION + POS + ")Z", inner.desc);
		assertNull(MixinFit.injectorOf(inner), "the original is no injector any more");
		// this, the result, then the overload's level, state, direction and neighbour — never its own position.
		assertEquals(List.of(0, 1, 2, 4, 5, 6), loads(outer));
		assertEquals(inner.name, call(outer).name);
	}

	/** An @Inject takes all of a method's arguments or none: the outer takes all five, and hands on the four and the callback. */
	@Test void anInjectTakingVanillasArgumentsTakesAllOfTheOverloads() {
		ClassNode renderer = renderer(1, 1);
		ClassNode mixin = mixin("com/example/xray/RendererMixin", "atHead", "(" + GETTER + STATE + DIRECTION + POS + CIR + ")V",
				injector(INJECT, "shouldRenderFace", at("HEAD")));
		assertEquals(1, MixinTwinRebind.adapt(mixin, name -> renderer));
		MethodNode outer = method(mixin, "atHead");
		assertEquals("(" + GETTER + POS + STATE + DIRECTION + POS + CIR + ")V", outer.desc);
		assertEquals(List.of(0, 1, 3, 4, 5, 6), loads(outer));
	}

	/** A selector spelling vanilla's descriptor binds the method nothing calls; capturing nothing, only the selector moves. */
	@Test void aVanillaSelectorCapturingNothingOnlyMoves() {
		ClassNode renderer = renderer(1, 1);
		ClassNode mixin = mixin("com/example/xray/RendererMixin", "always", "(Z)Z",
				injector(MODIFY_RETURN, "shouldRenderFace" + VANILLA, at("RETURN")));
		assertEquals(1, MixinTwinRebind.adapt(mixin, name -> renderer));
		assertEquals(1, mixin.methods.size(), "no outer handler");
		assertEquals(List.of("shouldRenderFace" + OVERLOAD), selectors(method(mixin, "always")));
	}

	/** Bound to the overload by name and taking nothing from it: Mixin already injects where the game runs. */
	@Test void aNameThatAlreadyBindsTheOverloadAndCapturesNothingStays() {
		ClassNode renderer = renderer(1, 1);
		ClassNode mixin = mixin("com/example/xray/RendererMixin", "always", "(Z)Z",
				injector(MODIFY_RETURN, "shouldRenderFace", at("RETURN")));
		assertNull(MixinTwinRebind.destination(mixin, mixin.methods.getFirst(), renderer));
		assertEquals(0, MixinTwinRebind.adapt(mixin, name -> renderer));
		assertEquals(List.of("shouldRenderFace"), selectors(mixin.methods.getFirst()));
	}

	@Test void whatItLeavesAlone() {
		String desc = "(Z" + GETTER + STATE + DIRECTION + POS + ")Z";
		// A NeoForge mod was compiled against the overload: the row is not its.
		ClassNode neo = mixin("com/example/xray/NeoMixin", "drawSide", desc, injector(MODIFY_RETURN, "shouldRenderFace", at("RETURN")));
		MixinStubRebind.noteEcosystem(neo.name, Ecosystem.NEOFORGE);
		assertEquals(0, MixinTwinRebind.adapt(neo, name -> renderer(1, 1)), "a NeoForge mod");
		// A local's ordinal counts the overload's locals, not vanilla's.
		ClassNode variable = mixin("com/example/xray/VariableMixin", "pos", "(" + POS + ")" + POS,
				injector(MODIFY_VARIABLE, "shouldRenderFace", at("HEAD")));
		assertEquals(0, MixinTwinRebind.adapt(variable, name -> renderer(1, 1)), "a @ModifyVariable");
		// Sugar would have to move with it; a nullability annotation is nothing.
		ClassNode local = mixin("com/example/xray/LocalMixin", "drawSide", desc, injector(MODIFY_RETURN, "shouldRenderFace", at("RETURN")));
		annotateParameter(local.methods.getFirst(), 1, LOCAL);
		assertEquals(0, MixinTwinRebind.adapt(local, name -> renderer(1, 1)), "a @Local");
		ClassNode notNull = mixin("com/example/xray/KotlinMixin", "drawSide", desc, injector(MODIFY_RETURN, "shouldRenderFace", at("RETURN")));
		annotateParameter(notNull.methods.getFirst(), 1, NOT_NULL);
		assertEquals(1, MixinTwinRebind.adapt(notNull, name -> renderer(1, 1)), "@NotNull");
		// An INVOKE point the overload holds another number of times: its ordinal would mean another call.
		ClassNode invoke = mixin("com/example/xray/InvokeMixin", "neighbour", "(Z" + GETTER + STATE + ")Z",
				injector(MODIFY_RETURN, "shouldRenderFace", at("INVOKE", "target", NEIGHBOR_CALL)));
		assertEquals(0, MixinTwinRebind.adapt(invoke, name -> renderer(1, 2)), "an INVOKE held twice in the overload");
		assertEquals(1, MixinTwinRebind.adapt(invoke, name -> renderer(1, 1)), "held once in each");
		// Vanilla's method called again in its class: it runs, and its injectors stay.
		ClassNode called = mixin("com/example/xray/CalledMixin", "drawSide", desc, injector(MODIFY_RETURN, "shouldRenderFace", at("RETURN")));
		ClassNode live = renderer(1, 1);
		MethodNode caller = method(live, "tesselateFlat");
		caller.instructions.insert(new InsnNode(Opcodes.POP));
		caller.instructions.insert(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDERER, "shouldRenderFace", VANILLA, false));
		for (int i = 0; i < 4; i++) caller.instructions.insert(new InsnNode(Opcodes.ACONST_NULL));
		caller.instructions.insert(new VarInsnNode(Opcodes.ALOAD, 0));
		assertEquals(0, MixinTwinRebind.adapt(called, name -> live), "vanilla's method is called");
		// The switch.
		System.setProperty(MixinTwinRebind.PROPERTY, "off");
		ClassNode off = mixin("com/example/xray/OffMixin", "drawSide", desc, injector(MODIFY_RETURN, "shouldRenderFace", at("RETURN")));
		assertEquals(0, MixinTwinRebind.adapt(off, name -> renderer(1, 1)), "switched off");
	}

	/** The released jar on the staged merged class: the face test moves, wrapped; nothing else in the mixin is touched. */
	@Test void releasedLiquidBounceMovesItsFaceTestAndNothingElse() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "actual game required");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(LIQUIDBOUNCE), LIQUIDBOUNCE + " absent");
		ClassNode renderer;
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			renderer = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(RENDERER + ".class")).readAllBytes()).accept(renderer, 0);
		}
		ClassNode mixin;
		try (ZipFile zip = new ZipFile(LIQUIDBOUNCE.toFile())) {
			ZipEntry entry = zip.getEntry("net/ccbluex/liquidbounce/injection/mixins/minecraft/render/MixinModelBlockRenderer.class");
			assertNotNull(entry);
			mixin = MixinFit.parse(zip.getInputStream(entry).readAllBytes());
		}
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		List<String> before = new ArrayList<>();
		for (MethodNode m : mixin.methods) if (MixinFit.injectorOf(m) != null) before.add(m.name + m.desc + selectors(m));
		assertEquals(1, MixinTwinRebind.adapt(mixin, name -> renderer));
		MethodNode outer = method(mixin, "injectXRayDrawSide");
		assertEquals("(Z" + GETTER + POS + STATE + DIRECTION + POS + ")Z", outer.desc);
		assertEquals(List.of("shouldRenderFace" + OVERLOAD), selectors(outer));
		assertEquals(List.of(0, 1, 2, 4, 5, 6), loads(outer));
		List<String> after = new ArrayList<>();
		for (MethodNode m : mixin.methods) if (MixinFit.injectorOf(m) != null && m != outer) after.add(m.name + m.desc + selectors(m));
		before.removeIf(s -> s.startsWith("injectXRayDrawSide("));
		assertEquals(before, after, "every other injector exactly as compiled");
	}

	/**
	 * The merged class's shape: NeoForge's overload first and called by {@code tesselateFlat}, vanilla's after it and
	 * called by nothing; each calls Block.shouldRenderFace the given number of times, and returns once.
	 */
	private static ClassNode renderer(int vanillaCalls, int overloadCalls) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = RENDERER;
		node.superName = "java/lang/Object";
		node.methods = new ArrayList<>(List.of(face(OVERLOAD, overloadCalls), face(VANILLA, vanillaCalls)));
		MethodNode tesselate = new MethodNode(Opcodes.ACC_PUBLIC, "tesselateFlat", "()V", null, null);
		tesselate.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (int i = 0; i < 5; i++) tesselate.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		tesselate.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDERER, "shouldRenderFace", OVERLOAD, false));
		tesselate.instructions.add(new InsnNode(Opcodes.POP));
		tesselate.instructions.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(tesselate);
		return node;
	}

	private static MethodNode face(String desc, int calls) {
		MethodNode face = new MethodNode(Opcodes.ACC_PRIVATE, "shouldRenderFace", desc, null, null);
		for (int i = 0; i < calls; i++) {
			face.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			face.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			face.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			face.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/world/level/block/Block", "shouldRenderFace",
					"(" + STATE + STATE + DIRECTION + ")Z", false));
			face.instructions.add(new InsnNode(Opcodes.POP));
		}
		face.instructions.add(new InsnNode(Opcodes.ICONST_1));
		face.instructions.add(new InsnNode(Opcodes.IRETURN));
		return face;
	}

	private static MethodNode overload(ClassNode renderer) {
		return method(renderer, "shouldRenderFace", OVERLOAD);
	}

	/** A Fabric mixin on the renderer with one instance handler. */
	private static ClassNode mixin(String name, String handlerName, String desc, AnnotationNode injector) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(RENDERER)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
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

	private static AnnotationNode injector(String desc, String selector, AnnotationNode at) {
		AnnotationNode injector = new AnnotationNode(desc);
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at", new ArrayList<>(List.of(at))));
		return injector;
	}

	private static AnnotationNode at(String value, Object... more) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", value));
		at.values.addAll(List.of(more));
		return at;
	}

	@SuppressWarnings("unchecked")
	private static void annotateParameter(MethodNode handler, int parameter, String desc) {
		int count = Type.getArgumentTypes(handler.desc).length;
		handler.invisibleParameterAnnotations = new List[count];
		handler.invisibleParameterAnnotations[parameter] = new ArrayList<>(List.of(new AnnotationNode(desc)));
	}

	private static List<String> selectors(MethodNode handler) {
		return MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method"));
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
	}

	/** The local slots {@code method} loads, in order. */
	private static List<Integer> loads(MethodNode method) {
		List<Integer> slots = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof VarInsnNode load) slots.add(load.var);
		return slots;
	}

	private static MethodInsnNode call(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call) return call;
		throw new AssertionError("no call in " + method.name);
	}
}
