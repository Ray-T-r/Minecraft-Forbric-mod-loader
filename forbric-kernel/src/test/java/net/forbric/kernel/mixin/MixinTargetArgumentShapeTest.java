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

import net.forbric.kernel.mixin.MixinHandlerShape.Extra;
import net.forbric.kernel.mixin.MixinHandlerShape.Role;
import net.forbric.kernel.mixin.MixinHandlerShape.Want;

/**
 * Where each injector's own operands end, and what the values a handler appends after them are. Mixin 0.8.7's
 * {@code Injector.validateParams} (and MixinExtras 0.5.4's injectors, which call it) hand a handler its operands and then
 * any leading run of the target method's arguments, each of exactly its type: after a redirected call's receiver and
 * arguments, a stored field value, a wrapped call's {@code Operation}, the one value a constant, variable, expression or
 * return modifier modifies, or a {@code @ModifyArgs}' {@code Args}. {@code @ModifyArg} takes none
 * ({@code ModifyArgInjector}: one argument, or exactly the call's). Read against the method it is written for, such a
 * value is the {@code @Local(argsOnly = true)} of its position; read alone it is no {@code @Local} at all.
 */
class MixinTargetArgumentShapeTest {
	private static final String HOST = "org/example/meadow/Hive", UTIL = "org/example/meadow/Pollen";
	private static final String OP = MixinHandlerShape.OPERATION, ARGS = MixinHandlerShape.ARGS;
	private static final String INJECTION = "Lorg/spongepowered/asm/mixin/injection/", EXTRAS = "Lcom/llamalad7/mixinextras/injector/";

	@Test void eachInjectorsOperandsEndWhereItsOwnRuleSaysAndWhatFollowsIsATargetArgument() {
		// A virtual call's receiver and argument, then a boolean of the target.
		assertSplit(handler(INJECTION + "Redirect;", "(L" + HOST + ";IZ)J", at("INVOKE", "L" + HOST + ";weigh(I)J")), List.of("L" + HOST + ";", "I"), "Z");
		// A static call: its two ints, then the target's hive.
		assertSplit(handler(INJECTION + "Redirect;", "(IIL" + HOST + ";)I", at("INVOKE", "L" + UTIL + ";mix(II)I")), List.of("I", "I"), "L" + HOST + ";");
		// A field write: the receiver and the value, then a target argument.
		assertSplit(handler(INJECTION + "Redirect;", "(L" + HOST + ";ILjava/lang/String;)V", at("FIELD", "L" + HOST + ";count:I")),
				List.of("L" + HOST + ";", "I"), "Ljava/lang/String;");
		// A field read named by its opcode: the receiver only.
		assertSplit(handler(INJECTION + "Redirect;", "(L" + HOST + ";J)I", at("FIELD", "L" + HOST + ";count:I", "opcode", Opcodes.GETFIELD)),
				List.of("L" + HOST + ";"), "J");
		assertSplit(handler(EXTRAS + "WrapWithCondition;", "(L" + HOST + ";ILjava/lang/String;)Z", at("INVOKE", "L" + HOST + ";buzz(I)V")),
				List.of("L" + HOST + ";", "I"), "Ljava/lang/String;");
		assertSplit(handler(INJECTION + "ModifyConstant;", "(IL" + HOST + ";)I", null), List.of("I"), "L" + HOST + ";");
		assertSplit(handler(INJECTION + "ModifyVariable;", "(ILjava/lang/String;)I", null), List.of("I"), "Ljava/lang/String;");
		assertSplit(handler(EXTRAS + "ModifyExpressionValue;", "(JI)J", at("INVOKE", "L" + HOST + ";weigh(I)J")), List.of("J"), "I");
		assertSplit(handler(EXTRAS + "ModifyReturnValue;", "(ZI)Z", at("RETURN", null)), List.of("Z"), "I");
		assertSplit(handler(INJECTION + "ModifyArgs;", "(" + ARGS + "ILjava/lang/String;)V", at("INVOKE", "L" + HOST + ";weigh(I)J")), List.of(ARGS), "I", "Ljava/lang/String;");
		assertSplit(handler(EXTRAS + "wrapoperation/WrapOperation;", "(L" + HOST + ";I" + OP + "Z)J", at("INVOKE", "L" + HOST + ";weigh(I)J")),
				List.of("L" + HOST + ";", "I", OP), "Z");
		// @ModifyArg hands over one argument, or all of the call's: never a target argument.
		assertSplit(handler(INJECTION + "ModifyArg;", "(I)I", at("INVOKE", "L" + UTIL + ";mix(II)I")), List.of("I"));
		assertSplit(handler(INJECTION + "ModifyArg;", "(II)I", at("INVOKE", "L" + UTIL + ";mix(II)I")), List.of("I", "I"));
	}

	@Test void aReceiverTheTypesCannotTellFromATargetArgumentIsReadOffTheInstruction() {
		// self() takes nothing and the hive is the first parameter either way: a virtual call's receiver, or a static
		// call's first appended target argument. Alone, nothing says which: everything stays an operand.
		MethodNode redirect = handler(INJECTION + "Redirect;", "(L" + HOST + ";)L" + HOST + ";", at("INVOKE", "L" + HOST + ";self()L" + HOST + ";"));
		assertSplit(redirect, List.of("L" + HOST + ";"));
		String target = "(L" + HOST + ";)V";
		MixinHandlerShape virtual = MixinHandlerShape.of(redirect, target, body(target, Opcodes.INVOKEVIRTUAL));
		assertEquals(List.of(Type.getType("L" + HOST + ";")), virtual.operands());
		assertEquals(List.of(), virtual.extras());
		MixinHandlerShape statik = MixinHandlerShape.of(redirect, target, body(target, Opcodes.INVOKESTATIC));
		assertEquals(List.of(), statik.operands());
		assertEquals(1, statik.extras().size());
		assertTrue(statik.extras().getFirst().implicit(), "the static call's hive is the target's argument");
		// A receiver widened by @Coerce looks like no receiver by type: alone, it stays an operand.
		MethodNode coerced = handler(INJECTION + "Redirect;", "(Ljava/lang/Object;)V", at("INVOKE", "L" + HOST + ";hum()V"));
		annotate(coerced, 0, INJECTION + "Coerce;");
		assertSplit(coerced, List.of("Ljava/lang/Object;"));
	}

	@Test void readAgainstItsMethodATargetArgumentIsTheArgsOnlyLocalOfItsPosition() {
		MethodNode wrap = handler(EXTRAS + "wrapoperation/WrapOperation;", "(L" + HOST + ";" + OP + "Ljava/lang/String;I)Z", at("INVOKE", "L" + HOST + ";ready()Z"));
		MixinHandlerShape alone = MixinHandlerShape.of(wrap);
		assertEquals(List.of(Role.ARGUMENT, Role.ARGUMENT), alone.extras().stream().map(Extra::role).toList());
		assertEquals(List.of(), alone.locals(), "alone, nothing says whose argument it is");

		String target = "(Ljava/lang/String;ILjava/lang/String;)V";
		MixinHandlerShape read = MixinHandlerShape.of(wrap, target, null);
		assertEquals(List.of(Role.LOCAL, Role.LOCAL), read.extras().stream().map(Extra::role).toList());
		assertTrue(read.extras().stream().allMatch(Extra::implicit));
		// The first String is one of two in the target: argsOnly picks it by ordinal. The int is the only one: by type.
		assertEquals(List.of("argsOnly", true, "ordinal", 0), read.extras().get(0).sugar().values);
		assertEquals(List.of("argsOnly", true), read.extras().get(1).sugar().values);
		assertTrue(read.matches("(L" + HOST + ";" + OP + ")Z", Want.local("Ljava/lang/String;"), Want.local("I")));
		MethodNode reference = body(target, Opcodes.INVOKEVIRTUAL);
		assertEquals(Map.of(2, 1, 3, 2), read.localSlots(reference, reference.instructions.getFirst()),
				"the target's first two parameters, whatever slot discriminator reads them");
	}

	@Test void aValueTheTargetDoesNotHaveThereIsNoLocalAndNoHandlerOfThatMethod() {
		MethodNode wrap = handler(EXTRAS + "wrapoperation/WrapOperation;", "(L" + HOST + ";" + OP + "I)Z", at("INVOKE", "L" + HOST + ";ready()Z"));
		assertNull(MixinHandlerShape.of(wrap, "()V", null), "no argument at all: Mixin refuses the handler on that method");
		assertNull(MixinHandlerShape.of(wrap, "(Ljava/lang/String;I)V", null), "the first argument is a String, not an int");
		assertNotNull(MixinHandlerShape.of(wrap, "(IZ)V", null), "a leading run of the target's arguments is enough");
		MethodNode coerced = handler(EXTRAS + "wrapoperation/WrapOperation;", "(L" + HOST + ";" + OP + "Ljava/lang/Object;)Z", at("INVOKE", "L" + HOST + ";ready()Z"));
		annotate(coerced, 2, INJECTION + "Coerce;");
		assertNull(MixinHandlerShape.of(coerced, "(Ljava/lang/String;)V", null), "a widened argument is no typed @Local");
		MethodNode annotated = handler(EXTRAS + "wrapoperation/WrapOperation;", "(L" + HOST + ";" + OP + "I)Z", at("INVOKE", "L" + HOST + ";ready()Z"));
		annotate(annotated, 2, "Lcom/llamalad7/mixinextras/sugar/Local;");
		assertFalse(MixinHandlerShape.of(annotated, "(Ljava/lang/String;)V", null).extras().getFirst().implicit(), "an annotated @Local is its own");
	}

	@Test void writingTheImplicitLocalsOutAnnotatesThemAllTogether() {
		MethodNode wrap = handler(EXTRAS + "wrapoperation/WrapOperation;", "(L" + HOST + ";" + OP + "Ljava/lang/String;I)Z", at("INVOKE", "L" + HOST + ";ready()Z"));
		assertFalse(MixinHandlerShape.annotateImplicit(wrap, Map.of(2, 1)), "the int after it would be read as the new method's argument");
		assertNull(wrap.invisibleParameterAnnotations, "nothing changed");
		assertTrue(MixinHandlerShape.annotateImplicit(wrap, Map.of(2, 1, 3, 4)));
		assertEquals(List.of("index", 1), wrap.invisibleParameterAnnotations[2].getFirst().values);
		assertEquals(List.of("index", 4), wrap.invisibleParameterAnnotations[3].getFirst().values);
		assertEquals(List.of(Role.LOCAL, Role.LOCAL), MixinHandlerShape.of(wrap).extras().stream().map(Extra::role).toList());
	}

	// -----------------------------------------------------------------------------------------------------------------

	private static void assertSplit(MethodNode handler, List<String> operands, String... arguments) {
		MixinHandlerShape shape = MixinHandlerShape.of(handler);
		assertEquals(operands.stream().map(Type::getType).toList(), shape.operands(), handler.visibleAnnotations.getFirst().desc);
		assertEquals(List.of(arguments).stream().map(Type::getType).toList(), shape.extras().stream().map(Extra::type).toList());
		assertTrue(shape.extras().stream().allMatch(extra -> extra.role() == Role.ARGUMENT), "values appended after the operands are target arguments");
	}

	private static MethodNode handler(String injector, String desc, AnnotationNode at) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "pollinate", desc, null, null);
		AnnotationNode annotation = new AnnotationNode(injector);
		annotation.values = new ArrayList<>(List.of("method", List.of("tend")));
		if (at != null) { annotation.values.add("at"); annotation.values.add(at); }
		method.visibleAnnotations = new ArrayList<>(List.of(annotation));
		return method;
	}

	private static AnnotationNode at(String value, String target, Object... more) {
		return CallbackSourceFixture.at(value, target, more);
	}

	@SuppressWarnings("unchecked")
	private static void annotate(MethodNode handler, int parameter, String desc) {
		int count = Type.getArgumentTypes(handler.desc).length;
		if (handler.invisibleParameterAnnotations == null) handler.invisibleParameterAnnotations = new List[count];
		handler.invisibleParameterAnnotations[parameter] = new ArrayList<>(List.of(new AnnotationNode(desc)));
	}

	/** A {@code tend} body of {@code desc} whose one instruction calls {@code self()} the way {@code opcode} says, then returns. */
	private static MethodNode body(String desc, int opcode) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "tend", desc, null, null);
		InsnList code = method.instructions;
		if (opcode == Opcodes.INVOKESTATIC) code.add(new MethodInsnNode(opcode, HOST, "self", "()L" + HOST + ";", false));
		else {
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(new MethodInsnNode(opcode, HOST, "self", "()L" + HOST + ";", false));
		}
		code.add(new InsnNode(Opcodes.POP));
		code.add(new InsnNode(Opcodes.RETURN));
		if (opcode != Opcodes.INVOKESTATIC) code.insert(new InsnNode(Opcodes.NOP));
		return method;
	}
}
