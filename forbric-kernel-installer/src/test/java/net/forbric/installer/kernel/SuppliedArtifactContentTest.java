package net.forbric.installer.kernel;

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
 * references the base check looks for; nothing here is loaded or link-checked, which is {@link BaseLinkGateTest}'s
 * job.
 *
 * <p>{@code --doctor} judges a supplied set with the same checks, so its report is checked here too.
 */
public final class SuppliedArtifactContentTest {
	private static final String MC = "26.2";
	private static final String BASE = ArtifactBuilder.BASE;
	private static final String NEO = ArtifactBuilder.NEOFORGE_RUNTIME;

	public static void main(String[] args) throws Exception {
		Path work = Files.createDirectories(Path.of(args[0]));
		int checks = 0;

		// ---- the real shapes pass ----
		Path base = jar(work.resolve("ok/patched-mc-neoforge-26.2.jar"), patchedGame(MC, true, false, false));
		Path neo = jar(work.resolve("ok/neoforge-runtime.jar"), neoRuntime(Pins.NEOFORGE));
		requireOk(BASE, base);
		requireOk(NEO, neo);
		GameArtifacts good = GameArtifacts.locate(MC, work.resolve("ok"));
		good.verifyContents(MC);
		// And where a development stage keeps them (tools/dev.py prepare), so the stage can be named as it is.
		Path stage = work.resolve("stage");
		Files.createDirectories(stage.resolve("neoforge-patched"));
		Files.createDirectories(stage.resolve("neoforge-runtime"));
		Files.copy(base, stage.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar"));
		Files.copy(neo, stage.resolve("neoforge-runtime/neoforge-runtime.jar"));
		GameArtifacts staged = GameArtifacts.locate(MC, stage);
		require(staged.get(BASE).equals(stage.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar"))
				&& staged.get(NEO).equals(stage.resolve("neoforge-runtime/neoforge-runtime.jar")), "the stage layout was not found");
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

		for (String coordinate : List.of(BASE, NEO)) {
			requireProblem(coordinate, gson, coordinate.equals(BASE) ? "does not contain Minecraft" : "does not contain NeoForge");
			requireProblem(coordinate, empty, "empty archive");
			requireProblem(coordinate, forgeInstaller, "the MinecraftForge installer");
			requireProblem(coordinate, neoInstaller, "the NeoForge installer");
			requireProblem(coordinate, fabricInstaller, "the Fabric installer");
			requireProblem(coordinate, notAJar, "not a jar file");
			checks += 6;
		}

		// ---- Minecraft, but not NeoForge's patched game ----
		requireProblem(BASE, jar(work.resolve("vanilla.jar"), patchedGame(MC, false, false, false)), "plain Minecraft 26.2");
		requireProblem(BASE, jar(work.resolve("mc-26.1.jar"), patchedGame("26.1", true, false, false)),
				"Minecraft 26.1, but this install is for Minecraft 26.2");
		// The merged base Forbric 0.4 and earlier built: refused by name, whichever of its marks it shows.
		requireProblem(BASE, jar(work.resolve("mc-merged.jar"), patchedGame(MC, true, true, false)), GameArtifacts.OLD_INSTALL);
		requireProblem(BASE, jar(work.resolve("mc-forge.jar"), patchedGame(MC, false, true, false)),
				"It is Minecraft 26.2 patched by MinecraftForge.");
		requireProblem(BASE, jar(work.resolve("mc-merged-index.jar"), patchedGame(MC, true, false, true)),
				GameArtifacts.OLD_INSTALL);
		checks += 5;

		// ---- NeoForge, but not the runtime Forbric assembles; and the wrong file in the runtime's place ----
		requireProblem(NEO, jar(work.resolve("neoforge-universal.jar"),
				entries("net/neoforged/neoforge/common/NeoForge.class", "x", "META-INF/neoforge.mods.toml", "")),
				"not the NeoForge runtime Forbric puts together");
		Path forgeRuntime = jar(work.resolve("forge-runtime.jar"), forgeRuntime());
		requireProblem(NEO, forgeRuntime, "does not contain NeoForge (it looks like MinecraftForge instead)");
		requireProblem(NEO, base, "does not contain NeoForge (it looks like Minecraft instead)");
		requireProblem(BASE, neo, "does not contain Minecraft (it looks like NeoForge instead)");
		checks += 4;

		// ---- the right runtime, built for another version: 0.2.0's NeoForge runtime passed all of the above ----
		requireProblem(NEO, jar(work.resolve("neoforge-runtime-0.2.0.jar"), neoRuntime("26.2.0.38-beta")),
				"It was built for NeoForge 26.2.0.38-beta; this installer needs " + Pins.NEOFORGE + ".");
		Map<String, byte[]> unversioned = neoRuntime(Pins.NEOFORGE);
		unversioned.remove("META-INF/MANIFEST.MF");
		requireProblem(NEO, jar(work.resolve("neoforge-runtime-no-manifest.jar"), unversioned),
				"does not say which NeoForge it was built for");
		// A per-library section's Implementation-Version is not what the runtime was built for.
		Map<String, byte[]> sectionOnly = neoRuntime(Pins.NEOFORGE);
		sectionOnly.putAll(entries("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n"
				+ "Implementation-Title: NeoForge\r\n\r\n"
				+ "Name: net/neoforged/fml/\r\nImplementation-Version: " + Pins.NEOFORGE + "\r\n\r\n"));
		requireProblem(NEO, jar(work.resolve("neoforge-runtime-section-version.jar"), sectionOnly),
				"does not say which NeoForge it was built for");
		checks += 3;

		// ---- a jar that opens but whose entry does not read back is damaged, not "not a jar" ----
		Path damaged = jar(work.resolve("patched-mc-neoforge-damaged.jar"), patchedGame(MC, true, false, false));
		damage(damaged, "net/minecraft/client/Minecraft.class");
		requireProblem(BASE, damaged, "It is damaged: part of it cannot be read");
		checks++;

		// ---- the whole set: every bad file named at once, with the way out ----
		Path wrong = Files.createDirectories(work.resolve("wrong"));
		Files.copy(gson, wrong.resolve("patched-mc-neoforge-26.2.jar"));
		Files.copy(neoInstaller, wrong.resolve("neoforge-runtime.jar"));
		try {
			GameArtifacts.locate(MC, wrong).verifyContents(MC);
			throw new AssertionError("a set of renamed jars was accepted");
		} catch (IOException expected) {
			String message = expected.getMessage();
			for (String part : List.of("these files are not the game files Forbric needs",
					wrong.resolve("patched-mc-neoforge-26.2.jar").toString(),
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
		Path baseVersion = Files.createDirectories(mcDir.resolve("versions/26.2"));
		Files.writeString(baseVersion.resolve("26.2.json"), "not json");
		Files.write(baseVersion.resolve("26.2.jar"), new byte[0]);
		try {
			new Installer(line -> { }).install(mcDir, MC, wrong);
			throw new AssertionError("the installer installed renamed jars");
		} catch (IOException expected) {
			require(expected.getMessage().contains("not the game files Forbric needs"),
					"refused for the wrong reason (was the base version read first?): " + expected.getMessage());
		}
		require(!Files.exists(mcDir.resolve("versions/26.2-forbric")), "a profile directory was written");
		require(!Files.exists(mcDir.resolve("libraries")), "something was staged into libraries/");
		require(!Files.exists(mcDir.resolve(".forbric-build")), "the link-check tools were unpacked for a refused set");
		checks++;

		// ---- a missing file: the way out comes first, the developer detail after ----
		Path partial = Files.createDirectories(work.resolve("partial"));
		Files.copy(base, partial.resolve("patched-mc-neoforge-26.2.jar"));
		try {
			GameArtifacts.locate(MC, partial);
			throw new AssertionError("an incomplete set was located");
		} catch (IOException expected) {
			String message = expected.getMessage();
			require(message.contains("cannot find neoforge-runtime.jar in " + partial), "missing file not named: " + message);
			require(message.contains("Leave \"Built artifacts\" empty"), "missing-file error has no way out: " + message);
			require(message.indexOf("Leave \"Built artifacts\"") < message.indexOf("Developers"),
					"the developer detail comes before the player's answer: " + message);
			require(!message.contains("Forbric 0.4"), "a directory with no old merged base was said to hold one: " + message);
		}
		checks++;

		// ---- an older Forbric's stage: no base by the new name, but its merged base by the old one, said so ----
		Path older = Files.createDirectories(work.resolve("older/merged-base"));
		Files.copy(base, older.resolve("patched-mc-merged-26.2.jar"));
		Files.createDirectories(work.resolve("older/neoforge-runtime"));
		Files.copy(neo, work.resolve("older/neoforge-runtime/neoforge-runtime.jar"));
		try {
			GameArtifacts.locate(MC, work.resolve("older"));
			throw new AssertionError("an older Forbric's stage was located");
		} catch (IOException expected) {
			String message = expected.getMessage();
			require(message.contains("cannot find patched-mc-neoforge-26.2.jar in "), "the missing base not named: " + message);
			require(message.contains(older.resolve("patched-mc-merged-26.2.jar")
					+ ", the merged game base Forbric 0.4 or earlier built"), "the old merged base not named: " + message);
		}
		checks++;

		checks += doctor(work, base, neo, forgeRuntime, wrong);

		System.out.println("PASS installer --artifacts content: " + checks + " checks (real shapes accepted;"
				+ " renamed gson, empty zip, installers, vanilla, an older Forbric's merged base, universal, MinecraftForge,"
				+ " swapped and other-version jars refused; --doctor names every file and every problem)");
	}

	/**
	 * {@code --doctor --artifacts}: each file judged on its own, and every reason it finds printed — the
	 * artifacts' and the JDK's.
	 */
	private static int doctor(Path work, Path base, Path neo, Path forgeRuntime, Path wrong) throws IOException {
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

		// The base there, a MinecraftForge runtime in the NeoForge runtime's place: "present" and "WRONG FILE", and the
		// wrong file's reason, with the way out once.
		Path half = Files.createDirectories(work.resolve("doctor-half"));
		Files.copy(base, half.resolve("patched-mc-neoforge-26.2.jar"));
		Files.copy(forgeRuntime, half.resolve("neoforge-runtime.jar"));
		out.clear();
		report = new Doctor(out::add).examine(mcDir, null, half);
		text = String.join("\n", out);
		require(!report.ok(), "--doctor passed a wrong runtime:\n" + text);
		for (String line : List.of("    present  " + ArtifactBuilder.BASE, "    WRONG FILE  " + ArtifactBuilder.NEOFORGE_RUNTIME)) {
			require(out.contains(line), "no line \"" + line + "\":\n" + text);
		}
		require(text.contains("does not contain NeoForge (it looks like MinecraftForge instead)"),
				"the wrong file's reason is missing:\n" + text);
		require(text.indexOf("Leave \"Built artifacts\" empty") == text.lastIndexOf("Leave \"Built artifacts\" empty"),
				"the way out is said more than once:\n" + text);
		checks++;

		// The runtime there, the base not: only what is not there is "missing", and it is named.
		Path runtimeOnly = Files.createDirectories(work.resolve("doctor-runtime-only"));
		Files.copy(neo, runtimeOnly.resolve("neoforge-runtime.jar"));
		out.clear();
		report = new Doctor(out::add).examine(mcDir, null, runtimeOnly);
		text = String.join("\n", out);
		require(!report.ok(), "--doctor passed a directory without the base:\n" + text);
		for (String line : List.of("    missing  " + ArtifactBuilder.BASE, "    present  " + ArtifactBuilder.NEOFORGE_RUNTIME)) {
			require(out.contains(line), "no line \"" + line + "\":\n" + text);
		}
		require(text.contains("cannot find patched-mc-neoforge-26.2.jar in " + runtimeOnly), "the missing file is not named:\n" + text);
		checks++;

		// Negative control: the same directory completed with the right files is ready.
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

	/**
	 * Minecraft {@code version} as a base carries it: with NeoForge's patches (the real base), with MinecraftForge's
	 * too (the merged base Forbric 0.4 and earlier built), and with the MinecraftForge native-reference index that merge
	 * embedded.
	 */
	private static Map<String, byte[]> patchedGame(String version, boolean neoPatched, boolean forgePatched,
			boolean forgeIndex) {
		String body = "client" + (neoPatched ? " net/neoforged/neoforge/client/ClientHooks" : "");
		Map<String, byte[]> entries = entries(
				"version.json", "{\"id\": \"" + version + "\", \"name\": \"" + version + "\"}",
				"net/minecraft/client/Minecraft.class", body,
				"net/minecraft/world/item/ItemStack.class", "item"
						+ (forgePatched ? " net/minecraftforge/common/extensions/IForgeItemStack" : ""));
		if (neoPatched) entries.putAll(entries("META-INF/neoforge.mods.toml", "modLoader=\"minecraft\""));
		if (forgeIndex) entries.putAll(entries("META-INF/forbric/native-reference/FORGE/index.tsv", ""));
		return entries;
	}

	/** A MinecraftForge runtime as an older Forbric assembled it: what a player might put in the NeoForge runtime's place. */
	private static Map<String, byte[]> forgeRuntime() {
		return entries(
				"META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Title: MinecraftForge\r\n"
						+ "Implementation-Version: 65.0.1\r\n\r\n",
				"fabric.mod.json", "{\"id\": \"forge\"}",
				"META-INF/mods.toml", "modId=\"forge\"",
				"net/minecraftforge/common/MinecraftForge.class", "core",
				"net/minecraftforge/fml/loading/FMLLoader.class", "loader",
				"net/minecraftforge/forgespi/language/IModInfo.class", "spi");
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
