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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
	public record Unmatched(String kind, String source, String directive, boolean retyped, boolean namePresent,
			String presentAs) {
		public Unmatched(String kind, String source, String directive) {
			this(kind, source, directive, false, false, null);
		}

		public Unmatched(String kind, String source, String directive, boolean retyped, boolean namePresent) {
			this(kind, source, directive, retyped, namePresent, null);
		}
	}

	/** The two ecosystems' own packages: a descriptor naming one is a type this Minecraft does not ship. */
	private static final List<String> CARRIER_TYPES = List.of("Lnet/minecraftforge/", "Lnet/neoforged/");

	/**
	 * Whether a field the widener missed is one an ECOSYSTEM re-typed, judged from what it is now.
	 *
	 * <p>"The name is there under another descriptor" was the old test, and it is not enough: vanilla itself
	 * re-types fields between versions, and a mod carried forward names the old one. {@code ItemStack.item} is
	 * {@code Holder<Item>} on the merged base AND on both patched bases AND on stock 26.2 — a stale line that a
	 * native loader ignores in exactly the same way — and it was being reported as a merge re-typing that marked
	 * the mod.
	 *
	 * <p>What distinguishes the real case is that the descriptor now names a CARRIER type: NeoForge's or
	 * MinecraftForge's own class, which stock Minecraft does not ship, so no version of the game ever declared it
	 * that way. That is a cost this instance introduced for the mod. {@code ChunkGenerator.featuresPerStep},
	 * MinecraftForge's {@code ClearableLazy} over vanilla's {@code Supplier}, is that case.
	 */
	public static boolean retypedByAnEcosystem(List<String> presentDescriptors) {
		for (String descriptor : presentDescriptors) {
			for (String carrier : CARRIER_TYPES) {
				if (descriptor.startsWith(carrier)) return true;
			}
		}
		return false;
	}


	/**
	 * Directives an ecosystem re-typed and a named kernel repair already satisfies, so the miss costs nothing.
	 *
	 * <p>The access transformers run in the ACCESS phase, before the COREMOD one — so a repair that gives a field
	 * vanilla's descriptor back has not happened yet when the widener looks, and the widener reports a miss for
	 * access the repair is about to grant anyway. Marking the mod then reports a loss that did not happen, and a
	 * report that cries wolf is worse than no report.
	 *
	 * <p>The bar is the same as {@code SupersededMixins}': the repair must do the directive's WHOLE job — the
	 * descriptor AND the access flags — because a repair that restores only the descriptor turns a
	 * {@code NoSuchFieldError} into an {@code IllegalAccessError}, which is not an improvement.
	 */
	private static final Map<String, String> SATISFIED_ELSEWHERE = satisfiedElsewhere();

	private static Map<String, String> satisfiedElsewhere() {
		Map<String, String> map = new LinkedHashMap<>();
		// MinecraftForge re-typed this to its own ClearableLazy so refreshFeaturesPerStep() has something to
		// invalidate, and the merge kept only that declaration. giveChunkGeneratorItsVanillaFeatureField puts
		// vanilla's Supplier descriptor back AND makes the field public non-final — which is the whole of what
		// this widener asks for — but it runs in the COREMOD phase, after this one.
		map.put("field net/minecraft/world/level/chunk/ChunkGenerator featuresPerStep Ljava/util/function/Supplier;",
				"the kernel gives that field vanilla's descriptor back and makes it public non-final in the "
						+ "COREMOD phase, which is this directive's whole job, only later");
		return Map.copyOf(map);
	}

	static String satisfiedBy(String directive) {
		return SATISFIED_ELSEWHERE.get(directive);
	}

	/** The rows, for the tests that keep each claim honest. */
	static Map<String, String> allSatisfiedElsewhere() {
		return SATISFIED_ELSEWHERE;
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
		unmatched(kind, source, directive, retyped, namePresent, null);
	}

	/**
	 * @param presentAs what the class declares the member as now, when the name is there — the one thing that
	 *                  turns "matched nothing" into a sentence someone can act on
	 */
	public static void unmatched(String kind, String source, String directive, boolean retyped, boolean namePresent,
			String presentAs) {
		synchronized (UNMATCHED) {
			UNMATCHED.add(new Unmatched(kind, source == null ? "?" : source, directive, retyped,
					namePresent || presentAs != null, presentAs));
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
				+ "%d re-typed by an ecosystem, %d with the name present under another descriptor this Minecraft ships "
				+ "(an overload it lacks, or the game's own drift — not judged), %d stale on this Minecraft as on a "
				+ "native loader", all.size(), transformed,
				at, all.size() - at, retyped, unjudged, all.size() - retyped - unjudged);
		for (Unmatched u : all) {
			boolean carrier = u.source().startsWith("carrier:");
			if (!u.retyped()) {
				ForbricLog.info("[Forbric/Access] %s directive from %s names a member this Minecraft does not have (%s): %s%s",
						u.kind(), u.source(), u.namePresent()
								? "the name is there under another descriptor this Minecraft ships, so a native "
										+ "loader ignores the line the same way"
								: "stale, ignored here as on a native loader", u.directive(),
						u.presentAs() == null ? "" : " (it is " + u.presentAs() + " here)");
				continue;
			}
			String satisfied = satisfiedBy(u.directive());
			String now = u.presentAs() == null ? "" : " (it is " + u.presentAs() + " here)";
			if (satisfied != null) {
				ForbricLog.info("[Forbric/Access] %s directive from %s names a member an ecosystem re-typed%s, but "
						+ "%s, so its mod is not marked: %s", u.kind(), u.source(), now, satisfied, u.directive());
				continue;
			}
			ForbricLog.warn("[Forbric/Access] %s directive from %s names a member an ecosystem re-typed%s, so it was "
					+ "not widened: %s%s", u.kind(), u.source(), now, u.directive(),
					carrier ? "" : " — the mod is marked on the Mods screen");
			if (!carrier && !"?".equals(u.source())) {
				ModCatalog.markByJar(u.source(), ModCatalog.Status.DEGRADED, "its access " + ("AT".equals(u.kind())
						? "transformer" : "widener") + " names " + u.directive() + ", which an ecosystem re-typed");
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
