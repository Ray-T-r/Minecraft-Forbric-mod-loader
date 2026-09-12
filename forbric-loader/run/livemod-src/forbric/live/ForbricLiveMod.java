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

	static {
		net.minecraftforge.common.ForgeConfigSpec.Builder b = new net.minecraftforge.common.ForgeConfigSpec.Builder();
		GREETING = b.comment("set this in <world>/serverconfig before the server boots to test config sync").define("greeting", "default");
		SERVER_SPEC = b.build();
	}

	/** The spec is unloaded until someone loads or syncs it; reading it then throws outside production. */
	private static String greeting() {
		return SERVER_SPEC.isLoaded() ? GREETING.get() : "<unloaded>";
	}

	private static void logConfig(String phase, net.minecraftforge.fml.config.ModConfig cfg) {
		if (cfg.getType() != net.minecraftforge.fml.config.ModConfig.Type.SERVER) return;
		String path;
		try {
			path = String.valueOf(cfg.getFullPath());
		} catch (RuntimeException syncedHasNoFile) {
			path = "<synced, no file>";
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
		System.out.println("[ForbricLive/NET] channel " + NET.getName() + " built (protocol v" + NET.getProtocolVersion() + ")");
		ctx.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER, SERVER_SPEC);
		net.minecraftforge.fml.event.config.ModConfigEvent.Loading.getBus(ctx.getModBusGroup())
				.addListener(e -> logConfig("LOADING", e.getConfig()));
		net.minecraftforge.fml.event.config.ModConfigEvent.Reloading.getBus(ctx.getModBusGroup())
				.addListener(e -> logConfig("RELOADING", e.getConfig()));
		System.out.println("[ForbricLive/CFG] registered SERVER config forbriclive-server.toml (greeting default 'default')");
		reportForeignMods();
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

		@SubscribeEvent
		public static void onServerStarted(ServerStartedEvent event) {
			System.out.println("[ForbricLive] ServerStartedEvent RECEIVED - the patched game's event posts reach mod listeners");
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
