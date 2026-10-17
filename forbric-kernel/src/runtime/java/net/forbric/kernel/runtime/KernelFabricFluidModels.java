/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Map;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.PublicApiStorage;

/**
 * NeoForge's fluid-model completeness check, told about the models fabric-rendering-fluids adds after it.
 *
 * <p>The merged {@code FluidStateModelSet.bake} ends in NeoForge's {@code ClientHooks.gatherFluidModels}, which posts
 * {@code RegisterFluidModelsEvent} and then warns "Missing FluidModel for fluid '…'" for every registered fluid not in
 * the map. fabric-rendering-fluids wraps {@code bake} with a {@code @WrapMethod}: it calls the original — NeoForge's
 * check included — and only then bakes and adds every model a Fabric mod registered through
 * {@code FluidRenderingRegistry}. So Traveler's Backpack's potion fluids were reported missing on every client boot,
 * and were in the final map all along. Native Fabric has no NeoForge check; native NeoForge has no Fabric registry.
 *
 * <p>FabricFluidModelsInjector calls {@link #hasModel} in place of the check's {@code containsKey}: a fluid NeoForge
 * has no model for still counts when Fabric's registry holds one, because Fabric's wrapper adds it before the map
 * reaches anyone. Registering them into NeoForge's event instead is not safe: {@code RegisterFluidModelsEvent.register}
 * throws on a fluid already present, and Fabric would bake the model a second time over it anyway. Should Fabric's
 * wrapper ever fail to attach, the mixin audit reports that injector, so the loss is not hidden by this.
 */
public final class KernelFabricFluidModels {
	/** fabric-rendering-fluids' public registration API; the map it puts a fluid's model into is found from it. */
	private static final String API = "net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderingRegistry";
	private static final String REGISTER_DESC = "(Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/client/renderer/block/FluidModel$Unbaked;)V";
	private static volatile boolean resolved;
	private static volatile java.lang.reflect.Field unbakedModels;

	private KernelFabricFluidModels() {
	}

	/** {@code models.containsKey(fluid)}, or a model fabric-rendering-fluids adds after NeoForge's check. */
	public static boolean hasModel(Map<?, ?> models, Object fluid) {
		if (models.containsKey(fluid)) return true;
		Map<?, ?> fabric = fabricModels();
		if (fabric == null || !fabric.containsKey(fluid)) return false;
		ForbricLog.debug("[Forbric/FluidModels] %s has no NeoForge fluid model, but fabric-rendering-fluids registers "
				+ "one and adds it after this check, so NeoForge's \"Missing FluidModel\" warning is skipped", fluid);
		return true;
	}

	/**
	 * Fabric's registered unbaked models, or null when fabric-rendering-fluids is not installed or unreadable: the static
	 * map its public {@code register(fluid, model)} puts them into ({@link PublicApiStorage}), whatever its implementation
	 * calls it.
	 */
	private static Map<?, ?> fabricModels() {
		if (!resolved) {
			synchronized (KernelFabricFluidModels.class) {
				if (!resolved) {
					try {
						unbakedModels = PublicApiStorage.staticMapPutBy(KernelFabricFluidModels.class.getClassLoader(), API, "register",
								REGISTER_DESC, 0, 1);
					} catch (RuntimeException | LinkageError absent) {
						// Absence is a null from the search; a throw is the search failing on what is installed.
						Caught.warn(absent, "[Forbric/FluidModels] could not find where fabric-rendering-fluids keeps its "
								+ "registered models; answered as none");
						unbakedModels = null;
					}
					resolved = true;
				}
			}
		}
		java.lang.reflect.Field models = unbakedModels;
		if (models == null) return null;
		try {
			return models.get(null) instanceof Map<?, ?> map ? map : null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
			// Answering "no Fabric model" keeps NeoForge's warning, which is the behaviour before this existed. A
			// LinkageError too (Field.get throws a failed class initialisation as it is, unwrapped): this runs in
			// the model bake of a resource reload, where an Error makes Minecraft drop every pack and stay black.
			Caught.warn(unreadable, "[Forbric/FluidModels] could not read fabric-rendering-fluids' unbaked models; answered as"
					+ " no Fabric model");
			return null;
		}
	}
}
