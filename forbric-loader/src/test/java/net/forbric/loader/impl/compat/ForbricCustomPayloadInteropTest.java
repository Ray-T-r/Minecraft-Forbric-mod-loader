package net.forbric.loader.impl.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraftforge.network.ChannelListManager;
import net.neoforged.neoforge.network.payload.MinecraftRegisterPayload;
import net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

class ForbricCustomPayloadInteropTest {
	@BeforeEach
	void resetFakeRegistries() throws Exception {
		PayloadTypeRegistryImpl.reset();
		NetworkRegistry.reset();
		Field mirroredLoaders = ForbricCustomPayloadInterop.class.getDeclaredField("MIRRORED_LOADERS");
		mirroredLoaders.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<ClassLoader, Boolean> loaders = (Map<ClassLoader, Boolean>) mirroredLoaders.get(null);
		loaders.clear();
	}

	@Test
	void mirrorsFabricOnlyCodecIntoNeoDecodeRegistry() {
		TestCodec codec = new TestCodec("fabric");
		Identifier id = new Identifier("fabric", "accepted_attachments_v1");
		CustomPacketPayload.Type<TestPayload> type = new CustomPacketPayload.Type<>(id);
		PayloadTypeRegistryImpl.CLIENTBOUND_CONFIGURATION.register(type, codec);

		ForbricCustomPayloadInterop.bootstrapMirrors(getClass().getClassLoader());

		assertEquals(0, NetworkRegistry.setupCalls());
		PayloadRegistration<?> mirrored = NetworkRegistry.registration(ConnectionProtocol.CONFIGURATION, id);
		assertSame(codec, mirrored.codec());
		assertSame(codec, ForbricCustomPayloadInterop.findCodec(Map.of(), id, ConnectionProtocol.CONFIGURATION,
				PacketFlow.CLIENTBOUND, null));
	}

	@Test
	void mirrorsNeoOnlyCodecIntoFabricRegistryOnDemand() {
		TestCodec codec = new TestCodec("neo");
		Identifier id = new Identifier("fabric", "custom_ingredient_sync");
		CustomPacketPayload.Type<TestPayload> type = new CustomPacketPayload.Type<>(id);
		NetworkRegistry.registerDirect(type, codec, ConnectionProtocol.PLAY, PacketFlow.SERVERBOUND);

		Object resolved = ForbricCustomPayloadInterop.findCodec(Map.of(), id, ConnectionProtocol.PLAY,
				PacketFlow.SERVERBOUND, null);

		assertSame(codec, resolved);
		assertSame(codec, PayloadTypeRegistryImpl.SERVERBOUND_PLAY.get(id).codec());
	}

	@Test
	void reusesSharedCodecWhenBothSidesAlreadyAgree() {
		TestCodec codec = new TestCodec("shared");
		Identifier id = new Identifier("forbric", "shared");
		CustomPacketPayload.Type<TestPayload> type = new CustomPacketPayload.Type<>(id);
		PayloadTypeRegistryImpl.CLIENTBOUND_PLAY.register(type, codec);
		NetworkRegistry.registerDirect(type, codec, ConnectionProtocol.PLAY, PacketFlow.CLIENTBOUND);

		Object resolved = ForbricCustomPayloadInterop.findCodec(Map.of(), id, ConnectionProtocol.PLAY,
				PacketFlow.CLIENTBOUND, null);

		assertSame(codec, resolved);
	}

	@Test
	void mirrorsDynamicRegisterAndUnregisterPackets() {
		Connection connection = new Connection();
		TestAddon addon = new TestAddon(connection);
		Identifier channel = new Identifier("fabric", "dynamic");

		Boolean handledRegister = ForbricCustomPayloadInterop.handleFabricChannelRegistrationAddon(addon,
				new MinecraftRegisterPayload(Set.of(channel)));
		assertEquals(Boolean.TRUE, handledRegister);
		assertTrue(connection.channels.contains(channel));
		assertTrue(addon.lastRegister);
		assertEquals(List.of(channel), addon.lastPayload.channels());

		Boolean handledUnregister = ForbricCustomPayloadInterop.handleFabricChannelRegistrationAddon(addon,
				new MinecraftUnregisterPayload(Set.of(channel)));
		assertEquals(Boolean.TRUE, handledUnregister);
		assertTrue(connection.channels.isEmpty());
	}

	/**
	 * Wire order, pinned on both edges. NeoForge's bookkeeping must be told BEFORE Fabric's receiveRegistration
	 * (on a server that call runs startConfiguration, whose registry-sync send NeoForge's checkPacket vetoes for a
	 * channel it has not yet learned), and MinecraftForge's declaration — one more minecraft:register — must go out
	 * AFTER Fabric's, because a Fabric server judges the client by the first register it receives.
	 */
	@Test
	void tellsNeoForgeFirstAndDeclaresForgeLast() {
		ChannelListManager.declared.clear();
		Connection connection = new Connection();
		OrderRecordingAddon addon = new OrderRecordingAddon(connection);
		Identifier channel = new Identifier("fabric", "ordered");

		Boolean handled = ForbricCustomPayloadInterop.handleFabricChannelRegistrationAddon(addon,
				new MinecraftRegisterPayload(Set.of(channel)));
		assertEquals(Boolean.TRUE, handled);
		assertEquals(Set.of(channel), addon.neoChannelsWhenFabricWasTold,
				"NeoForge's half must already know the channel when Fabric's receiveRegistration runs");
		assertEquals(0, addon.forgeDeclarationsWhenFabricWasTold,
				"Forge's declaration must not have gone out before Fabric's own register");
		assertEquals(List.of(connection), ChannelListManager.declared, "…but it does go out, once, afterwards");
	}

	@Test
	void mirrorsFabricsOwnPayloadItselfSoTheOrderStillHolds() {
		ChannelListManager.declared.clear();
		Connection connection = new Connection();
		OrderRecordingAddon addon = new OrderRecordingAddon(connection);
		Identifier channel = new Identifier("fabric", "native");
		RegistrationPayload fabricPayload = new RegistrationPayload(RegistrationPayload.REGISTER, List.of(channel));

		Boolean handled = ForbricCustomPayloadInterop.handleFabricChannelRegistrationAddon(addon, fabricPayload);
		assertEquals(Boolean.TRUE, handled, "Fabric's body would only repeat the call, after Forge's declaration");
		assertSame(fabricPayload, addon.lastPayload);
		assertTrue(addon.lastRegister);
		assertEquals(Set.of(channel), addon.neoChannelsWhenFabricWasTold);
		assertEquals(0, addon.forgeDeclarationsWhenFabricWasTold);
		assertEquals(List.of(connection), ChannelListManager.declared);
	}

	@Test
	void declaresForgeAtOnceWhenNoFabricAddonArbitrates() {
		// Without fabric-api the NeoForge head hook is the only place: declare right there, and only once.
		ChannelListManager.declared.clear();
		Connection connection = new Connection();
		Identifier channel = new Identifier("neoforge", "adhoc");

		NetworkRegistry.onMinecraftRegister(connection, Set.of(channel));
		NetworkRegistry.onMinecraftRegister(connection, Set.of(new Identifier("neoforge", "later")));
		assertEquals(List.of(connection), ChannelListManager.declared, "declared once per connection");
	}

	private static final class OrderRecordingAddon extends AbstractChanneledNetworkAddon<Object> {
		private Set<Identifier> neoChannelsWhenFabricWasTold;
		private int forgeDeclarationsWhenFabricWasTold;

		private OrderRecordingAddon(Connection connection) {
			super(connection);
		}

		@Override
		protected void receiveRegistration(boolean register, RegistrationPayload payload) {
			neoChannelsWhenFabricWasTold = Set.copyOf(connection.channels);
			forgeDeclarationsWhenFabricWasTold = ChannelListManager.declared.size();
			super.receiveRegistration(register, payload);
		}
	}

	private record TestPayload(CustomPacketPayload.Type<? extends CustomPacketPayload> type, String value)
			implements CustomPacketPayload {
	}

	private static final class TestCodec implements StreamCodec<FriendlyByteBuf, TestPayload> {
		private final String name;

		private TestCodec(String name) {
			this.name = name;
		}

		@Override
		public void encode(FriendlyByteBuf buf, TestPayload value) {
		}

		@Override
		public TestPayload decode(FriendlyByteBuf buf) {
			return new TestPayload(new CustomPacketPayload.Type<>(new Identifier("forbric", name)), name);
		}
	}

	private static final class TestAddon extends AbstractChanneledNetworkAddon<Object> {
		private TestAddon(Connection connection) {
			super(connection);
		}
	}
}
