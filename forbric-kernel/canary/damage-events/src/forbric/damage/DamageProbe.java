package forbric.damage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * MinecraftForge damage listeners the way Tombstone writes them, driven through the merged game's own damage methods:
 * an attack veto, a halved and a cancelled hurt, a lethal hit turned harmless in LivingDamageEvent (a Voodoo Poppet),
 * the post-absorption amount that event sees, a player's zero-damage attack (a snowball) and its one attack event per
 * hit, a cancelled knockback and a cancelled fall — each beside an untouched control.
 */
@Mod("forbricdamageprobe")
public final class DamageProbe {
	private static final List<Map<String, Object>> cases = new ArrayList<>();
	private static MinecraftServer server;
	private static int ticks;
	private static int playerAttacks, zeroAttacks;
	private static float damageSeen = -1;

	public DamageProbe(IEventBus bus) {
		LivingAttackEvent.BUS.addListener((Predicate<LivingAttackEvent>) event -> {
			if (event.getEntity() instanceof FakePlayer) {
				playerAttacks++;
				if (event.getAmount() == 0) zeroAttacks++;
			}
			return event.getEntity().entityTags().contains("m47_attack");
		});
		LivingHurtEvent.BUS.addListener((Predicate<LivingHurtEvent>) event -> {
			if (event.getEntity().entityTags().contains("m47_halve")) event.setAmount(event.getAmount() / 2);
			return event.getEntity().entityTags().contains("m47_hurtcancel");
		});
		LivingDamageEvent.BUS.addListener((Predicate<LivingDamageEvent>) event -> {
			LivingEntity entity = event.getEntity();
			if (entity.entityTags().contains("m47_absorb")) damageSeen = event.getAmount();
			if (entity.entityTags().contains("m47_saved") && entity.getHealth() <= event.getAmount()) event.setAmount(0);
			return false;
		});
		LivingKnockBackEvent.BUS.addListener((Predicate<LivingKnockBackEvent>) event ->
				event.getEntity().entityTags().contains("m47_noknock"));
		LivingFallEvent.BUS.addListener((Predicate<LivingFallEvent>) event ->
				event.getEntity().entityTags().contains("m47_nofall"));
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
			if (server != null || ++ticks < 20) return;
			server = event.getServer();
			run(server.overworld());
		});
		System.out.println("[M47Damage] REGISTERED MinecraftForge damage listeners");
	}

	private static void run(ServerLevel level) {
		DamageSource generic = level.damageSources().generic();
		test("attack.control", () -> {
			LivingEntity pig = pig(level, null);
			pig.hurtServer(level, generic, 4);
			require(pig.getHealth() == 6, "an untouched hit took " + (10 - pig.getHealth()));
		});
		test("attack.veto", () -> {
			LivingEntity pig = pig(level, "m47_attack");
			require(!pig.hurtServer(level, generic, 4) && pig.getHealth() == 10, "the vetoed attack landed: health " + pig.getHealth());
		});
		test("hurt.halve", () -> {
			LivingEntity pig = pig(level, "m47_halve");
			pig.hurtServer(level, generic, 4);
			require(pig.getHealth() == 8, "a halved hurt took " + (10 - pig.getHealth()));
		});
		test("hurt.cancel", () -> {
			LivingEntity pig = pig(level, "m47_hurtcancel");
			pig.hurtServer(level, generic, 4);
			require(pig.getHealth() == 10, "a cancelled hurt took " + (10 - pig.getHealth()));
		});
		test("damage.lethal", () -> {
			LivingEntity pig = pig(level, "m47_saved");
			pig.hurtServer(level, generic, 100);
			require(pig.isAlive() && pig.getHealth() == 10, "the lethal hit was not turned away: health " + pig.getHealth());
		});
		test("damage.absorption", () -> {
			LivingEntity pig = pig(level, "m47_absorb");
			pig.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION).setBaseValue(4);
			pig.setAbsorptionAmount(4);
			require(pig.getAbsorptionAmount() == 4, "the pig holds no absorption");
			damageSeen = -1;
			pig.hurtServer(level, generic, 6);
			require(damageSeen == 2 && pig.getHealth() == 8, "LivingDamageEvent saw " + damageSeen + " (want 2, after absorption), health " + pig.getHealth());
		});
		FakePlayer player = new FakePlayer(level, new GameProfile(UUID.fromString("5d1e7a2c-47a0-4e8b-9a61-2f0c3b8e6d47"), "DamageProbe")) {
			@Override public boolean isInvulnerableTo(ServerLevel l, DamageSource s) { return false; }
		};
		player.setGameMode(GameType.SURVIVAL);
		player.snapTo(0.5, -60, 0.5);
		level.addNewPlayer(player);
		try {
			test("player.attack.zero", () -> {
				playerAttacks = zeroAttacks = 0;
				player.hurtServer(level, generic, 0);
				require(zeroAttacks == 1, "a zero-damage hit on a player was asked " + zeroAttacks + " time(s)");
			});
			test("player.attack.once", () -> {
				playerAttacks = 0;
				player.invulnerableTime = 0;
				player.hurtServer(level, generic, 2);
				require(playerAttacks == 1 && player.getHealth() == 18, "one hit on a player was asked " + playerAttacks + " time(s), health " + player.getHealth());
			});
		} finally {
			level.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
		}
		test("knockback.control", () -> {
			LivingEntity pig = pig(level, null);
			pig.knockback(1.0, 1, 0, level.damageSources().generic(), 0f);
			require(pig.getDeltaMovement().lengthSqr() > 0, "an untouched knockback did not move the pig");
		});
		test("knockback.cancel", () -> {
			LivingEntity pig = pig(level, "m47_noknock");
			pig.knockback(1.0, 1, 0, level.damageSources().generic(), 0f);
			require(pig.getDeltaMovement().lengthSqr() == 0, "a cancelled knockback moved the pig");
		});
		test("fall.control", () -> {
			LivingEntity pig = pig(level, null);
			pig.causeFallDamage(10, 1, level.damageSources().fall());
			require(pig.getHealth() < 10, "an untouched fall did no damage");
		});
		test("fall.cancel", () -> {
			LivingEntity pig = pig(level, "m47_nofall");
			pig.causeFallDamage(10, 1, level.damageSources().fall());
			require(pig.getHealth() == 10, "a cancelled fall took " + (10 - pig.getHealth()));
		});
		finish();
	}

	private static final List<LivingEntity> spawned = new ArrayList<>();

	private static LivingEntity pig(ServerLevel level, String tag) {
		LivingEntity pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
		require(pig != null, "pig creation");
		pig.snapTo(2.5 + spawned.size() * 2, -60, 2.5);
		pig.setDeltaMovement(0, 0, 0);
		if (tag != null) pig.addTag(tag);
		require(level.addFreshEntity(pig), "pig insertion");
		require(pig.getHealth() == 10, "a pig starts at 10 health, not " + pig.getHealth());
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
		System.out.println("[M47Damage] " + (pass ? "PASS " : "FAIL ") + name + (pass ? "" : " — " + detail));
	}

	private static void require(boolean condition, String detail) {
		if (!condition) throw new IllegalStateException(detail);
	}

	private static void finish() {
		try {
			for (LivingEntity entity : spawned) entity.discard();
			Map<String, Object> report = new LinkedHashMap<>();
			report.put("phase", System.getProperty("forbric.damagePhase"));
			report.put("cases", cases);
			Path path = Path.of(System.getProperty("forbric.damageProbe"));
			Files.createDirectories(path.toAbsolutePath().getParent());
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
			System.out.println("[M47Damage] RESULT " + cases.stream().filter(c -> Boolean.TRUE.equals(c.get("pass"))).count() + "/" + cases.size());
		} catch (Exception failure) {
			failure.printStackTrace();
		} finally {
			server.halt(false);
		}
	}
}
