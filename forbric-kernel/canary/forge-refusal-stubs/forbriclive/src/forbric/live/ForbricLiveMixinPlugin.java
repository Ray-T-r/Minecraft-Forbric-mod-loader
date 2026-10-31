package forbric.live;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The refusal fixture's copy of the live canary's mixin config plugin, kept for the one thing the refusal gates read
 * from it: Mixin constructs every declared plugin while it prepares configs, on the first class through the
 * transformer, so a {@code [ForbricLive/PLUGIN]} line means the refused jar's {@code forbriclive.mixins.json} was handed
 * to Mixin. gate-m26 and gate-m29 assert that line is absent; without this class nothing in the jar could print it.
 *
 * <p>The live canary's plugin also reads {@code FMLEnvironment.dist} here. This one does not: that read is a
 * MinecraftForge class, resolved before the print, so on a stage without MinecraftForge it would throw instead of
 * printing and the sensor would go quiet exactly when it mattered.
 */
public class ForbricLiveMixinPlugin implements IMixinConfigPlugin {

	static {
		System.out.println("[ForbricLive/PLUGIN] prepareConfigs constructed the refused canary's mixin config plugin");
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
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
	}
}
