package forbric.nestlib;

import net.fabricmc.api.ModInitializer;

/** The Fabric half's bootstrap — the entrypoint of the nested Fabric build of this library. */
public final class NestLibFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		NestLibRegistry.register("fabric");
	}
}
