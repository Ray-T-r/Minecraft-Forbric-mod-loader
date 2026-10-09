package fixture.stagecall;

public final class Paints {
	private Paints() {
	}

	public static String solid(String id) {
		return "solid:" + id;
	}

	public static String translucent(String id) {
		return "translucent:" + id;
	}

	public static String outline(String id) {
		return "outline:" + id;
	}
}
