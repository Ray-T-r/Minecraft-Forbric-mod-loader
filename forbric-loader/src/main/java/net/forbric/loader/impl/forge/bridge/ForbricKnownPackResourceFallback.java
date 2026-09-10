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

package net.forbric.loader.impl.forge.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Merged-client fallback for vanilla known-pack resources during registry configuration.
 *
 * <p>The protocol still negotiates known packs normally. This only prevents a merged base from losing access to
 * the core vanilla server-data pack while resolving registry entries that the server correctly omitted because the
 * client reported the vanilla known pack as available.
 */
public final class ForbricKnownPackResourceFallback {
	private static final String VANILLA_LOCATION_ID = "vanilla";
	private static final String VANILLA_KNOWN_PACK_ID = "core";

	private ForbricKnownPackResourceFallback() {
	}

	public static Object wrapVanillaServerDataIfNeeded(Object primary, ClassLoader cl) {
		if (primary == null || !isMergedForgeNeoBase(cl)) return primary;

		Object fallback = null;
		try {
			fallback = openVanillaServerDataManager(cl);
			@SuppressWarnings("unchecked")
			Set<String> namespaces = (Set<String>) invoke(fallback, "getNamespaces");
			if (!namespaces.contains("minecraft")) {
				closeQuietly(fallback);
				ForbricLog.warn("[Forbric/KnownPacks] vanilla server-data fallback opened without minecraft namespace");
				return primary;
			}
			ForbricLog.debug("[Forbric/KnownPacks] attached vanilla server-data fallback to merged client known-pack manager");
			Class<?> closeableResourceManagerCls =
					Class.forName("net.minecraft.server.packs.resources.CloseableResourceManager", false, cl);
			Object vanillaFallback = fallback;
			return Proxy.newProxyInstance(cl, new Class<?>[] { closeableResourceManagerCls },
					(proxy, method, args) -> invokeResourceManagerMethod(primary, vanillaFallback, method, args));
		} catch (Throwable t) {
			closeQuietly(fallback);
			ForbricLog.warn("[Forbric/KnownPacks] could not attach vanilla server-data fallback", t);
			return primary;
		}
	}

	private static Object openVanillaServerDataManager(ClassLoader cl) throws Exception {
		Class<?> serverPacksSourceCls =
				Class.forName("net.minecraft.server.packs.repository.ServerPacksSource", false, cl);
		Class<?> packTypeCls = Class.forName("net.minecraft.server.packs.PackType", false, cl);
		Class<?> multiPackResourceManagerCls =
				Class.forName("net.minecraft.server.packs.resources.MultiPackResourceManager", false, cl);
		Class<?> packRepositoryCls = Class.forName("net.minecraft.server.packs.repository.PackRepository", false, cl);

		Object repository = serverPacksSourceCls.getMethod("createVanillaTrustedRepository").invoke(null);
		packRepositoryCls.getMethod("reload").invoke(repository);
		Collection<String> selected = coreVanillaPackIds(packRepositoryCls.getMethod("getAvailablePacks").invoke(repository));
		packRepositoryCls.getMethod("setSelected", Collection.class).invoke(repository, selected);
		Object openPacks = packRepositoryCls.getMethod("openAllSelected").invoke(repository);
		Object serverData = packTypeCls.getField("SERVER_DATA").get(null);
		Constructor<?> ctor = multiPackResourceManagerCls.getConstructor(packTypeCls, List.class);
		return ctor.newInstance(serverData, openPacks);
	}

	private static Collection<String> coreVanillaPackIds(Object packs) throws Exception {
		List<String> ids = new ArrayList<>();
		if (packs instanceof Iterable<?> iterable) {
			for (Object pack : iterable) {
				Object location = invoke(pack, "location");
				Object knownOptional = invoke(location, "knownPackInfo");
				if (!(knownOptional instanceof Optional<?> optional) || optional.isEmpty()) continue;
				Object known = optional.get();
				if (Boolean.TRUE.equals(invoke(known, "isVanilla"))
						&& VANILLA_KNOWN_PACK_ID.equals(invoke(known, "id"))) {
					ids.add(String.valueOf(invoke(pack, "getId")));
				}
			}
		}
		if (ids.isEmpty()) ids.add(VANILLA_LOCATION_ID);
		return ids;
	}

	private static Object invokeResourceManagerMethod(Object primary, Object fallback, Method method, Object[] args)
			throws Throwable {
		String name = method.getName();
		if ("equals".equals(name) && method.getParameterCount() == 1) {
			return primary == (args == null ? null : args[0]);
		}
		if ("hashCode".equals(name) && method.getParameterCount() == 0) {
			return System.identityHashCode(primary);
		}
		if ("toString".equals(name) && method.getParameterCount() == 0) {
			return "ForbricVanillaFallbackResourceManager[" + primary + "]";
		}

		return switch (name) {
			case "getResource" -> {
				Optional<?> primaryResource = castOptional(invoke(method, primary, args));
				yield primaryResource.isPresent() ? primaryResource : invoke(method, fallback, args);
			}
			case "getNamespaces" -> mergedNamespaces(method, primary, fallback, args);
			case "getResourceStack" -> {
				List<?> stack = castList(invoke(method, primary, args));
				yield stack.isEmpty() ? invoke(method, fallback, args) : stack;
			}
			case "listResources", "listResourceStacks" -> mergedMaps(method, primary, fallback, args);
			case "listPacks" -> Stream.concat(castStream(invoke(method, primary, args)),
					castStream(invoke(method, fallback, args)));
			case "close" -> {
				closeBoth(method, primary, fallback, args);
				yield null;
			}
			default -> invoke(method, primary, args);
		};
	}

	private static Set<?> mergedNamespaces(Method method, Object primary, Object fallback, Object[] args)
			throws Throwable {
		Set<Object> out = new LinkedHashSet<>(castCollection(invoke(method, fallback, args)));
		out.addAll(castCollection(invoke(method, primary, args)));
		return out;
	}

	private static Map<?, ?> mergedMaps(Method method, Object primary, Object fallback, Object[] args)
			throws Throwable {
		Map<Object, Object> out = new LinkedHashMap<>(castMap(invoke(method, fallback, args)));
		out.putAll(castMap(invoke(method, primary, args)));
		return out;
	}

	private static void closeBoth(Method method, Object primary, Object fallback, Object[] args) throws Throwable {
		Throwable thrown = null;
		try {
			invoke(method, primary, args);
		} catch (Throwable t) {
			thrown = t;
		}
		try {
			invoke(method, fallback, args);
		} catch (Throwable t) {
			if (thrown == null) {
				thrown = t;
			} else {
				thrown.addSuppressed(t);
			}
		}
		if (thrown != null) throw thrown;
	}

	private static boolean isMergedForgeNeoBase(ClassLoader cl) {
		try {
			Class.forName("net.minecraftforge.registries.NamespacedWrapper", false, cl);
			Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry", false, cl);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static Object invoke(Object target, String method) throws Exception {
		Method m = target.getClass().getMethod(method);
		return invoke(m, target, null);
	}

	private static Object invoke(Method method, Object target, Object[] args) throws Exception {
		try {
			return method.invoke(target, args == null ? new Object[0] : args);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception exception) throw exception;
			if (cause instanceof Error error) throw error;
			throw e;
		}
	}

	private static Optional<?> castOptional(Object value) {
		return value instanceof Optional<?> optional ? optional : Optional.empty();
	}

	private static List<?> castList(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	private static Collection<?> castCollection(Object value) {
		return value instanceof Collection<?> collection ? collection : List.of();
	}

	@SuppressWarnings("unchecked")
	private static Map<Object, Object> castMap(Object value) {
		return value instanceof Map<?, ?> map ? (Map<Object, Object>) map : Map.of();
	}

	@SuppressWarnings("unchecked")
	private static Stream<Object> castStream(Object value) {
		return value instanceof Stream<?> stream ? (Stream<Object>) stream : Stream.empty();
	}

	private static void closeQuietly(Object manager) {
		if (manager == null) return;
		try {
			invoke(manager, "close");
		} catch (Throwable ignored) {
			// best-effort cleanup on an already-failed fallback path
		}
	}
}
