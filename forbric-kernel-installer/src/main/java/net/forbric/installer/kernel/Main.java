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

package net.forbric.installer.kernel;

import java.nio.file.Path;

/**
 * Double-clicked, this opens the window; run with arguments, it installs and exits, which is what the gate does.
 *
 * <pre>
 *   java -jar forbric-kernel-installer.jar                       # window
 *   java -jar forbric-kernel-installer.jar --dir DIR [options]   # install, no window
 *       --mc 26.2            the base version (default 26.2)
 *       --artifacts DIR      where the locally built game jars are
 *       --jdk PATH           a JVM to build the game artifacts with
 *       --remote             take Forbric's jars from the release even if they are carried here
 *       --release TAG        install a specific release (this discards the compiled-in manifest pin)
 *       --mirror PREFIX      a relay in front of github.com, for networks where it is slow or blocked
 *       --offline            never touch the network for Forbric's own jars
 *   java -jar forbric-kernel-installer.jar --doctor [options]    # check only, writes nothing
 * </pre>
 */
public final class Main {
	private Main() {
	}

	/**
	 * Steers Java2D off Direct3D on Windows, before anything can load an AWT class.
	 *
	 * <p>Swing's Windows pipeline draws through D3D by default, and an overlay that hooks the D3D device to draw
	 * its own UI on top — NVIDIA's is the one seen here — corrupts what Swing renders into it: the window comes
	 * up in the wrong colours entirely. Nothing is actually wrong with the install underneath, which is worse
	 * than a crash, because it looks like the installer is broken.
	 *
	 * <p>GDI costs nothing on a window that is a few labels and a text area. Set only if the user has not chosen
	 * a value themselves, and only on Windows, so this never overrides a deliberate {@code -Dsun.java2d.d3d}.
	 */
	private static void avoidOverlayRenderingCorruption() {
		if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return;
		if (System.getProperty("sun.java2d.d3d") != null) return;
		System.setProperty("sun.java2d.d3d", "false");
	}

	public static void main(String[] args) {
		avoidOverlayRenderingCorruption();
		// No arguments means the window; any argument at all means a headless install. There is deliberately no
		// --headless flag, because there is no combination of arguments it could change the meaning of.
		if (args.length == 0) {
			if (java.awt.GraphicsEnvironment.isHeadless()) {
				System.err.println("no display available, and no arguments given");
				usage(System.err);
				System.exit(2);
			}
			InstallerGui.open();
			return;
		}
		Path dir = null;
		Path artifacts = null;
		Path jdk = null;
		boolean doctor = false;
		boolean forceRemote = false;
		boolean offline = false;
		String releaseTag = null;
		String mirror = null;
		String mcVersion = InstallerGui.DEFAULT_VERSION;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--dir" -> dir = Util.path(require(args, ++i, "--dir"));
				case "--artifacts" -> artifacts = Util.path(require(args, ++i, "--artifacts"));
				case "--jdk" -> jdk = Util.path(require(args, ++i, "--jdk"));
				case "--mc" -> mcVersion = require(args, ++i, "--mc");
				case "--doctor" -> doctor = true;
				case "--remote" -> forceRemote = true;
				case "--offline" -> offline = true;
				case "--release" -> releaseTag = require(args, ++i, "--release");
				case "--mirror" -> mirror = require(args, ++i, "--mirror");
				case "--help", "-h" -> {
					usage(System.out);
					return;
				}
				default -> {
					System.err.println("unknown option: " + args[i]);
					usage(System.err);
					System.exit(2);
				}
			}
		}
		// --doctor answers "would this work here", so it must run on a machine where nothing is set up yet:
		// --dir is optional and falls back to wherever the launcher would keep Minecraft.
		if (doctor) {
			Path target = dir != null ? dir : Util.defaultMinecraftDir();
			System.exit(new Doctor(System.out::println).examine(target, jdk, artifacts).ok() ? 0 : 1);
		}
		if (dir == null) {
			System.err.println("--dir is required when running without a window");
			usage(System.err);
			System.exit(2);
		}
		// --offline rules the release out entirely; anything else keeps it attached, as the payload's fallback
		// when a jar is missing from it and as the only source when this installer is slim.
		RemoteSource remote = offline ? null
				: RemoteSource.create(new Http(System.out::println), System.out::println, releaseTag, mirror,
						forceRemote);
		if (remote == null && !Installer.hasBundledManifest()) {
			System.err.println("this installer carries no jars and --offline forbids fetching them from the"
					+ " release; drop --offline, or use an installer built with its payload");
			System.exit(2);
		}
		System.out.println("Forbric installer");
		System.out.println("  minecraft dir : " + dir);
		System.out.println("  game version  : " + mcVersion);
		System.out.println("  Forbric jars  : " + describeSource(remote, forceRemote));
		System.out.println();
		try {
			new Installer(System.out::println).install(dir, mcVersion, artifacts, jdk, remote);
		} catch (Exception e) {
			System.err.println("install failed: " + e.getMessage());
			System.exit(1);
		}
	}

	/** Says where Forbric's own jars will come from, before any of them is fetched. */
	private static String describeSource(RemoteSource remote, boolean forceRemote) {
		boolean bundled = Installer.hasBundledManifest();
		if (remote == null) return "bundled (offline)";
		if (forceRemote || !bundled) return "GitHub release " + remote.describe();
		return "bundled (release " + remote.describe() + " as fallback)";
	}

	private static String require(String[] args, int i, String option) {
		if (i >= args.length) {
			System.err.println(option + " needs a value");
			System.exit(2);
		}
		return args[i];
	}

	private static void usage(java.io.PrintStream out) {
		out.println("usage: java -jar forbric-kernel-installer.jar [--dir DIR [options]]");
		out.println("       java -jar forbric-kernel-installer.jar --doctor [--dir DIR] [--jdk PATH]");
		out.println("       with no arguments, opens the installer window");
		out.println();
		out.println("  --dir DIR        the Minecraft directory to install into");
		out.println("  --mc " + Pins.MINECRAFT + "        the base version (default " + Pins.MINECRAFT + ")");
		out.println("  --artifacts DIR  where prebuilt game jars are, instead of building them");
		out.println("  --jdk PATH       a JVM (Java " + JdkLocator.MINIMUM_FEATURE
				+ "+) to build the game artifacts with");
		out.println("  --doctor         report whether an install would work here, and write nothing");
		out.println("  --remote         take Forbric's jars from the release even if carried here");
		out.println("  --release TAG    install a specific release (discards the compiled-in manifest pin)");
		out.println("  --mirror PREFIX  a relay in front of github.com (e.g. https://ghproxy.example/)");
		out.println("  --offline        never touch the network for Forbric's own jars");
	}
}
