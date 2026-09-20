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

package net.forbric.kernel.access;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ForbricLog;

/**
 * The access directives — MinecraftForge/NeoForge {@code accesstransformer.cfg} lines and Fabric access-widener
 * entries — that named a member their target class does not have.
 *
 * <p>Both transformers apply by visiting a class and widening what they meet; a directive for a member the merge
 * renamed or dropped meets nothing and says nothing, and the mod that needed the access dies later on an
 * {@code IllegalAccessError} that names neither the directive nor the mod. Each transformer records such a
 * directive here as it finishes the class, with the jar the directive came from; {@link #report} says how many,
 * names each, and marks the owning jar's rows DEGRADED. A carrier's own directive is named but marks nothing.
 *
 * <p>Only a class that was actually transformed can have unmatched directives: a directive whose class never
 * loaded on this side is not counted, so a client-only entry costs a dedicated server nothing.
 */
public final class AccessCensus {
	/**
	 * One directive that matched nothing: which kind, from which jar, what it named, and whether the member NAME
	 * is there with another descriptor. That distinction is the whole judgement: a name the class does not have
	 * at all is a line the mod's file carries for another Minecraft version — a native loader ignores it just the
	 * same, and a real pack has two dozen (journeymap's SRG-named fields, old overloads) — while a name that IS
	 * there under another descriptor is a member the merge re-typed, and only that one costs the mod something
	 * it would have had on its own loader.
	 */
	public record Unmatched(String kind, String source, String directive, boolean retyped, boolean namePresent) {
		public Unmatched(String kind, String source, String directive) {
			this(kind, source, directive, false, false);
		}
	}


	private static final Set<Unmatched> UNMATCHED = new LinkedHashSet<>();
	private static int transformedClasses;

	private AccessCensus() {
	}

	/** A class one of the access transformers visited. */
	public static void transformed() {
		synchronized (UNMATCHED) {
			transformedClasses++;
		}
	}

	public static void unmatched(String kind, String source, String directive) {
		unmatched(kind, source, directive, false, false);
	}

	/**
	 * @param retyped	 judged re-typed by the merge: an access-widener FIELD whose name is there under another
	 *					descriptor — a Fabric widener is written against the exact vanilla version and field names
	 *					are never overloaded, so that is a merge re-typing and nothing else
	 * @param namePresent the name is there under another descriptor but the case is NOT judged: a method (an
	 *					overload this Minecraft lacks is at least as likely — bagus_lib's Model.animate, YACL's
	 *					Tooltip constructor — and a Forge AT is carried across versions unchanged)
	 */
	public static void unmatched(String kind, String source, String directive, boolean retyped, boolean namePresent) {
		synchronized (UNMATCHED) {
			UNMATCHED.add(new Unmatched(kind, source == null ? "?" : source, directive, retyped, namePresent));
		}
	}


	/** One count line always; one WARN per directive; DEGRADED on every row from a mod jar that owns one. */
	public static void report() {
		List<Unmatched> all;
		int transformed;
		synchronized (UNMATCHED) {
			all = new ArrayList<>(UNMATCHED);
			transformed = transformedClasses;
		}
		int at = 0, retyped = 0, unjudged = 0;
		for (Unmatched u : all) {
			if ("AT".equals(u.kind())) at++;
			if (u.retyped()) retyped++;
			else if (u.namePresent()) unjudged++;
		}
		ForbricLog.info("[Forbric/Access] %d directive(s) matched nothing across %d transformed class(es) (%d AT, %d AW): "
				+ "%d re-typed by the merge, %d with the name present under another descriptor (an overload this Minecraft "
				+ "lacks, or a re-typing — not judged), %d stale on this Minecraft as on a native loader", all.size(), transformed,
				at, all.size() - at, retyped, unjudged, all.size() - retyped - unjudged);
		for (Unmatched u : all) {
			boolean carrier = u.source().startsWith("carrier:");
			if (!u.retyped()) {
				ForbricLog.info("[Forbric/Access] %s directive from %s names a member this Minecraft does not have (%s): %s", u.kind(),
						u.source(), u.namePresent() ? "the name is there under another descriptor; not judged"
								: "stale, ignored here as on a native loader", u.directive());
				continue;
			}
			ForbricLog.warn("[Forbric/Access] %s directive from %s names a member the merge re-typed, so it was not widened: %s%s",
					u.kind(), u.source(), u.directive(), carrier ? "" : " — the mod is marked on the Mods screen");
			if (!carrier && !"?".equals(u.source())) {
				ModCatalog.markByJar(u.source(), ModCatalog.Status.DEGRADED, "its access " + ("AT".equals(u.kind())
						? "transformer" : "widener") + " names " + u.directive() + ", which the merge re-typed");
			}
		}
	}

	/** Package-private, for the tests. */
	static List<Unmatched> entries() {
		synchronized (UNMATCHED) {
			return List.copyOf(UNMATCHED);
		}
	}

	static void reset() {
		synchronized (UNMATCHED) {
			UNMATCHED.clear();
			transformedClasses = 0;
		}
	}
}
