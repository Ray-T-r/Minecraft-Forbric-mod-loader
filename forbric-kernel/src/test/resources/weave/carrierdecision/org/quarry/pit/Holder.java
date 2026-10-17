package org.quarry.pit;

public class Holder {
	private final Recipe recipe;

	public Holder(Recipe recipe) {
		this.recipe = recipe;
	}

	public Recipe value() {
		return recipe;
	}
}
