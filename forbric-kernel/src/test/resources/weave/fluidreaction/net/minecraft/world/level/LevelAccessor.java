package net.minecraft.world.level;

import net.minecraft.core.BlockPos;

/** Fixture stand-in. */
public interface LevelAccessor {
	void levelEvent(int type, BlockPos pos, int data);
}
