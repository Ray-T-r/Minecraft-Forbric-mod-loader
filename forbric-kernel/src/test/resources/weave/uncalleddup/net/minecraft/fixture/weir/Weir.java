package net.minecraft.fixture.weir;

import java.util.ArrayList;
import java.util.List;

/**
 * The weir as merged: the carrier asks each neighbour's own {@code admits(this, face)} where the weir asked {@code opens}, with
 * the branches laid out its own way; {@code opens} is still declared, and nothing calls it.
 */
public class Weir {
	public static final List<String> LOG = new ArrayList<>();

	public String side(Stone here, Stone above, Stone below, Face face) {
		if (above.admits(this, null)) return face == Face.NORTH ? "UP" : "SIDE";
		if (!here.admits(this, face)) {
			if (here.solid()) return "NONE";
			return below.admits(this, null) ? "SIDE" : "NONE";
		}
		return "SIDE";
	}

	protected static boolean opens(Stone stone) {
		return opens(stone, null);
	}

	protected static boolean opens(Stone stone, Face face) {
		LOG.add("opens " + stone + "/" + face);
		if (stone.kind() == Kind.GATE) return true;
		return stone.flows() && face != null;
	}
}
