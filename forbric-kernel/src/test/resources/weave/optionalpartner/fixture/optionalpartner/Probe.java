package fixture.optionalpartner;

import net.minecraft.fixture.optionalpartner.Plaque;

/** Loads the woven Plaque and reports its label: the class defined, whatever the mixin did to it. */
public class Probe {
	public String probe() {
		return new Plaque().label();
	}
}
