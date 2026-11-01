package net.minecraft.core;

/** Stand-in: a position; its neighbour across a face is numbered after it. */
public record BlockPos(int n) {
	public BlockPos relative(Direction direction) {
		return new BlockPos(n * 10 + direction.ordinal() + 1);
	}

	@Override
	public String toString() {
		return "p" + n;
	}
}
