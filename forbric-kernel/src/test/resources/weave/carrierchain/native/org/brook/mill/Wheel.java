package org.brook.mill;

/** As the guest's loader has it: every vanilla call made here. */
public class Wheel {
	public final Stones stones = new Stones();
	public final Field field = new Field();

	public String turn(Grain grain, Sack sack) {
		return this.stones.grind(sack.kind(), sack.fine());
	}

	public void harvest(Ear ear, int day) {
		this.field.mark(day, ear.kernel(day));
	}

	public String sift(Chaff chaff) {
		return this.stones.grind(chaff.lift(), false);
	}


	public double dip(Bucket bucket) {
		return bucket.depth() + 0.25;
	}

	public void rest(Ear ear, int day) {
		this.field.mark(day, ear.kernel(day));
	}

	public void fallow(java.util.Optional<Ear> ear, int day) {
		ear.ifPresent(each -> this.field.mark(day, each.kernel(day)));
	}
}
