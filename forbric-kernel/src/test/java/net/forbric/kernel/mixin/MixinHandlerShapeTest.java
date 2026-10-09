/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.mixin.MixinHandlerShape.Role;
import net.forbric.kernel.mixin.MixinHandlerShape.Want;

/**
 * Handlers that answer one callback contract with different extras: the operands are the injector's, the extras are
 * whatever subset of {@code @Local}/{@code @Share}/{@code @Cancellable}/captured values each handler asks for, and which
 * value a {@code @Local} receives is read off the target body — never off the extra list one mod happened to declare.
 */
class MixinHandlerShapeTest {
	private static final String HOST = "example/shape/Host", OP = MixinHandlerShape.OPERATION, CI = MixinHandlerShape.CALLBACK;
	private static final String BUILDER = "Ljava/lang/StringBuilder;", REF = "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;", SHARE = "Lcom/llamalad7/mixinextras/sugar/Share;";

	@Test void theOperandsAreTheInjectorsAndTheExtrasAreWhateverTheHandlerAsksFor() {
		MethodNode full = wrap("(L" + HOST + ";" + OP + BUILDER + "I" + REF + ")Z", local(), local("ordinal", 1), share("key"));
		MethodNode fewer = wrap("(L" + HOST + ";" + OP + "I)Z", local("ordinal", 1));
		MethodNode none = wrap("(L" + HOST + ";" + OP + ")Z");
		for (MethodNode handler : List.of(full, fewer, none)) {
			MixinHandlerShape shape = MixinHandlerShape.of(handler);
			assertEquals("WrapOperation", shape.kind());
			assertTrue(shape.operands("(L" + HOST + ";" + OP + ")Z"), handler.desc);
		}
		MixinHandlerShape shape = MixinHandlerShape.of(full);
		assertEquals(List.of(Role.LOCAL, Role.LOCAL, Role.SHARE), shape.extras().stream().map(MixinHandlerShape.Extra::role).toList());
		assertTrue(shape.matches("(L" + HOST + ";" + OP + ")Z", Want.local(BUILDER), Want.local("I"), Want.share(REF)));
		assertFalse(shape.matches("(L" + HOST + ";" + OP + ")Z", Want.local(BUILDER), Want.local("I")), "an exact list is exact");
		assertFalse(MixinHandlerShape.of(fewer).operands("(L" + HOST + ";" + OP + ")I"), "the return is part of the operands' contract");
	}

	@Test void injectAndOtherInjectorsSplitWhereTheirOwnOperandsEnd() {
		MethodNode captured = inject("(L" + HOST + ";" + CI + BUILDER + "I)V");
		MixinHandlerShape shape = MixinHandlerShape.of(captured);
		assertEquals(List.of(Type.getType("L" + HOST + ";"), Type.getType(CI)), shape.operands());
		assertEquals(List.of(Role.CAPTURED, Role.CAPTURED), shape.extras().stream().map(MixinHandlerShape.Extra::role).toList(),
				"unannotated values after the callback are what a locals capture fills");
		MethodNode bare = inject("(" + CI + "L" + HOST + ";)V", local("argsOnly", true));
		assertTrue(MixinHandlerShape.of(bare).matches("(" + CI + ")V", Want.local("L" + HOST + ";")), "the callback-alone form");
		MethodNode constant = handler("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;", "(II)I", null, local());
		assertTrue(MixinHandlerShape.of(constant).matches("(I)I", Want.local("I")), "a modified value then sugar");
		MethodNode cancellable = wrap("(L" + HOST + ";" + OP + CI + ")Z", sugar("Lcom/llamalad7/mixinextras/sugar/Cancellable;"));
		assertTrue(MixinHandlerShape.of(cancellable).matches("(L" + HOST + ";" + OP + ")Z", Want.cancellable()));
		assertNull(MixinHandlerShape.of(new MethodNode(Opcodes.ACC_PRIVATE, "plain", "()V", null, null)), "not a handler");
	}

	@Test void aSubsetOfWhatAnAdapterServesIsServedByRoleAndTypeNeverByPosition() {
		List<Want> offered = List.of(Want.local(BUILDER), Want.local("I"), Want.share(REF));
		assertEquals(Map.of(0, 0, 1, 1, 2, 2), MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + BUILDER + "I" + REF + ")Z",
				local(), local(), share("key"))).within(offered));
		assertEquals(Map.of(0, 1), MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + "I)Z", local())).within(offered));
		assertEquals(Map.of(0, 2, 1, 0), MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + REF + BUILDER + ")Z", share("key"), local())).within(offered),
				"declared in another order");
		assertEquals(Map.of(), MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + ")Z")).within(offered));
		assertNull(MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + "II)Z", local("ordinal", 0), local("ordinal", 1))).within(offered),
				"two ints for one offered int");
		assertNull(MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + "J)Z", local())).within(offered), "a local nobody serves");
		assertNull(MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + "I)Z")).within(offered), "an unannotated value is not a @Local");
		assertNull(MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + "I)Z", local())).within(List.of(Want.local("I"), Want.local("I"))),
				"same-typed offers are told apart by slot, not by order");
	}

	@Test void eachLocalNamesTheSlotMixinExtrasReadsWhateverDiscriminatorItUses() {
		MethodNode reference = reference(false);
		AbstractInsnNode point = anchor(reference, "anchor");
		MixinHandlerShape byType = MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + BUILDER + ")Z", local()));
		MixinHandlerShape byOrdinal = MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + "I)Z", local("ordinal", 1)));
		MixinHandlerShape byName = MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + "I)Z", local("name", List.of("y"))));
		MixinHandlerShape byIndex = MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + "I)Z", local("index", 3)));
		MixinHandlerShape both = MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + "I" + BUILDER + ")Z", local("ordinal", 0), local()));
		assertEquals(Map.of(2, 1), byType.localSlots(reference, point));
		assertEquals(Map.of(2, 3), byOrdinal.localSlots(reference, point));
		assertEquals(Map.of(2, 3), byName.localSlots(reference, point));
		assertEquals(Map.of(2, 3), byIndex.localSlots(reference, point));
		assertEquals(Map.of(2, 2, 3, 1), both.localSlots(reference, point));
		assertNull(MixinHandlerShape.of(wrap("(L" + HOST + ";" + OP + "I)Z", local())).localSlots(reference, point),
				"two live ints: a bare @Local int names neither");
	}

	@Test void aMovedAnchorKeepsEachLocalItsOwnProducerWhicheverSubsetTheHandlerTakes() {
		MethodNode reference = reference(false), current = reference(true);
		AbstractInsnNode point = anchor(reference, "anchor"), moved = anchor(current, "carrierAnchor");
		MethodNode both = wrap("(L" + HOST + ";" + OP + BUILDER + "I)Z", local(), local("ordinal", 1));
		MethodNode builderOnly = wrap("(L" + HOST + ";" + OP + BUILDER + ")Z", local());
		MethodNode yOnly = wrap("(L" + HOST + ";" + OP + "I)Z", local("name", List.of("y")));
		assertEquals(Map.of(2, 2, 3, 4), MixinHandlerShape.of(both).proveLocals(HOST, reference, point, current, moved));
		assertEquals(Map.of(2, 2), MixinHandlerShape.of(builderOnly).proveLocals(HOST, reference, point, current, moved));
		assertEquals(Map.of(2, 4), MixinHandlerShape.of(yOnly).proveLocals(HOST, reference, point, current, moved),
				"the carrier's own int before y moved it, and y keeps its value");
		MethodNode x = wrap("(L" + HOST + ";" + OP + "I)Z", local("ordinal", 0));
		assertEquals(Map.of(2, 3), MixinHandlerShape.of(x).proveLocals(HOST, reference, point, current, moved),
				"ordinal 0 natively is x, and x is ordinal 1 in the merged body: the producer decides, not the ordinal");
	}

	// -----------------------------------------------------------------------------------------------------------------

	/**
	 * {@code run()V}: a fresh StringBuilder, then {@code int x = 7; int y = 9;}, then the anchor call. The merged body
	 * first stores a carrier int of its own, shifting every slot by one, and calls a carrier anchor instead.
	 */
	private static MethodNode reference(boolean merged) {
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
		LabelNode start = new LabelNode(), end = new LabelNode();
		InsnList c = m.instructions;
		int shift = merged ? 1 : 0;
		c.add(start);
		if (merged) { c.add(new IntInsnNode(Opcodes.BIPUSH, 5)); c.add(new VarInsnNode(Opcodes.ISTORE, 1)); }
		c.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder")); c.add(new InsnNode(Opcodes.DUP));
		c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
		c.add(new VarInsnNode(Opcodes.ASTORE, 1 + shift));
		c.add(new IntInsnNode(Opcodes.BIPUSH, 7)); c.add(new VarInsnNode(Opcodes.ISTORE, 2 + shift));
		c.add(new IntInsnNode(Opcodes.BIPUSH, 9)); c.add(new VarInsnNode(Opcodes.ISTORE, 3 + shift));
		c.add(new VarInsnNode(Opcodes.ALOAD, 0));
		c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, HOST, merged ? "carrierAnchor" : "anchor", "()V", false));
		c.add(new InsnNode(Opcodes.RETURN));
		c.add(end);
		m.localVariables = new ArrayList<>();
		if (merged) m.localVariables.add(new LocalVariableNode("carrier", "I", null, start, end, 1));
		m.localVariables.add(new LocalVariableNode("sb", BUILDER, null, start, end, 1 + shift));
		m.localVariables.add(new LocalVariableNode("x", "I", null, start, end, 2 + shift));
		m.localVariables.add(new LocalVariableNode("y", "I", null, start, end, 3 + shift));
		m.maxLocals = 4 + shift; m.maxStack = 2;
		return m;
	}

	private static AbstractInsnNode anchor(MethodNode method, String name) {
		for (AbstractInsnNode i : method.instructions) if (i instanceof MethodInsnNode call && call.name.equals(name)) return call;
		throw new AssertionError(name);
	}

	private static MethodNode wrap(String desc, AnnotationNode... extras) {
		return handler("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", desc, OP, extras);
	}

	private static MethodNode inject(String desc, AnnotationNode... extras) {
		return handler("Lorg/spongepowered/asm/mixin/injection/Inject;", desc, CI, extras);
	}

	/** A handler whose trailing parameters carry {@code extras}, in order, after the first parameter of {@code boundary}. */
	@SuppressWarnings("unchecked")
	private static MethodNode handler(String injector, String desc, String boundary, AnnotationNode... extras) {
		MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE, "callback", desc, null, null);
		AnnotationNode annotation = new AnnotationNode(injector);
		annotation.values = new ArrayList<>(List.of("method", List.of("run()V")));
		m.visibleAnnotations = new ArrayList<>(List.of(annotation));
		Type[] parameters = Type.getArgumentTypes(desc);
		int first = parameters.length - extras.length;
		if (boundary != null) for (int i = 0; i < parameters.length; i++) if (parameters[i].getDescriptor().equals(boundary)) { first = Math.max(first, i + 1); break; }
		m.invisibleParameterAnnotations = new List[parameters.length];
		for (int i = 0; i < extras.length; i++) if (extras[i] != null) m.invisibleParameterAnnotations[first + i] = new ArrayList<>(List.of(extras[i]));
		return m;
	}

	private static AnnotationNode local(Object... values) {
		AnnotationNode local = new AnnotationNode(LOCAL);
		local.values = new ArrayList<>(List.of(values));
		return local;
	}

	private static AnnotationNode share(String key) {
		AnnotationNode share = new AnnotationNode(SHARE);
		share.values = new ArrayList<>(List.of("value", key));
		return share;
	}

	private static AnnotationNode sugar(String desc) {
		return new AnnotationNode(desc);
	}
}
