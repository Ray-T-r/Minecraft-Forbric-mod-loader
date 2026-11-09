package fixture.commonpayload;

/**
 * Who handled the last payload: fabric-api's configuration addon, NeoForge's configuration listener or its common
 * dispatcher, or several in order.
 */
public final class Trail {
	private static final StringBuilder TRAIL = new StringBuilder();

	private Trail() {
	}

	public static void log(String step) {
		if (!TRAIL.isEmpty()) TRAIL.append('+');
		TRAIL.append(step);
	}

	public static String take() {
		String taken = TRAIL.isEmpty() ? "nobody" : TRAIL.toString();
		TRAIL.setLength(0);
		return taken;
	}
}
