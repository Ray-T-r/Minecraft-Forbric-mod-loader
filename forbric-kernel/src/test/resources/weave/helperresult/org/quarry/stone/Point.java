package org.quarry.stone;

public record Point(int n) {
	@Override
	public String toString() {
		return "p" + n;
	}
}
