package net.minecraft.world;

/** A stand-in for the use results: a success that may be an item interaction, and a pass. */
public interface InteractionResult {
	Pass PASS = new Pass();
	Success SUCCESS = new Success(true);

	record Success(boolean itemInteraction) implements InteractionResult {
		public boolean wasItemInteraction() {
			return itemInteraction;
		}
	}

	record Pass() implements InteractionResult {
	}
}
