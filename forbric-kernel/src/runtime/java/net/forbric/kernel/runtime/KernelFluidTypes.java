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
 *
 * <p>Tags are the last word only for a fluid that has no type at all. A MinecraftForge mod's fluid has one — its class
 * overrides MinecraftForge's {@code getFluidType()} — and on its own platform every entity question about it (does a
 * body drown, swim, get pushed, put out fire, float a boat) is that type's answer, never its tags'. The merged entity
 * code is NeoForge's and asks NeoForge's type, so such a fluid gets NeoForge's view of its own MinecraftForge type
 * ({@link KernelForgeFluidTypeViews}); before, an untagged one was NeoForge's empty type, air, and a body whose eye was in a
 * fluid declared {@code canDrown(true)} breathed there. {@code -Dforbric.forgeFluidOwnType=off} goes back to the tags.
 */
public final class KernelFluidTypes {

	private KernelFluidTypes() {
	}

	/** The type for {@code fluid} when NeoForge's own lookup would throw for it, else null (NeoForge answers it). */
	public static FluidType foreignType(Fluid fluid) {
		if (fluid == null || isNative(fluid)) return null;
		return neoTypeOf(fluid);
	}

	/** A fluid NeoForge cannot type: its own MinecraftForge type seen through NeoForge's, else what its tags imply. */
	private static FluidType neoTypeOf(Fluid fluid) {
		FluidType own = KernelForgeFluidTypeViews.of(fluid);
		return own != null ? own : taggedType(fluid);
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
	 * from its tags (no NeoForge view of a MinecraftForge type of its own) is recorded as holding a stand-in.
	 */
	public static FluidType foreignNeoLookup(Fluid fluid){
		FluidType own=KernelForgeFluidTypeViews.of(fluid);
		FluidType type=own!=null?own:taggedType(fluid);
		if(own==null&&fluid!=null)NEO_STAND_INS.add(fluid);
		return (FluidType)net.forbric.api.LookupOutcomes.foreign(fluid,FluidType.class,type);
	}
	public static net.minecraftforge.fluids.FluidType foreignForgeLookup(Fluid fluid){var type=forgeType(fluid);if(fluid!=null)FORGE_STAND_INS.add(fluid);return (net.minecraftforge.fluids.FluidType)net.forbric.api.LookupOutcomes.foreign(fluid,net.minecraftforge.fluids.FluidType.class,type);}

	/**
	 * Fluids whose MinecraftForge type MinecraftForge's own lookup could not give — {@link #foreignForgeLookup} answered
	 * them instead, from their tags. Such a type is a stand-in for entity physics (a Fabric fluid in
	 * {@code minecraft:water} swims like water), not the fluid's own: its client extensions — water's tint, water's
	 * model — describe some other fluid, and genuine MinecraftForge never asks a fluid it did not type. Registry
	 * singletons, so the set is bounded by the fluid registry; a fluid's class decides, so the answer never changes.
	 */
	private static final Set<Fluid> FORGE_STAND_INS = ConcurrentHashMap.newKeySet();

	/**
	 * Whether {@code fluid}'s MinecraftForge type is {@link #foreignForgeLookup}'s stand-in rather than one MinecraftForge
	 * itself gave it (vanilla's, or the fluid's own override). Read after the fluid's type was asked: MinecraftForge's
	 * getter never caches a stand-in, so every ask of a stand-in fluid passes the fallback that records it.
	 */
	public static boolean forgeTypeIsStandIn(Fluid fluid) {
		return fluid != null && FORGE_STAND_INS.contains(fluid);
	}

	/**
	 * Fluids whose NeoForge type NeoForge's own lookup could not give and {@link #foreignNeoLookup} answered from their
	 * tags: a Fabric mod's (water's, lava's, a Fabric behaviour's or the empty type), and a MinecraftForge mod's with
	 * {@code -Dforbric.forgeFluidOwnType=off}. Not a MinecraftForge mod's own type seen through NeoForge's (a view):
	 * that one is the fluid's. Bounded by the fluid registry, as {@link #FORGE_STAND_INS}.
	 */
	private static final Set<Fluid> NEO_STAND_INS = ConcurrentHashMap.newKeySet();

	/** Whether {@code fluid}'s NeoForge type is {@link #foreignNeoLookup}'s tag-derived stand-in. Read after the type was asked. */
	public static boolean neoTypeIsStandIn(Fluid fluid) {
		return fluid != null && NEO_STAND_INS.contains(fluid);
	}

	/** {@code -Dforbric.ownFluidTypeClauses=off} leaves each family's clause asking whatever type the fluid answers it. */
	public static final String CLAUSES_PROPERTY = "forbric.ownFluidTypeClauses";

	/**
	 * In a merged method that asks one fluid both families' type (OwnFluidTypeClausesInjector), MinecraftForge's clause
	 * gets the type MinecraftForge gave the fluid: {@code type} itself, or — where the kernel's tag-derived stand-in
	 * answered, because MinecraftForge does not type the fluid (a NeoForge or Fabric mod's) — MinecraftForge's empty type,
	 * which claims nothing. Its own loader asks that fluid only the other family's type, or (vanilla's {@code Fluid}) none.
	 */
	public static net.minecraftforge.fluids.FluidType ownForgeType(Fluid fluid, net.minecraftforge.fluids.FluidType type) {
		return forgeTypeIsStandIn(fluid) ? net.minecraftforge.common.ForgeMod.EMPTY_TYPE.get() : type;
	}

	/** NeoForge's side of {@link #ownForgeType}: NeoForge's empty type where the kernel's tag-derived stand-in answered. */
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
			Caught.expected(unbound);
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
			Caught.warn(broken, "[Forbric/Fluid] a Fabric fluid behaviour's type could not be looked up — a fluid it covers"
					+ " acts as its tags say");
			return null;
		}
	}

	/** MinecraftForge's side of behaviourType: its type for the behaviour NeoForge's answer is, or null. */
	static net.minecraftforge.fluids.FluidType forgeBehaviourType(Fluid fluid, FluidState state) {
		try {
			if (KernelFabricFluidBehaviors.tagOf(state) != null && net.forbric.api.VirtualGetters.get(Fluid.class, "getFluidType", FluidType.class, fluid) instanceof KernelFabricFluidBehaviors.NeoType behaviour) {
				return KernelFabricFluidBehaviors.forgeType(behaviour.tag);
			}
			return null;
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
