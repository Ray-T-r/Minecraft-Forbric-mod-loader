package forbric.regressprobe;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** A Fabric item in c:tools/shear with an entity entry of its own: it names the mob instead of shearing it. */
public class OwnEntryShears extends Item {
	public OwnEntryShears(Item.Properties properties) { super(properties); }
	@Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
		target.setCustomName(Component.literal("own-entry"));
		return InteractionResult.SUCCESS;
	}
}
