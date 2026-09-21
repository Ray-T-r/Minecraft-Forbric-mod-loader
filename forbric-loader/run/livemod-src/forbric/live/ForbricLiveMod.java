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

	/**
	 * D6: a structure-modifier serializer of this mod's own (the shape Forge worldgen mods use for structure
	 * spawns), so the kernel's forge:structure_modifier declaration is exercised by a mod-registered type, not only
	 * by Forge's built-ins. At ADD it gives mineshafts a CREATURE spawn override with a mooshroom.
	 */
	public record ProbeSpawnStructureModifier(
			net.minecraft.core.HolderSet<net.minecraft.world.level.levelgen.structure.Structure> structures)
			implements net.minecraftforge.common.world.StructureModifier {
		public static final com.mojang.serialization.MapCodec<ProbeSpawnStructureModifier> CODEC =
				com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
						net.minecraft.core.RegistryCodecs.homogeneousList(net.minecraft.core.registries.Registries.STRUCTURE)
								.fieldOf("structures").forGetter(ProbeSpawnStructureModifier::structures))
						.apply(instance, ProbeSpawnStructureModifier::new));

		@Override
		public void modify(net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.Structure> structure,
				Phase phase, net.minecraftforge.common.world.ModifiableStructureInfo.StructureInfo.Builder builder) {
			if (phase == Phase.ADD && structures.contains(structure)) {
				builder.getStructureSettings().getOrAddSpawnOverrides(net.minecraft.world.entity.MobCategory.CREATURE)
						.addSpawn(new net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData(
								net.minecraft.world.entity.EntityTypes.MOOSHROOM, 1, 1), 100);
			}
		}

		@Override
		public com.mojang.serialization.MapCodec<? extends net.minecraftforge.common.world.StructureModifier> codec() {
			return CODEC;
		}
	}

	private static final net.minecraftforge.registries.DeferredRegister<com.mojang.serialization.MapCodec<? extends net.minecraftforge.common.world.StructureModifier>> STRUCTURE_MODIFIER_SERIALIZERS =
			net.minecraftforge.registries.DeferredRegister.create(
					net.minecraftforge.registries.ForgeRegistries.Keys.STRUCTURE_MODIFIER_SERIALIZERS, "forbriclive");

	static {
		STRUCTURE_MODIFIER_SERIALIZERS.register("probe_spawn", () -> ProbeSpawnStructureModifier.CODEC);
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
		STRUCTURE_MODIFIER_SERIALIZERS.register(ctx.getModBusGroup());
		System.out.println("[ForbricLive/WORLDGEN] registered structure modifier serializer forbriclive:probe_spawn");
		registerRegistrationProbes(ctx);
		registerCapabilityProbe();
		registerReloadProbe();
		registerSetupLifecycle(ctx);
		registerClient(ctx);
		reportForeignMods();
		reportScanIndex();
	}

	/** The field the index is asked about. Its value is irrelevant; the ANNOTATION is the fixture. */
	@LiveScanned(kind = LiveScanned.Kind.SECOND, note = "probe")
	public static String PROBE = "probe";

	/**
	 * Whether {@code ModList.getAllScanData()} — the index a traditional-Forge mod finds its OWN members through —
	 * actually holds this mod's annotation, and whether an enum member in it has FML's own shape.
	 *
	 * <p>It held nothing at all for the kernel's whole life: the seeded {@code ModFile} carried an empty
	 * {@code ModFileScanData}, so SuperMartijn642's Core Lib injected no {@code @RegistryEntryAcceptor} field and
	 * Packed Up's menu type stayed null — the client died in {@code Minecraft.<init>} with "Container screen
	 * registered with null menu type!". Then the first non-empty index handed Core Lib a String where it casts to
	 * {@code EnumData}. Both halves are asserted here, because either one alone reads as working.
	 */
	private static void reportScanIndex() {
		try {
			int files = 0;
			int annotations = 0;
			for (net.minecraftforge.forgespi.language.ModFileScanData scan
					: net.minecraftforge.fml.ModList.getAllScanData()) {
				files++;
				annotations += scan.getAnnotations().size();
				for (net.minecraftforge.forgespi.language.ModFileScanData.AnnotationData data : scan.getAnnotations()) {
					if (!data.annotationType().getDescriptor().equals("Lforbric/live/LiveScanned;")) continue;
					System.out.println("[ForbricLive/SCAN] found its own @LiveScanned on " + data.targetType()
							+ " " + data.memberName() + " of " + data.clazz().getClassName());
					Object kind = data.annotationData().get("kind");
					// The constant name is read through the wrapper's own accessor, not from its toString: a
					// bare String would ALSO print "SECOND" and the check would pass on the broken shape.
					String constant = "?";
					if (kind != null) {
						try {
							constant = String.valueOf(kind.getClass().getMethod("value").invoke(kind));
						} catch (Throwable notAWrapper) {
							constant = "<no value() accessor: " + notAWrapper + ">";
						}
					}
					System.out.println("[ForbricLive/SCAN] enum member kind is " + (kind == null ? "ABSENT"
							: kind.getClass().getName() + " with value " + constant));
					System.out.println("[ForbricLive/SCAN] string member note is " + data.annotationData().get("note"));
				}
			}
			System.out.println("[ForbricLive/SCAN] ModList.getAllScanData(): " + files + " file(s), "
					+ annotations + " annotation(s)");
		} catch (Throwable t) {
			System.out.println("[ForbricLive/SCAN] could not read the mod scan index: " + t);
		}
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

	/** E10: the way every Forge storage mod attaches a handler — through Forge's own AttachCapabilitiesEvent. */
	private static final java.util.concurrent.atomic.AtomicInteger ATTACHED = new java.util.concurrent.atomic.AtomicInteger();

	private static void registerCapabilityProbe() {
		net.minecraftforge.event.AttachCapabilitiesEvent.BlockEntities.BUS.addListener(event -> {
			if (!(event.getObject() instanceof net.minecraft.world.level.block.entity.BellBlockEntity)) return;
			net.minecraftforge.items.ItemStackHandler handler = new net.minecraftforge.items.ItemStackHandler(1);
			net.minecraftforge.common.util.LazyOptional<net.minecraftforge.items.IItemHandler> optional =
					net.minecraftforge.common.util.LazyOptional.of(() -> handler);
			event.addCapability(Identifier.fromNamespaceAndPath("forbriclive", "probe"), new ProbeProvider(handler, optional));
			// Forge's own contract: the dispatcher invalidates exactly the runnables registered here.
			event.addListener(optional::invalidate);
			if (ATTACHED.incrementAndGet() == 1) {
				System.out.println("[ForbricLive/CAPS] AttachCapabilitiesEvent.BlockEntities RECEIVED for BellBlockEntity");
			}
		});
		net.minecraftforge.event.AttachCapabilitiesEvent.Levels.BUS.addListener(event -> {
			net.minecraftforge.energy.EnergyStorage storage = new net.minecraftforge.energy.EnergyStorage(1000);
			net.minecraftforge.common.util.LazyOptional<net.minecraftforge.energy.IEnergyStorage> optional =
					net.minecraftforge.common.util.LazyOptional.of(() -> storage);
			event.addCapability(Identifier.fromNamespaceAndPath("forbriclive", "level_probe"),
					new net.minecraftforge.common.capabilities.ICapabilityProvider() {
						@Override
						public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
								net.minecraftforge.common.capabilities.Capability<T> cap, net.minecraft.core.Direction side) {
							return cap == net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY ? optional.cast()
									: net.minecraftforge.common.util.LazyOptional.empty();
						}
					});
			System.out.println("[ForbricLive/CAPS] AttachCapabilitiesEvent.Levels RECEIVED");
		});
		System.out.println("[ForbricLive/CAPS] subscribed to AttachCapabilitiesEvent.BlockEntities");
	}

	/** The shape a Forge storage mod's provider takes: a serialisable capability provider. */
	static final class ProbeProvider
			implements net.minecraftforge.common.capabilities.ICapabilitySerializable<net.minecraft.nbt.CompoundTag> {
		private final net.minecraftforge.items.ItemStackHandler handler;
		private final net.minecraftforge.common.util.LazyOptional<net.minecraftforge.items.IItemHandler> optional;

		ProbeProvider(net.minecraftforge.items.ItemStackHandler handler,
				net.minecraftforge.common.util.LazyOptional<net.minecraftforge.items.IItemHandler> optional) {
			this.handler = handler;
			this.optional = optional;
		}

		@Override
		public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
				net.minecraftforge.common.capabilities.Capability<T> cap, net.minecraft.core.Direction side) {
			return cap == net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER ? optional.cast()
					: net.minecraftforge.common.util.LazyOptional.empty();
		}

		@Override
		public net.minecraft.nbt.CompoundTag serializeNBT(net.minecraft.core.HolderLookup.Provider provider) {
			return handler.serializeNBT(provider);
		}

		@Override
		public void deserializeNBT(net.minecraft.core.HolderLookup.Provider provider, net.minecraft.nbt.CompoundTag tag) {
			handler.deserializeNBT(provider, tag);
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

		/**
		 * Breaking a block — the event a claim or protection mod lives on.
		 *
		 * <p>{@code ServerPlayerGameMode} on the merged base posts only NeoForge's {@code BreakBlockEvent} and
		 * branches on its {@code isCanceled()}; it carries no MinecraftForge hook at all, so without the bridge
		 * this listener never runs and the block simply breaks while the mod looks healthy.
		 *
		 * <p>It REFUSES the break, and only the probe one: the canary's synthetic post is made on behalf of
		 * NeoForge's fake player, and a real break is not. Refusing every break would turn this fixture into a
		 * protection mod for every other gate. The class is named as text because this mod is compiled against
		 * MinecraftForge's carrier alone and cannot see NeoForge's.
		 */
		@SubscribeEvent
		public static void onBlockBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
			boolean probe = isProbe(event.getPlayer());
			if (probe) event.setResult(net.minecraftforge.common.util.Result.DENY);
			System.out.println("[ForbricLive/BLOCKBREAK] BreakEvent RECEIVED at " + event.getPos()
					+ " probe=" + probe + " refused=" + probe);
		}

		/**
		 * Right-clicking a block — what a protection mod, a lock and every custom block interaction listens for.
		 *
		 * <p>Refuses the BLOCK use only, so the pair below can refuse the other decision and a bridge that
		 * translated one of the two, or translated it in the wrong direction, still fails a check.
		 */
		@SubscribeEvent
		public static void onRightClickBlock(
				net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
			boolean probe = isProbe(event.getEntity());
			if (probe) event.setUseBlock(net.minecraftforge.common.util.Result.DENY);
			System.out.println("[ForbricLive/INTERACT] RightClickBlock RECEIVED at " + event.getPos()
					+ " probe=" + probe);
		}

		/** Left-clicking a block, the first half of every protection rule about breaking one. */
		@SubscribeEvent
		public static void onLeftClickBlock(
				net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
			boolean probe = isProbe(event.getEntity());
			if (probe) event.setUseItem(net.minecraftforge.common.util.Result.DENY);
			System.out.println("[ForbricLive/INTERACT] LeftClickBlock RECEIVED at " + event.getPos()
					+ " action=" + event.getAction() + " probe=" + probe);
		}

		/**
		 * Using an item in hand. Cancelling is the only decision this one carries.
		 *
		 * <p>A cancelling listener on this eventbus is a {@code Predicate} that returns true — there is no
		 * {@code setCanceled} on the event — so the refusal reaches the caller only as {@code post}'s return
		 * value, which is the half a bridge reading the result alone would drop.
		 */
		@SubscribeEvent
		public static boolean onRightClickItem(
				net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
			boolean probe = isProbe(event.getEntity());
			System.out.println("[ForbricLive/INTERACT] RightClickItem RECEIVED probe=" + probe);
			return probe;
		}

		/**
		 * Placing a block — the other half of every protection rule, and of every block-logging mod's record.
		 *
		 * <p>Reports the snapshot's REPLACED state as well as refusing: the snapshot is taken before the block is
		 * placed, so a bridge that rebuilt it after the fact would hand a mod the block that was just placed and
		 * call it the one that was there. A mod restoring that on cancel would put the new block back.
		 */
		@SubscribeEvent
		public static boolean onEntityPlace(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
			boolean probe = isProbe(event.getEntity() instanceof net.minecraft.world.entity.player.Player player
					? player : null);
			System.out.println("[ForbricLive/PLACE] EntityPlaceEvent RECEIVED at " + event.getPos()
					+ " replaced=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK
							.getKey(event.getBlockSnapshot().getReplacedBlock().getBlock())
					+ " probe=" + probe);
			return probe;
		}

		/**
		 * Whether this is the canary's own synthetic interaction, made on behalf of NeoForge's fake player.
		 *
		 * <p>Named as text because this mod is compiled against MinecraftForge's carrier alone. Refusing a REAL
		 * player's interactions would turn this fixture into a protection mod for every other gate.
		 */
		private static boolean isProbe(net.minecraft.world.entity.player.Player player) {
			return player != null && player.getClass().getName().endsWith("util.FakePlayer");
		}

		/** Every loot table this listener was offered, and whether it was offered its own. */
		private static final AtomicInteger LOOT_TABLES = new AtomicInteger();

		/**
		 * A loot table being loaded — the hook a loot mod adds to or replaces tables from.
		 *
		 * <p>The merged ReloadableServerRegistries posts only NeoForge's event, so this never ran and every such
		 * mod was a no-op that looked healthy. Counting is not enough to prove the link: the canary REPLACES its
		 * own table's pools with nothing, and the gate reads the live table back afterwards, so a forward that
		 * delivers the event but drops what the listener did still fails.
		 */
		@SubscribeEvent
		public static void onLootTableLoad(net.minecraftforge.event.LootTableLoadEvent event) {
			LOOT_TABLES.incrementAndGet();
			if (!"forbriclive:probe".equals(event.getName().toString())) return;
			event.setTable(net.minecraft.world.level.storage.loot.LootTable.lootTable().build());
			System.out.println("[ForbricLive/LOOT] LootTableLoadEvent RECEIVED for " + event.getName()
					+ " — replaced its pools with none");
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
			// Read the canary's own loot table back out of the live registry: the replacement above has to be
			// what the game actually holds, not merely something a listener said.
			try {
				var key = net.minecraft.resources.ResourceKey.create(
						net.minecraft.core.registries.Registries.LOOT_TABLE,
						net.minecraft.resources.Identifier.fromNamespaceAndPath("forbriclive", "probe"));
				var table = event.getServer().reloadableRegistries().getLootTable(key);
				int rolled = table.getRandomItems(new net.minecraft.world.level.storage.loot.LootParams.Builder(
						event.getServer().overworld())
						.create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.EMPTY)).size();
				System.out.println("[ForbricLive/LOOT] saw " + LOOT_TABLES.get() + " table(s); forbriclive:probe "
						+ "now rolls " + rolled + " item(s)");
			} catch (Throwable failure) {
				System.out.println("[ForbricLive/LOOT] read-back FAILED: " + failure);
			}
			System.out.println("[ForbricLive/REGISTRATION] common registration observations completed");
			// H1: one call away from any Forge mod — IForgeBlockPos.toCompoundTag() links against CompoundTag.builder().
			try {
				System.out.println("[ForbricLive/NBT] BlockPos.toCompoundTag() = "
						+ new net.minecraft.core.BlockPos(1, 2, 3).toCompoundTag());
			} catch (Throwable failure) {
				System.out.println("[ForbricLive/NBT] BlockPos.toCompoundTag() FAILED: " + failure);
			}
			// D1: did this canary's forge:add_features biome modifier reach the live biome? Read the same table the
			// chunk generator reads, so "the pass ran" and "the world has it" are two different lines.
			try {
				var biomes = event.getServer().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
				var plains = biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value();
				var steps = plains.getGenerationSettings().features();
				int ores = net.minecraft.world.level.levelgen.GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
				var features = steps.size() > ores ? steps.get(ores) : net.minecraft.core.HolderSet.<net.minecraft.world.level.levelgen.placement.PlacedFeature>empty();
				boolean present = false;
				for (var holder : features) {
					if (holder.unwrapKey().map(k -> k.identifier().toString()).orElse("").equals("forbriclive:probe")) present = true;
				}
				System.out.println("[ForbricLive/WORLDGEN] probe ran: plains has " + features.size() + " feature(s) in underground_ores");
				System.out.println("[ForbricLive/WORLDGEN] plains underground_ores has forbriclive:probe = " + present);
			} catch (Throwable failure) {
				System.out.println("[ForbricLive/WORLDGEN] probe FAILED: " + failure);
			}
			// E10: nine capability probes over the composed roots and Forge's own surviving overrides.
			probeCapabilities(event.getServer());
			// D6: did the mod-registered structure modifier reach the mineshaft's live settings?
			try {
				var structures = event.getServer().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
				var mineshaft = structures.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.MINESHAFT).value();
				var override = mineshaft.getModifiedStructureSettings().spawnOverrides().get(net.minecraft.world.entity.MobCategory.CREATURE);
				boolean present = false;
				if (override != null) {
					for (var weighted : override.spawns().unwrap()) {
						if (weighted.value().type() == net.minecraft.world.entity.EntityTypes.MOOSHROOM) present = true;
					}
				}
				System.out.println("[ForbricLive/WORLDGEN] structure probe ran: mineshaft creature override present = " + (override != null));
				System.out.println("[ForbricLive/WORLDGEN] mineshaft creature override has minecraft:mooshroom = " + present);
			} catch (Throwable failure) {
				System.out.println("[ForbricLive/WORLDGEN] structure probe FAILED: " + failure);
			}
			// H5: put a vanilla fluid where the joining player will see it, so the client's FluidRenderer funnel
			// (which asks MinecraftForge's client extensions) is provably on the render path in a save with no water.
			try {
				var level = event.getServer().overworld();
				var spawn = level.getRespawnData().pos();
				// At ServerStarted the chunk beside spawn may not be generated yet (its heightmap answers the world
				// floor), so load it first and then walk up to the first air block that has ground under it.
				var column = spawn.offset(3, 0, 3);
				level.getChunk(column.getX() >> 4, column.getZ() >> 4);
				var pos = column;
				for (int y = spawn.getY() - 4; y <= spawn.getY() + 24; y++) {
					var at = new net.minecraft.core.BlockPos(column.getX(), y, column.getZ());
					if (level.getBlockState(at).isAir() && !level.getBlockState(at.below()).isAir()) {
						pos = at;
						break;
					}
				}
				// Unconditionally, because this save is generated once and reused and the client saves on exit:
				// a block left at this column by an earlier run made the probe read that block forever, and the
				// gate went red on a fixture that had drifted rather than on anything the kernel does.
				level.setBlock(pos, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
				System.out.println("[ForbricLive/FLUID] water at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
						+ ": " + level.getBlockState(pos).getBlock());
			} catch (Throwable failure) {
				System.out.println("[ForbricLive/FLUID] water placement FAILED: " + failure);
			}
			// H4: a recipe whose ingredient is a MinecraftForge type (forge:intersection) must have parsed.
			try {
				boolean present = event.getServer().getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(
						net.minecraft.core.registries.Registries.RECIPE,
						Identifier.fromNamespaceAndPath("forbriclive", "forge_intersection"))).isPresent();
				System.out.println("[ForbricLive/RECIPE] forbriclive:forge_intersection present = " + present);
			} catch (Throwable failure) {
				System.out.println("[ForbricLive/RECIPE] forbriclive:forge_intersection probe FAILED: " + failure);
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

		private static void probeCapabilities(net.minecraft.server.MinecraftServer server) {
			try {
				var level = server.overworld();
				var pos = level.getRespawnData().pos().offset(0, 40, 0);
				var bell = new net.minecraft.world.level.block.entity.BellBlockEntity(pos,
						net.minecraft.world.level.block.Blocks.BELL.defaultBlockState());
				bell.setLevel(level);
				var handler = bell.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER,
						net.minecraft.core.Direction.UP);
				int slots = handler.map(net.minecraftforge.items.IItemHandler::getSlots).orElse(-1);
				System.out.println("[ForbricLive/CAPS] attached handler present=" + handler.isPresent() + " slots=" + slots);

				var chest = new net.minecraft.world.level.block.entity.ChestBlockEntity(pos,
						net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
				chest.setLevel(level);
				int chestSlots = chest.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null)
						.map(net.minecraftforge.items.IItemHandler::getSlots).orElse(-1);
				System.out.println("[ForbricLive/CAPS] vanilla chest handler slots=" + chestSlots);

				var levelCap = level.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY, null);
				System.out.println("[ForbricLive/CAPS] Level getCapability answered: present=" + levelCap.isPresent());
				var chunkCap = level.getChunk(0, 0).getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null);
				System.out.println("[ForbricLive/CAPS] LevelChunk getCapability answered: present=" + chunkCap.isPresent());
				var stackCap = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)
						.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null);
				System.out.println("[ForbricLive/CAPS] ItemStack lookup answered: present=" + stackCap.isPresent());

				var invalidated = new java.util.concurrent.atomic.AtomicBoolean();
				handler.addListener(lazy -> invalidated.set(true));
				bell.setRemoved();
				System.out.println("[ForbricLive/CAPS] LazyOptional invalidated on setRemoved: " + invalidated.get());

				var fresh = new net.minecraft.world.level.block.entity.BellBlockEntity(pos,
						net.minecraft.world.level.block.Blocks.BELL.defaultBlockState());
				fresh.setLevel(level);
				fresh.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null)
						.ifPresent(h -> ((net.minecraftforge.items.ItemStackHandler) h)
								.setStackInSlot(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7)));
				net.minecraft.nbt.CompoundTag saved = fresh.saveWithoutMetadata(level.registryAccess());
				boolean hasKey = saved.contains("ForgeCaps");
				var reloaded = new net.minecraft.world.level.block.entity.BellBlockEntity(pos,
						net.minecraft.world.level.block.Blocks.BELL.defaultBlockState());
				reloaded.setLevel(level);
				try (var problems = new net.minecraft.util.ProblemReporter.ScopedCollector(
						org.slf4j.LoggerFactory.getLogger("forbriclive"))) {
					reloaded.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(problems, level.registryAccess(), saved));
				}
				int count = reloaded.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null)
						.map(h -> h.getStackInSlot(0).getCount()).orElse(-1);
				System.out.println("[ForbricLive/CAPS] ForgeCaps round-trip: key=" + hasKey + " count=" + count);

				System.out.println("[ForbricLive/CAPS] dispatcher present=" + (level.getCapabilityDispatcher() != null));

				// E7: MinecraftForge's LivingEntity.handlers / AbstractFurnaceBlockEntity.handlers lost their constructor
				// initializers to the merge. A living entity must answer ITEM_HANDLER (its equipment wrapper) and
				// survive remove() -> invalidateCaps, which iterates that array; a furnace must answer the sided ask.
				var zombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level,
						net.minecraft.world.entity.EntitySpawnReason.LOAD);
				boolean equipment = zombie != null && zombie.getCapability(
						net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null).isPresent();
				boolean removed = false;
				if (zombie != null) {
					zombie.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
					removed = zombie.isRemoved();
				}
				System.out.println("[ForbricLive/CAPS] living entity equipment handler present=" + equipment
						+ " remove() invalidated without error=" + removed);
				var furnace = new net.minecraft.world.level.block.entity.FurnaceBlockEntity(pos,
						net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState());
				furnace.setLevel(level);
				int furnaceSlots = furnace.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER,
						net.minecraft.core.Direction.UP).map(net.minecraftforge.items.IItemHandler::getSlots).orElse(-1);
				System.out.println("[ForbricLive/CAPS] furnace sided handler slots=" + furnaceSlots);
			} catch (Throwable failure) {
				System.out.println("[ForbricLive/CAPS] probe FAILED: " + failure);
				failure.printStackTrace(System.out);
			}
		}

		private static void checkItem(String ns, String path) {
			boolean present = BuiltInRegistries.ITEM.containsKey(Identifier.fromNamespaceAndPath(ns, path));
			System.out.println("[ForbricLive/VERIFY] ITEM " + ns + ":" + path + " present = " + present);
		}
	}
}
