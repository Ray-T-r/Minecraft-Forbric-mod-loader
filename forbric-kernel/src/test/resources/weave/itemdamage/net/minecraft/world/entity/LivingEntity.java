package net.minecraft.world.entity;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** Fixture stand-in: a named entity in a level. */
public class LivingEntity {
	private final String name;
	private final Level level;

	public LivingEntity(String name, Level level) {
		this.name = name;
		this.level = level;
	}

	public String name() {
		return name;
	}

	public Level level() {
		return level;
	}

	public boolean hasInfiniteMaterials() {
		return false;
	}

	public void onEquippedItemBroken(Item item, EquipmentSlot slot) {
	}
}
