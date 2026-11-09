package net.minecraft.server.level;

import net.minecraft.world.entity.LivingEntity;

/** Fixture stand-in: a player is a living entity (the real one through Player). */
public class ServerPlayer extends LivingEntity {
	public ServerPlayer(String name, ServerLevel level) {
		super(name, level);
	}
}
