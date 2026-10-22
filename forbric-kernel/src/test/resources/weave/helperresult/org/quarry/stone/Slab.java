package org.quarry.stone;

import fixture.helperresult.Trace;

/** A slab of stone: shaped before it goes, shattered once it is gone. */
public class Slab {
	private final String name;

	public Slab(String name) {
		this.name = name;
	}

	public Slab shapedBy(Yard yard, Point at) {
		return new Slab("shaped-" + name);
	}

	public boolean keepable(Point at) {
		return true;
	}

	public void shatter(Yard yard, Point at, Slab shaped) {
		Trace.add("shatter " + this + " at " + at);
	}

	public void settle(Yard yard, Point at) {
		Trace.add("settle " + this + " at " + at);
	}

	public void seal(Point at) {
		Trace.add("seal " + this);
	}

	@Override
	public String toString() {
		return name;
	}
}
