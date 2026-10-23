package org.example.lanterns;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Stands in for an injector that changes code before it checks and then fails: after the lantern's mixin is applied, and
 * before the late injections run, it leaves a DUP behind the new in kindle()'s first branch, so the two branches no
 * longer meet with the same stack. No injector of MixinExtras 0.5.4 is known to leave such a thing on javac-compiled
 * code (the one with a path that inserts a DUP before its check, @ModifyExpressionValue on a new, leaves nothing on
 * javac's new: the weave test's newvalue run); the stand-in is what lets the test see the isolation refuse to keep a
 * class in that state.
 */
public class StrayDupPlugin implements IMixinConfigPlugin {
	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		for (MethodNode method : targetClass.methods) {
			if (!method.name.equals("kindle")) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn.getOpcode() == Opcodes.NEW) {
					method.instructions.insert(insn, new InsnNode(Opcodes.DUP));
					return;
				}
			}
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
}
