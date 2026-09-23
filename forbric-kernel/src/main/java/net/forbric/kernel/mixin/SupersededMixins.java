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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.api.CompatibilityFinding;

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
 * <p>The entry is a claim, and a claim is not evidence. A failure of a listed mixin is recorded as the loss it is,
 * and resolved only when the replacement is structurally there: in the bytes the kernel's transforms produce for
 * the class that carries it, checked at the failure, and again in that class's final definition. A repair that
 * stood down, or a switch that turned it into a pass-through, leaves the loss standing whatever the table says.
 *
 * <p>{@code -Dforbric.supersededMixins=off} marks them like any other failure, which is how the claim in each
 * entry can be checked against what the game actually does.
 */
public final class SupersededMixins {
	static final String PROPERTY = "forbric.supersededMixins";

	/**
	 * One entry: the words the log should use, the class whose bytes must carry the repair, and the structural
	 * check of those bytes, which answers the proof sentence or null.
	 */
	private record Replacement(String description, String witness, Function<ClassNode, String> proof) {
	}

	/** Mixin class → the kernel repair that does its job. */
	private static final Map<String, Replacement> SUPERSEDED = superseded();

	private static Map<String, Replacement> superseded() {
		Map<String, Replacement> map = new LinkedHashMap<>();
		// Both of this mixin's members are the fabric:load_conditions evaluator: a @WrapOperation on the codec
		// parse that applies the conditions, and an @Inject that skips the entry it rejected. Neither can apply —
		// NeoForge's patch of scanDirectory made the value Optional and reordered the lambda's captures, so the
		// descriptor Mixin expects is not the one the mod was built against. KernelFabricConditions does both
		// jobs one level down, on ConditionalOps' own funnel, which covers every consumer rather than this one.
		map.put("net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin",
				new Replacement("the kernel evaluates fabric:load_conditions at ConditionalOps' funnel instead "
						+ "(KernelFabricConditions), which covers every consumer rather than this one call site",
						MixinEquivalentImplementations.CONDITIONAL_OPS.replace('/', '.'),
						MixinEquivalentImplementations::conditionsFunnel));
		return Map.copyOf(map);
	}

	/** A failure of a listed mixin, kept until the class that would prove its replacement is defined. */
	private record Failure(String config, String detail, boolean required, List<String> evidence) {
	}

	private static final Map<String, Failure> FAILED = new ConcurrentHashMap<>();


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
		Replacement entry = enabled() ? SUPERSEDED.get(mixinClass) : null;
		return entry == null ? null : entry.description();
	}

	/**
	 * Records an apply failure of a listed mixin as a CONFIRMED loss, then resolves it at once when the witness
	 * class's transformed bytes already carry the replacement. Remembered either way, so the witness's final
	 * definition settles it again ({@link #observe}).
	 *
	 * @return the proof when the loss was resolved now, null when it stands
	 */
	static String failed(String config, String mixinClass, String detail, boolean required, List<String> evidence) {
		Replacement entry = enabled() ? SUPERSEDED.get(mixinClass) : null;
		if (entry == null) return null;
		List<String> claimed = new ArrayList<>(evidence);
		claimed.add("claimed replacement, pending its structural proof: " + entry.description());
		MixinCompatibility.record(config, mixinClass, detail, CompatibilityFinding.Confidence.CONFIRMED, required, claimed);
		FAILED.put(mixinClass, new Failure(config, detail, required, List.copyOf(claimed)));
		String proof = prove(entry, ForbricMixinService.preMixinBytes(entry.witness()));
		if (proof != null) {
			MixinCompatibility.resolve(config, mixinClass, proof + " (transformed bytes of " + entry.witness() + ")");
		}
		return proof;
	}

	/**
	 * The final definition of a class some remembered failure names as its witness: resolves the failure when
	 * the replacement is there, and confirms the loss again when it is not — a resolution from the transformed
	 * bytes is not allowed to outlive the class that actually runs.
	 */
	static void observe(String binary, byte[] bytes) {
		if (FAILED.isEmpty()) return;
		for (Map.Entry<String, Failure> e : FAILED.entrySet()) {
			Replacement entry = enabled() ? SUPERSEDED.get(e.getKey()) : null;
			if (entry == null || !entry.witness().equals(binary)) continue;
			Failure failure = e.getValue();
			String proof = prove(entry, bytes);
			if (proof != null) {
				MixinCompatibility.resolve(failure.config(), e.getKey(), proof + " (final definition of " + binary + ")");
			} else {
				List<String> evidence = new ArrayList<>(failure.evidence());
				evidence.add("the final definition of " + binary + " does not carry the replacement");
				MixinCompatibility.record(failure.config(), e.getKey(), failure.detail(),
						CompatibilityFinding.Confidence.CONFIRMED, failure.required(), evidence);
			}
		}
	}

	private static String prove(Replacement entry, byte[] witness) {
		if (witness == null) return null;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(witness).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
			return entry.proof().apply(node);
		} catch (RuntimeException unreadable) {
			return null;
		}
	}

	/** Every loader session starts with no remembered failures. */
	static void reset() {
		FAILED.clear();
	}

	/** The mixin classes with an entry, and the words each uses, for the tests that check each claim is still true. */
	static Map<String, String> all() {
		Map<String, String> words = new LinkedHashMap<>();
		SUPERSEDED.forEach((mixin, entry) -> words.put(mixin, entry.description()));
		return words;
	}
}
