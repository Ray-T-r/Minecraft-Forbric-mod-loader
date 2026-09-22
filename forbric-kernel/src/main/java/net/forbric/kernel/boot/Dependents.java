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

package net.forbric.kernel.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.ModPresence;
import net.forbric.api.UnifiedDependency;

/**
 * Who else stops working when one mod does.
 *
 * <h2>Why the report needs this</h2>
 *
 * <p>The load report names the mod that failed. When that mod is a LIBRARY, the player is not looking at the
 * library — they are looking at the dozen mods that quietly stopped doing anything, none of which is named
 * anywhere. The names in this project's own history are mostly libraries: supermartijn642corelib, architectury,
 * Puzzles Lib, balm, glitchcore, TerraBlender. {@code gate-m24-brokenmod} asserts that one mod's failure does not
 * spread, and its canary has no dependants, so the assertion has never been asked the question a pack asks.
 *
 * <p>This does not decide that a dependant failed — it cannot, and claiming so would be the false-accusation
 * shape this tree has already had to delete once. It states the fact the reader needs and cannot get anywhere
 * else: these mods declared they REQUIRE the one that did not load.
 */
public final class Dependents {

	private Dependents() {
	}

	/** Display names (falling back to ids) of every discovered mod that requires {@code modId}, sorted. */
	public static List<String> of(String modId) {
		List<DiscoveredMod> all = new ArrayList<>(ModPresence.forgeFamilyMods());
		all.addAll(ModPresence.fabricMods());
		return of(modId, all);
	}

	/** Testable form: the same question against an explicit list. */
	public static List<String> of(String modId, List<DiscoveredMod> all) {
		if (modId == null || modId.isBlank() || all == null) return List.of();
		// Sorted and de-duplicated: a universal jar is discovered once per ecosystem, and naming a mod twice in
		// a player-facing list reads like two broken mods.
		TreeSet<String> out = new TreeSet<>();
		for (DiscoveredMod mod : all) {
			if (mod == null || mod.getId() == null || mod.getId().equals(modId)) continue;
			for (UnifiedDependency dep : mod.getDependencies()) {
				if (dep == null || !dep.isMandatory()) continue;
				if (!sameMod(dep.getModId(), modId)) continue;
				String name = mod.getDisplayName();
				out.add(name == null || name.isBlank() ? mod.getId() : name);
				break;
			}
		}
		return List.copyOf(out);
	}

	/**
	 * Id comparison that crosses the ecosystems' spelling rules, the same way presence does.
	 *
	 * <p>A NeoForge mod requires {@code cloth_config} and the Fabric one is {@code cloth-config}; comparing the
	 * strings would report no dependants for exactly the libraries most likely to be shipped for both.
	 */
	private static boolean sameMod(String a, String b) {
		if (a == null || b == null) return false;
		return a.equals(b) || ModPresence.spellingKey(a).equals(ModPresence.spellingKey(b));
	}
}
