package net.forbric.installer.kernel;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Built artifacts" / {@code --artifacts}: a supplied file is judged by what is IN it, not by its name (#13).
 *
 * <p>Each fake jar below is built from the entries the real one was found to carry (or, for the negatives, the
 * entries the file a player might grab instead carries). Class bodies are placeholder bytes holding the package
 * references the merged-base check looks for, except the one class the interop check parses, which is a real
 * (minimal) class file; nothing here is loaded or link-checked, which is {@link MergedBaseLinkGateTest}'s job.
 *
 * <p>{@code --doctor} judges a supplied set with the same checks, so its report is checked here too.
 */
public final class SuppliedArtifactContentTest {
	private static final String MC = "26.2";
	private static final String MERGED = ArtifactBuilder.MERGED;
	private static final String FORGE = ArtifactBuilder.FORGE_RUNTIME;
	private static final String NEO = ArtifactBuilder.NEOFORGE_RUNTIME;

	public static void main(String[] args) throws Exception {
		Path work = Files.createDirectories(Path.of(args[0]));
		int checks = 0;

		// ---- the real shapes pass ----
		Path merged = jar(work.resolve("ok/patched-mc-merged-26.2.jar"), mergedBase(MC, true, true));
		Path forge = jar(work.resolve("ok/forge-runtime-interop.jar"), forgeRuntime("65.0.1", true));
		Path neo = jar(work.resolve("ok/neoforge-runtime.jar"), neoRuntime(Pins.NEOFORGE));
		requireOk(MERGED, merged);
		requireOk(FORGE, forge);
		requireOk(NEO, neo);
		GameArtifacts good = GameArtifacts.locate(MC, work.resolve("ok"));
		good.verifyContents(MC);
		checks += 4;

		// ---- what a player might pick up instead ----
		Path gson = jar(work.resolve("gson.jar"), entries("com/google/gson/Gson.class", "gson",
				"META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"));
		Path empty = jar(work.resolve("empty.jar"), Map.of());
		Path forgeInstaller = jar(work.resolve("forge-installer.jar"), entries(
				"install_profile.json", "{}",
				"version.json", "{\"id\": \"26.2-forge-65.0.1\"}",
				"net/minecraftforge/installer/SimpleInstaller.class", "installer"));
		Path neoInstaller = jar(work.resolve("neoforge-installer.jar"), entries(
				"install_profile.json", "{}",
				"version.json", "{\"id\": \"neoforge-26.2.0.93\"}",
				"net/minecraftforge/installer/SimpleInstaller.class", "installer",
				"net/neoforged/cliutils/progress/ProgressReporter.class", "installer"));
		Path fabricInstaller = jar(work.resolve("fabric-installer.jar"), entries(
				"net/fabricmc/installer/Main.class", "installer"));
		Path notAJar = work.resolve("notes.jar");
		Files.writeString(notAJar, "this is a text file someone renamed");

		for (String coordinate : List.of(MERGED, FORGE, NEO)) {
			requireProblem(coordinate, gson, coordinate.equals(MERGED) ? "does not contain Minecraft"
					: "does not contain " + (coordinate.equals(FORGE) ? "MinecraftForge" : "NeoForge"));
			requireProblem(coordinate, empty, "empty archive");
			requireProblem(coordinate, forgeInstaller, "the MinecraftForge installer");
			requireProblem(coordinate, neoInstaller, "the NeoForge installer");
			requireProblem(coordinate, fabricInstaller, "the Fabric installer");
			requireProblem(coordinate, notAJar, "not a jar file");
			checks += 6;
		}

		// ---- Minecraft, but not the merged base ----
		requireProblem(MERGED, jar(work.resolve("vanilla.jar"), mergedBase(MC, false, false)), "plain Minecraft 26.2");
		requireProblem(MERGED, jar(work.resolve("mc-forge.jar"), mergedBase(MC, true, false)),
				"patched by MinecraftForge only");
		requireProblem(MERGED, jar(work.resolve("mc-neo.jar"), mergedBase(MC, false, true)), "patched by NeoForge only");
		requireProblem(MERGED, jar(work.resolve("mc-26.1.jar"), mergedBase("26.1", true, true)),
				"Minecraft 26.1, but this install is for Minecraft 26.2");
		checks += 4;

		// ---- the right family, but not the runtime Forbric assembles; and the two runtimes swapped ----
		requireProblem(FORGE, jar(work.resolve("forge-universal.jar"),
				entries("net/minecraftforge/common/MinecraftForge.class", "x", "META-INF/mods.toml", "")),
				"not the MinecraftForge runtime Forbric puts together");
		requireProblem(NEO, jar(work.resolve("neoforge-universal.jar"),
				entries("net/neoforged/neoforge/common/NeoForge.class", "x", "META-INF/neoforge.mods.toml", "")),
				"not the NeoForge runtime Forbric puts together");
		requireProblem(FORGE, neo, "does not contain MinecraftForge (it looks like NeoForge instead)");
		requireProblem(NEO, forge, "does not contain NeoForge (it looks like MinecraftForge instead)");
		requireProblem(NEO, merged, "does not contain NeoForge (it looks like Minecraft instead)");
		checks += 5;

		// ---- the right runtime, built for another version: 0.2.0's NeoForge runtime passed all of the above ----
		requireProblem(NEO, jar(work.resolve("neoforge-runtime-0.2.0.jar"), neoRuntime("26.2.0.38-beta")),
				"It was built for NeoForge 26.2.0.38-beta; this installer needs " + Pins.NEOFORGE + ".");
		requireProblem(FORGE, jar(work.resolve("forge-runtime-65.0.0.jar"), forgeRuntime("65.0.0", true)),
				"It was built for MinecraftForge 65.0.0; this installer needs 65.0.1.");
		Map<String, byte[]> unversioned = neoRuntime(Pins.NEOFORGE);
		unversioned.remove("META-INF/MANIFEST.MF");
		requireProblem(NEO, jar(work.resolve("neoforge-runtime-no-manifest.jar"), unversioned),
				"does not say which NeoForge it was built for");
		// MinecraftForge's runtime manifest carries an Implementation-Version per bundled library; those are not it.
		Map<String, byte[]> sectionOnly = forgeRuntime("65.0.1", true);
		sectionOnly.putAll(entries("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n"
				+ "Implementation-Title: MinecraftForge\r\n\r\n"
				+ "Name: net/minecraftforge/accesstransformer/\r\nImplementation-Version: 65.0.1\r\n\r\n"));
		requireProblem(FORGE, jar(work.resolve("forge-runtime-section-version.jar"), sectionOnly),
				"does not say which MinecraftForge it was built for");
		checks += 4;

		// ---- MinecraftForge's runtime, but not the interop-patched one ----
		requireProblem(FORGE, jar(work.resolve("forge-runtime.jar"), forgeRuntime("65.0.1", false)),
				"It is forge-runtime.jar, the MinecraftForge runtime before Forbric patches it");
		Map<String, byte[]> noBridgeClass = forgeRuntime("65.0.1", true);
		noBridgeClass.remove(INTEROP_CLASS);
		requireProblem(FORGE, jar(work.resolve("forge-runtime-no-bridge-class.jar"), noBridgeClass),
				"It is forge-runtime.jar");
		// The class names contents()Ljava/util/Map; (it calls such a method) without declaring it: the method table
		// is what counts, not the bytes.
		Map<String, byte[]> callsOnly = forgeRuntime("65.0.1", false);
		callsOnly.put(INTEROP_CLASS, classFile(INTEROP_CLASS, List.of("contents", "()Ljava/util/Map;"), "<init>", "()V"));
		requireProblem(FORGE, jar(work.resolve("forge-runtime-calls-contents.jar"), callsOnly), "It is forge-runtime.jar");
		// NeoForge's runtime has no such bridge and needs none.
		requireOk(NEO, jar(work.resolve("neo-without-bridge/neoforge-runtime.jar"), neoRuntime(Pins.NEOFORGE)));
		checks += 4;

		// ---- a jar that opens but whose entry does not read back is damaged, not "not a jar" ----
		Path damaged = jar(work.resolve("forge-runtime-damaged.jar"), forgeRuntime("65.0.1", true));
		damage(damaged, INTEROP_CLASS);
		requireProblem(FORGE, damaged, "It is damaged: part of it cannot be read");
		checks++;

		// ---- the whole set: every bad file named at once, with the way out ----
		Path wrong = Files.createDirectories(work.resolve("wrong"));
		Files.copy(gson, wrong.resolve("patched-mc-merged-26.2.jar"));
		Files.copy(empty, wrong.resolve("forge-runtime-interop.jar"));
		Files.copy(neoInstaller, wrong.resolve("neoforge-runtime.jar"));
		try {
			GameArtifacts.locate(MC, wrong).verifyContents(MC);
			throw new AssertionError("a set of three renamed jars was accepted");
		} catch (IOException expected) {
			String message = expected.getMessage();
			for (String part : List.of("these files are not the game files Forbric needs",
					wrong.resolve("patched-mc-merged-26.2.jar").toString(),
					wrong.resolve("forge-runtime-interop.jar").toString(),
					wrong.resolve("neoforge-runtime.jar").toString(),
					"Leave \"Built artifacts\" empty")) {
				require(message.contains(part), "the refusal does not say \"" + part + "\":\n" + message);
			}
		}
		checks++;

		// ---- the installer refuses it before anything is downloaded, staged or written ----
		// versions/26.2 holds a jar and an unparseable JSON: if the supplied set were judged after the base version
		// is read, this would fail on the JSON (or, with no base at all, go to Mojang) instead of on the files.
		Path mcDir = work.resolve("minecraft");
		Path base = Files.createDirectories(mcDir.resolve("versions/26.2"));
		Files.writeString(base.resolve("26.2.json"), "not json");
		Files.write(base.resolve("26.2.jar"), new byte[0]);
		try {
			new Installer(line -> { }).install(mcDir, MC, wrong);
			throw new AssertionError("the installer installed three renamed jars");
		} catch (IOException expected) {
			require(expected.getMessage().contains("not the game files Forbric needs"),
					"refused for the wrong reason (was the base version read first?): " + expected.getMessage());
		}
		require(!Files.exists(mcDir.resolve("versions/26.2-forbric")), "a profile directory was written");
		require(!Files.exists(mcDir.resolve("libraries")), "something was staged into libraries/");
		require(!Files.exists(mcDir.resolve(".forbric-build")), "the link-check tools were unpacked for a refused set");
		checks++;

		// ---- a missing file: the way out comes first, the developer detail after ----
		// Run from a checkout that has built its own artifacts (forbric-loader/run/...), this used to be completed
		// from there; only the directory named counts now.
		Path partial = Files.createDirectories(work.resolve("partial"));
		Files.copy(merged, partial.resolve("patched-mc-merged-26.2.jar"));
		try {
			GameArtifacts.locate(MC, partial);
			throw new AssertionError("an incomplete set was located");
		} catch (IOException expected) {
			String message = expected.getMessage();
			require(message.contains("cannot find forge-runtime-interop.jar, neoforge-runtime.jar in " + partial),
					"missing files not named: " + message);
			require(message.contains("Leave \"Built artifacts\" empty"),
					"missing-file error has no way out: " + message);
			require(message.indexOf("Leave \"Built artifacts\"") < message.indexOf("Developers"),
					"the developer detail comes before the player's answer: " + message);
		}
		checks++;

		checks += doctor(work, merged, forge, neo, wrong);

		System.out.println("PASS installer --artifacts content: " + checks + " checks (real shapes accepted;"
				+ " renamed gson, empty zip, installers, vanilla, half-patched, universal, swapped, other-version and"
				+ " unpatched jars refused; --doctor names every file and every problem)");
	}

	/**
	 * {@code --doctor --artifacts}: each file judged on its own, and every reason it finds printed — the
	 * artifacts' and the JDK's.
	 */
	private static int doctor(Path work, Path merged, Path forge, Path neo, Path wrong) throws IOException {
		int checks = 0;
		Path mcDir = work.resolve("doctor-minecraft"); // never created: --doctor writes nothing

		// A bad --jdk AND a wrong set: both reasons, not just the first.
		Path noJdk = work.resolve("no-such-jdk");
		List<String> out = new ArrayList<>();
		Doctor.Report report = new Doctor(out::add).examine(mcDir, noJdk, wrong);
		String text = String.join("\n", out);
		require(!report.ok(), "--doctor passed a wrong set with no JDK:\n" + text);
		require(text.contains("not the game files Forbric needs"), "the artifact refusal is missing:\n" + text);
		require(text.contains("--jdk " + noJdk + " is not usable"), "the JDK's reason was dropped:\n" + text);
		checks++;

		// A half-filled directory: what is there is "present" (or WRONG FILE), only what is not is "missing", and
		// the verdict names both the missing file and the wrong one, with the way out once.
		Path half = Files.createDirectories(work.resolve("doctor-half"));
		Files.copy(merged, half.resolve("patched-mc-merged-26.2.jar"));
		Files.copy(forge, half.resolve("neoforge-runtime.jar")); // the usual mistake: the runtimes swapped
		out.clear();
		report = new Doctor(out::add).examine(mcDir, null, half);
		text = String.join("\n", out);
		require(!report.ok(), "--doctor passed a half-filled directory:\n" + text);
		for (String line : List.of("    present  net.forbric:patched-mc-merged",
				"    missing  net.forbric:forge-runtime",
				"    WRONG FILE  net.forbric:neoforge-runtime")) {
			require(out.contains(line), "no line \"" + line + "\":\n" + text);
		}
		require(text.contains("cannot find forge-runtime-interop.jar in " + half), "the missing file is not named:\n" + text);
		require(text.contains("does not contain NeoForge (it looks like MinecraftForge instead)"),
				"the wrong file's reason is missing:\n" + text);
		require(text.indexOf("Leave \"Built artifacts\" empty") == text.lastIndexOf("Leave \"Built artifacts\" empty"),
				"the way out is said more than once:\n" + text);
		checks++;

		// Negative control: the same directory completed with the right files is ready.
		Files.copy(forge, half.resolve("forge-runtime-interop.jar"));
		Files.copy(neo, half.resolve("neoforge-runtime.jar"), StandardCopyOption.REPLACE_EXISTING);
		out.clear();
		report = new Doctor(out::add).examine(mcDir, null, half);
		text = String.join("\n", out);
		require(report.ok() && out.contains("RESULT: ready to install, with no build needed."),
				"--doctor refused a complete, correct set:\n" + text);
		require(!Files.exists(mcDir), "--doctor created " + mcDir);
		checks++;
		return checks;
	}

	/** Minecraft {@code version} as the merged base carries it, with or without each family's patches. */
	private static Map<String, byte[]> mergedBase(String version, boolean forgePatched, boolean neoPatched) {
		String body = "client" + (forgePatched ? " net/minecraftforge/common/extensions/IForgeMinecraft" : "")
				+ (neoPatched ? " net/neoforged/neoforge/client/ClientHooks" : "");
		Map<String, byte[]> entries = entries(
				"version.json", "{\"id\": \"" + version + "\", \"name\": \"" + version + "\"}",
				"net/minecraft/client/Minecraft.class", body,
				"net/minecraft/world/item/ItemStack.class", "item");
		if (forgePatched) entries.putAll(entries("net/minecraftforge/api/distmarker/Dist.class", "dist"));
		if (neoPatched) entries.putAll(entries("META-INF/neoforge.mods.toml", "modLoader=\"minecraft\""));
		return entries;
	}

	/**
	 * MinecraftForge's runtime as ForgeRuntimeBuilder writes it — FML's version in the manifest's main section,
	 * one section per bundled library after it — with or without the bridge RuntimeInteropPatcher adds.
	 */
	private static Map<String, byte[]> forgeRuntime(String version, boolean interopPatched) {
		Map<String, byte[]> entries = entries(
				"META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n"
						+ "Automatic-Module-Name: net.minecraftforge.forge\r\n"
						+ "Implementation-Title: MinecraftForge\r\n"
						+ "Implementation-Version: " + version + "\r\n\r\n"
						+ "Name: net/minecraftforge/accesstransformer/\r\nImplementation-Version: 8.2.2\r\n\r\n",
				"fabric.mod.json", "{\"id\": \"forge\"}",
				"META-INF/mods.toml", "modId=\"forge\"",
				"net/minecraftforge/common/MinecraftForge.class", "core",
				"net/minecraftforge/fml/loading/FMLLoader.class", "loader",
				"net/minecraftforge/forgespi/language/IModInfo.class", "spi");
		entries.put(INTEROP_CLASS, interopPatched
				? classFile(INTEROP_CLASS, List.of(), "contents", "()Ljava/util/Map;", "<init>", "()V")
				: classFile(INTEROP_CLASS, List.of(), "<init>", "()V", "key", "()Lnet/minecraft/resources/ResourceKey;"));
		return entries;
	}

	/** NeoForge's runtime as NeoForgeRuntimeBuilder writes it: its four-line manifest names the NeoForge version. */
	private static Map<String, byte[]> neoRuntime(String version) {
		return entries(
				"META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Title: NeoForge\r\n"
						+ "Implementation-Version: " + version + "\r\nAutomatic-Module-Name: neoforge\r\n\r\n",
				"fabric.mod.json", "{\"id\": \"neoforge\"}",
				"META-INF/neoforge.mods.toml", "modId=\"neoforge\"",
				"net/neoforged/neoforge/common/NeoForge.class", "core",
				"net/neoforged/fml/loading/FMLLoader.class", "loader",
				"net/neoforged/neoforgespi/language/IModInfo.class", "spi");
	}

	private static final String INTEROP_CLASS = "net/minecraftforge/registries/NamespacedWrapper$3.class";

	/**
	 * A minimal class file: {@code extra} as additional constant-pool strings (what a class that only refers to a
	 * name carries), then one method per name/descriptor pair. A long constant sits between them, because it takes
	 * two constant-pool slots and a parser that forgets that misreads every index after it.
	 */
	private static byte[] classFile(String entryName, List<String> extra, String... methods) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeInt(0xCAFEBABE);
			out.writeShort(0);
			out.writeShort(61);
			// #1 this name, #2 Class #1, #3 java/lang/Object, #4 Class #3, then the extras, a long, the methods' names
			int count = 5 + extra.size() + 2 + methods.length;
			out.writeShort(count);
			out.writeByte(1);
			out.writeUTF(entryName.substring(0, entryName.length() - ".class".length()));
			out.writeByte(7);
			out.writeShort(1);
			out.writeByte(1);
			out.writeUTF("java/lang/Object");
			out.writeByte(7);
			out.writeShort(3);
			for (String s : extra) {
				out.writeByte(1);
				out.writeUTF(s);
			}
			out.writeByte(5);
			out.writeLong(0x5EED_5EEDL);
			int firstMethod = 5 + extra.size() + 2;
			for (String s : methods) {
				out.writeByte(1);
				out.writeUTF(s);
			}
			out.writeShort(0x0021); // public super
			out.writeShort(2);
			out.writeShort(4);
			out.writeShort(0); // interfaces
			out.writeShort(0); // fields
			out.writeShort(methods.length / 2);
			for (int i = 0; i < methods.length; i += 2) {
				out.writeShort(0x0401); // public abstract: no Code attribute needed
				out.writeShort(firstMethod + i);
				out.writeShort(firstMethod + i + 1);
				out.writeShort(0);
			}
			out.writeShort(0); // attributes
			return bytes.toByteArray();
		} catch (IOException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/**
	 * Makes {@code entryName}'s stored data in {@code jar} unreadable while the archive still opens: its first
	 * deflate block header is set to block type 3, which every inflater rejects.
	 */
	static void damage(Path jar, String entryName) throws IOException {
		byte[] bytes = Files.readAllBytes(jar);
		byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
		for (int i = 0; i + 30 <= bytes.length; i++) {
			if (bytes[i] != 'P' || bytes[i + 1] != 'K' || bytes[i + 2] != 3 || bytes[i + 3] != 4) continue;
			int nameLength = (bytes[i + 26] & 0xFF) | (bytes[i + 27] & 0xFF) << 8;
			int extraLength = (bytes[i + 28] & 0xFF) | (bytes[i + 29] & 0xFF) << 8;
			if (nameLength != name.length
					|| !java.util.Arrays.equals(bytes, i + 30, i + 30 + nameLength, name, 0, name.length)) continue;
			require(bytes[i + 8] == 8, entryName + " is not deflated, so it cannot be damaged this way");
			bytes[i + 30 + nameLength + extraLength] = 0x07; // final block, type 3 (reserved)
			Files.write(jar, bytes);
			return;
		}
		throw new AssertionError(entryName + " not found in " + jar);
	}

	private static Map<String, byte[]> entries(String... nameThenContent) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (int i = 0; i < nameThenContent.length; i += 2) {
			out.put(nameThenContent[i], nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
		}
		return out;
	}

	private static Path jar(Path dest, Map<String, byte[]> entries) throws IOException {
		Files.createDirectories(dest.getParent());
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dest))) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(e.getKey()));
				out.write(e.getValue());
				out.closeEntry();
			}
		}
		return dest;
	}

	private static void requireOk(String coordinate, Path jar) {
		String problem = GameArtifacts.problem(coordinate, jar, MC);
		require(problem == null, coordinate + " refused a correct " + jar.getFileName() + ": " + problem);
	}

	private static void requireProblem(String coordinate, Path jar, String expected) {
		String problem = GameArtifacts.problem(coordinate, jar, MC);
		require(problem != null, coordinate + " accepted " + jar.getFileName());
		require(problem.contains(expected), coordinate + " refused " + jar.getFileName() + " but said \"" + problem
				+ "\", not \"" + expected + "\"");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
