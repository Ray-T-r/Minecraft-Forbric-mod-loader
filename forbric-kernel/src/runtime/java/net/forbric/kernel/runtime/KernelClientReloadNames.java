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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.neoforge.client.resources.VanillaClientListeners;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives a name to a client reload listener NeoForge refuses to name, instead of letting it kill the client.
 *
 * <h2>Why a listener with no name is fatal here and nowhere else</h2>
 *
 * <p>NeoForge sorts client reload listeners in a graph keyed by {@code Identifier}, and
 * {@code AddClientReloadListenersEvent.lookupName} is where a listener already present in the manager gets its
 * key. It asks {@code VanillaClientListeners.getNameForClass}, and when that returns null it THROWS: "A
 * non-vanilla reload listener … was added via mixin before the AddClientReloadListenerEvent! Mod-added listeners
 * must go through that event." That assertion is written for an instance where the only mods are NeoForge mods.
 *
 * <p>Adding a reload listener by mixin is ordinary Fabric practice — it is how a Fabric mod has always done it,
 * and there is no event for it to go through. So on a tri-ecosystem instance the assertion fires on correct mod
 * code, from inside {@code ClientHooks.initClientHooks}, which runs inside {@code Minecraft.<init>}: vistas took
 * the whole client down before it drew a frame.
 *
 * <h2>What the name is for, and why synthesising one is not a workaround</h2>
 *
 * <p>The name is a sort key and a registry key, nothing more: {@code SortedReloadListenerEvent} holds
 * {@code Map<Identifier, listener>}, {@code Map<listener, Identifier>} and a dependency graph over them. A
 * listener with a synthesised unique name is therefore a listener that is registered, sorted and RUN — which is
 * the whole difference between this and catching the exception. Derived from the class, so it is stable across
 * runs and unique by construction; a second listener of the same class would collide, and that is reported.
 */
public final class KernelClientReloadNames {

	private static final String NAMESPACE = "forbric";
	private static final Set<String> REPORTED = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private KernelClientReloadNames() {
	}

	/**
	 * What {@code AddClientReloadListenersEvent.lookupName} asks instead of {@code VanillaClientListeners}.
	 *
	 * <p>Vanilla's own table is asked first and wins, so a vanilla listener keeps the exact name NeoForge's own
	 * dependency edges are written against. Only a class the table does not know gets a synthesised one.
	 */
	public static Identifier nameFor(Class<? extends PreparableReloadListener> type) {
		Identifier known = VanillaClientListeners.getNameForClass(type);
		if (known != null) return known;

		String path = sanitise(type.getName());
		Identifier synthesised = Identifier.fromNamespaceAndPath(NAMESPACE, path);
		if (REPORTED.add(type.getName())) {
			ForbricLog.info("[Forbric/ClientReload] %s was added to the resource manager by a mixin, which is how a "
					+ "Fabric mod has always done it and which NeoForge's sorted-listener event refuses to name — "
					+ "it used to take the client down inside Minecraft.<init>. It is registered and sorted as %s, "
					+ "so it still runs", type.getName(), synthesised);
		}
		return synthesised;
	}

	/**
	 * A class name as an {@code Identifier} path.
	 *
	 * <p>{@code Identifier} accepts only {@code [a-z0-9_.-/]} in a path, and a class name has neither case nor
	 * {@code $} in that set. The mapping is lossy in principle and unique in practice for the thing it names —
	 * two classes that differ only in case or in {@code $} placement would collide, which is why the caller
	 * reports each distinct class it synthesises for.
	 */
	private static String sanitise(String className) {
		StringBuilder out = new StringBuilder(className.length());
		for (int i = 0; i < className.length(); i++) {
			char c = Character.toLowerCase(className.charAt(i));
			out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-'
					? c : '_');
		}
		return out.toString().toLowerCase(Locale.ROOT);
	}
}
