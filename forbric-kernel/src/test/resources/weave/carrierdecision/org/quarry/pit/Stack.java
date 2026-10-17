package org.quarry.pit;

/** As merged: the carrier asks the stack whether it accepts a recipe holder. */
public class Stack {
	public final String name;

	public Stack(String name) {
		this.name = name;
	}

	public boolean accepts(Holder holder) {
		fixture.carrierdecision.Trace.add("carrier-accepts");
		return holder.value().fits(this);
	}
}
