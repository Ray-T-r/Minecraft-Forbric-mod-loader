package net.minecraft.world.level.block;

import net.neoforged.neoforge.common.extensions.IBlockExtension;

/** A stand-in for the merged Block: its own sound group, and NeoForge's sound playback. */
public class Block implements IBlockExtension {
	private final SoundType soundType;

	public Block(SoundType soundType) {
		this.soundType = soundType;
	}

	public SoundType soundType() {
		return soundType;
	}
}
