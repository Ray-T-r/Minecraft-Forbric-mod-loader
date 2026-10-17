package org.meadow.sieve;

public record Seed(String name) {
	public Trait trait() {
		return new Trait(name.length());
	}

	@Override
	public String toString() {
		return name;
	}
}
