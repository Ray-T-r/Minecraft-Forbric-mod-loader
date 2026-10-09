package fixture.pluginplatform;

import org.spongepowered.asm.service.IMixinService;

/** The platform check, kept out of the plugin as many mods keep it: a prefix test, then two equalities. */
final class Platforms {
	private Platforms() {
	}

	static String of(IMixinService service) {
		String name = service.getName();
		if (name.startsWith("Knot")) return "fabric";
		else if (name.equals("ModLauncher")) return "forge";
		else if (name.equals("FML")) return "neoforge";
		return "unknown:" + name;
	}
}
