package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class MixinLocalOriginProofTest {
	private static final String OWNER = "example/CaptureOwner", TYPE = "Ljava/lang/StringBuilder;";

	@Test void aRenamedAndReindexedFreshLocalMatchesItsSameConstructorProducer() {
		MethodNode original = method(1, "original", false, false), current = method(3, "changed", false, false);
		assertEquals(Map.of(1, 3), prove(handler("original"), original, current));
	}

	@Test void aDifferentConstructorCannotPassOnItsTypeAlone() {
		assertNull(prove(handler("original"), method(1, "original", false, false), method(3, "changed", true, false)));
	}

	@Test void twoSameTypedAllocationsAreNotInterchangeableEvenWithTheSameDebugName() {
		assertNull(prove(handler("original"), method(1, "original", false, false), method(3, "original", false, true)));
	}

	@Test void aNamedCaptureAbsentFromTheOriginalDebugScopeDoesNotGetInvented() {
		assertNull(prove(handler("not_the_native_local"), method(1, "original", false, false), method(3, "changed", false, false)));
	}

	@Test void aWidenedCapturedConstructorCanMatchOnlyItsExactAnchoredConsumerArgumentRole() {
		ClassNode original = roleMethod(false, false), current = roleMethod(true, false);
		MethodNode before = original.methods.getFirst(), after = current.methods.getFirst(), helper = current.methods.get(1);
		MethodNode handler = handler("original"); MethodInsnNode oldPoint = point(before), edge = edge(after), inner = point(helper);
		assertNull(MixinLocalOriginProof.prove(handler, OWNER, before, oldPoint, after, edge), "constructor inputs alone differ");
		var roles = MixinLocalOriginProof.constructorRoles(handler, OWNER, before, oldPoint, after, edge, helper, inner);
		assertEquals(1, roles.size(), "the unchanged Envelope constructor must consume the capture in the same input position");
		assertEquals(Map.of(1, 3), MixinLocalOriginProof.prove(handler, OWNER, before, oldPoint, after, edge, roles));
	}

	@Test void aSameTypedValueNotPassedToTheAnchoredConsumerCannotUseTheRoleProof() {
		ClassNode original = roleMethod(false, false), current = roleMethod(true, true);
		MethodNode before = original.methods.getFirst(), after = current.methods.getFirst(), helper = current.methods.get(1);
		MethodNode handler = handler("original"); MethodInsnNode oldPoint = point(before), edge = edge(after), inner = point(helper);
		var roles = MixinLocalOriginProof.constructorRoles(handler, OWNER, before, oldPoint, after, edge, helper, inner);
		assertTrue(roles.isEmpty(), "having the right type in another local is not the consumer's captured input");
		assertNull(MixinLocalOriginProof.prove(handler, OWNER, before, oldPoint, after, edge, roles));
	}

	@Test void theProvedSlotIsAppliedAsAnExactLocalDiscriminator() {
		ClassNode mixin = new ClassNode(); mixin.name = "example/CaptureMixin";
		MethodNode handler = handler("original"); mixin.methods.add(handler);
		assertEquals(1, MixinRetarget.apply(mixin, new MixinRetarget.Plan(mixin.name, List.of(
				new MixinRetarget.Rewrite(handler.name + handler.desc, MixinRetarget.Element.LOCAL_INDEX, "1", "3", "native producer proof")))));
		AnnotationNode local = MixinStubRebind.sugar(handler, 1, MixinRetarget.LOCAL_SUGAR);
		assertEquals(3, MixinFit.value(local, "index")); assertNull(MixinFit.value(local, "name"));
		assertEquals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;" + TYPE + ")V", handler.desc);
	}

	private static Map<Integer, Integer> prove(MethodNode handler, MethodNode original, MethodNode current) {
		return MixinLocalOriginProof.prove(handler, OWNER, original, point(original), current, point(current));
	}
	private static MethodInsnNode point(MethodNode method) { return (MethodInsnNode) Arrays.stream(method.instructions.toArray())
			.filter(i -> i instanceof MethodInsnNode call && call.name.equals("anchor")).findFirst().orElseThrow(); }
	private static MethodInsnNode edge(MethodNode method) { return (MethodInsnNode) Arrays.stream(method.instructions.toArray())
			.filter(i -> i instanceof MethodInsnNode call && call.name.equals("helper")).findFirst().orElseThrow(); }
	@SuppressWarnings("unchecked")
	private static MethodNode handler(String name) {
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "capture", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;" + TYPE + ")V", null, null);
		handler.visibleAnnotations = List.of(annotation(MixinRetarget.INJECT, "method", List.of("run")));
		handler.invisibleParameterAnnotations = new List[2]; handler.invisibleParameterAnnotations[1] = List.of(annotation(MixinRetarget.LOCAL_SUGAR, "name", List.of(name)));
		return handler;
	}
	private static MethodNode method(int slot, String name, boolean changedConstructor, boolean duplicate) {
		ClassNode owner = new ClassNode(); owner.name = OWNER; owner.superName = "java/lang/Object"; owner.version = Opcodes.V21; owner.access = Opcodes.ACC_PUBLIC;
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null); owner.methods.add(method);
		LabelNode start = new LabelNode(), end = new LabelNode(); method.instructions.add(start);
		allocation(method, slot, changedConstructor);
		if (duplicate) allocation(method, slot + 1, false);
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "example/Action", "anchor", "()V", false));
		method.instructions.add(new InsnNode(Opcodes.RETURN)); method.instructions.add(end);
		method.localVariables = List.of(new LocalVariableNode(name, TYPE, null, start, end, slot));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); owner.accept(writer);
		ClassNode parsed = new ClassNode(); new ClassReader(writer.toByteArray()).accept(parsed, ClassReader.SKIP_FRAMES);
		return parsed.methods.getFirst();
	}
	private static void allocation(MethodNode method, int slot, boolean changedConstructor) {
		method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder")); method.instructions.add(new InsnNode(Opcodes.DUP));
		if (changedConstructor) method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 12));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", changedConstructor ? "(I)V" : "()V", false));
		method.instructions.add(new VarInsnNode(Opcodes.ASTORE, slot));
	}
	private static ClassNode roleMethod(boolean changed, boolean wrongArgument) {
		ClassNode owner = new ClassNode(); owner.name = OWNER; owner.superName = "java/lang/Object"; owner.version = Opcodes.V21; owner.access = Opcodes.ACC_PUBLIC;
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null); owner.methods.add(method);
		LabelNode start = new LabelNode(), end = new LabelNode(); method.instructions.add(start);
		int capture = changed ? 3 : 1, producer = changed ? 4 : 2;
		allocation(method, capture, changed);
		method.instructions.add(new TypeInsnNode(Opcodes.NEW, "example/Envelope")); method.instructions.add(new InsnNode(Opcodes.DUP));
		method.instructions.add(wrongArgument ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, capture));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "example/Envelope", "<init>", "(" + TYPE + ")V", false));
		method.instructions.add(new VarInsnNode(Opcodes.ASTORE, producer));
		if (changed) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, producer));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, changed ? OWNER : "example/Envelope", changed ? "helper" : "anchor",
				changed ? "(Lexample/Envelope;)V" : "()V", false));
		method.instructions.add(new InsnNode(Opcodes.RETURN)); method.instructions.add(end);
		method.localVariables = List.of(new LocalVariableNode(changed ? "changed" : "original", TYPE, null, start, end, capture));
		if (changed) {
			MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE, "helper", "(Lexample/Envelope;)V", null, null); owner.methods.add(helper);
			helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
					"example/Envelope", "anchor", "()V", false)); helper.instructions.add(new InsnNode(Opcodes.RETURN));
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); owner.accept(writer);
		ClassNode parsed = new ClassNode(); new ClassReader(writer.toByteArray()).accept(parsed, ClassReader.SKIP_FRAMES); return parsed;
	}
	private static AnnotationNode annotation(String descriptor, Object... values) { AnnotationNode node = new AnnotationNode(descriptor); node.values = new ArrayList<>(Arrays.asList(values)); return node; }
}
