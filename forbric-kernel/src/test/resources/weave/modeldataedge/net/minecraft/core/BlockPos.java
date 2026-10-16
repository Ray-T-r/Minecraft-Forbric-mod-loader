package net.minecraft.core;

import java.util.List;

/** Stand-in block position: value equality and the six neighbours. */
public final class BlockPos {
	public final int x, y, z;

	public BlockPos(int x, int y, int z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public List<BlockPos> neighbours() {
		return List.of(new BlockPos(x + 1, y, z), new BlockPos(x - 1, y, z), new BlockPos(x, y + 1, z),
				new BlockPos(x, y - 1, z), new BlockPos(x, y, z + 1), new BlockPos(x, y, z - 1));
	}

	@Override public boolean equals(Object o) {
		return o instanceof BlockPos p && p.x == x && p.y == y && p.z == z;
	}

	@Override public int hashCode() {
		return (x * 31 + y) * 31 + z;
	}
}
