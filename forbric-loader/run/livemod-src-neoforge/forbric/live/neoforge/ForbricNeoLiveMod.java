/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package forbric.live.neoforge;

import java.util.concurrent.atomic.AtomicInteger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Forbric tri-in-one B-5 canary (NeoForge side): a genuine NeoForge {@code @Mod} that counts NeoForge's OWN
 * {@code ServerTickEvent.Post} on the game bus. Staged alongside the traditional-Forge canary (forbriclive) on the
 * MERGED base, it proves BOTH ecosystems' server-tick listeners fire — NeoForge natively (its lifecycle owns
 * {@code MinecraftServer.tickServer}'s hook) and Forge via the Neo->Forge event bridge — and, since each counts one
 * increment per game tick, that the bridge does NOT double-fire (both reach "20 ticks" at the same game tick, not
 * 40). Compiled at assemble time against the NeoForge runtime; never redistributed.
 *
 * <p>It is also where the contracts the kernel gives BOTH Forge families now live. The MinecraftForge canary
 * (forbriclive) was the only fixture proving several of them — config load-once and the live file watcher, the
 * per-world SERVER config reaching a client, client registrations reaching their tables, a server data loader, the
 * scan index, a mixin config plugin, a mod finding its own container — and MinecraftForge is being removed, so each
 * of those is asked again here through NeoForge's own API, in the MinecraftForge canary's log shape with this
 * canary's tag. The gates assert both until the MinecraftForge checks are deleted (gate-m16, m21, m26, m28).
 */
@Mod("forbricneolive")
public class ForbricNeoLiveMod {
	private static final AtomicInteger TICKS = new AtomicInteger();

	public ForbricNeoLiveMod(IEventBus modBus) {
		System.out.println("[ForbricNeoLive] @Mod(\"forbricneolive\") constructed by the real NeoForge ModLoader");
		reportOwnContainer();
		reportForeignMods();
		// The config listeners first: the STARTUP config below opens at registration, and its Loading event has to
		// find them already on the bus.
		modBus.addListener(net.neoforged.fml.event.config.ModConfigEvent.Loading.class,
				event -> logConfig("LOADING", event.getConfig()));
		modBus.addListener(net.neoforged.fml.event.config.ModConfigEvent.Reloading.class,
				event -> logConfig("RELOADING", event.getConfig()));
		registerStartupConfig();
		registerSidedConfigs();
		registerSetupLifecycle(modBus);
		BLOCKS.register(modBus);
		System.out.println("[ForbricNeoLive/BLOCK] registered block forbricneolive:probe_block");
		registerReloadProbe();
		registerClient(modBus);
		reportScanIndex();
		NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.server.ServerStartedEvent.class, event -> {
			// The value, not a log line: Block.getId reads the blockstate→id map, and a state with no entry in it
			// answers 0, which is AIR — every block_update packet about this block would describe air.
			try {
				var state = PROBE_BLOCK.get().defaultBlockState();
				System.out.println("[ForbricNeoLive/BLOCKID] forbricneolive:probe_block id="
						+ net.minecraft.world.level.block.Block.getId(state)
						+ " roundTrip=" + net.minecraft.world.level.block.Block.stateById(
								net.minecraft.world.level.block.Block.getId(state)));
			} catch (Throwable failure) {
				System.out.println("[ForbricNeoLive/BLOCKID] read-back FAILED: " + failure);
			}
			// D1 twin: did this canary's neoforge:add_features biome modifier reach the live biome?
			try {
				var biomes = event.getServer().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
				var plains = biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value();
				var steps = plains.getGenerationSettings().features();
				int ores = net.minecraft.world.level.levelgen.GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
				var features = steps.size() > ores ? steps.get(ores) : net.minecraft.core.HolderSet.<net.minecraft.world.level.levelgen.placement.PlacedFeature>empty();
				boolean present = false;
				for (var holder : features) {
					if (holder.unwrapKey().map(k -> k.identifier().toString()).orElse("").equals("forbricneolive:probe")) present = true;
				}
				System.out.println("[ForbricNeoLive/WORLDGEN] probe ran: plains has " + features.size() + " feature(s) in underground_ores");
				System.out.println("[ForbricNeoLive/WORLDGEN] plains underground_ores has forbricneolive:probe = " + present);
			} catch (Throwable failure) {
				System.out.println("[ForbricNeoLive/WORLDGEN] probe FAILED: " + failure);
			}
			// The Neo->Forge block-break bridge, driven from the one side that can name the NeoForge event.
			//
			// ServerPlayerGameMode posts exactly this event and branches on isCanceled() afterwards, so posting it
			// here reproduces the seam a MinecraftForge protection mod depends on. The Forge canary refuses it
			// (its listener writes DENY for a player-less probe), and the refusal must come back as isCanceled()
			// on THIS event — a forward that observes but does not carry the veto gives that mod a listener which
			// runs, decides and is ignored, which looks like it works.
			try {
				var level = event.getServer().overworld();
				var pos = new net.minecraft.core.BlockPos(0, 64, 0);
				// A real player, because MinecraftForge's BreakEvent constructor asks the player which tool it is
				// holding and NPEs on null before any listener runs. NeoForge's own fake player is what a mod
				// breaking a block on nobody's behalf uses, so it is also the honest stand-in here.
				var breaker = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
				var probe = new net.neoforged.neoforge.event.level.block.BreakBlockEvent(
						level, pos, level.getBlockState(pos), breaker);
				net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(probe);
				System.out.println("[ForbricNeoLive/BLOCKBREAK] posted BreakBlockEvent at " + pos
						+ " refused=" + probe.isCanceled());

				// The two click events, which carry a useBlock/useItem decision as well as a cancel. The Forge
				// canary refuses a different one on each, so a bridge that translated only one of the pair — or
				// translated it in the wrong direction — cannot pass both halves.
				var hit = new net.minecraft.world.phys.BlockHitResult(
						net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false);
				var right = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
						breaker, net.minecraft.world.InteractionHand.MAIN_HAND, pos, hit);
				net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(right);
				System.out.println("[ForbricNeoLive/INTERACT] right-click useBlock=" + right.getUseBlock().name()
						+ " useItem=" + right.getUseItem().name());

				var left = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock(
						breaker, pos, net.minecraft.core.Direction.UP,
						net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.START);
				net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(left);
				System.out.println("[ForbricNeoLive/INTERACT] left-click useBlock=" + left.getUseBlock().name()
						+ " useItem=" + left.getUseItem().name());

				var item = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem(
						breaker, net.minecraft.world.InteractionHand.MAIN_HAND);
				net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(item);
				System.out.println("[ForbricNeoLive/INTERACT] right-click-item refused=" + item.isCanceled());

				// Placing a block. The snapshot is taken BEFORE the block is placed and the event posted after, so
				// the one the bridge hands MinecraftForge has to carry the REPLACED state — a mod restoring it on
				// cancel must put back what was there, not what was just placed.
				var snapshot = net.neoforged.neoforge.common.util.BlockSnapshot.create(
						level.dimension(), level, pos);
				var placed = new net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent(
						snapshot, level.getBlockState(pos.below()), breaker);
				net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(placed);
				System.out.println("[ForbricNeoLive/PLACE] posted EntityPlaceEvent at " + pos
						+ " refused=" + placed.isCanceled());
			} catch (Throwable failure) {
				System.out.println("[ForbricNeoLive/BLOCKBREAK] probe FAILED: " + failure);
			}
			try {
				var structures = event.getServer().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
				var mineshaft = structures.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.MINESHAFT).value();
				var override = mineshaft.getModifiedStructureSettings().spawnOverrides().get(net.minecraft.world.entity.MobCategory.CREATURE);
				boolean present = false;
				if (override != null) {
					for (var weighted : override.spawns().unwrap()) {
						if (weighted.value().type() == net.minecraft.world.entity.EntityTypes.LLAMA) present = true;
					}
				}
				System.out.println("[ForbricNeoLive/WORLDGEN] structure probe ran: mineshaft creature override present = " + (override != null));
				System.out.println("[ForbricNeoLive/WORLDGEN] mineshaft creature override has minecraft:llama = " + present);
			} catch (Throwable failure) {
				System.out.println("[ForbricNeoLive/WORLDGEN] structure probe FAILED: " + failure);
			}
		});
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
			int n = TICKS.incrementAndGet();
			if (n == 20) {
				System.out.println("[ForbricNeoLive] 20 server ticks observed (NeoForge native) - the merged game loop "
						+ "posts NeoForge's ServerTickEvent to NeoForge mods");
				reportActiveContainerFallback();
			}
		});
	}

	/**
	 * A COMMON, a CLIENT and a per-world SERVER config, each with one value whose default the gates know.
	 *
	 * <p>They carry the contracts the MinecraftForge canary used to be the only fixture for, so that they survive
	 * the day that canary and its family are removed:
	 * <ul>
	 *   <li>COMMON is opened by the kernel's early pass, exactly once, and the carrier's own file watcher then reads
	 *       a live edit back (gate-m28). NeoForge's {@code registerConfig} opens STARTUP alone; without the kernel's
	 *       pass nothing ever opens COMMON, and reading {@code COMMON_PROBE} throws.</li>
	 *   <li>CLIENT is opened on the client and never on a dedicated server (gate-m16 and gate-m28).</li>
	 *   <li>SERVER is per-world: the gate writes a non-default value into the world before the server boots, and the
	 *       client of a dedicated server ends up reading THAT value only if NeoForge's configuration-phase sync ran
	 *       over the socket (gate-m16).</li>
	 * </ul>
	 */
	public static final net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<String> GREETING;
	public static final net.neoforged.neoforge.common.ModConfigSpec SERVER_SPEC;
	public static final net.neoforged.neoforge.common.ModConfigSpec.IntValue COMMON_PROBE;
	public static final net.neoforged.neoforge.common.ModConfigSpec COMMON_SPEC;
	public static final net.neoforged.neoforge.common.ModConfigSpec.IntValue CLIENT_PROBE;
	public static final net.neoforged.neoforge.common.ModConfigSpec CLIENT_SPEC;

	static {
		var b = new net.neoforged.neoforge.common.ModConfigSpec.Builder();
		GREETING = b.comment("set this in <world>/serverconfig before the server boots to test config sync")
				.define("greeting", "default");
		SERVER_SPEC = b.build();
		b = new net.neoforged.neoforge.common.ModConfigSpec.Builder();
		COMMON_PROBE = b.comment("change this while the server runs to test the native config watcher")
				.defineInRange("probe", 11, 0, 1000);
		COMMON_SPEC = b.build();
		b = new net.neoforged.neoforge.common.ModConfigSpec.Builder();
		CLIENT_PROBE = b.comment("client-only config load probe").defineInRange("probe", 17, 0, 1000);
		CLIENT_SPEC = b.build();
	}

	/** The spec is unloaded until someone loads or syncs it; reading it then throws. */
	static String greeting() {
		return SERVER_SPEC.isLoaded() ? GREETING.get() : "<unloaded>";
	}

	private static void registerSidedConfigs() {
		try {
			net.neoforged.fml.ModContainer container = net.neoforged.fml.ModLoadingContext.get().getActiveContainer();
			container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, SERVER_SPEC);
			container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, COMMON_SPEC);
			container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, CLIENT_SPEC);
			System.out.println("[ForbricNeoLive/CFG] registered SERVER config forbricneolive-server.toml (greeting default 'default')");
			System.out.println("[ForbricNeoLive/CFG] registered COMMON config forbricneolive-common.toml (probe default 11)");
			System.out.println("[ForbricNeoLive/CFG] registered CLIENT config forbricneolive-client.toml (probe default 17)");
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive/CFG] FAILED to register the sided configs: " + t);
		}
	}

	/**
	 * One line per Loading or Reloading event, in the MinecraftForge canary's exact shape, so a gate asserts the two
	 * families with the same pattern and only the tag differs. The value is read out of the SPEC, never off the
	 * event: an event delivered for a config whose spec never took the values would still print, and "loaded" is
	 * what tells the two apart.
	 */
	private static void logConfig(String phase, net.neoforged.fml.config.ModConfig cfg) {
		String path;
		try {
			path = String.valueOf(cfg.getFullPath());
		} catch (RuntimeException syncedHasNoFile) {
			path = "<synced, no file>";
		}
		switch (cfg.getType()) {
			case SERVER -> System.out.println("[ForbricNeoLive/CFG] " + phase + " " + cfg.getFileName() + ": greeting="
					+ greeting() + " loaded=" + SERVER_SPEC.isLoaded() + " path=" + path);
			case COMMON, CLIENT -> {
				boolean client = cfg.getType() == net.neoforged.fml.config.ModConfig.Type.CLIENT;
				var spec = client ? CLIENT_SPEC : COMMON_SPEC;
				var probe = client ? CLIENT_PROBE : COMMON_PROBE;
				System.out.println("[ForbricNeoLive/CFG] " + phase + " " + cfg.getFileName() + ": probe="
						+ (spec.isLoaded() ? probe.get() : "<unloaded>") + " loaded=" + spec.isLoaded() + " path=" + path);
			}
			default -> System.out.println("[ForbricNeoLive/CFG] " + phase + " " + cfg.getFileName() + " path=" + path);
		}
	}

	/**
	 * A block of this mod's own, so its blockstates have to be in the blockstate→id map that {@code Block.getId}
	 * and every {@code block_update} packet read.
	 *
	 * <p>The MinecraftForge canary carried this check because its family is constructed in a SECOND wave, inside
	 * {@code Minecraft.<init>}, after the kernel filled the map once. A NeoForge mod registers in the first wave,
	 * so this proves the first fill reaches a mod's block; the late wave that the refill exists for is what a Fabric
	 * client entrypoint registers, and this canary cannot stand in for that.
	 */
	private static final net.neoforged.neoforge.registries.DeferredRegister.Blocks BLOCKS =
			net.neoforged.neoforge.registries.DeferredRegister.createBlocks("forbricneolive");

	static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.Block> PROBE_BLOCK =
			BLOCKS.registerSimpleBlock("probe_block");

	/**
	 * Whether this mod finds its OWN container during its constructor, and is the active namespace there.
	 *
	 * <p>The shape a library mod's first line takes: {@code ModList.get().getModContainerById(id).orElseThrow()},
	 * narrowed to {@code FMLModContainer} for its bus, from a class initializer its constructor reaches — so a "not
	 * found" there is permanent for the run. The namespace too, because {@code getActiveNamespace()} answers
	 * "minecraft" rather than throwing when nothing is active, which files a mod's id-less registrations under the
	 * wrong mod in silence.
	 */
	private static void reportOwnContainer() {
		try {
			java.util.Optional<? extends net.neoforged.fml.ModContainer> own =
					net.neoforged.fml.ModList.get().getModContainerById("forbricneolive");
			net.neoforged.fml.javafmlmod.FMLModContainer fml =
					own.orElse(null) instanceof net.neoforged.fml.javafmlmod.FMLModContainer c ? c : null;
			System.out.println("[ForbricNeoLive/SELF] own container during ctor: present=" + own.isPresent()
					+ " fmlContainer=" + (fml != null)
					+ " eventBus=" + (fml != null && fml.getEventBus() != null)
					+ " activeNamespace=" + net.neoforged.fml.ModLoadingContext.get().getActiveNamespace());
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive/SELF] own container during ctor FAILED: " + t);
		}
	}

	/**
	 * NeoForge's documented way to register a JSON data loader: {@code AddServerReloadListenersEvent} on the game
	 * bus, then a listener over {@code data/<ns>/forbricneolive_probe/*.json}.
	 *
	 * <p>On the merged base this event is not posted by the game: {@code ReloadableServerResources} hands its reload
	 * to the kernel ({@code KernelForgeReload}), which asks NeoForge's {@code onResourceReload} on the way. So this
	 * is the probe that says that route still reaches a NeoForge mod — and on the genuine base, where NeoForge
	 * posts it from its own patch, it is the probe that says nothing was lost when the route went away.
	 */
	private static void registerReloadProbe() {
		NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.AddServerReloadListenersEvent.class, event -> {
			System.out.println("[ForbricNeoLive/RELOAD] AddServerReloadListenersEvent DELIVERED to a NeoForge mod");
			String context;
			try {
				var ctx = event.getConditionContext();
				context = ctx == net.neoforged.neoforge.common.conditions.ICondition.IContext.EMPTY ? "EMPTY"
						: "live " + ctx.getClass().getName();
			} catch (Throwable failure) {
				context = "FAILED " + failure;
			}
			System.out.println("[ForbricNeoLive/RELOAD] context=" + context);
			event.addListener(net.minecraft.resources.Identifier.fromNamespaceAndPath("forbricneolive", "probe"),
					new ProbeReloadListener());
		});
	}

	/** A data loader over data/<ns>/forbricneolive_probe/*.json — the shape every mod's custom data folder uses. */
	static final class ProbeReloadListener
			extends net.minecraft.server.packs.resources.SimplePreparableReloadListener<Integer> {
		@Override
		protected Integer prepare(net.minecraft.server.packs.resources.ResourceManager manager,
				net.minecraft.util.profiling.ProfilerFiller profiler) {
			return manager.listResources("forbricneolive_probe", id -> id.getPath().endsWith(".json")).size();
		}

		@Override
		protected void apply(Integer files, net.minecraft.server.packs.resources.ResourceManager manager,
				net.minecraft.util.profiling.ProfilerFiller profiler) {
			System.out.println("[ForbricNeoLive/RELOAD] reload listener ran over " + files + " file(s)");
		}
	}

	/** The field the index is asked about. Its value is irrelevant; the ANNOTATION is the fixture. */
	@NeoLiveScanned(kind = NeoLiveScanned.Kind.SECOND, note = "probe")
	public static String PROBE = "probe";

	/**
	 * Whether this mod finds its OWN annotation in the scan index, through both readers a NeoForge mod has, and
	 * whether an enum member there has NeoForge's own shape.
	 *
	 * <p>{@code ModList.get().getAllScanData()} is how JEI finds {@code @JeiPlugin}, Jade its providers and
	 * Sophisticated Core its plugins; it was an EMPTY index for a stretch, and JEI then threw "plugins must not be
	 * empty" out of its own constructor. The walk over {@code LoadingModList.getModFiles()} is the second reader —
	 * RollingGate finds its rule containers that way, from its constructor — and the kernel builds a different file
	 * object for each list, so each is asked separately. And the SHAPE: NeoForge stores an enum member as
	 * {@code ModAnnotation.EnumHolder}, MinecraftForge as {@code ModFileScanData.EnumData}, and a bare String would
	 * also print "SECOND"; the constant is read through the holder's own accessor so only the right shape passes.
	 */
	private static void reportScanIndex() {
		try {
			int files = 0;
			int annotations = 0;
			for (net.neoforged.neoforgespi.language.ModFileScanData scan : net.neoforged.fml.ModList.get().getAllScanData()) {
				files++;
				annotations += scan.getAnnotations().size();
				reportOwnAnnotation("ModList", scan);
			}
			System.out.println("[ForbricNeoLive/SCAN] ModList.getAllScanData(): " + files + " file(s), "
					+ annotations + " annotation(s)");
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive/SCAN] could not read the ModList scan index: " + t);
		}
		try {
			var file = net.neoforged.fml.loading.FMLLoader.getCurrent().getLoadingModList().getModFileById("forbricneolive");
			if (file == null) {
				System.out.println("[ForbricNeoLive/SCAN] LoadingModList has no file for forbricneolive");
			} else {
				reportOwnAnnotation("LoadingModList", file.getFile().getScanResult());
			}
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive/SCAN] could not read the LoadingModList scan index: " + t);
		}
	}

	private static void reportOwnAnnotation(String reader, net.neoforged.neoforgespi.language.ModFileScanData scan) {
		for (net.neoforged.neoforgespi.language.ModFileScanData.AnnotationData data : scan.getAnnotations()) {
			if (!data.annotationType().getDescriptor().equals("Lforbric/live/neoforge/NeoLiveScanned;")) continue;
			System.out.println("[ForbricNeoLive/SCAN] " + reader + " found its own @NeoLiveScanned on "
					+ data.targetType() + " " + data.memberName() + " of " + data.clazz().getClassName());
			Object kind = data.annotationData().get("kind");
			String constant = "?";
			if (kind != null) {
				try {
					constant = String.valueOf(kind.getClass().getMethod("value").invoke(kind));
				} catch (Throwable notAHolder) {
					constant = "<no value() accessor: " + notAHolder + ">";
				}
			}
			System.out.println("[ForbricNeoLive/SCAN] " + reader + " enum member kind is " + (kind == null ? "ABSENT"
					: kind.getClass().getName() + " with value " + constant));
			System.out.println("[ForbricNeoLive/SCAN] " + reader + " string member note is "
					+ data.annotationData().get("note"));
		}
	}

	/** Link client-only event types only on the client, through a class-name boundary. */
	private static void registerClient(IEventBus modBus) {
		if (net.neoforged.fml.loading.FMLEnvironment.getDist() != net.neoforged.api.distmarker.Dist.CLIENT) return;
		try {
			Class.forName("forbric.live.neoforge.ForbricNeoLiveClient", true, ForbricNeoLiveMod.class.getClassLoader())
					.getMethod("init", IEventBus.class).invoke(null, modBus);
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive/CLIENT] FAILED initialization: " + t);
		}
	}

	/**
	 * Registers a STARTUP config and reads it back immediately.
	 *
	 * <p>The kernel used to ask the config tracker to load STARTUP configs during its early pass, on top of the
	 * tracker already opening each one the moment it is registered — so every STARTUP config opened twice, fired
	 * its Loading event twice and stacked a second file watcher. The second ask was removed, and this is what
	 * proves nothing was lost: reading a value out of a spec that was never opened throws, so an answer here means
	 * the config really did load, by the path that was always doing the work.
	 */
	private static void registerStartupConfig() {
		try {
			net.neoforged.neoforge.common.ModConfigSpec.Builder builder =
					new net.neoforged.neoforge.common.ModConfigSpec.Builder();
			net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<String> value =
					builder.define("startupProbe", "startup-default");
			net.neoforged.neoforge.common.ModConfigSpec spec = builder.build();

			net.neoforged.fml.ModLoadingContext.get().getActiveContainer()
					.registerConfig(net.neoforged.fml.config.ModConfig.Type.STARTUP, spec);

			System.out.println("[ForbricNeoLive] STARTUP config loaded, startupProbe=" + value.get());
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive] STARTUP config FAILED: " + t);
		}
	}

	/**
	 * {@code ModLoadingContext.getActiveContainer()} from a GAME-bus listener, where no container is active.
	 *
	 * <p>That fallback path ends in {@code getModContainerById("minecraft").orElseThrow()}, and the kernel never
	 * published a "minecraft" container — so a mod registering an extension point outside a kernel-wrapped window
	 * got NeoForge's own "Where is minecraft???!" thrown at it. Asked from the mod constructor this proves
	 * nothing: the kernel sets the active container there. It has to be asked from here.
	 */
	private static void reportActiveContainerFallback() {
		try {
			net.neoforged.fml.ModContainer active = net.neoforged.fml.ModLoadingContext.get().getActiveContainer();
			System.out.println("[ForbricNeoLive] getActiveContainer() with none active answered " + active.getModId());
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive] getActiveContainer() with none active FAILED: " + t);
		}
	}

	/**
	 * The same setup lifecycle the traditional-Forge canary subscribes to, on this family's bus shape.
	 *
	 * <p>Its job in the gate is to be the control the Forge half never had: the kernel used to post these phases
	 * to NeoForge mods ONLY, so asserting the Forge lines alone cannot tell "both families now get it" apart from
	 * "the delivery moved from one family to the other". Both canaries print the same two lines per phase, and the
	 * gate asserts both sets from one boot.
	 */
	private void registerSetupLifecycle(IEventBus modBus) {
		phase(modBus, "common setup", net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class);
		phase(modBus, "dedicated server setup",
				net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent.class);
		phase(modBus, "IMC enqueue", net.neoforged.fml.event.lifecycle.InterModEnqueueEvent.class);
		phase(modBus, "IMC process", net.neoforged.fml.event.lifecycle.InterModProcessEvent.class);
		phase(modBus, "load complete", net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent.class);
		try {
			phase(modBus, "client setup", net.neoforged.fml.event.lifecycle.FMLClientSetupEvent.class);
		} catch (Throwable serverOnly) {
			System.out.println("[ForbricNeoLive/SETUP] client setup not observable here: " + serverOnly);
		}
	}

	/** Subscribes one phase and prints the delivered line, plus a deferred line only the work queue can print. */
	private static <T extends net.neoforged.fml.event.lifecycle.ParallelDispatchEvent> void phase(
			IEventBus modBus, String label, Class<T> type) {
		modBus.addListener(type, event -> {
			System.out.println("[ForbricNeoLive/SETUP] " + label + " DELIVERED to a NeoForge mod");
			event.enqueueWork(() -> System.out.println(
					"[ForbricNeoLive/SETUP] " + label + " DEFERRED work ran"));
		});
	}

	/**
	 * Whether the Fabric mod running in this same instance is visible through the two presence checks a NeoForge
	 * mod makes: ModList.isLoaded, and the LoadingModList lookup multi-platform mods use for a version probe
	 * (Physics Mod reads exactly the second one to decide whether Sodium is present). Answered per-ecosystem,
	 * both said no while the mod was right there, and the compatibility branch went the wrong way in silence.
	 */
	private static void reportForeignMods() {
		boolean modList = false;
		boolean modFile = false;
		try {
			modList = net.neoforged.fml.ModList.get().isLoaded("forbricfabriclive");
		} catch (Throwable notYet) {
			System.out.println("[ForbricNeoLive] ModList not available yet: " + notYet);
		}
		try {
			modFile = net.neoforged.fml.loading.FMLLoader.getCurrent().getLoadingModList()
					.getModFileById("forbricfabriclive") != null;
		} catch (Throwable absent) {
			System.out.println("[ForbricNeoLive] LoadingModList lookup unavailable: " + absent);
		}
		System.out.println("[ForbricNeoLive] foreign forbricfabriclive isLoaded=" + modList + " modFile=" + modFile);
		reportOwnModFile();
		reportOwnCodeSource();
	}

	/**
	 * {@code getProtectionDomain().getCodeSource()} — how a mod finds the jar it was loaded from when it ships
	 * data beside its own classes. JourneyMap and spark both read it, and Sodium's startup checks do too. The
	 * kernel defined every class with no protection domain at all, so it answered null and the mod NPE'd on its
	 * own line.
	 */
	private static void reportOwnCodeSource() {
		try {
			java.security.CodeSource source = ForbricNeoLiveMod.class.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) {
				System.out.println("[ForbricNeoLive] own code source is NULL");
				return;
			}
			java.io.File jar = new java.io.File(source.getLocation().toURI());
			System.out.println("[ForbricNeoLive] own code source resolves to a real file: " + jar.getName()
					+ " exists=" + jar.isFile());
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive] own code source FAILED: " + t);
		}
	}

	/**
	 * The lookup a mod makes about ITSELF: ModList.get().getModFileById(MODID).getFile(). It reads a map the
	 * kernel used to fill for the NeoForge baseline alone, so every kernel-loaded mod got null back and the very
	 * next dereference NPE'd — with nothing in the log to say why.
	 */
	private static void reportOwnModFile() {
		try {
			net.neoforged.neoforgespi.language.IModFileInfo info =
					net.neoforged.fml.ModList.get().getModFileById("forbricneolive");
			if (info == null) {
				System.out.println("[ForbricNeoLive] getModFileById(self) returned NULL");
				return;
			}
			System.out.println("[ForbricNeoLive] getModFileById(self) answered, file=" + info.getFile().getFileName()
					+ " id=" + info.getFile().getId() + " type=" + info.getFile().getType());
			java.util.List<? extends net.neoforged.neoforgespi.language.IModInfo> mine = info.getMods();
			if (mine.isEmpty()) {
				System.out.println("[ForbricNeoLive] own metadata: the file reports NO mods");
				return;
			}
			System.out.println("[ForbricNeoLive] own metadata: name=" + mine.get(0).getDisplayName()
					+ " version=" + mine.get(0).getVersion());
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive] getModFileById(self) FAILED: " + t);
		}
	}
}
