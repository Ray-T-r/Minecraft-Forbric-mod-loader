package org.meadow.sieve;

import java.util.List;
import java.util.stream.Stream;

/** As merged: the carrier filters with a method reference to its own pouch question, whose default folds the empty pouch in. */
public class Sieve {
	public List<Seed> pick(Stream<Seed> seeds, Pouch pouch) {
		boolean bare = pouch.isEmptyPouch();
		return seeds.filter(pouch::accepts).toList();
	}
}
