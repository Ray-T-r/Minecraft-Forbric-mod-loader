package net.minecraft.fixture.kiln;

public class Hearth {
	public static boolean hushed;
	private final Dice dice = new Dice();

	public Dice dice() {
		return dice;
	}

	public void breakAt(Kiln kiln, Spot pos, Tool tool) {
		kiln.crack(this, pos, tool);
	}
}
