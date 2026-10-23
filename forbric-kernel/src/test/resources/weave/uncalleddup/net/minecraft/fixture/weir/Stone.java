package net.minecraft.fixture.weir;

/** A stone beside the weir. The carrier asks the stone itself whether water passes into it ({@link #admits}). */
public record Stone(String name, Kind kind) {
	public boolean flows() {
		return kind == Kind.RILL;
	}

	public boolean solid() {
		return kind == Kind.CLAY;
	}

	/** The carrier's own decision, standing where the weir asked its static question: the same answer for the stand-in game's stones. */
	public boolean admits(Weir weir, Face face) {
		Weir.LOG.add("admits " + name + "/" + face);
		if (kind == Kind.GATE) return true;
		return flows() && face != null;
	}

	@Override
	public String toString() {
		return name;
	}
}
