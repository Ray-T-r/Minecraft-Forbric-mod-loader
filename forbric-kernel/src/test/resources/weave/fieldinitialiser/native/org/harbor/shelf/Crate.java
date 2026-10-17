package org.harbor.shelf;

import java.util.ArrayList;
import java.util.List;

/** Hand-written stand-in, the class as the guest's loader has it: the constructor builds no crate of its own. */
public class Crate {
	private final String name;
	private final List<Crate> children;

	public Crate(String name) {
		this(name, List.of());
	}

	private Crate(String name, List<Crate> given) {
		this.children = List.copyOf(given);
		this.name = name;
	}

	public static Crate withChildren(String name, String... children) {
		List<Crate> given = new ArrayList<>();
		for (String child : children) given.add(new Crate(child));
		return new Crate(name, given);
	}

	public String name() {
		return name;
	}

	public List<Crate> children() {
		return children;
	}
}
