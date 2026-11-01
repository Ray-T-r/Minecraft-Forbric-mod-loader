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
 * The two mod ecosystems Forbric runs side by side, as ONE name.
 *
 * <p>This is the first type of the unified Forbric API: the vocabulary the kernel's own services and the
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
 * <p>The spelling is not free choice: {@code boot.Main} writes {@link #name()} straight into the {@code --scan}
 * report, and {@code run/diff-oracle.sh} pins {@code "FABRIC"}/{@code "NEOFORGE"} in a python ground-truth extractor
 * that is deliberately INDEPENDENT of kernel code. Renaming a constant to match prose would have meant editing the
 * thing whose whole value is that it was written separately. So the prose name lives in {@link #displayName()}.
 *
 * <h2>Why there is no MinecraftForge constant</h2>
 *
 * <p>There was one, {@code FORGE}, while Forbric loaded traditional MinecraftForge mods. It no longer does: a
 * {@code META-INF/mods.toml} half is set aside in a universal jar and refused alone, and both are named by
 * {@code discovery.LoaderKind.MINECRAFTFORGE} (spelled {@code FORGE} in log lines and evidence, where gates and the
 * oracle still read that word), whose ecosystem is {@code null}: nothing can hand a refused half to a loader. A knob or
 * a player's file that still names MinecraftForge is recognised by {@link #namesMinecraftForge(String)} and warned
 * about; {@link #parse(String)} reads it as no ecosystem.
 */
public enum Ecosystem {
	FABRIC("Fabric"),
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
	 * Whether this is the FML-descended ecosystem, NeoForge (the "Forge family", which also held traditional
	 * MinecraftForge while Forbric loaded it).
	 *
	 * <p>A real distinction, not a convenience: {@code @Mod}, a mod container, an event bus and a {@code ModList} are
	 * the family's, and a great deal of kernel code is correct for it and not for Fabric.
	 */
	public boolean isForgeFamily() {
		return this != FABRIC;
	}

	/** The lowercase id used in manifests and in the {@code forbric:ecosystem} custom value. */
	public String familyId() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * The spelling this ecosystem is written as in user-facing config: what the kernel writes into the player's
	 * {@code forbric-duplicates.properties} template and what its "use fabric or neoforge" warnings name.
	 *
	 * <p>Kept apart from {@link #familyId()}, an INTERNAL id ({@code forbric:ecosystem} in synthesized mod metadata,
	 * {@code -Dforbric.forgeFamily}) whose value must not move: the two spelled MinecraftForge differently
	 * ({@code minecraftforge} here), and they may differ again.
	 */
	public String configId() {
		return familyId();
	}

	/**
	 * Parses a user- or manifest-supplied ecosystem name, or returns {@code null} if it names none of them — which
	 * includes MinecraftForge in any spelling ({@code forge}, {@code minecraftforge}): ask
	 * {@link #namesMinecraftForge(String)} first to tell that name apart and say it is no longer supported.
	 */
	public static Ecosystem parse(String raw) {
		if (raw == null) return null;
		String s = raw.trim().toLowerCase(Locale.ROOT);

		return switch (s) {
			case "fabric" -> FABRIC;
			case "neoforge" -> NEOFORGE;
			default -> null;
		};
	}

	/**
	 * Whether a user- or manifest-supplied name means traditional MinecraftForge, in either spelling it was accepted in
	 * ({@code forge}, {@code minecraftforge}).
	 *
	 * <p>Forbric no longer loads MinecraftForge mods, so a knob or a file that names it is warned about and the entry
	 * read as absent — {@code -Dforbric.multiLoaderPreference}, {@code -Dforbric.dupeIdPreference},
	 * {@code -Dforbric.nestedDupePreference}, {@code -Dforbric.modOwner} and the player's {@code forbric-mods.txt}. They
	 * ask this, by the name itself, before {@code parse}: the warning has to say "no longer supported", not "unknown
	 * ecosystem".
	 */
	public static boolean namesMinecraftForge(String raw) {
		if (raw == null) return false;
		String s = raw.trim().toLowerCase(Locale.ROOT);
		return s.equals("forge") || s.equals("minecraftforge");
	}
}
