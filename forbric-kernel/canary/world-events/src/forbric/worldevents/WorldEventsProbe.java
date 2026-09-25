package forbric.worldevents;

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
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingConversionEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.event.entity.player.CriticalHitEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * MinecraftForge world and entity listeners driven through the merged game: what each observer hears, and whether each
 * answer — a veto, a changed value, a forced decision — reaches the game, beside untouched controls.
 */
@Mod("forbricworldevents")
public final class WorldEventsProbe {
	private static final List<Map<String, Object>> cases = new ArrayList<>();
	private static final Map<String, Integer> heard = new LinkedHashMap<>();
	private static final BlockPos FARM = new BlockPos(20, -61, 20), FARM_KEPT = new BlockPos(22, -61, 20);
	private static final BlockPos TILL = new BlockPos(24, -61, 20), TILL_KEPT = new BlockPos(26, -61, 20);
	private static MinecraftServer server;
	private static int ticks;
	private static FakePlayer player;
	private static LivingEntity expiring, leaving;

	private static void hear(String what) { heard.merge(what, 1, Integer::sum); }
	private static boolean tagged(net.minecraft.world.entity.Entity entity, String tag) { return entity != null && entity.entityTags().contains(tag); }

	public WorldEventsProbe(IEventBus bus) {
		ChunkEvent.Load.BUS.addListener((Consumer<ChunkEvent.Load>) e -> hear("chunk.load"));
		EntityLeaveLevelEvent.BUS.addListener((Consumer<EntityLeaveLevelEvent>) e -> { if (tagged(e.getEntity(), "m49_leave")) hear("entity.leave"); });
		EntityEvent.EnteringSection.BUS.addListener((Consumer<EntityEvent.EnteringSection>) e -> { if (tagged(e.getEntity(), "m49_move")) hear("entity.section"); });
		PlayerWakeUpEvent.BUS.addListener((Consumer<PlayerWakeUpEvent>) e -> hear("player.wakeup"));
		TagsUpdatedEvent.BUS.addListener((Consumer<TagsUpdatedEvent>) e -> hear("tags.updated"));
		MobEffectEvent.Added.BUS.addListener((Consumer<MobEffectEvent.Added>) e -> { if (tagged(e.getEntity(), "m49_effect")) hear("effect.added"); });
		MobEffectEvent.Expired.BUS.addListener((Consumer<MobEffectEvent.Expired>) e -> { if (tagged(e.getEntity(), "m49_effect")) hear("effect.expired"); });
		MobEffectEvent.Applicable.BUS.addListener((Consumer<MobEffectEvent.Applicable>) e -> {
			if (tagged(e.getEntity(), "m49_immune") && e.getEffectInstance().is(MobEffects.SLOWNESS)) e.setResult(Result.DENY);
		});
		LivingConversionEvent.Pre.BUS.addListener((Predicate<LivingConversionEvent.Pre>) e -> tagged(e.getEntity(), "m49_noconvert"));
		LivingConversionEvent.Post.BUS.addListener((Consumer<LivingConversionEvent.Post>) e -> {
			hear("conversion.post");
			if (tagged(e.getEntity(), "m49_drown")) hear("drown.forge");
		});
		// NeoForge's own Post, as a NeoForge mod hears it: the merged Zombie's lambdas were MinecraftForge's.
		NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.living.LivingConversionEvent.Post.class, e -> {
			if (tagged(e.getEntity(), "m49_drown")) hear("drown.neo");
		});
		ProjectileImpactEvent.BUS.addListener((Consumer<ProjectileImpactEvent>) e -> {
			if (tagged(e.getProjectile(), "m49_nohit")) e.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
		});
		BlockEvent.FarmlandTrampleEvent.BUS.addListener((Predicate<BlockEvent.FarmlandTrampleEvent>) e -> e.getPos().equals(FARM_KEPT));
		CommandEvent.BUS.addListener((Predicate<CommandEvent>) e -> e.getParseResults().getReader().getString().contains("diamond_block"));
		PlayerInteractEvent.EntityInteractSpecific.BUS.addListener((Predicate<PlayerInteractEvent.EntityInteractSpecific>) e -> {
			if (!tagged(e.getTarget(), "m49_interact")) return false;
			hear("interact.specific");
			e.setCancellationResult(InteractionResult.SUCCESS);
			return true;
		});
		LivingHealEvent.BUS.addListener((Predicate<LivingHealEvent>) e -> {
			if (tagged(e.getEntity(), "m49_healx2")) e.setAmount(e.getAmount() * 2);
			return tagged(e.getEntity(), "m49_noheal");
		});
		LivingEvent.LivingVisibilityEvent.BUS.addListener((Consumer<LivingEvent.LivingVisibilityEvent>) e -> {
			if (tagged(e.getEntity(), "m49_hidden")) e.modifyVisibility(0.5);
		});
		CriticalHitEvent.BUS.addListener((Consumer<CriticalHitEvent>) e -> { if (tagged(e.getTarget(), "m49_crit")) e.setResult(Result.ALLOW); });
		BlockEvent.BlockToolModificationEvent.BUS.addListener((Predicate<BlockEvent.BlockToolModificationEvent>) e -> e.getPos().equals(TILL_KEPT));
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
			if (event.getServer() != server && server != null) return;
			ticks++;
			if (ticks == 20) { server = event.getServer(); act(server.overworld()); }
			if (ticks == 40) { check(server.overworld()); finish(); }
		});
		System.out.println("[M49World] REGISTERED MinecraftForge world listeners");
	}

	private static void act(ServerLevel level) {
		// No player is connected, so nothing here ticks or is tracked unless its chunks are held.
		for (int x = 0; x <= 2; x++) for (int z = 0; z <= 3; z++) level.setChunkForced(x, z, true);
		player = new FakePlayer(level, new GameProfile(UUID.fromString("5d1e7a2c-47a0-4e8b-9a61-2f0c3b8e6d50"), "WorldEvents"));
		player.setGameMode(GameType.SURVIVAL);
		player.snapTo(0.5, -60, 0.5);
		test("chunk.load", () -> require(heard.getOrDefault("chunk.load", 0) > 0, "no MinecraftForge chunk load heard"));
		test("tags.updated", () -> require(heard.getOrDefault("tags.updated", 0) > 0, "no MinecraftForge tag reload heard"));
		test("entity.section", () -> {
			LivingEntity pig = pig(level, "m49_move");
			pig.setPos(pig.getX() + 32, pig.getY(), pig.getZ());
			require(heard.getOrDefault("entity.section", 0) > 0, "a section move was not heard");
		});
		test("player.wakeup", () -> { player.stopSleepInBed(true, true); require(heard.getOrDefault("player.wakeup", 0) > 0, "waking was not heard"); });
		test("effect.added", () -> {
			expiring = pig(level, "m49_effect");
			expiring.addEffect(new MobEffectInstance(MobEffects.SPEED, 2));
			require(heard.getOrDefault("effect.added", 0) > 0, "an added effect was not heard");
		});
		leaving = pig(level, "m49_leave");
		test("applicable.control", () -> require(pig(level, null).addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100)), "an untouched effect did not apply"));
		test("applicable.deny", () -> {
			LivingEntity pig = pig(level, "m49_immune");
			require(!pig.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100)) && !pig.hasEffect(MobEffects.SLOWNESS), "a denied effect applied");
		});
		test("conversion.control", () -> {
			LivingEntity pig = pig(level, null);
			((net.minecraft.world.entity.animal.pig.Pig) pig).thunderHit(level, EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.COMMAND));
			require(pig.isRemoved(), "an untouched pig did not convert");
		});
		test("conversion.post", () -> require(heard.getOrDefault("conversion.post", 0) > 0, "the finished conversion was not heard"));
		test("drown.neo", () -> {
			LivingEntity zombie = drown(level);
			require(zombie.isRemoved() && heard.getOrDefault("drown.neo", 0) == 1, "NeoForge heard the zombie drown "
					+ heard.getOrDefault("drown.neo", 0) + " time(s)");
		});
		test("drown.forge", () -> require(heard.getOrDefault("drown.forge", 0) == 1, "MinecraftForge heard the zombie drown "
				+ heard.getOrDefault("drown.forge", 0) + " time(s)"));
		test("conversion.veto", () -> {
			LivingEntity pig = pig(level, "m49_noconvert");
			((net.minecraft.world.entity.animal.pig.Pig) pig).thunderHit(level, EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.COMMAND));
			require(!pig.isRemoved(), "a vetoed pig converted");
		});
		test("projectile.control", () -> {
			Snowball ball = new Snowball(level, 0, -58, 0, new ItemStack(Items.SNOWBALL));
			require(!EventHooks.onProjectileImpact(ball, new EntityHitResult(pig(level, null))), "an untouched impact was cancelled");
		});
		test("projectile.cancel", () -> {
			Snowball ball = new Snowball(level, 0, -58, 0, new ItemStack(Items.SNOWBALL));
			ball.addTag("m49_nohit");
			require(EventHooks.onProjectileImpact(ball, new EntityHitResult(pig(level, null))), "a stopped impact went on");
		});
		test("trample.control", () -> {
			level.setBlock(FARM, Blocks.FARMLAND.defaultBlockState(), 2);
			Blocks.FARMLAND.fallOn(level, level.getBlockState(FARM), FARM, pig(level, null), 10);
			require(level.getBlockState(FARM).is(Blocks.DIRT), "untouched farmland was not trampled");
		});
		test("trample.cancel", () -> {
			level.setBlock(FARM_KEPT, Blocks.FARMLAND.defaultBlockState(), 2);
			Blocks.FARMLAND.fallOn(level, level.getBlockState(FARM_KEPT), FARM_KEPT, pig(level, null), 10);
			require(level.getBlockState(FARM_KEPT).is(Blocks.FARMLAND), "protected farmland was trampled");
		});
		test("command.control", () -> {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "setblock 28 -61 30 minecraft:gold_block");
			require(level.getBlockState(new BlockPos(28, -61, 30)).is(Blocks.GOLD_BLOCK), "an untouched command did not run");
		});
		test("command.cancel", () -> {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "setblock 29 -61 30 minecraft:diamond_block");
			require(!level.getBlockState(new BlockPos(29, -61, 30)).is(Blocks.DIAMOND_BLOCK), "a refused command ran");
		});
		test("interact.specific", () -> {
			LivingEntity pig = pig(level, "m49_interact");
			InteractionResult result = player.interactOn(pig, InteractionHand.MAIN_HAND, new Vec3(0, 0.5, 0));
			require(heard.getOrDefault("interact.specific", 0) == 1 && result == InteractionResult.SUCCESS, "the specific interaction was heard "
					+ heard.getOrDefault("interact.specific", 0) + " time(s), result " + result);
		});
		test("heal.control", () -> { LivingEntity pig = hurtPig(level, null); pig.heal(2); require(pig.getHealth() == 7, "an untouched heal gave " + (pig.getHealth() - 5)); });
		test("heal.cancel", () -> { LivingEntity pig = hurtPig(level, "m49_noheal"); pig.heal(2); require(pig.getHealth() == 5, "a refused heal gave " + (pig.getHealth() - 5)); });
		test("heal.change", () -> { LivingEntity pig = hurtPig(level, "m49_healx2"); pig.heal(2); require(pig.getHealth() == 9, "a doubled heal gave " + (pig.getHealth() - 5)); });
		test("visibility.halve", () -> {
			double control = pig(level, null).getVisibilityPercent(player), hidden = pig(level, "m49_hidden").getVisibilityPercent(player);
			require(hidden == control * 0.5, "visibility " + hidden + " against " + control);
		});
		test("critical.force", () -> {
			var event = CommonHooks.fireCriticalHit(player, pig(level, "m49_crit"), false, 1.0f);
			require(event.isCriticalHit(), "a forced critical hit was not critical");
		});
		test("tool.control", () -> require(till(level, TILL).is(Blocks.FARMLAND), "untouched dirt was not tilled"));
		test("tool.veto", () -> require(till(level, TILL_KEPT).is(Blocks.DIRT), "vetoed dirt was tilled"));
	}

	private static void check(ServerLevel level) {
		test("effect.expired", () -> require(heard.getOrDefault("effect.expired", 0) > 0 && !expiring.hasEffect(MobEffects.SPEED), "an expired effect was not heard"));
		test("entity.leave", () -> {
			// Discarded a second after it was added, once the held chunk tracks it: leaving is only posted for a tracked entity.
			leaving.discard();
			require(heard.getOrDefault("entity.leave", 0) > 0, "an entity leaving the level was not heard");
		});
	}

	/** A zombie turned into a drowned the way drowning does it (Zombie.convertToZombieType), once. */
	private static LivingEntity drown(ServerLevel level) throws Exception {
		LivingEntity zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
		require(zombie != null, "zombie creation");
		zombie.snapTo(20.5, -60, 40.5);
		zombie.addTag("m49_drown");
		require(level.addFreshEntity(zombie), "zombie insertion");
		spawned.add(zombie);
		var convert = net.minecraft.world.entity.monster.zombie.Zombie.class.getDeclaredMethod("convertToZombieType",
				ServerLevel.class, net.minecraft.world.entity.EntityType.class);
		convert.setAccessible(true);
		convert.invoke(zombie, level, EntityTypes.DROWNED);
		return zombie;
	}

	private static net.minecraft.world.level.block.state.BlockState till(ServerLevel level, BlockPos pos) {
		level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
		level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 2);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_HOE));
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
		Items.IRON_HOE.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
		return level.getBlockState(pos);
	}

	private static final List<net.minecraft.world.entity.Entity> spawned = new ArrayList<>();

	private static LivingEntity pig(ServerLevel level, String tag) {
		LivingEntity pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
		require(pig != null, "pig creation");
		pig.snapTo(2.5 + (spawned.size() % 8) * 2, -60, 40.5 + (spawned.size() / 8) * 2);
		if (tag != null) pig.addTag(tag);
		require(level.addFreshEntity(pig), "pig insertion");
		spawned.add(pig);
		return pig;
	}

	private static LivingEntity hurtPig(ServerLevel level, String tag) {
		LivingEntity pig = pig(level, tag);
		pig.setHealth(5);
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
		System.out.println("[M49World] " + (pass ? "PASS " : "FAIL ") + name + (pass ? "" : " — " + detail));
	}

	private static void require(boolean condition, String detail) {
		if (!condition) throw new IllegalStateException(detail);
	}

	private static void finish() {
		try {
			for (var entity : spawned) if (!entity.isRemoved()) entity.discard();
			ServerLevel level = server.overworld();
			for (int x = 0; x <= 2; x++) for (int z = 0; z <= 3; z++) level.setChunkForced(x, z, false);
			Map<String, Object> report = new LinkedHashMap<>();
			report.put("phase", System.getProperty("forbric.worldEventsPhase"));
			report.put("cases", cases);
			report.put("heard", heard);
			Path path = Path.of(System.getProperty("forbric.worldEventsProbe"));
			Files.createDirectories(path.toAbsolutePath().getParent());
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
			System.out.println("[M49World] RESULT " + cases.stream().filter(c -> Boolean.TRUE.equals(c.get("pass"))).count() + "/" + cases.size());
		} catch (Exception failure) {
			failure.printStackTrace();
		} finally {
			server.halt(false);
		}
	}
}
