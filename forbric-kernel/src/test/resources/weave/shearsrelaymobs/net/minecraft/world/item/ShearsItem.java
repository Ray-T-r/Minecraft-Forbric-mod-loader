package net.minecraft.world.item;

import java.util.Set;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.IShearable;

/** Fixture stand-in for NeoForge's ShearsItem: its entity entry shears whatever is IShearable and ready. */
public class ShearsItem extends Item {
	public ShearsItem(String id, Set<String> tags) {
		super(id, tags);
	}

	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand) {
		if (entity instanceof IShearable target && target.isShearable(stack)) {
			target.onSheared(player, stack);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}
}
