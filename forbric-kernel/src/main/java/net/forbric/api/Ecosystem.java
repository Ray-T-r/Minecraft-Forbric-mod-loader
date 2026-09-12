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

package net.forbric.api;

import java.util.Locale;

/**
 * The three mod ecosystems Forbric runs side by side, as ONE name.
 *
 * <p>This is the first type of the unified Forbric API: the vocabulary the kernel's own services and the three
 * compatibility layers all speak. It exists because the kernel had grown <em>five</em> different answers to
 * "which ecosystem is this" — {@code metadata.ModEcosystem}, {@code MultiLoaderArbiter.Ecosystem} (different
 * spelling AND different order), {@code discovery.ModAnnotationScanner.Family},
 * {@code classloading.LoaderProbePolicy.Family}, and a fourth-arity {@code transform.TransformContext.Ecosystem}
 * that was missing NeoForge entirely — with a hand-written translator between two of them. Five vocabularies is
 * how a hub decays back into pairwise accommodation: code that cannot NAME the other ecosystem uniformly ends up
 * branching on its class names instead.
 *
 * <h2>Why the constants are spelled this way</h2>
 *
 * <p>{@code FORGE} means traditional MinecraftForge, never "the Forge family" — ask {@link #isForgeFamily()} for
 * that. The spelling is not free choice: {@code boot.Main} writes {@link #name()} straight into the {@code --scan}
 * report, and {@code run/diff-oracle.sh} pins {@code "FABRIC"}/{@code "FORGE"}/{@code "NEOFORGE"} in a python
 * ground-truth extractor that is deliberately INDEPENDENT of kernel code. Renaming the constant to match prose
 * would have meant editing the thing whose whole value is that it was written separately. So the prose name lives
 * in {@link #displayName()} instead, and {@link #parse(String)} accepts {@code "minecraftforge"} — which is what
 * {@code -Dforbric.multiLoaderPreference} has always taken.
 */
public enum Ecosystem {
	FABRIC("Fabric"),
	/** Traditional MinecraftForge. Not "the Forge family" — see {@link #isForgeFamily()}. */
	FORGE("MinecraftForge"),
	NEOFORGE("NeoForge");

	private final String displayName;

	Ecosystem(String displayName) {
		this.displayName = displayName;
	}

	/** How this ecosystem is written in prose and in user-facing log lines. */
	public String displayName() {
		return displayName;
	}

	/**
	 * Whether this is one of the two FML-descended ecosystems.
	 *
	 * <p>A real distinction, not a convenience: the two share {@code @Mod}, a mod container, an event bus and a
	 * {@code ModList}, and a great deal of kernel code is correct for both and for neither of the other.
	 */
	public boolean isForgeFamily() {
		return this != FABRIC;
	}

	/** The lowercase id used in manifests and in the {@code forbric:ecosystem} custom value. */
	public String familyId() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * The spelling this ecosystem is written as in user-facing config — {@code minecraftforge}, not {@code forge}.
	 *
	 * <p>Deliberately not {@link #familyId()}. That one is an INTERNAL id ({@code forbric:ecosystem} in synthesized
	 * mod metadata, {@code -Dforbric.forgeFamily}) whose value must not move. This one is what the kernel writes
	 * into the player's {@code forbric-duplicates.properties} template and what its "use fabric, neoforge or
	 * minecraftforge" warning names — an existing file on someone's disk still has to read back.
	 */
	public String configId() {
		return this == FORGE ? "minecraftforge" : familyId();
	}

	/**
	 * Parses a user- or manifest-supplied ecosystem name, or returns {@code null} if it names none of them.
	 *
	 * <p>Lenient on purpose: {@code "minecraftforge"} is the unambiguous name a person reaches for, and it is
	 * what {@code -Dforbric.multiLoaderPreference} accepted before this type existed. Breaking that to tidy a
	 * constant would be a silent behaviour change in a documented knob.
	 */
	public static Ecosystem parse(String raw) {
		if (raw == null) return null;
		String s = raw.trim().toLowerCase(Locale.ROOT);

		return switch (s) {
			case "fabric" -> FABRIC;
			case "forge", "minecraftforge" -> FORGE;
			case "neoforge" -> NEOFORGE;
			default -> null;
		};
	}
}
