package fixture.lanternview;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** The guest's own mutable angle, read and written through the call's argument list, and a count of the calls. */
public final class Angles {
	public static int calls;
	public static float turn = 45.0F;

	private final Consumer<Float> write;
	private final Supplier<Float> read;

	private Angles(Consumer<Float> write, Supplier<Float> read) {
		this.write = write;
		this.read = read;
	}

	public static Angles of(Consumer<Float> write, Supplier<Float> read) {
		return new Angles(write, read);
	}

	public void add(float by) {
		write.accept(read.get() + by);
	}
}
