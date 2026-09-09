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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import net.fabricmc.api.EnvType;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.fabric.FabricModMetadataParser;
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
	/** The player-facing override file, next to {@code mods/}. See {@link #loadOverrideFile}. */
	static final String OVERRIDE_FILE = "forbric-mods.txt";

	/** One jar's claim: the ecosystem it loads as, and the mod ids it declares under that ecosystem. */
	public record Claim(Path jar, Ecosystem ecosystem, List<String> modIds,
			Map<String, String> versions) {
		public Claim(Path jar, Ecosystem ecosystem, List<String> modIds) {
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
	public record Alias(String modId, Ecosystem ecosystem, String version) {
	}

	/** Which jars must not be loaded, who owns each contested id, and which ecosystems need a presence alias. */
	public record Decision(Set<Path> suppressedJars, Map<String, Path> ownerByModId, List<Alias> aliases) {
		public boolean suppressed(Path jar) {
			return jar != null && suppressedJars.contains(jar.toAbsolutePath());
		}

		/** The aliases this ecosystem must publish so {@code isLoaded(id)} answers for mods it lost. */
		public List<Alias> aliasesFor(Ecosystem ecosystem) {
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
		fileOverrides = Map.of();
	}

	/** Scans {@code modsDir} once and arbitrates. Repeat calls for the same directory return the same decision. */
	public static synchronized Decision arbitrate(Path modsDir, EnvType envType) {
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) {
			ForbricLog.warn("[Forbric/DupeId] cross-jar arbitration DISABLED (-D%s=off) — two jars sharing a mod id "
					+ "will BOTH load, shadowing each other's classes and applying each other's mixins", SWITCH);
			return Decision.none();
		}
		if (cached != null && modsDir != null && modsDir.equals(cachedDir)) return cached;

		Path rundir = modsDir == null ? null : modsDir.getParent();
		loadOverrideFile(rundir);
		List<Alias> universalAliases = new ArrayList<>();
		Decision decision = arbitrate(scan(modsDir, envType, universalAliases), universalAliases);
		writeOverrideTemplate(rundir, decision);
		MergeReport.write(rundir, modsDir, decision);
		cached = decision;
		cachedDir = modsDir;
		return decision;
	}

	/** The pure half: decide from claims alone. Package-visible so tests can drive it without a filesystem. */
	static Decision arbitrate(List<Claim> claims) {
		return arbitrate(claims, List.of());
	}

	/**
	 * The pure half, plus the aliases a universal jar's losing manifests need.
	 *
	 * <p>Those aliases must survive the no-contest early return below: an instance can have universal jars and no
	 * duplicate ids at all, and that is the common case.
	 */
	static Decision arbitrate(List<Claim> claims, List<Alias> universalAliases) {
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
		if (contested.isEmpty()) {
			if (universalAliases.isEmpty()) return Decision.none();
			logUniversalAliases(universalAliases);
			return new Decision(Set.of(), Map.of(), List.copyOf(universalAliases));
		}

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
		List<Alias> aliases = new ArrayList<>(universalAliases);
		logUniversalAliases(universalAliases);
		for (String id : contested) {
			Claim winner = winners.get(id);
			Set<Ecosystem> lost = new LinkedHashSet<>();
			for (Claim claimant : byId.get(id)) {
				if (claimant.ecosystem() != winner.ecosystem()) lost.add(claimant.ecosystem());
			}
			for (Ecosystem ecosystem : lost) {
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

	private static void logUniversalAliases(List<Alias> universalAliases) {
		if (universalAliases.isEmpty()) return;
		Map<Ecosystem, List<String>> byEcosystem = new LinkedHashMap<>();
		for (Alias alias : universalAliases) {
			byEcosystem.computeIfAbsent(alias.ecosystem(), k -> new ArrayList<>()).add(alias.modId());
		}
		ForbricLog.info("[Forbric/DupeId] %d universal jar identit(ies) handed back to the side that did not load "
				+ "them, so isModLoaded still answers: %s", universalAliases.size(), byEcosystem);
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
		Ecosystem forced = overrideFor(modId);
		if (forced != null) {
			for (Claim claim : claimants) {
				if (claim.ecosystem() == forced) return claim;
			}
			// A typo, or an ecosystem that has no claim on this id, must never unload the mod entirely.
			ForbricLog.warn("[Forbric/DupeId] -D%s asks for '%s' from %s, but no such jar claims it — falling back "
					+ "to the preference order", OWNER_OVERRIDE, modId, forced);
		}
		for (Ecosystem candidate : preference()) {
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
	static List<Ecosystem> preference() {
		String csv = System.getProperty("forbric.dupeIdPreference");
		if (csv == null || csv.isBlank()) return MultiLoaderArbiter.preference();

		List<Ecosystem> order = new ArrayList<>();
		for (String raw : csv.split(",")) {
			Ecosystem parsed = Ecosystem.parse(raw);
			if (parsed != null) {
				order.add(parsed);
			} else {
				ForbricLog.warn("[Forbric/DupeId] ignoring unknown ecosystem '%s' in -Dforbric.dupeIdPreference",
						raw.trim());
			}
		}
		return order.isEmpty() ? MultiLoaderArbiter.preference() : order;
	}

	/** {@code -Dforbric.modOwner=sodium=fabric,lithostitched=neoforge} */
	private static Ecosystem overrideFor(String modId) {
		String csv = System.getProperty(OWNER_OVERRIDE);
		if (csv != null && !csv.isBlank()) {
			for (String raw : csv.split(",")) {
				int eq = raw.indexOf('=');
				if (eq <= 0) continue;
				if (!raw.substring(0, eq).trim().equals(modId)) continue;
				Ecosystem eco = ecosystem(raw.substring(eq + 1), "-D" + OWNER_OVERRIDE);
				if (eco != null) return eco;
			}
		}
		// The command line wins, so a launcher argument can always override a stale file.
		return fileOverrides.get(modId);
	}

	/** Parsed {@code forbric-mods.txt}; empty until {@link #loadOverrideFile} runs, and after {@link #reset}. */
	private static volatile Map<String, Ecosystem> fileOverrides = Map.of();

	/**
	 * Reads {@code <rundir>/forbric-mods.txt} — the way a player picks a side without touching JVM arguments.
	 *
	 * <p>Launchers make {@code -D} flags awkward to set and easy to lose; a text file next to {@code mods/} is
	 * something anyone can edit, and {@link #writeOverrideTemplate} puts one there with every duplicate already
	 * listed and commented out, so the edit is deleting a {@code #}.
	 *
	 * <p>Parsing is deliberately forgiving — blank lines, {@code #} comments (whole-line and trailing), any casing,
	 * any spacing. A line that cannot be understood is warned about and SKIPPED: a typo must never be able to stop
	 * a mod from loading, which is the same rule the ecosystem-name handling follows.
	 */
	private static void loadOverrideFile(Path rundir) {
		fileOverrides = Map.of();
		if (rundir == null) return;
		Path file = rundir.resolve(OVERRIDE_FILE);
		if (!Files.isRegularFile(file)) return;

		Map<String, Ecosystem> parsed = new LinkedHashMap<>();
		try {
			int lineNo = 0;
			for (String raw : Files.readAllLines(file)) {
				lineNo++;
				int hash = raw.indexOf('#');
				String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
				if (line.isEmpty()) continue;

				int eq = line.indexOf('=');
				if (eq <= 0) {
					ForbricLog.warn("[Forbric/DupeId] %s line %d: expected '<mod id> = <loader>', got '%s' — skipped",
							OVERRIDE_FILE, lineNo, line);
					continue;
				}
				Ecosystem eco = ecosystem(line.substring(eq + 1), OVERRIDE_FILE + " line " + lineNo);
				if (eco != null) parsed.put(line.substring(0, eq).trim(), eco);
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/DupeId] could not read " + OVERRIDE_FILE + " — using the automatic choice", t);
			return;
		}
		fileOverrides = Map.copyOf(parsed);
		if (!parsed.isEmpty()) {
			ForbricLog.info("[Forbric/DupeId] %s pins %s", OVERRIDE_FILE, parsed);
		}
	}

	/**
	 * Writes {@code forbric-mods.txt} the first time an instance has duplicates, pre-filled and fully commented out.
	 *
	 * <p>The point is that the player never has to compose anything: every duplicate is already there with the
	 * choice the kernel made, so switching one is deleting a {@code #}. Never overwrites an existing file — that
	 * file is the player's.
	 *
	 * <p>Best-effort. A read-only rundir must cost a debug line, not the boot.
	 */
	private static void writeOverrideTemplate(Path rundir, Decision decision) {
		if (rundir == null || decision.ownerByModId().isEmpty()) return;
		Path file = rundir.resolve(OVERRIDE_FILE);
		if (Files.exists(file)) return;

		try {
			StringBuilder out = new StringBuilder();
			for (String line : MergeReport.overrideTemplateHeader()) out.append(line).append('\n');
			for (Map.Entry<String, Path> e : decision.ownerByModId().entrySet()) {
				Ecosystem owner = MultiLoaderArbiter.ownerOf(e.getValue());
				out.append("# ").append(e.getKey()).append(" = ")
						.append(owner == null ? "fabric" : owner.configId())
						.append('\n');
			}
			Files.writeString(file, out.toString());
			ForbricLog.info("[Forbric/DupeId] wrote %s — edit it to pick a different copy of any duplicated mod",
					file);
		} catch (IOException | RuntimeException e) {
			ForbricLog.debug("[Forbric/DupeId] could not write %s: %s", OVERRIDE_FILE, String.valueOf(e));
		}
	}

	/** Parses one ecosystem name, warning (and returning null) rather than throwing on anything unrecognised. */
	private static Ecosystem ecosystem(String raw, String where) {
		// The file this reads is the PLAYER'S. It has always spelled traditional Forge "minecraftforge", which is
		// why parsing goes through Ecosystem.parse rather than valueOf — the constant is FORGE, but an override
		// someone wrote months ago must still read back.
		Ecosystem parsed = Ecosystem.parse(raw);
		if (parsed == null) {
			ForbricLog.warn("[Forbric/DupeId] %s: '%s' is not a loader — use fabric, neoforge or minecraftforge",
					where, raw.trim());
		}
		return parsed;
	}

	/**
	 * Top-level jars only, sorted by path so ties are deterministic.
	 *
	 * <p>{@code universalAliases} collects the other half of the identity problem. A UNIVERSAL jar — one file
	 * carrying manifests for several loaders — enters as exactly ONE claim, under whichever ecosystem
	 * {@link MultiLoaderArbiter} picked, so the cross-jar pass below never sees it as contested and never issues
	 * an alias for it. But the losing side's identity is just as gone: the file is loaded once, and a Fabric mod
	 * asking {@code isModLoaded("iris")} of a jar loaded as NeoForge got no for an answer even though every class
	 * it wanted was present. Read those manifests here, where the file is already open, and hand their ids back.
	 */
	private static List<Claim> scan(Path modsDir, EnvType envType, List<Alias> universalAliases) {
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
			Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
			if (owner == null) continue; // a plain library — nobody claims it, so it cannot contest an id
			Map<String, String> versions = new LinkedHashMap<>();
			List<String> ids = owner == Ecosystem.FABRIC
					? fabricIds(jar, envType, versions)
					: forgeFamilyIds(discoverer, jar, versions);
			if (!ids.isEmpty()) claims.add(new Claim(jar, owner, ids, Map.copyOf(versions)));
			collectUniversalAliases(discoverer, jar, owner, envType, universalAliases);
		}
		return claims;
	}

	/**
	 * Presence aliases for every ecosystem a universal jar declares but is not loaded as.
	 *
	 * <p>The id must come from the LOSING manifest, never the winner's — they are not always the same name. The
	 * JourneyMap jar declares {@code journeymap} to NeoForge and {@code journeymap-wrongloader} to Fabric, the
	 * latter being a deliberate marker so stock Fabric ignores the file. Aliasing the winner's id into Fabric
	 * would answer a question nobody asked and leave the real one unanswered.
	 */
	private static void collectUniversalAliases(ForbricModDiscoverer discoverer, Path jar,
			Ecosystem owner, EnvType envType, List<Alias> out) {
		List<Ecosystem> declared = MultiLoaderArbiter.declaredBy(jar);
		if (declared.size() < 2) return;

		for (Ecosystem lost : declared) {
			if (lost == owner) continue;
			Map<String, String> versions = new LinkedHashMap<>();
			List<String> ids = lost == Ecosystem.FABRIC
					? fabricIds(jar, envType, versions)
					: forgeFamilyIds(discoverer, jar, versions);
			for (String id : ids) {
				out.add(new Alias(id, lost, versions.get(id)));
			}
		}
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
