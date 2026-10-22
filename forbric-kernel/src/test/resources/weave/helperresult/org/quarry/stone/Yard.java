package org.quarry.stone;

/** Where the slabs lie: clears a spot when it may. */
public class Yard {
	private final boolean yields;

	public Yard(boolean yields) {
		this.yields = yields;
	}

	public Slab slabAt(Point at) {
		return new Slab("slab" + at.n());
	}

	public Relic relicAt(Point at) {
		return new Relic("relic" + at.n());
	}

	public boolean clear(Point at) {
		return yields;
	}

	public boolean refuses(Point at) {
		return false;
	}
}
