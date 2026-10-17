package org.tavern.hall;

/** As the guest's own loader has it: the tone is stored once and handed straight to the chime. */
public class Bell {
	private final Metal metal;

	public Bell(Metal metal) {
		this.metal = metal;
	}

	public void ring(String where) {
		String tone = this.metal.isGold() ? "high" : "low";
		Chime.play(where, tone, 1.0F);
	}
}
