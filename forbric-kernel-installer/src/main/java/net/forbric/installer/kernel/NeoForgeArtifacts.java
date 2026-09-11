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

/**
 * Where NeoForge's artifacts live, and what Forbric calls the things it builds out of them.
 *
 * <p>The counterpart to {@link ForgeArtifacts}, and deliberately thin: the <em>contents</em> of a NeoForge
 * release are described by the {@code config.json} inside its {@code -userdev} jar, which
 * {@link ForgeArtifacts#readConfig} already parses — that file is a NeoForm userdev config on both sides, and the
 * two fields this half needs ({@code libraries} and {@code universal}) are present in both. Re-parsing it here
 * would be a second place to fix when a field moves.
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
		return NEOFORGED_MVN + "/" + Util.coordinateToPath(ForgeArtifacts.stripExtension(coordinate));
	}

	String centralUrl(String coordinate) {
		return CENTRAL + "/" + Util.coordinateToPath(ForgeArtifacts.stripExtension(coordinate));
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

	/** What Forbric stages the assembled runtime under. */
	String runtimeCoordinate() {
		return "net.forbric:neoforge-runtime:" + mcVersion;
	}

	/** What Forbric stages NeoForm's patched Minecraft under. */
	String patchedMcCoordinate() {
		return "net.forbric:patched-mc-neoforge:" + mcVersion;
	}
}
