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
 * Builds the two jars a Forbric instance runs on, here, on the machine that will run them, and the vanilla reference
 * the kernel reads beside them.
 *
 * <p>They cannot be shipped. The base is Minecraft with NeoForge's patches applied, and the runtime is assembled from
 * NeoForge's own distribution. Both carry code this project has no right to hand out, so an installer that shipped
 * them would be redistributing Mojang's and NeoForge's work. Building them from the upstreams' own Mavens, on the
 * user's machine, is the only lawful shape this can take.
 *
 * <p>The pipeline:
 *
 * <pre>
 *   neoforge userdev ─┬→ neoforge-runtime
 *                     └→ NFRT (client jar + server jar seeded) → patched-mc-neoforge   (the game base)
 *
 *   patched-mc-neoforge + neoforge-runtime ──→ link check            (nothing may dangle)
 *   vanilla 26.2.jar + patched-mc-neoforge ──→ vanilla-reference     (resources only; see VanillaReference)
 * </pre>
 *
 * <p>Everything lands under {@code <mcDir>/.forbric-build/}, one directory that can be deleted wholesale, and
 * each step short-circuits on a finished output so an interrupted install resumes rather than restarts.
 */
final class ArtifactBuilder {

	/** The coordinates {@link Installer} stages and the profile names, without their version suffix. */
	static final String BASE = "net.forbric:patched-mc-neoforge";
	static final String NEOFORGE_RUNTIME = "net.forbric:neoforge-runtime";
	/** Not a game jar: the resources-only vanilla reference the kernel reads beside the base; see {@link VanillaReference}. */
	static final String VANILLA_REFERENCE = VanillaReference.COORDINATE;

	private final Consumer<String> log;

	ArtifactBuilder(Consumer<String> log) {
		this.log = log;
	}

	/**
	 * Produces both, and the vanilla reference, reusing whatever is already built.
	 *
	 * @param mcDir     the Minecraft directory; its {@code versions/<mc>/<mc>.jar} is the vanilla input and its
	 *                  {@code .forbric-build/} holds every intermediate
	 * @param jvm       the JVM the build tools run under
	 * @return coordinate (without version) to the finished file, in the shape {@link GameArtifacts#all()} returns
	 */
	Map<String, Path> build(Path mcDir, String mcVersion, JdkLocator.Jvm jvm) throws IOException {
		Path build = mcDir.resolve(".forbric-build");
		Path dl = build.resolve("dl");
		Path tools = build.resolve("tools");
		Path out = build.resolve("out");
		Files.createDirectories(dl);
		Files.createDirectories(out);

		Path vanilla = mcDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".jar");
		if (!Files.isRegularFile(vanilla)) {
			throw new IOException("the vanilla " + mcVersion + " jar is missing: " + vanilla);
		}

		log.accept("");
		log.accept("Building the game artifacts. The first run downloads a few hundred megabytes and takes");
		log.accept("several minutes; afterwards it is cached in " + build + ".");
		log.accept("pins: " + Pins.stamp());

		Http http = new Http(log);

		log.accept("");
		log.accept("== NeoForge " + Pins.NEOFORGE + " ==");
		NeoForgeArtifacts nfa = new NeoForgeArtifacts(mcVersion, Pins.NEOFORGE);
		Path neoUserdev = dl.resolve(userdevFile("neoforge", Pins.NEOFORGE));
		http.ensureWithFallback(nfa.neoforgedUrl(nfa.userdevCoordinate()), nfa.centralUrl(nfa.userdevCoordinate()),
				neoUserdev);

		ArtifactResult neoRuntime = new NeoForgeRuntimeBuilder(nfa, http, build,
				out.resolve("neoforge-runtime.jar"), log).build(NeoForgeArtifacts.readLibraries(neoUserdev));
		// The server jar is fetched by NfrtRunner itself, and only when NFRT is about to run (see NfrtRunner.run).
		ArtifactResult base = new NfrtRunner(http, tools, build.resolve("nfrt"), build.resolve("nfrt-work"), log)
				.run(jvm, mcDir, out.resolve("patched-mc-neoforge-" + mcVersion + ".jar"),
						nfa.patchedMcCoordinate(), mcVersion, dl.resolve("server.jar"));

		log.accept("");
		log.accept("== checking ==");
		new LinkCheckTool(tools, log).linkCheck(jvm, base.file, neoRuntime.file);
		ArtifactResult reference = vanillaReference(vanilla, base.file, out.resolve(VanillaReference.fileName(mcVersion)),
				log);

		Map<String, Path> result = new LinkedHashMap<>();
		result.put(BASE, base.file);
		result.put(NEOFORGE_RUNTIME, neoRuntime.file);
		result.put(VANILLA_REFERENCE, reference.file);

		log.accept("");
		log.accept("game artifacts ready:");
		for (Map.Entry<String, Path> e : result.entrySet()) {
			log.accept("  " + e.getKey() + "  →  " + e.getValue().getFileName()
					+ " (" + (Files.size(e.getValue()) / (1024 * 1024)) + " MB)");
		}
		return result;
	}

	/**
	 * The vanilla-reference sidecar for {@code base}: how vanilla compiled each class, beside the base rather than inside
	 * it — the kernel's only source of FABRIC references, since NeoForge's patched game carries none. Built in-process
	 * — it is a zip, a byte comparison and SHA-256 — and reused when it records these two inputs, which the pins stamp
	 * cannot say: a base can be rebuilt, or supplied, without a pin moving.
	 *
	 * <p>Shared by the build above and by an install from supplied artifacts ({@link Installer}), which has a base but
	 * never runs this builder.
	 */
	static ArtifactResult vanillaReference(Path vanilla, Path base, Path out, Consumer<String> log) throws IOException {
		VanillaReference.Result built = VanillaReference.ensure(vanilla, base, out);
		log.accept(built.reused() ? "[vanilla-reference] up-to-date: " + out.getFileName()
				: "[vanilla-reference] wrote " + out.getFileName() + ": " + built.classes() + " vanilla classes, "
						+ built.differing() + " of them differ from " + base.getFileName() + " and carry their vanilla bytes");
		return new ArtifactResult(VANILLA_REFERENCE + ":" + Installer.artifactVersion(VANILLA_REFERENCE, ""), out,
				Util.sha1(out), Files.size(out));
	}

	/**
	 * Where a userdev jar is cached: named by the build it is, never a fixed name. The cache keeps a file as soon as
	 * it exists, so a fixed {@code neoforge-userdev.jar} survived a pin bump and every later install read the old
	 * build's {@code config.json} while the build stamp claimed the new one.
	 */
	static String userdevFile(String artifact, String version) {
		return artifact + "-" + version + "-userdev.jar";
	}

}
