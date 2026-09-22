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

package net.forbric.kernel.interop;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * What each ecosystem registered a payload type for, against what actually got declared to the peer.
 *
 * <h2>Why this is the family with no instrument</h2>
 *
 * <p>Five gates exist for networking and every one of them was red when it was written, with the worst symptoms
 * this project has: cannot connect, kicked one second after joining, a block decoded as a different block. On
 * one connection three handshake protocols are live, each treats the first {@code minecraft:register} it sees as
 * the peer's complete declaration, and each can veto the connection on its own.
 *
 * <p>The specific failure this counts is the one that kicked a player out of a world a second after joining: a
 * mod registers a payload type, nothing declares that channel to the peer, and the peer answers an unhandled
 * packet by disconnecting. Both halves are knowable at the moment they happen and neither was written down, so
 * the difference between them — a payload type with no channel declaration — was invisible until a mod used it.
 *
 * <p>Registration and declaration are recorded separately and joined at report time, because the whole point is
 * the difference. Everything here is best-effort and never throws: a census that can break a connection is worse
 * than no census.
 */
public final class NetworkChannelCensus {

	/** {@code -Dforbric.channelCensus=off}. */
	public static final String SWITCH = "forbric.channelCensus";

	private static final Map<Ecosystem, Set<String>> REGISTERED = new ConcurrentHashMap<>();
	private static final Map<Ecosystem, Set<String>> DECLARED = new ConcurrentHashMap<>();

	private NetworkChannelCensus() {
	}

	/** A payload type this ecosystem can send or receive. */
	public static void registered(Ecosystem ecosystem, Collection<?> channelIds) {
		add(REGISTERED, ecosystem, channelIds);
	}

	/** A channel actually announced to the peer. */
	public static void declared(Ecosystem ecosystem, Collection<?> channelIds) {
		add(DECLARED, ecosystem, channelIds);
	}

	private static void add(Map<Ecosystem, Set<String>> into, Ecosystem ecosystem, Collection<?> ids) {
		try {
			if (!enabled() || ecosystem == null || ids == null) return;
			Set<String> sink = into.computeIfAbsent(ecosystem,
					k -> Collections.synchronizedSet(new LinkedHashSet<>()));
			for (Object id : ids) {
				if (id == null) continue;
				String text = String.valueOf(id).strip();
				if (!text.isEmpty()) sink.add(text);
			}
		} catch (Throwable neverBreakAConnection) {
			// Deliberately swallowed: see the class javadoc.
		}
	}

	/** Forgets everything. For tests, and for a second connection in the same process. */
	public static void reset() {
		REGISTERED.clear();
		DECLARED.clear();
	}

	/**
	 * Channels some ecosystem registered a payload type for that NOBODY declared to the peer.
	 *
	 * <p>This is the set that gets a player kicked: the mod can build the packet, the peer never agreed to
	 * receive it, and an unhandled payload is a disconnect rather than a skip.
	 */
	public static List<String> registeredButNeverDeclared() {
		Set<String> declared = new TreeSet<>();
		DECLARED.values().forEach(declared::addAll);
		Set<String> undeclared = new TreeSet<>();
		REGISTERED.values().forEach(undeclared::addAll);
		undeclared.removeAll(declared);
		return List.copyOf(undeclared);
	}

	/**
	 * The one line a gate greps.
	 *
	 * <p>Shaped like every other census here: the denominator first, so a run that recorded nothing cannot read
	 * like a run that found nothing wrong.
	 */
	public static String summary() {
		Map<String, Integer> registeredBy = new TreeMap<>();
		REGISTERED.forEach((eco, ids) -> registeredBy.put(eco.name().toLowerCase(java.util.Locale.ROOT), ids.size()));
		Map<String, Integer> declaredBy = new TreeMap<>();
		DECLARED.forEach((eco, ids) -> declaredBy.put(eco.name().toLowerCase(java.util.Locale.ROOT), ids.size()));
		List<String> undeclared = registeredButNeverDeclared();
		return "[Forbric/Net] channel census: registered " + registeredBy + ", declared " + declaredBy
				+ ", registered-but-never-declared: " + undeclared.size()
				+ (undeclared.isEmpty() ? "" : " " + undeclared);
	}

	/** Says it once, wherever the connection has finished negotiating. */
	public static void report() {
		try {
			if (!enabled()) return;
			if (REGISTERED.isEmpty() && DECLARED.isEmpty()) return;
			ForbricLog.info("%s", summary());
			List<String> undeclared = registeredButNeverDeclared();
			if (!undeclared.isEmpty()) {
				ForbricLog.warn("[Forbric/Net] %d channel(s) have a payload type and no declaration: %s — a mod "
						+ "sending on one of these is not skipped, it disconnects the player",
						undeclared.size(), undeclared);
			}
		} catch (Throwable neverBreakAConnection) {
			// As above.
		}
	}

	private static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}
}
