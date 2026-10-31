package forbric.live;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * The refusal fixture's forbriclive: the live canary's {@code @Mod} entry and its {@code META-INF/mods.toml}, byte for
 * byte, and nothing behind them. The canary itself (forbric-loader/run/livemod-src) probes a hundred MinecraftForge
 * types that nothing runs any more — Forbric refuses the jar before any of it loads — and the refusal reads only what
 * is here: the manifest's mod id and display name, and a MinecraftForge-only jar.
 */
@Mod("forbriclive")
public final class ForbricLiveMod {
	public ForbricLiveMod(FMLJavaModLoadingContext ctx) {
	}
}
