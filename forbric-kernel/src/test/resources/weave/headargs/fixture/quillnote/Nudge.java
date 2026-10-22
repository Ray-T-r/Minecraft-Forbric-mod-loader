package fixture.quillnote;

/** The guest's own position event: a listener moves what the head callback recorded. */
public final class Nudge {
	public static final ThreadLocal<int[]> AT = ThreadLocal.withInitial(() -> new int[2]);

	private Nudge() {
	}

	public static void fire(int[] at) {
		at[0] += 150;
		at[1] += 60;
	}
}
