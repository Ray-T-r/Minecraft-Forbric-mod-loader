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

package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;

/**
 * Mixins that were written to attach to ANOTHER MOD and did not.
 *
 * <p>This is the failure no dependency check can see. Iris declares {@code sodium 0.9.x} and Sodium
 * 0.9.2-beta.1 is inside that range, so every loader — Forbric and stock Fabric alike, measured against Fabric's
 * own {@code VersionPredicate} — says the requirement is met. What is not met is the binary shape: Iris's
 * {@code @Redirect} wanted a call to {@code RenderRegion.clearAllCachedBatches()} inside
 * {@code RenderRegionManager.uploadResults}, and that Sodium no longer makes it. The declaration is right and
 * the bytecode is wrong, and only the bytecode was ever checked.
 *
 * <p>Recorded here so the boot can put it in front of the player next to the unmet dependencies, which is the
 * same question from their side: two mods are installed and they do not fit.
 *
 * <p><b>Why this is worth being loud about when a half-applied mixin is not.</b> Measured across every gate log
 * in this repo: 1226 anchors that do not resolve, all of them on merged-base classes, all on runs that pass —
 * and not one on another mod's class. The signal separates cleanly because the merged base is a rewritten game
 * and mixins miss it constantly, while two mods either fit or do not.
 */
public final class ForeignMixinBreaks {
	/**
	 * One mixin that did not attach to the mod it was written for.
	 *
	 * @param config  the mixin config it was declared in, which names the mod that owns it
	 * @param mixin   the mixin class
	 * @param anchors the injection points that did not resolve, as the report prints them
	 */
	public record Break(String config, String mixin, List<String> anchors) {
	}

	private static final List<Break> BREAKS = new ArrayList<>();

	private ForeignMixinBreaks() {
	}

	public static synchronized void record(String config, String mixin, List<String> anchors) {
		if (config == null || mixin == null || anchors == null || anchors.isEmpty()) return;
		BREAKS.add(new Break(config, mixin, List.copyOf(anchors)));
	}

	/** What was recorded, in the order the mixins were judged. */
	public static synchronized List<Break> all() {
		return List.copyOf(BREAKS);
	}

	/** Forgets everything. For tests, which judge different mixin sets in one JVM. */
	static synchronized void reset() {
		BREAKS.clear();
	}
}
