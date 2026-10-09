package net.minecraft.world.level.material;

import net.minecraft.tags.TagKey;

/** Fixture stand-in. */
public final class FluidState {
	private final Fluid type;
	private final boolean source;

	FluidState(Fluid type, boolean source) {
		this.type = type;
		this.source = source;
	}

	public Fluid getType() {
		return type;
	}

	public boolean isSource() {
		return source;
	}

	public boolean is(TagKey tag) {
		return type.is(tag);
	}
}
