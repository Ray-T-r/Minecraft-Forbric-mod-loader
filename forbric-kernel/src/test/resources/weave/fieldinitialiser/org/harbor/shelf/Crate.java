package org.harbor.shelf;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written stand-in, merged shape: after {@code super()} the constructor builds a copy of each child crate with
 * {@code new Crate(...)} in a loop, the way NeoForge's pack builds its children. A crate without children skips the loop.
 */
public class Crate {
	private final String name;
	private final List<Crate> children;

	public Crate(String name) {
		this(name, List.of());
	}

	private Crate(String name, List<Crate> given) {
		List<Crate> copies = new ArrayList<>();
		for (Crate child : given) {
			Crate copy = new Crate(child.name);
			copies.add(copy);
		}
		this.children = List.copyOf(copies);
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
