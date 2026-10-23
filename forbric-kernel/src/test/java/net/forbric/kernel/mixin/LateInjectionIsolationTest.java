package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import net.forbric.kernel.ExpectCaught;

/**
 * {@link LateInjectionIsolation}'s install, on Mixin's real extension registry and the kernel's real MixinExtras: where
 * it goes, the queue shape it agrees to read, and that every way of not installing leaves both lists as they were; and
 * that a queue entry it does not read is left where it is for the applicator. What it does to a woven class is
 * {@code LateInjectionIsolationWeaveTest}'s.
 */
class LateInjectionIsolationTest {
	private URLClassLoader mixinExtras;

	@BeforeEach void loadMixinExtras() throws Exception {
		String jar = System.getProperty("forbric.mixinExtrasForTests", "");
		assertFalse(jar.isBlank(), "the test task supplies the kernel's MixinExtras jar");
		mixinExtras = new URLClassLoader(new URL[] {Path.of(jar).toUri().toURL()}, getClass().getClassLoader());
	}

	@AfterEach void close() throws Exception {
		mixinExtras.close();
	}

	@Test void theQueueOfTheKernelsMixinExtrasIsUnderstood() throws Exception {
		Class<?> applicator = Class.forName(LateInjectionIsolation.APPLICATOR, false, mixinExtras);
		Type declared = applicator.getDeclaredField(LateInjectionIsolation.QUEUE_FIELD).getGenericType();
		assertTrue(LateInjectionIsolation.queueShapeUnderstood(declared), declared.getTypeName());
	}

	@SuppressWarnings({"unused", "rawtypes"})
	private static final class Declared {
		Map<ITargetClassContext, Map<String, List<Runnable[]>>> asWritten;
		ConcurrentMap<ITargetClassContext, LinkedHashMap<String, ArrayList<Runnable[]>>> subtypes;
		Map<ITargetClassContext, Map<String, List<Runnable>>> notPairs;
		Map<ITargetClassContext, Map<String, List<Object[]>>> otherSteps;
		Map<Object, Map<String, List<Runnable[]>>> otherKey;
		Map<ITargetClassContext, Map<Object, List<Runnable[]>>> otherType;
		Map<ITargetClassContext, List<Runnable[]>> flat;
		Map<ITargetClassContext, Map<String, Iterable<Runnable[]>>> notAList;
		Map raw;
	}

	@Test void onlyTheShapeItReadsIsAgreedTo() throws Exception {
		for (String field : List.of("asWritten", "subtypes")) {
			assertTrue(LateInjectionIsolation.queueShapeUnderstood(Declared.class.getDeclaredField(field).getGenericType()), field);
		}
		for (String field : List.of("notPairs", "otherSteps", "otherKey", "otherType", "flat", "notAList", "raw")) {
			assertFalse(LateInjectionIsolation.queueShapeUnderstood(Declared.class.getDeclaredField(field).getGenericType()), field);
		}
	}

	/** Another of Mixin's extensions, as MixinExtras' own and the kernel's sit around the applicator. */
	private static final class Neighbour implements IExtension {
		@Override public boolean checkActive(MixinEnvironment environment) { return true; }
		@Override public void preApply(ITargetClassContext context) { }
		@Override public void postApply(ITargetClassContext context) { }
		@Override public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) { }
	}

	@Test void itGoesDirectlyInFrontOfTheApplicatorInBothLists() throws Exception {
		Extensions extensions = new Extensions(null);
		IExtension before = new Neighbour();
		IExtension applicator = (IExtension) Class.forName(LateInjectionIsolation.APPLICATOR, true, mixinExtras)
				.getDeclaredConstructor().newInstance();
		IExtension after = new Neighbour();
		extensions.add(before);
		extensions.add(applicator);
		extensions.add(after);
		extensions.select(null);

		assertTrue(LateInjectionIsolation.install(extensions, mixinExtras));
		for (List<IExtension> list : List.of(extensions.getExtensions(), extensions.getActiveExtensions())) {
			assertEquals(4, list.size(), String.valueOf(list));
			assertSame(before, list.get(0));
			assertInstanceOf(LateInjectionIsolation.class, list.get(1));
			assertSame(applicator, list.get(2));
			assertSame(after, list.get(3));
		}
	}

	@Test void withoutTheApplicatorNothingChanges() throws Exception {
		Extensions extensions = new Extensions(null);
		IExtension only = new Neighbour();
		extensions.add(only);
		extensions.select(null);

		assertFalse(LateInjectionIsolation.install(extensions, mixinExtras));
		assertEquals(List.of(only), extensions.getExtensions());
		assertEquals(List.of(only), extensions.getActiveExtensions());
	}

	/** A MixinExtras whose queue holds something else: not installed, nothing changed, nothing to crash on later. */
	@Test void aQueueOfAnotherShapeIsNotReadAndNothingChanges() throws Exception {
		Extensions extensions = new Extensions(null);
		IExtension only = new Neighbour();
		extensions.add(only);
		extensions.select(null);
		String said;
		try (OtherShape other = new OtherShape(getClass().getClassLoader(), SINGLE_STEPS)) {
			assertSame(other, Class.forName(LateInjectionIsolation.APPLICATOR, false, other).getClassLoader(),
					"the applicator install reads must be the one of another shape");
			java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
			java.io.PrintStream was = System.err;
			System.setErr(new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8));
			try {
				assertFalse(LateInjectionIsolation.install(extensions, other));
			} finally {
				System.setErr(was);
			}
			said = err.toString(java.nio.charset.StandardCharsets.UTF_8);
		}
		assertTrue(said.contains(LateInjectionIsolation.QUEUE_FIELD + " is declared java.util.Map<") && said.contains("not installed"),
				"refused for its shape, and said so: " + said);
		assertEquals(List.of(only), extensions.getExtensions());
		assertEquals(List.of(only), extensions.getActiveExtensions());
	}

	/**
	 * A queue field whose Signature attribute does not parse: reading its generic type throws GenericSignatureFormatError,
	 * an Error. Not installed, nothing changed, and the start of the game goes on.
	 */
	@ExpectCaught("GenericSignatureFormatError")
	@Test void aQueueWhoseSignatureDoesNotParseIsNotReadAndNothingChanges() throws Exception {
		Extensions extensions = new Extensions(null);
		IExtension only = new Neighbour();
		extensions.add(only);
		extensions.select(null);
		try (OtherShape other = new OtherShape(getClass().getClassLoader(), "Ljava/util/Map<Lorg/spongepowered/asm/mixin/")) {
			assertFalse(LateInjectionIsolation.install(extensions, other));
		}
		assertEquals(List.of(only), extensions.getExtensions());
		assertEquals(List.of(only), extensions.getActiveExtensions());
	}

	/** A target class context and nothing more: the queue is keyed by it. */
	private static final class Context implements ITargetClassContext {
		@Override public ClassInfo getClassInfo() { return null; }
		@Override public ClassNode getClassNode() { return null; }
	}

	/**
	 * Installed on the kernel's MixinExtras, then handed queue entries that are not lists of {lateInject, latePostInject}
	 * pairs: each is left in the queue as it was, for the applicator, none of its steps is run here, and that is said
	 * once. A well-formed entry beside them is still taken and run, inject then post-inject.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test void aQueueEntryOfAnotherShapeIsLeftForTheApplicatorAndSaidOnce() throws Exception {
		Extensions extensions = new Extensions(null);
		Class<?> applicatorType = Class.forName(LateInjectionIsolation.APPLICATOR, true, mixinExtras);
		extensions.add((IExtension) applicatorType.getDeclaredConstructor().newInstance());
		extensions.select(null);
		assertTrue(LateInjectionIsolation.install(extensions, mixinExtras));
		IExtension isolation = extensions.getActiveExtensions().get(0);
		assertInstanceOf(LateInjectionIsolation.class, isolation);
		Field queueField = applicatorType.getDeclaredField(LateInjectionIsolation.QUEUE_FIELD);
		queueField.setAccessible(true);
		Map queue = (Map) queueField.get(null);

		AtomicInteger strayRuns = new AtomicInteger();
		Runnable stray = strayRuns::incrementAndGet;
		Map<String, Object> unreadable = new LinkedHashMap<>();
		unreadable.put("single steps", Map.of("ModifyExpressionValue", List.of(stray)));
		unreadable.put("three steps", Map.of("ModifyExpressionValue", List.of((Object) new Runnable[] {stray, stray, stray})));
		unreadable.put("a missing step", Map.of("ModifyExpressionValue", List.of((Object) new Runnable[] {stray, null})));
		unreadable.put("not a list", Map.of("ModifyExpressionValue", Map.of()));
		unreadable.put("not a map", List.of());
		Map<String, Context> contexts = new LinkedHashMap<>();
		List<String> ran = new ArrayList<>();
		Context wellFormed = new Context();
		Map<String, List<Runnable[]>> pairs = new LinkedHashMap<>();
		List<Runnable[]> steps = new ArrayList<>();
		steps.add(new Runnable[] {() -> ran.add("inject"), () -> ran.add("post")});
		pairs.put("WrapOperation", steps);

		Map<String, Object> leftInQueue = new LinkedHashMap<>();
		boolean wellFormedLeft;
		java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
		java.io.PrintStream was = System.err;
		System.setErr(new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8));
		try {
			for (var entry : unreadable.entrySet()) {
				Context context = new Context();
				contexts.put(entry.getKey(), context);
				queue.put(context, entry.getValue());
			}
			queue.put(wellFormed, pairs);
			for (Context context : contexts.values()) isolation.postApply(context);
			isolation.postApply(wellFormed);
			for (var context : contexts.entrySet()) leftInQueue.put(context.getKey(), queue.get(context.getValue()));
			wellFormedLeft = queue.containsKey(wellFormed);
		} finally {
			System.setErr(was);
			for (Context context : contexts.values()) queue.remove(context);
			queue.remove(wellFormed);
		}
		String said = err.toString(java.nio.charset.StandardCharsets.UTF_8);

		for (var entry : unreadable.entrySet()) {
			assertSame(entry.getValue(), leftInQueue.get(entry.getKey()), entry.getKey() + " was not left in the queue");
		}
		assertEquals(0, strayRuns.get(), "a step of an entry the isolation does not read was run");
		String warning = "is not the lists of step pairs the late-injection isolation reads";
		assertEquals(1, said.split(java.util.regex.Pattern.quote(warning), -1).length - 1, "said once: " + said);
		assertEquals(List.of("inject", "post"), ran, "the well-formed entry is still taken and run");
		assertFalse(wellFormedLeft, "the well-formed entry is taken out of the queue");
	}

	/** Defines an applicator of MixinExtras' name whose queue field carries {@code signature}. */
	private static final String SINGLE_STEPS = "Ljava/util/Map<Lorg/spongepowered/asm/mixin/transformer/ext/ITargetClassContext;"
			+ "Ljava/util/Map<Ljava/lang/String;Ljava/util/List<Ljava/lang/Runnable;>;>;>;";

	private static final class OtherShape extends URLClassLoader {
		private final String signature;

		OtherShape(ClassLoader parent, String signature) {
			super(new URL[0], parent);
			this.signature = signature;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (!name.equals(LateInjectionIsolation.APPLICATOR)) throw new ClassNotFoundException(name);
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
			writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, LateInjectionIsolation.QUEUE_FIELD,
					"Ljava/util/Map;", signature, null).visitEnd();
			writer.visitEnd();
			byte[] bytes = writer.toByteArray();
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
