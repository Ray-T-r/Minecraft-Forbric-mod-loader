package net.minecraft.world.level.material;

import net.minecraft.tags.TagKey;

/** Fixture stand-in: a fluid, the one tag it carries, and whether its default state is a source. */
public class Fluid {
	private final TagKey tag;
	private final boolean source;

	public Fluid(TagKey tag, boolean source) {
		this.tag = tag;
		this.source = source;
	}

	public boolean is(TagKey key) {
		return tag != null && tag.equals(key);
	}

	public FluidState defaultFluidState() {
		return new FluidState(this, source);
	}
}
