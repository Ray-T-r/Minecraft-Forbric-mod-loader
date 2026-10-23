package net.minecraft.fixture.weir;

import java.util.ArrayList;
import java.util.List;

/** The weir as the guest's own loader has it: it asks its static {@code opens} about each neighbour, twice through a forwarder. */
public class Weir {
	public static final List<String> LOG = new ArrayList<>();

	public String side(Stone here, Stone above, Stone below, Face face) {
		if (opens(above)) return face == Face.NORTH ? "UP" : "SIDE";
		if (opens(here, face)) return "SIDE";
		if (here.solid()) return "NONE";
		return opens(below) ? "SIDE" : "NONE";
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
