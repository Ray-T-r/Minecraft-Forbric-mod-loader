/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.mixin.MixinHandlerShape.Extra;

/**
 * Whether a field access hands its handler the value is what the instruction is, as Mixin and MixinExtras read it off
 * the instruction they inject at: a {@code PUTFIELD}/{@code PUTSTATIC} hands over the value being written, a
 * {@code GETFIELD}/{@code GETSTATIC} none ({@code RedirectInjector}; MixinExtras 0.5.4's
 * {@code WrapWithConditionInjector.getEffectiveArgTypes} and {@code ModifyReceiverInjector.getEffectiveArgTypes}).
 * The handler's return type is no witness: a {@code @WrapWithCondition} returns a boolean and a {@code @ModifyReceiver}
 * its receiver whichever access it is at. So the value written is an operand, and only what follows it is a target
 * argument the handler appends — the {@code @Local(argsOnly = true)} it stands for.
 */
class MixinFieldAccessOperandTest {
	private static final String KILN = "org/example/pottery/Kiln", HEAT = "L" + KILN + ";heat:I", FUEL = "L" + KILN + ";fuel:I";
	private static final String INJECTION = "Lorg/spongepowered/asm/mixin/injection/", EXTRAS = "Lcom/llamalad7/mixinextras/injector/";
	private static final String WWC = EXTRAS + "WrapWithCondition;", RECEIVER = EXTRAS + "ModifyReceiver;", REDIRECT = INJECTION + "Redirect;";

	@Test void aConditionOnAFieldWriteTakesTheWrittenValueThenTheTargetsArguments() {
		// The written heat, then fire's String argument: read off the PUTFIELD in fire's body.
		MethodNode condition = handler(WWC, "(L" + KILN + ";ILjava/lang/String;)Z", at(HEAT));
		MixinHandlerShape read = MixinHandlerShape.of(condition, "(Ljava/lang/String;)V", body("(Ljava/lang/String;)V", Opcodes.PUTFIELD, HEAT));
		assertEquals(types("L" + KILN + ";", "I"), read.operands());
		assertImplicit(read, "Ljava/lang/String;");
		// No body at hand: a condition wraps only an instruction that leaves nothing, a write.
		MixinHandlerShape alone = MixinHandlerShape.of(condition, "(Ljava/lang/String;)V", null);
		assertEquals(types("L" + KILN + ";", "I"), alone.operands());
		assertImplicit(alone, "Ljava/lang/String;");
		// The same condition taking nothing more, on a method whose first argument is an int: the int is the value written.
		MethodNode bare = handler(WWC, "(L" + KILN + ";I)Z", at(HEAT));
		MixinHandlerShape own = MixinHandlerShape.of(bare, "(I)V", body("(I)V", Opcodes.PUTFIELD, HEAT));
		assertEquals(types("L" + KILN + ";", "I"), own.operands());
		assertEquals(List.of(), own.extras(), "the int is the stored value, not the target's argument");
		// A static write: the value alone, then the target's argument.
		MethodNode statik = handler(WWC, "(ILjava/lang/String;)Z", at("L" + KILN + ";kilns:I"));
		MixinHandlerShape stored = MixinHandlerShape.of(statik, "(Ljava/lang/String;)V", body("(Ljava/lang/String;)V", Opcodes.PUTSTATIC, "L" + KILN + ";kilns:I"));
		assertEquals(types("I"), stored.operands());
		assertImplicit(stored, "Ljava/lang/String;");
	}

	@Test void aReceiverModifierOnAFieldWriteTakesTheWrittenValueAndOnAReadNone() {
		MethodNode modifier = handler(RECEIVER, "(L" + KILN + ";ILjava/lang/String;)L" + KILN + ";", at(FUEL));
		// Read off the instruction in the body…
		MixinHandlerShape write = MixinHandlerShape.of(modifier, "(Ljava/lang/String;)V", body("(Ljava/lang/String;)V", Opcodes.PUTFIELD, FUEL));
		assertEquals(types("L" + KILN + ";", "I"), write.operands());
		assertImplicit(write, "Ljava/lang/String;");
		// …or off the point's opcode.
		MethodNode named = handler(RECEIVER, "(L" + KILN + ";ILjava/lang/String;)L" + KILN + ";", at(FUEL, "opcode", Opcodes.PUTFIELD));
		MixinHandlerShape opcode = MixinHandlerShape.of(named, "(Ljava/lang/String;)V", null);
		assertEquals(types("L" + KILN + ";", "I"), opcode.operands());
		assertImplicit(opcode, "Ljava/lang/String;");
		// A read hands over no value: the int after the receiver is the target's first argument.
		MethodNode reader = handler(RECEIVER, "(L" + KILN + ";I)L" + KILN + ";", at(FUEL));
		MixinHandlerShape read = MixinHandlerShape.of(reader, "(I)V", body("(I)V", Opcodes.GETFIELD, FUEL));
		assertEquals(types("L" + KILN + ";"), read.operands());
		assertImplicit(read, "I");
	}

	@Test void aFieldAccessNothingSaysIsAWriteOrThatTheInjectorCannotTakeKeepsEveryValueAnOperand() {
		// A receiver modifier takes reads and writes alike: without the instruction, the int may be either.
		MethodNode modifier = handler(RECEIVER, "(L" + KILN + ";I)L" + KILN + ";", at(FUEL));
		assertNoArgument(MixinHandlerShape.of(modifier, "(I)V", null), "L" + KILN + ";", "I");
		// A point selecting a read and a write is not one access.
		MethodNode mixed = handler(RECEIVER, "(L" + KILN + ";I)L" + KILN + ";", at(FUEL));
		MethodNode both = body("(I)V", Opcodes.GETFIELD, FUEL);
		both.instructions.insertBefore(both.instructions.getLast(), access(Opcodes.PUTFIELD, FUEL));
		assertNoArgument(MixinHandlerShape.of(mixed, "(I)V", both), "L" + KILN + ";", "I");
		// MixinExtras refuses a condition on a read, and a receiver modifier of a static field.
		MethodNode condition = handler(WWC, "(L" + KILN + ";I)Z", at(HEAT));
		assertNoArgument(MixinHandlerShape.of(condition, "(I)V", body("(I)V", Opcodes.GETFIELD, HEAT)), "L" + KILN + ";", "I");
		MethodNode statik = handler(RECEIVER, "(II)I", at("L" + KILN + ";kilns:I"));
		assertNoArgument(MixinHandlerShape.of(statik, "(I)V", body("(I)V", Opcodes.PUTSTATIC, "L" + KILN + ";kilns:I")), "I", "I");
	}

	@Test void aRedirectOfAFieldIsReadTheSameWayWhateverSaysWhichAccessItIs() {
		// A read's redirect returns the field's value: the receiver alone, then the target's argument — read off the
		// instruction or the opcode. (Alone, a GETSTATIC appending a kiln and a string looks the same: it stays whole.)
		MethodNode reader = handler(REDIRECT, "(L" + KILN + ";Ljava/lang/String;)I", at(HEAT));
		MethodNode named = handler(REDIRECT, "(L" + KILN + ";Ljava/lang/String;)I", at(HEAT, "opcode", Opcodes.GETFIELD));
		for (MixinHandlerShape read : List.of(MixinHandlerShape.of(reader, "(Ljava/lang/String;)V", body("(Ljava/lang/String;)V", Opcodes.GETFIELD, HEAT)),
				MixinHandlerShape.of(named, "(Ljava/lang/String;)V", null))) {
			assertEquals(types("L" + KILN + ";"), read.operands());
			assertImplicit(read, "Ljava/lang/String;");
		}
		assertNoArgument(MixinHandlerShape.of(reader, "(Ljava/lang/String;)V", null), "L" + KILN + ";", "Ljava/lang/String;");
		// A write's returns nothing: the receiver and the value, then the target's argument.
		MethodNode writer = handler(REDIRECT, "(L" + KILN + ";ILjava/lang/String;)V", at(HEAT));
		for (MethodNode body : new MethodNode[]{null, body("(Ljava/lang/String;)V", Opcodes.PUTFIELD, HEAT)}) {
			MixinHandlerShape write = MixinHandlerShape.of(writer, "(Ljava/lang/String;)V", body);
			assertEquals(types("L" + KILN + ";", "I"), write.operands());
			assertImplicit(write, "Ljava/lang/String;");
		}
	}

	// -----------------------------------------------------------------------------------------------------------------

	private static void assertImplicit(MixinHandlerShape shape, String... arguments) {
		assertEquals(types(arguments), shape.extras().stream().map(Extra::type).toList());
		assertTrue(shape.extras().stream().allMatch(Extra::implicit), "each appended value is the target's argument, an implicit @Local");
	}

	private static void assertNoArgument(MixinHandlerShape shape, String... operands) {
		assertEquals(types(operands), shape.operands());
		assertEquals(List.of(), shape.locals(), "no value is read as the target's argument");
	}

	private static List<Type> types(String... descriptors) {
		return List.of(descriptors).stream().map(Type::getType).toList();
	}

	private static MethodNode handler(String injector, String desc, AnnotationNode at) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "glaze", desc, null, null);
		AnnotationNode annotation = new AnnotationNode(injector);
		annotation.values = new ArrayList<>(List.of("method", List.of("fire"), "at", at));
		method.visibleAnnotations = new ArrayList<>(List.of(annotation));
		return method;
	}

	private static AnnotationNode at(String target, Object... more) {
		return CallbackSourceFixture.at("FIELD", target, more);
	}

	/** A {@code fire} body of {@code desc} whose one field instruction is {@code opcode} on {@code field}, then returns. */
	private static MethodNode body(String desc, int opcode, String field) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "fire", desc, null, null);
		method.instructions.add(access(opcode, field));
		method.instructions.add(new InsnNode(Opcodes.RETURN));
		return method;
	}

	private static FieldInsnNode access(int opcode, String field) {
		MixinFit.Member member = MixinFit.parseMember(field);
		return new FieldInsnNode(opcode, member.owner(), member.name(), member.desc());
	}
}
