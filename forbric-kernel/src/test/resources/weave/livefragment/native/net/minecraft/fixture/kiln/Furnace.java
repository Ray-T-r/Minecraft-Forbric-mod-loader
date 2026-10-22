package net.minecraft.fixture.kiln;

/** As the guest's own loader has it: burning is firing, then the ash. */
public class Furnace {
	public static final java.util.List<String> LOG = new java.util.ArrayList<>();

	public void burn(String fuel) {
		fire(fuel);
		LOG.add("ash:" + fuel);
	}

	private void fire(String fuel) {
		if (Gauge.mode() == 1) LOG.add("a:" + fuel);
		else LOG.add("b:" + fuel);
		Bellows.blow();
		if (Gauge.mode() != 2) LOG.add("c:" + fuel);
	}
}
