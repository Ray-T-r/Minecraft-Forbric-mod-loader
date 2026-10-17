package fixture.decisionisland;

import java.util.ArrayList;
import java.util.List;

public final class Trace {
	public static final List<String> LINES = new ArrayList<>();

	private Trace() {
	}

	public static void add(String line) {
		LINES.add(line);
	}
}
