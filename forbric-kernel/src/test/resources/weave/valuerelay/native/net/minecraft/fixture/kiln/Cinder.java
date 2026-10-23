package net.minecraft.fixture.kiln;

/** A kiln whose own crack asks vent with a range made on the spot. */
public class Cinder extends Kiln {
	public Cinder() {
		super(Range.of(9, 9));
	}

	@Override public void crack(Hearth level, Spot pos, Tool tool) {
		this.vent(level, pos, tool, Range.of(2, 4));
	}
}
