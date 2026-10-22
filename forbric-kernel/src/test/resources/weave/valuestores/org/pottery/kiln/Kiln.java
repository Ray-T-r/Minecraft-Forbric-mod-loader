package org.pottery.kiln;

import org.pottery.carrier.Firings;

/**
 * As merged: the carrier keeps a copy of the raw pot first, in a new first {@code Pot} local, and hands the baked pot to its
 * own hook, whose answer is what is stored and shelved.
 */
public class Kiln {
	public Pot pot;
	public Pot shelf;

	public Kiln(String clay) {
		this.pot = new Pot(clay);
	}

	public void fire() {
		Pot copy = this.pot.copy();
		Pot result = Firings.fired(this, copy, this.pot.baked());
		if (result != this.pot) this.shelf = result;
	}
}
