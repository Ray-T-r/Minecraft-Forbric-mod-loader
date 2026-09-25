/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * The NeoForge fluid type of a fluid that declares none (ForeignFluidTypeInjector).
 *
 * <p>On the merged base every fluid is asked NeoForge's {@code getFluidType()}; NeoForge's answer for a fluid that does
 * not override it is {@code CommonHooks.getVanillaFluidType}, which knows vanilla's and its own milk and throws
 * "Mod fluids must override getFluidType." for anything else. A Fabric mod's fluid never overrides it — it has never
 * heard of NeoForge — and neither does a MinecraftForge mod's (its override returns MinecraftForge's type), so the first
 * entity to touch one took the server down.
 *
 * <p>Such a fluid gets the type vanilla's own rules give it: vanilla (and Fabric) decide what a fluid does to an entity
 * by its fluid tags, so a fluid in {@code minecraft:water} is water, one in {@code minecraft:lava} is lava, and one in
 * neither is NeoForge's empty type, which entities do not interact with — exactly what vanilla does with an untagged
 * fluid. The answer is not cached: tags are bound after registration and rebound on every reload.
 */
public final class KernelFluidTypes {
	private KernelFluidTypes() {
	}

	/** The type for {@code fluid} when NeoForge's own lookup would throw for it, else null (NeoForge answers it). */
	public static FluidType foreignType(Fluid fluid) {
		if (fluid == null || native_(fluid)) return null;
		FluidState state = fluid.defaultFluidState();
		try {
			if (state.is(FluidTags.WATER)) return NeoForgeMod.WATER_TYPE.value();
			if (state.is(FluidTags.LAVA)) return NeoForgeMod.LAVA_TYPE.value();
		} catch (IllegalStateException unbound) {
			// Tags are not bound yet (registration): nothing touches a fluid in a world before they are.
		}
		return NeoForgeMod.EMPTY_TYPE.value();
	}

	/** Whether NeoForge's getVanillaFluidType knows this fluid: vanilla's own, and NeoForge's milk. */
	private static boolean native_(Fluid fluid) {
		Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
		if (id != null && "minecraft".equals(id.getNamespace())) return true;
		return NeoForgeMod.MILK.asOptional().filter(milk -> milk == fluid).isPresent()
				|| NeoForgeMod.FLOWING_MILK.asOptional().filter(milk -> milk == fluid).isPresent();
	}
}
