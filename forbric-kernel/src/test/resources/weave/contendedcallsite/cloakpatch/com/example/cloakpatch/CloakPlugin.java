package com.example.cloakpatch;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * A raw post-Mixin patch, the way a mod that does not write a mixin for it makes one: in postApply, find the paint call
 * in Stage.render by owner and name, and make it translucent. Which call is -Dcloakpatch.call, solid by default.
 */
public final class CloakPlugin implements IMixinConfigPlugin {
	public void onLoad(String mixinPackage) {
	}

	public String getRefMapperConfig() {
		return null;
	}

	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true;
	}

	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	public List<String> getMixins() {
		return null;
	}

	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		if (!targetClassName.equals("fixture.stagecall.Stage")) return;
		String wanted = System.getProperty("cloakpatch.call", "solid");
		int patched = 0;
		for (MethodNode method : targetClass.methods) {
			if (!method.name.equals("render")) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals("fixture/stagecall/Paints")
						&& call.name.equals(wanted)) {
					call.name = "translucent";
					patched++;
				}
			}
		}
		System.out.println(patched == 0 ? "[cloakpatch] found no " + wanted + " call to patch" : "[cloakpatch] patched " + wanted);
	}
}
