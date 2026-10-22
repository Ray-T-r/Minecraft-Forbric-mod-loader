package net.minecraft.fixture.kiln;

/**
 * As merged: the carrier kept {@code fire} whole but calls it from nowhere; it runs its two halves as stages of its own,
 * {@code kindle} and {@code cool}, verbatim pieces of {@code fire}, with a stage of its own ({@code ash}) between them.
 */
public class Furnace {
	public static final java.util.List<String> LOG = new java.util.ArrayList<>();
	private final java.util.List<java.util.function.Consumer<String>> stages = new java.util.ArrayList<>();

	public Furnace() {
		stages.add(this::kindle);
		stages.add(this::ash);
		stages.add(this::cool);
	}

	public void burn(String fuel) {
		for (java.util.function.Consumer<String> stage : stages) stage.accept(fuel);
	}

	public void fire(String fuel) {
		if (Gauge.mode() == 1) LOG.add("a:" + fuel);
		else LOG.add("b:" + fuel);
		Bellows.blow();
		if (Gauge.mode() != 2) LOG.add("c:" + fuel);
	}

	private void kindle(String fuel) {
		if (Gauge.mode() == 1) LOG.add("a:" + fuel);
		else LOG.add("b:" + fuel);
	}

	private void cool(String fuel) {
		Bellows.blow();
		if (Gauge.mode() != 2) LOG.add("c:" + fuel);
	}

	private void ash(String fuel) {
		LOG.add("ash:" + fuel);
	}
}
