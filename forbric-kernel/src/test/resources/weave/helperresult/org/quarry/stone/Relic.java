package org.quarry.stone;

public record Relic(String name) {
	@Override
	public String toString() {
		return name;
	}
}
