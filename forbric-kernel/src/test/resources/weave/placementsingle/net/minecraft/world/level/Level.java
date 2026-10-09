package net.minecraft.world.level;

/** A stand-in server level. */
public class Level implements LevelReader {
	public boolean isClientSide() {
		return false;
	}
}
