package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The window's Install button, given what a player types or pastes into its path fields: every failure is a line
 * in the window's log, never an exception that escapes to stderr (which the .bat's javaw throws away).
 *
 * <p>Runs headless: it drives {@link InstallerGui#installFromFields}, which is all the button does besides
 * reading the fields' text and greying itself out, so no window is created here.
 */
public final class InstallerWindowInputTest {
	public static void main(String[] args) throws Exception {
		Path work = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
		int checks = 0;

		// ---- Util.path: the quotes Explorer's "Copy as path" adds ----
		Path plain = work.resolve("Some Folder");
		require(Util.path("\"" + plain + "\"").equals(plain), "quotes were not dropped: " + Util.path("\"" + plain + "\""));
		require(Util.path(plain.toString()).equals(plain), "an unquoted path changed");
		require(Util.path("\"~/x\"").equals(Path.of(System.getProperty("user.home"), "x")),
				"~/ is not expanded inside quotes");
		require(Util.unquote("\"").equals("\""), "a lone quote was eaten");
		require(Util.unquote("\"\"").isEmpty() && Util.unquote("\"a\"b\"").equals("a\"b"),
				"only one surrounding pair is dropped");
		checks += 5;

		// ---- fieldPath: empty means empty, pasted text is taken as pasted, a bad path names its field ----
		for (String blank : List.of("", "   ", "\"\"", "  \"\"  ")) {
			require(InstallerGui.fieldPath("Built artifacts", blank) == null, "[" + blank + "] is not empty");
		}
		require(InstallerGui.fieldPath("Game directory", "  \"" + plain + "\"  ").equals(plain),
				"a pasted, quoted path was not taken as the folder it names");
		require(InstallerGui.fieldPath("Game directory", "relative").isAbsolute(), "a relative path stayed relative");
		try {
			// NUL is the one character no platform's paths accept, so this is the same refusal Windows gives "|".
			InstallerGui.fieldPath("Built artifacts", plain + "\u0000x");
			throw new AssertionError("a path with a NUL in it was accepted");
		} catch (IOException expected) {
			String message = expected.getMessage();
			require(message.startsWith("Built artifacts: ") && message.contains("is not a folder path")
					&& message.contains("Browse"), "the refusal does not name the field or the way out: " + message);
		}
		checks += 7;

		// ---- installFromFields: a path Java refuses is reported in the log, and nothing is written ----
		Path mcDir = work.resolve("minecraft");
		List<String> log = install("\"" + mcDir + "\"", plain + "\u0000x");
		requireFailed(log, "Install failed: Built artifacts: ");
		require(!Files.exists(mcDir), "something was written for a refused install");
		log = install("   ", "");
		requireFailed(log, "Install failed: Game directory is empty");
		checks += 2;

		// ---- quoted paths reach the installer as the folders they name ----
		// A supplied set it refuses on content, so nothing is downloaded: the refusal names the files inside the
		// unquoted folder, which is the proof the quotes were dropped before the installer looked.
		Path wrong = Files.createDirectories(work.resolve("wrong set"));
		for (String name : List.of("patched-mc-merged-26.2.jar", "forge-runtime-interop.jar", "neoforge-runtime.jar")) {
			try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(wrong.resolve(name)))) {
				out.putNextEntry(new ZipEntry("com/google/gson/Gson.class"));
				out.closeEntry();
			}
		}
		log = install("\"" + mcDir + "\"", " \"" + wrong + "\" ");
		requireFailed(log, "Install failed: Built artifacts: these files are not the game files Forbric needs");
		require(log.contains("Minecraft directory: " + mcDir), "the game directory was not the unquoted folder: " + log);
		require(String.join("\n", log).contains(wrong.resolve("neoforge-runtime.jar").toString()),
				"the refusal does not name the files in the unquoted folder: " + log);
		require(!Files.exists(mcDir.resolve("versions")), "something was written for a refused set");
		checks += 3;

		// ---- InstallerText: the window's title and the install's last line name the two ecosystems Forbric loads ----
		// Since Stage 3 of dropping MinecraftForge the kernel refuses a MinecraftForge-only mod; telling the player
		// that MinecraftForge mods "all go in" the mods folder would send them straight into that refusal.
		String previous = System.getProperty("forbric.dialogLanguage");
		try {
			Path mods = work.resolve("mods");
			System.setProperty("forbric.dialogLanguage", "en");
			String en = InstallerText.modsFolder(mods, "26.2-forbric");
			require(en.startsWith("Fabric and NeoForge mods go in " + mods) && en.contains("versions/26.2-forbric/mods")
					&& en.contains("Forbric no longer loads MinecraftForge mods"), "English mods-folder line: " + en);
			require(InstallerText.title().equals("Forbric — Fabric and NeoForge mods in one game"),
					"English title: " + InstallerText.title());
			System.setProperty("forbric.dialogLanguage", "zh_CN");
			String zh = InstallerText.modsFolder(mods, "26.2-forbric");
			require(zh.startsWith("Fabric 和 NeoForge 的 mod 都放进 " + mods) && zh.contains("versions/26.2-forbric/mods")
					&& zh.contains("Forbric 不再加载 MinecraftForge 版的 mod"), "Chinese mods-folder line: " + zh);
			require(InstallerText.title().contains("Fabric 和 NeoForge"), "Chinese title: " + InstallerText.title());
			for (String line : List.of(en, zh, InstallerText.title())) {
				require(!line.contains("MinecraftForge and NeoForge") && !line.contains("Forge and NeoForge mods all"),
						"a sentence still offers MinecraftForge mods: " + line);
			}
		} finally {
			if (previous == null) System.clearProperty("forbric.dialogLanguage");
			else System.setProperty("forbric.dialogLanguage", previous);
		}
		checks += 5;

		System.out.println("PASS installer window input: " + checks + " checks (quoted, blank and invalid paths;"
				+ " every failure reaches the window's log; the which-mods sentences in English and Chinese)");
	}

	/** Install as the button does, returning the window's log; nothing may escape. */
	private static List<String> install(String dirText, String artifactText) {
		List<String> log = new ArrayList<>();
		InstallerGui.installFromFields(InstallerGui.DEFAULT_VERSION, dirText, artifactText, log::add);
		return log;
	}

	private static void requireFailed(List<String> log, String expected) {
		require(log.stream().anyMatch(line -> line.startsWith(expected)),
				"the log does not say \"" + expected + "\":\n" + String.join("\n", log));
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
