package net.minecraft.world.level.material;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;

/** A stand-in: a context-aware friction query on a type that does not lead back to a block. */
public class Fluid {
	public float getFriction(LevelReader level, BlockPos pos, Entity entity) {
		return 1.0F;
	}
}
