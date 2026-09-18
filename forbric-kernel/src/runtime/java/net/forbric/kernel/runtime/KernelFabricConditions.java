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

package net.forbric.kernel.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * Gives {@code fabric:load_conditions} an evaluator again, because fabric-api's own two cannot run here.
 *
 * <h2>Why nothing evaluates them</h2>
 *
 * <p>fabric-api reads that key from exactly two mixins, and the merged base defeats both:
 * <ul>
 *   <li>{@code RegistryLoadTaskPendingRegistrationMixin} injects at {@code Decoder.parse} inside
 *       {@code loadFromResource}. NeoForge's patch of that method replaced the call with {@code Codec.parse}
 *       (via {@code NeoForgeExtraCodecs.decodeOnly} and {@code ConditionalOps.createConditionalCodec}), so the
 *       anchor does not exist — and the kernel rewrites every guest config's {@code defaultRequire} to 0, so it
 *       SOFT-SKIPS. Silently.</li>
 *   <li>{@code SimpleJsonResourceReloadListenerMixin.skipData} targets
 *       {@code lambda$scanDirectory$0(Map, Identifier, Object)}; the merged base's is
 *       {@code (Identifier, Identifier, Map, Optional)}, because NeoForge's patch made the value optional and
 *       reordered the captures. Mixin rejects the class, taking the sibling injection with it.</li>
 * </ul>
 *
 * <p>So every Fabric mod's conditional data file has loaded unconditionally on this kernel — a config toggle
 * that is supposed to gate content did nothing, and nothing said so. The companion repair
 * ({@link KernelNeoConditions}) stopped NeoForge's evaluator failing the whole world load over an id it does not
 * own; this is the other half, and it is the half that makes the answer come from the mod.
 *
 * <h2>Why one insertion is enough</h2>
 *
 * <p>{@code ConditionalOps} has four public factories and they all funnel into
 * {@code createConditionalCodecWithConditions(Codec, String)}. A constant-pool scan of the merged base finds
 * seven classes reaching them — the datapack-registry loader, {@code SimpleJsonResourceReloadListener} (both its
 * {@code scanDirectory} and its {@code scanDirectoryWithModifier}, which is the one recipes use), loot tables,
 * recipes, advancements and the datapack generator. Wrapping the funnel covers all of them; wrapping a call site
 * would have missed recipes.
 *
 * <h2>Why {@code Optional.empty()} is the right "no"</h2>
 *
 * <p>Because it is the "no" the consumers already speak. {@code RegistryLoadTask} turns an empty optional into
 * its {@code SKIPPED_ELEMENT_MARKER} and logs at DEBUG without adding a loading error;
 * {@code SimpleJsonResourceReloadListener} skips the file the same way. Rejecting an element is therefore not a
 * new mechanism, it is NeoForge's own.
 *
 * <p>Fabric's answer is taken BEFORE NeoForge's decoder runs, so for a file carrying both dialects with
 * different opinions, Fabric's wins. Every dual-dialect file in the mod sets measured here says the same thing
 * in both, so that ordering is stated rather than tested.
 *
 * <p>{@code -Dforbric.fabricConditions=off} restores the previous behaviour, which is "everything loads".
 */
public final class KernelFabricConditions {

	private static final String IMPL = "net.fabricmc.fabric.impl.resource.conditions.ResourceConditionsImpl";
	private static final String KEY = "fabric:load_conditions";

	private static final AtomicInteger JUDGED = new AtomicInteger();
	private static final AtomicInteger REJECTED = new AtomicInteger();
	private static volatile boolean announced;
	private static volatile boolean reportedAbsent;

	/** Resolved once, lazily: the first decode can happen before fabric-api's own classes are reachable. */
	private static volatile MethodHandle evaluator;
	private static volatile boolean evaluatorResolved;

	private KernelFabricConditions() {
	}

	/**
	 * Wraps {@code ConditionalOps}' one codec factory so a Fabric condition gets asked before NeoForge decodes.
	 *
	 * <p>Raw {@code Codec} in and out on purpose: the call site is an inserted instruction in NeoForge's own
	 * factory, so the descriptor has to be exactly the one already on the stack — one instruction, no stack
	 * change, no frame to recompute.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Codec alsoAskFabric(Codec conditional) {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.fabricConditions", "on"))) return conditional;
		return new Codec<Object>() {
			@Override
			public <T> DataResult<Pair<Object, T>> decode(DynamicOps<T> ops, T input) {
				if (input instanceof JsonObject json && json.has(KEY)) {
					Boolean keep = ask(json, ops);
					if (Boolean.FALSE.equals(keep)) {
						return DataResult.success(Pair.of((Object) Optional.empty(), ops.empty()));
					}
				}
				return ((Codec<Object>) conditional).decode(ops, input);
			}

			@Override
			public <T> DataResult<T> encode(Object value, DynamicOps<T> ops, T prefix) {
				return ((Codec<Object>) conditional).encode(value, ops, prefix);
			}

			@Override
			public String toString() {
				return "Forbric(" + conditional + ")";
			}
		};
	}

	/**
	 * fabric-api's own verdict, or null when it could not be asked.
	 *
	 * <p>Their evaluator rather than a reimplementation, deliberately: their {@code CONDITION_CODEC} accepts both
	 * the single-object form and the list form mods use interchangeably, and their policy on a condition that
	 * fails to PARSE is "keep the element". Reproducing either by hand is how the two would drift.
	 */
	private static Boolean ask(JsonObject json, DynamicOps<?> ops) {
		MethodHandle handle = evaluatorHandle();
		if (handle == null) {
			if (!reportedAbsent) {
				reportedAbsent = true;
				ForbricLog.warn("[Forbric/Conditions] fabric-api's resource-condition evaluator is not installed, so "
						+ "element(s) carrying %s are kept unjudged — a Fabric mod's config toggle over its own "
						+ "data files does nothing", KEY);
			}
			return null;
		}
		RegistryOps.RegistryInfoLookup lookup =
				ops instanceof RegistryOps<?> registry ? registry.lookupProvider : null;
		try {
			boolean keep = (boolean) handle.invoke(json, KEY, (Identifier) null, lookup);
			int judged = JUDGED.incrementAndGet();
			if (!announced) {
				announced = true;
				ForbricLog.info("[Forbric/Conditions] Fabric's own resource-condition evaluator is live — its two "
						+ "mixins for this cannot apply on the merged base (one anchor was replaced by NeoForge's "
						+ "patch, the other's descriptor moved), so the kernel calls %s directly", IMPL);
			}
			if (!keep) {
				ForbricLog.info("[Forbric/Conditions] %s said no to a data file — %d of %d element(s) carrying the "
						+ "key have been rejected", KEY, REJECTED.incrementAndGet(), judged);
			}
			return keep;
		} catch (Throwable t) {
			// Keep the element. A judgement that could not be made is not a "no": the previous behaviour was to
			// load everything, and failing closed here would delete content over a reflection problem.
			ForbricLog.warn("[Forbric/Conditions] could not ask fabric-api about " + KEY + " — the element is kept",
					Reflect.unwrap(t));
			return null;
		}
	}

	private static MethodHandle evaluatorHandle() {
		if (evaluatorResolved) return evaluator;
		synchronized (KernelFabricConditions.class) {
			if (evaluatorResolved) return evaluator;
			try {
				Class<?> impl = Class.forName(IMPL, false, KernelFabricConditions.class.getClassLoader());
				evaluator = MethodHandles.lookup().findStatic(impl, "applyResourceConditions",
						MethodType.methodType(boolean.class, JsonObject.class, String.class, Identifier.class,
								RegistryOps.RegistryInfoLookup.class));
			} catch (Throwable absent) {
				evaluator = null;
			}
			evaluatorResolved = true;
			return evaluator;
		}
	}

	/** Test seam: fabric-api is not on any test classpath, so the evaluator has to be substitutable. */
	static void bindForTest(MethodHandle handle) {
		synchronized (KernelFabricConditions.class) {
			evaluator = handle;
			evaluatorResolved = true;
			announced = false;
			reportedAbsent = false;
			JUDGED.set(0);
			REJECTED.set(0);
		}
	}

	/** Test seam: how many elements carrying the key were judged, and how many of them were rejected. */
	static int[] countsForTest() {
		return new int[] {JUDGED.get(), REJECTED.get()};
	}
}
