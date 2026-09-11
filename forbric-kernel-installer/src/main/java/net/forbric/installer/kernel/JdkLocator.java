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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Finds a {@code java} launcher new enough to run the build tools the install needs.
 *
 * <p>The bar is {@value #MINIMUM_FEATURE}, and it is set by NeoFormRuntime: every class under
 * {@code net/neoforged/neoform/runtime/} in {@code neoform-runtime-2.0.18-all.jar} is class-file major 65.
 * Nothing else in the chain asks for more — the merge tools are compiled to 17, and every other tool runs as a
 * subprocess of whichever JVM we hand it. In particular <strong>no step needs {@code javac}</strong>: that was
 * only ever true of NeoForm's {@code recompile} node, which calls {@code ToolProvider.getSystemJavaCompiler()}
 * in-process, and Forbric takes the {@code gameJarNoRecomp} result instead — binary patches, no compiler.
 *
 * <p>Search order is cheapest-first: the JVM already running, then the Minecraft launcher's own runtimes, then
 * whatever the system has. There is deliberately <em>no</em> download-a-JDK fallback; on every machine looked at
 * so far the launcher alone settles it, and a 110 MB download for a case nobody has hit is not worth the code.
 *
 * <p>Two things about the launcher's runtimes, both measured on a real player's machine (Windows 10.0.26200,
 * 2026-09-11) rather than taken from Mojang's documentation:
 * <ul>
 *   <li>They are full JDKs. That machine had <em>no</em> system Java at all — empty {@code JAVA_HOME}, nothing
 *       on {@code PATH}, nothing under Program Files — yet {@code java-runtime-delta} (21.0.7) and
 *       {@code java-runtime-epsilon} (25.0.1) both ship {@code bin/javac.exe} beside {@code bin/java.exe}.</li>
 *   <li>The layout is <strong>flat</strong> there — {@code <runtime>/bin/java.exe} — not the nested
 *       {@code <runtime>/<os-arch>/<runtime>/bin} Mojang's manifests describe, and macOS adds a third shape
 *       ({@code jre.bundle/Contents/Home/bin}). So this walks the tree for the executable rather than guessing a
 *       depth; guessing is how a launcher-only machine gets told it has no Java.</li>
 * </ul>
 *
 * <p>Every candidate is <em>probed</em>, never trusted: a path that exists proves nothing about what runs, and a
 * jlink'd image can be missing pieces while still looking like a JDK from the outside.
 */
final class JdkLocator {

	/** NeoFormRuntime's own classes are class-file major 65; nothing in the chain needs more. */
	static final int MINIMUM_FEATURE = 21;

	/** How long to wait for a candidate to report its version before writing it off as unusable. */
	private static final int PROBE_TIMEOUT_SECONDS = 20;

	private JdkLocator() {
	}

	/** A usable JVM: the {@code java} launcher and the feature version it reported. */
	record Jvm(Path javaBin, int feature, String source) {
		@Override
		public String toString() {
			return javaBin + " (Java " + feature + ", " + source + ")";
		}
	}

	/**
	 * Finds a JVM of at least {@link #MINIMUM_FEATURE}.
	 *
	 * @param mcDir    the Minecraft directory, whose {@code runtime/} holds the launcher's own JVMs; may be null
	 * @param explicit a {@code java} launcher, a JDK home, or null — named by {@code --jdk}, tried first and, if
	 *                 it is unusable, reported as an error rather than silently skipped
	 * @throws IOException if nothing usable was found, listing what was tried
	 */
	static Jvm locate(Path mcDir, Path explicit, Consumer<String> log) throws IOException {
		List<String> rejected = new ArrayList<>();

		if (explicit != null) {
			Path bin = asJavaBin(explicit);
			int feature = probeFeature(bin);
			if (feature >= MINIMUM_FEATURE) return found(new Jvm(bin, feature, "--jdk"), log);
			throw new IOException("--jdk " + explicit + " is not usable: "
					+ (feature < 0 ? "it did not run" : "it is Java " + feature + ", and Java " + MINIMUM_FEATURE
					+ " or newer is needed"));
		}

		// 1) This JVM. Free — no process to start — and on a machine where the launcher started us, it IS the
		//    launcher's runtime, which keeps every tool on one Java.
		int own = Runtime.version().feature();
		if (own >= MINIMUM_FEATURE) {
			return found(new Jvm(Path.of(ForgeTool.javaBin()), own, "the JVM running this installer"), log);
		}
		rejected.add("this installer's own JVM (Java " + own + ")");

		// 2) The launcher's runtimes. A Minecraft player has these even when they have no other Java, and 26.2
		//    declares javaVersion 25, so the launcher has already fetched one new enough.
		for (Path candidate : launcherRuntimes(mcDir)) {
			int feature = probeFeature(candidate);
			if (feature >= MINIMUM_FEATURE) {
				return found(new Jvm(candidate, feature, "the Minecraft launcher's runtime"), log);
			}
			rejected.add(candidate + (feature < 0 ? " (did not run)" : " (Java " + feature + ")"));
		}

		// 3) Whatever the system has.
		for (Path candidate : systemJavas()) {
			int feature = probeFeature(candidate);
			if (feature >= MINIMUM_FEATURE) return found(new Jvm(candidate, feature, "the system"), log);
			rejected.add(candidate + (feature < 0 ? " (did not run)" : " (Java " + feature + ")"));
		}

		StringBuilder message = new StringBuilder("no Java ").append(MINIMUM_FEATURE)
				.append(" or newer was found, and the install needs one to build the game artifacts.");
		if (!rejected.isEmpty()) {
			message.append("\nTried:");
			for (String r : rejected) message.append("\n  - ").append(r);
		}
		message.append("\nStart Minecraft 26.2 once so the launcher downloads its runtime, or pass --jdk <path>.");
		throw new IOException(message.toString());
	}

	private static Jvm found(Jvm jvm, Consumer<String> log) {
		log.accept("Java " + jvm.feature() + ": " + jvm.javaBin() + "  (" + jvm.source() + ")");
		return jvm;
	}

	/**
	 * Runs a candidate and reads the feature version out of it, or -1 if it could not be run.
	 *
	 * <p>{@code -XshowSettings:properties} is used rather than parsing {@code -version}'s banner because the
	 * banner's shape is a vendor's choice while {@code java.specification.version} is the platform's own answer.
	 */
	static int probeFeature(Path javaBin) {
		if (javaBin == null || !Files.isRegularFile(javaBin)) return -1;
		Process p = null;
		try {
			p = new ProcessBuilder(javaBin.toString(), "-XshowSettings:properties", "-version")
					.redirectErrorStream(true)
					.start();
			p.getOutputStream().close();
			String version = null;
			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					int eq = line.indexOf('=');
					if (eq < 0) continue;
					String key = line.substring(0, eq).trim();
					if (key.equals("java.specification.version")) {
						version = line.substring(eq + 1).trim();
						break;
					}
				}
			}
			// Drain-then-wait: a candidate that hangs is a candidate we do not want, and waiting forever on one
			// turns "pick a JVM" into the installer's own hang.
			if (!p.waitFor(PROBE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) return -1;
			return version == null ? -1 : featureOf(version);
		} catch (IOException | InterruptedException notUsable) {
			if (notUsable instanceof InterruptedException) Thread.currentThread().interrupt();
			return -1;
		} finally {
			if (p != null && p.isAlive()) p.destroyForcibly();
		}
	}

	/** {@code "25.0.1"} and {@code "25"} are both Java 25; {@code "1.8"} is Java 8. */
	private static int featureOf(String specVersion) {
		try {
			String v = specVersion.startsWith("1.") ? specVersion.substring(2) : specVersion;
			int dot = v.indexOf('.');
			return Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
		} catch (RuntimeException unparseable) {
			return -1;
		}
	}

	/** Accepts either the {@code java} launcher itself or a JDK/JRE home, and returns the launcher. */
	private static Path asJavaBin(Path pathOrHome) {
		if (Files.isRegularFile(pathOrHome)) return pathOrHome;
		Path direct = pathOrHome.resolve("bin").resolve(exeName());
		if (Files.isRegularFile(direct)) return direct;
		// macOS JDKs are often named by their bundle root.
		Path bundle = pathOrHome.resolve("Contents").resolve("Home").resolve("bin").resolve(exeName());
		return Files.isRegularFile(bundle) ? bundle : direct;
	}

	private static String exeName() {
		return isWindows() ? "java.exe" : "java";
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}

	/**
	 * Every {@code java} launcher under the launcher's {@code runtime/} directories, newest-looking last so the
	 * caller meets them in a stable order. Walked rather than composed from a template — see the class javadoc.
	 *
	 * <p>Package-private rather than private so {@link Doctor} can report what it sees. On a developer's machine
	 * the search never runs — the JVM already in hand is new enough and wins at step 1 — which would leave the
	 * one piece written for a player-only machine untested everywhere it is easy to test.
	 */
	static List<Path> launcherRuntimes(Path mcDir) {
		Set<Path> roots = new LinkedHashSet<>();
		if (mcDir != null) roots.add(mcDir.resolve("runtime"));
		String home = System.getProperty("user.home", "");
		if (!home.isEmpty()) {
			roots.add(Path.of(home, "Library", "Application Support", "minecraft", "runtime"));
			roots.add(Path.of(home, ".minecraft", "runtime"));
		}
		String appData = System.getenv("APPDATA");
		if (appData != null && !appData.isEmpty()) roots.add(Path.of(appData, ".minecraft", "runtime"));

		String exe = exeName();
		Set<Path> hits = new LinkedHashSet<>();
		for (Path root : roots) {
			if (!Files.isDirectory(root)) continue;
			// Depth 6 clears both the flat <runtime>/bin and the nested <runtime>/<os-arch>/<runtime>/bin, plus
			// macOS's jre.bundle/Contents/Home/bin, without walking a whole disk if someone symlinks oddly.
			try (Stream<Path> walk = Files.walk(root, 6)) {
				walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals(exe))
						.filter(Files::isRegularFile)
						.forEach(hits::add);
			} catch (IOException unreadable) {
				// A runtime directory we cannot read is one we cannot use; the next root may still work.
			}
		}
		List<Path> ordered = new ArrayList<>(hits);
		// "epsilon" > "delta" > "gamma" alphabetically, which is also newest-first for Mojang's component names;
		// reversed so the newest is tried first without hardcoding the component list.
		ordered.sort((a, b) -> b.toString().compareToIgnoreCase(a.toString()));
		return ordered;
	}

	/** {@code JAVA_HOME}, then {@code PATH}, then the usual per-OS install roots. */
	private static List<Path> systemJavas() {
		Set<Path> out = new LinkedHashSet<>();
		String javaHome = System.getenv("JAVA_HOME");
		if (javaHome != null && !javaHome.isBlank()) out.add(asJavaBin(Path.of(javaHome)));

		String path = System.getenv("PATH");
		if (path != null) {
			for (String dir : path.split(java.io.File.pathSeparator)) {
				if (dir.isBlank()) continue;
				Path candidate = Path.of(dir).resolve(exeName());
				// macOS ships a /usr/bin/java stub that exists, runs, and only nags about installing a JDK.
				// Probing sorts it out, but skipping it keeps the rejection list readable.
				if (Files.isRegularFile(candidate) && !candidate.toString().equals("/usr/bin/java")) out.add(candidate);
			}
		}

		String exe = exeName();
		List<Path> roots = new ArrayList<>();
		String home = System.getProperty("user.home", "");
		if (isWindows()) {
			for (String env : new String[] {"ProgramFiles", "ProgramFiles(x86)"}) {
				String pf = System.getenv(env);
				if (pf != null && !pf.isBlank()) {
					roots.add(Path.of(pf, "Java"));
					roots.add(Path.of(pf, "Eclipse Adoptium"));
					roots.add(Path.of(pf, "Microsoft"));
				}
			}
		} else {
			roots.add(Path.of("/usr/lib/jvm"));
			roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
			if (!home.isEmpty()) roots.add(Path.of(home, "Library", "Java", "JavaVirtualMachines"));
		}
		for (Path root : roots) {
			if (!Files.isDirectory(root)) continue;
			try (Stream<Path> walk = Files.walk(root, 5)) {
				walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals(exe))
						.filter(Files::isRegularFile)
						.forEach(out::add);
			} catch (IOException unreadable) {
				// Same as above: skip what we cannot read.
			}
		}
		return new ArrayList<>(out);
	}
}
