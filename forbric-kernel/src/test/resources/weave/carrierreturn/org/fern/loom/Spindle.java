package org.fern.loom;

/** As merged: the carrier answers each question its own way, never asking the compass. */
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
		if (place == null) return null;
		Dial read = this.dial();
		return !read.marked(place) ? null : read.bearing(place);
	}

	/** One return, reached with a spot and without: the native answer's ways are not one return's. */
	public String tilt() {
		String place = this.spot();
		String answer = null;
		if (place != null) answer = this.dial().bearing(place);
		return answer;
	}

	public String lean() {
		String place = this.spot();
		if (place == null) return null;
		Dial read = this.dial();
		return !read.marked(place) ? null : read.bearing(place);
	}
}
