package org.meadow.sieve;

/** A pouch's kind; the carrier asks it, through its own default, whether a pouch admits a seed. */
public class Kind {
	public static final Kind EMPTY = new Kind(), GRAIN = new Kind();

	public boolean admits(Pouch pouch, Seed seed) {
		return seed.trait().size() < 7;
	}
}
