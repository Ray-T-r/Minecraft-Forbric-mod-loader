package org.brook.mill;

/** The carrier's extension of the stalk, whose inherited implementation makes the vanilla call. */
public interface StalkExtension {
	default void ripen(Ear ear, Field field, int day) {
		field.mark(day, ear.kernel(day));
	}
}
