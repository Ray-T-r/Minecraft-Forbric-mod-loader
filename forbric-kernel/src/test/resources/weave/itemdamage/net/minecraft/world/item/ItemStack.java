package net.minecraft.world.item;

import java.util.function.Consumer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/**
 * Fixture stand-in for NeoForge 26.2.0.93's ItemStack damage path (javap): hurtAndBreak(int, LivingEntity, EquipmentSlot)
 * calls NeoForge's own hurtAndBreak(int, ServerLevel, LivingEntity, Consumer) with the entity itself, and vanilla's
 * ServerPlayer overload only delegates there. Vanilla's body called the ServerPlayer overload with the entity as a
 * server player or null; that call is the one fabric-item-api's wrap names.
 */
public final class ItemStack {
	private final Item item;
	private int damage;
	private String damagedBy = "nobody";

	public ItemStack(Item item) {
		this.item = item;
	}

	public Item getItem() {
		return item;
	}

	public int getDamageValue() {
		return damage;
	}

	/** Who NeoForge's overload was told held the item. */
	public String damagedBy() {
		return damagedBy;
	}

	public void hurtAndBreak(int amount, ServerLevel level, LivingEntity entity, Consumer<Item> onBroken) {
		damagedBy = entity == null ? "null" : entity.name();
		damage += amount;
	}

	public void hurtAndBreak(int amount, ServerLevel level, ServerPlayer player, Consumer<Item> onBroken) {
		hurtAndBreak(amount, level, (LivingEntity) player, onBroken);
	}

	public void hurtAndBreak(int amount, LivingEntity entity, EquipmentSlot slot) {
		if (entity.level() instanceof ServerLevel server) {
			hurtAndBreak(amount, server, entity, broken -> entity.onEquippedItemBroken(broken, slot));
		}
	}
}
