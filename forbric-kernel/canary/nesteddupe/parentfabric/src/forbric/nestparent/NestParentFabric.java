package forbric.nestparent;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** The Fabric consumer, asking the same question from its own side. */
public final class NestParentFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		System.out.println("[ForbricNestParent] fabric parent up");
		boolean visible;
		try {
			visible = FabricLoader.getInstance().isModLoaded("forbricnestlib");
		} catch (Throwable t) {
			visible = false;
			System.out.println("[ForbricNestParent] fabric isModLoaded threw: " + t);
		}
		System.out.println("[ForbricNestParent] fabric sees forbricnestlib=" + visible);
	}
}
