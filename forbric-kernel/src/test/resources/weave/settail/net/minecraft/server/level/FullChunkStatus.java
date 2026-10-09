package net.minecraft.server.level;

/** Fixture stand-in. */
public enum FullChunkStatus {
	INACCESSIBLE, FULL, BLOCK_TICKING, ENTITY_TICKING;

	public boolean isOrAfter(FullChunkStatus other) {
		return ordinal() >= other.ordinal();
	}
}
