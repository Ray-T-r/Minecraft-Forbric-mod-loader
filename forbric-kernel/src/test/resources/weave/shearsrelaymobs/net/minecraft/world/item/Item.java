package net.minecraft.world.item;

import java.util.Set;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Fixture stand-in: an item with the tags it carries and vanilla's default entity entry, which does nothing. */
public class Item {
	private final String id;
	private final Set<String> tags;

	public Item(String id, Set<String> tags) {
		this.id = id;
		this.tags = tags;
	}

	public boolean tagged(String tag) {
		return tags.contains(tag);
	}

	public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand) {
		return InteractionResult.PASS;
	}

	@Override
	public String toString() {
		return id;
	}
}
