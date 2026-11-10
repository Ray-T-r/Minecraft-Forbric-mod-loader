package net.neoforged.neoforge.registries;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.neoforged.fml.ModLoader;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.CreativeModeTabRegistry;
import net.neoforged.neoforge.common.tooltip.ItemTooltipHandler;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;

/**
 * Stand-in for NeoForge 26.2.0.93's GameData.postRegisterEvents, statement for statement (javap of the genuine method:
 * the loop over getRegistrationOrder() posting each RegisterEvent through ModLoader.postEventWrapContainerInModOrder
 * inside a per-registry catch, the getSuppressed() test with its roll-back, then the six tail steps in order).
 */
public class GameData {
	public static Set<String> getRegistrationOrder() {
		return new LinkedHashSet<>(List.of("a", "b", "c"));
	}

	public static void postRegisterEvents() {
		Set<String> keys = getRegistrationOrder();
		RuntimeException aggregate = new RuntimeException();
		for (String key : keys) {
			try {
				RegisterEvent event = new RegisterEvent(key);
				ModLoader.postEventWrapContainerInModOrder(event);
			} catch (Throwable t) {
				aggregate.addSuppressed(t);
			}
		}
		if (aggregate.getSuppressed().length > 0) {
			RegistryManager.revertToVanilla();
			throw aggregate;
		}
		CommonHooks.modifyAttributes();
		SpawnPlacements.fireSpawnPlacementEvent();
		ModLoader.postEvent(new BlockEntityTypeAddBlocksEvent());
		CreativeModeTabRegistry.sortTabs();
		GameRuleCategory.registerModdedCategories();
		ItemTooltipHandler.init();
	}
}
