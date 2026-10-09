package fixture.libraryplugin.lib;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/**
 * A config plugin base a library ships for the mods that bundle it, the library itself declaring no loader: it learns
 * the platform once, in its constructor, from the Mixin service's name, and applies its mod's mixins only on a
 * platform it knows.
 */
public abstract class LoaderSensingPlugin implements IMixinConfigPlugin {
	protected final String platform;

	protected LoaderSensingPlugin() {
		String service = MixinService.getService().getName();
		switch (service) {
			case "Knot/Fabric" -> platform = "fabric";
			case "ModLauncher" -> platform = "forge";
			case "FML" -> platform = "neoforge";
			default -> platform = "unknown:" + service;
		}
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return !platform.startsWith("unknown:");
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
