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
import org.spongepowered.asm.mixin.transformer.ext.Extensions;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ModContainer;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.util.Caught;
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
		// A read of a MinecraftForge-routed field from another class goes through the getter only where the base's own
		// class has it in the shape that answers for every constructor; NativeCoremodParity reads that class from here.
		net.forbric.kernel.transform.NativeCoremodParity.readGameClassesFrom(gameClasses(loader));

		if (configs.isEmpty()) {
			ForbricLog.info("[Forbric/Mixin] no mixin configs declared — Mixin not started");
			// NeoForge's coremods still apply; they are the stage after Mixin, not part of it.
			loader.setMixinTransformer(net.forbric.kernel.transform.NativeCoremodParity::apply);
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
		// The kernel's injection point that counts a guest's point over its platform's own body (NativeOccurrences hands
		// guest points to it); registered before any config is parsed, like MixinExtras' points.
		org.spongepowered.asm.mixin.injection.InjectionPoint.register(NativeOccurrencePoint.class, NativeOccurrencePoint.NAMESPACE);

		for (String config : configs) {
			Mixins.addConfiguration(config);
		}
		nameTheModsBehindTheConfigs();
		giveFabricConfigsTheirCompatibilityLevel();
		configsRegistered = true;
		// A mixin that fails to prepare or apply names its mod on the Mods screen and in load-report.txt. Mixin
		// instantiates the handler by name through the service's class provider when the first error happens.
		if (KernelMixinErrorHandler.enabled()) {
			Mixins.registerErrorHandlerClass(KernelMixinErrorHandler.NAME);
		}

		IMixinTransformer transformer = resolveTransformer();
		// Before the first class is woven: Mixin picks its active extensions when it first prepares the configs, and one
		// added after that sits out until it prepares again.
		if (transformer.getExtensions() instanceof Extensions extensions) {
			extensions.add(new MixinCaptureLanding());
			// MixinExtras registered its late applicator above (initMixinExtras); one guest's late injector failing must
			// cost that injection, not every mod's mixins on the class.
			LateInjectionIsolation.install(extensions, loader);
		} else {
			ForbricLog.warn("[Forbric/Mixin] Mixin's extension registry is a %s, not Mixin's own: a moved locals capture "
					+ "gets no NOP to land on, and at 0.9.2 Mixin may skip it; a late injector that fails costs its target "
					+ "class every mixin", String.valueOf(transformer.getExtensions()));
		}
		// Mixin is handed pre-mixin bytes, and null for a class in no owned jar — which is its class-GENERATION
		// request (org.spongepowered.asm.synthetic.*). transformClassBytes handles both. PostMixinFixups then repairs
		// the handful of classes a guest mixin wove wrong because the byte-merge restructured the target (e.g.
		// PackMixin's field initializer mis-woven into NeoForge's recursive Pack ctor).
		// Two unrelated interfaces each supplying the same default is an error the IMPLEMENTOR must settle, and
		// only after weaving is the second interface on the class at all — so this is the one point where it can
		// be seen. See InterfaceDefaultConflictRepair.
		var conflicts = new net.forbric.kernel.transform.InterfaceDefaultConflictRepair(internalName -> {
			try (var in = loader.getResourceAsStream(internalName + ".class")) {
				return in == null ? null : in.readAllBytes();
			} catch (Throwable unreadable) {
				Caught.warn(unreadable, "[Forbric/Mixin] could not read %s; its interface default conflicts are not "
						+ "settled", internalName);
				return null;
			}
		});
		// NativeCoremodParity directly after Mixin, where NeoForge runs its own coremods: a mixin aimed at the vanilla
		// field read or finalizeSpawn call still binds, and what a mixin adds to those classes is rewritten too.
		// The transformer is read from MixinWeaverSlot per class rather than captured: a guest that wraps the weaver
		// the way NeoForge lets it (LibJF's ASM layer) must be the one that weaves from then on. With no wrapper it
		// is `transformer`, one volatile read away.
		MixinWeaverSlot.install(transformer);
		// Knot's delegate.mixinTransformer on the loader is a view of that slot too, seeded before any plugin can read it
		// (Mixin constructs config plugins when it first selects, on the first class woven below). See MixinPlatformIdentity.
		loader.knotDelegate().attach();
		var codecReturns = new net.forbric.kernel.transform.PostMixinCodecReturnArbitration();
		var callbackPriority = new net.forbric.kernel.transform.PostMixinCallbackPriority();
		// The transfer audit proves an interface-default delegate against the same raw game classes the merged-base
		// repair read when it dropped the ones it could prove (KernelBoot hands it this lookup).
		java.util.function.Function<String, byte[]> gameClasses = gameClasses(loader);
		// UncalledValueRelay after the fixups: a vanilla method the merged game never calls, into which guest hooks were woven, gets
		// a relay of its woven body the carrier's own place runs (Block.tryDropExperience, from NeoForge's drops event). It needs
		// the bytes Mixin was handed, to tell what was woven.
		//
		// One step per line, in the order they run. The transfer audit is MinecraftForge's (it certifies the shapes its
		// transfer interop was reviewed against) and runs last, on the finished bytes, as it always has; it is its own
		// line rather than the call wrapped around all the others, so it goes with MinecraftForge as a line, and nothing
		// that serves NeoForge or Fabric has to be unwrapped from it.
		loader.setMixinTransformer((name, bytes) -> {
			byte[] woven = net.forbric.kernel.transform.NativeCoremodParity.apply(name,
					MixinWeaverSlot.currentOr(transformer).transformClassBytes(name, name, bytes));
			woven = PostMixinFixups.apply(name, woven);
			woven = UncalledValueRelay.apply(name, bytes, woven);
			woven = conflicts.transform(name, bytes, woven);
			woven = codecReturns.transform(name, woven, null);
			woven = callbackPriority.transform(name, woven, null);
			woven = net.forbric.kernel.transform.ForgeTransferShapeAudit.certify(name, woven, gameClasses);
			return woven;
		});

		// Leave PREINIT so the registered configs are prepared and their targets become weavable.
		gotoPhase(MixinEnvironment.Phase.INIT);
		gotoPhase(MixinEnvironment.Phase.DEFAULT);

		initialized = true;
		ForbricLog.info("[Forbric/Mixin] Mixin up on the sovereign kernel — %d config(s) registered, side %s",
				configs.size(), side);
	}

	/** The game's raw class files by resource path ({@code a/b/C.class}), as the base has them; null when absent. */
	private static java.util.function.Function<String, byte[]> gameClasses(ForbricClassLoader loader) {
		return path -> {
			try (var in = loader.getGameResourceAsStream(path)) {
				return in == null ? null : in.readAllBytes();
			} catch (java.io.IOException unreadable) {
				Caught.warn(unreadable, "[Forbric/Mixin] could not read game resource %s; taken as absent", path);
				return null;
			}
		};
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
	 * <p>Only {@code KEY_MOD_ID}. {@code KEY_COMPATIBILITY} sets injector semantics and has its own switch and its own
	 * step, {@link #giveFabricConfigsTheirCompatibilityLevel}, rather than riding along on a naming change.
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
				Caught.warn(t, "[Forbric/Mixin] could not name the mod behind %s", registered.getName());
			}
		}
		ForbricLog.info("[Forbric/Mixin] %d of %d mixin config(s) now name their mod in Mixin's own messages; %d "
				+ "could not be attributed and keep saying '(unknown)'", named, named + unnamed, unnamed);
	}

	/**
	 * {@code fabric} (default): the level Fabric Loader gives each mod; {@code latest}: every Fabric mod on the bundled
	 * Mixin's newest behaviour, as before the level was set at all.
	 */
	static final String COMPATIBILITY_PROPERTY = "forbric.mixinCompat";

	/**
	 * The level a Fabric config is given under {@code mode}, or {@code null} to leave it undecorated (Mixin's newest).
	 *
	 * <p>No floor. Fabric answers 0.9.2 when the lowest accepted {@code fabricloader} is older than 0.12.0 or there is no
	 * lower bound at all, and the kernel's moved TAIL captures hold there too. That walk reads them differently, and
	 * {@link MixinCaptureLanding} gives them a real instruction to land on. The exception is a method where another mixin
	 * shifts a point {@code BY}, which keeps its body, so at 0.9.2 the capture is skipped there with a WARN (a moved
	 * {@code CAPTURE_FAILEXCEPTION} is softened to that, not left to throw).
	 */
	static Integer compatibilityLevel(java.util.Collection<? extends net.fabricmc.loader.api.metadata.ModDependency> dependencies,
			String mode) {
		if ("latest".equalsIgnoreCase(mode)) return null;
		return FabricMixinCompat.levelFor(dependencies);
	}

	/** Whether {@link #init} has registered and decorated every config with Mixin. */
	private static volatile boolean configsRegistered;

	/**
	 * The Mixin compatibility level {@code configName}'s mixins are woven at: its decoration when Mixin has the config
	 * decorated, else the level {@link #giveFabricConfigsTheirCompatibilityLevel} gives it (the same rule, so a judgement
	 * made before Mixin's INIT agrees with the weave), else Mixin's newest — every Forge-family config, a config nobody
	 * owns, and every config under {@code -Dforbric.mixinCompat=latest}.
	 *
	 * <p>For a mechanism that predicts what Mixin will do with an injector: what Mixin does at a point (which stores a
	 * {@code @ModifyVariable} finds, which local an ordinal picks) changes with this level, mod by mod.
	 */
	public static int wovenLevel(String configName) {
		if (configName == null) return FabricUtil.COMPATIBILITY_LATEST;
		String mode = System.getProperty(COMPATIBILITY_PROPERTY, "fabric");
		if ("latest".equalsIgnoreCase(mode)) return FabricUtil.COMPATIBILITY_LATEST;
		try {
			// Mixin's registry only once this bootstrap has filled it: before that (a judgement at config registration, a
			// unit test without Mixin) asking it would start Mixin's services, and the decoration is not there yet anyway.
			if (configsRegistered) for (Config registered : Mixins.getConfigs()) {
				if (!configName.equals(registered.getName())) continue;
				IMixinConfig config = registered.getConfig();
				if (config == null) break;
				// Undecorated, Mixin weaves it at its newest, whatever the metadata would have given it.
				return config.hasDecoration(FabricUtil.KEY_COMPATIBILITY) ? FabricUtil.getCompatibility(config)
						: FabricUtil.COMPATIBILITY_LATEST;
			}
			if (MixinConfigOwners.ecosystemOf(configName) != Ecosystem.FABRIC) return FabricUtil.COMPATIBILITY_LATEST;
			KernelFabricLoader fabric = KernelFabricLoader.getInstanceOrNull();
			String modId = MixinConfigOwners.modIdOf(configName);
			ModContainer mod = fabric == null || modId == null ? null : fabric.getModContainer(modId).orElse(null);
			Integer level = mod == null ? null : compatibilityLevel(mod.getMetadata().getDependencies(), mode);
			return level == null ? FabricUtil.COMPATIBILITY_LATEST : level;
		} catch (RuntimeException | LinkageError unreadable) {
			Caught.warn(unreadable, "[Forbric/Mixin] could not tell which level %s is woven at; it is taken as Mixin's newest",
					configName);
			return FabricUtil.COMPATIBILITY_LATEST;
		}
	}

	/**
	 * Gives each Fabric mod's mixin configs the Mixin compatibility level Fabric Loader gives them
	 * ({@link FabricMixinCompat}): the behaviour of the Mixin the mod was built against, read from the lowest Fabric
	 * Loader version it accepts.
	 *
	 * <p>Before this, nothing set it, and Mixin reads an undecorated config as its newest level — so every Fabric
	 * mod ran with injector semantics newer than those it was tested with on Fabric: the order initialisers and
	 * injectors are applied in, how a local variable is picked out, where a shifted point lands. About half the
	 * Fabric mods in the test corpora declare a lower bound that Fabric answers with an older level.
	 *
	 * <p>Fabric-owned configs only. The Forge-family configs are left as they were: NeoForge's FML decorates its own
	 * with a declared {@code behaviorVersion}, and refuses one below 0.17.1 at load, so every level it can give behaves
	 * as the bundled Mixin's newest, which an undecorated config already gets. Before {@code gotoPhase(INIT)}, like the
	 * mod id: Mixin reads the level while it prepares each mixin, and a level set afterwards is read by nobody.
	 */
	private static void giveFabricConfigsTheirCompatibilityLevel() {
		String mode = System.getProperty(COMPATIBILITY_PROPERTY, "fabric");
		if ("latest".equalsIgnoreCase(mode)) {
			ForbricLog.info("[Forbric/Mixin] -D%s=latest — every Fabric mod's mixins run with Mixin's newest "
					+ "behaviour, not the level Fabric Loader would give them", COMPATIBILITY_PROPERTY);
			return;
		}
		KernelFabricLoader fabric = KernelFabricLoader.getInstanceOrNull();
		if (fabric == null) return;

		java.util.Map<Integer, Integer> levels = new java.util.TreeMap<>();
		for (Config registered : Mixins.getConfigs()) {
			try {
				if (MixinConfigOwners.ecosystemOf(registered.getName()) != Ecosystem.FABRIC) continue;
				String modId = MixinConfigOwners.modIdOf(registered.getName());
				ModContainer mod = modId == null ? null : fabric.getModContainer(modId).orElse(null);
				IMixinConfig config = registered.getConfig();
				if (mod == null || config == null || config.hasDecoration(FabricUtil.KEY_COMPATIBILITY)) continue;

				Integer level = compatibilityLevel(mod.getMetadata().getDependencies(), mode);
				if (level == null) continue;
				config.decorate(FabricUtil.KEY_COMPATIBILITY, level);
				levels.merge(level, 1, Integer::sum);
			} catch (Throwable t) {
				// The newest level is what an undecorated config already had: a config that cannot be read keeps it.
				Caught.warn(t, "[Forbric/Mixin] could not give %s its Fabric compatibility level", registered.getName());
			}
		}
		if (!levels.isEmpty()) {
			ForbricLog.info("[Forbric/Mixin] Fabric mixin configs by compatibility level, Fabric Loader's per mod "
					+ "(level=configs): %s (-D%s=latest puts them all on Mixin's newest)", levels, COMPATIBILITY_PROPERTY);
		}
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
			Caught.warn(e, "[Forbric/Mixin] MixinExtras absent — mixins using @ModifyExpressionValue / LocalRef "
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
