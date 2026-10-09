/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;
import net.minecraft.core.BlockPos;import net.minecraft.world.entity.Entity;import net.minecraft.world.level.LevelReader;import net.minecraft.world.level.block.SoundType;import net.minecraft.world.level.block.state.BlockState;
import net.forbric.kernel.interop.BlockSoundCallbackScope;

public final class KernelBlockSoundQuery {
	private KernelBlockSoundQuery() { }
	/** The open callbacks' answer, composed; the native query answers for the state the innermost one passes on. */
	public static SoundType sound(BlockState state,LevelReader level,BlockPos pos,Entity entity){return (SoundType)BlockSoundCallbackScope.compose(state,pos,passed->((BlockState)passed).getSoundType(level,pos,entity));}
}
