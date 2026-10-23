package org.example.lanterns;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * What a mod that bundles its own, newer MixinExtras under its own package does: start it from its config plugin's
 * onLoad, after the kernel's MixinExtras is up and before the first class is woven. Being newer, it takes over.
 */
public class NewerMixinExtrasPlugin implements IMixinConfigPlugin {
	/** Where the lantern mod's bundled MixinExtras lives; the test relocates the real one there. */
	public static final String BUNDLED = "org.example.lanterns.shadow.mixinextras";

	@Override
	public void onLoad(String mixinPackage) {
		try {
			Class.forName(BUNDLED + ".MixinExtrasBootstrap", true, getClass().getClassLoader()).getMethod("init").invoke(null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("the bundled MixinExtras did not start", e);
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
