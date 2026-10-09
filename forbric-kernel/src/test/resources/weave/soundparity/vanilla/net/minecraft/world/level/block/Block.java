package net.minecraft.world.level.block;

/** A stand-in for vanilla's Block: its own sound group. */
public class Block {
	private final SoundType soundType;

	public Block(SoundType soundType) {
		this.soundType = soundType;
	}

	public SoundType soundType() {
		return soundType;
	}
}
