package net.minecraft.core;

/** Stand-in: a position, and whether the player breaking there is in creative. */
public record BlockPos(int n, boolean creative) {
	@Override
	public String toString() {
		return "p" + n;
	}
}
