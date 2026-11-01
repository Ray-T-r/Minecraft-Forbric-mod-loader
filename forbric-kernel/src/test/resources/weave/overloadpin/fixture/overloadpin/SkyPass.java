package fixture.overloadpin;

/**
 * A class as a carrier patch leaves it: {@code render}'s lambda takes {@code (StringBuilder, String, String)}, where
 * vanilla's (the test's native reference) takes {@code (String, StringBuilder)}. The lambda is written out by hand under
 * javac's name for it (and no real lambda is used in this class, or javac's own would collide with it).
 */
public class SkyPass {
	public String render() {
		StringBuilder out = new StringBuilder();
		lambda$render$0(out, "sky", "moon");
		return out.toString();
	}

	/** The carrier's lambda: its body calls this one. */
	private void lambda$render$0(StringBuilder out, String sky, String moon) {
		out.append("drew ").append(sky).append('+').append(moon);
	}
}
