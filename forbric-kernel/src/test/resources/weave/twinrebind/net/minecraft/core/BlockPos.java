package net.minecraft.core;

/** Hand-written stand-in, not game code: a named position. */
public final class BlockPos {
	public final String name;

	public BlockPos(String name) {
		this.name = name;
	}

	public BlockPos relative(Direction direction) {
		return new BlockPos(name + "-" + direction.name().toLowerCase());
	}
}
