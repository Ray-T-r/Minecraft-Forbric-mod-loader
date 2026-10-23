package net.minecraft.fixture.kiln;

/** A kiln that puffs a fixed amount itself, unless the tool is silk, and never asks vent. */
public class Brazier extends Kiln {
	public Brazier() {
		super(Range.of(1, 1));
	}

	@Override public void crack(Hearth level, Spot pos, Tool tool) {
		if (!tool.silk()) this.puff(level, pos, 7);
	}
}
