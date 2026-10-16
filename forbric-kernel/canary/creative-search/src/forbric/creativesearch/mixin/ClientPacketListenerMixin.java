/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package forbric.creativesearch.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

/**
 * What TCDCommons (Better Stats' library) does on every join, with nothing else: rebuild the creative tabs, then
 * refresh the creative search through vanilla's two {@code SessionSearchTrees} methods.
 *
 * <p>That is a legitimate vanilla sequence — vanilla's own creative screen does the same two calls after the same
 * rebuild — and it is the one that left every creative search empty on the merged game: the vanilla methods filed
 * their trees where the (NeoForge) creative screen does not read, and the rebuild this made meant the screen never
 * built its own.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleLogin", at = @At("TAIL"))
	private void forbriccreativesearch$refreshTheVanillaWay(ClientboundLoginPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return;
		boolean operator = minecraft.options.operatorItemsTab().get() && player.canUseGameMasterBlocks();
		boolean rebuilt = CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), operator, level.registryAccess());
		List<ItemStack> items = List.copyOf(CreativeModeTabs.searchTab().getDisplayItems());
		// gate-m55's negative control: the same rebuild with the refresh left out, which is what the creative screen
		// saw on the merged game when the refresh went somewhere it does not read.
		if ("off".equals(System.getProperty("forbric.creativeSearchCanaryRefresh"))) {
			System.out.println("[ForbricCreativeSearchCanary] rebuilt the creative tabs (changed=" + rebuilt
					+ ") and withheld the creative search refresh (" + items.size() + " item(s) not filed)");
			return;
		}
		SessionSearchTrees trees = ((ClientPacketListener) (Object) this).searchTrees();
		trees.updateCreativeTooltips(level.registryAccess(), items);
		trees.updateCreativeTags(items);
		System.out.println("[ForbricCreativeSearchCanary] rebuilt the creative tabs (changed=" + rebuilt
				+ ") and refreshed the creative search through vanilla's methods with " + items.size() + " item(s)");
	}
}
