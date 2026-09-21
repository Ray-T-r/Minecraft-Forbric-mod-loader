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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Turns encoded world data into the form two encoders can be compared in.
 *
 * <p>{@link KernelForgeWorldgen}'s round-trip audit rebuilds every biome through MinecraftForge's builders and
 * compares the result with the original. Any difference and it stands the whole modifier bridge down — a
 * deliberately conservative call, because the alternative is applying modifiers through builders that quietly
 * lose world data.
 *
 * <p>It compared encoded JSON exactly, and two differences that mean nothing were enough to trip it.
 * MinecraftForge's builder does not write out a trailing EMPTY decoration step or an empty spawner category, and
 * it emits the spawner categories in {@code MobCategory}'s order rather than the datapack's. On Stellarity's
 * biomes that was 36 of 98 "differing", and every MinecraftForge biome and structure modifier in the pack stood
 * down over empty brackets and key order — Animal Garden's redpanda spawns among them, reported to the player
 * only as a load-report row.
 *
 * <p>Forgives exactly three things, and nothing else: member order (a JSON object is unordered), members whose
 * value is an empty container, and empty elements at the TAIL of an array. An empty element in the MIDDLE holds a
 * position — the decoration steps are positional — so it is kept, and a container that LOST content still
 * differs, which is the case the audit exists for.
 *
 * <p>{@code -Dforbric.worldgenAuditNormalise=off} compares the encodings exactly, as before.
 */
public final class WorldDataShape {
	static final String SWITCH = "forbric.worldgenAuditNormalise";

	private WorldDataShape() {
	}

	static boolean normalising() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/** {@code element} rendered so that two encodings of the same world data render the same. */
	public static String comparable(JsonElement element) {
		return String.valueOf(normalising() ? withoutEmptyContainers(element) : element);
	}

	static JsonElement withoutEmptyContainers(JsonElement element) {
		if (element instanceof JsonObject object) {
			// Members SORTED, because a JSON object is unordered and the two sides write it in different orders.
			JsonObject out = new JsonObject();
			List<String> keys = new ArrayList<>(object.keySet());
			Collections.sort(keys);
			for (String key : keys) {
				JsonElement value = withoutEmptyContainers(object.get(key));
				if (isEmptyContainer(value)) continue;
				out.add(key, value);
			}
			return out;
		}
		if (element instanceof JsonArray array) {
			List<JsonElement> items = new ArrayList<>();
			for (JsonElement item : array) items.add(withoutEmptyContainers(item));
			while (!items.isEmpty() && isEmptyContainer(items.get(items.size() - 1))) items.remove(items.size() - 1);
			JsonArray out = new JsonArray();
			for (JsonElement item : items) out.add(item);
			return out;
		}
		return element;
	}

	private static boolean isEmptyContainer(JsonElement element) {
		return (element instanceof JsonArray array && array.isEmpty())
				|| (element instanceof JsonObject object && object.isEmpty());
	}
}
