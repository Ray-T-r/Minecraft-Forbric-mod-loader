package fixture.valuestores;

import org.pottery.kiln.Kiln;

/** Fires a kiln of each clay and prints what each firing did and what ended up on the shelf. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		for (String clay : new String[] {"red", "white"}) {
			Trace.LINES.clear();
			Kiln kiln = new Kiln(clay);
			try {
				kiln.fire();
				Trace.add("shelf " + kiln.shelf);
			} catch (RuntimeException failed) {
				Trace.add("threw " + failed.getClass().getSimpleName() + ": " + failed.getMessage());
			}
			out.append(out.isEmpty() ? "" : " | ").append(clay).append(": ").append(String.join(", ", Trace.LINES));
		}
		return out.toString();
	}
}
