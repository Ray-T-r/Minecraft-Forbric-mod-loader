package net.minecraft.world.level.block.state;

/** Stand-in block state: a name. */
public final class BlockState {
	private final String name;

	public BlockState(String name) {
		this.name = name;
	}

	public String name() {
		return name;
	}
}
