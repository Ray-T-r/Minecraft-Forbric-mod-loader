package org.fern.loom;

/** As the guest's loader has it: every answer is the compass's. */
public class Spindle {
	private final String spot;
	private final Dial dial = new Dial();

	public Spindle(String spot) {
		this.spot = spot;
	}

	public String spot() {
		return spot;
	}

	public Dial dial() {
		return dial;
	}

	public String facing() {
		String place = this.spot();
		return place != null ? Compass.read(this.dial(), place) : null;
	}

	public String tilt() {
		String place = this.spot();
		return place != null ? Compass.read(this.dial(), place) : null;
	}

	public String lean() {
		String place = this.spot();
		return place != null ? Compass.read(this.dial(), place) : null;
	}
}
