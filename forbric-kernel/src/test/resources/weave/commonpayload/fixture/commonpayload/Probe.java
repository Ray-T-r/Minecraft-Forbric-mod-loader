package fixture.commonpayload;

import java.util.Set;

import net.fabricmc.fabric.impl.networking.server.ServerConfigurationNetworkAddon;
import net.fabricmc.fabric.impl.networking.server.ServerPlayNetworkAddon;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Sends a configuration listener a Fabric mod's payload and one of NeoForge's, and a play listener a payload fabric-api's
 * play receiver declined (one of a NeoForge mod's): who handled each, or the disconnect the listener raised.
 */
public class Probe {
	public String probe() {
		ServerConfigurationPacketListenerImpl configuration = new ServerConfigurationPacketListenerImpl();
		Addons.set(configuration, new ServerConfigurationNetworkAddon(Set.of("examplemod:hello")));
		ServerGamePacketListenerImpl play = new ServerGamePacketListenerImpl();
		Addons.set(play, new ServerPlayNetworkAddon());
		return "configuration.fabric=" + send(configuration, "examplemod:hello") + " configuration.neoforge=" + send(configuration, "neoforge:register")
				+ " play.neoforge=" + send(play, "neomod:sync");
	}

	private static String send(ServerCommonPacketListenerImpl listener, String channel) {
		try {
			listener.handleCustomPayload(new ServerboundCustomPayloadPacket(new CustomPacketPayload(channel)));
			return Trail.take();
		} catch (IllegalStateException refused) {
			Trail.take();
			return "disconnect(" + refused.getMessage() + ")";
		}
	}
}
