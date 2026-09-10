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
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
			int n = TICKS.incrementAndGet();
			if (n == 20) {
				System.out.println("[ForbricNeoLive] 20 server ticks observed (NeoForge native) - the merged game loop "
						+ "posts NeoForge's ServerTickEvent to NeoForge mods");
			}
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
	}
}
