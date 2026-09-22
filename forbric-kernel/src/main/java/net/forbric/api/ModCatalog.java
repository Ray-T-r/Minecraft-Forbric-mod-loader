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

package net.forbric.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Every mod running in this instance, from every ecosystem, with what it takes to SHOW one to a player.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link ModPresence} answers "is mod X installed" and stops there, because that is all a compatibility branch
 * needs. A mod list is the other question: a player opening the Mods screen wants the whole set, with names,
 * versions and descriptions, and there was no place that held it. Each family's screen reads its own family's
 * registry — NeoForge's {@code ModListScreen} reads {@code ModList.get()}, Mod Menu reads Fabric's containers —
 * so on a Forbric instance running 16 jars across three ecosystems, NeoForge's screen listed three of them and
 * Mod Menu listed the Fabric ones plus a row of question marks for everything it could only see as a bare id.
 *
 * <p>Neither screen is wrong. Each is complete for the loader it was written against, and no loader on a normal
 * instance has to answer for another one's mods. This catalogue is the answer for an instance that does.
 *
 * <h2>What it is not</h2>
 *
 * <p>Display metadata, and nothing else. Publishing a mod here gives it no container, no event bus, no config and
 * no place in any family's loading list — a multiplayer handshake announces a loading list, and a Fabric mod must
 * not turn into something this client claims to run under MinecraftForge. {@link ModPresence} draws that line for
 * presence; this draws the same one for display.
 *
 * <p>It is also not the arbitration result. A jar suppressed as a cross-ecosystem duplicate is not running and
 * does not appear; what appears is the winner, under the ecosystem that won it.
 *
 * <h2>Publishing</h2>
 *
 * <p>Once, during boot, by whoever has all three ecosystems' mods in hand — which is exactly one place. Readers
 * are game-side (the Mods screen), so this lives in {@code net.forbric.api}, which is parent-loaded: the screen
 * and the boot code that filled it see the same class and the same list.
 */
public final class ModCatalog {
	/**
	 * One mod as a player should see it.
	 *
	 * <p>Every string is non-null; absent metadata arrives as the empty string, because the one consumer is a
	 * renderer and a null there is a crash in the middle of a frame rather than a blank line. {@code iconPath} is
	 * the path INSIDE {@code jar} — resolving it is the caller's business, and the game side is the only place
	 * that can turn it into a texture anyway.
	 */
	public record Entry(Ecosystem ecosystem, String modId, String name, String version, String description,
			List<String> authors, String jar, String iconPath, String bundledBy, Status status,
			String statusDetail) {

		/**
		 * The nine-argument form every existing caller uses. A mod is {@link Status#OK} until something says
		 * otherwise, and the catalogue is built before anything can.
		 */
		public Entry(Ecosystem ecosystem, String modId, String name, String version, String description,
				List<String> authors, String jar, String iconPath, String bundledBy) {
			this(ecosystem, modId, name, version, description, authors, jar, iconPath, bundledBy, Status.OK, "");
		}

		public Entry {
			if (ecosystem == null) throw new NullPointerException("ecosystem");
			if (modId == null || modId.isBlank()) throw new IllegalArgumentException("modId");
			name = orEmpty(name).isEmpty() ? modId : name.trim();
			version = orEmpty(version);
			description = orEmpty(description);
			authors = authors == null ? List.of() : List.copyOf(authors);
			jar = orEmpty(jar);
			iconPath = orEmpty(iconPath);
			bundledBy = orEmpty(bundledBy);
			status = status == null ? Status.OK : status;
			statusDetail = orEmpty(statusDetail);
		}

		/** The same mod, with what became of it. */
		public Entry withStatus(Status newStatus, String detail) {
			return new Entry(ecosystem, modId, name, version, description, authors, jar, iconPath, bundledBy,
					newStatus, detail);
		}

		/**
		 * Whether this mod is a jar a player put in {@code mods/}, rather than one a mod carries inside itself.
		 *
		 * <p>The distinction is the difference between a list of sixteen things someone chose and a list of
		 * ninety-one, most of which are fabric-api's own modules and somebody's Kotlin runtime. Both are running
		 * and both are honestly "installed"; only one of them is what a player means by "my mods".
		 */
		public boolean installed() {
			return bundledBy.isEmpty();
		}

		private static String orEmpty(String s) {
			return s == null ? "" : s.trim();
		}
	}

	/**
	 * Sorted by display name, case-insensitively, then by id.
	 *
	 * <p>Deliberately NOT by ecosystem. Grouping the list by loader would make a player's first question — "is
	 * this mod here" — depend on knowing which loader built it, which on this instance is the one thing they
	 * should never have to know.
	 */
	/**
	 * What became of a mod during loading.
	 *
	 * <p>The wording matters and is deliberate. A mod whose constructor threw has had its container withdrawn
	 * from ModList, but its classes are still loaded, its mixins still applied, and {@code isLoaded(id)} still
	 * answers true ON PURPOSE. So the honest description is "did not finish loading", never "is not running" —
	 * the second would send a player to reinstall something that is already there.
	 */
	public enum Status {
		/** Nothing reported a problem. */
		OK,
		/** Part of this mod did not run: a setup phase threw, an entrypoint failed, its mixins were suppressed. */
		DEGRADED,
		/** This mod did not finish loading: its constructor or its entrypoint threw and its container was withdrawn. */
		FAILED
	}

	private static final Comparator<Entry> BY_NAME =
			Comparator.comparing((Entry e) -> e.name().toLowerCase(Locale.ROOT))
					.thenComparing(Entry::modId);

	private static volatile List<Entry> entries = List.of();
	private static volatile List<Entry> installed = List.of();

	private ModCatalog() {
	}

	/** Publishes the catalogue. The last caller wins; duplicate ids are collapsed, first ecosystem to claim wins. */
	public static synchronized void publish(List<Entry> found) {
		if (found == null) {
			entries = List.of();
			return;
		}
		List<Entry> sorted = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (Entry e : found) {
			if (e != null && seen.add(e.modId())) sorted.add(e);
		}
		sorted.sort(BY_NAME);
		entries = List.copyOf(sorted);
		installed = sorted.stream().filter(Entry::installed).toList();
	}

	/**
	 * The mods a player installed: one entry per jar in {@code mods/}, name-sorted.
	 *
	 * <p>This is what a Mods screen shows. The jars a mod carries inside itself are running too, and
	 * {@link #everything()} still has them, but they are not what the question "what have I installed" is asking
	 * -- on a real pack they outnumber the answer five to one.
	 */
	public static List<Entry> all() {
		return CompatibilityFindings.project(installed);
	}

	/** Every mod, bundled ones included. For anything counting what is RUNNING rather than what was chosen. */
	public static List<Entry> everything() {
		return CompatibilityFindings.project(entries);
	}

	/** What {@code modId}'s jar carries inside it, name-sorted. Empty for most mods. */
	public static List<Entry> bundledBy(String modId) {
		return everything().stream().filter(e -> e.bundledBy().equals(modId)).toList();
	}

	/** How many mods each ecosystem contributed, for the one-line boot summary and for the screen's subtitle. */
	public static int count(Ecosystem ecosystem) {
		int n = 0;
		for (Entry e : installed) {
			if (e.ecosystem() == ecosystem) n++;
		}
		return n;
	}

	/** Test seam. */
	/**
	 * Records what became of one mod, if the catalogue has it.
	 *
	 * <p>Two rules, and they are the whole correctness of this.
	 *
	 * <p><b>Never invent a row.</b> An id the catalogue does not have is dropped without a word. Aliases,
	 * {@code provides} ids, the NeoForge baseline container and presence-only ids all reach the call sites that
	 * use this, and none of them is a mod a player installed. A Mods screen listing things that do not exist
	 * would be worse than one that says nothing.
	 *
	 * <p><b>FAILED is sticky and outranks DEGRADED.</b> A mod whose constructor threw and which then also missed
	 * a setup phase is still, first and last, a mod that did not finish loading.
	 *
	 * <p>A second reason at the same status is kept, joined with {@code "; "}: a mod whose mixin was left out and
	 * whose deferred task then threw has two things wrong with it, and the row says both. The same reason twice
	 * is recorded once.
	 */
	public static synchronized void mark(String modId, Status status, String detail) {
		if (modId == null || status == null || status == Status.OK) return;
		remark(e -> e.modId().equals(modId), status, detail);
	}

	/**
	 * {@link #mark} for every mod that came out of one jar file — a universal jar has one row, a Jar-in-Jar
	 * child its own — for a finding that is about the jar rather than a mod id: what it was compiled against,
	 * which API package its classes name. {@code jarFileName} is compared with {@link Entry#jar()} as published;
	 * a name no row carries invents nothing, exactly like an unknown mod id.
	 */
	public static synchronized void markByJar(String jarFileName, Status status, String detail) {
		if (jarFileName == null || jarFileName.isBlank() || status == null || status == Status.OK) return;
		remark(e -> e.jar().equals(jarFileName), status, detail);
	}

	private static void remark(java.util.function.Predicate<Entry> which, Status status, String detail) {
		List<Entry> updated = new ArrayList<>(entries.size());
		boolean found = false;
		for (Entry e : entries) {
			if (!which.test(e)) {
				updated.add(e);
				continue;
			}
			found = true;
			Status kept = e.status() == Status.FAILED ? Status.FAILED : status;
			updated.add(e.withStatus(kept, joinedDetail(e, status, kept, detail)));
		}
		if (!found) return;

		entries = List.copyOf(updated);
		installed = updated.stream().filter(Entry::installed).toList();
	}

	/**
	 * The detail a re-marked row carries: the new reason when the status rises; the existing reason when the new
	 * one is outranked (a DEGRADED reason adds nothing to "did not finish loading"); both reasons, joined, when a
	 * second distinct reason arrives at the same status; the existing text when the new one repeats it or is
	 * empty.
	 */
	private static String joinedDetail(Entry e, Status incomingStatus, Status kept, String detail) {
		String incoming = detail == null ? "" : detail.trim();
		String existing = e.statusDetail();
		if (kept != e.status() || existing.isEmpty()) return incoming;
		if (incomingStatus != e.status() || incoming.isEmpty()) return existing;

		// Clause by clause, not whole string by whole string. A reason is often several clauses already joined
		// with "; " — one repair naming two things it could not do — and comparing the whole incoming text to
		// each existing clause never matches, so the SAME two-clause reason arriving twice was printed twice on
		// the Mods screen and in the load report. fabric-item-api's tooltip row read that way for months.
		List<String> reasons = new ArrayList<>(List.of(existing.split("; ")));
		for (String clause : incoming.split("; ")) {
			if (!clause.isEmpty() && !reasons.contains(clause)) reasons.add(clause);
		}
		return String.join("; ", reasons);
	}

	/** The mods something went wrong with, name-sorted. Empty is the ordinary case. */
	public static List<Entry> failures() {
		List<Entry> out = new ArrayList<>();
		for (Entry e : everything()) {
			if (e.status() != Status.OK) out.add(e);
		}
		return List.copyOf(out);
	}

	/** Legacy marks have no structured proof or necessity classification; never guess those from their prose. */
	static List<Entry> unclassifiedFailures() {
		return entries.stream().filter(e -> e.status() != Status.OK).toList();
	}

	static void reset() {
		entries = List.of();
		installed = List.of();
	}
}
