package forbric.nestlib;

import net.minecraftforge.fml.common.Mod;

/** The MinecraftForge half's bootstrap — the {@code @Mod} of the nested Forge build of this library. */
@Mod("forbricnestlib")
public final class NestLibForge {
	public NestLibForge() {
		NestLibRegistry.register("forge");
	}
}
