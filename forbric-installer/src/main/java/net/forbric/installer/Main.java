/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.installer;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

/**
 * Entry point. With no args (and a display available) it opens the Swing GUI; otherwise it runs headless.
 *
 * <pre>
 *   java -jar forbric-installer.jar                       # GUI
 *   java -jar forbric-installer.jar --headless \
 *        --mc-dir "~/Library/Application Support/minecraft" \
 *        --game-version 1.21.11 \
 *        --manifest /path/to/forbric-loader/build/forbric-libraries.json
 * </pre>
 *
 * <p>Where the jars come from, in order: one bundled inside this installer, then a local build named by
 * {@code --manifest}, then the published GitHub release. An installer built without a payload therefore still
 * installs — it downloads. {@code --remote} forces the release even when a payload is bundled, and
 * {@code --offline} forbids the network entirely.
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
		Remote remote = Remote.from(opt);

		boolean headless = opt.containsKey("headless") || GraphicsEnvironment.isHeadless();
		if (headless) {
			runCli(mcDir, mcVersion, mode, manifest, autoDownloadBase, remote);
		} else {
			final Path mf = manifest;
			final String m = mode;
			SwingUtilities.invokeLater(() ->
					new InstallerGui(mcDir, mcVersion, m, mf, autoDownloadBase, remote).show());
		}
	}

	/**
	 * The release-download options, parsed once and passed to whichever front end runs. Kept as a small value so
	 * the CLI and the GUI cannot drift on what {@code --remote}/{@code --offline} mean.
	 */
	static final class Remote {
		/** Fetch the manifest from the release even when this installer bundles one. */
		final boolean force;
		/** Never touch the network for Forbric's jars. */
		final boolean offline;
		/** An explicit release tag, or null for the one this installer was built against. */
		final String tag;
		/** A URL prefix for github.com requests (a ghproxy-style relay), or null. */
		final String mirror;

		private Remote(boolean force, boolean offline, String tag, String mirror) {
			this.force = force;
			this.offline = offline;
			this.tag = tag;
			this.mirror = mirror;
		}

		static Remote from(Map<String, String> opt) {
			return new Remote(opt.containsKey("remote"), opt.containsKey("offline"),
					blankToNull(opt.get("release")), blankToNull(opt.get("mirror")));
		}

		/** The source to download from, or null when {@code --offline} rules it out. */
		RemoteSource source(Consumer<String> log) {
			return offline ? null : RemoteSource.create(new Http(log), log, tag, mirror);
		}

		private static String blankToNull(String s) {
			return (s == null || s.isBlank()) ? null : s;
		}
	}

	private static void runCli(Path mcDir, String mcVersion, String mode, Path manifest, boolean autoDownloadBase,
			Remote remote) {
		Consumer<String> log = System.out::println;
		RemoteSource source = remote.source(log);
		boolean useRemote = source != null && (remote.force || (manifest == null && !Installer.hasBundledManifest()));

		System.out.println("Forbric installer (headless)");
		System.out.println("  minecraft dir : " + mcDir);
		System.out.println("  mode          : " + mode);
		System.out.println("  game version  : " + mcVersion);
		System.out.println("  jars from     : " + describeSource(manifest, useRemote, source));
		System.out.println("  download base : " + autoDownloadBase);
		System.out.println();
		if (manifest == null && !Installer.hasBundledManifest() && source == null) {
			System.err.println("ERROR: this installer jar carries no bundled manifest and --offline forbids "
					+ "downloading one. Drop --offline to fetch the release, rebuild the installer with "
					+ "`./gradlew jar` in forbric-installer/ to bundle the jars, or pass "
					+ "--manifest <forbric-libraries.json> for a dev build.");
			System.exit(2);
		}
		try {
			if (!Files.isDirectory(mcDir)) Files.createDirectories(mcDir);
			installer(manifest, useRemote, source).install(mcDir, mcVersion, mode, autoDownloadBase, log);
		} catch (IOException e) {
			System.err.println("ERROR: " + e.getMessage());
			System.exit(1);
		}
	}

	/**
	 * Pick the manifest source. Shared with the GUI so the two front ends cannot disagree: an explicit
	 * {@code --manifest} always wins, {@code --remote} (or having nothing local) goes to the release, and
	 * otherwise the bundled manifest is used with the release left attached as a fallback for missing entries.
	 */
	static Installer installer(Path manifest, boolean useRemote, RemoteSource source) throws IOException {
		if (manifest != null) return Installer.fromManifest(manifest).withRemote(source);
		if (useRemote) return Installer.fromRemote(source);
		return Installer.fromBundledManifest().withRemote(source);
	}

	static String describeSource(Path manifest, boolean useRemote, RemoteSource source) {
		if (manifest != null) return manifest.toString();
		if (useRemote) return "GitHub release " + source.describe();
		return source != null ? "bundled (release " + source.describe() + " as fallback)" : "bundled";
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
		System.out.println("  --remote               download Forbric's jars from the GitHub release even if");
		System.out.println("                         this installer bundles them");
		System.out.println("  --release <tag>        install a specific release tag instead of the one this");
		System.out.println("                         installer was built against");
		System.out.println("  --mirror <url-prefix>  put a relay in front of github.com, for networks where it");
		System.out.println("                         is slow or blocked (e.g. https://ghproxy.example/)");
		System.out.println("  --offline              never download Forbric's jars; fail if they are not local");
		System.out.println("  --headless             no GUI");
		System.out.println("  --help                 this message");
	}

	// Kept for potential reuse by callers/tests.
	static List<String> baseVersions(Path mcDir) {
		return Installer.discoverBaseVersions(mcDir);
	}

	private Main() {}
}
