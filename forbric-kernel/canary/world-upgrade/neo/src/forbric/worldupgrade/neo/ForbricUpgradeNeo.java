package forbric.worldupgrade.neo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import forbric.worldupgrade.UpgradeProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The NeoForge half of gate-m58's world-upgrade canary: NeoForge data attachments on the level, a chunk, an entity, a
 * block entity and the player (copied on death), the player's persistent data, a data component type, a global and a
 * per-dimension {@code SavedData}; and the world and player scripts every save of the gate is written with (phase
 * {@code write} only, see {@link UpgradeProbe}). Phase {@code read} reports each value and the registry census (with
 * NeoForge's attachment type registry) to neo-read.json. Built once from this tree and booted on both sides.
 */
@Mod(ForbricUpgradeNeo.ID)
public final class ForbricUpgradeNeo {
	public static final String ID = "forbricupgradeneo";
	private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ID);
	private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ID);
	static final Supplier<AttachmentType<Integer>> LEVEL = ATTACHMENTS.register("level_value",
			() -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("value")).build());
	static final Supplier<AttachmentType<Integer>> CHUNK = ATTACHMENTS.register("chunk_value",
			() -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("value")).build());
	static final Supplier<AttachmentType<String>> ENTITY = ATTACHMENTS.register("entity_value",
			() -> AttachmentType.builder(() -> "").serialize(Codec.STRING.fieldOf("value")).build());
	static final Supplier<AttachmentType<Integer>> BLOCK_ENTITY = ATTACHMENTS.register("be_value",
			() -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("value")).build());
	static final Supplier<AttachmentType<Integer>> PLAYER = ATTACHMENTS.register("player_value",
			() -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("value")).copyOnDeath().build());
	public static final Supplier<DataComponentType<Integer>> STAMP = COMPONENTS.registerComponentType("stamp",
			b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

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

	static final SavedDataType<State> GLOBAL = new SavedDataType<>(Identifier.fromNamespaceAndPath(ID, "state"), State::new, State.CODEC);
	static final SavedDataType<State> DIMENSION = new SavedDataType<>(Identifier.fromNamespaceAndPath(ID, "level_state"), State::new, State.CODEC);

	private static int ticks = -1;

	public ForbricUpgradeNeo(IEventBus bus) {
		ATTACHMENTS.register(bus);
		COMPONENTS.register(bus);
		NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> started(event.getServer()));
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
			if (ticks < 0) return;
			ticks++;
			if (ticks == UpgradeProbe.SCRIPT_TICK && UpgradeProbe.writesWorld()) {
				UpgradeProbe.runScript(event.getServer(), "forbric.worldUpgradeCommands", null);
			}
			if (ticks != UpgradeProbe.VALUES_TICK) return;
			if ("write".equals(UpgradeProbe.phase())) write(event.getServer());
			else read(event.getServer());
		});
		NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
			if (event.getEntity() instanceof ServerPlayer player) joined(player);
		});
		System.out.println("[M58Upgrade] REGISTERED neo " + ID);
	}

	private static void started(MinecraftServer server) {
		if (!UpgradeProbe.armed(server, "neo")) return;
		ticks = 0;
		if (UpgradeProbe.writesWorld()) {
			boolean ok = UpgradeProbe.command(server, UpgradeProbe.FORCELOAD, null);
			System.out.println("[M58Upgrade] " + (ok ? "OK" : "FAIL") + " command (canary) " + UpgradeProbe.FORCELOAD);
		}
	}

	private static void joined(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null || !UpgradeProbe.armed(server, "neo player")) return;
		if ("write".equals(UpgradeProbe.phase())) {
			UpgradeProbe.runScript(server, "forbric.worldUpgradePlayerCommands", player);
			player.setData(PLAYER, 1004);
			player.getPersistentData().putInt(ID, 1005);
			System.out.println("[M58Upgrade] wrote neo player values for " + player.getUUID());
		} else {
			UpgradeProbe.Report report = new UpgradeProbe.Report("neo player");
			report.expect("player_value", 1004, player.getExistingDataOrNull(PLAYER.get()));
			report.expect("persistent_data", 1005, player.getPersistentData().contains(ID) ? player.getPersistentData().getIntOr(ID, -1) : null);
			report.finish("neo-read-player.json", false);
		}
	}

	private static void write(MinecraftServer server) {
		var level = server.overworld();
		level.setData(LEVEL, 1001);
		var chunk = level.getChunk(0, 0);
		chunk.setData(CHUNK, 1002);
		chunk.markUnsaved();
		var pig = level.getEntity(UpgradeProbe.PIG);
		if (pig != null) pig.setData(ENTITY, "pig-neo");
		var be = level.getBlockEntity(new BlockPos(UpgradeProbe.CHEST_X, UpgradeProbe.CHEST_Y, UpgradeProbe.CHEST_Z));
		if (be != null) {
			be.setData(BLOCK_ENTITY, 1006);
			be.setChanged();
		}
		State global = server.getDataStorage().computeIfAbsent(GLOBAL);
		global.value = 1007;
		global.setDirty();
		State dimension = level.getDataStorage().computeIfAbsent(DIMENSION);
		dimension.value = 1008;
		dimension.setDirty();
		if (pig != null && be != null) System.out.println("[M58Upgrade] wrote neo world values");
		else System.out.println("[M58Upgrade] FAIL wrote neo world values without " + (pig == null ? "the pig " : "") + (be == null ? "the chest" : ""));
	}

	private static void read(MinecraftServer server) {
		var level = server.overworld();
		UpgradeProbe.Report report = new UpgradeProbe.Report("neo world");
		report.expect("level_value", 1001, level.getExistingDataOrNull(LEVEL.get()));
		report.expect("chunk_value", 1002, level.getChunk(0, 0).getExistingDataOrNull(CHUNK.get()));
		var pig = level.getEntity(UpgradeProbe.PIG);
		report.expect("entity_value", "pig-neo", pig == null ? "(no pig)" : pig.getExistingDataOrNull(ENTITY.get()));
		var be = level.getBlockEntity(new BlockPos(UpgradeProbe.CHEST_X, UpgradeProbe.CHEST_Y, UpgradeProbe.CHEST_Z));
		report.expect("be_value", 1006, be == null ? "(no chest)" : be.getExistingDataOrNull(BLOCK_ENTITY.get()));
		State global = server.getDataStorage().get(GLOBAL);
		report.expect("saved_data", 1007, global == null ? null : global.value);
		State dimension = level.getDataStorage().get(DIMENSION);
		report.expect("level_saved_data", 1008, dimension == null ? null : dimension.value);
		// NeoForge's attachment types are a registry: every attachment key a save can carry is in it.
		List<String> names = new ArrayList<>();
		for (var id : NeoForgeRegistries.ATTACHMENT_TYPES.keySet()) names.add(id.toString());
		names.sort(null);
		report.registry("attachment_types", names);
		report.finish("neo-read.json", true);
	}
}
