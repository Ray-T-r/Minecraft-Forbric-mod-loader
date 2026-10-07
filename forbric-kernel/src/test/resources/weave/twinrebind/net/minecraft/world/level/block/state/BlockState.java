package net.minecraft.world.level.block.state;

/** Hand-written stand-in, not game code: a named block, opaque or not. */
public final class BlockState {
	public static final BlockState STONE = new BlockState("stone", true);
	public static final BlockState AIR = new BlockState("air", false);
	public static final BlockState ORE = new BlockState("ore", true);

	public final String name;
	public final boolean opaque;

	private BlockState(String name, boolean opaque) {
		this.name = name;
		this.opaque = opaque;
	}
}
