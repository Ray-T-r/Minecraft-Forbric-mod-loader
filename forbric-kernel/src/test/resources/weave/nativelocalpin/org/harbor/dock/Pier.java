package org.harbor.dock;

import fixture.nativelocalpin.Trace;

/** As merged: the carrier looks at the cargo once more, into a local of its own, before the one vanilla keeps. */
public class Pier {
	public void unload(Ship ship) {
		Cargo first = ship.peek();
		if (first.empty()) return;
		Cargo cargo = ship.take();
		this.store(cargo);
	}

	public void store(Cargo cargo) {
		Trace.add("stored:" + cargo.name);
	}
}
