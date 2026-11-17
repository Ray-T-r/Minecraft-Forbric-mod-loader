package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/**
 * Runs the installer's link check as an install runs it (the checker jar it carries, in a child JVM, both runs) against
 * linked and deliberately broken game jars: a member gone from either jar, a member narrowed so the other jar can no
 * longer reach it, an empty base; and the supplied-artifacts path that checks a developer's set before anything is
 * written.
 */
public final class LinkCheckToolTest {
	public static void main(String[] args) throws Exception {
		Path work = Path.of(args[0]);
		Path source = Files.createDirectories(work.resolve("src/game"));
		Path classes = Files.createDirectories(work.resolve("classes"));
		Path target = source.resolve("Target.java");
		Path caller = source.resolve("Caller.java");
		// The carrier side: a class the game calls into, as NeoForge's patched game calls into net/neoforged/.
		Path hookSource = Files.createDirectories(work.resolve("src/carrier")).resolve("Hook.java");
		Path carrierClasses = Files.createDirectories(work.resolve("carrier-classes"));
		Files.writeString(hookSource, "package carrier; public class Hook { public static void present() { } }");
		compileWith(carrierClasses, null, hookSource);
		Path carrier = work.resolve("carrier.jar");
		jar(carrierClasses, carrier);
		Files.writeString(target, "package game; public class Target { public static int value = 3; }");
		Files.writeString(caller, "package game; public class Caller { public static int read() { "
				+ "carrier.Hook.present(); return Target.value; } }");
		compileWith(classes, carrierClasses.toString(), target, caller);
		Path game = work.resolve("game.jar");
		jar(classes, game);
		Path empty = work.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(empty))) { }
		List<String> logs = new ArrayList<>();
		LinkCheckTool tool = new LinkCheckTool(work.resolve("tools"), logs::add);
		JdkLocator.Jvm jvm = new JdkLocator.Jvm(Path.of(ToolProcess.javaBin()), Runtime.version().feature(), "test");
		tool.linkCheck(jvm, game, carrier);
		require(logs.stream().filter(s -> s.endsWith("; dangling references: 0")).count() == 2,
				"a successful gate did not scan in both orders: " + logs);
		Files.writeString(target, "package game; public class Target { }");
		compile(classes, target); // Caller still refers to the removed field.
		jar(classes, game);
		try {
			tool.linkCheck(jvm, game, carrier);
			throw new AssertionError("installer accepted a dangling reference");
		} catch (LinkCheckTool.Failed expected) {
			require(expected.getMessage().contains("game/Target.value"), "lost the underlying evidence");
		}
		// The game calling a carrier method that is gone: only the run that judges the carrier's members finds it.
		Files.writeString(target, "package game; public class Target { public static int value = 3; }");
		compile(classes, target);
		jar(classes, game);
		Files.writeString(hookSource, "package carrier; public class Hook { }");
		compileWith(carrierClasses, null, hookSource);
		jar(carrierClasses, carrier);
		logs.clear();
		try {
			tool.linkCheck(jvm, game, carrier);
			throw new AssertionError("installer accepted a game base calling a method the carrier does not have");
		} catch (LinkCheckTool.Failed expected) {
			require(expected.getMessage().contains("carrier/Hook.present") && expected.getMessage().contains(
					"references into the NeoForge runtime"), "lost the underlying evidence: " + expected.getMessage());
			List<String> verdicts = logs.stream().filter(s -> s.contains("dangling references:")).toList();
			require(verdicts.size() == 2 && verdicts.get(0).endsWith("; dangling references: 0")
					&& verdicts.get(1).endsWith("; dangling references: 1"), "the base-first run was not the clean one: " + logs);
		}
		// A carrier member the game calls, still there but narrowed: the old checker passed this (it judged existence
		// only), and the game would meet it as an IllegalAccessError. The install must stop on it like a missing one.
		Files.writeString(hookSource, "package carrier; public class Hook { static void present() { } }");
		compileWith(carrierClasses, null, hookSource);
		jar(carrierClasses, carrier);
		logs.clear();
		try {
			tool.linkCheck(jvm, game, carrier);
			throw new AssertionError("installer accepted a game base calling a carrier method it cannot access");
		} catch (LinkCheckTool.Failed expected) {
			require(expected.getMessage().contains("[MEMBER_ACCESS] game/Caller#read()I  ->  METHOD carrier/Hook.present()V")
					&& expected.getMessage().contains("package-private in carrier/Hook"), "lost the underlying evidence: "
					+ expected.getMessage());
			List<String> verdicts = logs.stream().filter(s -> s.contains("dangling references:")).toList();
			require(verdicts.size() == 2 && verdicts.get(1).endsWith("; dangling references: 1"),
					"an inaccessible member did not count in the verdict: " + logs);
		}
		try {
			tool.linkCheck(jvm, empty, carrier);
			throw new AssertionError("installer accepted an empty game base");
		} catch (IOException expected) {
			require(expected.getMessage().contains("no classes"), "empty scan was not explained");
		}
		verdicts();
		System.out.println("PASS installer link check: valid build checked in both runs, a missing or narrowed member of"
				+ " either jar rejected, empty scan rejected");
		suppliedArtifacts(work, classes, target, logs);
	}

	/**
	 * What the checker's last line has to say for the install to go ahead: exactly its own success, measured on
	 * NeoForge 26.2.0.93's patched game and carrier as {@code [link-check] loaded 13394 classes (13394 scanned; 1243939
	 * references judged, 894 unresolvable through an unread supertype, 363460 out of scope); dangling references: 0}. A
	 * non-zero count, a non-zero exit, a missing line, and the summary the old checker's baseline run printed (which
	 * could say {@code new 0} while references dangled) each fail.
	 */
	private static void verdicts() {
		String clean = "[link-check] loaded 13394 classes (13394 scanned; 1243939 references judged, 894 unresolvable"
				+ " through an unread supertype, 363460 out of scope); dangling references: 0";
		require(LinkCheckTool.passed(0, clean), "the genuine pair's verdict line was not accepted");
		require(!LinkCheckTool.passed(1, clean), "a non-zero exit was accepted");
		require(!LinkCheckTool.passed(0, clean.replace("references: 0", "references: 7")), "dangling references were accepted");
		require(!LinkCheckTool.passed(0, clean.replace("references: 0", "references: 01")), "a count starting with 0 was accepted");
		require(!LinkCheckTool.passed(0, "[link-check] loaded 13394 classes (10964 from the merged jar); dangling references: 7"
				+ " (known 7, new 0); baseline entries now fixed: 0"), "a baseline run's \"new 0\" was accepted");
		require(!LinkCheckTool.passed(0, "[link-check] produced no summary line (exit 0)"), "a missing summary was accepted");
	}

	/**
	 * {@code --artifacts DIR}: the supplied set is link-checked like a built one, a failed check leaves no profile
	 * behind, and the merged base an older Forbric built is refused by name.
	 *
	 * <p>The set has to look like a real one first, or the content check refuses it before any link check runs:
	 * the game jar is Minecraft 26.2 whose client refers to NeoForge, and the carrier holds NeoForge's core class and
	 * mod loader and names the pinned version in its manifest. Those are real bytecode, so they are resolved like any
	 * other.
	 */
	private static void suppliedArtifacts(Path work, Path classes, Path target, List<String> logs) throws Exception {
		Path supplied = Files.createDirectories(work.resolve("supplied"));
		Path game = Files.createDirectories(supplied.resolve("neoforge-patched")).resolve("patched-mc-neoforge-26.2.jar");
		Path neo = Files.createDirectories(supplied.resolve("neoforge-runtime")).resolve("neoforge-runtime.jar");
		Path carriers = Files.createDirectories(work.resolve("src/carriers"));
		Path forgeClasses = Files.createDirectories(work.resolve("forge-classes"));
		Path neoClasses = Files.createDirectories(work.resolve("neo-classes"));
		String hook = "{ public static void hook() { } }";
		compileWith(neoClasses, null,
				source(carriers, "net.neoforged.neoforge.common", "public class NeoForge " + hook),
				source(carriers, "net.neoforged.fml.loading", "public class FMLLoader { }"),
				source(carriers, "net.neoforged.neoforgespi.language", "public interface IModInfo { }"));
		// MinecraftForge only as the old merged base's other half: what makes it refer to net/minecraftforge/.
		compileWith(forgeClasses, null,
				source(carriers, "net.minecraftforge.common", "public class MinecraftForge " + hook));
		Files.writeString(Files.createDirectories(neoClasses.resolve("META-INF")).resolve("MANIFEST.MF"),
				"Manifest-Version: 1.0\r\nImplementation-Title: NeoForge\r\nImplementation-Version: " + Pins.NEOFORGE
						+ "\r\n\r\n");
		jar(neoClasses, neo);
		compileWith(classes, neoClasses.toString(),
				source(work.resolve("src"), "net.minecraft.client", "public class Minecraft { void run() { "
						+ "net.neoforged.neoforge.common.NeoForge.hook(); } }"));
		Files.writeString(classes.resolve("version.json"), "{\"id\": \"26.2\"}");
		Path mc = Files.createDirectories(work.resolve("minecraft/versions/26.2"));
		Files.writeString(mc.resolve("26.2.json"), "{\"id\":\"26.2\",\"libraries\":[]}");
		Files.write(mc.resolve("26.2.jar"), new byte[] { 'P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
		Path mcDir = work.resolve("minecraft");
		Path jdk = Path.of(ToolProcess.javaBin());

		Files.writeString(target, "package game; public class Target { public static int value = 3; }");
		compile(classes, target);
		jar(classes, game);
		logs.clear();
		var found = new Installer(logs::add).obtainGameArtifacts(mcDir, "26.2", supplied, jdk);
		require(found.keySet().equals(java.util.Set.of(ArtifactBuilder.BASE, ArtifactBuilder.NEOFORGE_RUNTIME))
				&& found.get(ArtifactBuilder.BASE).equals(game) && found.get(ArtifactBuilder.NEOFORGE_RUNTIME).equals(neo),
				"the supplied set is the base and the NeoForge runtime: " + found);
		require(logs.stream().anyMatch(s -> s.endsWith("; dangling references: 0")), "a supplied set was not link-checked");

		Files.writeString(target, "package game; public class Target { }");
		compile(classes, target);
		jar(classes, game);
		try {
			new Installer(logs::add).install(mcDir, "26.2", supplied, jdk);
			throw new AssertionError("installer published a supplied base with a dangling reference");
		} catch (IOException expected) {
			requireDoNotFit(expected, supplied, "game/Target.value");
		}
		require(!Files.exists(mcDir.resolve("versions/26.2-forbric/26.2-forbric.json")), "a profile was written");

		// A damaged copy of the runtime is still the right kind of file (the content check reads only what it needs),
		// and the link checker dies reading it. The way out first, then the checker's own words.
		Files.writeString(target, "package game; public class Target { public static int value = 3; }");
		compile(classes, target);
		jar(classes, game);
		Path intact = work.resolve("neoforge-runtime-intact.jar");
		Files.copy(neo, intact);
		SuppliedArtifactContentTest.damage(neo, "net/neoforged/fml/loading/FMLLoader.class");
		try {
			new Installer(logs::add).obtainGameArtifacts(mcDir, "26.2", supplied, jdk);
			throw new AssertionError("a supplied set with a damaged runtime passed the link check");
		} catch (IOException expected) {
			requireDoNotFit(expected, supplied, "do not link");
		}
		Files.copy(intact, neo, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

		// The merged base Forbric 0.3.x and earlier built: NeoForge's patches AND MinecraftForge's. It links (the old
		// install's carrier set held both families) -- here against a carrier that has both -- and is still refused by
		// its content, by name, before any link check, so it never reaches a profile.
		compileWith(classes, neoClasses + java.io.File.pathSeparator + forgeClasses,
				source(work.resolve("src"), "net.minecraft.client", "public class Minecraft { void run() { "
						+ "net.neoforged.neoforge.common.NeoForge.hook(); "
						+ "net.minecraftforge.common.MinecraftForge.hook(); } }"));
		jar(classes, game);
		logs.clear();
		try {
			new Installer(logs::add).install(mcDir, "26.2", supplied, jdk);
			throw new AssertionError("the merged base of an older Forbric was installed");
		} catch (IOException expected) {
			require(expected.getMessage().contains(InstallerText.oldMergedBase()), "not refused as an old install: " + expected);
			require(logs.stream().noneMatch(s -> s.contains("dangling references")), "the old base was link-checked: " + logs);
		}
		require(!Files.exists(mcDir.resolve("versions/26.2-forbric/26.2-forbric.json")), "a profile was written");
		System.out.println("PASS installer --artifacts: link-checked, broken or damaged set refused with the way out and"
				+ " no profile, an older Forbric's merged base refused by name");
	}

	/** A supplied set whose link check failed: said to be the supplied files', the way out, then the evidence. */
	private static void requireDoNotFit(IOException refusal, Path supplied, String evidence) {
		String message = refusal.getMessage();
		require(message.startsWith("Built artifacts: the files in " + supplied + " do not fit together"),
				"not said to be the supplied files: " + message);
		int wayOut = message.indexOf(GameArtifacts.LEAVE_EMPTY);
		require(wayOut >= 0 && wayOut < message.indexOf(evidence), "no way out before the evidence: " + message);
	}

	private static void compile(Path classes, Path... sources) {
		compileWith(classes, null, sources);
	}

	private static void compileWith(Path classes, String classpath, Path... sources) {
		List<String> args = new ArrayList<>(List.of("--release", "17", "-d", classes.toString()));
		if (classpath != null) args.addAll(List.of("-cp", classpath));
		for (Path source : sources) args.add(source.toString());
		require(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)) == 0, "javac failed");
	}

	/** Writes {@code body} as the one top-level type of {@code pkg}, named after the type it declares. */
	private static Path source(Path root, String pkg, String body) throws IOException {
		String name = body.replaceFirst("^public (?:class|interface) ([\\w$]+).*$", "$1");
		Path file = Files.createDirectories(root.resolve(pkg.replace('.', '/'))).resolve(name + ".java");
		Files.writeString(file, "package " + pkg + "; " + body);
		return file;
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
