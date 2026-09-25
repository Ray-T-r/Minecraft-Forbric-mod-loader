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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Which mod a mixin config came from, so that a failure can say.
 *
 * <p>Both producers know the answer and both used to throw it away. The Forge-family side builds a record that
 * carries the jar, and the Fabric side is iterating mod containers when it collects the declarations — but both
 * were flattened to a bare list of config names before anything downstream saw them. Everything after that
 * point, including Mixin's own messages, could only name a file.
 *
 * <p>A player reading "sodium-common.mixins.json failed" has to already know which jar that is. On a
 * tri-ecosystem instance, where the same mod may be present as a Fabric build and a NeoForge build and only one
 * of them won arbitration, that is not a reasonable thing to expect them to know.
 *
 * <h2>Ambiguity is answered with silence, never with a guess</h2>
 *
 * <p>Two shapes produce a config more than one mod can claim. A MinecraftForge toml with several
 * {@code [[mods]]} sections copies one config list into every mod it declares, so a first-wins rule would pick
 * one id and be quietly wrong about the others. And {@code Mixins.registerConfiguration} de-duplicates by config
 * NAME, so two different jars shipping the same name register once and the loser is invisible.
 *
 * <p>In both cases this class answers null and callers fall back to the bare config name. A confidently wrong
 * mod name is worse than no mod name: it sends someone to the wrong jar, and it makes the log look more
 * authoritative than it is. The same id appearing twice is not ambiguity and keeps its owner.
 */
public final class MixinConfigOwners {

	/**
	 * One mixin config and who declared it.
	 *
	 * @param config    the resource name, which is what Mixin is registered with
	 * @param modId     the declaring mod's id
	 * @param ecosystem which family's manifest declared it
	 * @param bundledBy the id of the jar that carries this one inside itself, or null for a top-level jar
	 */
	public record Owned(String config, String modId, Ecosystem ecosystem, String bundledBy) {
		public Owned(String config, String modId, Ecosystem ecosystem) {
			this(config, modId, ecosystem, null);
		}
	}

	/** config name -> owning mod id, or absent when no single mod owns it. */
	private static volatile Map<String, Owned> owners = Map.of();

	private MixinConfigOwners() {
	}

	/**
	 * Resolves ownership across every declaration and publishes it.
	 *
	 * <p>Called before configs are registered, because the log sites that read this fire DURING registration --
	 * Mixin constructs each config's plugin while parsing it, and the kernel's own adapter reports on mixins from
	 * inside that parse.
	 */
	public static void publish(Collection<Owned> declared) {
		Map<String, Set<String>> claims = new LinkedHashMap<>();
		Map<String, Owned> first = new LinkedHashMap<>();
		for (Owned one : declared) {
			if (one == null || one.config() == null) continue;
			claims.computeIfAbsent(one.config(), k -> new LinkedHashSet<>()).add(String.valueOf(one.modId()));
			first.putIfAbsent(one.config(), one);
		}

		Map<String, Owned> resolved = new LinkedHashMap<>();
		List<String> contested = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : claims.entrySet()) {
			if (entry.getValue().size() == 1) {
				resolved.put(entry.getKey(), first.get(entry.getKey()));
			} else {
				contested.add(entry.getKey() + " claimed by " + entry.getValue());
			}
		}
		owners = Map.copyOf(resolved);

		if (!contested.isEmpty()) {
			ForbricLog.debug("[Forbric/Mixin] %d mixin config(s) are claimed by more than one mod, so they will be "
					+ "reported by file name only rather than by a name that might be the wrong one: %s",
					contested.size(), String.join("; ", contested));
		}
	}

	/** The owning mod's id, or null when nothing or more than one mod claims this config. */
	public static String modIdOf(String config) {
		Owned one = owners.get(config);
		return one == null ? null : one.modId();
	}

	/** The declaring mod's ecosystem, or null when nothing or more than one mod claims this config. */
	public static net.forbric.api.Ecosystem ecosystemOf(String config) {
		Owned one = owners.get(config);
		return one == null ? null : one.ecosystem();
	}

	/**
	 * How a config should be named to a reader: {@code "<mod id> (<config>)"} when that is known and unambiguous,
	 * the bare config name otherwise, and the jar that bundled it when there is one.
	 */
	public static String describe(String config) {
		Owned one = owners.get(config);
		if (one == null || one.modId() == null) return config;
		if (one.bundledBy() != null && !one.bundledBy().isBlank()) {
			return one.modId() + " (" + config + ", bundled by " + one.bundledBy() + ")";
		}
		return one.modId() + " (" + config + ")";
	}

	/** Test seam: forgets everything published, so one test cannot decide another's answers. */
	static void reset() {
		owners = Map.of();
	}
}
