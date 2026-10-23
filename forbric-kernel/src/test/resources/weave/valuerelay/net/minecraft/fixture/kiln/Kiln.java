package net.minecraft.fixture.kiln;

import java.util.ArrayList;
import java.util.List;

/**
 * The kiln as merged: the carrier draws the kiln's yield and refines it itself, at the hearth, and puffs what is left there;
 * cracking no longer asks vent, which is still declared and which nothing calls.
 */
public class Kiln {
	public static final List<String> LOG = new ArrayList<>();
	public final Range range;

	public Kiln(Range range) {
		this.range = range;
	}

	public void crack(Hearth level, Spot pos, Tool tool) {
	}

	public int yield(Hearth level) {
		return this.range.roll(level.dice());
	}

	protected void vent(Hearth level, Spot pos, Tool tool, Range range) {
		int i = Lore.refine(level, tool, range.roll(level.dice()));
		if (i > 0) this.puff(level, pos, i);
	}

	public void puff(Hearth level, Spot pos, int amount) {
		LOG.add(getClass().getSimpleName() + " puff " + amount);
	}
}
