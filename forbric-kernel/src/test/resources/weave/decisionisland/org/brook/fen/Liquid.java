package org.brook.fen;

public final class Liquid {
	public static final Liquid WATER = new Liquid("water"), LAVA = new Liquid("lava"), SLIME = new Liquid("slime");
	public final String name;

	private Liquid(String name) {
		this.name = name;
	}
}
