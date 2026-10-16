package org.vault.keep;

public record Key(String name) {
	@Override
	public String toString() {
		return name;
	}
}
