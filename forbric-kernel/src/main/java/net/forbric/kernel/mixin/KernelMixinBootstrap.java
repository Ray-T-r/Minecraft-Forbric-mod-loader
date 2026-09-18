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
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.FabricUtil;
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
	 * @param declared mixin config resource names, already filtered to the running side; de-duplicated here
	 */
	public static void init(ForbricClassLoader loader, EnvType side, List<String> declared) {
		if (initialized) throw new IllegalStateException("Mixin already bootstrapped");

		// Registering the same name twice is not a correctness problem — Mixins.registerConfiguration dedupes — but
		// it dedupes only AFTER Config.create has read and rewritten the JSON through
		// ForbricMixinService.getResourceAsStream, which re-runs KernelGuestMixinAdapter and re-emits every
		// "suppressed mixin" line. Now that two ecosystems feed this list, collapse it up front.
		List<String> configs = List.copyOf(new java.util.LinkedHashSet<>(declared));

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
		nameTheModsBehindTheConfigs();

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

	/** {@code off} leaves Mixin's own messages saying "(unknown)" where the mod id would go. */
	static final String DECORATION_PROPERTY = "forbric.mixinModIdDecoration";

	/**
	 * Tells Mixin which mod each config came from, so its OWN messages name it.
	 *
	 * <p>Mixin reads {@code FabricUtil.KEY_MOD_ID} off the config as a decoration and prints it in
	 * {@code "<config>:<mixin> from mod <id>"}. Nothing was setting it, so every one of those lines said
	 * {@code (unknown)} — which is the literal default {@code FabricUtil.getModId} falls back to. The kernel has
	 * always known the answer; it just never handed it over. This is the same thing fabric-loader's own
	 * MixinConfigDecorator does, minus its reflective probe for whether {@code decorate} exists, because the
	 * kernel pins its Mixin version.
	 *
	 * <p>Only {@code KEY_MOD_ID}. {@code KEY_COMPATIBILITY} defaults to a level that sets injector semantics, and
	 * changing that per mod is a separate decision needing its own evidence, not a free rider on a naming change.
	 *
	 * <p><b>This changes generated bytecode</b>, which is why it has a switch. {@code MethodMapper.getHandlerName}
	 * formats {@code prefix + "$" + uid + "$" + sourceId + name}, and {@code sourceId} falls through to exactly
	 * this decoration — so {@code handler$abc000$onFoo} becomes {@code handler$abc000$sodium$onFoo}. The two
	 * places the kernel matches on those names ({@code PostMixinFixups}) test {@code startsWith("handler$")} and
	 * are unaffected, but that is a fact about today's code rather than a guarantee.
	 *
	 * <p>A mod id that is not a legal Java identifier fragment is NOT decorated. It would be spliced straight
	 * into a method name, and a {@code .} or {@code ;} there produces a class that fails verification. Those keep
	 * saying "(unknown)", which is the outcome they had before this existed.
	 */
	private static void nameTheModsBehindTheConfigs() {
		if ("off".equalsIgnoreCase(System.getProperty(DECORATION_PROPERTY, "on"))) {
			ForbricLog.info("[Forbric/Mixin] -D%s=off — Mixin's own failures will say '(unknown)' where the mod "
					+ "name would be", DECORATION_PROPERTY);
			return;
		}

		int named = 0;
		int unnamed = 0;
		for (Config registered : Mixins.getConfigs()) {
			try {
				String modId = MixinConfigOwners.modIdOf(registered.getName());
				if (!decoratable(modId)) {
					unnamed++;
					continue;
				}
				IMixinConfig config = registered.getConfig();
				if (config == null || config.hasDecoration(FabricUtil.KEY_MOD_ID)) continue;
				config.decorate(FabricUtil.KEY_MOD_ID, modId);
				named++;
			} catch (Throwable t) {
				// Naming a failure must never become one. A config that cannot be decorated simply keeps the
				// placeholder it had.
				unnamed++;
				ForbricLog.debug("[Forbric/Mixin] could not name the mod behind %s: %s", registered.getName(),
						String.valueOf(t));
			}
		}
		ForbricLog.info("[Forbric/Mixin] %d of %d mixin config(s) now name their mod in Mixin's own messages; %d "
				+ "could not be attributed and keep saying '(unknown)'", named, named + unnamed, unnamed);
	}

	/**
	 * Whether this id can be spliced into a generated method name.
	 *
	 * <p>Mixin concatenates it into an identifier without escaping, so anything the JVM forbids in a method name
	 * -- {@code . ; [ / < >} -- would produce a class that fails verification at load. Refusing to decorate is
	 * the safe answer, and it lands the mod exactly where it was before.
	 */
	private static boolean decoratable(String modId) {
		if (modId == null || modId.isEmpty() || modId.length() > 64) return false;
		for (int i = 0; i < modId.length(); i++) {
			char c = modId.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| c == '_' || c == '-';
			if (!ok) return false;
		}
		return true;
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
