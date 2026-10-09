package net.minecraft.core;

/** Fixture stand-in. */
public record BlockPos(int x, int y, int z) {
	public BlockPos relative(Direction direction) {
		return new BlockPos(x + direction.dx, y + direction.dy, z + direction.dz);
	}

	@Override
	public String toString() {
		return x + "," + y + "," + z;
	}
}
