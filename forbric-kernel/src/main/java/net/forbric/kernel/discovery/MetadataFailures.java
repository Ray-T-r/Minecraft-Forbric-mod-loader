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
 */
public final class MetadataFailures {
	/** One manifest that could not be read. {@code modIds} is empty when the file did not even parse. */
	public record Failure(Path jar, Ecosystem ecosystem, String manifest, List<String> modIds, String message) {
		public Failure {
			jar = key(jar);
			modIds = List.copyOf(modIds);
		}
	}

	/** A jar nothing could load because its loading family's manifest failed; the catalogue gives it a row. */
	public record Lost(Path jar, Ecosystem ecosystem, String modId, String detail) {
	}

	private static final Map<Path, Map<Ecosystem, Failure>> FAILURES = new ConcurrentHashMap<>();
	private static final Set<Path> INSPECTED = ConcurrentHashMap.newKeySet();
	private static final List<Lost> LOST = new java.util.concurrent.CopyOnWriteArrayList<>();

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
		if (previous == null) {
			ForbricLog.warn("[Forbric] could not read %s in %s: %s — the %s side of this jar is not loaded",
					failure.manifest(), failure.jar().getFileName(), failure.message(), failure.ecosystem());
		}
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
		Set<Path> kernelJars = ConcurrentHashMap.newKeySet();
		for (Path carrier : carriers) kernelJars.add(key(carrier));
		for (Failure failure : all()) {
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
	}
}
