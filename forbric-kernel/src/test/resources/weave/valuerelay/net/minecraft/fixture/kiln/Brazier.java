package net.minecraft.fixture.kiln;

/** As merged: its fixed amount is its yield, which the hearth puffs. */
public class Brazier extends Kiln {
	public Brazier() {
		super(Range.of(1, 1));
	}

	@Override public void crack(Hearth level, Spot pos, Tool tool) {
	}

	@Override public int yield(Hearth level) {
		return 7;
	}
}
