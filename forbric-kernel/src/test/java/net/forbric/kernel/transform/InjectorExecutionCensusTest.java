package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Which transform-chain injectors some test actually defines and runs the output of, read from the bytecode.
 *
 * <p>Two lists, both computed rather than typed in, so an injector cannot be added without this test noticing:
 * <ul>
 *   <li>every concrete {@link ClassTransformer} whose name ends in {@code Injector}, from the classes beside
 *       {@code ClassTransformer} itself (a directory under Gradle, a jar elsewhere);</li>
 *   <li>every test class carrying {@link ExecutesInjector}, from the classes beside this test.</li>
 * </ul>
 * Every injector is either executed by such a test or in {@link #NOT_EXECUTED_YET} with the reason. An injector in
 * both, an allowlist row or a claim that names no injector any more, and a claim the claiming class does not back
 * with code all fail. The allowlist only shrinks: a new execution test deletes its row here, and the diff shows it.
 */
class InjectorExecutionCensusTest {
	private static final String TRANSFORMER = Type.getInternalName(ClassTransformer.class);
	private static final String ANNOTATION = Type.getDescriptor(ExecutesInjector.class);
	private static final String EXECUTION = Type.getInternalName(InjectorExecution.class);
	private static final String WEAVE = "net/forbric/kernel/mixin/weave/WeaveHarness";

	private static final String NOT_CREDITED = "no test carrying @ExecutesInjector defines and runs its output yet";
	/**
	 * Only shrinks. Seeded with every injector; empty since each got a test carrying {@link ExecutesInjector} that
	 * passes the claim checks below, so a new injector arrives with one.
	 */
	static final Map<String, String> NOT_EXECUTED_YET = notExecutedYet();

	@Test void everyInjectorIsExecutedOrListedWithAReason() throws Exception {
		Map<String, String> injectors = injectors(classesBeside(ClassTransformer.class));
		assertTrue(injectors.size() >= 85, "the census could not read the injectors beside ClassTransformer: " + injectors.keySet());
		assertEquals(Type.getInternalName(ExitHookInjector.class), injectors.get("ExitHookInjector"),
				"a known injector is missing from the census");

		Map<String, byte[]> tests = classesBeside(InjectorExecutionCensusTest.class);
		assertTrue(tests.containsKey(Type.getInternalName(InjectorExecutionCensusTest.class)),
				"the census could not read the test classes beside itself");
		Map<String, Claim> claims = claims(tests);

		List<String> problems = new ArrayList<>(problems(injectors.keySet(), executed(claims, injectors), NOT_EXECUTED_YET.keySet()));
		for (Claim claim : claims.values()) problems.addAll(claim.problems(injectors));
		assertEquals(List.of(), problems);

		long executed = injectors.keySet().stream().filter(name -> !NOT_EXECUTED_YET.containsKey(name)).count();
		System.out.printf("injector execution coverage: %d/%d%n", executed, injectors.size());
	}

	/** The census must be able to see an undeclared injector, an overlap, a stale row and an unbacked claim. */
	@Test void theCensusCanFail() {
		// Made-up names: the live allowlist is empty, and a real one would break the day its injector is credited.
		Set<String> allowlisted = Set.of("ListedInjector");
		Set<String> injectors = new TreeSet<>(Set.of("ListedInjector", "RunInjector", "BrandNewInjector"));
		assertEquals(List.of("undeclared: BrandNewInjector"), problems(injectors, Set.of("RunInjector"), allowlisted));

		injectors.remove("BrandNewInjector");
		assertEquals(List.of("both executed and allowlisted: ListedInjector"),
				problems(injectors, Set.of("RunInjector", "ListedInjector"), allowlisted));

		Set<String> gone = new TreeSet<>(injectors);
		gone.remove("ListedInjector");
		assertEquals(List.of("allowlisted but no longer an injector: ListedInjector"),
				problems(gone, Set.of("RunInjector"), allowlisted));

		Map<String, String> known = Map.of("ExitHookInjector", "net/forbric/kernel/transform/ExitHookInjector");
		String exit = known.get("ExitHookInjector");
		assertEquals(List.of(), new Claim("t/Real", Set.of(exit), Set.of(exit), true).problems(known));
		assertEquals(List.of("t/Paper names ExitHookInjector in @ExecutesInjector but defines no class: it never calls "
				+ "InjectorExecution.load, WeaveHarness.run or a defineClass"), new Claim("t/Paper", Set.of(exit), Set.of(exit), false).problems(known));
		assertEquals(List.of("t/Paper names ExitHookInjector in @ExecutesInjector but no code in it references that class"),
				new Claim("t/Paper", Set.of(exit), Set.of(), true).problems(known));
		assertEquals(List.of("t/Stale names net/forbric/kernel/transform/MethodBodyNeuter in @ExecutesInjector, which is "
				+ "not a concrete *Injector ClassTransformer"),
				new Claim("t/Stale", Set.of("net/forbric/kernel/transform/MethodBodyNeuter"), Set.of(), true).problems(known));
	}

	/**
	 * The reader, over what javac really emits. With nothing credited yet the census's own pass reads no annotation at
	 * all, so without this a reader that saw nothing would pass forever.
	 */
	@Test void theCensusReadsWhatJavacEmits(@TempDir Path work) throws Exception {
		Map<String, byte[]> compiled = InjectorExecution.compile(work, Map.of(
				"net.forbric.kernel.transform.CensusClaimFixture", """
						package net.forbric.kernel.transform;
						@ExecutesInjector(ExitHookInjector.class)
						class CensusClaimFixture {
							void run() {
								Runnable later = () -> InjectorExecution.load(java.util.Map.of());
								new ExitHookInjector();
							}
						}
						""",
				"net.forbric.kernel.transform.CensusPaperFixture", """
						package net.forbric.kernel.transform;
						@ExecutesInjector({ExitHookInjector.class, LifecycleHookInjector.class})
						class CensusPaperFixture {
							Object run() {
								return LifecycleHookInjector.forServer();
							}
						}
						"""));
		Map<String, Claim> claims = claims(compiled);
		assertEquals(Set.of("net/forbric/kernel/transform/CensusClaimFixture", "net/forbric/kernel/transform/CensusPaperFixture"),
				claims.keySet());
		Map<String, String> injectors = injectors(classesBeside(ClassTransformer.class));
		Claim real = claims.get("net/forbric/kernel/transform/CensusClaimFixture");
		assertEquals(Set.of(injectors.get("ExitHookInjector")), real.claimed());
		// The load sits in a lambda body, a synthetic method of the same class.
		assertTrue(real.definesClasses(), "a load inside a lambda was not seen");
		assertEquals(List.of(), real.problems(injectors));
		assertEquals(Set.of("ExitHookInjector"), executed(Map.of("r", real), injectors));

		assertEquals(List.of(
				"net/forbric/kernel/transform/CensusPaperFixture names ExitHookInjector in @ExecutesInjector but defines no "
						+ "class: it never calls InjectorExecution.load, WeaveHarness.run or a defineClass",
				"net/forbric/kernel/transform/CensusPaperFixture names ExitHookInjector in @ExecutesInjector but no code in "
						+ "it references that class",
				"net/forbric/kernel/transform/CensusPaperFixture names LifecycleHookInjector in @ExecutesInjector but defines "
						+ "no class: it never calls InjectorExecution.load, WeaveHarness.run or a defineClass"),
				claims.get("net/forbric/kernel/transform/CensusPaperFixture").problems(injectors));
	}


	/** Running the real weaver defines output in its child JVM; merely building a fixture does not. */
	@Test void theCensusDistinguishesWeavingFromFixturePreparation(@TempDir Path work) throws Exception {
		Map<String, byte[]> compiled = InjectorExecution.compile(work, Map.of(
				"net.forbric.kernel.mixin.weave.CensusWeaveFixture", """
						package net.forbric.kernel.mixin.weave;
						@net.forbric.kernel.transform.ExecutesInjector(net.forbric.kernel.transform.ExitHookInjector.class)
						class CensusWeaveFixture {
							Object run() throws Exception {
								Class<?> injector = net.forbric.kernel.transform.ExitHookInjector.class;
								return WeaveHarness.run(java.nio.file.Path.of("."), "woven", java.nio.file.Path.of("fixture.jar"),
										"source.json", "fixture", net.forbric.api.Ecosystem.FABRIC, net.fabricmc.api.EnvType.SERVER,
										"fixture.Probe", "run", java.util.Map.of());
							}
						}
						""",
				"net.forbric.kernel.mixin.weave.CensusPreparedFixture", """
						package net.forbric.kernel.mixin.weave;
						@net.forbric.kernel.transform.ExecutesInjector(net.forbric.kernel.transform.ExitHookInjector.class)
						class CensusPreparedFixture {
							Object run() throws Exception {
								Class<?> injector = net.forbric.kernel.transform.ExitHookInjector.class;
								return WeaveHarness.fixture(java.nio.file.Path.of("."), "prepared", java.util.List.of(),
										java.util.Map.of(), java.util.List.of());
							}
						}
						"""));
		Map<String, Claim> claims = claims(compiled);
		Map<String, String> injectors = injectors(classesBeside(ClassTransformer.class));
		Claim woven = claims.get("net/forbric/kernel/mixin/weave/CensusWeaveFixture");
		assertTrue(woven.definesClasses());
		assertEquals(List.of(), woven.problems(injectors));
		Claim prepared = claims.get("net/forbric/kernel/mixin/weave/CensusPreparedFixture");
		assertFalse(prepared.definesClasses());
		assertEquals(1, prepared.problems(injectors).size(), "fixture preparation alone must not earn execution credit");
	}

	static List<String> problems(Set<String> injectors, Set<String> executed, Set<String> allowlisted) {
		List<String> problems = new ArrayList<>();
		for (String injector : injectors) {
			if (executed.contains(injector) && allowlisted.contains(injector)) problems.add("both executed and allowlisted: " + injector);
			else if (!executed.contains(injector) && !allowlisted.contains(injector)) problems.add("undeclared: " + injector);
		}
		for (String row : allowlisted) if (!injectors.contains(row)) problems.add("allowlisted but no longer an injector: " + row);
		return problems;
	}

	/**
	 * One class carrying {@link ExecutesInjector}: the internal names it claims, the types its code (and its nested and
	 * anonymous classes' code) references, and whether that code defines a class at all.
	 */
	record Claim(String test, Set<String> claimed, Set<String> referenced, boolean definesClasses) {
		/**
		 * An annotation is a promise about code, so the code is held to the two parts of it a class file can show: the
		 * injector is used, and something is defined. Neither proves the output ran; both are absent on a paper credit.
		 */
		List<String> problems(Map<String, String> injectors) {
			Map<String, String> byInternal = new HashMap<>();
			injectors.forEach((simple, internal) -> byInternal.put(internal, simple));
			List<String> problems = new ArrayList<>();
			for (String claim : new TreeSet<>(claimed)) {
				String injector = byInternal.get(claim);
				if (injector == null) {
					problems.add(test + " names " + claim + " in @ExecutesInjector, which is not a concrete *Injector ClassTransformer");
					continue;
				}
				if (!definesClasses) problems.add(test + " names " + injector + " in @ExecutesInjector but defines no class: "
						+ "it never calls InjectorExecution.load, WeaveHarness.run or a defineClass");
				if (!referenced.contains(claim)) problems.add(test + " names " + injector
						+ " in @ExecutesInjector but no code in it references that class");
			}
			return problems;
		}
	}

	/** The simple names of the injectors the claims credit. A claim naming a non-injector is reported by the claim. */
	static Set<String> executed(Map<String, Claim> claims, Map<String, String> injectors) {
		Set<String> executed = new TreeSet<>();
		for (var injector : injectors.entrySet()) {
			if (claims.values().stream().anyMatch(c -> c.claimed().contains(injector.getValue()))) executed.add(injector.getKey());
		}
		return executed;
	}

	/** simple name -> internal name of every concrete ClassTransformer named *Injector among {@code classes}. */
	static Map<String, String> injectors(Map<String, byte[]> classes) {
		Map<String, ClassNode> headers = new HashMap<>();
		Function<String, ClassNode> header = name -> headers.computeIfAbsent(name, n -> {
			byte[] bytes = classes.containsKey(n) ? classes.get(n) : resource(n);
			return bytes == null ? null : read(bytes, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		});
		Map<String, String> injectors = new TreeMap<>();
		for (String name : classes.keySet()) {
			String simple = name.substring(name.lastIndexOf('/') + 1);
			if (!simple.endsWith("Injector")) continue;
			ClassNode node = header.apply(name);
			if ((node.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0 || !isTransformer(name, header)) continue;
			String clash = injectors.put(simple, name);
			if (clash != null) fail("two injectors are both called " + simple + ": " + clash + " and " + name);
		}
		return injectors;
	}

	private static boolean isTransformer(String name, Function<String, ClassNode> header) {
		if (name.equals(TRANSFORMER)) return true;
		ClassNode node = header.apply(name);
		if (node == null) return false; // a JDK or library type: not ours, so not a ClassTransformer
		if (node.superName != null && isTransformer(node.superName, header)) return true;
		for (String parent : node.interfaces) if (isTransformer(parent, header)) return true;
		return false;
	}

	/** Every class among {@code classes} that carries {@link ExecutesInjector}, by internal name. */
	static Map<String, Claim> claims(Map<String, byte[]> classes) {
		Map<String, Claim> claims = new TreeMap<>();
		for (var entry : classes.entrySet()) {
			ClassNode node = read(entry.getValue(), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			if (node.visibleAnnotations == null) continue;
			for (AnnotationNode annotation : node.visibleAnnotations) {
				if (!annotation.desc.equals(ANNOTATION)) continue;
				Set<String> claimed = new TreeSet<>();
				for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2) {
					if (annotation.values.get(i).equals("value")) {
						for (Object type : (List<?>) annotation.values.get(i + 1)) claimed.add(((Type) type).getInternalName());
					}
				}
				claims.put(node.name, code(node.name, claimed, classes));
			}
		}
		return claims;
	}

	/** What the claiming class and every class nested in it do in code: types referenced, and any class defined. */
	private static Claim code(String test, Set<String> claimed, Map<String, byte[]> classes) {
		Set<String> referenced = new TreeSet<>();
		boolean defines = false;
		for (var entry : classes.entrySet()) {
			if (!entry.getKey().equals(test) && !entry.getKey().startsWith(test + "$")) continue;
			for (MethodNode method : read(entry.getValue(), 0).methods) {
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof MethodInsnNode call) {
						referenced.add(call.owner);
						defines |= definesClass(call.owner, call.name);
					} else if (insn instanceof TypeInsnNode type) {
						referenced.add(Type.getObjectType(type.desc).getSort() == Type.ARRAY
								? Type.getObjectType(type.desc).getElementType().getInternalName() : type.desc);
					} else if (insn instanceof FieldInsnNode field) {
						referenced.add(field.owner);
					} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT) {
						referenced.add(type.getInternalName());
					} else if (insn instanceof MultiANewArrayInsnNode array) {
						referenced.add(Type.getType(array.desc).getElementType().getInternalName());
					} else if (insn instanceof InvokeDynamicInsnNode indy) {
						for (Object arg : indy.bsmArgs) {
							if (arg instanceof Handle handle) {
								referenced.add(handle.getOwner());
								defines |= definesClass(handle.getOwner(), handle.getName());
							}
						}
					}
				}
			}
		}
		return new Claim(test, Set.copyOf(claimed), Set.copyOf(referenced), defines);
	}

	private static boolean definesClass(String owner, String name) {
		return owner.equals(EXECUTION) && name.equals("load") || owner.equals(WEAVE) && name.equals("run")
				|| name.equals("defineClass") || name.equals("defineHiddenClass");
	}

	/** Every class file in the directory or jar {@code anchor} was loaded from, by internal name. */
	static Map<String, byte[]> classesBeside(Class<?> anchor) throws IOException, URISyntaxException {
		CodeSource source = anchor.getProtectionDomain().getCodeSource();
		assertNotNull(source, anchor.getName() + " has no code source to census");
		Path location = Path.of(source.getLocation().toURI());
		Map<String, byte[]> classes = new TreeMap<>();
		if (Files.isDirectory(location)) {
			try (Stream<Path> walk = Files.walk(location)) {
				for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
					String name = location.relativize(file).toString().replace('\\', '/');
					classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
				}
			}
		} else {
			try (JarFile jar = new JarFile(location.toFile())) {
				for (JarEntry entry : jar.stream().toList()) {
					String name = entry.getName();
					if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
					try (InputStream in = jar.getInputStream(entry)) {
						classes.put(name.substring(0, name.length() - ".class".length()), in.readAllBytes());
					}
				}
			}
		}
		classes.keySet().removeIf(name -> name.endsWith("module-info") || name.endsWith("package-info"));
		assertFalse(classes.isEmpty(), "no class files under " + location);
		return classes;
	}

	private static byte[] resource(String internalName) {
		try (InputStream in = InjectorExecutionCensusTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
			return in == null ? null : in.readAllBytes();
		} catch (IOException unreadable) {
			throw new IllegalStateException(unreadable);
		}
	}

	private static ClassNode read(byte[] bytes, int flags) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, flags);
		return node;
	}

	private static Map<String, String> notExecutedYet(String... injectors) {
		Map<String, String> rows = new LinkedHashMap<>();
		for (String injector : injectors) rows.put(injector, NOT_CREDITED);
		return rows;
	}
}
