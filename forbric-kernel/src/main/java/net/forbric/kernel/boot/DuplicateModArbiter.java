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

package net.forbric.kernel.boot;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.metadata.DiscoveredMod;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.forbric.kernel.util.ForbricLog;

/**
 * Arbitrates TWO SEPARATE JARS that declare the SAME mod id — the case that appears the moment a Fabric modpack and
 * a NeoForge modpack are merged into one instance.
 *
 * <p><b>Why this is not part of {@link MultiLoaderArbiter}.</b> That one arbitrates ONE jar declaring several loader
 * manifests, and is keyed by jar path; it cannot see two files sharing an id. The two arbitrations also have
 * opposite effects on the classpath: {@code suppressedFor(jar, mine)} means "this jar acts as one family" and the
 * jar STAYS on the classpath, because the winning family needs its classes. Cross-jar means the jar is superseded
 * by a different file and must come OFF, or the losing copy still shadows classes (first-URL-wins: 662 of the
 * Fabric Sodium jar's 777 classes were measured as shadowed by the NeoForge one) and still contributes its mixin
 * configs — which nothing dedupes by mixin CLASS, only by config NAME, with {@code required} stripped, so a double
 * apply is silent. Folding the two together would make {@code suppressedFor} mean two things.
 *
 * <p><b>Reading ids.</b> Forge family via {@link ForbricModDiscoverer}; Fabric via
 * {@link FabricModMetadataParser}, deliberately NOT the metadata-package reader — only the parser reads
 * {@code environment}, and the runtime Fabric scanner filters on it. Arbitrating with a reader blind to
 * {@code environment} would let a client-only Fabric jar win an id on a dedicated server and then be dropped by the
 * environment filter, leaving the mod loaded by NOBODY. Claims are made on the declared id only, never on
 * {@code provides} aliases — {@code sodium-fabric} provides {@code indium}, and an alias claim would suppress a
 * real Indium jar.
 *
 * <p>Composes with the per-jar arbiter: {@link MultiLoaderArbiter#ownerOf} runs first, and only the ids declared
 * under that ecosystem count, so a universal jar enters as exactly one claim.
 *
 * <p>Switches: {@code -Dforbric.crossJarArbitration=off} disables it entirely;
 * {@code -Dforbric.modOwner=sodium=fabric,lithostitched=neoforge} overrides individual mods;
 * {@code -Dforbric.multiLoaderPreference} (shared with {@link MultiLoaderArbiter}) sets the global order.
 */
public final class DuplicateModArbiter {
	static final String SWITCH = "forbric.crossJarArbitration";
	static final String OWNER_OVERRIDE = "forbric.modOwner";

	/** One jar's claim: the ecosystem it loads as, and the mod ids it declares under that ecosystem. */
	public record Claim(Path jar, MultiLoaderArbiter.Ecosystem ecosystem, List<String> modIds,
			Map<String, String> versions) {
		public Claim(Path jar, MultiLoaderArbiter.Ecosystem ecosystem, List<String> modIds) {
			this(jar, ecosystem, modIds, Map.of());
		}

		String versionOf(String modId) {
			return versions.getOrDefault(modId, "0");
		}
	}

	/**
	 * A mod id whose jar for {@code ecosystem} was suppressed, so that ecosystem lost the mod's IDENTITY even
	 * though the winner still supplies its classes.
	 *
	 * <p>This is the residual of arbitration, and it is narrow but real. Two builds of the same multiloader mod are
	 * 98–100% the same classes — measured on the two packs: ferritecore and YACL are identical, lithostitched
	 * differs by 2 + 8 platform-glue classes, Jade by 20 + 8. So a mod on the LOSING side still links against the
	 * winner's copy and still sees the content the winner registered. What it cannot see is the mod itself:
	 * {@code ModList.get().isLoaded(id)} answers false, and a mod that gates an integration on that check silently
	 * disables it. Registering a presence-only container on the losing side closes exactly that gap and nothing
	 * more.
	 */
	public record Alias(String modId, MultiLoaderArbiter.Ecosystem ecosystem, String version) {
	}

	/** Which jars must not be loaded, who owns each contested id, and which ecosystems need a presence alias. */
	public record Decision(Set<Path> suppressedJars, Map<String, Path> ownerByModId, List<Alias> aliases) {
		public boolean suppressed(Path jar) {
			return jar != null && suppressedJars.contains(jar.toAbsolutePath());
		}

		/** The aliases this ecosystem must publish so {@code isLoaded(id)} answers for mods it lost. */
		public List<Alias> aliasesFor(MultiLoaderArbiter.Ecosystem ecosystem) {
			List<Alias> mine = new ArrayList<>();
			for (Alias alias : aliases) {
				if (alias.ecosystem() == ecosystem) mine.add(alias);
			}
			return mine;
		}

		public static Decision none() {
			return new Decision(Set.of(), Map.of(), List.of());
		}
	}

	private static volatile Decision cached;
	private static volatile Path cachedDir;

	private DuplicateModArbiter() {
	}

	/**
	 * The decision this boot already made, or {@link Decision#none()} if arbitration has not run.
	 *
	 * <p>For consumers that run after {@code KernelBoot} decided and must not re-scan — notably
	 * {@code KernelModLoader}, which publishes the NeoForge presence aliases long after the mods directory was
	 * walked.
	 */
	public static synchronized Decision current() {
		return cached != null ? cached : Decision.none();
	}

	/** Forgets the decision — for tests, and so a re-launch in one process re-arbitrates. */
	public static synchronized void reset() {
		cached = null;
		cachedDir = null;
	}

	/** Scans {@code modsDir} once and arbitrates. Repeat calls for the same directory return the same decision. */
	public static synchronized Decision arbitrate(Path modsDir, EnvType envType) {
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) {
			ForbricLog.warn("[Forbric/DupeId] cross-jar arbitration DISABLED (-D%s=off) — two jars sharing a mod id "
					+ "will BOTH load, shadowing each other's classes and applying each other's mixins", SWITCH);
			return Decision.none();
		}
		if (cached != null && modsDir != null && modsDir.equals(cachedDir)) return cached;

		Decision decision = arbitrate(scan(modsDir, envType));
		cached = decision;
		cachedDir = modsDir;
		return decision;
	}

	/** The pure half: decide from claims alone. Package-visible so tests can drive it without a filesystem. */
	static Decision arbitrate(List<Claim> claims) {
		Map<String, List<Claim>> byId = new LinkedHashMap<>();
		for (Claim claim : claims) {
			for (String id : claim.modIds()) {
				byId.computeIfAbsent(id, k -> new ArrayList<>()).add(claim);
			}
		}

		Map<String, Claim> winners = new LinkedHashMap<>();
		Map<String, Path> ownerByModId = new LinkedHashMap<>();
		List<String> contested = new ArrayList<>();
		for (Map.Entry<String, List<Claim>> e : byId.entrySet()) {
			// A single claimant is NEVER suppressed. This invariant is what keeps every existing gate green: no
			// gate stages the same mod id twice, so the whole pass is a provable no-op on all of them.
			if (e.getValue().size() < 2) continue;
			contested.add(e.getKey());
			Claim winner = pick(e.getKey(), e.getValue());
			winners.put(e.getKey(), winner);
			ownerByModId.put(e.getKey(), winner.jar().toAbsolutePath());
		}
		if (contested.isEmpty()) return Decision.none();

		Set<Path> suppressed = new LinkedHashSet<>();
		for (Claim claim : claims) {
			// SUBSET RULE: a jar may only lose if EVERY id it declares is also claimed by a winner. On partial
			// overlap suppress nothing and say which ids are orphaned — otherwise a jar bundling foo + foo_compat
			// is deleted wholesale because foo alone collided, and foo_compat is loaded by nobody.
			if (claim.modIds().isEmpty()) continue;
			List<String> orphaned = new ArrayList<>();
			boolean anyLost = false;
			for (String id : claim.modIds()) {
				Claim winner = winners.get(id);
				if (winner == null) {
					orphaned.add(id);
				} else if (winner != claim) {
					anyLost = true;
				}
			}
			if (!anyLost) continue;
			if (!orphaned.isEmpty()) {
				ForbricLog.warn("[Forbric/DupeId] keeping %s despite losing %s — it also declares %s, which nothing "
						+ "else provides; suppressing it would leave those loaded by nobody",
						claim.jar().getFileName(), contestedOf(claim, winners), orphaned);
				continue;
			}
			suppressed.add(claim.jar().toAbsolutePath());
		}

		// Every ecosystem that lost its copy of a contested id needs the mod's IDENTITY back — see Alias.
		List<Alias> aliases = new ArrayList<>();
		for (String id : contested) {
			Claim winner = winners.get(id);
			Set<MultiLoaderArbiter.Ecosystem> lost = new LinkedHashSet<>();
			for (Claim claimant : byId.get(id)) {
				if (claimant.ecosystem() != winner.ecosystem()) lost.add(claimant.ecosystem());
			}
			for (MultiLoaderArbiter.Ecosystem ecosystem : lost) {
				aliases.add(new Alias(id, ecosystem, winner.versionOf(id)));
			}
			ForbricLog.info("[Forbric/DupeId] mod id '%s' claimed by %d jars — loading %s (%s)%s", id,
					byId.get(id).size(), winner.jar().getFileName(), winner.ecosystem(),
					lost.isEmpty() ? "" : ", aliased into " + lost);
		}
		ForbricLog.info("[Forbric/DupeId] cross-jar arbitration: %d duplicate mod id(s), %d jar(s) suppressed, "
				+ "%d presence alias(es)", contested.size(), suppressed.size(), aliases.size());
		return new Decision(Set.copyOf(suppressed), Map.copyOf(ownerByModId), List.copyOf(aliases));
	}

	private static List<String> contestedOf(Claim claim, Map<String, Claim> winners) {
		List<String> lost = new ArrayList<>();
		for (String id : claim.modIds()) {
			if (winners.get(id) != null && winners.get(id) != claim) lost.add(id);
		}
		return lost;
	}

	/** Per-mod override first, then the global ecosystem preference, then first-by-path. */
	private static Claim pick(String modId, List<Claim> claimants) {
		MultiLoaderArbiter.Ecosystem forced = overrideFor(modId);
		if (forced != null) {
			for (Claim claim : claimants) {
				if (claim.ecosystem() == forced) return claim;
			}
			// A typo, or an ecosystem that has no claim on this id, must never unload the mod entirely.
			ForbricLog.warn("[Forbric/DupeId] -D%s asks for '%s' from %s, but no such jar claims it — falling back "
					+ "to the preference order", OWNER_OVERRIDE, modId, forced);
		}
		for (MultiLoaderArbiter.Ecosystem candidate : preference()) {
			for (Claim claim : claimants) {
				if (claim.ecosystem() == candidate) return claim;
			}
		}
		// Same ecosystem twice (two versions of one jar in mods/), or an ecosystem the preference does not list:
		// keep the first by path, matching KernelFabricLoader's "keeping the first".
		return claimants.get(0);
	}

	/**
	 * {@code -Dforbric.dupeIdPreference}, falling back to the shared {@code -Dforbric.multiLoaderPreference}.
	 *
	 * <p>These started as ONE knob, on the reasoning that "prefer Fabric on this instance" should mean one thing.
	 * Merging the two real packs disproved it: the two arbitrations answer different questions. Per-jar asks "this
	 * jar ships both manifests — which of ITS OWN implementations do we run?", and the right answer is the one the
	 * pack it came from was built around. Cross-jar asks "two different FILES claim this id — which project do we
	 * keep?". Setting the shared knob to Fabric-first to resolve the second flipped the first as well, and every
	 * universal jar in the NeoForge pack (CreativeCore, EnhancedVisuals, AmbientSounds — all shipping a
	 * fabric.mod.json despite {@code _NEOFORGE_} filenames) started running its Fabric path instead of the tested
	 * NeoForge one. EnhancedVisuals' entrypoint then failed and its renderer took the client down.
	 *
	 * <p>So they default to the same value and can be separated when an instance needs it.
	 */
	static List<MultiLoaderArbiter.Ecosystem> preference() {
		String csv = System.getProperty("forbric.dupeIdPreference");
		if (csv == null || csv.isBlank()) return MultiLoaderArbiter.preference();

		List<MultiLoaderArbiter.Ecosystem> order = new ArrayList<>();
		for (String raw : csv.split(",")) {
			try {
				order.add(MultiLoaderArbiter.Ecosystem.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException unknown) {
				ForbricLog.warn("[Forbric/DupeId] ignoring unknown ecosystem '%s' in -Dforbric.dupeIdPreference",
						raw.trim());
			}
		}
		return order.isEmpty() ? MultiLoaderArbiter.preference() : order;
	}

	/** {@code -Dforbric.modOwner=sodium=fabric,lithostitched=neoforge} */
	private static MultiLoaderArbiter.Ecosystem overrideFor(String modId) {
		String csv = System.getProperty(OWNER_OVERRIDE);
		if (csv == null || csv.isBlank()) return null;
		for (String raw : csv.split(",")) {
			int eq = raw.indexOf('=');
			if (eq <= 0) continue;
			if (!raw.substring(0, eq).trim().equals(modId)) continue;
			String eco = raw.substring(eq + 1).trim().toUpperCase(Locale.ROOT);
			try {
				return MultiLoaderArbiter.Ecosystem.valueOf(eco);
			} catch (IllegalArgumentException unknown) {
				ForbricLog.warn("[Forbric/DupeId] ignoring unknown ecosystem '%s' in -D%s", eco, OWNER_OVERRIDE);
				return null;
			}
		}
		return null;
	}

	/** Top-level jars only, sorted by path so ties are deterministic. */
	private static List<Claim> scan(Path modsDir, EnvType envType) {
		List<Claim> claims = new ArrayList<>();
		if (modsDir == null || !Files.isDirectory(modsDir)) return claims;

		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		List<Path> jars;
		try (var entries = Files.list(modsDir)) {
			jars = entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(Files::isRegularFile).sorted().toList();
		} catch (Exception e) {
			ForbricLog.warn("[Forbric/DupeId] could not list %s: %s", modsDir, String.valueOf(e));
			return claims;
		}

		for (Path jar : jars) {
			MultiLoaderArbiter.Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
			if (owner == null) continue; // a plain library — nobody claims it, so it cannot contest an id
			Map<String, String> versions = new LinkedHashMap<>();
			List<String> ids = owner == MultiLoaderArbiter.Ecosystem.FABRIC
					? fabricIds(jar, envType, versions)
					: forgeFamilyIds(discoverer, jar, versions);
			if (!ids.isEmpty()) claims.add(new Claim(jar, owner, ids, Map.copyOf(versions)));
		}
		return claims;
	}

	private static List<String> fabricIds(Path jar, EnvType envType, Map<String, String> versions) {
		try (JarFile zip = new JarFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(ForbricModDiscoverer.FABRIC_MANIFEST);
			if (entry == null) return List.of();
			try (InputStream in = zip.getInputStream(entry)) {
				KernelModMetadata metadata = FabricModMetadataParser.read(in);
				if (metadata == null || metadata.getId() == null) return List.of();
				// The environment filter, mirrored from FabricModDiscovery: a jar the running side will drop must
				// not win an id here, or the mod ends up loaded by nobody.
				if (envType != null && !metadata.getEnvironment().matches(envType)) return List.of();
				if (metadata.getVersion() != null) versions.put(metadata.getId(), metadata.getVersion().toString());
				return List.of(metadata.getId());
			}
		} catch (Exception e) {
			ForbricLog.debug("[Forbric/DupeId] could not read Fabric metadata from %s: %s", jar.getFileName(),
					String.valueOf(e));
			return List.of();
		}
	}

	private static List<String> forgeFamilyIds(ForbricModDiscoverer discoverer, Path jar,
			Map<String, String> versions) {
		List<String> ids = new ArrayList<>();
		try {
			for (DiscoveredMod mod : discoverer.discoverJar(jar)) {
				if (!mod.getEcosystem().isForgeFamily()) continue;
				if (mod.getId() == null || ids.contains(mod.getId())) continue;
				ids.add(mod.getId());
				if (mod.getVersion() != null) versions.put(mod.getId(), mod.getVersion());
			}
		} catch (Exception e) {
			ForbricLog.debug("[Forbric/DupeId] could not read Forge-family metadata from %s: %s", jar.getFileName(),
					String.valueOf(e));
		}
		return ids;
	}
}
