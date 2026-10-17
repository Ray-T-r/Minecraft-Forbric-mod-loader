package fixture.carrierdecision;

import org.quarry.pit.Bit;
import org.quarry.pit.Drill;
import org.quarry.pit.Holder;
import org.quarry.pit.Recipe;
import org.quarry.pit.Stack;

/** Runs every scenario on fresh drills and prints what each step did. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		String[][] bits = {{"hard", "0", "hard", "0"}, {"hard", "0", "soft", "0"}, {"soft", "0", "soft", "3"}, {"hard", "0", "hard", "1"}};
		for (String[] pair : bits) {
			Drill drill = new Drill(new Bit(pair[0], Integer.parseInt(pair[1])));
			record(out, "keep " + pair[2] + pair[3], () -> drill.keepsBoring(new Bit(pair[2], Integer.parseInt(pair[3]))));
		}
		for (String stack : new String[] {"ore", "wild", "dirt"}) {
			Drill drill = new Drill(new Bit("hard", 0));
			record(out, "takes " + stack, () -> drill.takes(new Stack(stack), new Holder(new Recipe("ore"))));
			record(out, "price " + stack, () -> drill.price(new Stack(stack), new Holder(new Recipe("ore"))));
		}
		for (String stack : new String[] {"ore", "stone"}) {
			Drill drill = new Drill(new Bit("hard", 0));
			record(out, "feed " + stack, () -> drill.feed(new Stack(stack), "hand"));
		}
		for (String extra : new String[] {"a", "locked"}) {
			Drill drill = new Drill(new Bit("hard", 0));
			drill.items.put(extra, "1");
			drill.items.put("b", "2");
			record(out, "empty " + extra, () -> Trace.add("returned " + drill.empty() + " left " + new java.util.TreeSet<>(drill.items.keySet())));
		}
		Drill frozen = new Drill(new Bit("hard", 0));
		record(out, "freeze", () -> Trace.add("storage " + new java.util.TreeSet<>(frozen.freeze().keySet())));
		Drill shiny = new Drill(new Bit("hard", 0));
		record(out, "shine", () -> shiny.shine(new Bit("hard", 0)));
		return out.toString();
	}

	private static void record(StringBuilder out, String label, Runnable step) {
		Trace.LINES.clear();
		step.run();
		out.append(out.isEmpty() ? "" : " | ").append(label).append(": ").append(String.join(", ", Trace.LINES));
	}
}
