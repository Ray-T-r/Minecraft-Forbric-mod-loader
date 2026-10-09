package net.minecraft.core;

/** Fixture stand-in. */
public record BlockPos(int x, int y, int z) {
	@Override
	public String toString() {
		return x + "," + y + "," + z;
	}
}
