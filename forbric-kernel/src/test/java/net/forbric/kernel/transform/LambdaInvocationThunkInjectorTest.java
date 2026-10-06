package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

@ResourceLock("system-properties")
@ExecutesInjector(LambdaInvocationThunkInjector.class)
class LambdaInvocationThunkInjectorTest {
	private static final String OWNER = "fixture/CallbackOwner";
	private static final Map<String, String> SOURCES = Map.of("fixture.CallbackOwner", """
			package fixture;
			import java.io.Serializable;
			import java.util.function.*;
			public class CallbackOwner implements Serializable {
			 private static final long serialVersionUID = 7;
			 public int calls;
			 public synchronized int compute(int value) {
			  calls++; if (value < 0) throw new IllegalArgumentException("negative input"); return value * 2;
			 }
			 private long longValue() { calls++; return 42L; }
			 public IntUnaryOperator bound() { return this::compute; }
			 public IntUnaryOperator captured(int offset) { return value -> compute(value + offset); }
			 public ToIntBiFunction<CallbackOwner,Integer> unbound() { return CallbackOwner::compute; }
			 public LongSupplier wideReturn() { return this::longValue; }
			 public IntUnaryOperator serializable() { return (IntUnaryOperator & Serializable) this::compute; }
			 public ToIntFunction<String> differentOwner() { return String::length; }
			 public IntUnaryOperator staticTarget() { return CallbackOwner::twice; }
			 private static int twice(int value) { return value * 2; }
			 public Supplier<CallbackOwner> constructorTarget() { return CallbackOwner::new; }
			}
			""", "fixture.Child", """
			package fixture;
			public class Child extends CallbackOwner {
			 @Override public int compute(int value) { calls++; return value + 100; }
			}
			""");

	@AfterEach void reset() { System.clearProperty(LambdaInvocationThunkInjector.PROPERTY); }

	@Test void actualBoundUnboundCapturedAndWideResultLambdasKeepValuesEffectsAndExceptions(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, SOURCES);
		Map<String, byte[]> changed = new TreeMap<>(original);
		byte[] transformed = InjectorExecution.transform(new LambdaInvocationThunkInjector(), "fixture.CallbackOwner", original.get(OWNER), EnvType.CLIENT);
		assertNotSame(original.get(OWNER), transformed);
		changed.put(OWNER, transformed);
		ClassLoader before = InjectorExecution.load(original), after = InjectorExecution.load(changed);
		assertEquals("", InjectorExecution.verify(transformed, after));
		assertEquals(exercise(before), exercise(after));
		ClassNode output = node(transformed);
		for (MethodNode method : output.methods) if (method.name.startsWith(LambdaInvocationThunkInjector.PREFIX)) {
			assertTrue(LambdaInvocationThunkInjector.isThunk(OWNER, method), method.name);
			assertNotEquals(method.name, LambdaInvocationThunkInjector.invocation(method).name, "the thunk must not recurse");
		}
	}

	@Test void virtualDispatchToASubclassStillHappensExactlyOnce(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, SOURCES), changed = new TreeMap<>(original);
		changed.put(OWNER, InjectorExecution.transform(new LambdaInvocationThunkInjector(), "fixture.CallbackOwner", original.get(OWNER), EnvType.CLIENT));
		for (Map<String, byte[]> bytes : List.of(original, changed)) {
			ClassLoader loader = InjectorExecution.load(bytes);
			Object child = InjectorExecution.construct(loader.loadClass("fixture.Child"));
			IntUnaryOperator bound = (IntUnaryOperator) InjectorExecution.invoke(child, "bound");
			assertEquals(105, bound.applyAsInt(5));
			assertEquals(1, child.getClass().getField("calls").getInt(child));
		}
	}

	@Test void serializationAndUnsupportedReferenceKindsStayOnTheirOriginalImplementation(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, SOURCES), changed = new TreeMap<>(original);
		byte[] output = InjectorExecution.transform(new LambdaInvocationThunkInjector(), "fixture.CallbackOwner", original.get(OWNER), EnvType.CLIENT);
		changed.put(OWNER, output);
		assertEquals(unsupportedHandles(node(original.get(OWNER))), unsupportedHandles(node(output)));
		byte[] before = serialized(InjectorExecution.load(original)), after = serialized(InjectorExecution.load(changed));
		assertArrayEquals(before, after, "serialized implementation identity and captured receiver must not change");
	}

	@Test void applyingTheTransformAgainDoesNotAddAnotherLayer(@TempDir Path work) throws Exception {
		byte[] input = InjectorExecution.compile(work, SOURCES).get(OWNER);
		LambdaInvocationThunkInjector transformer = new LambdaInvocationThunkInjector();
		byte[] once = transformer.transform("fixture.CallbackOwner", input, null);
		assertNotSame(input, once);
		assertSame(once, transformer.transform("fixture.CallbackOwner", once, null));
		System.setProperty(LambdaInvocationThunkInjector.PROPERTY, "off");
		assertSame(input, transformer.transform("fixture.CallbackOwner", input, null));
	}

	@Test void compilerExpressionBodiesKeepTheirOriginalHandlesForCaptureAndControlFlowProofs(@TempDir Path work) throws Exception {
		byte[] input = InjectorExecution.compile(work, SOURCES).get(OWNER);
		ClassNode before = node(input), after = node(new LambdaInvocationThunkInjector().transform("fixture.CallbackOwner", input, null));
		List<Handle> original = syntheticHandles(before), retained = syntheticHandles(after);
		assertFalse(original.isEmpty(), "premise: the captured expression has a compiler-generated implementation");
		assertEquals(original, retained, "adding a thunk must not replace the capture/control-flow implementation handle");
	}

	@Test void specialDispatchAndConstructorHandlesAreNotMaterialized() {
		for (int kind : List.of(Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL, Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKEINTERFACE)) {
			byte[] bytes = oneHandle(kind);
			assertSame(bytes, new LambdaInvocationThunkInjector().transform("fixture.Special", bytes, null), "reference kind " + kind);
		}
	}

	@Test void theActualHudReferencesAcquireExactSingleCallThunks() throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "actual merged game required");
		byte[] bytes;
		try (ZipFile zip = new ZipFile(jar.toFile())) { bytes = zip.getInputStream(zip.getEntry("net/minecraft/client/gui/Hud.class")).readAllBytes(); }
		ClassNode before = node(bytes), after = node(new LambdaInvocationThunkInjector().transform("net.minecraft.client.gui.Hud", bytes, null));
		List<Handle> original = ordinaryOwn(before), transformed = ordinaryOwn(after);
		assertFalse(original.isEmpty(), "premise: real HUD layers use own instance method references");
		assertEquals(original.size(), transformed.size());
		for (int i = 0; i < original.size(); i++) {
			Handle from = original.get(i), to = transformed.get(i);
			assertEquals(from.getDesc(), to.getDesc()); assertEquals(from.getTag(), to.getTag()); assertEquals(from.getOwner(), to.getOwner());
			MethodNode thunk = after.methods.stream().filter(m -> m.name.equals(to.getName()) && m.desc.equals(to.getDesc())).findFirst().orElseThrow();
			assertTrue(LambdaInvocationThunkInjector.isThunk(after.name, thunk));
			MethodInsnNode call = LambdaInvocationThunkInjector.invocation(thunk);
			assertEquals(from.getName(), call.name); assertEquals(from.getDesc(), call.desc); assertEquals(from.getOwner(), call.owner);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Object> exercise(ClassLoader loader) throws Throwable {
		Object receiver = InjectorExecution.construct(loader.loadClass("fixture.CallbackOwner"));
		IntUnaryOperator bound = (IntUnaryOperator) InjectorExecution.invoke(receiver, "bound");
		IntUnaryOperator captured = (IntUnaryOperator) InjectorExecution.invoke(receiver, "captured", 5);
		ToIntBiFunction<Object, Integer> unbound = (ToIntBiFunction<Object, Integer>) InjectorExecution.invoke(receiver, "unbound");
		LongSupplier wide = (LongSupplier) InjectorExecution.invoke(receiver, "wideReturn");
		List<Object> values = new ArrayList<>(List.of(bound.applyAsInt(3), captured.applyAsInt(2), unbound.applyAsInt(receiver, 4), wide.getAsLong()));
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> bound.applyAsInt(-1));
		values.add(failure.getMessage()); values.add(receiver.getClass().getField("calls").getInt(receiver));
		assertEquals(List.of(6, 14, 8, 42L, "negative input", 5), values);
		return values;
	}

	private static byte[] serialized(ClassLoader loader) throws Throwable {
		Object receiver = InjectorExecution.construct(loader.loadClass("fixture.CallbackOwner"));
		Object lambda = InjectorExecution.invoke(receiver, "serializable");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(lambda); }
		return bytes.toByteArray();
	}
	private static List<Handle> unsupportedHandles(ClassNode owner) {
		List<Handle> result = new ArrayList<>();
		for (MethodNode method : owner.methods) for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof InvokeDynamicInsnNode indy) {
			for (Object argument : indy.bsmArgs) if (argument instanceof Handle handle && (!indy.bsm.getName().equals("metafactory")
					|| handle.getTag() != Opcodes.H_INVOKEVIRTUAL || !handle.getOwner().equals(owner.name))) result.add(handle);
		}
		return result;
	}
	private static List<Handle> ordinaryOwn(ClassNode owner) {
		List<Handle> result = new ArrayList<>();
		for (MethodNode method : owner.methods) for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof InvokeDynamicInsnNode indy
				&& indy.bsm.getName().equals("metafactory") && indy.bsmArgs.length == 3 && indy.bsmArgs[1] instanceof Handle h
				&& h.getTag() == Opcodes.H_INVOKEVIRTUAL && h.getOwner().equals(owner.name)
				&& owner.methods.stream().anyMatch(m -> m.name.equals(h.getName()) && m.desc.equals(h.getDesc())
						&& ((m.access & Opcodes.ACC_SYNTHETIC) == 0 || h.getName().startsWith(LambdaInvocationThunkInjector.PREFIX)))) result.add(h);
		return result;
	}
	private static List<Handle> syntheticHandles(ClassNode owner) {
		List<Handle> result = new ArrayList<>();
		for (MethodNode method : owner.methods) for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof InvokeDynamicInsnNode indy) {
			for (Object argument : indy.bsmArgs) if (argument instanceof Handle handle && handle.getOwner().equals(owner.name)
					&& owner.methods.stream().anyMatch(m -> m.name.equals(handle.getName()) && m.desc.equals(handle.getDesc())
							&& (m.access & Opcodes.ACC_SYNTHETIC) != 0 && !m.name.startsWith(LambdaInvocationThunkInjector.PREFIX))) result.add(handle);
		}
		return result;
	}
	private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
	private static byte[] oneHandle(int kind) {
		ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/Special", null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "factory", "()Ljava/lang/Runnable;", null, null);
		method.visitCode();
		method.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
				Type.getMethodType("()V"), new Handle(kind, "fixture/Special", kind == Opcodes.H_NEWINVOKESPECIAL ? "<init>" : "run", "()V", kind == Opcodes.H_INVOKEINTERFACE), Type.getMethodType("()V"));
		method.visitInsn(Opcodes.ARETURN); method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
	}
}
