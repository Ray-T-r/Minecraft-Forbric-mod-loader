package net.minecraft.fixture.kiln;

/** A kiln whose crack only adds to its parent's. */
public class Oven extends Kiln {
	public int cracks;

	public Oven() {
		super(Range.of(3, 5));
	}

	@Override public void crack(Hearth level, Spot pos, Tool tool) {
		super.crack(level, pos, tool);
		this.cracks++;
	}
}
