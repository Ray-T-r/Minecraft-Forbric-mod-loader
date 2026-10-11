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

/**
 * Restores Forge's own client hooks at the same lifecycle sites that already serve NeoForge.
 *
 * <p>A base whose merge kept MinecraftForge's own {@code initClientHooks} call ahead of NeoForge's (a merge that keeps
 * both sides' edits) has it routed to {@link #initForgeClientHooks}, which runs MinecraftForge's half — into a scratch
 * manager, then the options reload — where MinecraftForge's call was, and {@link #initClientHooks} at NeoForge's place
 * then takes those listeners instead of running MinecraftForge's init a second time. Left native, MinecraftForge's
 * call would register its listeners straight into the manager and then the legacy bridge, seeing no capture, would
 * post its registration event again, and its key mappings would miss their saved bindings.
 */
public final class KernelForgeClientInit {
	/** MinecraftForge's half, run at its own call site, waiting for NeoForge's: its captured reload listeners. */
	private static List<PreparableReloadListener> forgeHalf;

	private KernelForgeClientInit() {}

	private static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"));
	}

	/** In place of a merged base's own {@code ForgeHooksClient.initClientHooks}, ahead of NeoForge's call. */
	public static void initForgeClientHooks(Minecraft minecraft, ReloadableResourceManager resources) {
		if (!enabled()) {
			ForgeHooksClient.initClientHooks(minecraft, resources);
			return;
		}
		forgeHalf = forgeHalf(minecraft);
	}

	public static void initClientHooks(Minecraft minecraft, ReloadableResourceManager resources) {
		if (!enabled()) {
			ClientHooks.initClientHooks(minecraft, resources);
			return;
		}
		List<PreparableReloadListener> listeners = forgeHalf;
		forgeHalf = null;
		if (listeners == null) listeners = forgeHalf(minecraft);
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

	/**
	 * MinecraftForge's init into a scratch manager that owns no packs, then the options reload its new key mappings need.
	 * Its listeners must enter NeoForge's graph while that graph is built: adding them directly to the real manager would
	 * be undone by NeoForge's updateListenersFrom afterwards.
	 */
	private static List<PreparableReloadListener> forgeHalf(Minecraft minecraft) {
		List<PreparableReloadListener> listeners;
		try (ReloadableResourceManager scratch = new ReloadableResourceManager(PackType.CLIENT_RESOURCES)) {
			ForgeHooksClient.initClientHooks(minecraft, scratch);
			listeners = List.copyOf(scratch.getListeners());
		}
		minecraft.options.load(true);
		return listeners;
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
