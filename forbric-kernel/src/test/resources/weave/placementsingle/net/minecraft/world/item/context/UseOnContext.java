package net.minecraft.world.item.context;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** A stand-in: who used which stack on which block of which level. */
public class UseOnContext {
	private final Player player;
	private final ItemStack itemInHand;
	private final Level level;
	private final BlockPos pos;

	public UseOnContext(Player player, ItemStack itemInHand, Level level, BlockPos pos) {
		this.player = player;
		this.itemInHand = itemInHand;
		this.level = level;
		this.pos = pos;
	}

	public Player getPlayer() {
		return player;
	}

	public ItemStack getItemInHand() {
		return itemInHand;
	}

	public Level getLevel() {
		return level;
	}

	public BlockPos getClickedPos() {
		return pos;
	}
}
