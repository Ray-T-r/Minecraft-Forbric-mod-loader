package net.minecraft.util;

/** A stand-in for the one helper the fall sound uses. */
public final class Mth {
	private Mth() {
	}

	public static int floor(double value) {
		return (int) Math.floor(value);
	}
}
