package fixture.twincapture;

import java.util.ArrayList;
import java.util.List;

/** What a renderer submits its state to. */
public final class Sink {
	public final List<String> submitted = new ArrayList<>();

	public void submit(String state) {
		submitted.add(state);
	}
}
