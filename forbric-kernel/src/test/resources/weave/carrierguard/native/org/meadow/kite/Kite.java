package org.meadow.kite;

import fixture.carrierguard.Trace;
import java.util.ArrayList;
import java.util.List;

/** As the guest's own loader has it. */
public class Kite {
	public static final List<String> PARTS = List.of("tail", "sail");
	public final String name;
	public boolean grounded;
	public int stat;
	public final List<String> parts = new ArrayList<>();

	public Kite(String name) {
		this.name = name;
	}

	public boolean canFly() {
		if (grounded) return false;
		for (String part : PARTS) if (parts.contains(part)) return true;
		return false;
	}

	public void flutter() {
		List<String> worn = parts.stream().filter(PARTS::contains).toList();
		String picked = Picker.pick(worn);
		Trace.add("picked " + picked);
	}

	public boolean launch() {
		Trace.add("before-launch");
		Trace.add("launch " + Launcher.go(this));
		return true;
	}
}
