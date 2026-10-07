package fixture.thinnedcall;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/** A player holding stone, nothing in the off hand, uses it on a block; what ran. */
public class Probe {
	public String probe() {
		LocalPlayer player = new LocalPlayer(new ItemStack("stone", false), new ItemStack("air", true));
		new MultiPlayerGameMode().useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult());
		return String.join(",", Trace.LINES);
	}
}
