package fixture.methodreference;

import java.util.stream.Stream;

import org.meadow.sieve.Kind;
import org.meadow.sieve.Pouch;
import org.meadow.sieve.Seed;
import org.meadow.sieve.Sieve;

/** Runs every scenario on a fresh sieve and prints what each step did. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		record(out, "pick full", () -> Trace.add("kept " + new Sieve().pick(seeds(), new Pouch(Kind.GRAIN))));
		record(out, "pick empty", () -> Trace.add("kept " + new Sieve().pick(seeds(), new Pouch(Kind.EMPTY))));
		return out.toString();
	}

	private static Stream<Seed> seeds() {
		return Stream.of(new Seed("wheat"), new Seed("oat"), new Seed("wild rye"));
	}

	private static void record(StringBuilder out, String label, Runnable step) {
		Trace.LINES.clear();
		step.run();
		out.append(out.isEmpty() ? "" : " | ").append(label).append(": ").append(String.join(", ", Trace.LINES));
	}
}
