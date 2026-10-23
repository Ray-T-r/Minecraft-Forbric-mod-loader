package net.minecraft.fixture.kiln;

import net.forbric.kernel.mixin.UncalledValueRelay;

/** The stand-in carrier's last listener, as the kernel's drops listener runs the experience relay: the value it holds goes in. */
public final class Carrier {
	private static final String VENT = "net/minecraft/fixture/kiln/Kiln#vent(Lnet/minecraft/fixture/kiln/Hearth;Lnet/minecraft/fixture/kiln/Spot;"
			+ "Lnet/minecraft/fixture/kiln/Tool;Lnet/minecraft/fixture/kiln/Range;)V";

	private Carrier() {
	}

	public static int after(Kiln kiln, Hearth level, Spot pos, Tool tool, int value) {
		UncalledValueRelay.Contract contract = UncalledValueRelay.contract(VENT);
		UncalledValueRelay.Relay relay = contract == null ? null : UncalledValueRelay.relayFor(kiln.getClass(), contract);
		if (relay == null) return value;
		try {
			return UncalledValueRelay.run(relay, value, kiln, level, pos, tool, null);
		} catch (Throwable thrown) {
			throw new IllegalStateException(thrown);
		}
	}
}
