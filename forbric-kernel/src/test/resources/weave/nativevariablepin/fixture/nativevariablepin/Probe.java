package fixture.nativevariablepin;

import org.orchard.press.Mill;

/** Crushes fruit on a fresh mill and prints what each step did. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		for (String fruit : new String[] {"apple", "raspberry"}) {
			Trace.LINES.clear();
			String juice = new Mill().crush(fruit);
			out.append(out.isEmpty() ? "" : " | ").append(fruit).append(": ").append(String.join(", ", Trace.LINES)).append(", got ").append(juice);
		}
		return out.toString();
	}
}
