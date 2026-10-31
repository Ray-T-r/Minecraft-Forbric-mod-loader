package net.minecraft.world.item;

import java.util.function.Supplier;

import net.minecraft.world.level.material.Fluid;

/** Compile-only: MinecraftForge's patch to vanilla's bucket, the constructor that takes a supplier; see LiquidBlock. Never shipped. */
public class BucketItem extends Item {
	public BucketItem(Supplier<? extends Fluid> fluid, Item.Properties properties) {
		super(properties);
	}
}
