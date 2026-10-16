package org.harbor.dock;

import fixture.nativelocalpin.Trace;

/** As the mod's loader has it: one cargo local at the store. */
public class Pier {
	public void unload(Ship ship) {
		Cargo cargo = ship.take();
		this.store(cargo);
	}

	public void store(Cargo cargo) {
		Trace.add("stored:" + cargo.name);
	}
}
