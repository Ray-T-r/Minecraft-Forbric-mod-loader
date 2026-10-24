/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.util.Caught;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * The NeoForge type of a MinecraftForge mod's fluid: NeoForge's view of the MinecraftForge type the fluid gives itself
 * (KernelFluidTypes asks here before it falls back to the fluid's tags).
 *
 * <p>On its own platform every entity question about such a fluid — does a body drown, swim, get pushed, put out fire,
 * float a boat — is its type's answer. The merged entity code is NeoForge's and asks NeoForge's type, which the fluid
 * never gives, so it used to get the one its tags imply: for an untagged fluid NeoForge's empty type, air, and a body
 * whose eye was in a fluid built {@code canDrown(true)} breathed there. {@code -Dforbric.forgeFluidOwnType=off} goes
 * back to the tags.
 */
public final class KernelForgeFluidTypeViews {
	public static final String PROPERTY = "forbric.forgeFluidOwnType";

	private KernelForgeFluidTypeViews() {
	}

	/**
	 * Whether a fluid class has a MinecraftForge type of its own: the MinecraftForge {@code getFluidType()} it resolves to
	 * is declared below {@code Fluid}, which declares the fallback every class that does not override it gets.
	 */
	private static final ClassValue<Boolean> OWNS_FORGE_TYPE = new ClassValue<>() {
		@Override protected Boolean computeValue(Class<?> type) {
			for (java.lang.reflect.Method method : type.getMethods()) {
				if (method.getName().equals("getFluidType") && method.getParameterCount() == 0
						&& method.getReturnType() == net.minecraftforge.fluids.FluidType.class) {
					return method.getDeclaringClass() != Fluid.class;
				}
			}
			return false;
		}
	};

	/** NeoForge's view of each MinecraftForge type, one per type, so the same fluid always answers the same type. */
	private static final java.util.Map<net.minecraftforge.fluids.FluidType, FluidType> VIEWS = new ConcurrentHashMap<>();
	/**
	 * The NeoForge type of a fluid with a MinecraftForge type of its own, or null: MinecraftForge's empty, water and lava
	 * types are NeoForge's own (NeoForge's entity code knows them by identity), any other is its {@link View}.
	 */
	static FluidType of(Fluid fluid) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")) || !OWNS_FORGE_TYPE.get(fluid.getClass())) return null;
		net.minecraftforge.fluids.FluidType own;
		try {
			own = net.forbric.api.VirtualGetters.get(Fluid.class, "getFluidType", net.minecraftforge.fluids.FluidType.class, fluid);
			if (own == null) return null;
			if (own == net.minecraftforge.common.ForgeMod.EMPTY_TYPE.get()) return NeoForgeMod.EMPTY_TYPE.value();
			if (own == net.minecraftforge.common.ForgeMod.WATER_TYPE.get()) return NeoForgeMod.WATER_TYPE.value();
			if (own == net.minecraftforge.common.ForgeMod.LAVA_TYPE.get()) return NeoForgeMod.LAVA_TYPE.value();
		} catch (RuntimeException notYet) {
			// A MinecraftForge fluid's getter reads its type's registry entry, which is only filled once MinecraftForge's
			// fluid types are registered; until then the fluid answers as its tags say, uncached, as before.
			Caught.warn(notYet, "[Forbric/Fluid] %s's own MinecraftForge fluid type could not be read; until it can, it "
					+ "acts as its fluid tags say", fluid.getClass().getName());
			return null;
		}
		return VIEWS.computeIfAbsent(own, View::new);
	}
	/**
	 * NeoForge's view of a MinecraftForge fluid type: every question NeoForge's entity and world code asks a type is put
	 * to the MinecraftForge type, whose methods take the same game objects, so a mod's override of {@code canDrownIn} or
	 * {@code move} answers as on MinecraftForge. The plain values (light, density, temperature, viscosity, rarity, name)
	 * are copied, as NeoForge's final {@code isLighterThanAir} reads the density. It is not air: only NeoForge's empty
	 * type is. Not registered with NeoForge, as no NeoForge code looks a fluid's type up by name.
	 */
	static final class View extends FluidType {
		final net.minecraftforge.fluids.FluidType forge;

		View(net.minecraftforge.fluids.FluidType forge) {
			super(Properties.create().descriptionId(forge.getDescriptionId()).lightLevel(forge.getLightLevel())
					.density(forge.getDensity()).temperature(forge.getTemperature()).viscosity(forge.getViscosity())
					.rarity(forge.getRarity()));
			this.forge = forge;
		}

		private static net.minecraftforge.common.SoundAction forgeAction(net.neoforged.neoforge.common.SoundAction action) {
			return net.minecraftforge.common.SoundAction.get(action.name());
		}

		@Override public double motionScale(Entity entity) { return forge.motionScale(entity); }
		@Override public boolean canPushEntity(Entity entity) { return forge.canPushEntity(entity); }
		@Override public boolean canSwim(Entity entity) { return forge.canSwim(entity); }
		@Override public float getFallDistanceModifier(Entity entity) { return forge.getFallDistanceModifier(entity); }
		@Override public boolean canExtinguish(Entity entity) { return forge.canExtinguish(entity); }
		@Override public boolean canDrownIn(LivingEntity entity) { return forge.canDrownIn(entity); }
		@Override public boolean canHydrate(Entity entity) { return forge.canHydrate(entity); }
		@Override public void setItemMovement(ItemEntity item) { forge.setItemMovement(item); }
		@Override public boolean supportsBoating(AbstractBoat boat) { return forge.supportsBoating(boat); }
		@Override public boolean supportsBoating(FluidState state, AbstractBoat boat) { return forge.supportsBoating(state, boat); }
		@Override public boolean canRideVehicleUnder(Entity vehicle, Entity rider) { return forge.canRideVehicleUnder(vehicle, rider); }

		/** NeoForge's deprecated overload, which hands over the fluid state it has: MinecraftForge's own shape. */
		@SuppressWarnings("removal")
		@Override public boolean move(FluidState state, LivingEntity entity, Vec3 movement, double gravity) {
			return forge.move(state, entity, movement, gravity);
		}

		/** NeoForge's entity-only overload: MinecraftForge asks with the fluid at the body's block, as its travel does. */
		@Override public boolean move(LivingEntity entity, Vec3 movement, double gravity) {
			return forge.move(entity.level().getFluidState(entity.blockPosition()), entity, movement, gravity);
		}

		@Override public boolean canExtinguish(FluidState state, BlockGetter getter, BlockPos pos) { return forge.canExtinguish(state, getter, pos); }
		@Override public boolean canConvertToSource(FluidState state, LevelReader reader, BlockPos pos) { return forge.canConvertToSource(state, reader, pos); }

		@Override public boolean canHydrate(FluidState state, BlockGetter getter, BlockPos pos, BlockState source, BlockPos sourcePos) {
			return forge.canHydrate(state, getter, pos, source, sourcePos);
		}

		@Override public PathType getBlockPathType(FluidState state, BlockGetter level, BlockPos pos, Mob mob, boolean canFluidLog) {
			return forge.getBlockPathType(state, level, pos, mob, canFluidLog);
		}

		@Override public PathType getAdjacentBlockPathType(FluidState state, BlockGetter level, BlockPos pos, Mob mob, PathType original) {
			return forge.getAdjacentBlockPathType(state, level, pos, mob, original);
		}

		@Override public SoundEvent getSound(net.neoforged.neoforge.common.SoundAction action) { return forge.getSound(forgeAction(action)); }
		@Override public SoundEvent getSound(Entity entity, net.neoforged.neoforge.common.SoundAction action) { return forge.getSound(entity, forgeAction(action)); }

		@Override public SoundEvent getSound(LivingEntity entity, BlockGetter getter, BlockPos pos, net.neoforged.neoforge.common.SoundAction action) {
			return forge.getSound(entity, getter, pos, forgeAction(action));
		}

		@Override public String toString() { return "NeoForge view of " + forge; }
	}
}
