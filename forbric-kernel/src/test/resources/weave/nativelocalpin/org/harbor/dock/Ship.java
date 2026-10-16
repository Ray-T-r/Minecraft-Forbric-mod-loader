package org.harbor.dock;

public class Ship {
	public final String hold;

	public Ship(String hold) {
		this.hold = hold;
	}

	public Cargo peek() {
		return new Cargo("peeked-" + hold);
	}

	public Cargo take() {
		return new Cargo("taken-" + hold);
	}
}
