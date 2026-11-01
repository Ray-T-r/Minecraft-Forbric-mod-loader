package forbric.fluidheadprobe;

import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.block.v1.FluidFlowEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Exercises the released HEAD handlers of LiquidBlock.shouldSpreadLiquid — fabric-block-api's FluidFlowEvents veto and,
 * when Create Fly is loaded, its lava + honey reaction — on placement and on a neighbour change, in a real server; only
 * staged in isolated gate worlds. Vanilla (and native Fabric) asks shouldSpreadLiquid in both LiquidBlock.onPlace and
 * neighborChanged, so each handler runs on both. Neighbours are set with flags 19 (neighbour updates, no shape updates:
 * a shape update schedules a fluid tick of its own, which no handler is asked about).
 */
@Mod("forbricfluidheadprobe")
public final class FluidHeadProbe {
	private static final int NEIGHBOURS = 1 | 2 | 16;
	private static MinecraftServer server;
	private static int ticks;
	private static final Set<BlockPos> DENIED = new HashSet<>();
	private static final List<Map<String, Object>> cases = new ArrayList<>();

	public FluidHeadProbe(IEventBus bus) {
		NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> { server = e.getServer(); server.overworld().setChunkForced(0, 0, true); });
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
			if (e.getServer() != server || ++ticks != 30) return;
			try { run(); } catch (Throwable t) { test("infrastructure", () -> { throw t; }); } finally { finish(); }
		});
	}

	interface Case { void run() throws Throwable; }

	private static void test(String name, Case body) {
		String error = "";
		DENIED.clear();
		try { body.run(); } catch (Throwable t) { error = t.toString(); t.printStackTrace(); }
		DENIED.clear();
		cases.add(Map.of("name", name, "pass", error.isEmpty(), "detail", error));
		System.out.println("[FluidHeadProbe] " + name + " " + (error.isEmpty() ? "PASS" : error));
	}

	private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

	private static BlockPos cell(ServerLevel w, int x) {
		BlockPos p = new BlockPos(x, 80, 10);
		for (BlockPos q : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 1, 1))) w.setBlock(q, Blocks.AIR.defaultBlockState(), 18);
		w.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 18);
		return p;
	}

	private static boolean ticked(ServerLevel w, BlockPos p) { return w.getFluidTicks().hasScheduledTick(p, Fluids.LAVA); }

	private static void run() {
		ServerLevel world = server.overworld();
		FluidFlowEvents.ALLOW.register((fluid, level, pos) -> !DENIED.contains(pos.immutable()));
		BlockState lava = Blocks.LAVA.defaultBlockState(), flowingLava = lava.setValue(LiquidBlock.LEVEL, 1);
		// Placement: NeoForge's onPlace asks vanilla's shouldSpreadLiquid, so the veto holds there with or without the kernel.
		test("place.veto", () -> {
			BlockPos p = cell(world, 2); DENIED.add(p);
			world.setBlock(p, lava, 3);
			require(!ticked(world, p), "a vetoed lava placement scheduled a fluid tick");
		});
		// A neighbour change: vanilla asks shouldSpreadLiquid again, so the veto holds; NeoForge asks its registry instead.
		test("neighbor.veto", () -> {
			BlockPos p = cell(world, 6); DENIED.add(p);
			world.setBlock(p, lava, 3);
			require(!ticked(world, p), "premise: the placement was vetoed");
			world.setBlock(p.east(), Blocks.STONE.defaultBlockState(), NEIGHBOURS);
			require(!ticked(world, p), "a vetoed lava cell scheduled a fluid tick on a neighbour change");
		});
		test("neighbor.veto.reaction", () -> {
			BlockPos p = cell(world, 10); DENIED.add(p);
			world.setBlock(p, lava, 3);
			world.setBlock(p.east(), Blocks.WATER.defaultBlockState(), NEIGHBOURS);
			require(world.getBlockState(p).is(Blocks.LAVA), "a vetoed lava cell reacted with water on a neighbour change: " + world.getBlockState(p));
		});
		// Not vetoed: the registry decides as written (vanilla's lava-meets-water rule).
		test("neighbor.allow.reaction", () -> {
			BlockPos p = cell(world, 14);
			world.setBlock(p, lava, 3);
			world.setBlock(p.east(), Blocks.WATER.defaultBlockState(), NEIGHBOURS);
			require(world.getBlockState(p).is(Blocks.OBSIDIAN), "result=" + world.getBlockState(p));
		});
		Fluid honey = BuiltInRegistries.FLUID.getValue(Identifier.fromNamespaceAndPath("create", "honey"));
		if (honey == null || honey == Fluids.EMPTY) return;
		BlockState honeyBlock = honey.defaultFluidState().createLegacyBlock();
		// Create Fly's HEAD reaction: flowing lava beside honey becomes limestone (its own registry, not NeoForge's).
		test("create.place.reaction", () -> {
			BlockPos p = cell(world, 18);
			world.setBlock(p.east(), honeyBlock, 18);
			world.setBlock(p, flowingLava, 3);
			require(world.getBlockState(p).is(BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", "limestone"))), "result=" + world.getBlockState(p));
		});
		test("create.neighbor.reaction", () -> {
			BlockPos p = cell(world, 22);
			world.setBlock(p, flowingLava, 3);
			require(world.getBlockState(p).is(Blocks.LAVA), "premise: flowing lava alone stays lava: " + world.getBlockState(p));
			world.setBlock(p.east(), honeyBlock, NEIGHBOURS);
			require(world.getBlockState(p).is(BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", "limestone"))), "result=" + world.getBlockState(p));
		});
	}

	private static void finish() {
		try { Files.writeString(Path.of("fluid-head-probe.json"), new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("cases", cases))); }
		catch (Exception e) { e.printStackTrace(); }
		server.halt(false);
	}
}
