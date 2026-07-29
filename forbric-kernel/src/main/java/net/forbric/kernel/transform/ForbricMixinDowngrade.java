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

package net.forbric.kernel.transform;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.forbric.kernel.util.ForbricLog;

/**
 * Boot-safety net for GUEST Fabric mixin configs on the Forge-patched merged game base.
 *
 * <p>A Fabric mod's required injector (an {@code @Inject}/{@code @Redirect}/… with {@code require>0}, or the
 * config's {@code defaultRequire}) frequently matches 0 targets on the Forge-rewritten, refmap-less merged game
 * and throws a fatal {@code org.spongepowered.asm.mixin.injection.throwables.InjectionError} — crashing the
 * client to desktop during class transform. That error is an {@link Error} (not an {@code InvalidMixinException}),
 * so Mixin's own {@code IMixinErrorHandler} never sees it; it surfaces at the substrate's mixin-apply site
 * ({@code KnotClassDelegate.getPostMixinClassByteArray}, patch 0004) wrapped in a {@code MixinTransformerError}.
 *
 * <p>For configs the loader marked as guest-and-non-fatal — {@code -Dforbric.downgradeInjectionErrors}, a glob csv
 * computed in {@code ForbricBootstrap} from the discovered FABRIC mods on a Forge base — this SKIPS that class's
 * mixin transform (the class loads vanilla-shaped) with a warning instead of aborting the boot. Full functional
 * compat (adapting the guest mixin to the moved targets) is out of scope; the goal is "boots, inert, honest".
 *
 * <p>Scoped by config OWNERSHIP: only guest-Fabric configs are ever in the set. The forge/neoforge runtime,
 * {@code forbric*}, and wrapped Forge/NeoForge mod configs are NOT added, so a real failure there still crashes
 * loudly. Same glob contract as substrate patches 0007/0008 (trailing {@code *} = prefix glob, else exact).
 */
public final class ForbricMixinDowngrade {
	private ForbricMixinDowngrade() {
	}

	/** "… in &lt;config&gt;.mixins.json:&lt;MixinClass&gt; …" — capture the config name (non-space/non-colon, ends .json). */
	private static final Pattern CONFIG = Pattern.compile("([^\\s:]+\\.json):");

	/** Cap the cause-chain walk (defensive against a pathological/cyclic chain). */
	private static final int MAX_DEPTH = 32;

	/**
	 * Whether a mixin-apply failure for {@code className} should be swallowed (the class is loaded without its
	 * mixins). True only when the failure originates from Mixin AND names a config listed in
	 * {@code forbric.downgradeInjectionErrors}; false (crash normally) otherwise.
	 */
	public static boolean shouldSkip(String className, Throwable failure) {
		String csv = System.getProperty("forbric.downgradeInjectionErrors");
		if (csv == null || csv.isEmpty()) return false;

		String mixinType = null;
		String config = null;
		Throwable t = failure;
		for (int depth = 0; t != null && depth < MAX_DEPTH; t = t.getCause(), depth++) {
			if (mixinType == null && t.getClass().getName().startsWith("org.spongepowered.asm.mixin.")) {
				mixinType = t.getClass().getSimpleName();
			}
			if (config == null && t.getMessage() != null) {
				Matcher m = CONFIG.matcher(t.getMessage());
				if (m.find()) config = m.group(1);
			}
			if (mixinType != null && config != null) break;
		}

		// Not a Mixin-originated failure, or no config named -> can't scope it safely, so let it crash.
		if (mixinType == null || config == null) return false;
		// Config isn't a guest config the loader marked non-fatal -> stay strict (runtime/forbric/wrapped configs).
		if (!matches(config, csv)) return false;

		ForbricLog.warn("[Forbric] skipping guest mixin config '%s' on %s — %s on the Forge-patched base "
				+ "(class loads vanilla-shaped; install the mod's Forge/NeoForge build for full function)",
				config, className, mixinType);
		return true;
	}

	/** Glob csv match, identical contract to patches 0007/0008: trailing '*' = prefix glob, else exact. */
	private static boolean matches(String name, String csv) {
		for (String raw : csv.split(",")) {
			String entry = raw.trim();
			if (entry.isEmpty()) continue;
			if (entry.endsWith("*")) {
				if (name.startsWith(entry.substring(0, entry.length() - 1))) return true;
			} else if (name.equals(entry)) {
				return true;
			}
		}
		return false;
	}
}
