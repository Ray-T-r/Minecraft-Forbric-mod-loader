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
import java.util.List;
import java.util.function.Consumer;

/**
 * Produces NeoForge's patched Minecraft by driving NeoFormRuntime, the tool NeoForge itself uses.
 *
 * <p>This is the one artifact the project had no automation for at all: {@code patched-mc-neoforge-26.2.jar} was
 * a file hand-copied out of a developer's {@code ~/.neoformruntime} cache. Reimplementing what it does is not a
 * project — decompiling, patching and recompiling Minecraft <em>is</em> NeoForm — so the published tool gets
 * driven instead.
 *
 * <h2>Why the binary-patch result</h2>
 *
 * <p>NFRT's graph offers nine named results. The obvious one, {@code gameJar}, comes off the {@code recompile}
 * node: decompile with Vineflower, apply patches, recompile with an in-process {@code javac}. It works — its
 * output was verified byte-for-byte against the reference, sha1
 * {@code 5b2970209ee12702117309576b08521aa38ae67b} — but it costs a 4 GB decompiler heap, a JDK new enough to
 * compile {@code --release 25}, and a couple of minutes, and its bytes depend on a compiler version nobody here
 * controls. That last part matters more than it looks: {@code MergedBaseBuilder} downstream was calibrated
 * against one observed NeoForge jar.
 *
 * <p>{@link Pins#NFRT_RESULT} takes {@code gameJarNoRecomp} instead — {@code preProcessJar → binaryPatch →
 * copyUnpatchedClasses → applyDevTransforms}, no decompiler and no compiler. Measured: six seconds, the same
 * 10,963 classes, and a merge whose conflict report is identical <em>as a set</em> to the recompile path's with a
 * merged base carrying the same 30,471 entries. The class bytes do differ from the recompile path's, in one
 * systematic way — the binary-patched classes keep Mojang's {@code MethodParameters} attribute, which
 * {@code javac} drops without {@code -parameters} — which is metadata, not semantics.
 */
final class NfrtRunner {

	private static final String NEOFORGED_MVN = "https://maven.neoforged.net/releases";

	/** NFRT asks for about 200 MB on a cold cache and forks nothing heavy on this path. */
	private static final String HEAP = "-Xmx3g";

	private final Http http;
	private final Path toolsDir;
	private final Path nfrtHome;
	private final Path workDir;
	private final Consumer<String> log;

	/**
	 * @param toolsDir where the NFRT fat jar is cached
	 * @param nfrtHome NFRT's own artifact cache — deliberately inside the install's build directory rather than
	 *                 {@code ~/.neoformruntime}, so one directory holds everything an install created and a
	 *                 developer's own NeoForge work is never disturbed
	 */
	NfrtRunner(Http http, Path toolsDir, Path nfrtHome, Path workDir, Consumer<String> log) {
		this.http = http;
		this.toolsDir = toolsDir;
		this.nfrtHome = nfrtHome;
		this.workDir = workDir;
		this.log = log;
	}

	/**
	 * Runs NeoForm and writes {@link Pins#NFRT_RESULT} to {@code outJar}.
	 *
	 * @param jvm   a JVM to run NFRT under; NFRT's own classes are class-file major 65, so this must be Java 21+
	 * @param mcDir the Minecraft directory, handed to NFRT as a {@code --launcher-dir} so it reuses the client
	 *              and server jars the launcher already downloaded instead of fetching its own
	 */
	ArtifactResult run(JdkLocator.Jvm jvm, Path mcDir, Path outJar, String coordinate) throws IOException {
		if (Files.isRegularFile(outJar) && Files.size(outJar) > 0) {
			log.accept("[neoform] up-to-date: " + outJar.getFileName());
			return new ArtifactResult(coordinate, outJar, Util.sha1(outJar), Files.size(outJar));
		}
		Files.createDirectories(outJar.getParent());
		Files.createDirectories(nfrtHome);
		Files.createDirectories(workDir);

		Path tool = fetchTool();

		List<String> cmd = new ArrayList<>(List.of(
				jvm.javaBin().toString(), HEAP,
				"-jar", tool.toString(),
				"--home-dir", nfrtHome.toString(),
				"--work-dir", workDir.toString(),
				"--launcher-dir", mcDir.toString(),
				"run",
				// The bare net.neoforged:neoforge:<v> coordinate does not exist on the Maven; without the
				// classifier NFRT's ArtifactManager reports "Could not find ... in any repository".
				"--neoforge", Pins.neoforgeUserdevCoordinate(),
				"--dist", "joined",
				// NFRT runs cache maintenance on startup and will happily prune a shared cache; this build owns
				// its own cache directory and has nothing to maintain.
				"--disable-cache-maintenance",
				"--write-result=" + Pins.NFRT_RESULT + ":" + outJar));

		log.accept("[neoform] building NeoForge's patched Minecraft (" + Pins.NFRT_RESULT + ") …");
		List<String> tail = runAndCapture(cmd);

		if (!Files.isRegularFile(outJar) || Files.size(outJar) == 0) {
			String available = tail.stream().filter(l -> l.contains("Available results:")).findFirst().orElse(null);
			throw new IOException("NeoFormRuntime did not produce " + outJar.getFileName()
					+ (available == null ? "" : "\n" + available.trim())
					+ "\n" + String.join("\n", tail));
		}

		// The *WithNeoForge results fold NeoForge's own classes into the jar. Those classes also live in
		// neoforge-runtime.jar, so taking the wrong result defines each of them twice under the kernel's
		// classloader — a failure that shows up far from here, if it shows up at all.
		long neoforgeEntries = Zips.readAll(outJar).keySet().stream()
				.filter(n -> n.startsWith("net/neoforged/"))
				.count();
		if (neoforgeEntries > 0) {
			throw new IOException("NeoFormRuntime's " + Pins.NFRT_RESULT + " carries " + neoforgeEntries
					+ " net/neoforged/ entries; it must carry none (wrong result id?)");
		}

		long size = Files.size(outJar);
		log.accept("[neoform] wrote " + outJar.getFileName() + " (" + (size / (1024 * 1024)) + " MB)");
		return new ArtifactResult(coordinate, outJar, Util.sha1(outJar), size);
	}

	/** NeoFormRuntime's own fat jar, cached beside the other tools. */
	private Path fetchTool() throws IOException {
		String coordinate = Pins.nfrtCoordinate();
		String rel = Util.coordinateToPath(coordinate);
		Path dest = toolsDir.resolve(rel.substring(rel.lastIndexOf('/') + 1));
		Files.createDirectories(toolsDir);
		http.ensure(NEOFORGED_MVN + "/" + rel, dest);
		return dest;
	}

	/**
	 * Runs the command, streams its output into the log, and keeps the tail for an error message.
	 *
	 * <p>NFRT draws progress with carriage returns and ANSI colour; both are stripped so the installer's own log
	 * — which may be a Swing text pane — stays readable.
	 */
	private List<String> runAndCapture(List<String> cmd) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
		Process p = pb.start();
		List<String> tail = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				String clean = line.replaceAll("\\[[;\\d]*m", "").replace('\r', ' ').trim();
				if (clean.isEmpty()) continue;
				tail.add(clean);
				if (tail.size() > 80) tail.remove(0);
				log.accept("  " + clean);
			}
		}
		int exit;
		try {
			exit = p.waitFor();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
			throw new IOException("interrupted while running NeoFormRuntime", interrupted);
		}
		if (exit != 0) {
			throw new IOException("NeoFormRuntime exited " + exit + "\n" + String.join("\n", tail));
		}
		return tail;
	}
}
