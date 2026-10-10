package net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** A stand-in: a named block, which a player's break may first turn into another state of it. */
public class Block {
	private final String name;
	private final String broken;

	public Block(String name, String broken) {
		this.name = name;
		this.broken = broken;
	}

	public String name() {
		return name;
	}

	/** Vanilla's hook before a player's break: the state the block leaves behind, which may differ from the one broken. */
	public BlockState playerWillDestroy(ServerLevel level, BlockPos pos, BlockState state) {
		return broken == null ? state : new BlockState(broken, this);
	}
}
