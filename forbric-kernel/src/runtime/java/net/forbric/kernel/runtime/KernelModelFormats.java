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
import java.lang.reflect.Type;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;

import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.neoforged.neoforge.client.model.UnbakedModelParser;

import net.forbric.kernel.transform.ModelFormatFunnelInjector;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * Lets a model format NeoForge does not own be parsed by the code that does own it.
 *
 * <h2>Three model-format mechanisms, one deserializer</h2>
 *
 * <p>Every block model on the merged base is read by {@code UnbakedModelParser.parse}, which goes through
 * {@code CuboidModel.GSON}, whose adapter for {@code UnbakedModel} is NeoForge's
 * {@code UnbakedModelParser$Deserializer}. Natively each ecosystem has its own way to plug a custom format in, and
 * two of the three sit BEHIND that deserializer:
 * <ul>
 *   <li>NeoForge: {@code "loader": "<id>"} dispatched to a registered {@code UnbakedModelLoader}. This is the
 *       deserializer itself, and it throws {@code Unknown loader} for any id it did not register — before it ever
 *       reaches {@code context.deserialize(json, CuboidModel.class)}.</li>
 *   <li>MinecraftForge: the same {@code "loader"} key, read by the vanilla {@code CuboidModel$Deserializer}
 *       ({@code getElements} asks {@code ForgeHooksClient.deserializeBlockModelGeometry}, which asks
 *       {@code GeometryLoaderManager}). Mods that add a format without a geometry loader hook that same
 *       deserializer: fusion's {@code CuboidModelDeserializerMixin} claims {@code "loader": "fusion:model"} at
 *       its HEAD. On the merged base NeoForge's throw comes first, so every one of them was unreachable —
 *       MinecraftForge's own {@code forge:} loaders included.</li>
 *   <li>Fabric: {@code "fabric:type"}, dispatched through fabric-model-loading's {@code UnbakedModelDeserializer}
 *       registry by a pair of injectors on {@code ModelManager} that cannot fit the merged base
 *       ({@code GuestInjectorPruner} removes them). NeoForge's deserializer never looks at the key, so the model
 *       parsed as a plain empty cuboid.</li>
 * </ul>
 *
 * <p>What that cost, measured on the sweep90 pack: all 220 of Traveler's Backpack's backpack models failed to
 * bake with {@code Expected BackpackDynamicModel, instead received ...CuboidModel} (its models are
 * {@code "fabric:type": "travelersbackpack:backpack"}), and Rechiseled Anti-Blocks' 24 connected-texture models
 * ({@code "loader": "fusion:model"}) would each have died on {@code Unknown loader} the moment their overlay was
 * mounted.
 *
 * <h2>The funnel</h2>
 *
 * <p>{@code ModelFormatFunnelInjector} inserts one call to {@link #foreign} at the top of NeoForge's
 * deserializer, after it has checked that the element is an object and before it reads {@code "loader"}. A
 * non-null answer is returned as the model; null means "NeoForge's", and its dispatch runs exactly as before.
 * What stays unchanged, deliberately:
 * <ul>
 *   <li>A loader id NeoForge registered is always NeoForge's. Precedence is not renegotiated.</li>
 *   <li>A string loader nobody claims still fails the model — with MinecraftForge's
 *       {@code Model loader '%s' not found} instead of NeoForge's message, because that is the last deserializer
 *       that looked. There is no "whose namespace is this" heuristic: {@code forge:} is not a mod jar's namespace,
 *       and a loader's namespace need not be its mod's.</li>
 *   <li>NeoForge's object form, {@code {"id": ..., "optional": ...}}, is NeoForge's own dialect; a non-optional
 *       miss keeps NeoForge's error.</li>
 * </ul>
 *
 * <p>One NeoForge behaviour is repaired on the way through, because the funnel would otherwise inherit it: an
 * OPTIONAL object-form loader that is absent means "parse this as a plain model", and NeoForge does that by
 * falling through to the cuboid deserializer with the object still under {@code "loader"} — where the merged
 * {@code getElements} is MinecraftForge's, reads the key as a string and throws. The plain parse gets a copy
 * without the key, which is what native NeoForge's deserializer would have seen.
 *
 * <p>{@code -Dforbric.modelFormatFunnel=off} restores the previous behaviour on both halves: the injector is not
 * registered, and this method answers null.
 */
public final class KernelModelFormats {
	private static final String FABRIC_KEY = "fabric:type";
	private static final String LOADER_KEY = "loader";
	private static final String FABRIC_API = "net.fabricmc.fabric.api.client.model.loading.v1.UnbakedModelDeserializer";

	/** One line per route and format id, the first time it is taken — the live evidence, bounded by format count. */
	private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

	/** fabric-model-loading's registry lookup and its deserializer call, resolved once, lazily. */
	private static volatile MethodHandle fabricLookup;
	private static volatile MethodHandle fabricDeserialize;
	private static volatile boolean fabricResolved;
	private static volatile boolean reportedFabricAbsent;

	private KernelModelFormats() {
	}

	/**
	 * The model for {@code json} when its format is not NeoForge's to parse, or null to let NeoForge's
	 * deserializer carry on.
	 *
	 * <p>Called from NeoForge's {@code UnbakedModelParser$Deserializer.deserialize}, so its descriptor is the one
	 * the inserted call site pushes: the {@code JsonObject} NeoForge has just unwrapped and the context it was
	 * handed.
	 */
	public static UnbakedModel foreign(JsonObject json, JsonDeserializationContext context) {
		if (!enabled() || json == null) return null;
		if (json.has(FABRIC_KEY)) {
			UnbakedModel fabric = fabricType(json, context);
			if (fabric != null) return fabric;
		}
		JsonElement loader = json.get(LOADER_KEY);
		if (loader == null) return null;
		if (loader.isJsonPrimitive() && loader.getAsJsonPrimitive().isString()) {
			Identifier id = Identifier.tryParse(loader.getAsString());
			// An id that does not parse is left to NeoForge, whose Identifier.parse reports it as it always has.
			if (id == null || neoForgeOwns(id)) return null;
			announce(LOADER_KEY, id, "\"loader\": \"%s\" is not a NeoForge loader — handed to the vanilla cuboid "
					+ "deserializer, where MinecraftForge's geometry loaders and guest hooks on it (fusion) read the "
					+ "key as they do on MinecraftForge; an id none of them claims still fails the model");
			return asCuboid(json, context);
		}
		if (loader.isJsonObject()) {
			JsonObject spec = loader.getAsJsonObject();
			if (!GsonHelper.isStringValue(spec, "id")) return null;
			Identifier id = Identifier.tryParse(spec.get("id").getAsString());
			if (id == null || neoForgeOwns(id)) return null;
			// NeoForge's own dialect: a required miss is NeoForge's error to report, in NeoForge's words.
			if (!GsonHelper.getAsBoolean(spec, "optional", false)) return null;
			JsonObject plain = json.deepCopy();
			plain.remove(LOADER_KEY);
			announce("optional " + LOADER_KEY, id, "optional loader %s is absent — parsed as a plain model without "
					+ "the loader object, which MinecraftForge's half of the merged cuboid deserializer would read as a "
					+ "string and reject");
			return asCuboid(plain, context);
		}
		return null;
	}

	/**
	 * fabric-model-loading's answer for a {@code fabric:type} model, or null when there is none to give.
	 *
	 * <p>The key is read exactly as fabric-api's {@code UnbakedModelJsonDeserializer} reads it — a string, or an
	 * object with {@code id} and {@code optional} — with its two error messages, so a broken model fails the same
	 * way it does on Fabric. The one difference is an OPTIONAL type that nobody registered: Fabric then parses a
	 * plain cuboid, and here NeoForge's deserializer carries on, which for a model with no {@code "loader"} is the
	 * same plain cuboid.
	 *
	 * <p>Without fabric-model-loading nothing reads the key, on any loader — so it is not even parsed then.
	 */
	private static UnbakedModel fabricType(JsonObject json, JsonDeserializationContext context) {
		if (!resolveFabric()) return null;
		JsonElement spec = json.get(FABRIC_KEY);
		String type;
		boolean optional;
		if (spec.isJsonPrimitive()) {
			type = spec.getAsString();
			optional = false;
		} else if (spec.isJsonObject()) {
			JsonObject object = spec.getAsJsonObject();
			type = GsonHelper.getAsString(object, "id");
			optional = GsonHelper.getAsBoolean(object, "optional", false);
		} else {
			throw new JsonSyntaxException("Expected " + FABRIC_KEY + " to be a string or object, was "
					+ GsonHelper.getType(spec));
		}
		Identifier id = Identifier.parse(type);

		Object deserializer;
		try {
			deserializer = fabricLookup.invoke(id);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (deserializer == null) {
			if (optional) return null;
			throw new JsonParseException("Cannot deserialize custom unbaked model of unknown type '" + id + "'");
		}
		announce(FABRIC_KEY, id, FABRIC_KEY + " %s parsed by the deserializer its mod registered with "
				+ "fabric-model-loading — the two injectors that did this on Fabric cannot fit the merged base");
		try {
			return (UnbakedModel) fabricDeserialize.invoke(deserializer, json, context);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/**
	 * Whether NeoForge registered a loader under {@code id}.
	 *
	 * <p>Before NeoForge's loader registry is initialised the lookup NPEs; that is answered "NeoForge's", so the
	 * failure is NeoForge's own, raised one instruction later by the code that always raised it.
	 */
	private static boolean neoForgeOwns(Identifier id) {
		try {
			return UnbakedModelParser.get(id) != null;
		} catch (RuntimeException notYetInitialised) {
			return true;
		}
	}

	/**
	 * The vanilla deserializer's answer, reached the way NeoForge reaches it for a model without a loader.
	 *
	 * <p>Through {@code (Type)} on purpose: the generic {@code deserialize} would otherwise make javac cast the
	 * result to {@code CuboidModel}, and a guest hook on that deserializer answers with whatever its format builds.
	 * NeoForge's own call site casts only to {@code UnbakedModel}, so this does too.
	 */
	private static UnbakedModel asCuboid(JsonObject json, JsonDeserializationContext context) {
		Object parsed = context.deserialize(json, (Type) CuboidModel.class);
		return (UnbakedModel) parsed;
	}

	private static boolean resolveFabric() {
		if (!fabricResolved) {
			synchronized (KernelModelFormats.class) {
				if (!fabricResolved) {
					try {
						Class<?> api = Class.forName(FABRIC_API, false, KernelModelFormats.class.getClassLoader());
						MethodHandles.Lookup lookup = MethodHandles.publicLookup();
						fabricLookup = lookup.findStatic(api, "get", MethodType.methodType(api, Identifier.class));
						fabricDeserialize = lookup.findVirtual(api, "deserialize", MethodType.methodType(
								UnbakedModel.class, JsonObject.class, JsonDeserializationContext.class));
					} catch (Throwable absent) {
						fabricLookup = null;
						fabricDeserialize = null;
					}
					fabricResolved = true;
				}
			}
		}
		if (fabricLookup != null && fabricDeserialize != null) return true;
		if (!reportedFabricAbsent) {
			reportedFabricAbsent = true;
			ForbricLog.info("[Forbric/ModelFormats] a model declares %s but fabric-model-loading is not installed — "
					+ "it parses as a plain model, as it would on any loader without fabric-api", FABRIC_KEY);
		}
		return false;
	}

	private static void announce(String route, Identifier id, String what) {
		if (ANNOUNCED.add(route + " " + id)) ForbricLog.info("[Forbric/ModelFormats] " + what, id);
	}

	private static RuntimeException rethrow(Throwable t) {
		Throwable cause = Reflect.unwrap(t);
		if (cause instanceof RuntimeException runtime) return runtime;
		if (cause instanceof Error error) throw error;
		return new JsonParseException(cause);
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(ModelFormatFunnelInjector.PROPERTY, "on"));
	}
}
