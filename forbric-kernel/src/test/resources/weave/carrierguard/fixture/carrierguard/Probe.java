package fixture.carrierguard;

import java.util.List;
import org.meadow.kite.Kite;

/** Flies, flutters and launches kites with and without a part the kite knows. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		for (String part : List.of("wing", "tail")) {
			Kite kite = new Kite(part);
			kite.parts.add(part);
			kite.stat = part.equals("tail") ? 1 : 0;
			record(out, "fly " + part, () -> Trace.add("flies " + kite.canFly()));
			record(out, "flutter " + part, kite::flutter);
		}
		for (String name : List.of("free", "tethered")) {
			Kite kite = new Kite(name);
			record(out, "launch " + name, () -> Trace.add("launched " + kite.launch()));
		}
		return out.toString();
	}

	private static void record(StringBuilder out, String label, Runnable step) {
		Trace.LINES.clear();
		try {
			step.run();
		} catch (RuntimeException failed) {
			Trace.add("threw " + failed.getMessage());
		}
		out.append(out.isEmpty() ? "" : " | ").append(label).append(": ").append(String.join(", ", Trace.LINES));
	}
}
