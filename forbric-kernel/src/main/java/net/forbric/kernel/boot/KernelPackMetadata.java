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

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.util.ForbricLog;

/**
 * Decides whether a {@code pack.mcmeta} section that will not parse is fatal to the WHOLE pack.
 *
 * <p>On a normal instance exactly one loader is running, so a pack only ever meets the metadata parsers that
 * loader ships. On Forbric all three are running at once, and a multiloader mod's {@code pack.mcmeta} carries a
 * section per loader — so a Fabric-only build gets read by NeoForge's parser, which then resolves things only a
 * NeoForge build would have registered.
 *
 * <p>Observed on two real mods. {@code lithostitched} and {@code Terralith} are Fabric-only builds whose
 * {@code pack.mcmeta} ships BOTH a {@code fabric:overlays} and a {@code neoforge:overlays} section (one source
 * tree, one metadata file, every platform's section in it). The NeoForge section names its overlay conditions by
 * type — {@code "type": "lithostitched:breaks_seed_parity"} — and NeoForge's parser resolves those against the
 * {@code neoforge:condition_codecs} registry. The Fabric build never registers there; its condition class
 * {@code implements net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition} and lands in Fabric's
 * registry instead. So the lookup misses and the codec throws.
 *
 * <p>The damage is out of all proportion to the cause. {@code ResourceMetadata}'s JSON-backed {@code getSection}
 * hard-throws through {@code DataResult.getOrThrow}, and {@code Pack.readPackMetadata} wraps its whole body in
 * {@code catch (Exception) -> LOGGER.warn("Failed to read pack {} metadata") -> return null}, which
 * {@code readMetaAndCreate} turns into "no pack". **One unparseable optional section silently deletes the entire
 * pack.** Both mods vanished from the ResourceManager; lithostitched's {@code template_list} data never loaded;
 * {@code TemplateLists.getRandom} called {@code Optional.get()} on the resulting empty registry; and ruined-portal
 * chunk generation died with a {@code ReportedException} about twelve seconds after the player joined. The user
 * reported it as the game freezing, because that is what a crash looks like from inside the twelve seconds.
 *
 * <p>The rule here restores what the pack was actually built and tested against: <b>a section this instance
 * cannot parse is treated as ABSENT, exactly as a loader with no parser for it would treat it.</b> Skipping
 * {@code neoforge:overlays} on a Fabric mod is not a compromise — it is precisely what stock Fabric does, and the
 * mod ships {@code fabric:overlays} alongside it for that reason.
 *
 * <p>The test is whether the section name contains a colon, NOT whether its namespace is {@code minecraft}.
 * Vanilla's own sections are not namespaced at all — they are the bare names {@code pack}, {@code overlays} and
 * {@code features} — so a namespace check would have failed open on the very sections that must stay loud. A
 * colon means some loader or mod defined the section, and only those fail soft; corruption in {@code pack} still
 * throws, and the original exception propagates unwrapped so the crash report is unchanged.
 *
 * <p>Escape hatches via {@code -Dforbric.packMetadataFailSoft}: {@code off} restores vanilla's
 * everything-throws behaviour, {@code all} extends fail-soft to vanilla-named sections too. {@code all} exists
 * for one known residual — a pack whose BARE {@code overlays} section carries {@code neoforge:conditions} would
 * still be dropped by the default rule, because {@code overlays} is a name vanilla owns. No pack in the observed
 * mod sets does that, and widening the default pre-emptively is how the over-broad generalisations documented in
 * {@code MixinFit.Result#shouldSuppress} got their start.
 */
public final class KernelPackMetadata {
	private static final String PROPERTY = "forbric.packMetadataFailSoft";

	/** Section names already reported. getSection runs once per pack per section, so without this every pack repeats it. */
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	/** {@code MetadataSectionType.name()}, resolved once. The type is a record, so the accessor is public. */
	private static volatile Method nameAccessor;

	private KernelPackMetadata() {
	}

	/**
	 * {@code -Dforbric.packMetadataFailSoft}, read per call rather than cached in a {@code static final}.
	 *
	 * <p>An escape hatch nobody can exercise is an escape hatch that rots; this seam is what lets the tests drive
	 * all three modes in one JVM.
	 */
	static String mode() {
		String raw = System.getProperty(PROPERTY);
		return raw == null || raw.isBlank() ? "on" : raw.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Whether a failure to parse {@code sectionName} should be swallowed.
	 *
	 * <p>A {@code null} name (the accessor could not be reached) fails soft: a name we cannot read cannot be one of
	 * the three bare vanilla names we are protecting.
	 */
	static boolean failSoft(String sectionName) {
		String mode = mode();
		if ("off".equals(mode)) return false;
		if ("all".equals(mode)) return true;
		return sectionName == null || sectionName.indexOf(':') >= 0;
	}

	/**
	 * The hook the transformed {@code ResourceMetadata$…getSection} calls from its catch block.
	 *
	 * <p>Rethrows {@code failure} untouched for a vanilla section — same exception object, so the crash report is
	 * byte-identical to an untransformed game. Otherwise answers {@code Optional.empty()}, which is what
	 * {@code getSection} already answers for a section that is not in the file at all.
	 *
	 * <p>{@code sectionType} is typed {@code Object} because this class is BOOT-side and cannot name
	 * {@code net.minecraft} types at compile time — the same widening-reference trick, and the same reason, as
	 * {@code ClientPackHookInjector.HOOK_DESC}.
	 */
	public static Optional<Object> sectionFailed(Object sectionType, RuntimeException failure) {
		String name = nameOf(sectionType);
		if (!failSoft(name)) throw failure;

		if (REPORTED.add(name == null ? "?" : name)) {
			ForbricLog.warn("[Forbric/PackMeta] pack.mcmeta section '%s' would not parse (%s) — treating it as absent, "
					+ "as a loader without a parser for that section would. The pack itself still loads; unguarded, one "
					+ "unparseable optional section drops the WHOLE pack (-D%s=off to restore)",
					name, String.valueOf(failure), PROPERTY);
		}
		return Optional.empty();
	}

	/** The section's name, or {@code null} when it cannot be read. Never throws — this runs on a failure path. */
	private static String nameOf(Object sectionType) {
		if (sectionType == null) return null;
		try {
			Method accessor = nameAccessor;
			if (accessor == null || !accessor.getDeclaringClass().isInstance(sectionType)) {
				accessor = sectionType.getClass().getMethod("name");
				try {
					accessor.setAccessible(true);
				} catch (RuntimeException notPermitted) {
					// A public accessor on a public record; if the module system says otherwise, invoke() still works.
				}
				nameAccessor = accessor;
			}
			return accessor.invoke(sectionType) instanceof String name ? name : null;
		} catch (Throwable unreadable) {
			return null;
		}
	}

	/** Forgets which sections have been reported — for tests, and so a re-launch in one process reports again. */
	static void reset() {
		REPORTED.clear();
		nameAccessor = null;
	}
}
