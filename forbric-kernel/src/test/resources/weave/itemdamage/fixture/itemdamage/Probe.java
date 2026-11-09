package fixture.itemdamage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Damages a tool whose custom damage handler absorbs all damage, once held by a mob and once by a player, and a plain tool
 * held by a mob: per use, how often the handler was asked, the damage the stack took, and whom NeoForge's overload was told
 * held it.
 */
public class Probe {
	private int asked;

	public String probe() {
		ServerLevel level = new ServerLevel();
		Item everlasting = new Item("examplemod:everlasting_pickaxe", (stack, amount, entity, slot) -> {
			asked++;
			return 0;
		});
		Item plain = new Item("minecraft:iron_pickaxe", null);
		return "mob=" + hurt(everlasting, new LivingEntity("zombie", level)) + " player=" + hurt(everlasting, new ServerPlayer("steve", level))
				+ " plain=" + hurt(plain, new LivingEntity("zombie", level));
	}

	private String hurt(Item item, LivingEntity holder) {
		int before = asked;
		ItemStack stack = new ItemStack(item);
		stack.hurtAndBreak(1, holder, EquipmentSlot.MAINHAND);
		return "handler:" + (asked - before) + ",damage:" + stack.getDamageValue() + ",base:" + stack.damagedBy();
	}
}
