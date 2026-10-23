package fixture.lateinjection;

import java.util.ArrayList;
import java.util.List;

/** The class two mods mix into: the lantern mod and the sconce mod know nothing of each other. */
public class Lamp {
	public final List<String> trail = new ArrayList<>();

	public String light() {
		trail.add("light");
		return "glow " + brightness(2);
	}

	/** Two calls named brightness, one of each overload: a target without a descriptor matches both, in this order. */
	public String flare() {
		return "flare " + brightness(3) + "/" + brightness();
	}

	/** A new inside one branch of a conditional: whatever is left on the stack there meets the other branch. */
	public String kindle(boolean lit) {
		return lit ? new StringBuilder("k").append(brightness()).toString() : "cold";
	}

	int brightness(int level) {
		return level * 7;
	}

	int brightness() {
		return 1;
	}
}
