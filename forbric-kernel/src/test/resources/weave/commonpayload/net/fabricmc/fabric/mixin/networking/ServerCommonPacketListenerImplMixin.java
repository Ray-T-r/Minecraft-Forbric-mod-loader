package net.fabricmc.fabric.mixin.networking;

import fixture.commonpayload.Addons;
import net.fabricmc.fabric.impl.networking.server.ServerConfigurationNetworkAddon;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fabric-networking-api-v1's ServerCommonPacketListenerImplMixin in shape (javap, fabric-api 0.155.2): a cancellable HEAD
 * @Inject of handleCustomPayload that tests its listener's addon once, against the configuration addon, and throws
 * IllegalStateException("Unknown addon") for every other. The addon lookup is the fixture's own (the real one is the
 * mixin's NetworkHandlerExtensions.getAddon).
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
	@Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
	private void handleCustomPayloadReceivedAsync(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
		CustomPacketPayload payload = packet.payload();
		boolean handled;
		if (Addons.of(this) instanceof ServerConfigurationNetworkAddon addon) {
			handled = addon.handle(payload);
		} else {
			throw new IllegalStateException("Unknown addon");
		}
		if (handled) {
			ci.cancel();
		}
	}
}
