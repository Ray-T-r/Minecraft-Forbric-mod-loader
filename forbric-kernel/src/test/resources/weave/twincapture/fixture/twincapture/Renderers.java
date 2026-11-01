package fixture.twincapture;

/**
 * Fixture stand-in for a game class whose anonymous renderer {@code Renderers$1} has the folded tail: the state is built
 * and submitted inside the non-null branch, and the call falls straight into the join the tail sits on.
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
}
