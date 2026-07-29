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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.fabricmc.api.EnvType;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * Brings Mixin up on the sovereign kernel and installs the weaver as the LAST stage of the class pipeline.
 *
 * <p>Must run before any class a mixin targets is defined — in practice, before {@code PassiveSeeder} touches the
 * ecosystem carriers and long before the game's {@code Main}. The sequence mirrors what Fabric Loader's
 * {@code FabricMixinBootstrap} + {@code FabricLauncherBase.finishMixinBootstrapping} do, minus the loader:
 * bind the service, {@code MixinBootstrap.init()}, declare the side, register every config, then advance the
 * environment out of PREINIT so those configs are actually prepared.
 */
public final class KernelMixinBootstrap {
	private static volatile boolean initialized;

	private KernelMixinBootstrap() {
	}

	/** Whether Mixin was brought up (false when no mod declared a mixin config — the weaver is then never installed). */
	public static boolean isInitialized() {
		return initialized;
	}

	/**
	 * Initializes Mixin with {@code configs} and installs the weaver on {@code loader}.
	 *
	 * @param configs mixin config resource names, already filtered to the running side
	 */
	public static void init(ForbricClassLoader loader, EnvType side, List<String> configs) {
		if (initialized) throw new IllegalStateException("Mixin already bootstrapped");

		if (configs.isEmpty()) {
			ForbricLog.info("[Forbric/Mixin] no mixin configs declared — Mixin not started");
			return;
		}

		ForbricMixinService.bind(loader, side);

		// Every config here came from a discovered guest mod (the kernel authors no mixins), so relax them all:
		// guest mixins are written against vanilla bytecode and the base is byte-merged, so any of them can meet a
		// moved anchor. Without this, only `fabric-*` was relaxed and one unpatchable injector in any other real mod
		// (e.g. collective's collective_fabric.mixins.json) aborted the launch instead of soft-skipping.
		ForbricMixinService.setGuestConfigs(configs);

		// Echo the compatibility knobs. A mistyped config name in -Dforbric.suppressMixins silently does nothing,
		// which reads exactly like "the suppression did not help" — a trap worth one log line.
		String suppress = System.getProperty("forbric.suppressMixins");
		if (suppress != null && !suppress.isEmpty()) {
			ForbricLog.info("[Forbric/Mixin] suppression requested for: %s", suppress);
		}

		MixinBootstrap.init();
		MixinEnvironment.getDefaultEnvironment()
				.setSide(side == EnvType.CLIENT ? MixinEnvironment.Side.CLIENT : MixinEnvironment.Side.SERVER);

		// Before any config is parsed: MixinExtras registers the injection-point specifiers and annotations that
		// most of fabric-api's mixins use (@ModifyExpressionValue, MIXINEXTRAS:EXPRESSION, LocalRef sugar). Without
		// it those configs fail to even parse their injection points.
		initMixinExtras(loader);

		for (String config : configs) {
			Mixins.addConfiguration(config);
		}

		IMixinTransformer transformer = resolveTransformer();
		// Mixin is handed pre-mixin bytes, and null for a class in no owned jar — which is its class-GENERATION
		// request (org.spongepowered.asm.synthetic.*). transformClassBytes handles both. PostMixinFixups then repairs
		// the handful of classes a guest mixin wove wrong because the byte-merge restructured the target (e.g.
		// PackMixin's field initializer mis-woven into NeoForge's recursive Pack ctor).
		loader.setMixinTransformer((name, bytes) ->
				PostMixinFixups.apply(name, transformer.transformClassBytes(name, name, bytes)));

		// Leave PREINIT so the registered configs are prepared and their targets become weavable.
		gotoPhase(MixinEnvironment.Phase.INIT);
		gotoPhase(MixinEnvironment.Phase.DEFAULT);

		initialized = true;
		ForbricLog.info("[Forbric/Mixin] Mixin up on the sovereign kernel — %d config(s) registered, side %s",
				configs.size(), side);
	}

	/**
	 * Boots MixinExtras through the GAME loader.
	 *
	 * <p>It must be that loader: MixinExtras generates classes (the {@code LocalRef} machinery) that have to share
	 * a loader with the game classes they wrap, which is why {@code DelegationPolicy} pins
	 * {@code com.llamalad7.mixinextras.} to the game side and {@code KernelBundledJars} hands the jar to
	 * {@code ForbricClassLoader}. Calling {@code init()} from boot-side code would load a second, useless copy.
	 */
	private static void initMixinExtras(ForbricClassLoader loader) {
		try {
			Class<?> bootstrap = Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap", true, loader);
			bootstrap.getMethod("init").invoke(null);
			String version = String.valueOf(bootstrap.getMethod("getVersion").invoke(null));
			ForbricLog.info("[Forbric/Mixin] MixinExtras %s initialized (game-side)", version);
		} catch (ClassNotFoundException e) {
			ForbricLog.warn("[Forbric/Mixin] MixinExtras absent — mixins using @ModifyExpressionValue / LocalRef "
					+ "(most of fabric-api) will fail to parse their injection points");
		} catch (Throwable t) {
			throw new IllegalStateException("MixinExtras is present but failed to initialize", t);
		}
	}

	/**
	 * Advances the Mixin environment. {@code MixinEnvironment.gotoPhase} is a package-private static — there is no
	 * public way to leave PREINIT, and every Mixin host (Fabric Loader included) reaches it reflectively.
	 */
	private static void gotoPhase(MixinEnvironment.Phase phase) {
		try {
			Method m = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
			m.setAccessible(true);
			m.invoke(null, phase);
		} catch (Throwable t) {
			throw new IllegalStateException("could not advance the Mixin environment to " + phase, t);
		}
	}

	/**
	 * The weaver Mixin offered the service during {@code init()}. Mixin only calls {@code offer(...)} on some
	 * paths, so fall back to constructing the transformer directly — exactly what Fabric Loader does.
	 */
	private static IMixinTransformer resolveTransformer() {
		IMixinTransformer offered = ForbricMixinService.getTransformer();
		if (offered != null) return offered;

		try {
			@SuppressWarnings("unchecked")
			Constructor<IMixinTransformer> ctor = (Constructor<IMixinTransformer>)
					Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
			ctor.setAccessible(true);
			return ctor.newInstance();
		} catch (Throwable t) {
			throw new IllegalStateException("Mixin did not offer a transformer and one could not be constructed", t);
		}
	}
}
