package net.minecraft.world.level.block;

/** A stand-in for vanilla's Block: its own friction. */
public class Block {
	private final float friction;

	public Block(float friction) {
		this.friction = friction;
	}

	public float getFriction() {
		return friction;
	}
}
