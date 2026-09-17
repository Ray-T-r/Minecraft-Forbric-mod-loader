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
 */
@Mod("forbricneolive")
public class ForbricNeoLiveMod {
	private static final AtomicInteger TICKS = new AtomicInteger();

	public ForbricNeoLiveMod(IEventBus modBus) {
		System.out.println("[ForbricNeoLive] @Mod(\"forbricneolive\") constructed by the real NeoForge ModLoader");
		reportForeignMods();
		registerSetupLifecycle(modBus);
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
			int n = TICKS.incrementAndGet();
			if (n == 20) {
				System.out.println("[ForbricNeoLive] 20 server ticks observed (NeoForge native) - the merged game loop "
						+ "posts NeoForge's ServerTickEvent to NeoForge mods");
			}
		});
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
