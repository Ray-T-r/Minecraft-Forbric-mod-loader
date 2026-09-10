package net.minecraft.world.level.levelgen;

public final class DebugLevelSource {
	public static int initCalls;

	private DebugLevelSource() {
	}

	public static void initValidStates() {
		initCalls++;
	}
}
