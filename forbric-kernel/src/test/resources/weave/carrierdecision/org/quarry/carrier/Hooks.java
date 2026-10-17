package org.quarry.carrier;

import fixture.carrierdecision.Trace;
import java.util.Map;
import org.quarry.pit.Drill;
import org.quarry.pit.Stack;

/** The carrier's own decisions and hooks. */
public final class Hooks {
	private Hooks() {
	}

	public static boolean mayFeed(Drill drill, Stack stack, Object source) {
		Trace.add("carrier-may-feed");
		return !stack.name.equals("stone");
	}

	public static void capture(Map<String, String> loaded) {
		Trace.add("carrier-capture " + loaded.size());
	}
}
