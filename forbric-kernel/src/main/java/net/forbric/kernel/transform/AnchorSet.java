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
import java.util.Objects;

/**
 * What a {@link ClassTransformer} promises to edit, so that its failing to edit it can be noticed.
 *
 * <p>Most transformers in this package find an anchor in a target class's bytecode and splice there. When a
 * carrier moves or renames that anchor, about a third of them simply {@code return classBytes} and say nothing,
 * and {@link TransformChain} has never asked whether any of them fired. That combination is how a NeoForge
 * carrier bump from 26.2.0.38-beta to .88 took four separate repairs offline at once, each costing a
 * user-visible feature, with nothing in the log naming any of them.
 *
 * <p>A declaration here is deliberately only <em>which classes</em> this transformer must land on — never a
 * method name, a descriptor or an instruction shape. The hit signal is the one {@link ClassTransformer#transform}
 * already contracts for: the same array back means "I made no edit". So the audit and the transformer cannot
 * drift apart, because the audit does not re-implement the match; it watches the transformer make it.
 *
 * <p>The class names are declared, not searched for in a particular jar. {@code ModListScreen} migrated out of
 * the merged base and into the NeoForge carrier between two carrier versions; pinning which artifact a class
 * ought to live in would be one more thing to keep current. "It is in none of them" is a finding, not a lookup
 * failure.
 */
public record AnchorSet(List<Anchor> anchors, String scanNote) {

	/**
	 * How much a missed anchor costs, which decides how loudly it is reported.
	 *
	 * <p>These three exist to preserve what the code already does rather than to invent a policy. In particular
	 * {@link #HEDGE} is not padding: two transformers deliberately carry a target they expect never to match, so
	 * that they start working by themselves if a carrier ever grows the method — see
	 * {@code ClientPackHookInjector}'s class javadoc and {@code LifecycleHookInjector}'s note on MinecraftForge's
	 * no-arg {@code ServerModLoader.load}. Encoding that is what keeps the audit from crying wolf on the entries
	 * that are designed to match nothing.
	 */
	public enum Severity {
		/** The genuine loader's lifecycle runs if this misses. Refusing to boot is the existing behaviour. */
		FATAL,
		/** A user-visible feature disappears silently. Loud, but never a refusal to boot. */
		REQUIRED,
		/** Expected not to match on today's carriers. Silent when absent; worth a word if it ever wakes up. */
		HEDGE
	}

	/**
	 * One class this transformer must edit when that class is loaded.
	 *
	 * @param binaryName the dot-separated class name, exactly as {@link ClassTransformer#transform} receives it
	 * @param severity   what a miss costs
	 * @param cost       one sentence naming what stops working, written for whoever reads the failure
	 */
	public record Anchor(String binaryName, Severity severity, String cost) {
		public Anchor {
			Objects.requireNonNull(binaryName, "binaryName");
			Objects.requireNonNull(severity, "severity");
			if (binaryName.isBlank()) throw new IllegalArgumentException("binaryName is blank");
			if (cost == null || cost.isBlank()) {
				throw new IllegalArgumentException("an anchor without a cost cannot be reported usefully: "
						+ binaryName);
			}
		}
	}

	public AnchorSet {
		anchors = anchors == null ? List.of() : List.copyOf(anchors);
	}

	/** Declares the classes this transformer must edit. */
	public static AnchorSet of(Anchor... anchors) {
		return new AnchorSet(List.of(anchors), null);
	}

	/**
	 * Declares that this transformer has no fixed target: it decides per class, by scanning. The reason is
	 * recorded so that "has not declared yet" and "cannot declare" stay distinguishable.
	 */
	public static AnchorSet scanned(String why) {
		if (why == null || why.isBlank()) throw new IllegalArgumentException("scanned() needs a reason");
		return new AnchorSet(List.of(), why);
	}

	/** The pre-migration default: nothing said either way. */
	public static AnchorSet undeclared() {
		return new AnchorSet(List.of(), null);
	}

	/** True when this transformer has said nothing at all — neither a target nor a reason it has none. */
	public boolean isUndeclared() {
		return anchors.isEmpty() && scanNote == null;
	}
}
