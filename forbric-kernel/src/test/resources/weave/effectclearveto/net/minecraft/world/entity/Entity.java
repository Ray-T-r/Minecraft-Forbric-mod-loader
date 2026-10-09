package net.minecraft.world.entity;

import net.minecraft.world.level.Level;

/** Hand-written stand-in, not game code. */
public class Entity {
	private final Level level;

	public Entity(Level level) {
		this.level = level;
	}

	public Level level() {
		return level;
	}
}
