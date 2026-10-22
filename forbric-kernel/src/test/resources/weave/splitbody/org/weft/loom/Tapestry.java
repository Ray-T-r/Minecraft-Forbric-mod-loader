package org.weft.loom;

/**
 * As merged: the carrier split {@code weave} into {@code warp}, {@code weft} and {@code fringe}, each repeating the null
 * guard, and shows each as a layer of its own, with one of its own ({@code border}) between the last two. Nothing calls
 * {@code weave} any more.
 */
public class Tapestry {
	public static final java.util.List<String> LOG = new java.util.ArrayList<>();
	private final java.util.List<java.util.function.Consumer<String>> layers = new java.util.ArrayList<>();

	public Tapestry() {
		register();
	}

	private void register() {
		layers.add(g -> warp(g));
		layers.add(g -> weft(g));
		layers.add(g -> border(g));
		layers.add(g -> fringe(g));
	}

	public void show(String g) {
		for (java.util.function.Consumer<String> layer : layers) layer.accept(g);
	}

	public void weave(String g) {
		warp(g);
		weft(g);
		fringe(g);
	}

	private void warp(String g) {
		if (g != null) Spool.wind(g);
	}

	private void weft(String g) {
		if (g != null) Spool.dye(g);
	}

	private void fringe(String g) {
		if (g != null) Spool.knot(g);
	}

	private void border(String g) {
		LOG.add("border:" + g);
	}
}
