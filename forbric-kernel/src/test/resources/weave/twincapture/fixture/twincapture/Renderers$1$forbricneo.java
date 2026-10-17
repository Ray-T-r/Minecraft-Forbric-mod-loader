package fixture.twincapture;

/** The merge's renamed twin of {@code Renderers$1}: the same folded body. */
public final class Renderers$1$forbricneo implements Renderer {
	@Override
	public void render(String item, Sink sink) {
		if (item != null) {
			String state = "state(" + item + ")";
			sink.submit(state);
		}
	}
}
