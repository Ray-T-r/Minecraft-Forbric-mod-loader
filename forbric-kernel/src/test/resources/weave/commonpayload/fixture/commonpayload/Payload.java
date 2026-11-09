package fixture.commonpayload;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A decoded payload of a channel the fixture names: a Fabric mod's or a NeoForge mod's. */
public record Payload(String id) implements CustomPacketPayload {
}
