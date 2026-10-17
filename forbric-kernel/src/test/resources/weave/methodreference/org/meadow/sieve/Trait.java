package org.meadow.sieve;

/** The vanilla question the lambda asks: does a seed's trait fit the pouch. */
public record Trait(int size) {
	public boolean fits(Pouch pouch) {
		return size < 5;
	}
}
