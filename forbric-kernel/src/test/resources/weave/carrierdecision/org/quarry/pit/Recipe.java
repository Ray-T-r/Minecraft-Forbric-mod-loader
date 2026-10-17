package org.quarry.pit;

public class Recipe {
	public final String name;

	public Recipe(String name) {
		this.name = name;
	}

	public boolean fits(Stack stack) {
		return stack.name.startsWith(name);
	}
}
