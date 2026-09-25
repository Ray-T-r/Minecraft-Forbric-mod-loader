/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * An {@code @Inject} anchored on a call the merged body now makes through a subtype of the same method.
 *
 * <p>Mixin matches an INVOKE target by its recorded owner, so a mod compiled against vanilla — where
 * {@code RegistryLoadTask.PendingRegistration.loadFromResource} calls {@code Decoder.parse} — finds nothing in NeoForge's
 * body, which decodes through {@code ConditionalOps.createConditionalCodec(…).parse}: {@code Codec.parse}, the very same
 * method ({@code Codec} extends {@code Decoder} and does not redeclare it). lithostitched's Fabric build reads a
 * resource's {@code "predicate"} there and skips entries whose predicate fails; with the anchor missing, entries gated on
 * another mod being installed always loaded (its NeoForge build already anchors on {@code Codec.parse}).
 *
 * <p>The anchor is moved to the subtype's call only where that is the same call: the pair is in {@link #SAME_METHOD}
 * (verified: the subtype does not redeclare it); the injector is an {@code @Inject}, which never sees the receiver; the
 * selected method makes no call with the recorded owner and exactly one with the subtype; and every {@code @Local} the
 * handler takes is named and covers that call. fabric-resource-conditions' own mixin at the same place is left out —
 * the kernel already asks Fabric's conditions at NeoForge's condition funnel, and asking twice would double every skip.
 * {@code -Dforbric.mixinSubtypeOwner=off} leaves every injector as written.
 */
public final class MixinSubtypeOwnerRetarget {
	public static final String PROPERTY = "forbric.mixinSubtypeOwner";
	/** Recorded owner → a subtype through which the merged game calls the same method (name and descriptor). */
	static final Map<String, String> SAME_METHOD = Map.of("com/mojang/serialization/Decoder", "com/mojang/serialization/Codec");
	private static final Set<String> LEFT_OUT = Set.of(
			"net/fabricmc/fabric/mixin/resource/conditions/RegistryLoadTaskPendingRegistrationMixin");
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";

	private MixinSubtypeOwnerRetarget() {
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")) || LEFT_OUT.contains(mixin.name)) return 0;
		List<String> owners = MixinFit.mixinTargets(mixin);
		if (owners.size() != 1) return 0;
		ClassNode target = null;
		int moved = 0;
		List<String> described = new ArrayList<>();
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || !injector.desc.equals(INJECT)) continue;
			List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
			if (selectors.size() != 1) continue;
			for (AnnotationNode at : MixinFit.atNodes(injector)) {
				if (!"INVOKE".equals(MixinFit.value(at, "value"))) continue;
				String recorded = MixinFit.asString(MixinFit.value(at, "target"));
				if (recorded == null || !recorded.startsWith("L") || recorded.indexOf(';') < 0) continue;
				String owner = recorded.substring(1, recorded.indexOf(';'));
				String subtype = SAME_METHOD.get(owner);
				if (subtype == null) continue;
				String member = recorded.substring(recorded.indexOf(';') + 1);
				int paren = member.indexOf('(');
				if (paren < 0) continue;
				String name = member.substring(0, paren), desc = member.substring(paren);
				if (target == null) target = targets.apply(owners.getFirst());
				if (target == null) return moved;
				MethodNode host = select(target, selectors.getFirst());
				if (host == null) continue;
				List<MethodInsnNode> through = new ArrayList<>();
				int direct = 0;
				for (AbstractInsnNode insn : host.instructions) {
					if (!(insn instanceof MethodInsnNode call) || !call.name.equals(name) || !call.desc.equals(desc)) continue;
					if (call.owner.equals(owner)) direct++;
					else if (call.owner.equals(subtype)) through.add(call);
				}
				if (direct != 0 || through.size() != 1 || !localsCover(handler, host, through.getFirst())) continue;
				setTarget(at, "L" + subtype + ";" + member);
				moved++;
				described.add(handler.name + " → " + subtype.substring(subtype.lastIndexOf('/') + 1) + "." + name);
			}
		}
		if (moved > 0) {
			ForbricLog.info("[Forbric/Mixin] %s: %s — the merged body makes the same call through the subtype, which "
					+ "Mixin's owner match did not see", mixin.name.replace('/', '.'), String.join(", ", described));
		}
		return moved;
	}

	/** The one method a name-only (or name+descriptor) selector picks in the target, as Mixin would: the first declared. */
	private static MethodNode select(ClassNode target, String selector) {
		int paren = selector.indexOf('(');
		String name = paren < 0 ? selector : selector.substring(0, paren), desc = paren < 0 ? null : selector.substring(paren);
		for (MethodNode method : target.methods) {
			if (method.name.equals(name) && (desc == null || method.desc.equals(desc))) return method;
		}
		return null;
	}

	/** Every {@code @Local} the handler takes is by name and names a local of that type that is live at the call. */
	static boolean localsCover(MethodNode handler, MethodNode host, MethodInsnNode call) {
		Type[] params = Type.getArgumentTypes(handler.desc);
		List<AnnotationNode>[][] sets = handlerParameterAnnotations(handler);
		int at = host.instructions.indexOf(call);
		for (int i = 0; i < params.length; i++) {
			for (List<AnnotationNode>[] set : sets) {
				if (set == null || i >= set.length || set[i] == null) continue;
				for (AnnotationNode annotation : set[i]) {
					if (!annotation.desc.equals(LOCAL)) continue;
					Object named = MixinFit.value(annotation, "name");
					List<String> names = named == null ? List.of() : MixinFit.stringList(named);
					if (names.size() != 1 || MixinFit.value(annotation, "ordinal") != null) return false;
					if (!covers(host, names.getFirst(), params[i].getDescriptor(), at)) return false;
				}
			}
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[][] handlerParameterAnnotations(MethodNode handler) {
		return new List[][] {handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations};
	}

	private static boolean covers(MethodNode host, String name, String desc, int at) {
		if (host.localVariables == null) return false;
		for (LocalVariableNode local : host.localVariables) {
			if (!local.name.equals(name) || !local.desc.equals(desc)) continue;
			if (index(host, local.start) <= at && at < index(host, local.end)) return true;
		}
		return false;
	}

	private static int index(MethodNode host, LabelNode label) {
		return host.instructions.indexOf(label);
	}

	private static void setTarget(AnnotationNode at, String target) {
		for (int i = 0; i < at.values.size(); i += 2) {
			if ("target".equals(at.values.get(i))) {
				at.values.set(i + 1, target);
				return;
			}
		}
	}
}
