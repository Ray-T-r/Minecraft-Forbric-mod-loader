/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.util.Caught;
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
 * <p>Every fluid is asked NeoForge's {@code getFluidType()}; NeoForge's answer for a fluid that does not override it is
 * {@code CommonHooks.getVanillaFluidType}, which knows vanilla's and its own milk and throws "Mod fluids must override
 * getFluidType." for anything else. A Fabric mod's fluid never overrides it — it has never heard of NeoForge — so the
 * first entity to touch one took the server down.
 *
 * <p>Such a fluid gets the type vanilla's own rules give it: vanilla (and Fabric) decide what a fluid does to an entity
 * by its fluid tags, so a fluid in {@code minecraft:water} is water, one in {@code minecraft:lava} is lava, one in a tag
 * a Fabric mod gave a fluid behaviour ({@code EntityFluidInteractionRegistry}) is that behaviour's type
 * (KernelFabricFluidBehaviors), and one in none of them is NeoForge's empty type, which entities do not interact with —
 * exactly what vanilla does with an untagged fluid. The answer is not cached: tags are bound after registration and
 * rebound on every reload.
 */
public final class KernelFluidTypes {

	private KernelFluidTypes() {
	}

	/** The type for {@code fluid} when NeoForge's own lookup would throw for it, else null (NeoForge answers it). */
	public static FluidType foreignType(Fluid fluid) {
		if (fluid == null || isNative(fluid)) return null;
		return neoTypeOf(fluid);
	}

	/** A fluid NeoForge cannot type: what its tags imply. */
	private static FluidType neoTypeOf(Fluid fluid) {
		return taggedType(fluid);
	}

	private static FluidType taggedType(Fluid fluid) {
		FluidState state = fluid.defaultFluidState();
		try {
			if (state.is(FluidTags.WATER)) return NeoForgeMod.WATER_TYPE.value();
			if (state.is(FluidTags.LAVA)) return NeoForgeMod.LAVA_TYPE.value();
			FluidType behaviour = behaviourType(state);
			if (behaviour != null) return behaviour;
		} catch (IllegalStateException unbound) {
			// Tags are not bound yet (registration): nothing touches a fluid in a world before they are.
			Caught.expected(unbound);
		}
		return NeoForgeMod.EMPTY_TYPE.value();
	}
	/**
	 * Entered only at the actual SDK lookup's proved unsupported branch; namespace guesses are unnecessary. A fluid answered
	 * here is recorded as holding a stand-in derived from its tags.
	 */
	public static FluidType foreignNeoLookup(Fluid fluid){
		FluidType type=taggedType(fluid);
		if(fluid!=null)NEO_STAND_INS.add(fluid);
		return (FluidType)net.forbric.api.LookupOutcomes.foreign(fluid,FluidType.class,type);
	}

	/**
	 * Fluids whose NeoForge type NeoForge's own lookup could not give and {@link #foreignNeoLookup} answered from their
	 * tags: a Fabric mod's (water's, lava's, a Fabric behaviour's or the empty type). Registry singletons, so the set is
	 * bounded by the fluid registry; a fluid's class decides, so the answer never changes.
	 */
	private static final Set<Fluid> NEO_STAND_INS = ConcurrentHashMap.newKeySet();

	/** Whether {@code fluid}'s NeoForge type is {@link #foreignNeoLookup}'s tag-derived stand-in. Read after the type was asked. */
	public static boolean neoTypeIsStandIn(Fluid fluid) {
		return fluid != null && NEO_STAND_INS.contains(fluid);
	}

	/** {@code -Dforbric.ownFluidTypeClauses=off} leaves NeoForge's fire clause asking whatever type the fluid answers it. */
	public static final String CLAUSES_PROPERTY = "forbric.ownFluidTypeClauses";

	/**
	 * In NeoForge's fire clause ({@code Fluid.entityInside}, OwnFluidTypeClausesInjector): NeoForge's empty type where the
	 * kernel's tag-derived stand-in answered, which claims nothing, as vanilla's empty {@code entityInside}; else the type
	 * itself.
	 */
	public static FluidType ownNeoType(Fluid fluid, FluidType type) {
		return neoTypeIsStandIn(fluid) ? NeoForgeMod.EMPTY_TYPE.value() : type;
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

	/** Whether the merged type-based tracker has a meaning for this tag. An untracked vanilla tag query is false. */
	public static boolean hasTagType(TagKey<Fluid> tag) {
		return FluidTags.WATER.equals(tag) || FluidTags.LAVA.equals(tag) || byTag(tag) != null;
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
			Caught.warn(broken, "[Forbric/Fluid] a Fabric fluid behaviour's type could not be looked up — a fluid it covers"
					+ " acts as its tags say");
			return null;
		}
	}

	/** Whether NeoForge's getVanillaFluidType knows this fluid: vanilla's own, and NeoForge's milk. */
	static boolean isNative(Fluid fluid) {
		Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
		if (id != null && "minecraft".equals(id.getNamespace())) return true;
		return NeoForgeMod.MILK.asOptional().filter(milk -> milk == fluid).isPresent()
				|| NeoForgeMod.FLOWING_MILK.asOptional().filter(milk -> milk == fluid).isPresent();
	}
}
