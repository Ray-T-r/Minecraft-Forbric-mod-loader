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

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;

import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import net.forbric.kernel.util.ForbricLog;

/**
 * Stops one ecosystem's resource-condition dialect from failing the other ecosystem's data files.
 *
 * <h2>The failure</h2>
 *
 * <p>The merged {@code RegistryLoadTask$PendingRegistration.loadFromResource} carries NeoForge's patch: where
 * stock Minecraft calls {@code Decoder.parse} directly, the merged body wraps every element in
 * {@code ConditionalOps.createConditionalCodec}. That is unconditional and global — EVERY datapack-registry
 * element, from EVERY pack, is decoded through NeoForge's condition evaluator, whichever ecosystem's mod shipped
 * the file.
 *
 * <p>A multi-loader mod ships ONE data tree carrying both dialects, which is ordinary Architectury output:
 * {@code "fabric:load_conditions"} and {@code "neoforge:conditions"} side by side in the same json. A jar built
 * for Fabric registers its own condition type on the Fabric side only, so NeoForge's evaluator cannot resolve
 * the id, {@code ICondition.CODEC}'s registry dispatch returns an error, and
 * {@code RegistryDataLoader.logErrors} escalates it — "Failed to load registries due to errors". The server does
 * not start and the world does not open. waystones is the mod that demonstrated it; every multi-loader mod with
 * a condition of its own is the class.
 *
 * <h2>What this does instead</h2>
 *
 * <p>A condition type NeoForge does not know is not NeoForge's to judge. It is decoded as a condition that does
 * not veto, so the element loads and the OTHER ecosystem's evaluator — which owns that id — decides. Failing
 * the parse instead loses the element, the registry and the world; ignoring one condition's opinion loses one
 * condition's opinion.
 *
 * <p>The id is checked against the registry rather than the error MESSAGE being pattern-matched. A message is
 * upstream's to reword, and a leniency that silently stops applying is the shape this project has paid for
 * before.
 *
 * <p>Every distinct id is reported once, with the count, because "your world loaded" and "your world loaded and
 * three conditions were ignored" are different facts and only the log can carry the second.
 */
public final class KernelNeoConditions {

	/** Decoded in place of a condition whose type belongs to another ecosystem's registry. */
	private static final ICondition FOREIGN = new ICondition() {
		@Override
		public boolean test(ICondition.IContext context) {
			return true;
		}

		@Override
		public MapCodec<? extends ICondition> codec() {
			// Never registered, so it can be decoded and never re-encoded. encode() below refuses first, with a
			// message that says which of those two happened.
			return MapCodec.unit(this);
		}

		@Override
		public String toString() {
			return "forbric:foreign-condition";
		}
	};

	/**
	 * The overlay twin of {@link #FOREIGN}: a condition no evaluator on this instance can judge, met on a
	 * pack.mcmeta OVERLAY entry. A data file has a second evaluator afterwards, an overlay entry has none — so the
	 * only safe answer is "no", through NeoForge's own drop path ({@code ConditionalDecoder} → {@code Optional.empty}
	 * → {@code listWithoutEmpty}); the kernel only changes the answer, never the mechanism.
	 */
	private static final ICondition VETO = new ICondition() {
		@Override
		public boolean test(ICondition.IContext context) {
			return false;
		}

		@Override
		public MapCodec<? extends ICondition> codec() {
			return MapCodec.unit(this);
		}

		@Override
		public String toString() {
			return "forbric:vetoed-overlay-condition";
		}
	};

	/** {@code -Dforbric.overlayConditions=off}: mount an overlay gated by an unjudgeable condition, as before. */
	public static final String OVERLAY_PROPERTY = "forbric.overlayConditions";

	/** The foreign types met while decoding one pack.mcmeta overlay list; present only during that decode. */
	private static final ThreadLocal<Set<String>> OVERLAY = new ThreadLocal<>();
	private static final Set<String> VETOED_REPORTED = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private static final Set<String> REPORTED = Collections.newSetFromMap(new ConcurrentHashMap<>());

	/** {@code -Dforbric.neoConditions=off}: decode strictly, exactly as the carrier's own codec would. Read per decode. */
	public static final String PROPERTY = "forbric.neoConditions";

	/** Whether a condition type id is registered; substitutable so a unit test never initialises the registry. */
	private static volatile java.util.function.Predicate<String> known = id -> {
		// An id that does not even parse is "known": the strict codec then fails it exactly as before.
		Identifier parsed = Identifier.tryParse(id);
		return parsed == null || NeoForgeRegistries.CONDITION_SERIALIZERS.containsKey(parsed);
	};

	private KernelNeoConditions() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Test seam: the registry lookup, replaced. */
	static void bindKnownTypesForTest(java.util.function.Predicate<String> knownTypes) {
		known = knownTypes;
	}

	/**
	 * Wraps NeoForge's own {@code ICondition} codec, which is what {@code ICondition.<clinit>} hands over.
	 *
	 * <p>Declared and returned as {@code Codec} so the rewritten {@code <clinit>} is one inserted instruction
	 * with nothing on the stack moved: the dispatch codec goes in, the lenient one comes out, the existing
	 * {@code PUTSTATIC} stores it, and {@code LIST_CODEC} — built from {@code CODEC} two instructions later —
	 * inherits the leniency for free.
	 */
	public static Codec<ICondition> lenient(Codec<ICondition> strict) {
		return new Codec<>() {
			@Override
			public <T> DataResult<Pair<ICondition, T>> decode(DynamicOps<T> ops, T input) {
				if (!enabled()) return strict.decode(ops, input);
				String foreign = foreignType(ops, input);
				if (foreign != null) {
					report(foreign);
					Set<String> overlay = OVERLAY.get();
					if (overlay != null && overlayVetoEnabled()) {
						overlay.add(foreign);
						return DataResult.success(Pair.of(VETO, ops.empty()));
					}
					return DataResult.success(Pair.of(FOREIGN, ops.empty()));
				}
				return strict.decode(ops, input);
			}

			@Override
			public <T> DataResult<T> encode(ICondition value, DynamicOps<T> ops, T prefix) {
				if (value == FOREIGN || value == VETO) {
					return DataResult.error(() -> "a resource condition belonging to another ecosystem was read "
							+ "and cannot be written back");
				}
				return strict.encode(value, ops, prefix);
			}

			@Override
			public String toString() {
				return "Forbric(" + strict + ")";
			}
		};
	}

	/**
	 * The {@code type} of this condition when it names something NeoForge's registry does not have, else null.
	 *
	 * <p>Anything unreadable returns null, which hands the input back to the strict codec: a malformed condition
	 * has to keep producing NeoForge's own error, or this leniency would swallow genuinely broken data.
	 */
	public static boolean overlayVetoEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(OVERLAY_PROPERTY, "on"));
	}

	/**
	 * Wraps the overlay-entry LIST codec ({@code ConditionalOps.decodeListWithElementConditions(IntermediateEntry.CODEC)})
	 * so that, for the duration of its decode, {@link #lenient} answers a foreign type with {@link #VETO}; then names
	 * what NeoForge dropped by diffing the input's {@code directory} strings against the surviving entries.
	 * Inserted by the merged-base compat transformer in {@code OverlayEntry.listCodecForPackType}, which both the
	 * vanilla {@code overlays} and the {@code neoforge:overlays} section read through.
	 */
	public static <E> Codec<List<E>> forOverlayEntries(Codec<List<E>> listCodec) {
		if (!overlayVetoEnabled()) return listCodec;
		return new Codec<>() {
			@Override
			public <T> DataResult<Pair<List<E>, T>> decode(DynamicOps<T> ops, T input) {
				Set<String> scope = new LinkedHashSet<>();
				OVERLAY.set(scope);
				DataResult<Pair<List<E>, T>> result;
				try {
					result = listCodec.decode(ops, input);
				} finally {
					OVERLAY.remove();
				}
				if (!scope.isEmpty()) {
					result.result().ifPresent(pair -> nameTheDropped(ops, input, pair.getFirst(), scope));
				}
				return result;
			}

			@Override
			public <T> DataResult<T> encode(List<E> value, DynamicOps<T> ops, T prefix) {
				return listCodec.encode(value, ops, prefix);
			}

			@Override
			public String toString() {
				return "ForbricOverlay(" + listCodec + ")";
			}
		};
	}

	private static <T, E> void nameTheDropped(DynamicOps<T> ops, T input, List<E> kept, Set<String> foreignTypes) {
		List<String> asked = new ArrayList<>();
		try {
			ops.getList(input).result().ifPresent(each -> each.accept(element -> {
				String directory = stringField(ops, element, "directory");
				if (directory != null) asked.add(directory);
			}));
		} catch (Throwable ignored) {
			return;
		}
		Set<String> mounted = new LinkedHashSet<>();
		for (E entry : kept) {
			try {
				Object overlay = entry.getClass().getMethod("overlay").invoke(entry);
				if (overlay != null) mounted.add(overlay.toString());
			} catch (Throwable ignored) {
				// not the entry type we expect: nothing to name
			}
		}
		String types = String.join(", ", foreignTypes);
		for (String directory : asked) {
			if (mounted.contains(directory) || !VETOED_REPORTED.add(directory + "|" + types)) continue;
			ForbricLog.warn("[Forbric/Conditions] overlay directory '%s' is gated by condition type '%s', which no evaluator "
					+ "on this instance can judge — NOT mounted (a pack.mcmeta overlay has no second evaluator, unlike a "
					+ "data file; -D%s=off mounts it as before)", directory, types, OVERLAY_PROPERTY);
			net.forbric.kernel.boot.KernelPackRepair.overlayVetoed(directory, types);
		}
	}

	private static <T> String stringField(DynamicOps<T> ops, T map, String key) {
		try {
			Optional<Map<T, T>> values = ops.getMapValues(map)
					.map(stream -> stream.collect(java.util.stream.Collectors.toMap(Pair::getFirst, Pair::getSecond, (a, b) -> b)))
					.result();
			if (values.isEmpty()) return null;
			for (Map.Entry<T, T> entry : values.get().entrySet()) {
				if (ops.getStringValue(entry.getKey()).result().filter(key::equals).isPresent()) {
					return ops.getStringValue(entry.getValue()).result().orElse(null);
				}
			}
		} catch (Throwable ignored) {
			// fall through
		}
		return null;
	}

	private static <T> String foreignType(DynamicOps<T> ops, T input) {
		try {
			Optional<Map<T, T>> map = ops.getMapValues(input)
					.map(stream -> stream.collect(java.util.stream.Collectors.toMap(Pair::getFirst,
							Pair::getSecond, (a, b) -> b)))
					.result();
			if (map.isEmpty()) return null;
			T type = null;
			for (Map.Entry<T, T> entry : map.get().entrySet()) {
				if (ops.getStringValue(entry.getKey()).result().filter("type"::equals).isPresent()) {
					type = entry.getValue();
				}
			}
			if (type == null) return null;
			Optional<String> name = ops.getStringValue(type).result();
			if (name.isEmpty()) return null;
			return known.test(name.get()) ? null : name.get();
		} catch (Throwable t) {
			return null;
		}
	}

	private static void report(String type) {
		if (!REPORTED.add(type)) return;
		ForbricLog.warn("[Forbric/Conditions] resource condition '%s' is not in NeoForge's condition registry, so "
				+ "NeoForge's evaluator — which the merged base runs over EVERY datapack element from every pack — "
				+ "could not judge it and used to fail the whole registry load with it. It is being ignored here "
				+ "instead; the ecosystem that owns that id decides. %d distinct condition(s) so far",
				type, REPORTED.size());
	}
}
