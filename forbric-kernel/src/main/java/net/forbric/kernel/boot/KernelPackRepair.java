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
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;

/**
 * The boot-side hooks for the two pack repairs, and the one place the whole failure is written down.
 *
 * <p>A merged base runs two sets of patches over one {@code Pack.readPackMetadata}, and they collide. NeoForge's
 * patch appends its own {@code neoforge:overlays} to the vanilla overlay list, and it does that the obvious way —
 * copy into an {@code ArrayList}, {@code addAll}, freeze. fabric-api's {@code fabric-resource-conditions-api-v1}
 * ships a mixin that hooks every STORE of that same local and always hands back a {@code List.copyOf}, i.e. an
 * IMMUTABLE list. On Fabric there is exactly one such store and nothing mutates the local afterwards, so the
 * mixin is right. On NeoForge there is no such mixin, so the patch is right. Merged, the mixin intercepts the
 * store NeoForge's patch just made and the very next instruction is {@code addAll} on an immutable list.
 *
 * <p>What that costs is out of all proportion to it. {@code readPackMetadata} catches {@code Exception} across
 * its whole body, so the {@code UnsupportedOperationException} becomes a {@code null} return; {@code
 * Pack.readMetaAndCreate} turns that into a {@code null} pack; a mod that does not null-check hands the null to
 * the consumer it was given, and {@code PackRepository.discoverAvailable} dies on
 * {@code Cannot invoke "Pack.streamSelfAndChildren()" because "pack" is null}. One list's mutability, and the
 * world will not load.
 *
 * <p>{@link #concat} is the repair: the merged base stops assuming a mutability it can no longer guarantee.
 * {@link #nullPackSkipped} is the backstop one layer out, because a {@code RepositorySource} emitting a null pack
 * is always a bug in that source — vanilla's own sources null-check before calling the consumer, and NeoForge's
 * {@code ResourcePackLoader} does too. Both, not either: the backstop alone would turn a loud crash into a
 * silently missing pack and quietly wrong terrain, which is the worse failure.
 */
public final class KernelPackRepair {
	/** {@code off} restores the unpatched merged behaviour — i.e. puts the collision back. */
	static final String PROPERTY = "forbric.packRepair";

	private static final AtomicBoolean NULL_PACK_REPORTED = new AtomicBoolean();

	private KernelPackRepair() {
	}

	/** Whether the repairs are installed. Read per call so the tests can drive both modes in one JVM. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(String.valueOf(System.getProperty(PROPERTY, "on")).trim());
	}

	/**
	 * {@code base} and {@code extra} joined into a fresh mutable list — the functional form of
	 * {@code base.addAll(extra)} for a {@code base} whose mutability nobody can promise any more.
	 *
	 * <p>Equivalent to the original when no mixin is present: the {@code ArrayList} the patch builds is stored into
	 * one local and never aliased, so replacing in-place mutation with a copy changes nothing observable. The
	 * caller freezes the result on the very next instruction either way.
	 */
	public static List<Object> concat(List<Object> base, Collection<?> extra) {
		List<Object> merged = new ArrayList<>();
		if (base != null) merged.addAll(base);
		if (extra != null) merged.addAll(extra);
		return merged;
	}

	/**
	 * A {@code RepositorySource} handed the repository a {@code null} pack. Skipped instead of crashing.
	 *
	 * <p>Once only, and deliberately without a pack name — there is none to report, the null is all that arrived.
	 * The identity is one line earlier in the log: vanilla's own {@code catch} in {@code readPackMetadata} already
	 * logged {@code "Failed to read pack <id> metadata"} for whichever pack produced it. Saying so here is what
	 * keeps this a visible degradation rather than a silent one.
	 */
	public static void nullPackSkipped() {
		if (!NULL_PACK_REPORTED.compareAndSet(false, true)) return;
		ForbricLog.warn("[Forbric/PackRepair] a RepositorySource emitted a null pack — skipping it instead of "
				+ "failing the whole repository. The pack that produced it is named in the preceding "
				+ "'Failed to read pack … metadata' warning, and it will be MISSING from this run (-D%s=off to "
				+ "restore the crash)", PROPERTY);
	}

	/** Test seam: forget that the null-pack warning was already emitted. */
	static void resetForTests() {
		NULL_PACK_REPORTED.set(false);
	}
}
