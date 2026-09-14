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
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Records which {@link Pins} a cached build artifact was produced from, so a pin bump cannot be served from cache.
 *
 * <p><b>Why this exists.</b> Every builder's reuse test used to be "the output file exists and is not empty", and
 * none of the artifacts carries its version in its filename — {@code neoforge-runtime.jar},
 * {@code patched-mc-neoforge-26.2.jar}, {@code patched-mc-merged-26.2.jar}. So moving the NeoForge pin from
 * {@code 26.2.0.38-beta} to {@code 26.2.0.88} and re-running the installer on a machine that had built before
 * printed {@code neoforge=26.2.0.88} in its own pin line and then {@code [neoforge-runtime] up-to-date} — and
 * installed the OLD carrier. Nothing failed; the install said "Installed." and the game came up. What it came up
 * with was a kernel built against one NeoForge and a carrier built from another, which is the silent-mismatch
 * shape the kernel spends most of its transformers defending against.
 *
 * <p>It only ever surfaced on a user's machine because the gate wipes its install directory first, so every gated
 * install was a cold one and the cache path was never exercised at a bump.
 *
 * <p><b>Every artifact is stamped with the WHOLE pin set</b>, not with the subset that feeds it. A per-artifact
 * key would avoid rebuilding the MinecraftForge half when only NeoForge moves, and would also be a second place
 * to get wrong — omit one input and the cache is silently stale again, which is the bug being fixed. The cost of
 * over-invalidating is one rebuild of a few minutes on a pin bump; the cost of under-invalidating is an install
 * that is wrong and says nothing.
 *
 * <p>A missing stamp counts as a miss: artifacts built by an installer older than this class have no stamp and
 * cannot be shown to match, so they are rebuilt once.
 */
final class BuildStamp {

	private BuildStamp() {
	}

	/** The sibling file that records what produced {@code artifact}. */
	private static Path stampFile(Path artifact) {
		return artifact.resolveSibling(artifact.getFileName() + ".pins");
	}

	/** True when {@code artifact} exists, is non-empty, and was built from today's pins. */
	static boolean isFresh(Path artifact) {
		try {
			if (!Files.isRegularFile(artifact) || Files.size(artifact) == 0) return false;
			Path stamp = stampFile(artifact);
			if (!Files.isRegularFile(stamp)) return false;
			return Pins.stamp().equals(Files.readString(stamp, StandardCharsets.UTF_8).trim());
		} catch (IOException unreadable) {
			return false;
		}
	}

	/**
	 * Records today's pins beside {@code artifact}. Written only after the artifact itself is in place, so an
	 * interrupted build leaves no stamp and the next run rebuilds.
	 *
	 * <p>Best-effort: a stamp that cannot be written costs a rebuild next time, which is the safe direction.
	 */
	static void write(Path artifact) {
		try {
			Files.writeString(stampFile(artifact), Pins.stamp() + System.lineSeparator(), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// Nothing to do: an unstamped artifact is treated as stale, which is correct, just slower.
		}
	}
}
