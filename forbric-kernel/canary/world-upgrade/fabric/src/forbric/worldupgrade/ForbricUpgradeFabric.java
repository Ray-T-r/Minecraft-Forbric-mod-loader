package forbric.worldupgrade;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The Fabric half of gate-m58's world-upgrade canary: data a Fabric mod keeps in a world through Fabric's own APIs —
 * persistent attachments (fabric-data-attachment-api-v1) on the level, a chunk, an entity, a block entity and the
 * player (copied on death), a data component type registered in the vanilla registry, and a vanilla {@code SavedData}.
 * Phase {@code write} sets each one; {@code read} reports each one, plus the registry census, to fabric-read.json.
 * Built once from this tree and booted on both sides of the upgrade (see {@link UpgradeProbe}).
 */
public final class ForbricUpgradeFabric implements ModInitializer {
	public static final String ID = "forbricupgradefabric";
	static final AttachmentType<Integer> LEVEL = AttachmentRegistry.create(id("level_value"), b -> b.persistent(Codec.INT));
	static final AttachmentType<Integer> CHUNK = AttachmentRegistry.create(id("chunk_value"), b -> b.persistent(Codec.INT));
	static final AttachmentType<String> ENTITY = AttachmentRegistry.create(id("entity_value"), b -> b.persistent(Codec.STRING));
	static final AttachmentType<Integer> BLOCK_ENTITY = AttachmentRegistry.create(id("be_value"), b -> b.persistent(Codec.INT));
	static final AttachmentType<Integer> PLAYER = AttachmentRegistry.create(id("player_value"), b -> b.persistent(Codec.INT).copyOnDeath());
	static final List<String> ATTACHMENTS = List.of(id("be_value").toString(), id("chunk_value").toString(),
			id("entity_value").toString(), id("level_value").toString(), id("player_value").toString());
	public static final DataComponentType<Integer> STAMP = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id("stamp"),
			DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

	public static final class State extends SavedData {
		static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(Codec.INT.fieldOf("value").forGetter(s -> s.value))
				.apply(i, State::new));
		int value;

		State() {
		}

		State(int value) {
			this.value = value;
		}
	}

	static final SavedDataType<State> STATE = new SavedDataType<>(id("state"), State::new, State.CODEC);

	private static int ticks = -1;

	static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(ID, path);
	}

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> ticks = UpgradeProbe.armed(server, "fabric") ? 0 : -1);
		// After the NeoForge canary's world script (UpgradeProbe.SCRIPT_TICK) has placed the chest and summoned the pig,
		// and the forced chunks' entities are in.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (ticks < 0 || ++ticks != UpgradeProbe.VALUES_TICK) return;
			if ("write".equals(UpgradeProbe.phase())) write(server);
			else read(server);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!UpgradeProbe.armed(server, "fabric player")) return;
			ServerPlayer player = handler.getPlayer();
			if ("write".equals(UpgradeProbe.phase())) {
				((AttachmentTarget) player).setAttached(PLAYER, 2004);
				System.out.println("[M58Upgrade] wrote fabric player values for " + player.getUUID());
			} else {
				UpgradeProbe.Report report = new UpgradeProbe.Report("fabric player");
				report.expect("player_value", 2004, ((AttachmentTarget) player).getAttached(PLAYER));
				report.finish("fabric-read-player.json", false);
			}
		});
		System.out.println("[M58Upgrade] REGISTERED fabric " + ID);
	}

	private static void write(MinecraftServer server) {
		var level = server.overworld();
		((AttachmentTarget) level).setAttached(LEVEL, 2001);
		var chunk = level.getChunk(0, 0);
		((AttachmentTarget) chunk).setAttached(CHUNK, 2002);
		chunk.markUnsaved();
		var pig = level.getEntity(UpgradeProbe.PIG);
		if (pig != null) ((AttachmentTarget) pig).setAttached(ENTITY, "pig-fabric");
		var be = level.getBlockEntity(new BlockPos(UpgradeProbe.CHEST_X, UpgradeProbe.CHEST_Y, UpgradeProbe.CHEST_Z));
		if (be != null) {
			((AttachmentTarget) be).setAttached(BLOCK_ENTITY, 2006);
			be.setChanged();
		}
		State state = server.getDataStorage().computeIfAbsent(STATE);
		state.value = 2007;
		state.setDirty();
		if (pig != null && be != null) System.out.println("[M58Upgrade] wrote fabric world values");
		else System.out.println("[M58Upgrade] FAIL wrote fabric world values without " + (pig == null ? "the pig " : "") + (be == null ? "the chest" : ""));
	}

	private static void read(MinecraftServer server) {
		var level = server.overworld();
		UpgradeProbe.Report report = new UpgradeProbe.Report("fabric world");
		report.expect("level_value", 2001, ((AttachmentTarget) level).getAttached(LEVEL));
		report.expect("chunk_value", 2002, ((AttachmentTarget) level.getChunk(0, 0)).getAttached(CHUNK));
		var pig = level.getEntity(UpgradeProbe.PIG);
		report.expect("entity_value", "pig-fabric", pig == null ? "(no pig)" : ((AttachmentTarget) pig).getAttached(ENTITY));
		var be = level.getBlockEntity(new BlockPos(UpgradeProbe.CHEST_X, UpgradeProbe.CHEST_Y, UpgradeProbe.CHEST_Z));
		report.expect("be_value", 2006, be == null ? "(no chest)" : ((AttachmentTarget) be).getAttached(BLOCK_ENTITY));
		State state = server.getDataStorage().get(STATE);
		report.expect("saved_data", 2007, state == null ? null : state.value);
		// Fabric's attachment ids have no public registry: the census lists every one this JVM registered.
		report.registry("attachment_types", UpgradeProbe.fabricAttachmentIds(ATTACHMENTS));
		report.finish("fabric-read.json", true);
	}
}
