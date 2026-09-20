package net.forbric.kernel.runtime;

import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.model.geometry.GeometryLoaderManager;
import net.neoforged.neoforge.client.ClientHooks;

/** Restores Forge's own client hooks at the same lifecycle sites that already serve NeoForge. */
public final class KernelForgeClientInit {
	private KernelForgeClientInit() {}

	private static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"));
	}

	public static void initClientHooks(Minecraft minecraft, ReloadableResourceManager resources) {
		if (!enabled()) {
			ClientHooks.initClientHooks(minecraft, resources);
			return;
		}
		// The scratch owns no packs. Its listeners must enter NeoForge's graph while that graph is built:
		// adding them directly to resources would be undone by NeoForge's updateListenersFrom afterwards.
		List<PreparableReloadListener> listeners;
		try (ReloadableResourceManager scratch = new ReloadableResourceManager(PackType.CLIENT_RESOURCES)) {
			ForgeHooksClient.initClientHooks(minecraft, scratch);
			listeners = List.copyOf(scratch.getListeners());
		}
		minecraft.options.load(true);
		boolean drained = ForgeClientReloadCapture.withCaptured(listeners,
				() -> ClientHooks.initClientHooks(minecraft, resources));
		if (!drained && !listeners.isEmpty()) {
			if ("off".equalsIgnoreCase(System.getProperty("forbric.unifiedEvents", "on"))) {
				ForbricLog.warn("[Forbric/ForgeClient] client reload bridge is disabled; %d captured listener(s) were not installed", listeners.size());
			} else {
				throw new IllegalStateException("Forge client reload capture was not consumed by NeoForge's registration event");
			}
		}
		ForbricLog.info("[Forbric/ForgeClient] applied MinecraftForge client init: %d captured reload listener(s), %d key mapping(s)",
				listeners.size(), minecraft.options.keyMappings.length);
	}

	public static void onRegisterParticleProviders(ParticleResources particles) {
		if (enabled()) ForgeHooksClient.onRegisterParticleProviders(particles);
		ClientHooks.onRegisterParticleProviders(particles);
	}

	/** Forge's original ModelManager calls this on every reload, before starting any model-loading future. */
	public static void initGeometryLoaders() {
		if (enabled()) GeometryLoaderManager.init();
	}
}
