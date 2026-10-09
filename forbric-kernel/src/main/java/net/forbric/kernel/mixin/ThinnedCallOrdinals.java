/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Recounts a retained invocation only from verified source/current bytes. Receiver and argument origins,
 * continuation operations and branch destinations must identify every surviving occurrence uniquely and in
 * source order. Slices, callback groups, unavailable references and ambiguous origins remain untouched, and so does a
 * handler whose ordinal an earlier pass already counted over the current body ({@link CurrentBodyOrdinals}); a handler
 * this pass re-counts is marked the same way.
 */
public final class ThinnedCallOrdinals {
	public static final String PROPERTY = "forbric.thinnedCallOrdinals";

	/**
	 * @param owner      the class the mixins target (internal name)
	 * @param method     the method there, {@code name + descriptor}
	 * @param call       the call, as an {@code @At} target
	 * @param ordinals   for each of vanilla's occurrences, the merged occurrence that is the same call, or -1 for none
	 * @param next       the call the method makes next after each kept occurrence, on both sides, as an {@code @At} target
	 * @param ecosystems the mods compiled against vanilla's count
	 * @param because    why the kept occurrences are vanilla's
	 */
	public record Site(String owner, String method, String call, int[] ordinals, String next, Set<Ecosystem> ecosystems,
			String because) {
		/** How many occurrences the merged method keeps. */
		int kept() {
			int kept = 0;
			for (int ordinal : ordinals) if (ordinal >= 0) kept++;
			return kept;
		}
	}


	private ThinnedCallOrdinals() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Re-counts every eligible point of {@code mixin}; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
        return adapt(mixin, targets, NativeGameReferences::reference);
    }

    /** The source seam supplies hash-verified native bytes in production and explicit original bytes in tests. */
    static int adapt(ClassNode mixin, Function<String, ClassNode> targets,
            java.util.function.BiFunction<Ecosystem, String, ClassNode> references) {
        if (!enabled() || mixin == null || mixin.methods == null || targets == null || references == null) return 0;
        Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
        List<String> owners = MixinOverloadPin.targetsOf(mixin);
        if (ecosystem == null || owners.size() != 1) return 0;
        ClassNode current = targets.apply(owners.getFirst()), original = references.apply(ecosystem, owners.getFirst());
        if (current == null || original == null) return 0;
        int moved = 0;
        for (MethodNode handler : mixin.methods) {
            // An ordinal an earlier pass already counted over the merged body is not a native count: translating it
            // again would read merged occurrence n as native occurrence n and land on another call.
            if (CurrentBodyOrdinals.counted(handler)) continue;
            AnnotationNode injector = MixinFit.injectorOf(handler);
            if (injector == null || MixinFit.value(injector, "slice") != null || MixinFit.groupOf(handler) != null) continue;
            List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
            if (selectors.size() != 1) continue;
            MethodNode before = MixinStubRebind.bound(original, selectors.getFirst());
            MethodNode after = MixinStubRebind.bound(current, selectors.getFirst());
            if (before == null || after == null) continue;
            for (AnnotationNode at : MixinFit.atNodes(injector)) {
                if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value")))
                        || !(MixinFit.value(at, "ordinal") instanceof Integer ordinal) || ordinal < 0) continue;
                String member = MixinFit.asString(MixinFit.value(at, "target"));
                MixinFit.Member wanted = MixinFit.parseMember(member);
                if (wanted == null || wanted.owner() == null || wanted.desc() == null) continue;
                int live = CallOccurrenceAlignment.retainedOrdinal(original, before, current, after, member, ordinal);
                if (live < 0 || live == ordinal) continue;
                set(at, "ordinal", live); moved++;
                CurrentBodyOrdinals.mark(handler);
                ForbricLog.info("[Forbric/Mixin] %s: %s's ordinal %d → %d is proved from native/current operand "
                        + "origins and the same next operation in %s.%s", mixin.name, handler.name, ordinal, live,
                        current.name, after.name);
            }
        }
        return moved;
    }

	/** Whether {@code method} makes the row's call exactly as often as the row keeps, each followed by the row's next call. */
	static boolean hostHasTheSiteShape(MethodNode method, Site site) {
		int found = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof MethodInsnNode call) || !matches(call, site.call())) continue;
			found++;
			MethodInsnNode next = nextCall(insn);
			if (next == null || !matches(next, site.next())) return false;
		}
		return found == site.kept();
	}

	/** {@code ItemStack.isEmpty} for {@code Lnet/minecraft/world/item/ItemStack;isEmpty()Z}. */
	private static String callName(String member) {
		MixinFit.Member call = MixinFit.parseMember(member);
		return call.owner().substring(call.owner().lastIndexOf('/') + 1) + "." + call.name();
	}

	private static MethodInsnNode nextCall(AbstractInsnNode from) {
		for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call) return call;
		}
		return null;
	}

	private static boolean matches(MethodInsnNode call, String member) {
		MixinFit.Member want = MixinFit.parseMember(member);
		return want != null && call.owner.equals(want.owner()) && call.name.equals(want.name()) && call.desc.equals(want.desc());
	}

	/** Whether an {@code @At} target names the row's call: the same owner, name and descriptor. */
	private static boolean sameMember(String target, String call) {
		if (target == null) return false;
		MixinFit.Member have = MixinFit.parseMember(target), want = MixinFit.parseMember(call);
		return have != null && want != null && want.owner().equals(have.owner()) && want.name().equals(have.name())
				&& want.desc().equals(have.desc());
	}

	private static void set(AnnotationNode at, String key, Object value) {
		if (at.values == null) at.values = new ArrayList<>();
		for (int i = 0; i + 1 < at.values.size(); i += 2) {
			if (key.equals(at.values.get(i))) {
				at.values.set(i + 1, value);
				return;
			}
		}
		at.values.add(key);
		at.values.add(value);
	}
}
