package org.meadow.kite;

import fixture.carrierguard.Trace;
import java.util.ArrayList;
import java.util.List;
import org.meadow.carrier.Hooks;

/**
 * As merged: {@code canFly()} forwards to the carrier's {@code canFly(true)}, which answers from its own stat on that entry
 * and reads the parts only for {@code canFly(false)}; {@code flutter} guards the pick against an empty list; {@code launch}
 * lets the carrier's hook veto it before the launcher is asked.
 */
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
		return canFly(true);
	}

	public boolean canFly(boolean useStat) {
		if (grounded) return false;
		if (useStat) return stat > 0;
		for (String part : PARTS) if (parts.contains(part)) return true;
		return false;
	}

	public void flutter() {
		List<String> worn = parts.stream().filter(PARTS::contains).toList();
		if (!worn.isEmpty()) {
			String picked = Picker.pick(worn);
			Trace.add("picked " + picked);
		}
	}

	public boolean launch() {
		Trace.add("before-launch");
		if (Hooks.vetoLaunch(this)) return false;
		Trace.add("launch " + Launcher.go(this));
		return true;
	}
}
