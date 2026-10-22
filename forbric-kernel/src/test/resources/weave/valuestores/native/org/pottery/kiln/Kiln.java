package org.pottery.kiln;

/** As the guest's loader has it: the baked pot is stored once, and goes on the shelf when it is a new one. */
public class Kiln {
	public Pot pot;
	public Pot shelf;

	public Kiln(String clay) {
		this.pot = new Pot(clay);
	}

	public void fire() {
		Pot result = this.pot.baked();
		if (result != this.pot) this.shelf = result;
	}
}
