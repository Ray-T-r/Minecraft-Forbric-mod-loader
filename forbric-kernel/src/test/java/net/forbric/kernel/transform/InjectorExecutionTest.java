package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;

/** {@link InjectorExecution} itself: it must catch a broken class, and run a transformed one against shared hooks. */
class InjectorExecutionTest {
	private static final String GREETER = "fixture/Greeter";
	private static final String GREETER_SOURCE = """
			package fixture;
			public class Greeter {
				public static String greet() {
					return "hello";
				}
				public static void refuse() {
					throw new IllegalStateException("refused");
				}
			}
			""";

	/** The stand-in for a kernel hook: defined by the test's loader, so the transformed class and the test share it. */
	public static final class Probe {
		static int hits;

		public static void hit() {
			hits++;
		}
	}

	/** The smallest injector: a call to {@link Probe#hit} at the head of {@code greet}, and a reply naming the side. */
	private static final ClassTransformer PROBE_AT_HEAD = (name, bytes, context) -> {
		if (!name.equals("fixture.Greeter")) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (!method.name.equals("greet")) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof LdcInsnNode ldc && "hello".equals(ldc.cst)) ldc.cst = "hello from " + context.getEnvType();
			}
			method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,
					"net/forbric/kernel/transform/InjectorExecutionTest$Probe", "hit", "()V", false));
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	};

	@Test void verifyNamesTheMethodThatPopsAnEmptyStack() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/Broken", null, "java/lang/Object", null);
		MethodVisitor broken = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "broken", "()V", null, null);
		broken.visitCode();
		broken.visitInsn(Opcodes.POP);
		broken.visitInsn(Opcodes.RETURN);
		broken.visitMaxs(1, 0);
		broken.visitEnd();
		MethodVisitor fine = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fine", "()V", null, null);
		fine.visitCode();
		fine.visitInsn(Opcodes.RETURN);
		fine.visitMaxs(0, 0);
		fine.visitEnd();
		writer.visitEnd();

		String report = InjectorExecution.verify(writer.toByteArray(), null);
		assertTrue(report.startsWith("fixture/Broken.broken()V: "), report);
		assertTrue(report.contains("POP"), "the report must show the failing instruction: " + report);
		assertFalse(report.contains("fine()V"), "only the broken method is reported: " + report);
	}

	@Test void verifyResolvesTypesThroughTheGivenLoader(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, Map.of(
				"fixture.Base", "package fixture; public class Base {}",
				"fixture.Child", "package fixture; public class Child extends Base {}",
				"fixture.Use", "package fixture; public class Use { static Base up() { return new Child(); } }"));
		byte[] use = classes.get("fixture/Use");
		assertEquals("", InjectorExecution.verify(use, InjectorExecution.load(classes)));
		// Without the stand-ins, Child -> Base cannot be checked; the report says so instead of passing.
		String unresolved = InjectorExecution.verify(use, null);
		assertTrue(unresolved.startsWith("fixture/Use.up()Lfixture/Base;: "), "an unresolvable type must be reported: " + unresolved);
	}

	@Test void aTransformedClassRunsAgainstTheTestsOwnHook(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, Map.of("fixture.Greeter", GREETER_SOURCE));
		byte[] transformed = InjectorExecution.transform(PROBE_AT_HEAD, "fixture.Greeter", original.get(GREETER), EnvType.SERVER);
		assertNotSame(original.get(GREETER), transformed);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(GREETER, transformed);

		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(transformed, loader));
		Class<?> greeter = loader.loadClass("fixture.Greeter");
		assertSame(loader, greeter.getClassLoader());
		int before = Probe.hits;
		assertEquals("hello from SERVER", InjectorExecution.invokeStatic(greeter, "greet"));
		assertEquals(before + 1, Probe.hits, "the hook the transformed class called is not the test's");

		// The untransformed control, in its own loader: same source, no hook, the original reply.
		Class<?> control = InjectorExecution.load(original).loadClass("fixture.Greeter");
		assertEquals("hello", InjectorExecution.invokeStatic(control, "greet"));
		assertEquals(before + 1, Probe.hits);

		// A stand-in's own exception arrives unwrapped.
		assertEquals("refused", assertThrows(IllegalStateException.class,
				() -> InjectorExecution.invokeStatic(greeter, "refuse")).getMessage());
	}

	@Test void theCallHelpersReachPrivateAndInheritedMembers(@TempDir Path work) throws Throwable {
		ClassLoader loader = InjectorExecution.load(InjectorExecution.compile(work, Map.of(
				"fixture.Counter", """
						package fixture;
						class Counter {
							private static int made;
							private final int step;
							Counter(int step) { this.step = step; made++; }
							private int next(int from) { return from + step; }
						}
						""",
				"fixture.Pair", "package fixture; final class Pair extends Counter { private Pair() { super(2); } }")));
		Object pair = InjectorExecution.construct(loader.loadClass("fixture.Pair"));
		assertEquals(5, InjectorExecution.invoke(pair, "next", 3));
		assertEquals(1, InjectorExecution.getStatic(loader.loadClass("fixture.Counter"), "made"));
		assertThrows(IllegalArgumentException.class, () -> InjectorExecution.invoke(pair, "next", "three"),
				"an argument no overload accepts must say so, not call something else");
	}

	@Test void theLoaderAnswersItsOwnNamesBeforeTheTestClasspath() throws Exception {
		String shadowed = "net/forbric/kernel/transform/InjectorExecutionTest$Probe";
		byte[] bytes;
		try (var in = InjectorExecutionTest.class.getClassLoader().getResourceAsStream(shadowed + ".class")) {
			bytes = in.readAllBytes();
		}
		Class<?> copy = InjectorExecution.load(Map.of(shadowed, bytes)).loadClass(Probe.class.getName());
		assertNotSame(Probe.class, copy, "a name in the map must be defined by the execution loader, not the parent");
	}

	@Test void fixtureDependenciesStayInTheSuppliedClasspathAndParent(@TempDir Path work) throws Throwable {
		String originalClasspath = System.getProperty("java.class.path");
		Map<String, byte[]> dependency = InjectorExecution.compile(work, Map.of(
				"isolated_dependency.Base", "package isolated_dependency; public class Base {}"));
		Path library = work.resolve("library");
		for (var entry : dependency.entrySet()) {
			Path target = library.resolve(entry.getKey() + ".class");
			java.nio.file.Files.createDirectories(target.getParent());
			java.nio.file.Files.write(target, entry.getValue());
		}
		Map<String, String> source = Map.of("fixture.UseDependency", """
				package fixture;
				public class UseDependency {
					public static Object value() { return new isolated_dependency.Base(); }
				}
				""");
		assertThrows(AssertionError.class, () -> InjectorExecution.compile(work, source));
		Map<String, byte[]> classes = InjectorExecution.compile(work, source, java.util.List.of(library));
		ClassLoader parent = InjectorExecution.load(dependency);
		ClassLoader loader = InjectorExecution.load(classes, parent);
		assertEquals("", InjectorExecution.verify(classes.get("fixture/UseDependency"), loader));
		Object value = InjectorExecution.invokeStatic(loader.loadClass("fixture.UseDependency"), "value");
		assertSame(parent.loadClass("isolated_dependency.Base"), value.getClass());
		assertThrows(ClassNotFoundException.class,
				() -> InjectorExecutionTest.class.getClassLoader().loadClass("isolated_dependency.Base"));
		classes.putAll(dependency);
		ClassLoader shadowing = InjectorExecution.load(classes, parent);
		assertSame(shadowing, shadowing.loadClass("isolated_dependency.Base").getClassLoader());
		assertEquals(originalClasspath, System.getProperty("java.class.path"));
	}

	@Test void transformRefusesAnInternalNameThatWouldMatchNothing() {
		assertThrows(IllegalArgumentException.class,
				() -> InjectorExecution.transform(PROBE_AT_HEAD, GREETER, new byte[0], EnvType.CLIENT));
	}

	@Test void aSourceJavacRejectsFailsWithItsMessage(@TempDir Path work) {
		AssertionError rejected = assertThrows(AssertionError.class,
				() -> InjectorExecution.compile(work, Map.of("fixture.Bad", "package fixture; class Bad { int x = ; }")));
		assertTrue(rejected.getMessage().contains("Bad.java"), rejected.getMessage());
	}
}
