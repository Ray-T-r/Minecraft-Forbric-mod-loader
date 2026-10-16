package fixture.nativelocalpin;

import org.harbor.dock.Pier;
import org.harbor.dock.Ship;

public class Probe {
	public String probe() {
		new Pier().unload(new Ship("salt"));
		new Pier().unload(new Ship("tea"));
		return String.join(",", Trace.LINES);
	}
}
