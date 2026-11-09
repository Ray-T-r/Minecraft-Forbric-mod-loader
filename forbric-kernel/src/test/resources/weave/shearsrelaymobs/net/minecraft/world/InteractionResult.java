package net.minecraft.world;

/** Fixture stand-in: an interaction's answer, and whether it used the action up. */
public final class InteractionResult {
	public static final InteractionResult PASS = new InteractionResult("pass", false);
	public static final InteractionResult SUCCESS = new InteractionResult("success", true);
	private final String name;
	private final boolean consumes;

	private InteractionResult(String name, boolean consumes) {
		this.name = name;
		this.consumes = consumes;
	}

	public boolean consumesAction() {
		return consumes;
	}

	@Override
	public String toString() {
		return name;
	}
}
