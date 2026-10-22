package org.weft.loom;

/** As the guest's own loader has it: one method does the three steps, after one guard. */
public class Tapestry {
	public static final java.util.List<String> LOG = new java.util.ArrayList<>();

	public Tapestry() {
	}

	public void show(String g) {
		weave(g);
		border(g);
	}

	private void weave(String g) {
		if (g == null) return;
		Spool.wind(g);
		Spool.dye(g);
		Spool.knot(g);
	}

	private void border(String g) {
		LOG.add("border:" + g);
	}
}
