package net.neoforged.neoforge.common.extensions;

import net.minecraft.world.entity.LivingEntity;

/**
 * Fixture stand-in for NeoForge's ILivingEntityExtension: it defaults {@code LivingEntity self()}, and its other defaults
 * (canDrownInFluidType in the real one) reach the entity through it.
 */
public interface ILivingEntityExtension {
	default LivingEntity self() {
		return (LivingEntity) this;
	}

	default String describe() {
		return "neoforge:" + self().name();
	}
}
