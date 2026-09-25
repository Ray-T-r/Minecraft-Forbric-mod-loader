package forbric.newdriver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.particle.v1.FabricBlockParticleOption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * A mob's landing dust and sprint dust, made by the game's own methods on a zombie standing on stone: NeoForge's
 * position on the option (control) and fabric-particles' ground block beside it (FabricBlockParticleOption).
 */
@Mod("forbricnewdriver")
public final class NewDriver {
	private static final List<Map<String, Object>> CASES = new ArrayList<>();
	private static MinecraftServer server;
	private static int ticks;

	public NewDriver(IEventBus bus) {
		NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> server = e.getServer());
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
			if (e.getServer() != server || ++ticks != 20) return;
			try { run(); } catch (Exception failure) { test("driver", () -> "threw " + failure); } finally { finish(); }
		});
	}

	interface Case { String run() throws Throwable; }

	private static void test(String name, Case body) {
		String failure;
		try { failure = body.run(); } catch (Throwable t) { failure = "threw " + t; }
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("name", name); row.put("pass", failure == null); row.put("detail", failure == null ? "" : failure);
		CASES.add(row);
	}

	private static String expect(boolean ok, String what) { return ok ? null : what; }

	private static BlockPos fabricPos(BlockParticleOption option) {
		return option == null ? null : ((FabricBlockParticleOption) (Object) option).getBlockPos();
	}

	private static void run() throws Exception {
		ServerLevel level = server.overworld();
		level.setChunkForced(0, 0, true);
		BlockPos ground = new BlockPos(8, 99, 8);
		BlockState stone = Blocks.STONE.defaultBlockState();
		level.setBlock(ground, stone, 3);
		Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
		zombie.snapTo(8.5, 100, 8.5);
		zombie.setNoAi(true);
		zombie.setInvulnerable(true);
		level.addFreshEntity(zombie);

		System.getProperties().remove("forbric.m53.sent");
		zombie.fallDistance = 10;
		var fall = LivingEntity.class.getDeclaredMethod("checkFallDamage", double.class, boolean.class, BlockState.class, BlockPos.class);
		fall.setAccessible(true);
		fall.invoke(zombie, -1.0, true, stone, ground);
		BlockParticleOption landed = (BlockParticleOption) System.getProperties().get("forbric.m53.sent");
		test("fall.native", () -> expect(landed != null && ground.equals(landed.getPos()), "landing dust " + landed + " at " + (landed == null ? null : landed.getPos())));
		test("fall.fabric", () -> expect(landed != null && ground.equals(fabricPos(landed)), "fabric's ground block on landing dust: " + fabricPos(landed)));

		System.getProperties().remove("forbric.m53.added");
		var sprint = Entity.class.getDeclaredMethod("spawnSprintParticle");
		sprint.setAccessible(true);
		sprint.invoke(zombie);
		BlockPos on = zombie.getOnPosLegacy();
		BlockParticleOption ran = (BlockParticleOption) System.getProperties().get("forbric.m53.added");
		test("sprint.native", () -> expect(ran != null && on.equals(ran.getPos()), "sprint dust " + ran + " at " + (ran == null ? null : ran.getPos()) + ", standing on " + on));
		test("sprint.fabric", () -> expect(ran != null && on.equals(fabricPos(ran)), "fabric's ground block on sprint dust: " + fabricPos(ran)));
		zombie.discard();
		level.setChunkForced(0, 0, false);
	}

	private static void finish() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("phase", System.getProperty("forbric.newPhase"));
		out.put("cases", CASES);
		try {
			Files.writeString(Path.of(System.getProperty("forbric.newProbe")), new GsonBuilder().setPrettyPrinting().create().toJson(out));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		System.out.println("[M53New] RESULT " + CASES);
		server.halt(false);
	}
}
