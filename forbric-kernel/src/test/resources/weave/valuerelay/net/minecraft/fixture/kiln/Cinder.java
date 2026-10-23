package net.minecraft.fixture.kiln;

/** As merged: the range it made on the spot is its yield's. */
public class Cinder extends Kiln {
	public Cinder() {
		super(Range.of(9, 9));
	}

	@Override public void crack(Hearth level, Spot pos, Tool tool) {
	}

	@Override public int yield(Hearth level) {
		return Range.of(2, 4).roll(level.dice());
	}
}
