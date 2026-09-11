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
			UnifiedDependency.Side physicalSide) {
		if (present == null || present.isEmpty()) return;

		Map<String, DiscoveredMod> byId = new LinkedHashMap<>();
		for (DiscoveredMod mod : present) {
			if (mod.getId() != null) byId.putIfAbsent(mod.getId().toLowerCase(Locale.ROOT), mod);
		}
		boolean indexComplete = nestedJars != null;
		for (DiscoveredMod mod : nestedMods(nestedJars)) {
			if (mod.getId() != null) byId.putIfAbsent(mod.getId().toLowerCase(Locale.ROOT), mod);
		}

		List<String> missing = new ArrayList<>();
		List<String> unsatisfied = new ArrayList<>();
		int crossEcosystem = 0;
		int sideSkipped = 0;

		for (DiscoveredMod mod : present) {
			for (UnifiedDependency dep : mod.getDependencies()) {
				if (!dep.isMandatory() || dep.getModId() == null) continue;

				String wanted = dep.getModId().toLowerCase(Locale.ROOT);
				if (NON_MOD_IDS.contains(wanted)) continue;
				if (EcosystemVersions.provided(wanted) != null) continue; // EcosystemVersions.audit owns these

				if (dep.getSide() != UnifiedDependency.Side.BOTH) {
					if (physicalSide == null) {
						sideSkipped++;
						continue;
					}
					if (!dep.appliesOn(physicalSide)) continue;
				}

				DiscoveredMod provider = byId.get(wanted);
				if (provider == null) {
					// Only when we know the index covers everything that is loaded. Otherwise this is the one
					// thing a diagnostic must never do: accuse a mod of a problem it does not have.
					if (indexComplete) {
						missing.add(describe(mod) + " requires " + dep.getModId() + " "
								+ dep.getVersionConstraint() + " — not installed");
					}
					continue;
				}
				if (!dep.isSatisfiedBy(provider.getVersion())) {
					unsatisfied.add(describe(mod) + " requires " + dep.getModId() + " " + dep.getVersionConstraint()
							+ " but " + describe(provider) + " is version " + provider.getVersion());
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
