package net.minecraft.fixture.kiln;

/** The stand-in game's value provider. */
public class Range {
	private final int lo, hi;

	private Range(int lo, int hi) {
		this.lo = lo;
		this.hi = hi;
	}

	public static Range of(int lo, int hi) {
		return new Range(lo, hi);
	}

	public int roll(Dice dice) {
		return lo + dice.next() % (hi - lo + 1);
	}

	@Override public String toString() {
		return "Range[" + lo + ".." + hi + "]";
	}
}
