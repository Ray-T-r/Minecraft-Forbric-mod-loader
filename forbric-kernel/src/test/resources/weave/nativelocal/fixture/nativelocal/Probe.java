package fixture.nativelocal;

import org.quarry.yard.Bundle;
import org.quarry.yard.Crate;
import org.quarry.yard.Slot;

public class Probe {
	public String probe() {
		Crate crate = new Crate();
		crate.each(Slot.TOP, Trace::add);
		crate.stored = new Bundle("oats");
		crate.each(Slot.SIDE, Trace::add);
		return String.join(",", Trace.LINES);
	}
}
