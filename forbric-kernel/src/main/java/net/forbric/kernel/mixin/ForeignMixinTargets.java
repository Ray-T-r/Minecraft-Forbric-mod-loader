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

package net.forbric.kernel.mixin;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import net.forbric.kernel.util.ForbricLog;

/**
 * Which merged-base classes a mixin config OTHER than the one being judged also targets.
 *
 * <p>{@link MixinFit} resolves a guest mixin's anchors against the MERGED BASE, and {@link KernelGuestMixinAdapter}
 * drops the mixin when none of them resolve ({@link MixinFit.Verdict#UNFIT}) on the reasoning that it "was going to
 * be dead weight". That reasoning has one blind spot, and it is exactly the inter-mod compatibility layer: a mixin
 * may deliberately target a member that <em>another mod's mixin adds at runtime</em>, which is not in the merged
 * base and never will be.
 *
 * <p>Measured, with Physics Mod on a Sodium + Iris instance — the two mixins it needs to integrate with them are the
 * two the adapter dropped:
 * <ul>
 *   <li>{@code physicsmod.mixins.json:sodium.MixinVertexTransform} injects
 *       {@code SpriteCoordinateExpander.transform}, which <b>Sodium</b> adds via its own
 *       {@code SpriteCoordinateExpanderMixin} (that mixin also makes the class implement {@code VertexBufferWriter}).
 *   <li>{@code physicsmod.mixins.json:liquid.MixinProgramManager} injects {@code VertexFormat.bindAttributesIris},
 *       which <b>Iris</b> adds via its {@code MixinVertexFormat}.
 * </ul>
 * Neither member exists in the merged base, so every anchor failed to resolve, both were auto-suppressed, and
 * Physics Mod lost its Sodium and Iris rendering integration silently — no error, just no effects.
 *
 * <p>So the adapter asks this class first: is some other registered config also mixing into that target? If it is,
 * the missing member may be that mod's contribution and the mixin is kept. Getting it wrong in this direction is
 * cheap — a guest config's injectors are relaxed ({@code injectors.defaultRequire -> 0}), so an anchor that really is
 * absent soft-skips, which is precisely what the mod's own {@code "defaultRequire": -1} already asks for. Getting it
 * wrong the other way deletes a working feature.
 *
 * <p>The index is built once, lazily — only a config that actually produced an UNFIT verdict pays for it — and reads
 * each config's mixin classes through the same resolver the adapter already uses, so the reads are cache hits.
 */
public final class ForeignMixinTargets {
	private static volatile Map<String, Set<String>> index;

	private ForeignMixinTargets() {
	}

	/** Forgets the built index. For tests, which register different config sets in one JVM. */
	static void reset() {
		index = null;
	}

	/**
	 * Whether any config other than {@code configName} mixes into one of {@code targets} (internal names).
	 *
	 * @param resource resolves a resource path — a config name, or {@code some/pkg/Name.class} — to its bytes
	 */
	public static boolean claimedByAnotherConfig(String configName, List<String> targets,
			Function<String, byte[]> resource) {
		if (targets == null || targets.isEmpty()) return false;

		Map<String, Set<String>> byTarget = index(resource);
		for (String target : targets) {
			Set<String> owners = byTarget.get(target);
			if (owners == null) continue;
			for (String owner : owners) {
				if (!owner.equals(configName)) return true;
			}
		}
		return false;
	}

	private static Map<String, Set<String>> index(Function<String, byte[]> resource) {
		Map<String, Set<String>> built = index;
		if (built != null) return built;

		synchronized (ForeignMixinTargets.class) {
			if (index != null) return index;

			Map<String, Set<String>> byTarget = new LinkedHashMap<>();
			int configs = 0;
			for (String config : ForbricMixinService.registeredConfigNames()) {
				byte[] json = resource.apply(config);
				if (json == null) continue;
				configs++;
				for (String target : targetsOf(json, resource)) {
					byTarget.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(config);
				}
			}

			ForbricLog.debug("[Forbric/Mixin] indexed %d mixin config(s) covering %d target class(es) — used to tell a "
					+ "cross-mod compatibility mixin from genuine dead weight", configs, byTarget.size());
			index = byTarget;
			return byTarget;
		}
	}

	/** Every class the mixins declared by one config's JSON target. Best-effort: an unreadable entry is skipped. */
	private static List<String> targetsOf(byte[] configJson, Function<String, byte[]> resource) {
		UnmodifiableConfig config;
		try (Reader reader = new InputStreamReader(new ByteArrayInputStream(configJson), StandardCharsets.UTF_8)) {
			config = JsonFormat.fancyInstance().createParser().parse(reader);
		} catch (RuntimeException | java.io.IOException notAMixinConfig) {
			return List.of();
		}

		Object pkg = config.get(List.of("package"));
		if (pkg == null) return List.of();
		String pkgPath = pkg.toString().replace('.', '/');
		if (pkgPath.isEmpty()) return List.of();

		Set<String> entries = new LinkedHashSet<>();
		addEntries(config.get(List.of("mixins")), entries);
		addEntries(config.get(List.of("client")), entries);
		addEntries(config.get(List.of("server")), entries);

		List<String> targets = new ArrayList<>();
		for (String mixin : entries) {
			byte[] bytes = resource.apply(pkgPath + "/" + mixin.replace('.', '/') + ".class");
			if (bytes == null) continue;
			try {
				targets.addAll(MixinFit.mixinTargets(MixinFit.parse(bytes)));
			} catch (RuntimeException unreadable) {
				// One unparseable mixin must not cost the whole config's contribution to the index.
			}
		}
		return targets;
	}

	private static void addEntries(Object value, Set<String> out) {
		if (!(value instanceof List<?> list)) return;
		for (Object element : list) {
			if (element instanceof String s && !s.isBlank()) out.add(s.trim());
		}
	}
}
