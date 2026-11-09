package fixture.privatedefault;

/** What the guest's handler saw. */
public final class Trail {
	private static String last = "nothing";

	private Trail() {
	}

	public static void log(String seen) {
		last = seen;
	}

	public static String take() {
		String taken = last;
		last = "nothing";
		return taken;
	}
}
