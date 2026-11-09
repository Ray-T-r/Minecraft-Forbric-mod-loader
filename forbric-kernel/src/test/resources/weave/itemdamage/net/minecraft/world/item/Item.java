package net.minecraft.world.item;

import net.fabricmc.fabric.api.item.v1.CustomDamageHandler;

/** Fixture stand-in: an item and the custom damage handler fabric-item-api lets it carry (null: none). */
public class Item {
	private final String id;
	private final CustomDamageHandler handler;

	public Item(String id, CustomDamageHandler handler) {
		this.id = id;
		this.handler = handler;
	}

	public CustomDamageHandler getCustomDamageHandler() {
		return handler;
	}

	@Override
	public String toString() {
		return id;
	}
}
