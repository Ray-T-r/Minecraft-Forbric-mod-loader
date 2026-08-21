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

package net.forbric.kernel.metadata.forge;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.util.ForbricLog;

/**
 * What version of each ecosystem this instance actually provides, and whether the mods believe it is enough.
 *
 * <p>WHY THIS EXISTS. A genuine NeoForge refuses to launch when a mod's {@code versionRange} on {@code neoforge}
 * is not satisfied, and says so by name. The kernel parses those ranges — {@code ForgeMetadataMapper} translates
 * every one of them into a Fabric predicate — and then nothing ever evaluates them, so an under-provisioned mod
 * loads and fails later, somewhere else, in a shape that names neither the mod nor the version.
 *
 * <p>Measured cost of not having this: JEI 30.14.0.87 declares {@code neoforge [26.2.0.16-beta,)} and the carrier
 * is {@code 26.2.0.7-beta}. It loaded. Nine releases of drift later, {@code NeoForgeGuiPlugin} died on
 * {@code NoClassDefFoundError: net/neoforged/neoforge/common/extensions/TooltipFlagExtension} — an interface that
 * genuinely does not exist in .7, where those methods are inlined on {@code TooltipFlag} instead. Tracing that
 * back to a version range took a disassembler. One warning line would have said it.
 *
 * <p>It WARNS rather than rejects, deliberately. Rejecting is what a genuine loader does and would be the more
 * faithful choice, but it is also a decision to eject mods from a running pack, and that belongs to whoever
 * assembled the pack — not to a diagnostic. The warning names the mod, the requirement and the reality; acting on
 * it is a separate call.
 */
public final class EcosystemVersions {
	/** The ecosystem mod ids a carrier can claim, and the only ones a range is checked against. */
	private static final List<String> ECOSYSTEMS = List.of("neoforge", "forge", "minecraft");

	private static final Map<String, String> PROVIDED = new LinkedHashMap<>();

	private EcosystemVersions() {
	}

	/** Forgets what was recorded. For tests. */
	static void reset() {
		synchronized (PROVIDED) {
			PROVIDED.clear();
		}
	}

	/** The version this instance provides for {@code modId}, or {@code null} if no carrier claimed it. */
	public static String provided(String modId) {
		synchronized (PROVIDED) {
			return PROVIDED.get(modId);
		}
	}

	/**
	 * Records the ecosystem versions the runtime carriers declare, by reading each carrier's own
	 * {@code mods.toml} / {@code neoforge.mods.toml} — the same file a genuine loader reports its version from.
	 *
	 * <p>Best-effort: an unreadable carrier simply contributes nothing, and an ecosystem with no recorded version
	 * is never audited rather than being audited against a guess.
	 */
	public static void record(List<Path> runtimeJars) {
		if (runtimeJars == null) return;

		for (Path jar : runtimeJars) {
			for (String manifest : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
				readInto(jar, manifest);
			}
		}
		synchronized (PROVIDED) {
			if (!PROVIDED.isEmpty()) {
				ForbricLog.info("[Forbric/Versions] this instance provides %s — a mod's versionRange is checked "
						+ "against these, so an under-provisioned mod says so at load instead of failing later "
						+ "somewhere that names neither it nor the version", PROVIDED);
			}
		}
	}

	/**
	 * The version to audit against, or {@code null} for one we must not audit against.
	 *
	 * <p>A carrier's {@code mods.toml} can still hold the Gradle placeholder its build was meant to substitute —
	 * the MinecraftForge carrier's says {@code ${global.forgeVersion}} verbatim. Recording that produces confident
	 * nonsense: the first run of this audit compared {@code shogi_api}'s honest {@code forge [63.0.2,)} against the
	 * literal string and accused it of requiring something newer than we provide. An unresolved placeholder is not
	 * a low version, it is an ABSENT one, and the only correct thing to do with it is decline to judge.
	 */
	private static String usableVersion(String raw) {
		if (raw == null) return null;

		String version = raw.trim();
		if (version.isEmpty() || version.contains("${")) return null;
		return version;
	}

	private static void readInto(Path jar, String manifestPath) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(manifestPath);
			if (entry == null) return;

			ForgeModsToml toml;
			try (InputStream in = zip.getInputStream(entry)) {
				toml = ModsTomlParser.parse(in);
			}
			for (ForgeModEntry mod : toml.getMods()) {
				if (!ECOSYSTEMS.contains(mod.getModId())) continue;

				String version = usableVersion(mod.getVersion());
				if (version == null) continue;
				synchronized (PROVIDED) {
					PROVIDED.putIfAbsent(mod.getModId(), version);
				}
			}
		} catch (Throwable unreadable) {
			ForbricLog.debug("[Forbric/Versions] could not read %s from %s: %s", manifestPath, jar.getFileName(),
					String.valueOf(unreadable));
		}
	}

	/**
	 * Warns for each MANDATORY dependency in {@code toml} that names an ecosystem this instance provides at a
	 * version outside the declared range. Optional dependencies are left alone: a mod that declares one has already
	 * said it can do without.
	 */
	public static void audit(ForgeModsToml toml, String source) {
		if (toml == null) return;

		for (ForgeModEntry mod : toml.getMods()) {
			for (ForgeDependency dep : mod.getDependencies()) {
				if (!dep.isMandatory()) continue;

				String have = provided(dep.getModId());
				if (have == null) continue; // not an ecosystem we claim to provide — not ours to judge
				if (ForgeVersionRange.satisfies(dep.getVersionRange(), have)) continue;

				ForbricLog.warn("[Forbric/Versions] %s requires %s %s but this instance provides %s (%s) — it is "
						+ "being loaded anyway, and a genuine loader would have refused; expect it to fail on "
						+ "whatever the newer version added", mod.getModId(), dep.getModId(),
						dep.getVersionRange(), have, source);
			}
		}
	}
}
