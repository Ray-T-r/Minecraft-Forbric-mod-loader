package net.neoforged.neoforge.registries;

import net.minecraft.core.IdMapper;

public final class GameData {
	private static final IdMapper<Object> BLOCK_STATE_ID_MAP = new IdMapper<>();

	private GameData() {
	}

	public static IdMapper<Object> getBlockStateIDMap() {
		return BLOCK_STATE_ID_MAP;
	}
}
