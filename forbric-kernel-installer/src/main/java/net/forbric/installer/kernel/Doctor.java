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
 * <p>It exists because the expensive part of a Forbric install is not writing the profile — it is building three
 * jars the project is not allowed to hand out, which takes a JVM, a few hundred megabytes of downloads and
 * several minutes. Finding out that the machine cannot do that <em>after</em> a user has waited through most of
 * it is the failure mode worth designing away, so every precondition is resolved up front and printed.
 *
 * <p>The one invariant: this writes nothing, creates no directories, and downloads nothing. A gate asserts it.
 */
final class Doctor {

	/**
	 * Peak and resident disk, in megabytes, for a cold install.
	 *
	 * <p>Measured from the caches this project already produced rather than estimated: NFRT's artifacts and
	 * intermediates for one version, the Forge download+work trees, and the six outputs. The binary-patch path
	 * skips the decompiler, so the NFRT half is smaller than the recompile path's — but it has not been measured
	 * cold yet, and the number below is still the recompile-path measurement, deliberately left high rather than
	 * guessed down.
	 */
	private static final int PEAK_MB = 730;
	private static final int RESIDENT_MB = 190;

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

		// Prebuilt artifacts short-circuit the whole build, so say plainly which ones are already here.
		Map<String, Boolean> artifacts = new LinkedHashMap<>();
		Map<String, Path> located = Map.of();
		try {
			if (artifactDir != null) located = GameArtifacts.locate(Pins.MINECRAFT, artifactDir).all();
		} catch (IOException someMissing) {
			// Expected on a machine that has never built them; the per-name report below is the useful answer.
		}
		for (String coordinate : new String[] {
				"net.forbric:patched-mc-merged", "net.forbric:forge-runtime", "net.forbric:neoforge-runtime"}) {
			artifacts.put(coordinate, located.containsKey(coordinate));
		}
		log.accept("");
		log.accept("game artifacts: " + (artifactDir == null ? "none supplied (--artifacts), they will be built"
				: "looking in " + artifactDir));
		for (Map.Entry<String, Boolean> e : artifacts.entrySet()) {
			log.accept("    " + (e.getValue() ? "present" : "to build") + "  " + e.getKey());
		}

		boolean allPresent = artifacts.values().stream().allMatch(Boolean::booleanValue);
		log.accept("");
		if (allPresent) {
			log.accept("disk          : nothing to build — the three artifacts are already here");
		} else {
			log.accept("disk          : about " + PEAK_MB + " MB at peak, about " + RESIDENT_MB
					+ " MB kept afterwards");
		}

		log.accept("");
		if (problem != null) {
			log.accept("RESULT: this machine cannot build the game artifacts yet.");
			log.accept(problem);
		} else if (allPresent) {
			log.accept("RESULT: ready to install, with no build needed.");
		} else {
			log.accept("RESULT: ready to install; the game artifacts will be built here first.");
		}
		return new Report(mcDir, mcDirExists, baseInstalled, jvm, problem, artifacts);
	}
}
