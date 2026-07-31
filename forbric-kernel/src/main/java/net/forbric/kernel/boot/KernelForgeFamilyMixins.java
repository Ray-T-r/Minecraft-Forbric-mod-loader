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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.forbric.kernel.metadata.ModEcosystem;
import net.forbric.kernel.mixin.MixinConfigPolicy;
import net.forbric.kernel.util.ForbricLog;

/**
 * Selects which Forge/NeoForge mixin configs to register — the Forge-family counterpart of
 * {@link KernelFabricEcosystem#mixinConfigs()}.
 *
 * <p>Discovery has always parsed these correctly (manifest {@code MixinConfigs} for MinecraftForge,
 * {@code [[mixins]]} in the toml for NeoForge) and they were then dropped on the floor: the only feed into
 * {@code KernelMixinBootstrap.init} walked the FABRIC loader's mod list, and a Forge-family mod is never in it. The
 * measured effect was that adding three mods shipping three configs left the count at "70 config(s) registered",
 * unchanged — so a Forge/NeoForge mod that works by mixin (which is most rendering mods) silently did nothing.
 *
 * <p>Arbitration is the first filter and not an optional one. A universal jar ships one manifest per family, so
 * {@code collective} declares both {@code collective_forge.mixins.json} and {@code collective_neoforge.mixins.json};
 * registering what discovery reports would inject the same mod's logic twice. {@link MultiLoaderArbiter} already
 * decides which family owns a jar for {@code @Mod} construction ({@code KernelModLoader}) and for Fabric
 * registration ({@code KernelFabricEcosystem}); the mixin path must ask the same question.
 */
public final class KernelForgeFamilyMixins {
	private KernelForgeFamilyMixins() {
	}

	/**
	 * One declared Forge-family mixin config: the resource name, the jar that declared it, and which family's
	 * manifest it came from.
	 */
	public record ForgeMixinConfig(String config, Path jar, ModEcosystem ecosystem) {
	}

	/** Whether the Forge-family mixin path is on. Default ON — the switch exists for bisecting, not for shipping. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.forgeFamilyMixins", "on"));
	}

	/**
	 * The configs to register, in declaration order, after arbitration / the master switch / the disable gate /
	 * de-duplication by name.
	 */
	public static List<String> select(List<ForgeMixinConfig> declared) {
		if (declared == null || declared.isEmpty()) return List.of();
		if (!enabled()) {
			ForbricLog.warn("[Forbric/Mixin] -Dforbric.forgeFamilyMixins=off — dropping all %d Forge-family mixin "
					+ "config(s); those mods' mixins will not apply", declared.size());
			return List.of();
		}

		List<String> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		int suppressed = 0;
		int disabled = 0;
		for (ForgeMixinConfig decl : declared) {
			MultiLoaderArbiter.Ecosystem mine = decl.ecosystem() == ModEcosystem.NEOFORGE
					? MultiLoaderArbiter.Ecosystem.NEOFORGE
					: MultiLoaderArbiter.Ecosystem.MINECRAFTFORGE;
			if (MultiLoaderArbiter.suppressedFor(decl.jar(), mine)) {
				suppressed++;
				ForbricLog.debug("[Forbric/Mixin] skipping %s — %s does not own %s", decl.config(), mine,
						decl.jar().getFileName());
				continue;
			}

			if (MixinConfigPolicy.isDisabled(decl.config())) {
				disabled++;
				ForbricLog.warn("[Forbric/Mixin] mixin config %s DISABLED — that module's mixins will not apply",
						decl.config());
				continue;
			}

			if (!seen.add(decl.config())) {
				ForbricLog.debug("[Forbric/Mixin] mixin config %s declared more than once — registering it once",
						decl.config());
				continue;
			}
			out.add(decl.config());
		}

		if (suppressed > 0 || disabled > 0) {
			ForbricLog.debug("[Forbric/Mixin] Forge-family mixin configs: %d kept, %d arbitrated away, %d disabled",
					out.size(), suppressed, disabled);
		}
		return List.copyOf(out);
	}

	/** How many of {@code declared} came from each family — for the boot summary line. */
	public static int count(List<ForgeMixinConfig> declared, List<String> selected, ModEcosystem family) {
		int n = 0;
		Set<String> counted = new LinkedHashSet<>();
		for (ForgeMixinConfig decl : declared) {
			if (decl.ecosystem() != family) continue;
			if (!selected.contains(decl.config())) continue;
			if (counted.add(decl.config())) n++;
		}
		return n;
	}
}
