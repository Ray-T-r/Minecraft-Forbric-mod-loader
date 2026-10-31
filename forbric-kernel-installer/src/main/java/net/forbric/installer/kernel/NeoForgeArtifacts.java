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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where NeoForge's artifacts live, what Forbric calls the things it builds out of them, and what the userdev jar's
 * {@code config.json} says NeoForge's runtime is made of.
 *
 * <p>The <em>contents</em> of a NeoForge release are described by the {@code config.json} inside its
 * {@code -userdev} jar, so retargeting another NeoForge build is data, not code. Only its {@code libraries} are read:
 * the universal jar's coordinate is derived from the pin ({@link #universalCoordinate}), and NFRT reads the patches
 * and access transformers itself.
 */
final class NeoForgeArtifacts {

	private static final String NEOFORGED_MVN = "https://maven.neoforged.net/releases";
	private static final String CENTRAL = "https://repo1.maven.org/maven2";

	final String mcVersion;
	final String neoforgeVersion;

	NeoForgeArtifacts(String mcVersion, String neoforgeVersion) {
		this.mcVersion = mcVersion;
		this.neoforgeVersion = neoforgeVersion;
	}

	String neoforgedUrl(String coordinate) {
		return NEOFORGED_MVN + "/" + Util.coordinateToPath(stripExtension(coordinate));
	}

	String centralUrl(String coordinate) {
		return CENTRAL + "/" + Util.coordinateToPath(stripExtension(coordinate));
	}

	/** The jar carrying {@code config.json}, the patches and the library list. */
	String userdevCoordinate() {
		return "net.neoforged:neoforge:" + neoforgeVersion + ":userdev";
	}

	/**
	 * NeoForge's own classes.
	 *
	 * <p>{@code assemble-neoforge-runtime.sh:62} claims this one "is NOT fetched here (gradle-module-routed, no
	 * bare jar)" and reads it out of an NFRT cache directory instead. That is not true: the Maven serves it
	 * directly, 6,984,676 bytes, the same file the cache holds. The claim is what tied a build to a developer's
	 * {@code ~/.neoformruntime}, so it is worth stating plainly that it was checked.
	 */
	String universalCoordinate() {
		return "net.neoforged:neoforge:" + neoforgeVersion + ":universal";
	}

	/** What Forbric stages the assembled runtime under: the coordinate the profile names. */
	String runtimeCoordinate() {
		return ArtifactBuilder.NEOFORGE_RUNTIME + ":" + Installer.artifactVersion(ArtifactBuilder.NEOFORGE_RUNTIME, mcVersion);
	}

	/** What Forbric stages NeoForm's patched Minecraft under: the game base the profile names. */
	String patchedMcCoordinate() {
		return ArtifactBuilder.BASE + ":" + Installer.artifactVersion(ArtifactBuilder.BASE, mcVersion);
	}

	/** Strip a Maven {@code @extension} suffix (e.g. {@code :universal@jar} → {@code :universal}). */
	static String stripExtension(String coordinate) {
		int at = coordinate.indexOf('@');
		return at >= 0 ? coordinate.substring(0, at) : coordinate;
	}

	/**
	 * The runtime library coordinates {@code config.json} in a NeoForge {@code -userdev} jar lists, in its order:
	 * the superset {@link NeoForgeRuntimeBuilder} picks the carrier's libraries from.
	 */
	@SuppressWarnings("unchecked")
	static List<String> readLibraries(Path userdevJar) throws IOException {
		byte[] bytes = Zips.readEntry(userdevJar, "config.json");
		if (bytes == null) {
			throw new IOException("no config.json in " + userdevJar.getFileName() + " (not a NeoForge userdev jar?)");
		}
		Map<String, Object> root;
		try {
			root = (Map<String, Object>) Json.parse(new String(bytes, StandardCharsets.UTF_8));
		} catch (RuntimeException e) {
			throw new IOException("malformed config.json in " + userdevJar.getFileName() + ": " + e.getMessage(), e);
		}
		List<String> out = new ArrayList<>();
		if (root.get("libraries") instanceof List<?> list) {
			for (Object e : list) if (e instanceof String s) out.add(s);
		}
		return out;
	}
}
