package fixture.pluginplatform;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/**
 * A multi-loader mod's config plugin: it asks the Mixin service which loader it is on (through a helper, by prefix
 * and equality rather than a switch) and, on Fabric, decorates Knot's weaver. Its onLoad runs when Mixin selects the
 * config, as a real plugin's does.
 */
public final class SplitPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
		Report.platform = Platforms.of(MixinService.getService());
		if (Report.platform.equals("fabric") || Boolean.getBoolean("fixture.pluginplatform.alwaysKnot")) {
			Report.knot = new KnotDecoration().install();
		}
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true;
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
