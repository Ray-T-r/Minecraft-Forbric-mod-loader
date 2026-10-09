package fixture.libraryplugin.mod;

import fixture.libraryplugin.lib.LoaderSensingPlugin;

/** The mod's config plugin: all of its platform sense is the library base's. */
public final class WidgetPlugin extends LoaderSensingPlugin {
	@Override
	public void onLoad(String mixinPackage) {
		Seen.platform = platform;
	}
}
