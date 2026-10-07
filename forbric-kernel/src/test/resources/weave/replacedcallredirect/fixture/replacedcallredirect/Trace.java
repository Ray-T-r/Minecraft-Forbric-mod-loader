package fixture.replacedcallredirect;

import java.util.ArrayList;
import java.util.List;

/** What ran, in order. */
public final class Trace {
	public static final List<String> LINES = new ArrayList<>();

	private Trace() {
	}

	public static void add(String line) {
		LINES.add(line);
	}
}
