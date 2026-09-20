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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.Side;
import net.forbric.api.UnifiedDependency;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.metadata.forge.EcosystemVersions;
import net.forbric.kernel.util.ForbricLog;

/**
 * Says out loud whether each mod's declared hard dependencies are actually met, ACROSS ecosystems.
 *
 * <p>Nothing did. {@code EcosystemVersions.audit} checks the platform ids a mod declares — {@code minecraft},
 * {@code neoforge}, {@code forge} — and a single-ecosystem loader's own resolver would check the rest, but under
 * Forbric no resolver runs over the combined set: the Fabric side solves only Fabric mods, and the Forge families'
 * mods are driven by {@link KernelLifecycle} rather than by FML's resolution. A Forge mod requiring a mod that is
 * not installed therefore reached its constructor and failed there, as a {@code NoClassDefFoundError} or a null
 * dereference somewhere inside the mod, with nothing anywhere saying "you did not install its dependency".
 *
 * <p>The reason this belongs to Forbric rather than to either ecosystem is the other half: on a merged instance a
 * Forge mod's requirement can be satisfied by a FABRIC mod, and vice versa. Neither loader can see that, so
 * neither can report it — one would call it missing and the other would never be asked. This counts those too,
 * because "your Forge mod's dependency is provided by the Fabric mod next to it" is the single most Forbric-shaped
 * fact the boot log can carry.
 *
 * <h2>It reports; it never refuses</h2>
 *
 * <p>Deliberately no enforcement. Refusing to boot over an unmet requirement is what a genuine loader does, and it
 * is the wrong trade here: a single bad declaration would cost the user every other mod, and Forbric's whole value
 * is "load as much as possible and be honest about the rest". {@link net.forbric.api.VersionPredicate} fails open
 * for the same reason — an unreadable constraint is not evidence of a problem.
 */
public final class DependencyAudit {
	/**
	 * One hard requirement this instance does not meet.
	 *
	 * @param requiredBy          the mod id that declared the requirement
	 * @param requiredByName      its display name, for a reader who knows the mod by its name and not its id
	 * @param requiredByEcosystem which family declared it — a player looking for the download needs to know
	 *                            whether to fetch the Fabric build or the Forge one
	 * @param requiredId          the mod id it asked for
	 * @param requiredRange       the version constraint as declared
	 * @param installedVersion    the version that IS installed, or {@code null} when nothing provides the id at
	 *                            all. This is the whole difference between "install it" and "change its version",
	 *                            which is the difference between the two things a player can do about it.
	 */
	public record Unmet(String requiredBy, String requiredByName, Ecosystem requiredByEcosystem,
			String requiredId, String requiredRange, String installedVersion) {
		public boolean absent() {
			return installedVersion == null;
		}
	}

	private DependencyAudit() {
	}

	/**
	 * Ids that name the platform or the language runtime, never a mod in {@code mods/}.
	 *
	 * <p>{@link EcosystemVersions#provided} is asked as well, and the overlap is deliberate rather than sloppy:
	 * the two answer different questions. This set is "no discovered mod will ever carry this id"; {@code provided}
	 * is "this instance supplies it, at this version, and its range has already been audited". Relying only on the
	 * second would make every mod's {@code minecraft} requirement read as MISSING in any boot where the provided
	 * table had not been populated yet — a page of false accusations, which is the one failure mode a diagnostic
	 * must not have.
	 */
	private static final Set<String> NON_MOD_IDS = Set.of(
			"java", "fml", "fabricloader", "fabric", "mixinextras", "minecraft", "forge", "neoforge");

	/**
	 * Indexes a mod under its id AND every {@code provides} alias.
	 *
	 * <p>A dependency names whatever id its author was given, and for Fabric that is often an alias: LibJF ships
	 * {@code "id":"libjf-base"}/{@code "provides":["libjf_base"]}, and respackopts requires {@code libjf_base}.
	 * Indexing only the id made this report three installed mods as NOT INSTALLED, in a dialog whose whole job is
	 * to be believed.
	 */
	private static void index(Map<String, DiscoveredMod> byId, DiscoveredMod mod) {
		if (mod == null) return;
		if (mod.getId() != null) byId.putIfAbsent(mod.getId().toLowerCase(Locale.ROOT), mod);
		for (String alias : mod.getAliases()) {
			if (alias != null && !alias.isBlank()) byId.putIfAbsent(alias.toLowerCase(Locale.ROOT), mod);
		}
	}

	/**
	 * Reports unmet hard dependencies among {@code present}.
	 *
	 * <p>{@code nestedJars} is what JarJar extraction unpacked this boot ({@link KernelBoot#nestedJarJarJars()}).
	 * Those mods are loaded exactly like top-level ones but are not in {@code mods/}, so they are not in
	 * {@code present} — and without them this reports Journeymap's {@code commonnetworking} and
	 * LambDynamicLights' {@code spruceui} as missing while the boot log says, a few lines earlier, that it
	 * extracted them. {@code null} means extraction has not run, which is NOT the same as "found nothing": in
	 * that state nothing is reported missing at all, because the index cannot be trusted to be complete.
	 *
	 * <p>{@code physicalSide} is the side actually running. Pass {@code null} when it is not known: requirements
	 * scoped to one side are then skipped rather than guessed at, because a client-only requirement judged on a
	 * dedicated server is a false accusation, and a false accusation in a boot log costs more than a silence.
	 */
	public static void report(List<DiscoveredMod> present, List<Path> nestedJars,
			Side physicalSide) {
		if (present == null || present.isEmpty()) return;

		Map<String, DiscoveredMod> byId = new LinkedHashMap<>();
		for (DiscoveredMod mod : present) index(byId, mod);
		boolean indexComplete = nestedJars != null;
		for (DiscoveredMod mod : nestedMods(nestedJars)) index(byId, mod);

		List<String> missing = new ArrayList<>();
		List<String> unsatisfied = new ArrayList<>();
		// Requirements met by the same library under the other ecosystem's id spelling. Reported rather than
		// silently absorbed: a player who reads "cloth_config" in a mod's description and sees
		// "cloth-config" in their mods folder deserves to be told those are one mod, not left to wonder.
		List<String> respelled = new ArrayList<>();
		// The same two findings as structured values. The prose above is what the log has always said and what a
		// gate would grep; this is what a dialog can lay out in a table. Built alongside rather than parsed back
		// out of the strings, because a formatter is not a data source.
		List<Unmet> unmet = new ArrayList<>();
		int crossEcosystem = 0;
		int sideSkipped = 0;

		for (DiscoveredMod mod : present) {
			for (UnifiedDependency dep : mod.getDependencies()) {
				if (!dep.isMandatory() || dep.getModId() == null) continue;

				String wanted = dep.getModId().toLowerCase(Locale.ROOT);
				if (NON_MOD_IDS.contains(wanted)) continue;
				if (EcosystemVersions.provided(wanted) != null) continue; // EcosystemVersions.audit owns these

				if (dep.getSideScope() != UnifiedDependency.SideScope.BOTH) {
					if (physicalSide == null) {
						sideSkipped++;
						continue;
					}
					if (!dep.appliesOn(physicalSide)) continue;
				}

				DiscoveredMod provider = byId.get(wanted);
				if (provider == null) {
					// The same library, spelled the other ecosystem's way. Tried only here, after the exact id
					// and every provides alias have missed, so nothing that already resolved changes meaning.
					provider = net.forbric.api.ModIds.underAnotherSpelling(wanted, byId);
					if (provider != null) {
						respelled.add(describe(mod) + " requires " + dep.getModId() + ", which is installed as "
								+ describe(provider) + " — the same library, spelled the way its own ecosystem "
								+ "spells it");
					}
				}
				if (provider == null) {
					// Only when we know the index covers everything that is loaded. Otherwise this is the one
					// thing a diagnostic must never do: accuse a mod of a problem it does not have.
					if (indexComplete) {
						missing.add(describe(mod) + " requires " + dep.getModId() + " "
								+ dep.getVersionConstraint() + " — not installed");
						unmet.add(new Unmet(mod.getId(), mod.getDisplayName(), mod.getEcosystem(),
								dep.getModId(), String.valueOf(dep.getVersionConstraint()), null));
					}
					continue;
				}
				if (!dep.isSatisfiedBy(provider.getVersion())) {
					unsatisfied.add(describe(mod) + " requires " + dep.getModId() + " " + dep.getVersionConstraint()
							+ " but " + describe(provider) + " is version " + provider.getVersion());
					unmet.add(new Unmet(mod.getId(), mod.getDisplayName(), mod.getEcosystem(),
							dep.getModId(), String.valueOf(dep.getVersionConstraint()), provider.getVersion()));
					continue;
				}
				if (provider.getEcosystem() != mod.getEcosystem()) crossEcosystem++;
			}
		}

		for (String line : missing) {
			ForbricLog.warn("[Forbric/Deps] %s. It is being loaded anyway — install it if that mod misbehaves.", line);
		}
		for (String line : unsatisfied) {
			ForbricLog.warn("[Forbric/Deps] %s. It is being loaded anyway — expect it to fail on whatever the "
					+ "required version added.", line);
		}
		for (String line : respelled) {
			ForbricLog.info("[Forbric/Deps] %s.", line);
		}
		if (crossEcosystem > 0) {
			ForbricLog.info("[Forbric/Deps] %d hard dependenc%s satisfied ACROSS ecosystems — neither loader on its "
					+ "own could have resolved %s.", crossEcosystem, crossEcosystem == 1 ? "y is" : "ies are",
					crossEcosystem == 1 ? "it" : "them");
		}
		if (sideSkipped > 0) {
			ForbricLog.debug("[Forbric/Deps] %d side-scoped requirement(s) not judged: the physical side was not "
					+ "known here", sideSkipped);
		}
		if (missing.isEmpty() && unsatisfied.isEmpty()) {
			ForbricLog.info("[Forbric/Deps] every hard dependency of %d mod(s) is present and in range.",
					present.size());
		}
		if (!indexComplete) {
			ForbricLog.debug("[Forbric/Deps] JarJar extraction has not run, so nothing was reported as missing — "
					+ "a nested provider would have looked absent");
		}

		// Everything above is the log, unchanged. This is the same findings put where a player will see them —
		// the WARNs are one line each in a ten-thousand-line file, and what they predict arrives much later
		// wearing another mod's name. Client only, and it never changes what loads; see DependencyDialog.
		offerDialog(unmet, physicalSide);
	}

	/**
	 * Hands the findings to the dialog, if there are any.
	 *
	 * <p>Wrapped, because this class is a diagnostic and a diagnostic must never be able to fail the boot it
	 * reports on. The caller in {@code PassiveSeeder} wraps it too; this second net exists because the failure
	 * modes here are a child process and a windowing system rather than the audit's own arithmetic, and those
	 * deserve their own sentence in the log.
	 */
	private static void offerDialog(List<Unmet> unmet, Side physicalSide) {
		List<net.forbric.kernel.mixin.ForeignMixinBreaks.Break> breaks =
				net.forbric.kernel.mixin.ForeignMixinBreaks.all();
		if (unmet.isEmpty() && breaks.isEmpty()) return;
		try {
			List<net.forbric.kernel.ui.DependencyReport.Row> rows = new ArrayList<>();
			for (Unmet one : unmet) {
				rows.add(new net.forbric.kernel.ui.DependencyReport.Row(one.requiredBy(), one.requiredByName(),
						one.requiredByEcosystem() == null ? "?" : one.requiredByEcosystem().toString(),
						one.requiredId(), one.requiredRange(), one.installedVersion()));
			}
			List<net.forbric.kernel.ui.DependencyReport.MixinRow> mixinRows = new ArrayList<>();
			for (var one : breaks) {
				mixinRows.add(new net.forbric.kernel.ui.DependencyReport.MixinRow(
						one.config(), one.mixin(), String.join(", ", one.anchors())));
			}
			net.forbric.kernel.ui.DependencyDialog.offer(rows, mixinRows,
					physicalSide != null && physicalSide.isClient());
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Deps] could not offer the unmet-dependency dialog: %s", String.valueOf(t));
		}
	}

	/**
	 * The mods inside the extracted JarJar jars.
	 *
	 * <p>Read here rather than threaded down from extraction because extraction deals in paths and has no reason
	 * to parse manifests. Per-jar failures are swallowed: an unreadable nested jar means one provider missing from
	 * the index, which at worst costs one over-report — it must not cost the whole audit.
	 */
	private static List<DiscoveredMod> nestedMods(List<Path> nestedJars) {
		if (nestedJars == null || nestedJars.isEmpty()) return List.of();

		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		List<DiscoveredMod> out = new ArrayList<>();
		for (Path jar : nestedJars) {
			try {
				out.addAll(discoverer.discoverJar(jar));
			} catch (Throwable unreadable) {
				ForbricLog.debug("[Forbric/Deps] could not read nested %s: %s", jar.getFileName(),
						String.valueOf(unreadable));
			}
		}
		return out;
	}

	private static String describe(DiscoveredMod mod) {
		Ecosystem eco = mod.getEcosystem();
		return mod.getId() + " (" + (eco == null ? "?" : eco.displayName()) + ")";
	}
}
