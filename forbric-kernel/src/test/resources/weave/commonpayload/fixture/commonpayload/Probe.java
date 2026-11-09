package fixture.commonpayload;

import java.util.Set;

import net.fabricmc.fabric.impl.networking.server.ServerConfigurationNetworkAddon;
import net.fabricmc.fabric.impl.networking.server.ServerPlayNetworkAddon;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryPayload;

/**
 * Sends a configuration listener a Fabric mod's payload, a NeoForge mod's configuration payload (which NeoForge's common
 * dispatcher hands to {@code NetworkRegistry.handleModdedPayload}) and NeoForge's network query, and a play listener a
 * payload fabric-api's play receiver declined (one of a NeoForge mod's): who handled each, or the disconnect the listener
 * raised.
 */
public class Probe {
	public String probe() {
		ServerConfigurationPacketListenerImpl configuration = new ServerConfigurationPacketListenerImpl();
		Addons.set(configuration, new ServerConfigurationNetworkAddon(Set.of("examplemod:hello")));
		ServerGamePacketListenerImpl play = new ServerGamePacketListenerImpl();
		Addons.set(play, new ServerPlayNetworkAddon());
		return "configuration.fabric=" + send(configuration, new Payload("examplemod:hello"))
				+ " configuration.neoforge=" + send(configuration, new Payload("neomod:config_sync"))
				+ " configuration.query=" + send(configuration, new ModdedNetworkQueryPayload())
				+ " play.neoforge=" + send(play, new Payload("neomod:sync"));
	}

	private static String send(ServerCommonPacketListenerImpl listener, CustomPacketPayload payload) {
		try {
			listener.handleCustomPayload(new ServerboundCustomPayloadPacket(payload));
			return Trail.take();
		} catch (IllegalStateException refused) {
			Trail.take();
			return "disconnect(" + refused.getMessage() + ")";
		}
	}
}
