package forbric.tooltipsfabric;

import java.util.LinkedHashMap;
import java.util.Map;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.item.v1.ItemComponentTooltipProviderRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ItemLore;

/**
 * Component tooltip providers a Fabric mod registers the way fabric-item-api documents: first, one chained after
 * first, before and after vanilla's lore, after durability, last, one hidden by the stack's tooltip display, one the
 * stack never carries — and one registered only when the server starts, after NeoForge built its appenders.
 */
public final class FabricTooltips implements ModInitializer {
	static final Map<String, DataComponentType<ItemLore>> TYPES = new LinkedHashMap<>();

	private static DataComponentType<ItemLore> type(String name) {
		DataComponentType<ItemLore> type = DataComponentType.<ItemLore>builder().persistent(ItemLore.CODEC).build();
		Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Identifier.fromNamespaceAndPath("forbrictooltipsfabric", name), type);
		TYPES.put(name, type);
		return type;
	}

	@Override
	public void onInitialize() {
		for (String name : new String[] {"first", "chain", "before_lore", "after_lore", "after_damage", "last", "hidden", "absent", "late"}) {
			type(name);
		}
		ItemComponentTooltipProviderRegistry.addFirst(TYPES.get("first"));
		ItemComponentTooltipProviderRegistry.addAfter(TYPES.get("first"), TYPES.get("chain"));
		ItemComponentTooltipProviderRegistry.addBefore(DataComponents.LORE, TYPES.get("before_lore"));
		ItemComponentTooltipProviderRegistry.addAfter(DataComponents.LORE, TYPES.get("after_lore"));
		ItemComponentTooltipProviderRegistry.addAfter(DataComponents.LORE, TYPES.get("hidden"));
		ItemComponentTooltipProviderRegistry.addAfter(DataComponents.LORE, TYPES.get("absent"));
		ItemComponentTooltipProviderRegistry.addAfter(DataComponents.DAMAGE, TYPES.get("after_damage"));
		ItemComponentTooltipProviderRegistry.addLast(TYPES.get("last"));
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			ItemComponentTooltipProviderRegistry.addBefore(DataComponents.LORE, TYPES.get("late"));
			System.setProperty("forbric.m51.lateRegistered", "true");
		});
	}
}
