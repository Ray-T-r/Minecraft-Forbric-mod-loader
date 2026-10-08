/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop.protocol.sodium;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.forbric.api.*;
import net.forbric.kernel.boot.KernelLifecycle;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.transform.*;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/** Sodium's published config entrypoint protocol, independent of the kernel lifecycle. */
public final class SodiumProtocolExtension implements ProtocolExtension {
    @Override public String id() { return "sodium-config"; }
    @Override public void registerTransformers(Context context, Transforms transforms) {
        if (context.side() != Side.CLIENT) return;
        transforms.register("COREMOD", "entrypoint-collection", net.forbric.kernel.interop.protocol.ProtocolTransformAdapters.transform(new EntrypointCollectionBridgeInjector(
            new EntrypointCollectionBridgeInjector.Contract(SODIUM_CONFIG_USER_KEY, SODIUM_CONFIG_MANAGER,
                "registerConfigEntryPoint", "setModInfoFunction",
                new EntrypointCollectionBridgeInjector.Hook(SodiumProtocolExtension.class.getName(), "collectForeignDeclarations"),
                new EntrypointCollectionBridgeInjector.Hook(SodiumProtocolExtension.class.getName(), "metadataProvider")))));
    }
	/**
	 * The key both ecosystems spell the same, in different files: a Fabric entrypoint in {@code fabric.mod.json},
	 * a {@code [modproperties.<id>]} entry in {@code neoforge.mods.toml}. Not a {@link ForeignType} — it is one
	 * literal owned by Sodium, not a concept with a twin under each Forge family.
	 */
	private static final String SODIUM_CONFIG_USER_KEY = "sodium:config_api_user";
	private static final String SODIUM_CONFIG_MANAGER = "net.caffeinemc.mods.sodium.client.config.ConfigManager";

	/**
	 * Called from the end of Sodium's {@code ConfigLoaderForge.collectConfigEntryPoints}: registers the Fabric
	 * mods that declared a Sodium config entry point, which that method structurally cannot see.
	 *
	 * <p>Sodium's NeoForge build finds its config users two ways, both NeoForge-only — it walks
	 * {@code ModList.getMods()} reading {@code sodium:config_api_user} out of each {@code getModProperties()}, and
	 * it walks {@code ModList.getAllScanData()} for {@code @ConfigEntryPointForge}. A Fabric mod declares the same
	 * thing as a Fabric ENTRYPOINT, has no {@code IModInfo}, and is not in {@code ModList} at all, so neither walk
	 * reaches it. On this instance that was voxy: the page simply did not exist in Video Settings, with no warning
	 * anywhere, because nothing had looked. (iris was a different defect with the same symptom — it declares the
	 * property in its own {@code neoforge.mods.toml} and the kernel was returning an empty map for it.)
	 *
	 * <p>Only the mod id and the DECLARED class name cross over. Sodium does its own {@code Class.forName}, its own
	 * type check and its own construction, and keeps its three warning paths; handing it an instance the kernel
	 * built would answer for a class Sodium never accepted.
	 *
	 * <p>A mod already in {@code ModList} is skipped — it is reachable by Sodium's own walk, and registering it
	 * twice is how the page gets built twice. Note that Sodium's duplicate check is on {@code ModOptions.configId()},
	 * not on the mod id, and the kernel cannot know a configId before the entry point runs: two mods that pick the
	 * same configId still crash Sodium, exactly as they would on NeoForge.
	 *
	 * <p>Every failure is contained. {@code collectConfigEntryPoints} carries NO exception table and runs inside
	 * {@code Minecraft.<init>}, so a Throwable escaping this method is not a missing options page, it is a boot
	 * crash. {@code -Dforbric.sodiumConfigUsers=off} skips it entirely.
	 */
	public static void collectForeignDeclarations() {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.sodiumConfigUsers", "on"))) {
			ForbricLog.warn("[Forbric/Sodium] -Dforbric.sodiumConfigUsers=off — a Fabric mod's Sodium options page "
					+ "will not appear in Video Settings");
			return;
		}
		try {
			KernelFabricLoader loader = KernelFabricLoader.getInstanceOrNull();
			if (loader == null) return;
			Map<String, String> declared = loader.declaredEntrypoints(SODIUM_CONFIG_USER_KEY);
			if (declared.isEmpty()) return;

			ClassLoader cl = KernelLifecycle.gameLoader();
			Class<?> configManager = Class.forName(SODIUM_CONFIG_MANAGER, false, cl);
			Method register = configManager.getMethod("registerConfigEntryPoint", String.class, String.class);
			Object modList = Class.forName(ForeignType.MOD_LIST.binary(Ecosystem.NEOFORGE), false, cl)
					.getMethod("get").invoke(null);
			Method byId = modList == null ? null : modList.getClass().getMethod("getModContainerById", String.class);

			List<String> bridged = new ArrayList<>();
			for (Map.Entry<String, String> entry : declared.entrySet()) {
				String modId = entry.getKey();
				try {
					if (byId != null && !((java.util.Optional<?>) byId.invoke(modList, modId)).isEmpty()) continue;
					register.invoke(null, entry.getValue(), modId);
					bridged.add(modId);
				} catch (Throwable perMod) {
					ModCatalog.mark(modId, ModCatalog.Status.DEGRADED, "its Sodium options page is missing — the "
							+ "kernel could not hand " + entry.getValue() + " to Sodium's config registry");
					ForbricLog.warn("[Forbric/Sodium] could not register %s's config entry point %s", Reflect.unwrap(perMod),
							modId, entry.getValue());
				}
			}
			if (!bridged.isEmpty()) {
				ForbricLog.info("[Forbric/Sodium] handed %d Fabric mod(s) to Sodium's config registry %s — Sodium's "
						+ "NeoForge build finds config users only through ModList, which a Fabric mod is not in, so "
						+ "their Video Settings pages did not exist", bridged.size(), bridged);
			}
		} catch (Throwable t) {
			// Never rethrow: the caller has no exception table and runs inside Minecraft.<init>.
			ForbricLog.warn("[Forbric/Sodium] could not bridge the Fabric mods that declare a Sodium config entry "
					+ "point — their options pages will be missing from Video Settings", Reflect.unwrap(t));
		}
	}

	/** Wraps a public configuration metadata provider with the kernel's cross-ecosystem view. */
	public static java.util.function.Function<String, Object> metadataProvider(java.util.function.Function<String, Object> delegate) {
		if (delegate == null) throw new NullPointerException("configuration metadata provider");
		if ("off".equalsIgnoreCase(System.getProperty("forbric.sodiumConfigUsers", "on"))) return delegate;
		return modId -> {
			RuntimeException failure = null;
			try { Object nativeMetadata = delegate.apply(modId); if (nativeMetadata != null) return nativeMetadata; }
			catch (RuntimeException unknown) { failure = unknown; }
			KernelFabricLoader loader = KernelFabricLoader.getInstanceOrNull();
			Object fallback = null;
			if (loader != null) {
				try {
					Constructor<?> metadata = Class.forName(SODIUM_CONFIG_MANAGER + "$ModMetadata", false, KernelLifecycle.gameLoader())
							.getConstructor(String.class, String.class);
					fallback = fabricModMetadata(metadata, loader, modId);
				} catch (ReflectiveOperationException | LinkageError unsupportedApi) {
					if (failure != null) failure.addSuppressed(unsupportedApi);
				}
			}
			if (fallback != null) return fallback;
			if (failure != null) throw failure;
			throw new IllegalStateException("no metadata for mod id " + modId);
		};
	}

	/** One bridged mod as Sodium's {@code ModMetadata}, or null when the kernel does not know the id either. */
	private static Object fabricModMetadata(Constructor<?> metadata, KernelFabricLoader loader, String modId) {
		try {
			var container = loader.getModContainer(modId);
			if (container.isEmpty()) return null;
			var meta = container.get().getMetadata();
			String name = meta.getName() == null || meta.getName().isBlank() ? modId : meta.getName();
			String version = meta.getVersion() == null ? null : meta.getVersion().getFriendlyString();
			// Same rule as KernelModMetadata.versionOf: an unresolved placeholder is worse than "unknown", and
			// this string is rendered on the Video Settings page.
			if (version == null || version.isBlank() || version.contains("${")) version = "0.0";
			return metadata.newInstance(name, version);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Sodium] no kernel metadata for %s — %s", modId, String.valueOf(Reflect.unwrap(t)));
			return null;
		}
	}

}
