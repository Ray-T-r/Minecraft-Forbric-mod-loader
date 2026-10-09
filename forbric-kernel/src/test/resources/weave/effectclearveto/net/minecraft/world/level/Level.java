package net.minecraft.world.level;

/** Hand-written stand-in, not game code. */
public class Level {
	private final boolean clientSide;

	public Level(boolean clientSide) {
		this.clientSide = clientSide;
	}

	public boolean isClientSide() {
		return clientSide;
	}
}
