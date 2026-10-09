package net.minecraft.fixture.optionalpartner;

/**
 * A game class in miniature, in the game's package so the kernel judges it as one. It has no beta$cacheable(): that is
 * the method mod beta's own mixin would add, and beta is not installed — so no platform's game declares it either.
 */
public class Plaque {
	public String label() {
		return "plaque";
	}
}
