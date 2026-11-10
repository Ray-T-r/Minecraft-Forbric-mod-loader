package net.minecraft.world.entity;

import fixture.registerevents.Trace;

/** Stand-in: one step of postRegisterEvents' tail. */
public class SpawnPlacements {
	public static void fireSpawnPlacementEvent() {
		if (Boolean.getBoolean("fixture.registerevents.spawnThrows")) throw new IllegalStateException("a mod's spawn placement listener failed");
		Trace.add("tail:fireSpawnPlacementEvent");
	}
}
