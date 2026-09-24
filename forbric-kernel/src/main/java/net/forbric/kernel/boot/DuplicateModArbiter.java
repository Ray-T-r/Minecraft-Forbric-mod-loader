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
import java.util.HashMap;
import java.util.HashSet;
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

	/**
	 * Which jars must not be loaded, who owns each contested id, and which ecosystems need a presence alias.
	 *
	 * <p>{@code rescueJars} is the subset the class loader may still serve a missing class from (see
	 * ForbricClassLoader.setRescueJars). It is NOT every suppressed jar: discovery must skip every unselected
	 * physical candidate, but only the other ecosystem's build of a mod that did load may lend it a class. A
	 * losing JarJar version would mix two builds of one library, a side-excluded jar would make client-only
	 * code loadable on a server, and a losing root's nested tree was never meant to run (PLAN.md:63).
	 */
	public record Decision(Set<Path> suppressedJars, Map<String, Path> ownerByModId, List<Alias> aliases, Set<Path> rescueJars) {
		/** The top-level-only passes, where every suppressed jar is exactly such another-ecosystem build. */
		public Decision(Set<Path> suppressedJars, Map<String, Path> ownerByModId, List<Alias> aliases) {
			this(suppressedJars, ownerByModId, aliases, suppressedJars);
		}

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
	private static volatile EnvType cachedSide;
	private static volatile NestedCandidatePlan wholeInstancePlan;

	/**
	 * What the top-level pass claimed, kept so the nested pass can arbitrate over the UNION rather than over the
	 * nested jars alone. Without them a nested jar could only ever be compared with other nested jars, and a
	 * library nested beside a top-level copy of itself would still load twice.
	 */
	private static volatile List<Claim> topLevelClaims = List.of();
	private static volatile List<Alias> topLevelAliases = List.of();

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
		cachedSide = null;
		wholeInstancePlan = null;
		topLevelClaims = List.of();
		topLevelAliases = List.of();
		fileOverrides = Map.of();
	}

	/** Scans {@code modsDir} once and arbitrates. Repeat calls for the same directory return the same decision. */
	public static synchronized Decision arbitrate(Path modsDir, EnvType envType) {
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) {
			wholeInstancePlan = null;
			cached = null; cachedDir = null; cachedSide = null;
			ForbricLog.warn("[Forbric/DupeId] cross-jar arbitration DISABLED (-D%s=off) — two jars sharing a mod id "
					+ "will BOTH load, shadowing each other's classes and applying each other's mixins", SWITCH);
			return Decision.none();
		}
		if (cached != null && modsDir != null && modsDir.equals(cachedDir) && envType == cachedSide) return cached;

		Path rundir = modsDir == null ? null : modsDir.getParent();
		loadOverrideFile(rundir);
		List<Alias> universalAliases = new ArrayList<>();
		List<Claim> claims = scan(modsDir, envType, universalAliases);
		topLevelClaims = List.copyOf(claims);
		topLevelAliases = List.copyOf(universalAliases);
		Decision decision;
		if (claims.isEmpty()) {
			wholeInstancePlan = null;
			decision = new Decision(Set.of(), Map.of(), List.copyOf(universalAliases));
		} else {
			NestedCandidateInventory inventory = NestedCandidateInventory.scan(claims,
					rundir.resolve(".forbric-kernel").resolve("candidates"), envType);
			List<Claim> all = inventory.claims();
			Map<String, Ecosystem> overrides = new LinkedHashMap<>();
			for (Claim claim : all) for (String id : claim.modIds()) { Ecosystem forced = overrideFor(id); if (forced != null) overrides.put(id, forced); }
			List<JointCandidateSelector.Rule> contracts = CandidateContractScanner.scanPhysical(all, envType, inventory.symbolOwners());
			var result = ReachableCandidateSelector.solve(inventory, contracts, preference(), nestedPreference(), overrides,
					Math.max(1, Math.min(1_000_000, Integer.getInteger("forbric.arbitrationMaxNodes", 100_000))));
			wholeInstancePlan = new NestedCandidatePlan(inventory, result);
			reportSelection(all, result, overrides);
			// A parent the scan stopped inside has nested jars nobody examined, and both discoveries read only
			// this plan, so they will not load. That must stop or prompt, not pass as a quiet suspicion.
			for (var issue : inventory.issues()) if (issue.bound() && result.selected().contains(issue.source())) {
				String owner = wholeInstancePlan.ownerOf(issue.source());
				net.forbric.api.CompatibilityFindings.record(new net.forbric.api.CompatibilityFinding("arbitration:inventory", owner,
						"Bundled libraries", "arbitration:inventory", net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, true,
						"Some libraries bundled in this mod were not examined and will not be loaded", List.of(issue.source() + ": " + issue.detail())));
			}
			List<Alias> aliases = new ArrayList<>(universalAliases); aliases.addAll(inventory.universalAliases());
			decision = decisionFromSelection(all, aliases, "whole-instance", result);
			Set<Path> suppressed = new LinkedHashSet<>(decision.suppressedJars());
			for (var node : inventory.nodes().values()) if (!result.selected().contains(node.path())) suppressed.add(node.path());
			decision = new Decision(Set.copyOf(suppressed), decision.ownerByModId(), decision.aliases(), rescuable(inventory, result));
			// Each physical candidate owns only its own classes. Do not count losing nested classes as a root's.
			for (String line : divergenceReport(all, decision)) ForbricLog.info("%s", line);
		}
		writeOverrideTemplate(rundir, decision);
		MergeReport.write(rundir, modsDir, decision);
		cached = decision;
		cachedDir = modsDir;
		cachedSide = envType;
		return decision;
	}

	/**
	 * Unselected candidates that are another ecosystem's build of a mod that did load: every id they claim is
	 * owned by a selected build of a different ecosystem, and they were themselves reachable (a root, or a child
	 * of a selected parent). Side-excluded jars, same-ecosystem version losers, anonymous libraries and anything
	 * inside a losing root are left out.
	 */
	static Set<Path> rescuable(NestedCandidateInventory inventory, JointCandidateSelector.Result result) {
		Map<String, Set<Ecosystem>> winners = new HashMap<>();
		for (var node : inventory.nodes().values()) {
			if (!result.selected().contains(node.path()) || node.claim() == null) continue;
			for (String id : node.claim().modIds()) winners.computeIfAbsent(JointCandidateSelector.key(id), k -> new HashSet<>()).add(node.claim().ecosystem());
		}
		Set<Path> rescue = new LinkedHashSet<>();
		for (var node : inventory.nodes().values()) {
			if (result.selected().contains(node.path()) || node.excluded() || node.claim() == null || node.claim().modIds().isEmpty()) continue;
			boolean reachable = node.root() || inventory.edges().stream().anyMatch(e -> e.child().equals(node.path()) && result.selected().contains(e.parent()));
			boolean otherBuild = node.claim().modIds().stream().allMatch(id -> {
				Set<Ecosystem> owners = winners.get(JointCandidateSelector.key(id));
				return owners != null && !owners.contains(node.claim().ecosystem());
			});
			if (reachable && otherBuild) rescue.add(node.path());
		}
		return Set.copyOf(rescue);
	}

	/** For discovery only: another mods directory or physical side must never borrow this plan. */
	public static NestedCandidatePlan planned(Path modsDir, EnvType side) {
		return modsDir != null && modsDir.equals(cachedDir) && side == cachedSide ? wholeInstancePlan : null;
	}

	public static NestedCandidatePlan currentPlan() { return wholeInstancePlan; }

	/**
	 * The SECOND pass: the same arbitration, over the nested jars both families extract out of their mods.
	 *
	 * <p>A JarJar/JiJ child is not in {@code mods/}, so {@link #arbitrate(Path, EnvType)} never saw it — and each
	 * loader only dedupes against its own family ({@code KernelFabricLoader.register} drops a duplicate Fabric id,
	 * {@code KernelModLoader} the same for {@code @Mod}), so nobody was checking across. A library nested by a
	 * Fabric mod AND by a MinecraftForge mod therefore loaded twice, once per ecosystem, and initialised twice.
	 * Xaero's is the worked example: {@code xaerominimap-fabric} nests {@code xaerolib-fabric},
	 * {@code xaeroworldmap-forge} nests {@code xaerolib-forge}, and the second {@code XaeroLib.<init>} died on
	 * "Attempted to register a duplicate config channel: xaerolib:main" — but only AFTER its superclass
	 * constructor had already overwritten {@code XaeroLib.INSTANCE} with the half-built object, so a live mixin
	 * then called into it and took the client down on a render frame.
	 *
	 * <p>Arbitrated over the UNION of the top-level claims and the nested ones, not over the nested ones alone:
	 * a nested copy must also lose to a top-level jar of the same mod. The top-level half of the answer is then
	 * held fixed — those jars' discovery has already run by the time this is called, so re-deciding them would
	 * describe a load that did not happen. A union that WOULD have changed one is a bug in the ordering, and says
	 * so rather than pretending.
	 *
	 * @param nestedJars every nested jar both families extracted, in extraction order
	 * @return a decision that suppresses everything phase one did, plus the nested losers
	 */
	/**
	 * One line per suppressed jar whose build carries classes the winning build does not — report only. The
	 * residual the Alias javadoc measures (Jade: 20 Fabric-only + 8 NeoForge-only) is what a mod on the losing side
	 * cannot link against; a loser-only glue class is not a KNOWN loss, so nothing is marked — a confidently wrong
	 * mark is worse than none. Zip listings only, no bytecode. Silent for a pair whose class sets agree.
	 */
	static List<String> divergenceReport(List<Claim> claims, Decision decision) {
		List<String> lines = new ArrayList<>();
		Map<Path, Set<String>> read = new HashMap<>();
		Set<String> elsewhere = null;
		for (Claim loser : claims) {
			if (!decision.suppressed(loser.jar())) continue;
			Path winner = null;
			for (String id : loser.modIds()) {
				Path owner = decision.ownerByModId().get(id);
				if (owner != null && !owner.equals(loser.jar().toAbsolutePath())) { winner = owner; break; }
			}
			if (winner == null) continue;
			List<String> only = loserOnlyClasses(loser.jar(), winner, read);
			if (only.isEmpty()) continue;
			Ecosystem winnerFamily = null;
			for (Claim claim : claims) if (claim.jar().toAbsolutePath().equals(winner)) winnerFamily = claim.ecosystem();
			// The same measurement, kept rather than only printed: a guest mixin that targets one of these has no
			// target on this instance, and nothing else in the chain can tell that from an ordinary absence.
			//
			// But "only the losing build has it" is not "nothing in this instance has it", and the registry is
			// read as the second. A losing build routinely bundles a third mod's classes: sodium's FABRIC build
			// ships fabric-api's ExtendedBlockModelSubmit, and the player's own fabric-api supplies it whatever
			// sodium does. Recording it unsubtracted marked four mods on a 28-mod instance for mixins that were
			// fine. So what every jar that DID load provides — including inside its bundled jars — is taken back
			// out first, and only classes no loaded jar has reach the registry.
			if (elsewhere == null) elsewhere = classesStillLoaded(claims, decision, read);
			List<String> gone = new ArrayList<>();
			for (String name : only) if (!elsewhere.contains(name) && !onTheLaunchClasspath(name)) gone.add(name);
			if (!gone.isEmpty()) {
				ArbitratedAwayClasses.record(gone,
						new ArbitratedAwayClasses.Loss(loser.modIds().get(0), loser.ecosystem(), winnerFamily,
								String.valueOf(loser.jar().getFileName())));
			}
			List<String> shown = only.subList(0, Math.min(8, only.size()));
			lines.add("[Forbric/DupeId] " + loser.modIds().get(0) + ": the losing " + loser.ecosystem() + " build ("
					+ loser.jar().getFileName() + ") carries " + only.size() + " class(es) the winning "
					+ (winnerFamily == null ? "other" : winnerFamily.toString()) + " build does not: "
					+ String.join(", ", shown) + (only.size() > shown.size() ? ", …" : ""));
		}
		return lines;
	}

	/** The .class entries (dotted, no extension) in {@code loser} that {@code winner} lacks; empty if either is unreadable. */
	static List<String> loserOnlyClasses(Path loser, Path winner) {
		return loserOnlyClasses(loser, winner, new HashMap<>());
	}

	private static List<String> loserOnlyClasses(Path loser, Path winner, Map<Path, Set<String>> read) {
		Set<String> winning = classEntries(winner, read);
		if (winning == null) return List.of();
		Set<String> losing = classEntries(loser, read);
		if (losing == null) return List.of();
		List<String> only = new ArrayList<>();
		for (String name : losing) if (!winning.contains(name)) only.add(name);
		java.util.Collections.sort(only);
		return only;
	}

	/**
	 * Every class the jars that DID load bring, so the ones that did not can be named exactly.
	 *
	 * <p>Read once per arbitration, and only when there is something to subtract from — on an instance with no
	 * duplicated mod id nothing here is opened at all. A jar that cannot be read contributes nothing, which
	 * widens the "lost" set rather than narrowing it; that direction is the one a reader can check, because a
	 * name that turns out to be present is visible the moment the mixin applies anyway.
	 */
	private static Set<String> classesStillLoaded(List<Claim> claims, Decision decision, Map<Path, Set<String>> read) {
		Set<String> loaded = new HashSet<>();
		for (Claim claim : claims) {
			if (decision.suppressed(claim.jar())) continue;
			Set<String> names = classEntries(claim.jar(), read);
			if (names != null) loaded.addAll(names);
		}
		return loaded;
	}

	/**
	 * Whether the launch classpath already serves {@code name}, so losing a mod's copy of it costs nothing.
	 *
	 * <p>The third source, after the winning build and the other mods. A losing build often bundles a shaded
	 * LIBRARY: glitchcore's Fabric build carries all 189 {@code com.electronwill.nightconfig.core.*} classes,
	 * which the game's own {@code libraries/} supplies to every mod regardless of which glitchcore loaded. Without
	 * this those 189 were the whole "arbitrated away" set for that mod, measured on the 28-mod instance.
	 *
	 * <p>The system loader is the right question and the sovereign loader is not: this one has {@code libraries/}
	 * and NOT {@code mods/}, which is exactly the line being drawn. Asking the sovereign loader would answer yes
	 * for the losing jar's own classes too — it keeps them readable — and that is measured: the live boot still
	 * served {@code sodium.fabric.render.FluidRendererImpl}'s bytes while the loaded sodium was the NeoForge
	 * build, so a resource check against it was silent on the one case this registry was written for.
	 */
	private static boolean onTheLaunchClasspath(String dottedName) {
		return ClassLoader.getSystemResource(dottedName.replace('.', '/') + ".class") != null;
	}

	private static Set<String> classEntries(Path jar, Map<Path, Set<String>> read) {
		if (read.containsKey(jar)) return read.get(jar);
		Set<String> names = classEntries(jar);
		read.put(jar, names);
		return names;
	}

	/**
	 * Every class a jar brings, INCLUDING the ones inside its bundled jars.
	 *
	 * <p>Counting only top-level entries makes two builds of the same mod look wildly different when one of them
	 * nests its shared half and the other inlines it: sodium's NeoForge build bundles the common
	 * {@code sodium.client.*} classes in {@code META-INF/jars/}, so a flat comparison called 727 classes
	 * "Fabric-only" that both builds plainly have. That was harmless while this fed one log line and stopped
	 * being harmless the moment {@link ArbitratedAwayClasses} made a mixin's target depend on it — four mods were
	 * marked for mixins against classes that were present all along.
	 */
	private static Set<String> classEntries(Path jar) {
		Set<String> names = new LinkedHashSet<>();
		if (wholeInstancePlan != null && wholeInstancePlan.inventory().nodes().containsKey(jar.toAbsolutePath().normalize())) {
			try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
				zip.stream().map(java.util.zip.ZipEntry::getName).filter(name -> name.endsWith(".class"))
						.forEach(name -> names.add(name.substring(0, name.length() - 6).replace('/', '.')));
				return names;
			} catch (IOException unreadable) { return null; }
		}
		if (!collectClasses(jar, names)) return null;
		return names;
	}

	/** Adds {@code jar}'s classes and those of its bundled jars; false when the jar itself cannot be read. */
	private static boolean collectClasses(Path jar, Set<String> names) {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			for (java.util.zip.ZipEntry entry : zip.stream().toList()) {
				String name = entry.getName();
				if (name.endsWith(".class")) {
					names.add(name.substring(0, name.length() - 6).replace('/', '.'));
				} else if (name.endsWith(".jar")) {
					// Read in memory: the nested jar is a comparison input, not something to extract.
					try (java.util.zip.ZipInputStream nested =
							new java.util.zip.ZipInputStream(zip.getInputStream(entry))) {
						for (java.util.zip.ZipEntry inner; (inner = nested.getNextEntry()) != null; ) {
							String innerName = inner.getName();
							if (!innerName.endsWith(".class")) continue;
							names.add(innerName.substring(0, innerName.length() - 6).replace('/', '.'));
						}
					} catch (java.io.IOException unreadableNested) {
						// One unreadable bundle must not make the whole jar unreadable — it only widens the diff.
					}
				}
			}
		} catch (java.io.IOException unreadable) {
			return false;
		}
		return true;
	}

	public static synchronized Decision arbitrateNested(EnvType envType, List<Path> nestedJars) {
		Decision phase1 = current();
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) return phase1;
		if (wholeInstancePlan != null && envType == cachedSide) {
			wholeInstancePlan.verify(nestedJars == null ? List.of() : nestedJars);
			return phase1;
		}
		if (nestedJars == null || nestedJars.isEmpty()) return phase1;

		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		List<Claim> nestedClaims = new ArrayList<>();
		List<Alias> ignored = new ArrayList<>();
		for (Path jar : nestedJars) {
			Claim claim = claimOf(discoverer, jar, envType, ignored);
			if (claim != null) nestedClaims.add(claim);
		}
		if (nestedClaims.isEmpty()) return phase1;
		// Published, not just returned. KernelModLoader reads current() long after the mods directory was walked,
		// to hand NeoForge the presence aliases (KernelModLoader:159), and PassiveSeeder reads it to skip
		// suppressed jars. Leaving the nested half out of the cache would mean the answer this boot acted on and
		// the answer those two see are different answers.
		cached = arbitrateNested(phase1, topLevelClaims, nestedClaims);
		return cached;
	}

	/**
	 * The pure half of the nested pass, so tests can drive it without a filesystem — the same split as
	 * {@link #arbitrate(List, List)}.
	 */
	static Decision arbitrateNested(Decision phase1, List<Claim> topLevel, List<Claim> nestedClaims) {
		// Everything that can claim an id, so a nested copy is also weighed against a TOP-LEVEL jar of the same
		// mod — a library nested inside a Fabric mod must still lose to the NeoForge build the user installed.
		Map<String, List<Claim>> byId = new LinkedHashMap<>();
		for (Claim claim : topLevel) {
			if (phase1.suppressed(claim.jar())) continue;   // already lost phase one; it is not a live claimant
			for (String id : claim.modIds()) byId.computeIfAbsent(id, k -> new ArrayList<>()).add(claim);
		}
		Set<Path> nestedPaths = new LinkedHashSet<>();
		for (Claim claim : nestedClaims) {
			nestedPaths.add(claim.jar().toAbsolutePath());
			for (String id : claim.modIds()) byId.computeIfAbsent(id, k -> new ArrayList<>()).add(claim);
		}

		Set<Path> suppressed = new LinkedHashSet<>(phase1.suppressedJars());
		Map<String, Path> owners = new LinkedHashMap<>(phase1.ownerByModId());
		List<Alias> aliases = new ArrayList<>(phase1.aliases());
		int contested = 0;
		for (Map.Entry<String, List<Claim>> e : byId.entrySet()) {
			List<Claim> claimants = e.getValue();
			if (claimants.size() < 2) continue;

			// ONLY a cross-ECOSYSTEM contest. Same-family duplicates are the ordinary shape of JarJar — one
			// library nested by five mods that each bundle it — and both loaders already keep the first and ignore
			// the rest, without taking anything off the classpath. Withdrawing those jars is not a smaller
			// version of this fix, it is a different and much larger change: it took Sodium's NeoForge build off
			// the classpath (its real mod jar is nested inside a wrapper that declares the same id) and its
			// ServiceLoader lookup then failed. What no loader handles, and what this pass exists for, is the
			// SAME id claimed by two ecosystems, because each family only ever deduplicates within itself.
			Set<Ecosystem> families = new LinkedHashSet<>();
			boolean anyNested = false;
			for (Claim claim : claimants) {
				families.add(claim.ecosystem());
				if (nestedPaths.contains(claim.jar().toAbsolutePath())) anyNested = true;
			}
			if (!anyNested || families.size() < 2) continue;

			Claim winner = pick(e.getKey(), claimants, nestedPreference());
			// A top-level jar is past the point of being withdrawn: phase one already handed it to its family's
			// discovery. If the preference would pick a nested jar over one, keep the top-level jar and say so.
			if (nestedPaths.contains(winner.jar().toAbsolutePath())) {
				Claim installed = null;
				for (Claim claim : claimants) {
					if (!nestedPaths.contains(claim.jar().toAbsolutePath())) { installed = claim; break; }
				}
				if (installed != null) {
					ForbricLog.warn("[Forbric/DupeId] '%s' would be taken from the nested %s, but the top-level %s "
							+ "is already loaded and cannot be withdrawn — keeping the top-level one",
							e.getKey(), winner.jar().getFileName(), installed.jar().getFileName());
					winner = installed;
				}
			}

			contested++;
			owners.put(e.getKey(), winner.jar().toAbsolutePath());
			Set<Ecosystem> lost = new LinkedHashSet<>();
			for (Claim claim : claimants) {
				if (claim == winner) continue;
				if (claim.ecosystem() != winner.ecosystem()) lost.add(claim.ecosystem());
				// SUBSET RULE, as in the top-level pass: a jar may only lose if every id it declares is also
				// claimed by someone else, or a library bundling foo + foo_compat is withdrawn because foo alone
				// collided and foo_compat ends up loaded by nobody.
				if (!nestedPaths.contains(claim.jar().toAbsolutePath())) continue;
				List<String> orphaned = new ArrayList<>();
				for (String id : claim.modIds()) {
					List<Claim> others = byId.getOrDefault(id, List.of());
					if (others.size() < 2) orphaned.add(id);
				}
				if (!orphaned.isEmpty()) {
					ForbricLog.warn("[Forbric/DupeId] keeping the nested %s despite losing '%s' — it also declares "
							+ "%s, which nothing else provides", claim.jar().getFileName(), e.getKey(), orphaned);
					continue;
				}
				suppressed.add(claim.jar().toAbsolutePath());
			}
			// The winner's version, but fall back to any claimant that declared one: an alias exists so
			// isModLoaded answers, and a mod comparing the version it gets back against a range is better served
			// by the losing jar's real number than by versionOf's "0" placeholder.
			String version = winner.versionOf(e.getKey());
			if ("0".equals(version)) {
				for (Claim claim : claimants) {
					String declared = claim.versionOf(e.getKey());
					if (!"0".equals(declared)) { version = declared; break; }
				}
			}
			for (Ecosystem ecosystem : lost) aliases.add(new Alias(e.getKey(), ecosystem, version));
			ForbricLog.info("[Forbric/DupeId] nested mod id '%s' is claimed by %d jars across %s — loading %s (%s). "
					+ "Each loader only deduplicates within its own family, so without this it would have been "
					+ "constructed once per ecosystem%s", e.getKey(), claimants.size(), families,
					winner.jar().getFileName(), winner.ecosystem(),
					lost.isEmpty() ? "" : ", aliased into " + lost);
		}
		if (contested == 0) {
			ForbricLog.debug("[Forbric/DupeId] nested pass: %d nested jar(s), no mod id claimed by more than one "
					+ "ecosystem — nothing to arbitrate", nestedClaims.size());
			return phase1;
		}
		ForbricLog.info("[Forbric/DupeId] nested pass: %d nested jar(s), %d mod id(s) claimed across ecosystems, "
				+ "%d nested jar(s) suppressed", nestedClaims.size(), contested,
				suppressed.size() - phase1.suppressedJars().size());
		return new Decision(Set.copyOf(suppressed), Map.copyOf(owners), List.copyOf(aliases));
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
		return arbitrate(claims, universalAliases, "top-level");
	}

	/**
	 * @param pass which walk these claims came from, so the two passes' log lines cannot be mistaken for each
	 *             other. A gate reading "cross-jar arbitration: 0 duplicate mod id(s)" has to know WHICH walk
	 *             found none — the top-level one finding none says nothing about the nested one.
	 */
	static Decision arbitrate(List<Claim> claims, List<Alias> universalAliases, String pass) {
		return chooseJoint(claims, universalAliases, pass, List.of());
	}

	/** Metadata and bytecode clauses are read only for a real contest; ordinary single-jar boots keep their path. */
	static Decision arbitrateJoint(List<Claim> claims, List<Alias> aliases, EnvType side) {
		Map<String, Integer> counts = new HashMap<>();
		for (Claim claim : claims) for (String id : claim.modIds()) counts.merge(JointCandidateSelector.key(id), 1, Integer::sum);
		List<JointCandidateSelector.Rule> rules = counts.values().stream().anyMatch(n -> n > 1)
				? CandidateContractScanner.scan(claims, side) : List.of();
		return chooseJoint(claims, aliases, "top-level", rules);
	}

	private static Decision chooseJoint(List<Claim> claims, List<Alias> universalAliases, String pass,
			List<JointCandidateSelector.Rule> rules) {
		Map<String, List<Claim>> byId = new java.util.TreeMap<>();
		Map<String, Ecosystem> overrides = new LinkedHashMap<>();
		for (Claim claim : claims) for (String id : claim.modIds()) {
			byId.computeIfAbsent(JointCandidateSelector.key(id), ignored -> new ArrayList<>()).add(claim);
			Ecosystem forced = overrideFor(id); if (forced != null) overrides.put(id, forced);
		}
		long contested = byId.values().stream().filter(list -> list.size() > 1).count();
		if (contested == 0) {
			logUniversalAliases(universalAliases);
			return new Decision(Set.of(), Map.of(), List.copyOf(universalAliases));
		}
		int limit = Math.max(1, Math.min(1_000_000, Integer.getInteger("forbric.arbitrationMaxNodes", 100_000)));
		JointCandidateSelector.Result result = JointCandidateSelector.solve(claims, rules, preference(), overrides, limit);
		reportSelection(claims, result, overrides);
		return decisionFromSelection(claims, universalAliases, pass, result);
	}

	private static Decision decisionFromSelection(List<Claim> claims, List<Alias> universalAliases, String pass,
			JointCandidateSelector.Result result) {
		Map<String, List<Claim>> byId = new java.util.TreeMap<>();
		for (Claim claim : claims) for (String id : claim.modIds()) byId.computeIfAbsent(JointCandidateSelector.key(id), ignored -> new ArrayList<>()).add(claim);
		long contested = byId.values().stream().filter(list -> list.size() > 1).count();
		Set<Path> suppressed = new LinkedHashSet<>(); Map<String, Path> owners = new LinkedHashMap<>();
		for (Claim claim : claims) if (!result.selected().contains(JointCandidateSelector.path(claim))) suppressed.add(claim.jar().toAbsolutePath());
		List<Alias> aliases = new ArrayList<>(universalAliases);
		for (var entry : byId.entrySet()) {
			if (entry.getValue().size() < 2) continue;
			Claim winner = entry.getValue().stream().filter(c -> result.selected().contains(JointCandidateSelector.path(c))).findFirst().orElse(null);
			if (winner == null) continue;
			Set<Ecosystem> lost = new LinkedHashSet<>();
			for (Claim claim : entry.getValue()) {
				for (String id : claim.modIds()) if (JointCandidateSelector.key(id).equals(entry.getKey())) owners.put(id, winner.jar().toAbsolutePath());
				if (suppressed.contains(claim.jar().toAbsolutePath()) && claim.ecosystem() != winner.ecosystem()) lost.add(claim.ecosystem());
			}
			String winningId = winner.modIds().stream().filter(id -> JointCandidateSelector.key(id).equals(entry.getKey())).findFirst().orElse(entry.getKey());
			for (Claim claim : entry.getValue()) {
				if (!suppressed.contains(claim.jar().toAbsolutePath()) || claim.ecosystem() == winner.ecosystem()) continue;
				for (String id : claim.modIds()) {
					if (!JointCandidateSelector.key(id).equals(entry.getKey())) continue;
					Alias alias = new Alias(id, claim.ecosystem(), winner.versionOf(winningId));
					if (!aliases.contains(alias)) aliases.add(alias);
				}
			}
			ForbricLog.info("[Forbric/DupeId] mod id '%s' claimed by %d jars — loading %s (%s)%s", winningId,
					entry.getValue().size(), winner.jar().getFileName(), winner.ecosystem(), lost.isEmpty() ? "" : ", aliased into " + lost);
		}
		logUniversalAliases(universalAliases);
		ForbricLog.info("[Forbric/DupeId] cross-jar arbitration (%s): %d duplicate mod id(s), %d jar(s) suppressed, %d presence alias(es)",
				pass, contested, suppressed.size(), aliases.size());
		return new Decision(Set.copyOf(suppressed), Map.copyOf(owners), List.copyOf(aliases));
	}

	private static void reportSelection(List<Claim> claims, JointCandidateSelector.Result result, Map<String, Ecosystem> overrides) {
		Map<Path, Claim> byPath = new HashMap<>();
		for (Claim claim : claims) byPath.put(JointCandidateSelector.path(claim), claim);
		// A bounded search's selection is its best model so far, not a proof that the rest is impossible: what it
		// leaves unmet stays visible but cannot be confirmed (the same pack must not stop on a slower machine).
		boolean bounded = result.status() == JointCandidateSelector.Status.SEARCH_LIMIT;
		for (var rule : result.unsatisfied()) recordRule(byPath.get(rule.consumer()), rule, !bounded);
		// A soft closure rule only says the scan could not follow a path that may not run; it steers nothing and
		// names no member, so it stays in the count below instead of becoming one of hundreds of player notes.
		for (var rule : result.uncertain()) if (rule.hard() || !rule.id().startsWith("entry-closure:")) recordRule(byPath.get(rule.consumer()), rule, false);
		// No installed combination meets these, so no choice made here caused them: reported, never a launch stop.
		for (var rule : result.unavoidable()) recordRule(byPath.get(rule.consumer()), rule, false);
		recordOverrides(result.refusedOverrides(), overrides, true);
		recordOverrides(result.impossibleOverrides(), overrides, false);
		if (result.status() != JointCandidateSelector.Status.SOLVED) {
			boolean confirmed = result.status() == JointCandidateSelector.Status.UNSATISFIABLE;
			// The aggregate row is the arbitration's own verdict. Filing it under the first selected jar marked
			// whichever mod sorted first in mods/ DEGRADED and named it in the prompt; the mods actually involved
			// already carry their own rows (recordRule / recordOverrides) and are listed here as evidence.
			Set<String> involved = new LinkedHashSet<>();
			for (var rule : confirmed ? result.unsatisfied() : result.uncertain()) {
				Claim owner = byPath.get(rule.consumer());
				if (owner != null && !owner.modIds().isEmpty()) involved.add(owner.modIds().getFirst());
			}
			for (String pinned : result.refusedOverrides().keySet()) {
				involved.add(overrides.keySet().stream().filter(raw -> JointCandidateSelector.key(raw).equals(pinned)).findFirst().orElse(pinned));
			}
			net.forbric.api.CompatibilityFindings.record(new net.forbric.api.CompatibilityFinding(
					"arbitration:selection", "forbric", "Mod dependency combination", "arbitration",
					confirmed ? net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED : net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED,
					confirmed, confirmed ? "No installed candidate combination satisfies all modeled required contracts and explicit overrides"
							: result.status() == JointCandidateSelector.Status.SEARCH_LIMIT
									? "Candidate search reached its bound; this selection has not been proved compatible"
									: "Some required candidate contracts could not be verified; this selection remains unproved",
					List.of("status=" + result.status(), "visited=" + result.visited(), "overrides=" + overrides, "involved=" + involved)));
		}
		ForbricLog.info("[Forbric/Arbitration] status=%s; nodes=%d; confirmed violations=%d; unproved contracts=%d%s",
				result.status(), result.visited(), bounded ? 0 : result.unsatisfied().size(), result.uncertain().size(),
				result.unavoidable().isEmpty() ? "" : "; unmeetable by any installed build=" + result.unavoidable().size());
	}

	/**
	 * A pin that was not honoured, filed under the pinned mod. {@code conflicting}: it clashes with another pin or
	 * with what a bundling parent requires, which the player has to resolve. Otherwise the named ecosystem has no
	 * usable build of the mod at all; like the old per-id pick, that is a warning and the automatic choice stands.
	 */
	private static void recordOverrides(Map<String, Ecosystem> pins, Map<String, Ecosystem> requested, boolean conflicting) {
		for (var pin : pins.entrySet()) {
			String id = requested.keySet().stream().filter(raw -> JointCandidateSelector.key(raw).equals(pin.getKey())).findFirst().orElse(pin.getKey());
			if (!conflicting) ForbricLog.warn("[Forbric/DupeId] %s asks for '%s' from %s, but no usable jar of that ecosystem claims it — "
					+ "keeping the automatic choice", OVERRIDE_FILE + " / -D" + OWNER_OVERRIDE, id, pin.getValue());
			net.forbric.api.CompatibilityFindings.record(new net.forbric.api.CompatibilityFinding(
					"arbitration:override:" + id, id, "Chosen mod build", "arbitration:override",
					conflicting ? net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED : net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED,
					conflicting, conflicting ? "The requested " + pin.getValue() + " build cannot be combined with the other explicit choices or its bundling mods"
							: "No usable " + pin.getValue() + " build of this mod is installed; the automatic choice was kept",
					List.of("override=" + id + "=" + pin.getValue())));
		}
	}

	private static void recordRule(Claim owner, JointCandidateSelector.Rule rule, boolean confirmed) {
		String mod = owner == null || owner.modIds().isEmpty() ? "forbric" : owner.modIds().getFirst();
		net.forbric.api.CompatibilityFindings.record(new net.forbric.api.CompatibilityFinding(
				"arbitration:" + rule.id(), mod, "Mod dependency integration", "arbitration:" + rule.consumer().getFileName(),
				confirmed ? net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED : net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED,
				confirmed && rule.hard(), rule.detail(), List.of("candidate=" + rule.consumer(), "providers=" + rule.providers(), "unresolved=" + rule.uncertainProviders())));
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
		return pick(modId, claimants, preference());
	}

	private static Claim pick(String modId, List<Claim> claimants, List<Ecosystem> order) {
		Ecosystem forced = overrideFor(modId);
		if (forced != null) {
			for (Claim claim : claimants) {
				if (claim.ecosystem() == forced) return claim;
			}
			// A typo, or an ecosystem that has no claim on this id, must never unload the mod entirely.
			ForbricLog.warn("[Forbric/DupeId] -D%s asks for '%s' from %s, but no such jar claims it — falling back "
					+ "to the preference order", OWNER_OVERRIDE, modId, forced);
		}
		for (Ecosystem candidate : order) {
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

	/**
	 * {@code -Dforbric.nestedDupePreference}, defaulting to NEOFORGE, then FABRIC, then traditional FORGE.
	 *
	 * <p>Deliberately not the top-level order. A duplicate there is two builds of a mod the USER chose, and the
	 * pack they built around it is the one whose glue is most likely intact. A nested jar is chosen by nobody: it
	 * is a library its parents happened to bundle, both families' parents call it, and only one copy of a class
	 * can exist. So the question is not "which build was this pack tested with" but "which build, when it is the
	 * only one, leaves the fewest callers talking to a method that does nothing".
	 *
	 * <p>That last clause is the whole difficulty, because a multi-loader library ships one build per loader and
	 * each build STUBS OUT the phases its own loader does not have. The stub is an empty method, not an error:
	 * the caller registers nothing, hears nothing, and dies much later somewhere else. Both defects below are
	 * that same shape, and between them they fix the order:
	 *
	 * <p><b>FORGE loses to FABRIC.</b> Xaero's {@code xaerolib} is nested by a Fabric minimap and a
	 * MinecraftForge world map. Its Fabric bootstrap sets {@code XaeroLib.client} from {@code onInitializeClient},
	 * which the kernel runs inside {@code Minecraft.<init>} — before any tick. Its MinecraftForge bootstrap sets
	 * the same field from {@code FMLClientSetupEvent}. Letting MinecraftForge win left the Fabric minimap's
	 * first-tick hook calling {@code XaeroLib.getClient()} on a null: "Cannot invoke
	 * XaeroLibClient.getBufferProvider() because the return value of XaeroLib.getClient() is null", at
	 * {@code CustomRenderTypes.applyFixedOrder}. Letting Fabric win produced a clean boot with BOTH mods up — the
	 * world map is a traditional-Forge {@code @Mod} and did not mind at all.
	 *
	 * <p><b>FABRIC loses to NEOFORGE.</b> tr7zw's {@code transition} is nested by EntityCulling (Fabric, the
	 * {@code -fabric-} build) and NotEnoughAnimations (NeoForge, the {@code -neoforge-} build) — same id, same
	 * version 1.0.25, one host each, so nothing about the contest itself separates them. The two builds differ in
	 * exactly two of their 5,800 methods, and the Fabric one is
	 * {@code ModLoaderEventUtil.registerClientSetupListener(Runnable)}, whose entire Fabric body is {@code return}
	 * — Fabric has no client-setup phase. NotEnoughAnimations does ALL of its initialisation from that listener.
	 * With the Fabric copy loaded its {@code @Mod} constructor handed the runnable to an empty method, nothing
	 * was registered, nothing was logged, {@code NEABaseMod.config} stayed null, and twenty seconds later the
	 * first player tick threw "Cannot read field maxBlockingAngle" out of its own mixin and took the client with
	 * it. The other direction costs {@code ModLoaderUtil.disableDisplayTest}, stubbed in the NeoForge build and
	 * called by both hosts — a server-list version marker, cosmetic, and nothing waits on it.
	 *
	 * <p>So the loss is real either way and this is a default, not a law: {@code -Dforbric.modOwner=<id>=<ecosystem>}
	 * overrides it per mod and this knob replaces the order wholesale. What the order buys is that when a nested
	 * library is contested, the family that loses is the one whose callers lose the least.
	 */
	static List<Ecosystem> nestedPreference() {
		String csv = System.getProperty("forbric.nestedDupePreference");
		if (csv == null || csv.isBlank()) return NESTED_DEFAULT;

		List<Ecosystem> order = new ArrayList<>();
		for (String raw : csv.split(",")) {
			Ecosystem parsed = Ecosystem.parse(raw);
			if (parsed != null) {
				order.add(parsed);
			} else {
				ForbricLog.warn("[Forbric/DupeId] ignoring unknown ecosystem '%s' in -Dforbric.nestedDupePreference",
						raw.trim());
			}
		}
		return order.isEmpty() ? NESTED_DEFAULT : order;
	}

	/** See {@link #nestedPreference()} — both halves of this order are a measured defect, one each way. */
	private static final List<Ecosystem> NESTED_DEFAULT =
			List.of(Ecosystem.NEOFORGE, Ecosystem.FABRIC, Ecosystem.FORGE);

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
			Claim claim = claimOf(discoverer, jar, envType, universalAliases);
			if (claim != null) claims.add(claim);
		}
		return claims;
	}

	/**
	 * One jar's claim on the ids it declares, or {@code null} for a plain library — nobody claims it, so it cannot
	 * contest an id.
	 *
	 * <p>Split out of {@link #scan} so the nested pass can build claims for jars that are not in {@code mods/}:
	 * a JarJar/JiJ child is extracted to {@code .forbric-kernel/}, and the walk that produced this decision never
	 * goes there.
	 */
	static Claim claimOf(ForbricModDiscoverer discoverer, Path jar, EnvType envType, List<Alias> aliasesOut) {
		Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
		if (owner == null) return null;
		Map<String, String> versions = new LinkedHashMap<>();
		List<String> ids = owner == Ecosystem.FABRIC
				? fabricIds(jar, envType, versions)
				: forgeFamilyIds(discoverer, jar, versions, owner);
		collectUniversalAliases(discoverer, jar, owner, envType, aliasesOut);
		return ids.isEmpty() ? null : new Claim(jar, owner, ids, Map.copyOf(versions));
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
					: forgeFamilyIds(discoverer, jar, versions, lost);
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
			Map<String, String> versions, Ecosystem family) {
		List<String> ids = new ArrayList<>();
		try {
			for (DiscoveredMod mod : discoverer.discoverJar(jar)) {
				if (mod.getEcosystem() != family) continue;
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
