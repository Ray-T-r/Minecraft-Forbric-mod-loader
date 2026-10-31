package forbric.live;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * The refusal fixture's forbriclive: the live canary's {@code @Mod} entry, its {@code META-INF/mods.toml} and its
 * {@code forbriclive.mixins.json}, byte for byte, and nothing behind them but {@link ForbricLiveMixinPlugin}. The canary
 * itself (forbric-loader/run/livemod-src) probes a hundred MinecraftForge types that nothing runs any more — Forbric
 * refuses the jar before any of it loads — and the refusal reads only what is here: the manifest's mod id and display
 * name, and a MinecraftForge-only jar. The constructor prints the live canary's first line, so a refused jar that is
 * constructed anyway still says so in the canary's own words.
 */
@Mod("forbriclive")
public final class ForbricLiveMod {
	public ForbricLiveMod(FMLJavaModLoadingContext ctx) {
		System.out.println("[ForbricLive] constructed by the real ModLoader");
	}
}
