package fixture.placementwitness;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Four uses of one stack: by a builder, by an adventure-mode player whose placement vanilla refuses before the call, with
 * no player, and by a player the carrier's own event refuses after the head (it answers a success without the use).
 */
public class Probe {
	public String probe() {
		ItemStack stack = new ItemStack(new Item());
		Level level = new Level();
		StringBuilder out = new StringBuilder();
		for (Player player : java.util.Arrays.asList(new Player("alice", true), new Player("bob", false), null, new Player("carol", true))) {
			Log.EVENTS.clear();
			stack.useOn(new UseOnContext(player, stack, level, new BlockPos(1, 2, 3)));
			if (out.length() > 0) out.append(" | ");
			out.append(player == null ? "nobody" : player.name()).append(": ").append(Log.EVENTS.isEmpty() ? "-" : String.join(", ", Log.EVENTS));
		}
		return out.toString();
	}
}
