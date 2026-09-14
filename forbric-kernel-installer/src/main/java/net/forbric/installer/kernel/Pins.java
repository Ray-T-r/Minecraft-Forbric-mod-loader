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
 * The upstream versions this installer builds against.
 *
 * <p>They are pins, not defaults: each one was chosen because a specific thing breaks at the neighbouring
 * versions, and the reason lives next to the number so nobody "updates" it back into the failure. Everything the
 * install produces is keyed on this set, so bumping any of them invalidates the cached artifacts that depend on
 * it.
 */
final class Pins {

	private Pins() {
	}

	/** The only Minecraft version this generation supports. */
	static final String MINECRAFT = "26.2";

	/** MinecraftForge, in its own {@code <mc>-<fml>} coordinate form. */
	static final String FORGE = "26.2-65.0.1";

	/**
	 * NeoForge, on the first release line rather than a beta.
	 *
	 * <p>This used to be {@code 26.2.0.38-beta}, because {@code .40-beta} deletes {@code ContainerScreenEvent}
	 * and {@code .43-beta} deletes {@code PlayerInteractEvent$EntityInteractSpecific}, and the reference pack's
	 * jei / sophisticatedcore / sophisticatedbackpacks still called them. Two things ended that:
	 * {@code .57} and up are releases rather than betas, and the title screen brands whatever build it is running,
	 * so a beta carrier tells every player it is a beta; and JEI now declares {@code neoforge [26.2.0.67,)}, which
	 * {@code .38-beta} does not satisfy — staying put had become the thing that froze the pack.
	 *
	 * <p>Re-measured at the bump: {@code .38-beta → .88} removes 12 classes and adds 24; of the 98 jars in the
	 * reference pack exactly two named anything removed, and the current builds of those mods name none of it.
	 * Every {@code neoforge} versionRange declared in the pack is satisfied. The one thing only the class diff
	 * caught is that {@code client.gui.ModListScreen} moved to {@code client.gui.modlist} — which the kernel's
	 * mods-button redirect names, and which would have failed silently. {@code ForeignTypeCarrierTest} now checks
	 * every such name against the carrier.
	 */
	static final String NEOFORGE = "26.2.0.88";

	/**
	 * NeoFormRuntime, pinned to the build actually validated rather than the newest published one.
	 *
	 * <p>NFRT's own jar digest is part of its cache key, so a different NFRT is entitled to produce different
	 * bytes. 2.0.18 is the build whose {@code gameJar} result was checked byte-for-byte against the reference
	 * {@code patched-mc-neoforge-26.2.jar} (sha1 {@code 5b2970209ee12702117309576b08521aa38ae67b}).
	 */
	static final String NFRT = "2.0.18";

	/**
	 * The NeoForm result Forbric takes out of NFRT.
	 *
	 * <p>{@code gameJarNoRecomp} is the binary-patch path — {@code preProcessJar → binaryPatch →
	 * copyUnpatchedClasses → applyDevTransforms} — and it produces the same 10,963 classes as the {@code gameJar}
	 * recompile path in about six seconds, with no decompiler, no 4 GB heap and no {@code javac}. Merging from it
	 * yields a conflict report that is identical to the recompile path's <em>as a set</em> and a merged base with
	 * the same 30,471 entries.
	 *
	 * <p>It must not be {@code gameJarNoRecompWithNeoForge}: that variant routes through
	 * {@code binaryWithNeoForge} and folds NeoForge's own classes into the jar, which would then define them
	 * twice — once inside the merged base, once in {@code neoforge-runtime.jar}.
	 */
	static final String NFRT_RESULT = "gameJarNoRecomp";

	/** The NeoForge artifact NFRT is pointed at. The bare coordinate does not exist on the Maven. */
	static String neoforgeUserdevCoordinate() {
		return "net.neoforged:neoforge:" + NEOFORGE + ":userdev";
	}

	/** NeoFormRuntime's own fat jar. */
	static String nfrtCoordinate() {
		return "net.neoforged:neoform-runtime:" + NFRT + ":all";
	}

	/** A one-line summary for the build stamp, so a cached artifact records what produced it. */
	static String stamp() {
		return "mc=" + MINECRAFT + " forge=" + FORGE + " neoforge=" + NEOFORGE
				+ " nfrt=" + NFRT + " result=" + NFRT_RESULT;
	}
}
