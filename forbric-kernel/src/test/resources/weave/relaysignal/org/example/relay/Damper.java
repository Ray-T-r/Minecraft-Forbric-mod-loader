package org.example.relay;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** The relay mod's own say on whether weak power read from a side, at a position, passes one of its blocks. */
public interface Damper {
	boolean passesFrom(Direction from, BlockPos asked);
}
