package org.meadow.sieve;

import java.util.List;
import java.util.stream.Stream;

/** As the guest's loader has it: a lambda asks the seed's trait, or takes anything into an empty pouch. */
public class Sieve {
	public List<Seed> pick(Stream<Seed> seeds, Pouch pouch) {
		boolean bare = pouch.isEmptyPouch();
		return seeds.filter(seed -> seed.trait().fits(pouch) || bare).toList();
	}
}
