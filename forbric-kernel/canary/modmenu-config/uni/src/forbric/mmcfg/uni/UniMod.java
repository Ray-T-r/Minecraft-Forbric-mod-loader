/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package forbric.mmcfg.uni;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * The universal canary's NeoForge half: it registers its config screen the NeoForge way, on its own container. The
 * jar also carries a Fabric half that declares a Mod Menu entrypoint (FabricMenu); NeoForge wins the jar, so that half
 * never runs, and the screen Mod Menu shows for this mod has to be this one.
 */
@Mod(value = "forbricmmuni", dist = Dist.CLIENT)
public final class UniMod {
	public UniMod(ModContainer container) {
		container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new UniNeoScreen(parent));
	}
}
