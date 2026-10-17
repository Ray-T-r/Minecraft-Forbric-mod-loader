package org.tavern.hall;

/**
 * As merged: one carrier's tone first, the hall's own choice only when it has none, then the other carrier's custom tone when
 * it has one — and the tone handed to the chime as before.
 */
public class Bell {
	private final Metal metal;

	public Bell(Metal metal) {
		this.metal = metal;
	}

	public void ring(String where) {
		String tone = org.tavern.carrier.Belfry.tone(this.metal);
		if (tone == null) {
			tone = this.metal.isGold() ? "high" : "low";
		}
		String custom = org.tavern.carrier.Steeple.tone(this.metal);
		if (custom != null) {
			tone = custom;
		}
		Chime.play(where, tone, 1.0F);
	}
}
