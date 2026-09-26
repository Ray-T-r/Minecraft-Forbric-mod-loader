/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
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
 * by its fluid tags, so a fluid in {@code minecraft:water} is water, one in {@code minecraft:lava} is lava, one in a tag
 * a Fabric mod gave a fluid behaviour ({@code EntityFluidInteractionRegistry}) is that behaviour's type
 * (KernelFabricFluidBehaviors), and one in none of them is NeoForge's empty type, which entities do not interact with —
 * exactly what vanilla does with an untagged fluid. The answer is not cached: tags are bound after registration and
 * rebound on every reload.
 */
public final class KernelFluidTypes {
	private static final AtomicBoolean LOOKUP_BROKE = new AtomicBoolean();

	private KernelFluidTypes() {
	}

	/** The type for {@code fluid} when NeoForge's own lookup would throw for it, else null (NeoForge answers it). */
	public static FluidType foreignType(Fluid fluid) {
		if (fluid == null || isNative(fluid)) return null;
		FluidState state = fluid.defaultFluidState();
		try {
			if (state.is(FluidTags.WATER)) return NeoForgeMod.WATER_TYPE.value();
			if (state.is(FluidTags.LAVA)) return NeoForgeMod.LAVA_TYPE.value();
			FluidType behaviour = behaviourType(state);
			if (behaviour != null) return behaviour;
		} catch (IllegalStateException unbound) {
			// Tags are not bound yet (registration): nothing touches a fluid in a world before they are.
		}
		return NeoForgeMod.EMPTY_TYPE.value();
	}

	/**
	 * The merged {@code EntityFluidInteraction.getFluidTypeByTag} answer for a tag other than water and lava: the type
	 * of a tag a Fabric mod registered a fluid behaviour for, else null, and NeoForge's {@code IllegalArgumentException}
	 * stands. fabric-api asks {@code isInFluid}, {@code getFluidHeight} and {@code applyCurrentTo} by its tags every
	 * entity tick, and {@code isEyeInFluid} for the air-bubble HUD every frame.
	 */
	public static FluidType byTag(TagKey<Fluid> tag) {
		return tag != null && KernelFabricFluidBehaviors.registered(tag) ? KernelFabricFluidBehaviors.neoType(tag) : null;
	}

	/**
	 * MinecraftForge's fluid type for a fluid that does not override MinecraftForge's {@code getFluidType()} — a NeoForge
	 * or Fabric mod's (vanilla's are bridged per class). MinecraftForge's default throws the same "Mod fluids must
	 * override getFluidType." for it; merged entity-fluid code and any MinecraftForge mod walking fluids ask it. Decided
	 * by the same tags, as MinecraftForge's water, lava or empty type — or, exactly when NeoForge's answer is a Fabric
	 * behaviour's type, MinecraftForge's type for that behaviour, so the two families never disagree about one fluid.
	 * NeoForge's answer, not foreignType's: a NeoForge fluid in a behaviour tag answers with its own type there.
	 */
	public static net.minecraftforge.fluids.FluidType forgeType(Fluid fluid) {
		FluidState state = fluid == null ? null : fluid.defaultFluidState();
		try {
			if (state != null && state.is(FluidTags.WATER)) return net.minecraftforge.common.ForgeMod.WATER_TYPE.get();
			if (state != null && state.is(FluidTags.LAVA)) return net.minecraftforge.common.ForgeMod.LAVA_TYPE.get();
			net.minecraftforge.fluids.FluidType behaviour = state == null ? null : forgeBehaviourType(fluid, state);
			if (behaviour != null) return behaviour;
		} catch (IllegalStateException unbound) {
			// Tags are not bound yet; see foreignType.
		}
		return net.minecraftforge.common.ForgeMod.EMPTY_TYPE.get();
	}

	/**
	 * The Fabric behaviour type of a fluid in neither water nor lava, or null. Nothing else comes out: a fluid's type
	 * is asked inside the client's resource reload too (a fluid container's item model), where an exception or Error
	 * drops every resource pack for a black screen; a lookup that breaks leaves the fluid the type its tags imply.
	 */
	static FluidType behaviourType(FluidState state) {
		try {
			TagKey<Fluid> tag = KernelFabricFluidBehaviors.tagOf(state);
			return tag == null ? null : KernelFabricFluidBehaviors.neoType(tag);
		} catch (IllegalStateException unbound) {
			throw unbound;
		} catch (RuntimeException | LinkageError broken) {
			return lookupBroke(broken);
		}
	}

	/** MinecraftForge's side of behaviourType: its type for the behaviour NeoForge's answer is, or null. */
	static net.minecraftforge.fluids.FluidType forgeBehaviourType(Fluid fluid, FluidState state) {
		try {
			if (KernelFabricFluidBehaviors.tagOf(state) != null && fluid.getFluidType() instanceof KernelFabricFluidBehaviors.NeoType behaviour) {
				return KernelFabricFluidBehaviors.forgeType(behaviour.tag);
			}
			return null;
		} catch (IllegalStateException unbound) {
			throw unbound;
		} catch (RuntimeException | LinkageError broken) {
			return lookupBroke(broken);
		}
	}

	private static <T> T lookupBroke(Throwable broken) {
		if (LOOKUP_BROKE.compareAndSet(false, true)) {
			ForbricLog.warn("[Forbric/Fluid] a Fabric fluid behaviour's type could not be looked up — "
					+ "a fluid it covers acts as its tags say", broken);
		}
		return null;
	}

	/** Whether NeoForge's getVanillaFluidType knows this fluid: vanilla's own, and NeoForge's milk. */
	static boolean isNative(Fluid fluid) {
		Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
		if (id != null && "minecraft".equals(id.getNamespace())) return true;
		return NeoForgeMod.MILK.asOptional().filter(milk -> milk == fluid).isPresent()
				|| NeoForgeMod.FLOWING_MILK.asOptional().filter(milk -> milk == fluid).isPresent();
	}
}
