package forbric.regressprobeneo;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * The NeoForge half of the regress probe: one item in c:tools/shear (NeoForge's own convention tag) that is not a ShearsItem
 * and brings an entity entry of its own, which names the mob instead of shearing it. On NeoForge that entry runs; BCLib's
 * tag shears relay must not pre-empt it.
 */
@Mod("regressprobeneo")
public final class RegressProbeNeo {
	static final Identifier ID = Identifier.fromNamespaceAndPath("regressprobeneo", "own_entry_shears");

	public RegressProbeNeo(IEventBus bus) {
		bus.addListener(RegisterEvent.class, event -> event.register(Registries.ITEM, ID,
				() -> new OwnEntryShears(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)))));
	}

	static final class OwnEntryShears extends Item {
		OwnEntryShears(Item.Properties properties) { super(properties); }
		@Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
			target.setCustomName(Component.literal("own-entry"));
			return InteractionResult.SUCCESS;
		}
	}
}
