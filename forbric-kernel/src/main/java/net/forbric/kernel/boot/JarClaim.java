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

	/** The ecosystem that loads the jar, or {@code null} when it is refused or a plain library. */
	default Ecosystem owner() {
		return this instanceof Owned owned ? owned.ecosystem() : null;
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
