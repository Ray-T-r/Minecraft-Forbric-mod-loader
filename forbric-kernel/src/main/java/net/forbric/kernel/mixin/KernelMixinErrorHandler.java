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

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.forbric.api.CompatibilityFinding;
import net.forbric.kernel.util.ForbricLog;

/**
 * Puts a mixin that failed to prepare or apply on its owning mod's row.
 *
 * <p>Mixin's own report of an apply failure names a mixin class and a target and nothing about which mod; its
 * {@link IMixinErrorHandler} SPI hands over the {@link IMixinInfo}, so the config name is read off it and the
 * owner comes from {@link MixinConfigOwners} — no log text is parsed. The incoming action is returned UNCHANGED:
 * attribution never alters Mixin's decision, so a required config still errors and an optional one still warns.
 *
 * <p>Registered by {@link KernelMixinBootstrap} through {@code Mixins.registerErrorHandlerClass}, which is why
 * this has a public no-argument constructor and is named by {@link #NAME} as a string. Mixin instantiates it
 * through the service's class provider, i.e. the game loader, which delegates kernel packages to the parent.
 *
 * <p>Not covered: a raw {@code InjectionError} — an {@link Error}, not an {@code InvalidMixinException} — bypasses
 * every error handler, and Mixin then abandons the whole target class. An injector's OWN {@code require} (or
 * {@code allow}) produces one whatever the config says; {@link MixinLocalsCapture#softenRequirements} lowers those
 * on relaxed guest mixins, so it is reachable only under {@code -Dforbric.mixinDiagnostics} (requirements kept
 * strict on purpose), {@code -Dforbric.requireFailSoft=off}, or in a config the kernel does not relax.
 *
 * <p>{@code -Dforbric.mixinErrorAttribution=off} skips the registration.
 */
public final class KernelMixinErrorHandler implements IMixinErrorHandler {
	public static final String NAME = "net.forbric.kernel.mixin.KernelMixinErrorHandler";
	public static final String PROPERTY = "forbric.mixinErrorAttribution";

	public KernelMixinErrorHandler() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
		record(config, mixin, "failed to prepare", th);
		return action;
	}

	@Override
	public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
		record(mixin == null ? null : mixin.getConfig(), mixin, "failed to apply to " + targetClassName, th);
		return action;
	}

	private static void record(IMixinConfig config, IMixinInfo mixin, String what, Throwable th) {
		String configName = config == null ? null : config.getName();
		String mixinName = mixin == null ? "?" : mixin.getClassName();
		String cause = th == null ? "" : " (" + th.getClass().getSimpleName() + ")";
		String modId = configName == null ? null : MixinConfigOwners.modIdOf(configName);
		String replacement = SupersededMixins.replacementFor(mixinName);
		if (replacement != null) {
			// Recorded like any loss, and resolved only once the replacement is seen in the class the game
			// defines (SupersededMixins.observeDefinition). The table entry alone is a claim, and a switched-off or
			// never-installed repair would otherwise report a real loss as handled.
			ForbricLog.info("[Forbric/Mixin] %s:%s %s%s — %s; its mod stays marked until that repair is seen in"
					+ " the defined class", configName == null ? "?" : MixinConfigOwners.describe(configName),
					mixinName, what, cause, replacement);
		} else {
			ForbricLog.warn("[Forbric/Mixin] %s:%s %s%s — Mixin's own report follows; the owning mod%s",
					configName == null ? "?" : MixinConfigOwners.describe(configName), mixinName, what, cause,
					modId == null ? " is not known, so no row is marked" : " " + modId + " is marked");
		}
		{
			// The reason, when the kernel worked one out while READING the mixin. "InvalidInjectionException" is
			// true and tells a player nothing; what the merge did to the target is the sentence worth carrying.
			String why = MixinOverloadPin.reasonFor(mixinName);
			String detail = why != null
					? "its mixin " + mixinName + " " + what + cause + " — " + why
					: "its mixin " + mixinName + " " + what + cause;
			boolean required = MixinCompatibility.required(configName, config != null && config.isRequired());
			MixinCompatibility.record(configName, mixinName, detail, CompatibilityFinding.Confidence.CONFIRMED,
					required, java.util.List.of(what, th == null ? "no exception supplied" : th.toString()));
		}
		if (replacement != null) SupersededMixins.awaitProof(configName, mixinName);
	}
}
