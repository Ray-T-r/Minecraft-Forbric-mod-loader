/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;
import net.minecraft.core.BlockPos;import net.minecraft.world.entity.Entity;import net.minecraft.world.level.LevelReader;import net.minecraft.world.level.block.SoundType;import net.minecraft.world.level.block.state.BlockState;
import net.forbric.kernel.interop.BlockSoundCallbackScope;

public final class KernelBlockSoundQuery {
	private KernelBlockSoundQuery() { }
	public static SoundType sound(BlockState state,LevelReader level,BlockPos pos,Entity entity){return (SoundType)BlockSoundCallbackScope.query(state,pos,()->state.getSoundType(level,pos,entity));}
}
