package org.pottery.kiln;

/** A pot: raw until baked, glazed when a glazer has seen it. */
public record Pot(String name) {
	public Pot copy() {
		return new Pot(name);
	}

	public Pot baked() {
		return new Pot("baked-" + name);
	}

	public Pot glazed() {
		return new Pot("glazed-" + name);
	}

	@Override
	public String toString() {
		return name;
	}
}
