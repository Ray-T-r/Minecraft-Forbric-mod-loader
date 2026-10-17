package org.meadow.sieve;

/** The carrier's extension of the pouch: an empty pouch takes anything; otherwise its kind decides. */
public interface PouchExtension {
	default boolean accepts(Seed seed) {
		Pouch self = (Pouch) this;
		if (self.isEmptyPouch()) return true;
		return self.kind().admits(self, seed);
	}
}
