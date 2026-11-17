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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Answers "would an install work here, and what would it cost?" without touching the disk.
 *
 * <p>It exists because the expensive part of a Forbric install is not writing the profile — it is building two
 * jars the project is not allowed to hand out, which takes a JVM, a few hundred megabytes of downloads and
 * several minutes. Finding out that the machine cannot do that <em>after</em> a user has waited through most of
 * it is the failure mode worth designing away, so every precondition is resolved up front and printed.
 *
 * <p>The one invariant: this writes nothing, creates no directories, and downloads nothing. A gate asserts it.
 */
final class Doctor {

	/**
	 * Peak and resident disk, in megabytes (MiB), for a cold install: what the install itself puts in
	 * {@code .forbric-build} and in the libraries it stages (Forbric's own under {@code net/forbric}, the kernel's few
	 * dependencies), not the vanilla game and libraries a launcher already keeps.
	 *
	 * <p>Measured on a cold NeoForge-only install (gate-m17's first install, {@code du -sk} of {@code .forbric-build} and
	 * {@code libraries/net/forbric} sampled every half second; Stage 4 C6, 2026-11-17): the size only grows during an
	 * install and nothing is deleted at its end, so the highest sample is the last one, 476,904 KiB (a sampler that
	 * slow misses only what lives under half a second; downloads are renamed into place, not copied). About 470 MiB
	 * with the kernel's third-party libraries: NFRT's cache for one version (~249) and its work files (~3), the
	 * downloads (~72: the NeoForge userdev, universal and runtime libraries and the 58 MB server jar), the three outputs
	 * in {@code out} (~66), their staged copies and the kernel jar in {@code libraries/net/forbric} (~70), the tools
	 * (~6), and the kernel's third-party libraries (~4). PEAK keeps a margin over that for those short-lived files.
	 * Before Stage 4 the install also built MinecraftForge's game and runtime and
	 * the merged base, and these numbers were 730 and 190 (estimated from earlier caches; what the 190 counted is not
	 * recorded, and a NeoForge-only install alone keeps more than that, mostly NFRT's cache). An upgrade over such an install
	 * holds the old files too until its last step, the clean-up of what nothing uses any more ({@link OldInstallCleanup}).
	 */
	private static final int PEAK_MB = 480;
	private static final int RESIDENT_MB = 470;

	private final Consumer<String> log;

	Doctor(Consumer<String> log) {
		this.log = log;
	}

	/** What the check found; {@code jvm} is null when no usable one exists, and {@code problem} says why. */
	record Report(Path mcDir, boolean mcDirExists, boolean baseVersionInstalled,
	              JdkLocator.Jvm jvm, String problem, Map<String, Boolean> artifacts) {

		boolean ok() {
			return problem == null;
		}
	}

	/**
	 * @param mcDir     the Minecraft directory to inspect
	 * @param explicitJdk a {@code --jdk} override, or null
	 * @param artifactDir where prebuilt game artifacts may already be, or null
	 */
	Report examine(Path mcDir, Path explicitJdk, Path artifactDir) {
		log.accept("Forbric installer — toolchain check");
		log.accept("");

		log.accept("platform      : " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
		log.accept("this JVM      : Java " + Runtime.version() + "  (" + System.getProperty("java.home") + ")");

		boolean mcDirExists = Files.isDirectory(mcDir);
		log.accept("minecraft dir : " + mcDir + (mcDirExists ? "" : "   [not found]"));

		Path baseJson = mcDir.resolve("versions").resolve(Pins.MINECRAFT).resolve(Pins.MINECRAFT + ".json");
		boolean baseInstalled = Files.isRegularFile(baseJson);
		log.accept("base " + Pins.MINECRAFT + "     : "
				+ (baseInstalled ? "installed" : "not installed — the installer will fetch it from Mojang"));

		log.accept("");
		log.accept("pins          : " + Pins.stamp());

		// Report every launcher runtime found, not just the JVM chosen: on most machines the installer's own JVM
		// is new enough and wins outright, so this is the only place the launcher-only path gets exercised.
		log.accept("");
		java.util.List<Path> launcherJvms = JdkLocator.launcherRuntimes(mcDir);
		if (launcherJvms.isEmpty()) {
			log.accept("launcher JVMs : none found under any runtime/ directory");
		} else {
			log.accept("launcher JVMs : " + launcherJvms.size() + " found");
			for (Path candidate : launcherJvms) {
				int feature = JdkLocator.probeFeature(candidate);
				log.accept("    " + (feature < 0 ? "unusable" : "Java " + feature) + "  " + candidate);
			}
		}

		log.accept("");
		JdkLocator.Jvm jvm = null;
		String problem = null;
		try {
			jvm = JdkLocator.locate(mcDir, explicitJdk, line -> log.accept("build JVM     : " + line));
		} catch (IOException noJvm) {
			problem = noJvm.getMessage();
			log.accept("build JVM     : NONE USABLE");
		}

		// Supplied artifacts replace the whole build, so say plainly which ones are here — and judge them the way an
		// install would. A supplied set that is incomplete, or holds files that are not what their names say, is
		// refused rather than built around, so "to build" would be a promise the install does not keep. Each file
		// is judged on its own: one missing file does not make the other one missing, and the ones that are here
		// are opened even so, so the answer to a half-filled directory is everything wrong with it at once.
		Map<String, Boolean> artifacts = new LinkedHashMap<>();
		Map<String, Path> located = Map.of();
		Map<String, String> wrong = Map.of();
		String artifactProblem = null;
		if (artifactDir != null) {
			GameArtifacts supplied = GameArtifacts.find(Pins.MINECRAFT, artifactDir);
			located = supplied.all();
			wrong = supplied.contentProblems(Pins.MINECRAFT);
			if (!wrong.isEmpty() || !supplied.missing().isEmpty()) {
				artifactProblem = supplied.refusal(wrong).getMessage();
			}
		}
		for (String coordinate : new String[] {ArtifactBuilder.BASE, ArtifactBuilder.NEOFORGE_RUNTIME}) {
			artifacts.put(coordinate, located.containsKey(coordinate));
		}
		log.accept("");
		log.accept("game artifacts: " + (artifactDir == null ? "none supplied (--artifacts), they will be built"
				: "looking in " + artifactDir));
		for (Map.Entry<String, Boolean> e : artifacts.entrySet()) {
			String state = !e.getValue() ? (artifactDir == null ? "to build" : "missing")
					: wrong.containsKey(e.getKey()) ? "WRONG FILE" : "present";
			log.accept("    " + state + "  " + e.getKey());
		}

		boolean allPresent = artifacts.values().stream().allMatch(Boolean::booleanValue);
		if (artifactDir == null) {
			log.accept("");
			log.accept("disk          : about " + PEAK_MB + " MB at peak, about " + RESIDENT_MB
					+ " MB kept afterwards");
		} else if (allPresent && artifactProblem == null) {
			log.accept("");
			log.accept("disk          : nothing to build — both artifacts are already here");
		}

		log.accept("");
		if (artifactProblem != null) {
			log.accept("RESULT: an install would refuse the supplied game artifacts.");
			log.accept(artifactProblem);
		}
		// Said after an artifact refusal too: otherwise fixing the artifacts just uncovers this on the next run.
		if (problem != null) {
			if (artifactProblem != null) log.accept("");
			log.accept((artifactProblem == null ? "RESULT: " : "ALSO: ") + (artifactDir == null
					? "this machine cannot build the game artifacts yet."
					: "this machine has no usable Java to link-check the supplied game artifacts with."));
			log.accept(problem);
		}
		if (artifactProblem == null && problem == null) {
			log.accept(allPresent ? "RESULT: ready to install, with no build needed."
					: "RESULT: ready to install; the game artifacts will be built here first.");
		}
		if (problem == null) problem = artifactProblem;
		return new Report(mcDir, mcDirExists, baseInstalled, jvm, problem, artifacts);
	}
}
