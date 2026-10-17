package fixture.carrierchain;

import org.brook.carrier.CustomGrain;
import org.brook.carrier.DeepBucket;
import org.brook.mill.Bucket;
import org.brook.carrier.ThornStalk;
import org.brook.mill.Chaff;
import org.brook.mill.Ear;
import org.brook.mill.Grain;
import org.brook.mill.Sack;
import org.brook.mill.Stalk;
import org.brook.mill.Wheel;

/** Runs every scenario on a fresh wheel and prints what each step did. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		record(out, "turn plain", () -> Trace.add("got " + new Wheel().turn(new Grain() { }, new Sack("wheat"))));
		record(out, "turn custom", () -> Trace.add("got " + new Wheel().turn(new CustomGrain(), new Sack("rye"))));
		record(out, "harvest plain", () -> new Wheel().harvest(new Ear(new Stalk(), "barley"), 3));
		record(out, "harvest thorn", () -> new Wheel().harvest(new Ear(new ThornStalk(), "oat"), 4));
		record(out, "sift", () -> Trace.add("got " + new Wheel().sift(new Chaff("husk"))));
		record(out, "dip plain", () -> Trace.add("got " + new Wheel().dip(new Bucket())));
		record(out, "dip deep", () -> Trace.add("got " + new Wheel().dip(new DeepBucket())));
		return out.toString();
	}

	private static void record(StringBuilder out, String label, Runnable step) {
		Trace.LINES.clear();
		step.run();
		out.append(out.isEmpty() ? "" : " | ").append(label).append(": ").append(String.join(", ", Trace.LINES));
	}
}
