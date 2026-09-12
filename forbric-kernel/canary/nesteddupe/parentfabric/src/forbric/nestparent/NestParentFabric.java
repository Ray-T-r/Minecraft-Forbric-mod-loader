package forbric.nestparent;

import net.fabricmc.api.ModInitializer;

/** The Fabric consumer. Proves the library it nests is usable from this side after arbitration. */
public final class NestParentFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		System.out.println("[ForbricNestParent] fabric parent up");
	}
}
