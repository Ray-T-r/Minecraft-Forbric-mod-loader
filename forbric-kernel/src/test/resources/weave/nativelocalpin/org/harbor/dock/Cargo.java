package org.harbor.dock;

public class Cargo {
	public final String name;

	public Cargo(String name) {
		this.name = name;
	}

	public boolean empty() {
		return name.isEmpty();
	}
}
