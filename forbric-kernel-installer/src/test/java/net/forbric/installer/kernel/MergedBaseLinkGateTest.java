package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/** Runs the real installer subprocess gate against linked and deliberately broken game jars. */
public final class MergedBaseLinkGateTest {
	public static void main(String[] args) throws Exception {
		Path work = Path.of(args[0]);
		Path source = Files.createDirectories(work.resolve("src/game"));
		Path classes = Files.createDirectories(work.resolve("classes"));
		Path target = source.resolve("Target.java");
		Path caller = source.resolve("Caller.java");
		Files.writeString(target, "package game; public class Target { public static int value = 3; }");
		Files.writeString(caller, "package game; public class Caller { public static int read() { return Target.value; } }");
		compile(classes, target, caller);
		Path game = work.resolve("game.jar");
		jar(classes, game);
		Path empty = work.resolve("carrier.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(empty))) { }
		List<String> logs = new ArrayList<>();
		MergedBaseTool tool = new MergedBaseTool(work.resolve("tools"), logs::add);
		JdkLocator.Jvm jvm = new JdkLocator.Jvm(Path.of(ForgeTool.javaBin()), Runtime.version().feature(), "test");
		tool.linkCheck(jvm, game, empty, empty);
		require(logs.stream().anyMatch(s -> s.contains("(known 0, new 0)")), "successful gate did not scan");
		Files.writeString(target, "package game; public class Target { }");
		compile(classes, target); // Caller still refers to the removed field.
		jar(classes, game);
		try {
			tool.linkCheck(jvm, game, empty, empty);
			throw new AssertionError("installer accepted a new dangling reference");
		} catch (IOException expected) {
			require(expected.getMessage().contains("game/Target.value"), "lost the underlying evidence");
		}
		try {
			tool.linkCheck(jvm, empty, empty, empty);
			throw new AssertionError("installer accepted an empty merged base");
		} catch (IOException expected) {
			require(expected.getMessage().contains("no classes"), "empty scan was not explained");
		}
		System.out.println("PASS installer gate: valid build, new defect rejected, empty scan rejected");
		suppliedArtifacts(work, classes, target, logs);
	}

	/**
	 * {@code --artifacts DIR}: the supplied set is link-checked like a built one, the interop jar is what stands for
	 * the Forge runtime, and a failed check leaves no profile behind.
	 */
	private static void suppliedArtifacts(Path work, Path classes, Path target, List<String> logs) throws Exception {
		Path supplied = Files.createDirectories(work.resolve("supplied"));
		Path game = Files.createDirectories(supplied.resolve("merged-base")).resolve("patched-mc-merged-26.2.jar");
		Path interop = supplied.resolve("merged-base").resolve("forge-runtime-interop.jar");
		Path raw = Files.createDirectories(supplied.resolve("forge-runtime")).resolve("forge-runtime.jar");
		Path neo = Files.createDirectories(supplied.resolve("neoforge-runtime")).resolve("neoforge-runtime.jar");
		for (Path carrier : List.of(interop, raw, neo)) {
			try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		}
		Path mc = Files.createDirectories(work.resolve("minecraft/versions/26.2"));
		Files.writeString(mc.resolve("26.2.json"), "{\"id\":\"26.2\",\"libraries\":[]}");
		Files.write(mc.resolve("26.2.jar"), new byte[] { 'P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
		Path mcDir = work.resolve("minecraft");
		Path jdk = Path.of(ForgeTool.javaBin());

		Files.writeString(target, "package game; public class Target { public static int value = 3; }");
		compile(classes, target);
		jar(classes, game);
		logs.clear();
		var found = new Installer(logs::add).obtainGameArtifacts(mcDir, "26.2", supplied, jdk);
		require(found.get(ArtifactBuilder.FORGE_RUNTIME).equals(interop),
				"the interop jar, not the raw runtime, stands for the Forge runtime: " + found);
		require(logs.stream().anyMatch(s -> s.contains("(known 0, new 0)")), "a supplied set was not link-checked");

		Files.writeString(target, "package game; public class Target { }");
		compile(classes, target);
		jar(classes, game);
		try {
			new Installer(logs::add).install(mcDir, "26.2", supplied, jdk);
			throw new AssertionError("installer published a supplied merged base with a new dangling reference");
		} catch (IOException expected) {
			require(expected.getMessage().contains("game/Target.value"), "lost the underlying evidence: " + expected);
		}
		require(!Files.exists(mcDir.resolve("versions/26.2-forbric/26.2-forbric.json")), "a profile was written");

		Files.delete(interop);
		try {
			new Installer(logs::add).obtainGameArtifacts(mcDir, "26.2", supplied, jdk);
			throw new AssertionError("the raw forge-runtime.jar was accepted in place of the interop jar");
		} catch (IOException expected) {
			require(expected.getMessage().contains("forge-runtime-interop.jar"), "missing interop not named: " + expected);
		}
		System.out.println("PASS installer --artifacts: link-checked, interop staged, broken set refused with no profile");
	}

	private static void compile(Path classes, Path... sources) {
		List<String> args = new ArrayList<>(List.of("--release", "17", "-d", classes.toString()));
		for (Path source : sources) args.add(source.toString());
		require(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)) == 0, "javac failed");
	}

	private static void jar(Path classes, Path dest) throws IOException {
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(dest)); var files = Files.walk(classes)) {
			for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
				Files.copy(file, out);
				out.closeEntry();
			}
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
