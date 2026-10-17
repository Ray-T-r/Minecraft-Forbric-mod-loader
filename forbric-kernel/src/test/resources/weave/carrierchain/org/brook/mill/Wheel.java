package org.brook.mill;

/** As merged: the carrier routes each vanilla call through a method of its own. */
public class Wheel {
	public final Stones stones = new Stones();
	public final Field field = new Field();

	public String turn(Grain grain, Sack sack) {
		Grain held = grain;
		return held.milledBy(this.stones, sack, sack.fine());
	}

	public void harvest(Ear ear, int day) {
		ear.ripen(this.field, day);
	}

	public String sift(Chaff chaff) {
		return chaff.blownBy(this.stones);
	}


	public double dip(Bucket bucket) {
		return bucket.dipped();
	}

	public void rest(Ear ear, int day) {
		ear.ripen(this.field, day);
	}

	public void fallow(java.util.Optional<Ear> ear, int day) {
		ear.ifPresent(each -> each.ripen(this.field, day));
	}
}
