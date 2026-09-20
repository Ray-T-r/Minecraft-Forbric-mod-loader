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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guest mixins whose job the kernel has taken over, so their failure is not the mod's failure.
 *
 * <p>A mixin that cannot apply normally means a mod lost something, and the Mods screen says so. Sometimes it
 * means the opposite: the kernel could not make the mixin fit, looked at what it was for, and did that job
 * itself somewhere else. Marking the mod then reports a loss that did not happen — and a report that cries wolf
 * is worse than no report, because the next real one is read the same way.
 *
 * <p>The bar for an entry here is deliberately high. It is not "the kernel has something similar"; it is that a
 * named kernel repair does <b>everything this mixin class does</b>, and was written for exactly this failure. A
 * mixin class with a second injection the kernel does not replace does not belong here, because suppressing the
 * mark would hide that half.
 *
 * <p>{@code -Dforbric.supersededMixins=off} marks them like any other failure, which is how the claim in each
 * entry can be checked against what the game actually does.
 */
public final class SupersededMixins {
	static final String PROPERTY = "forbric.supersededMixins";

	/** Mixin class → the kernel repair that does its job, in the words the log should use. */
	private static final Map<String, String> SUPERSEDED = superseded();

	private static Map<String, String> superseded() {
		Map<String, String> map = new LinkedHashMap<>();
		// Both of this mixin's members are the fabric:load_conditions evaluator: a @WrapOperation on the codec
		// parse that applies the conditions, and an @Inject that skips the entry it rejected. Neither can apply —
		// NeoForge's patch of scanDirectory made the value Optional and reordered the lambda's captures, so the
		// descriptor Mixin expects is not the one the mod was built against. KernelFabricConditions does both
		// jobs one level down, on ConditionalOps' own funnel, which covers every consumer rather than this one.
		map.put("net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin",
				"the kernel evaluates fabric:load_conditions at ConditionalOps' funnel instead "
						+ "(KernelFabricConditions), which covers every consumer rather than this one call site");
		return Map.copyOf(map);
	}

	private SupersededMixins() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * What the kernel does instead of {@code mixinClass}, or null when nothing does.
	 *
	 * <p>Null is the answer whenever the switch is off, so the switch turns every one of these back into an
	 * ordinary marked failure rather than merely changing the wording.
	 */
	public static String replacementFor(String mixinClass) {
		return enabled() ? SUPERSEDED.get(mixinClass) : null;
	}

	/** The mixin classes with an entry, for the tests that check each claim is still true. */
	static Map<String, String> all() {
		return SUPERSEDED;
	}
}
