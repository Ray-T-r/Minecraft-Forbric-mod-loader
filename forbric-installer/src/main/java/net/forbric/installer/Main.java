package net.forbric.installer;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.SwingUtilities;

/**
 * Entry point. With no args (and a display available) it opens the Swing GUI; otherwise it runs headless.
 *
 * <pre>
 *   java -jar forbric-installer.jar                       # GUI
 *   java -jar forbric-installer.jar --headless \
 *        --mc-dir "~/Library/Application Support/minecraft" \
 *        --game-version 1.21.11 \
 *        --manifest /path/to/forbic-loader/build/forbric-libraries.json
 * </pre>
 */
public final class Main {

	public static void main(String[] args) throws Exception {
		Map<String, String> opt = parseOpts(args);
		if (opt.containsKey("help") || opt.containsKey("h")) {
			printUsage();
			return;
		}

		Path mcDir = opt.containsKey("mc-dir") ? Util.path(opt.get("mc-dir")) : Util.defaultMinecraftDir();
		// Mode: intermediary-v1 (default, MC 1.21.11) or full-forge-26.2 (genuine Forge lifecycle, MC 26.2). The
		// mode picks the default base version when --game-version is not given.
		String mode = opt.getOrDefault("mode", Installer.MODE_INTERMEDIARY_V1);
		boolean fullForge = Installer.MODE_FULL_FORGE_26_2.equals(mode);
		String mcVersion = opt.getOrDefault("game-version", fullForge ? "26.2" : "1.21.11");
		// Dev override only: without --manifest we use the manifest + jars bundled inside this installer jar.
		String explicitManifest = opt.get("manifest");
		Path manifest = (explicitManifest != null && !explicitManifest.isBlank()) ? Util.path(explicitManifest) : null;
		// Auto-download the base vanilla version if it is missing (default on; --no-download-mc opts out).
		boolean autoDownloadBase = !opt.containsKey("no-download-mc");

		boolean headless = opt.containsKey("headless") || GraphicsEnvironment.isHeadless();
		if (headless) {
			runCli(mcDir, mcVersion, mode, manifest, autoDownloadBase);
		} else {
			final Path mf = manifest;
			final String m = mode;
			SwingUtilities.invokeLater(() -> new InstallerGui(mcDir, mcVersion, m, mf, autoDownloadBase).show());
		}
	}

	private static void runCli(Path mcDir, String mcVersion, String mode, Path manifest, boolean autoDownloadBase) {
		System.out.println("Forbric installer (headless)");
		System.out.println("  minecraft dir : " + mcDir);
		System.out.println("  mode          : " + mode);
		System.out.println("  game version  : " + mcVersion);
		System.out.println("  manifest      : " + (manifest != null ? manifest : "(bundled)"));
		System.out.println("  download base : " + autoDownloadBase);
		System.out.println();
		if (manifest == null && !Installer.hasBundledManifest()) {
			System.err.println("ERROR: this installer jar carries no bundled manifest. Rebuild it with "
					+ "`./gradlew jar` in forbric-installer/ (that bundles Forbric's jars), or pass "
					+ "--manifest <forbric-libraries.json> for a dev build.");
			System.exit(2);
		}
		try {
			if (!Files.isDirectory(mcDir)) Files.createDirectories(mcDir);
			Installer installer = (manifest != null) ? Installer.fromManifest(manifest) : Installer.fromBundledManifest();
			installer.install(mcDir, mcVersion, mode, autoDownloadBase, System.out::println);
		} catch (IOException e) {
			System.err.println("ERROR: " + e.getMessage());
			System.exit(1);
		}
	}

	private static Map<String, String> parseOpts(String[] args) {
		Map<String, String> opt = new LinkedHashMap<>();
		for (int i = 0; i < args.length; i++) {
			String a = args[i];
			if (a.startsWith("--")) a = a.substring(2);
			else if (a.startsWith("-")) a = a.substring(1);
			else continue;
			if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
				opt.put(a, args[++i]);
			} else {
				opt.put(a, "true");
			}
		}
		return opt;
	}

	private static void printUsage() {
		System.out.println("Forbric installer — make the Forbric loader launchable from PCL2 / HMCL.");
		System.out.println();
		System.out.println("Usage:");
		System.out.println("  java -jar forbric-installer.jar                 open the GUI");
		System.out.println("  java -jar forbric-installer.jar --headless [opts]  install without a GUI");
		System.out.println();
		System.out.println("Options:");
		System.out.println("  --mc-dir <path>        Minecraft directory (default: OS launcher dir)");
		System.out.println("  --mode <mode>          intermediary-v1 (default) | full-forge-26.2");
		System.out.println("                         full-forge-26.2 builds the real Forge runtime at install time");
		System.out.println("  --game-version <id>    base MC version (default: 1.21.11, or 26.2 for full-forge-26.2)");
		System.out.println("  --no-download-mc       do NOT download the base version if it is missing");
		System.out.println("  --manifest <path>      dev override: external forbric-libraries.json");
		System.out.println("                         (default: the manifest + jars bundled in this installer)");
		System.out.println("  --headless             no GUI");
		System.out.println("  --help                 this message");
	}

	// Kept for potential reuse by callers/tests.
	static List<String> baseVersions(Path mcDir) {
		return Installer.discoverBaseVersions(mcDir);
	}

	private Main() {}
}
