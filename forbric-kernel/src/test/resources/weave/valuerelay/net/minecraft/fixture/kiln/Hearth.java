package net.minecraft.fixture.kiln;

/** As merged: the hearth draws and refines the kiln's yield, lets the carrier's listeners see it, cracks the kiln and puffs what is left. */
public class Hearth {
	public static boolean hushed;
	private final Dice dice = new Dice();

	public Dice dice() {
		return dice;
	}

	public void breakAt(Kiln kiln, Spot pos, Tool tool) {
		int value = Lore.refine(this, tool, kiln.yield(this));
		value = Carrier.after(kiln, this, pos, tool, value);
		kiln.crack(this, pos, tool);
		if (value > 0) kiln.puff(this, pos, value);
	}
}
