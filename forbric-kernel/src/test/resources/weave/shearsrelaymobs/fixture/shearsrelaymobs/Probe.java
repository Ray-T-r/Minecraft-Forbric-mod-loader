package fixture.shearsrelaymobs;

import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Uses vanilla shears, a modded shears item that only carries the common tag, and a stick on a fresh sheep and a fresh
 * snow golem, in Player.interactOn's order: the mob's mobInteract first, then, if that passed, the item's own entity
 * entry. Each answer names who sheared the mob: "mob" (mobInteract), "item" (the item's entry) or "pass" (nobody).
 */
public class Probe {
	public String probe() {
		Item tagged = new Item("examplemod:flint_shears", Set.of("c:tools/shear"));
		return "sheep:" + row(Sheep::new, tagged) + " golem:" + row(SnowGolem::new, tagged);
	}

	private static String row(Supplier<Mob> mob, Item tagged) {
		return " shears=" + use(mob.get(), new ItemStack(Items.SHEARS)) + " tagged=" + use(mob.get(), new ItemStack(tagged))
				+ " stick=" + use(mob.get(), new ItemStack(Items.STICK));
	}

	private static String use(Mob mob, ItemStack stack) {
		Player player = new Player(stack);
		InteractionResult first = mob.mobInteract(player, InteractionHand.MAIN_HAND);
		String who = first.consumesAction() ? "mob"
				: stack.getItem().interactLivingEntity(stack, player, mob, InteractionHand.MAIN_HAND).consumesAction() ? "item" : "pass";
		boolean sheared = mob instanceof Sheep sheep ? sheep.sheared() : ((SnowGolem) mob).sheared();
		return sheared == who.equals("pass") ? who + "(sheared=" + sheared + "!)" : who;
	}
}
