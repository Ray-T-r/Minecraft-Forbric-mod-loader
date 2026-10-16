package fixture.nativeentry;

import org.mill.works.Gate;
import org.mill.works.Sluice;
import org.mill.works.Trough;
import org.mill.works.Water;

/** Opens and nudges the sluice for river water, ice the guest holds back, and mud a decision settles. */
public class Probe {
	public String probe() {
		Sluice sluice = new Sluice();
		StringBuilder out = new StringBuilder();
		for (String name : new String[] {"river", "ice", "mud"}) {
			for (String update : new String[] {"open", "nudge"}) {
				Trace.LINES.clear();
				if (update.equals("open")) sluice.open(new Gate(), new Water(name), new Trough());
				else sluice.nudge(new Gate(), new Water(name), new Trough(), 2);
				out.append(out.isEmpty() ? "" : " | ").append(update).append(' ').append(name).append(": ").append(String.join(", ", Trace.LINES));
			}
		}
		return out.toString();
	}
}
