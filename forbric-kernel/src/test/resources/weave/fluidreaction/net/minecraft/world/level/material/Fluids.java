package net.minecraft.world.level.material;

import net.minecraft.tags.FluidTags;

/** Fixture stand-in. */
public final class Fluids {
	public static final Fluid EMPTY = new Fluid(null, false);
	public static final FlowingFluid LAVA = new FlowingFluid(FluidTags.LAVA, true);
	public static final FlowingFluid FLOWING_LAVA = new FlowingFluid(FluidTags.LAVA, false);
	public static final FlowingFluid WATER = new FlowingFluid(FluidTags.WATER, true);

	private Fluids() {
	}
}
