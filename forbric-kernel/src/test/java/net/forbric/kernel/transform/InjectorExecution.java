package net.forbric.kernel.transform;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.SimpleVerifier;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import net.fabricmc.api.EnvType;

/**
 * Runs an injector's OUTPUT: compile a tiny stand-in for the class it targets, transform it, verify it, define it, and
 * call it.
 *
 * <p>Most injector tests read the emitted bytecode back and check its shape. That proves the edit was made, not that
 * the edited class loads, links against the hook it calls, or does what the edit was for. This is the shared path for
 * a test that goes the rest of the way; such a test carries {@link ExecutesInjector}, and
 * {@code InjectorExecutionCensusTest} counts it.
 *
 * <p>Needs no game jar: the stand-ins are compiled here from source, so a test built on this runs on a fresh clone and
 * never skips. A missing compiler fails.
 */
public final class InjectorExecution {
	/** {@code -proc:none}: the sponge-mixin jar on the test classpath registers an annotation processor. */
	private static final List<String> JAVAC = List.of("-proc:none", "--release", "21");

	private InjectorExecution() {
	}

	/**
	 * Compiles inline sources and returns every class javac wrote, by internal name, in a map the caller may edit.
	 *
	 * @param sources the top-level class's binary name ({@code net.minecraft.server.MinecraftServer}) or a relative
	 *                path ending in {@code .java}, to its source text
	 */
	public static Map<String, byte[]> compile(Path work, Map<String, String> sources) throws IOException {
		return compile(work, sources, List.of());
	}

	/** Additional dependencies belong to this fixture, rather than every test in the JVM. */
	public static Map<String, byte[]> compile(Path work, Map<String, String> sources, List<Path> dependencies) throws IOException {
		Path root = Files.createTempDirectory(Files.createDirectories(work), "src-");
		List<Path> files = new ArrayList<>();
		for (var source : new TreeMap<>(sources).entrySet()) {
			String key = source.getKey();
			Path file = root.resolve(key.endsWith(".java") ? key : key.replace('.', '/') + ".java");
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
			files.add(file);
		}
		return compile(work, files, dependencies);
	}

	/** Compiles source files against the test's own classpath; returns every class written, by internal name. */
	public static Map<String, byte[]> compile(Path work, List<Path> sources) throws IOException {
		return compile(work, sources, List.of());
	}

	public static Map<String, byte[]> compile(Path work, List<Path> sources, List<Path> dependencies) throws IOException {
		if (sources.isEmpty()) throw new IllegalArgumentException("nothing to compile");
		var javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new AssertionError("no system Java compiler: run the tests on a JDK, not a JRE");

		Path classes = Files.createTempDirectory(Files.createDirectories(work), "classes-");
		List<String> args = new ArrayList<>(JAVAC);
		// The test's classpath, so a stand-in may name a kernel type the way the real game class would.
		String classpath = Stream.concat(Stream.of(System.getProperty("java.class.path")),
				dependencies.stream().map(path -> path.toAbsolutePath().toString()))
				.collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
		args.addAll(List.of("-cp", classpath, "-d", classes.toString()));
		// A Writer, not run()'s byte stream: javac's messages follow the system language, and a zh run must still read.
		StringWriter errors = new StringWriter();
		boolean compiled;
		try (var files = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			compiled = javac.getTask(errors, files, null, args, null, files.getJavaFileObjectsFromPaths(sources)).call();
		}
		if (!compiled) throw new AssertionError("javac rejected " + sources + ":\n" + errors);

		Map<String, byte[]> out = new TreeMap<>();
		try (Stream<Path> walk = Files.walk(classes)) {
			for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
				String name = classes.relativize(file).toString().replace('\\', '/');
				out.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
			}
		}
		return out;
	}

	/**
	 * Runs one transformer over one class the way {@link TransformChain} calls it: the four-argument {@code transform},
	 * a context for {@code side}, and {@code null} read as "unchanged".
	 *
	 * <p>The context is the one {@code KernelBoot} builds (not development, namespace {@code named}). The result is the
	 * input array when the transformer made no edit, so {@code assertNotSame(bytes, result)} is how a test proves it
	 * matched at all.
	 *
	 * @param binaryName the dotted name the chain passes, not the internal one
	 */
	public static byte[] transform(ClassTransformer transformer, String binaryName, byte[] bytes, EnvType side) {
		// Every injector compares against the dotted name; an internal one would quietly match nothing.
		if (binaryName.indexOf('/') >= 0) throw new IllegalArgumentException("a binary name, not an internal one: " + binaryName);
		byte[] result = transformer.transform(binaryName, bytes, new TransformContext(side, false, "named"),
				ClassTransformer.ClaimReporter.NONE);
		return result == null ? bytes : result;
	}

	/**
	 * ASM's verifier over one class: the structural checks of {@link CheckClassAdapter}, then the same per-method data
	 * flow analysis its {@code verify} runs, with types resolved through {@code resolver}.
	 *
	 * <p>Returns an empty string when the class verifies, else one line per failing method naming the method, the
	 * instruction and its source line. ASM does not check stack map frames; defining and running the class does.
	 *
	 * @param resolver where the referenced types are loaded from to check assignments, usually the loader from
	 *                 {@link #load}; {@code null} means the test's own
	 */
	public static String verify(byte[] bytes, ClassLoader resolver) {
		ClassNode node = new ClassNode();
		try {
			new ClassReader(bytes).accept(new CheckClassAdapter(node, false), 0);
		} catch (RuntimeException malformed) {
			return "malformed class: " + malformed;
		}
		Type superType = node.superName == null ? null : Type.getObjectType(node.superName);
		List<Type> interfaces = node.interfaces.stream().map(Type::getObjectType).toList();
		boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;

		List<String> report = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
			SimpleVerifier verifier = new SimpleVerifier(Type.getObjectType(node.name), superType, interfaces, isInterface);
			verifier.setClassLoader(resolver != null ? resolver : InjectorExecution.class.getClassLoader());
			try {
				new Analyzer<BasicValue>(verifier).analyze(node.name, method);
			} catch (AnalyzerException rejected) {
				report.add(node.name + "." + method.name + method.desc + ": " + rejected.getMessage() + where(rejected.node));
			}
		}
		return String.join("\n", report);
	}

	/**
	 * Defines {@code classes} (internal name to bytes) in a fresh loader that answers those names itself and asks the
	 * test's loader for everything else.
	 *
	 * <p>Child-first for exactly these names, so a stand-in wins over a same-named class on the test classpath. Parent
	 * for the rest, so the kernel hook an injector calls is the test's own class: what the hook records in its static
	 * state, the test reads back.
	 */
	public static ClassLoader load(Map<String, byte[]> classes) {
		return load(classes, InjectorExecution.class.getClassLoader());
	}

	/** Keep transformed fixtures child-first while resolving their actual dependencies in an isolated parent. */
	public static ClassLoader load(Map<String, byte[]> classes, ClassLoader parent) {
		Map<String, byte[]> byBinaryName = new TreeMap<>();
		for (var entry : classes.entrySet()) {
			if (entry.getKey().indexOf('.') >= 0) throw new IllegalArgumentException("an internal name, not a binary one: " + entry.getKey());
			byBinaryName.put(entry.getKey().replace('/', '.'), entry.getValue().clone());
		}
		return new ChildFirst(byBinaryName, parent);
	}

	/** Calls the one static method {@code name} of {@code owner} that accepts {@code args}; its throw is rethrown as is. */
	public static Object invokeStatic(Class<?> owner, String name, Object... args) throws Throwable {
		return call(pick(owner, name, true, args), null, args);
	}

	/** Calls the one instance method {@code name} of {@code receiver} (own or inherited) that accepts {@code args}. */
	public static Object invoke(Object receiver, String name, Object... args) throws Throwable {
		return call(pick(receiver.getClass(), name, false, args), receiver, args);
	}

	/** Calls the one constructor of {@code type} that accepts {@code args}. */
	public static Object construct(Class<?> type, Object... args) throws Throwable {
		List<Executable> matches = new ArrayList<>();
		for (Constructor<?> constructor : type.getDeclaredConstructors()) {
			if (accepts(constructor.getParameterTypes(), args)) matches.add(constructor);
		}
		return call(only(matches, type.getName() + ".<init>", args), null, args);
	}

	/** Reads a static field of {@code owner}, at any access level; the usual way a stand-in reports what ran. */
	public static Object getStatic(Class<?> owner, String field) throws ReflectiveOperationException {
		Field f = owner.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(null);
	}

	private static Executable pick(Class<?> type, String name, boolean wantStatic, Object[] args) {
		List<Executable> matches = new ArrayList<>();
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (Method method : c.getDeclaredMethods()) {
				if (method.getName().equals(name) && !method.isBridge() && Modifier.isStatic(method.getModifiers()) == wantStatic
						&& accepts(method.getParameterTypes(), args)
						&& matches.stream().noneMatch(m -> overrides(m, method))) {
					matches.add(method);
				}
			}
			if (wantStatic) break; // a static method is called on the class that declares it
		}
		return only(matches, type.getName() + "." + name, args);
	}

	private static boolean overrides(Executable subclassMethod, Method superMethod) {
		return java.util.Arrays.equals(subclassMethod.getParameterTypes(), superMethod.getParameterTypes());
	}

	private static Executable only(List<Executable> matches, String what, Object[] args) {
		if (matches.size() == 1) return matches.get(0);
		throw new IllegalArgumentException((matches.isEmpty() ? "nothing" : "more than one") + " named " + what
				+ " accepts " + java.util.Arrays.toString(args) + (matches.isEmpty() ? "" : ": " + matches));
	}

	private static boolean accepts(Class<?>[] parameters, Object[] args) {
		if (parameters.length != args.length) return false;
		for (int i = 0; i < parameters.length; i++) {
			Class<?> p = parameters[i].isPrimitive() ? boxed(parameters[i]) : parameters[i];
			if (args[i] == null ? parameters[i].isPrimitive() : !p.isInstance(args[i])) return false;
		}
		return true;
	}

	private static Class<?> boxed(Class<?> primitive) {
		return java.lang.invoke.MethodType.methodType(primitive).wrap().returnType();
	}

	private static Object call(Executable target, Object receiver, Object[] args) throws Throwable {
		target.setAccessible(true);
		try {
			return target instanceof Method method ? method.invoke(receiver, args) : ((Constructor<?>) target).newInstance(args);
		} catch (InvocationTargetException thrown) {
			// The stand-in's own exception, not reflection's wrapper, so assertThrows reads like a direct call.
			throw thrown.getCause();
		}
	}

	private static String where(AbstractInsnNode insn) {
		if (insn == null) return "";
		Textifier text = new Textifier();
		insn.accept(new TraceMethodVisitor(text));
		StringWriter printed = new StringWriter();
		text.print(new PrintWriter(printed));
		String at = " [at " + printed.toString().strip() + "]";
		for (AbstractInsnNode p = insn; p != null; p = p.getPrevious()) {
			if (p instanceof LineNumberNode line) return at + " line " + line.line;
		}
		return at;
	}

	private static final class ChildFirst extends ClassLoader {
		static {
			registerAsParallelCapable();
		}

		private final Map<String, byte[]> classes;

		ChildFirst(Map<String, byte[]> classes, ClassLoader parent) {
			super("injector-execution", parent);
			this.classes = classes;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> defined = findLoadedClass(name);
				if (defined == null) defined = defineClass(name, bytes, 0, bytes.length);
				if (resolve) resolveClass(defined);
				return defined;
			}
		}

		/** The defined bytes, for code under test that reads a class file through its loader. */
		@Override
		public InputStream getResourceAsStream(String name) {
			if (name.endsWith(".class")) {
				byte[] bytes = classes.get(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
				if (bytes != null) return new ByteArrayInputStream(bytes);
			}
			return super.getResourceAsStream(name);
		}
	}
}
