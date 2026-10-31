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

import net.forbric.api.Ecosystem;
import net.forbric.kernel.discovery.LoaderKind;

/**
 * What {@link MultiLoaderArbiter} decided about one jar: an ecosystem loads it, it is refused, or nobody claims it.
 *
 * <p>Three answers, not an {@link Ecosystem} and a null. Null is the arbiter's word for a plain library, and every pass
 * that sees it keeps the jar: its classes go on the game, its nested jars are inventoried, its data and feature flags are
 * served. A refused MinecraftForge build must get none of that, and while the refusal was spelled as an owner of
 * {@code Ecosystem.FORGE} it was kept off the game only because every such pass happened to compare against a family it
 * was not. A pass that keeps unowned jars asks {@link #suppresses} (or tells the three apart itself), so a refused jar
 * cannot read as a library to it.
 *
 * <p>So no accessor here answers "an ecosystem, or null": that answer is null for a refused jar and a library alike, and
 * the first pass written as "null, so keep it" would keep the refused one. A pass asks whether one ecosystem owns the jar
 * ({@link #ownedBy}), or matches {@link Owned} for the ecosystem itself.
 */
public sealed interface JarClaim {
	/** The claim of a jar declaring no loader manifest: a plain library, kept by every pass. */
	JarClaim UNOWNED = new Unowned();

	/** One ecosystem loads the jar; every other family's pass leaves it out. */
	record Owned(Ecosystem ecosystem) implements JarClaim {
	}

	/**
	 * Nothing loads the jar: it declares {@code loader} — MinecraftForge, today the only kind refused — and no half that
	 * can load it on Forbric. Discovery recorded why ({@code MetadataFailures.unsupported}).
	 */
	record Refused(LoaderKind loader) implements JarClaim {
	}

	/** Nobody claims the jar; see {@link #UNOWNED}. */
	record Unowned() implements JarClaim {
	}

	/** Whether {@code family} loads the jar: false for every other family's jar, a refused one and a plain library. */
	default boolean ownedBy(Ecosystem family) {
		return this instanceof Owned owned && owned.ecosystem() == family;
	}

	/** Whether the jar is refused. */
	default boolean refused() {
		return this instanceof Refused;
	}

	/** Whether nobody claims the jar: a plain library, the one answer a pass may read as "keep it". */
	default boolean unowned() {
		return this instanceof Unowned;
	}

	/**
	 * Whether a pass loading {@code family} must leave the jar out: another ecosystem owns it, or it is refused — for
	 * every family. A plain library is never left out.
	 */
	default boolean suppresses(Ecosystem family) {
		return this instanceof Refused || this instanceof Owned owned && owned.ecosystem() != family;
	}
}
