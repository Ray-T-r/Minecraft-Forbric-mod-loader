package fixture.carrierreturn;

import org.fern.loom.Spindle;

/** Runs every scenario on a fresh spindle and prints what each step did. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		for (String spot : new String[] {null, "north", "west"}) {
			record(out, "facing " + spot, () -> Trace.add("faces " + new Spindle(spot).facing()));
			record(out, "tilt " + spot, () -> Trace.add("tilts " + new Spindle(spot).tilt()));
			record(out, "lean " + spot, () -> Trace.add("leans " + new Spindle(spot).lean()));
		}
		return out.toString();
	}

	private static void record(StringBuilder out, String label, Runnable step) {
		Trace.LINES.clear();
		step.run();
		out.append(out.isEmpty() ? "" : " | ").append(label).append(": ").append(String.join(", ", Trace.LINES));
	}
}
