package net.minecraft.world.level.block.state;

/** Stand-in: a named block state that hides its neighbours' faces when solid. */
public record BlockState(String name, boolean solid) {
	@Override
	public String toString() {
		return name;
	}
}
