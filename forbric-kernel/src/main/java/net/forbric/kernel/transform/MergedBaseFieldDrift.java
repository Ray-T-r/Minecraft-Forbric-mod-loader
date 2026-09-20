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

package net.forbric.kernel.transform;

import java.util.List;

/**
 * Vanilla fields whose descriptor the merge changed and that nothing restores: a mod compiled against vanilla
 * reads them with vanilla's descriptor and gets {@code NoSuchFieldError}.
 *
 * <p>This is the ledger {@code MergedBaseNoVanishedVanillaFieldTest} keeps honest in both directions (a new
 * drift fails it; a repaired one fails it too, until its row is deleted). It lives here rather than in the test
 * so {@code FieldDriftAudit} can name, at boot, every installed mod that references one of these — a static
 * finding, complete before {@code load-report.txt} is written, instead of a {@code NoSuchFieldError} whenever
 * the access happens to run.
 */
public final class MergedBaseFieldDrift {
	/**
	 * @param owner       the class (internal name)
	 * @param name        the field
	 * @param vanillaDesc the descriptor a vanilla-compiled reader carries — the one the merged base no longer has
	 * @param cost        what a reader loses, in one sentence
	 */
	public record Drift(String owner, String name, String vanillaDesc, String cost) {
		public String key() {
			return owner + "#" + name + ":" + vanillaDesc;
		}
	}

	public static final List<Drift> KNOWN = List.of(
			new Drift("net/minecraft/client/KeyMapping", "MAP", "Ljava/util/Map;",
					"both ecosystems replace vanilla's plain Map with their own KeyMappingLookup. A mod reading KeyMapping.MAP "
							+ "as a Map cannot; the kernel instead routes the game's own readers at the lookup that registration "
							+ "fills (see routeKeyMappingClickToPopulatedLookup)"),
			new Drift("net/minecraft/util/random/WeightedList$Builder", "result", "Lcom/google/common/collect/ImmutableList$Builder;",
					"re-typed to a plain List. Same shape as AttributeSupplier$Builder#builder was before its twin; no consumer "
							+ "has been observed hitting it yet"));

	private MergedBaseFieldDrift() {
	}

	/** The row for {@code owner.name:desc}, or null when that access is fine on the merged base. */
	public static Drift find(String owner, String name, String desc) {
		for (Drift drift : KNOWN) {
			if (drift.owner().equals(owner) && drift.name().equals(name) && drift.vanillaDesc().equals(desc)) return drift;
		}
		return null;
	}
}
