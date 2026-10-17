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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.payload.RecipeContentPayload;

import net.forbric.kernel.util.Caught;

/**
 * NeoForge's recipe sync without the recipes their own serializer cannot put on the wire.
 *
 * <p>Called from {@code CommonHooks.sendRecipes} on the payload it just built (see
 * {@code RecipeSyncFailSoftInjector}). Each recipe is encoded once, by the same {@code RecipeHolder.STREAM_CODEC} the
 * payload uses; one that throws is left out, with one warning naming it, and the rest are sent. The list is filtered
 * before the payload exists, so the count NeoForge writes always matches the entries and the client decodes as before.
 * Results are kept per recipe holder (weakly — a reload makes new holders), so a reload is tried once, not once per
 * player.
 */
public final class KernelRecipeSync {
	private static final Map<RecipeHolder<?>, Boolean> ENCODES = Collections.synchronizedMap(new WeakHashMap<>());

	private KernelRecipeSync() {
	}

	/** {@code payload} itself when every recipe encodes, otherwise a copy without the ones that do not. */
	public static RecipeContentPayload encodable(RecipeContentPayload payload, ServerPlayer player) {
		List<RecipeHolder<?>> recipes = payload.recipes();
		List<RecipeHolder<?>> kept = null;
		for (int i = 0; i < recipes.size(); i++) {
			RecipeHolder<?> recipe = recipes.get(i);
			if (encodes(recipe, player)) {
				if (kept != null) kept.add(recipe);
			} else if (kept == null) {
				kept = new ArrayList<>(recipes.subList(0, i));
			}
		}
		return kept == null ? payload : new RecipeContentPayload(payload.recipeTypes(), kept);
	}

	private static boolean encodes(RecipeHolder<?> recipe, ServerPlayer player) {
		Boolean known = ENCODES.get(recipe);
		if (known != null) return known;
		boolean encodes = true;
		ByteBuf scratch = Unpooled.buffer();
		try {
			RecipeHolder.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(scratch, player.registryAccess(),
					player.connection.getConnectionType()), recipe);
		} catch (RuntimeException | LinkageError refused) {
			encodes = false;
			Caught.warn(refused, "[Forbric/RecipeSync] left recipe %s (serializer %s) out of NeoForge's recipe sync — "
					+ "its own stream codec cannot encode it (%s). On NeoForge alone this disconnects every player who "
					+ "joins once any mod asks for that recipe type to be synced; here it still crafts on the server, "
					+ "clients just do not receive it (-Dforbric.recipeSyncFailSoft=off to send it anyway)",
					recipe.id().identifier(), serializerOf(recipe), refused);
		} finally {
			scratch.release();
		}
		ENCODES.put(recipe, encodes);
		return encodes;
	}

	private static Object serializerOf(RecipeHolder<?> recipe) {
		try {
			return BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.value().getSerializer());
		} catch (RuntimeException unknown) {
			Caught.expected(unknown);
			return recipe.value().getClass().getName();
		}
	}
}
