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

package net.forbric.kernel.discovery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * The loader manifests that could not be read, per jar and family.
 *
 * <p>One mod's metadata file is that mod's problem. It used to be everyone's: a single
 * {@code versionRange = "[26.2,26.23"} in one jar's {@code neoforge.mods.toml} threw out of discovery, and a
 * 130-jar pack stopped three seconds into the boot with a stack trace and no mod named. Discovery now reads each
 * manifest on its own, records the ones that fail here, and carries on with the rest of the jar and the pack.
 *
 * <p>Recorded is not the same as forgiven. {@link #recordFindings} turns every failure into a
 * {@link CompatibilityFinding}: a jar left with no family that could be read is a confirmed, required loss the
 * player is asked about (or that stops a strict launch), exactly as a mod whose constructor threw. A universal jar
 * that still loads through another family's manifest only gets a note.
 *
 * <p>Static, because about seven passes each make their own {@link ForbricModDiscoverer} during one boot and all
 * of them must agree on which manifests failed.
 *
 * <h2>Unsupported, not unreadable</h2>
 *
 * <p>Forbric no longer loads MinecraftForge mods. A jar {@link LoaderHalfJudge} refuses — a MinecraftForge half and no
 * usable Fabric or NeoForge half — is recorded here as a {@link Kind#UNSUPPORTED} failure of its MinecraftForge half.
 * It travels the path an unreadable jar travels, because to the player it is the same event: a mod they installed
 * that will not load. A jar in {@code mods/} becomes a {@link Lost} row and a confirmed, required finding, so a client
 * is asked and a dedicated server or a strict launch stops; the words name the mod, say that MinecraftForge is no
 * longer supported, and point at its NeoForge or Fabric build, in the player's language ({@code DialogLang}). Silently
 * leaving it out was the one answer the owner ruled out. A MinecraftForge build another mod merely bundles is not
 * something the player chose, and its parent still loads, so it is only noted. Nor is a MinecraftForge build whose mod
 * another installed jar supplies — the NeoForge or Fabric build of the same mod beside it: the mod loads, so the
 * refusal is a note naming the file it loads from, and nothing stops.
 */
public final class MetadataFailures {
	/** Why a half does not load: its manifest could not be read, or it is a loader Forbric no longer supports. */
	public enum Kind { UNREADABLE, UNSUPPORTED }

	/**
	 * One manifest that could not be read, or one MinecraftForge half that will not be loaded. {@code modIds} is empty
	 * when the file did not even parse; {@code name} is the display name the manifest gives, when there is one.
	 */
	public record Failure(Path jar, Ecosystem ecosystem, String manifest, List<String> modIds, String message, Kind kind,
			String name) {
		public Failure {
			jar = key(jar);
			modIds = List.copyOf(modIds);
			name = name == null ? "" : name;
		}

		/** An unreadable manifest — the shape every failure had before MinecraftForge was refused. */
		public Failure(Path jar, Ecosystem ecosystem, String manifest, List<String> modIds, String message) {
			this(jar, ecosystem, manifest, modIds, message, Kind.UNREADABLE, "");
		}

		/** The refusal of a jar {@link LoaderHalfJudge} found has nothing but a MinecraftForge half to load through. */
		static Failure unsupported(Path jar, LoaderHalfJudge.Verdict verdict) {
			List<String> ids = verdict.half(Ecosystem.FORGE).map(LoaderHalfJudge.Half::modIds).orElse(List.of());
			return new Failure(jar, Ecosystem.FORGE, LoaderHalfJudge.FORGE_MANIFEST,
					ids.isEmpty() ? List.of(verdict.modId()) : ids, verdict.describe(), Kind.UNSUPPORTED,
					verdict.displayName());
		}
	}

	/**
	 * A jar nothing could load because its loading family's manifest failed, or because it is a MinecraftForge build
	 * Forbric refuses; the catalogue gives it a row, shown as {@code name}.
	 */
	public record Lost(Path jar, Ecosystem ecosystem, String modId, String name, String detail, Kind kind) {
		public Lost(Path jar, Ecosystem ecosystem, String modId, String detail) {
			this(jar, ecosystem, modId, modId, detail, Kind.UNREADABLE);
		}
	}

	/** The finding id every refused MinecraftForge jar carries, on the mod's own id. */
	public static final String UNSUPPORTED_FINDING = "unsupported:minecraftforge";

	private static final Map<Path, Map<Ecosystem, Failure>> FAILURES = new ConcurrentHashMap<>();
	private static final Set<Path> INSPECTED = ConcurrentHashMap.newKeySet();
	private static final List<Lost> LOST = new java.util.concurrent.CopyOnWriteArrayList<>();
	/**
	 * The loader-half verdict of every jar discovery judged — those carrying a MinecraftForge half. Handed over again
	 * by discovery on every ask, as the failures are, so it survives {@link #reset()} for a jar discovery remembers.
	 */
	private static final Map<Path, LoaderHalfJudge.Verdict> VERDICTS = new ConcurrentHashMap<>();

	private MetadataFailures() {
	}

	static Path key(Path jar) {
		return jar.toAbsolutePath().normalize();
	}

	/** Remembers {@code failure}; warns the first time this jar and family fail, not once per pass that reads it. */
	public static void record(Failure failure) {
		Map<Ecosystem, Failure> byFamily = FAILURES.computeIfAbsent(failure.jar(), k -> new EnumMap<>(Ecosystem.class));
		Failure previous;
		synchronized (byFamily) {
			previous = byFamily.putIfAbsent(failure.ecosystem(), failure);
		}
		if (previous == null && failure.kind() == Kind.UNSUPPORTED) {
			ForbricLog.warn("[Forbric/Unsupported] refusing %s (%s): it is a MinecraftForge mod with no usable Fabric or "
					+ "NeoForge half, and Forbric no longer supports MinecraftForge — use the mod's NeoForge or Fabric "
					+ "build. Halves: %s", failure.jar().getFileName(),
					failure.modIds().isEmpty() ? "?" : failure.modIds().get(0), failure.message());
		} else if (previous == null) {
			ForbricLog.warn("[Forbric] could not read %s in %s: %s — the %s side of this jar is not loaded",
					failure.manifest(), failure.jar().getFileName(), failure.message(), failure.ecosystem());
		}
	}

	/** Remembers what {@link LoaderHalfJudge} said of {@code jar}; discovery hands it over on every ask. */
	static void judged(Path jar, LoaderHalfJudge.Verdict verdict) {
		if (verdict != null) VERDICTS.put(key(jar), verdict);
	}

	/**
	 * The loader-half verdict for {@code jar}, reading the jar first if no pass has yet; {@code null} when the jar
	 * declares no MinecraftForge half (only those are judged at boot) or cannot be opened at all.
	 */
	public static LoaderHalfJudge.Verdict verdict(Path jar) {
		failedFamilies(jar);
		return VERDICTS.get(key(jar));
	}

	/** Whether {@code jar} was refused as a MinecraftForge-only build. */
	public static boolean unsupported(Path jar) {
		return of(jar).stream().anyMatch(f -> f.kind() == Kind.UNSUPPORTED);
	}

	/** Every refusal recorded so far, sorted by jar. */
	public static List<Failure> unsupported() {
		return all().stream().filter(f -> f.kind() == Kind.UNSUPPORTED).toList();
	}

	public static boolean failed(Path jar, Ecosystem ecosystem) {
		Map<Ecosystem, Failure> byFamily = FAILURES.get(key(jar));
		if (byFamily == null) return false;
		synchronized (byFamily) {
			return byFamily.containsKey(ecosystem);
		}
	}

	public static List<Failure> of(Path jar) {
		Map<Ecosystem, Failure> byFamily = FAILURES.get(key(jar));
		if (byFamily == null) return List.of();
		synchronized (byFamily) {
			return List.copyOf(byFamily.values());
		}
	}

	public static List<Failure> all() {
		List<Failure> all = new ArrayList<>();
		for (Path jar : FAILURES.keySet()) all.addAll(of(jar));
		all.sort(Comparator.comparing((Failure f) -> f.jar().toString()).thenComparing(Failure::ecosystem));
		return all;
	}

	/**
	 * The families of {@code jar} whose manifest failed, reading the jar first if no pass has yet. A caller that
	 * arbitrates between a jar's families (MultiLoaderArbiter) can run before discovery has opened that jar.
	 */
	public static Set<Ecosystem> failedFamilies(Path jar) {
		if (INSPECTED.add(key(jar))) {
			try {
				new ForbricModDiscoverer().discoverJar(jar);
			} catch (IOException unreadable) {
				// The whole zip is unreadable; every pass reports that on its own.
				Caught.expected(unreadable);
			}
		}
		Set<Ecosystem> failed = java.util.EnumSet.noneOf(Ecosystem.class);
		for (Failure failure : of(jar)) failed.add(failure.ecosystem());
		return failed;
	}

	/**
	 * Turns the failures into findings, once discovery is complete and every jar has an owner.
	 *
	 * <p>{@code owner} is the family the boot decided loads the jar (MultiLoaderArbiter, after it set aside the
	 * families that failed here). If that family's own manifest failed, nothing of the jar loads: a confirmed,
	 * required finding, the same weight as a mod whose constructor threw. If it did not, a losing family's broken
	 * manifest costs nothing and is only noted. {@code carriers} are the kernel's own runtime jars; a failure there
	 * is a broken Forbric install, not a mod problem, and is reported as such.
	 */
	public static void recordFindings(java.util.function.Function<Path, Ecosystem> owner, Collection<Path> carriers) {
		recordFindings(owner, carriers, null, jar -> false);
	}

	/**
	 * As above, knowing where the player's jars are and which of them this boot leaves out on purpose.
	 *
	 * @param modsDir   the {@code mods/} directory: a refused MinecraftForge jar directly in it was installed by the
	 *                  player and is a confirmed, required loss; one anywhere else was bundled by another mod, whose
	 *                  parent still loads, and is only noted. {@code null}: every refused jar counts as installed.
	 * @param notLoaded jars the boot leaves out for its own reasons — the player's {@code forbric-disabled.txt}. Some
	 *                  passes (the merge report among them) still ask the arbiter about every file in {@code mods/}, so
	 *                  a switched-off MinecraftForge jar is judged too; the player already took it out, and asking them
	 *                  about it again, or stopping a server over it, would be wrong. Applied to refusals only.
	 */
	public static void recordFindings(java.util.function.Function<Path, Ecosystem> owner, Collection<Path> carriers,
			Path modsDir, java.util.function.Predicate<Path> notLoaded) {
		recordFindings(owner, carriers, modsDir, notLoaded, modId -> null);
	}

	/**
	 * As above, knowing which jar this boot loads for a mod id ({@code DuplicateModArbiter.loadedClaimant}).
	 *
	 * @param loadedFrom the jar that loads {@code modId}, or {@code null}. A refused MinecraftForge jar whose mod
	 *                   another jar supplies — the player put the NeoForge or Fabric build of the same mod beside it —
	 *                   costs nothing: the mod loads. It is a note naming the file the mod loads from, with no Lost row,
	 *                   and not a required finding, which would stop a dedicated server over a mod that loads and, being
	 *                   keyed by the shared id, mark the row of the build that loads as "not loaded". Before
	 *                   MinecraftForge was refused, the same pair was a duplicate the boot set aside without a word.
	 */
	public static void recordFindings(java.util.function.Function<Path, Ecosystem> owner, Collection<Path> carriers,
			Path modsDir, java.util.function.Predicate<Path> notLoaded,
			java.util.function.Function<String, Path> loadedFrom) {
		Set<Path> kernelJars = ConcurrentHashMap.newKeySet();
		for (Path carrier : carriers) kernelJars.add(key(carrier));
		for (Failure failure : all()) {
			if (failure.kind() == Kind.UNSUPPORTED) {
				if (!kernelJars.contains(failure.jar()) && !notLoaded.test(failure.jar())) {
					recordRefusal(failure, modsDir == null || key(modsDir).equals(failure.jar().getParent()),
							suppliedBy(failure, loadedFrom));
				}
				continue;
			}
			boolean kernel = kernelJars.contains(failure.jar());
			Ecosystem loads = kernel ? failure.ecosystem() : owner.apply(failure.jar());
			boolean lost = kernel || loads == null || failed(failure.jar(), loads);
			String file = failure.jar().getFileName().toString();
			String modId = kernel ? "forbric" : failure.modIds().isEmpty() ? "jar:" + file : failure.modIds().get(0);
			String detail = lost
					? (kernel ? "Forbric's own " + file + " has a manifest that cannot be read: " : "Its ")
							+ failure.manifest() + " cannot be read, so the mod is not loaded: " + failure.message()
					: "Its " + failure.manifest() + " cannot be read (" + failure.message() + "); it loads as "
							+ loads + " instead";
			if (lost && !kernel && LOST.stream().noneMatch(l -> l.jar().equals(failure.jar()))) {
				LOST.add(new Lost(failure.jar(), failure.ecosystem(), modId, detail));
			}
			CompatibilityFindings.record(new CompatibilityFinding(
					"metadata:" + failure.ecosystem().name().toLowerCase(java.util.Locale.ROOT), modId, "Mod metadata",
					"ForbricModDiscoverer " + failure.manifest(),
					lost ? CompatibilityFinding.Confidence.CONFIRMED : CompatibilityFinding.Confidence.SUSPECTED,
					lost, detail,
					List.of("jar=" + file, "manifest=" + failure.manifest(), "family=" + failure.ecosystem(),
							"loads=" + (loads == null ? "nothing" : loads), failure.message())));
		}
	}

	/**
	 * One refused MinecraftForge jar as a finding, and — when the player installed it — as a {@link Lost} row.
	 *
	 * <p>The id is {@value #UNSUPPORTED_FINDING} on the mod's own id, so the in-game prompt, the confirmation window, the
	 * Mods screen and the load report all attach it to the row of the mod the player recognises. Detail and feature are
	 * in the player's language ({@code DialogLang}, which {@code -Dforbric.dialogLanguage} also steers); the evidence
	 * stays machine words, so a bug report reads the same in any language.
	 */
	private static void recordRefusal(Failure failure, boolean installed, Path suppliedBy) {
		net.forbric.kernel.ui.DialogLang lang = net.forbric.kernel.ui.DialogLang.ofSystem();
		String file = failure.jar().getFileName().toString();
		String modId = failure.modIds().isEmpty() ? "jar:" + file : failure.modIds().get(0);
		String name = failure.name().isBlank() ? modId : failure.name();
		// Only a refusal that costs the player a mod is a loss: installed by them, and not supplied by another build.
		boolean loss = installed && suppliedBy == null;
		// A Fabric or NeoForge manifest that does not even parse is why this jar has no usable half: telling the player
		// to "get the Fabric build" of what may be the Fabric build would be wrong, so the broken file is named instead.
		LoaderHalfJudge.Verdict verdict = VERDICTS.get(failure.jar());
		String broken = verdict == null || verdict.unreadableHalves().isEmpty() ? null
				: verdict.unreadableHalves().get(0).manifest();
		String detail = suppliedBy != null
				? lang.get("unsupported.forge.superseded", name, file, String.valueOf(suppliedBy.getFileName()))
				: !installed ? lang.get("unsupported.forge.bundled", name, file)
				: broken != null ? lang.get("unsupported.forge.unreadable", name, file, broken)
				: lang.get("unsupported.forge.detail", name, file);
		if (suppliedBy != null) {
			ForbricLog.info("[Forbric/Unsupported] %s is the MinecraftForge build of %s, which loads from %s — left out "
					+ "without stopping anything", file, modId, suppliedBy.getFileName());
		}
		if (loss && LOST.stream().noneMatch(l -> l.jar().equals(failure.jar()))) {
			LOST.add(new Lost(failure.jar(), Ecosystem.FORGE, modId, name, detail, Kind.UNSUPPORTED));
		}
		CompatibilityFindings.record(new CompatibilityFinding(UNSUPPORTED_FINDING, modId,
				lang.get("unsupported.forge.feature"), "ForbricModDiscoverer " + failure.manifest(),
				loss ? CompatibilityFinding.Confidence.CONFIRMED : CompatibilityFinding.Confidence.SUSPECTED,
				loss, detail,
				List.of("jar=" + file, "manifest=" + failure.manifest(), "family=" + failure.ecosystem(),
						suppliedBy == null ? "loads=nothing" : "loads=" + suppliedBy.getFileName(),
						"unsupported=minecraftforge", installed ? "installed=mods" : "installed=bundled",
						failure.message())));
	}

	/**
	 * The jar this boot loads in place of a refused one: every id the refused jar declares is supplied by a jar that
	 * loads (a refused jar claims none itself), so the player loses nothing by its refusal. {@code null} when any id is
	 * left unsupplied, or the refusal named no id at all.
	 */
	private static Path suppliedBy(Failure failure, java.util.function.Function<String, Path> loadedFrom) {
		if (failure.modIds().isEmpty()) return null;
		Path first = null;
		for (String id : failure.modIds()) {
			Path jar = loadedFrom.apply(id);
			if (jar == null || key(jar).equals(failure.jar())) return null;
			if (first == null) first = jar;
		}
		return first;
	}

	/**
	 * The jars {@link #recordFindings} found nothing could load. A player installed each of them, so the Mods screen
	 * and the load report must list them as mods that did not load, not as problems that belong to no mod.
	 */
	public static List<Lost> lost() {
		return List.copyOf(LOST);
	}

	/** Called at the start of a boot, next to {@link CompatibilityFindings#reset()}, and by tests. */
	public static void reset() {
		FAILURES.clear();
		INSPECTED.clear();
		LOST.clear();
		VERDICTS.clear();
	}
}
