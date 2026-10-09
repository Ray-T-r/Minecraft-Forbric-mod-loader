package fixture.placementsingle;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Three uses of one stack: by a builder, by an adventure-mode player whose placement vanilla refuses before the call,
 * and with no player at all (no item-interaction check after the call). What happened in each, in order.
 */
public class Probe {
	public String probe() {
		ItemStack stack = new ItemStack(new Item());
		Level level = new Level();
		StringBuilder out = new StringBuilder();
		for (Player player : java.util.Arrays.asList(new Player("alice", true), new Player("bob", false), null)) {
			Log.EVENTS.clear();
			stack.useOn(new UseOnContext(player, stack, level, new BlockPos(1, 2, 3)));
			if (out.length() > 0) out.append(" | ");
			out.append(player == null ? "nobody" : player.name()).append(": ").append(Log.EVENTS.isEmpty() ? "-" : String.join(", ", Log.EVENTS));
		}
		return out.toString();
	}
}
