package org.brook.mill;

/** The carrier's extension of the ear: asks the ear's stalk, through a private accessor, two levels down. */
public interface EarExtension {
	private Ear self() {
		return (Ear) this;
	}

	default void ripen(Field field, int day) {
		self().stalk().ripen(self(), field, day);
	}
}
