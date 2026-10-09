package com.example.tallypatch;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** A postApply that does work and changes nothing: it counts calls. It names no call it could be looking for. */
public final class TallyPlugin implements IMixinConfigPlugin {
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
		int calls = 0;
		for (MethodNode method : targetClass.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode) calls++;
			}
		}
		System.out.println("[tallypatch] counted " + calls + " call(s) in " + targetClassName);
	}
}
