package net.minecraft.core;

/** A stand-in position. */
public record BlockPos(int x, int y, int z) {
	@Override
	public String toString() {
		return x + "," + y + "," + z;
	}
}
