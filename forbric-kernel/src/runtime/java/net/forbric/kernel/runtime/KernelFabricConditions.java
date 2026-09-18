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
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

	/**
	 * Turns a guest mixin's private "skip this file" sentinel into the one the merged consumer understands.
	 *
	 * <h2>The half-applied pair</h2>
	 *
	 * <p>fabric-api's {@code SimpleJsonResourceReloadListenerMixin} is two injectors that only work together. The
	 * producer is a {@code @WrapOperation} on {@code Codec.parse} inside {@code scanDirectory}: when a file's
	 * {@code fabric:load_conditions} say no, it returns {@code DataResult.success(SKIP_DATA_MARKER)}, where the
	 * marker is a bare {@code new Object()}. The consumer is an {@code @Inject} on {@code lambda$scanDirectory$0}
	 * that recognises the marker and cancels the map-put.
	 *
	 * <p>On the merged base the producer applies and the consumer does not. The merge left the class carrying TWO
	 * methods named {@code lambda$scanDirectory$0} — NeoForge's {@code (Identifier,Identifier,Map,Optional)} and
	 * vanilla's {@code (Codec,Identifier,Map,Object)} — and the live {@code invokedynamic} binds the first. So
	 * fabric's {@code @Inject}, written against the second, attaches to nothing, and the bare Object flows into
	 * {@code DataResult.ifSuccess} whose consumer casts it to {@code Optional}: ClassCastException, datapack load
	 * fails, "can't proceed with server load", and the server never starts.
	 *
	 * <p>It needs no unusual mod set. Any instance with fabric-api plus any mod shipping a condition-gated file
	 * under a plain {@code scanDirectory} listener — an advancement, a loot table — whose condition evaluates
	 * false will reach it.
	 *
	 * <h2>Why Optional.empty()</h2>
	 *
	 * <p>Because that is the merged consumer's OWN vocabulary for "this file's conditions were not met" — it is
	 * what the surviving lambda already does with an empty value, and it logs exactly that. The substitution says
	 * what fabric's unattached {@code skipData} would have said.
	 *
	 * <p>Anything non-{@code Optional} reaching this point is already fatal at the cast one instruction later, so
	 * this cannot cost a file that would otherwise have loaded. What it could do is hide a FUTURE producer-side
	 * defect behind a skip, which is what the warning is for: the class of the value is named, once.
	 */
	/**
	 * Stands in for {@code DataResult.ifSuccess} at the two readers, filtering the marker out on the way through.
	 *
	 * <p>It replaces the call rather than wrapping its receiver because of where the receiver sits: the
	 * {@code invokedynamic} that builds the consumer pops three captured values first, so the {@code DataResult}
	 * is buried under them and there is no instruction boundary at which it is on top. Swapping
	 * {@code invokeinterface ifSuccess(Consumer)DataResult} for an {@code invokestatic} of the same shape moves
	 * nothing on the stack at all.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static DataResult ifSuccessWithoutAForeignSkipMarker(DataResult parsed, java.util.function.Consumer consumer) {
		return ((DataResult) withoutAForeignSkipMarker(parsed)).ifSuccess(consumer);
	}

	public static DataResult<?> withoutAForeignSkipMarker(DataResult<?> parsed) {
		if (parsed == null) return null;
		Object value = parsed.result().orElse(null);
		if (value == null || value instanceof Optional) return parsed;

		reportSkipMarker(value.getClass());
		return DataResult.success(Optional.empty());
	}

	private static final Set<String> MARKERS = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private static void reportSkipMarker(Class<?> type) {
		if (!MARKERS.add(type.getName())) return;
		ForbricLog.warn("[Forbric/Conditions] a data file was skipped by a guest mixin that could not finish the "
				+ "job: it produced %s where the merged reader expects Optional, because the merge left two "
				+ "methods named lambda$scanDirectory$0 and the half that mod patches is not the half that runs. "
				+ "Treated as 'conditions not met', which is what its other half would have done — without this "
				+ "the datapack load dies and the server does not start", type.getName());
	}
}
