package net.minecraft.fixture.kiln;

import java.util.ArrayList;
import java.util.List;

/** The kiln as the guest's own game has it: cracking it asks vent, which draws, refines and puffs. */
public class Kiln {
	public static final List<String> LOG = new ArrayList<>();
	public final Range range;

	public Kiln(Range range) {
		this.range = range;
	}

	public void crack(Hearth level, Spot pos, Tool tool) {
		this.vent(level, pos, tool, this.range);
	}

	protected void vent(Hearth level, Spot pos, Tool tool, Range range) {
		int i = Lore.refine(level, tool, range.roll(level.dice()));
		if (i > 0) this.puff(level, pos, i);
	}

	public void puff(Hearth level, Spot pos, int amount) {
		LOG.add(getClass().getSimpleName() + " puff " + amount);
	}
}
