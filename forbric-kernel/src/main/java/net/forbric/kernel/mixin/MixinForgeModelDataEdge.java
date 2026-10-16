/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.transform.ForgeModelDataFlowTransformer;
import net.forbric.kernel.util.ForbricLog;

/**
 * Gives an injector at the start of a carrier's model-data overload the overload's edge twin as a second target, so it
 * runs wherever the merged game reaches that entry.
 *
 * <p>MinecraftForge's {@code ModelBlockRenderer.tesselateBlock(…, seed, ModelData)} is where its section compiler
 * tessellates every block, and where its mods hook a block's rendering: Physics Mod hides the vines it simulates there,
 * fusion takes over a block whose model carries per-model random offsets. The merged section compiler is NeoForge's
 * and calls only the vanilla signature, which carries NeoForge's body; the overload was called by nothing, and every
 * such injector attached and never ran. {@link ForgeModelDataFlowTransformer} makes the vanilla signature enter the
 * overload's edge twin first — the overload's exact signature, with the model data MinecraftForge's caller would have
 * computed, and a body that only marks it was reached — and return at once when a guest left the twin early.
 *
 * <p>Kept exactly: the target's pre-mixin bytes declare the twin under the overload's name with
 * {@link ForgeModelDataFlowTransformer#EDGE_SUFFIX}, with the overload's descriptor and nothing but
 * {@code this.<flag> = true; return}, and the vanilla signature calls it; the injector is an {@code @Inject} whose
 * selectors bind the overload alone, every point a {@code HEAD}, with no slice, no captured locals, no
 * {@code allow}/{@code expect} count and no {@code @Group} (a second target would count twice against them), and a
 * handler taking the overload's arguments, a prefix of them or none, then its callback, with no parameter annotation.
 * The overload keeps the injector: a mod that calls the overload itself — fusion does, per offset model — still meets
 * it there, as natively. {@code -Dforbric.forgeModelDataEdge=off} leaves every selector as compiled.
 */
public final class MixinForgeModelDataEdge {
	public static final String PROPERTY = "forbric.forgeModelDataEdge";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

	private MixinForgeModelDataEdge() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")) && ForgeModelDataFlowTransformer.enabled();
	}

	/** Adds the edge twin to every eligible injector of {@code mixin}; returns how many. {@code targets} return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, true);
	}

	private static int adapt(ClassNode mixin, Function<String, ClassNode> targets, boolean log) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		int added = 0;
		for (String targetName : MixinOverloadPin.targetsOf(mixin)) {
			ClassNode target = targets.apply(targetName);
			if (target == null || target.methods == null) continue;
			for (MethodNode twin : target.methods) {
				MethodNode overload = overloadOf(target, twin);
				if (overload == null) continue;
				for (MethodNode handler : mixin.methods) {
					if (!eligible(handler, target, overload)) continue;
					AnnotationNode injector = MixinFit.injectorOf(handler);
					for (int i = 0; i + 1 < injector.values.size(); i += 2) {
						if (!"method".equals(injector.values.get(i))) continue;
						List<Object> selectors = new ArrayList<>((List<?>) injector.values.get(i + 1));
						selectors.add(twin.name + twin.desc);
						injector.values.set(i + 1, selectors);
					}
					added++;
					if (log) ForbricLog.info("[Forbric/Mixin] %s: %s also starts %s.%s — the merged game calls only %s's vanilla signature, "
							+ "which now enters MinecraftForge's model-data entry there first; the overload keeps it too",
							mixin.name.replace('/', '.'), handler.name, targetName.substring(targetName.lastIndexOf('/') + 1),
							twin.name, overload.name);
				}
			}
		}
		return added;
	}

	/**
	 * The overload {@code twin} is the edge twin of, when the class has the whole shape: the twin named after it, with its
	 * descriptor and only {@code this.<flag> = true; return}, and a same-name vanilla signature that calls the twin.
	 */
	static MethodNode overloadOf(ClassNode target, MethodNode twin) {
		if (!twin.name.endsWith(ForgeModelDataFlowTransformer.EDGE_SUFFIX)) return null;
		String name = twin.name.substring(0, twin.name.length() - ForgeModelDataFlowTransformer.EDGE_SUFFIX.length());
		MethodNode overload = null;
		for (MethodNode m : target.methods) if (m.name.equals(name) && m.desc.equals(twin.desc)) overload = m;
		if (overload == null) return null;
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode insn : twin.instructions) if (insn.getOpcode() >= 0) real.add(insn);
		if (real.size() != 4 || !(real.get(0) instanceof VarInsnNode self && self.getOpcode() == Opcodes.ALOAD && self.var == 0)
				|| real.get(1).getOpcode() != Opcodes.ICONST_1
				|| !(real.get(2) instanceof FieldInsnNode flag && flag.getOpcode() == Opcodes.PUTFIELD && flag.owner.equals(target.name)
						&& flag.desc.equals("Z"))
				|| real.get(3).getOpcode() != Opcodes.RETURN) return null;
		for (MethodNode m : target.methods) {
			if (!m.name.equals(name) || m == overload) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(target.name) && call.name.equals(twin.name)
						&& call.desc.equals(twin.desc)) return overload;
			}
		}
		return null;
	}

	/** An {@code @Inject} at the start of {@code overload} alone, which a second target cannot change the meaning of. */
	static boolean eligible(MethodNode handler, ClassNode target, MethodNode overload) {
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null || !INJECT.equals(injector.desc)) return false;
		List<AnnotationNode> annotations = new ArrayList<>();
		if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
		if (annotations.stream().anyMatch(a -> GROUP.equals(a.desc))) return false;
		for (String key : List.of("slice", "target", "allow", "expect", "locals")) {
			if (MixinFit.value(injector, key) != null) return false;
		}
		if (!MixinCallbackShape.binds(handler, target, overload.name + overload.desc)) return false;
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.isEmpty()) return false;
		for (AnnotationNode at : points) {
			if (!"HEAD".equals(MixinFit.asString(MixinFit.value(at, "value")))) return false;
		}
		if (annotated(handler.visibleParameterAnnotations) || annotated(handler.invisibleParameterAnnotations)) return false;
		Type[] params = Type.getArgumentTypes(handler.desc);
		Type[] wanted = Type.getArgumentTypes(overload.desc);
		if (!Type.getReturnType(handler.desc).equals(Type.VOID_TYPE) || params.length == 0
				|| !params[params.length - 1].getDescriptor().equals(CALLBACK) || params.length - 1 > wanted.length) return false;
		for (int i = 0; i < params.length - 1; i++) if (!params[i].equals(wanted[i])) return false;
		return true;
	}

	private static boolean mentions(byte[] bytes, String text) {
		byte[] needle = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		outer:
		for (int i = 0; i + needle.length <= bytes.length; i++) {
			for (int j = 0; j < needle.length; j++) if (bytes[i + j] != needle[j]) continue outer;
			return true;
		}
		return false;
	}

	private static boolean annotated(List<AnnotationNode>[] annotations) {
		if (annotations == null) return false;
		for (List<AnnotationNode> list : annotations) if (list != null && !list.isEmpty()) return true;
		return false;
	}

	/** The adapter as Mixin will receive the class, for the config-time judgement: a guest mixin's bytes, adapted or as given. */
	public static byte[] asLoaded(byte[] bytes, Function<String, byte[]> resource) {
		if (!enabled()) return bytes;
		try {
			// Every guest mixin passes through here: its @Mixin alone first, the code only when a target has an edge twin.
			ClassNode head = new ClassNode();
			new org.objectweb.asm.ClassReader(bytes).accept(head, org.objectweb.asm.ClassReader.SKIP_CODE);
			boolean edged = false;
			for (String target : MixinOverloadPin.targetsOf(head)) {
				byte[] found = resource.apply(target + ".class");
				if (found != null && mentions(found, ForgeModelDataFlowTransformer.EDGE_SUFFIX)) edged = true;
			}
			if (!edged) return bytes;
			ClassNode mixin = new ClassNode();
			new org.objectweb.asm.ClassReader(bytes).accept(mixin, 0);
			Function<String, ClassNode> targets = name -> {
				byte[] found = resource.apply(name + ".class");
				if (found == null) return null;
				ClassNode node = new ClassNode();
				new org.objectweb.asm.ClassReader(found).accept(node, 0);
				return node;
			};
			if (adapt(mixin, targets, false) == 0) return bytes;
			org.objectweb.asm.ClassWriter out = new org.objectweb.asm.ClassWriter(0);
			mixin.accept(out);
			return out.toByteArray();
		} catch (RuntimeException unreadable) {
			return bytes;
		}
	}
}
