package org.brook.mill;

/** The carrier's own method, whose inherited implementation makes the vanilla call; a carrier mod's grain may override it. */
public interface Grain {
	default String milledBy(Stones stones, Sack sack, boolean fine) {
		return stones.grind(sack.kind(), fine);
	}
}
