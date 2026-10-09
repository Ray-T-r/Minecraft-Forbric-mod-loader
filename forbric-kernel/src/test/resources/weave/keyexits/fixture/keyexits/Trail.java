package fixture.keyexits;

import java.util.ArrayList;
import java.util.List;

/** What every party to one key event said, in order. */
public final class Trail {
	private static final List<String> LINES = new ArrayList<>();

	private Trail() {
	}

	public static void add(String line) {
		LINES.add(line);
	}

	public static String drain() {
		String all = String.join(", ", LINES);
		LINES.clear();
		return all;
	}
}
