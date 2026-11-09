package net.minecraft.world.entity;

import net.neoforged.neoforge.common.extensions.ILivingEntityExtension;

/** Fixture stand-in: NeoForge's patch makes LivingEntity implement ILivingEntityExtension; it declares no self() itself. */
public class LivingEntity implements ILivingEntityExtension {
	private final String name;

	public LivingEntity(String name) {
		this.name = name;
	}

	public String name() {
		return name;
	}

	public void tick() {
	}
}
