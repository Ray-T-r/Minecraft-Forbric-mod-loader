package org.tavern.hall;

/** Bronze and gold the hall knows; tin only one carrier gives a tone of its own, iron the other carrier too. */
public enum Metal {
	BRONZE, GOLD, TIN, IRON;

	public boolean isGold() {
		return this == GOLD;
	}
}
