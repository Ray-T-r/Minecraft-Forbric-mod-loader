package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.ClassTransformer;

/**
 * Weaves guest-style mixins into fixture classes with the REAL Mixin and Forbric's real pipeline, then runs them.
 *
 * <p>Until this existed no test in the suite bootstrapped Mixin: every mixin adapter was tested on the ClassNode
 * it rewrote, and {@code FinalMixinApplications} on hand-written "woven" classes. What a guest mixin actually does
 * to its target after the adapters, Mixin and the post-Mixin stages — the output — was checked only by gates that
 * need the staged game jars and never run in CI. This needs no Minecraft jar: the fixtures are tiny classes
 * compiled here, so it runs on a fresh clone, and it never skips — a missing compiler or MixinExtras jar fails.
 *
 * <p>Each {@link #run} is one child JVM ({@link WeaveHarnessMain}) because Mixin's bootstrap is one-shot per JVM
 * and leaves global state behind. A run and its {@code =off} control differ only in system properties, which is
 * also how the gates control their runs.
 */
final class WeaveHarness {
	/** {@code -proc:none} is not optional: the sponge-mixin and MixinExtras jars both register annotation processors. */
	private static final List<String> JAVAC = List.of("-proc:none", "--release", "21", "-nowarn");
	private static final long TIMEOUT_SECONDS = 120;

	private WeaveHarness() {
	}

	/** What one child run printed and recorded. */
	record Result(String label, int exit, String output, List<Finding> findings, Path dir) {
		boolean printed(String line) {
			return output.contains(line);
		}

		/**
		 * Whether the child printed {@code line} as a whole line. {@link #printed} matches any substring, so a probe result
		 * that only gains a suffix — a guard removed, and {@code widened=look:golem} becomes {@code widened=look:golem,fix}
		 * — still matches it; a predicate that must tell those apart uses this.
		 */
		boolean printedLine(String line) {
			return output.lines().anyMatch(printedLine -> printedLine.stripTrailing().equals(line));
		}

		/** The woven bytes of {@code internalName}, exactly as ForbricClassLoader defined them. */
		byte[] defined(String internalName) throws IOException {
			Path evidence = dir.resolve("defined");
			try (Stream<Path> sessions = Files.list(evidence)) {
				List<Path> found = sessions.filter(p -> p.getFileName().toString().startsWith("definitions-")).toList();
				assertEquals(1, found.size(), "one evidence session per run: " + found);
				Path session = found.get(0);
				assertTrue(Files.exists(session.resolve("intact")), "evidence session lost a record: " + session);
				for (String row : Files.readAllLines(session.resolve("definitions.tsv"))) {
					if (row.startsWith("#")) continue;
					String[] cells = row.split("\t");
					if (cells[0].equals(internalName)) return Files.readAllBytes(session.resolve("blobs").resolve(cells[1] + ".class"));
				}
			}
			throw new AssertionError(internalName + " was not defined in run " + label);
		}

		/** ASM's verifier over the woven class; empty means it verifies. Failure messages carry this. */
		String verify(String internalName, ClassLoader resolver) throws IOException {
			StringWriter report = new StringWriter();
			CheckClassAdapter.verify(new ClassReader(defined(internalName)), resolver, false, new PrintWriter(report));
			return report.toString().strip();
		}

		String describe() {
			return "run " + label + " (exit " + exit + ", evidence " + dir + "):\n" + output;
		}
	}

	/** One CompatibilityFindings row from the child. */
	record Finding(String id, String modId, String confidence, boolean required, String source, String detail) {
		boolean confirmedRequired() {
			return "CONFIRMED".equals(confidence) && required;
		}
	}

	/** Compiles {@code sources} and packs the classes plus {@code resources} into a real jar under {@code work}. */
	static Path fixture(Path work, String name, List<Path> sources, Map<String, Path> resources) throws IOException {
		return fixture(work, name, sources, resources, List.of());
	}

	/**
	 * As above, with {@code javacOptions} after the harness's own: {@code -g} when a scenario needs the
	 * LocalVariableTable a mod's release build carries and javac's default leaves out.
	 */
	static Path fixture(Path work, String name, List<Path> sources, Map<String, Path> resources, List<String> javacOptions)
			throws IOException {
		Path classes = Files.createDirectories(work.resolve(name + "-classes"));
		List<String> args = new ArrayList<>(JAVAC);
		args.addAll(javacOptions);
		args.addAll(List.of("-cp", compileClasspath(), "-d", classes.toString()));
		for (Path source : sources) args.add(source.toString());
		StringWriter errors = new StringWriter();
		var javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new AssertionError("no system Java compiler: run the tests on a JDK, not a JRE");
		int code = javac.run(null, null, new WriterOutputStream(errors), args.toArray(String[]::new));
		assertEquals(0, code, "fixture " + name + " did not compile:\n" + errors);

		Path jar = work.resolve(name + ".jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
				Stream<Path> walk = Files.walk(classes)) {
			for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
				out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
				out.write(Files.readAllBytes(file));
				out.closeEntry();
			}
			for (var resource : new LinkedHashMap<>(resources).entrySet()) {
				out.putNextEntry(new JarEntry(resource.getKey()));
				out.write(Files.readAllBytes(resource.getValue()));
				out.closeEntry();
			}
		}
		return jar;
	}

	/** One mixin config of a run and the mod that declared it, as KernelBoot publishes it to MixinConfigOwners. */
	record Config(String name, String modId, Ecosystem ecosystem) {
	}

	/** One child JVM: bootstrap Mixin on {@code fixture} with one config, call {@code probeClass.probeMethod()}, record findings. */
	static Result run(Path work, String label, Path fixture, String config, String modId, Ecosystem ecosystem,
			EnvType side, String probeClass, String probeMethod, Map<String, String> properties)
			throws IOException, InterruptedException {
		return run(work, label, fixture, List.of(new Config(config, modId, ecosystem)), List.of(), side, probeClass,
				probeMethod, properties);
	}

	/**
	 * One child JVM as a boot runs it: {@code chain} installed on the loader first, each transformer registered as
	 * KernelBoot registers it ({@link KernelBootChain}); then {@code configs} published and registered in KernelBoot's
	 * order, the Fabric ones as given with the Forge family's appended; Mixin up on {@code side}; then the probe.
	 */
	static Result run(Path work, String label, Path fixture, List<Config> configs,
			List<Class<? extends ClassTransformer>> chain, EnvType side, String probeClass, String probeMethod,
			Map<String, String> properties) throws IOException, InterruptedException {
		assertFalse(configs.isEmpty(), "a weave run needs at least one mixin config");
		Path dir = Files.createDirectories(work.resolve("run-" + label));
		List<String> argfile = new ArrayList<>();
		argfile.add("-cp");
		argfile.add(quote(childClasspath()));
		argfile.add("-Xmx256m");
		argfile.add("-Djava.awt.headless=true");
		argfile.add("-Dstdout.encoding=UTF-8");
		argfile.add("-Dstderr.encoding=UTF-8");
		argfile.add(quote("-Dforbric.definedClassEvidence=" + dir.resolve("defined")));
		argfile.add("-Dmixin.debug.export=true");
		for (var property : properties.entrySet()) argfile.add(quote("-D" + property.getKey() + "=" + property.getValue()));
		argfile.add(WeaveHarnessMain.class.getName());
		List<String> childArgs = new ArrayList<>(List.of(fixture.toString(), mixinExtrasJar(), side.name(), probeClass,
				probeMethod, dir.toString(), String.join(",", chain.stream().map(Class::getName).toList())));
		for (Config config : configs) childArgs.addAll(List.of(config.name(), config.modId(), config.ecosystem().name()));
		for (String arg : childArgs) argfile.add(quote(arg));
		Path args = dir.resolve("java.args");
		Files.write(args, argfile, StandardCharsets.UTF_8);

		Path log = dir.resolve("output.log");
		Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + args)
				.directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
		if (!child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
			throw new AssertionError("run " + label + " did not finish in " + TIMEOUT_SECONDS + " s:\n"
					+ Files.readString(log, StandardCharsets.UTF_8));
		}
		String output = Files.readString(log, StandardCharsets.UTF_8);
		List<Finding> findings = new ArrayList<>();
		Path tsv = dir.resolve("findings.tsv");
		if (Files.exists(tsv)) {
			for (String row : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
				if (row.isBlank()) continue;
				String[] c = row.split("\t", -1);
				findings.add(new Finding(c[0], c[1], c[2], Boolean.parseBoolean(c[3]), c[4], c[5]));
			}
		}
		Result result = new Result(label, child.exitValue(), output, List.copyOf(findings), dir);
		assertEquals(0, result.exit(), "the child JVM itself failed — " + result.describe());
		assertTrue(result.printed("[WeaveHarness] findings written"), "the child did not finish — " + result.describe());
		return result;
	}

	/**
	 * A run that must have defined and verified {@code internalName} through the real pipeline.
	 *
	 * <p>The verifier resolves every type the woven code hands across an assignment, MixinExtras' included: an
	 * {@code Operation} or {@code LocalRef} comes from the MixinExtras jar the run wove with, and a {@code LocalRef}
	 * implementation from {@link MixinExtrasGeneratedRefs}, because MixinExtras defines those only at run time.
	 */
	static void assertWovenAndVerified(Result run, String internalName, Path fixture) throws IOException {
		try (var jars = new java.net.URLClassLoader(new java.net.URL[] {fixture.toUri().toURL(),
				Path.of(mixinExtrasJar()).toUri().toURL()}, WeaveHarness.class.getClassLoader())) {
			String verdict = run.verify(internalName, new MixinExtrasGeneratedRefs(jars));
			assertTrue(verdict.isEmpty(), internalName + " does not verify after weaving:\n" + verdict + "\n" + run.describe());
		}
	}

	/**
	 * A bodiless stand-in for each {@code LocalRef} implementation MixinExtras generates. It defines them through a
	 * Lookup while the class weaves, so neither its jar nor the defined-class evidence has them, and the verifier needs
	 * only their place in the hierarchy: the ref interface of the same name they implement.
	 */
	private static final class MixinExtrasGeneratedRefs extends ClassLoader {
		private static final String GENERATED = "com.llamalad7.mixinextras.sugar.impl.ref.generated.";

		MixinExtrasGeneratedRefs(ClassLoader parent) {
			super(parent);
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (!name.startsWith(GENERATED) || !name.endsWith("Impl")) throw new ClassNotFoundException(name);
			String simple = name.substring(GENERATED.length(), name.length() - "Impl".length());
			org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
			writer.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, name.replace('.', '/'), null,
					"java/lang/Object", new String[] { "com/llamalad7/mixinextras/sugar/ref/" + simple });
			writer.visitEnd();
			byte[] bytes = writer.toByteArray();
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	static boolean hasMergedMethod(byte[] woven) {
		var node = new org.objectweb.asm.tree.ClassNode();
		new ClassReader(woven).accept(node, 0);
		return node.methods.stream().anyMatch(m -> m.visibleAnnotations != null && m.visibleAnnotations.stream()
				.anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")));
	}

	static String mixinExtrasJar() {
		String jar = System.getProperty("forbric.mixinExtrasForTests", "");
		assertFalse(jar.isBlank(), "forbric.mixinExtrasForTests is not set: run this through Gradle");
		return jar;
	}

	private static String childClasspath() {
		String explicit = System.getProperty("forbric.weaveHarnessClasspath", "");
		return explicit.isBlank() ? System.getProperty("java.class.path") : explicit;
	}

	private static String compileClasspath() {
		List<String> entries = new ArrayList<>();
		for (Class<?> anchor : List.of(org.spongepowered.asm.mixin.Mixin.class, org.objectweb.asm.tree.ClassNode.class,
				org.objectweb.asm.Opcodes.class, net.forbric.kernel.interop.PayloadInterop.class)) {
			entries.add(Path.of(codeSource(anchor)).toString());
		}
		entries.add(mixinExtrasJar());
		return String.join(java.io.File.pathSeparator, entries);
	}

	private static java.net.URI codeSource(Class<?> type) {
		try {
			return type.getProtectionDomain().getCodeSource().getLocation().toURI();
		} catch (java.net.URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Java @argfile quoting: double quotes, with backslashes and quotes escaped. */
	private static String quote(String value) {
		return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

	private static final class WriterOutputStream extends OutputStream {
		private final StringWriter target;

		WriterOutputStream(StringWriter target) {
			this.target = target;
		}

		@Override
		public void write(int b) {
			target.write(b);
		}
	}
}
