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

	private static final Set<String> REPORTED = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private static final java.util.concurrent.atomic.AtomicBoolean OVERLAY_RISK =
			new java.util.concurrent.atomic.AtomicBoolean();

	private KernelNeoConditions() {
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
				String foreign = foreignType(ops, input);
				if (foreign != null) {
					report(foreign);
					return DataResult.success(Pair.of(FOREIGN, ops.empty()));
				}
				return strict.decode(ops, input);
			}

			@Override
			public <T> DataResult<T> encode(ICondition value, DynamicOps<T> ops, T prefix) {
				if (value == FOREIGN) {
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
			Identifier id = Identifier.tryParse(name.get());
			if (id == null) return null;
			return NeoForgeRegistries.CONDITION_SERIALIZERS.containsKey(id) ? null : name.get();
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
		if (OVERLAY_RISK.compareAndSet(false, true)) {
			// Known and unfixed, and said out loud rather than left to be discovered in a world. For a DATA
			// element "ignored" is safe: the owning ecosystem's evaluator judges it afterwards, which is the whole
			// design. For a pack.mcmeta OVERLAY entry there is no afterwards — Pack.readPackMetadata takes the
			// UNION of both sections' overlays, so a condition this evaluator cannot judge stops vetoing and the
			// directory mounts. Measured on Terralith: with "vanilla_stone_gen": false in its config, the six
			// placed_feature files under enable.vanilla_stone_gen carry no conditions of their own and override
			// vanilla granite, diorite and andesite generation anyway. No crash and no other log line.
			ForbricLog.warn("[Forbric/Conditions] if a condition of that kind gates a pack.mcmeta OVERLAY rather "
					+ "than a data file, ignoring it MOUNTS the overlay — content a mod's own config may have "
					+ "turned off can end up in your world with nothing else saying so. Known and not yet fixed");
		}
	}
}
