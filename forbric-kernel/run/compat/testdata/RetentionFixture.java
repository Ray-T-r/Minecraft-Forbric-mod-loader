import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;

/** A small live heap for test_soak.py: a Target held through the holder named by args[1], then dumped to args[0]. */
public class RetentionFixture {
	public static class Target { }
	/** Shipped in the test's "mod" jar: its fields are mod-owned. */
	public static class ModHolder { Object held; }
	/** Declared with {@code held} in the test's "game" jar: a mod declaring the same name must not make it mod-owned. */
	public static class GameHolder { Object held; }
	/** The "game" jar's copy of this class has no {@code injected}: at runtime it stands for a Mixin-added field. */
	public static class AddedHolder { Object injected; }
	static final ModHolder MOD = new ModHolder();
	static final GameHolder GAME = new GameHolder();
	static final AddedHolder ADDED = new AddedHolder();

	public static void main(String[] args) throws Exception {
		Target target = new Target();
		switch (args[1]) {
			case "mod" -> MOD.held = target;
			case "game" -> GAME.held = target;
			case "added" -> ADDED.injected = target;
			default -> throw new IllegalArgumentException(args[1]);
		}
		target = null;
		ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(args[0], true);
	}
}
