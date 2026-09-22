/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public class AdditiveMethodMergerTest {

	static final String NEO = "net/neoforged/Probe";
	static final String FORGE = "net/minecraftforge/Probe";
	private static final List<Integer> OBSERVED = new ArrayList<>();
	private static boolean failHook;

	public static void record(int family, int value) {
		OBSERVED.add(family * 100 + value);
		if (failHook) throw new ArithmeticException("from hook");
	}

	@Test
	void composesBothEntryHooksWithoutMutatingInputsAndExecutesTheOriginalBody() throws Exception {
		MethodNode vanilla = arithmetic();
		MethodNode base = prefix(vanilla, NEO), other = prefix(vanilla, FORGE);
		byte[] beforeBase = classBytes("Example", base), beforeOther = classBytes("Example", other);
		AdditiveMethodMerger.Result result = merge(vanilla, base, other);
		assertTrue(result.accepted(), result.reason());
		assertEquals(1, result.baseCalls());
		assertEquals(1, result.otherCalls());
		assertArrayEquals(beforeBase, classBytes("Example", base));
		assertArrayEquals(beforeOther, classBytes("Example", other));
		OBSERVED.clear();
		assertEquals(10, invoke(result.method(), 3));
		assertEquals(List.of(103, 203), OBSERVED);
	}

	@Test
	void originalControlFlowAndExceptionRegionArePreservedAndDoNotCatchNewHooks() throws Exception {
		MethodNode vanilla = divisionWithHandler();
		AdditiveMethodMerger.Result result = merge(vanilla, prefix(vanilla, NEO), prefix(vanilla, FORGE));
		assertTrue(result.accepted(), result.reason());
		assertEquals(5, invoke(result.method(), 2));
		assertEquals(-1, invoke(result.method(), 0));
		try {
			failHook = true;
			InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
					() -> invoke(result.method(), 0));
			assertInstanceOf(ArithmeticException.class, thrown.getCause());
		} finally {
			failHook = false;
		}
	}

	@Test
	void changedImmediateAndLocalSlotCannotHideBehindEqualOpcodes() {
		refusesChangedBody(arithmetic(), m -> ((IntInsnNode) executable(m).get(3)).operand++);
		MethodNode twoArguments = arithmetic();
		twoArguments.desc = "(II)I";
		refusesChangedBody(twoArguments, m -> ((VarInsnNode) executable(m).get(2)).var = 1);
	}

	@Test
	void incrementOperandAndSwitchKeysAndDestinationsAreCompared() {
		MethodNode increment = method("(I)I");
		increment.instructions.add(new IincInsnNode(0, 1));
		increment.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		increment.instructions.add(new InsnNode(Opcodes.IRETURN));
		refusesChangedBody(increment, m -> ((IincInsnNode) executable(m).get(2)).incr = 2);

		MethodNode switching = method("(I)I");
		LabelNode hit = new LabelNode(), miss = new LabelNode();
		switching.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		switching.instructions.add(new LookupSwitchInsnNode(miss, new int[] { 1 }, new LabelNode[] { hit }));
		switching.instructions.add(hit);
		switching.instructions.add(new InsnNode(Opcodes.ICONST_1));
		switching.instructions.add(new InsnNode(Opcodes.IRETURN));
		switching.instructions.add(miss);
		switching.instructions.add(new InsnNode(Opcodes.ICONST_0));
		switching.instructions.add(new InsnNode(Opcodes.IRETURN));
		assertTrue(merge(switching, prefix(switching, NEO), prefix(switching, FORGE)).accepted());
		refusesChangedBody(switching, m -> ((LookupSwitchInsnNode) executable(m).get(3)).keys.set(0, 2));
		refusesChangedBody(switching, m -> {
			LookupSwitchInsnNode s = (LookupSwitchInsnNode) executable(m).get(3);
			s.labels.set(0, s.dflt);
		});
	}

	@Test
	void changedJumpDestinationAndExpandedHandlerAreRejected() {
		MethodNode vanilla = divisionWithHandler();
		MethodNode other = prefix(vanilla, FORGE);
		LabelNode start = new LabelNode();
		other.instructions.insert(start);
		other.tryCatchBlocks.get(0).start = start;
		assertFalse(merge(vanilla, prefix(vanilla, NEO), other).accepted());

		MethodNode branch = method("(I)I");
		LabelNode first = new LabelNode(), second = new LabelNode();
		branch.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		branch.instructions.add(new JumpInsnNode(Opcodes.IFEQ, first));
		branch.instructions.add(second);
		branch.instructions.add(new InsnNode(Opcodes.ICONST_1));
		branch.instructions.add(new InsnNode(Opcodes.IRETURN));
		branch.instructions.add(first);
		branch.instructions.add(new InsnNode(Opcodes.ICONST_0));
		branch.instructions.add(new InsnNode(Opcodes.IRETURN));
		refusesChangedBody(branch, m -> {
			JumpInsnNode jump = (JumpInsnNode) executable(m).get(3);
			jump.label = (LabelNode) jump.getNext();
		});
	}

	@Test
	void bootstrapOwnerArgumentsAndDynamicConstantsAreNotDiscarded() {
		Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "Bootstrap", "make", "()V", false);
		MethodNode dynamic = arithmetic();
		dynamic.instructions.insert(new InvokeDynamicInsnNode("call", "()V", bootstrap, 1));
		refusesChangedBody(dynamic, m -> ((InvokeDynamicInsnNode) executable(m).get(2)).bsmArgs = new Object[] { 2 });
		refusesChangedBody(dynamic, m -> ((InvokeDynamicInsnNode) executable(m).get(2)).bsm =
				new Handle(Opcodes.H_INVOKESTATIC, "DifferentBootstrap", "make", "()V", false));
		MethodNode constant = method("(I)I");
		constant.instructions.add(new LdcInsnNode(new ConstantDynamic("value", "I", bootstrap, 1)));
		constant.instructions.add(new InsnNode(Opcodes.IRETURN));
		refusesChangedBody(constant, m -> ((LdcInsnNode) executable(m).get(2)).cst =
				new ConstantDynamic("value", "I", bootstrap, 2));
	}

	@Test
	void wideParameterSlotsAndLiteralArgumentTypesAreChecked() {
		MethodNode vanilla = method("(JDLjava/lang/String;)I");
		vanilla.instructions.add(new InsnNode(Opcodes.ICONST_1));
		vanilla.instructions.add(new InsnNode(Opcodes.IRETURN));
		List<MethodNode> sides = new ArrayList<>();
		for (String family : List.of(NEO, FORGE)) {
			MethodNode side = new MethodNode(Opcodes.ASM9, vanilla.access, vanilla.name, vanilla.desc, null, null);
			vanilla.accept(side);
			InsnList prefix = new InsnList();
			prefix.add(new VarInsnNode(Opcodes.LLOAD, 0));
			prefix.add(new VarInsnNode(Opcodes.DLOAD, 2));
			prefix.add(new VarInsnNode(Opcodes.ALOAD, 4));
			prefix.add(new InsnNode(Opcodes.ACONST_NULL));
			prefix.add(new IntInsnNode(Opcodes.SIPUSH, 1200));
			prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, family, "observe",
					"(JDLjava/lang/String;Ljava/lang/Object;I)V", false));
			side.instructions.insert(prefix);
			sides.add(side);
		}
		assertTrue(merge(vanilla, sides.get(0), sides.get(1)).accepted());
		((VarInsnNode) executable(sides.get(1)).get(0)).var = 1; // second half of the long is not a parameter
		assertFalse(merge(vanilla, sides.get(0), sides.get(1)).accepted());
	}

	@Test
	void nonVoidHooksLocalWritesAndUnknownHelpersAreRefused() {
		MethodNode vanilla = arithmetic();
		for (Consumer<MethodNode> mutation : List.<Consumer<MethodNode>>of(
				m -> ((MethodInsnNode) executable(m).get(1)).desc = "(I)I",
				m -> m.instructions.insert(new IincInsnNode(0, 1)),
				m -> ((MethodInsnNode) executable(m).get(1)).owner = "Example$1",
				m -> ((VarInsnNode) executable(m).get(0)).var = 3,
				m -> ((VarInsnNode) executable(m).get(0)).setOpcode(Opcodes.ALOAD))) {
			MethodNode other = prefix(vanilla, FORGE);
			mutation.accept(other);
			AdditiveMethodMerger.Result result = merge(vanilla, prefix(vanilla, NEO), other);
			assertFalse(result.accepted(), result.reason());
			assertTrue(result.reason().contains("prefix"), result.reason());
		}
	}

	@Test
	void constructorsMissingVanillaAndChangedExecutionFlagsKeepExistingArbitration() {
		MethodNode vanilla = arithmetic();
		assertFalse(merge(null, prefix(vanilla, NEO), prefix(vanilla, FORGE)).accepted());
		MethodNode other = prefix(vanilla, FORGE);
		other.access &= ~Opcodes.ACC_STATIC;
		assertFalse(merge(vanilla, prefix(vanilla, NEO), other).accepted());
		vanilla.name = "<clinit>";
		assertFalse(merge(vanilla, prefix(vanilla, NEO), prefix(vanilla, FORGE)).accepted());
	}

	private static void refusesChangedBody(MethodNode vanilla, Consumer<MethodNode> mutate) {
		MethodNode other = prefix(vanilla, FORGE);
		mutate.accept(other);
		AdditiveMethodMerger.Result result = merge(vanilla, prefix(vanilla, NEO), other);
		assertFalse(result.accepted(), result.reason());
		assertTrue(result.reason().contains("operands, control flow or handlers"), result.reason());
	}

	static AdditiveMethodMerger.Result merge(MethodNode vanilla, MethodNode base, MethodNode other) {
		return AdditiveMethodMerger.merge(vanilla, base, other, "net/neoforged/", "net/minecraftforge/");
	}

	static MethodNode method(String descriptor) {
		return new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "compute", descriptor, null, null);
	}

	static MethodNode arithmetic() {
		MethodNode method = method("(I)I");
		method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 7));
		method.instructions.add(new InsnNode(Opcodes.IADD));
		method.instructions.add(new InsnNode(Opcodes.IRETURN));
		return method;
	}

	static MethodNode divisionWithHandler() {
		MethodNode method = method("(I)I");
		LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
		method.instructions.add(start);
		method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 10));
		method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		method.instructions.add(new InsnNode(Opcodes.IDIV));
		method.instructions.add(end);
		method.instructions.add(new InsnNode(Opcodes.IRETURN));
		method.instructions.add(handler);
		method.instructions.add(new InsnNode(Opcodes.POP));
		method.instructions.add(new InsnNode(Opcodes.ICONST_M1));
		method.instructions.add(new InsnNode(Opcodes.IRETURN));
		method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/ArithmeticException"));
		return method;
	}

	static MethodNode prefix(MethodNode source, String owner) {
		MethodNode result = new MethodNode(Opcodes.ASM9, source.access, source.name, source.desc, source.signature,
				source.exceptions.toArray(String[]::new));
		source.accept(result);
		InsnList prefix = new InsnList();
		prefix.add(new VarInsnNode(Opcodes.ILOAD, 0));
		prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "observe", "(I)V", false));
		result.instructions.insert(prefix);
		return result;
	}

	private static List<AbstractInsnNode> executable(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) out.add(instruction);
		return out;
	}

	static byte[] classBytes(String name, MethodNode method) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		method.accept(writer);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static Object invoke(MethodNode method, int value) throws Exception {
		return invokeClass("Example", classBytes("Example", method), value);
	}

	static Object invokeClass(String owner, byte[] bytes, int value) throws Exception {
		class Loader extends ClassLoader {
			Class<?> define(String name, byte[] bytes) { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
		}
		Loader loader = new Loader();
		for (String family : List.of(NEO, FORGE)) {
			MethodNode hook = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "observe", "(I)V", null, null);
			hook.instructions.add(new InsnNode(family.equals(NEO) ? Opcodes.ICONST_1 : Opcodes.ICONST_2));
			hook.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
			hook.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
					TypeNames.RECORDER, "record", "(II)V", false));
			hook.instructions.add(new InsnNode(Opcodes.RETURN));
			loader.define(family, classBytes(family, hook));
		}
		return loader.define(owner, bytes).getMethod("compute", int.class).invoke(null, value);
	}

	private static final class TypeNames {
		static final String RECORDER = AdditiveMethodMergerTest.class.getName().replace('.', '/');
	}
}
