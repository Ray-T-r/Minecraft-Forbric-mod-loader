/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.injection.struct.MemberInfo;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import net.forbric.kernel.util.ForbricLog;

/**
 * A real instruction for a capture {@link MixinGuiItemCaptureAdapter} moved to land on, so that Mixin's 0.9.2 local walk
 * finds the locals there as the 0.10.0 walk does.
 *
 * <p>The adapter moves a TAIL capture to {@code INVOKE <the call that ends the body>, AFTER}. Where that call falls
 * straight into the join the tail sits on, AFTER is the join's label. To read the locals at a label, Mixin first steps
 * forward over pseudo-instructions, and its two walks step differently. Since 0.10.0 ({@code Locals.getLocalsAt}) it
 * stops before a frame. The 0.9.2 walk ({@code getLocalsAt092}) steps up to three nodes, onto the join's frame, and
 * reads it. Fabric Loader gives that walk to a mod whose lowest accepted {@code fabricloader} is older than 0.12.0, or
 * that sets no lower bound at all (no {@code fabricloader} dependency, {@code *}, or only an upper bound). The frame
 * belongs to the join, and the join is also
 * reached by the path that never stored the local, so the frame does not declare it: Mixin reports "LVT ... has
 * incompatible changes" and skips the handler. Nothing crashes, and the capture is gone.
 *
 * <p>A {@code NOP} right after the call is still inside the locals' scope and is not a pseudo-instruction, so the 0.9.2
 * walk does not step: it reads the locals the body left, and the capture applies. Placed only where all of this holds,
 * in {@link IExtension#preApply}, as Mixin is about to apply its mixins to a class:
 * <ul>
 * <li>a mixin Mixin is applying to this class carries a moved capture — whatever class the adapter read, so a target the
 * mixin reaches through {@link MixinMergedTwin} gets its landing too, and a mixin its config plugin refused gets none;
 * <li>that mixin's config is below 0.10.0: from 0.10.0 on, the walk stops before the frame and needs nothing;
 * <li>the call is the one the moved point binds, in a method the handler binds, read off the mixin as Mixin applies it
 * and matched as Mixin matches an INVOKE point, with the ordinal counted over this method;
 * <li>the node after the call is a pseudo-instruction: a call followed by its result's {@code pop} or by a {@code goto}
 * already gives Mixin a real instruction;
 * <li>no mixin applied to this class shifts a point {@code BY} in that method. {@code Shift.BY} counts every node, so a
 * {@code by = 2} on the same call would land one node earlier, before the join's label instead of after it. Such a
 * method keeps its body, and at 0.9.2 the moved capture is skipped as it was before this existed.
 * </ul>
 * The NOP is then the capture's own AFTER point. Any other point in the class lands where it did. Another AFTER on the
 * same call now resolves to the NOP instead of the join's label, which is the same place in the code; a 0.9.2 local walk
 * from there no longer steps onto the join's frame either. No point type selects a NOP by itself, the NOP has no frame
 * of its own, and no BY counts across it. {@code -Dforbric.captureLanding=off} leaves every target as it was.
 */
public final class MixinCaptureLanding implements IExtension {
	public static final String PROPERTY = "forbric.captureLanding";
	private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";

	/** Mixin class (internal name) → the handlers (name + descriptor) whose TAIL capture the adapter moved. */
	private static final Map<String, Set<String>> MOVED = new ConcurrentHashMap<>();

	/** One mixin Mixin is applying to a class: its name, its config's Fabric compatibility level, its class as prepared. */
	record Applied(String name, int level, Supplier<ClassNode> node) { }

	/** Records that {@code handler}'s TAIL capture was moved to {@code INVOKE <call>, AFTER}. */
	static void moved(ClassNode mixin, MethodNode handler) {
		if (mixin == null || handler == null) return;
		MOVED.computeIfAbsent(mixin.name, m -> ConcurrentHashMap.newKeySet()).add(handler.name + handler.desc);
	}

	/** Inserts the NOPs {@code target} needs while {@code applied} are applied to it; returns how many. Package-private for the tests. */
	static int land(ClassNode target, List<Applied> applied) {
		if (target == null || target.methods == null || applied == null || MOVED.isEmpty()
				|| "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return 0;
		int placed = 0;
		for (Applied mixin : applied) {
			Set<String> handlers = MOVED.get(mixin.name());
			if (handlers == null || mixin.level() >= FabricUtil.COMPATIBILITY_0_10_0) continue;
			ClassNode node = mixin.node().get();
			if (node == null || node.methods == null) continue;
			for (MethodNode handler : node.methods) {
				if (!handlers.contains(handler.name + handler.desc)) continue;
				List<AnnotationNode> ats = MixinFit.atNodes(MixinFit.injectorOf(handler));
				if (ats.size() != 1 || !"INVOKE".equals(MixinFit.value(ats.getFirst(), "value"))
						|| !"AFTER".equals(shift(ats.getFirst()))) continue;
				List<MethodNode> bound = MixinTargetSelectors.bound(handler, target);
				if (bound == null) continue;
				for (MethodNode method : bound) {
					List<MethodInsnNode> landings = new ArrayList<>();
					for (MethodInsnNode call : calls(method, ats.getFirst())) {
						AbstractInsnNode next = call.getNext();
						if (next != null && next.getOpcode() < 0) landings.add(call);
					}
					if (landings.isEmpty()) continue;
					String shifting = shiftedBy(applied, target, method);
					if (shifting != null) {
						ForbricLog.info("[Forbric/Mixin] %s.%s%s: no NOP for %s's moved locals capture — %s shifts a point BY "
								+ "in that method, and a NOP would move it; at %d Mixin may skip the capture", target.name,
								method.name, method.desc, mixin.name(), shifting, mixin.level());
						continue;
					}
					for (MethodInsnNode call : landings) {
						method.instructions.insert(call, new InsnNode(Opcodes.NOP));
						placed++;
					}
				}
			}
		}
		return placed;
	}

	/** The calls in {@code method} the INVOKE point {@code at} selects, as Mixin's INVOKE point matches and counts them. */
	private static List<MethodInsnNode> calls(MethodNode method, AnnotationNode at) {
		List<MethodInsnNode> selected = new ArrayList<>();
		if (method.instructions == null || !(MixinFit.value(at, "target") instanceof String spelled)) return selected;
		MemberInfo member;
		try {
			member = MemberInfo.parse(spelled, null);
		} catch (RuntimeException unparsable) {
			return selected;
		}
		int ordinal = MixinFit.value(at, "ordinal") instanceof Integer value ? value : -1;
		int seen = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !member.matches(call.owner, call.name, call.desc).isExactMatch()) continue;
			if (ordinal < 0 || seen == ordinal) selected.add(call);
			seen++;
		}
		return selected;
	}

	/** The first applied handler that shifts a point BY in {@code method} (or may: its selectors cannot be judged); else null. */
	private static String shiftedBy(List<Applied> applied, ClassNode target, MethodNode method) {
		for (Applied mixin : applied) {
			ClassNode node = mixin.node().get();
			if (node == null || node.methods == null) continue;
			for (MethodNode handler : node.methods) {
				if (!shiftsBy(handler.visibleAnnotations) && !shiftsBy(handler.invisibleAnnotations)) continue;
				List<MethodNode> bound = MixinTargetSelectors.bound(handler, target);
				if (bound == null || bound.contains(method)) return mixin.name() + "::" + handler.name;
			}
		}
		return null;
	}

	/** Whether any {@code @At} in these annotations, nested ones included (a slice's bounds), is shifted {@code BY}. */
	private static boolean shiftsBy(List<?> annotations) {
		if (annotations == null) return false;
		for (Object element : annotations) {
			if (element instanceof AnnotationNode annotation) {
				if (AT.equals(annotation.desc) && "BY".equals(shift(annotation))) return true;
				if (annotation.values != null && shiftsBy(annotation.values)) return true;
			} else if (element instanceof List<?> list && shiftsBy(list)) {
				return true;
			}
		}
		return false;
	}

	private static String shift(AnnotationNode at) {
		return MixinFit.value(at, "shift") instanceof String[] shift && shift.length == 2 ? shift[1] : null;
	}

	/** Forgets every moved capture. For the tests, which reuse one JVM. */
	static void clear() {
		MOVED.clear();
	}

	private static volatile Field mixinsField;

	/** The mixins Mixin is applying to the class of {@code context}; empty when they cannot be read. */
	private static List<Applied> applied(ITargetClassContext context) throws ReflectiveOperationException {
		Field field = mixinsField;
		if (field == null) {
			field = context.getClass().getDeclaredField("mixins");
			field.setAccessible(true);
			mixinsField = field;
		}
		List<Applied> applied = new ArrayList<>();
		if (!(field.get(context) instanceof Collection<?> mixins)) return applied;
		for (Object element : mixins) {
			if (!(element instanceof IMixinInfo info)) continue;
			ClassNode[] read = new ClassNode[1];
			applied.add(new Applied(info.getClassRef(), FabricUtil.getCompatibility(info.getConfig()), () -> {
				if (read[0] == null) read[0] = info.getClassNode(0);
				return read[0];
			}));
		}
		return applied;
	}

	@Override
	public boolean checkActive(MixinEnvironment environment) {
		return true;
	}

	@Override
	public void preApply(ITargetClassContext context) {
		if (MOVED.isEmpty()) return;
		ClassNode target = context.getClassNode();
		try {
			int placed = land(target, applied(context));
			if (placed > 0) {
				ForbricLog.info("[Forbric/Mixin] %s: %d moved locals capture(s) given a NOP to land on after their call",
						target.name, placed);
			}
		} catch (ReflectiveOperationException | RuntimeException unexpected) {
			// A capture without its NOP is what the class had before this existed; it must not keep the class from weaving.
			ForbricLog.debug("[Forbric/Mixin] could not give the moved captures in %s their NOP: %s",
					target == null ? "?" : target.name, String.valueOf(unexpected));
		}
	}

	@Override
	public void postApply(ITargetClassContext context) {
	}

	@Override
	public void export(MixinEnvironment environment, String name, boolean force, ClassNode classNode) {
	}
}
