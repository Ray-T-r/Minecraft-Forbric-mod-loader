package fixture.registerevents;

import java.util.ArrayList;
import java.util.List;

/** What ran, in order: the stand-in NeoForge's steps, the guest mixin's handlers and the stand-in mods' listeners. */
public final class Trace {
	public static final List<String> LINES = new ArrayList<>();

	private Trace() {
	}

	public static void add(String line) {
		LINES.add(line);
	}
}
