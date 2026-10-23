package net.minecraft.fixture.kiln;

/** The stand-in game's processing of a drawn value: a silk tool keeps nothing. */
public final class Lore {
	private Lore() {
	}

	public static int refine(Hearth level, Tool tool, int value) {
		return tool.silk() ? 0 : value;
	}
}
