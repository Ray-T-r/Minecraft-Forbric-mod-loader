package fixture.twincapture;

/**
 * Fixture stand-in for a merged class the byte merge kept twice: {@code Renderers$1} is the vanilla-named anonymous
 * renderer and {@code Renderers$1$forbricneo} its renamed twin, which {@link #live()} hands out. Both bodies have the
 * folded tail: the state is built and submitted inside the non-null branch, and the call falls straight into the join
 * the tail sits on.
 */
public final class Renderers {
	public static final Renderer VANILLA = new Renderer() {
		@Override
		public void render(String item, Sink sink) {
			if (item != null) {
				String state = "state(" + item + ")";
				sink.submit(state);
			}
		}
	};

	private Renderers() {
	}

	public static Renderer live() {
		return new Renderers$1$forbricneo();
	}
}
