package fixture.stubcapture;

import java.util.ArrayList;
import java.util.List;

/** What the guest's capture saw. */
public final class History {
	public static final List<String> SEEN = new ArrayList<>();

	private History() {
	}
}
