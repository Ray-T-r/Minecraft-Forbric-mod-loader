/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.util.ForbricLog;

/**
 * Puts The camera roll callback back on the calls the merged {@code Camera.alignWithEntity} makes.
 *
 * <p>Vanilla aligns the camera with four {@code setRotation(FF)} calls: minecart, ordinary view, mirrored view, bed.
 * the source {@code CameraMixin} wraps ordinals 1, 2 and 3 of that call and stores the roll each case should add, then
 * adds it inside {@code setRotation} with a {@code @ModifyArg} on {@code Quaternionf.rotationYXZ}. NeoForge's
 * {@code alignWithEntity} posts {@code ViewportEvent.ComputeCameraAngles} and makes the ordinary and mirrored calls
 * through its own {@code setRotation(FFF)} with the event's roll, so on the merged base:
 * <ul>
 *   <li>ordinal 1 of {@code setRotation(FF)} is the bed call and ordinals 2 and 3 do not exist: the ordinary-view
 *       hook bound the bed call (it ran only while sleeping), the mirrored and bed hooks bound nothing, and no roll
 *       was ever stored while flying;</li>
 *   <li>the name-only {@code setRotation} selector of the {@code @ModifyArg} binds the first declared overload, the
 *       two-argument one that only delegates, so even a stored roll was never added.</li>
 * </ul>
 * The player rolls; the camera does not.
 *
 * <p>This moves the ordinary and mirrored wraps to ordinals 0 and 1 of {@code setRotation(FFF)} (each handler gains the
 * carrier's roll as an extra, unused parameter, so its {@code @Share} slot moves by one), the bed wrap to ordinal 1 of
 * {@code setRotation(FF)}, and pins the {@code @ModifyArg} to {@code setRotation(FFF)V}. The event roll still reaches
 * {@code rotationYXZ} and the source handler adds its own on top, as it does over vanilla's zero.
 *
 * <p>Only the exact shape is adapted: a merged {@code alignWithEntity} that posts the event and calls
 * {@code (FF), (FFF), (FFF), (FF)} in that order, and the source four handlers as written. Anything else is left alone.
 * {@code -Dforbric.cameraRollCallbacks=off} turns it off.
 */
public final class MixinCameraRollAdapter {
	public static final String PROPERTY = "forbric.cameraRollCallbacks";
	static final String CAMERA = "net/minecraft/client/Camera";
	static final String SHORT = "L" + CAMERA + ";setRotation(FF)V";
	static final String LONG = "L" + CAMERA + ";setRotation(FFF)V";
	private static final String EVENT = "net/neoforged/neoforge/client/event/ViewportEvent$ComputeCameraAngles";
	private static final String SHARE = "Lcom/llamalad7/mixinextras/sugar/ref/LocalFloatRef;";

	private MixinCameraRollAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** @return the number of injectors moved: 4, or 0 when anything differs from the shape this was written for */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || !MixinCallbackShape.targets(mixin, CAMERA)) return 0;
		ClassNode camera = targets.apply(CAMERA);
		if (camera == null) return 0;
		MethodNode align = method(camera, "alignWithEntity", "(F)V");
		if (align == null) return 0;
		// The carrier shape: minecart, ordinary (event roll), mirrored (event roll), bed.
		List<String> rotations = new ArrayList<>();
		boolean event = false;
		for (AbstractInsnNode insn : align.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (EVENT.equals(call.owner) && "getRoll".equals(call.name)) event = true;
			if (CAMERA.equals(call.owner) && "setRotation".equals(call.name)) rotations.add(call.desc);
		}
		if (!event || !rotations.equals(List.of("(FF)V", "(FFF)V", "(FFF)V", "(FF)V"))) return 0;

		MethodNode ordinary = MixinCallbackShape.unique(mixin, m -> m.desc.equals("(L" + CAMERA + ";FF" + SHARE + ")Z") && wraps(m, 1));
		MethodNode mirrored = MixinCallbackShape.unique(mixin, m -> m.desc.equals("(L" + CAMERA + ";FF)Z") && wraps(m, 2));
		MethodNode bed = MixinCallbackShape.unique(mixin, m -> m.desc.equals("(L" + CAMERA + ";FF)Z") && wraps(m, 3));
		MethodNode roll = MixinCallbackShape.unique(mixin, m -> m.desc.equals("(F)F") && MixinCallbackShape.kind(m, "ModifyArg") && MixinCallbackShape.selects(m, "setRotation")
                && MixinCallbackShape.plainPoint(m, "INVOKE", "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;"));
		if (ordinary == null || mirrored == null || bed == null || roll == null
				|| !wraps(ordinary, 1) || !wraps(mirrored, 2) || !wraps(bed, 3)) return 0;
		AnnotationNode modifier = MixinFit.injectorOf(roll);
		if (modifier == null || !modifier.desc.endsWith("/ModifyArg;")
				|| !MixinFit.stringList(MixinFit.value(modifier, "method")).equals(List.of("setRotation"))) return 0;

		widen(ordinary);
		widen(mirrored);
		retarget(ordinary, LONG, 0);
		retarget(mirrored, LONG, 1);
		retarget(bed, SHORT, 1);
		set(modifier, "method", new ArrayList<>(List.of("setRotation(FFF)V")));
		ForbricLog.info("[Forbric/Mixin] The camera roll callback now wraps the merged alignWithEntity's "
				+ "setRotation(FFF) calls and adds its roll in setRotation(FFF) — its vanilla ordinals bound the bed call "
				+ "or nothing, so the camera never rolled");
		return 4;
	}

	private static boolean wraps(MethodNode method, int ordinal) {
		AnnotationNode wrap = MixinFit.injectorOf(method);
		if (wrap == null || !MixinCallbackShape.instance(method) || !MixinCallbackShape.kind(method, "WrapWithCondition") || !MixinCallbackShape.point(method, "INVOKE", SHORT)
				|| !MixinFit.stringList(MixinFit.value(wrap, "method")).equals(List.of("alignWithEntity"))) return false;
		List<AnnotationNode> at = MixinFit.atNodes(wrap);
		return at.size() == 1 && SHORT.equals(MixinFit.value(at.getFirst(), "target"))
				&& Integer.valueOf(ordinal).equals(MixinFit.value(at.getFirst(), "ordinal"));
	}

	private static void retarget(MethodNode method, String target, int ordinal) {
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(method)).getFirst();
		set(at, "target", target);
		set(at, "ordinal", ordinal);
	}

	private static void set(AnnotationNode annotation, String key, Object value) {
		if (annotation.values == null) annotation.values = new ArrayList<>();
		for (int i = 0; i < annotation.values.size(); i += 2) {
			if (key.equals(annotation.values.get(i))) {
				annotation.values.set(i + 1, value);
				return;
			}
		}
		annotation.values.add(key);
		annotation.values.add(value);
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
	}

	/**
	 * Adds the carrier's roll as the handler's fourth parameter ({@code Camera, yaw, pitch, roll}), which is local
	 * slot 4 of these instance handlers: every local, frame entry and parameter annotation from slot 4 on moves by one.
	 */
	private static void widen(MethodNode method) {
		List<Type> args = new ArrayList<>(Arrays.asList(Type.getArgumentTypes(method.desc)));
		args.add(3, Type.FLOAT_TYPE);
		method.desc = Type.getMethodDescriptor(Type.getReturnType(method.desc), args.toArray(Type[]::new));
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof VarInsnNode var && var.var >= 4) var.var++;
			if (insn instanceof IincInsnNode inc && inc.var >= 4) inc.var++;
			if (insn instanceof FrameNode frame && (frame.type == Opcodes.F_NEW || frame.type == Opcodes.F_FULL)
					&& frame.local != null && frame.local.size() >= 4) frame.local.add(4, Opcodes.FLOAT);
		}
		if (method.localVariables != null) for (LocalVariableNode local : method.localVariables) if (local.index >= 4) local.index++;
		method.visibleParameterAnnotations = widened(method.visibleParameterAnnotations);
		method.invisibleParameterAnnotations = widened(method.invisibleParameterAnnotations);
		if (method.visibleAnnotableParameterCount > 0) method.visibleAnnotableParameterCount++;
		if (method.invisibleAnnotableParameterCount > 0) method.invisibleAnnotableParameterCount++;
		if (method.parameters != null) method.parameters.add(3, new ParameterNode("forbric$carrierRoll", 0));
		method.maxLocals++;
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] widened(List<AnnotationNode>[] annotations) {
		if (annotations == null) return null;
		List<AnnotationNode>[] expanded = new List[annotations.length + 1];
		System.arraycopy(annotations, 0, expanded, 0, Math.min(3, annotations.length));
		if (annotations.length > 3) System.arraycopy(annotations, 3, expanded, 4, annotations.length - 3);
		return expanded;
	}
}
