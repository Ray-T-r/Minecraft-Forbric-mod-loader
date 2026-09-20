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

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.crafting.ingredients.AbstractIngredient;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Lets MinecraftForge ingredient types ({@code forge:intersection}, {@code forge:difference},
 * {@code forge:compound}, {@code forge:nbt} and every serializer a Forge mod registers) decode on the merged base.
 *
 * <p>The merged {@code Ingredient.<clinit>} stores NeoForge's {@code IngredientCodecs.codec(base)} into
 * {@code CODEC} and nothing else: NeoForge's dispatch knows only {@code neoforge:*} types, and the vanilla codec
 * rejects a map, so a recipe with a Forge ingredient was "Parsing error loading recipe" and gone. The transformer
 * inserts one instruction before that single {@code PUTSTATIC}, handing the NeoForge codec through here.
 *
 * <p>The returned codec composes nothing itself: on every decode it asks the carrier's own
 * {@code ForgeHooks.ingredientBaseCodec(neo)} — Forge's real {@code either(registry dispatch, base)} with the
 * NeoForge codec as its base — so a {@code neoforge:*} or vanilla ingredient falls through to NeoForge exactly as a
 * vanilla one falls through to vanilla on genuine Forge. Encoding routes only Forge-built ingredients
 * ({@link AbstractIngredient} subclasses) through Forge: everything else encodes through NeoForge, because Forge's
 * encode path reads {@code Ingredient.VANILLA_SERIALIZER}, a static the merge left unwritten, for any ingredient
 * NeoForge's constructors built ({@code isVanilla} is only ever set by Forge's constructor).
 *
 * <p>A Forge decode that throws — the serializer registry not built yet, a serializer whose codec links against
 * something the base lacks — falls back to NeoForge's result for that one value, is logged once, and is NOT
 * cached as a failure: the next decode asks Forge again. {@code -Dforbric.forgeIngredients=off} returns NeoForge's
 * codec by identity.
 */
public final class KernelForgeIngredients {
	public static final String PROPERTY = "forbric.forgeIngredients";
	private static final Set<String> REPORTED = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private static volatile boolean failureReported;

	private KernelForgeIngredients() {
	}

	/** The one inserted call: NeoForge's codec in, the composed one out, {@code PUTSTATIC CODEC} stores it. */
	public static Codec<Ingredient> alsoAskMinecraftForge(Codec<Ingredient> neo) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return neo;
		// Codec.lazyInitialized inside: the registry is touched at the first decode, not here.
		Codec<Ingredient> forge = ForgeHooks.ingredientBaseCodec(neo);
		return new Codec<>() {
			@Override
			public <T> DataResult<Pair<Ingredient, T>> decode(DynamicOps<T> ops, T input) {
				if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return neo.decode(ops, input);
				try {
					DataResult<Pair<Ingredient, T>> result = forge.decode(ops, input);
					if (result.result().isPresent()) {
						String type = forgeType(ops, input);
						if (type != null) report(type);
					}
					return result;
				} catch (Throwable t) {
					if (!failureReported) {
						failureReported = true;
						ForbricLog.warn("[Forbric/Ingredients] MinecraftForge's ingredient codec threw — NeoForge's "
								+ "answer is used for this value and Forge is asked again next time", Reflect.unwrap(t));
					}
					return neo.decode(ops, input);
				}
			}

			@Override
			public <T> DataResult<T> encode(Ingredient value, DynamicOps<T> ops, T prefix) {
				if (value instanceof AbstractIngredient
						&& !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) {
					return forge.encode(value, ops, prefix);
				}
				return neo.encode(value, ops, prefix);
			}

			@Override
			public String toString() {
				return "Forbric(" + forge + ")";
			}
		};
	}

	/** The {@code type} of this map when it names a serializer MinecraftForge's registry has, else null. */
	private static <T> String forgeType(DynamicOps<T> ops, T input) {
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
			return ForgeRegistries.INGREDIENT_SERIALIZERS.get().containsKey(id) ? name.get() : null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static void report(String type) {
		if (!REPORTED.add(type)) return;
		ForbricLog.info("[Forbric/Ingredients] MinecraftForge ingredient type %s decoded — %d distinct type(s) so far; "
				+ "NeoForge's dispatch alone rejected them as a recipe parsing error", type, REPORTED.size());
	}
}
