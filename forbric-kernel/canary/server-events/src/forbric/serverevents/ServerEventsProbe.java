package forbric.serverevents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.brewing.BrewingRecipeRegisterEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * MinecraftForge listeners for what a player keeps, written the way packedup and Tombstone write theirs, driven through
 * the merged game: a respawn copy, a dropped-experience cancel and change, an explosion that must spare one block, and a
 * brewing recipe registered through MinecraftForge's event — each beside an untouched control.
 */
@Mod("forbricserverevents")
public final class ServerEventsProbe {
	private static final BlockPos SPARED = new BlockPos(4, -60, 8), CONTROL = new BlockPos(12, -60, 8);
	private static final List<Map<String, Object>> cases = new ArrayList<>();
	private static MinecraftServer server;
	private static int ticks, clones;
	private static boolean cloneWasDeath, cloneOriginal;
	private static FakePlayer original;

	public ServerEventsProbe(IEventBus bus) {
		PlayerEvent.Clone.BUS.addListener((Consumer<PlayerEvent.Clone>) event -> {
			clones++;
			cloneWasDeath = event.isWasDeath();
			cloneOriginal = event.getOriginal() == original;
		});
		LivingExperienceDropEvent.BUS.addListener((Predicate<LivingExperienceDropEvent>) event -> {
			if (event.getEntity().entityTags().contains("m48_xp7")) event.setDroppedExperience(7);
			return event.getEntity().entityTags().contains("m48_noxp");
		});
		ExplosionEvent.Detonate.BUS.addListener((Consumer<ExplosionEvent.Detonate>) event -> event.getAffectedBlocks().remove(SPARED));
		BrewingRecipeRegisterEvent.BUS.addListener((Consumer<BrewingRecipeRegisterEvent>) event ->
				event.addRecipe(Ingredient.of(Items.DIRT), Ingredient.of(Items.DIAMOND), new ItemStack(Items.EMERALD)));
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
			if (server != null || ++ticks < 20) return;
			server = event.getServer();
			run(server.overworld());
		});
		System.out.println("[M48Server] REGISTERED MinecraftForge server listeners");
	}

	private static void run(ServerLevel level) {
		test("clone.forge", () -> {
			original = player(level, "5d1e7a2c-47a0-4e8b-9a61-2f0c3b8e6d48", "ServerEventsOld");
			FakePlayer respawned = player(level, "5d1e7a2c-47a0-4e8b-9a61-2f0c3b8e6d49", "ServerEventsNew");
			respawned.restoreFrom(original, false);
			require(clones == 1 && cloneWasDeath && cloneOriginal, "MinecraftForge's Clone seen " + clones + " time(s), death=" + cloneWasDeath + ", original=" + cloneOriginal);
		});
		FakePlayer killer = player(level, "5d1e7a2c-47a0-4e8b-9a61-2f0c3b8e6d4a", "ServerEventsKiller");
		test("xp.control", () -> require(EventHooks.getExperienceDrop(pig(level, null), killer, 10) == 10, "an untouched drop changed"));
		test("xp.cancel", () -> {
			int dropped = EventHooks.getExperienceDrop(pig(level, "m48_noxp"), killer, 10);
			require(dropped == 0, "a cancelled drop still dropped " + dropped);
		});
		test("xp.change", () -> {
			int dropped = EventHooks.getExperienceDrop(pig(level, "m48_xp7"), killer, 10);
			require(dropped == 7, "a changed drop dropped " + dropped);
		});
		level.setBlock(SPARED, Blocks.DIRT.defaultBlockState(), 2);
		level.setBlock(CONTROL, Blocks.DIRT.defaultBlockState(), 2);
		level.explode(null, 8.5, -60, 8.5, 5.0f, Level.ExplosionInteraction.TNT);
		test("detonate.control", () -> require(level.getBlockState(CONTROL).isAir(), "the explosion left the control block"));
		test("detonate.spare", () -> require(level.getBlockState(SPARED).is(Blocks.DIRT), "the explosion took the spared block"));
		test("brewing.forge", () -> {
			var brewing = server.potionBrewing();
			ItemStack dirt = new ItemStack(Items.DIRT), diamond = new ItemStack(Items.DIAMOND);
			require(brewing.isIngredient(diamond) && brewing.hasMix(dirt, diamond), "the MinecraftForge recipe is not in the brewing registry");
			ItemStack out = brewing.mix(diamond, dirt);
			require(out.is(Items.EMERALD), "the MinecraftForge recipe brewed " + out);
		});
		finish();
	}

	private static final List<net.minecraft.world.entity.Entity> spawned = new ArrayList<>();

	private static FakePlayer player(ServerLevel level, String uuid, String name) {
		FakePlayer player = new FakePlayer(level, new GameProfile(UUID.fromString(uuid), name));
		player.snapTo(0.5, -60, 0.5);
		return player;
	}

	private static LivingEntity pig(ServerLevel level, String tag) {
		LivingEntity pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
		require(pig != null, "pig creation");
		pig.snapTo(2.5 + spawned.size() * 2, -60, 20.5);
		if (tag != null) pig.addTag(tag);
		require(level.addFreshEntity(pig), "pig insertion");
		spawned.add(pig);
		return pig;
	}

	@FunctionalInterface private interface Probe { void run() throws Exception; }

	private static void test(String name, Probe probe) {
		boolean pass = false;
		String detail = "";
		try { probe.run(); pass = true; } catch (Throwable failure) { detail = failure.toString(); failure.printStackTrace(); }
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("name", name); row.put("pass", pass); row.put("detail", detail);
		cases.add(row);
		System.out.println("[M48Server] " + (pass ? "PASS " : "FAIL ") + name + (pass ? "" : " — " + detail));
	}

	private static void require(boolean condition, String detail) {
		if (!condition) throw new IllegalStateException(detail);
	}

	private static void finish() {
		try {
			for (var entity : spawned) entity.discard();
			Map<String, Object> report = new LinkedHashMap<>();
			report.put("phase", System.getProperty("forbric.serverEventsPhase"));
			report.put("cases", cases);
			Path path = Path.of(System.getProperty("forbric.serverEventsProbe"));
			Files.createDirectories(path.toAbsolutePath().getParent());
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
			System.out.println("[M48Server] RESULT " + cases.stream().filter(c -> Boolean.TRUE.equals(c.get("pass"))).count() + "/" + cases.size());
		} catch (Exception failure) {
			failure.printStackTrace();
		} finally {
			server.halt(false);
		}
	}
}
