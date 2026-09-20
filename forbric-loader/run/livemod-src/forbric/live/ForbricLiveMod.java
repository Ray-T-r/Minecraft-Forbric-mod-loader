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

package forbric.live;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forbric stage-5 gameplay-event canary: proves that the Forge-patched vanilla's static event posts
 * (ForgeHooks/EventHooks -> event BUS fields) reach a real mod's {@code @Mod.EventBusSubscriber} listeners
 * in-game, and that registry content from earlier boots persisted. Halts the server after
 * {@code -Dforbric.ticks=N} server ticks so headless verification runs are self-terminating.
 */
@Mod("forbriclive")
public class ForbricLiveMod {
	/**
	 * A traditional-Forge network channel. The server pings the first player it sees on the tick event (bridged
	 * from NeoForge's, so it fires on Forbric; Forge's own PlayerLoggedInEvent is not), the client logs the ping
	 * and pongs back, the server logs the pong. The two log lines — one per direction — are the observable
	 * result, and they only appear if the whole chain worked: SimpleChannel's encode, Forge's ForgePayload, and
	 * the dispatch on each side.
	 */
	public static final net.minecraftforge.network.SimpleChannel NET = net.minecraftforge.network.ChannelBuilder
			.named(Identifier.fromNamespaceAndPath("forbriclive", "net"))
			.networkProtocolVersion(1)
			.optional()
			.simpleChannel()
			.messageBuilder(Ping.class)
				.encoder((msg, buf) -> buf.writeUtf(msg.text))
				.decoder(buf -> new Ping(buf.readUtf()))
				.consumerMainThread((msg, ctx) -> {
					System.out.println("[ForbricLive/NET] PING received on the " + (ctx.isClientSide() ? "client" : "server")
							+ ": " + msg.text + " (peer declares the channel: " + net().isRemotePresent(ctx.getConnection()) + ")");
					if (ctx.isClientSide()) {
						logHandshake("client", ctx.getConnection());
						System.out.println("[ForbricLive/CFG] greeting seen on the client at PING: " + greeting());
					}
					if (ctx.isClientSide()) {
						net().send(new Pong("pong for " + msg.text), net.minecraftforge.network.PacketDistributor.SERVER.noArg());
						System.out.println("[ForbricLive/NET] PONG sent from the client");
					}
					ctx.setPacketHandled(true);
				})
				.add()
			.messageBuilder(Pong.class)
				.encoder((msg, buf) -> buf.writeUtf(msg.text))
				.decoder(buf -> new Pong(buf.readUtf()))
				.consumerMainThread((msg, ctx) -> {
					System.out.println("[ForbricLive/NET] PONG received on the " + (ctx.isClientSide() ? "client" : "server")
							+ ": " + msg.text + (ctx.getSender() != null ? " from " + ctx.getSender().getGameProfile().name() : ""));
					ctx.setPacketHandled(true);
				})
				.add();

	public record Ping(String text) {}
	public record Pong(String text) {}

	/** The lambdas above run long after the initializer finished; javac just refuses the field's own name inside it. */
	private static net.minecraftforge.network.SimpleChannel net() {
		return NET;
	}

	/**
	 * A traditional-Forge SERVER config. Write a non-default value into the server's world before it boots and
	 * the client must end up reading THAT value, which can only happen if Forge's configuration-phase config
	 * sync ran over the socket.
	 */
	public static final net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> GREETING;
	public static final net.minecraftforge.common.ForgeConfigSpec SERVER_SPEC;
	public static final net.minecraftforge.common.ForgeConfigSpec.IntValue COMMON_PROBE;
	public static final net.minecraftforge.common.ForgeConfigSpec COMMON_SPEC;
	public static final net.minecraftforge.common.ForgeConfigSpec.IntValue CLIENT_PROBE;
	public static final net.minecraftforge.common.ForgeConfigSpec CLIENT_SPEC;

	static {
		net.minecraftforge.common.ForgeConfigSpec.Builder b = new net.minecraftforge.common.ForgeConfigSpec.Builder();
		GREETING = b.comment("set this in <world>/serverconfig before the server boots to test config sync").define("greeting", "default");
		SERVER_SPEC = b.build();
		b = new net.minecraftforge.common.ForgeConfigSpec.Builder();
		COMMON_PROBE = b.comment("change this while the server runs to test the native config watcher")
				.defineInRange("probe", 11, 0, 1000);
		COMMON_SPEC = b.build();
		b = new net.minecraftforge.common.ForgeConfigSpec.Builder();
		CLIENT_PROBE = b.comment("client-only config load probe").defineInRange("probe", 17, 0, 1000);
		CLIENT_SPEC = b.build();
	}

	/** The spec is unloaded until someone loads or syncs it; reading it then throws outside production. */
	private static String greeting() {
		return SERVER_SPEC.isLoaded() ? GREETING.get() : "<unloaded>";
	}

	private static void logConfig(String phase, net.minecraftforge.fml.config.ModConfig cfg) {
		String path;
		try {
			path = String.valueOf(cfg.getFullPath());
		} catch (RuntimeException syncedHasNoFile) {
			path = "<synced, no file>";
		}
		if (cfg.getType() != net.minecraftforge.fml.config.ModConfig.Type.SERVER) {
			boolean client = cfg.getType() == net.minecraftforge.fml.config.ModConfig.Type.CLIENT;
			var spec = client ? CLIENT_SPEC : COMMON_SPEC;
			var probe = client ? CLIENT_PROBE : COMMON_PROBE;
			System.out.println("[ForbricLive/CFG] " + phase + " " + cfg.getFileName() + ": probe="
					+ (spec.isLoaded() ? probe.get() : "<unloaded>") + " loaded=" + spec.isLoaded() + " path=" + path);
			return;
		}
		System.out.println("[ForbricLive/CFG] " + phase + " " + cfg.getFileName() + ": greeting=" + greeting()
				+ " loaded=" + SERVER_SPEC.isLoaded() + " path=" + path);
	}

	/** What a real Forge mod asks the connection: is the peer modded, and what is it running? */
	private static void logHandshake(String side, net.minecraft.network.Connection c) {
		net.minecraftforge.network.NetworkContext nc = net.minecraftforge.network.NetworkContext.get(c);
		System.out.println("[ForbricLive/HS] " + side + " NetworkContext type=" + nc.getType()
				+ " netVersion=" + nc.getNetVersion() + " mods=" + new java.util.TreeSet<>(nc.getModList().keySet())
				+ " remoteChannels=" + nc.getRemoteChannels().size());
	}

	public ForbricLiveMod(FMLJavaModLoadingContext ctx) {
		System.out.println("[ForbricLive] constructed by the real ModLoader; game-event listeners auto-register via @EventBusSubscriber");
		reportOwnContainer();
		System.out.println("[ForbricLive/NET] channel " + NET.getName() + " built (protocol v" + NET.getProtocolVersion() + ")");
		ctx.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER, SERVER_SPEC);
		ctx.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, COMMON_SPEC);
		ctx.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT, CLIENT_SPEC);
		net.minecraftforge.fml.event.config.ModConfigEvent.Loading.getBus(ctx.getModBusGroup())
				.addListener(e -> logConfig("LOADING", e.getConfig()));
		net.minecraftforge.fml.event.config.ModConfigEvent.Reloading.getBus(ctx.getModBusGroup())
				.addListener(e -> logConfig("RELOADING", e.getConfig()));
		System.out.println("[ForbricLive/CFG] registered SERVER config forbriclive-server.toml (greeting default 'default')");
		System.out.println("[ForbricLive/CFG] registered COMMON config forbriclive-common.toml (probe default 11)");
		System.out.println("[ForbricLive/CFG] registered CLIENT config forbriclive-client.toml (probe default 17)");
		registerRegistrationProbes(ctx);
		registerReloadProbe();
		registerSetupLifecycle(ctx);
		registerClient(ctx);
		reportForeignMods();
	}

	/**
	 * H2/H3: the documented way a Forge mod registers a JSON data loader. On the merged base the event was never
	 * constructed, and the documented getConditionContext() call was a NoSuchMethodError the moment it ran.
	 */
	private static void registerReloadProbe() {
		net.minecraftforge.event.AddReloadListenerEvent.BUS.addListener(event -> {
			System.out.println("[ForbricLive/RELOAD] AddReloadListenerEvent DELIVERED to a traditional-Forge mod");
			String context;
			try {
				var ctx = event.getConditionContext();
				context = ctx == net.minecraftforge.common.crafting.conditions.ICondition.IContext.EMPTY ? "EMPTY"
						: "live " + ctx.getClass().getName();
			} catch (Throwable failure) {
				context = "FAILED " + failure;
			}
			System.out.println("[ForbricLive/RELOAD] context=" + context);
			event.addListener(new ProbeReloadListener());
		});
	}

	/** A data loader over data/<ns>/forbriclive_probe/*.json — the shape every Forge mod's custom data folder uses. */
	static final class ProbeReloadListener
			extends net.minecraft.server.packs.resources.SimplePreparableReloadListener<Integer> {
		@Override
		protected Integer prepare(net.minecraft.server.packs.resources.ResourceManager manager,
				net.minecraft.util.profiling.ProfilerFiller profiler) {
			return manager.listResources("forbriclive_probe", id -> id.getPath().endsWith(".json")).size();
		}

		@Override
		protected void apply(Integer files, net.minecraft.server.packs.resources.ResourceManager manager,
				net.minecraft.util.profiling.ProfilerFiller profiler) {
			System.out.println("[ForbricLive/RELOAD] reload listener ran over " + files + " file(s)");
		}
	}

	/** Register through Forge's genuine buses; only the patched game may deliver these events. */
	private static void registerRegistrationProbes(FMLJavaModLoadingContext ctx) {
		net.minecraftforge.event.entity.SpawnPlacementRegisterEvent.BUS.addListener(event -> {
			event.register(net.minecraft.world.entity.EntityTypes.ZOMBIE,
					net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND,
					net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
					net.minecraft.world.entity.monster.Monster::checkMonsterSpawnRules,
					net.minecraftforge.event.entity.SpawnPlacementRegisterEvent.Operation.REPLACE);
			System.out.println("[ForbricLive/REGISTRATION] SpawnPlacementRegisterEvent RECEIVED");
		});
		net.minecraftforge.event.BuildCreativeModeTabContentsEvent.BUS.addListener(event -> {
			if (!event.getTabKey().equals(net.minecraft.world.item.CreativeModeTabs.BUILDING_BLOCKS)) return;
			event.accept(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COMMAND_BLOCK),
					net.minecraft.world.item.CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
			System.out.println("[ForbricLive/REGISTRATION] BuildCreativeModeTabContentsEvent RECEIVED: minecraft:building_blocks");
		});
		net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent.getBus(ctx.getModBusGroup())
				.addListener(event -> event.enqueueWork(() -> {
					System.out.println("[ForbricLive/REGISTRATION] zombie heightmap="
							+ net.minecraft.world.entity.SpawnPlacements.getHeightmapType(net.minecraft.world.entity.EntityTypes.ZOMBIE));
				}));
		System.out.println("[ForbricLive/REGISTRATION] subscribed to Forge spawn and creative registration events");
	}

	/** Read the tab's materialized parent and search collections, never the event's mutable request map. */
	static void observeCreativeContents(String phase) {
		var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(net.minecraft.world.item.CreativeModeTabs.BUILDING_BLOCKS);
		boolean parent = tab.getDisplayItems().stream().anyMatch(stack -> stack.is(net.minecraft.world.item.Items.COMMAND_BLOCK));
		boolean search = tab.getSearchTabDisplayItems().stream().anyMatch(stack -> stack.is(net.minecraft.world.item.Items.COMMAND_BLOCK));
		System.out.println("[ForbricLive/REGISTRATION] injection VISIBLE: " + parent + " search=" + search + " phase=" + phase);
	}

	/**
	 * Reproduces what a real library mod does FIRST: resolve its own container out of {@code ModList}.
	 *
	 * <p>This is libraryferret's and awesomedungeonocean's exact shape —
	 * {@code RegistryProviderForgeImpl.getIEventBus} does {@code ModList.getModContainerById(id).orElseThrow()},
	 * casts to {@code FMLModContainer} and takes its bus group — and it runs from a class initializer their
	 * constructor reaches, so a "not found" there is permanent for the rest of the run. Printing the active
	 * namespace too, because {@code getActiveNamespace()} answers "minecraft" rather than throwing when no
	 * container is active, which makes a mod's id-less registrations land silently under the wrong mod.
	 */
	private static void reportOwnContainer() {
		java.util.Optional<? extends net.minecraftforge.fml.ModContainer> own =
				net.minecraftforge.fml.ModList.getModContainerById("forbriclive");
		net.minecraftforge.fml.javafmlmod.FMLModContainer fml =
				own.orElse(null) instanceof net.minecraftforge.fml.javafmlmod.FMLModContainer c ? c : null;
		System.out.println("[ForbricLive/SELF] own container during ctor: present=" + own.isPresent()
				+ " fmlContainer=" + (fml != null)
				+ " busGroup=" + (fml != null && fml.getModBusGroup() != null)
				+ " activeNamespace=" + net.minecraftforge.fml.ModLoadingContext.get().getActiveNamespace());
	}

	/** Link client-only event types and screens only on the client, through a class-name boundary. */
	private static void registerClient(FMLJavaModLoadingContext ctx) {
		if (net.minecraftforge.fml.loading.FMLEnvironment.dist
				!= net.minecraftforge.api.distmarker.Dist.CLIENT) return;
		try {
			Class.forName("forbric.live.ForbricLiveClient", true, ForbricLiveMod.class.getClassLoader())
					.getMethod("init", FMLJavaModLoadingContext.class).invoke(null, ctx);
		} catch (Throwable t) {
			System.out.println("[ForbricLive/CLIENT] FAILED initialization: " + t);
		}
	}

	/**
	 * The mod-loading lifecycle a real traditional-Forge mod actually initialises from.
	 *
	 * <p>This is the canary for a gap that shipped: the kernel posted FMLCommonSetupEvent and its siblings to
	 * NeoForge mods only, so every traditional-MinecraftForge mod that does its real work from setup did nothing
	 * at all — no error, no warning, just a mod that loaded and then sat there. Biomes O' Plenty is the case that
	 * found it: its whole TerraBlender region registration hangs off commonSetup -> enqueueWork, so worldgen came
	 * out vanilla while the mod reported itself loaded and its blocks and items were all present.
	 *
	 * <p>Two lines per phase on purpose. The first says the event was DELIVERED; the second says what the listener
	 * DEFERRED actually ran. They fail independently: posting the event without draining ModLoadingStage's
	 * DeferredWorkQueue prints the first and never the second, which is precisely the half-fix that would look
	 * right in a log that only asserted delivery.
	 */
	private static void registerSetupLifecycle(FMLJavaModLoadingContext ctx) {
		Object group = ctx.getModBusGroup();
		phase("common setup", net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent.getBus(
				(net.minecraftforge.eventbus.api.bus.BusGroup) group));
		phase("dedicated server setup", net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent.getBus(
				(net.minecraftforge.eventbus.api.bus.BusGroup) group));
		phase("IMC enqueue", net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent.getBus(
				(net.minecraftforge.eventbus.api.bus.BusGroup) group));
		phase("IMC process", net.minecraftforge.fml.event.lifecycle.InterModProcessEvent.getBus(
				(net.minecraftforge.eventbus.api.bus.BusGroup) group));
		phase("load complete", net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent.getBus(
				(net.minecraftforge.eventbus.api.bus.BusGroup) group));
		// Registered through the same seam, but the class is only loadable where the client half of the base is:
		// a failure here must cost this one phase, not the four above it.
		try {
			phase("client setup", net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent.getBus(
					(net.minecraftforge.eventbus.api.bus.BusGroup) group));
		} catch (Throwable serverOnly) {
			System.out.println("[ForbricLive/SETUP] client setup not observable here: " + serverOnly);
		}
	}

	/** Subscribes one phase and prints the delivered line, plus a deferred line that only the queue can print. */
	private static <T extends net.minecraftforge.fml.event.lifecycle.ParallelDispatchEvent> void phase(
			String label, net.minecraftforge.eventbus.api.bus.EventBus<T> bus) {
		bus.addListener(event -> {
			System.out.println("[ForbricLive/SETUP] " + label + " DELIVERED to a traditional-Forge mod");
			event.enqueueWork(() -> System.out.println(
					"[ForbricLive/SETUP] " + label + " DEFERRED work ran"));
		});
	}

	/**
	 * Whether the mods of the OTHER two ecosystems, which are running in this same instance, are visible through
	 * the presence checks a MinecraftForge mod actually makes. A wrong answer here disables an integration in
	 * silence, so both these lines are logged and are worth checking against a negative control.
	 *
	 * <p>The NeoForge lookup is asked from here on purpose: both runtimes are loaded in this instance, and it is
	 * the seam a real multi-platform mod hits after detecting NeoForge by class presence — which on the merged
	 * base every mod does, whichever family built it.
	 */
	private static void reportForeignMods() {
		boolean modList = net.minecraftforge.fml.ModList.isLoaded("forbricfabriclive");
		boolean modFile = false;
		try {
			modFile = net.neoforged.fml.loading.FMLLoader.getCurrent().getLoadingModList()
					.getModFileById("forbricfabriclive") != null;
		} catch (Throwable absent) {
			System.out.println("[ForbricLive] foreign lookup through NeoForge's LoadingModList unavailable: " + absent);
		}
		System.out.println("[ForbricLive] foreign forbricfabriclive isLoaded=" + modList + " modFile=" + modFile);
	}

	@Mod.EventBusSubscriber(modid = "forbriclive")
	public static final class GameEvents {
		private static final AtomicInteger TICKS = new AtomicInteger();

		/**
		 * The bridge that decides whether this mod's commands exist at all.
		 *
		 * <p>On the merged base {@code Commands} calls only NeoForge's {@code EventHooks.onCommandRegister}, so
		 * without a re-emission this listener never runs, the node is never added, and a player typing
		 * {@code /forbriclive} is told "Unknown command" — while the mod itself loaded cleanly and reports no
		 * problem anywhere. Registering a real node rather than just logging is the point: the gate asserts the
		 * node is in the live dispatcher, which only a genuine registration can produce.
		 */
		@SubscribeEvent
		public static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
			event.getDispatcher().register(
					com.mojang.brigadier.builder.LiteralArgumentBuilder
							.<net.minecraft.commands.CommandSourceStack>literal("forbriclive")
							.executes(ctx -> 1));
			System.out.println("[ForbricLive] RegisterCommandsEvent RECEIVED - /forbriclive registered into the live "
					+ "dispatcher (" + event.getDispatcher().getRoot().getChildren().size() + " root nodes)");
		}

		/** Login. {@code PlayerList} on the merged base is 13 NeoForge hook references to 0 MinecraftForge. */
		@SubscribeEvent
		public static void onPlayerLoggedIn(
				net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
			System.out.println("[ForbricLive] PlayerLoggedInEvent RECEIVED for "
					+ event.getEntity().getGameProfile().name());
		}

		/** Logout, the other half of the pair a mod needs to keep per-player state honest. */
		@SubscribeEvent
		public static void onPlayerLoggedOut(
				net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
			System.out.println("[ForbricLive] PlayerLoggedOutEvent RECEIVED for "
					+ event.getEntity().getGameProfile().name());
		}

		/**
		 * The hook that also initialises MinecraftForge's PermissionAPI. Without the re-emission, not only does
		 * this listener never run — every permission question any Forge mod asks NPEs inside Forge's own API,
		 * because {@code initializePermissionAPI} is the only thing that ever sets the handler it reads.
		 */
		@SubscribeEvent
		public static void onServerStarting(net.minecraftforge.event.server.ServerStartingEvent event) {
			System.out.println("[ForbricLive] ServerStartingEvent RECEIVED - PermissionAPI is initialised by this "
					+ "same hook");
			walkModFiles();
			addAnEnumConstant();
		}

		/**
		 * What a MinecraftForge mod does to add its own mob category, arm pose or item display context.
		 *
		 * <p>In the shipped game that factory is a stub whose whole body throws "Enum not extended" — the real
		 * loader rewrites it while the class is being defined, and the kernel replaces that loader. Mods call it
		 * from a static initialiser, which only runs once, so the exception does not cost them a constant: it
		 * kills the mod and everything that touches it.
		 */
		private static void addAnEnumConstant() {
			try {
				int before = net.minecraft.world.entity.MobCategory.values().length;
				net.minecraft.world.entity.MobCategory added = net.minecraft.world.entity.MobCategory
						.create("FORBRIC_CANARY", "forbric_canary", "forbric_canary", 1, true, false, 128);
				int after = net.minecraft.world.entity.MobCategory.values().length;
				System.out.println("[ForbricLive] MobCategory.create gave us " + added.name()
						+ ", values went " + before + " -> " + after);
			} catch (Throwable t) {
				System.out.println("[ForbricLive] MobCategory.create FAILED: " + t);
			}
		}

		/**
		 * What ShoulderSurfing-Forge and collective do from their own listeners: walk every mod file and ask it
		 * for a resource. Both accessors go through the ModFile's SecureJar, so a seeded file without one NPEs
		 * inside MinecraftForge's own code — and because toString goes the same way, even LOGGING the failure NPEs.
		 */
		private static void walkModFiles() {
			try {
				int files = 0;
				int resolved = 0;
				for (net.minecraftforge.forgespi.language.IModFileInfo info : net.minecraftforge.fml.ModList
						.getModFiles()) {
					files++;
					java.nio.file.Path path = info.getFile().getFilePath();
					java.nio.file.Path toml = info.getFile().findResource("META-INF", "mods.toml");
					if (path != null && toml != null) resolved++;
				}
				System.out.println("[ForbricLive] walked " + files + " mod file(s), " + resolved
						+ " answered getFilePath and findResource");
			} catch (Throwable t) {
				System.out.println("[ForbricLive] walking ModList.getModFiles() FAILED: " + t);
			}
		}

		@SubscribeEvent
		public static void onServerStarted(ServerStartedEvent event) {
			System.out.println("[ForbricLive] ServerStartedEvent RECEIVED - the patched game's event posts reach mod listeners");
			// Creative stacks need the game's bound components, which load-complete does not yet guarantee.
			try {
				var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(net.minecraft.world.item.CreativeModeTabs.BUILDING_BLOCKS);
				tab.buildContents(new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(
						net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS, true,
						net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
				observeCreativeContents("server started");
			} catch (Throwable failure) {
				System.out.println("[ForbricLive/REGISTRATION] creative contents probe failed: " + failure);
			}
			System.out.println("[ForbricLive/REGISTRATION] common registration observations completed");
			// H1: one call away from any Forge mod — IForgeBlockPos.toCompoundTag() links against CompoundTag.builder().
			try {
				System.out.println("[ForbricLive/NBT] BlockPos.toCompoundTag() = "
						+ new net.minecraft.core.BlockPos(1, 2, 3).toCompoundTag());
			} catch (Throwable failure) {
				System.out.println("[ForbricLive/NBT] BlockPos.toCompoundTag() FAILED: " + failure);
			}
			checkItem("forbrictest", "test_item");
			checkItem("forbricfab", "fab_item");
			checkItem("mcwbridges", "pliers");
		}

		private static boolean pinged;

		@SubscribeEvent
		public static void onServerTickPost(TickEvent.ServerTickEvent.Post event) {
			int n = TICKS.incrementAndGet();
			int limit = Integer.getInteger("forbric.ticks", 0);
			if (!pinged && n % 20 == 0) {
				for (net.minecraft.server.level.ServerPlayer player : event.server().getPlayerList().getPlayers()) {
					pinged = true;
					// What a real mod checks before sending: did the peer's minecraft:register name this channel?
					boolean present = NET.isRemotePresent(player.connection.getConnection());
					NET.send(new Ping("hello " + player.getGameProfile().name()),
							net.minecraftforge.network.PacketDistributor.PLAYER.with(player));
					System.out.println("[ForbricLive/NET] PING sent from the server to " + player.getGameProfile().name()
							+ " (peer declares the channel: " + present + ")");
					logHandshake("server", player.connection.getConnection());
					System.out.println("[ForbricLive/CFG] greeting on the server at PING: " + greeting());
					break;
				}
			}

			if (n == 20) {
				System.out.println("[ForbricLive] 20 server ticks observed - the game loop posts TickEvent to mods");
			}

			if (limit > 0 && n == limit) {
				System.out.println("[ForbricLive] tick limit " + limit + " reached - halting server (clean shutdown)");
				event.server().halt(false);
			}
		}

		private static void checkItem(String ns, String path) {
			boolean present = BuiltInRegistries.ITEM.containsKey(Identifier.fromNamespaceAndPath(ns, path));
			System.out.println("[ForbricLive/VERIFY] ITEM " + ns + ":" + path + " present = " + present);
		}
	}
}
