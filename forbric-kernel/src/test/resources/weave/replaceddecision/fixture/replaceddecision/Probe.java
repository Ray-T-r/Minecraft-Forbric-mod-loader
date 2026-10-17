package fixture.replaceddecision;

import org.harbor.deck.Diver;
import org.harbor.deck.Tide;

/** Ticks and surfaces a plain diver and a gilled one, in shallow and deep tides. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		for (String update : new String[] {"tick", "surface", "dive"}) {
			for (String name : new String[] {"plain", "gilled"}) {
				for (String tide : new String[] {"shallow", "deep"}) {
					Trace.LINES.clear();
					Diver diver = new Diver(name);
					if (update.equals("tick")) diver.tick(new Tide(tide));
					else if (update.equals("surface")) diver.surface(new Tide(tide));
					else diver.dive(new Tide(tide));
					out.append(out.isEmpty() ? "" : " | ").append(update).append(' ').append(name).append(' ').append(tide).append(": ")
							.append(String.join(", ", Trace.LINES));
				}
			}
		}
		return out.toString();
	}
}
