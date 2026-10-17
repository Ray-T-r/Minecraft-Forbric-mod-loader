package fixture.consumerstores;

import org.tavern.hall.Bell;
import org.tavern.hall.Metal;

/** Rings a bell of each metal and reports every line, in order. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		for (Metal metal : Metal.values()) {
			Trace.LINES.clear();
			new Bell(metal).ring("hall");
			out.append(out.isEmpty() ? "" : " | ").append(metal.name().toLowerCase()).append(": ").append(String.join(", ", Trace.LINES));
		}
		return out.toString();
	}
}
